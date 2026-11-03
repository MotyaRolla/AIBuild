package dev.aibuild.build;

import com.google.gson.JsonObject;
import dev.aibuild.model.VoxelModel;

/**
 * One immutable-in-practice revision of a {@link Build}'s model: the model itself plus the request that
 * produced it and a quality score from the verify loop.
 *
 * <p>Instances are mutable public-field carriers (Gson-friendly, cheap to clone). They are handed between
 * threads only through {@link BuildStore}'s snapshotting contract: a {@code Build} (and therefore its
 * versions) must only be mutated on the server main thread. Read-only access (serialization) is safe from
 * any thread because {@link VoxelModel} itself is never mutated after a version is published.</p>
 *
 * <p>JSON shape:</p>
 * <pre>
 * {"number":3,"request":"add a chimney","summary":"added brick chimney",
 *  "createdAt":1730000000000,"score":87,"model":{...VoxelModel.toJson()...}}
 * </pre>
 */
public final class BuildVersion {

    /** 1-based version number (display only; the authoritative index is the position in the list). */
    public int number;
    /** The user request that produced this version (may be null/blank for console-created builds). */
    public String request;
    /** Short human/LLM summary of what changed in this version, e.g. "added a brick chimney". */
    public String summary;
    /** The model of this version; never null for a stored version (may be empty, not null). */
    public VoxelModel model;
    /** Creation timestamp, {@link System#currentTimeMillis()}. */
    public long createdAt;
    /** Quality score 0..100 from the verify loop, or 0 when unverified. */
    public int score;
    /** Thinking mode id that produced this version (fast/light/standard/extra), or null (import/manual). */
    public String mode;
    /** null for legacy/manual versions, false for a generated draft, true for an approved generation. */
    public Boolean approved;
    /** Build script that produced this version (script strategy), or null. */
    public String script;
    /** Seed the script ran with. */
    public long seed;
    /** Stdlib version the script ran against, or null. */
    public String stdlibVersion;
    /** True when re-running {@link #script} reproduces {@link #model} exactly (no later op patches). */
    public boolean scriptExact;

    /** Creates an empty version (number 0, empty model). Primarily for deserialization. */
    public BuildVersion() {
        this.model = new VoxelModel();
        this.createdAt = System.currentTimeMillis();
    }

    /**
     * Creates a version.
     *
     * @param number   1-based version number
     * @param request  originating user request (nullable)
     * @param summary  short summary (nullable)
     * @param model    model snapshot; {@code null} is replaced by an empty model
     * @param createdAt epoch millis
     * @param score    quality score 0..100
     */
    public BuildVersion(int number, String request, String summary, VoxelModel model, long createdAt, int score) {
        this.number = number;
        this.request = request;
        this.summary = summary;
        this.model = model != null ? model : new VoxelModel();
        this.createdAt = createdAt > 0 ? createdAt : System.currentTimeMillis();
        this.score = score;
    }

    /** Convenience factory for the first version of a build. */
    public static BuildVersion of(int number, String request, VoxelModel model) {
        return new BuildVersion(number, request, null, model, System.currentTimeMillis(), 0);
    }

    /** Deep copy (the model is copied, not shared). */
    public BuildVersion copy() {
        BuildVersion c = new BuildVersion(number, request, summary, model == null ? new VoxelModel() : model.copy(),
                createdAt, score);
        c.mode = mode;
        c.approved = approved;
        c.script = script;
        c.seed = seed;
        c.stdlibVersion = stdlibVersion;
        c.scriptExact = scriptExact;
        return c;
    }

