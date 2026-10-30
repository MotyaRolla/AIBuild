package dev.aibuild.build;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.aibuild.model.VoxelModel;
import dev.aibuild.world.PlacementMath;
import dev.aibuild.world.WorldKey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A user build: placement metadata, the version history of its model and the terrain backup needed to
 * undo it.
 *
 * <h2>Version model</h2>
 * {@link #versions} is append-only; {@link #currentVersion} is an index into it, {@code -1} meaning
 * "nothing of this build is placed in the world" (only terrain / removed build). {@link #current()} and
 * {@link #currentModel()} return {@code null} for {@code -1}. {@link #canUndo()} is true whenever a
 * previous state exists (including the initial "nothing placed" state), {@link #canRedo()} when newer
 * versions remain.
 *
 * <h2>Terrain backup</h2>
 * {@link #terrain} maps {@link WorldKey packed world coordinates} to the block-state string that was
 * present <b>before AIBuild first modified that cell</b>. It is captured lazily by the world placer and is
 * the authoritative source for "restore the site" operations. Entries are only added, never removed,
 * because the earliest observed state is the one that must be restored.
 *
 * <h2>Threading</h2>
 * Mutable public-field carrier. It is <b>not</b> safe for concurrent mutation: mutate only on the server
 * main thread. Cross-thread reads of an already published build are tolerated (the version list is only
 * appended to, {@link #terrain} is a {@link ConcurrentHashMap}) but must not be combined with writes
 * without external synchronization. {@link BuildStore#save(Build)} snapshots on the calling thread.
 */
public final class Build {

    /** Unique build id: exactly 6 lower-case hexadecimal characters, e.g. {@code "3fa8c1"}. */
    public String id;
    /** Owner UUID, {@code null} for console/system builds. */
    public UUID owner;
    /** Owner display name (never null; "console" for console builds). */
    public String ownerName;
    /** User visible build name (defaults to the first request / plan title). */
    public String name;
    /** World the build lives in (bukkit world name). */
    public String worldName;
    /** World x of model space (0,0,0) after rotation. */
    public int originX;
    /** World y of model layer 0 (the ground surface layer). */
    public int originY;
    /** World z of model space (0,0,0) after rotation. */
    public int originZ;
    /** Clockwise quarter turns 0..3 applied to the model when placing (see {@link PlacementMath}). */
    public int rotation;
    /** True while the build's current version is placed in the world. */
    public boolean placed;
    /** The original user prompt that created the build. */
    public String originalPrompt;
    /** The LLM design brief (plan), or {@code null}. Owned by the caller; serialized verbatim. */
    public JsonObject plan;
    /** Chronological list of user requests (edit history), newest last. */
    public List<String> history = new ArrayList<>();
    /** Append-only version list; index 0 is the first generation. */
    public List<BuildVersion> versions = new ArrayList<>();
    /** Index of the currently placed version, {@code -1} when nothing of this build is placed. */
    public int currentVersion = -1;
    /** Creation timestamp (epoch millis). */
    public long createdAt;
    /** Last modification timestamp (epoch millis). */
    public long updatedAt;
    /** Packed world coordinate → original block state string, captured before AIBuild modified the cell. */
    public Map<Long, String> terrain = new ConcurrentHashMap<>();

    /** Empty build for deserialization; {@link #id} is generated so the instance is always usable. */
    public Build() {
        this.id = randomId();
        this.ownerName = "console";
        this.name = "Build";
        this.worldName = "world";
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = this.createdAt;
    }

    /**
     * Creates a build with a generated id.
     *
     * @param owner     owner UUID or {@code null} for console
     * @param ownerName owner display name; blank becomes "console"
     * @param worldName bukkit world name; blank becomes "world"
     */
    public Build(UUID owner, String ownerName, String worldName) {
        this();
        this.owner = owner;
        this.ownerName = ownerName == null || ownerName.isBlank() ? "console" : ownerName;
        this.worldName = worldName == null || worldName.isBlank() ? "world" : worldName;
    }

    // ------------------------------------------------------------------ versions

    /** The currently placed version, or {@code null} when {@link #currentVersion} is out of range. */
    public BuildVersion current() {
        List<BuildVersion> v = versions;
        int i = currentVersion;
        if (v == null || i < 0 || i >= v.size()) return null;
        return v.get(i);
    }

    /** The model of the current version, or {@code null} when nothing is placed. */
    public VoxelModel currentModel() {
        BuildVersion v = current();
        return v == null ? null : v.model;
    }

    /** Bounding box of the current model in model space, or {@code null} when there is no model. */
    public int[] modelBounds() {
        VoxelModel m = currentModel();
        if (m == null || m.isEmpty()) return null;
        return m.bounds();
    }

    /** True when a previous state exists (an earlier version, or the empty initial state). */
    public boolean canUndo() {
        List<BuildVersion> v = versions;
        if (v == null) return false;
        return currentVersion > -1 && !v.isEmpty();
    }

    /** True when a newer version than the current one exists. */
    public boolean canRedo() {
        List<BuildVersion> v = versions;
        return v != null && currentVersion < v.size() - 1;
    }

    /**
     * Appends a version (model snapshot is copied, not shared) and makes it current.
     *
     * @param model   the generated model (copied)
     * @param request the user request that produced it (nullable)
     * @param summary short summary (nullable)
     * @param score   verify-loop score 0..100
     * @return the appended version
     */
    public BuildVersion addVersion(VoxelModel model, String request, String summary, int score) {
        if (versions == null) versions = new ArrayList<>();
        BuildVersion v = new BuildVersion(versions.size() + 1, request, summary,
                model == null ? new VoxelModel() : model.copy(), System.currentTimeMillis(), score);
        versions.add(v);
        currentVersion = versions.size() - 1;
        if (request != null && !request.isBlank()) {
            if (history == null) history = new ArrayList<>();
            history.add(request);
        }
        updatedAt = System.currentTimeMillis();
        return v;
    }

    /**
     * Steps one version back.
     *
     * @return the now-current version, or {@code null} when the build was moved to the empty state
     * @throws IllegalStateException when {@link #canUndo()} is false
     */
    public BuildVersion undo() {
        if (!canUndo()) throw new IllegalStateException("nothing to undo for build " + id);
        currentVersion--;
        updatedAt = System.currentTimeMillis();
        return current();
    }

    /**
     * Steps one version forward.
     *
     * @return the now-current version
     * @throws IllegalStateException when {@link #canRedo()} is false
     */
    public BuildVersion redo() {
        if (!canRedo()) throw new IllegalStateException("nothing to redo for build " + id);
        currentVersion++;
        updatedAt = System.currentTimeMillis();
        return current();
    }

    // ------------------------------------------------------------------ terrain

    /** Records the original block state of a cell if it is not yet known. Main thread only. */
    public void captureTerrain(int x, int y, int z, String original) {
        if (original == null) return;
        if (terrain == null) terrain = new ConcurrentHashMap<>();
        terrain.putIfAbsent(WorldKey.pack(x, y, z), original);
    }

    /** The recorded original state of a cell, or {@code null}. */
    public String originalState(int x, int y, int z) {
        Map<Long, String> t = terrain;
        return t == null ? null : t.get(WorldKey.pack(x, y, z));
    }

    /** Forgets one cell (used when a cell no longer differs from its original state). */
    public void forgetTerrain(int x, int y, int z) {
        if (terrain != null) terrain.remove(WorldKey.pack(x, y, z));
    }

    /** Number of terrain backup entries (cells the build has touched at least once). */
    public int terrainSize() {
        Map<Long, String> t = terrain;
        return t == null ? 0 : t.size();
    }

    /** Marks the build dirty (call after mutating placement fields). */
    public void touch() {
        updatedAt = System.currentTimeMillis();
    }

    @Override
    public String toString() {
        return "Build{" + id + " '" + name + "' " + worldName + " " + originX + "," + originY + "," + originZ
                + " rot=" + rotation + " v=" + currentVersion + "/" + versions.size()
                + (placed ? " placed" : "") + '}';
    }

    // ------------------------------------------------------------------ serialization

    /**
     * Serializes the build to JSON. Terrain is stored compactly as a palette plus a flat int array of
     * {@code x,y,z,paletteIndex} quadruples. The plan object is copied so later caller mutations cannot
     * corrupt the snapshot.
     */
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        if (owner != null) o.addProperty("owner", owner.toString());
        o.addProperty("ownerName", ownerName);
        o.addProperty("name", name);
        o.addProperty("worldName", worldName);
        o.addProperty("originX", originX);
        o.addProperty("originY", originY);
        o.addProperty("originZ", originZ);
        o.addProperty("rotation", PlacementMath.norm(rotation));
        o.addProperty("placed", placed);
        if (originalPrompt != null) o.addProperty("originalPrompt", originalPrompt);
        if (plan != null) o.add("plan", plan.deepCopy());
        JsonArray hist = new JsonArray();
        if (history != null) for (String h : history) if (h != null) hist.add(h);
        o.add("history", hist);
        JsonArray vs = new JsonArray();
        if (versions != null) for (BuildVersion v : versions) if (v != null) vs.add(v.toJson());
        o.add("versions", vs);
        o.addProperty("currentVersion", currentVersion);
        o.addProperty("createdAt", createdAt);
        o.addProperty("updatedAt", updatedAt);
        o.add("terrain", terrainToJson());
        return o;
    }

    private JsonObject terrainToJson() {
        JsonObject t = new JsonObject();
        Map<String, Integer> palette = new LinkedHashMap<>();
        JsonArray cells = new JsonArray();
        Map<Long, String> map = terrain;
        if (map != null) {
            for (Map.Entry<Long, String> e : map.entrySet()) {
                String state = e.getValue();
                if (state == null) continue;
                Integer idx = palette.get(state);
                if (idx == null) {
                    idx = palette.size();
                    palette.put(state, idx);
                }
                long k = e.getKey();
                cells.add(WorldKey.x(k));
                cells.add(WorldKey.y(k));
                cells.add(WorldKey.z(k));
                cells.add(idx);
            }
        }
        JsonArray pal = new JsonArray();
        for (String s : palette.keySet()) pal.add(s);
        t.add("palette", pal);
        t.add("cells", cells);
        return t;
    }

    /**
     * Parses a build. Unknown fields are ignored; missing/malformed optional fields fall back to safe
     * defaults. The id is preserved (the store relies on it), and a missing id is generated.
     *
     * @throws IllegalArgumentException when {@code o} is null
     */
    public static Build fromJson(JsonObject o) {
        if (o == null) throw new IllegalArgumentException("build json is null");
        Build b = new Build();
        b.id = sanitizeId(BuildVersion.optString(o, "id"));
        b.owner = parseUuid(BuildVersion.optString(o, "owner"));
        String on = BuildVersion.optString(o, "ownerName");
        b.ownerName = on == null || on.isBlank() ? "console" : on;
        String nm = BuildVersion.optString(o, "name");
        b.name = nm == null || nm.isBlank() ? "Build" : nm;
        String wn = BuildVersion.optString(o, "worldName");
        b.worldName = wn == null || wn.isBlank() ? "world" : wn;
        b.originX = BuildVersion.optInt(o, "originX", 0);
        b.originY = BuildVersion.optInt(o, "originY", 0);
        b.originZ = BuildVersion.optInt(o, "originZ", 0);
        b.rotation = PlacementMath.norm(BuildVersion.optInt(o, "rotation", 0));
        b.placed = BuildVersion.optBoolean(o, "placed", false);
        b.originalPrompt = BuildVersion.optString(o, "originalPrompt");

        JsonObject plan = null;
        try {
            if (o.has("plan") && o.get("plan").isJsonObject()) plan = o.getAsJsonObject("plan").deepCopy();
        } catch (RuntimeException ignored) {
            // ignore corrupt plan
        }
        b.plan = plan;

        List<String> hist = new ArrayList<>();
        try {
            JsonElement he = o.get("history");
            if (he != null && he.isJsonArray()) {
                for (JsonElement e : he.getAsJsonArray()) {
                    if (e != null && !e.isJsonNull()) {
                        try {
                            hist.add(e.getAsString());
                        } catch (RuntimeException ignored) {
                            // skip non-string entry
                        }
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // ignore corrupt history
        }
        b.history = hist;

        List<BuildVersion> vs = new ArrayList<>();
        try {
            JsonElement ve = o.get("versions");
            if (ve != null && ve.isJsonArray()) {
                for (JsonElement e : ve.getAsJsonArray()) {
                    if (e != null && e.isJsonObject()) {
                        try {
                            vs.add(BuildVersion.fromJson(e.getAsJsonObject()));
                        } catch (RuntimeException ignored) {
                            // skip corrupt version
                        }
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // ignore corrupt version list
        }
        b.versions = vs;
        int cv = BuildVersion.optInt(o, "currentVersion", vs.isEmpty() ? -1 : vs.size() - 1);
        b.currentVersion = cv < -1 ? -1 : Math.min(cv, vs.size() - 1);
        b.createdAt = BuildVersion.optLong(o, "createdAt", System.currentTimeMillis());
        b.updatedAt = BuildVersion.optLong(o, "updatedAt", b.createdAt);

        Map<Long, String> terrain = new ConcurrentHashMap<>();
        try {
            JsonElement te = o.get("terrain");
            if (te != null && te.isJsonObject()) terrainFromJson(te.getAsJsonObject(), terrain);
        } catch (RuntimeException ignored) {
            // ignore corrupt terrain (worst case the site cannot be restored perfectly)
        }
        b.terrain = terrain;
        return b;
    }

    private static void terrainFromJson(JsonObject t, Map<Long, String> out) {
        JsonArray palArr = t.has("palette") && t.get("palette").isJsonArray() ? t.getAsJsonArray("palette") : null;
        if (palArr == null) return;
        String[] pal = new String[palArr.size()];
        for (int i = 0; i < pal.length; i++) {
            try {
                JsonElement e = palArr.get(i);
                pal[i] = e == null || e.isJsonNull() ? null : e.getAsString();
            } catch (RuntimeException ignored) {
                pal[i] = null;
            }
        }
        JsonElement ce = t.get("cells");
        if (ce == null || !ce.isJsonArray()) return;
        JsonArray cells = ce.getAsJsonArray();
        for (int i = 0; i + 3 < cells.size(); i += 4) {
            try {
                int x = cells.get(i).getAsInt();
                int y = cells.get(i + 1).getAsInt();
                int z = cells.get(i + 2).getAsInt();
                int idx = cells.get(i + 3).getAsInt();
                if (idx < 0 || idx >= pal.length || pal[idx] == null) continue;
                out.put(WorldKey.pack(x, y, z), pal[idx]);
            } catch (RuntimeException ignored) {
                // skip bad quadruple
            }
        }
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ ids

    private static final java.security.SecureRandom ID_RANDOM = new java.security.SecureRandom();

    /** Generates a fresh 6 character lower-case hex build id. */
    public static String randomId() {
        return String.format(java.util.Locale.ROOT, "%06x", ID_RANDOM.nextInt(1 << 24));
    }

    /**
     * Normalizes a build id read from disk or a command: lower-cased, trimmed, and reduced to its first
     * 6 hex characters when longer; a blank or invalid id is replaced by a fresh random id.
     */
    public static String sanitizeId(String raw) {
        if (raw == null) return randomId();
        String s = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (s.endsWith(".json.gz")) s = s.substring(0, s.length() - ".json.gz".length());
        else if (s.endsWith(".json")) s = s.substring(0, s.length() - ".json".length());
        if (s.length() > 6) s = s.substring(0, 6);
        if (s.isEmpty()) return randomId();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) return randomId();
        }
        return s;
    }

    /** True when {@code id} is a well-formed build id (1..6 lower-case hex characters). */
    public static boolean isValidId(String id) {
        if (id == null || id.isEmpty() || id.length() > 6) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
        }
        return true;
    }
}
