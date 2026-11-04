package dev.aibuild.pipeline;

import dev.aibuild.model.StubBlockInfo;
import dev.aibuild.script.Canvas;
import dev.aibuild.script.ScriptPatch;
import dev.aibuild.script.ScriptResult;
import dev.aibuild.script.ScriptRuntime;
import dev.aibuild.script.Stdlib;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** When a confined rewrite is kept with its stray cells dropped, and when it goes back for a fix. */
class SkippedWritesCheck {
    private final ScriptRuntime runtime = new ScriptRuntime(new StubBlockInfo(), Stdlib.V2);

    /** A wall 40 long, and a detail part that writes {@code own} blocks of its own and {@code stray} holes in the wall. */
    private Canvas.Confinement run(int own, int stray) {
        String src = "part('wall', () => { box(0, 1, 0, 39, 4, 0, 'stone_bricks'); });\n"
                + "part('trim', () => {\n"
                + "  for (let i = 0; i < " + own + "; i++) block(i % 40, 1 + Math.floor(i / 40), -1, 'stone_brick_slab');\n"
                + "  for (let i = 0; i < " + stray + "; i++) block(i % 40, 1 + Math.floor(i / 40), 0, 'air');\n"
                + "});\n";
        ScriptResult r = runtime.run(ScriptPatch.confine(src, "trim", Canvas.OTHERS), 1, null, null, ScriptRuntime.Budget.standard(), null);
        assertTrue(r.ok(), () -> r.error().describe());
        return r.confinement("trim");
    }

    @Test void aFewStrayCellsAreDroppedAndTheWorkIsKept() {
        assertFalse(BuildPipeline.tooManySkipped(run(30, 2)), "2 stray cells of 32 writes");
        assertFalse(BuildPipeline.tooManySkipped(run(100, 12)), "12 cells is the old neighbour tolerance");
    }

    @Test void aRewriteWithPiecesMissingGoesBack() {
        // 20 of 120 writes skipped: well under a quarter, yet a whole run of trim would be missing
        assertTrue(BuildPipeline.tooManySkipped(run(100, 20)));
    }

    @Test void aLargePassToleratesThreePercent() {
        assertFalse(BuildPipeline.tooManySkipped(run(150, 4)));
        Canvas.Confinement big = run(150, 6);
        assertEquals(6, big.dropped);
        assertFalse(BuildPipeline.tooManySkipped(big));
    }
}
