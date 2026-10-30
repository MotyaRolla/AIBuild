package dev.aibuild.ai;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Offline, self-checking test suite for the {@code dev.aibuild.ai} module.
 *
 * <p>Runs entirely against a local {@link HttpServer} — no network, no API key, no credit usage.
 * Prints {@code PASS}/{@code FAIL} lines and exits with status 1 if anything failed.</p>
 */
public final class LlmUnitTests {

    private static int passed;
    private static final List<String> FAILURES = new CopyOnWriteArrayList<>();
    private static final Logger LOG = Logger.getLogger("LlmUnitTests");

    public static void main(String[] args) throws Exception {
        // Keep the test output readable: the router logs expected retry warnings.
        Logger.getLogger(LlmRouter.class.getName()).setLevel(java.util.logging.Level.OFF);
        LOG.setLevel(java.util.logging.Level.OFF);

        testRecords();
        testExceptionClassification();
        testProviderHappyPath();
        testProviderEofWithoutDone();
        testProviderHttpError();
        testProviderRetryOn429();
        testProviderCancellation();
        testProviderIdleTimeout();
        testProviderConcurrency();
        testGenerateHappyPath();
        testGenerateReasoningEffortRetry();
        testGenerateErrorEvent();
        testGenerateIdleTimeout();
        testVersionHelper();
        testRouterSwapToGenerate();
        testRouterRetryBackoff();
        testRouterPinnedTransports();
        testRouterCancellationDuringBackoff();
        testRouterFallbackFailure();

        System.out.println();
        System.out.println("--------------------------------------------------");
        if (FAILURES.isEmpty()) {
            System.out.println("ALL CHECKS PASSED (" + passed + " assertions)");
        } else {
            System.out.println("FAILURES (" + FAILURES.size() + " of " + (passed + FAILURES.size()) + " checks):");
            for (String f : FAILURES) {
                System.out.println("  - " + f);
            }
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ assertions

    private static void check(String what, boolean condition) {
        if (condition) {
            passed++;
        } else {
            FAILURES.add(what);
            System.out.println("FAIL " + what);
        }
    }

    private static void checkEquals(String what, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (ok) {
            passed++;
        } else {
            FAILURES.add(what + " (expected " + expected + ", got " + actual + ")");
            System.out.println("FAIL " + what + " (expected " + expected + ", got " + actual + ")");
        }
    }

    private static void section(String name) {
        System.out.println();
        System.out.println("== " + name);
    }

    private static void ok(String name) {
        System.out.println("PASS " + name);
    }

    // ------------------------------------------------------------------ value types

    private static void testRecords() {
        section("records");
        ChatMessage m = new ChatMessage("  USER ", null);
        checkEquals("role normalised", "user", m.role());
        checkEquals("null content becomes empty", "", m.content());
        checkEquals("system helper", "system", ChatMessage.system("x").role());
        try {
            new ChatMessage("   ", "x");
            check("blank role rejected", false);
        } catch (IllegalArgumentException expected) {
            check("blank role rejected", true);
        }

        ChatRequest r = new ChatRequest(null, List.of(ChatMessage.user("hi")), -5, 9.5, "HIGH", true);
        checkEquals("model defaults", ChatRequest.DEFAULT_MODEL, r.model());
        checkEquals("maxTokens clamped up", 1, r.maxTokens());
        checkEquals("temperature clamped", 2.0d, r.temperature());
        checkEquals("effort normalised", "high", r.reasoningEffort());
        ChatRequest badEffort = ChatRequest.simple("x", 10).withReasoningEffort("turbo");
        checkEquals("unknown effort dropped", null, badEffort.reasoningEffort());
        ChatRequest huge = ChatRequest.simple("x", 9_999_999);
        checkEquals("maxTokens clamped down", ChatRequest.MAX_TOKENS_LIMIT, huge.maxTokens());
        try {
            new ChatRequest("m", List.of(), 10, null, null, false);
            check("empty message list rejected", false);
        } catch (IllegalArgumentException expected) {
            check("empty message list rejected", true);
        }
        ChatRequest sys = new ChatRequest("m", List.of(ChatMessage.system("A"), ChatMessage.user("q"),
                ChatMessage.system("B")), 10, null, null, false);
        checkEquals("systemPrompt joins", "A\n\nB", sys.systemPrompt());

        ChatResult res = new ChatResult(null, null, null, -1, 5, 0, 0, -3, "  ");
        checkEquals("result null content", "", res.content());
        checkEquals("result finish default", "unknown", res.finishReason());
        checkEquals("result tokens clamped", 0, res.promptTokens());
        checkEquals("result elapsed clamped", 0L, res.elapsedMs());
        checkEquals("totalTokens", 5, res.totalTokens());
        check("summary mentions transport", res.summary().contains("transport="));

        ChatMessage npe = ChatMessage.user("x");
        check("StreamListener.NOOP usable", StreamListener.NOOP != null && npe.content().equals("x"));
        StreamListener.NOOP.onProgress(1, 2);
        StreamListener.fire((c, rr) -> {
            throw new IllegalStateException("listener bug");
        }, 1, 1);
        check("listener exceptions swallowed", true);
        ok("value records behave defensively");
    }

    private static void testExceptionClassification() {
        section("exception classification");
        check("429 retryable", new LlmException("x", 429, "rate_limited").retryable());
        check("503 retryable", LlmException.http(503, "x", null).retryable());
        check("408 retryable", LlmException.http(408, "x", null).retryable());
        check("529 retryable", LlmException.http(529, "x", null).retryable());
        check("network retryable", LlmException.network("boom", null).retryable());
        check("idle timeout retryable", LlmException.idleTimeout(100).retryable());
        check("401 not retryable", !LlmException.http(401, "x", null).retryable());
        check("403 not retryable", !LlmException.http(403, "x", null).retryable());
        check("400 not retryable", !LlmException.http(400, "x", null).retryable());
        check("cancelled not retryable", !LlmException.cancelled().retryable());
        check("cancelled detected", LlmException.cancelled().isCancellation());
        check("status 0 for cancellation", LlmException.cancelled().status() == 0);
        check("upgrade via code", new LlmException("nope", 200, "upgrade_required").isUpgradeRequired());
        check("upgrade via 403", LlmException.http(403, "no", null).isUpgradeRequired());
        check("upgrade via message",
                new LlmException("Your plan doesn't include API access.", 0, null).isUpgradeRequired());
        check("not upgrade", !LlmException.http(500, "boom", null).isUpgradeRequired());
        check("message carries status", LlmException.http(404, "nope", "E404").getMessage().contains("404"));
        check("code null when blank", LlmException.http(500, "x", "  ").code() == null);
        ok("LlmException classification matches the spec");
    }

    // ------------------------------------------------------------------ fake server plumbing

    /** Handler that receives the parsed request body and writes a response. */
    @FunctionalInterface
    interface Responder {
        void respond(HttpExchange exchange, String requestBody) throws Exception;
    }

    /** Tiny capture record for one received request. */
    record Captured(String body, Map<String, List<String>> headers) {
        String header(String name) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) {
                    return e.getValue().get(0);
                }
            }
            return null;
        }
    }

    /** Wrapper that makes a local {@link HttpServer} usable in try-with-resources. */
    static final class FakeServer implements AutoCloseable {
        private final HttpServer server;

        FakeServer(HttpServer server) {
            this.server = server;
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    /** Starts a local HTTP server on a random port, returning a closeable handle. */
    private static FakeServer serve(String path, Responder responder,
                                    List<Captured> captures) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (captures != null) {
                captures.add(new Captured(body, exchange.getRequestHeaders()));
            }
            try {
                responder.respond(exchange, body);
            } catch (Exception e) {
                // The client aborted the connection (cancellation tests) — expected.
            } finally {
                exchange.close();
            }
        });
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "fake-llm-server");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        return new FakeServer(server);
    }

    private static void streamSse(HttpExchange exchange, List<String> events) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        OutputStream out = exchange.getResponseBody();
        for (String e : events) {
            out.write(e.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    private static String sseChunk(String deltaJson) {
        return "data: " + deltaJson + "\n\n";
    }

    private static final String USAGE_JSON = "\"usage\":{\"prompt_tokens\":35,\"completion_tokens\":11,"
            + "\"prompt_tokens_details\":{\"cached_tokens\":7},"
            + "\"completion_tokens_details\":{\"reasoning_tokens\":9}}";

    // ------------------------------------------------------------------ provider transport

    private static void testProviderHappyPath() throws Exception {
        section("provider transport: SSE happy path");
        List<Captured> captures = new CopyOnWriteArrayList<>();
        FakeServer server = serve("/provider/v1/chat/completions", (exchange, body) -> {
            List<String> events = new ArrayList<>();
            events.add(sseChunk("{\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"},\"finish_reason\":null}]}"));
            events.add(sseChunk("{\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"thinking\"}}]}"));
            events.add(sseChunk("{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"{\\\"ok\\\":true\"}}]}"));
            events.add(sseChunk("{\"choices\":[{\"index\":0,\"delta\":{\"content\":\",\\\"sum\\\":42}\"}}]}"));
            events.add(sseChunk("{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],"
                    + USAGE_JSON + "}"));
            events.add("data: [DONE]\n\n");
            streamSse(exchange, events);
        }, captures);

        try (server) {
            OpenAiCompatClient client = new OpenAiCompatClient(server.url() + "/provider/v1", "test-key",
                    Duration.ofSeconds(5), Duration.ofSeconds(20), Map.of("X-Test", "yes"));
            AtomicInteger progressCalls = new AtomicInteger();
            ChatRequest req = new ChatRequest("deepseek/deepseek-v4.1-flash",
                    List.of(ChatMessage.system("be brief"), ChatMessage.user("json please")),
                    256, 0.2, "high", true);
            ChatResult result = client.chat(req, (c, r) -> progressCalls.incrementAndGet(), null);

            checkEquals("content assembled", "{\"ok\":true,\"sum\":42}", result.content());
            checkEquals("reasoning assembled", "thinking", result.reasoning());
            checkEquals("finish reason", "stop", result.finishReason());
            checkEquals("prompt tokens", 35, result.promptTokens());
            checkEquals("completion tokens", 11, result.completionTokens());
            checkEquals("cached tokens", 7, result.cachedTokens());
            checkEquals("reasoning tokens", 9, result.reasoningTokens());
            checkEquals("transport name", "provider", result.transport());
            check("elapsed recorded", result.elapsedMs() >= 0);
            checkEquals("client name", "provider", client.name());
            check("progress listener called", progressCalls.get() >= 1);

            Captured cap = captures.get(0);
            JsonObject sent = LlmJson.parseObject(cap.body());
            check("request is json", sent != null);
            checkEquals("model sent", "deepseek/deepseek-v4.1-flash", LlmJson.string(sent, "model"));
            checkEquals("stream enabled", Boolean.TRUE.toString(), String.valueOf(
                    sent.get("stream").getAsBoolean()));
            checkEquals("max_tokens sent", 256, LlmJson.integer(sent, "max_tokens"));
            checkEquals("reasoning_effort sent", "high", LlmJson.string(sent, "reasoning_effort"));
            checkEquals("response_format", "json_object",
                    LlmJson.string(LlmJson.obj(sent, "response_format"), "type"));
            checkEquals("include_usage", Boolean.TRUE.toString(), String.valueOf(
                    LlmJson.obj(sent, "stream_options").get("include_usage").getAsBoolean()));
            checkEquals("temperature sent", "0.2", LlmJson.string(sent, "temperature"));
            check("bearer header", "Bearer test-key".equals(cap.header("Authorization")));
            check("user agent", "AIBuild/1.0".equals(cap.header("User-Agent")));
            checkEquals("extra header", "yes", cap.header("X-Test"));
            check("two messages", LlmJson.array(sent, "messages").size() == 2);
            ok("provider SSE parsing, headers and body verified");
        }
    }

    private static void testProviderEofWithoutDone() throws Exception {
        section("provider transport: stream ends without [DONE]");
        FakeServer server = serve("/v1/chat/completions", (exchange, body) -> {
            streamSse(exchange, List.of(
                    sseChunk("{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"partial\"}}]}")));
        }, null);
        try (server) {
            OpenAiCompatClient client = new OpenAiCompatClient(server.url(), "k",
                    Duration.ofSeconds(5), Duration.ofSeconds(5), null);
            ChatResult r = client.chat(ChatRequest.simple("hi", 32), null, null);
            checkEquals("partial content kept", "partial", r.content());
            checkEquals("eof finish reason", "eof", r.finishReason());
            ok("truncated stream still returns content");
        }
    }

    private static void testProviderHttpError() throws Exception {
        section("provider transport: HTTP error mapping");
        FakeServer server = serve("/v1/chat/completions", (exchange, body) -> {
            byte[] payload = ("{\"error\":{\"message\":\"Pay up\",\"type\":\"billing\","
                    + "\"code\":\"upgrade_required\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(403, payload.length);
            exchange.getResponseBody().write(payload);
        }, null);
        try (server) {
            OpenAiCompatClient client = new OpenAiCompatClient(server.url(), "k",
                    Duration.ofSeconds(5), Duration.ofSeconds(5), null);
            try {
                client.chat(ChatRequest.simple("hi", 32), null, null);
                check("403 must throw", false);
            } catch (LlmException e) {
                checkEquals("status", 403, e.status());
                checkEquals("code", "upgrade_required", e.code());
                check("message preserved", e.getMessage().contains("Pay up"));
                check("not retryable", !e.retryable());
                check("upgrade required", e.isUpgradeRequired());
            }
            ok("403/upgrade_required mapped");
        }
    }

    private static void testProviderRetryOn429() throws Exception {
        section("provider transport: 429 surfaces as retryable");
        FakeServer server = serve("/v1/chat/completions", (exchange, body) -> {
            byte[] payload = "{\"error\":{\"message\":\"slow down\",\"code\":\"rate_limited\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, payload.length);
            exchange.getResponseBody().write(payload);
        }, null);
        try (server) {
            OpenAiCompatClient client = new OpenAiCompatClient(server.url(), "k",
                    Duration.ofSeconds(5), Duration.ofSeconds(5), null);
            try {
                client.chat(ChatRequest.simple("hi", 32), null, null);
                check("429 must throw", false);
            } catch (LlmException e) {
                checkEquals("429 status", 429, e.status());
                check("429 retryable", e.retryable());
            }
            ok("429 classified retryable");
        }
    }

    private static void testProviderCancellation() throws Exception {
        section("provider transport: cancellation aborts a blocked read");
        CountDownLatch streaming = new CountDownLatch(1);
        FakeServer server = serve("/v1/chat/completions", (exchange, body) -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            out.write(sseChunk("{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"once upon a time \"}}]}")
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
            streaming.countDown();
            // Keep trickling for a while; the client must abort long before this ends.
            for (int i = 0; i < 100; i++) {
                Thread.sleep(100);
                out.write(sseChunk("{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"more \"}}]}")
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        }, null);
        try (server) {
            OpenAiCompatClient client = new OpenAiCompatClient(server.url(), "k",
                    Duration.ofSeconds(5), Duration.ofSeconds(60), null);
            AtomicBoolean cancelled = new AtomicBoolean();
            AtomicInteger progressCallbacks = new AtomicInteger();
            List<LlmException> failures = new CopyOnWriteArrayList<>();
            CountDownLatch done = new CountDownLatch(1);
            Thread worker = new Thread(() -> {
                try {
                    client.chat(ChatRequest.simple("write a very long story", 4000),
                            (c, r) -> progressCallbacks.incrementAndGet(), cancelled);
                } catch (LlmException e) {
                    failures.add(e);
                } finally {
                    done.countDown();
                }
            }, "llm-worker");
            long started = System.nanoTime();
            worker.start();
            check("server started streaming", streaming.await(10, TimeUnit.SECONDS));
            Thread.sleep(400); // let a little content accumulate
            long cancelAt = System.nanoTime();
            cancelled.set(true);
            check("worker finished after cancel", done.await(5, TimeUnit.SECONDS));
            worker.join(2000);
            long reactionMs = (System.nanoTime() - cancelAt) / 1_000_000L;
            checkEquals("one failure", 1, failures.size());
            if (!failures.isEmpty()) {
                LlmException e = failures.get(0);
                checkEquals("code cancelled", "cancelled", e.code());
                check("isCancellation", e.isCancellation());
                check("not retryable", !e.retryable());
                check("no content leaked into the exception", !e.getMessage().contains("once upon"));
            }
            check("reaction within 2 s (was " + reactionMs + " ms)", reactionMs <= 2000);
            check("total call ended reasonably fast", (System.nanoTime() - started) / 1_000_000L < 5000);
            check("progress was reported before cancelling", progressCallbacks.get() >= 1);
            ok("cancellation aborts within ~100 ms via the watchdog");
        }
    }

    private static void testProviderIdleTimeout() throws Exception {
        section("provider transport: idle timeout");
        FakeServer server = serve("/v1/chat/completions", (exchange, body) -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            out.write(sseChunk("{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"start\"}}]}")
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
            Thread.sleep(4000); // stall well past the 600 ms idle timeout
        }, null);
        try (server) {
            OpenAiCompatClient client = new OpenAiCompatClient(server.url(), "k",
                    Duration.ofSeconds(5), Duration.ofMillis(600), null);
            long started = System.nanoTime();
            try {
                client.chat(ChatRequest.simple("hi", 64), null, null);
                check("idle timeout must throw", false);
            } catch (LlmException e) {
                long ms = (System.nanoTime() - started) / 1_000_000L;
                checkEquals("idle code", "idle_timeout", e.code());
                check("idle retryable", e.retryable());
                check("idle fired quickly (" + ms + " ms)", ms < 3000);
            }
            ok("idle timeout surfaces as retryable idle_timeout");
        }
    }

    private static void testProviderConcurrency() throws Exception {
        section("provider transport: concurrent calls on one instance");
        FakeServer server = serve("/v1/chat/completions", (exchange, body) -> {
            JsonObject sent = LlmJson.parseObject(body);
            String user = sent == null ? "" : sent.getAsJsonArray("messages").get(0).getAsJsonObject()
                    .get("content").getAsString();
            streamSse(exchange, List.of(
                    sseChunk("{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"echo:" + user + "\"}}]}"),
                    sseChunk("{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],"
                            + USAGE_JSON + "}"),
                    "data: [DONE]\n\n"));
        }, null);
        try (server) {
            OpenAiCompatClient client = new OpenAiCompatClient(server.url(), "k",
                    Duration.ofSeconds(5), Duration.ofSeconds(10), null);
            int threads = 8;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch ready = new CountDownLatch(1);
            List<String> results = Collections.synchronizedList(new ArrayList<>());
            List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
            for (int i = 0; i < threads; i++) {
                final int id = i;
                pool.submit(() -> {
                    try {
                        ready.await();
                        ChatResult r = client.chat(ChatRequest.simple("t" + id, 32), null, null);
                        results.add(r.content());
                    } catch (Throwable t) {
                        errors.add(t);
                    }
                });
            }
            ready.countDown();
            pool.shutdown();
            check("pool finished", pool.awaitTermination(20, TimeUnit.SECONDS));
            checkEquals("no concurrent errors " + errors, 0, errors.size());
            checkEquals("all results", threads, results.size());
            boolean allEchoed = true;
            for (int i = 0; i < threads; i++) {
                allEchoed &= results.contains("echo:t" + i);
            }
            check("each thread got its own answer", allEchoed);
            ok("one client instance serves " + threads + " concurrent calls");
        }
    }

    // ------------------------------------------------------------------ generate transport

    private static void testGenerateHappyPath() throws Exception {
        section("generate transport: NDJSON happy path");
        List<Captured> captures = new CopyOnWriteArrayList<>();
        FakeServer server = serve("/alpha/generate", (exchange, body) -> {
            List<String> lines = List.of(
                    "{\"type\":\"start\"}",
                    "{\"type\":\"start-step\",\"request\":{},\"warnings\":[]}",
                    "{\"type\":\"reasoning-delta\",\"id\":\"r\",\"text\":\"pondering\"}",
                    "{\"type\":\"text-start\",\"id\":\"t\"}",
                    "{\"type\":\"text-delta\",\"id\":\"t\",\"text\":\"{\\\"ok\\\":\"}",
                    "{\"type\":\"text-delta\",\"id\":\"t\",\"text\":\"true}\"}",
                    "{\"type\":\"text-end\",\"id\":\"t\"}",
                    "{\"type\":\"finish-step\",\"finishReason\":\"stop\",\"usage\":{\"inputTokens\":100,"
                            + "\"outputTokens\":20,\"cachedInputTokens\":40,\"reasoningTokens\":5}}",
                    "{\"type\":\"finish\",\"finishReason\":\"stop\",\"totalUsage\":{\"inputTokens\":100,"
                            + "\"outputTokens\":20,\"reasoningTokens\":5,\"cachedInputTokens\":40}}",
                    "{\"type\":\"provider-metadata\",\"providerMetadata\":{}}");
            exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            for (String l : lines) {
                out.write((l + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        }, captures);
        try (server) {
            CommandCodeGenerateClient client = new CommandCodeGenerateClient(server.url(), "k",
                    () -> "9.9.9", Duration.ofSeconds(5), Duration.ofSeconds(20));
            AtomicInteger progress = new AtomicInteger();
            ChatRequest req = new ChatRequest("deepseek/deepseek-v4.1-flash",
                    List.of(ChatMessage.system("you build things"), ChatMessage.user("make json")),
                    512, 0.3, "high", true);
            ChatResult r = client.chat(req, (c, rr) -> progress.incrementAndGet(), null);
            checkEquals("content", "{\"ok\":true}", r.content());
            checkEquals("reasoning", "pondering", r.reasoning());
            checkEquals("finish", "stop", r.finishReason());
            checkEquals("prompt tokens", 100, r.promptTokens());
            checkEquals("completion tokens", 20, r.completionTokens());
            checkEquals("cached tokens", 40, r.cachedTokens());
            checkEquals("reasoning tokens", 5, r.reasoningTokens());
            checkEquals("transport", "generate", r.transport());
            checkEquals("name", "generate", client.name());
            check("progress fired", progress.get() >= 1);

            Captured cap = captures.get(0);
            JsonObject sent = LlmJson.parseObject(cap.body());
            check("body parses", sent != null);
            check("config present", LlmJson.obj(sent, "config") != null);
            checkEquals("workingDir", "/", LlmJson.string(LlmJson.obj(sent, "config"), "workingDir"));
            checkEquals("permissionMode", "standard", LlmJson.string(sent, "permissionMode"));
            JsonObject params = LlmJson.obj(sent, "params");
            checkEquals("param model", "deepseek/deepseek-v4.1-flash", LlmJson.string(params, "model"));
            check("system hoisted", LlmJson.string(params, "system").contains("you build things"));
            check("json instruction appended", LlmJson.string(params, "system").contains("JSON"));
            check("tools empty", LlmJson.array(params, "tools").isEmpty());
            checkEquals("params.stream", Boolean.TRUE.toString(),
                    String.valueOf(params.get("stream").getAsBoolean()));
            checkEquals("params.max_tokens", 512, LlmJson.integer(params, "max_tokens"));
            checkEquals("params.reasoning_effort", "high", LlmJson.string(params, "reasoning_effort"));
            com.google.gson.JsonArray msgs = LlmJson.array(params, "messages");
            checkEquals("only the user message is in params.messages", 1, msgs.size());
            JsonObject firstMsg = msgs.get(0).getAsJsonObject();
            checkEquals("message role", "user", LlmJson.string(firstMsg, "role"));
            checkEquals("typed content part", "text",
                    LlmJson.string(firstMsg.getAsJsonArray("content").get(0).getAsJsonObject(), "type"));
            checkEquals("cli version header", "9.9.9", cap.header("x-command-code-version"));
            checkEquals("cli environment header", "production", cap.header("x-cli-environment"));
            check("session id header is a uuid",
                    cap.header("x-session-id") != null && cap.header("x-session-id").length() == 36);
            ok("generate NDJSON parsing and envelope verified");
        }
    }

    private static void testGenerateReasoningEffortRetry() throws Exception {
        section("generate transport: retry without reasoning_effort on HTTP 400");
        List<Captured> captures = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        FakeServer server = serve("/alpha/generate", (exchange, body) -> {
            int n = calls.incrementAndGet();
            if (body.contains("reasoning_effort")) {
                byte[] payload = ("{\"success\":false,\"error\":{\"code\":\"BAD_REQUEST\",\"status\":400,"
                        + "\"message\":\"Unknown parameter: reasoning_effort\"}}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(400, payload.length);
                exchange.getResponseBody().write(payload);
                return;
            }
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            out.write(("{\"type\":\"text-delta\",\"text\":\"fine\"}\n"
                    + "{\"type\":\"finish\",\"finishReason\":\"stop\",\"totalUsage\":{\"inputTokens\":5,"
                    + "\"outputTokens\":1}}\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }, captures);
        try (server) {
            CommandCodeGenerateClient client = new CommandCodeGenerateClient(server.url(), "k",
                    () -> "1.0.0", Duration.ofSeconds(5), Duration.ofSeconds(10));
            ChatResult r = client.chat(ChatRequest.simple("hi", 64).withReasoningEffort("max"), null, null);
            checkEquals("content after retry", "fine", r.content());
            checkEquals("two requests", 2, calls.get());
            checkEquals("second request omits reasoning_effort", false, captures.get(1).body().contains("reasoning_effort"));
            ok("400 mentioning reasoning_effort retried once without it");
        }
    }

    private static void testGenerateErrorEvent() throws Exception {
        section("generate transport: error event and empty content");
        FakeServer server = serve("/alpha/generate", (exchange, body) -> {
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            out.write("{\"type\":\"error\",\"error\":{\"message\":\"model unavailable\",\"code\":\"model_error\"}}\n"
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
        }, null);
        try (server) {
            CommandCodeGenerateClient client = new CommandCodeGenerateClient(server.url(), "k",
                    () -> "1.0.0", Duration.ofSeconds(5), Duration.ofSeconds(10));
            try {
                client.chat(ChatRequest.simple("hi", 64), null, null);
                check("error event must throw", false);
            } catch (LlmException e) {
                check("message mentions model unavailable", e.getMessage().contains("model unavailable"));
                checkEquals("code preserved", "model_error", e.code());
            }
            ok("NDJSON error events become LlmExceptions");
        }
    }

    private static void testGenerateIdleTimeout() throws Exception {
        section("generate transport: idle timeout");
        FakeServer server = serve("/alpha/generate", (exchange, body) -> {
            exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            out.write("{\"type\":\"start\"}\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            Thread.sleep(4000);
        }, null);
        try (server) {
            CommandCodeGenerateClient client = new CommandCodeGenerateClient(server.url(), "k",
                    () -> "1.0.0", Duration.ofSeconds(5), Duration.ofMillis(500));
            long started = System.nanoTime();
            try {
                client.chat(ChatRequest.simple("hi", 64), null, null);
                check("generate idle timeout must throw", false);
            } catch (LlmException e) {
                long ms = (System.nanoTime() - started) / 1_000_000L;
                checkEquals("generate idle code", "idle_timeout", e.code());
                check("generate idle retryable", e.retryable());
                check("generate idle fired quickly (" + ms + " ms)", ms < 3000);
            }
            ok("generate transport honours the idle timeout");
        }
    }

    private static void testVersionHelper() throws Exception {
        section("cli version helper");
        String realVersion = CommandCodeGenerateClient.fetchLatestCliVersion(null, null);
        check("live npm lookup returns a dotted version (" + realVersion + ")",
                realVersion.matches("\\d+\\.\\d+\\.\\d+.*"));
        checkEquals("blank url yields fallback", "9.9.9",
                CommandCodeGenerateClient.fetchCliVersion(null, "  ", "9.9.9"));

        FakeServer npm = serve("/command-code/latest", (exchange, body) -> {
            byte[] payload = "{\"name\":\"command-code\",\"version\":\"9.8.7\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
        }, null);
        try (npm) {
            checkEquals("parses the registry version", "9.8.7",
                    CommandCodeGenerateClient.fetchCliVersion(null, npm.url() + "/command-code/latest", "0.0.0"));
        }
        checkEquals("non-200 yields fallback", "5.0.0",
                CommandCodeGenerateClient.fetchCliVersion(null, "http://127.0.0.1:1/nope", "5.0.0"));
        checkEquals("garbage json yields fallback", "6.0.0",
                CommandCodeGenerateClient.fetchCliVersion(null, "http://127.0.0.1:1/x", "6.0.0"));

        CommandCodeGenerateClient c = new CommandCodeGenerateClient("https://api.commandcode.ai", "k",
                () -> null, Duration.ofSeconds(1), Duration.ofSeconds(1));
        checkEquals("null supplier falls back", CommandCodeGenerateClient.FALLBACK_CLI_VERSION,
                c.describe().get("cliVersion"));
        CommandCodeGenerateClient noisy = new CommandCodeGenerateClient("https://api.commandcode.ai", "k",
                () -> "  1.88.2 \u00e9 ", Duration.ofSeconds(1), Duration.ofSeconds(1));
        checkEquals("version sanitised", "1.88.2", noisy.describe().get("cliVersion"));
        CommandCodeGenerateClient broken = new CommandCodeGenerateClient("https://api.commandcode.ai", "k",
                () -> {
                    throw new IllegalStateException("boom");
                }, Duration.ofSeconds(1), Duration.ofSeconds(1));
        checkEquals("throwing supplier survives", CommandCodeGenerateClient.FALLBACK_CLI_VERSION,
                broken.describe().get("cliVersion"));
        ok("CLI version resolution is defensive");
    }

    // ------------------------------------------------------------------ router

    /** Scripted client used to exercise the router without any I/O. */
    private static final class ScriptedClient implements LlmClient {
        private final String name;
        private final List<Object> script = new CopyOnWriteArrayList<>();
        final AtomicInteger calls = new AtomicInteger();
        final List<Long> callTimes = new CopyOnWriteArrayList<>();

        ScriptedClient(String name) {
            this.name = name;
        }

        ScriptedClient then(Object outcome) {
            script.add(outcome);
            return this;
        }

        @Override
        public ChatResult chat(ChatRequest req, StreamListener listener, AtomicBoolean cancelled) throws LlmException {
            calls.incrementAndGet();
            callTimes.add(System.nanoTime());
            if (cancelled != null && cancelled.get()) {
                throw LlmException.cancelled();
            }
            if (script.isEmpty()) {
                throw new IllegalStateException("no scripted outcome left for " + name);
            }
            Object next = script.remove(0);
            if (next instanceof LlmException e) {
                throw e;
            }
            return (ChatResult) next;
        }

        @Override
        public String name() {
            return name;
        }
    }

    private static ChatResult okResult(String transport, String content) {
        return new ChatResult(content, "", "stop", 10, 5, 0, 0, 1, transport);
    }

    private static void testRouterSwapToGenerate() {
        section("router: auto mode falls back to generate on upgrade_required");
        ScriptedClient provider = new ScriptedClient("provider")
                .then(LlmException.http(403, "Your plan doesn't include API access.", null));
        ScriptedClient generate = new ScriptedClient("generate")
                .then(okResult("generate", "from generate"))
                .then(okResult("generate", "from generate"));
        LlmRouter router = new LlmRouter(provider, generate, "auto", 2, LOG);
        try {
            ChatResult r = router.chat(ChatRequest.simple("hi", 32), null, null);
            checkEquals("content from generate", "from generate", r.content());
            checkEquals("transport field", "generate", r.transport());
            check("router switched", router.isUsingGenerate());
            checkEquals("provider tried once", 1, provider.calls.get());
            checkEquals("generate tried once", 1, generate.calls.get());
            ChatResult r2 = router.chat(ChatRequest.simple("hi", 32), null, null);
            checkEquals("sticky: second call goes straight to generate", "from generate", r2.content());
            checkEquals("provider not called again", 1, provider.calls.get());
            checkEquals("generate called again", 2, generate.calls.get());
            router.resetTransport();
            check("reset re-enables provider", !router.isUsingGenerate());
            ok("auto mode swaps transport permanently on 403");
        } catch (LlmException e) {
            check("router swap threw " + e, false);
        }

        ScriptedClient provider2 = new ScriptedClient("provider")
                .then(new LlmException("no api for you", 200, "upgrade_required"));
        ScriptedClient generate2 = new ScriptedClient("generate").then(okResult("generate", "swapped"));
        LlmRouter router2 = new LlmRouter(provider2, generate2, "auto", 1, LOG);
        try {
            ChatResult r = router2.chat(ChatRequest.simple("hi", 32), null, null);
            checkEquals("upgrade_required code also swaps", "swapped", r.content());
        } catch (LlmException e) {
            check("code-based swap threw " + e, false);
        }
    }

    private static void testRouterRetryBackoff() {
        section("router: retries retryable errors with backoff");
        ScriptedClient provider = new ScriptedClient("provider")
                .then(LlmException.http(429, "slow down", "rate_limited"))
                .then(okResult("provider", "second time lucky"));
        LlmRouter router = new LlmRouter(provider, null, "auto", 2, LOG);
        long started = System.nanoTime();
        try {
            ChatResult r = router.chat(ChatRequest.simple("hi", 32), null, null);
            long ms = (System.nanoTime() - started) / 1_000_000L;
            checkEquals("content", "second time lucky", r.content());
            checkEquals("two attempts", 2, provider.calls.get());
            check("waited at least the 2 s backoff (was " + ms + " ms)", ms >= 1900);
            check("did not wait longer than 6 s", ms < 6000);
        } catch (LlmException e) {
            check("retry succeeded unexpectedly failed: " + e, false);
        }

        ScriptedClient alwaysFails = new ScriptedClient("provider")
                .then(LlmException.http(500, "boom", null))
                .then(LlmException.http(500, "boom", null))
                .then(LlmException.http(500, "boom", null));
        LlmRouter router2 = new LlmRouter(alwaysFails, null, "auto", 1, LOG);
        try {
            router2.chat(ChatRequest.simple("hi", 32), null, null);
            check("exhausted retries must throw", false);
        } catch (LlmException e) {
            checkEquals("original error surfaced", 500, e.status());
            checkEquals("attempts capped", 2, alwaysFails.calls.get());
        }

        ScriptedClient emptyClient = new ScriptedClient("provider")
                .then(okResult("provider", "   "))
                .then(okResult("provider", "finally"));
        LlmRouter router3 = new LlmRouter(emptyClient, null, "auto", 1, LOG);
        try {
            ChatResult r = router3.chat(ChatRequest.simple("hi", 32), null, null);
            checkEquals("empty content retried", "finally", r.content());
        } catch (LlmException e) {
            check("empty-content retry failed: " + e, false);
        }

        ScriptedClient fatal = new ScriptedClient("provider").then(LlmException.http(401, "bad key", null));
        LlmRouter router4 = new LlmRouter(fatal, null, "auto", 3, LOG);
        try {
            router4.chat(ChatRequest.simple("hi", 32), null, null);
            check("401 must throw", false);
        } catch (LlmException e) {
            checkEquals("401 not retried", 1, fatal.calls.get());
        }
        checkEquals("backoff ladder 1", 2000L, backoffOf(1));
        checkEquals("backoff ladder 2", 5000L, backoffOf(2));
        checkEquals("backoff ladder 3", 12000L, backoffOf(3));
        checkEquals("backoff ladder 4", 30000L, backoffOf(4));
        checkEquals("backoff capped", 30000L, backoffOf(9));
        ok("retry policy, backoff ladder and give-up behaviour verified");
    }

    private static long backoffOf(int retry) {
        try {
            var m = LlmRouter.class.getDeclaredMethod("backoffMillis", int.class);
            m.setAccessible(true);
            return (long) m.invoke(null, retry);
        } catch (ReflectiveOperationException e) {
            return -1;
        }
    }

    private static void testRouterPinnedTransports() {
        section("router: pinned transports");
        ScriptedClient provider = new ScriptedClient("provider")
                .then(LlmException.http(403, "no access", "upgrade_required"));
        ScriptedClient generate = new ScriptedClient("generate").then(okResult("generate", "gen"));
        LlmRouter pinnedProvider = new LlmRouter(provider, generate, "provider", 0, LOG);
        try {
            pinnedProvider.chat(ChatRequest.simple("hi", 32), null, null);
            check("pinned provider must surface the error", false);
        } catch (LlmException e) {
            checkEquals("pinned provider keeps error", 403, e.status());
            checkEquals("generate untouched", 0, generate.calls.get());
        }
        ScriptedClient provider2 = new ScriptedClient("provider");
        ScriptedClient generate2 = new ScriptedClient("generate").then(okResult("generate", "gen"));
        LlmRouter pinnedGenerate = new LlmRouter(provider2, generate2, "generate", 0, LOG);
        try {
            ChatResult r = pinnedGenerate.chat(ChatRequest.simple("hi", 32), null, null);
            checkEquals("pinned generate result", "gen", r.content());
            checkEquals("provider untouched", 0, provider2.calls.get());
            checkEquals("active transport", "generate", pinnedGenerate.activeTransport());
        } catch (LlmException e) {
            check("pinned generate threw " + e, false);
        }
        LlmRouter unbounded = new LlmRouter(provider2, generate2, "auto", 99, LOG);
        checkEquals("retries clamped", 10, unbounded.maxRetries());
        try {
            new LlmRouter(null, null, "auto", 0, LOG);
            check("router without transports rejected", false);
        } catch (IllegalArgumentException expected) {
            check("router without transports rejected", true);
        }
        try {
            new LlmRouter(null, generate2, "provider", 0, LOG);
            check("missing pinned transport rejected", false);
        } catch (IllegalArgumentException expected) {
            check("missing pinned transport rejected", true);
        }
        LlmRouter built = LlmRouter.builder().provider(provider2).generate(generate2).transport("auto")
                .maxRetries(1).logger(LOG).build();
        checkEquals("builder transport", "auto", built.transport());
        ok("pinned modes and validation behave");
    }

    private static void testRouterFallbackFailure() {
        section("router: failing fallback is not retried as an upgrade");
        ScriptedClient provider = new ScriptedClient("provider")
                .then(LlmException.http(403, "Your plan doesn't include API access.", "upgrade_required"));
        ScriptedClient generate = new ScriptedClient("generate")
                .then(LlmException.http(403, "still no access", "upgrade_required"));
        LlmRouter router = new LlmRouter(provider, generate, "auto", 5, LOG);
        try {
            router.chat(ChatRequest.simple("hi", 32), null, null);
            check("failing fallback must throw", false);
        } catch (LlmException e) {
            checkEquals("second 403 surfaced", 403, e.status());
            checkEquals("provider tried once", 1, provider.calls.get());
            checkEquals("generate tried once (no retry loop)", 1, generate.calls.get());
        }
        ok("a failing fallback surfaces immediately instead of looping");
    }

    private static void testRouterCancellationDuringBackoff() throws Exception {
        section("router: cancellation interrupts the backoff sleep");
        ScriptedClient provider = new ScriptedClient("provider")
                .then(LlmException.http(503, "unavailable", null))
                .then(okResult("provider", "never reached"));
        LlmRouter router = new LlmRouter(provider, null, "auto", 3, LOG);
        AtomicBoolean cancelled = new AtomicBoolean();
        long[] cancelAt = new long[1];
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
                return;
            }
            cancelAt[0] = System.nanoTime();
            cancelled.set(true);
        }, "router-cancel");
        t.start();
        long started = System.nanoTime();
        try {
            router.chat(ChatRequest.simple("hi", 32), null, cancelled);
            check("cancelled router must throw", false);
        } catch (LlmException e) {
            long ms = (System.nanoTime() - cancelAt[0]) / 1_000_000L;
            checkEquals("cancelled code", "cancelled", e.code());
            check("cancellation honoured within 1 s (was " + ms + " ms)", ms < 1000);
            check("only one provider attempt", provider.calls.get() == 1);
        }
        t.join(2000);
        check("whole call aborted fast", (System.nanoTime() - started) / 1_000_000L < 2000);
        ok("cancellation aborts backoff immediately");
    }
}
