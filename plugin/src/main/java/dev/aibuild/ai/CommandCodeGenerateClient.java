package dev.aibuild.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * {@link LlmClient} for the Command&nbsp;Code {@code /alpha/generate} endpoint, the fallback
 * transport for subscription ("Go plan") keys that cannot use the OpenAI-compatible provider API.
 *
 * <p>Wire protocol: a JSON body that mirrors the command-code CLI request envelope
 * ({@code config}, {@code params.messages} with typed content parts, tools left empty) answered with
 * <b>NDJSON</b> — one JSON object per line, not SSE. The events consumed here are
 * {@code text-delta}, {@code reasoning-delta}, {@code finish-step} (usage),
 * {@code finish} (total usage) and {@code error}; unknown event types (heartbeats,
 * {@code provider-metadata}, tool events) are ignored so future CLI additions keep working.</p>
 *
 * <p>The {@code x-command-code-version} header must look like a real CLI version, therefore it is
 * resolved lazily through the supplied {@link Supplier} (normally
 * {@link #fetchLatestCliVersion(HttpClient, String)}), evaluated once per call and cached by the
 * caller's supplier if desired.</p>
 *
 * <p><b>Thread-safety:</b> immutable after construction (the {@link HttpClient} is thread-safe), so a
 * single instance serves all worker threads concurrently.</p>
 *
 * <p><b>Cancellation / idle timeout:</b> same semantics as {@link OpenAiCompatClient} — the flag is
 * polled between lines and a watchdog closes the stream to unblock a stalled read.</p>
 */
public class CommandCodeGenerateClient extends AbstractStreamingClient {

    /** Default API root of Command&nbsp;Code. */
    public static final String DEFAULT_BASE_URL = "https://api.commandcode.ai";
    /** npm registry URL used to resolve the current CLI version. */
    public static final String NPM_LATEST_URL = "https://registry.npmjs.org/command-code/latest";
    /** Version reported when the npm lookup fails and the supplier returns nothing. */
    public static final String FALLBACK_CLI_VERSION = "1.0.0";

    private static final String GENERATE_PATH = "/alpha/generate";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);
    private static final String JSON_MODE_INSTRUCTION =
            "Reply with a single valid JSON object and nothing else, no markdown fences.";

    private final String baseUrl;
    private final Supplier<String> cliVersion;

    /**
     * Creates a client.
     *
     * @param baseUrl        API root, e.g. {@code https://api.commandcode.ai}; may already end with
     *                       {@code /alpha/generate}
     * @param apiKey         bearer token from the Command&nbsp;Code CLI login
     * @param cliVersion     supplier of the {@code x-command-code-version} header; may be
     *                       {@code null} (falls back to {@value #FALLBACK_CLI_VERSION}), may return
     *                       {@code null} or throw — both are handled
     * @param connectTimeout connect budget, {@code null} → 15 s
     * @param idleTimeout    maximum silence while streaming, {@code null} → 120 s
     * @throws IllegalArgumentException if {@code baseUrl} is blank or not an absolute http(s) URI
     */
    public CommandCodeGenerateClient(String baseUrl, String apiKey, Supplier<String> cliVersion,
                                     Duration connectTimeout, Duration idleTimeout) {
        super(apiKey, connectTimeout, idleTimeout, Map.of());
        String normalised = requireBaseUrl(baseUrl == null ? DEFAULT_BASE_URL : baseUrl, "generate");
        if (normalised.endsWith(GENERATE_PATH)) {
            this.baseUrl = normalised.substring(0, normalised.length() - GENERATE_PATH.length());
        } else {
            this.baseUrl = normalised;
        }
        this.cliVersion = cliVersion;
    }

    /**
     * Convenience constructor with the default Command&nbsp;Code root and a lazily cached CLI version.
     *
     * @param apiKey         bearer token
     * @param connectTimeout connect budget, {@code null} → 15 s
     * @param idleTimeout    maximum silence while streaming, {@code null} → 120 s
     */
    public CommandCodeGenerateClient(String apiKey, Duration connectTimeout, Duration idleTimeout) {
        this(DEFAULT_BASE_URL, apiKey, cachingVersionSupplier(), connectTimeout, idleTimeout);
    }

    @Override
    public String name() {
        return transportName();
    }

    @Override
    protected String transportName() {
        return "generate";
    }

    /** @return the normalised API root (no trailing slash). */
    public String baseUrl() {
        return baseUrl;
    }

    /** @return the absolute endpoint URL used for requests. */
    public String endpoint() {
        return baseUrl + GENERATE_PATH;
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
        try {
            return execute(req, listener, cancelled, started, true);
        } catch (LlmException e) {
            // Some deployments reject reasoning_effort inside params: retry once without it.
            if (req.reasoningEffort() != null && e.status() == 400 && mentionsReasoningEffort(e.getMessage())) {
                if (isCancelled(cancelled)) {
                    throw LlmException.cancelled();
                }
                return execute(req, listener, cancelled, System.nanoTime(), false);
            }
            throw e;
        }
    }

    private static boolean mentionsReasoningEffort(String message) {
        return message != null && message.toLowerCase(Locale.ROOT).contains("reasoning_effort");
    }

    private ChatResult execute(ChatRequest req, StreamListener listener, AtomicBoolean cancelled,
                               long startedNanos, boolean includeReasoningEffort) throws LlmException {
        String body = buildBody(req, includeReasoningEffort);
        HttpRequest request = buildRequest(URI.create(endpoint()), body, cliHeaders(), "application/json");
        HttpResponse<java.io.InputStream> response = send(request, cancelled);
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
                if (trimmed.startsWith("data:")) {
                    trimmed = trimmed.substring(5).trim(); // tolerate an SSE-wrapped gateway
                    if (trimmed.isEmpty() || "[DONE]".equals(trimmed)) {
                        continue;
                    }
                }
                JsonObject event;
                try {
                    JsonElement el = JsonParser.parseString(trimmed);
                    event = el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
                } catch (RuntimeException parseError) {
                    continue; // ignore malformed lines; a long generation must not die on a heartbeat
                }
                if (event == null) {
                    continue;
                }
                if (acc.apply(event) == ApplyResult.FAILED) {
                    throw acc.failure();
                }
            }
            return acc.toResult(Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L), transportName());
        } catch (IOException io) {
            guard.checkAborted(io);
            if (acc.hasContent()) {
                return acc.toResult(Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L), transportName());
            }
            throw LlmException.network("I/O error while streaming the LLM response: " + io.getMessage(), io);
        }
    }

    // ------------------------------------------------------------------ request

    /**
     * Resolves the CLI version for the header, tolerating a broken or missing supplier.
     *
     * @return a header-safe version string (never blank)
     */
    String resolveCliVersion() {
        String v = null;
        if (cliVersion != null) {
            try {
                v = cliVersion.get();
            } catch (RuntimeException ignored) {
                v = null;
            }
        }
        if (v == null || v.isBlank()) {
            return FALLBACK_CLI_VERSION;
        }
        String cleaned = v.trim();
        StringBuilder sb = new StringBuilder(cleaned.length());
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || c == '.' || c == '-' || c == '+' || c == '_') {
                sb.append(c);
            }
        }
        return sb.isEmpty() ? FALLBACK_CLI_VERSION : sb.toString();
    }

    private Map<String, String> cliHeaders() {
        return Map.of(
                "x-cli-environment", "production",
                "x-command-code-version", resolveCliVersion(),
                "x-session-id", UUID.randomUUID().toString());
    }

    /**
     * Serialises the request into the CLI-compatible JSON envelope.
     *
     * @param req                     the request to encode
     * @param includeReasoningEffort  whether the (possibly unsupported) {@code reasoning_effort}
     *                                parameter is emitted
     * @return JSON text
     */
    String buildBody(ChatRequest req, boolean includeReasoningEffort) {
        JsonObject root = new JsonObject();
        JsonObject config = new JsonObject();
        config.addProperty("workingDir", "/");
        config.addProperty("date", LocalDate.now().format(DATE));
        config.addProperty("environment", "production");
        config.add("structure", new JsonArray());
        config.addProperty("isGitRepo", false);
        config.addProperty("currentBranch", "");
        config.addProperty("mainBranch", "");
        config.addProperty("gitStatus", "");
        config.add("recentCommits", new JsonArray());
        root.add("config", config);
        root.addProperty("memory", "");
        root.add("taste", com.google.gson.JsonNull.INSTANCE);
        root.add("skills", com.google.gson.JsonNull.INSTANCE);
        root.addProperty("permissionMode", "standard");

        JsonObject params = new JsonObject();
        params.addProperty("model", req.model());

        String system = req.systemPrompt();
        if (req.jsonMode()) {
            system = system.isBlank() ? JSON_MODE_INSTRUCTION : system + "\n\n" + JSON_MODE_INSTRUCTION;
        }
        params.addProperty("system", system);

        JsonArray messages = new JsonArray();
        for (ChatMessage m : req.messages()) {
            if (m.isSystem()) {
                continue; // hoisted into params.system, exactly like the CLI does
            }
            JsonObject msg = new JsonObject();
            msg.addProperty("role", "assistant".equals(m.role()) ? "assistant" : "user");
            msg.add("content", m.contentParts().toPartsJson());
            messages.add(msg);
        }
        if (messages.isEmpty()) {
            JsonObject msg = new JsonObject();
            msg.addProperty("role", "user");
            JsonArray parts = new JsonArray();
            JsonObject part = new JsonObject();
            part.addProperty("type", "text");
            part.addProperty("text", "");
            parts.add(part);
            msg.add("content", parts);
            messages.add(msg);
        }
        params.add("messages", messages);
        params.add("tools", new JsonArray());
        params.addProperty("max_tokens", req.maxTokens());
        params.addProperty("stream", true);
        if (req.temperature() != null) {
            params.addProperty("temperature", req.temperature());
        }
        if (includeReasoningEffort && req.reasoningEffort() != null) {
            params.addProperty("reasoning_effort", req.reasoningEffort());
        }
        root.add("params", params);
        return LlmJson.GSON.toJson(root);
    }

    // ------------------------------------------------------------------ accumulation

    private enum ApplyResult { OK, FAILED }

    /** Accumulates NDJSON events of one call; confined to the reading thread. */
    private static final class Accumulator {

        private final StringBuilder content = new StringBuilder();
        private final OperationLoopGuard loopGuard = new OperationLoopGuard();
        private final StringBuilder reasoning = new StringBuilder();
        private final Progress progress;
        private final AtomicBoolean cancelled;

        private String finishReason;
        private int promptTokens;
        private int completionTokens;
        private int cachedTokens;
        private int reasoningTokens;
        private boolean sawFinish;
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
            return failure != null ? failure : LlmException.invalidResponse("generate stream failed", null);
        }

        ApplyResult apply(JsonObject event) {
            if (cancelled != null && cancelled.get()) {
                failure = LlmException.cancelled();
                return ApplyResult.FAILED;
            }
            String type = LlmJson.string(event, "type");
            if (type == null) {
                // Some gateways omit "type" for the final usage object.
                JsonObject usage = LlmJson.obj(event, "usage");
                if (usage != null) {
                    applyUsage(usage, true);
                }
                return ApplyResult.OK;
            }
            switch (type) {
                case "text-delta", "text_delta", "content-delta" -> appendText(event);
                case "reasoning-delta", "reasoning_delta", "thinking-delta" -> appendReasoning(event);
                case "finish-step", "finish_step", "step-finish" -> {
                    String fr = LlmJson.string(event, "finishReason", "finish_reason");
                    if (fr != null && !fr.isBlank()) {
                        finishReason = fr.trim();
                    }
                    JsonObject usage = LlmJson.obj(event, "usage");
                    if (usage != null) {
                        applyUsage(usage, true);
                    }
                }
                case "finish" -> {
                    String fr = LlmJson.string(event, "finishReason", "finish_reason");
                    if (fr != null && !fr.isBlank()) {
                        finishReason = fr.trim();
                    }
                    JsonObject usage = LlmJson.obj(event, "totalUsage");
                    if (usage == null) {
                        usage = LlmJson.obj(event, "usage");
                    }
                    if (usage != null) {
                        applyUsage(usage, false);
                    }
                    sawFinish = true;
                }
                case "error" -> {
                    JsonObject err = LlmJson.obj(event, "error");
                    if (err != null) {
                        failure = LlmException.http(0, LlmJson.string(err, "message", "detail", "msg"),
                                LlmJson.string(err, "code", "type"));
                    } else {
                        String msg = LlmJson.string(event, "error", "message", "detail");
                        failure = LlmException.http(0, msg == null ? "generate stream reported an error" : msg, null);
                    }
                    return ApplyResult.FAILED;
                }
                case "text-start", "text-end", "reasoning-start", "reasoning-end", "start", "start-step",
                     "provider-metadata", "tool-input-start", "tool-input-delta", "tool-input-end",
                     "tool-call", "tool-result", "step-start", "step-finish-event" -> {
                    // Structural / metadata events: nothing to accumulate.
                }
                default -> {
                    // Unknown event: salvage any text/reasoning payload, ignore the rest.
                    if (event.has("text") && type.contains("delta")) {
                        appendText(event);
                    }
                }
            }
            if (loopGuard.looping(content)) {
                failure = LlmException.invalidResponse("repetitive build operations in stream", null);
                return ApplyResult.FAILED;
            }
            progress.maybe(content, reasoning.length());
            return ApplyResult.OK;
        }

        private void appendText(JsonObject event) {
            String t = LlmJson.textOf(event.get("text"));
            if (t == null) {
                t = LlmJson.textOf(event.get("delta"));
            }
            if (t == null) {
                t = LlmJson.textOf(event.get("content"));
            }
            if (t != null && !t.isEmpty()) {
                content.append(t);
            }
        }

        private void appendReasoning(JsonObject event) {
            String r = LlmJson.textOf(event.get("text"));
            if (r == null) {
                r = LlmJson.textOf(event.get("delta"));
            }
            if (r != null && !r.isEmpty()) {
                reasoning.append(r);
            }
        }

        private void applyUsage(JsonObject usage, boolean allowNestedDetails) {
            Integer p = LlmJson.integer(usage, "inputTokens", "prompt_tokens", "input_tokens");
            Integer c = LlmJson.integer(usage, "outputTokens", "completion_tokens", "output_tokens");
            Integer cached = LlmJson.integer(usage, "cachedInputTokens", "cached_tokens");
            Integer reason = LlmJson.integer(usage, "reasoningTokens", "reasoning_tokens");
            if (allowNestedDetails) {
                if (cached == null) {
                    JsonObject details = LlmJson.obj(usage, "inputTokenDetails");
                    if (details == null) {
                        details = LlmJson.obj(usage, "prompt_tokens_details");
                    }
                    cached = LlmJson.integer(details, "cacheReadTokens", "cached_tokens", "cachedInputTokens");
                }
                if (reason == null) {
                    JsonObject details = LlmJson.obj(usage, "outputTokenDetails");
                    if (details == null) {
                        details = LlmJson.obj(usage, "completion_tokens_details");
                    }
                    reason = LlmJson.integer(details, "reasoningTokens", "reasoning_tokens");
                }
            }
            if (p != null) {
                promptTokens = p;
            }
            if (c != null) {
                completionTokens = c;
            }
            if (cached != null) {
                cachedTokens = cached;
            }
            if (reason != null) {
                reasoningTokens = reason;
            }
        }

        ChatResult toResult(long elapsedMs, String transport) {
            progress.finish(content, reasoning.length());
            String fr = finishReason;
            if (fr == null || fr.isBlank()) {
                fr = hasContent() ? (sawFinish ? "stop" : "eof") : "empty";
            }
            return new ChatResult(content.toString(), reasoning.toString(), fr, promptTokens, completionTokens,
                    cachedTokens, reasoningTokens, elapsedMs, transport);
        }
    }

    // ------------------------------------------------------------------ version discovery

    /**
     * Fetches the latest {@code command-code} npm version, as required by the
     * {@code x-command-code-version} header.
     *
     * <p>Never throws and never blocks longer than ~10&nbsp;s; any failure (offline, DNS, non-200,
     * malformed body) yields {@code fallback}.</p>
     *
     * @param http     HTTP client to use; {@code null} creates a short-lived one
     * @param fallback value returned on failure, may be {@code null} (then
     *                 {@value #FALLBACK_CLI_VERSION} is used)
     * @return a version string, never blank
     */
    public static String fetchLatestCliVersion(HttpClient http, String fallback) {
        return fetchCliVersion(http, NPM_LATEST_URL, fallback);
    }

    /**
     * Same as {@link #fetchLatestCliVersion(HttpClient, String)} but against an arbitrary registry URL
     * (used by tests and by private npm mirrors).
     *
     * @param http     HTTP client to use; {@code null} creates a short-lived one
     * @param url      absolute URL returning a JSON object with a {@code version} field
     * @param fallback value returned on failure, may be {@code null}
     * @return a version string, never blank
     */
    public static String fetchCliVersion(HttpClient http, String url, String fallback) {
        String fb = fallback == null || fallback.isBlank() ? FALLBACK_CLI_VERSION : fallback.trim();
        if (url == null || url.isBlank()) {
            return fb;
        }
        HttpClient client = http;
        boolean owned = false;
        if (client == null) {
            client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            owned = true;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json")
                    .header("User-Agent", "AIBuild/1.0")
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(
                    StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                return fb;
            }
            JsonObject root = LlmJson.parseObject(response.body());
            String version = LlmJson.string(root, "version");
            if (version == null || version.isBlank()) {
                return fb;
            }
            return version.trim();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return fb;
        } catch (Exception e) {
            return fb;
        } finally {
            if (owned) {
                client.close();
            }
        }
    }

    /**
     * Returns a supplier that resolves the CLI version once, lazily, and caches it for the lifetime
     * of the process (a failed lookup falls back to {@value #FALLBACK_CLI_VERSION}).
     *
     * @return a thread-safe memoising supplier
     */
    public static Supplier<String> cachingVersionSupplier() {
        return new Supplier<>() {
            private volatile String cached;

            @Override
            public String get() {
                String v = cached;
                if (v == null) {
                    synchronized (this) {
                        v = cached;
                        if (v == null) {
                            v = fetchLatestCliVersion(null, FALLBACK_CLI_VERSION);
                            cached = v;
                        }
                    }
                }
                return v;
            }
        };
    }

    /**
     * Builds the exact list of fields that {@link #chat} will send, for diagnostics/tests.
     *
     * @param req the request to inspect
     * @return the serialised JSON body (reasoning effort included)
     */
    public String previewBody(ChatRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        return buildBody(req, true);
    }

    /**
     * @return an immutable snapshot of the effective non-secret settings
     */
    public Map<String, String> describe() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("endpoint", endpoint());
        m.put("transport", transportName());
        m.put("connectTimeoutMs", Long.toString(connectTimeout.toMillis()));
        m.put("idleTimeoutMs", Long.toString(idleTimeout.toMillis()));
        m.put("apiKeyConfigured", Boolean.toString(hasApiKey()));
        m.put("cliVersion", resolveCliVersion());
        return Collections.unmodifiableMap(m);
    }

    /** @return an unmodifiable list of the message roles this transport can encode. */
    public List<String> supportedRoles() {
        return List.of(ChatMessage.ROLE_SYSTEM, ChatMessage.ROLE_USER, ChatMessage.ROLE_ASSISTANT);
    }
}
