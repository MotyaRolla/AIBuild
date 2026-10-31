package dev.aibuild.scene;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The edit an AI critic asks for: objects to add, to replace and to remove.
 *
 * <p>This is the "patch" member of the critique answer</p>
 *
 * <pre>{@code
 * {"add": [OBJ, ...], "replace": [OBJ, ...], "remove": ["roof", "#frame", 3]}
 * }</pre>
 *
 * <p>and the only place in the 3D pipeline where a model answer is allowed to change the scene.
 * Applying is therefore deliberately forgiving: every operation is resolved, applied and re-validated
 * on its own, and an operation that would corrupt the scene is dropped with an error message while the
 * rest of the patch still lands. A bad id from the model costs one object, never the iteration.</p>
 *
 * <p>A reference may be:</p>
 * <ul>
 *   <li>an object {@code id} ({@code "roof"}) or a prefixed id ({@code "#roof"});</li>
 *   <li>a <b>0-based</b> index into the {@code objects} array ({@code 0} is the first object); when the
 *       number is out of that range it is read as a <b>1-based</b> position instead, which is how the
 *       digest in the prompt numbers objects. Ids always win over numbers, so a model that follows the
 *       prompt and uses ids can never hit the ambiguity.</li>
 * </ul>
 *
 * <p>Instances are immutable: the object lists are deep-copied on construction and {@link #apply} never
 * touches the spec it is given (it works on a copy of the raw JSON and returns a new {@link SceneSpec}).</p>
 */
public record ScenePatch(List<JsonObject> add, List<JsonObject> replace, List<String> remove) {

    /** Upper bound on operations taken from one answer; extra operations are dropped (prompt says 12). */
    public static final int MAX_OPERATIONS = 40;

    /** The "no change" patch a happy critic produces. */
    public static final ScenePatch EMPTY = new ScenePatch(List.of(), List.of(), List.of());

    /** Defensive deep copies; {@code null} lists become empty ones and {@code null} elements are skipped. */
    public ScenePatch {
        add = copyObjects(add);
        replace = copyObjects(replace);
        remove = copyStrings(remove);
    }

    /**
     * Parses the patch from a critique answer.
     *
     * <p>Tolerates a missing {@code patch} member (empty patch), a patch nested under {@code "patch"} in
     * a larger answer, {@code remove} entries that are ids, {@code "#id"}, numbers or
     * {@code {"id": …}} / {@code {"index": …}} objects, and more operations than
     * {@link #MAX_OPERATIONS} (the extras are dropped). Never throws for malformed input.</p>
     *
     * @param json the critique answer or the patch object itself; may be {@code null}
     * @return the parsed patch, {@link #EMPTY} when there is nothing usable
     */
    public static ScenePatch parse(JsonObject json) {
        if (json == null) return EMPTY;
        JsonObject source = json;
        JsonElement nested = json.get("patch");
        if (nested != null && nested.isJsonObject()
                && !json.has("add") && !json.has("replace") && !json.has("remove")) {
            source = nested.getAsJsonObject();
        }
        List<JsonObject> adds = objectsIn(source.get("add"));
        List<JsonObject> replaces = objectsIn(source.get("replace"));
        List<String> removes = refsIn(source.get("remove"));

        int budget = MAX_OPERATIONS;
        adds = trimObjects(adds, budget);
        budget -= adds.size();
        replaces = trimObjects(replaces, Math.max(0, budget));
        budget -= replaces.size();
        removes = trimStrings(removes, Math.max(0, budget));
        return new ScenePatch(adds, replaces, removes);
    }

    /** @return {@code true} when the critic asked for no change at all */
    public boolean isEmpty() {
        return add.isEmpty() && replace.isEmpty() && remove.isEmpty();
    }

    /** @return the number of requested operations */
    public int size() {
        return add.size() + replace.size() + remove.size();
    }

    /**
     * Applies this patch to a spec.
     *
     * <p>Removes run first, then replaces, then adds — so a patch that both removes and re-adds the same
     * id behaves predictably. Every accepted operation is re-parsed with {@link SceneSpec#parse} and
     * committed only when the whole scene is still valid and at most {@code maxObjects} objects remain;
     * a rejected operation leaves the scene exactly as it was before it.</p>
     *
     * @param spec       the scene to patch; must not be {@code null}
     * @param maxObjects guardrail on the object count (a patch may not grow the scene past it)
     * @return what was applied, what was dropped and why; never {@code null}
     * @throws IllegalArgumentException if {@code spec} is {@code null}
     */
    public Result apply(SceneSpec spec, int maxObjects) {
        if (spec == null) throw new IllegalArgumentException("spec is null");
        int limit = Math.max(1, maxObjects);
        List<JsonObject> working = new ArrayList<>(spec.objects().size());
        for (SceneObject o : spec.objects()) working.add(o.toJson());
        JsonObject base = spec.toJson();

        List<String> errors = new ArrayList<>();
        List<Change> changes = new ArrayList<>();

        for (String ref : remove) {
            int index = resolve(working, ref);
            if (index < 0) {
                errors.add("remove: неизвестный объект " + quote(ref));
                continue;
            }
            JsonObject removed = working.get(index);
            List<JsonObject> next = new ArrayList<>(working);
            next.remove(index);
            List<JsonObject> committed = commit(base, next, limit, errors, "remove");
            if (committed != null) {
                working = committed;
                changes.add(new Change("remove", idOf(removed), typeOf(removed), index));
            }
        }

        for (JsonObject raw : replace) {
            JsonObject next = raw.deepCopy();
            int index = resolveObject(working, next);
            if (index < 0) {
                errors.add("replace: неизвестный объект " + quote(refOf(next)));
                continue;
            }
            next.remove("index");
            String targetId = idOf(working.get(index));
            if (!targetId.isEmpty()) {
                // A replacement keeps the id of what it replaces: ids stay stable for later patches and
                // for the history the player reads.
                next.addProperty("id", targetId);
            }
            List<JsonObject> candidate = new ArrayList<>(working);
            candidate.set(index, next);
            List<JsonObject> committed = commit(base, candidate, limit, errors, "replace");
            if (committed != null) {
                working = committed;
                changes.add(new Change("replace", idOf(next), typeOf(next), index));
            }
        }

        for (JsonObject raw : add) {
            JsonObject next = raw.deepCopy();
            next.remove("index");
            if (idOf(next).isEmpty()) {
                next.addProperty("id", freshId(working));
            }
            String id = idOf(next);
            if (hasId(working, id)) {
                errors.add("add: идентификатор " + quote(id) + " уже занят");
                continue;
            }
            List<JsonObject> candidate = new ArrayList<>(working);
            candidate.add(next);
            List<JsonObject> committed = commit(base, candidate, limit, errors, "add");
            if (committed != null) {
                String finalId = idOf(next);
                working = committed;
                changes.add(new Change("add", finalId, typeOf(next), committed.size() - 1));
            }
        }

        if (changes.isEmpty()) {
            boolean empty = size() == 0;
            return new Result(spec, false, empty,
                    errors.isEmpty() && !empty ? List.of("патч не содержит применимых операций") : List.copyOf(errors),
                    List.of());
        }
        return new Result(spec.withObjects(parseCommitted(working, base)), true, false, List.copyOf(errors),
                List.copyOf(changes));
    }

    /** Convenience overload with the pipeline's default object budget. */
    public Result apply(SceneSpec spec) {
        return apply(spec, 300);
    }

    // ------------------------------------------------------------------ commit / validation

    /**
     * Rebuilds the spec from candidate objects and accepts it only when it parses cleanly.
     *
     * @return the normalised object list (as a fresh mutable list) or {@code null} when rejected
     */
    private static List<JsonObject> commit(JsonObject base, List<JsonObject> candidate, int maxObjects,
                                           List<String> errors, String op) {
        if (candidate.isEmpty()) {
            errors.add(op + ": сцена не может остаться без объектов (операция отклонена)");
            return null;
        }
        JsonObject json = base.deepCopy();
        JsonArray array = new JsonArray();
        for (JsonObject o : candidate) array.add(o);
        json.add("objects", array);
        SceneSpec parsed;
        try {
            parsed = SceneSpec.parse(json);
        } catch (RuntimeException e) {
            errors.add(op + ": " + e.getMessage() + " (операция отклонена)");
            return null;
        }
        if (parsed.objects().size() != candidate.size()) {
            // SceneSpec.parse drops invalid objects instead of throwing; a drop means the operation
            // would have silently deleted parts of the scene, so it is rejected as a whole.
            String problem = parsed.errors().isEmpty() ? "объект отклонён валидатором"
                    : parsed.errors().get(parsed.errors().size() - 1);
            errors.add(op + ": " + problem + " (операция отклонена)");
            return null;
        }
        if (parsed.objects().size() > maxObjects) {
            errors.add(op + ": превышен лимит в " + maxObjects + " объектов (операция отклонена)");
            return null;
        }
        if (parsed.objects().isEmpty()) {
            errors.add(op + ": сцена не может остаться без объектов (операция отклонена)");
            return null;
        }
        return copyParsed(parsed);
    }

    private static List<SceneObject> parseCommitted(List<JsonObject> objects, JsonObject base) {
        JsonObject json = base.deepCopy();
        JsonArray array = new JsonArray();
        for (JsonObject o : objects) array.add(o);
        json.add("objects", array);
        return SceneSpec.parse(json).objects();
    }

    private static List<JsonObject> copyParsed(SceneSpec spec) {
        List<JsonObject> out = new ArrayList<>(spec.objects().size());
        for (SceneObject o : spec.objects()) out.add(o.toJson());
        return out;
    }

    // ------------------------------------------------------------------ reference resolution

    /**
     * Resolves one textual reference ({@code id}, {@code "#id"} or a number).
     *
     * @return the 0-based index in {@code objects}, or {@code -1} when nothing matches
     */
    static int resolve(List<JsonObject> objects, String ref) {
        if (ref == null) return -1;
        String raw = ref.trim();
        if (raw.isEmpty()) return -1;
        String id = raw.startsWith("#") ? raw.substring(1) : raw;
        int byId = indexOfId(objects, id);
        if (byId >= 0) return byId;
        Long number = asLong(id);
        return number == null ? -1 : resolveNumber(objects.size(), number.intValue());
    }

    /**
     * Resolves the reference carried by a replacement object ({@code id}, {@code "#id"}, {@code index}).
     *
     * @return the 0-based index in {@code objects}, or {@code -1} when nothing matches
     */
    static int resolveObject(List<JsonObject> objects, JsonObject ref) {
        if (ref == null) return -1;
        JsonElement index = ref.get("index");
        if (isNumber(index)) {
            return resolveNumber(objects.size(), index.getAsInt());
        }
        JsonElement id = ref.get("id");
        if (id == null || id.isJsonNull()) return -1;
        if (isNumber(id)) return resolveNumber(objects.size(), id.getAsInt());
        if (id.isJsonPrimitive()) return resolve(objects, id.getAsString());
        return -1;
    }

    /**
     * Maps a numeric reference to an index: a 0-based index when it exists, otherwise a 1-based position.
     *
     * @param size  object count
     * @param value the reference as written by the model
     * @return the index, or {@code -1} when both readings are out of range
     */
    static int resolveNumber(int size, int value) {
        if (value >= 0 && value < size) return value;
        if (value >= 1 && value <= size) return value - 1;
        return -1;
    }

    private static int indexOfId(List<JsonObject> objects, String id) {
        if (id.isEmpty()) return -1;
        for (int i = 0; i < objects.size(); i++) {
            String candidate = idOf(objects.get(i));
            if (candidate.equals(id)) return i;
        }
        // ids are case-sensitive in the spec, but a model that changes the case still means the object
        for (int i = 0; i < objects.size(); i++) {
            String candidate = idOf(objects.get(i));
            if (candidate.equalsIgnoreCase(id)) return i;
        }
        return -1;
    }

    private static boolean hasId(List<JsonObject> objects, String id) {
        return indexOfId(objects, id) >= 0;
    }

    private static String freshId(List<JsonObject> objects) {
        int n = objects.size() + 1;
        while (hasId(objects, "ai-" + n)) n++;
        return "ai-" + n;
    }

    // ------------------------------------------------------------------ small JSON helpers

    private static String idOf(JsonObject o) {
        JsonElement e = o == null ? null : o.get("id");
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return "";
        try {
            return e.getAsString().trim();
        } catch (RuntimeException ex) {
            return "";
        }
    }

    private static String typeOf(JsonObject o) {
        JsonElement e = o == null ? null : o.get("type");
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return "";
        try {
            return e.getAsString().trim().toLowerCase(Locale.ROOT);
        } catch (RuntimeException ex) {
            return "";
        }
    }

    private static String refOf(JsonObject o) {
        JsonElement e = o == null ? null : o.get("id");
        if (e != null && !e.isJsonNull() && e.isJsonPrimitive()) {
            try {
                return e.getAsString();
            } catch (RuntimeException ignored) {
                // fall through to index
            }
        }
        JsonElement index = o == null ? null : o.get("index");
        return index == null || index.isJsonNull() ? "?" : index.toString();
    }

    private static boolean isNumber(JsonElement e) {
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return false;
        JsonPrimitive p = e.getAsJsonPrimitive();
        return p.isNumber();
    }

    private static Long asLong(String s) {
        if (s == null || s.isEmpty() || s.length() > 9) return null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return null;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String quote(String s) {
        return "«" + s + "»";
    }

    private static List<JsonObject> objectsIn(JsonElement element) {
        if (element == null || !element.isJsonArray()) return List.of();
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement e : element.getAsJsonArray()) {
            if (e != null && e.isJsonObject()) out.add(e.getAsJsonObject().deepCopy());
        }
        return out;
    }

    private static List<String> refsIn(JsonElement element) {
        if (element == null || !element.isJsonArray()) return List.of();
        List<String> out = new ArrayList<>();
        for (JsonElement e : element.getAsJsonArray()) {
            if (e == null || e.isJsonNull()) continue;
            if (e.isJsonPrimitive()) {
                String s = e.getAsString().trim();
                if (!s.isEmpty()) out.add(s);
            } else if (e.isJsonObject()) {
                JsonObject o = e.getAsJsonObject();
                JsonElement id = o.get("id");
                if (id != null && !id.isJsonNull() && id.isJsonPrimitive()) {
                    out.add(id.getAsString());
                } else if (isNumber(o.get("index"))) {
                    out.add(o.get("index").getAsString());
                }
            }
        }
        return out;
    }

    private static List<JsonObject> copyObjects(List<JsonObject> in) {
        if (in == null || in.isEmpty()) return List.of();
        List<JsonObject> out = new ArrayList<>(in.size());
        for (JsonObject o : in) if (o != null) out.add(o.deepCopy());
        return List.copyOf(out);
    }

    private static List<String> copyStrings(List<String> in) {
        if (in == null || in.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(in.size());
        for (String s : in) if (s != null && !s.isBlank()) out.add(s.trim());
        return List.copyOf(out);
    }

    private static List<JsonObject> trimObjects(List<JsonObject> in, int limit) {
        if (in.size() <= limit) return in;
        return List.copyOf(in.subList(0, Math.max(0, limit)));
    }

    private static List<String> trimStrings(List<String> in, int limit) {
        if (in.size() <= limit) return in;
        return List.copyOf(in.subList(0, Math.max(0, limit)));
    }

    /** One applied operation, used for the Russian report and for the history. */
    public record Change(String op, String id, String type, int index) {

        /**
         * @return a short Russian description, e.g. {@code "заменён объект «roof» (prism)"}
         */
        public String describe() {
            String what = type == null || type.isEmpty() ? "" : " (" + type + ")";
            String who = id == null || id.isEmpty() ? "№" + (index + 1) : "«" + id + "»";
            return switch (op == null ? "" : op) {
                case "add" -> "добавлен объект " + who + what;
                case "replace" -> "заменён объект " + who + what;
                default -> "удалён объект " + who + what;
            };
        }
    }

    /**
     * Outcome of {@link #apply}.
     *
     * @param spec    the scene after the patch; the original instance when nothing was applied
     * @param applied whether at least one operation changed the scene
     * @param empty   whether the critic asked for no change at all (not an error)
     * @param errors  Russian messages for dropped operations, in the order they were dropped
     * @param changes the applied operations, in application order
     */
    public record Result(SceneSpec spec, boolean applied, boolean empty, List<String> errors,
                         List<Change> changes) {

        /** Canonicalises the lists. */
        public Result {
            errors = errors == null ? List.of() : List.copyOf(errors);
            changes = changes == null ? List.of() : List.copyOf(changes);
        }

        /** @return {@code true} when the model asked for changes and none of them were usable */
        public boolean rejected() {
            return !applied && !empty;
        }

        /** @return one Russian line for the chat report */
        public String summary() {
            if (applied) {
                StringBuilder sb = new StringBuilder("правки применены: ");
                int shown = 0;
                for (Change c : changes) {
                    if (shown > 0) sb.append("; ");
                    if (shown++ >= 4) {
                        sb.append("ещё ").append(changes.size() - 4);
                        break;
                    }
                    sb.append(c.describe());
                }
                if (!errors.isEmpty()) sb.append(" (").append(errors.size()).append(" оп. отклонено)");
                return sb.toString();
            }
            if (empty) return "правок не нужно";
            return errors.isEmpty() ? "правки отклонены" : "правки отклонены: " + errors.get(0);
        }
    }
}
