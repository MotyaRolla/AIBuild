package dev.aibuild.build;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aibuild.world.PlacementMath;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Persistence for {@link Build}s.
 *
 * <h2>Layout</h2>
 * <pre>
 *   plugins/AIBuild/builds/&lt;id&gt;.json.gz   one gzip-compressed JSON document per build
 *   plugins/AIBuild/players.json          active build per player (small, plain JSON)
 * </pre>
 *
 * <h2>Threading contract</h2>
 * <ul>
 *     <li>The index ({@link #get}, {@link #all}, {@link #byOwner}, {@link #findAt}, active-player map) is a
 *     {@link ConcurrentHashMap} and safe from any thread.</li>
 *     <li>{@link #save(Build)} must be called on the server main thread: the build is serialized
 *     <b>synchronously on the calling thread</b> (so the caller's snapshot is consistent), and only the
 *     resulting bytes are handed to a single background writer thread. Saves of the same build are
 *     coalesced (last writer wins) and file replacement is atomic (temp file + move).</li>
 *     <li>{@link #delete(String)} removes the build from the index immediately and deletes the file on the
 *     writer thread.</li>
 *     <li>{@link #shutdown()} stops the writer and waits up to 10 seconds for pending writes.</li>
 * </ul>
 *
 * <p>Mutating a {@link Build} while another thread serializes it can produce a torn snapshot; that is the
 * caller's responsibility (main thread only).</p>
 */
public final class BuildStore {

    /** Sub-directory (relative to the plugin data folder) holding one gzip JSON file per build. */
    public static final String BUILDS_DIR = "builds";
    /** File name (relative to the plugin data folder) with the per-player active build selection. */
    public static final String PLAYERS_FILE = "players.json";

    private static final String BUILD_SUFFIX = ".json.gz";
    private static final long SHUTDOWN_TIMEOUT_MS = 10_000L;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final File dataFolder;
    private final File buildsFolder;
    private final File playersFile;
    private final Logger log;

    private final Map<String, Build> builds = new ConcurrentHashMap<>();
    private final Map<UUID, String> active = new ConcurrentHashMap<>();
    /** id -> latest serialized snapshot waiting to be written. */
    private final Map<String, byte[]> pending = new ConcurrentHashMap<>();
    /** ids whose file must be removed (prevents a queued write from resurrecting a deleted build). */
    private final Set<String> deleted = ConcurrentHashMap.newKeySet();

    private final ExecutorService writer;
    private final Object playersLock = new Object();
    private volatile boolean shuttingDown;

    /**
     * Creates a store. The directory structure is created lazily by {@link #loadAll()} / {@link #save}.
     *
     * @param dataFolder the plugin data folder ({@code plugins/AIBuild}); created when missing
     * @param log        logger for diagnostics; {@code null} falls back to a JUL logger named after this class
     */
    public BuildStore(File dataFolder, Logger log) {
        if (dataFolder == null) throw new IllegalArgumentException("dataFolder is null");
        this.dataFolder = dataFolder;
        this.buildsFolder = new File(dataFolder, BUILDS_DIR);
        this.playersFile = new File(dataFolder, PLAYERS_FILE);
        this.log = log != null ? log : Logger.getLogger(BuildStore.class.getName());
        AtomicInteger counter = new AtomicInteger();
        this.writer = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "AIBuild-store-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        ensureDir(buildsFolder);
    }

    /** The folder holding the build files (created on demand). */
    public File buildsFolder() {
        return buildsFolder;
    }

    // ------------------------------------------------------------------ loading

    /**
     * Loads every {@code builds/*.json.gz} into memory and reads the active-player file.
     * Corrupt files are logged and skipped (never abort startup). Files left over from an interrupted
     * atomic write ({@code *.tmp*}) are removed.
     *
     * <p>Safe to call once at startup on any thread; callers normally invoke it from
     * {@code onEnable()}.</p>
     */
    public void loadAll() {
        ensureDir(buildsFolder);
        File[] files = buildsFolder.listFiles();
        if (files == null) {
            log.warning("Cannot list build directory " + buildsFolder + " (permissions?)");
            files = new File[0];
        }
        int loaded = 0, skipped = 0;
        for (File f : files) {
            String n = f.getName();
            if (!f.isFile()) continue;
            if (n.contains(".tmp")) {
                // leftover from a crashed atomic write
                if (!f.delete()) log.fine("Could not delete stale temp file " + f);
                continue;
            }
            if (!n.endsWith(BUILD_SUFFIX)) continue;
            String id = n.substring(0, n.length() - BUILD_SUFFIX.length()).toLowerCase(java.util.Locale.ROOT);
            if (!Build.isValidId(id)) {
                log.warning("Skipping build file with invalid id: " + n);
                skipped++;
                continue;
            }
            try {
                Build b = readBuild(f, id);
                Build prev = builds.putIfAbsent(b.id, b);
                if (prev != null) {
                    log.warning("Duplicate build id '" + b.id + "' in " + f + "; keeping the already loaded build");
                    skipped++;
                } else {
                    loaded++;
                }
            } catch (Exception e) {
                skipped++;
                log.log(Level.WARNING, "Skipping corrupt build file " + f + ": " + e, e);
            }
        }
        loadPlayers();
        if (loaded > 0 || skipped > 0) {
            log.info("Loaded " + loaded + " build(s) from " + buildsFolder + (skipped > 0 ? ", skipped " + skipped : ""));
        }
    }

    private Build readBuild(File f, String id) throws IOException {
        byte[] raw = Files.readAllBytes(f.toPath());
        try (Reader r = new InputStreamReader(new GZIPInputStream(new ByteArrayInputStream(raw)), StandardCharsets.UTF_8)) {
            JsonElement e = JsonParser.parseReader(r);
            if (e == null || !e.isJsonObject()) throw new IOException("not a JSON object");
            Build b = Build.fromJson(e.getAsJsonObject());
            if (!id.equals(b.id)) {
                log.fine("Build file " + f.getName() + " contains id '" + b.id + "'; using the file name as authority");
                b.id = id;
            }
            return b;
        }
    }

    // ------------------------------------------------------------------ queries

    /** The build with this id, or {@code null}. Lookup is case-insensitive. */
    public Build get(String id) {
        if (id == null) return null;
        String key = normalizeId(id);
        Build b = builds.get(key);
        if (b != null) return b;
        return key.equals(id) ? null : builds.get(id);
    }

    /** All loaded builds (snapshot, unordered). Never {@code null}. */
    public Collection<Build> all() {
        return Collections.unmodifiableCollection(new ArrayList<>(builds.values()));
    }

    /** All builds owned by {@code owner}, newest first. Console builds have no owner and are not returned. */
    public List<Build> byOwner(UUID owner) {
        List<Build> out = new ArrayList<>();
        if (owner == null) return out;
        for (Build b : builds.values()) {
            if (owner.equals(b.owner)) out.add(b);
        }
        out.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
        return out;
    }

    /** Number of loaded builds. */
    public int size() {
        return builds.size();
    }

    /**
     * Finds the build whose current model's rotated world bounding box contains the point.
     * Only builds in {@code world} with a placed model and a non-empty model are considered; when several
     * match, the one whose origin is closest to the point wins (deterministic tie-break on id).
     *
     * @return the containing build or {@code null}
     */
    public Build findAt(String world, int x, int y, int z) {
        if (world == null) return null;
        Build best = null;
        long bestDist = Long.MAX_VALUE;
        for (Build b : builds.values()) {
            if (!b.placed || b.worldName == null || !b.worldName.equals(world)) continue;
            int[] mb = b.modelBounds();
            if (mb == null) continue;
            int[] wb;
            try {
                wb = PlacementMath.worldBounds(b, mb);
            } catch (RuntimeException e) {
                continue; // defensive: never let one bad build break the lookup
            }
            if (x < wb[0] || x > wb[3] || y < wb[1] || y > wb[4] || z < wb[2] || z > wb[5]) continue;
            long dx = (long) x - b.originX, dy = (long) y - b.originY, dz = (long) z - b.originZ;
            long d = dx * dx + dy * dy + dz * dz;
            if (best == null || d < bestDist || (d == bestDist && b.id.compareTo(best.id) < 0)) {
                best = b;
                bestDist = d;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ creation / mutation

    /**
     * Creates a new build with a unique 6 hex character id and registers it in the index. The build is not
     * written to disk until {@link #save(Build)} is called.
     *
     * @param owner     owner UUID or {@code null} for console
     * @param ownerName owner display name (null/blank becomes "console")
     * @param worldName bukkit world name (null/blank becomes "world")
     * @return the new build
     */
    public Build create(UUID owner, String ownerName, String worldName) {
        return create(owner, ownerName, worldName, 64);
    }

    private Build create(UUID owner, String ownerName, String worldName, int attempts) {
        for (int i = 0; i < attempts; i++) {
            String id = Build.randomId();
            if (builds.containsKey(id) || new File(buildsFolder, id + BUILD_SUFFIX).exists()) continue;
            Build b = new Build(owner, ownerName, worldName);
            b.id = id;
            Build prev = builds.putIfAbsent(id, b);
            if (prev == null) return b;
        }
        throw new IllegalStateException("could not allocate a unique build id after " + attempts + " attempts");
    }

    /**
     * Publishes a build into the index (used after loading or for externally constructed builds).
     *
     * @return the previous build with the same id, or {@code null}
     */
    public Build put(Build b) {
        if (b == null || b.id == null) throw new IllegalArgumentException("build/id is null");
        String id = normalizeId(b.id);
        if (!Build.isValidId(id)) throw new IllegalArgumentException("invalid build id: " + b.id);
        b.id = id;
        deleted.remove(id);
        return builds.put(id, b);
    }

    /**
     * Serializes {@code b} on the calling thread and writes the bytes asynchronously.
     *
     * <p>Must be called on the server main thread so that the snapshot of the mutable {@link Build} is
     * consistent. When the store is already shut down the bytes are written synchronously as a last
     * resort (and a warning is logged).</p>
     *
     * @throws IllegalArgumentException when {@code b} or its id is null
     */
    public void save(Build b) {
        if (b == null) throw new IllegalArgumentException("build is null");
        String id = normalizeId(b.id);
        if (!Build.isValidId(id)) throw new IllegalArgumentException("invalid build id: " + b.id);
        b.id = id;
        byte[] bytes;
        try {
            bytes = serialize(b);
        } catch (Exception e) {
            log.log(Level.SEVERE, "Failed to serialize build " + id, e);
            throw new IllegalStateException("failed to serialize build " + id, e);
        }
        // Registering before the write keeps the index consistent even if the write fails.
        builds.put(id, b);
        deleted.remove(id);
        pending.put(id, bytes);
        if (shuttingDown) {
            log.warning("BuildStore.save() called after shutdown; writing build " + id + " synchronously");
            writeNow(id, bytes);
            return;
        }
        submitDrain();
    }

    /**
     * Removes a build from the index and deletes its file. The in-memory removal is immediate; the file
     * deletion happens on the writer thread.
     *
     * @return {@code true} when the build was known
     */
    public boolean delete(String id) {
        if (id == null) return false;
        String key = normalizeId(id);
        Build removed = builds.remove(key);
        pending.remove(key);
        deleted.add(key);
        for (Map.Entry<UUID, String> e : active.entrySet()) {
            if (key.equals(e.getValue())) active.remove(e.getKey(), key);
        }
        Runnable del = () -> {
            Path p = new File(buildsFolder, key + BUILD_SUFFIX).toPath();
            try {
                Files.deleteIfExists(p);
            } catch (IOException e) {
                log.log(Level.WARNING, "Could not delete build file " + p, e);
            }
        };
        if (shuttingDown) {
            del.run();
        } else {
            try {
                writer.execute(del);
            } catch (RejectedExecutionException e) {
                del.run();
            }
        }
        return removed != null;
    }

    // ------------------------------------------------------------------ active build per player

    /** The id of the player's active build, or {@code null}. */
    public String getActive(UUID player) {
        return player == null ? null : active.get(player);
    }

    /**
     * Sets (or clears, with {@code null}) the player's active build and persists {@code players.json}
     * synchronously (the file is tiny).
     */
    public void setActive(UUID player, String buildId) {
        if (player == null) return;
        if (buildId == null) {
            active.remove(player);
        } else {
            active.put(player, normalizeId(buildId));
        }
        savePlayers();
    }

    /** A snapshot of the active-build map (player → build id). */
    public Map<UUID, String> activeMap() {
        return Collections.unmodifiableMap(new java.util.LinkedHashMap<>(active));
    }

    private void loadPlayers() {
        if (!playersFile.isFile()) return;
        try (Reader r = Files.newBufferedReader(playersFile.toPath(), StandardCharsets.UTF_8)) {
            JsonElement e = JsonParser.parseReader(r);
            if (e == null || !e.isJsonObject()) return;
            JsonObject o = e.getAsJsonObject();
            JsonElement players = o.get("players");
            if (players == null || !players.isJsonObject()) return;
            for (Map.Entry<String, JsonElement> en : players.getAsJsonObject().entrySet()) {
                try {
                    UUID u = UUID.fromString(en.getKey());
                    String id = en.getValue().getAsString();
                    if (id != null && !id.isBlank()) active.put(u, normalizeId(id));
                } catch (RuntimeException ignored) {
                    // skip malformed entry
                }
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "Could not read " + playersFile + " (ignoring): " + e.getMessage(), e);
        }
    }

    private void savePlayers() {
        JsonObject players = new JsonObject();
        for (Map.Entry<UUID, String> e : active.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) players.addProperty(e.getKey().toString(), e.getValue());
        }
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.add("players", players);
        byte[] bytes = GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
        synchronized (playersLock) {
            try {
                ensureDir(dataFolder);
                writeAtomically(playersFile.toPath(), bytes);
            } catch (Exception e) {
                log.log(Level.WARNING, "Could not write " + playersFile, e);
            }
        }
    }

    // ------------------------------------------------------------------ shutdown

    /**
     * Stops the writer thread, waiting up to 10 seconds for pending build writes to complete.
     * Calls after the first are ignored. The in-memory index stays readable.
     */
    public void shutdown() {
        if (shuttingDown) return;
        shuttingDown = true;
        drain(); // best-effort inline flush of everything still queued
        writer.shutdown();
        try {
            if (!writer.awaitTermination(SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                log.warning("BuildStore: pending writes did not finish within " + SHUTDOWN_TIMEOUT_MS + " ms; "
                        + pending.size() + " snapshot(s) may be lost");
                writer.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writer.shutdownNow();
            log.warning("BuildStore: interrupted while flushing pending writes");
        }
    }

    // ------------------------------------------------------------------ writer

    private void submitDrain() {
        try {
            writer.execute(this::drain);
        } catch (RejectedExecutionException e) {
            // already shut down: nothing sensible left to do, the bytes stay in `pending`
            log.fine("BuildStore writer rejected a task (store shut down?)");
        }
    }

    /** Writes every queued snapshot; runs on the writer thread (or inline during shutdown). */
    private void drain() {
        for (Map.Entry<String, byte[]> e : snapshotPending().entrySet()) {
            String id = e.getKey();
            byte[] bytes = e.getValue();
            if (deleted.contains(id)) {
                pending.remove(id, bytes);
                continue;
            }
            if (pending.remove(id, bytes)) {
                writeNow(id, bytes);
            }
        }
    }

    private Map<String, byte[]> snapshotPending() {
        if (pending.isEmpty()) return Collections.emptyMap();
        return new java.util.LinkedHashMap<>(pending);
    }

    private void writeNow(String id, byte[] bytes) {
        ensureDir(buildsFolder);
        Path target = new File(buildsFolder, id + BUILD_SUFFIX).toPath();
        try {
            writeAtomically(target, bytes);
        } catch (Exception e) {
            log.log(Level.SEVERE, "Failed to write build " + id + " to " + target, e);
        }
    }

    private static void writeAtomically(Path target, byte[] bytes) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, target.getFileName().toString(), ".tmp");
        try {
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    private byte[] serialize(Build b) throws IOException {
        JsonObject json = b.toJson();
        String text = GSON.toJson(json);
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(512, text.length() / 2));
        try (Writer w = new OutputStreamWriter(new GZIPOutputStream(bos), StandardCharsets.UTF_8)) {
            w.write(text);
        }
        return bos.toByteArray();
    }

    // ------------------------------------------------------------------ helpers

    private static String normalizeId(String id) {
        if (id == null) return null;
        String s = id.trim().toLowerCase(java.util.Locale.ROOT);
        if (s.endsWith(BUILD_SUFFIX)) s = s.substring(0, s.length() - BUILD_SUFFIX.length());
        return s;
    }

    private static void ensureDir(File dir) {
        if (dir == null) return;
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            Logger.getLogger(BuildStore.class.getName()).warning("Could not create directory " + dir);
        }
    }

    @Override
    public String toString() {
        return "BuildStore{" + buildsFolder + ", builds=" + builds.size() + ", pending=" + pending.size() + '}';
    }
}
