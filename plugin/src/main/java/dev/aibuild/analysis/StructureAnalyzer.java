package dev.aibuild.analysis;

import dev.aibuild.model.BlockInfo;
import dev.aibuild.model.BlockSpec;
import dev.aibuild.model.Dir;
import dev.aibuild.model.VoxelModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Objective, deterministic analysis of a {@link VoxelModel}.
 *
 * <p>The analyzer is the "eyes" of the verify loop: it reports defects the reviewing LLM must fix and the
 * metrics that describe the build (palette, rooms, lighting, facades, detail level). Output is intended to be
 * read by an LLM, so issue codes, messages and samples are compact and unambiguous.</p>
 *
 * <h2>Model semantics used</h2>
 * <ul>
 *   <li>+x = east, +z = south, +y = up; the front of the build faces SOUTH.</li>
 *   <li>A cell is <b>present</b> when it holds a non-air {@link BlockSpec}.</li>
 *   <li>Unset cells are <b>terrain</b> (a solid barrier and a valid support) when {@code y <= 0}, and <b>air</b>
 *   when {@code y >= 1}. Explicit air cells override terrain.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * All methods are static and stateless: any number of threads may call {@link #analyze} concurrently, provided
 * the {@link VoxelModel} they pass is not mutated while the call runs ({@code VoxelModel} itself is not
 * thread-safe). {@link BlockInfo} implementations are required to be thread-safe by the interface contract.
 * The returned {@link AnalysisReport} is immutable.
 *
 * <h2>Cost</h2>
 * One snapshot of the non-air bounding box expanded by 2 cells, then a handful of linear passes and two
 * flood-fills: {@code O(volume + cells)}, no {@code HashMap} lookups in inner loops. 400k cells analyse in
 * well under a second on a normal server thread. Regions are capped ({@link Grid#MAX_CELLS}); an oversized
 * model yields a CRITICAL {@code too_large} report instead of an OOM.
 */
public final class StructureAnalyzer {

    private StructureAnalyzer() {
    }

    /** Padding around the non-air bounding box; 2 keeps the "outside" flood fill and facade rays valid. */
    private static final int PAD = 2;

    /**
     * How far a measured body size may sit from the asked size: a proportion of the ask, never less than one
     * block. A real fachwerk house measured 11 wide against an asked 10 because a porch column survives the
     * trim; an exact-equality demand for small builds turned that into a false defect.
     */
    static int sizeToleranceFor(int expected) {
        return Math.max(1, Math.round(expected * 0.08f));
    }

    /** Cells kept per kind in {@link AnalysisReport#cells}; a longer list is cut off at this many. */
    public static final int MAX_MARKED_CELLS = 5000;

    /** Maximum number of floating components reported individually. */
    private static final int MAX_FLOATING_REPORTS = 4;
    /** Volume threshold for a connected interior pocket to count as a room. */
    private static final int ROOM_MIN_VOLUME = 4;
    /** Thresholds for facade complaints. */
    private static final double MONOTONY_SHARE = 0.55;
    private static final int FACADE_MIN_CELLS = 20;

    /**
     * Analyses {@code model}.
     *
     * @param model the model to inspect, not mutated by this method
     * @param info  block knowledge (validated/canonical specs); must be thread-safe
     * @param opts  optional analysis options; {@code null} means {@link AnalyzerOptions#AnalyzerOptions defaults}
     * @return an immutable report, never null
     * @throws IllegalArgumentException if {@code model} or {@code info} is null
     */
    public static AnalysisReport analyze(VoxelModel model, BlockInfo info, AnalyzerOptions opts) {
        if (model == null) throw new IllegalArgumentException("model is null");
        if (info == null) throw new IllegalArgumentException("info is null");
        AnalyzerOptions o = opts == null ? new AnalyzerOptions() : opts;

        if (model.isEmpty()) return AnalysisReport.empty();

        int[] nonAirBounds = model.solidBounds();
        if (nonAirBounds == null) return AnalysisReport.empty();

        Grid grid;
        try {
            grid = Grid.ofNonAir(model, PAD);
        } catch (RuntimeException e) {
            grid = null;
        }
        if (grid == null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("interiorVolume", 0);
            m.put("rooms", 0);
            m.put("detailScore", 0);
            m.put("tooLarge", true);
            List<Issue> issues = new ArrayList<>();
            issues.add(Issue.of(Severity.CRITICAL, "too_large",
                    "model bounding box is too large to analyse (" + (nonAirBounds[3] - nonAirBounds[0] + 1)
                            + "x" + (nonAirBounds[4] - nonAirBounds[1] + 1) + "x"
                            + (nonAirBounds[5] - nonAirBounds[2] + 1) + "); split the build into smaller parts"));
            return new AnalysisReport(nonAirBounds, nonAirBounds[3] - nonAirBounds[0] + 1,
                    nonAirBounds[4] - nonAirBounds[1] + 1, nonAirBounds[5] - nonAirBounds[2] + 1, 0, 0, 0,
                    List.of(), 0, 0, 0, 0, 0, 0, m, issues);
        }
        return new Ctx(model, info, o, grid, nonAirBounds).run();
    }

    // =====================================================================================================
    // Analysis context: one instance per analyze() call, confined to the calling thread.
    // =====================================================================================================
    private static final class Ctx {
        final VoxelModel model;
        final BlockInfo info;
        final AnalyzerOptions opts;
        final Grid g;
        final int[] nonAirBounds;
        final int minX, minY, minZ, maxX, maxY, maxZ;
        final int sx, sy, sz, n;

        // per-cell flags (index = grid index)
        final byte[] present;   // non-air block present
        final byte[] open;      // air or unset y>=1 (terrain is a barrier)
        final byte[] occ;       // occluding (blocks light)
        final byte[] solid;     // isSolid
        final byte[] emit;      // block light emission 0..15
        final byte[] liquid;    // water/lava/bubble column
        final byte[] gravity;   // has gravity (sand, gravel, concrete powder, ...)

        // running results
        int nonAir;
        final Map<String, Integer> nameCounts = new HashMap<>();
        final Map<BlockSpec, Integer> stateCounts = new HashMap<>();
        /** Cached physical properties per block state: one lookup per state instead of three per cell. */
        final Map<BlockSpec, StateProps> stateProps = new HashMap<>();
        final List<Issue> issues = new ArrayList<>();
        final LinkedHashMap<String, Object> metrics = new LinkedHashMap<>();

        int interiorVolume;
        int rooms;
        int biggestRoom;
        int darkSpots;
        int doors;
        int windows;
        int lightSources;

        byte[] outside;      // cell reached by the outside flood fill
        byte[] interiorMask; // open && !outside
        int[] light;         // block light
        int[] highestY;      // per (x,z) column: highest present y, lazily filled

        Ctx(VoxelModel model, BlockInfo info, AnalyzerOptions opts, Grid g, int[] nonAirBounds) {
            this.model = model;
            this.info = info;
            this.opts = opts;
            this.g = g;
            this.nonAirBounds = nonAirBounds;
            this.minX = g.minX;
            this.minY = g.minY;
            this.minZ = g.minZ;
            this.maxX = g.maxX;
            this.maxY = g.maxY;
            this.maxZ = g.maxZ;
            this.sx = g.sx;
            this.sy = g.sy;
            this.sz = g.sz;
            this.n = (int) g.volume;
            this.present = new byte[n];
            this.open = new byte[n];
            this.occ = new byte[n];
            this.solid = new byte[n];
            this.emit = new byte[n];
            this.liquid = new byte[n];
            this.gravity = new byte[n];
        }

        // ---------------------------------------------------------------------------------------------- run

        AnalysisReport run() {
            scanCells();
            checkFloating();
            checkGravity();
            checkAttachments();
            checkEnclosure();
            checkLighting();
            checkRooms();
            checkDoorsAndBeds();
            checkVerticalAccess();
            checkLiquids();
            checkFacades();
            checkRoofOverhang();
            finishDetailMetrics();
            checkFootprint();
            checkOverallSize();
            finishMetrics();
            return buildReport();
        }

        // ---------------------------------------------------------------------------------------------- build

        private void scanCells() {
            int i = 0;
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    for (int x = minX; x <= maxX; x++, i++) {
                        BlockSpec s = g.at(i);
                        if (s == null) {
                            if (y >= 1) open[i] = 1; // terrain (y <= 0) stays a barrier
                            continue;
                        }
                        if (s.isAir()) {
                            open[i] = 1;
                            continue;
                        }
                        present[i] = 1;
                        nonAir++;
                        StateProps sp = stateProps.get(s);
                        if (sp == null) {
                            sp = new StateProps(isSolid(s), isOccl(s), emission(s), isLiquid(s),
                                    isGravity(s));
                            stateProps.put(s, sp);
                        }
                        solid[i] = (byte) (sp.solid ? 1 : 0);
                        occ[i] = (byte) (sp.occluding ? 1 : 0);
                        emit[i] = (byte) sp.emission;
                        if (sp.emission > 0) lightSources++;
                        if (isWindow(s)) windows++;
                        liquid[i] = (byte) (sp.liquid ? 1 : 0);
                        gravity[i] = (byte) (sp.gravity ? 1 : 0);
                        nameCounts.merge(s.name(), 1, Integer::sum);
                        stateCounts.merge(s, 1, Integer::sum);
                    }
                }
            }
        }

        private AnalysisReport buildReport() {
            List<Map.Entry<String, Integer>> top = new ArrayList<>(nameCounts.entrySet());
            top.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed()
                    .thenComparing(Map.Entry::getKey));
            if (top.size() > AnalysisReport.TOP_BLOCK_LIMIT) {
                top = new ArrayList<>(top.subList(0, AnalysisReport.TOP_BLOCK_LIMIT));
            }
            AnalysisReport report = new AnalysisReport(nonAirBounds,
                    nonAirBounds[3] - nonAirBounds[0] + 1,
                    nonAirBounds[4] - nonAirBounds[1] + 1,
                    nonAirBounds[5] - nonAirBounds[2] + 1,
                    nonAir, nameCounts.size(), stateCounts.size(), top,
                    interiorVolume, rooms, darkSpots, doors, windows, lightSources,
                    metrics, issues, roomList);
            report.cells = Map.copyOf(cells);
            return report;
        }

        // ---------------------------------------------------------------------------------------------- (b) floating

        private void checkFloating() {
            byte[] seen = new byte[n];
            IntQueue queue = new IntQueue(1 << 12);
            for (int i = 0; i < n; i++) {
                if (present[i] == 0) continue;
                int y = g.yOf(i);
                boolean ground;
                if (y <= 0) {
                    ground = true;
                } else {
                    // resting directly on unset terrain: the cell below is unset, so y=1 sits on the ground layer
                    ground = y == 1 && g.raw(g.xOf(i), 0, g.zOf(i)) == null;
                }
                if (ground) {
                    seen[i] = 1;
                    queue.add(i);
                }
            }
            // Nothing seeded means nothing rests on the ground: every component is floating, which the
            // component pass below reports anyway.
            // BFS through present cells sharing a face or an edge: a stepped roof ring or a corbel sits diagonally on the course
            // below; a block held by one corner alone still floats
            while (!queue.isEmpty()) {
                int c = queue.poll();
                int y = g.yOf(c);
                int z = g.zOf(c);
                int x = g.xOf(c);
                for (int d = 0; d < 18; d++) {
                    int nx = x + NX[d], ny = y + NY[d], nz = z + NZ[d];
                    if (!g.contains(nx, ny, nz)) continue;
                    int j = g.index(nx, ny, nz);
                    if (present[j] == 0 || seen[j] != 0) continue;
                    seen[j] = 1;
                    queue.add(j);
                }
            }

            int floatingTotal = 0;
            List<Comp> comps = new ArrayList<>();
            byte[] compSeen = seen; // reuse: mark component members while collecting
            for (int i = 0; i < n; i++) {
                if (present[i] == 0 || compSeen[i] != 0) continue;
                Comp comp = new Comp();
                compSeen[i] = 1;
                queue.add(i);
                while (!queue.isEmpty()) {
                    int c = queue.poll();
                    comp.add(c, g);
                    int y = g.yOf(c), z = g.zOf(c), x = g.xOf(c);
                    // every floating cell, so the finisher can settle or remove whole loose clusters;
                    // comp.samples is capped at Issue.MAX_SAMPLES and would only show the first few
                    mark("floating_all", x, y, z);
                    for (int d = 0; d < 18; d++) {
                        int nx = x + NX[d], ny = y + NY[d], nz = z + NZ[d];
                        if (!g.contains(nx, ny, nz)) continue;
                        int j = g.index(nx, ny, nz);
                        if (present[j] == 0 || compSeen[j] != 0) continue;
                        compSeen[j] = 1;
                        queue.add(j);
                    }
                }
                comps.add(comp);
                floatingTotal += comp.count;
                if (comp.count <= 3) for (int[] p : comp.samples) mark("floating_small", p[0], p[1], p[2]);
            }

            metrics.put("floatingBlocks", floatingTotal);
            metrics.put("floatingComponents", comps.size());
            if (comps.isEmpty()) return;

            comps.sort(Comparator.comparingInt((Comp c) -> c.count).reversed()
                    .thenComparingInt(c -> c.minY));
            for (int k = 0; k < comps.size() && k < MAX_FLOATING_REPORTS; k++) {
                Comp c = comps.get(k);
                String bbox = "x" + c.minX + ".." + c.maxX + " y" + c.minY + ".." + c.maxY
                        + " z" + c.minZ + ".." + c.maxZ;
                String msg;
                if (k == 0) {
                    msg = floatingTotal + " block" + (floatingTotal == 1 ? "" : "s")
                            + " not connected to the ground (component of " + c.count + " at " + bbox + ")";
                } else {
                    msg = "floating component of " + c.count + " block" + (c.count == 1 ? "" : "s")
                            + " at " + bbox;
                }
                issues.add(Issue.of(Severity.MAJOR, "floating_blocks", msg, c.samples));
            }
            if (comps.size() > MAX_FLOATING_REPORTS) {
                int rest = 0;
                for (int k = MAX_FLOATING_REPORTS; k < comps.size(); k++) rest += comps.get(k).count;
                issues.add(Issue.of(Severity.MAJOR, "floating_blocks",
                        "plus " + (comps.size() - MAX_FLOATING_REPORTS) + " more floating component(s), " + rest
                                + " blocks total; connect them to the ground or remove them"));
            }
        }

        // ---------------------------------------------------------------------------------------------- (c) gravity

        private void checkGravity() {
            int count = 0;
            List<int[]> samples = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (present[i] == 0 || gravity[i] == 0) continue;
                int y = g.yOf(i);
                if (y < 1) continue;
                int z = g.zOf(i), x = g.xOf(i);
                if (isAirBelow(x, y, z)) {
                    count++;
                    addSample(samples, x, y, z);
                }
            }
            metrics.put("unsupportedGravity", count);
            if (count > 0) {
                issues.add(Issue.of(Severity.MAJOR, "unsupported_gravity",
                        count + " falling block" + (count == 1 ? " hangs" : "s hang")
                                + " in the air (sand/gravel/concrete powder) and will fall;"
                                + " put a solid block below",
                        samples));
            }
        }

        private boolean isAirBelow(int x, int y, int z) {
            BlockSpec below = g.raw(x, y - 1, z);
            if (below == null) return y - 1 >= 1; // unset y<=0 is terrain
            return below.isAir();
        }

        // ---------------------------------------------------------------------------------------------- (d) attachments

        private void checkAttachments() {
            List<int[]> wallBad = new ArrayList<>(), floorBad = new ArrayList<>(), ceilBad = new ArrayList<>();
            int wallCount = 0, floorCount = 0, ceilCount = 0;
            for (int i = 0; i < n; i++) {
                if (present[i] == 0) continue;
                BlockSpec s = g.at(i);
                String name = s.name();
                int y = g.yOf(i), z = g.zOf(i), x = g.xOf(i);

                AttachmentKind kind = classify(s);
                if (kind == AttachmentKind.NONE) continue;
                switch (kind) {
                    case WALL -> {
                        Dir d = facing(s, "facing", "rotation");
                        if (d == null || !d.isHorizontal()) continue;
                        int supX = x - d.dx, supY = y, supZ = z - d.dz;
                        BlockSpec sup = g.raw(supX, supY, supZ);
                        // the supporting face (the side of the support that points at the attachment) must be
                        // sturdy: glass panes, fences, slabs-from-the-side, leaves... cannot hold a wall torch
                        boolean ok = sup == null ? supY <= 0 : !sup.isAir() && info.isSturdyFace(sup, d);
                        if (!ok) {
                            wallCount++;
                            addSample(wallBad, x, y, z);
                            mark("unsupported_wall", x, y, z);
                        }
                    }
                    case FLOOR -> {
                        BlockSpec below = g.raw(x, y - 1, z);
                        boolean supported = effNonAir(x, y - 1, z) && !(below != null && Names.isCarpet(below));
                        if (supported && below != null && isPointLight(name) && !holdsPointLight(below)) supported = false;
                        if (!supported) {
                            floorCount++;
                            addSample(floorBad, x, y, z);
                            mark("unsupported_floor", x, y, z);
                        }
                    }
                    case CEILING -> {
                        if (!effNonAir(x, y + 1, z)) {
                            ceilCount++;
                            addSample(ceilBad, x, y, z);
                            mark("unsupported_ceiling", x, y, z);
                        }
                    }
                    default -> { }
                }
            }
            if (wallCount > 0) {
                issues.add(Issue.of(Severity.MAJOR, "unsupported_attachment",
                        wallCount + " wall-mounted block" + (wallCount == 1 ? " has no supporting block behind it" : "s have no supporting block behind them")
                                + " (wall torch, ladder, wall sign/banner, wall button/lever);"
                                + " the support must be at pos - facing", wallBad));
            }
            if (floorCount > 0) {
                issues.add(Issue.of(Severity.MAJOR, "unsupported_attachment",
                        floorCount + " floor-attached block"
                                + (floorCount == 1 ? " has no solid block below" : "s have no solid block below")
                                + " (torch, plant, carpet, rail, door lower half, ...)", floorBad));
            }
            if (ceilCount > 0) {
                issues.add(Issue.of(Severity.MAJOR, "unsupported_attachment",
                        ceilCount + " ceiling-mounted block"
                                + (ceilCount == 1 ? " has no block above" : "s have no block above")
                                + " (hanging lantern, hanging sign, ceiling button/lever)", ceilBad));
            }
            metrics.put("unsupportedAttachments", wallCount + floorCount + ceilCount);
        }

        /** Standing torches / lanterns / candles need a top face with centre support. */
        private boolean isPointLight(String name) {
            return name.endsWith("torch") || name.endsWith("lantern") && !name.equals("sea_lantern") && !name.equals("jack_o_lantern");
        }

        private boolean holdsPointLight(BlockSpec below) {
            String b = below.name();
            if (info.isSturdyFace(below, Dir.UP)) return true;
            return b.endsWith("_fence") || b.endsWith("_wall") || b.endsWith("glass_pane") || b.endsWith("_bars")
                    || b.endsWith("chain") || b.endsWith("lantern") || b.equals("hopper") || b.endsWith("_slab")
                    || b.endsWith("_stairs") || b.endsWith("_trapdoor") || b.equals("end_rod") || b.endsWith("lightning_rod")
                    || b.endsWith("_shelf") || b.equals("lectern") || b.endsWith("anvil") || b.equals("bell");
        }

        private enum AttachmentKind { NONE, WALL, FLOOR, CEILING }

        private AttachmentKind classify(BlockSpec spec) {
            String n = spec.name();
            // --- wall mounted
            if (n.endsWith("wall_torch") || n.endsWith("_wall_sign") || n.endsWith("_wall_banner")
                    || n.endsWith("_wall_hanging_sign") || n.equals("ladder") || n.equals("tripwire_hook")) {
                return AttachmentKind.WALL;
            }
            if (n.endsWith("_button") || n.equals("lever")) {
                String face = propOf(spec, "face", "wall");
                if ("wall".equals(face)) return AttachmentKind.WALL;
                if ("ceiling".equals(face)) return AttachmentKind.CEILING;
                return AttachmentKind.FLOOR;
            }
            // --- ceiling mounted
            boolean lantern = isLantern(n);
            if (lantern) {
                String hanging = propOf(spec, "hanging", "false");
                return "true".equals(hanging) ? AttachmentKind.CEILING : AttachmentKind.FLOOR;
            }
            if (n.endsWith("_hanging_sign") && !n.contains("_wall_")) return AttachmentKind.CEILING;
            // --- floor attached
            if (isFloorAttached(n)) return AttachmentKind.FLOOR;
            return AttachmentKind.NONE;
        }

        /** Explicit property of the spec, else the default reported by {@code info}, else {@code def}. */
        private String propOf(BlockSpec spec, String key, String def) {
            String v = spec.prop(key);
            if (v != null) return v;
            try {
                v = info.getProp(spec, key);
            } catch (RuntimeException ignored) {
                // fall through to the hard default
            }
            return v == null ? def : v;
        }

        private boolean isFloorAttached(String n) {
            if (n.equals("torch") || n.equals("soul_torch") || n.equals("copper_torch") || n.equals("redstone_torch")) {
                return true;
            }
            if (n.endsWith("candle") && !n.equals("candle_cake")) return true;
            if (n.endsWith("_carpet")) return true;
            if (n.endsWith("_pressure_plate")) return true;
            if (Names.FLOOR_PLANTS.contains(n) || n.endsWith("_sapling")) return true;
            if (n.equals("rail") || n.endsWith("_rail")) return true;
            if ((n.endsWith("_sign") || n.endsWith("_banner"))
                    && !n.contains("wall") && !n.endsWith("_hanging_sign")) {
                return true;
            }
            if (n.endsWith("_door")) return true;
            if (n.equals("redstone_wire") || n.endsWith("repeater") || n.endsWith("comparator")) return true;
            if (n.equals("flower_pot")) return false; // explicitly allowed anywhere
            return false;
        }

        private boolean isLantern(String n) {
            return n.endsWith("lantern") && !n.equals("sea_lantern") && !n.equals("jack_o_lantern")
                    && !n.equals("redstone_lamp");
        }

        private Dir facing(BlockSpec s, String... props) {
            for (String p : props) {
                String v = null;
                try {
                    v = info.getProp(s, p);
                } catch (RuntimeException ignored) {
                    // fall through
                }
                if (v == null) v = s.prop(p);
                Dir d = Dir.parse(v);
                if (d != null) return d;
            }
            return null;
        }

        // ---------------------------------------------------------------------------------------------- (e) enclosure

        private void checkEnclosure() {
            outside = new byte[n];
            IntQueue q = new IntQueue(1 << 12);
            // seed all boundary cells of the padded grid
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    for (int x = minX; x <= maxX; x++) {
                        boolean boundary = x == minX || x == maxX || y == minY || y == maxY || z == minZ || z == maxZ;
                        if (!boundary) continue;
                        int i = g.index(x, y, z);
                        if (open[i] != 0 && outside[i] == 0) {
                            outside[i] = 1;
                            q.add(i);
                        }
                    }
                }
            }
            while (!q.isEmpty()) {
                int c = q.poll();
                int y = g.yOf(c), z = g.zOf(c), x = g.xOf(c);
                for (int d = 0; d < 6; d++) {
                    int nx = x + DX[d], ny = y + DY[d], nz = z + DZ[d];
                    if (!g.contains(nx, ny, nz)) continue;
                    int j = g.index(nx, ny, nz);
                    if (open[j] == 0 || outside[j] != 0) continue;
                    outside[j] = 1;
                    q.add(j);
                }
            }

            interiorMask = new byte[n];
            for (int i = 0; i < n; i++) {
                if (open[i] != 0 && outside[i] == 0) {
                    interiorMask[i] = 1;
                    interiorVolume++;
                }
            }

            // rooms: connected components of interior with volume >= 4
            byte[] seen = new byte[n];
            for (int i = 0; i < n; i++) {
                if (interiorMask[i] == 0 || seen[i] != 0) continue;
                int vol = 0;
                seen[i] = 1;
                q.add(i);
                while (!q.isEmpty()) {
                    int c = q.poll();
                    vol++;
                    int y = g.yOf(c), z = g.zOf(c), x = g.xOf(c);
                    for (int d = 0; d < 6; d++) {
                        int nx = x + DX[d], ny = y + DY[d], nz = z + DZ[d];
                        if (!g.contains(nx, ny, nz)) continue;
                        int j = g.index(nx, ny, nz);
                        if (interiorMask[j] == 0 || seen[j] != 0) continue;
                        seen[j] = 1;
                        q.add(j);
                    }
                }
                if (vol >= ROOM_MIN_VOLUME) {
                    rooms++;
                    if (vol > biggestRoom) biggestRoom = vol;
                }
            }

            metrics.put("interiorVolume", interiorVolume);
            metrics.put("rooms", rooms);
            metrics.put("biggestRoom", biggestRoom);

            if (opts.expectInterior && interiorVolume == 0) {
                issues.add(Issue.of(Severity.MAJOR, "no_enclosed_interior",
                        "no enclosed interior found: walls/roof have holes or there is no room"
                                + " (every air cell is reachable from outside)"));
            } else if (opts.expectInterior && rooms == 0 && interiorVolume > 0) {
                issues.add(Issue.of(Severity.MINOR, "small_interior",
                        "the " + interiorVolume + " enclosed air cell(s) form no usable room (each room needs >= 4"
                                + " connected cells); the walls are probably too thin or full of gaps"));
            }
            checkSkyExposure();
        }

        /**
         * Roof holes. Interior cells cannot see the sky by construction (the outside flood fills from above), so
         * the signal is: a walkable air cell inside the wall footprint that IS reached from outside and has no
         * block above it — i.e. the roof above that spot is missing. Requires a wall ring to exist, which keeps
         * open builds (bridges, ruins) from producing false positives.
         */
        private void checkSkyExposure() {
            if (!opts.expectInterior) return;
            int[] fp = wallFootprint();
            if (fp == null) return;
            // Only a real wall ring can have a roof hole. Corner pillars or a couple of scattered blocks must not
            // produce a "sky exposed interior" report (false positives are worse than misses here).
            if (ringRatio(fp) < 0.7) return;
            int wallTop = wallTop(fp);
            if (wallTop < 1) return;
            List<int[]> samples = new ArrayList<>();
            int exposed = 0;
            for (int y = 1; y <= wallTop && y <= maxY; y++) {
                for (int z = fp[2] + 1; z <= fp[3] - 1; z++) {
                    for (int x = fp[0] + 1; x <= fp[1] - 1; x++) {
                        if (!g.contains(x, y, z)) continue;
                        int i = g.index(x, y, z);
                        if (open[i] == 0 || outside[i] == 0) continue;
                        if (!openAt(x, y + 1, z)) continue;
                        if (!floorSolidAt(x, y - 1, z)) continue;
                        if (hasBlockAbove(x, y + 1, z)) continue;
                        exposed++;
                        addSample(samples, x, y, z);
                    }
                }
            }
            metrics.put("skyExposedInterior", exposed);
            // a large open area inside the wall ring is a courtyard / bailey / atrium, not a roof hole
            long innerArea = (long) Math.max(1, fp[1] - fp[0] - 1) * Math.max(1, fp[3] - fp[2] - 1);
            if (exposed > 0 && exposed > 0.2 * innerArea) {
                metrics.put("openCourtyard", true);
                issues.add(Issue.of(Severity.INFO, "open_courtyard",
                        "a large open area inside the walls is open to the sky (courtyard/bailey) - fine for castles and"
                                + " walled yards; buildings inside it still need closed roofs", samples));
                return;
            }
            if (exposed > 0) {
                Severity sev = exposed >= 8 ? Severity.MAJOR : Severity.MINOR;
                issues.add(Issue.of(sev, "sky_exposed_interior",
                        exposed + " walkable spot" + (exposed == 1 ? " is" : "s are")
                                + " open to the sky inside the wall outline: the roof or the upper walls have a"
                                + " hole (rain, mobs and lighting problems); close the roof", samples));
            }
        }

        /** Highest y with a present block in column (x,z), or Integer.MIN_VALUE. Cached per analysis run. */
        private int highestInColumn(int x, int z) {
            if (highestY == null) {
                highestY = new int[sx * sz];
                java.util.Arrays.fill(highestY, Integer.MIN_VALUE);
                int i = 0;
                for (int y = minY; y <= maxY; y++) {
                    for (int zz = minZ; zz <= maxZ; zz++) {
                        for (int xx = minX; xx <= maxX; xx++, i++) {
                            if (present[i] != 0) highestY[(zz - minZ) * sx + (xx - minX)] = y;
                        }
                    }
                }
            }
            return highestY[(z - minZ) * sx + (x - minX)];
        }

        /** True when any block sits strictly above (x,y,z) in the same column. */
        private boolean hasBlockAbove(int x, int y, int z) {
            return highestInColumn(x, z) >= y;
        }

        /** Bounding box {minX,maxX,minZ,maxZ} of the wall layer (y = 1, else the lowest non-air layer >= 1). */
        private int[] wallFootprintCache;

        /**
         * Footprint of the main body: a box that looks like a walled body, chosen from candidates gathered
         * over the wall layers y = 1..5 (opaque blocks, glass, doors, logs).
         *
         * <p>A live run exposed the old selection: a fachwerk house whose ring is cut by open windows at one
         * level produced a largest per-layer component of 24 cells (a wall fragment), and the vote tie-break
         * "smallest area wins" then reported a 2&times;6 footprint for a 10&times;10 house, failing the size
         * check. Two changes fix that:</p>
         * <ol>
         *   <li>a candidate from the <b>union</b> of the wall layers: a ring cut by windows at one level is
         *       still connected across the others;</li>
         *   <li>candidates must be <b>maximal</b> ({@link #maximalBox(int[])}): the line just outside each
         *       side is mostly not wall, so a box that could be widened into standing walls (a trimmed
         *       fragment that stops short of the outer wall) is rejected. Among the maximal boxes the one
         *       with the most wall cells inside wins — the mass of the body, not a fragment of it.</li>
         * </ol>
         * Garden props, fences, wells and paths form separate or non-wall components, and thin protrusions
         * are stripped by the trim, so they do not inflate the footprint. When no candidate is maximal the
         * union box is used, then the bounding box of the lowest non-empty layer.
         */
        private int[] wallFootprint() {
            if (wallFootprintCache != null) return wallFootprintCache.length == 0 ? null : wallFootprintCache;
            int sx = maxX - minX + 1, sz = maxZ - minZ + 1;
            int[] queue = new int[sx * sz];
            java.util.Map<String, int[]> boxes = new java.util.LinkedHashMap<>();
            java.util.Map<String, Integer> votes = new java.util.HashMap<>();
            boolean[] union = new boolean[sx * sz];
            boolean anyUnion = false;
            for (int y = Math.max(1, minY); y <= Math.min(maxY, 5); y++) {
                boolean[] wall = layerWall(y, sx, sz);
                for (int i = 0; i < wall.length; i++) {
                    if (wall[i]) {
                        union[i] = true;
                        anyUnion = true;
                    }
                }
                boolean[] bestMask = largestComponent(wall, sx, sz, queue);
                if (bestMask == null) continue;
                int size = 0;
                for (boolean b : bestMask) if (b) size++;
                if (size < 8) continue;
                int[] box = trimmedBox(bestMask, sx, sz);
                if (box == null) continue;
                int[] world = {box[0] + minX, box[1] + minX, box[2] + minZ, box[3] + minZ};
                String key = world[0] + "," + world[1] + "," + world[2] + "," + world[3];
                boxes.putIfAbsent(key, world);
                votes.merge(key, 1, Integer::sum);
            }
            // every component of the union of the layers becomes a candidate: a ring cut by windows at one
            // level stays connected across the others, and a building that shares its plot with a raised
            // lawn is a candidate of its own instead of losing the "largest component" vote to the lawn
            int[] unionBox = null;
            if (anyUnion) {
                for (boolean[] comp : components(union, sx, sz, queue, 8)) {
                    int[] box = trimmedBox(comp, sx, sz);
                    if (box == null) continue;
                    int[] world = {box[0] + minX, box[1] + minX, box[2] + minZ, box[3] + minZ};
                    String key = world[0] + "," + world[1] + "," + world[2] + "," + world[3];
                    boxes.putIfAbsent(key, world);
                    votes.merge(key, 1, Integer::sum);
                    if (unionBox == null) unionBox = world;
                }
            }
            // a body box is maximal (it cannot be widened into standing walls), hollow (walls make a shell;
            // a solid terrain slab is scenery, not a body) and among those the most complete wall ring wins,
            // with the wall mass as the tie-break. A garden pergola of logs leaning on the corner widened a
            // live 10x10 body to 12; its box has a broken ring (0.90) while the true body's is whole (1.00).
            int[] best = null;
            double bestRing = 0.0;
            int bestCells = -1;
            for (int[] b : boxes.values()) {
                if (!maximalBox(b)) continue;
                if (hollowShare(b) < 0.25) continue;
                double ring = ringRatio(b);
                int cells = wallCellsIn(b);
                if (ring > bestRing + 0.01 || (Math.abs(ring - bestRing) <= 0.01 && cells > bestCells)) {
                    best = b;
                    bestRing = ring;
                    bestCells = cells;
                }
            }
            if (best == null && unionBox != null) best = unionBox;
            if (best == null) {
                int bestVotes = -1;
                long bestArea = Long.MAX_VALUE;
                for (java.util.Map.Entry<String, int[]> e : boxes.entrySet()) {
                    int v = votes.get(e.getKey());
                    int[] b = e.getValue();
                    long area = (long) (b[1] - b[0] + 1) * (b[3] - b[2] + 1);
                    if (v > bestVotes || (v == bestVotes && area < bestArea)) {
                        best = b;
                        bestVotes = v;
                        bestArea = area;
                    }
                }
            }
            if (best == null) {
                best = footprintAt(1);
                for (int y = 2; y <= maxY && best == null; y++) best = footprintAt(y);
            }
            wallFootprintCache = best == null ? new int[0] : best;
            return best;
        }

        /**
         * Natural ground, never a wall. Raised garden beds sit at wall level, are occluding, and touch the
         * house — a live build measured a 10&times;10 body as 12 wide because a podzol bed at y=1 leaned on the
         * wall and joined its component. Only soil is listed: stone, andesite, granite, diorite, deepslate,
         * tuff, calcite, snow and ice are also what castle walls, towers and igloos are built of, and leaving
         * them out of the mask would measure such a body by whatever trim it happens to have.
         */
        private static final java.util.Set<String> NATURAL_GROUND = java.util.Set.of(
                "grass_block", "dirt", "coarse_dirt", "rooted_dirt", "podzol", "mycelium", "moss_block",
                "sand", "red_sand", "gravel", "clay", "mud", "dirt_path", "farmland", "azalea");

        /** The wall-like cells of one layer: opaque blocks, glass, doors, logs and wood — but not terrain. */
        private boolean[] layerWall(int y, int sx, int sz) {
            boolean[] wall = new boolean[sx * sz];
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    BlockSpec s = g.raw(x, y, z);
                    if (s == null || s.isAir()) continue;
                    String nm = s.name();
                    if (NATURAL_GROUND.contains(nm)) continue;
                    wall[(z - minZ) * sx + (x - minX)] = info.isOccluding(s) || nm.contains("glass")
                            || nm.endsWith("_door") || nm.endsWith("_log") || nm.endsWith("_wood");
                }
            }
            return wall;
        }

        /**
         * All 4-connected (in x/z) components of a mask with at least {@code min} cells, largest first. The
         * union of the wall layers can hold several real subjects — a house and the lawn it stands on — and
         * picking only the largest would let the lawn hide the house.
         */
        private static List<boolean[]> components(boolean[] mask, int sx, int sz, int[] queue, int min) {
            boolean[] seen = new boolean[mask.length];
            List<boolean[]> out = new ArrayList<>();
            for (int start = 0; start < mask.length; start++) {
                if (!mask[start] || seen[start]) continue;
                int head = 0, tail = 0;
                queue[tail++] = start;
                seen[start] = true;
                int size = 1;
                while (head < tail) {
                    int c = queue[head++];
                    int cx = c % sx, cz = c / sx;
                    int[][] nb = {{cx + 1, cz}, {cx - 1, cz}, {cx, cz + 1}, {cx, cz - 1}};
                    for (int[] q : nb) {
                        if (q[0] < 0 || q[1] < 0 || q[0] >= sx || q[1] >= sz) continue;
                        int ni = q[1] * sx + q[0];
                        if (mask[ni] && !seen[ni]) {
                            seen[ni] = true;
                            size++;
                            queue[tail++] = ni;
                        }
                    }
                }
                if (size < min) continue;
                boolean[] comp = new boolean[mask.length];
                for (int k = 0; k < tail; k++) comp[queue[k]] = true;
                out.add(comp);
            }
            out.sort((a, b) -> Integer.compare(count(b), count(a)));
            return out;
        }

        private static int count(boolean[] mask) {
            int c = 0;
            for (boolean b : mask) if (b) c++;
            return c;
        }

        /**
         * Largest 4-connected (in x/z) component of a mask, or {@code null} when the mask is empty. The
         * queue buffer is supplied by the caller so repeated calls do not allocate.
         */
        private static boolean[] largestComponent(boolean[] mask, int sx, int sz, int[] queue) {
            boolean[] seen = new boolean[mask.length];
            boolean[] bestMask = null;
            int bestSize = 0;
            for (int start = 0; start < mask.length; start++) {
                if (!mask[start] || seen[start]) continue;
                int head = 0, tail = 0;
                queue[tail++] = start;
                seen[start] = true;
                while (head < tail) {
                    int c = queue[head++];
                    int cx = c % sx, cz = c / sx;
                    int[][] nb = {{cx + 1, cz}, {cx - 1, cz}, {cx, cz + 1}, {cx, cz - 1}};
                    for (int[] q : nb) {
                        if (q[0] < 0 || q[1] < 0 || q[0] >= sx || q[1] >= sz) continue;
                        int ni = q[1] * sx + q[0];
                        if (mask[ni] && !seen[ni]) {
                            seen[ni] = true;
                            queue[tail++] = ni;
                        }
                    }
                }
                if (tail > bestSize) {
                    bestSize = tail;
                    bestMask = new boolean[mask.length];
                    for (int k = 0; k < tail; k++) bestMask[queue[k]] = true;
                }
            }
            return bestMask;
        }

        /**
         * Whether a candidate box is the tight extent of its wall mass: the line just outside each of the
         * four sides is mostly NOT wall-like over the wall layers. A box trimmed out of a window level that
         * stops short of the outer wall fails this — the wall stands right outside its edge — while the true
         * body box is surrounded by air, garden or terrain.
         */
        private boolean maximalBox(int[] fp) {
            int yLo = Math.max(1, minY);
            int yHi = Math.min(maxY, 5);
            double outside = 0.60;
            for (int y = yLo; y <= yHi; y++) {
                boolean[] wall = layerWall(y, maxX - minX + 1, maxZ - minZ + 1);
                int sx = maxX - minX + 1;
                int sz = maxZ - minZ + 1;
                // west and east lines just outside the box
                for (int side = 0; side < 2; side++) {
                    int x = side == 0 ? fp[0] - 1 : fp[1] + 1;
                    if (x < minX - 1 || x > maxX + 1) continue;
                    int wallish = 0;
                    int total = 0;
                    for (int z = fp[2]; z <= fp[3]; z++) {
                        total++;
                        if (x >= minX && x <= maxX && z >= minZ && z <= maxZ && wall[(z - minZ) * sx + (x - minX)]) wallish++;
                    }
                    if (total > 0 && (double) wallish / total > outside) return false;
                }
                // north and south lines just outside the box
                for (int side = 0; side < 2; side++) {
                    int z = side == 0 ? fp[2] - 1 : fp[3] + 1;
                    if (z < minZ - 1 || z > maxZ + 1) continue;
                    int wallish = 0;
                    int total = 0;
                    for (int x = fp[0]; x <= fp[1]; x++) {
                        total++;
                        if (x >= minX && x <= maxX && z >= minZ && z <= maxZ && wall[(z - minZ) * sx + (x - minX)]) wallish++;
                    }
                    if (total > 0 && (double) wallish / total > outside) return false;
                }
            }
            return true;
        }

        /**
         * The share of air strictly inside a candidate box over the wall layers. Walls make a hollow shell;
         * a solid terrain slab has no interior air at all, so it can never be picked as the body over a
         * real building on the same plot.
         */
        private double hollowShare(int[] fp) {
            // only up to the top of what the box actually holds: a slab that fills y=1..3 must not look
            // hollow just because y=4..5 above it are air
            int top = 0;
            for (int y = Math.max(1, minY); y <= Math.min(maxY, 5); y++) {
                for (int x = fp[0]; x <= fp[1]; x++) {
                    for (int z = fp[2]; z <= fp[3]; z++) {
                        if (g.contains(x, y, z) && present[g.index(x, y, z)] != 0) {
                            top = Math.max(top, y);
                            break;
                        }
                    }
                }
            }
            if (top <= 0) return 0.0;
            int inner = 0;
            int air = 0;
            for (int y = Math.max(1, minY); y <= top; y++) {
                for (int x = fp[0] + 1; x <= fp[1] - 1; x++) {
                    for (int z = fp[2] + 1; z <= fp[3] - 1; z++) {
                        inner++;
                        BlockSpec s = g.raw(x, y, z);
                        if (s == null || s.isAir()) air++;
                    }
                }
            }
            return inner == 0 ? 0.0 : (double) air / inner;
        }

        /** Present cells inside a candidate box over the wall layers, used to break body-score ties. */
        private int wallCellsIn(int[] fp) {
            int cells = 0;
            for (int y = Math.max(1, minY); y <= Math.min(maxY, 5); y++) {
                for (int x = fp[0]; x <= fp[1]; x++) {
                    for (int z = fp[2]; z <= fp[3]; z++) {
                        if (g.contains(x, y, z) && present[g.index(x, y, z)] != 0) cells++;
                    }
                }
            }
            return cells;
        }

        /**
         * Bounding box {x0,x1,z0,z1} (grid-relative) of a component mask, shrunk from each side while the edge
         * line is less than 40% occupied — this strips chimneys, buttresses, porches and jetty brackets that
         * stick out of the wall rectangle.
         */
        private static int[] trimmedBox(boolean[] mask, int sx, int sz) {
            int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
            for (int i = 0; i < mask.length; i++) {
                if (!mask[i]) continue;
                int x = i % sx, z = i / sx;
                x0 = Math.min(x0, x);
                x1 = Math.max(x1, x);
                z0 = Math.min(z0, z);
                z1 = Math.max(z1, z);
            }
            if (x0 > x1) return null;
            boolean changed = true;
            while (changed && x1 - x0 >= 2 && z1 - z0 >= 2) {
                changed = false;
                if (colOcc(mask, sx, x0, z0, z1) < 0.4) { x0++; changed = true; }
                if (x1 - x0 >= 2 && colOcc(mask, sx, x1, z0, z1) < 0.4) { x1--; changed = true; }
                if (rowOcc(mask, sx, z0, x0, x1) < 0.4) { z0++; changed = true; }
                if (z1 - z0 >= 2 && rowOcc(mask, sx, z1, x0, x1) < 0.4) { z1--; changed = true; }
            }
            return new int[]{x0, x1, z0, z1};
        }

        private static double colOcc(boolean[] mask, int sx, int x, int z0, int z1) {
            int c = 0;
            for (int z = z0; z <= z1; z++) if (mask[z * sx + x]) c++;
            return (double) c / (z1 - z0 + 1);
        }

        private static double rowOcc(boolean[] mask, int sx, int z, int x0, int x1) {
            int c = 0;
            for (int x = x0; x <= x1; x++) if (mask[z * sx + x]) c++;
            return (double) c / (x1 - x0 + 1);
        }

        /** Perimeter (x,z) pairs of a footprint: the wall ring, no duplicates. */
        private int[][] perimeter(int[] fp) {
            List<int[]> pts = new ArrayList<>(2 * (fp[1] - fp[0] + fp[3] - fp[2]) + 8);
            for (int x = fp[0]; x <= fp[1]; x++) {
                pts.add(new int[]{x, fp[2]});
                if (fp[3] != fp[2]) pts.add(new int[]{x, fp[3]});
            }
            for (int z = fp[2] + 1; z <= fp[3] - 1; z++) {
                pts.add(new int[]{fp[0], z});
                if (fp[1] != fp[0]) pts.add(new int[]{fp[1], z});
            }
            return pts.toArray(new int[0][]);
        }

        private int[] footprintAt(int y) {
            if (y < minY || y > maxY) return null;
            int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    if (present[g.index(x, y, z)] == 0) continue;
                    if (x < x0) x0 = x;
                    if (x > x1) x1 = x;
                    if (z < z0) z0 = z;
                    if (z > z1) z1 = z;
                }
            }
            return x1 < x0 ? null : new int[]{x0, x1, z0, z1};
        }

        /**
         * Completeness of the outer wall ring: the best share of present blocks on the perimeter of {@code fp}
         * over the wall layers y=1..wallTop. 1.0 = a closed ring, 0.25 = only the four corners.
         */
        private double ringRatio(int[] fp) {
            int top = Math.max(1, wallTop(fp));
            int[][] ring = perimeter(fp);
            if (ring.length == 0) return 0.0;
            int best = 0;
            for (int y = 1; y <= top && y <= maxY; y++) {
                int presentCount = 0;
                for (int[] p : ring) {
                    if (g.contains(p[0], y, p[1]) && present[g.index(p[0], y, p[1])] != 0) presentCount++;
                }
                if (presentCount > best) best = presentCount;
            }
            return (double) best / ring.length;
        }

        /**
         * Eave level: the highest layer at which the footprint perimeter is still at least 70% closed. Gable
         * triangles and chimneys rise above it but do not count, so roof surfaces are not mistaken for floors.
         */
        private int wallTop(int[] fp) {
            int[][] ring = perimeter(fp);
            if (ring.length == 0) return 0;
            int top = 0;
            for (int y = Math.max(1, minY); y <= maxY; y++) {
                int cnt = 0;
                for (int[] p : ring) {
                    if (g.contains(p[0], y, p[1]) && present[g.index(p[0], y, p[1])] != 0) cnt++;
                }
                if (cnt >= 0.7 * ring.length) top = y;
            }
            return top;
        }

        // ---------------------------------------------------------------------------------------------- (f) lighting

        /** Smallest floor area (cells) that counts as a room to furnish. */
        private static final int ROOM_MIN_AREA = 12;
        final List<AnalysisReport.Room> roomList = new ArrayList<>();

        /**
         * Rooms: floor cells inside the shell (enclosed air or a carpet with headroom, on a standable floor), joined
         * per level by 4-connectivity. Each room counts the blocks standing in its box up to 3 high; a large room
         * with almost nothing in it reads as an empty shell however good the facade is.
         */
        private void checkRooms() {
            byte[] floor = new byte[n];
            for (int i = 0; i < n; i++) {
                int y = g.yOf(i), z = g.zOf(i), x = g.xOf(i);
                // a carpet is part of the floor layer it lies in, not a floor under the layer above it
                boolean onCarpet = g.contains(x, y - 1, z) && present[g.index(x, y - 1, z)] != 0 && Names.isCarpet(g.at(g.index(x, y - 1, z)));
                boolean cell = interiorMask[i] != 0 && !onCarpet
                        || present[i] != 0 && Names.isCarpet(g.at(i)) && g.contains(x, y + 1, z) && interiorMask[g.index(x, y + 1, z)] != 0;
                if (cell && openAt(x, y + 1, z) && floorSolidAt(x, y - 1, z)) floor[i] = 1;
            }
            byte[] doorway = new byte[n];
            // doorways split rooms: a one-cell gap between wall blocks two high with a lintel above (a gap between
            // furniture has open air above it)
            for (int i = 0; i < n; i++) {
                if (floor[i] == 0) continue;
                int y = g.yOf(i), z = g.zOf(i), x = g.xOf(i);
                if (!presentAt(x, y + 2, z)) continue;
                boolean ew = presentAt(x - 1, y, z) && presentAt(x + 1, y, z) && presentAt(x - 1, y + 1, z) && presentAt(x + 1, y + 1, z);
                boolean ns = presentAt(x, y, z - 1) && presentAt(x, y, z + 1) && presentAt(x, y + 1, z - 1) && presentAt(x, y + 1, z + 1);
                if (ew != ns) {
                    floor[i] = 0;
                    doorway[i] = 1;
                }
            }
            byte[] seen = new byte[n];
            IntQueue q = new IntQueue(1 << 10);
            for (int i = 0; i < n; i++) {
                if (floor[i] == 0 || seen[i] != 0) continue;
                int y = g.yOf(i);
                int x1 = Integer.MAX_VALUE, z1 = Integer.MAX_VALUE, x2 = Integer.MIN_VALUE, z2 = Integer.MIN_VALUE, area = 0, minLight = 15, core = 0;
                boolean access = false;
                int dark = 0;
                List<int[]> region = new ArrayList<>();
                seen[i] = 1;
                q.add(i);
                while (!q.isEmpty()) {
                    int c = q.poll();
                    int cx = g.xOf(c), cz = g.zOf(c);
                    area++;
                    region.add(new int[]{cx, y, cz});
                    if (light != null && light[c] == 0) dark++;
                    if (!access) access = reachable(cx, y, cz, doorway);
                    x1 = Math.min(x1, cx);
                    x2 = Math.max(x2, cx);
                    z1 = Math.min(z1, cz);
                    z2 = Math.max(z2, cz);
                    if (interiorMask[c] != 0) minLight = Math.min(minLight, light[c]);
                    for (int d = 2; d < 6; d++) {
                        int nx = cx + DX[d], nz = cz + DZ[d];
                        if (!g.contains(nx, y, nz)) continue;
                        int j = g.index(nx, y, nz);
                        if (floor[j] == 0 || seen[j] != 0) continue;
                        seen[j] = 1;
                        q.add(j);
                    }
                    boolean open3 = true;
                    for (int dx = -1; dx <= 1 && open3; dx++) for (int dz = -1; dz <= 1 && open3; dz++) {
                        open3 = g.contains(cx + dx, y, cz + dz) && floor[g.index(cx + dx, y, cz + dz)] != 0;
                    }
                    if (open3) core++;
                }
                // a room has somewhere to stand in the open: strips (eave gaps under a roof, wall tops, the tops of
                // furniture rows) never hold a 3 x 3 patch of floor
                if (area < ROOM_MIN_AREA || core < 1) continue;
                // no door, doorway, stair, ladder or opening reaches it: a roof void or a sealed cavity, not a room
                if (!access) {
                    sealedDark += dark;
                    sealed++;
                    for (int[] p : region) mark("sealed", p[0], p[1], p[2]);
                    continue;
                }
                int furniture = 0;
                for (int yy = y; yy <= y + 2; yy++) for (int zz = z1; zz <= z2; zz++) for (int xx = x1; xx <= x2; xx++) {
                    if (!g.contains(xx, yy, zz)) continue;
                    int j = g.index(xx, yy, zz);
                    if (present[j] != 0 && occ[j] == 0 || present[j] != 0 && (emit[j] > 0 || isFurnitureBlock(g.at(j)))) furniture++;
                }
                roomList.add(new AnalysisReport.Room(x1, z1, x2, z2, y, area, furniture, minLight));
            }
            // the tops of walls and tall furniture standing in a room's own air are not a floor above it: a real
            // storey sits at least 4 higher (3 air + the floor layer)
            List<AnalysisReport.Room> found = new ArrayList<>(roomList);
            roomList.removeIf(r -> found.stream().filter(o -> o != r && o.y() < r.y() && r.y() - o.y() < 4)
                    .mapToDouble(o -> overlap(r, o)).sum() >= 0.5);
            roomList.sort((a, b) -> Integer.compare(b.area(), a.area()));
            int bare = 0;
            for (AnalysisReport.Room r : roomList) {
                if (!opts.expectInterior) break;
                if (r.area() >= 16 && r.density() < 0.08) {
                    bare++;
                    issues.add(Issue.of(Severity.MAJOR, "bare_room", "room " + r.describe()
                            + " is nearly empty: furnish it for its purpose (furnish() or furniture pieces), with light"));
                } else if (r.density() < 0.18) {
                    issues.add(Issue.of(Severity.MINOR, "sparse_room", "room " + r.describe()
                            + " is sparse: add furniture, decoration and light along its walls"));
                }
            }
            metrics.put("furnishedRooms", roomList.size() - bare);
            metrics.put("bareRooms", bare);
            metrics.put("sealedVoids", sealed);
            if (sealed > 0) {
                issues.add(Issue.of(Severity.INFO, "sealed_void", sealed + " enclosed space(s) no door, stair or ladder reaches"
                        + " (roof voids, solid cores); they are not rooms and need neither furniture nor light"));
            }
            if (sealedDark > 0) {
                darkSpots = Math.max(0, darkSpots - sealedDark);
                metrics.put("darkSpots", darkSpots);
                for (int k = 0; k < issues.size(); k++) {
                    Issue is = issues.get(k);
                    if (!is.code().equals("dark_interior")) continue;
                    if (darkSpots == 0) issues.remove(k);
                    else issues.set(k, Issue.of(Severity.MAJOR, "dark_interior", darkSpots + " walkable interior cells in reachable rooms"
                            + " have block light 0 (hostile mobs can spawn); add torches/lanterns", is.samples()));
                    break;
                }
            }
        }

        private int sealed, sealedDark;

        /** Every defect cell by kind, for tools that repair them (the report's issues carry only samples). */
        final Map<String, List<int[]>> cells = new HashMap<>();

        private void mark(String kind, int x, int y, int z) {
            List<int[]> l = cells.computeIfAbsent(kind, k -> new ArrayList<>());
            if (l.size() < MAX_MARKED_CELLS) l.add(new int[]{x, y, z});
        }

        /**
         * Whether a floor cell is reached from elsewhere: a door, doorway, stair, ladder or trapdoor beside or under
         * it, or open air outside the shell next to it (an arch or an open side).
         */
        private boolean reachable(int x, int y, int z, byte[] doorway) {
            if (g.contains(x, y - 1, z) && present[g.index(x, y - 1, z)] != 0 && isAccess(g.at(g.index(x, y - 1, z)))) return true;
            for (int d = 0; d < 6; d++) {
                if (d >= 2 || d == 1) {
                    for (int dy = 0; dy <= 1; dy++) {
                        int nx = x + DX[d], ny = y + dy + (d == 1 ? 1 : 0), nz = z + DZ[d];
                        if (!g.contains(nx, ny, nz)) continue;
                        int j = g.index(nx, ny, nz);
                        if (doorway[j] != 0) return true;
                        if (present[j] != 0 ? isAccess(g.at(j)) : dy == 0 && d >= 2 && interiorMask[j] == 0) return true;
                        // an opening in the floor beside it: a stairwell or a ladder shaft from the storey below
                        if (dy == 0 && d >= 2 && present[j] == 0 && g.contains(nx, ny - 1, nz) && present[g.index(nx, ny - 1, nz)] == 0) return true;
                    }
                }
            }
            return false;
        }

        private static boolean isAccess(BlockSpec s) {
            String nm = s.name();
            return nm.endsWith("_door") || nm.endsWith("_stairs") || nm.equals("ladder") || nm.equals("scaffolding")
                    || nm.endsWith("_trapdoor") || nm.equals("vine");
        }

        /** Share of room r's box that lies over room q's box widened by its walls (2 cells). */
        private static double overlap(AnalysisReport.Room r, AnalysisReport.Room q) {
            int w = Math.min(r.x2(), q.x2() + 2) - Math.max(r.x1(), q.x1() - 2) + 1, d = Math.min(r.z2(), q.z2() + 2) - Math.max(r.z1(), q.z1() - 2) + 1;
            if (w <= 0 || d <= 0) return 0;
            return (double) w * d / ((r.x2() - r.x1() + 1) * (r.z2() - r.z1() + 1));
        }

        /** Full blocks that are furniture rather than walls (they count in a room even though they are opaque). */
        private static boolean isFurnitureBlock(BlockSpec s) {
            String n = s.name();
            return n.equals("bookshelf") || n.equals("chiseled_bookshelf") || n.equals("barrel") || n.equals("crafting_table")
                    || n.endsWith("furnace") || n.equals("smoker") || n.equals("hay_block") || n.equals("loom")
                    || n.equals("cartography_table") || n.equals("fletching_table") || n.equals("smithing_table")
                    || n.equals("jukebox") || n.equals("note_block") || n.equals("beehive") || n.equals("melon")
                    || n.equals("pumpkin") || n.equals("target");
        }

        private void checkLighting() {
            light = new int[n];
            IntQueue q = new IntQueue(1 << 12);
            for (int i = 0; i < n; i++) {
                if (emit[i] > 0) {
                    light[i] = emit[i];
                    q.add(i);
                }
            }
            while (!q.isEmpty()) {
                int c = q.poll();
                int lv = light[c];
                if (lv <= 1) continue;
                int y = g.yOf(c), z = g.zOf(c), x = g.xOf(c);
                for (int d = 0; d < 6; d++) {
                    int nx = x + DX[d], ny = y + DY[d], nz = z + DZ[d];
                    if (!g.contains(nx, ny, nz)) continue;
                    int j = g.index(nx, ny, nz);
                    if (occ[j] != 0) continue; // opaque blocks block light
                    if (light[j] < lv - 1) {
                        light[j] = lv - 1;
                        q.add(j);
                    }
                }
            }

            int walkable = 0, dark = 0, sum = 0, min = 15, pockets = 0;
            List<int[]> darkSamples = new ArrayList<>(), pocketSamples = new ArrayList<>();
            byte[] walk = new byte[n];
            for (int i = 0; i < n; i++) {
                if (interiorMask[i] == 0) continue;
                int y = g.yOf(i), z = g.zOf(i), x = g.xOf(i);
                if (!openAt(x, y + 1, z)) continue;      // need headroom
                if (!floorSolidAt(x, y - 1, z)) continue; // need a standable floor
                walk[i] = 1;
            }
            // a pocket (an arrow-slit niche, a gap behind a stair) is walkable floor of under 4 cells joined on its level
            byte[] pocket = new byte[n];
            IntQueue pq = new IntQueue(64);
            List<Integer> comp = new ArrayList<>();
            byte[] seenW = new byte[n];
            for (int i = 0; i < n; i++) {
                if (walk[i] == 0 || seenW[i] != 0) continue;
                comp.clear();
                seenW[i] = 1;
                pq.add(i);
                while (!pq.isEmpty()) {
                    int c = pq.poll();
                    comp.add(c);
                    int cy = g.yOf(c), cz = g.zOf(c), cx = g.xOf(c);
                    for (int d = 2; d < 6; d++) {
                        int nx = cx + DX[d], nz = cz + DZ[d];
                        if (!g.contains(nx, cy, nz)) continue;
                        int j = g.index(nx, cy, nz);
                        if (walk[j] == 0 || seenW[j] != 0) continue;
                        seenW[j] = 1;
                        pq.add(j);
                    }
                }
                if (comp.size() < 4) for (int c : comp) pocket[c] = 1;
            }
            for (int i = 0; i < n; i++) {
                if (walk[i] == 0) continue;
                int y = g.yOf(i), z = g.zOf(i), x = g.xOf(i);
                walkable++;
                sum += light[i];
                if (light[i] < min) min = light[i];
                if (light[i] == 0 && pocket[i] != 0) {
                    pockets++;
                    addSample(pocketSamples, x, y, z);
                } else if (light[i] == 0) {
                    dark++;
                    addSample(darkSamples, x, y, z);
                    mark("dark", x, y, z);
                }
            }
            if (pockets > 0 && opts.expectInterior) {
                issues.add(Issue.of(Severity.MINOR, "dark_pockets", pockets + " dark cells in niches of under 4 floor cells"
                        + " (arrow slits, gaps behind stairs); a torch nearby would light them", pocketSamples));
            }
            darkSpots = dark;
            double avg = walkable == 0 ? 0.0 : round1((double) sum / walkable);
            metrics.put("walkableInterior", walkable);
            metrics.put("avgInteriorLight", avg);
            metrics.put("minInteriorLight", walkable == 0 ? 0 : min);
            metrics.put("darkSpots", dark);
            if (dark > 0 && opts.expectInterior) {
                issues.add(Issue.of(Severity.MAJOR, "dark_interior",
                        dark + " of " + walkable + " walkable interior cells have block light 0"
                                + " (hostile mobs can spawn); add torches/lanterns", darkSamples));
            }
        }

        // ---------------------------------------------------------------------------------------------- (g) doors, beds

        private void checkDoorsAndBeds() {
            int upperMissing = 0;
            List<int[]> blockedSamples = new ArrayList<>();
            List<int[]> blockedDoors = new ArrayList<>();
            int blocked = 0;
            int bedIssues = 0;
            int orphanUpper = 0;
            List<int[]> bedSamples = new ArrayList<>();
            List<int[]> orphanSamples = new ArrayList<>();

            for (int i = 0; i < n; i++) {
                if (present[i] == 0) continue;
                BlockSpec s = g.at(i);
                String name = s.name();
                int y = g.yOf(i), z = g.zOf(i), x = g.xOf(i);

                if (name.endsWith("_door")) {
                    String half = propOfSpec(s, "half", "lower");
                    if (!"lower".equals(half)) {
                        // an upper half without a lower half below it is just as broken
                        BlockSpec below = g.raw(x, y - 1, z);
                        if (below == null || !below.name().endsWith("_door")
                                || !"lower".equals(propOfSpec(below, "half", "lower"))) {
                            orphanUpper++;
                            addSample(orphanSamples, x, y, z);
                        }
                        continue;
                    }
                    doors++;
                    BlockSpec above = g.raw(x, y + 1, z);
                    if (above == null || !above.name().endsWith("_door")
                            || !"upper".equals(propOfSpec(above, "half", "lower"))) {
                        upperMissing++;
                        addSample(orphanSamples, x, y, z);
                    }
                    Dir d = facing(s, "facing");
                    if (d == null || !d.isHorizontal()) d = Dir.NORTH;
                    boolean doorBlocked = false;
                    for (int sign = -1; sign <= 1; sign += 2) {
                        int fx = x + d.dx * sign, fz = z + d.dz * sign;
                        for (int dy = 0; dy <= 1; dy++) {
                            if (!passableAt(fx, y + dy, fz)) {
                                doorBlocked = true;
                                mark("door_blocked", fx, y + dy, fz);
                                if (blockedSamples.size() < Issue.MAX_SAMPLES) {
                                    addSample(blockedSamples, fx, y + dy, fz);
                                }
                            }
                        }
                    }
                    if (doorBlocked) {
                        blocked++;
                        addSample(blockedDoors, x, y, z);
                    }
                }

                if (name.endsWith("_bed")) {
                    String part = propOfSpec(s, "part", "foot");
                    if ("head".equals(part)) {
                        Dir d = facing(s, "facing");
                        if (d == null || !d.isHorizontal()) d = Dir.NORTH;
                        int fx = x - d.dx, fz = z - d.dz;
                        BlockSpec foot = g.raw(fx, y, fz);
                        if (foot == null || !foot.name().endsWith("_bed")) {
                            bedIssues++;
                            addSample(bedSamples, x, y, z);
                        }
                    } else if ("foot".equals(part)) {
                        Dir d = facing(s, "facing");
                        if (d == null || !d.isHorizontal()) d = Dir.NORTH;
                        int hx = x + d.dx, hz = z + d.dz;
                        BlockSpec head = g.raw(hx, y, hz);
                        if (head == null || !head.name().endsWith("_bed")) {
                            bedIssues++;
                            addSample(bedSamples, x, y, z);
                        }
                    }
                }
            }

            metrics.put("doors", doors);
            metrics.put("windows", windows);
            metrics.put("blockedDoors", blocked);
            if (blocked > 0) {
                issues.add(Issue.of(Severity.MAJOR, "door_blocked",
                        blocked + " door" + (blocked == 1 ? "" : "s")
                                + " cannot be walked through: the cells in front/behind at both halves must be"
                                + " passable (air or any non-solid block)", blockedSamples));
            }
            int incomplete = upperMissing + orphanUpper;
            if (incomplete > 0) {
                String detail;
                if (upperMissing > 0 && orphanUpper > 0) {
                    detail = upperMissing + " door lower half" + (upperMissing == 1 ? "" : "s")
                            + " without an upper half and " + orphanUpper + " orphan upper half"
                            + (orphanUpper == 1 ? "" : "s");
                } else if (upperMissing > 0) {
                    detail = upperMissing + " door lower half" + (upperMissing == 1 ? "" : "s")
                            + " without a matching upper half";
                } else {
                    detail = orphanUpper + " door upper half" + (orphanUpper == 1 ? "" : "s")
                            + " without a lower half below";
                }
                issues.add(Issue.of(Severity.MINOR, "door_incomplete",
                        detail + "; a door needs a lower and an upper block in the same column", orphanSamples));
            }
            if (opts.expectInterior && doors == 0) {
                issues.add(Issue.of(Severity.MAJOR, "no_door",
                        "no door found on the build; the entrance should face SOUTH (+z)"));
            }
            if (bedIssues > 0) {
                issues.add(Issue.of(Severity.MINOR, "bed_incomplete",
                        bedIssues + " bed half" + (bedIssues == 1 ? "" : "s")
                                + " has no matching other half; a bed needs a head and a foot", bedSamples));
            }
        }

        private String propOfSpec(BlockSpec s, String key, String def) {
            String v = null;
            try {
                v = info.getProp(s, key);
            } catch (RuntimeException ignored) {
                // fall through
            }
            if (v == null) v = s.prop(key);
            return v == null ? def : v;
        }

        // ---------------------------------------------------------------------------------------------- (h) vertical access

        private void checkVerticalAccess() {
            int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
            int walkable = 0;
            for (int i = 0; i < n; i++) {
                if (interiorMask[i] == 0) continue;
                int y = g.yOf(i), z = g.zOf(i), x = g.xOf(i);
                if (!openAt(x, y + 1, z) || !floorSolidAt(x, y - 1, z)) continue;
                walkable++;
                if (y < lo) lo = y;
                if (y > hi) hi = y;
            }
            if (walkable == 0) {
                metrics.put("floors", 0);
                metrics.put("verticalAccess", "na");
                return;
            }
            int spans = hi - lo;
            metrics.put("floors", countFloorLevels());
            metrics.put("interiorYMin", lo);
            metrics.put("interiorYMax", hi);
            if (spans < 3) {
                metrics.put("verticalAccess", "na");
                return;
            }
            // a ladder hanging on the inside wall of a room is "adjacent" to the interior air next to it, but a
            // staircase of stairs occupies interior cells directly, so both cases look at the 6-neighbourhood.
            boolean access = false;
            outer:
            for (int i = 0; i < n; i++) {
                if (present[i] == 0) continue;
                String name = g.at(i).name();
                boolean helper = name.contains("ladder") || name.contains("scaffolding") || name.contains("vine")
                        || name.endsWith("_stairs");
                if (!helper) continue;
                int hy = g.yOf(i);
                if (hy < lo - 1 || hy > hi + 1) continue; // roof stairs outside the rooms do not count
                if (interiorMask[i] != 0) {
                    access = true;
                    break;
                }
                int y = g.yOf(i), z = g.zOf(i), x = g.xOf(i);
                for (int d = 0; d < 6; d++) {
                    int nx = x + DX[d], ny = y + DY[d], nz = z + DZ[d];
                    if (!g.contains(nx, ny, nz)) continue;
                    if (interiorMask[g.index(nx, ny, nz)] != 0) {
                        access = true;
                        break outer;
                    }
                }
            }
            metrics.put("verticalAccess", access);
            if (!access && opts.expectInterior) {
                issues.add(Issue.of(Severity.MAJOR, "no_vertical_access",
                        "the interior is " + (spans + 1) + " blocks tall (walkable y" + lo + ".." + hi
                                + ") but there is no ladder, scaffolding, vines or stairs to change level"));
            }
        }

        /** Walkable interior y levels merged into storeys: levels less than 3 blocks apart are one storey. */
        private int countFloorLevels() {
            boolean[] walkableY = new boolean[sy + 1];
            boolean any = false;
            for (int i = 0; i < n; i++) {
                if (interiorMask[i] == 0) continue;
                int y = g.yOf(i), z = g.zOf(i), x = g.xOf(i);
                if (!openAt(x, y + 1, z) || !floorSolidAt(x, y - 1, z)) continue;
                int idx = y - minY;
                if (idx < 0 || idx >= walkableY.length) continue;
                walkableY[idx] = true;
                any = true;
            }
            if (!any) return 0;
            int floors = 0;
            int lastY = Integer.MIN_VALUE;
            for (int idx = 0; idx < walkableY.length; idx++) {
                if (!walkableY[idx]) continue;
                int y = minY + idx;
                if (lastY == Integer.MIN_VALUE || y - lastY >= 3) floors++;
                lastY = y;
            }
            return Math.max(1, floors);
        }

        // ---------------------------------------------------------------------------------------------- (i) liquids

        private void checkLiquids() {
            int count = 0;
            List<int[]> samples = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (liquid[i] == 0) continue;
                int y = g.yOf(i), z = g.zOf(i), x = g.xOf(i);
                // unset cells below y=0 are solid terrain: they seal a pool exactly like a block does
                boolean leak = !effNonAir(x, y, z + 1) || !effNonAir(x, y, z - 1)
                        || !effNonAir(x + 1, y, z) || !effNonAir(x - 1, y, z)
                        || !effNonAir(x, y - 1, z);
                if (leak) {
                    count++;
                    addSample(samples, x, y, z);
                }
            }
            metrics.put("liquidLeaks", count);
            if (count > 0) {
                issues.add(Issue.of(Severity.MINOR, "liquid_may_flow",
                        count + " water/lava cell" + (count == 1 ? "" : "s")
                                + " touches air and will flow; enclose it with solid blocks", samples));
            }
        }

        // ---------------------------------------------------------------------------------------------- (j) facades

        private void checkFacades() {
            facade("south", true, false);
            facade("north", false, false);
            facade("east", true, true);
            facade("west", false, true);
        }

        /**
         * @param side    metric suffix
         * @param reverse iterate x (or z) from the far side, so the first non-air cell is the outermost one
         * @param alongX  true = rays run along x (east/west views), false = along z (north/south views)
         */
        private void facade(String side, boolean reverse, boolean alongX) {
            Map<String, Integer> counts = new HashMap<>();
            int visible = 0;
            int a0 = alongX ? minZ : minX;
            int a1 = alongX ? maxZ : maxX;   // the row/column coordinate
            int b0 = alongX ? minX : minZ;   // the ray coordinate
            int b1 = alongX ? maxX : maxZ;
            int rays = b1 - b0 + 1;
            // visible[k] = number of (row,y) cells whose first visible block sits k blocks inside the shell
            int[] depthCounts = new int[Math.max(1, rays)];
            // per row (the perpendicular axis): depth of the outermost visible block, i.e. the facade plane
            int[] firstDepth = new int[Math.max(1, a1 - a0 + 1)];
            java.util.Arrays.fill(firstDepth, Integer.MAX_VALUE);
            for (int a = a0; a <= a1; a++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int k = 0; k < rays; k++) {
                        int b = reverse ? b1 - k : b0 + k;
                        int x = alongX ? b : a;
                        int z = alongX ? a : b;
                        if (!g.contains(x, y, z)) continue;
                        if (present[g.index(x, y, z)] == 0) continue;
                        visible++;
                        int row = a - a0;
                        if (k < firstDepth[row]) firstDepth[row] = k;
                        depthCounts[k]++;
                        counts.merge(g.raw(x, y, z).name(), 1, Integer::sum);
                        break;
                    }
                }
            }
            String dominant = "-";
            int domCount = 0;
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                if (e.getValue() > domCount || (e.getValue() == domCount && e.getKey().compareTo(dominant) < 0)) {
                    dominant = e.getKey();
                    domCount = e.getValue();
                }
            }
            double share = visible == 0 ? 0.0 : round2((double) domCount / visible);

            // Depth is measured relative to the facade plane (the outermost visible block of each row), so a flat
            // wall always reports depth=1 / relief=0 no matter how much empty space surrounds the model.
            int minFirst = Integer.MAX_VALUE;
            for (int v : firstDepth) if (v < minFirst) minFirst = v;
            if (minFirst == Integer.MAX_VALUE) minFirst = 0;
            int layers = 0;
            long depthSum = 0;
            for (int k = 0; k < depthCounts.length; k++) {
                if (depthCounts[k] == 0) continue;
                layers++;
                depthSum += (long) Math.max(0, k - minFirst) * depthCounts[k];
            }
            double relief = visible == 0 ? 0.0 : round2((double) depthSum / visible);

            metrics.put("facade_" + side + "_visible", visible);
            metrics.put("facade_" + side + "_distinct", counts.size());
            metrics.put("facade_" + side + "_dominant", dominant);
            metrics.put("facade_" + side + "_dominantShare", share);
            metrics.put("facade_" + side + "_depth", Math.max(1, layers));
            metrics.put("facade_" + side + "_relief", relief);

            if (visible > FACADE_MIN_CELLS && share > MONOTONY_SHARE) {
                issues.add(Issue.of(Severity.MINOR, "monotonous_facade_" + side,
                        Math.round(share * 100) + "% of the " + side + " facade is " + dominant
                                + "; descriptive palette concentration, not automatically a design defect."
                                + " Check coherence and intent before changing materials"));
            }
            if (visible > FACADE_MIN_CELLS && relief < 0.5) {
                issues.add(Issue.of(Severity.MINOR, "flat_facade_" + side,
                        "the " + side + " facade is flat (" + visible + " visible blocks, average recess "
                                + fmtDouble(relief) + " blocks); assess against the requested style; intentional plain surfaces can be valid"));
            }
        }

        private static String fmtDouble(double v) {
            if (v == Math.rint(v)) return Integer.toString((int) v);
            return String.format(java.util.Locale.ROOT, "%.1f", v);
        }

        // ---------------------------------------------------------------------------------------------- (k) roof overhang

        private void checkRoofOverhang() {
            int[] fp = wallFootprint();
            if (fp == null) {
                metrics.put("roofOverhang", 0);
                return;
            }
            // overhang = roof-like blocks (stairs/slabs) above the ground floor that stick out 1..3 blocks
            // beyond the wall footprint; garden props outside the body are ignored
            int over = 0;
            for (int i = 0; i < n; i++) {
                if (present[i] == 0) continue;
                int y = g.yOf(i);
                if (y < 3) continue;
                BlockSpec s = g.at(i);
                String nm = s.name();
                if (!(nm.endsWith("_stairs") || nm.endsWith("_slab"))) continue;
                int x = g.xOf(i), z = g.zOf(i);
                int d = Math.max(Math.max(fp[0] - x, x - fp[1]), Math.max(fp[2] - z, z - fp[3]));
                if (d <= 3 && d > over) over = d;
            }
            metrics.put("roofOverhang", over);
            if (over == 0 && opts.expectInterior) {
                issues.add(Issue.of(Severity.INFO, "no_roof_overhang",
                        "nothing extends beyond the walls at y>=2: the roof has no overhang; a 1-block overhang"
                                + " adds depth and shelters the facade"));
            }
        }

        // ---------------------------------------------------------------------------------------------- (l) detail

        private int orientedBlocks;
        private int decorativeBlocks;

        private void finishDetailMetrics() {
            for (int i = 0; i < n; i++) {
                if (present[i] == 0) continue;
                BlockSpec s = g.at(i);
                boolean oriented = false;
                for (String p : Names.ORIENTED_PROPS) {
                    if (s.hasProp(p)) {
                        oriented = true;
                        break;
                    }
                }
                if (oriented) orientedBlocks++;
                if (Names.isDetailish(s)) decorativeBlocks++;
            }
            double orientedShare = nonAir == 0 ? 0 : (double) orientedBlocks / nonAir;
            double decorativeShare = nonAir == 0 ? 0 : (double) decorativeBlocks / nonAir;
            int avgDepth = 0;
            int nSides = 0;
            for (String side : new String[]{"south", "north", "east", "west"}) {
                Object v = metrics.get("facade_" + side + "_depth");
                if (v instanceof Number num) {
                    avgDepth += num.intValue();
                    nSides++;
                }
            }
            avgDepth = nSides == 0 ? 0 : Math.round((float) avgDepth / nSides);

            // Documented heuristic, max 100:
            //   base 10 (something was built)
            // + min(18, distinctBlocks * 2)            palette variety
            // + min(22, round(decorativeShare * 120))  props/detail density (18% saturates)
            // + min(12, round(orientedShare * 40))     oriented states (30% saturates)
            // + min(12, avgDepth * 6)                  facade depth
            // + min(14, rooms * 7)                     spatial richness
            // + min(12, lightSources)                  lighting fixtures
            int depthBoost = Math.min(12, Math.max(0, avgDepth) * 6);
            int score = 10
                    + Math.min(18, nameCounts.size() * 2)
                    + Math.min(22, (int) Math.round(decorativeShare * 120))
                    + Math.min(12, (int) Math.round(orientedShare * 40))
                    + depthBoost
                    + Math.min(14, rooms * 7)
                    + Math.min(12, lightSources);
            score = Math.max(0, Math.min(100, score));

            metrics.put("detailScore", score);
            metrics.put("orientedBlocks", orientedBlocks);
            metrics.put("decorativeBlocks", decorativeBlocks);
            metrics.put("orientedPct", round1(orientedShare * 100));
            metrics.put("decorativePct", round1(decorativeShare * 100));
            metrics.put("avgFacadeDepth", avgDepth);
        }

        private void checkFootprint() {
            int[] fp = wallFootprint();
            if (fp != null) {
                metrics.put("footprintX", fp[1] - fp[0] + 1);
                metrics.put("footprintZ", fp[3] - fp[2] + 1);
            }
            if (opts.expectedFootprint == null || opts.expectedFootprint.length < 2) return;
            int ex = opts.expectedFootprint[0], ez = opts.expectedFootprint[1];
            if (ex <= 0 || ez <= 0) return;
            if (fp == null) {
                issues.add(Issue.of(Severity.MAJOR, "footprint_mismatch",
                        "expected a " + ex + "x" + ez + " footprint but no wall layer was found at y=1"));
                return;
            }
            int ax = fp[1] - fp[0] + 1, az = fp[3] - fp[2] + 1;
            if (Math.abs(ax - ex) > 1 || Math.abs(az - ez) > 1) {
                issues.add(Issue.of(Severity.MAJOR, "footprint_mismatch",
                        "expected footprint " + ex + "x" + ez + " (x by z) but the main body walls measure "
                                + ax + "x" + az + " at x" + fp[0] + ".." + fp[1] + " z" + fp[2] + ".." + fp[3]));
            }
        }

        /**
         * Size of the subject rather than the scene: the main-body footprint in x and z, and in y the cells above the
         * ground layer inside that footprint. A castle on a landscaped plot measures the castle. Falls back to the
         * whole model when no wall footprint is found.
         */
        private int[] subjectSize(int[] whole) {
            int[] fp = wallFootprint();
            if (fp == null) return whole;
            int top = Integer.MIN_VALUE;
            for (int x = fp[0]; x <= fp[1]; x++) for (int z = fp[2]; z <= fp[3]; z++) for (int y = maxY; y >= 1; y--) {
                BlockSpec s = g.raw(x, y, z);
                if (s != null && !s.isAir()) {
                    top = Math.max(top, y);
                    break;
                }
            }
            return new int[]{fp[1] - fp[0] + 1, top == Integer.MIN_VALUE ? whole[1] : top, fp[3] - fp[2] + 1};
        }

        private static int sizeTolerance(int expected) {
            return sizeToleranceFor(expected);
        }

        private void checkOverallSize() {
            int[] actual = {nonAirBounds[3] - nonAirBounds[0] + 1,
                    nonAirBounds[4] - nonAirBounds[1] + 1, nonAirBounds[5] - nonAirBounds[2] + 1};
            String[] axes = {"x", "y", "z"};
            int[] subject = subjectSize(actual);
            for (int i = 0; i < 3; i++) {
                if (opts.maximumSize != null && opts.maximumSize.length > i && opts.maximumSize[i] > 0
                        && actual[i] > opts.maximumSize[i]) {
                    issues.add(Issue.of(Severity.MAJOR, "size_limit_exceeded",
                            "overall solid " + axes[i] + " size " + actual[i] + " exceeds maximum " + opts.maximumSize[i]));
                }
                // the size asked for may be the building's (a castle in its grounds) or the whole scene's (a garden
                // with a tree and a pond, where the "main body" found is a lantern): either one matching is a match
                if (opts.exactSize != null && opts.exactSize.length > i && opts.exactSize[i] > 0
                        && Math.abs(subject[i] - opts.exactSize[i]) > sizeTolerance(opts.exactSize[i])
                        && Math.abs(actual[i] - opts.exactSize[i]) > sizeTolerance(opts.exactSize[i])) {
                    issues.add(Issue.of(Severity.MAJOR, "overall_size_mismatch",
                            subject[i] == actual[i]
                                    ? "the build's " + axes[i] + " size is " + actual[i] + " but should be about " + opts.exactSize[i]
                                    : "the " + axes[i] + " size should be about " + opts.exactSize[i] + ", but the main building measures "
                                    + subject[i] + " and the whole build " + actual[i]));
                }
            }
        }

        // ---------------------------------------------------------------------------------------------- metrics

        private void finishMetrics() {
            metrics.put("nonAir", nonAir);
            metrics.put("distinctBlocks", nameCounts.size());
            metrics.put("distinctStates", stateCounts.size());
            metrics.put("lightSources", lightSources);
            metrics.put("expectedInterior", opts.expectInterior);
            // stable, readable order: headline -> structure -> lighting -> detail -> facades
            reorderMetrics();
        }

        private void reorderMetrics() {
            String[] head = {"footprintX", "footprintZ", "interiorVolume", "rooms", "biggestRoom", "floors",
                    "verticalAccess", "doors", "windows", "lightSources", "darkSpots", "avgInteriorLight",
                    "minInteriorLight", "walkableInterior", "skyExposedInterior"};
            String[] tail = {"detailScore", "distinctBlocks", "distinctStates", "decorativeBlocks", "orientedBlocks",
                    "decorativePct", "orientedPct", "avgFacadeDepth", "roofOverhang", "floatingBlocks",
                    "floatingComponents", "unsupportedGravity", "unsupportedAttachments", "liquidLeaks",
                    "interiorYMin", "interiorYMax", "nonAir", "expectedInterior"};
            LinkedHashMap<String, Object> ordered = new LinkedHashMap<>();
            for (String k : head) if (metrics.containsKey(k)) ordered.put(k, metrics.get(k));
            for (Map.Entry<String, Object> e : metrics.entrySet()) {
                if (ordered.containsKey(e.getKey())) continue;
                if (e.getKey().startsWith("facade_")) continue;
                if (contains(tail, e.getKey())) continue;
                ordered.put(e.getKey(), e.getValue());
            }
            for (String k : tail) if (metrics.containsKey(k)) ordered.put(k, metrics.get(k));
            for (String side : new String[]{"south", "north", "east", "west"}) {
                for (String m : new String[]{"visible", "distinct", "dominant", "dominantShare", "depth", "relief"}) {
                    String k = "facade_" + side + "_" + m;
                    if (metrics.containsKey(k)) ordered.put(k, metrics.get(k));
                }
            }
            metrics.clear();
            metrics.putAll(ordered);
        }

        private static boolean contains(String[] arr, String k) {
            for (String s : arr) if (s.equals(k)) return true;
            return false;
        }

        // ---------------------------------------------------------------------------------------------- helpers

        /** The 18-neighbourhood (faces and edges, not lone corners), for connectivity. */
        private static final int[] NX = new int[18], NY = new int[18], NZ = new int[18];
        static {
            int i = 0;
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                int k = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                if (k == 0 || k == 3) continue;
                NX[i] = dx;
                NY[i] = dy;
                NZ[i++] = dz;
            }
        }
        private static final int[] DX = {0, 0, 0, 0, -1, 1};
        private static final int[] DY = {-1, 1, 0, 0, 0, 0};
        private static final int[] DZ = {0, 0, -1, 1, 0, 0};

        private boolean isSolid(BlockSpec s) {
            try {
                return info.isSolid(s);
            } catch (RuntimeException e) {
                return !s.isAir();
            }
        }

        private boolean isOccl(BlockSpec s) {
            try {
                return info.isOccluding(s);
            } catch (RuntimeException e) {
                return isSolid(s);
            }
        }

        private int emission(BlockSpec s) {
            try {
                int v = info.lightEmission(s);
                return Math.max(0, Math.min(15, v));
            } catch (RuntimeException e) {
                return 0;
            }
        }

        private static boolean isWindow(BlockSpec s) {
            String n = s.name();
            return n.equals("glass") || n.equals("glass_pane") || n.equals("tinted_glass")
                    || (n.endsWith("_glass") && !n.contains("glass_pane"))
                    || n.endsWith("_glass_pane") || n.equals("iron_bars");
        }

        private boolean isGravity(BlockSpec s) {
            try {
                return info.hasGravity(s);
            } catch (RuntimeException e) {
                return false;
            }
        }

        private boolean isLiquid(BlockSpec s) {
            try {
                return info.isLiquid(s);
            } catch (RuntimeException e) {
                return Names.isLiquidName(s.name());
            }
        }

        private boolean presentAt(int x, int y, int z) {
            BlockSpec s = g.raw(x, y, z);
            return s != null && !s.isAir();
        }

        /** Effective non-air: present block or unset terrain below y=0. */
        private boolean effNonAir(int x, int y, int z) {
            BlockSpec s = g.raw(x, y, z);
            if (s == null) return y <= 0;
            return !s.isAir();
        }

        private boolean openAt(int x, int y, int z) {
            BlockSpec s = g.raw(x, y, z);
            if (s != null) return s.isAir();
            return y >= 1;
        }

        private boolean solidAt(int x, int y, int z) {
            BlockSpec s = g.raw(x, y, z);
            if (s == null) return y <= 0;
            if (s.isAir()) return false;
            return isSolid(s);
        }

        /** True when the block at (x,y,z) can be stood on (a carpet counts as its base block). */
        private boolean floorSolidAt(int x, int y, int z) {
            BlockSpec s = g.raw(x, y, z);
            if (s == null) return y <= 0;
            if (s.isAir()) return false;
            if (Names.isCarpet(s)) {
                BlockSpec b = g.raw(x, y - 1, z);
                if (b == null) return y - 1 <= 0;
                if (b.isAir()) return false;
                return !Names.isCarpet(b) && isSolid(b);
            }
            return isSolid(s);
        }

        private boolean passableAt(int x, int y, int z) {
            BlockSpec s = g.raw(x, y, z);
            if (s == null) return y >= 1;
            if (s.isAir()) return true;
            return !isSolid(s);
        }

        private static void addSample(List<int[]> samples, int x, int y, int z) {
            if (samples.size() >= Issue.MAX_SAMPLES) return;
            samples.add(new int[]{x, y, z});
        }

        private static double round1(double v) {
            return Math.round(v * 10.0) / 10.0;
        }

        private static double round2(double v) {
            return Math.round(v * 100.0) / 100.0;
        }

        /** Cached physical properties of one block state. */
        private record StateProps(boolean solid, boolean occluding, int emission, boolean liquid,
                                  boolean gravity) {
        }

        /** Floating component accumulator. */
        private static final class Comp {
            int count;
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            final List<int[]> samples = new ArrayList<>(Issue.MAX_SAMPLES);

            void add(int i, Grid g) {
                int x = g.xOf(i), y = g.yOf(i), z = g.zOf(i);
                count++;
                if (x < minX) minX = x;
                if (y < minY) minY = y;
                if (z < minZ) minZ = z;
                if (x > maxX) maxX = x;
                if (y > maxY) maxY = y;
                if (z > maxZ) maxZ = z;
                if (samples.size() < Issue.MAX_SAMPLES) samples.add(new int[]{x, y, z});
            }
        }
    }

    // =====================================================================================================
    // Growable int FIFO used by all flood fills (avoids boxing).
    // =====================================================================================================
    private static final class IntQueue {
        private int[] a;
        private int head, tail;

        IntQueue(int cap) {
            a = new int[Math.max(16, cap)];
        }

        void add(int v) {
            if (tail == a.length) {
                if (head > 0) {
                    System.arraycopy(a, head, a, 0, tail - head);
                    tail -= head;
                    head = 0;
                }
                if (tail == a.length) a = java.util.Arrays.copyOf(a, a.length + (a.length >> 1) + 16);
            }
            a[tail++] = v;
        }

        boolean isEmpty() {
            return head == tail;
        }

        int poll() {
            return a[head++];
        }
    }
}
