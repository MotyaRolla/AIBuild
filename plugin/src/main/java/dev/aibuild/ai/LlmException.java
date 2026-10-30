package dev.aibuild.ai;

import java.util.Locale;
import java.util.Set;

/**
 * Checked failure of an LLM call: transport error, HTTP error, cancellation, idle timeout or a
 * malformed provider response.
 *
 * <p>The exception carries machine-readable classification so callers can decide whether to retry
 * ({@link #retryable()}), fall back to another transport ({@link #isUpgradeRequired()}) or simply
 * report the problem to the player:</p>
 * <ul>
 *   <li>{@link #status()} — HTTP status, or {@code 0} for network errors, timeouts and cancellation.</li>
 *   <li>{@link #code()} — provider error code when available ({@code upgrade_required},
 *       {@code rate_limited}, {@code UNAUTHORIZED}, …) or one of the synthetic codes
 *       {@code cancelled}, {@code idle_timeout}, {@code network_error}, {@code invalid_response},
 *       {@code empty_content}. May be {@code null} when the provider gave no code.</li>
 * </ul>
 *
 * <p>Instance is immutable and thread-safe; {@link #getMessage()} contains the provider's error
 * message (truncated) but never the API key.</p>
 */
public class LlmException extends Exception {

    private static final long serialVersionUID = 1L;

    /** Synthetic code for a call aborted through the caller's cancellation flag. */
    public static final String CODE_CANCELLED = "cancelled";
    /** Synthetic code for a stream that received no bytes for the configured idle timeout. */
    public static final String CODE_IDLE_TIMEOUT = "idle_timeout";
    /** Synthetic code for connect/read failures and other I/O problems. */
    public static final String CODE_NETWORK = "network_error";
    /** Synthetic code for a response that could not be parsed (bad JSON, missing fields, …). */
    public static final String CODE_INVALID_RESPONSE = "invalid_response";
    /** Synthetic code for a technically successful call that produced no answer text. */
    public static final String CODE_EMPTY_CONTENT = "empty_content";

    /** HTTP statuses that are always worth retrying (plus most 5xx, see {@link #retryable()}). */
    private static final Set<Integer> RETRYABLE_STATUSES = Set.of(408, 409, 425, 429, 500, 502, 503, 504, 529);
    /** HTTP statuses that are never retried even though they are 5xx. */
    private static final Set<Integer> NON_RETRYABLE_5XX = Set.of(501, 505);

    private final int status;
    private final String code;
    private final boolean retryable;

    /**
     * Creates an exception.
     *
     * @param message human readable description, should mention the endpoint and the provider text
     * @param status  HTTP status, or {@code 0} for network/timeout/cancellation
     * @param code    provider or synthetic error code, may be {@code null} or blank
     * @param cause   underlying cause, may be {@code null}
     */
    public LlmException(String message, int status, String code, Throwable cause) {
        super(buildMessage(message, status, code), cause);
        this.status = Math.max(0, status);
        String c = code == null ? "" : code.trim();
        this.code = c.isEmpty() ? null : c;
        this.retryable = computeRetryable(this.status, this.code);
    }

    /** Creates an exception without a cause. */
    public LlmException(String message, int status, String code) {
        this(message, status, code, null);
    }

    private static String buildMessage(String message, int status, String code) {
        String base = message == null || message.isBlank() ? "LLM call failed" : message.trim();
        StringBuilder sb = new StringBuilder(base);
        if (status > 0) {
            sb.append(" [HTTP ").append(status).append(']');
        }
        if (code != null && !code.isBlank()) {
            sb.append(" [code=").append(code.trim()).append(']');
        }
        return sb.toString();
    }

