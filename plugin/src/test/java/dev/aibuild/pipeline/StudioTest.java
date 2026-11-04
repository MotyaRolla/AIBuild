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

        /** The blind judge's answers, by call; "SAME" when none are left. */
        final Deque<String> judgeAnswers = new ArrayDeque<>();
        final List<String> judgeTags = new ArrayList<>();

        public synchronized String judge(List<ChatMessage> msgs, String tag) {
            judgeTags.add(tag);
            return judgeAnswers.isEmpty() ? "SAME\nno difference" : judgeAnswers.poll();
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
        s.verify = false;        // the blind check has its own tests
        return s;
    }

    private static final String HOUSE = "```js\npart('house', () => { box(0,1,0,6,4,6,'stone_bricks',{walls:true}); });\n```";

    @Test void writeFailFixLookDoneShipsTheVersionTheModelNamed() throws Exception {
        Scripted host = new Scripted(null,
                HOUSE,
                "```js\npart('house', () => { box(0,1,0,6,6,6,'stone_bricks',{walls:true}); undefinedCall(); });\n```",
                """
                Fix of my draft: drop the stray call.
                <<<<<<< SEARCH
                 undefinedCall();
                =======
                >>>>>>> REPLACE
                """,
                "```inspect\n{\"box\":[0,5,0,6,6,6]}\n```",
                "Version 1 had the better proportions.\nDONE 1");
        StudioSession.Outcome o = session(host, settings()).run(null);
        assertTrue(o.done());
        assertEquals(2, o.versions().size(), "the failed run made no version, its fix did");
        assertTrue(o.versions().get(1).source().contains("6,6,6"), "the fix applied to the draft, not to version 1");
        assertEquals(1, o.versions().get(1).parent());
        assertEquals(1, o.shipped().n(), "the model chose version 1");
        assertEquals(5, o.turns());
        String afterFail = host.sent.get(2).getLast().content();
        assertTrue(afterFail.contains("The script failed") && afterFail.contains("version 1 stays current"), afterFail);
        assertTrue(afterFail.contains("DRAFT: your latest edit does not run") && afterFail.contains("undefinedCall"), afterFail);
        String afterInspect = host.sent.get(4).getLast().content();
        assertTrue(afterInspect.contains("inspect x 0..6 y 5..6"), afterInspect);
        assertEquals("Version 1 had the better proportions. DONE 1", o.diagnosis());
    }

    @Test void theReplyCarriesTheRunTheAnalysisTheStateAndTheClock() throws Exception {
        Scripted host = new Scripted(null, "Walls first.\n```notes\nINTENT: a 7x7 stone hut\n```\n" + HOUSE, "DONE");
        session(host, settings()).run(null);
        String reply = host.sent.get(1).getLast().content();
        assertTrue(reply.contains("VERSION 1 (whole script)"), reply);
        assertTrue(reply.contains("PARTS:"), reply);
        assertTrue(reply.contains("ANALYSIS: "), reply);
        assertTrue(reply.contains("INTENT: a 7x7 stone hut"), "the notes come back: " + reply);
        assertTrue(reply.contains("v1 whole script:") && reply.contains("You: Walls first."), "the journal: " + reply);
        assertTrue(reply.contains("THE SCRIPT OF VERSION 1") && reply.contains("box(0,1,0,6,4,6"), reply);
        assertTrue(reply.contains("No time limit; turn 2 of at most 40"), reply);
        assertFalse(reply.contains("Walkable zones"), "the spatial report only on REPORT");
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

    @Test void onlyTheLastAnswerIsSentAndTheCurrentScriptOnce() throws Exception {
        String pad = "// " + "a long comment that makes the script big enough to be elided ".repeat(10) + "\n";
        String big = HOUSE.replace("```js\n", "```js\n" + pad);
        Scripted host = new Scripted(null, big, big.replace("6,4,6", "6,5,6"), big.replace("6,4,6", "6,7,6"), "DONE");
        session(host, settings()).run(null);
        List<ChatMessage> last = host.sent.getLast();
        assertEquals(4, last.size(), "system, opening, the last answer, the reply");
        String all = String.join("\n", last.stream().map(ChatMessage::content).toList());
        assertTrue(all.contains("THE SCRIPT OF VERSION 3"), all);
        assertTrue(all.contains("[code omitted: the script is shown below]"), "the answer's code is elided");
        assertFalse(all.contains("6,4,6"), "the first script is gone from the conversation");
        assertEquals(1, all.split("6,7,6", -1).length - 1, "the current script appears once");
        assertTrue(all.contains("v2 <- v1") && all.contains("v3 <- v2"), "the journal keeps every version: " + all);
    }

    @Test void aBlockOfPartsReplacesThosePartsAndKeepsTheRest() throws Exception {
        String two = "```js\nmeta({title:'t'});\nconst W = 6;\npart('house', () => { box(0,1,0,W,4,W,'stone_bricks',{walls:true}); });\n"
                + "part('garden', () => { box(8,1,0,9,1,1,'oak_leaves'); });\n```";
        Scripted host = new Scripted(null, two, "Fuller garden.\n```js\npart('garden', () => { box(8,1,0,12,2,3,'oak_leaves'); });\n```", "DONE");
        StudioSession.Outcome o = session(host, settings()).run(null);
        String src = o.shipped().source();
        assertEquals(2, o.shipped().n());
        assertTrue(src.contains("const W = 6;") && src.contains("box(0,1,0,W,4,W"), "the rest stays: " + src);
        assertTrue(src.contains("box(8,1,0,12,2,3") && !src.contains("box(8,1,0,9,1,1"), src);
        assertTrue(host.sent.get(2).getLast().content().contains("rewrote part('garden')"));
    }

    @Test void partsOnlyNeedsNothingButPartStatements() {
        assertNotNull(StudioSession.partsOnly("// a\npart('a', () => { block(0,1,0,'stone'); });\npart('b', () => {});"));
        assertNull(StudioSession.partsOnly("meta({});\npart('a', () => {});"), "meta() makes it a whole script");
        assertNull(StudioSession.partsOnly("const k = 2;\npart('a', () => {});"), "other statements make it a whole script");
        assertEquals(1, StudioSession.partsOnly("part('a', () => { part('a/b', () => {}); });").size(), "nested parts belong to theirs");
    }

    @Test void undoGoesBackToTheVersionTheCurrentOneCameFrom() throws Exception {
        Scripted host = new Scripted(null, HOUSE, HOUSE.replace("6,4,6", "6,5,6"), HOUSE.replace("6,4,6", "6,8,6"), "UNDO", "DONE");
        StudioSession.Outcome o = session(host, settings()).run(null);
        assertEquals(2, o.shipped().n());
        assertTrue(host.sent.get(4).getLast().content().contains("-> back to v2"));
    }

    @Test void aChangeThatLosesMostOfAPartIsMeasuredAsAPossibleRegression() throws Exception {
        String full = "```js\npart('house', () => { box(0,1,0,12,8,12,'stone_bricks'); });\n```";
        String gutted = "```js\npart('house', () => { box(0,1,0,12,2,12,'stone_bricks'); });\n```";
        Scripted host = new Scripted(null, full, gutted, "DONE");
        session(host, settings()).run(null);
        String reply = host.sent.get(2).getLast().content();
        assertTrue(reply.contains("CHECK against version 1"), reply);
        assertTrue(reply.contains("POSSIBLE REGRESSION: part house lost"), reply);
        assertTrue(reply.contains("height 8 -> 2"), reply);
    }

    @Test void aRewriteBothJudgmentsPreferBeforeKeepsTheVersionBefore() throws Exception {
        ExecutorService pool = Executors.newCachedThreadPool();
        try {
            StudioSession.Settings s = settings();
            s.eyes = true;
            s.verify = true;
            s.imageSize = 320;
            Scripted host = new Scripted(pool, HOUSE, HOUSE.replace("6,4,6", "9,9,9"), "DONE");
            // first call: A = before; second call (order swapped): B = before
            host.judgeAnswers.add("A\nthe proportions of A are right");
            host.judgeAnswers.add("B\nthe proportions of B are right");
            StudioSession.Outcome o = session(host, s).run(null);
            assertEquals(1, o.shipped().n(), "the version before stays current and ships");
            assertEquals(2, o.versions().size(), "the rewrite is kept as a version");
            assertEquals(1, o.checks());
            assertEquals(1, o.rejected());
            assertTrue(host.sent.get(2).getLast().content().contains("BLIND CHECK") && host.sent.get(2).getLast().content().contains("REVERT 2"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test void aSplitJudgmentKeepsTheNewVersion() throws Exception {
        ExecutorService pool = Executors.newCachedThreadPool();
        try {
            StudioSession.Settings s = settings();
            s.eyes = true;
            s.verify = true;
            s.imageSize = 320;
            Scripted host = new Scripted(pool, HOUSE, HOUSE.replace("6,4,6", "9,9,9"), "DONE");
            host.judgeAnswers.add("A\nA");      // prefers before in one order...
            host.judgeAnswers.add("A\nA");      // ...and the new one in the other: position bias, not a verdict
            StudioSession.Outcome o = session(host, s).run(null);
            assertEquals(2, o.shipped().n());
            assertEquals(0, o.rejected());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test void firstDraftsAreWrittenInParallelAndTheTournamentWinnerIsBuiltOn() throws Exception {
        ExecutorService pool = Executors.newCachedThreadPool();
        try {
            StudioSession.Settings s = settings();
            s.eyes = true;
            s.imageSize = 320;
            s.firstDrafts = 2;
            String other = HOUSE.replace("6,4,6", "6,9,6");
            Scripted host = new Scripted(pool, HOUSE, other, "DONE") {
                @Override public synchronized String call(List<ChatMessage> msgs, String tag) {
                    if (tag.endsWith("-t1x2")) return other;      // the parallel draft
                    if (tag.endsWith("-t1")) return HOUSE;
                    return answers.isEmpty() ? "DONE" : "DONE";
                }
            };
            host.judgeAnswers.add("B\nB is taller and reads better");   // draft 1 = A, draft 2 = B
            host.judgeAnswers.add("A\nA is taller and reads better");   // swapped
            StudioSession.Outcome o = session(host, s).run(null);
            assertEquals(9, o.shipped().result().model().solidBounds()[4], "draft 2 won and is version 1");
            assertTrue(host.judgeTags.stream().allMatch(t -> t.contains("drafts")), host.judgeTags.toString());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test void aDraftThatLooksThingsUpFirstGetsItsAnswersAndStaysInTheRunning() throws Exception {
        ExecutorService pool = Executors.newCachedThreadPool();
        try {
            StudioSession.Settings s = settings();
            s.eyes = true;
            s.imageSize = 320;
            s.firstDrafts = 2;
            String other = HOUSE.replace("6,4,6", "6,9,6");
            List<String> lookPrompts = new java.util.concurrent.CopyOnWriteArrayList<>();
            Scripted host = new Scripted(pool, HOUSE, other, "DONE") {
                @Override public synchronized String call(List<ChatMessage> msgs, String tag) {
                    if (tag.endsWith("-t1x1-look")) {
                        lookPrompts.add(msgs.getLast().content());
                        return other;                              // draft 1 writes its script after the lookups
                    }
                    if (tag.endsWith("-t1x2")) return HOUSE;
                    if (tag.endsWith("-t1")) return "```notes\nINTENT: a tall house\n```\n```guide walls\n```\n```blocks\nstone bricks\n```";
                    return "DONE";
                }
            };
            host.judgeAnswers.add("A\nA is taller");   // draft 1 = A
            host.judgeAnswers.add("B\nB is taller");   // swapped
            StudioSession.Outcome o = session(host, s).run(null);
            assertEquals(1, lookPrompts.size(), "the lookups were answered inside the draft");
            assertTrue(lookPrompts.getFirst().contains("BLOCKS matching 'stone bricks'"), lookPrompts.getFirst());
            assertEquals(9, o.shipped().result().model().solidBounds()[4], "the draft that looked first won and is version 1");
        } finally {
            pool.shutdownNow();
        }
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
        long limit = 40_000;
        s.imageBytes = 200_000;
        List<Long> sizes = new ArrayList<>();
        int[] refused = {0};
        Scripted host = new Scripted(null, HOUSE, HOUSE.replace("6,4,6", "6,5,6"), HOUSE.replace("6,4,6", "6,6,6"),
                HOUSE.replace("6,4,6", "6,7,6"), HOUSE.replace("6,4,6", "6,8,6"), "DONE") {
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
        assertTrue(text.contains("left out to keep the request small"), text);
        assertTrue(imageBytes(host.sent.getLast()) > 0, "the newest turn keeps its first images");
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

    // ------------------------------------------------------------------ nothing an answer asks for is lost

    private static final String SCENE = "```js\nmeta({title:'t'});\n"
            + "part('tower', () => { box(0,1,0,2,6,2,'stone_bricks'); box(0,9,0,2,10,2,'stone_bricks'); });\n"
            + "part('yard', () => { box(5,1,0,9,1,4,'oak_planks'); });\n```";

    @Test void severalPartBlocksAndAHunkInOneAnswerAllApply() throws Exception {
        Scripted host = new Scripted(null, SCENE, """
                Tower, yard and one tweak.
                ```js
                part('tower', () => { box(0,1,0,2,10,2,'stone_bricks'); });
                ```
                ```js
                part('yard', () => { box(5,1,0,9,1,6,'oak_planks'); });
                ```
                ```js
                part('well', () => { box(12,1,0,13,2,1,'cobblestone'); });
                ```
                <<<<<<< SEARCH
                meta({title:'t'});
                =======
                meta({title:'t2'});
                >>>>>>> REPLACE
                """, "DONE");
        StudioSession.Outcome o = session(host, settings()).run(null);
        String src = o.shipped().source();
        assertEquals(2, o.shipped().n());
        assertTrue(src.contains("title:'t2'"), "the hunk applied: " + src);
        assertTrue(src.contains("box(0,1,0,2,10,2") && !src.contains("box(0,9,0"), "the tower block applied: " + src);
        assertTrue(src.contains("box(5,1,0,9,1,6"), "the yard block applied: " + src);
        assertTrue(src.contains("part('well'"), "the new part was added: " + src);
        assertFalse(host.sent.get(2).getLast().content().contains("NOT APPLIED"));
    }

    @Test void aLooseFragmentIsNamedInsteadOfWipingTheBuild() throws Exception {
        Scripted host = new Scripted(null, SCENE, """
                ```js
                part('yard', () => { box(5,1,0,9,1,6,'oak_planks'); });
                ```
                ```js
                function helper(x) { return x + 1; }
                ```
                """, "DONE");
        StudioSession.Outcome o = session(host, settings()).run(null);
        String src = o.shipped().source();
        assertTrue(src.contains("part('tower'") && src.contains("box(5,1,0,9,1,6"), src);
        String reply = host.sent.get(2).getLast().content();
        assertTrue(reply.contains("NOT APPLIED") && reply.contains("function helper"), reply);
    }

    @Test void aWholeScriptWithOtherEditsSaysWhatWasNotApplied() throws Exception {
        Scripted host = new Scripted(null, SCENE, SCENE.replace("9,1,4", "9,1,8") + """

                <<<<<<< SEARCH
                part('yard'
                =======
                part('court'
                >>>>>>> REPLACE
                """, "DONE");
        StudioSession.Outcome o = session(host, settings()).run(null);
        assertTrue(o.shipped().source().contains("9,1,8") && o.shipped().source().contains("part('yard'"));
        assertTrue(host.sent.get(2).getLast().content().contains("NOT APPLIED"), host.sent.get(2).getLast().content());
    }

    @Test void takeBringsBackAPartOfAnotherVersion() throws Exception {
        Scripted host = new Scripted(null, SCENE,
                "```js\npart('tower', () => { box(0,1,0,2,10,2,'stone_bricks'); });\npart('yard', () => { box(5,1,0,6,1,1,'dirt'); });\n```",
                "REVERT 1", "TAKE tower FROM 2", "DONE");
        StudioSession.Outcome o = session(host, settings()).run(null);
        String src = o.shipped().source();
        assertEquals(3, o.shipped().n());
        assertEquals(1, o.shipped().parent());
        assertTrue(src.contains("box(0,1,0,2,10,2"), "the tower of v2: " + src);
        assertTrue(src.contains("box(5,1,0,9,1,4,'oak_planks')"), "the yard of v1 stays: " + src);
        assertTrue(o.shipped().origin().contains("part('tower') from v2"), o.shipped().origin());
    }

    @Test void anOpenFaultStaysListedWithTheVersionThatHadItFixed() throws Exception {
        // v1: the tower's top floats; v2 fixes the tower but is reverted; the fault stays in the state, with the TAKE
        Scripted host = new Scripted(null, SCENE,
                "```js\npart('tower', () => { box(0,1,0,2,10,2,'stone_bricks'); });\n```",
                "REVERT 1", "```look\n{\"top\":true}\n```");
        StudioSession.Outcome o = session(host, settings()).run(null);
        String state = host.sent.get(3).getLast().content();
        assertTrue(state.contains("OPEN FAULTS"), state);
        assertTrue(state.contains("open since v1") && state.contains("part tower"), state);
        assertTrue(state.contains("TAKE tower FROM 2"), state);
    }

    @Test void theJudgesLettersAreToldAsVersions() {
        assertEquals("version 2 buries the tree, while version 1 keeps the lake",
                StudioSession.relabel("Version B buries the tree, while Version A keeps the lake", "version 1", "version 2"));
        assertEquals("A cluttered grove: version 1 reads better than version 2",
                StudioSession.relabel("A cluttered grove: A reads better than B", "version 1", "version 2"));
    }

    @Test void blocksAreLookedUpWithTheirColours() {
        Scripted host = new Scripted(null);
        String r = BlockLookup.lookup("oak", host.info);
        assertTrue(r.contains("oak_planks") && r.contains("#"), r);
        assertTrue(BlockLookup.lookup("no-such-thing-xyz", host.info).contains("none"));
        assertTrue(BlockLookup.name(0xF6B4DA).endsWith("pink"), BlockLookup.name(0xF6B4DA));
        assertEquals("white", BlockLookup.name(0xFAFAFA));
        assertTrue(BlockLookup.name(0x3B2A1A).contains("brown"), BlockLookup.name(0x3B2A1A));
    }

    @Test void theStudioPromptHasNoBlockListButSaysHowToLookThemUp() {
        String sys = ScriptPrompts.studioSystem(Stdlib.V2, "stone, dirt, oak_planks");
        assertFalse(sys.contains("VALID BLOCK IDS"));
        assertTrue(sys.contains("```blocks"));
        assertFalse(sys.contains("## Example:"), "the examples are a handbook chapter now");
        assertNotNull(Handbook.chapter(Stdlib.V2, "examples"));
    }
}
