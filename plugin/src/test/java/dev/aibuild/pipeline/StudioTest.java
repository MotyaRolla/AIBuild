package dev.aibuild.pipeline;

import dev.aibuild.ai.ChatMessage;
import dev.aibuild.analysis.AnalyzerOptions;
import dev.aibuild.model.ShapeResolver;
import dev.aibuild.model.StubBlockInfo;
import dev.aibuild.script.ScriptResult;
import dev.aibuild.script.ScriptRuntime;
import dev.aibuild.script.Stdlib;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** The studio: the protocol a model answers in, and a session driven by scripted answers instead of a model. */
class StudioTest {
    // ------------------------------------------------------------------ protocol

    @Test void aWholeScriptIsTheLargestJsBlock() {
        var t = StudioProtocol.parse("I start with the masses.\n```js\npart('a', () => { box(0,1,0,3,3,3,'stone'); });\n```\n");
        assertNotNull(t.script());
        assertTrue(t.script().contains("part('a'"));
        assertTrue(t.hunks().isEmpty());
        assertFalse(t.done());
    }

    @Test void hunksWinOverTheFenceAroundThem() {
        var t = StudioProtocol.parse("""
                Raise the wall.
                ```js
                <<<<<<< SEARCH
                box(0,1,0,3,3,3,'stone');
                =======
                box(0,1,0,3,5,3,'stone');
                >>>>>>> REPLACE
                ```
                """);
        assertNull(t.script());
        assertEquals(1, t.hunks().size());
        assertTrue(t.changes());
    }

    @Test void toolsAndCommandsAreReadTogether() {
        var t = StudioProtocol.parse("""
                Looking closer.
                ```look
                [{"part":"keep","side":"sw"}, {"eye":"south"}]
                ```
                ```inspect
                {"box":[0,1,0,4,4,4]}
                ```
                ```guide
                walls
                roofs
                ```
                ```delegate
                {"part":"garden","brief":"paths and flower drifts"}
                ```
                REVERT 2
                FINISH
                ACCEPT d1
                DISCARD d2
                WAIT
                DONE 3
                """);
        assertEquals(2, t.looks().size());
        assertEquals(1, t.inspects().size());
        assertEquals(List.of("walls", "roofs"), t.guides());
        assertEquals("garden", t.delegates().getFirst().part());
        assertEquals(2, t.revert());
        assertTrue(t.finish());
        assertEquals(List.of(1), t.accepts());
        assertEquals(List.of(2), t.discards());
        assertTrue(t.waitHelpers());
        assertTrue(t.done());
        assertEquals(3, t.doneVersion());
        assertFalse(t.changes(), "a JSON fence is not a script");
    }

    @Test void commandWordsInsideCodeAreNotCommands() {
        var t = StudioProtocol.parse("```js\n// DONE\npart('a', () => { block(0,1,0,'stone'); });\n```");
        assertFalse(t.done());
        assertNotNull(t.script());
    }

    @Test void badToolBlocksBecomeProblemsTheReplyNames() {
        var t = StudioProtocol.parse("```look\n{part: keep\n```\n```inspect\n{\"box\":[1,2]}\n```\nREVERT\n");
        assertEquals(3, t.problems().size(), t.problems().toString());
        assertTrue(t.acts() || !t.problems().isEmpty());
    }

    @Test void pickKeepAndPreferAreRead() {
        var t = StudioProtocol.parse("Hipped reads better.\nPICK roof=hipped, bays=5\nKEEP 2 4\nPREFER B\n");
        assertEquals(2, t.picks().size());
        assertArrayEquals(new String[]{"roof", "hipped"}, t.picks().getFirst());
        assertArrayEquals(new String[]{"bays", "5"}, t.picks().get(1));
        assertEquals(List.of(2, 4), t.keeps());
        assertEquals('B', t.prefer());
        assertTrue(t.acts());
        assertFalse(StudioProtocol.parse("I prefer A over B").prefer() != null, "only a command line counts");
    }

    // ------------------------------------------------------------------ session

