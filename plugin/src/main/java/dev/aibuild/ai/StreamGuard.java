package dev.aibuild.ai;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Watchdog around a streaming HTTP response body.
 *
 * <p>A single daemon timer thread polls every {@value #POLL_MS} ms and <b>closes the body
 * stream</b> when the caller raises its cancellation flag or when no data arrived within the idle
 * timeout. Closing the stream is what unblocks a {@code readLine()} that is stuck waiting for the
 * next chunk, which is the only reliable way to abort a blocking read on
 * {@code BodyHandlers.ofInputStream()}. The reader then observes an {@link IOException} (or a
 * {@code null} line at EOF) and calls {@link #checkAborted(Throwable)} to translate the abort into
 * the correct {@link LlmException}.</p>
 *
 * <p>Instances are single-use and confined to one call; {@link #touch()} is called by the reading
 * thread on every line, the timer only writes {@link #abortCode} and closes the stream, so the
 * class is safe for that two-thread pattern without further locking.</p>
 */
final class StreamGuard implements AutoCloseable {

    /** Poll interval of the watchdog. */
    static final long POLL_MS = 100L;

    private static final ScheduledExecutorService TIMER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "AIBuild-LLM-watchdog");
                t.setDaemon(true);
                return t;
            });

    private final InputStream body;
    private final AtomicBoolean cancelled;
    private final long idleNanos;
    private final long idleMillis;
    private final long startedNanos;

    private volatile long lastActivityNanos;
    private volatile String abortCode;
    private volatile boolean closed;
    private ScheduledFuture<?> task;

    private StreamGuard(InputStream body, AtomicBoolean cancelled, Duration idleTimeout) {
        this.body = body;
        this.cancelled = cancelled;
        Duration d = idleTimeout == null || idleTimeout.isNegative() || idleTimeout.isZero()
                ? Duration.ofSeconds(120) : idleTimeout;
        this.idleNanos = d.toNanos();
        this.idleMillis = d.toMillis();
        this.startedNanos = System.nanoTime();
        this.lastActivityNanos = startedNanos;
    }

    /**
     * Starts watching a response body.
     *
     * @param body        the streaming body, must not be {@code null}
     * @param cancelled   caller's cancellation flag, may be {@code null}
     * @param idleTimeout maximum silence before abort; {@code null}/non-positive means 120 s
     * @return the running guard, to be closed in a {@code finally} block
     */
    static StreamGuard start(InputStream body, AtomicBoolean cancelled, Duration idleTimeout) {
        StreamGuard guard = new StreamGuard(body, cancelled, idleTimeout);
        guard.task = TIMER.scheduleWithFixedDelay(guard::poll, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS);
        return guard;
    }

    /** Records that data was received; called by the reading thread for every line. */
    void touch() {
        lastActivityNanos = System.nanoTime();
    }

    /** @return {@code true} once the watchdog decided to abort the stream. */
    boolean aborted() {
        return abortCode != null;
    }

    /** @return the abort reason ({@code cancelled}/{@code idle_timeout}) or {@code null}. */
    String abortCode() {
        return abortCode;
    }

    /** @return milliseconds without data at the time of the last poll. */
    long silentMillis() {
        return (System.nanoTime() - lastActivityNanos) / 1_000_000L;
    }

    private void poll() {
        if (closed) {
            return;
        }
        try {
            if (cancelled != null && cancelled.get()) {
                abort("cancelled");
                return;
            }
            if (System.nanoTime() - lastActivityNanos > idleNanos) {
                abort("idle_timeout");
            }
        } catch (Throwable ignored) {
            // Never let the timer thread die.
        }
    }

    private void abort(String code) {
        if (abortCode == null) {
            abortCode = code;
        }
        closeQuietly();
    }

    private void closeQuietly() {
        try {
            body.close();
        } catch (IOException | RuntimeException ignored) {
            // Best effort: the reader will notice EOF/exception either way.
        }
    }

    /**
     * Translates a pending abort into the matching exception.
     *
     * @param cause the I/O failure observed by the reader, may be {@code null}
     * @throws LlmException always, when an abort is pending
     */
    void checkAborted(Throwable cause) throws LlmException {
        String code = abortCode;
        if (code == null) {
            if (cancelled != null && cancelled.get()) {
                throw LlmException.cancelled();
            }
            return;
        }
        if ("cancelled".equals(code)) {
            throw LlmException.cancelled();
        }
        if ("idle_timeout".equals(code)) {
            throw LlmException.idleTimeout(idleMillis);
        }
        throw LlmException.network("stream aborted (" + code + ") after " + silentMillis() + " ms", cause);
    }

    /** @return elapsed milliseconds since the guard started (i.e. since the response headers arrived). */
    long elapsedMillis() {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    @Override
    public void close() {
        closed = true;
        ScheduledFuture<?> t = task;
        if (t != null) {
            t.cancel(false);
        }
    }
}
