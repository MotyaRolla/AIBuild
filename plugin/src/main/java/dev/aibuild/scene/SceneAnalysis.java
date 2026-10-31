package dev.aibuild.scene;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Objective checks on a {@link SceneSpec} — the facts the AI critic is given along with the pictures.
 *
 * <p>Everything here is computed from the spec alone (no world access, no rendering), so it is cheap enough to
 * run on every iteration and completely deterministic. The point is not to judge beauty: it is to catch the
 * things the model forgets while it is busy with details — a missing door, a roof that is a single flat plane,
 * a spec whose size contradicts the request, objects that do nothing.</p>
 *
 * <p>Findings are split into {@link Severity} levels: {@code MAJOR} blocks convergence, {@code MINOR} is polish,
 * {@code INFO} is context. The build loop stops early only when no MAJOR or CRITICAL finding remains.</p>
 *
 * <p>Instances are immutable and cheap; create one per iteration with {@link #analyze(SceneSpec, String)}.</p>
 */
public final class SceneAnalysis {

    /** One finding of the analysis. */
    public record Finding(Severity severity, String what) {
    }

    /** Severity of a finding, ordered from harmless to blocking. */
    public enum Severity {
        /** Context only, never blocks. */
        INFO,
        /** Polish; does not block convergence. */
        MINOR,
        /** A visible architectural defect; blocks convergence. */
        MAJOR,
        /** The build is broken or does not answer the request. */
        CRITICAL
    }

    private final List<Finding> findings;
    private final int[][] bounds;
    private final int objects;
    private final int materials;

    private SceneAnalysis(List<Finding> findings, int[][] bounds, int objects, int materials) {
        this.findings = List.copyOf(findings);
        this.bounds = bounds;
        this.objects = objects;
        this.materials = materials;
    }

    /**
     * Analyses a spec against the original request.
     *
     * @param spec    the spec to check; must not be {@code null}
     * @param request the user request, used to verify an explicitly asked size ("10 на 10", "20x20")
     * @return the findings plus the measured numbers
     */
    public static SceneAnalysis analyze(SceneSpec spec, String request) {
        List<Finding> out = new ArrayList<>();
        int[][] b = spec.bounds();
        List<SceneObject> objects = spec.objects();
        Set<String> paletteKeys = new LinkedHashSet<>();

        if (objects.isEmpty()) {
            out.add(new Finding(Severity.CRITICAL, "сцена пустая: нет ни одного объекта"));
            return new SceneAnalysis(out, b, 0, 0);
        }

        for (SceneObject o : objects) paletteKeys.add(o.material());

        // ---- structural completeness --------------------------------------------------------
        boolean hasRoof = objects.stream().anyMatch(o -> o.type().equals("prism"));
        boolean hasFloor = objects.stream().anyMatch(o -> o.type().equals("floor") || o.type().equals("box"));
        boolean hasWalls = objects.stream().anyMatch(o -> o.type().equals("box") || o.type().equals("wall"));
        boolean hasDoor = objects.stream().anyMatch(o -> o.type().equals("door"))
                || objects.stream().anyMatch(o -> o.type().equals("box") && hasOpening(o, "door"));
        boolean hasWindow = objects.stream().anyMatch(o -> o.type().equals("window"))
                || objects.stream().anyMatch(o -> hasOpening(o, "window"));
        boolean hasStairs = objects.stream().anyMatch(o -> o.type().equals("stairs"))
                || objects.stream().anyMatch(o -> o.type().equals("box") && hasOpening(o, "stairs"));

        if (!hasWalls) out.add(new Finding(Severity.CRITICAL, "нет стен: объект box/wall с телом здания отсутствует"));
        if (!hasRoof) out.add(new Finding(Severity.MAJOR, "нет крыши: нужен объект prism (gable/hip/pyramid)"));
        if (!hasFloor) out.add(new Finding(Severity.MINOR, "нет пола"));
        if (!hasDoor) out.add(new Finding(Severity.MAJOR, "нет входа: нужен объект door или проём kind=door"));
        if (!hasWindow && hasWalls) out.add(new Finding(Severity.MAJOR, "нет окон: фасады глухие"));
        if (!hasStairs && b[2][1] >= 6) out.add(new Finding(Severity.MINOR, "нет лестницы, хотя высота " + b[2][1] + " блоков"));

        // ---- size fidelity -----------------------------------------------------------------
        int[] asked = parseRequestedSize(request);
        if (asked != null) {
            // compare the MAIN BODY, not the whole scene: a path, a fence or a chimney sticking out
            // must not be reported as "wrong size" (the request is about the building itself)
            int[] body = mainBodySize(objects);
            int gotX = body != null ? body[0] : b[2][0];
            int gotZ = body != null ? body[1] : b[2][2];
            if (Math.abs(gotX - asked[0]) > 2 || Math.abs(gotZ - asked[1]) > 2) {
                out.add(new Finding(Severity.MAJOR, "размер не совпадает с запросом: просили "
                        + asked[0] + "x" + asked[1] + ", получилось " + gotX + "x" + gotZ));
            }
        }

        // ---- degenerate and duplicated objects ----------------------------------------------
        for (SceneObject o : objects) {
            int[] from = o.from();
            int[] to = o.to();
            if (from != null && to != null) {
                long volume = (long) (to[0] - from[0] + 1) * (to[1] - from[1] + 1) * (to[2] - from[2] + 1);
                if (volume <= 0) out.add(new Finding(Severity.MINOR, o.label() + ": пустой объём"));
                if (volume > 60_000) out.add(new Finding(Severity.MINOR, o.label() + ": гигантский объект (" + volume + " клеток), проверьте координаты"));
            }
        }
        int duplicates = 0;
        for (int i = 0; i < objects.size(); i++) {
            for (int j = i + 1; j < objects.size(); j++) {
                if (sameGeometry(objects.get(i), objects.get(j))) duplicates++;
            }
        }
        if (duplicates > 0) {
            out.add(new Finding(Severity.MINOR, duplicates + " объектов полностью совпадают по геометрии и материалу"));
        }

        // ---- proportions -------------------------------------------------------------------
        int height = b[2][1];
        int footprint = Math.max(b[2][0], b[2][2]);
        if (footprint > 0 && height > 0) {
            double ratio = (double) height / footprint;
            if (ratio > 4.0) out.add(new Finding(Severity.MINOR, "слишком вытянуто вверх: высота " + height + " при основании " + footprint));
            if (ratio < 0.35) out.add(new Finding(Severity.MINOR, "слишком приплюснуто: высота " + height + " при основании " + footprint));
        }
        if (hasRoof) {
            int roofTop = roofTopY(objects);
            int wallTop = wallTopY(objects);
            if (roofTop > 0 && wallTop > 0 && roofTop <= wallTop) {
                out.add(new Finding(Severity.MAJOR, "крыша не поднимается над стенами (верх стен y=" + wallTop + ", верх крыши y=" + roofTop + ")"));
            }
        }

        // ---- detail budget -----------------------------------------------------------------
        int detailObjects = 0;
        for (SceneObject o : objects) {
            switch (o.type()) {
                case "window", "door", "arch", "balustrade", "beam", "column", "chimney", "detail", "ring" -> detailObjects++;
                default -> { }
            }
        }
        double detailShare = (double) detailObjects / objects.size();
        if (objects.size() >= 12 && detailShare < 0.25) {
            out.add(new Finding(Severity.MAJOR, "мало деталей: только " + detailObjects + " из " + objects.size()
                    + " объектов — декор (" + (int) (detailShare * 100) + "%), постройка будет плоской"));
        }

        // ---- palette -----------------------------------------------------------------------
        if (paletteKeys.size() <= 2 && objects.size() > 8) {
            out.add(new Finding(Severity.MINOR, "в палитре всего " + paletteKeys.size() + " материала — постройка будет однообразной"));
        }

        return new SceneAnalysis(out, b, objects.size(), paletteKeys.size());
    }

    /**
     * Footprint of the largest wall/roof object — the building "body" excluding paths and props.
     *
     * @param objects the spec objects
     * @return {@code {width, depth}} or {@code null} when there is no candidate
     */
    private static int[] mainBodySize(List<SceneObject> objects) {
        int bestArea = 0;
        int[] best = null;
        for (SceneObject o : objects) {
            if (!o.type().equals("box") && !o.type().equals("prism") && !o.type().equals("wall")) continue;
            int[] from = o.from();
            int[] to = o.to();
            if (from == null || to == null) continue;
            int w = to[0] - from[0] + 1;
            int d = to[2] - from[2] + 1;
            if (w * d > bestArea) {
                bestArea = w * d;
                best = new int[]{w, d};
            }
        }
        return best;
    }

    private static boolean hasOpening(SceneObject o, String kind) {
        JsonElement e = o.raw().get("openings");
        if (e == null || !e.isJsonArray()) return false;
        for (JsonElement el : e.getAsJsonArray()) {
            if (!el.isJsonObject()) continue;
            JsonObject op = el.getAsJsonObject();
            String k = SceneObject.str(op, "kind");
            if (k.isEmpty()) k = SceneObject.str(op, "type");
            if (k.toLowerCase(Locale.ROOT).contains(kind)) return true;
        }
        return false;
    }

    private static int wallTopY(List<SceneObject> objects) {
        int top = -1;
        for (SceneObject o : objects) {
            if (!o.type().equals("box") && !o.type().equals("wall")) continue;
            int[] to = o.to();
            if (to != null) top = Math.max(top, to[1]);
        }
        return top;
    }

    private static int roofTopY(List<SceneObject> objects) {
        int top = -1;
        for (SceneObject o : objects) {
            if (!o.type().equals("prism")) continue;
            int[] to = o.to();
            if (to == null) continue;
            int rise = (int) Math.ceil(Math.min(to[0] - o.from()[0], to[2] - o.from()[2]) / 2.0 * SceneObject.dbl(o.raw(), "pitch", 1.0));
            top = Math.max(top, to[1] + Math.max(1, rise));
        }
        return top;
    }

    private static boolean sameGeometry(SceneObject a, SceneObject b) {
        if (!a.type().equals(b.type())) return false;
        if (!a.material().equals(b.material())) return false;
        return java.util.Arrays.equals(a.from(), b.from()) && java.util.Arrays.equals(a.to(), b.to());
    }

    /**
     * Extracts an explicitly requested footprint ("10 на 10", "10x10", "12 by 8").
     *
     * @param request the user text
     * @return {@code {width, depth}} or {@code null} when the request does not state a size
     */
    public static int[] parseRequestedSize(String request) {
        if (request == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d{1,3})\\s*(?:на|by|[xXхХ×*])\\s*(\\d{1,3})")
                .matcher(request);
        if (!m.find()) return null;
        int a = Integer.parseInt(m.group(1));
        int b = Integer.parseInt(m.group(2));
        if (a < 2 || b < 2 || a > 200 || b > 200) return null;
        return new int[]{a, b};
    }

    /** @return every finding, in the order they were discovered */
    public List<Finding> findings() {
        return findings;
    }

    /** @return the number of findings at or above the given severity */
    public int count(Severity atLeast) {
        int n = 0;
        for (Finding f : findings) if (f.severity().ordinal() >= atLeast.ordinal()) n++;
        return n;
    }

    /**
     * @return {@code true} when nothing blocks convergence (no CRITICAL and no MAJOR findings)
     */
    public boolean ok() {
        return count(Severity.MAJOR) == 0;
    }

    /** @return the bounding box, as computed by {@link SceneSpec#bounds()} */
    public int[][] bounds() {
        return bounds;
    }

    /** @return the number of accepted objects */
    public int objects() {
        return objects;
    }

    /** @return the number of distinct materials */
    public int materials() {
        return materials;
    }

    /**
     * Renders the findings as a compact multi-line text for a prompt.
     *
     * @return the text, never {@code null}
     */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("bounds: ").append(bounds[2][0]).append('x').append(bounds[2][1]).append('x').append(bounds[2][2])
                .append(", objects: ").append(objects).append(", materials: ").append(materials).append('\n');
        if (findings.isEmpty()) {
            sb.append("objective checks: clean\n");
        } else {
            for (Finding f : findings) sb.append("- [").append(f.severity()).append("] ").append(f.what()).append('\n');
        }
        return sb.toString();
    }
}
