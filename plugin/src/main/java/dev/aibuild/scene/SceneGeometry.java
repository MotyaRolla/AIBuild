package dev.aibuild.scene;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.aibuild.model.BlockSpec;
import dev.aibuild.model.Dir;
import dev.aibuild.model.VoxelModel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

/**
 * Turns a {@link SceneSpec} (declarative 3D primitives) into real Minecraft blocks.
 *
 * <h2>Why this class exists</h2>
 * <p>The AI edits a compact scene of primitives (a house is 40–90 objects); this builder is the
 * deterministic code that transfers that scene into the block grid. The AI never writes thousands
 * of {@code set} operations, and the transfer cannot "forget" a block — the same input always
 * produces the same output.</p>
 *
 * <h2>How it works</h2>
 * <ol>
 *     <li>Every solid object is expanded into unit cells. A cell {@code (x,y,z)} covers
 *     {@code [x,x+1] x [y,y+1] x [z,z+1]}, so its centre is {@code x+0.5} and a 1-thick wall is
 *     exactly one block of thickness.</li>
 *     <li>Openings ({@code box.openings} and the standalone {@code window}/{@code door}/{@code arch}
 *     objects) are resolved <b>after</b> all solids exist. A standalone opening searches for its host
 *     from outside the scene inwards, punches a real hole through the wall and only then drops its
 *     frame and glass into that hole. Nothing is drawn on top of a wall.</li>
 *     <li>Each cell is assigned a Minecraft block. The shape follows the local geometry: sloped roof
 *     cells become {@code *_stairs} facing uphill, flat roof tops become {@code *_slab}, glass becomes
 *     {@code glass_pane} with the side connections its neighbours justify, and everything else stays
 *     a full block. This is what makes the Minecraft result look built instead of like a heap of
 *     cubes — the single most visible quality factor of the transfer.</li>
 *     <li>The model is written back in sorted key order, so two builds produce byte-identical JSON.</li>
 * </ol>
 *
 * <h2>Determinism and cost</h2>
 * <p>All randomness comes from a {@link Random} seeded with {@link SceneSpec#seed()} and is used only
 * by the {@code detail} patterns; {@link Math#random()} is never called and no iteration order leaks
 * into the result. A 10x10x15 house builds in a few milliseconds, because every builder is a simple
 * loop over its own bounding box. {@link #MAX_OBJECT_CELLS} keeps one pathological spec (a sphere of
 * radius 60) from stalling an iteration.</p>
 *
 * <h2>Coordinates and materials</h2>
 * <p>+x = east, +y = up, +z = south; y = 0 is the ground layer, matching the rest of the plugin, and
 * the scene "front" faces south by convention. A spec material is a palette key; {@link #DEFAULT_BLOCKS}
 * maps the conventional keys to real blocks and anything else is matched by colour against the spec
 * palette, so a spec that invents its own keys still gets plausible blocks.</p>
 *
 * <p>Instances are stateless static entry points; all working state lives in a private per-call
 * context, so the class is thread-safe as long as the target {@link VoxelModel} is confined to one
 * thread.</p>
 */
public final class SceneGeometry {

    /** Hard cap on the number of cells one object may claim; larger shapes are clamped and reported. */
    public static final int MAX_OBJECT_CELLS = 400_000;

    /** Largest absolute coordinate any builder may write; bigger values are dropped as a spec bug. */
    public static final int MAX_COORD = 512;

    /** {@code |ny|} below this is a slope and becomes a stair. */
    public static final double SLOPE_NY = 0.85;

    /**
     * {@code |ny|} below this is a vertical face, never a slope. A cell whose normal is this
     * horizontal is a wall, a riser or a trim edge and stays a full block.
     */
    public static final double VERTICAL_NY = 0.2;

    /**
     * Smallest roof rise per cell of run that still becomes stairs. Below it the surface is shallow
     * enough that slabs read as a proper terrace, while stairs would look like a broken staircase.
     */
    public static final double MIN_STAIR_GRADIENT = 0.45;

    /** Exposure bit: the +x face is open. */
    public static final int EXPOSED_XP = 1;
    /** Exposure bit: the -x face is open. */
    public static final int EXPOSED_XN = 2;
    /** Exposure bit: the +y face is open. */
    public static final int EXPOSED_YP = 4;
    /** Exposure bit: the -y face is open. */
    public static final int EXPOSED_YN = 8;
    /** Exposure bit: the +z face is open. */
    public static final int EXPOSED_ZP = 16;
    /** Exposure bit: the -z face is open. */
    public static final int EXPOSED_ZN = 32;

    /**
     * Scene palette key to the Minecraft block a plain, unshaped cell uses.
     *
     * <p>The palette of a spec is a set of <i>colours</i>; this table is the bridge to real blocks.
     * Materials not listed here are matched against the palette by colour distance, which is how a
     * spec that uses its own palette keys still ends up with plausible blocks.</p>
     */
    public static final Map<String, String> DEFAULT_BLOCKS = Map.ofEntries(
            Map.entry("wall", "white_terracotta"),
            Map.entry("frame", "dark_oak_log"),
            Map.entry("wood", "spruce_planks"),
            Map.entry("roof", "dark_oak_planks"),
            Map.entry("roof_accent", "spruce_planks"),
            Map.entry("stone", "stone_bricks"),
            Map.entry("stone_dark", "deepslate_bricks"),
            Map.entry("plain", "stone"),
            Map.entry("glass", "glass_pane"),
            Map.entry("metal", "iron_block"),
            Map.entry("leaf", "oak_leaves"),
            Map.entry("brick", "bricks"),
            Map.entry("gold", "gold_block"),
            Map.entry("water", "water"),
            Map.entry("trim", "stone_bricks"),
            Map.entry("accent", "spruce_planks"),
            Map.entry("carpet", "white_wool"));

    /** How far outside the scene a standalone opening searches for its host wall. */
    private static final int OPENING_MARGIN = 10;

    /**
     * Per-voxel information the renderer and the block transfer need.
     *
     * @param material the scene palette key of the cell ({@code roof}, {@code wall}, ...); the
     *                 renderer maps it to a colour and the transfer maps it to a block family
     * @param up       there is a solid cell directly above, so this cell cannot carry a top slab and
     *                 its top face is hidden
     * @param down     there is a solid cell directly below, so this cell cannot carry a bottom slab
     * @param nx       x of the averaged outward normal of the cell's exposed faces (unit length)
     * @param ny       y of the averaged outward normal; values below {@link #SLOPE_NY} mean "sloped"
     * @param nz       z of the averaged outward normal (unit length)
     * @param exposed  bitmask of {@link #EXPOSED_XP}..{@link #EXPOSED_ZN} marking which of the six
     *                 neighbour cells are empty
     */
    public record CellInfo(String material, boolean up, boolean down, double nx, double ny, double nz, int exposed) {

        /** @return true when the cell has no open face and can never be seen or reached */
        public boolean interior() {
            return exposed == 0;
        }
    }

    /**
     * Result of a full geometry build: the blocks plus the per-cell information that was derived while
     * placing them.
     *
     * @param model    the built model (model space, sorted, never {@code null})
     * @param info     cell key (see {@link VoxelModel#key}) to {@link CellInfo}
     * @param warnings non-fatal notes (clamped shapes, ignored objects), possibly empty
     */
    public record Scene(VoxelModel model, Map<Long, CellInfo> info, List<String> warnings) {
    }

    private SceneGeometry() {
    }

    /**
     * Builds the whole spec into a fresh model.
     *
     * @param spec the scene to build; {@code null} yields an empty model
     * @return a new model holding every block of the scene
     */
    public static VoxelModel build(SceneSpec spec) {
        VoxelModel out = new VoxelModel();
        buildInto(spec, out);
        return out;
    }

    /**
     * Builds the spec additively into an existing model, leaving cells the spec does not touch alone.
     * This is what a scene patch uses: only the objects of the patch are rebuilt, the rest of the
     * structure survives.
     *
     * @param spec the scene (or patch) to build; {@code null} is a no-op
     * @param out  the target model, mutated in place
     */
    public static void buildInto(SceneSpec spec, VoxelModel out) {
        Objects.requireNonNull(out, "out");
        if (spec == null) return;
        new Impl(spec, out).run();
    }

    /**
     * Builds a single object of a spec into a fresh model (used by tests and by the editor preview).
     *
     * <p>The other objects of the spec are not built, but its palette, seed and bounds are used, so
     * the object looks exactly as it will inside the complete scene. A standalone window built this
     * way has no host wall, so it falls back to a free-standing frame, which is the documented
     * behaviour of a lone opening.</p>
     *
     * @param obj  the object to build
     * @param spec the spec the object belongs to (palette / seed / bounds); may be {@code null}
     * @return a model holding just that object
     */
    public static VoxelModel buildObject(SceneObject obj, SceneSpec spec) {
        Objects.requireNonNull(obj, "obj");
        SceneSpec owner = spec == null ? SceneSpec.of("object", List.of(obj)) : spec;
        VoxelModel out = new VoxelModel();
        new Impl(owner, out).runObject(obj);
        return out;
    }

    /**
     * Builds the spec and returns the per-voxel information the renderer and the transfer consume.
     *
     * <p>This is the "one build, two views" entry point: the returned map is derived from the same
     * pass that produces the blocks, so the render and the Minecraft result can never disagree. It is
     * the cheap query for callers that need only normals and exposure.</p>
     *
     * @param spec the scene to build
     * @return cell key to {@link CellInfo}; empty for a {@code null} or empty spec
     */
    public static Map<Long, CellInfo> info(SceneSpec spec) {
        if (spec == null) return Map.of();
        return new Impl(spec, new VoxelModel()).run().info();
    }

    /**
     * Builds the spec once and returns the blocks, the cell information and any warnings.
     *
     * @param spec the scene to build
     * @return the complete build result; prefer this over calling {@link #build} and {@link #info}
     * separately when both are needed, because it builds only once
     */
    public static Scene buildScene(SceneSpec spec) {
        Objects.requireNonNull(spec, "spec");
        return new Impl(spec, new VoxelModel()).run();
    }

