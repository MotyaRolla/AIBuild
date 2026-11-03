package dev.aibuild.ai;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared plumbing for the two LLM transports: request construction, the connect/headers phase with
 * cancellation, charset-tolerant line reading, throttled progress reporting and error mapping.
 *
 * <p>Subclasses implement {@link #execute} for one complete call. Instances are immutable after
 * construction apart from the immutable {@link HttpClient} they own, which is explicitly documented
 * thread-safe by the JDK, so one instance may serve arbitrarily many concurrent {@link #chat} calls.
 * Per-call state lives exclusively in local variables and in {@link Call}.</p>
 */
abstract class AbstractStreamingClient implements LlmClient {

    /** Minimum interval between two {@link StreamListener} callbacks. */
    static final long PROGRESS_INTERVAL_NANOS = Duration.ofMillis(250).toNanos();
    /** Upper bound for the raw error body read from a non-2xx response. */
    static final int MAX_ERROR_BODY_CHARS = 8_192;

    private static final String USER_AGENT = "AIBuild/1.0";

    protected final String apiKey;
    protected final Duration connectTimeout;
    protected final Duration idleTimeout;
    protected final HttpClient http;
    protected final String userAgent;

    /**
     * @param apiKey         bearer token, may be blank (the server then reports 401)
     * @param connectTimeout TCP/TLS connect budget, {@code null} means 15 s
     * @param idleTimeout    maximum silence while streaming, {@code null} means 120 s
     * @param extraHeaders   additional headers, {@code null} allowed; {@code User-Agent} is always
     *                       forced to {@code AIBuild/1.0} and {@code Content-Type} to JSON
     */
    protected AbstractStreamingClient(String apiKey, Duration connectTimeout, Duration idleTimeout,
                                      Map<String, String> extraHeaders) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.connectTimeout = positive(connectTimeout, Duration.ofSeconds(15));
        this.idleTimeout = positive(idleTimeout, Duration.ofSeconds(120));
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(this.connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.userAgent = USER_AGENT;
    }

    private static Duration positive(Duration d, Duration fallback) {
        return d == null || d.isNegative() || d.isZero() ? fallback : d;
    }

    /** @return the configured bearer token (never logged). */
    protected final String apiKey() {
        return apiKey;
    }

    /** @return the configured idle timeout (never {@code null}). */
    public Duration idleTimeout() {
        return idleTimeout;
    }

    /** @return the configured connect timeout (never {@code null}). */
    public Duration connectTimeout() {
        return connectTimeout;
    }

    /** @return {@code true} if a bearer token is configured. */
    public boolean hasApiKey() {
        return !apiKey.isEmpty();
    }

    // ------------------------------------------------------------------ requests

    /**
     * Builds a JSON POST request with the common headers applied.
     *
     * @param uri          absolute endpoint URI
     * @param jsonBody     already-serialised JSON body
     * @param extraHeaders additional per-call headers, may be {@code null}
     * @param accept       value of the {@code Accept} header
     * @return the request, ready to send
     */
    protected final HttpRequest buildRequest(URI uri, String jsonBody, Map<String, String> extraHeaders, String accept) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .timeout(connectTimeout.plus(Duration.ofMinutes(10)))
                .header("Content-Type", "application/json")
                .header("Accept", accept)
                .header("User-Agent", userAgent)
                .header("Cache-Control", "no-store");
        if (!apiKey.isEmpty()) {
            b.header("Authorization", "Bearer " + apiKey);
        }
        if (extraHeaders != null) {
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) {
                    continue;
                }
                String k = e.getKey().trim();
                if (k.isEmpty() || "content-type".equalsIgnoreCase(k) || "user-agent".equalsIgnoreCase(k)
                        || "authorization".equalsIgnoreCase(k)) {
                    continue;
                }
                try {
                    b.header(k, e.getValue());
                } catch (IllegalArgumentException ignored) {
                    // Illegal header name/value: skip rather than fail the whole call.
                }
            }
        }
        b.POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        return b.build();
    }

    /**
     * Sends a request and waits for the response headers while honouring cancellation.
     *
     * <p>The send is asynchronous so that raising the caller's flag aborts a stalled connect or a
     * server that never responds with headers; the future is cancelled and a
     * {@code cancelled} {@link LlmException} is thrown.</p>
     *
     * @param request   the request to send
     * @param cancelled caller's cancellation flag, may be {@code null}
     * @return the response with a streaming body
     * @throws LlmException on network failure, timeout, cancellation or interrupt
     */
    protected final HttpResponse<InputStream> send(HttpRequest request, AtomicBoolean cancelled) throws LlmException {
        CompletableFuture<HttpResponse<InputStream>> future =
                http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        long deadline = System.nanoTime() + connectTimeout.toNanos() + Duration.ofSeconds(30).toNanos();
        while (true) {
            if (cancelled != null && cancelled.get()) {
                future.cancel(true);
                throw LlmException.cancelled();
            }
            try {
                HttpResponse<InputStream> resp = future.get(StreamGuard.POLL_MS, TimeUnit.MILLISECONDS);
                return resp;
            } catch (TimeoutException te) {
                if (System.nanoTime() > deadline) {
                    future.cancel(true);
                    throw new LlmException("no response headers within " + connectTimeout.toMillis()
                            + " ms (+30 s grace)", 0, LlmException.CODE_NETWORK);
                }
            } catch (InterruptedException ie) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw new LlmException("interrupted while waiting for the LLM response", 0,
                        LlmException.CODE_NETWORK, ie);
            } catch (ExecutionException ee) {
                throw mapSendFailure(ee.getCause());
            } catch (java.util.concurrent.CancellationException ce) {
                throw LlmException.cancelled();
            }
        }
    }

    private static LlmException mapSendFailure(Throwable cause) {
        if (cause instanceof HttpConnectTimeoutException || cause instanceof HttpTimeoutException) {
            return new LlmException("timeout talking to the LLM gateway: " + cause.getMessage(), 0,
                    LlmException.CODE_NETWORK, cause);
        }
        if (cause instanceof ConnectException) {
            return new LlmException("cannot connect to the LLM gateway: " + cause.getMessage(), 0,
                    LlmException.CODE_NETWORK, cause);
        }
        if (cause instanceof IOException) {
            return LlmException.network("I/O error talking to the LLM gateway: " + cause.getMessage(), cause);
        }
        if (cause instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return LlmException.cancelled();
        }
        String msg = cause == null ? "unknown" : cause.getClass().getSimpleName() + ": " + cause.getMessage();
        return LlmException.network("LLM request failed: " + msg, cause);
    }

    /**
     * Reads the (small) body of a non-2xx response and maps it to an {@link LlmException}.
     *
     * @param response the failed response
     * @return the exception to throw (never {@code null}); the body is closed first
     */
    protected final LlmException errorFromResponse(HttpResponse<InputStream> response) {
        int status = response.statusCode();
        String body;
        try (InputStream in = response.body()) {
            body = readAtMost(in, MAX_ERROR_BODY_CHARS);
        } catch (IOException | RuntimeException e) {
            body = "";
        }
        LlmJson.ApiError api = LlmJson.error(LlmJson.parseObject(body));
        String message;
        String code;
        if (api != null) {
            message = api.message() != null ? api.message() : "provider returned HTTP " + status;
            code = api.code();
        } else if (!body.isBlank() && !body.stripLeading().startsWith("<")) {
            message = LlmJson.oneLine(body, 400);
            code = null;
        } else {
            message = "provider returned HTTP " + status + describeStatus(status);
            code = null;
        }
        return LlmException.http(status, message, code);
    }

    private static String describeStatus(int status) {
        return switch (status) {
            case 401 -> " (authentication failed — check the API key)";
            case 403 -> " (access forbidden — the plan may not include API access)";
            case 404 -> " (endpoint not found — check the base URL)";
            case 429 -> " (rate limited)";
            default -> "";
        };
    }

    private static String readAtMost(InputStream in, int maxChars) throws IOException {
        byte[] buf = new byte[4096];
        StringBuilder sb = new StringBuilder();
        int n;
        while (sb.length() < maxChars && (n = in.read(buf)) > 0) {
            sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /**
     * Opens a UTF-8 buffered reader over a response body.
     *
     * @param body the streaming body
     * @return a line reader; never {@code null}
     */
    protected static BufferedReader reader(InputStream body) {
        return new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8), 16 * 1024);
    }

    // ------------------------------------------------------------------ progress

    /**
     * Throttled progress reporter. One instance per call; used by the reading thread only.
     */
    static final class Progress {
        private final StreamListener listener;
        private long lastNanos;
        private int lastContent = -1;
        private int lastReasoning = -1;

        Progress(StreamListener listener) {
            this.listener = listener;
            this.lastNanos = System.nanoTime();
        }

        /** Reports progress if at least 250 ms passed since the previous callback. */
        void maybe(CharSequence content, int reasoningChars) {
            int contentChars = content.length();
            if (listener == null) {
                return;
            }
            long now = System.nanoTime();
            if (now - lastNanos < PROGRESS_INTERVAL_NANOS) {
                return;
            }
            if (contentChars == lastContent && reasoningChars == lastReasoning) {
                return;
            }
            lastNanos = now;
            lastContent = contentChars;
            lastReasoning = reasoningChars;
            StreamListener.fire(listener, content, reasoningChars);
        }

        /** Forces a final callback with the totals, if anything was produced. */
        void finish(CharSequence content, int reasoningChars) {
            int contentChars = content.length();
            if (listener == null) {
                return;
            }
            if (contentChars == lastContent && reasoningChars == lastReasoning) {
                return;
            }
            lastContent = contentChars;
            lastReasoning = reasoningChars;
            StreamListener.fire(listener, content, reasoningChars);
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Validates that a configured base URL can be used.
     *
     * @param baseUrl the configured value
     * @param what    description used in the exception message
     * @return the normalised value
     * @throws IllegalArgumentException if it is blank or not absolute http(s)
     */
    protected static String requireBaseUrl(String baseUrl, String what) {
        String b = Objects.requireNonNullElse(baseUrl, "").trim();
        if (b.isEmpty()) {
            throw new IllegalArgumentException(what + " base URL must not be blank");
        }
        try {
            URI u = URI.create(b);
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw new IllegalArgumentException(what + " base URL must be http(s): " + b);
            }
            if (u.getHost() == null || u.getHost().isBlank()) {
                throw new IllegalArgumentException(what + " base URL has no host: " + b);
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(what + " base URL is not a valid URI: " + b, e);
        }
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b;
    }

    /**
     * True when the caller's flag is set.
     *
     * @param cancelled the flag, may be {@code null}
     * @return {@code true} if cancellation was requested
     */
    protected static boolean isCancelled(AtomicBoolean cancelled) {
        return cancelled != null && cancelled.get();
    }

    /** @return a name for {@link ChatResult#transport()} and {@link #name()}. */
    protected abstract String transportName();
}
