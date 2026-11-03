package dev.aibuild.analysis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Immutable result of {@link StructureAnalyzer#analyze}.
 *
 * <p>This is the machine-readable summary that is also fed to the reviewing LLM, so {@link #toText()} is kept
 * below roughly 4500 characters: one header line with the headline statistics, the palette, the key metrics and
 * one line per issue (most severe first).</p>
 *
 * <p>Instances are immutable apart from the {@code int[] bounds} array, which is copied on construction; getters
 * that return arrays return copies. Safe to share between threads.</p>
 */
public final class AnalysisReport {

    /** Absolute hard budget for {@link #toText()}; longer output is truncated at an issue boundary. */
    public static final int TEXT_LIMIT = 4500;

    // --- headline stats -------------------------------------------------------------------------------
    /** Bounding box of the non-air cells {minX,minY,minZ,maxX,maxY,maxZ}, or null for an empty model. */
    public final int[] bounds;
    public final int sizeX;
    public final int sizeY;
    public final int sizeZ;
    /** Number of non-air cells. */
    public final int nonAir;
    /** Number of distinct block names (ignoring properties). */
    public final int distinctBlocks;
    /** Number of distinct block states (name + properties). */
    public final int distinctStates;
    /** Most common block names with their counts, descending, at most {@value #TOP_BLOCK_LIMIT} entries. */
    public final List<Map.Entry<String, Integer>> topBlocks;

    // --- structure metrics ----------------------------------------------------------------------------
    /** Air cells enclosed by the shell (0 when the build is open). */
    public final int interiorVolume;
    /** Connected interior components with volume >= 4. */
    public final int rooms;
    /** Walkable interior cells with block light 0 (mob spawn surface). */
    public final int darkSpots;
    /** Door lower halves. */
    public final int doors;
    /** Glass blocks + panes. */
    public final int windows;
    /** Light emitters (any block with lightEmission > 0). */
    public final int lightSources;

    /** Extra metrics, insertion ordered, stable keys — see {@link StructureAnalyzer} for the key list. */
    public final Map<String, Object> metrics;

    /** Issues, most severe first. */
    public final List<Issue> issues;

    /**
     * One room: a connected floor area on one level inside the shell. {@code y} is the layer players stand in;
     * {@code furniture} counts blocks standing in the room up to 3 high; {@code minLight} is the darkest floor cell.
     */
    public record Room(int x1, int z1, int x2, int z2, int y, int area, int furniture, int minLight) {
        public double density() {
            return area == 0 ? 0 : (double) furniture / area;
        }

        public String describe() {
            return "y=" + y + " x " + x1 + ".." + x2 + " z " + z1 + ".." + z2 + ": " + area + " floor cells, " + furniture
                    + " furniture blocks, darkest light " + minLight;
        }
    }

    /** Rooms of 12+ floor cells, largest first (empty when the build has no interior). */
    public final List<Room> roomList;

    /** Maximum number of {@link #topBlocks} entries. */
    public static final int TOP_BLOCK_LIMIT = 30;

    /**
     * Builds a report. All collections are copied defensively; {@code null} becomes an empty list/map and a null
     * {@code bounds} means "empty model".
     */
    public AnalysisReport(int[] bounds, int sizeX, int sizeY, int sizeZ, int nonAir, int distinctBlocks,
                          int distinctStates, List<Map.Entry<String, Integer>> topBlocks, int interiorVolume,
                          int rooms, int darkSpots, int doors, int windows, int lightSources,
                          Map<String, Object> metrics, List<Issue> issues) {
        this(bounds, sizeX, sizeY, sizeZ, nonAir, distinctBlocks, distinctStates, topBlocks, interiorVolume, rooms, darkSpots,
                doors, windows, lightSources, metrics, issues, List.of());
    }

    /** As above, with the rooms found inside the shell. */
    public AnalysisReport(int[] bounds, int sizeX, int sizeY, int sizeZ, int nonAir, int distinctBlocks,
                          int distinctStates, List<Map.Entry<String, Integer>> topBlocks, int interiorVolume,
                          int rooms, int darkSpots, int doors, int windows, int lightSources,
                          Map<String, Object> metrics, List<Issue> issues, List<Room> roomList) {
        this.roomList = roomList == null ? List.of() : List.copyOf(roomList);
        this.bounds = bounds == null ? null : bounds.clone();
        this.sizeX = Math.max(0, sizeX);
        this.sizeY = Math.max(0, sizeY);
        this.sizeZ = Math.max(0, sizeZ);
        this.nonAir = Math.max(0, nonAir);
        this.distinctBlocks = Math.max(0, distinctBlocks);
        this.distinctStates = Math.max(0, distinctStates);
        this.topBlocks = topBlocks == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(topBlocks));
        this.interiorVolume = Math.max(0, interiorVolume);
        this.rooms = Math.max(0, rooms);
        this.darkSpots = Math.max(0, darkSpots);
        this.doors = Math.max(0, doors);
        this.windows = Math.max(0, windows);
        this.lightSources = Math.max(0, lightSources);
        this.metrics = metrics == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metrics));
        List<Issue> sorted = issues == null ? new ArrayList<>() : new ArrayList<>(issues);
        sorted.removeIf(java.util.Objects::isNull);
        sorted.sort(java.util.Comparator.comparingInt((Issue i) -> i.severity().rank).thenComparing(Issue::code));
        this.issues = Collections.unmodifiableList(sorted);
    }

    /** An empty model report: CRITICAL {@code empty} and nothing else. */
    public static AnalysisReport empty() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("solid", 0);
        m.put("interiorVolume", 0);
        return new AnalysisReport(null, 0, 0, 0, 0, 0, 0, List.of(), 0, 0, 0, 0, 0, 0, m,
                List.of(Issue.of(Severity.CRITICAL, "empty",
                        "model contains no blocks; nothing was generated")));
    }

    /** Copy of the bounds array (or null). */
    public int[] boundsCopy() {
        return bounds == null ? null : bounds.clone();
    }

    /** True when any CRITICAL or MAJOR issue is present: the build must not be accepted as-is. */
    public boolean hasBlocking() {
        for (Issue i : issues) if (i.severity() == Severity.CRITICAL || i.severity() == Severity.MAJOR) return true;
        return false;
    }

    /** Number of issues with the given severity. */
    public int count(Severity s) {
        if (s == null) return 0;
        int n = 0;
        for (Issue i : issues) if (i.severity() == s) n++;
        return n;
    }

    /** All issues with the given severity, in report order. */
    public List<Issue> issues(Severity s) {
        List<Issue> out = new ArrayList<>();
        for (Issue i : issues) if (i.severity() == s) out.add(i);
        return out;
    }

    /** True when an issue with this code exists (case-insensitive). */
    public boolean hasCode(String code) {
        for (Issue i : issues) if (i.code().equalsIgnoreCase(code)) return true;
        return false;
    }

    /** Metric value as int, or {@code def} when absent / not numeric. */
    public int metricInt(String key, int def) {
        Object v = metrics.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof Boolean b) return b ? 1 : 0;
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    /** Metric value as double, or {@code def} when absent / not numeric. */
    public double metricNum(String key, double def) {
        Object v = metrics.get(key);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    /** Metric value as String, or {@code def} when absent. */
    public String metricString(String key, String def) {
        Object v = metrics.get(key);
        return v == null ? def : String.valueOf(v);
    }

    /** Metrics grouped per facade side: keys are {@code facade_<side>_<name>} with side in south/north/east/west. */
    public Map<String, Object> facadeMetrics(String side) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (side == null) return out;
        String p = "facade_" + side.toLowerCase(Locale.ROOT) + "_";
        for (Map.Entry<String, Object> e : metrics.entrySet()) {
            if (e.getKey().startsWith(p)) out.put(e.getKey().substring(p.length()), e.getValue());
        }
        return out;
    }

    /**
     * Compact LLM-facing text (aims for &lt;= {@value #TEXT_LIMIT} characters, hard-truncated at an issue
     * boundary). Layout:
     * <pre>
     * AIBUILD 13x6x13 (x1..13 y0..5 z1..13) nonAir=582 states=14 interior=97 rooms=1 dark=0 doors=1 windows=4 lights=3
     * Top blocks: oak_planks 320 (55%), cobblestone 180 (31%), glass_pane 12 (2%)
     * Metrics: detailScore=48 floors=2 avgLight=9.3 minLight=2 skyExposed=0 footprintX=13 ...
     * Facades: south(visible=42 depth=1 relief=0 dominant=oak_planks 60%) north(...) east(...) west(...)
     * Issues: 2 major, 1 minor
     * MAJOR dark_interior: ...
     * </pre>
     */
    public String toText() {
        StringBuilder sb = new StringBuilder(1400);
        if (bounds == null) {
            sb.append("AIBUILD empty: no blocks\n");
            sb.append("Issues: ").append(issues.size()).append('\n');
            for (Issue i : issues) sb.append(i.toLine()).append('\n');
            return sb.toString();
        }
        sb.append("AIBUILD ").append(sizeX).append('x').append(sizeY).append('x').append(sizeZ)
                .append(" (x").append(bounds[0]).append("..").append(bounds[3])
                .append(" y").append(bounds[1]).append("..").append(bounds[4])
                .append(" z").append(bounds[2]).append("..").append(bounds[5]).append(')');
        sb.append(" nonAir=").append(nonAir)
                .append(" states=").append(distinctStates)
                .append(" interior=").append(interiorVolume)
                .append(" rooms=").append(rooms)
                .append(" dark=").append(darkSpots)
                .append(" doors=").append(doors)
                .append(" windows=").append(windows)
                .append(" lights=").append(lightSources)
                .append('\n');

        sb.append("Top blocks: ").append(topBlocksLine()).append('\n');
        sb.append("Metrics: ").append(metricsLine()).append('\n');
        String facades = facadeLine();
        if (!facades.isEmpty()) sb.append("Facades: ").append(facades).append('\n');
        if (!roomList.isEmpty()) {
            sb.append("Rooms (y = standing layer):");
            for (int i = 0; i < Math.min(6, roomList.size()); i++) sb.append(i == 0 ? " " : "; ").append(roomList.get(i).describe());
            if (roomList.size() > 6) sb.append("; +").append(roomList.size() - 6).append(" more");
            sb.append('\n');
        }

        sb.append("Issues: ");
        boolean first = true;
        for (Severity s : Severity.values()) {
            int n = count(s);
            if (n == 0) continue;
            if (!first) sb.append(", ");
            sb.append(n).append(' ').append(s.id());
            first = false;
        }
        if (first) sb.append("none");
        sb.append('\n');

        for (Issue i : issues) {
            String line = i.toLine() + '\n';
            if (sb.length() + line.length() > TEXT_LIMIT) {
                sb.append("... (").append(issues.size()).append(" issues total, remainder truncated)\n");
                break;
            }
            sb.append(line);
        }
        if (sb.length() > TEXT_LIMIT) {
            return sb.substring(0, TEXT_LIMIT - 14) + "\n... truncated\n";
        }
        return sb.toString();
    }

    private String topBlocksLine() {
        if (topBlocks.isEmpty()) return "none";
        // Show at most 10 names (the LLM does not need the long tail) and say how many were hidden.
        StringBuilder sb = new StringBuilder(220);
        int shown = 0;
        int totalNames = Math.max(topBlocks.size(), distinctBlocks);
        for (Map.Entry<String, Integer> e : topBlocks) {
            if (shown >= 10) {
                sb.append(", +").append(Math.max(0, totalNames - shown)).append(" more names");
                break;
            }
            if (shown > 0) sb.append(", ");
            int c = e.getValue() == null ? 0 : e.getValue();
            int pct = nonAir > 0 ? (int) Math.round(100.0 * c / nonAir) : 0;
            sb.append(e.getKey()).append(' ').append(c).append(" (").append(pct).append("%)");
            shown++;
        }
        return sb.toString();
    }

    /**
     * One compact line per build: {@code south(visible=42 depth=1 relief=0.0 dominant=oak_planks 60%)}. Keeps the
     * side-by-side comparison cheap for the reviewer.
     */
    private String facadeLine() {
        StringBuilder sb = new StringBuilder(200);
        for (String side : new String[]{"south", "north", "east", "west"}) {
            String p = "facade_" + side + "_";
            if (!metrics.containsKey(p + "visible")) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(side).append('(');
            sb.append("visible=").append(fmt(metrics.get(p + "visible")));
            sb.append(" depth=").append(fmt(metrics.get(p + "depth")));
            sb.append(" relief=").append(fmt(metrics.get(p + "relief")));
            sb.append(" dominant=").append(fmt(metrics.get(p + "dominant")));
            Object share = metrics.get(p + "dominantShare");
            if (share instanceof Number n) sb.append(' ').append(Math.round(n.doubleValue() * 100)).append('%');
            sb.append(')');
        }
        return sb.toString();
    }

    private String metricsLine() {
        String[] keys = {"detailScore", "floors", "avgInteriorLight", "minInteriorLight", "skyExposedInterior",
                "footprintX", "footprintZ", "roofOverhang", "verticalAccess", "orientedPct", "decorativePct",
                "facade_south_dominantShare", "facade_south_depth", "biggestRoom"};
        StringBuilder sb = new StringBuilder(240);
        for (String k : keys) {
            if (!metrics.containsKey(k)) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(shortKey(k)).append('=').append(fmt(metrics.get(k)));
        }
        if (sb.length() == 0) {
            for (Map.Entry<String, Object> e : metrics.entrySet()) {
                if (sb.length() > 120) {
                    sb.append(" ...");
                    break;
                }
                if (sb.length() > 0) sb.append(' ');
                sb.append(e.getKey()).append('=').append(fmt(e.getValue()));
            }
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    private static String shortKey(String k) {
        return switch (k) {
            case "avgInteriorLight" -> "avgLight";
            case "minInteriorLight" -> "minLight";
            case "skyExposedInterior" -> "skyExposed";
            case "facade_south_dominantShare" -> "southDominantShare";
            case "facade_south_depth" -> "southDepth";
            case "orientedPct" -> "oriented%";
            case "decorativePct" -> "decorative%";
            default -> k;
        };
    }

    private static String fmt(Object v) {
        if (v == null) return "-";
        if (v instanceof Double d) {
            if (d == Math.rint(d)) return Integer.toString(d.intValue());
            return String.format(Locale.ROOT, "%.1f", d);
        }
        if (v instanceof Float f) return fmt((double) f);
        return String.valueOf(v);
    }

    /** Severity histogram, for logs. */
    public Map<Severity, Integer> severityCounts() {
        Map<Severity, Integer> m = new EnumMap<>(Severity.class);
        for (Severity s : Severity.values()) m.put(s, count(s));
        return m;
    }

    @Override
    public String toString() {
        return toText();
    }
}
