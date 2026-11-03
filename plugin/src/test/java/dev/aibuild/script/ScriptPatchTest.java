package dev.aibuild.script;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScriptPatchTest {
    private static final String SRC = """
            const W = 10;
            part('body', () => {
              box(0, 1, 0, W, 5, 8, 'oak_planks', {walls: true});
            });
            part('roof', () => {
              roof(0, 0, W, 8, 6, {type: 'gable', m: 'spruce_planks'});
            });
            """;

    @Test void appliesExactAndReindentedHunks() {
        String answer = """
                DIAGNOSIS: the roof is too plain.
                ```
                <<<<<<< SEARCH
                const W = 10;
                =======
                const W = 14;
                >>>>>>> REPLACE
                <<<<<<< SEARCH
                    roof(0, 0, W, 8, 6, {type: 'gable', m: 'spruce_planks'});
                =======
                  roof(0, 0, W, 8, 6, {type: 'gable', m: 'deepslate_tiles', overhang: 2});
                  chimney(2, 2, 6, 14);
                >>>>>>> REPLACE
                ```
                """;
        ScriptPatch.Result r = ScriptPatch.apply(SRC, answer);
        assertTrue(r.ok(), r.error());
        assertEquals(2, r.hunks());
        assertTrue(r.source().contains("const W = 14;"));
        assertTrue(r.source().contains("deepslate_tiles"));
        assertTrue(r.source().contains("chimney(2, 2, 6, 14);"));
        assertFalse(r.source().contains("spruce_planks"));
    }

    @Test void rejectsMissingOrAmbiguousHunks() {
        ScriptPatch.Result missing = ScriptPatch.apply(SRC, "<<<<<<< SEARCH\nbox(1,1,1)\n=======\nx\n>>>>>>> REPLACE");
        assertFalse(missing.ok());
        assertTrue(missing.error().contains("not found"));
        ScriptPatch.Result twice = ScriptPatch.apply(SRC, "<<<<<<< SEARCH\n});\n=======\n}); // end\n>>>>>>> REPLACE");
        assertFalse(twice.ok());
        assertTrue(twice.error().contains("more than once"));
        assertEquals(SRC, twice.source(), "a failed revision leaves the script unchanged");
    }

    @Test void acceptsFullRewrite() {
        ScriptPatch.Result r = ScriptPatch.apply(SRC, "Rewritten:\n```js\nbox(0,1,0,3,3,3,'stone');\n```");
        assertTrue(r.ok());
        assertTrue(r.fullRewrite());
        assertEquals("box(0,1,0,3,3,3,'stone');\n", r.source());
    }

    @Test void numbersLines() {
        assertTrue(ScriptPatch.numbered(SRC).startsWith("   1| const W = 10;\n   2| part"));
    }
}
