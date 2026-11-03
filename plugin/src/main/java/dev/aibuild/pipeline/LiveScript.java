package dev.aibuild.pipeline;

import dev.aibuild.ai.StreamListener;
import dev.aibuild.script.ScriptLint;
import dev.aibuild.script.ScriptResult;
import dev.aibuild.script.ScriptRuntime;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Runs a build script while the model is still streaming it, so the build grows in the world part by part.
 *
 * <p>Each time the answer gains a complete top-level statement ({@link ScriptLint#partial}) the prefix is run
 * with the same seed as the final run, at most one run at a time and at most once per {@link #MIN_GAP_MS} by default;
 * a prefix that arrives while a run is busy is picked up by the next callback. Prefixes that fail (a call to a
 * function defined further down, say) are skipped, and the last good result stays on screen. Costs no tokens:
 * only already-streamed code runs.</p>
 */
final class LiveScript implements StreamListener {
    static final long MIN_GAP_MS = 1500;

    private final ScriptRuntime runtime;
    private final long seed;
    private final int[] limits;
    private final ScriptRuntime.Budget budget;
    private final AtomicBoolean cancel;
    private final ExecutorService exec;
    private final Consumer<ScriptResult> show;
    private final Consumer<String> writing;
    private final long minGapMs;
    private final AtomicBoolean busy = new AtomicBoolean();
    /** Held while a preview is handed to the world, so {@link #close} returns only after it. */
    private final Object showLock = new Object();
    private volatile boolean closed;
    private int lastLength;
    private long lastRun;
    private String lastPart;

    LiveScript(ScriptRuntime runtime, long seed, int[] limits, ScriptRuntime.Budget budget, AtomicBoolean cancel,
               ExecutorService exec, Consumer<ScriptResult> show, Consumer<String> writing) {
        this(runtime, seed, limits, budget, cancel, exec, show, writing, MIN_GAP_MS);
    }

    LiveScript(ScriptRuntime runtime, long seed, int[] limits, ScriptRuntime.Budget budget, AtomicBoolean cancel,
               ExecutorService exec, Consumer<ScriptResult> show, Consumer<String> writing, long minGapMs) {
        this.minGapMs = minGapMs;
        this.runtime = Objects.requireNonNull(runtime);
        this.seed = seed;
        this.limits = limits;
        this.budget = budget;
        this.cancel = cancel;
        this.exec = exec;
        this.show = show;
        this.writing = writing;
    }

    @Override
    public void onProgress(int contentChars, int reasoningChars) {
    }

    @Override
    public synchronized void onText(CharSequence contentSoFar) {
        if (closed) return;
        ScriptLint.Partial p = ScriptLint.partial(contentSoFar);
        if (p.part() != null && !p.part().equals(lastPart)) {
            lastPart = p.part();
            writing.accept(p.part());
        }
        String code = p.code();
        if (code.length() < lastLength) lastLength = 0; // transport retry: the text started over
        long now = System.currentTimeMillis();
        if (code.length() <= lastLength || now - lastRun < minGapMs || !busy.compareAndSet(false, true)) return;
        lastLength = code.length();
        lastRun = now;
        try {
            exec.execute(() -> {
                try {
                    if (closed || (cancel != null && cancel.get())) return;
                    ScriptResult r = runtime.run(code, seed, null, limits, budget, cancel);
                    if (!r.ok() || r.model().nonAirCount() == 0) return;
                    synchronized (showLock) {
                        if (!closed) show.accept(r);
                    }
                } catch (RuntimeException ignored) {
                    // a preview must never disturb the real run
                } finally {
                    busy.set(false);
                }
            });
        } catch (RuntimeException e) {
            busy.set(false);
        }
    }

    /**
     * Stops further previews. Returns after any preview already being shown was handed to the placer, so a
     * placement issued afterwards (the real result) always lands last.
     */
    void close() {
        synchronized (showLock) {
            closed = true;
        }
    }
}