    private static boolean computeRetryable(int status, String code) {
        if (code != null) {
            String c = code.toLowerCase(Locale.ROOT);
            if (CODE_CANCELLED.equals(c) || "upgrade_required".equals(c)) {
                return false; // cancellation and plan upgrades are final outcomes
            }
            if (CODE_IDLE_TIMEOUT.equals(c) || CODE_NETWORK.equals(c) || "rate_limited".equals(c)
                    || "rate_limit_exceeded".equals(c) || "timeout".equals(c)) {
                return true;
            }
        }
        if (status == 0) {
            // No HTTP status: network failure, connect timeout, stalled/aborted stream.
            return !"invalid_response".equalsIgnoreCase(code != null ? code : "")
                    && !"empty_content".equalsIgnoreCase(code != null ? code : "");
        }
        if (status == 403) {
            return false; // access/plan problem: switching transport or a new key is the fix
        }
        if (status == 401 || status == 403 || status == 404 || status == 400 || status == 402 || status == 422) {
            return false;
        }
        if (RETRYABLE_STATUSES.contains(status)) {
            return true;
        }
        return status >= 500 && !NON_RETRYABLE_5XX.contains(status);
    }

    // ---------------------------------------------------------------- factories

    /** A network/connect/read failure (status 0). */
    public static LlmException network(String message, Throwable cause) {
        return new LlmException(message == null ? "network error" : message, 0, CODE_NETWORK, cause);
    }

    /** A call aborted through the caller's cancellation flag. */
    public static LlmException cancelled() {
        return new LlmException("request cancelled by caller", 0, CODE_CANCELLED);
    }

    /** A call aborted because no bytes arrived within the idle timeout. */
    public static LlmException idleTimeout(long idleMillis) {
        return new LlmException("no data received for " + idleMillis + " ms", 0, CODE_IDLE_TIMEOUT);
    }

    /** A non-2xx HTTP response, with the provider-supplied message and code when available. */
    public static LlmException http(int status, String providerMessage, String providerCode) {
        String msg = providerMessage == null || providerMessage.isBlank()
                ? "provider returned HTTP " + status
                : providerMessage.trim();
        return new LlmException(msg, status, providerCode);
    }

    /** A response that could not be parsed. */
    public static LlmException invalidResponse(String message, Throwable cause) {
        return new LlmException(message, 0, CODE_INVALID_RESPONSE, cause);
    }

    /** A successful HTTP exchange that yielded no usable answer text. */
    public static LlmException emptyContent(String message) {
        return new LlmException(message == null ? "model returned no content" : message, 0, CODE_EMPTY_CONTENT);
    }

    // ---------------------------------------------------------------- accessors

    /** @return the HTTP status, or {@code 0} for network errors, timeouts and cancellation. */
    public int status() {
        return status;
    }

    /** @return the provider error code or synthetic code; {@code null} if none was reported. */
    public String code() {
        return code;
    }

    /**
     * Whether retrying the same call may succeed: network errors, idle timeouts, HTTP
     * 408/409/425/429/500/502/503/504/529 (and unlisted 5xx other than 501/505) are retryable;
     * 400/401/403/404/402/422 and cancellation are not.
     *
     * @return {@code true} if a retry is worth attempting
     */
    public boolean retryable() {
        return retryable;
    }

    /** @return {@code true} if the call failed because the caller cancelled it. */
    public boolean isCancellation() {
        return CODE_CANCELLED.equalsIgnoreCase(code);
    }

    /** @return {@code true} if the stream stalled past the idle timeout. */
    public boolean isIdleTimeout() {
        return CODE_IDLE_TIMEOUT.equalsIgnoreCase(code);
    }

    /**
     * @return {@code true} if the account/plan cannot use the OpenAI-compatible provider transport
     *         (HTTP 403, {@code upgrade_required}, or a message about missing API access), which is
     *         the signal {@link LlmRouter} uses to switch to the {@code generate} transport
     */
    public boolean isUpgradeRequired() {
        if (status == 403) {
            return true;
        }
        if (code != null && "upgrade_required".equalsIgnoreCase(code)) {
            return true;
        }
        String msg = getMessage();
        if (msg == null) {
            return false;
        }
        String lower = msg.toLowerCase(Locale.ROOT);
        return lower.contains("doesn't include api access")
                || lower.contains("does not include api access")
                || lower.contains("upgrade_required")
                || lower.contains("upgrade required");
    }

    @Override
    public String toString() {
        return "LlmException{status=" + status + ", code=" + code + ", retryable=" + retryable + ", message="
                + getMessage() + "}";
    }
}
