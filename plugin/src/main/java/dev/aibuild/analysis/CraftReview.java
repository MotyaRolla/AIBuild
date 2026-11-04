package dev.aibuild.analysis;

import dev.aibuild.model.BlockInfo;
import dev.aibuild.model.BlockSpec;
import dev.aibuild.model.VoxelModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The red pen: what a professional builder sees at a glance, measured from the blocks and located, so the model
 * can find it in the image and in its script. The structure analyzer checks that a build works (support, light,
 * doors); this checks how it reads: pieces hanging in the air, walls with no depth, roofs with no eave, single
 * blocks of a contrasting colour scattered like confetti.
 *
 * <p>Models are poor at noticing these in a render on their own (a critic stage kept 0 of 6 revisions, and a hay
 * beam floated above a roof through 21 model calls), but fix them reliably once they are pointed at with
 * coordinates. Every mark is advice: nothing is enforced.</p>
 */
public final class CraftReview {
    private CraftReview() {
    }

    /**
     * One note. {@code box} is x1,y1,z1,x2,y2,z2 (inclusive); {@code normal} the direction the marked surface faces
     * (null when it has none, e.g. a floating piece), so a renderer draws it only where it can be seen.
     */
    public record Mark(int id, String kind, String note, int[] box, int[] normal) {
    }

    /** The notes, a few whole-build measures, and the text the model reads. */
    public record Result(List<Mark> marks, Map<String, Object> metrics) {
        public static final Result EMPTY = new Result(List.of(), Map.of());

        public String toText() {
            if (marks.isEmpty() && metrics.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            sb.append("RED PEN (a professional builder's notes, measured from the blocks; the numbers match the red marks on the"
                    + " red-pen image; advice, not rules):\n");
            if (marks.isEmpty()) sb.append("- nothing to mark.\n");
            for (Mark m : marks) sb.append(m.id()).append(". ").append(m.note()).append('\n');
            if (!metrics.isEmpty()) {
                sb.append("Measures:");
                metrics.forEach((k, v) -> sb.append(' ').append(k).append(' ').append(v).append(';'));
                sb.setLength(sb.length() - 1);
                sb.append('\n');
            }
            return sb.toString();
        }

        /** Number of marks of a kind. */
        public int count(String kind) {
            return (int) marks.stream().filter(m -> m.kind().equals(kind)).count();
        }
    }

    /** Which part owns a cell; null when unknown. */
    @FunctionalInterface
    public interface Owners {
        String at(int x, int y, int z);
    }