    /** A host whose "model" answers from a list, recording what it was sent. */
    static class Scripted implements StudioSession.Host {
        final Deque<String> answers;
        final Deque<String> helperAnswers = new ArrayDeque<>();
        final List<List<ChatMessage>> sent = new ArrayList<>();
        final StubBlockInfo info = new StubBlockInfo();
        final ExecutorService pool;

        Scripted(ExecutorService pool, String... answers) {
            this.answers = new ArrayDeque<>(List.of(answers));
            this.pool = pool;
        }

        public synchronized String call(List<ChatMessage> msgs, String tag) {
            if (tag.contains("-d")) return helperAnswers.isEmpty() ? "DONE" : helperAnswers.poll();
            sent.add(msgs);
            return answers.isEmpty() ? "DONE" : answers.poll();
        }

        public void prepare(ScriptResult r) {
            ShapeResolver.resolve(r.model(), info);
        }

        public AnalyzerOptions options(ScriptResult r) {
            return new AnalyzerOptions(false);
        }

        public ExecutorService pool() {
            return pool;
        }

        String lastReply() {
            List<ChatMessage> m = sent.getLast();
            return m.getLast().content();
        }
    }

    private static StudioSession session(Scripted host, StudioSession.Settings s) {
        ScriptRuntime rt = new ScriptRuntime(host.info, Stdlib.V2);
        return new StudioSession(host, host.info, rt, 5, null, ScriptRuntime.Budget.standard(), s, "SYSTEM", "START", "a small house");
    }

    private static StudioSession.Settings settings() {
        StudioSession.Settings s = new StudioSession.Settings();
        s.eyes = false;          // renders are tested elsewhere; here the loop
        return s;
    }

    private static final String HOUSE = "```js\npart('house', () => { box(0,1,0,6,4,6,'stone_bricks',{walls:true}); });\n```";

    @Test void writeFailFixLookDoneShipsTheVersionTheModelNamed() throws Exception {
        Scripted host = new Scripted(null,
                HOUSE,
                "```js\npart('house', () => { box(0,1,0,6,4,6,'stone_bricks',{walls:true}); undefinedCall(); });\n```",
                """
                Fix: higher walls.
                <<<<<<< SEARCH
                box(0,1,0,6,4,6,'stone_bricks',{walls:true});
                =======
                box(0,1,0,6,6,6,'stone_bricks',{walls:true});
                >>>>>>> REPLACE
                """,
                "```inspect\n{\"box\":[0,5,0,6,6,6]}\n```",
                "Version 1 had the better proportions.\nDONE 1");
        StudioSession.Outcome o = session(host, settings()).run(null);
        assertTrue(o.done());
        assertEquals(2, o.versions().size(), "the failed run made no version");
        assertEquals(1, o.shipped().n(), "the model chose version 1");
        assertEquals(5, o.turns());
        String afterFail = host.sent.get(2).getLast().content();
        assertTrue(afterFail.contains("The script failed") && afterFail.contains("version 1 stays current"), afterFail);
        String afterInspect = host.sent.get(4).getLast().content();
        assertTrue(afterInspect.contains("inspect x 0..6 y 5..6"), afterInspect);
        assertEquals("Version 1 had the better proportions. DONE 1", o.diagnosis());
    }

    @Test void theReplyCarriesTheRunTheAnalysisAndTheClock() throws Exception {
        Scripted host = new Scripted(null, HOUSE, "DONE");
        session(host, settings()).run(null);
        String reply = host.sent.get(1).getLast().content();
        assertTrue(reply.contains("VERSION 1 (whole script)"), reply);
        assertTrue(reply.contains("PARTS:"), reply);
        assertTrue(reply.contains("ANALYSIS (information for you; nothing here is enforced)"), reply);
        assertTrue(reply.contains("No time limit; turn 1 of at most 40"), reply);
    }

