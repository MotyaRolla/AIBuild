package dev.aibuild.world;

import dev.aibuild.build.Build;
import dev.aibuild.model.BlockSpec;
import dev.aibuild.model.VoxelModel;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Rail;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Door;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Applies a {@link VoxelModel} of a {@link Build} to the world as an incremental diff, spread over server
 * ticks, and records the original terrain for undo.
 *
 * <h2>What a placement does</h2>
 * <ol>
 *     <li><b>Target</b>: every set model cell is placed at
 *     {@code world = origin + rotate(x, z, rotation)} with rotation applied to the block data as well.</li>
 *     <li><b>Site clearing</b>: every unset cell inside the model bounds with {@code y >= 1} becomes air
 *     (only cells that are currently non-air are touched).</li>
 *     <li><b>Foundation</b>: model columns whose lowest solid cell reaches the ground layer get a support
 *     column below it while the original world block is air/replaceable, at most
 *     {@code maxFoundationDepth} blocks deep.</li>
 *     <li><b>Restore</b>: every terrain cell the build modified earlier but that is no longer part of the
 *     target is restored to its recorded original state. With {@code model == null} (build removal) the
 *     whole terrain backup is restored.</li>
 *     <li><b>Apply</b>: non-fragile blocks first (sorted by y ascending), fragile blocks (doors, beds,
 *     plants, torches, rails, redstone, …) last. Cells already in the desired state are skipped. The
 *     original state of every cell that is about to change is captured into {@link Build#terrain}.</li>
 * </ol>
 *
 * <h2>Threading</h2>
 * {@link #place} may be called from any thread. All world access, all mutation of the {@link Build} and
 * every {@link IntConsumer} callback happen on the server main thread. Placements of the same build are
 * serialized: a second {@code place()} waits for the first to finish before it starts. The class itself is
 * thread-safe (concurrent maps + atomics).
 */
public final class WorldPlacer {

    /**
     * Creates {@link BlockData} from a block-state string. Production uses
     * {@link Bukkit#createBlockData(String)}; the seam exists so the placement logic can be exercised
     * without a running server and so a platform could substitute its own registry.
     */
    @FunctionalInterface
    public interface BlockDataFactory {
        /**
         * @param state a block state string such as {@code "minecraft:oak_stairs[facing=east]"}
         * @return the block data (a fresh instance)
         * @throws IllegalArgumentException when the state is unknown to the registry
         */
        BlockData create(String state) throws IllegalArgumentException;
    }

    /**
     * Runs the placement on the server main thread and provides a repeating tick. Production uses the
     * Bukkit scheduler ({@link BukkitTickScheduler}); the seam keeps {@link WorldPlacer} testable without
     * starting a server.
     */
    public interface TickScheduler {
        /**
         * Schedules {@code task} to run on the main thread after {@code delayTicks}, then every
         * {@code periodTicks}.
         *
         * @return an opaque handle passed to {@link #cancel(Object)}
         */
        Object scheduleRepeating(Runnable task, long delayTicks, long periodTicks);

        /** Cancels a handle returned by {@link #scheduleRepeating}; unknown/already-cancelled handles are ignored. */
        void cancel(Object handle);

        /** Runs {@code task} on the main thread as soon as possible. */
        void runOnMainThread(Runnable task);
    }

    /** Default {@link TickScheduler}: the Bukkit scheduler of the owning plugin. */
    private final class BukkitTickScheduler implements TickScheduler {
        @Override
        public Object scheduleRepeating(Runnable task, long delayTicks, long periodTicks) {
            return Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
        }

        @Override
        public void cancel(Object handle) {
            if (handle instanceof BukkitTask t) t.cancel();
        }

        @Override
        public void runOnMainThread(Runnable task) {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /** Columns capped by one of these get a dirt foundation; everything else gets the configured block. */
    private static final Set<String> DIRT_CAPPED = Set.of(
            "grass_block", "dirt", "dirt_path", "coarse_dirt", "podzol", "farmland", "rooted_dirt", "mud");
    /** Soil-ish blocks accepted as a valid full-block cap even though {@code isOccluding} may say no. */
    private static final Set<String> SOIL_CAPS = Set.of(
            "dirt_path", "farmland", "grass_block", "dirt", "coarse_dirt", "podzol", "rooted_dirt", "mud",
            "mycelium", "soul_soil", "moss_block");

    private final Plugin plugin;
    private final int blocksPerTick;
    private final String foundationBlock;
    private final int maxFoundationDepth;
    private final Logger log;
    private final TickScheduler scheduler;
    private final BlockDataFactory dataFactory;

    /** Currently running placement per build id. */
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    /** Number of place() calls queued or running per build id (isBusy, cancellation of queued work). */
    private final Map<String, java.util.concurrent.atomic.AtomicInteger> queued = new ConcurrentHashMap<>();
    /** Builds whose queued placement must be aborted as soon as it starts. */
    private final Set<String> cancelRequestedIds = ConcurrentHashMap.newKeySet();
    /** Tail of the per-build placement chain (completed when the job finishes). */
    private final Map<String, CompletableFuture<Void>> chains = new java.util.HashMap<>();
    private final Object chainLock = new Object();

    /** Canonical block string → immutable template data (always {@link BlockData#clone() cloned} before use). */
    private final Map<String, BlockData> dataCache = new ConcurrentHashMap<>();
    private final Set<String> loggedBadBlocks = ConcurrentHashMap.newKeySet();

    private volatile boolean shutdown;

    /**
     * @param plugin            owning plugin (scheduler handle); must not be null
     * @param blocksPerTick     maximum number of block changes per server tick (values below 1 are raised
     *                          to 1; a sane production value is ~2000–8000)
     * @param foundationBlock   block used for foundations under non-dirt columns, e.g. {@code "cobblestone"}
     *                          (invalid values fall back to stone)
     * @param maxFoundationDepth maximum number of support blocks placed below a column
     */
    public WorldPlacer(Plugin plugin, int blocksPerTick, String foundationBlock, int maxFoundationDepth) {
        this(plugin, blocksPerTick, foundationBlock, maxFoundationDepth, null, null);
    }

    /**
     * Full constructor with the scheduling and block-data seams.
     *
     * @param plugin             owning plugin (scheduler handle and logger); must not be null
     * @param blocksPerTick      maximum block changes per tick (at least 1)
     * @param foundationBlock    foundation material name (null/blank becomes {@code "cobblestone"})
     * @param maxFoundationDepth maximum support depth below a column (0 disables foundations)
     * @param scheduler          tick scheduler, or {@code null} for the Bukkit scheduler
     * @param dataFactory        block-data factory, or {@code null} for {@link Bukkit#createBlockData(String)}
     */
    public WorldPlacer(Plugin plugin, int blocksPerTick, String foundationBlock, int maxFoundationDepth,
                       TickScheduler scheduler, BlockDataFactory dataFactory) {
        if (plugin == null) throw new IllegalArgumentException("plugin is null");
        this.plugin = plugin;
        this.log = plugin.getLogger() != null ? plugin.getLogger() : Logger.getLogger(WorldPlacer.class.getName());
        this.blocksPerTick = Math.max(1, blocksPerTick);
        this.foundationBlock = foundationBlock == null || foundationBlock.isBlank()
                ? "cobblestone" : foundationBlock.trim();
        this.maxFoundationDepth = Math.max(0, maxFoundationDepth);
        this.scheduler = scheduler != null ? scheduler : new BukkitTickScheduler();
        this.dataFactory = dataFactory != null ? dataFactory : Bukkit::createBlockData;
    }

    /** Maximum block changes per tick. */
    public int blocksPerTick() {
        return blocksPerTick;
    }

    /** True while a placement for this build is queued or running. */
    public boolean isBusy(String buildId) {
        if (buildId == null) return false;
        String id = normalizeId(buildId);
        if (jobs.containsKey(id)) return true;
        var n = queued.get(id);
        return n != null && n.get() > 0;
    }

    /**
     * Requests cancellation of the placement for this build: a running job stops at the next tick and the
     * returned future completes with {@code cancelled = true}; a queued job is dropped when it starts.
     */
    public void cancel(String buildId) {
        if (buildId == null) return;
        String id = normalizeId(buildId);
        Job j = jobs.get(id);
        if (j != null) {
            j.cancelRequested.set(true);
            return;
        }
        var n = queued.get(id);
        if (n != null && n.get() > 0) cancelRequestedIds.add(id);
    }

    /** Cancels every queued or running placement (used on plugin disable). */
    public void cancelAll() {
        for (Map.Entry<String, Job> e : jobs.entrySet()) {
            e.getValue().cancelRequested.set(true);
            cancelRequestedIds.add(e.getKey());
        }
        for (String id : queued.keySet()) cancelRequestedIds.add(id);
    }

    /**
     * Places {@code modelOrNull} for {@code build}. A {@code null} model restores the build's original
     * terrain (removing the build).
     *
     * @param build           the build to place; its origin/rotation/worldName are used
     * @param modelOrNull     the model to place, or {@code null} to restore terrain only
     * @param progressPercent optional progress callback (0..100), invoked on the main thread every tick;
     *                        may be {@code null}
     * @return a future completed on the main thread with the result; completed exceptionally on error
     *         (unknown world, plugin disabled, internal failure)
     * @throws IllegalArgumentException when {@code build} is null (the returned future is already failed)
     */
    public CompletableFuture<PlaceResult> place(Build build, VoxelModel modelOrNull, IntConsumer progressPercent) {
        CompletableFuture<PlaceResult> result = new CompletableFuture<>();
        if (build == null || build.id == null) {
            result.completeExceptionally(new IllegalArgumentException("build/id is null"));
            return result;
        }
        if (shutdown) {
            result.completeExceptionally(new IllegalStateException("WorldPlacer is shut down"));
            return result;
        }
        String id = normalizeId(build.id);
        queued.computeIfAbsent(id, k -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
        CompletableFuture<Void> previous;
        CompletableFuture<Void> tail = new CompletableFuture<>();
        synchronized (chainLock) {
            previous = chains.getOrDefault(id, CompletableFuture.completedFuture(null));
            chains.put(id, tail);
        }
        // `tail` completes when this job is finished (success, error or cancellation) so the next
        // place() for the same build starts afterwards.
        previous.whenComplete((ignored, err) -> {
            try {
                scheduleStart(build, modelOrNull, progressPercent, result, tail, id);
            } catch (Throwable t) {
                // never leave the per-build chain (or the caller's future) hanging
                releaseQueued(id);
                fail(result, tail, t, "could not schedule placement");
            }
        });
        return result;
    }

    /**
     * Looks a world up by name through the owning plugin's server, falling back to {@link Bukkit#getWorld}
     * when the plugin has no server reference yet (which only happens outside a running server).
     */
    private World resolveWorld(String name) {
        if (name == null || name.isBlank()) return null;
        try {
            org.bukkit.Server server = plugin.getServer();
            if (server != null) {
                World w = server.getWorld(name);
                if (w != null) return w;
            }
        } catch (Throwable ignored) {
            // fall through to the global lookup
        }
        try {
            return Bukkit.getWorld(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Stops all placements and rejects new ones. Does not block. */
    public void shutdown() {
        shutdown = true;
        cancelAll();
    }

    // ------------------------------------------------------------------ job start

    private void scheduleStart(Build build, VoxelModel model, IntConsumer progress,
                               CompletableFuture<PlaceResult> result, CompletableFuture<Void> tail, String id) {
        try {
            if (!plugin.isEnabled()) {
                releaseQueued(id);
                fail(result, tail, new IllegalStateException("plugin is disabled"), null);
                return;
            }
            scheduler.runOnMainThread(() -> {
                if (cancelRequestedIds.remove(id)) {
                    releaseQueued(id);
                    finishCancelled(result, tail);
                    return;
                }
                startJob(build, model, progress, result, tail);
            });
        } catch (Throwable t) {
            releaseQueued(id);
            fail(result, tail, t, "could not schedule placement");
        }
    }

    private void releaseQueued(String id) {
        var n = queued.get(id);
        if (n == null) return;
        if (n.updateAndGet(v -> Math.max(0, v - 1)) == 0) queued.remove(id, n);
    }

    private void finishCancelled(CompletableFuture<PlaceResult> result, CompletableFuture<Void> tail) {
        result.complete(new PlaceResult(0, 0, 0L, true));
        tail.complete(null);
    }

    private void startJob(Build build, VoxelModel model, IntConsumer progress,
                          CompletableFuture<PlaceResult> result, CompletableFuture<Void> tail) {
        String id = normalizeId(build.id);
        Job job = null;
        try {
            if (shutdown) throw new IllegalStateException("WorldPlacer is shut down");
            World world = resolveWorld(build.worldName);
            if (world == null) {
                throw new IllegalStateException("world '" + build.worldName + "' is not loaded");
            }
            job = new Job(build, model, progress, result, tail, world);
            jobs.put(job.key, job);
            job.begin();
        } catch (Throwable t) {
            // A partially started job owns the queue slot only if its tick task was scheduled, in which
            // case cancel the task here; the slot is released exactly once either way.
            if (job != null) {
                jobs.remove(job.key, job);
                job.cancelTaskOnly();
                job.phase = Phase.DONE;
            }
            releaseQueued(id);
            fail(result, tail, t, "could not start placement for build " + build.id);
        }
    }

    private static void fail(CompletableFuture<PlaceResult> result, CompletableFuture<Void> tail,
                             Throwable error, String message) {
        if (message != null) {
            result.completeExceptionally(new IllegalStateException(message + ": " + error.getMessage(), error));
        } else {
            result.completeExceptionally(error);
        }
        tail.complete(null);
    }

    // ------------------------------------------------------------------ the job

    private enum Phase {
        LOAD_CHUNKS, TARGET_CELLS, TARGET_CLEAR, FOUNDATION, RESTORE, ORDER, APPLY, DONE
    }

    /**
     * One placement run. All fields are only touched from the main thread (plus the cancellation flag,
     * which is atomic). The tick budget is expressed in "work units"; a work unit is roughly one block
     * read/write, so a tick never costs more than ~{@code blocksPerTick} block operations.
     */
    private final class Job {
        final Build build;
        final VoxelModel model;
        final IntConsumer progress;
        final CompletableFuture<PlaceResult> result;
        final CompletableFuture<Void> tail;
        final World world;
        final String key;
        final AtomicBoolean cancelRequested = new AtomicBoolean();
        final long startNanos = System.nanoTime();

        final int rotation;
        final int originX, originY, originZ;
        final int minY, maxY;           // world height bounds (inclusive / exclusive)
        final int[] box;                // normalized model bounds, or null when no model
        final Set<String> reportedProgressErrors = ConcurrentHashMap.newKeySet();

        Phase phase = Phase.LOAD_CHUNKS;
        Object task;
        /** Set once the job completed or aborted: a stray tick must never touch the world again. */
        boolean finished;

        // chunk loading
        List<int[]> chunks = Collections.emptyList();
        int chunkCursor;

        // target cells
        Iterator<Map.Entry<Long, BlockSpec>> cellIt;
        int cellIndex;

        // clear / foundation column iteration over model bounds
        int colX, colZ, clearY;
        boolean columnsDone;
        // resumable foundation column (survives tick boundaries)
        int pendingX, pendingZ, pendingY, pendingDepth;
        BlockData pendingSupport;

        // restore
        Iterator<Map.Entry<Long, String>> terrainIt;

        // orders
        List<Long> solidQueue = Collections.emptyList();
        List<Long> fragileQueue = Collections.emptyList();
        int solidCursor, fragileCursor;
        final Set<Long> applied = new HashSet<>();

        // desired state
        final Map<Long, BlockData> desired = new LinkedHashMap<>();
        /** Keys that come from the terrain backup (restores) rather than from the model. */
        final Set<Long> restoreKeys = new HashSet<>();
        int changed;
        int captured;

        // progress
        long estimatedWork;
        long workDone;
        int lastPercent = -1;

        Job(Build build, VoxelModel model, IntConsumer progress, CompletableFuture<PlaceResult> result,
            CompletableFuture<Void> tail, World world) {
            this.build = build;
            this.key = normalizeId(build.id);
            this.model = model;
            this.progress = progress;
            this.result = result;
            this.tail = tail;
            this.world = world;
            this.rotation = PlacementMath.norm(build.rotation);
            this.originX = build.originX;
            this.originY = build.originY;
            this.originZ = build.originZ;
            this.minY = world.getMinHeight();
            this.maxY = world.getMaxHeight();
            int[] mb = null;
            if (model != null && !model.isEmpty()) {
                mb = model.bounds();
            }
            this.box = mb == null ? null : PlacementMath.normalizeBounds(mb);
        }

        // -------------------------------------------------------------- lifecycle

        void begin() {
            if (box == null) {
                // restore-only run: nothing to clear, no foundation, no target cells
                phase = Phase.RESTORE;
                terrainIt = build.terrain.entrySet().iterator();
                estimatedWork = Math.max(1, build.terrainSize() + 1);
            } else {
                scheduleChunks();
                // Estimated once so the reported percentage never moves backwards.
                estimatedWork = Math.max(1, (long) model.size() + volume(box) + build.terrainSize()
                        + chunks.size() + 4);
            }
            try {
                Object handle = scheduler.scheduleRepeating(this::tick, 1L, 1L);
                task = handle;
            } catch (RuntimeException e) {
                throw new IllegalStateException("could not schedule the placement tick task", e);
            }
        }

        private void scheduleChunks() {
            int[] wb = PlacementMath.worldBounds(originX, originY, originZ, rotation, box);
            int minX = wb[0] - 1, maxX = wb[3] + 1;
            int minZ = wb[2] - 1, maxZ = wb[5] + 1;
            int minWorldY = Math.max(minY, wb[1] - maxFoundationDepth - 1);
            Set<Long> seen = new LinkedHashSet<>();
            List<int[]> list = new ArrayList<>();
            int cx0 = minX >> 4, cx1 = maxX >> 4, cz0 = minZ >> 4, cz1 = maxZ >> 4;
            for (int cx = cx0; cx <= cx1; cx++) {
                for (int cz = cz0; cz <= cz1; cz++) {
                    long k = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
                    if (seen.add(k)) list.add(new int[]{cx, cz});
                }
            }
            chunks = list;
            // one work unit per chunk; generation is expensive but bounded by the model footprint
            if (minWorldY < minY) log.fine("foundation extends below world min height for build " + build.id);
        }

        private void tick() {
            try {
                if (phase == Phase.DONE || finished) return;
                if (cancelRequested.get() && phase != Phase.DONE) {
                    finish(true);
                    return;
                }
                int budget = blocksPerTick;
                // Every step consumes at least one budget unit; a phase that only transitions still
                // consumes one, so the loop always terminates.
                while (budget > 0 && phase != Phase.DONE) {
                    int used = switch (phase) {
                        case LOAD_CHUNKS -> stepChunks(budget);
                        case TARGET_CELLS -> stepTargetCells(budget);
                        case TARGET_CLEAR -> stepClear(budget);
                        case FOUNDATION -> stepFoundation(budget);
                        case RESTORE -> stepRestore(budget);
                        case ORDER -> stepOrder();
                        case APPLY -> stepApply(budget);
                        default -> budget;
                    };
                    budget -= Math.max(1, used);
                    workDone += Math.max(1, used);
                }
                // The callback is invoked once per tick with the current percentage (the dedupe above only
                // suppresses a repeat when the value did not move, which keeps boss-bar traffic low).
                reportProgress(percent());
            } catch (Throwable t) {
                log.log(Level.SEVERE, "Placement of build " + build.id + " failed", t);
                abort(t);
            }
        }

        /** Cancels the repeating task without touching the result future. */
        void cancelTaskOnly() {
            if (task != null) {
                try {
                    scheduler.cancel(task);
                } catch (RuntimeException ignored) {
                    // already cancelled
                }
                task = null;
            }
        }

        private void finish(boolean cancelled) {
            if (phase == Phase.DONE && finished) return;
            finished = true;
            phase = Phase.DONE;
            cancelTaskOnly();
            jobs.remove(key, this);
            releaseQueued(key);
            if (!cancelled) reportProgress(100);
            long millis = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
            if (!cancelled) {
                build.placed = model != null;
                build.updatedAt = System.currentTimeMillis();
            }
            PlaceResult r = new PlaceResult(changed, captured, millis, cancelled);
            try {
                result.complete(r);
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "Place result consumer threw for build " + build.id, e);
            }
            tail.complete(null);
        }

        private void abort(Throwable t) {
            if (finished) return;
            finished = true;
            phase = Phase.DONE;
            cancelTaskOnly();
            jobs.remove(key, this);
            releaseQueued(key);
            result.completeExceptionally(t);
            tail.complete(null);
        }

        // -------------------------------------------------------------- phases

        private int stepChunks(int budget) {
            int used = 0;
            while (used < budget && chunkCursor < chunks.size()) {
                int[] c = chunks.get(chunkCursor++);
                try {
                    world.getChunkAt(c[0], c[1]);
                } catch (RuntimeException e) {
                    log.log(Level.WARNING, "Could not load chunk " + c[0] + "," + c[1] + " for build " + build.id, e);
                }
                used++;
            }
            if (chunkCursor >= chunks.size()) {
                phase = Phase.TARGET_CELLS;
                cellIt = model.entries().entrySet().iterator();
            }
            return Math.max(1, used);
        }

        private int stepTargetCells(int budget) {
            int used = 0;
            while (used < budget && cellIt.hasNext()) {
                Map.Entry<Long, BlockSpec> e = cellIt.next();
                cellIndex++;
                used++;
                BlockSpec spec = e.getValue();
                if (spec == null) continue;
                long k = e.getKey();
                int wx = VoxelModel.kx(k), wy = VoxelModel.ky(k), wz = VoxelModel.kz(k);
                int[] r = PlacementMath.rotate(wx, wz, rotation);
                int tx = r[0] + originX, ty = wy + originY, tz = r[1] + originZ;
                if (ty < minY || ty >= maxY) continue;
                BlockData data = dataFor(spec, rotation);
                if (data == null) continue;
                long wk = WorldKey.pack(tx, ty, tz);
                desired.put(wk, data);
            }
            if (!cellIt.hasNext()) {
                phase = Phase.TARGET_CLEAR;
                clearY = Math.max(1, box[1]);
                colX = box[0];
                colZ = box[2];
            }
            return Math.max(1, used);
        }

        private int stepClear(int budget) {
            int used = 0;
            int maxClearY = box[4];
            int minClearY = Math.max(1, box[1]);
            while (used < budget && colX <= box[3]) {
                if (clearY > maxClearY) {
                    // column finished: move to the next one (visiting a column counts as work)
                    clearY = minClearY;
                    used++;
                    colZ++;
                    if (colZ > box[5]) {
                        colZ = box[2];
                        colX++;
                    }
                    continue;
                }
                int cy = clearY++;
                used++;
                if (model.isSet(colX, cy, colZ)) continue;
                int[] r = PlacementMath.rotate(colX, colZ, rotation);
                int tx = r[0] + originX, ty = cy + originY, tz = r[1] + originZ;
                if (ty < minY || ty >= maxY) continue;
                long wk = WorldKey.pack(tx, ty, tz);
                if (desired.containsKey(wk)) continue;
                BlockData live;
                try {
                    live = world.getBlockData(tx, ty, tz);
                } catch (RuntimeException e) {
                    log.log(Level.FINE, "Skipping unreadable cell " + tx + "," + ty + "," + tz, e);
                    continue;
                }
                if (live != null && !isAirData(live)) {
                    desired.put(wk, air());
                }
            }
            if (colX > box[3]) {
                phase = Phase.FOUNDATION;
                colX = box[0];
                colZ = box[2];
            }
            return Math.max(1, used);
        }

        private int stepFoundation(int budget) {
            if (maxFoundationDepth <= 0) {
                phase = Phase.RESTORE;
                terrainIt = build.terrain.entrySet().iterator();
                return 1;
            }
            int used = 0;
            while (used < budget) {
                // resume a column that was interrupted by the tick budget
                if (pendingDepth > 0) {
                    int ty = pendingY;
                    long wk = WorldKey.pack(pendingX, ty, pendingZ);
                    if (ty >= minY && !desired.containsKey(wk) && isReplaceableAt(wk, pendingX, ty, pendingZ)) {
                        desired.put(wk, pendingSupport);
                        used++;
                        pendingY--;
                        pendingDepth--;
                        continue;
                    }
                    pendingDepth = 0; // blocked or reached the bottom: column is done
                    continue;
                }
                if (colX > box[3]) {
                    phase = Phase.RESTORE;
                    terrainIt = build.terrain.entrySet().iterator();
                    break;
                }
                int cx = colX, cz = colZ;
                colZ++;
                if (colZ > box[5]) {
                    colZ = box[2];
                    colX++;
                }
                used++;
                // lowest non-air cell of this model column
                int bottomY = Integer.MIN_VALUE;
                BlockSpec bottom = null;
                for (int y = box[1]; y <= box[4]; y++) {
                    BlockSpec sp = model.get(cx, y, cz);
                    if (sp != null && !sp.isAir()) {
                        bottomY = y;
                        bottom = sp;
                        break;
                    }
                }
                // Support is needed whenever the column does not start with natural terrain beneath it:
                // a cap on the ground layer (y = 0) or a column whose lowest cell is exactly y = 1.
                if (bottom == null || bottomY > 1) continue;
                BlockData data = dataFor(bottom, rotation);
                if (data == null || !isFoundationCapable(data, bottom.name())) continue;
                int[] r = PlacementMath.rotate(cx, cz, rotation);
                pendingX = r[0] + originX;
                pendingZ = r[1] + originZ;
                pendingY = originY + bottomY - 1;
                pendingDepth = maxFoundationDepth;
                pendingSupport = dirtCapped(bottom.name()) ? dirt() : foundationData();
            }
            if (colX > box[3] && pendingDepth == 0) {
                phase = Phase.RESTORE;
                terrainIt = build.terrain.entrySet().iterator();
            }
            return Math.max(1, used);
        }

        /** True when the original block at the cell is air/replaceable, so a foundation block fits. */
        private boolean isReplaceableAt(long key, int x, int y, int z) {
            String recorded = build.terrain.get(key);
            if (recorded != null) {
                try {
                    return isReplaceable(dataFactory.create(recorded));
                } catch (RuntimeException e) {
                    return false;
                }
            }
            try {
                BlockData live = world.getBlockData(x, y, z);
                return live != null && isReplaceable(live);
            } catch (RuntimeException e) {
                return false;
            }
        }

        private int stepRestore(int budget) {
            int used = 0;
            while (used < budget && terrainIt != null && terrainIt.hasNext()) {
                Map.Entry<Long, String> e = terrainIt.next();
                used++;
                long k = e.getKey();
                if (desired.containsKey(k)) continue; // target wins
                String state = e.getValue();
                if (state == null) continue;
                BlockData data;
                try {
                    data = dataFactory.create(state);
                } catch (RuntimeException ex) {
                    if (loggedBadBlocks.add(state)) {
                        log.warning("Cannot restore unknown block state '" + state + "' for build " + build.id);
                    }
                    continue;
                }
                desired.put(k, data);
                restoreKeys.add(k);
            }
            if (terrainIt == null || !terrainIt.hasNext()) {
                phase = Phase.ORDER;
            }
            return Math.max(1, used);
        }

        private int stepOrder() {
            List<Map.Entry<Long, BlockData>> solid = new ArrayList<>(desired.size());
            List<Map.Entry<Long, BlockData>> fragile = new ArrayList<>();
            for (Map.Entry<Long, BlockData> e : desired.entrySet()) {
                if (isFragile(e.getValue())) fragile.add(e);
                else solid.add(e);
            }
            Comparator<Map.Entry<Long, BlockData>> byY = Comparator
                    .comparingInt((Map.Entry<Long, BlockData> e) -> WorldKey.y(e.getKey()))
                    .thenComparingLong(Map.Entry::getKey);
            solid.sort(byY);
            fragile.sort(byY);
            solidQueue = new ArrayList<>(solid.size());
            for (Map.Entry<Long, BlockData> e : solid) solidQueue.add(e.getKey());
            fragileQueue = new ArrayList<>(fragile.size());
            for (Map.Entry<Long, BlockData> e : fragile) fragileQueue.add(e.getKey());
            phase = Phase.APPLY;
            return 1;
        }

        private int stepApply(int budget) {
            int used = 0;
            while (used < budget
                    && (solidCursor < solidQueue.size() || fragileCursor < fragileQueue.size())) {
                long key = solidCursor < solidQueue.size()
                        ? solidQueue.get(solidCursor++)
                        : fragileQueue.get(fragileCursor++);
                used++;
                if (!applied.add(key)) continue;
                applyCell(key, true);
            }
            if (solidCursor >= solidQueue.size() && fragileCursor >= fragileQueue.size()) {
                // finish() takes care of phase = DONE, task cancellation and completing the future
                finish(false);
            }
            return Math.max(1, used);
        }

        // -------------------------------------------------------------- apply

        /**
         * Applies one cell. When {@code handlePair} is set and the desired block is a door/bed, the other
         * half of the block is applied in the same tick (required for valid multi-block states).
         */
        private void applyCell(long key, boolean handlePair) {
            BlockData want = desired.get(key);
            if (want == null) return;
            int x = WorldKey.x(key), y = WorldKey.y(key), z = WorldKey.z(key);
            Block block;
            BlockData live;
            try {
                block = world.getBlockAt(x, y, z);
                live = block.getBlockData();
            } catch (RuntimeException e) {
                log.log(Level.FINE, "Skipping unreadable cell " + x + "," + y + "," + z, e);
                return;
            }
            boolean restore = restoreKeys.contains(key);
            if (live != null && matches(live, want)) {
                // Already in the desired state: nothing to change, nothing to capture. For a restore this
                // means the cell matches its original again, so the backup entry can be dropped.
                if (restore) build.terrain.remove(key);
                return;
            }
            // capture the original state before the first modification
            String original = live == null ? null : live.getAsString();
            if (original != null && !build.terrain.containsKey(key)) {
                build.terrain.put(key, original);
                captured++;
            }
            if (live != null && isContainerLike(live)) clearContainer(block);
            try {
                block.setBlockData(want, false);
                changed++;
                if (restore) build.terrain.remove(key); // back to its original: no backup needed any more
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "Could not set block " + x + "," + y + "," + z + " to "
                        + want.getAsString() + " for build " + build.id, e);
                return;
            }
            if (handlePair) {
                applyPair(x, y, z, want);
            }
        }

        /** Applies the partner half of a door or bed within the same tick. */
        private void applyPair(int x, int y, int z, BlockData want) {
            long partner;
            if (want instanceof Door door) {
                boolean top = door.getHalf() == Bisected.Half.TOP;
                partner = WorldKey.pack(x, top ? y - 1 : y + 1, z);
            } else if (want instanceof Bed bed) {
                var dir = bed.getFacing();
                boolean head = bed.getPart() == Bed.Part.HEAD;
                int mx = dir.getModX(), mz = dir.getModZ();
                // the head is one block in the facing direction from the foot
                int px = head ? x - mx : x + mx;
                int pz = head ? z - mz : z + mz;
                if (mx == 0 && mz == 0) return;
                partner = WorldKey.pack(px, y, pz);
            } else {
                return;
            }
            if (!desired.containsKey(partner)) return;
            if (!applied.add(partner)) return;
            BlockData other = desired.get(partner);
            if (other == null) return;
            int px = WorldKey.x(partner), py = WorldKey.y(partner), pz = WorldKey.z(partner);
            Block pb;
            try {
                pb = world.getBlockAt(px, py, pz);
            } catch (RuntimeException e) {
                return;
            }
            if (matches(pb.getBlockData(), other)) return;
            String orig = pb.getBlockData().getAsString();
            if (orig != null && !build.terrain.containsKey(partner)) {
                build.terrain.put(partner, orig);
                captured++;
            }
            if (isContainerLike(pb.getBlockData())) clearContainer(pb);
            try {
                pb.setBlockData(other, false);
                changed++;
            } catch (RuntimeException e) {
                log.log(Level.FINE, "Could not set pair half at " + px + "," + py + "," + pz, e);
            }
        }

        /** Cheap pre-check: only blocks that can hold items get a (comparatively costly) BlockState. */
        private boolean isContainerLike(BlockData data) {
            if (data == null) return false;
            String n;
            try {
                n = data.getMaterial().name();
            } catch (Throwable ignored) {
                n = stateName(data).toUpperCase(Locale.ROOT);
            }
            for (String part : CONTAINER_NAME_PARTS) {
                if (n.contains(part)) return true;
            }
            return false;
        }

        /** Clears a container's inventory before it is replaced so nothing can drop. */
        private void clearContainer(Block block) {
            try {
                if (block.getState() instanceof Container c) {
                    c.getInventory().clear();
                }
            } catch (Throwable ignored) {
                // physics=false prevents drops anyway; never fail the placement for this
            }
        }

        // -------------------------------------------------------------- progress

        private void reportProgress(int percent) {
            if (progress == null) return;
            if (percent == lastPercent) return;
            lastPercent = percent; // still called every tick by tick(); see below
            try {
                progress.accept(Math.max(0, Math.min(100, percent)));
            } catch (Throwable t) {
                if (reportedProgressErrors.add("warned")) {
                    log.log(Level.WARNING, "Progress callback threw for build " + build.id, t);
                }
            }
        }

        private int percent() {
            if (phase == Phase.DONE) return 100;
            long total = Math.max(1, estimatedWork);
            long done = Math.min(workDone, total);
            int pct = (int) Math.min(99L, done * 100L / total);
            return Math.max(lastPercent, pct); // monotonic by construction
        }
    }

    // ------------------------------------------------------------------ block data helpers

    /**
     * Returns a rotated copy of the block data for {@code spec}, or {@code null} when the spec is invalid
     * (logged once per distinct spec, placement continues with the rest).
     */
    private BlockData dataFor(BlockSpec spec, int rotation) {
        if (spec == null) return null;
        if (spec.isAir()) return air();
        String raw = spec.toString();
        String key = rotation == 0 ? raw : raw + '\u0000' + rotation;
        BlockData cached = dataCache.get(key);
        if (cached != null) return cached;
        BlockData base = null;
        try {
            base = baseData(raw);
        } catch (RuntimeException e) {
            if (loggedBadBlocks.add(raw)) {
                log.warning("Unknown block state '" + raw + "' (from build data); using stone instead");
            }
        }
        if (base == null) {
            try {
                base = fallbackStone();
            } catch (RuntimeException e) {
                log.log(Level.SEVERE, "Cannot create fallback stone BlockData", e);
                return null;
            }
        }
        BlockData out = base.clone();
        if (rotation != 0) {
            try {
                out.rotate(PlacementMath.toStructureRotation(rotation));
            } catch (RuntimeException e) {
                log.log(Level.FINE, "Could not rotate block data for '" + raw + "'", e);
            }
        }
        if (dataCache.size() > 8192) dataCache.clear(); // bound memory for pathological models
        dataCache.put(key, out);
        return out;
    }

    private BlockData baseData(String raw) {
        BlockData d = dataCache.get(raw);
        if (d != null) return d;
        d = dataFactory.create(raw);
        dataCache.put(raw, d);
        return d;
    }

    /** Stone, used when a model references an unknown block. */
    private BlockData fallbackStone() {
        BlockData d = stoneData;
        if (d == null) {
            d = dataFactory.create("minecraft:stone");
            stoneData = d;
        }
        return d;
    }

    private volatile BlockData airData;
    private volatile BlockData stoneData;
    private volatile BlockData dirtData;
    private volatile BlockData foundationData;

    private BlockData air() {
        BlockData d = airData;
        if (d == null) {
            synchronized (this) {
                d = airData;
                if (d == null) {
                    d = dataFactory.create("minecraft:air");
                    airData = d;
                }
            }
        }
        return d;
    }

    private BlockData dirt() {
        BlockData d = dirtData;
        if (d == null) {
            synchronized (this) {
                d = dirtData;
                if (d == null) {
                    d = dataFactory.create("minecraft:dirt");
                    dirtData = d;
                }
            }
        }
        return d;
    }

    private BlockData foundationData() {
        BlockData d = foundationData;
        if (d == null) {
            synchronized (this) {
                d = foundationData;
                if (d == null) {
                    try {
                        d = dataFactory.create(foundationBlock);
                    } catch (RuntimeException e) {
                        log.warning("Invalid foundation block '" + foundationBlock + "'; using stone");
                        d = fallbackStone();
                    }
                    foundationData = d;
                }
            }
        }
        return d;
    }

    /**
     * True for block data whose state is already the given one. Uses {@link BlockData#matches(BlockData)}
     * and degrades gracefully when the platform registry is unavailable (offline tooling).
     */
    static boolean matches(BlockData live, BlockData want) {
        if (live == null || want == null) return false;
        try {
            return live.matches(want);
        } catch (Throwable ignored) {
            try {
                return live.getAsString().equals(want.getAsString());
            } catch (Throwable ignored2) {
                return false;
            }
        }
    }

    /** True for air-like block data, without requiring a live block registry. */
    static boolean isAirData(BlockData data) {
        if (data == null) return true;
        try {
            Material m = data.getMaterial();
            if (m == Material.AIR || m == Material.CAVE_AIR || m == Material.VOID_AIR) return true;
            return m.isAir();
        } catch (Throwable ignored) {
            String s;
            try {
                s = data.getAsString();
            } catch (Throwable ignored2) {
                return false;
            }
            return s.equals("minecraft:air") || s.equals("minecraft:cave_air") || s.equals("minecraft:void_air");
        }
    }

    /** True for blocks that can be replaced by a foundation pillar. */
    static boolean isReplaceable(BlockData data) {
        if (data == null) return true;
        if (isAirData(data)) return true;
        Material m = null;
        try {
            m = data.getMaterial();
        } catch (Throwable ignored) {
            // registry unavailable: fall through to the name-based check
        }
        if (m != null) {
            if (m == Material.WATER || m == Material.LAVA || m == Material.BUBBLE_COLUMN) return true;
            if (m == Material.SHORT_GRASS || m == Material.TALL_GRASS || m == Material.FERN
                    || m == Material.LARGE_FERN || m == Material.DEAD_BUSH || m == Material.SNOW) return true;
        }
        try {
            return data.isReplaceable();
        } catch (Throwable ignored) {
            String s;
            try {
                s = data.getAsString();
            } catch (Throwable ignored2) {
                return false;
            }
            return REPLACEABLE_NAMES.stream().anyMatch(s::contains);
        }
    }

    /** Fallback list used only when the platform registry cannot answer {@code isReplaceable()}. */
    private static final List<String> REPLACEABLE_NAMES = List.of(
            ":water", ":lava", ":bubble_column", "short_grass", "tall_grass", ":fern", "large_fern",
            "dead_bush", ":snow", ":vine", "seagrass", "kelp", "lily_pad");

    /** Font: full cube (occluding) or one of the soil-ish blocks the spec accepts as foundation cap. */
    private static boolean isFoundationCapable(BlockData data, String name) {
        if (data == null) return false;
        if (name != null && SOIL_CAPS.contains(name)) return true;
        try {
            return data.isOccluding();
        } catch (Throwable ignored) {
            // registry unavailable: fall back to the model block name
            return name != null && (name.endsWith("_planks") || name.endsWith("_bricks")
                    || name.endsWith("_block") || name.endsWith("stone") || name.endsWith("_log"));
        }
    }

    private static boolean dirtCapped(String name) {
        return name != null && DIRT_CAPPED.contains(name);
    }

    /** Material name fragments identifying blocks with an inventory (item duplication / drops). */
    private static final String[] CONTAINER_NAME_PARTS = {
            "chest", "barrel", "hopper", "furnace", "smoker", "blast_furnace", "dispenser", "dropper",
            "shulker_box", "brewing_stand", "crafter", "decorated_pot", "jukebox", "lectern", "campfire",
            "chiseled_bookshelf", "shelf"
    };

    private static final String[] FRAGILE_NAME_PARTS = {
            "door", "bed", "_sign", "sign_", "hanging_sign", "banner", "torch", "lantern", "candle",
            "chain", "ladder", "rail", "button", "lever", "pressure_plate", "tripwire", "carpet",
            "flower", "sapling", "vine", "grass", "fern", "poppy", "dandelion", "tulip", "orchid",
            "allium", "cornflower", "lily", "oxeye", "peony", "lilac", "rose_bush", "sunflower",
            "pitcher", "torchflower", "azalea", "berry", "lichen", "sculk_vein", "mushroom", "wheat",
            "carrot", "potato", "beetroot", "sugar_cane", "bamboo", "sea_pickle", "coral", "kelp",
            "snow", "cobweb", "cake", "pot", "frame", "painting", "head", "skull", "armor_stand",
            "redstone", "repeater", "comparator", "observer", "piston", "scaffolding", "dripstone",
            "cauldron", "composter", "bell", "brewing", "beacon", "conduit", "hopper", "soul_sand",
            "honey", "slime", "tnt", "cactus", "sweet_berry", "frogspawn", "glow_frame",
            "fence", "pane", "_bars", "wall_torch", "wall_sign", "wall_banner", "wall_head",
            "wall_skull", "wall_hanging", "pressure_plate", "tripwire", "lever", "_button",
            "eye_plant", "amethyst_cluster", "bud", "candle", "sculk_sensor", "turtle_egg"
    };

    /**
     * Heuristic classification of "fragile" blocks: blocks that need a supporting neighbour, pop off when
     * placed before their support, or have a shape that depends on neighbours. They are placed last.
     */
    static boolean isFragile(BlockData data) {
        if (data == null) return false;
        Material m = null;
        try {
            m = data.getMaterial();
        } catch (Throwable ignored) {
            // offline/registry-less environment: the instanceof checks below still work
        }
        if (m != null) {
            try {
                if (m.isAir()) return false;
            } catch (Throwable ignored) {
                // ignore
            }
            if (m == Material.WATER || m == Material.LAVA || m == Material.BUBBLE_COLUMN) return true;
        }
        if (data instanceof Door || data instanceof Bed) return true;
        if (data instanceof Bisected) return true;
        if (data instanceof Rail) return true;
        if (data instanceof org.bukkit.block.data.type.Lantern) return true;
        if (data instanceof org.bukkit.block.data.type.Candle) return true;
        if (data instanceof org.bukkit.block.data.type.Ladder) return true;
        if (data instanceof org.bukkit.block.data.type.TrapDoor) return true;
        if (data instanceof org.bukkit.block.data.type.Sign) return true;
        if (data instanceof org.bukkit.block.data.type.WallSign) return true;
        if (data instanceof org.bukkit.block.data.type.HangingSign) return true;
        if (data instanceof org.bukkit.block.data.type.WallHangingSign) return true;
        if (data instanceof org.bukkit.block.data.type.Switch) return true;   // buttons, levers
        if (data instanceof org.bukkit.block.data.type.Bell) return true;
        if (data instanceof org.bukkit.block.data.type.TripwireHook) return true;
        if (data instanceof org.bukkit.block.data.type.Snow) return true;
        if (data instanceof org.bukkit.block.data.type.FlowerBed) return true;
        if (data instanceof org.bukkit.block.data.type.RedstoneWire) return true;
        if (data instanceof org.bukkit.block.data.type.Repeater) return true;
        if (data instanceof org.bukkit.block.data.type.Comparator) return true;
        if (data instanceof org.bukkit.block.data.type.Speleothem) return true; // dripstone, stalactites
        if (data instanceof org.bukkit.block.data.MultipleFacing) return true; // fences, panes, walls, vines
        String name = m != null ? m.name().toLowerCase(Locale.ROOT) : stateName(data);
        for (String part : FRAGILE_NAME_PARTS) {
            if (name.contains(part)) return true;
        }
        return false;
    }

    /** The block name from the state string, without a registry: {@code "minecraft:oak_door[..]"} → {@code "oak_door"}. */
    private static String stateName(BlockData data) {
        String s;
        try {
            s = data.getAsString();
        } catch (Throwable ignored) {
            return "";
        }
        int br = s.indexOf('[');
        if (br >= 0) s = s.substring(0, br);
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(colon + 1);
        return s.toLowerCase(Locale.ROOT);
    }

    /** Exposed for tests: the fragility classifier is a pure function of the block data. */
    public static boolean fragile(BlockData data) {
        return isFragile(data);
    }

    // ------------------------------------------------------------------ misc

    private static long volume(int[] box) {
        long dx = (long) box[3] - box[0] + 1;
        long dy = (long) box[4] - box[1] + 1;
        long dz = (long) box[5] - box[2] + 1;
        return Math.max(0, dx) * Math.max(0, dy) * Math.max(0, dz);
    }

    private static String normalizeId(String id) {
        return id == null ? null : id.trim().toLowerCase(Locale.ROOT);
    }
}
