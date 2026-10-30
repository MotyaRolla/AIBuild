package dev.aibuild.analysis;

import dev.aibuild.model.BlockInfo;
import dev.aibuild.model.BlockSpec;
import dev.aibuild.model.VoxelModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renders a {@link VoxelModel} as compact, unambiguous ASCII for an LLM.
 *
 * <p>Output sections (each can be disabled through {@link Options}):</p>
 * <ol>
 *   <li>header — dimension, bounds and the coordinate convention;</li>
 *   <li>{@code LEGEND} — one line per block state: {@code symbol = canonical_state count}, most frequent first;</li>
 *   <li>{@code LAYER y=N} — a top-down slice per y with an x ruler, one row per z ({@code z=  3 |##..a..##|});</li>
 *   <li>{@code ELEVATION} — first visible block along each view ray for the four sides, top row first;</li>
 *   <li>{@code TOP VIEW} — highest block per column plus a {@code HEIGHT MAP} (y in base 36).</li>
 * </ol>
 *
 * <p>Symbols: {@code .} is air (and explicit air), {@code ,} is untouched terrain (unset cell with {@code y <= 0},
 * layers only), everything else is a block state from the legend. Single characters are used while the palette
 * fits into the pool (92 symbols); beyond that <b>all</b> cells switch to two-character symbols separated by a
 * space. Symbols are assigned by descending frequency, then by state string, so output is deterministic.</p>
 *
 * <p>Stateless and thread-safe: {@link #render} only reads the model (which must not be mutated concurrently) and
 * {@link BlockInfo} implementations are required to be thread-safe. No mutable static state is used.</p>
 */
public final class TextRenderer {

    private TextRenderer() {
    }

    /** Hard cap for a rendered document; beyond this the output is truncated with a note. */
    public static final int MAX_OUTPUT_CHARS = 420_000;

    /** Single-character symbol pool, built deterministically (immutable String; index with {@link #poolChar}). */
    private static final String POOL = buildPool();
    /** Row prefix width of {@code "z=  0 |"}. */
    private static final String GUTTER = "       ";
    private static final int GUTTER_LEN = 7;

    private static char poolChar(int i) {
        return POOL.charAt(i);
    }

    private static String buildPool() {
        StringBuilder sb = new StringBuilder(92);
        String preferred = "#$%&*+-/";
        sb.append(preferred);
        for (char c = '0'; c <= '9'; c++) sb.append(c);
        for (char c = 'A'; c <= 'Z'; c++) sb.append(c);
        for (char c = 'a'; c <= 'z'; c++) sb.append(c);
        for (char c = 33; c <= 126; c++) {
            if (c == '.' || c == ',' || c == ' ' || c == '|' || sb.indexOf(String.valueOf(c)) >= 0) continue;
            sb.append(c);
        }
        return sb.toString();
    }

    /** Rendering options. Mutable bean with fluent setters; not thread-safe but only read during a render. */
    public static final class Options {
        /** Emit {@code LAYER y=N} slices (default true). */
        public boolean layers = true;
        /** Emit the four side elevations (default true). */
        public boolean elevations = true;
        /** Emit the top view and height map (default true). */
        public boolean topView = true;
        /**
         * Crop box {@code {minX,minY,minZ,maxX,maxY,maxZ}} (corners in any order), or null for the whole model.
         * Invalid/oversized boxes fall back to the model bounds.
         */
        public int[] region = null;
        /** Optional document title printed in the header line. */
        public String title = null;
        /** Force (true) / forbid (false) compact run-list layers; null = automatic for large builds. */
        public Boolean compact = null;

        public Options layers(boolean v) {
            this.layers = v;
            return this;
        }

        public Options elevations(boolean v) {
            this.elevations = v;
            return this;
        }

        public Options topView(boolean v) {
            this.topView = v;
            return this;
        }

        /** Sets the crop box; null or an array shorter than 6 disables cropping. */
        public Options region(int[] box) {
            this.region = box == null || box.length < 6 ? null : box.clone();
            return this;
        }

        public Options compact(Boolean v) {
            this.compact = v;
            return this;
        }

        public Options title(String t) {
            this.title = t;
            return this;
        }

        /** Only one section enabled: layers. Handy for very large builds. */
        public static Options layersOnly() {
            return new Options().elevations(false).topView(false);
        }
    }

    /**
     * Renders {@code model} as ASCII.
     *
     * @param model model to render (read-only use)
     * @param info  block knowledge (unused for symbol assignment today, kept for API symmetry and future
     *              canonicalization; must be non-null)
     * @param o     options, {@code null} = defaults
     * @return the rendered text, never null; empty models yield a short "empty" document
     * @throws IllegalArgumentException when {@code model} or {@code info} is null
     */
    public static String render(VoxelModel model, BlockInfo info, Options o) {
        if (model == null) throw new IllegalArgumentException("model is null");
        if (info == null) throw new IllegalArgumentException("info is null");
        Options opt = o == null ? new Options() : o;

        int[] nonAir = model.solidBounds();
        if (nonAir == null) {
            return (opt.title == null ? "" : opt.title + "\n") + "AIBUILD RENDER empty: no blocks\n";
        }
        int[] box = opt.region != null ? normalizeRegion(opt.region, nonAir) : nonAir;
        Grid g;
        String note = "";
        try {
            g = Grid.ofRegion(model, box);
        } catch (RuntimeException e) {
            g = null;
        }
        if (g == null) {
            // the crop (or the model) is too large to materialise: retry with the plain non-air bounds
            box = nonAir;
            try {
                g = Grid.ofRegion(model, box);
            } catch (RuntimeException e) {
                g = null;
            }
            note = "NOTE: the requested region was too large and has been ignored.\n";
            if (g == null) {
                return "AIBUILD RENDER error: the model bounding box is too large to render ("
                        + (box[3] - box[0] + 1) + "x" + (box[4] - box[1] + 1) + "x" + (box[5] - box[2] + 1)
                        + "); use a smaller region\n";
            }
        }

        // ----- symbol assignment: 2-char codes, 1st char = material, 2nd char = orientation glyph
        Map<BlockSpec, Integer> counts = new HashMap<>();
        for (int i = 0; i < (int) g.volume; i++) {
            BlockSpec s = g.at(i);
            if (s == null || s.isAir()) continue;
            counts.merge(s, 1, Integer::sum);
        }
        List<Map.Entry<BlockSpec, Integer>> palette = new ArrayList<>(counts.entrySet());
        palette.sort(Comparator.<Map.Entry<BlockSpec, Integer>>comparingInt(Map.Entry::getValue).reversed()
                .thenComparing(e -> e.getKey().toString()));
        Map<BlockSpec, String> symbols = new LinkedHashMap<>();
        Map<String, Map<String, Integer>> legend = new LinkedHashMap<>(); // code -> display state -> count
        boolean twoChar = true;
        if (!assignSemantic(palette, info, symbols, legend)) {
            symbols.clear();
            legend.clear();
            for (int i = 0; i < palette.size(); i++) {
                String code = symbol(i, true);
                symbols.put(palette.get(i).getKey(), code);
                legend.computeIfAbsent(code, k -> new LinkedHashMap<>()).merge(palette.get(i).getKey().toString(), palette.get(i).getValue(), Integer::sum);
            }
        }

        StringBuilder sb = new StringBuilder(4096);
        if (!note.isEmpty()) sb.append(note);
        appendHeader(sb, opt, g, palette.size(), twoChar);
        appendLegend(sb, legend);

        // large builds: list layers as coordinate runs instead of character grids (exact x ranges, much smaller)
        boolean compact = opt.compact != null ? opt.compact : (g.sx > 34 || g.sz > 34 || (long) g.sx * g.sz * g.sy > 40_000L);
        if (opt.layers && compact) {
            sb.append("LAYERS (compact, large build): one line per row z listing runs \"x1-x2 CODE\" (or \"x CODE\");\n")
                    .append("  cells not listed are air (or untouched ground ,, at y<=0). Codes as in the LEGEND.\n");
        }
        if (opt.layers) {
            for (int y = g.minY; y <= g.maxY; y++) {
                if (sb.length() > MAX_OUTPUT_CHARS) {
                    sb.append("... output truncated at ").append(MAX_OUTPUT_CHARS)
                            .append(" chars (use region= to crop or disable elevations/topView)\n");
                    break;
                }
                if (compact) appendLayerRuns(sb, g, y, symbols);
                else appendLayer(sb, g, y, symbols, twoChar);
            }
        }
        if (opt.elevations) {
            if (sb.length() > MAX_OUTPUT_CHARS) {
                sb.append("... output truncated\n");
            } else {
                appendElevation(sb, g, symbols, twoChar, "south");
                appendElevation(sb, g, symbols, twoChar, "north");
                appendElevation(sb, g, symbols, twoChar, "east");
                appendElevation(sb, g, symbols, twoChar, "west");
            }
        }
        if (opt.topView) {
            if (sb.length() > MAX_OUTPUT_CHARS) {
                sb.append("... output truncated\n");
            } else if (compact) {
                appendTopViewRuns(sb, g, symbols);
            } else {
                appendTopView(sb, g, symbols, twoChar);
            }
        }
        return sb.toString();
    }

    /**
     * Convenience overload rendering with {@code TextRenderer.Options} defaults (layers + elevations + top view).
     *
     * @param model model to render
     * @param info  block knowledge
     * @return the rendered text
     */
    public static String render(VoxelModel model, BlockInfo info) {
        return render(model, info, null);
    }

    // ------------------------------------------------------------------ sections

    private static void appendHeader(StringBuilder sb, Options o, Grid g, int states, boolean twoChar) {
        if (o.title != null && !o.title.isBlank()) sb.append(o.title.trim()).append('\n');
        sb.append("AIBUILD RENDER ").append(g.sx).append('x').append(g.sy).append('x').append(g.sz)
                .append(" (x").append(g.minX).append("..").append(g.maxX)
                .append(" y").append(g.minY).append("..").append(g.maxY)
                .append(" z").append(g.minZ).append("..").append(g.maxZ).append(") ")
                .append("states=").append(states)
                .append(twoChar ? " symbols=2char" : "")
                .append('\n');
        sb.append("Coordinates: x -> east (columns), z -> south (rows), y -> up; front faces SOUTH (+z).")
                .append('\n');
        sb.append("Each cell is a 2-char code (see LEGEND); .. = air; ,, = untouched terrain (unset ground y<=0,")
                .append(" layers only). In elevation/top views a blank cell means no block along that ray.")
                .append(" Read coordinates from the rulers: column = x (or z in east/west views), row = z (or y).\n");
    }

    private static void appendLegend(StringBuilder sb, Map<String, Map<String, Integer>> legend) {
        sb.append("LEGEND - every cell is a 2-char code: 1st char = material, 2nd char = orientation.\n");
        sb.append("  stairs:  ^ > v < = facing north/east/south/west (side of the TALL BACK; you walk up toward it)\n");
        sb.append("           n e s w = upside-down (half=top) stairs facing north/east/south/west\n");
        sb.append("  slabs:   _ = bottom half, ~ = top half, = = double;   logs/pillars: - axis x, / axis z, doubled = axis y\n");
        sb.append("  other directional blocks (doors, trapdoors when open, torches, ladders, beds, chests...): ^ > v < = facing;\n");
        sb.append("           closed trapdoors _ bottom / ~ top; u/d = facing up/down; h = hanging; doubled char = no orientation\n");
        sb.append("  .. = air, ,, = untouched natural ground. Auto-computed props (stair shape, fence/pane/wall connections,\n");
        sb.append("  waterlogged, leaves distance) are not shown.\n");
        if (legend.isEmpty()) {
            sb.append("  (no block states)\n");
            return;
        }
        for (Map.Entry<String, Map<String, Integer>> e : legend.entrySet()) {
            sb.append("  ").append(e.getKey()).append(" = ");
            boolean first = true;
            for (Map.Entry<String, Integer> st : e.getValue().entrySet()) {
                if (!first) sb.append(" | ");
                sb.append(st.getKey()).append(" x").append(st.getValue());
                first = false;
            }
            sb.append('\n');
        }
    }

    /** Props the renderer hides because they are computed automatically or irrelevant for design review. */
    private static final java.util.Set<String> AUTO_PROPS = java.util.Set.of("shape", "north", "east", "south", "west",
            "up", "waterlogged", "distance", "persistent", "snowy", "occupied", "powered", "in_wall", "bottom");
    private static final String MATERIAL_POOL = buildMaterialPool();

    private static String buildMaterialPool() {
        // letters and digits first (most readable), glyph letters last; never '.', ',', '|' or space
        StringBuilder sb = new StringBuilder();
        for (char c = 'A'; c <= 'Z'; c++) sb.append(c);
        for (char c = 'a'; c <= 'z'; c++) if ("neswvhud".indexOf(c) < 0) sb.append(c);
        for (char c = '0'; c <= '9'; c++) sb.append(c);
        for (char c : "#$%&*+@?;:'\"`()[]{}\\".toCharArray()) sb.append(c);
        for (char c : "neswvhud!^<>_~=-/".toCharArray()) sb.append(c);
        return sb.toString();
    }

    /** Display form of a state: canonical string without auto-computed properties. */
    static String displayState(BlockSpec s) {
        if (s.props().isEmpty()) return s.name();
        StringBuilder sb = new StringBuilder(s.name());
        boolean any = false;
        for (Map.Entry<String, String> e : s.props().entrySet()) {
            if (AUTO_PROPS.contains(e.getKey())) continue;
            sb.append(any ? ',' : '[').append(e.getKey()).append('=').append(e.getValue());
            any = true;
        }
        if (any) sb.append(']');
        return sb.toString();
    }

    /** Orientation glyph (2nd char) for a state, or 0 for "no orientation" (doubled material char). */
    private static char glyph(BlockSpec s, BlockInfo info) {
        String n = s.name();
        String facing = prop(s, info, "facing");
        if (n.endsWith("_stairs")) {
            boolean top = "top".equals(prop(s, info, "half"));
            return switch (facing == null ? "north" : facing) {
                case "east" -> top ? 'e' : '>';
                case "south" -> top ? 's' : 'v';
                case "west" -> top ? 'w' : '<';
                default -> top ? 'n' : '^';
            };
        }
        if (n.endsWith("_slab")) {
            String t = prop(s, info, "type");
            return "top".equals(t) ? '~' : "double".equals(t) ? '=' : '_';
        }
        if (n.endsWith("_trapdoor") && !"true".equals(prop(s, info, "open"))) {
            return "top".equals(prop(s, info, "half")) ? '~' : '_';
        }
        String axis = prop(s, info, "axis");
        if (axis != null && facing == null) return "x".equals(axis) ? '-' : "z".equals(axis) ? '/' : 0;
        if ("true".equals(prop(s, info, "hanging"))) return 'h';
        if (facing != null) {
            return switch (facing) {
                case "north" -> '^';
                case "east" -> '>';
                case "south" -> 'v';
                case "west" -> '<';
                case "up" -> 'u';
                case "down" -> 'd';
                default -> 0;
            };
        }
        return 0;
    }

    private static String prop(BlockSpec s, BlockInfo info, String key) {
        String v = s.prop(key);
        if (v != null) return v;
        try {
            return info.getProp(s, key);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Semantic code assignment; false when there are more materials than material characters. */
    private static boolean assignSemantic(List<Map.Entry<BlockSpec, Integer>> palette, BlockInfo info,
                                          Map<BlockSpec, String> symbols, Map<String, Map<String, Integer>> legend) {
        Map<String, Integer> byName = new LinkedHashMap<>();
        for (Map.Entry<BlockSpec, Integer> e : palette) byName.merge(e.getKey().name(), e.getValue(), Integer::sum);
        if (byName.size() > MATERIAL_POOL.length()) return false;
        List<Map.Entry<String, Integer>> names = new ArrayList<>(byName.entrySet());
        names.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed().thenComparing(Map.Entry::getKey));
        Map<String, Character> matChar = new HashMap<>();
        for (int i = 0; i < names.size(); i++) matChar.put(names.get(i).getKey(), MATERIAL_POOL.charAt(i));
        List<Map.Entry<String, String>> order = new ArrayList<>();
        for (Map.Entry<BlockSpec, Integer> e : palette) {
            BlockSpec spec = e.getKey();
            char m = matChar.get(spec.name());
            char gl = glyph(spec, info);
            String code = new String(new char[]{m, gl == 0 ? m : gl});
            symbols.put(spec, code);
            legend.computeIfAbsent(code, k -> new LinkedHashMap<>()).merge(displayState(spec), e.getValue(), Integer::sum);
        }
        // order legend by material frequency, then code
        List<String> codes = new ArrayList<>(legend.keySet());
        codes.sort(Comparator.<String>comparingInt(c -> MATERIAL_POOL.indexOf(c.charAt(0))).thenComparing(c -> c));
        Map<String, Map<String, Integer>> sorted = new LinkedHashMap<>();
        for (String c : codes) sorted.put(c, legend.get(c));
        legend.clear();
        legend.putAll(sorted);
        return true;
    }

    private static void appendLayer(StringBuilder sb, Grid g, int y, Map<BlockSpec, String> symbols, boolean twoChar) {
        int w = g.sx;
        sb.append("LAYER y=").append(y).append('\n');
        appendRuler(sb, g.minX, w, twoChar, true);
        for (int z = g.minZ; z <= g.maxZ; z++) {
            sb.append("z=").append(pad(z, 3)).append(" |");
            for (int x = g.minX; x <= g.maxX; x++) {
                BlockSpec raw = g.raw(x, y, z);
                String sym;
                if (raw == null) {
                    sym = twoChar ? (y <= 0 ? ",," : "..") : (y <= 0 ? "," : ".");
                } else if (raw.isAir()) {
                    sym = twoChar ? ".." : ".";
                } else {
                    sym = symbols.get(raw);
                    if (sym == null) sym = twoChar ? "??" : "?";
                }
                sb.append(sym);
                if (twoChar) sb.append(' ');
            }
            trimTrailing(sb, ' ');
            sb.append("|\n");
        }
    }

    /** One layer as coordinate runs per row: {@code z=  3: 0-9 AA, 10 Bv, 12-20 CC}. */
    private static void appendLayerRuns(StringBuilder sb, Grid g, int y, Map<BlockSpec, String> symbols) {
        int start = sb.length();
        sb.append("LAYER y=").append(y).append('\n');
        boolean any = false;
        for (int z = g.minZ; z <= g.maxZ; z++) {
            StringBuilder row = new StringBuilder();
            String runCode = null;
            int runStart = 0;
            for (int x = g.minX; x <= g.maxX + 1; x++) {
                String code = null;
                if (x <= g.maxX) {
                    BlockSpec raw = g.raw(x, y, z);
                    if (raw != null && !raw.isAir()) {
                        code = symbols.get(raw);
                        if (code == null) code = "??";
                    }
                }
                if (!java.util.Objects.equals(code, runCode)) {
                    if (runCode != null) {
                        if (!row.isEmpty()) row.append(", ");
                        row.append(runStart);
                        if (x - 1 > runStart) row.append('-').append(x - 1);
                        row.append(' ').append(runCode);
                    }
                    runCode = code;
                    runStart = x;
                }
            }
            if (!row.isEmpty()) {
                sb.append("z=").append(pad(z, 3)).append(": ").append(row).append('\n');
                any = true;
            }
        }
        if (!any) {
            sb.setLength(start);
            sb.append("LAYER y=").append(y).append(": (empty)\n");
        }
    }

    /** Top view as runs of the highest block per column, plus its height. */
    private static void appendTopViewRuns(StringBuilder sb, Grid g, Map<BlockSpec, String> symbols) {
        sb.append("TOP VIEW (compact): per row z, runs \"x1-x2 CODE@y\" of the highest block in each column\n");
        for (int z = g.minZ; z <= g.maxZ; z++) {
            StringBuilder row = new StringBuilder();
            String run = null;
            int runStart = 0;
            for (int x = g.minX; x <= g.maxX + 1; x++) {
                String key = null;
                if (x <= g.maxX) {
                    for (int y = g.maxY; y >= g.minY; y--) {
                        BlockSpec s = g.raw(x, y, z);
                        if (s != null && !s.isAir()) {
                            String c = symbols.get(s);
                            key = (c == null ? "??" : c) + "@" + y;
                            break;
                        }
                    }
                }
                if (!java.util.Objects.equals(key, run)) {
                    if (run != null) {
                        if (!row.isEmpty()) row.append(", ");
                        row.append(runStart);
                        if (x - 1 > runStart) row.append('-').append(x - 1);
                        row.append(' ').append(run);
                    }
                    run = key;
                    runStart = x;
                }
            }
            if (!row.isEmpty()) sb.append("z=").append(pad(z, 3)).append(": ").append(row).append('\n');
        }
    }

    private static void appendRuler(StringBuilder sb, int minX, int width, boolean twoChar, boolean lastDigits) {
        // Two ruler rows: the upper one carries the sign / tens digit ('-' for negative coordinates,
        // blank for 0..9), the lower one the absolute units digit. Column "-" over "1" reads as -1.
        String gap = twoChar ? " " : "";
        boolean needTens = minX < 0 || minX + width - 1 >= 10;
        if (needTens) {
            sb.append(GUTTER);
            for (int i = 0; i < width; i++) {
                if (i > 0) sb.append(gap);
                int v = minX + i;
                char c = v < 0 ? '-' : v >= 10 ? (char) ('0' + (v / 10) % 10) : ' ';
                sb.append(c);
                if (twoChar) sb.append(' ');
            }
            trimTrailing(sb, ' ');
            sb.append('\n');
        }
        sb.append(GUTTER);
        for (int i = 0; i < width; i++) {
            if (i > 0) sb.append(gap);
            sb.append((char) ('0' + Math.abs(minX + i) % 10));
            if (twoChar) sb.append(' ');
        }
        trimTrailing(sb, ' ');
        sb.append('\n');
    }

    /**
     * One orthographic side view: for every (column, y) the first non-air block along the view ray.
     *
     * <p>Column order and ray direction follow the physical view:</p>
     * <ul>
     *   <li>south (viewer at +z): columns x west-&gt;east, ray from maxZ towards minZ</li>
     *   <li>north (viewer at -z): columns x east-&gt;west, ray from minZ towards maxZ</li>
     *   <li>east (viewer at +x): columns z north-&gt;south, ray from maxX towards minX</li>
     *   <li>west (viewer at -x): columns z south-&gt;north, ray from minX towards maxX</li>
     * </ul>
     */
    private static void appendElevation(StringBuilder sb, Grid g, Map<BlockSpec, String> symbols, boolean twoChar,
                                        String side) {
        boolean alongX;
        boolean reverseColumns;
        boolean reverseRay;
        String map;
        switch (side) {
            case "south" -> {
                alongX = false;
                reverseColumns = false;
                reverseRay = true;
                map = "columns x left->right = west->east (front)";
            }
            case "north" -> {
                alongX = false;
                reverseColumns = true;
                reverseRay = false;
                map = "columns x left->right = east->west";
            }
            case "east" -> {
                alongX = true;
                reverseColumns = false;
                reverseRay = true;
                map = "columns z left->right = north->south";
            }
            default -> {
                alongX = true;
                reverseColumns = true;
                reverseRay = false;
                map = "columns z left->right = south->north";
            }
        }
        sb.append("ELEVATION VIEW FROM ").append(side.toUpperCase(Locale.ROOT)).append(" - ").append(map)
                .append(", top row = highest y\n");

        int col0 = alongX ? g.minZ : g.minX;
        int col1 = alongX ? g.maxZ : g.maxX;
        int width = col1 - col0 + 1;
        appendRuler(sb, col0, width, twoChar, true);
        int rayMin = alongX ? g.minX : g.minZ;
        int rayMax = alongX ? g.maxX : g.maxZ;
        for (int y = g.maxY; y >= g.minY; y--) {
            StringBuilder row = new StringBuilder(width * (twoChar ? 3 : 2) + 16);
            row.append("y=").append(pad(y, 3)).append(" |");
            for (int k = 0; k < width; k++) {
                int col = reverseColumns ? col1 - k : col0 + k;
                BlockSpec hit = null;
                for (int r = rayMin; r <= rayMax; r++) {
                    int ray = reverseRay ? rayMax - (r - rayMin) : r;
                    int x = alongX ? ray : col;
                    int z = alongX ? col : ray;
                    BlockSpec s = g.raw(x, y, z);
                    if (s != null && !s.isAir()) {
                        hit = s;
                        break;
                    }
                }
                if (hit == null) {
                    row.append(twoChar ? "   " : " ");
                } else {
                    String sym = symbols.get(hit);
                    row.append(sym == null ? (twoChar ? "??" : "?") : sym);
                    if (twoChar) row.append(' ');
                }
            }
            trimTrailing(row, ' ');
            row.append("|\n");
            sb.append(row);
        }
        sb.append('\n');
    }

    private static void appendTopView(StringBuilder sb, Grid g, Map<BlockSpec, String> symbols, boolean twoChar) {
        sb.append("TOP VIEW (highest block per column; rows z, front/SOUTH is the last row)\n");
        appendRuler(sb, g.minX, g.sx, twoChar, true);
        for (int z = g.minZ; z <= g.maxZ; z++) {
            sb.append("z=").append(pad(z, 3)).append(" |");
            for (int x = g.minX; x <= g.maxX; x++) {
                BlockSpec hit = null;
                for (int y = g.maxY; y >= g.minY; y--) {
                    BlockSpec s = g.raw(x, y, z);
                    if (s != null && !s.isAir()) {
                        hit = s;
                        break;
                    }
                }
                if (hit == null) {
                    sb.append(twoChar ? "   " : " ");
                } else {
                    String sym = symbols.get(hit);
                    sb.append(sym == null ? (twoChar ? "??" : "?") : sym);
                    if (twoChar) sb.append(' ');
                }
            }
            trimTrailing(sb, ' ');
            sb.append("|\n");
        }
        sb.append("HEIGHT MAP (y of the highest block, base36)\n");
        appendRuler(sb, g.minX, g.sx, twoChar, true);
        for (int z = g.minZ; z <= g.maxZ; z++) {
            sb.append("z=").append(pad(z, 3)).append(" |");
            for (int x = g.minX; x <= g.maxX; x++) {
                int hy = Integer.MIN_VALUE;
                for (int y = g.maxY; y >= g.minY; y--) {
                    BlockSpec s = g.raw(x, y, z);
                    if (s != null && !s.isAir()) {
                        hy = y;
                        break;
                    }
                }
                char c;
                if (hy == Integer.MIN_VALUE) {
                    c = ' ';
                } else if (hy <= 0) {
                    c = '0';
                } else if (hy >= 36) {
                    c = 'Z';
                } else {
                    c = Character.toUpperCase(Character.forDigit(hy, 36));
                }
                sb.append(c);
                if (twoChar) sb.append(' ');
            }
            trimTrailing(sb, ' ');
            sb.append("|\n");
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String symbol(int index, boolean twoChar) {
        int size = POOL.length();
        if (!twoChar) return String.valueOf(poolChar(index % size));
        int hi = index / size;
        int lo = index % size;
        if (hi >= size) return "??";
        return new String(new char[]{poolChar(hi), poolChar(lo)});
    }

    private static void trimTrailing(StringBuilder sb, char c) {
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == c) sb.setLength(sb.length() - 1);
    }

    /** Right-aligned decimal without allocating via String.format. */
    private static String pad(int v, int width) {
        String s = Integer.toString(v);
        if (s.length() >= width) return s;
        StringBuilder sb = new StringBuilder(width);
        for (int i = s.length(); i < width; i++) sb.append(' ');
        return sb.append(s).toString();
    }

    /** Normalizes a crop box: orders corners, clamps to the model bounds, keeps at least a 1x1x1 area. */
    private static int[] normalizeRegion(int[] r, int[] modelBounds) {
        int minX = Math.min(r[0], r[3]), maxX = Math.max(r[0], r[3]);
        int minY = Math.min(r[1], r[4]), maxY = Math.max(r[1], r[4]);
        int minZ = Math.min(r[2], r[5]), maxZ = Math.max(r[2], r[5]);
        minX = Math.max(minX, modelBounds[0]);
        minY = Math.max(minY, modelBounds[1]);
        minZ = Math.max(minZ, modelBounds[2]);
        maxX = Math.min(maxX, modelBounds[3]);
        maxY = Math.min(maxY, modelBounds[4]);
        maxZ = Math.min(maxZ, modelBounds[5]);
        if (maxX < minX) maxX = minX;
        if (maxY < minY) maxY = minY;
        if (maxZ < minZ) maxZ = minZ;
        long vol = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (vol > Grid.MAX_CELLS) {
            // refuse pathological crops: clamp to the model bounds
            return new int[]{modelBounds[0], modelBounds[1], modelBounds[2], modelBounds[3], modelBounds[4], modelBounds[5]};
        }
        return new int[]{minX, minY, minZ, maxX, maxY, maxZ};
    }
}