    @Test void theDeadlineShipsTheCurrentVersion() throws Exception {
        AtomicLong now = new AtomicLong(0);
        StudioSession.Settings s = settings();
        s.clock = now::get;
        s.deadlineNanos = 1000;
        Scripted host = new Scripted(null, HOUSE, HOUSE.replace("6,4,6", "6,5,6"), HOUSE) {
            @Override public synchronized String call(List<ChatMessage> msgs, String tag) {
                now.addAndGet(600);   // each call takes "600 ns" of the 1000 available
                return super.call(msgs, tag);
            }
        };
        StudioSession.Outcome o = session(host, s).run(null);
        assertFalse(o.done());
        assertEquals("time is up", o.reason());
        assertEquals(2, o.shipped().n());
    }

    @Test void revertMakesAnOlderVersionCurrentAndDoneTakesIt() throws Exception {
        Scripted host = new Scripted(null, HOUSE, HOUSE.replace("6,4,6", "6,8,6"), "REVERT 1", "DONE");
        StudioSession.Outcome o = session(host, settings()).run(null);
        assertEquals(1, o.shipped().n());
        assertTrue(host.sent.get(3).getLast().content().contains("Reverted: version 1 is current again"));
    }

    @Test void doneInTheSameAnswerAsAFailedChangeIsNotTaken() throws Exception {
        Scripted host = new Scripted(null, HOUSE, "```js\npart('house', () => { nope(); });\n```\nDONE", "DONE");
        StudioSession.Outcome o = session(host, settings()).run(null);
        assertEquals(3, o.turns());
        assertEquals(1, o.shipped().n());
        assertTrue(host.sent.get(2).getLast().content().contains("DONE was not taken"));
    }

    @Test void anEditStartsFromTheStoredScriptAsVersionOne() throws Exception {
        Scripted host = new Scripted(null, "DONE");
        StudioSession.Outcome o = session(host, settings()).run(HOUSE.substring(6, HOUSE.length() - 4));
        assertEquals(1, o.shipped().n());
        assertTrue(host.sent.getFirst().get(1).content().contains("VERSION 1 (the build as it is now)"));
    }

