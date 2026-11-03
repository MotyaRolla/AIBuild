package dev.aibuild.pipeline;

import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** A running AI job (create / edit). Fields are written by the worker thread and read by the UI task. */
public final class Job {
    public enum Kind {CREATE, EDIT, BENCH}

    public final String buildId;
    public final UUID owner; // null = console
    public final Kind kind;
    public final String request;
    public final long startedAt = System.currentTimeMillis();
    public final AtomicBoolean cancelled;
    /** Number of LLM calls made by this job. */
    public final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
    public final java.util.concurrent.atomic.AtomicInteger callsWithoutUsage = new java.util.concurrent.atomic.AtomicInteger();
    /** Quiet jobs (benchmarks) do not send chat progress messages. */
    public volatile boolean quiet;
    public final AtomicLong promptTokens = new AtomicLong();
    public final AtomicLong completionTokens = new AtomicLong();
    public final AtomicLong cachedTokens = new AtomicLong();
    public final AtomicLong reasoningTokens = new AtomicLong();
    /** Thinking mode of this job. */
    public final dev.aibuild.config.BuildMode mode;

    public volatile String status = "Ожидание...";
    public volatile double progress; // 0..1
    public volatile int streamChars;
    public volatile int reasoningChars;
    public volatile Future<?> future;
    public volatile boolean finished;
    /** Allowed build box for ops (plot + margin), null = unlimited. */
    public volatile int[] plotLimits;
    /** Recent automatic orientation corrections (guarded by this). */
    public final java.util.List<String> autoNotes = new java.util.ArrayList<>();
    public final java.util.List<dev.aibuild.model.ops.Doorway> doorways = new java.util.ArrayList<>();
    /** Build script of the construction (script strategy), its seed and the cells its smoother oriented. */
    public volatile String script;
    public volatile long scriptSeed;
    public volatile String scriptParts = "";
    public volatile java.util.Set<Long> smoothed = java.util.Set.of();
    /** The model exactly as the script produced it (after post-processing), to tell whether review changed it. */
    public volatile dev.aibuild.model.VoxelModel scriptModel;
    /** The build was positioned by the streaming preview (its ground height predates any preview blocks). */
    public volatile boolean liveAnchored;

    public Job(String buildId, UUID owner, Kind kind, String request, dev.aibuild.config.BuildMode mode) {
        this(buildId, owner, kind, request, mode, new AtomicBoolean());
    }

    public Job(String buildId, UUID owner, Kind kind, String request, dev.aibuild.config.BuildMode mode, AtomicBoolean cancelled) {
        this.cancelled = cancelled;
        this.mode = mode;
        this.buildId = buildId;
        this.owner = owner;
        this.kind = kind;
        this.request = request;
    }

    /** A quiet sub-job (benchmark variant) sharing the cancel flag and limits, with its own token accounting. */
    public Job child(String suffix) {
        Job c = new Job(buildId + "/" + suffix, null, kind, request, mode, cancelled);
        c.quiet = true;
        c.plotLimits = plotLimits == null ? null : plotLimits.clone();
        return c;
    }

    public void status(String s, double p) {
        status = s;
        progress = Math.max(0, Math.min(1, p));
        streamChars = 0;
        reasoningChars = 0;
    }

    public long elapsedSeconds() {
        return (System.currentTimeMillis() - startedAt) / 1000;
    }
}
