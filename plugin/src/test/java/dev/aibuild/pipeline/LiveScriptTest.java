package dev.aibuild.pipeline;

import dev.aibuild.model.StubBlockInfo;
import dev.aibuild.script.ScriptLint;
import dev.aibuild.script.ScriptResult;
import dev.aibuild.script.ScriptRuntime;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LiveScriptTest {
    private static final ExecutorService DIRECT = new AbstractExecutorService() {
        public void execute(Runnable r) {
            r.run();
        }

        public void shutdown() {
        }

        public List<Runnable> shutdownNow() {
            return List.of();
        }

        public boolean isShutdown() {
            return false;
        }

        public boolean isTerminated() {
            return false;
        }

        public boolean awaitTermination(long t, TimeUnit u) {
            return true;
        }
    };

    @Test void partialCutsAfterCompleteTopLevelStatements() {
        assertEquals("", ScriptLint.partial("Sure, here is the plan.\n```j").code(), "nothing before the fence line ends");
        ScriptLint.Partial p = ScriptLint.partial("intro\n```js\nmeta({title:'x'});\npart('body', function () {\n  box(0,0,0,");
        assertEquals("meta({title:'x'});\n", p.code(), "an open part() call is not cut into");
        assertEquals("body", p.part());

        String code = "var m = 'a;\\n b';\n/* note ( */ var r = /[)]/;\nvar t = a +\n  b;\nvar s = '";
        assertEquals("var m = 'a;\\n b';\n/* note ( */ var r = /[)]/;\nvar t = a +\n  b;\n", ScriptLint.partial(code).code(),
                "strings, comments and regexes do not count as brackets; a trailing operator continues the line");
        assertEquals("box(0,0,0,1,1,1,'stone');\n", ScriptLint.partial("```js\nbox(0,0,0,1,1,1,'stone');\n```\ntext").code(),
                "the closing fence ends the code");
    }

    @Test void streamedExampleGrowsPartByPartAndEndsAtTheFinalModel() throws Exception {
        String src;
        try (InputStream in = getClass().getResourceAsStream("/stdlib/v2/examples/fox.js")) {
            src = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String answer = "Plan first.\n```js\n" + src + "```\n";
        ScriptRuntime runtime = new ScriptRuntime(new StubBlockInfo());
        List<Integer> shown = new ArrayList<>();
        List<String> parts = new ArrayList<>();
        LiveScript live = new LiveScript(runtime, 7, null, ScriptRuntime.Budget.standard(), null, DIRECT,
                r -> shown.add(r.model().nonAirCount()), parts::add, 0);
        for (int i = 40; i < answer.length() + 40; i += 40) live.onText(answer.substring(0, Math.min(i, answer.length())));

        ScriptResult full = runtime.run(src, 7, null, null, ScriptRuntime.Budget.standard(), null);
        assertTrue(shown.size() >= 3, "several previews while streaming: " + shown);
        for (int i = 1; i < shown.size(); i++) assertTrue(shown.get(i) >= shown.get(i - 1), "the build only grows: " + shown);
        assertEquals(full.model().nonAirCount(), shown.getLast(), "same seed: the last preview is the final build");
        assertEquals(List.of("base", "body", "head", "tail"), parts);

        live.close();
        live.onText(answer + "\nbox(0,40,0,3,40,3,'stone');\n");
        assertEquals(full.model().nonAirCount(), shown.getLast(), "nothing is shown after close()");
    }

    @Test void restartedStreamIsFollowedAgain() {
        ScriptRuntime runtime = new ScriptRuntime(new StubBlockInfo());
        List<Integer> shown = new ArrayList<>();
        LiveScript live = new LiveScript(runtime, 1, null, ScriptRuntime.Budget.standard(), null, DIRECT,
                r -> shown.add(r.model().nonAirCount()), p -> { }, 0);
        live.onText("```js\nbox(0,0,0,4,0,4,'stone');\nbox(0,1,0,4,1,4,'stone');\n");
        live.onText("```js\nbox(0,0,0,1,0,0,'stone');\n"); // transport retry: shorter text
        assertEquals(List.of(50, 2), shown);
    }
}
