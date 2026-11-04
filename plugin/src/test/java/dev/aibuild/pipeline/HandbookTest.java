package dev.aibuild.pipeline;

import dev.aibuild.model.StubBlockInfo;
import dev.aibuild.script.ScriptResult;
import dev.aibuild.script.ScriptRuntime;
import dev.aibuild.script.Stdlib;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The builder's handbook: every chapter in the contents exists, and every snippet in it runs and builds something. */
class HandbookTest {
    @Test void everyTopicHasAChapter() {
        for (String topic : Handbook.TOPICS.keySet()) {
            String text = Handbook.chapter(Stdlib.V2, topic);
            assertNotNull(text, topic);
            assertTrue(text.startsWith("# HANDBOOK: " + topic), topic);
            assertTrue(Handbook.contents().contains("- " + topic + ":"), topic);
        }
        assertNull(Handbook.chapter(Stdlib.V2, "no-such-topic"));
        assertNotNull(Handbook.chapter(Stdlib.V2, "  Walls "), "topic names are forgiving about case and spaces");
    }

    @Test void everySnippetRunsCleanly() {
        ScriptRuntime rt = new ScriptRuntime(new StubBlockInfo(), Stdlib.V2);
        int snippets = 0;
        for (String topic : Handbook.TOPICS.keySet()) {
            for (String code : Handbook.snippets(Handbook.chapter(Stdlib.V2, topic))) {
                snippets++;
                ScriptResult r = rt.run(code, 11, null, null, ScriptRuntime.Budget.standard(), null);
                assertTrue(r.ok(), () -> topic + ": " + r.error().describe());
                assertTrue(r.model().nonAirCount() > 10, topic + " builds something");
                List<String> fixed = r.warnings().stream().filter(w -> w.startsWith("auto-corrected")).toList();
                assertTrue(fixed.isEmpty(), () -> topic + " teaches only correct names: " + fixed);
            }
        }
        assertTrue(snippets >= 9, "snippets " + snippets);
    }
}
