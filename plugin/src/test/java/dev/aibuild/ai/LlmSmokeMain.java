package dev.aibuild.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Live smoke test for the LLM client layer against the real Command&nbsp;Code gateway.
 *
 * <p>Reads the API key from {@code /root/ai-build/.cc_key} (the key is never printed), then checks:</p>
 * <ol>
 *   <li>(a) provider transport, streaming, JSON mode, {@code reasoning_effort=high}: a JSON object
 *       {@code {"ok":true,"sum":42}} must come back and a {@link StreamListener} must have fired;</li>
 *   <li>(b) generate transport with a tiny prompt: reported, but a plan/permission failure does not
 *       fail the test;</li>
 *   <li>(c) cancellation: a long generation cancelled after ~3 s must throw {@code cancelled} within
 *       ~2 s;</li>
 *   <li>(d) {@link LlmRouter} in {@code auto} mode must return a usable answer through either
 *       transport.</li>
 * </ol>
 *
 * <p>Prints PASS/FAIL lines, timings and token usage; exits 1 if a mandatory check fails.</p>
 */
public final class LlmSmokeMain {

    private static final Path KEY_FILE = Path.of("/root/ai-build/.cc_key");
    private static final String MODEL = ChatRequest.DEFAULT_MODEL;

    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        Logger.getLogger(LlmRouter.class.getName()).setLevel(Level.ALL);
        ConsoleHandler handler = new ConsoleHandler();
        handler.setLevel(Level.ALL);
        Logger.getLogger(LlmRouter.class.getName()).addHandler(handler);

        String key;
        try {
            key = readKey();
        } catch (IOException e) {
            System.out.println("FAIL cannot read " + KEY_FILE + ": " + e.getMessage());
            System.exit(1);
            return;
        }
        System.out.println("key loaded: " + key.length() + " chars (value never printed)");
        System.out.println("model: " + MODEL);
        System.out.println();

        String cliVersion = CommandCodeGenerateClient.fetchLatestCliVersion(null,
                CommandCodeGenerateClient.FALLBACK_CLI_VERSION);
        System.out.println("command-code CLI version for the generate header: " + cliVersion);
        System.out.println();

        OpenAiCompatClient provider = new OpenAiCompatClient(OpenAiCompatClient.DEFAULT_BASE_URL, key,
                Duration.ofSeconds(15), Duration.ofSeconds(90), Map.of());
        CommandCodeGenerateClient generate = new CommandCodeGenerateClient(
                CommandCodeGenerateClient.DEFAULT_BASE_URL, key, () -> cliVersion,
                Duration.ofSeconds(15), Duration.ofSeconds(90));

        partA(provider);
        partB(generate);
        partC(provider);
        partD(provider, generate);

