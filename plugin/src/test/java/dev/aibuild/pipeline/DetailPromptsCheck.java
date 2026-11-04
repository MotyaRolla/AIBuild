package dev.aibuild.pipeline;

import dev.aibuild.script.Stdlib;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The 3.3 prompt switches and the self-check answer. */
class DetailPromptsCheck {

    @Test void theCreatePromptAsksForTheSheetAndOneWriting() {
        String on = ScriptPrompts.create(Stdlib.V2, "a small house", "", null, true, true);
        assertTrue(on.contains("note('...')"), on);
        assertTrue(on.contains("do not draft the script"), on);
        String off = ScriptPrompts.create(Stdlib.V2, "a small house", "", null, false, false);
        assertFalse(off.contains("note('...')"));
        assertFalse(off.contains("do not draft the script"));
        assertEquals(off, ScriptPrompts.create(Stdlib.V2, "a small house", "", null), "the old overload is the 3.2 prompt");
    }

    @Test void keepMeansNoCorrection() {
        assertTrue(BuildPipeline.isKeep("KEEP"));
        assertTrue(BuildPipeline.isKeep("  keep — it matches the brief"));
        assertFalse(BuildPipeline.isKeep("KEEP the gate but:\n```js\npart('gate', () => {});\n```"));
        assertFalse(BuildPipeline.isKeep("```js\npart('gate', () => {});\n```"));
    }

    @Test void theSelfCheckNamesTheSkippedCells() {
        String p = ScriptPrompts.selfCheck("gate", "deeper arch", 3, List.of("(1,2,3) stone_bricks of wall -> air"), "floating 0", "floating 0");
        assertTrue(p.contains("3 of your writes"), p);
        assertTrue(p.contains("part('gate'"), p);
        assertFalse(ScriptPrompts.selfCheck("gate", "x", 0, List.of(), "a", "b").contains("skipped"));
    }
}
