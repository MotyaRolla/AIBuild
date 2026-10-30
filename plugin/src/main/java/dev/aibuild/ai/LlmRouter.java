package dev.aibuild.ai;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link LlmClient} that hides the two Command&nbsp;Code transports behind one resilient call.
 *
 * <p>Responsibilities:</p>
 * <ol>
 *   <li><b>Transport selection.</b> In {@code "auto"} mode the OpenAI-compatible provider endpoint
 *       is tried first; a failure that indicates the account/plan has no API access
 *       (HTTP&nbsp;403, {@code upgrade_required}, or a message about missing API access) switches the
 *       instance <i>permanently</i> to the {@code /alpha/generate} transport and retries immediately.
 *       {@code "provider"} and {@code "generate"} pin a single transport and disable switching.</li>
 *   <li><b>Retries.</b> Retryable {@link LlmException}s (network, idle timeout, 429, 5xx) and
 *       empty-content answers are retried up to {@code maxRetries} times with backoff
 *       {@code 2 s, 5 s, 12 s, 30 s, 30 s…}. A raised cancellation flag aborts the backoff sleep
 *       immediately with a {@code cancelled} exception.</li>
 *   <li><b>Observability.</b> Every retry is logged at {@code WARNING} with a one-line reason; the
 *       returned {@link ChatResult#transport()} names the transport that produced the answer.</li>
 * </ol>
 *
 * <p><b>Thread-safety:</b> the instance is safe for concurrent use. The sticky transport switch is a
 * {@code volatile} flag (a racing duplicate switch is harmless and idempotent); retry counters and
 * backoff state are per-call locals.</p>
 */
public class LlmRouter implements LlmClient {

    /** Transport name of the OpenAI-compatible provider endpoint. */
    public static final String TRANSPORT_PROVIDER = "provider";
    /** Transport name of the CLI-compatible generate endpoint. */
    public static final String TRANSPORT_GENERATE = "generate";
    /** Automatic transport selection with fallback. */
    public static final String TRANSPORT_AUTO = "auto";

    /** Backoff ladder in milliseconds; the last value is reused for all further retries. */
    private static final long[] BACKOFF_MS = {2_000L, 5_000L, 12_000L, 30_000L};
    /** Maximum accepted retry count; guards against accidental very long stalls. */
    private static final int MAX_RETRIES_LIMIT = 10;

    private final LlmClient provider;
    private final LlmClient generate;
    private final String transport;
    private final int maxRetries;
    private final Logger log;
    private final AtomicBoolean stickyGenerate = new AtomicBoolean(false);

    /**
     * Creates a router.
     *
     * @param provider   transport for the OpenAI-compatible provider endpoint, may be {@code null}
     *                   when {@code transport} is {@code "generate"}
     * @param generate   transport for the {@code /alpha/generate} endpoint, may be {@code null} when
     *                   {@code transport} is {@code "provider"}
     * @param transport  {@code "auto"} (default for anything unrecognised), {@code "provider"} or
     *                   {@code "generate"}
     * @param maxRetries number of retries after the first attempt, clamped to {@code [0, 10]}
     * @param log        logger for retry/fallback warnings; {@code null} disables logging
     * @throws IllegalArgumentException if the transport selected by {@code transport} is missing
     */
    public LlmRouter(LlmClient provider, LlmClient generate, String transport, int maxRetries, Logger log) {
        this.provider = provider;
        this.generate = generate;
        this.transport = normaliseTransport(transport);
        this.maxRetries = Math.clamp(maxRetries, 0, MAX_RETRIES_LIMIT);
        this.log = log == null ? Logger.getLogger(LlmRouter.class.getName()) : log;
        switch (this.transport) {
            case TRANSPORT_PROVIDER -> requireNonNull(provider, "provider transport is required for transport=provider");
            case TRANSPORT_GENERATE -> requireNonNull(generate, "generate transport is required for transport=generate");
            default -> {
                if (provider == null && generate == null) {
                    throw new IllegalArgumentException("at least one transport must be provided");
                }
            }
        }
        if (TRANSPORT_GENERATE.equals(this.transport) || provider == null) {
            stickyGenerate.set(true);
        }
    }

    /**
     * Creates a router with the production defaults: {@code auto} transport, 2 retries and the
     * given logger.
     *
     * @param provider provider transport, may be {@code null}
     * @param generate generate transport, may be {@code null}
     * @param log      logger, may be {@code null}
     */
    public LlmRouter(LlmClient provider, LlmClient generate, Logger log) {
        this(provider, generate, TRANSPORT_AUTO, 2, log);
    }

    private static LlmClient requireNonNull(LlmClient c, String message) {
        if (c == null) {
            throw new IllegalArgumentException(message);
        }
        return c;
    }

    private static String normaliseTransport(String value) {
        String t = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (t) {
            case TRANSPORT_PROVIDER -> TRANSPORT_PROVIDER;
            case TRANSPORT_GENERATE -> TRANSPORT_GENERATE;
            default -> TRANSPORT_AUTO;
        };
    }

    @Override
    public String name() {
        return "router(" + transport + ")";
    }

    /** @return the configured transport policy ({@code auto}, {@code provider} or {@code generate}). */
    public String transport() {
        return transport;
    }

    /** @return the configured retry budget (retries after the first attempt). */
    public int maxRetries() {
        return maxRetries;
    }

    /**
     * @return {@code true} once the router has permanently switched to the generate transport
     *         (either because it was pinned there or because the provider reported no API access)
     */
    public boolean isUsingGenerate() {
        return stickyGenerate.get();
    }

    /** @return the transport that the next call would start with ({@code "provider"} or {@code "generate"}). */
    public String activeTransport() {
        if (stickyGenerate.get() || provider == null) {
            return generate != null ? TRANSPORT_GENERATE : TRANSPORT_PROVIDER;
        }
        return TRANSPORT_PROVIDER;
    }

    /**
     * Resets the sticky transport switch back to the configured policy; intended for {@code /aib
     * reload} after the operator changes the plan or key.
     */
    public void resetTransport() {
        boolean generateOnly = TRANSPORT_GENERATE.equals(transport) || provider == null;
        stickyGenerate.set(generateOnly);
    }

    @Override
    public ChatResult chat(ChatRequest req, StreamListener listener, AtomicBoolean cancelled) throws LlmException {
        if (req == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        int failures = 0;
        while (true) {
            if (isCancelled(cancelled)) {
                throw LlmException.cancelled();
            }
            LlmClient client = currentClient();
            if (client == null) {
                throw new LlmException("no LLM transport is configured", 0, "no_transport");
            }
            boolean usingGenerate = client == generate;
            try {
                ChatResult result = client.chat(req, listener, cancelled);
                if (result == null || !result.hasContent()) {
                    throw LlmException.emptyContent("model returned an empty answer via " + client.name());
                }
                if (failures > 0 && log.isLoggable(Level.INFO)) {
                    log.info("LLM call succeeded after " + failures + " failed attempt(s) via " + client.name());
                }
                return result;
            } catch (LlmException e) {
                if (e.isCancellation() || isCancelled(cancelled)) {
                    throw e;
                }
                if (!usingGenerate && TRANSPORT_AUTO.equals(transport) && generate != null && e.isUpgradeRequired()) {
                    boolean switched = stickyGenerate.compareAndSet(false, true);
                    if (switched) {
                        warn("provider transport cannot be used for this key (" + oneLine(e) + "); switching to the '"
                                + TRANSPORT_GENERATE + "' transport for this session");
                    }
                    if (!switched || generate == null) {
                        throw e; // already on the fallback: nothing else to try for this failure
                    }
                    continue; // immediate retry on the other transport, does not consume the retry budget
                }
                boolean retryable = e.retryable()
                        || LlmException.CODE_EMPTY_CONTENT.equalsIgnoreCase(e.code());
                if (!retryable || failures >= maxRetries) {
                    throw e;
                }
                failures++;
                long delay = backoffMillis(failures);
                warn("LLM attempt " + (failures + 1) + "/" + (maxRetries + 1) + " via " + client.name()
                        + " failed (" + oneLine(e) + "); retrying in " + delay + " ms");
                sleep(delay, cancelled);
            }
        }
    }

    /** @return the client the next attempt should use. */
    private LlmClient currentClient() {
        if (TRANSPORT_PROVIDER.equals(transport)) {
            return provider;
        }
        if (TRANSPORT_GENERATE.equals(transport)) {
            return generate;
        }
        if (stickyGenerate.get()) {
            return generate != null ? generate : provider;
        }
        return provider != null ? provider : generate;
    }

    /**
     * Backoff for the given (1-based) retry number: 2 s, 5 s, 12 s, then 30 s for every further
     * retry.
     *
     * @param retry 1-based retry index
     * @return delay in milliseconds
     */
    static long backoffMillis(int retry) {
        int idx = Math.clamp(retry - 1, 0, BACKOFF_MS.length - 1);
        return BACKOFF_MS[idx];
    }

    /**
     * Sleeps for the given duration, waking every 100 ms to honour cancellation.
     *
     * @param millis    total sleep
     * @param cancelled caller's flag, may be {@code null}
     * @throws LlmException with code {@code cancelled} if the flag is raised (or the thread is
     *                      interrupted) during the sleep
     */
    static void sleep(long millis, AtomicBoolean cancelled) throws LlmException {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        while (true) {
            if (isCancelled(cancelled)) {
                throw LlmException.cancelled();
            }
            long remainingMs = (deadline - System.nanoTime()) / 1_000_000L;
            if (remainingMs <= 0) {
                return;
            }
            try {
                Thread.sleep(Math.min(100L, remainingMs));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw LlmException.cancelled();
            }
        }
    }

    private static boolean isCancelled(AtomicBoolean cancelled) {
        return cancelled != null && cancelled.get();
    }

    private static String oneLine(LlmException e) {
        String code = e.code();
        return "HTTP " + e.status() + (code == null ? "" : "/" + code) + ": " + LlmJson.oneLine(e.getMessage(), 160);
    }

    private void warn(String message) {
        try {
            log.warning(message);
        } catch (RuntimeException ignored) {
            // Logging must never break an LLM call.
        }
    }

    @Override
    public String toString() {
        return "LlmRouter{transport=" + transport + ", maxRetries=" + maxRetries
                + ", active=" + activeTransport() + "}";
    }

    /** Builder for {@link LlmRouter} in {@code auto} mode; both transports are required. */
    public static final class Builder {
        private LlmClient provider;
        private LlmClient generate;
        private String transport = TRANSPORT_AUTO;
        private int maxRetries = 2;
        private Logger log;

        /** Sets the provider transport. */
        public Builder provider(LlmClient client) {
            this.provider = Objects.requireNonNull(client, "provider");
            return this;
        }

        /** Sets the generate transport. */
        public Builder generate(LlmClient client) {
            this.generate = Objects.requireNonNull(client, "generate");
            return this;
        }

        /** Sets the transport policy. */
        public Builder transport(String policy) {
            this.transport = policy;
            return this;
        }

        /** Sets the retry budget (clamped to {@code [0, 10]}). */
        public Builder maxRetries(int retries) {
            this.maxRetries = retries;
            return this;
        }

        /** Sets the logger used for retry warnings. */
        public Builder logger(Logger logger) {
            this.log = logger;
            return this;
        }

        /** @return the configured router. */
        public LlmRouter build() {
            return new LlmRouter(provider, generate, transport, maxRetries, log);
        }
    }

    /** @return a fresh builder. */
    public static Builder builder() {
        return new Builder();
    }
}