        System.out.println();
        System.out.println("==================================================");
        if (failures.isEmpty()) {
            System.out.println("SMOKE TEST PASSED (" + passed + " checks)");
        } else {
            System.out.println("SMOKE TEST FAILED (" + failures.size() + " failed of " + (passed + failures.size()) + ")");
            for (String f : failures) {
                System.out.println("  - " + f);
            }
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ parts

    private static void partA(LlmClient provider) {
        System.out.println("-- (a) provider transport: JSON mode + reasoning_effort=high");
        ChatRequest req = new ChatRequest(MODEL,
                List.of(ChatMessage.system("You are a precise assistant. Reply with JSON only."),
                        ChatMessage.user("Return a JSON object with exactly two fields: "
                                + "\"ok\" (boolean true) and \"sum\" (the integer result of 17+25).")),
                256, null, "high", true);
        AtomicInteger callbacks = new AtomicInteger();
        long started = System.nanoTime();
        try {
            ChatResult r = provider.chat(req, (c, rr) -> callbacks.incrementAndGet(), null);
            long ms = (System.nanoTime() - started) / 1_000_000L;
            System.out.println("   " + r.summary());
            System.out.println("   listener callbacks: " + callbacks.get());
            check("(a) listener invoked at least once", callbacks.get() >= 1);
            check("(a) content non-empty", r.hasContent());
            check("(a) transport is provider", "provider".equals(r.transport()));
            JsonObject json = parseJsonObject(r.content());
            check("(a) content parses as a JSON object", json != null);
            if (json != null) {
                check("(a) ok == true", json.has("ok") && json.get("ok").isJsonPrimitive()
                        && json.get("ok").getAsJsonPrimitive().isBoolean() && json.get("ok").getAsBoolean());
                Integer sum = json.has("sum") && json.get("sum").isJsonPrimitive()
                        && json.get("sum").getAsJsonPrimitive().isNumber() ? json.get("sum").getAsInt() : null;
                check("(a) sum == 42 (got " + sum + ")", Integer.valueOf(42).equals(sum));
            }
            System.out.println("   elapsed " + ms + " ms, tokens prompt=" + r.promptTokens()
                    + " completion=" + r.completionTokens() + " cached=" + r.cachedTokens()
                    + " reasoning=" + r.reasoningTokens() + ", finish=" + r.finishReason());
            check("(a) token usage reported", r.totalTokens() > 0);
        } catch (LlmException e) {
            fail("(a) provider call failed: " + e);
        }
        System.out.println();
    }

    private static void partB(LlmClient generate) {
        System.out.println("-- (b) generate transport: tiny prompt (a plan/permission error is acceptable)");
        ChatRequest req = new ChatRequest(MODEL, List.of(ChatMessage.user("Reply with only: OK")),
                32, null, null, false);
        long started = System.nanoTime();
        try {
            ChatResult r = generate.chat(req, null, null);
            long ms = (System.nanoTime() - started) / 1_000_000L;
            System.out.println("   OK: " + r.summary());
            System.out.println("   elapsed " + ms + " ms, tokens prompt=" + r.promptTokens()
                    + " completion=" + r.completionTokens() + " cached=" + r.cachedTokens()
                    + " reasoning=" + r.reasoningTokens() + ", finish=" + r.finishReason());
            System.out.println("   reply: " + LlmJson.oneLine(r.content(), 120));
        } catch (LlmException e) {
            System.out.println("   generate transport unavailable (accepted): " + e);
            System.out.println("   -> status=" + e.status() + " code=" + e.code() + " retryable=" + e.retryable());
        }
        System.out.println();
    }

    private static void partC(LlmClient provider) {
        System.out.println("-- (c) cancellation: long generation cancelled after 3 s");
        ChatRequest req = new ChatRequest(MODEL,
                List.of(ChatMessage.user("Write a 3000 word story about a lighthouse keeper.")),
                1400, null, null, false);
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicInteger progress = new AtomicInteger();
        List<LlmException> errors = new ArrayList<>();
        long[] cancelAt = new long[1];
        Thread worker = new Thread(() -> {
            try {
                ChatResult r = provider.chat(req, (c, rr) -> progress.incrementAndGet(), cancelled);
                System.out.println("   unexpected success: " + r.summary());
            } catch (LlmException e) {
                synchronized (errors) {
                    errors.add(e);
                }
            }
        }, "smoke-cancel-worker");
        long started = System.nanoTime();
        worker.start();
        try {
            Thread.sleep(3000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        cancelAt[0] = System.nanoTime();
        cancelled.set(true);
        try {
            worker.join(10_000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        long reactionMs = (System.nanoTime() - cancelAt[0]) / 1_000_000L;
        long totalMs = (System.nanoTime() - started) / 1_000_000L;
        System.out.println("   worker alive after cancel: " + worker.isAlive());
        System.out.println("   reaction " + reactionMs + " ms, total " + totalMs
                + " ms, progress callbacks " + progress.get());
        LlmException e;
        synchronized (errors) {
            e = errors.isEmpty() ? null : errors.get(0);
        }
        check("(c) exactly one LlmException", errors.size() == 1);
        check("(c) code is cancelled", e != null && "cancelled".equals(e.code()));
        check("(c) not retryable", e != null && !e.retryable());
        check("(c) aborted within 2 s of cancelling (" + reactionMs + " ms)", reactionMs <= 2000);
        // Informational only: whether any delta arrived within 3 s depends on gateway queueing;
        // part (a) already proves the listener contract on a completed call.
        System.out.println("   (info) progress callbacks before cancel: " + progress.get());
        System.out.println();
    }

    private static void partD(LlmClient provider, LlmClient generate) {
        System.out.println("-- (d) LlmRouter in auto mode");
        LlmRouter router = new LlmRouter(provider, generate, "auto", 1,
                Logger.getLogger(LlmRouter.class.getName()));
        ChatRequest req = new ChatRequest(MODEL,
                List.of(ChatMessage.user("Reply with only: ROUTER_OK")), 64, 0.0, null, false);
        long started = System.nanoTime();
        try {
            ChatResult r = router.chat(req, null, null);
            long ms = (System.nanoTime() - started) / 1_000_000L;
            System.out.println("   " + r.summary());
            System.out.println("   elapsed " + ms + " ms, active transport=" + router.activeTransport()
                    + ", stickyGenerate=" + router.isUsingGenerate());
            check("(d) router returned content", r.hasContent());
            check("(d) transport labelled", "provider".equals(r.transport()) || "generate".equals(r.transport()));
            check("(d) answer mentions ROUTER_OK", r.content().toUpperCase().contains("ROUTER")
                    || r.content().toUpperCase().contains("OK"));
        } catch (LlmException e) {
            fail("(d) router call failed: " + e
                    + (router.isUsingGenerate() ? " (after switching to generate)" : ""));
        }
        System.out.println();
    }

    // ------------------------------------------------------------------ helpers

    private static String readKey() throws IOException {
        if (!Files.isReadable(KEY_FILE)) {
            throw new IOException("missing key file");
        }
        String raw = Files.readString(KEY_FILE, StandardCharsets.UTF_8).strip();
        if (raw.isEmpty()) {
            throw new IOException("key file is empty");
        }
        if (raw.startsWith("Bearer ")) {
            raw = raw.substring("Bearer ".length()).strip();
        }
        return raw;
    }

    private static JsonObject parseJsonObject(String text) {
        try {
            String t = text.strip();
            if (t.startsWith("```")) {
                int firstNewline = t.indexOf('\n');
                int lastFence = t.lastIndexOf("```");
                if (firstNewline > 0 && lastFence > firstNewline) {
                    t = t.substring(firstNewline + 1, lastFence).strip();
                }
            }
            var el = JsonParser.parseString(t);
            return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void check(String what, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("   PASS " + what);
        } else {
            fail(what);
        }
    }

    private static void fail(String what) {
        failures.add(what);
        System.out.println("   FAIL " + what);
    }
}
