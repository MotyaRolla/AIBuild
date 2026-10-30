package dev.aibuild.ai;

/**
 * Receives coarse streaming progress while a {@link LlmClient#chat} call is running.
 *
 * <p>Implementations are invoked from the transport's I/O thread, <b>never</b> from the caller's
 * thread, and are throttled to at most roughly four callbacks per second plus one final callback
 * when the stream ends. Implementations must therefore be non-blocking and thread-safe; heavy work
 * (boss-bar updates, packet sending) should be handed off to the server's main thread. Exceptions
 * thrown by a listener are swallowed by the transports and never abort the call.</p>
 */
@FunctionalInterface
public interface StreamListener {

    /**
     * Reports cumulative progress of the current call.
     *
     * @param contentChars   number of answer characters received so far (monotonically non-decreasing)
     * @param reasoningChars number of reasoning/thinking characters received so far (monotonically non-decreasing)
     */
    void onProgress(int contentChars, int reasoningChars);

    /** A listener that ignores all progress. */
    StreamListener NOOP = (contentChars, reasoningChars) -> {
    };

    /**
     * Safely notifies a listener, swallowing (but never logging at the transports' level) any
     * exception it throws.
     *
     * @param listener the listener, may be {@code null}
     * @param content  answer characters so far
     * @param reasoning reasoning characters so far
     */
    static void fire(StreamListener listener, int content, int reasoning) {
        if (listener == null) {
            return;
        }
        try {
            listener.onProgress(content, reasoning);
        } catch (RuntimeException ignored) {
            // A broken UI callback must never break an in-flight LLM request.
        }
    }
}
