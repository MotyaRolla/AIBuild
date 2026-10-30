package dev.aibuild.ai;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Blocking LLM chat client.
 *
 * <p>Implementations are <b>thread-safe</b> and stateless per call: the plugin fires many requests
 * concurrently from worker threads (build stages, review rounds, chat edits), so a single client
 * instance is shared and may receive overlapping {@link #chat} calls. Each call blocks the calling
 * thread until the answer is complete, an error occurs, or the cancellation flag is raised.</p>
 *
 * <p>Cancellation is cooperative and best-effort: {@link LlmClient}s poll the supplied
 * {@link AtomicBoolean} while reading the response and abort promptly (within a few hundred
 * milliseconds) by throwing an {@link LlmException} whose {@link LlmException#isCancellation()} is
 * {@code true}. Raising the flag also covers DNS/connect stalls because a watchdog closes the
 * underlying stream.</p>
 */
public interface LlmClient {

    /**
     * Performs one blocking chat completion.
     *
     * @param req       the request to send; must not be {@code null}
     * @param listener  optional progress sink invoked from the I/O thread (may be {@code null})
     * @param cancelled cancellation flag owned by the caller; when it becomes {@code true} the call
     *                  aborts with a {@code cancelled} {@link LlmException} (may be {@code null},
     *                  in which case the call is not cancellable through the flag)
     * @return the completed result, never {@code null}
     * @throws LlmException on transport failure, HTTP error, cancellation, idle timeout or an
     *                      unusable response
     * @throws IllegalArgumentException if {@code req} is {@code null}
     */
    ChatResult chat(ChatRequest req, StreamListener listener, AtomicBoolean cancelled) throws LlmException;

    /** @return a short, stable, human-readable transport name used in logs and {@link ChatResult#transport()}. */
    String name();
}