    // ================================================================== shared helpers

    /** @return {@code 0} for {@code -0.0}, otherwise {@code v}, so equality checks stay clean */
    private static double clean(double v) {
        return v == 0 ? 0 : v;
    }

    /** Normalises a vector, or returns a zero vector when it is degenerate. */
    private static double[] unit(double[] v) {
        double len = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (len < 1e-12) return new double[]{0, 0, 0};
        return new double[]{v[0] / len, v[1] / len, v[2] / len};
    }

    /**
     * The thin direction of a cell whose exposed normals cancel (a 1-thick wall, a floor tile, a
     * glass pane). Picks the first axis that is open on both sides and keeps the positive sign, so
     * the result is a usable direction instead of a zero vector.
     *
     * @param exposed the exposure bitmask
     * @return a unit axis vector, or {@code (0,0,0)} when nothing is exposed
     */
    private static double[] thinNormal(int exposed) {
        if ((exposed & EXPOSED_XP) != 0 && (exposed & EXPOSED_XN) != 0) return new double[]{1, 0, 0};
        if ((exposed & EXPOSED_YP) != 0 && (exposed & EXPOSED_YN) != 0) return new double[]{0, 1, 0};
        if ((exposed & EXPOSED_ZP) != 0 && (exposed & EXPOSED_ZN) != 0) return new double[]{0, 0, 1};
        return new double[]{0, 0, 0};
    }

    /** @return true for a pane-like block id (including the namespaceless {@code glass_pane}) */
    private static boolean isPane(String name) {
        return name.equals("glass_pane") || name.endsWith("_glass_pane") || name.equals("iron_bars");
    }

    /** @return true when light passes through the block, so a pane does not connect to it */
    private static boolean isTransparent(String name) {
        return isPane(name) || name.equals("air") || name.contains("glass")
                || name.endsWith("_leaves") || name.equals("water");
    }

    /**
     * Maps a full block id to the prefix its {@code _stairs} / {@code _slab} variants are built from.
     *
     * @param name a block id such as {@code dark_oak_planks} or {@code stone_bricks}
     * @return the variant prefix ({@code dark_oak}, {@code stone_brick}), or {@code null} when the
     * block has no stair or slab variant (logs, terracotta, leaves, metal)
     */
    private static String shapeBase(String name) {
        if (name.endsWith("_planks")) return name.substring(0, name.length() - "_planks".length());
        return switch (name) {
            case "stone_bricks" -> "stone_brick";
            case "mossy_stone_bricks" -> "mossy_stone_brick";
            case "deepslate_bricks" -> "deepslate_brick";
            case "bricks" -> "brick";
            case "mud_bricks" -> "mud_brick";
            case "polished_deepslate" -> "polished_deepslate";
            case "cobblestone" -> "cobblestone";
            case "mossy_cobblestone" -> "mossy_cobblestone";
            case "stone" -> "stone";
            case "smooth_stone" -> "smooth_stone";
            case "blackstone" -> "blackstone";
            case "sandstone" -> "sandstone";
            case "smooth_sandstone" -> "smooth_sandstone";
            case "quartz_block", "smooth_quartz" -> "quartz";
            default -> null;
        };
    }

    /** Builds a stair block that is full on its uphill side. */
    private static BlockSpec stairBlock(String base, Dir facing) {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("facing", facing.id());
        props.put("half", "bottom");
        props.put("shape", "straight");
        return BlockSpec.of(base + "_stairs", props);
    }

    /** Builds a slab block with the given {@code type} ({@code bottom}, {@code top}, {@code double}). */
    private static BlockSpec slabBlock(String base, String type) {
        return BlockSpec.of(base + "_slab", Map.of("type", type));
    }