    static final int MAX_FLOATING = 3, MAX_FLAT = 3, MAX_EAVES = 2, MAX_SPECKLE = 2;
    /** Exterior air is flood-filled only below this many cells of bounds; above it every face counts as outside. */
    private static final long MAX_FLOOD = 24_000_000L;
    private static final int[][] DIRS = {{0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
    private static final String[] FACING = {"north", "south", "west", "east"};
    private static final Set<String> SOIL = Set.of("grass_block", "dirt", "coarse_dirt", "rooted_dirt", "podzol", "mycelium",
            "moss_block", "sand", "red_sand", "gravel", "clay", "mud", "dirt_path", "farmland", "snow_block", "water", "lava",
            "ice", "packed_ice", "blue_ice", "powder_snow");

    public static Result review(VoxelModel model, BlockInfo info, Owners owners) {
        int[] b = model.solidBounds();
        if (b == null) return Result.EMPTY;
        List<Mark> out = new ArrayList<>();
        Map<String, Object> metrics = new LinkedHashMap<>();
        Owners own = owners == null ? (x, y, z) -> null : owners;
        floating(model, info, own, out);
        Facades f = facades(model, info, b, own, out);
        if (f.total > 0) metrics.put("exterior wall area without depth", Math.round(100.0 * f.flatArea / f.total) + "%");
        int contrast = speckle(model, info, b, out);
        detail(model, info, metrics);
        metrics.put("contrasting neighbour pairs on top surfaces", contrast + "%");
        List<Mark> numbered = new ArrayList<>();
        for (Mark m : out) numbered.add(new Mark(numbered.size() + 1, m.kind(), m.note(), m.box(), m.normal()));
        return new Result(List.copyOf(numbered), metrics);
    }

    // ------------------------------------------------------------------ floating pieces

    private static final int[][] AROUND;

    static {
        List<int[]> a = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            if (dx != 0 || dy != 0 || dz != 0) a.add(new int[]{dx, dy, dz});
        }
        AROUND = a.toArray(new int[0][]);
    }

    private static void floating(VoxelModel model, BlockInfo info, Owners own, List<Mark> out) {
        Map<Long, BlockSpec> cells = new HashMap<>();
        model.forEach((x, y, z, s) -> {
            if (s != null && !s.isAir()) cells.put(VoxelModel.key(x, y, z), s);
        });
        Set<Long> seen = new java.util.HashSet<>();
        List<List<Long>> comps = new ArrayList<>();
        long[] stack = new long[64];
        for (Long start : cells.keySet()) {
            if (seen.contains(start)) continue;
            List<Long> comp = new ArrayList<>();
            boolean grounded = false, solid = false;
            int sp = 0;
            stack[sp++] = start;
            seen.add(start);
            while (sp > 0) {
                long k = stack[--sp];
                comp.add(k);
                int x = VoxelModel.kx(k), y = VoxelModel.ky(k), z = VoxelModel.kz(k);
                if (y <= 0 || (y == 1 && model.get(x, 0, z) == null)) grounded = true;
                if (info.isSolid(cells.get(k))) solid = true;
                // edge and corner contact counts: a cone of stairs set diagonally on a tower top does not float
                for (int[] d : AROUND) {
                    long q = VoxelModel.key(x + d[0], y + d[1], z + d[2]);
                    if (cells.containsKey(q) && seen.add(q)) {
                        if (sp == stack.length) stack = java.util.Arrays.copyOf(stack, sp * 2);
                        stack[sp++] = q;
                    }
                }
            }
            // a stray flower or tuft is not worth a mark; anything solid is
            if (!grounded && (solid || comp.size() > 3)) comps.add(comp);
        }
        comps.sort(Comparator.comparingInt((List<Long> c) -> -c.size()));
        int total = comps.stream().mapToInt(List::size).sum();
        for (int i = 0; i < Math.min(MAX_FLOATING, comps.size()); i++) {
            List<Long> c = comps.get(i);
            int[] box = boxOf(c);
            Map<String, Integer> mats = new HashMap<>();
            for (long k : c) mats.merge(base(cells.get(k)), 1, Integer::sum);
            String part = majority(c, own);
            String more = i == 0 && comps.size() > MAX_FLOATING ? " (" + comps.size() + " floating pieces, " + total + " blocks in all)" : "";
            out.add(new Mark(0, "floating", "floating: " + c.size() + " block" + (c.size() == 1 ? "" : "s") + " (" + top(mats, 3) + ") at "
                    + where(box) + part(part) + " touch nothing that reaches the ground; connect it (a post, bracket, chain) or remove it"
                    + more + ".", box, null));
        }
    }

    // ------------------------------------------------------------------ walls and eaves

    private static final java.util.regex.Pattern NATURAL = java.util.regex.Pattern.compile(
            "(?i)(terrain|ground|hill|cliff|rock|stone_?field|mountain|lake|pond|river|shore|beach|island|landscape|grass|moss)");
    private static final java.util.regex.Pattern BRIDGE = java.util.regex.Pattern.compile("(?i)(bridge|deck|pier|dock|jetty|walkway|boardwalk)");
    private static final java.util.regex.Pattern RAIL = java.util.regex.Pattern.compile("(?i)(fence|rail|hedge|balustrade|parapet)");
    private static final java.util.regex.Pattern ORGANIC = java.util.regex.Pattern.compile("(?i)(tree|trunk|limb|branch|crown|canopy|bush|statue|creature|body|head)");

    /** A part that is ground or water: its sides are slopes and banks, not walls. */
    private static boolean natural(String part) {
        return part != null && NATURAL.matcher(part).find();
    }

    private static boolean wallLike(String part) {
        return part == null || !(BRIDGE.matcher(part).find() || RAIL.matcher(part).find() || ORGANIC.matcher(part).find());
    }

    /** What gives this kind of surface depth: a facade is not a bridge, and neither is a tree. */
    private static String depthAdvice(String part) {
        if (part != null && BRIDGE.matcher(part).find()) {
            return "Give the side of a bridge or deck depth: a beam or trim line along the deck edge (slabs or stairs set"
                    + " out by one), posts at a rhythm, an open railing instead of a solid one, an arch or brackets underneath";
        }
        if (part != null && RAIL.matcher(part).find()) {
            return "Break it up: posts at a rhythm, a cap course of slabs or walls, openings or a change of material at the base";
        }
        if (part != null && ORGANIC.matcher(part).find()) {
            return "An organic form should not have a flat face: bulge, twist or step it, and vary the outline";
        }
        return "Give it depth: a frame (pillars, beams) proud of a recessed infill, window sills and lintels, a base course";
    }

    private record Facades(int flatArea, int total) {
    }

    private static Facades facades(VoxelModel model, BlockInfo info, int[] b, Owners own, List<Mark> out) {
        Outside outside = Outside.of(model, b);
        // faces per direction and plane: key (dir, plane) -> cells (u, y)
        Map<Long, Map<Long, BlockSpec>> planes = new HashMap<>();
        model.forEach((x, y, z, s) -> {
            if (y < 1 || s == null || s.isAir() || !info.isOccluding(s) || SOIL.contains(s.name())) return;
            for (int d = 0; d < 4; d++) {
                int nx = x + DIRS[d][0], nz = z + DIRS[d][2];
                BlockSpec n = model.get(nx, y, nz);
                if (!thin(n)) continue;          // something with depth stands in front
                if (!outside.at(nx, y, nz)) continue;
                int plane = d < 2 ? z : x, u = d < 2 ? x : z;
                planes.computeIfAbsent(((long) d << 32) | (plane & 0xffffffffL), k -> new HashMap<>()).put(uy(u, y), s);
            }
        });
        record Region(int d, int plane, int u1, int u2, int y1, int y2, int area, Map<String, Integer> mats, Map<Integer, Integer> tops,
                      String part) {
        }
        List<Region> regions = new ArrayList<>();
        int total = 0;
        for (Map.Entry<Long, Map<Long, BlockSpec>> e : planes.entrySet()) {
            int d = (int) (e.getKey() >>> 32), plane = (int) (long) e.getKey();
            Map<Long, BlockSpec> cells = e.getValue();
            total += cells.size();
            Set<Long> seen = new java.util.HashSet<>();
            for (Long start : cells.keySet()) {
                if (!seen.add(start)) continue;
                List<Long> stack = new ArrayList<>(List.of(start));
                int u1 = Integer.MAX_VALUE, u2 = Integer.MIN_VALUE, y1 = Integer.MAX_VALUE, y2 = Integer.MIN_VALUE, area = 0;
                Map<String, Integer> mats = new HashMap<>();
                Map<Integer, Integer> tops = new HashMap<>();
                List<Long> members = new ArrayList<>();
                while (!stack.isEmpty()) {
                    long k = stack.removeLast();
                    int u = (int) (k >> 32), y = (int) k;
                    area++;
                    members.add(k);
                    u1 = Math.min(u1, u);
                    u2 = Math.max(u2, u);
                    y1 = Math.min(y1, y);
                    y2 = Math.max(y2, y);
                    mats.merge(base(cells.get(k)), 1, Integer::sum);
                    tops.merge(u, y, Math::max);
                    for (long q : new long[]{uy(u + 1, y), uy(u - 1, y), uy(u, y + 1), uy(u, y - 1)}) {
                        if (cells.containsKey(q) && seen.add(q)) stack.add(q);
                    }
                }
                Long sample = members.get(members.size() / 2);
                int su = (int) (sample >> 32), sy = (int) (long) sample;
                String part = d < 2 ? own.at(su, sy, plane) : own.at(plane, sy, su);
                regions.add(new Region(d, plane, u1, u2, y1, y2, area, mats, tops, part));
            }
        }
        // flat walls: a large, dense, single-plane surface with nothing in front of it
        List<Region> flat = regions.stream()
                .filter(r -> !natural(r.part))
                .filter(r -> r.u2 - r.u1 + 1 >= 7 && r.y2 - r.y1 + 1 >= 4 && r.area >= 28
                        && r.area >= 0.6 * (r.u2 - r.u1 + 1) * (r.y2 - r.y1 + 1))
                .sorted(Comparator.comparingInt((Region r) -> -r.area)).toList();
        int flatArea = flat.stream().mapToInt(Region::area).sum();
        List<Region> shownFlat = flat.subList(0, Math.min(MAX_FLAT, flat.size()));
        for (Region r : shownFlat) {
            int w = r.u2 - r.u1 + 1, h = r.y2 - r.y1 + 1;
            int[] box = r.d < 2 ? new int[]{r.u1, r.y1, r.plane, r.u2, r.y2, r.plane} : new int[]{r.plane, r.y1, r.u1, r.plane, r.y2, r.u2};
            int share = (int) Math.round(100.0 * r.mats.values().stream().mapToInt(Integer::intValue).max().orElse(0) / r.area);
            out.add(new Mark(0, "flat-wall", "flat " + (wallLike(r.part) ? "wall " : "side ") + w + "x" + h + " facing " + FACING[r.d] + ", "
                    + where(box) + part(r.part) + " (" + top(r.mats, 2) + ", the main one " + share + "%): one plane with nothing in front. "
                    + depthAdvice(r.part)
                    + (r == shownFlat.getLast() && flat.size() > shownFlat.size() ? " (" + (flat.size() - shownFlat.size()) + " more walls like it)" : "")
                    + ".", box, DIRS[r.d]));
        }
        // eaves: the wall carries something on top, and nothing reaches out over its face
        List<int[]> eaves = new ArrayList<>();
        List<Region> eaveRegions = new ArrayList<>();
        for (Region r : regions) {
            if (r.u2 - r.u1 + 1 < 5) continue;
            int roofed = 0, flush = 0;
            int ey1 = Integer.MAX_VALUE, ey2 = Integer.MIN_VALUE;
            for (Map.Entry<Integer, Integer> t : r.tops.entrySet()) {
                int u = t.getKey(), y = t.getValue();
                int wx = r.d < 2 ? u : r.plane, wz = r.d < 2 ? r.plane : u;
                if (!solidAt(model, wx, y + 1, wz)) continue;
                roofed++;
                int ox = wx + DIRS[r.d][0], oz = wz + DIRS[r.d][2];
                boolean over = solidAt(model, ox, y, oz) || solidAt(model, ox, y + 1, oz) || solidAt(model, ox, y + 2, oz);
                if (!over) {
                    flush++;
                    ey1 = Math.min(ey1, y);
                    ey2 = Math.max(ey2, y);
                }
            }
            if (roofed >= 5 && flush >= 0.7 * roofed) {
                int[] box = r.d < 2 ? new int[]{r.u1, ey1, r.plane, r.u2, ey2 + 1, r.plane} : new int[]{r.plane, ey1, r.u1, r.plane, ey2 + 1, r.u2};
                eaves.add(box);
                eaveRegions.add(r);
            }
        }
        for (int i = 0; i < Math.min(MAX_EAVES, eaves.size()); i++) {
            Region r = eaveRegions.get(i);
            out.add(new Mark(0, "no-eave", "no eave: the roof sits flush on the " + FACING[r.d] + " wall top, " + where(eaves.get(i))
                    + part(r.part) + ". Let the roof overhang the wall by 1 (an eave of stairs, upside-down stairs or slabs under it)"
                    + " so it casts a shadow line.", eaves.get(i), DIRS[r.d]));
        }
        return new Facades(flatArea, total);
    }

    /**
     * Air, or a decoration with no real depth (a button, trapdoor, sign, torch, vine...). A wall covered in these is
     * still one flat plane: only a frame, a recess, a sill, a stair or a slab gives it depth, so stuck-on clutter
     * never clears a flat-wall mark.
     */
    static boolean thin(BlockSpec s) {
        if (s == null || s.isAir()) return true;
        String n = s.name();
        for (String t : THIN) if (n.contains(t)) return true;
        return false;
    }

    private static final String[] THIN = {"button", "trapdoor", "_sign", "torch", "vine", "lichen", "lever", "banner", "ladder",
            "tripwire", "carpet", "pressure_plate", "item_frame", "painting", "moss_carpet", "sculk_vein", "redstone_wire", "rail"};

    private static boolean solidAt(VoxelModel m, int x, int y, int z) {
        return !thin(m.get(x, y, z));
    }

    /** Air that is connected to the outside of the build (not inside rooms), flood-filled through empty cells. */
    private static final class Outside {
        final int x0, y0, z0, nx, ny, nz;
        final java.util.BitSet out;

        private Outside(int x0, int y0, int z0, int nx, int ny, int nz, java.util.BitSet out) {
            this.x0 = x0;
            this.y0 = y0;
            this.z0 = z0;
            this.nx = nx;
            this.ny = ny;
            this.nz = nz;
            this.out = out;
        }

        static Outside of(VoxelModel model, int[] b) {
            int x0 = b[0] - 1, y0 = Math.max(1, b[1]), z0 = b[2] - 1;
            int nx = b[3] - b[0] + 3, ny = b[4] - y0 + 2, nz = b[5] - b[2] + 3;
            if (ny <= 0 || (long) nx * ny * nz > MAX_FLOOD) return new Outside(0, 0, 0, 0, 0, 0, null);
            java.util.BitSet out = new java.util.BitSet(nx * ny * nz);
            int[] queue = new int[1024];
            int head = 0, tail = 0;
            for (int x = 0; x < nx; x++) {
                for (int y = 0; y < ny; y++) {
                    for (int z = 0; z < nz; z++) {
                        if (x != 0 && x != nx - 1 && z != 0 && z != nz - 1 && y != ny - 1) continue;
                        int i = (x * ny + y) * nz + z;
                        if (!free(model, x + x0, y + y0, z + z0) || out.get(i)) continue;
                        out.set(i);
                        if (tail == queue.length) queue = java.util.Arrays.copyOf(queue, queue.length * 2);
                        queue[tail++] = i;
                    }
                }
            }
            int[][] steps = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
            while (head < tail) {
                int i = queue[head++];
                int x = i / (ny * nz), y = (i / nz) % ny, z = i % nz;
                for (int[] s : steps) {
                    int qx = x + s[0], qy = y + s[1], qz = z + s[2];
                    if (qx < 0 || qy < 0 || qz < 0 || qx >= nx || qy >= ny || qz >= nz) continue;
                    int j = (qx * ny + qy) * nz + qz;
                    if (out.get(j) || !free(model, qx + x0, qy + y0, qz + z0)) continue;
                    out.set(j);
                    if (tail == queue.length) {
                        // compact the consumed head before growing
                        System.arraycopy(queue, head, queue, 0, tail - head);
                        tail -= head;
                        head = 0;
                        if (tail == queue.length) queue = java.util.Arrays.copyOf(queue, queue.length * 2);
                    }
                    queue[tail++] = j;
                }
            }
            return new Outside(x0, y0, z0, nx, ny, nz, out);
        }

        private static boolean free(VoxelModel m, int x, int y, int z) {
            return thin(m.get(x, y, z));
        }

        boolean at(int x, int y, int z) {
            if (out == null) return true;
            int ix = x - x0, iy = y - y0, iz = z - z0;
            if (ix < 0 || iy < 0 || iz < 0 || ix >= nx || iy >= ny || iz >= nz) return true;
            return out.get((ix * ny + iy) * nz + iz);
        }
    }

    // ------------------------------------------------------------------ speckle

    /**
     * Surfaces where neighbouring blocks keep jumping between contrasting colours: per 8 x 8 columns of top
     * surface, the share of neighbour pairs whose colours differ strongly. Mixing close tones (stone, andesite,
     * cobble) is texturing and passes, and so do patches (a blob of moss in grass has contrast only along its
     * edge); dirt kinds dotted at random through grass, or yellow blocks salted over a brown roof, read as noise.
     * Returns the build's share of contrasting pairs, in percent.
     */
    private static int speckle(VoxelModel model, BlockInfo info, int[] b, List<Mark> out) {
        Map<Long, int[]> top = new HashMap<>();          // (x,z) -> {y, colour}
        Map<Long, String> names = new HashMap<>();
        Map<String, Integer> colours = new HashMap<>();
        java.util.function.Function<BlockSpec, Integer> colour = s -> colours.computeIfAbsent(s.name(), n -> IsoRenderer.colourOf(s, info));
        Map<Long, Integer> water = new HashMap<>();      // (x,z) -> highest liquid cell
        model.forEach((x, y, z, s) -> {
            if (s != null && !s.isAir() && info.isLiquid(s)) water.merge(VoxelModel.key(x, 0, z), y, Math::max);
            if (s == null || s.isAir() || !info.isSolid(s)) return;
            long k = VoxelModel.key(x, 0, z);
            int[] t = top.get(k);
            if (t == null || y > t[0]) {
                top.put(k, new int[]{y, colour.apply(s)});
                names.put(k, base(s));
            }
        });
        // a lake or river bed under water is seen through the water, blurred and tinted: its mix is not speckle
        water.forEach((k, wy) -> {
            int[] t = top.get(k);
            if (t != null && wy > t[0]) {
                top.remove(k);
                names.remove(k);
            }
        });
        int grass = colour.apply(VoxelModel.TERRAIN);
        for (int x = b[0]; x <= b[3]; x++) {
            for (int z = b[2]; z <= b[5]; z++) {
                long k = VoxelModel.key(x, 0, z);
                if (!top.containsKey(k) && model.get(x, 0, z) == null) {
                    top.put(k, new int[]{0, grass});
                    names.put(k, "grass_block");
                }
            }
        }
        record Tile(int[] pairs, Map<String, Integer> mats, int[] box) {
        }
        // tiles of the ground and of raised surfaces (roofs, terraces) are separate: they are different surfaces
        record Key(int tx, int tz, boolean raised) {
        }
        Map<Key, Tile> tiles = new HashMap<>();
        int allPairs = 0, allContrast = 0;
        for (Map.Entry<Long, int[]> e : top.entrySet()) {
            int x = VoxelModel.kx(e.getKey()), z = VoxelModel.kz(e.getKey());
            int[] c = e.getValue();
            Key tk = new Key(Math.floorDiv(x, 8), Math.floorDiv(z, 8), c[0] > 2);
            Tile t = tiles.computeIfAbsent(tk, q -> new Tile(new int[2], new HashMap<>(), new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE,
                    Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE}));
            boolean edge = false;
            for (int[] d : new int[][]{{1, 0, 0}, {0, 0, 1}}) {       // each pair once
                int[] o = top.get(VoxelModel.key(x + d[0], 0, z + d[2]));
                if (o == null || Math.abs(o[0] - c[0]) > 1) continue;
                t.pairs[0]++;
                allPairs++;
                if (distance(o[1], c[1]) >= 60) {
                    t.pairs[1]++;
                    allContrast++;
                    edge = true;
                }
            }
            if (edge) {
                t.mats.merge(names.get(e.getKey()), 1, Integer::sum);
                int[] p = {x, c[0], z};
                for (int i = 0; i < 3; i++) {
                    t.box[i] = Math.min(t.box[i], p[i]);
                    t.box[i + 3] = Math.max(t.box[i + 3], p[i]);
                }
            }
        }
        Set<Key> noisy = new java.util.HashSet<>();
        tiles.forEach((k, t) -> {
            if (t.pairs[0] >= 40 && t.pairs[1] >= 0.3 * t.pairs[0]) noisy.add(k);
        });
        record Patch(int tiles, int pairs, int contrast, Map<String, Integer> mats, int[] box) {
        }
        List<Patch> patches = new ArrayList<>();
        Set<Key> seen = new java.util.HashSet<>();
        for (Key k : noisy) {
            if (!seen.add(k)) continue;
            List<Key> stack = new ArrayList<>(List.of(k));
            int n = 0, pairs = 0, contrast = 0;
            Map<String, Integer> mats = new HashMap<>();
            int[] box = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
            while (!stack.isEmpty()) {
                Key q = stack.removeLast();
                Tile t = tiles.get(q);
                n++;
                pairs += t.pairs[0];
                contrast += t.pairs[1];
                t.mats.forEach((m, v) -> mats.merge(m, v, Integer::sum));
                for (int i = 0; i < 3; i++) {
                    box[i] = Math.min(box[i], t.box[i]);
                    box[i + 3] = Math.max(box[i + 3], t.box[i + 3]);
                }
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        Key r = new Key(q.tx() + dx, q.tz() + dz, q.raised());
                        if (noisy.contains(r) && seen.add(r)) stack.add(r);
                    }
                }
            }
            patches.add(new Patch(n, pairs, contrast, mats, box));
        }
        patches.sort(Comparator.comparingInt((Patch p) -> -p.contrast));
        for (Patch p : patches.subList(0, Math.min(MAX_SPECKLE, patches.size()))) {
            boolean ground = p.box[4] <= 2;
            int share = Math.round(100f * p.contrast / Math.max(1, p.pairs));
            out.add(new Mark(0, "speckle", "speckle " + (ground ? "on the ground" : "on a top surface") + ", " + where(p.box) + ": "
                    + share + "% of neighbouring blocks jump between contrasting colours (" + top(p.mats, 4) + "), which reads as"
                    + " noise from a distance. Group materials into patches and bands (a path with a soft edge, moss in clumps,"
                    + " wear along a gradient) instead of mixing them block by block.", p.box, new int[]{0, 1, 0}));
        }
        return Math.round(100f * allContrast / Math.max(1, allPairs));
    }

    private static double distance(int a, int b) {
        int dr = ((a >> 16) & 255) - ((b >> 16) & 255), dg = ((a >> 8) & 255) - ((b >> 8) & 255), db = (a & 255) - (b & 255);
        return Math.sqrt(dr * dr + dg * dg + db * db);
    }

    // ------------------------------------------------------------------ measures

    private static void detail(VoxelModel model, BlockInfo info, Map<String, Object> metrics) {
        int[] n = new int[2];
        model.forEach((x, y, z, s) -> {
            if (y < 1 || s == null || s.isAir() || !info.isSolid(s) || SOIL.contains(s.name())) return;
            String name = s.name();
            if (name.contains("leaves") || name.endsWith("_log") || name.endsWith("_wood")) return;
            n[0]++;
            if (!info.isOccluding(s) && !name.contains("glass")) n[1]++;
        });
        if (n[0] > 0) metrics.put("detail blocks (stairs, slabs, walls, fences, trapdoors...)", Math.round(100.0 * n[1] / n[0]) + "%");
    }

    // ------------------------------------------------------------------ small things

    private static long uy(int u, int y) {
        return ((long) u << 32) | (y & 0xffffffffL);
    }

    private static String base(BlockSpec s) {
        return s.name();
    }

    private static int[] boxOf(List<Long> keys) {
        int[] box = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (long k : keys) {
            int[] p = {VoxelModel.kx(k), VoxelModel.ky(k), VoxelModel.kz(k)};
            for (int i = 0; i < 3; i++) {
                box[i] = Math.min(box[i], p[i]);
                box[i + 3] = Math.max(box[i + 3], p[i]);
            }
        }
        return box;
    }

    private static String majority(List<Long> keys, Owners own) {
        Map<String, Integer> n = new HashMap<>();
        for (long k : keys) {
            String p = own.at(VoxelModel.kx(k), VoxelModel.ky(k), VoxelModel.kz(k));
            if (p != null) n.merge(p, 1, Integer::sum);
        }
        return n.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    private static String part(String p) {
        return p == null ? "" : ", part " + p;
    }

    static String where(int[] b) {
        return "x " + span(b[0], b[3]) + " y " + span(b[1], b[4]) + " z " + span(b[2], b[5]);
    }

    private static String span(int a, int b) {
        return a == b ? String.valueOf(a) : a + ".." + b;
    }

    private static String top(Map<String, Integer> counts, int n) {
        return String.join(", ", counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(n)
                .map(e -> e.getKey() + " x" + e.getValue()).toList());
    }
}
