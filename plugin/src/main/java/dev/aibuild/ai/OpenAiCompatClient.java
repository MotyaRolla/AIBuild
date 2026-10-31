package dev.aibuild.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link LlmClient} for an OpenAI-compatible {@code /chat/completions} endpoint, used to reach
 * DeepSeek V4.1 Flash through the Command&nbsp;Code provider gateway.
 *
 * <p>Wire protocol: a streaming SSE response ({@code data: {json}} lines terminated by
 * {@code data: [DONE]}) where each chunk carries {@code choices[0].delta.content} and
 * {@code choices[0].delta.reasoning_content}; the final chunk (requested via
 * {@code stream_options.include_usage}) carries the token counters. Text is accumulated in
 * {@code StringBuilder}s and returned as one {@link ChatResult}, so callers never see partial
 * JSON.</p>
 *
 * <p><b>Thread-safety:</b> the instance is immutable apart from its {@link java.net.http.HttpClient}
 * (documented thread-safe) and may be shared by any number of worker threads. Every mutable field
 * of a call is local to {@link #chat}.</p>
 *
 * <p><b>Cancellation / timeouts:</b> the caller's {@link AtomicBoolean} is polled while sending and
 * between lines; a watchdog thread additionally closes the response body when the flag is raised or
 * when no bytes arrive for the configured idle timeout, so a blocked socket read aborts promptly.
 * The resulting {@link LlmException} carries code {@code cancelled} or {@code idle_timeout}.</p>
 */
public class OpenAiCompatClient extends AbstractStreamingClient {

    /** Default base URL of the Command&nbsp;Code provider gateway. */
    public static final String DEFAULT_BASE_URL = "https://api.commandcode.ai/provider/v1";

    private final String baseUrl;
    private final String chatPath;
    private final Map<String, String> extraHeaders;

    /**
     * Creates a client.
     *
     * @param baseUrl        API root without a trailing slash, e.g.
     *                       {@code https://api.commandcode.ai/provider/v1}; may end with
     *                       {@code /chat/completions} (then the path is not appended again)
     * @param apiKey         bearer token; blank is allowed and will surface as HTTP 401
     * @param connectTimeout connect budget, {@code null} → 15 s
     * @param idleTimeout    maximum silence while streaming, {@code null} → 120 s
     * @param extraHeaders   optional additional request headers (e.g. CLI fingerprint headers);
     *                       {@code Content-Type}, {@code User-Agent} and {@code Authorization} are
     *                       reserved and ignored here
     * @throws IllegalArgumentException if {@code baseUrl} is blank or not an absolute http(s) URI
     */
    public OpenAiCompatClient(String baseUrl, String apiKey, Duration connectTimeout, Duration idleTimeout,
                              Map<String, String> extraHeaders) {
        super(apiKey, connectTimeout, idleTimeout, extraHeaders);
        String normalised = requireBaseUrl(baseUrl == null ? DEFAULT_BASE_URL : baseUrl, "provider");
        if (normalised.endsWith("/chat/completions")) {
            this.baseUrl = normalised.substring(0, normalised.length() - "/chat/completions".length());
            this.chatPath = "/chat/completions";
        } else {
            this.baseUrl = normalised;
            this.chatPath = normalised.endsWith("/v1") || normalised.contains("/provider/")
                    ? "/chat/completions" : "/v1/chat/completions";
        }
        this.extraHeaders = extraHeaders == null ? Map.of() : Map.copyOf(extraHeaders);
    }

    /**
     * Convenience constructor using {@link #DEFAULT_BASE_URL} and no extra headers.
     *
     * @param apiKey         bearer token
     * @param connectTimeout connect budget, {@code null} → 15 s
     * @param idleTimeout    maximum silence while streaming, {@code null} → 120 s
     */
    public OpenAiCompatClient(String apiKey, Duration connectTimeout, Duration idleTimeout) {
        this(DEFAULT_BASE_URL, apiKey, connectTimeout, idleTimeout, Map.of());
    }

    @Override
    public String name() {
        return transportName();
    }

    @Override
    protected String transportName() {
        return "provider";
    }

    /** @return the normalised API root (no trailing slash). */
    public String baseUrl() {
        return baseUrl;
    }

    /** @return the absolute endpoint URL used for chat completions. */
    public String endpoint() {
        return baseUrl + chatPath;
    }

    @Override
    public ChatResult chat(ChatRequest req, StreamListener listener, AtomicBoolean cancelled) throws LlmException {
        if (req == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (isCancelled(cancelled)) {
            throw LlmException.cancelled();
        }
        long started = System.nanoTime();
        String body = buildBody(req);
        HttpRequest request = buildRequest(URI.create(endpoint()), body, extraHeaders, "text/event-stream");
        HttpResponse<InputStream> response = send(request, cancelled);
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw errorFromResponse(response);
        }
        Accumulator acc = new Accumulator(new Progress(listener), cancelled);
        StreamGuard guard = StreamGuard.start(response.body(), cancelled, idleTimeout);
        try (guard; BufferedReader reader = reader(response.body())) {
            String line;
            while (true) {
                try {
                    line = reader.readLine();
                } catch (IOException io) {
                    guard.checkAborted(io);
                    if (isCancelled(cancelled)) {
                        throw LlmException.cancelled();
                    }
                    if (acc.hasContent()) {
                        // Keep what we already have: a truncated stream is better than nothing.
                        acc.markEof();
                        break;
                    }
                    throw LlmException.network("stream failed before any content arrived: " + io.getMessage(), io);
                }
                if (line == null) {
                    guard.checkAborted(null);
                    acc.markEof();
                    break;
                }
                guard.touch();
                if (isCancelled(cancelled)) {
                    throw LlmException.cancelled();
                }
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith(":")) {
                    continue;
                }
                if (!trimmed.startsWith("data:")) {
                    continue; // ignore event: / id: / retry: and any vendor keep-alive comments
                }
                String payload = trimmed.substring(5).trim();
                if (payload.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(payload)) {
                    acc.sawDone = true;
                    break;
                }
                JsonObject chunk;
                try {
                    JsonElement el = JsonParser.parseString(payload);
                    chunk = el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
                } catch (RuntimeException parseError) {
                    continue; // tolerate a malformed chunk instead of failing a long generation
                }
                if (chunk == null) {
                    continue;
                }
                if (acc.apply(chunk) == ApplyResult.FAILED) {
                    throw acc.failure();
                }
            }
            return acc.toResult(currentElapsedMs(started), transportName());
        } catch (IOException io) {
            guard.checkAborted(io);
            if (acc.hasContent()) {
                return acc.toResult(currentElapsedMs(started), transportName());
            }
            throw LlmException.network("I/O error while streaming the LLM response: " + io.getMessage(), io);
        }
    }

    private static long currentElapsedMs(long startedNanos) {
        return Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    // ------------------------------------------------------------------ request body

    /**
     * Serialises the request into the OpenAI chat-completions JSON shape.
     *
     * <p>Each message is encoded by {@link ChatMessage#toJson()}: text-only messages keep the plain
     * string {@code content} they always had, vision messages become an array of typed content parts
     * ({@code text} + {@code image_url} data URLs).</p>
     *
     * @param req the request
     * @return JSON text
     */
    String buildBody(ChatRequest req) {
        JsonObject root = new JsonObject();
        root.addProperty("model", req.model());
        JsonArray msgs = new JsonArray();
        for (ChatMessage m : req.messages()) {
            msgs.add(m.toJson());
        }
        root.add("messages", msgs);
        root.addProperty("max_tokens", req.maxTokens());
        root.addProperty("stream", true);
        JsonObject streamOptions = new JsonObject();
        streamOptions.addProperty("include_usage", true);
        root.add("stream_options", streamOptions);
        if (req.temperature() != null) {
            root.addProperty("temperature", req.temperature());
        }
        if (req.reasoningEffort() != null) {
            root.addProperty("reasoning_effort", req.reasoningEffort());
        }
        if (req.jsonMode()) {
            JsonObject format = new JsonObject();
            format.addProperty("type", "json_object");
            root.add("response_format", format);
        }
        return LlmJson.GSON.toJson(root);
    }

    /** @return an immutable view of the configured extra headers. */
    public Map<String, String> extraHeaders() {
        return extraHeaders;
    }

    /**
     * Convenience factory that builds a client for the Command&nbsp;Code gateway with the standard
     * 15&nbsp;s connect and 120&nbsp;s idle budgets.
     *
     * @param apiKey bearer token
     * @return a ready client
     */
    public static OpenAiCompatClient commandCode(String apiKey) {
        return new OpenAiCompatClient(DEFAULT_BASE_URL, apiKey, Duration.ofSeconds(15), Duration.ofSeconds(120),
                Map.of());
    }

    /**
     * Returns a client identical to this one but with a different idle timeout; useful for tests and
     * for long generation stages.
     *
     * @param newIdleTimeout new idle budget
     * @return a new client instance
     */
    public OpenAiCompatClient withIdleTimeout(Duration newIdleTimeout) {
        return new OpenAiCompatClient(baseUrl, apiKey(), connectTimeout, newIdleTimeout, extraHeaders);
    }

    /**
     * Copies the client configuration into a mutable map (for diagnostics).
     *
     * @return ordered map of the effective non-secret settings
     */
    public Map<String, String> describe() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("endpoint", endpoint());
        m.put("transport", transportName());
        m.put("connectTimeoutMs", Long.toString(connectTimeout.toMillis()));
        m.put("idleTimeoutMs", Long.toString(idleTimeout.toMillis()));
        m.put("apiKeyConfigured", Boolean.toString(hasApiKey()));
        m.put("extraHeaders", List.copyOf(extraHeaders.keySet()).toString());
        return m;
    }

    // ------------------------------------------------------------------ accumulation

    private enum ApplyResult { OK, FAILED }

    /**
     * Accumulates streamed chunks into content, reasoning, finish reason and usage. Confined to the
     * reading thread of a single call.
     */
    private static final class Accumulator {

        private final StringBuilder content = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();
        private final Progress progress;
        private final AtomicBoolean cancelled;

        private String finishReason;
        private int promptTokens;
        private int completionTokens;
        private int cachedTokens;
        private int reasoningTokens;
        private boolean sawDone;
        private boolean usageSeen;
        private LlmException failure;

        Accumulator(Progress progress, AtomicBoolean cancelled) {
            this.progress = progress;
            this.cancelled = cancelled;
        }

        boolean hasContent() {
            return !content.isEmpty();
        }

        void markEof() {
            if (finishReason == null) {
                finishReason = hasContent() ? "eof" : "empty";
            }
        }

        LlmException failure() {
            return failure != null ? failure : LlmException.invalidResponse("provider stream failed", null);
        }

        ApplyResult apply(JsonObject chunk) {
            if (cancelled != null && cancelled.get()) {
                failure = LlmException.cancelled();
                return ApplyResult.FAILED;
            }
            LlmJson.ApiError err = LlmJson.error(chunk);
            JsonArray choices = LlmJson.array(chunk, "choices");
            boolean hasChoices = choices != null && !choices.isEmpty();
            if (err != null && !hasChoices) {
                failure = LlmException.http(0, err.message(), err.code());
                return ApplyResult.FAILED;
            }
            String ef = null;
            JsonObject first = LlmJson.firstObject(choices);
            if (first != null) {
                ef = LlmJson.string(first, "finish_reason", "finishReason");
                JsonObject delta = LlmJson.obj(first, "delta");
                if (delta == null) {
                    delta = LlmJson.obj(first, "message");
                }
                if (delta != null) {
                    appendDelta(delta);
                }
            }
            if (ef == null) {
                ef = LlmJson.string(chunk, "finish_reason", "finishReason");
            }
            if (ef != null && !ef.isBlank()) {
                finishReason = ef.trim();
            }
            JsonObject usage = LlmJson.obj(chunk, "usage");
            if (usage != null) {
                applyUsage(usage);
            }
            progress.maybe(content.length(), reasoning.length());
            return ApplyResult.OK;
        }

        private void appendDelta(JsonObject delta) {
            String c = LlmJson.textOf(delta.get("content"));
            if (c != null && !c.isEmpty()) {
                content.append(c);
            }
            String r = LlmJson.textOf(delta.get("reasoning_content"));
            if (r == null) {
                r = LlmJson.textOf(delta.get("reasoning"));
            }
            if (r == null) {
                r = LlmJson.textOf(delta.get("thinking"));
            }
            if (r != null && !r.isEmpty()) {
                reasoning.append(r);
            }
        }

        private void applyUsage(JsonObject usage) {
            Integer p = LlmJson.integer(usage, "prompt_tokens", "inputTokens", "input_tokens");
            Integer c = LlmJson.integer(usage, "completion_tokens", "outputTokens", "output_tokens");
            JsonObject promptDetails = LlmJson.obj(usage, "prompt_tokens_details");
            Integer cached = LlmJson.integer(usage, "cachedInputTokens", "cached_tokens");
            if (cached == null) {
                cached = LlmJson.integer(promptDetails, "cached_tokens", "cachedInputTokens");
            }
            JsonObject completionDetails = LlmJson.obj(usage, "completion_tokens_details");
            JsonObject outputDetails = LlmJson.obj(usage, "outputTokenDetails");
            Integer reason = LlmJson.integer(usage, "reasoningTokens", "reasoning_tokens");
            if (reason == null) {
                reason = LlmJson.integer(completionDetails, "reasoning_tokens", "reasoningTokens");
            }
            if (reason == null) {
                reason = LlmJson.integer(outputDetails, "reasoningTokens", "reasoning_tokens");
            }
            if (p != null) {
                promptTokens = p;
                usageSeen = true;
            }
            if (c != null) {
                completionTokens = c;
                usageSeen = true;
            }
            if (cached != null) {
                cachedTokens = cached;
            }
            if (reason != null) {
                reasoningTokens = reason;
            }
        }

        ChatResult toResult(long elapsedMs, String transport) {
            progress.finish(content.length(), reasoning.length());
            String fr = finishReason;
            if (fr == null || fr.isBlank()) {
                fr = hasContent() ? (sawDone ? "stop" : "eof") : "empty";
            }
            if (!usageSeen && !sawDone) {
                // No usage chunk and no [DONE]: the stream was truncated.
                fr = hasContent() ? "eof" : "empty";
            }
            return new ChatResult(content.toString(), reasoning.toString(), fr, promptTokens, completionTokens,
                    cachedTokens, reasoningTokens, elapsedMs, transport);
        }
    }
}