    /** Parses {@code #rgb} or {@code #rrggbb}; returns {@code null} when it is not a colour. */
    private static int[] parseHex(String hex) {
        if (hex == null) return null;
        String s = hex.trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() == 3) {
            s = "" + s.charAt(0) + s.charAt(0) + s.charAt(1) + s.charAt(1) + s.charAt(2) + s.charAt(2);
        }
        if (s.length() != 6) return null;
        try {
            return new int[]{
                    Integer.parseInt(s.substring(0, 2), 16),
                    Integer.parseInt(s.substring(2, 4), 16),
                    Integer.parseInt(s.substring(4, 6), 16)};
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * Reads a full three-component vector from a raw object field.
     *
     * <p>{@link SceneObject#at()} intentionally keeps only two components because windows and doors
     * use it as a wall coordinate {@code [u, y]}; columns and chimneys use the same field as a real
     * {@code [x, y, z]} anchor, so they have to read the third component here.</p>
     *
     * @param json the object JSON
     * @param key  the field name ({@code at}, {@code from}, ...)
     * @param def  the value returned when the field is missing or too short
     * @return a three-element vector
     */
    private static int[] vec3Of(JsonObject json, String key, int[] def) {
        JsonElement e = json.get(key);
        if (e == null || !e.isJsonArray()) return def;
        JsonArray a = e.getAsJsonArray();
        if (a.size() < 3) return def;
        return new int[]{(int) Math.round(a.get(0).getAsDouble()),
                (int) Math.round(a.get(1).getAsDouble()),
                (int) Math.round(a.get(2).getAsDouble())};
    }

    /** @return true when both corners are present and ordered */
    private static boolean validBox(int[] from, int[] to) {
        return from != null && to != null
                && to[0] >= from[0] && to[1] >= from[1] && to[2] >= from[2];
    }

    // ================================================================== build phases

    /**
     * Which build phase a group belongs to. It decides whether the group may be reshaped into stairs
     * and slabs ({@link Stage#ROOF}, plus the explicit {@code stairs} object) or must keep its authored
     * blocks ({@link Stage#BASE}, {@link Stage#OPENING}).
     */
    private enum Stage {
        /** Walls, floors, columns, details: authored geometry that keeps its blocks. */
        BASE,
        /** A roof mass with per-cell slope normals, which becomes stairs and slabs. */
        ROOF,
        /** Frame, glass, trim, coping and caps created around other geometry. */
        OPENING
    }

    /**
     * One block a builder wants to place: the palette key that produced it plus the exact block.
     * Keeping the palette key means {@link CellInfo#material()} stays a scene material even when a
     * pattern paints individual cells with an accent block.
     */
    private record Painted(String material, BlockSpec block) {
    }

    /** One object (or one part of an object) that owns a set of cells. */
    private static final class Group {
        final String id;
        final String type;
        final String material;
        final BlockSpec block;
        final boolean shaped;
        final LinkedHashMap<Long, Painted> cells = new LinkedHashMap<>();
        /** Per-cell normal override for sloped surfaces: cell key to a not-yet-normalised normal. */
        final Map<Long, double[]> slope = new LinkedHashMap<>();
        boolean truncated;

        Group(String id, String type, String material, BlockSpec block, boolean shaped) {
            this.id = id;
            this.type = type;
            this.material = material;
            this.block = block;
            this.shaped = shaped;
        }
    }

    /**
     * A resolved aperture: which wall it belongs to, its extent along the wall and in height, and the
     * decoration to put into the hole. The wall's depth coordinate is resolved per host, because the
     * wall plane depends on the object the hole is cut into.
     *
     * @param wall    {@code north|south|east|west}
     * @param uAxis   axis of the coordinate along the wall (0 = x, 2 = z)
     * @param dAxis   axis through the wall (2 = z for north/south, 0 = x for east/west)
     * @param sign    outward direction along {@code dAxis} (+1 south/east, -1 north/west)
     * @param u0      first cell along the wall (inclusive)
     * @param u1      last cell along the wall (inclusive)
     * @param y0      first row (inclusive)
     * @param y1      last row (inclusive, including the arched top when {@code arch} is set)
     * @param arch    the top narrows like a semicircle
     * @param kind    {@code window|door|arch}
     * @param frame   palette key of the frame, or {@code null} for the kind's default
     * @param glass   palette key of the glass, or {@code null} when the opening stays empty
     * @param sill    add a sill course under the frame
     * @param shutters add shutter columns beside the frame
     * @param canopy  add a small canopy above the opening
     * @param depth   how many cells deep to cut when the opening is not a through-hole
     * @param through cut through the whole host thickness (doors and arches always do)
     * @param filter  explicit {@code [u,y]} cells to keep, or {@code null} for the whole rect
     */
    private record Rect(String wall, int uAxis, int dAxis, int sign, int u0, int u1, int y0, int y1,
                        boolean arch, String kind, String frame, String glass, boolean sill,
                        boolean shutters, boolean canopy, int depth, boolean through, Set<Long> filter) {

        /** @return true when cell {@code (u,y)} is part of the aperture */
        boolean allows(int u, int y) {
            if (arch) {
                int spring = y1 - Math.max(1, (int) Math.ceil((u1 - u0 + 1) / 2.0)) + 1;
                if (!insideArch(u, y, u0, u1, y1, spring)) return false;
            }
            return filter == null || filter.contains(VoxelModel.key(u, y, 0));
        }

        /** @return a copy clamped to the given wall extent */
        Rect clampTo(int uMin, int uMax, int yMin, int yMax) {
            int cu0 = u0;
            int cu1 = u1;
            if (cu1 < uMin || cu0 > uMax) {
                cu0 = Math.min(Math.max(cu0, uMin), uMax);
                cu1 = Math.min(cu0 + (u1 - u0), uMax);
            }
            cu0 = Math.max(cu0, uMin);
            cu1 = Math.min(cu1, uMax);
            int cy0 = Math.max(y0, yMin);
            int cy1 = Math.min(y1, yMax);
            return new Rect(wall, uAxis, dAxis, sign, cu0, cu1, cy0, cy1, arch, kind, frame, glass,
                    sill, shutters, canopy, depth, through, filter);
        }
    }

    /** A staged standalone opening, resolved after every solid exists. */
    private static final class Opening {
        final SceneObject obj;
        final Rect rect;
        final int dLo;
        final int dHi;

        Opening(SceneObject obj, Rect rect, int dLo, int dHi) {
            this.obj = obj;
            this.rect = rect;
            this.dLo = dLo;
            this.dHi = dHi;
        }
    }

    /**
     * The working state of one build: global occupancy (last writer wins, in spec order), the groups
     * that own the cells, and the openings waiting to be punched.
     */
    private static final class Impl {

        private final SceneSpec spec;
        private final VoxelModel out;
        private final SceneBounds bounds;
        private final List<Group> groups = new ArrayList<>();
        private final Map<Long, Group> owner = new LinkedHashMap<>();
        private final Map<Long, CellInfo> info = new LinkedHashMap<>();
        private final List<Opening> staged = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private final Random random;

        Impl(SceneSpec spec, VoxelModel out) {
            this.spec = spec;
            this.out = out;
            this.bounds = SceneBounds.from(spec);
            this.random = new Random(spec.seed());
        }

        Scene run() {
            for (SceneObject o : spec.objects()) addObject(o);
            resolveOpenings();
            emit();
            sortIntoModel();
            return new Scene(out, Map.copyOf(info), List.copyOf(warnings));
        }

        void runObject(SceneObject obj) {
            addObject(obj);
            resolveOpenings();
            emit();
            sortIntoModel();
        }

        // -------------------------------------------------------------- dispatch

        private void addObject(SceneObject o) {
            switch (o.type()) {
                case "box" -> addBox(o);
                case "prism" -> addRoof(o);
                case "cylinder" -> addCylinder(o);
                case "cone" -> addCone(o);
                case "sphere" -> addSphere(o);
                case "ring" -> addRing(o);
                case "wall" -> addWall(o);
                case "floor" -> addFloor(o);
                case "stairs" -> addStairs(o);
                case "window", "door", "arch" -> stageOpening(o);
                case "balustrade" -> addBalustrade(o);
                case "beam" -> addBeam(o);
                case "column" -> addColumn(o);
                case "chimney" -> addChimney(o);
                case "detail" -> addDetail(o);
                default -> warnings.add("unknown object type '" + o.type() + "' ignored");
            }
        }

        // -------------------------------------------------------------- groups and cells

        private Group newGroup(SceneObject o, Stage stage, String material) {
            BlockSpec block = blockFor(material);
            boolean shaped = stage == Stage.ROOF || o.type().equals("stairs");
            Group g = new Group(o.id().isEmpty() ? o.type() : o.id(), o.type(),
                    material, block, shaped);
            groups.add(g);
            return g;
        }

        private String materialOf(SceneObject o, String fallback) {
            String m = o.material();
            return m == null || m.isBlank() ? fallback : m;
        }

        private void put(Group g, int x, int y, int z) {
            put(g, x, y, z, g.material, g.block);
        }

        private void put(Group g, int x, int y, int z, String material, BlockSpec block) {
            if (Math.abs(x) > MAX_COORD || Math.abs(y) > MAX_COORD || Math.abs(z) > MAX_COORD) return;
            if (g.cells.size() >= MAX_OBJECT_CELLS) {
                g.truncated = true;
                return;
            }
            long k = VoxelModel.key(x, y, z);
            if (g.cells.putIfAbsent(k, new Painted(material, block)) == null) owner.put(k, g);
        }

        private void put(Group g, int[] p) {
            put(g, p[0], p[1], p[2]);
        }

        /** Places a cell at a wall-plane coordinate: {@code u} along the wall, {@code y} up, {@code d} through. */
        private void putAt(Group g, Rect r, int u, int y, int d) {
            int[] p = {0, y, 0};
            p[r.uAxis()] = u;
            p[r.dAxis()] = d;
            put(g, p[0], p[1], p[2]);
        }

        /** Removes a cell from its owning group, so the hole is real and not merely hidden. */
        private void cut(long k) {
            Group g = owner.remove(k);
            if (g != null) g.cells.remove(k);
        }

        private boolean occupied(int x, int y, int z) {
            return owner.containsKey(VoxelModel.key(x, y, z));
        }

        // -------------------------------------------------------------- emission

        private void emit() {
            // Pass 1: paint every owned cell with its plain block and derive its CellInfo. Doing this
            // before any shape is chosen means a pane can see its neighbours and a stair can see the
            // whole slope, regardless of which group owns them.
            List<long[]> placed = new ArrayList<>();
            for (Group g : groups) {
                if (g.truncated) {
                    warnings.add("'" + g.id + "' hit the " + MAX_OBJECT_CELLS + " cell cap and was clamped");
                }
                for (Map.Entry<Long, Painted> e : g.cells.entrySet()) {
                    long k = e.getKey();
                    if (owner.get(k) != g) continue;
                    int x = VoxelModel.kx(k);
                    int y = VoxelModel.ky(k);
                    int z = VoxelModel.kz(k);
                    int exposed = exposure(x, y, z);
                    double[] n = normalFor(g, k, exposed);
                    info.put(k, new CellInfo(e.getValue().material(),
                            occupied(x, y + 1, z), occupied(x, y - 1, z),
                            clean(n[0]), clean(n[1]), clean(n[2]), exposed));
                    out.set(x, y, z, e.getValue().block());
                    placed.add(new long[]{k});
                }
            }
            // Pass 2: choose the final block (stairs, slabs, panes) now that every neighbour exists.
            for (long[] p : placed) {
                long k = p[0];
                Group g = owner.get(k);
                CellInfo ci = info.get(k);
                if (g == null || ci == null) continue;
                out.set(VoxelModel.kx(k), VoxelModel.ky(k), VoxelModel.kz(k), choose(g, k, ci));
            }
        }

        /** Rewrites the model in sorted key order so a rebuild produces identical output. */
        private void sortIntoModel() {
            if (out.isEmpty()) return;
            long[] keys = out.entries().keySet().stream().mapToLong(Long::longValue).sorted().toArray();
            BlockSpec[] specs = new BlockSpec[keys.length];
            for (int i = 0; i < keys.length; i++) {
                specs[i] = out.get(VoxelModel.kx(keys[i]), VoxelModel.ky(keys[i]), VoxelModel.kz(keys[i]));
            }
            out.clear();
            for (int i = 0; i < keys.length; i++) {
                out.set(VoxelModel.kx(keys[i]), VoxelModel.ky(keys[i]), VoxelModel.kz(keys[i]), specs[i]);
            }
        }

        private int exposure(int x, int y, int z) {
            int mask = 0;
            if (!occupied(x + 1, y, z)) mask |= EXPOSED_XP;
            if (!occupied(x - 1, y, z)) mask |= EXPOSED_XN;
            if (!occupied(x, y + 1, z)) mask |= EXPOSED_YP;
            if (!occupied(x, y - 1, z)) mask |= EXPOSED_YN;
            if (!occupied(x, y, z + 1)) mask |= EXPOSED_ZP;
            if (!occupied(x, y, z - 1)) mask |= EXPOSED_ZN;
            return mask;
        }

        /**
         * The cell's outward normal: the sum of the normals of its open faces, or — when that sum
         * cancels (a 1-thick wall, a floor tile, a pane) — the thin direction, which is what a stair
         * or slab needs. A sloped group overrides the top cell of every column with the exact surface
         * normal; without that a stepped roof could only ever report {@code (0,1,0)} and would come
         * out as flat planks.
         */
        private double[] normalFor(Group g, long k, int exposed) {
            double[] slope = g.slope.get(k);
            if (slope != null) return unit(slope);
            double nx = 0;
            double ny = 0;
            double nz = 0;
            if ((exposed & EXPOSED_XP) != 0) nx += 1;
            if ((exposed & EXPOSED_XN) != 0) nx -= 1;
            if ((exposed & EXPOSED_YP) != 0) ny += 1;
            if ((exposed & EXPOSED_YN) != 0) ny -= 1;
            if ((exposed & EXPOSED_ZP) != 0) nz += 1;
            if ((exposed & EXPOSED_ZN) != 0) nz -= 1;
            double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > 1e-9) return new double[]{nx / len, ny / len, nz / len};
            return thinNormal(exposed);
        }

        // -------------------------------------------------------------- block choice

        private BlockSpec choose(Group g, long k, CellInfo ci) {
            String base = blockName(k);
            if (isPane(base)) return paneBlock(base, k);
            if (!g.shaped || ci.exposed() == 0) return blockValue(k);
            String family = shapeBase(base);
            if (family == null) return blockValue(k);

            // Only the top cell of a column carries the authored surface normal, and only when
            // nothing sits on top of it. Riser and rim cells of the stepped mass stay full blocks:
            // turning them into stairs too is what makes a roof look like a staircase of diagonals
            // instead of a slope.
            if (!g.slope.containsKey(k) || ci.up()) return blockValue(k);
            // The exact surface normal above this column tells us how steep the roof is: `gradient`
            // is the rise per cell of run. A roof that rises at least half a block per cell becomes a
            // stair flight; a shallower one becomes slabs, so the surface stays flush instead of
            // poking a full block above the slope.
            double horizontal = Math.hypot(ci.nx(), ci.nz());
            double ny = Math.abs(ci.ny());
            double gradient = ny < 1e-6 ? Double.MAX_VALUE : horizontal / ny;
            if (gradient >= MIN_STAIR_GRADIENT) return stairBlock(family, uphill(ci));
            if (ci.down()) return slabBlock(family, "bottom");
            return slabBlock(family, "double");
        }

        /** @return the final block of an already placed cell */
        private BlockSpec blockValue(long k) {
            BlockSpec s = out.get(VoxelModel.kx(k), VoxelModel.ky(k), VoxelModel.kz(k));
            return s == null ? BlockSpec.of("stone") : s;
        }

        /** @return the final block name of an already placed cell */
        private String blockName(long k) {
            BlockSpec s = out.get(VoxelModel.kx(k), VoxelModel.ky(k), VoxelModel.kz(k));
            if (s != null) return s.name();
            Group g = owner.get(k);
            return g == null ? "stone" : g.block.name();
        }

        /**
         * Uphill is opposite the outward horizontal normal, because the normal points away from the
         * solid and therefore down the slope. A Minecraft stair's full side points uphill, so its
         * {@code facing} is the uphill direction. The dominant horizontal component wins, which is
         * what keeps a 45-degree roof consistent instead of rounding both components to zero.
         */
        private static Dir uphill(CellInfo ci) {
            double nx = ci.nx();
            double nz = ci.nz();
            if (Math.abs(nx) < 1e-9 && Math.abs(nz) < 1e-9) return Dir.NORTH;
            if (Math.abs(nx) >= Math.abs(nz)) return nx > 0 ? Dir.WEST : Dir.EAST;
            return nz > 0 ? Dir.NORTH : Dir.SOUTH;
        }

        private BlockSpec paneBlock(String base, long k) {
            int x = VoxelModel.kx(k);
            int y = VoxelModel.ky(k);
            int z = VoxelModel.kz(k);
            boolean north = connects(x, y, z - 1);
            boolean south = connects(x, y, z + 1);
            boolean west = connects(x - 1, y, z);
            boolean east = connects(x + 1, y, z);
            if (!north && !south && !west && !east) {
                // An isolated pane renders as a 2x2 post; a full glass block is the honest reading of
                // a single window cell.
                return BlockSpec.of("glass");
            }
            Map<String, String> props = new LinkedHashMap<>();
            props.put("north", Boolean.toString(north));
            props.put("south", Boolean.toString(south));
            props.put("west", Boolean.toString(west));
            props.put("east", Boolean.toString(east));
            return BlockSpec.of(base, props);
        }

        private boolean connects(int x, int y, int z) {
            long k = VoxelModel.key(x, y, z);
            if (!owner.containsKey(k)) return false;
            String name = blockName(k);
            return isPane(name) || !isTransparent(name);
        }

        // -------------------------------------------------------------- material mapping

        /**
         * Resolves a palette material (a palette key, a hex colour or an unknown key) to a block.
         *
         * <p>Known keys are answered from {@link #DEFAULT_BLOCKS}; a hex colour or an unknown key is
         * matched by colour against the spec palette, which is the point of the scene palette being
         * colours: a spec that invents {@code "cobble": "#777"} still lands on a sensible block.</p>
         */
        private BlockSpec blockFor(String material) {
            String key = material == null || material.isBlank() ? "plain" : material;
            String direct = DEFAULT_BLOCKS.get(key);
            if (direct != null) return BlockSpec.of(direct);
            if (key.startsWith("#")) {
                String nearest = nearestPaletteKey(key);
                if (nearest != null && DEFAULT_BLOCKS.containsKey(nearest)) {
                    return BlockSpec.of(DEFAULT_BLOCKS.get(nearest));
                }
            }
            return BlockSpec.of(DEFAULT_BLOCKS.getOrDefault("plain", "stone"));
        }

        /** @return the palette key whose colour is closest to {@code hex}, or {@code null} */
        private String nearestPaletteKey(String hex) {
            int[] rgb = parseHex(hex);
            if (rgb == null) return null;
            String best = null;
            int bestDistance = Integer.MAX_VALUE;
            for (Map.Entry<String, String> e : spec.palette().entrySet()) {
                int[] c = parseHex(e.getValue());
                if (c == null) continue;
                int d = (c[0] - rgb[0]) * (c[0] - rgb[0])
                        + (c[1] - rgb[1]) * (c[1] - rgb[1])
                        + (c[2] - rgb[2]) * (c[2] - rgb[2]);
                if (d < bestDistance) {
                    bestDistance = d;
                    best = e.getKey();
                }
            }
            return best;
        }

        private String accentOf(SceneObject o, String primary) {
            String accent = SceneObject.str(o.raw(), "accent");
            if (!accent.isEmpty()) return accent;
            String frame = SceneObject.str(o.raw(), "frame");
            if (!frame.isEmpty()) return frame;
            if (primary.equals("roof") || primary.equals("roof_accent")) return "roof_accent";
            if (primary.equals("wall")) return "frame";
            return "stone_dark";
        }

        // ============================================================== box

        /**
         * {@code box}: a solid or hollow axis-aligned box with optional open faces and openings cut
         * straight through its walls.
         */
        private void addBox(SceneObject o) {
            int[] from = o.from();
            int[] to = o.to();
            if (!validBox(from, to)) return;
            String material = materialOf(o, "wall");
            Group body = newGroup(o, Stage.BASE, material);
            List<String> open = strings(o.raw().get("open"));
            if (SceneObject.bool(o.raw(), "hollow", false)) {
                shell(body, from, to, open);
            } else {
                fill(body, from, to);
            }
            JsonElement ops = o.raw().get("openings");
            if (ops != null && ops.isJsonArray()) {
                for (JsonElement e : ops.getAsJsonArray()) {
                    if (!e.isJsonObject()) continue;
                    cutIntoBox(o, body, e.getAsJsonObject(), from, to);
                }
            }
        }

        private void fill(Group g, int[] from, int[] to) {
            for (int x = from[0]; x <= to[0]; x++) {
                for (int y = from[1]; y <= to[1]; y++) {
                    for (int z = from[2]; z <= to[2]; z++) put(g, x, y, z);
                }
            }
        }

        private void shell(Group g, int[] from, int[] to, List<String> open) {
            for (int x = from[0]; x <= to[0]; x++) {
                for (int y = from[1]; y <= to[1]; y++) {
                    for (int z = from[2]; z <= to[2]; z++) {
                        boolean onX = x == from[0] || x == to[0];
                        boolean onY = y == from[1] || y == to[1];
                        boolean onZ = z == from[2] || z == to[2];
                        if (!onX && !onY && !onZ) continue;
                        List<String> hit = new ArrayList<>(3);
                        if (onX) hit.add(x == to[0] ? "east" : "west");
                        if (onY) hit.add(y == to[1] ? "up" : "down");
                        if (onZ) hit.add(z == to[2] ? "south" : "north");
                        if (!hit.isEmpty() && open.containsAll(hit)) continue;
                        put(g, x, y, z);
                    }
                }
            }
        }

        private void cutIntoBox(SceneObject o, Group body, JsonObject op, int[] from, int[] to) {
            Rect base = resolveRect(op, o.type());
            Rect r = base.clampTo(from[base.uAxis()], to[base.uAxis()], from[1], to[1]);
            if (r.u1() < r.u0() || r.y1() < r.y0()) return;
            int dMin = from[r.dAxis()];
            int dMax = to[r.dAxis()];
            int start = r.sign() > 0 ? dMax : dMin;
            int step = -r.sign();
            int maxDepth = r.through() ? (dMax - dMin + 1) : Math.min(dMax - dMin + 1, Math.max(1, r.depth()));
            for (int u = r.u0(); u <= r.u1(); u++) {
                for (int y = r.y0(); y <= r.y1(); y++) {
                    if (!r.allows(u, y)) continue;
                    int d = start;
                    int removed = 0;
                    while (removed < maxDepth && d >= dMin && d <= dMax) {
                        int[] p = {0, y, 0};
                        p[r.uAxis()] = u;
                        p[r.dAxis()] = d;
                        long k = VoxelModel.key(p[0], p[1], p[2]);
                        if (!body.cells.containsKey(k)) break;
                        cut(k);
                        removed++;
                        d += step;
                    }
                }
            }
            addFrame(o, r, start);
            addGlass(o, r, null, start);
        }

        // ============================================================== wall

        /**
         * {@code wall}: a straight run between two points with a thickness, a height, a coping row and
         * openings that run through the whole thickness.
         */
        private void addWall(SceneObject o) {
            int[] from = o.from();
            int[] to = o.to();
            if (from == null || to == null) return;
            String material = materialOf(o, "wall");
            int axis = Math.abs(to[0] - from[0]) >= Math.abs(to[2] - from[2]) ? 0 : 2;
            int cross = axis == 0 ? 2 : 0;
            int a0 = Math.min(from[axis], to[axis]);
            int a1 = Math.max(from[axis], to[axis]);
            int height = Math.max(1, (int) Math.round(SceneObject.dbl(o.raw(), "height",
                    Math.abs(to[1] - from[1]) + 1)));
            int thickness = Math.max(1, (int) Math.round(SceneObject.dbl(o.raw(), "thickness", 1)));
            int y0 = from[1];
            int y1 = y0 + height - 1;
            int c0 = from[cross] - (thickness - 1) / 2;
            int c1 = c0 + thickness - 1;

            Group body = newGroup(o, Stage.BASE, material);
            for (int a = a0; a <= a1; a++) {
                for (int cc = c0; cc <= c1; cc++) {
                    for (int y = y0; y <= y1; y++) {
                        int[] p = {0, y, 0};
                        p[axis] = a;
                        p[cross] = cc;
                        put(body, p[0], p[1], p[2]);
                    }
                }
            }

            if (SceneObject.bool(o.raw(), "coping", true)) {
                Group coping = newGroup(o, Stage.OPENING, accentOf(o, material));
                for (int a = a0; a <= a1; a++) {
                    for (int cc = c0 - 1; cc <= c1 + 1; cc++) {
                        int[] p = {0, y1 + 1, 0};
                        p[axis] = a;
                        p[cross] = cc;
                        put(coping, p[0], p[1], p[2]);
                    }
                }
            }

            JsonElement ops = o.raw().get("openings");
            if (ops == null || !ops.isJsonArray()) return;
            for (JsonElement e : ops.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                Rect r = resolveRect(e.getAsJsonObject(), o.type()).clampTo(a0, a1, y0, y1);
                if (r.u1() < r.u0() || r.y1() < r.y0()) continue;
                for (int u = r.u0(); u <= r.u1(); u++) {
                    for (int y = r.y0(); y <= r.y1(); y++) {
                        if (!r.allows(u, y)) continue;
                        for (int cc = c0; cc <= c1; cc++) {
                            int[] p = {0, y, 0};
                            p[axis] = u;
                            p[cross] = cc;
                            cut(VoxelModel.key(p[0], p[1], p[2]));
                        }
                    }
                }
                addFrame(o, r, c0);
                addGlass(o, r, null, c0);
            }
        }

        // ============================================================== floor

        /** {@code floor}: a horizontal slab with an optional checker / border / plank pattern. */
        private void addFloor(SceneObject o) {
            int[] from = o.from();
            int[] to = o.to();
            if (!validBox(from, to)) return;
            String material = materialOf(o, "stone");
            int thickness = Math.max(1, (int) Math.round(SceneObject.dbl(o.raw(), "thickness", 1)));
            int top = to[1];
            int bottom = Math.max(from[1], top - thickness + 1);
            String pattern = SceneObject.str(o.raw(), "pattern").toLowerCase(Locale.ROOT);
            String accent = accentOf(o, material);
            BlockSpec accentBlock = blockFor(accent);
            Group g = newGroup(o, Stage.BASE, material);
            for (int x = from[0]; x <= to[0]; x++) {
                for (int z = from[2]; z <= to[2]; z++) {
                    boolean useAccent = switch (pattern) {
                        case "checker" -> (x + z) % 2 == 0;
                        case "border" -> x == from[0] || x == to[0] || z == from[2] || z == to[2];
                        case "planks" -> Math.floorDiv(x - from[0], 3) % 2 == 1;
                        default -> false;
                    };
                    for (int y = bottom; y <= top; y++) {
                        if (useAccent) {
                            put(g, x, y, z, accent, accentBlock);
                        } else {
                            put(g, x, y, z);
                        }
                    }
                }
            }
        }

        // ============================================================== stairs

        /**
         * {@code stairs}: a solid flight climbing along {@code axis}. The top cell of every step gets a
         * slope normal pointing downhill, so the transfer turns it into a real stair block facing the
         * climb direction.
         */
        private void addStairs(SceneObject o) {
            int[] from = o.from();
            int[] to = o.to();
            if (!validBox(from, to)) return;
            String material = materialOf(o, "wood");
            String axisName = SceneObject.str(o.raw(), "axis").toLowerCase(Locale.ROOT);
            int axis = axisName.equals("x") ? 0 : axisName.equals("z") ? 2 : 2;
            int cross = axis == 0 ? 2 : 0;
            int climbLo = Math.min(from[axis], to[axis]);
            int climbHi = Math.max(from[axis], to[axis]);
            int crossLo = Math.min(from[cross], to[cross]);
            int crossHi = Math.max(from[cross], to[cross]);
            int levels = to[1] - from[1] + 1;
            int length = climbHi - climbLo + 1;
            if (levels < 1 || length < 1) return;
            boolean back = SceneObject.str(o.raw(), "direction").equalsIgnoreCase("back");
            int upSign = back ? -1 : 1;

            Group g = newGroup(o, Stage.BASE, material);
            Group rail = SceneObject.bool(o.raw(), "railing", false)
                    ? newGroup(o, Stage.OPENING, accentOf(o, material)) : null;

            for (int i = 0; i < length; i++) {
                int pos = back ? climbHi - i : climbLo + i;
                int step = Math.min(levels - 1, (int) Math.floor((double) i * levels / length));
                for (int y = from[1]; y <= from[1] + step; y++) {
                    for (int cc = crossLo; cc <= crossHi; cc++) {
                        int[] p = {0, y, 0};
                        p[axis] = pos;
                        p[cross] = cc;
                        put(g, p[0], p[1], p[2]);
                    }
                }
                for (int cc = crossLo; cc <= crossHi; cc++) {
                    int[] p = {0, from[1] + step, 0};
                    p[axis] = pos;
                    p[cross] = cc;
                    double[] n = {0, 1, 0};
                    n[axis] = -upSign;
                    g.slope.put(VoxelModel.key(p[0], p[1], p[2]), n);
                }
                if (rail != null && i < length - 1) {
                    for (int cc : new int[]{crossLo, crossHi}) {
                        int[] p = {0, from[1] + step + 1, 0};
                        p[axis] = pos;
                        p[cross] = cc;
                        put(rail, p[0], p[1], p[2]);
                    }
                }
            }
        }

        // ============================================================== beam

        /** {@code beam}: a structural member between two points with a square cross-section. */
        private void addBeam(SceneObject o) {
            int[] from = o.from();
            int[] to = o.to();
            if (from == null || to == null) return;
            String material = materialOf(o, "frame");
            int thickness = Math.max(1, (int) Math.round(SceneObject.dbl(o.raw(), "thickness", 1)));
            int[] delta = {to[0] - from[0], to[1] - from[1], to[2] - from[2]};
            int[] length = {Math.abs(delta[0]) + 1, Math.abs(delta[1]) + 1, Math.abs(delta[2]) + 1};
            int axis = length[0] >= length[1] && length[0] >= length[2] ? 0 : length[1] >= length[2] ? 1 : 2;
            int run = length[axis];
            int half = (thickness - 1) / 2;
            Group g = newGroup(o, Stage.BASE, material);
            for (int step = 0; step < run; step++) {
                double f = run == 1 ? 0 : (double) step / (run - 1);
                int[] base = new int[3];
                for (int k = 0; k < 3; k++) base[k] = (int) Math.round(from[k] + delta[k] * f);
                for (int ox = -half; ox <= thickness - 1 - half; ox++) {
                    for (int oy = -half; oy <= thickness - 1 - half; oy++) {
                        for (int oz = -half; oz <= thickness - 1 - half; oz++) {
                            put(g, base[0] + ox, base[1] + oy, base[2] + oz);
                        }
                    }
                }
            }
        }

        // ============================================================== column

        /** {@code column}: a plinth, a round or square shaft and a capital. */
        private void addColumn(SceneObject o) {
            int[] at = vec3Of(o.raw(), "at", null);
            if (at == null) return;
            String material = materialOf(o, "stone");
            int height = Math.max(1, (int) Math.round(SceneObject.dbl(o.raw(), "height", 4)));
            double radius = Math.max(0.5, SceneObject.dbl(o.raw(), "radius", 0.5));
            boolean square = SceneObject.str(o.raw(), "style").equalsIgnoreCase("square");
            int size = Math.max(1, (int) Math.round(radius * 2));
            double halfU = (size - 1) / 2.0;
            double cx = at[0] + halfU;
            double cz = at[2] + halfU;
            int xLo = (int) Math.floor(cx - radius - 2);
            int xHi = (int) Math.ceil(cx + radius + 2);
            int zLo = (int) Math.floor(cz - radius - 2);
            int zHi = (int) Math.ceil(cz + radius + 2);

            Group body = newGroup(o, Stage.BASE, material);
            for (int y = at[1]; y < at[1] + height; y++) {
                double grow = y == at[1] || y == at[1] + height - 1 ? 0.5 : 0;
                for (int x = xLo; x <= xHi; x++) {
                    for (int z = zLo; z <= zHi; z++) {
                        if (!inColumnCross(x, z, cx, cz, radius, halfU, square, grow)) continue;
                        put(body, x, y, z);
                    }
                }
            }
            Group accent = newGroup(o, Stage.OPENING, accentOf(o, material));
            for (int y = at[1]; y <= at[1] + 1; y++) {
                for (int x = xLo - 1; x <= xHi + 1; x++) {
                    for (int z = zLo - 1; z <= zHi + 1; z++) {
                        if (!inColumnCross(x, z, cx, cz, radius, halfU, square, 1)) continue;
                        put(accent, x, y, z);
                    }
                }
            }
            for (int y = Math.max(at[1], at[1] + height - 2); y <= at[1] + height - 1; y++) {
                for (int x = xLo - 1; x <= xHi + 1; x++) {
                    for (int z = zLo - 1; z <= zHi + 1; z++) {
                        if (!inColumnCross(x, z, cx, cz, radius, halfU, square, 1)) continue;
                        put(accent, x, y, z);
                    }
                }
            }
        }

        private boolean inColumnCross(int x, int z, double cx, double cz, double radius,
                                      double halfU, boolean square, double grow) {
            if (square) {
                return Math.abs(x - cx) <= halfU + grow + 0.5 && Math.abs(z - cz) <= halfU + grow + 0.5;
            }
            return Math.hypot(x - cx, z - cz) <= radius + grow;
        }

        // ============================================================== chimney

        /** {@code chimney}: a stack with a cap ring and an open flue. */
        private void addChimney(SceneObject o) {
            int[] at = vec3Of(o.raw(), "at", null);
            if (at == null) return;
            String material = materialOf(o, "brick");
            int w = Math.max(1, (int) Math.round(SceneObject.dbl(o.raw(), "w", 1)));
            int d = Math.max(1, (int) Math.round(SceneObject.dbl(o.raw(), "d", w)));
            int height = Math.max(1, (int) Math.round(SceneObject.dbl(o.raw(), "height", 6)));
            Group body = newGroup(o, Stage.BASE, material);
            fill(body, at, new int[]{at[0] + w - 1, at[1] + height - 1, at[2] + d - 1});
            if (!SceneObject.bool(o.raw(), "cap", true)) return;
            Group cap = newGroup(o, Stage.OPENING, accentOf(o, material));
            int top = at[1] + height;
            for (int x = at[0] - 1; x <= at[0] + w; x++) {
                for (int z = at[2] - 1; z <= at[2] + d; z++) {
                    boolean rim = x == at[0] - 1 || x == at[0] + w || z == at[2] - 1 || z == at[2] + d;
                    if (rim) put(cap, x, top, z);
                }
            }
        }

        // ============================================================== balustrade

        /** {@code balustrade}: a railing run with posts, balusters and ball caps. */
        private void addBalustrade(SceneObject o) {
            int[] from = o.from();
            int[] to = o.to();
            if (from == null || to == null) return;
            String material = materialOf(o, "stone");
            int axis = Math.abs(to[0] - from[0]) >= Math.abs(to[2] - from[2]) ? 0 : 2;
            int cross = axis == 0 ? 2 : 0;
            int lo = Math.min(from[axis], to[axis]);
            int hi = Math.max(from[axis], to[axis]);
            int baseY = Math.min(from[1], to[1]);
            int topY = Math.max(from[1], to[1]) > baseY ? Math.max(from[1], to[1]) : baseY + 1;
            int crossAt = from[cross];
            int spacing = Math.max(1, (int) Math.round(SceneObject.dbl(o.raw(), "spacing", 3)));
            String style = SceneObject.str(o.raw(), "style").toLowerCase(Locale.ROOT);
            if (style.isEmpty()) style = "baluster";
            Group g = newGroup(o, Stage.BASE, material);
            for (int u = lo; u <= hi; u++) {
                put(g, pos(axis, u, cross, crossAt, baseY));
                put(g, pos(axis, u, cross, crossAt, topY));
                boolean post = (u - lo) % spacing == 0 || u == hi;
                if (post || style.equals("solid")) {
                    for (int y = baseY + 1; y < topY; y++) put(g, pos(axis, u, cross, crossAt, y));
                } else if (style.equals("baluster")) {
                    if ((u - lo) % 2 == 1 && baseY + 1 < topY) put(g, pos(axis, u, cross, crossAt, baseY + 1));
                    if (topY - baseY > 2) put(g, pos(axis, u, cross, crossAt, topY - 1));
                }
            }
            if (!style.equals("fence")) {
                for (int u = lo; u <= hi; u += spacing) put(g, pos(axis, u, cross, crossAt, topY + 1));
                put(g, pos(axis, hi, cross, crossAt, topY + 1));
            }
        }

        /** @return the three coordinates of a wall-plane cell on the axis/cross pair */
        private int[] pos(int axis, int u, int crossAxis, int crossAt, int y) {
            int[] p = {0, y, 0};
            p[axis] = u;
            p[crossAxis] = crossAt;
            return p;
        }

        // ============================================================== detail

        /** {@code detail}: a free-form voxel cluster with per-cell materials and patterns. */
        private void addDetail(SceneObject o) {
            JsonElement cells = o.raw().get("cells");
            if (cells == null || !cells.isJsonArray()) return;
            List<String> materials = materialList(o);
            String pattern = SceneObject.str(o.raw(), "pattern").toLowerCase(Locale.ROOT);
            Group g = newGroup(o, Stage.BASE, materials.get(0));
            int index = 0;
            for (JsonElement e : cells.getAsJsonArray()) {
                if (!e.isJsonArray()) continue;
                JsonArray a = e.getAsJsonArray();
                if (a.size() < 3) continue;
                int x = (int) Math.round(a.get(0).getAsDouble());
                int y = (int) Math.round(a.get(1).getAsDouble());
                int z = (int) Math.round(a.get(2).getAsDouble());
                if (pattern.equals("hash") && Math.floorMod(x + y + z, 2) != 0) continue;
                if (pattern.equals("sparse") && random.nextDouble() <= 0.35) continue;
                String material = materials.size() > 1
                        ? materials.get(Math.floorMod(Math.abs(x * 31 + y * 17 + z * 7 + index), materials.size()))
                        : materials.get(0);
                put(g, x, y, z, material, blockFor(material));
                index++;
            }
        }

        private List<String> materialList(SceneObject o) {
            List<String> outMaterials = new ArrayList<>(2);
            JsonElement m = o.raw().get("material");
            if (m != null && m.isJsonArray()) {
                for (JsonElement e : m.getAsJsonArray()) {
                    if (e.isJsonPrimitive()) outMaterials.add(e.getAsString());
                }
            } else if (!o.material().isBlank()) {
                outMaterials.add(o.material());
            }
            if (outMaterials.isEmpty()) outMaterials.add("plain");
            return outMaterials;
        }

        // ============================================================== prism / roof

        /**
         * {@code prism} (also spelled {@code roof}): gable, hip, shed or pyramid roof with overhang
         * and closed gable ends.
         *
         * <p>The roof is modelled as the lower envelope of a few affine planes
         * {@code H(x,z) = min_i(a_i + bx_i*x + bz_i*z)} over the overhang-expanded footprint. Every
         * column is filled from the roof base up to {@code ceil(H)-1}, which produces a watertight
         * stepped mass with closed gable ends, and the top cell of the column records the exact slope
         * normal of the plane above it so the transfer can turn it into a stair facing uphill.</p>
         */
        private void addRoof(SceneObject o) {
            int[] from = o.from();
            int[] to = o.to();
            if (from == null || to == null) return;
            String material = materialOf(o, "roof");
            String style = SceneObject.str(o.raw(), "style").toLowerCase(Locale.ROOT);
            if (!style.equals("gable") && !style.equals("hip") && !style.equals("shed")
                    && !style.equals("pyramid")) {
                style = "gable";
            }
            int overhang = Math.max(0, (int) Math.round(SceneObject.dbl(o.raw(), "overhang", 0)));
            double pitch = Math.max(0.25, SceneObject.dbl(o.raw(), "pitch", 1));
            int x0 = Math.min(from[0], to[0]) - overhang;
            int x1 = Math.max(from[0], to[0]) + 1 + overhang;
            int z0 = Math.min(from[2], to[2]) - overhang;
            int z1 = Math.max(from[2], to[2]) + 1 + overhang;
            int baseY = from[1];
            double w = x1 - x0;
            double d = z1 - z0;
            String ridgeName = SceneObject.str(o.raw(), "ridge").toLowerCase(Locale.ROOT);
            String ridge = ridgeName.equals("x") || ridgeName.equals("z") ? ridgeName : w >= d ? "x" : "z";
            boolean back = SceneObject.str(o.raw(), "direction").equalsIgnoreCase("back");

            double eave = baseY + pitch;
            double[][] planes = switch (style) {
                case "shed" -> shedPlanes(eave, pitch, w, d, x0, z0, ridge, back);
                case "pyramid" -> pyramidPlanes(eave, pitch, w, d, x0, z0);
                case "hip" -> hipPlanes(eave, pitch, x0, x1, z0, z1);
                default -> gablePlanes(eave, pitch, x0, x1, z0, z1, ridge);
            };

            Group g = newGroup(o, Stage.ROOF, material);
            int maxTop = Math.min(baseY + 96, (int) Math.ceil(maxSurface(planes, x0, x1, z0, z1)) + 1);
            for (int x = x0; x < x1; x++) {
                for (int z = z0; z < z1; z++) {
                    double h = surfaceHeight(planes, x + 0.5, z + 0.5);
                    int top = Math.min(maxTop, (int) Math.ceil(h) - 1);
                    if (top < baseY) continue;
                    for (int y = baseY; y <= top; y++) put(g, x, y, z);
                    double[] n = planeNormal(pickPlane(planes, x + 0.5, z + 0.5));
                    g.slope.put(VoxelModel.key(x, top, z), n);
                }
            }
        }

        private double[][] gablePlanes(double eave, double pitch, int x0, int x1, int z0, int z1, String ridge) {
            if (ridge.equals("x")) {
                return new double[][]{
                        {eave - pitch * z0, 0, pitch},
                        {eave + pitch * z1, 0, -pitch}};
            }
            return new double[][]{
                    {eave - pitch * x0, pitch, 0},
                    {eave + pitch * x1, -pitch, 0}};
        }

        private double[][] hipPlanes(double eave, double pitch, int x0, int x1, int z0, int z1) {
            return new double[][]{
                    {eave - pitch * x0, pitch, 0},
                    {eave + pitch * x1, -pitch, 0},
                    {eave - pitch * z0, 0, pitch},
                    {eave + pitch * z1, 0, -pitch}};
        }

        private double[][] pyramidPlanes(double eave, double pitch, double w, double d, int x0, int z0) {
            double apex = eave + pitch * Math.min(w, d) / 2;
            double sx = (apex - eave) / (w / 2);
            double sz = (apex - eave) / (d / 2);
            return new double[][]{
                    {eave - sx * x0, sx, 0},
                    {eave + sx * (x0 + w), -sx, 0},
                    {eave - sz * z0, 0, sz},
                    {eave + sz * (z0 + d), 0, -sz}};
        }

        private double[][] shedPlanes(double eave, double pitch, double w, double d, int x0, int z0,
                                      String ridge, boolean back) {
            boolean highAtLowZ = !back;
            if (ridge.equals("z")) {
                // Slope rises along x: high on one x side, low on the other.
                return new double[][]{highAtLowZ
                        ? new double[]{eave + pitch * (w - 1), -pitch, 0}
                        : new double[]{eave - pitch * (w - 1), pitch, 0}};
            }
            return new double[][]{highAtLowZ
                    ? new double[]{eave + pitch * (d - 1), 0, -pitch}
                    : new double[]{eave - pitch * (d - 1), 0, pitch}};
        }

        private static double surfaceHeight(double[][] planes, double x, double z) {
            double h = Double.MAX_VALUE;
            for (double[] p : planes) {
                double v = p[0] + p[1] * x + p[2] * z;
                if (v < h) h = v;
            }
            return h;
        }

        private static double[] pickPlane(double[][] planes, double x, double z) {
            double[] best = planes[0];
            double h = Double.MAX_VALUE;
            for (double[] p : planes) {
                double v = p[0] + p[1] * x + p[2] * z;
                if (v < h - 1e-12) {
                    h = v;
                    best = p;
                }
            }
            return best;
        }

        private static double maxSurface(double[][] planes, int x0, int x1, int z0, int z1) {
            double h = -Double.MAX_VALUE;
            int[][] corners = {{x0, z0}, {x1, z0}, {x0, z1}, {x1, z1}, {(x0 + x1) / 2, (z0 + z1) / 2}};
            for (int[] c : corners) h = Math.max(h, surfaceHeight(planes, c[0], c[1]));
            return h;
        }

        /** Unit upward normal of the plane {@code y = a + bx*x + bz*z}. */
        private static double[] planeNormal(double[] plane) {
            return unit(new double[]{-plane[1], 1, -plane[2]});
        }

        // ============================================================== curved shapes

        /**
         * {@code cylinder}: a vertical tube or drum, optionally hollow, optionally crenellated.
         *
         * <p>Cells whose centre lies inside the shape are kept, so a hollow tower wall with a
         * thickness of one is exactly one cell thick.</p>
         */
        private void addCylinder(SceneObject o) {
            int[] center = o.center();
            if (center == null) return;
            String material = materialOf(o, "stone");
            double radius = Math.max(0.5, SceneObject.dbl(o.raw(), "radius", 2));
            int height = Math.max(1, (int) Math.round(SceneObject.dbl(o.raw(), "height", 4)));
            boolean hollow = SceneObject.bool(o.raw(), "hollow", false);
            boolean crenellated = SceneObject.bool(o.raw(), "crenellated", false);
            double cx = center[0] + 0.5;
            double cz = center[2] + 0.5;
            double inner = Math.max(0, radius - Math.max(0.5, SceneObject.dbl(o.raw(), "thickness", 1)));
            int xLo = (int) Math.floor(cx - radius - 1);
            int xHi = (int) Math.ceil(cx + radius + 1);
            int zLo = (int) Math.floor(cz - radius - 1);
            int zHi = (int) Math.ceil(cz + radius + 1);
            Group g = newGroup(o, Stage.BASE, material);
            for (int y = center[1]; y < center[1] + height; y++) {
                for (int x = xLo; x <= xHi; x++) {
                    for (int z = zLo; z <= zHi; z++) {
                        double r = Math.hypot(x + 0.5 - cx, z + 0.5 - cz);
                        if (r > radius + 0.5) continue;
                        if (hollow && r < inner) continue;
                        if (crenellated && y == center[1] + height - 1) {
                            double angle = Math.atan2(z + 0.5 - cz, x + 0.5 - cx);
                            int teeth = Math.max(8, (int) Math.round(radius * 8));
                            int index = (int) Math.floor(((angle + Math.PI) / (Math.PI * 2)) * teeth);
                            if (index % 2 != 0) continue;
                        }
                        put(g, x, y, z);
                    }
                }
            }
        }

        /** {@code cone}: a tapering spire for tower roofs. */
        private void addCone(SceneObject o) {
            int[] center = o.center();
            if (center == null) return;
            String material = materialOf(o, "roof");
            double radius = Math.max(0.5, SceneObject.dbl(o.raw(), "radius", 2));
            int height = Math.max(1, (int) Math.round(SceneObject.dbl(o.raw(), "height", 4)));
            double shell = Math.max(0.5, SceneObject.dbl(o.raw(), "thickness", 1));
            boolean hollow = SceneObject.bool(o.raw(), "hollow", false);
            double cx = center[0] + 0.5;
            double cz = center[2] + 0.5;
            int xLo = (int) Math.floor(cx - radius - 1);
            int xHi = (int) Math.ceil(cx + radius + 1);
            int zLo = (int) Math.floor(cz - radius - 1);
            int zHi = (int) Math.ceil(cz + radius + 1);
            Group g = newGroup(o, Stage.ROOF, material);
            double slope = Math.max(0.25, radius / Math.max(1, height));
            for (int i = 0; i < height; i++) {
                double t = height == 1 ? 0 : (double) i / (height - 1);
                double r = radius * (1 - t);
                int y = center[1] + i;
                for (int x = xLo; x <= xHi; x++) {
                    for (int z = zLo; z <= zHi; z++) {
                        double rr = Math.hypot(x + 0.5 - cx, z + 0.5 - cz);
                        if (rr > r + 0.5) continue;
                        if (hollow && rr < Math.max(0, r - shell)) continue;
                        put(g, x, y, z);
                        // The cone surface falls away from the axis at `slope`; the top cell of a
                        // column reports that tilt so the transfer can build a stair ring.
                        if (i == height - 1 || !hollow) {
                            double dx = x + 0.5 - cx;
                            double dz = z + 0.5 - cz;
                            double len = Math.hypot(dx, dz);
                            if (len < 1e-9) {
                                g.slope.put(VoxelModel.key(x, y, z), new double[]{0, 1, 0});
                            } else {
                                g.slope.put(VoxelModel.key(x, y, z),
                                        new double[]{dx / len * slope, 1, dz / len * slope});
                            }
                        }
                    }
                }
            }
        }

        /** {@code sphere}: a ball, a dome ({@code half=top}) or a bowl ({@code half=bottom}). */
        private void addSphere(SceneObject o) {
            int[] center = o.center();
            if (center == null) return;
            String material = materialOf(o, "stone");
            double radius = Math.max(0.5, SceneObject.dbl(o.raw(), "radius", 2));
            String half = SceneObject.str(o.raw(), "half").toLowerCase(Locale.ROOT);
            boolean top = !half.equals("bottom") && !half.equals("full");
            boolean bottom = half.equals("bottom");
            boolean hollow = SceneObject.bool(o.raw(), "hollow", false);
            double cx = center[0] + 0.5;
            double cy = center[1] + 0.5;
            double cz = center[2] + 0.5;
            double thickness = Math.max(0.5, SceneObject.dbl(o.raw(), "thickness", 1));
            int xLo = (int) Math.floor(cx - radius - 1);
            int xHi = (int) Math.ceil(cx + radius + 1);
            int zLo = (int) Math.floor(cz - radius - 1);
            int zHi = (int) Math.ceil(cz + radius + 1);
            int yLo = top ? (int) Math.floor(cy) : (int) Math.floor(cy - radius - 1);
            int yHi = bottom ? (int) Math.ceil(cy) : (int) Math.ceil(cy + radius + 1);
            Group g = newGroup(o, Stage.BASE, material);
            for (int y = yLo; y <= yHi; y++) {
                for (int x = xLo; x <= xHi; x++) {
                    for (int z = zLo; z <= zHi; z++) {
                        double dist = Math.sqrt((x + 0.5 - cx) * (x + 0.5 - cx)
                                + (y + 0.5 - cy) * (y + 0.5 - cy)
                                + (z + 0.5 - cz) * (z + 0.5 - cz));
                        if (dist > radius + 0.5) continue;
                        if (hollow && dist < radius - thickness) continue;
                        put(g, x, y, z);
                    }
                }
            }
        }

        /**
         * {@code ring}: a horizontal band around a rectangular footprint — a cornice, a string course
         * or a plinth. {@code profile} controls whether the band flares outwards as it rises
         * ({@code cornice}), flares at the bottom ({@code base}) or only at the lower edge
         * ({@code eave}); {@code flat} keeps the band vertical.
         */
        private void addRing(SceneObject o) {
            int[] from = o.from();
            int[] to = o.to();
            String material = materialOf(o, "trim");
            int x0;
            int x1;
            int z0;
            int z1;
            int baseY;
            if (from != null && to != null) {
                x0 = Math.min(from[0], to[0]);
                x1 = Math.max(from[0], to[0]);
                z0 = Math.min(from[2], to[2]);
                z1 = Math.max(from[2], to[2]);
                baseY = (int) Math.round(SceneObject.dbl(o.raw(), "y", from[1]));
            } else {
                x0 = bounds.min(0);
                x1 = bounds.max(0);
                z0 = bounds.min(2);
                z1 = bounds.max(2);
                baseY = bounds.min(1);
            }
            int thickness = Math.max(1, (int) Math.round(SceneObject.dbl(o.raw(), "thickness", 1)));
            String profile = SceneObject.str(o.raw(), "profile").toLowerCase(Locale.ROOT);
            if (profile.isEmpty()) profile = "flat";
            int layers = profile.equals("cornice") ? Math.max(2, thickness) : thickness;
            Group g = newGroup(o, Stage.BASE, material);
            for (int layer = 0; layer < layers; layer++) {
                int grow = switch (profile) {
                    case "cornice" -> layer;
                    case "base" -> Math.max(0, layers - 1 - layer);
                    case "eave" -> layer == 0 ? 1 : 0;
                    default -> 0;
                };
                int ox0 = x0 - grow;
                int ox1 = x1 + grow;
                int oz0 = z0 - grow;
                int oz1 = z1 + grow;
                for (int x = ox0; x <= ox1; x++) {
                    for (int z = oz0; z <= oz1; z++) {
                        boolean inOuter = x >= ox0 && x <= ox1 && z >= oz0 && z <= oz1;
                        boolean inInner = x >= ox0 + thickness && x <= ox1 - thickness
                                && z >= oz0 + thickness && z <= oz1 - thickness;
                        if (!inOuter || inInner) continue;
                        put(g, x, baseY + layer, z);
                    }
                }
            }
            if (profile.equals("cornice") || profile.equals("eave")) {
                // A cap course in the accent material closes the top of the band. It copies the
                // footprint of the band's last layer exactly, so every cap cell rests on a band cell
                // instead of hanging over the flared edge.
                int lastGrow = switch (profile) {
                    case "cornice" -> layers - 1;
                    case "base" -> 0;
                    case "eave" -> 0;
                    default -> 0;
                };
                Group cap = newGroup(o, Stage.OPENING, accentOf(o, material));
                int top = baseY + layers;
                int ox0 = x0 - lastGrow;
                int ox1 = x1 + lastGrow;
                int oz0 = z0 - lastGrow;
                int oz1 = z1 + lastGrow;
                for (int x = ox0; x <= ox1; x++) {
                    for (int z = oz0; z <= oz1; z++) {
                        boolean inOuter = x >= ox0 && x <= ox1 && z >= oz0 && z <= oz1;
                        boolean inInner = x >= ox0 + thickness && x <= ox1 - thickness
                                && z >= oz0 + thickness && z <= oz1 - thickness;
                        if (!inOuter || inInner) continue;
                        put(cap, x, top, z);
                    }
                }
            }
        }

        // ============================================================== openings

        /**
         * Stages a standalone {@code window}, {@code door} or {@code arch} object. The hole is cut
         * later, once every solid exists, so an opening can punch a box it does not own.
         */
        private void stageOpening(SceneObject o) {
            Rect r = resolveRect(o.raw(), o.type());
            if (!bounds.isEmpty()) {
                r = r.clampTo(bounds.min(r.uAxis()), bounds.max(r.uAxis()),
                        bounds.min(1), bounds.max(1));
                if (r.u1() < r.u0() || r.y1() < r.y0()) return;
            }
            int dLo = bounds.isEmpty() ? -OPENING_MARGIN : bounds.min(r.dAxis()) - OPENING_MARGIN;
            int dHi = bounds.isEmpty() ? OPENING_MARGIN : bounds.max(r.dAxis()) + OPENING_MARGIN;
            staged.add(new Opening(o, r, dLo, dHi));
        }

        /**
         * Cuts every staged opening into the finished scene and decorates it. For each aperture cell
         * the search starts outside the scene and walks inwards until it hits the first occupied
         * cell; that cell (and the run of cells behind it, for a through-hole) is deleted, so the
         * result is a real hole rather than a decal.
         */
        private void resolveOpenings() {
            for (Opening opening : staged) {
                Rect r = opening.rect;
                Map<Long, Integer> outer = new LinkedHashMap<>();
                for (int u = r.u0(); u <= r.u1(); u++) {
                    for (int y = r.y0(); y <= r.y1(); y++) {
                        if (!r.allows(u, y)) continue;
                        int d = r.sign() > 0 ? opening.dHi : opening.dLo;
                        int step = -r.sign();
                        Integer first = null;
                        while (r.sign() > 0 ? d >= opening.dLo : d <= opening.dHi) {
                            int[] p = {0, y, 0};
                            p[r.uAxis()] = u;
                            p[r.dAxis()] = d;
                            if (occupied(p[0], p[1], p[2])) {
                                first = d;
                                break;
                            }
                            d += step;
                        }
                        if (first == null) continue;
                        outer.put(VoxelModel.key(u, y, 0), first);
                        int maxDepth = r.through() ? (opening.dHi - opening.dLo + 1) : Math.max(1, r.depth());
                        int removed = 0;
                        int dd = first;
                        while (removed < maxDepth) {
                            int[] p = {0, y, 0};
                            p[r.uAxis()] = u;
                            p[r.dAxis()] = dd;
                            if (!occupied(p[0], p[1], p[2])) break;
                            cut(VoxelModel.key(p[0], p[1], p[2]));
                            removed++;
                            dd += step;
                        }
                    }
                }
                int dOuter;
                if (outer.isEmpty()) {
                    // No host wall: anchor the frame on the first free plane in front of the scene,
                    // so a lone window still has a frame and glass instead of vanishing.
                    dOuter = r.sign() > 0
                            ? (bounds.isEmpty() ? OPENING_MARGIN : bounds.max(r.dAxis()) + 1)
                            : (bounds.isEmpty() ? -OPENING_MARGIN : bounds.min(r.dAxis()) - 1);
                } else {
                    dOuter = r.sign() > 0 ? Integer.MIN_VALUE : Integer.MAX_VALUE;
                    for (int v : outer.values()) {
                        dOuter = r.sign() > 0 ? Math.max(dOuter, v) : Math.min(dOuter, v);
                    }
                }
                addFrame(opening.obj, r, dOuter);
                // A lone opening (no host wall) has no per-cell plane, so its glass sits on the same
                // plane as its frame. The frame is a ring around the aperture, so the two never
                // fight for a cell.
                addGlass(opening.obj, r, outer.isEmpty() ? null : outer, dOuter);
            }
        }

        /**
         * Builds the frame ring of an opening on plane {@code d}.
         *
         * <p>Frame cells that coincide with wall cells only change the material, they do not add
         * volume; the sill, shutters and canopy deliberately stick out and do add cells.</p>
         */
        private void addFrame(SceneObject o, Rect r, int d) {
            String material = r.frame() != null && !r.frame().isBlank()
                    ? r.frame()
                    : r.kind().equals("door") ? "frame" : "wood";
            Group frame = newGroup(o, Stage.OPENING, material);
            for (int u = r.u0() - 1; u <= r.u1() + 1; u++) {
                putAt(frame, r, u, r.y0() - 1, d);
                putAt(frame, r, u, r.y1() + 1, d);
            }
            for (int y = r.y0(); y <= r.y1(); y++) {
                putAt(frame, r, r.u0() - 1, y, d);
                putAt(frame, r, r.u1() + 1, y, d);
            }
            if (r.sill() || r.kind().equals("window")) {
                for (int u = r.u0() - 1; u <= r.u1() + 1; u++) putAt(frame, r, u, r.y0() - 2, d);
            }
            if (r.shutters()) {
                for (int y = r.y0(); y <= r.y1(); y++) {
                    putAt(frame, r, r.u0() - 2, y, d);
                    putAt(frame, r, r.u1() + 2, y, d);
                }
            }
            if (r.canopy()) {
                for (int u = r.u0() - 1; u <= r.u1() + 1; u++) {
                    for (int offset = 1; offset <= 2; offset++) {
                        putAt(frame, r, u, r.y1() + 2, d + r.sign() * offset);
                    }
                }
            }
        }

        /**
         * Fills the aperture with glass. {@code perCell} maps a cell key ({@code key(u,y,0)}) to the
         * plane the glass sits on, which is how a standalone window glazes the outermost wall layer it
         * cut; {@code fixed} is used by the {@code box}/{@code wall} variants, whose wall plane is
         * known up front.
         */
        private void addGlass(SceneObject o, Rect r, Map<Long, Integer> perCell, int fixed) {
            String material = r.glass() != null && !r.glass().isBlank()
                    ? r.glass()
                    : SceneObject.str(o.raw(), "glass");
            if (material.isBlank()) material = "glass";
            boolean wanted = r.kind().equals("window") || (r.glass() != null && !r.glass().isBlank())
                    || !SceneObject.str(o.raw(), "glass").isBlank();
            if (!wanted) return;
            Group glass = newGroup(o, Stage.OPENING, material);
            for (int u = r.u0(); u <= r.u1(); u++) {
                for (int y = r.y0(); y <= r.y1(); y++) {
                    if (!r.allows(u, y)) continue;
                    Integer plane = perCell == null ? Integer.valueOf(fixed) : perCell.get(VoxelModel.key(u, y, 0));
                    if (plane == null) continue;
                    putAt(glass, r, u, y, plane);
                }
            }
        }

        /**
         * Resolves the many accepted opening spellings ({@code {x,y,w,h}}, {@code {at:[u,y],w,h}},
         * {@code {u,y,w,h}}) into one {@link Rect} clamped to the host when a host is given.
         */
        private Rect resolveRect(JsonObject op, String type) {
            String wall = SceneObject.str(op, "wall").toLowerCase(Locale.ROOT);
            if (!wall.equals("north") && !wall.equals("south") && !wall.equals("east") && !wall.equals("west")) {
                wall = "south";
            }
            int uAxis = wall.equals("east") || wall.equals("west") ? 2 : 0;
            int dAxis = uAxis == 0 ? 2 : 0;
            int sign = wall.equals("south") || wall.equals("east") ? 1 : -1;
            String kind = SceneObject.str(op, "kind").toLowerCase(Locale.ROOT);
            if (kind.isEmpty()) kind = type.equals("door") ? "door" : type.equals("arch") ? "arch" : "window";
            int u0;
            int y0;
            JsonElement at = op.get("at");
            if (at != null && at.isJsonArray() && at.getAsJsonArray().size() >= 2) {
                JsonArray a = at.getAsJsonArray();
                u0 = (int) Math.round(a.get(0).getAsDouble());
                y0 = (int) Math.round(a.get(1).getAsDouble());
            } else {
                u0 = (int) Math.round(SceneObject.dbl(op, op.has("u") ? "u" : "x", 0));
                y0 = (int) Math.round(SceneObject.dbl(op, "y", 0));
            }
            int w = Math.max(1, (int) Math.round(SceneObject.dbl(op, "w", 1)));
            int h = Math.max(1, (int) Math.round(SceneObject.dbl(op, "h", 2)));
            boolean arch = SceneObject.bool(op, "arch", false) || kind.equals("arch");
            int u1 = u0 + w - 1;
            int y1 = y0 + h - 1;
            if (arch && !kind.equals("door")) y1 += (int) Math.ceil(w / 2.0);
            Set<Long> filter = null;
            JsonElement cells = op.get("cells");
            if (cells != null && cells.isJsonArray()) {
                filter = new HashSet<>();
                for (JsonElement e : cells.getAsJsonArray()) {
                    if (!e.isJsonArray()) continue;
                    JsonArray a = e.getAsJsonArray();
                    if (a.size() < 2) continue;
                    filter.add(VoxelModel.key((int) Math.round(a.get(0).getAsDouble()),
                            (int) Math.round(a.get(1).getAsDouble()), 0));
                }
            }
            Rect r = new Rect(wall, uAxis, dAxis, sign, u0, u1, y0, y1, arch, kind,
                    emptyToNull(SceneObject.str(op, "frame")),
                    emptyToNull(SceneObject.str(op, "glass")),
                    SceneObject.bool(op, "sill", false),
                    SceneObject.bool(op, "shutters", false),
                    SceneObject.bool(op, "canopy", false),
                    Math.max(1, (int) Math.round(SceneObject.dbl(op, "depth", 2))),
                    kind.equals("door") || kind.equals("arch") || SceneObject.bool(op, "through", false),
                    filter);
            return r;
        }

        private static String emptyToNull(String s) {
            return s == null || s.isBlank() ? null : s;
        }
    }

    // ================================================================== aperture geometry

    /**
     * The arch curve: above the spring line the aperture narrows like a semicircle.
     *
     * @param u      cell column along the wall
     * @param y      cell row
     * @param u0     first column of the aperture
     * @param u1     last column of the aperture
     * @param y1     last row of the aperture
     * @param spring first row of the curved part
     * @return true when the cell is inside the arch
     */
    private static boolean insideArch(int u, int y, int u0, int u1, int y1, int spring) {
        if (y < spring) return true;
        double w = u1 - u0 + 1;
        double ry = y1 - spring + 1;
        double cx = (u0 + u1 + 1) / 2.0;
        double nx = (u + 0.5 - cx) / (w / 2);
        double ny = (y + 0.5 - spring) / ry;
        return nx * nx + ny * ny <= 1.0001;
    }

    /** @return the strings of a JSON array of primitives, or an empty list */
    private static List<String> strings(JsonElement e) {
        List<String> out = new ArrayList<>(4);
        if (e == null || !e.isJsonArray()) return out;
        for (JsonElement v : e.getAsJsonArray()) {
            if (v.isJsonPrimitive()) out.add(v.getAsString().toLowerCase(Locale.ROOT));
        }
        return out;
    }
}