    @Test void aHelpersPartGoesInOnlyOnAccept() throws Exception {
        ExecutorService pool = Executors.newCachedThreadPool();
        try {
            String two = "```js\npart('house', () => { box(0,1,0,6,4,6,'stone_bricks',{walls:true}); });\n"
                    + "part('garden', () => { box(8,1,0,9,1,1,'oak_leaves'); });\n```";
            // the lead: build, delegate, wait, accept, done; the helper: its part (plus an edit outside it), done
            Scripted host = new Scripted(pool,
                    two,
                    "```delegate\n{\"part\":\"garden\",\"brief\":\"more\"}\n```",
                    "WAIT",
                    "ACCEPT d1",
                    "DONE");
            host.helperAnswers.add("```js\npart('garden', () => { box(8,1,0,12,2,3,'oak_leaves'); });\npart('house', () => { box(0,1,0,6,9,6,'stone'); });\n```");
            host.helperAnswers.add("DONE");
            StudioSession.Settings s = settings();
            StudioSession.Outcome o = session(host, s).run(null);
            assertTrue(o.done(), o.reason());
            String src = o.shipped().source();
            assertTrue(src.contains("box(8,1,0,12,2,3,'oak_leaves')"), "the helper's part is in: " + src);
            assertTrue(src.contains("box(0,1,0,6,4,6,'stone_bricks'"), "the helper's other edits never reach the lead: " + src);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test void oldTurnsAreShortenedButTheCurrentScriptIsRestated() throws Exception {
        StudioSession.Settings s = settings();
        s.contextTokens = 50;   // force compaction from the start
        String pad = "// " + "a long comment that makes the script big enough to be elided ".repeat(10) + "\n";
        String big = HOUSE.replace("```js\n", "```js\n" + pad);
        Scripted host = new Scripted(null, big, big.replace("6,4,6", "6,5,6"), big.replace("6,4,6", "6,7,6"), "DONE");
        session(host, s).run(null);
        List<ChatMessage> last = host.sent.getLast();
        String all = String.join("\n", last.stream().map(ChatMessage::content).toList());
        assertTrue(all.contains("CURRENT SCRIPT, version 3"), all);
        assertTrue(all.contains("[code omitted]"), "old code is elided");
        assertFalse(all.contains("6,4,6"), "the first script is gone from the conversation");
    }

    private static final String OPTION_HOUSE = """
            ```js
            part('house', () => {
                const h = option('height', [4, 7]);
                box(0, 1, 0, 6, h, 6, 'stone_bricks', {walls: true});
            });
            ```""";

    @Test void everyChoiceOfANewOptionIsBuiltSideBySideAndPickWritesItIn() throws Exception {
        StudioSession.Settings s = settings();
        s.eyes = true;
        s.imageSize = 480;
        Scripted host = new Scripted(null, OPTION_HOUSE, "The taller one.\nPICK height=7", "DONE");
        StudioSession.Outcome o = session(host, s).run(null);
        String first = host.sent.get(1).getLast().content();
        assertTrue(first.contains("OPTIONS:") && first.contains("height = 4 (open"), first);
        assertTrue(first.contains("option height: every choice built"), first);
        assertTrue(first.contains("option choices side by side"), first);
        String src = o.shipped().source();
        assertTrue(src.startsWith("decide({\"height\":7}); // decided in the studio\n"), src);
        assertEquals(7, o.shipped().result().model().solidBounds()[4], "the picked choice is built");
        String second = host.sent.get(2).getLast().content();
        assertTrue(second.contains("height = 7 (decided)"), second);
        assertFalse(second.contains("every choice built"), "a decided option is not built again");
        // the stored script reproduces without the studio
        ScriptResult again = new ScriptRuntime(host.info, Stdlib.V2).run(src, 5, null, null, ScriptRuntime.Budget.standard(), null);
        assertEquals(o.shipped().result().model().nonAirCount(), again.model().nonAirCount());
    }

    @Test void theRedPenIsInTheReplyAndKeepSilencesAMark() throws Exception {
        String floating = "```js\npart('house', () => { box(0,1,0,6,4,6,'stone_bricks',{walls:true}); box(1,9,1,4,9,1,'hay_block'); });\n```";
        String changed = floating.replace("6,4,6", "6,5,6");
        Scripted host = new Scripted(null, floating, "It is a sign hanging on nothing on purpose.\nKEEP 1", changed, "DONE");
        session(host, settings()).run(null);
        String first = host.sent.get(1).getLast().content();
        assertTrue(first.contains("RED PEN") && first.contains("1. floating: 4 blocks (hay_block x4)"), first);
        assertTrue(host.sent.get(2).getLast().content().contains("Mark 1 (floating) is kept as intended"));
        String later = host.sent.get(3).getLast().content();
        assertFalse(later.contains("floating: 4 blocks"), "a kept mark is not marked again: " + later);
    }

    @Test void beforeShippingTheModelComparesBlindAndItsChoiceShips() throws Exception {
        StudioSession.Settings s = settings();
        s.eyes = true;
        s.imageSize = 360;
        // seed 5, shipping version 4: A is the version from three changes earlier, B the one being shipped
        Scripted host = new Scripted(null, HOUSE, HOUSE.replace("6,4,6", "6,5,6"), HOUSE.replace("6,4,6", "6,6,6"),
                HOUSE.replace("6,4,6", "6,8,6"), "DONE", "PREFER B");
        StudioSession.Outcome o = session(host, s).run(null);
        assertTrue(host.sent.get(5).getLast().content().contains("Before this ships: a blind comparison"));
        assertTrue(o.done());
        assertEquals(4, o.shipped().n());
        assertEquals(6, o.turns());
    }

    @Test void preferringTheEarlierVersionIsReportedAndTheModelDecides() throws Exception {
        StudioSession.Settings s = settings();
        s.eyes = true;
        s.imageSize = 360;
        Scripted host = new Scripted(null, HOUSE, HOUSE.replace("6,4,6", "6,5,6"), HOUSE.replace("6,4,6", "6,6,6"),
                HOUSE.replace("6,4,6", "6,8,6"), "DONE", "PREFER A", "DONE 1");
        StudioSession.Outcome o = session(host, s).run(null);
        String reply = host.sent.get(6).getLast().content();
        assertTrue(reply.contains("You preferred version 1, from before your last changes, over version 4"), reply);
        assertEquals(1, o.shipped().n());
    }

    private static long imageBytes(List<ChatMessage> msgs) {
        long n = 0;
        for (ChatMessage m : msgs) if (m.contentParts() != null) for (byte[] b : m.contentParts().images()) n += b.length;
        return n;
    }

    @Test void oldImagesAreLeftOutSoARequestStaysUnderTheProvidersLimit() throws Exception {
        StudioSession.Settings s = settings();
        s.eyes = true;
        s.imageSize = 360;
        // a provider that refuses big bodies, as the gateway does at 32 MB; the session's own budget is larger
        long limit = 120_000;
        s.imageBytes = 400_000;
        List<Long> sizes = new ArrayList<>();
        int[] refused = {0};
        Scripted host = new Scripted(null, HOUSE, HOUSE.replace("6,4,6", "6,5,6"), HOUSE.replace("6,4,6", "6,6,6"),
                HOUSE.replace("6,4,6", "6,7,6"), HOUSE.replace("6,4,6", "6,8,6"), "DONE", "PREFER B") {
            @Override public synchronized String call(List<ChatMessage> msgs, String tag) {
                long b = imageBytes(msgs);
                if (b > limit) {
                    refused[0]++;
                    throw new RuntimeException(dev.aibuild.ai.LlmException.http(413, "Request body is too large", "request_too_large"));
                }
                sizes.add(b);
                return super.call(msgs, tag);
            }
        };
        // the 413 arrives wrapped, as the pipeline's call wrappers may do
        StudioSession.Outcome o = session(host, s).run(null);
        assertTrue(o.done(), o.reason());
        assertEquals(5, o.shipped().n());
        assertTrue(sizes.stream().allMatch(b -> b <= limit), sizes.toString());
        assertTrue(refused[0] >= 1, "the limit was reached at least once");
        String text = host.sent.getLast().stream().map(ChatMessage::content).reduce("", String::concat);
        assertTrue(text.contains("the images of this turn were left out"), text);
        assertTrue(imageBytes(host.sent.getLast()) > 0, "the newest turns keep their images");
    }

    @Test void aFailedModelCallShipsTheVersionsAlreadyBuilt() throws Exception {
        Scripted host = new Scripted(null, HOUSE, HOUSE.replace("6,4,6", "6,5,6")) {
            @Override public synchronized String call(List<ChatMessage> msgs, String tag) {
                if (sent.size() == 2) throw new IllegalStateException(dev.aibuild.ai.LlmException.http(400, "bad request", "invalid"));
                return super.call(msgs, tag);
            }
        };
        StudioSession.Outcome o = session(host, settings()).run(null);
        assertFalse(o.done());
        assertEquals(2, o.shipped().n());
        assertTrue(o.reason().startsWith("the model call failed"), o.reason());
    }

    @Test void everyKindOfViewRenders() throws Exception {
        StudioSession.Settings s = settings();
        s.eyes = true;
        s.imageSize = 480;
        Scripted host = new Scripted(null, HOUSE, """
                ```look
                [{"part":"house","side":"nw"}, {"eye":"south"}, {"from":[20,10,20],"at":[3,2,3]}, {"top":true}]
                ```
                ```look
                [{"cut":2}, {"elevation":"east"}, {"box":[0,1,0,3,3,3]}, {"rooms":true}]
                ```
                """, "DONE");
        session(host, s).run(null);
        List<ChatMessage> afterLook = host.sent.get(2);
        String reply = afterLook.getLast().content();
        assertTrue(reply.contains("eye level from the south"), reply);
        assertTrue(reply.contains("cut at y=2"), reply);
        assertFalse(reply.contains("look {"), "no view failed: " + reply);
        assertNotNull(afterLook.getLast().contentParts(), "the images went with the reply");
    }
}
