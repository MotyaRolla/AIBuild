package dev.aibuild.scene;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A whole building described as declarative 3D primitives — the AI's working material in the 3D pipeline.
 *
 * <p>A spec is small JSON (a house is 40–90 objects, a few KB) instead of thousands of block writes. That is
 * why the 3D pipeline spends an order of magnitude fewer tokens than the block-DSL pipeline: the model edits
 * {@code #roof prism} rather than 1400 {@code set} operations, and it re-reads the same compact digest every
 * iteration.</p>
 *
 * <p>Parsing never throws on a bad object: problems are collected in {@link #errors()} so the whole list can be
 * shown to the model at once ("these three objects are broken, fix them"). Only a structurally unusable spec
 * (no {@code objects} array) is rejected.</p>
 *
 * <p>Instances are immutable; {@link #withObjects(List)} and {@link #withPalette} return new specs.</p>
 */
public final class SceneSpec {

    /** Palette used when the model does not define one. */
    public static final Map<String, String> DEFAULT_PALETTE = Map.ofEntries(
            Map.entry("stone", "#8a8a8a"),
            Map.entry("stone_dark", "#6b6b6b"),
            Map.entry("wall", "#d9cfba"),
            Map.entry("frame", "#3a2a1c"),
            Map.entry("wood", "#8a6242"),
            Map.entry("roof", "#6b452f"),
            Map.entry("roof_accent", "#4d3222"),
            Map.entry("glass", "#a9c9d9"),
            Map.entry("metal", "#4a4a52"),
            Map.entry("leaf", "#4e7a34"),
            Map.entry("plain", "#c8c8c8"));

    private final String name;
    private final long seed;
    private final Map<String, String> palette;
    private final List<SceneObject> objects;
    private final List<String> errors;
    private final String notes;

    private SceneSpec(String name, long seed, Map<String, String> palette, List<SceneObject> objects,
                      List<String> errors, String notes) {
        this.name = name;
        this.seed = seed;
        this.palette = palette;
        this.objects = objects;
        this.errors = errors;
        this.notes = notes;
    }

    /**
     * Parses a spec from JSON.
     *
     * @param json the spec object; must contain an {@code objects} array
     * @return the parsed spec, with {@link #errors()} describing every rejected object
     * @throws IllegalArgumentException when {@code json} has no objects array
     */
    public static SceneSpec parse(JsonObject json) {
        if (json == null) throw new IllegalArgumentException("spec must not be null");
        JsonElement objs = json.get("objects");
        if (objs == null || !objs.isJsonArray()) {
            throw new IllegalArgumentException("spec needs an 'objects' array");
        }
        Map<String, String> palette = new LinkedHashMap<>(DEFAULT_PALETTE);
        JsonElement pal = json.get("palette");
        if (pal != null && pal.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : pal.getAsJsonObject().entrySet()) {
                if (e.getValue().isJsonPrimitive()) palette.put(e.getKey(), e.getValue().getAsString());
            }
        }
        List<SceneObject> objects = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int index = 0;
        for (JsonElement e : objs.getAsJsonArray()) {
            index++;
            if (!e.isJsonObject()) {
                errors.add("objects[" + index + "]: not an object");
                continue;
            }
            try {
                SceneObject o = SceneObject.parse(e.getAsJsonObject());
                List<String> problems = o.validate();
                if (!problems.isEmpty()) {
                    errors.addAll(problems);
                    continue;
                }
                objects.add(o);
            } catch (RuntimeException ex) {
                errors.add("objects[" + index + "]: " + ex.getMessage());
            }
        }
        String name = SceneObject.str(json, "name");
        long seed = SceneObject.num(json, "seed", 1);
        String notes = SceneObject.str(json, "notes");
        return new SceneSpec(name.isEmpty() ? "structure" : name, seed, Map.copyOf(palette), List.copyOf(objects), List.copyOf(errors), notes);
    }

    /**
     * Builds a spec programmatically (used by tests and by the editor).
     *
     * @param name    the build name
     * @param objects the objects
     * @return an error-free spec with the default palette
     */
    public static SceneSpec of(String name, List<SceneObject> objects) {
        return new SceneSpec(name, 1L, Map.copyOf(DEFAULT_PALETTE), List.copyOf(objects), List.of(), "");
    }

    /** @return the build name (never blank) */
    public String name() {
        return name;
    }

    /** @return the deterministic seed for any randomised detail */
    public long seed() {
        return seed;
    }

    /** @return the palette: key to {@code #rrggbb} */
    public Map<String, String> palette() {
        return palette;
    }

    /** @return the free-form notes the model wants to remember */
    public String notes() {
        return notes;
    }

    /** @return the accepted objects, in spec order */
    public List<SceneObject> objects() {
        return objects;
    }

    /** @return problems found while parsing; empty for a clean spec */
    public List<String> errors() {
        return errors;
    }

    /** @return the colour for a palette key, falling back to a mid grey */
    public String color(String material) {
        String c = palette.get(material);
        return c == null ? "#a0a0a0" : c;
    }

    /**
     * Returns a copy with another object list (used when applying a model patch).
     *
     * @param newObjects the replacement list
     * @return a new spec with the same name, seed and palette
     */
    public SceneSpec withObjects(List<SceneObject> newObjects) {
        return new SceneSpec(name, seed, palette, List.copyOf(newObjects), List.of(), notes);
    }

    /**
     * @param newPalette the palette to use
     * @return a new spec with the given palette
     */
    public SceneSpec withPalette(Map<String, String> newPalette) {
        Map<String, String> merged = new LinkedHashMap<>(palette);
        merged.putAll(newPalette);
        return new SceneSpec(name, seed, Map.copyOf(merged), objects, errors, notes);
    }

    /**
     * Computes the bounding box of all objects, without building geometry.
     *
     * @return {@code {min:[x,y,z], max:[x,y,z], size:[sx,sy,sz]}}; zeros when the spec is empty
     */
    public int[][] bounds() {
        int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] max = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (SceneObject o : objects) {
            grow(min, max, o.from());
            grow(min, max, o.to());
            grow(min, max, o.center());
            int[] at = o.at();
            if (at != null) grow(min, max, new int[]{at[0], at.length > 2 ? at[1] : 0, at.length > 2 ? at[2] : at.length == 2 ? at[1] : 0});
            JsonElement cells = o.raw().get("cells");
            if (cells != null && cells.isJsonArray()) {
                for (JsonElement c : cells.getAsJsonArray()) {
                    if (c.isJsonArray() && c.getAsJsonArray().size() >= 3) {
                        JsonArray a = c.getAsJsonArray();
                        grow(min, max, new int[]{a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()});
                    }
                }
            }
        }
        if (min[0] == Integer.MAX_VALUE) return new int[][]{{0, 0, 0}, {0, 0, 0}, {0, 0, 0}};
        int[] size = {max[0] - min[0] + 1, max[1] - min[1] + 1, max[2] - min[2] + 1};
        return new int[][]{min, max, size};
    }

    private static void grow(int[] min, int[] max, int[] v) {
        if (v == null) return;
        for (int k = 0; k < 3 && k < v.length; k++) {
            min[k] = Math.min(min[k], v[k]);
            max[k] = Math.max(max[k], v[k]);
        }
    }

    /**
     * Compact textual digest for prompts: name, size, palette and one line per object.
     *
     * <p>This is what the model re-reads every iteration instead of the full JSON, keeping the input
     * tokens low even after many rounds.</p>
     *
     * @param maxObjects how many objects to list
     * @return the digest, never {@code null}
     */
    public String digest(int maxObjects) {
        int[][] b = bounds();
        StringBuilder sb = new StringBuilder();
        sb.append("name: ").append(name).append("  size: ")
                .append(b[2][0]).append('x').append(b[2][1]).append('x').append(b[2][2])
                .append("  objects: ").append(objects.size()).append('\n');
        sb.append("palette:");
        palette.forEach((k, v) -> sb.append(' ').append(k).append('=').append(v));
        sb.append('\n');
        int i = 0;
        for (SceneObject o : objects) {
            if (i++ >= maxObjects) {
                sb.append("... ").append(objects.size() - maxObjects).append(" more objects\n");
                break;
            }
            sb.append(i).append(". ").append(o.label()).append(' ');
            String raw = o.raw().toString();
            sb.append(raw.length() > 200 ? raw.substring(0, 200) + "…" : raw).append('\n');
        }
        return sb.toString();
    }

    /** @return the spec as JSON, with the palette inlined */
    public JsonObject toJson() {
        JsonObject out = new JsonObject();
        out.addProperty("name", name);
        out.addProperty("seed", seed);
        if (!notes.isEmpty()) out.addProperty("notes", notes);
        JsonObject pal = new JsonObject();
        palette.forEach(pal::addProperty);
        out.add("palette", pal);
        JsonArray arr = new JsonArray();
        for (SceneObject o : objects) arr.add(o.toJson());
        out.add("objects", arr);
        return out;
    }
}
