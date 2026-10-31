package dev.aibuild.scene;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One primitive of a {@link SceneSpec}: a declarative piece of architecture (box, roof, window, ...).
 *
 * <p>The scene is the intermediate representation between the AI and Minecraft. The AI writes JSON
 * objects like {@code {"id":"walls","type":"box","from":[0,0,0],"to":[9,6,9],"material":"wall"}};
 * this class parses that into typed fields, and {@link SceneGeometry} turns it into voxels. Working on
 * primitives instead of blocks is what makes the 3D pipeline compact: a whole house is a few dozen
 * objects, not thousands of {@code set} operations.</p>
 *
 * <p>Coordinates follow the plugin convention: +x east, +y up, +z south, y=0 is the ground layer.
 * Instances are immutable after {@link #parse(JsonObject)} and cheap; they are parsed once per iteration
 * and reused by the geometry builder and the critic.</p>
 */
public final class SceneObject {

    /** Every object type the geometry builder understands. */
    public static final List<String> TYPES = List.of(
            "box", "prism", "cylinder", "cone", "sphere", "ring", "wall", "floor", "stairs",
            "window", "door", "arch", "balustrade", "beam", "column", "chimney", "detail");

    private final String id;
    private final String type;
    private final String material;
    private final int[] from;
    private final int[] to;
    private final int[] center;
    private final int[] at;
    private final JsonObject raw;

    private SceneObject(String id, String type, String material, int[] from, int[] to, int[] center, int[] at, JsonObject raw) {
        this.id = id;
        this.type = type;
        this.material = material;
        this.from = from;
        this.to = to;
        this.center = center;
        this.at = at;
        this.raw = raw;
    }

    /**
     * Parses one JSON object.
     *
     * @param json the raw object; must not be {@code null}
     * @return the parsed object
     * @throws IllegalArgumentException if the type is unknown or a required coordinate is missing
     */
    public static SceneObject parse(JsonObject json) {
        if (json == null) throw new IllegalArgumentException("object must not be null");
        String type = str(json, "type");
        if (type.isEmpty()) throw new IllegalArgumentException("object without type");
        type = type.equals("roof") ? "prism" : type.toLowerCase(Locale.ROOT);
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("unknown object type '" + type + "' (valid: " + String.join(", ", TYPES) + ")");
        }
        String id = str(json, "id");
        String material = str(json, "material");
        int[] from = vec3(json, "from");
        int[] to = vec3(json, "to");
        if (from != null && to != null) {
            for (int k = 0; k < 3; k++) {
                if (from[k] > to[k]) {
                    int t = from[k];
                    from[k] = to[k];
                    to[k] = t;
                }
            }
        }
        return new SceneObject(id, type, material, from, to, vec3(json, "center"), vec2(json, "at"), json);
    }

    /** Reads a string field, returning "" when absent. */
    public static String str(JsonObject json, String key) {
        JsonElement e = json.get(key);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? "" : e.getAsString();
    }

    /** Reads an int field with a default. */
    public static int num(JsonObject json, String key, int def) {
        JsonElement e = json.get(key);
        if (e == null || !e.isJsonPrimitive()) return def;
        try {
            return e.getAsInt();
        } catch (RuntimeException ex) {
            return def;
        }
    }

    /** Reads a double field with a default. */
    public static double dbl(JsonObject json, String key, double def) {
        JsonElement e = json.get(key);
        if (e == null || !e.isJsonPrimitive()) return def;
        try {
            return e.getAsDouble();
        } catch (RuntimeException ex) {
            return def;
        }
    }

    /** Reads a bool field with a default. */
    public static boolean bool(JsonObject json, String key, boolean def) {
        JsonElement e = json.get(key);
        if (e == null || !e.isJsonPrimitive()) return def;
        try {
            return e.getAsBoolean();
        } catch (RuntimeException ex) {
            return def;
        }
    }

    private static int[] vec3(JsonObject json, String key) {
        JsonElement e = json.get(key);
        if (e == null || !e.isJsonArray()) return null;
        JsonArray a = e.getAsJsonArray();
        if (a.size() < 3) return null;
        return new int[]{a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()};
    }

    private static int[] vec2(JsonObject json, String key) {
        JsonElement e = json.get(key);
        if (e == null || !e.isJsonArray()) return null;
        JsonArray a = e.getAsJsonArray();
        if (a.size() < 2) return null;
        return new int[]{a.get(0).getAsInt(), a.get(1).getAsInt()};
    }

    /**
     * Validates the object on its own (coordinates present where required).
     *
     * @return a list of problems; empty when the object is usable
     */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        String label = id.isEmpty() ? type : type + " '" + id + "'";
        switch (type) {
            case "box", "prism", "ring", "floor", "stairs", "beam", "wall" -> {
                if (from == null || to == null) errors.add(label + ": needs from and to");
            }
            case "cylinder", "cone", "sphere" -> {
                if (center == null) errors.add(label + ": needs center");
                if (dbl(raw, "radius", 0) <= 0) errors.add(label + ": needs a positive radius");
            }
            case "window", "door", "arch" -> {
                if (at == null) errors.add(label + ": needs at [u,y]");
                String wall = str(raw, "wall").toLowerCase(Locale.ROOT);
                if (!List.of("north", "south", "east", "west").contains(wall)) {
                    errors.add(label + ": wall must be north|south|east|west");
                }
            }
            case "column", "chimney" -> {
                if (at == null) errors.add(label + ": needs at [x,y,z]");
            }
            case "balustrade" -> {
                if (from == null || to == null) errors.add(label + ": needs from and to");
            }
            case "detail" -> {
                JsonElement cells = raw.get("cells");
                if (cells == null || !cells.isJsonArray() || cells.getAsJsonArray().isEmpty()) {
                    errors.add(label + ": needs a non-empty cells array");
                }
            }
            default -> { }
        }
        return errors;
    }

    /** @return the object id, or "" when the model did not give one */
    public String id() {
        return id;
    }

    /** @return the object type (already normalised: {@code roof} becomes {@code prism}) */
    public String type() {
        return type;
    }

    /** @return the palette key of the main material, or "" for none */
    public String material() {
        return material;
    }

    /** @return the minimal corner, or {@code null} */
    public int[] from() {
        return from == null ? null : from.clone();
    }

    /** @return the maximal corner, or {@code null} */
    public int[] to() {
        return to == null ? null : to.clone();
    }

    /** @return the centre point (cylinders, cones, spheres), or {@code null} */
    public int[] center() {
        return center == null ? null : center.clone();
    }

    /** @return the anchor point (windows, doors, columns, chimneys), or {@code null} */
    public int[] at() {
        return at == null ? null : at.clone();
    }

    /** @return the underlying JSON, for fields this class does not model explicitly */
    public JsonObject raw() {
        return raw;
    }

    /** @return a label for logs and critic prompts: {@code #id type} or just the type */
    public String label() {
        return id.isEmpty() ? type : "#" + id + " " + type;
    }

    /**
     * Serialises back to JSON, preserving the original fields (so a round-trip through the model keeps
     * {@code openings}, {@code style}, {@code pitch} and everything else).
     *
     * @return a fresh JSON object
     */
    public JsonObject toJson() {
        return raw.deepCopy();
    }

    /**
     * Counts objects by type.
     *
     * @param objects the objects to count
     * @return type to count, in first-seen order
     */
    public static Map<String, Integer> counts(List<SceneObject> objects) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (SceneObject o : objects) map.merge(o.type, 1, Integer::sum);
        return map;
    }

    @Override
    public String toString() {
        return label();
    }
}
