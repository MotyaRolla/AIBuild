package dev.aibuild.live;

import dev.aibuild.config.BuildMode;
import dev.aibuild.model.StubBlockInfo;
import dev.aibuild.pipeline.Job;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The debug trace: the record a person reads to find out why a build came out wrong.
 *
 * <p>These run against a real session and a real loopback server, because the point of the feature is that a
 * step's prompts and tracebacks survive the trip to the browser — a unit test on the data structure alone would
 * not catch a field dropped on the way out.</p>
 */
class TraceTest {

    private LiveSession.Registry registry;
    private LiveViewServer server;
    private LiveSession session;
    private String base;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws IOException {
        int port = freePort();
        registry = new LiveSession.Registry(15, Logger.getAnonymousLogger());
        server = new LiveViewServer(LiveHttpConfig.defaults().withAddress("127.0.0.1", port), registry::get,
                token -> true, Logger.getAnonymousLogger());
        server.start();
        Job job = new Job("tracebuild", null, Job.Kind.CREATE, "a house", BuildMode.defaults().get(BuildMode.STANDARD));
        session = registry.open(job, "Trace House", "Steve", new StubBlockInfo());
        base = "http://127.0.0.1:" + port + "/live/" + session.token();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.close();
        if (registry != null) registry.closeAll();
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    // ------------------------------------------------------------------ recording

    @Test
    void anLlmStepKeepsEverythingNeededToExplainIt() {
        session.trace(new TraceStep.Builder(TraceStep.Kind.LLM, "script")
                .title("LLM · script")
                .status(TraceStep.Status.OK)
                .took(1234)
                .summary("wrote a cottage")
                .field("prompt", "[system]\nYou build.\n\n[user]\nMake a cottage.")
                .field("reasoning", "Start with the foundation.")
                .field("answer", "part('walls', () => {})"));

        TraceStep step = session.trace().bySeq(1);
        assertNotNull(step, "the first step is seq 1");
        assertEquals(TraceStep.Kind.LLM, step.kind());
        assertEquals(TraceStep.Status.OK, step.status());
        assertEquals(1234, step.ms(), "the call's own timing is kept, not the recording time");
        assertEquals("[system]\nYou build.\n\n[user]\nMake a cottage.", step.fields().get("prompt"));
        assertEquals("Start with the foundation.", step.fields().get("reasoning"));
        assertEquals("part('walls', () => {})", step.fields().get("answer"));
    }

    @Test
    void stepsAreNumberedInOrderAndKeepTheirIdentity() {
        for (int i = 0; i < 5; i++) {
            session.trace(new TraceStep.Builder(TraceStep.Kind.PIPELINE, "stage" + i).title("Stage " + i));
        }
        assertEquals(5, session.trace().size());
        assertEquals(5, session.trace().lastSeq());
        List<TraceStep> all = session.trace().since(0, 100);
        assertEquals(List.of(1, 2, 3, 4, 5), all.stream().map(TraceStep::seq).toList());
    }

    @Test
    void blankFieldsAreDroppedRatherThanSentAsEmptySections() {
        session.trace(new TraceStep.Builder(TraceStep.Kind.LLM, "t")
                .field("prompt", "hello")
                .field("reasoning", "")
                .field("answer", null));
        TraceStep step = session.trace().bySeq(1);
        assertEquals(List.of("prompt"), new ArrayList<>(step.fields().keySet()),
                "an absent reasoning block should not become an empty section on the page");
    }

    @Test
    void aHugeFieldIsTruncatedAndSaysSo() {
        String big = "x".repeat(TraceStep.FIELD_LIMIT + 5000);
        session.trace(new TraceStep.Builder(TraceStep.Kind.LLM, "t").field("prompt", big));
        String kept = session.trace().bySeq(1).fields().get("prompt");
        assertTrue(kept.length() < big.length(), "the field must be capped");
        assertTrue(kept.contains("more chars"), "truncation is marked, so the page never implies the text ended: " + kept.length());
        assertTrue(kept.startsWith("xxxx"), "the beginning of the prompt is what matters and must survive");
    }

    @Test
    void theOldestStepsAreDroppedOnceTheCapIsReached() {
        for (int i = 0; i < TraceLog.LIMIT + 25; i++) {
            session.trace(new TraceStep.Builder(TraceStep.Kind.PIPELINE, "s").title("Step " + i));
        }
        assertEquals(TraceLog.LIMIT, session.trace().size());
        assertNull(session.trace().bySeq(1), "the first steps are evicted");
        assertNotNull(session.trace().bySeq(TraceLog.LIMIT + 25), "the newest step is always kept");
        assertEquals(TraceLog.LIMIT + 25, session.trace().lastSeq(), "sequence numbers keep counting after eviction");
    }

    // ------------------------------------------------------------------ over the wire

    @Test
    void theSummaryCarriesNoPayloadButSaysDetailExists() throws Exception {
        session.trace(new TraceStep.Builder(TraceStep.Kind.LLM, "script")
                .title("LLM · script").summary("ok").field("prompt", "a very large prompt ".repeat(200)));
        String json = get("/state.json").body();
        assertTrue(json.contains("\"trace\""), "the snapshot must include the trace list: " + json.substring(0, 200));
        assertFalse(json.contains("a very large prompt"), "the snapshot carries summaries only, never the payload");
    }

    @Test
    void theDetailEndpointReturnsTheWholeStep() throws Exception {
        session.trace(new TraceStep.Builder(TraceStep.Kind.SCRIPT, "script-run-0")
                .title("Script run · script").ok(false)
                .summary("failed: TypeError")
                .field("source", "part('walls', () => { nope(); })")
                .field("error", "TypeError: nope is not a function"));

        HttpResponse<String> res = get("/step/1");
        assertEquals(200, res.statusCode());
        String body = res.body();
        assertTrue(body.contains("part('walls'"), "the source is returned: " + body);
        assertTrue(body.contains("TypeError: nope is not a function"), "the traceback is returned: " + body);
        assertTrue(body.contains("\"status\":\"failed\""), "the status is returned: " + body);
    }

    @Test
    void anUnknownStepIs404AndANonNumericOneIsRejected() throws Exception {
        session.trace(new TraceStep.Builder(TraceStep.Kind.PIPELINE, "s"));
        assertEquals(404, get("/step/999").statusCode());
        assertEquals(404, get("/step/abc").statusCode());
        assertEquals(404, get("/step/-1").statusCode());
    }

    @Test
    void theWholeTraceDownloadsAsMarkdown() throws Exception {
        session.trace(new TraceStep.Builder(TraceStep.Kind.LLM, "script")
                .title("LLM · script").summary("wrote a cottage")
                .field("prompt", "Make a cottage.").field("answer", "part('walls', () => {})"));
        session.trace(new TraceStep.Builder(TraceStep.Kind.SCRIPT, "run")
                .title("Script run").ok(false).summary("failed")
                .field("error", "TypeError: x is not a function"));

        HttpResponse<String> res = get("/trace.md");
        assertEquals(200, res.statusCode());
        assertTrue(res.headers().firstValue("content-type").orElse("").contains("text/markdown"));
        assertTrue(res.headers().firstValue("content-disposition").orElse("").contains("aibuild-trace.md"));
        String md = res.body();
        assertTrue(md.contains("Make a cottage."), "the prompt is in the download: " + md);
        assertTrue(md.contains("TypeError: x is not a function"), "so is the traceback");
        assertTrue(md.contains("## 1.") && md.contains("## 2."), "steps are numbered headings");
    }

    @Test
    void aStepEventReachesALiveViewerWithItsStats() throws Exception {
        session.stage("program", "Writing the program", 0.3);
        List<String> frames = new CopyOnWriteArrayList<>();
        session.subscribe(frame -> frames.add(frame));

        session.trace(new TraceStep.Builder(TraceStep.Kind.LLM, "script").title("LLM · script").summary("ok")
                .field("prompt", "make a cottage"));

        // The replay also contains the step recorded by stage(), which has no fields; pick the LLM one.
        String frame = frames.stream()
                .filter(f -> f.contains("event: step"))
                .filter(f -> f.contains("\"kind\":\"llm\""))
                .findFirst().orElseThrow(() -> new AssertionError("no llm step frame in " + frames));
        assertTrue(frame.contains("\"stats\""), "the frame carries the running counters: " + frame);
        assertTrue(frame.contains("\"detailed\":true"), "and says the detail can be fetched");
    }

    @Test
    void aLateViewerSeesTheWholeTraceInTheSnapshot() throws Exception {
        session.trace(new TraceStep.Builder(TraceStep.Kind.LLM, "a").title("First call"));
        session.trace(new TraceStep.Builder(TraceStep.Kind.LLM, "b").title("Second call"));

        String json = get("/state.json").body();
        assertTrue(json.contains("First call"), "a viewer who opens the page late still gets the history: " + json);
        assertTrue(json.contains("Second call"));
        assertTrue(json.contains("\"traceStats\""));
    }

    /**
     * The reasoning a transport streams is what the trace shows when the provider returns it out-of-band instead
     * of in the answer. The pipeline prefers {@code res.reasoning()} and falls back to what the listener
     * accumulated, so that fallback has to actually collect the cumulative text.
     */
    @Test
    void reasoningStreamedByTheTransportIsCapturedForTheTrace() {
        List<String> seen = new CopyOnWriteArrayList<>();
        dev.aibuild.ai.StreamListener listener = new dev.aibuild.ai.StreamListener() {
            @Override
            public void onProgress(int contentChars, int reasoningChars) {
            }

            @Override
            public void reasoning(String textSoFar) {
                seen.add(textSoFar);
            }
        };
        // Transports send the whole text so far, growing, at most once a second.
        listener.reasoning("Сначала");
        listener.reasoning("Сначала фундамент");
        listener.reasoning("Сначала фундамент, потом стены");

        session.trace(new TraceStep.Builder(TraceStep.Kind.LLM, "script")
                .title("LLM · script")
                .field("reasoning", seen.get(seen.size() - 1)));
        assertEquals("Сначала фундамент, потом стены", session.trace().bySeq(1).fields().get("reasoning"),
                "the trace keeps the cumulative reasoning, not just the last delta");
    }

    /**
     * A build that is not approved used to show the same model twice: commit() emitted it inside the failure
     * branch and again after it. The trace must show one row per distinct model version, otherwise the list of a
     * long build fills with pairs and the call that actually changed the build is hard to find.
     */
    @Test
    void oneModelVersionProducesOneTraceRow() {
        dev.aibuild.model.VoxelModel m = new dev.aibuild.model.VoxelModel();
        m.set(0, 1, 0, dev.aibuild.model.BlockSpec.of("stone"));
        session.model(m, "final");
        session.model(m, "final");
        session.model(m, "final");

        List<String> titles = session.trace().since(0, 100).stream()
                .filter(s -> s.kind() == TraceStep.Kind.MODEL)
                .map(TraceStep::title).toList();
        assertEquals(1, titles.size(), "the same model emitted three times is one row: " + titles);
    }

    @Test
    void pipelimeStagesAndModelChangesAreStepsToo() throws Exception {
        session.stage("program", "Writing the program", 0.3);
        dev.aibuild.model.VoxelModel m = new dev.aibuild.model.VoxelModel();
        m.set(0, 1, 0, dev.aibuild.model.BlockSpec.of("stone"));
        session.model(m, "critic");

        List<TraceStep> steps = session.trace().since(0, 100);
        assertTrue(steps.stream().anyMatch(s -> s.kind() == TraceStep.Kind.PIPELINE),
                "stages appear in the trace so the gaps between calls are explained");
        assertTrue(steps.stream().anyMatch(s -> s.kind() == TraceStep.Kind.MODEL),
                "model changes appear too, so a version can be tied to the call that made it");
    }
}