    /**
     * Returns a one-line description for chat output, e.g. {@code "#2 (score 87) added a brick chimney"}.
     * Never null.
     */
    public String display() {
        StringBuilder sb = new StringBuilder("#").append(number);
        if (score > 0) sb.append(" (score ").append(score).append(')');
        if (Boolean.FALSE.equals(approved)) sb.append(" [draft]");
        String s = summary;
        if (s == null || s.isBlank()) s = request;
        if (s != null && !s.isBlank()) {
            String flat = s.replace('\n', ' ').replace('\r', ' ').trim();
            if (flat.length() > 80) flat = flat.substring(0, 77) + "...";
            sb.append(' ').append(flat);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ serialization

    /** Serializes this version; the model is embedded as a nested object. */
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("number", number);
        if (request != null) o.addProperty("request", request);
        if (summary != null) o.addProperty("summary", summary);
        o.addProperty("createdAt", createdAt);
        o.addProperty("score", score);
        if (mode != null) o.addProperty("mode", mode);
        if (approved != null) o.addProperty("approved", approved);
        if (script != null) {
            o.addProperty("script", script);
            o.addProperty("seed", seed);
            if (stdlibVersion != null) o.addProperty("stdlibVersion", stdlibVersion);
            o.addProperty("scriptExact", scriptExact);
        }
        o.add("model", (model == null ? new VoxelModel() : model).toJson());
        return o;
    }

    /**
     * Parses a version. Missing or malformed optional fields fall back to defaults; a missing/invalid model
     * becomes an empty model so a partially corrupt file still loads a usable build.
     *
     * @throws IllegalArgumentException when {@code o} is null
     */
    public static BuildVersion fromJson(JsonObject o) {
        if (o == null) throw new IllegalArgumentException("version json is null");
        BuildVersion v = new BuildVersion();
        v.number = clampInt(optInt(o, "number", 0), 0, Integer.MAX_VALUE);
        v.request = optString(o, "request");
        v.summary = optString(o, "summary");
        v.createdAt = optLong(o, "createdAt", 0L);
        v.score = clampInt(optInt(o, "score", 0), 0, 100);
        v.mode = optString(o, "mode");
        if (o.has("approved") && !o.get("approved").isJsonNull()) v.approved = optBoolean(o,"approved",false);
        v.script = optString(o, "script");
        v.seed = optLong(o, "seed", 0L);
        v.stdlibVersion = optString(o, "stdlibVersion");
        v.scriptExact = v.script != null && optBoolean(o, "scriptExact", false);
        JsonObject m = null;
        try {
            if (o.has("model") && o.get("model").isJsonObject()) m = o.getAsJsonObject("model");
        } catch (RuntimeException ignored) {
            // treat as missing
        }
        if (m != null) {
            try {
                v.model = VoxelModel.fromJson(m);
            } catch (RuntimeException e) {
                v.model = new VoxelModel();
            }
        } else {
            v.model = new VoxelModel();
        }
        return v;
    }

    @Override
    public String toString() {
        return "BuildVersion{#" + number + ", cells=" + (model == null ? 0 : model.size()) + ", score=" + score + '}';
    }

    // ------------------------------------------------------------------ tolerant json helpers
    // Duplicated tiny helpers instead of a shared util dependency: keeps this module compilable on its own.

    static String optString(JsonObject o, String key) {
        try {
            if (!o.has(key)) return null;
            var e = o.get(key);
            if (e == null || e.isJsonNull()) return null;
            return e.getAsString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    static int optInt(JsonObject o, String key, int def) {
        try {
            if (!o.has(key)) return def;
            var e = o.get(key);
            if (e == null || e.isJsonNull()) return def;
            return e.getAsInt();
        } catch (RuntimeException e) {
            return def;
        }
    }

    static long optLong(JsonObject o, String key, long def) {
        try {
            if (!o.has(key)) return def;
            var e = o.get(key);
            if (e == null || e.isJsonNull()) return def;
            return e.getAsLong();
        } catch (RuntimeException e) {
            return def;
        }
    }

    static boolean optBoolean(JsonObject o, String key, boolean def) {
        try {
            if (!o.has(key)) return def;
            var e = o.get(key);
            if (e == null || e.isJsonNull()) return def;
            return e.getAsBoolean();
        } catch (RuntimeException e) {
            return def;
        }
    }

    static int clampInt(int v, int min, int max) {
        return v < min ? min : Math.min(v, max);
    }
}
