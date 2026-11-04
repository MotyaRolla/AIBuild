package dev.aibuild.script;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The sculptor's lean context: its own part and the helpers in full, every other part folded to its bounds. */
class FoldPartsTest {
    private static final String SRC = """
            const WALL = 'stone_bricks';
            function post(x, z) { box(x, 1, z, x, 4, z, 'oak_log'); }
            part('land', () => {
                box(-20, 0, -20, 20, 0, 20, 'grass_block');
                box(-20, -1, -20, 20, -1, 20, 'dirt');
            });
            part('castle', () => {
                part('keep', () => {
                    box(0, 1, 0, 9, 9, 9, WALL, {hollow: true});
                });
                part('hall', () => {
                    box(12, 1, 0, 20, 5, 6, WALL, {hollow: true});
                    post(12, 0);
                });
            });
            const k = part('garden', () => { post(-5, -5); return 1; });
            """;

    @Test void theOwnPartStaysWholeAndTheOthersFold() {
        String out = ScriptLint.foldOtherParts(SRC, "keep", Map.of("land", "1681 blocks, x -20..20", "hall", "120 blocks, x 12..20"));
        assertTrue(out.contains(ScriptPatch.partSource(SRC, "keep")), out);
        assertTrue(out.contains("part('land', () => { /* 4 lines not shown; 1681 blocks, x -20..20 */ });"), out);
        assertTrue(out.contains("part('hall', () => { /* 4 lines not shown; 120 blocks, x 12..20 */ });"), out);
        assertTrue(out.contains("const k = part('garden', () => { /* 1 lines not shown */ });"), out);
        assertTrue(out.contains("function post(x, z)"), "top-level helpers stay");
        assertTrue(out.contains("part('castle', () => {"), "the enclosing part stays open");
        assertFalse(out.contains("'dirt'"), "the folded bodies are gone");
        assertEquals(ScriptLint.partNames(SRC), ScriptLint.partNames(out), "every part is still named");
    }

    @Test void anUnknownPartLeavesTheScriptAsItIs() {
        assertEquals(SRC, ScriptLint.foldOtherParts(SRC, "moat", Map.of()));
    }
}
