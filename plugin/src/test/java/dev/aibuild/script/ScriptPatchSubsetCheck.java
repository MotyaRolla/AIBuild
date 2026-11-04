package dev.aibuild.script;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for applying a revision's hunks one at a time.
 *
 * <p>Live defect: a critic answer fixed a ladder and a loft in one hunk and a gable in another; the gable
 * hunk darkened the interior, the wholesale rejection threw away the ladder fix too, and the build stayed a
 * draft. Hunks are written against the same current source, so a subset applied in order is the same
 * revision minus the dropped hunks — these tests pin that behaviour.</p>
 */
class ScriptPatchSubsetCheck {

    private static final String SEARCH_A = "place(a)";
    private static final String SEARCH_B = "place(b)";

    @Test
    void hunksOfReadsThePairsInOrder() {
        String answer = """
                DIAGNOSIS: 1. fix A 2. fix B
                <<<<<<< SEARCH
                place(a)
                =======
                place(fixedA)
                >>>>>>> REPLACE
                <<<<<<< SEARCH
                place(b)
                =======
                place(fixedB)
                >>>>>>> REPLACE
                """;
        List<String[]> hunks = ScriptPatch.hunksOf(answer);
        assertEquals(2, hunks.size());
        assertEquals(SEARCH_A, hunks.get(0)[0].strip());
        assertEquals("place(fixedA)", hunks.get(1 - 1)[1].strip());
        assertEquals(SEARCH_B, hunks.get(1)[0].strip());
    }

    @Test
    void anAnswerWithoutHunksYieldsNone() {
        assertTrue(ScriptPatch.hunksOf("no hunks here").isEmpty());
        assertTrue(ScriptPatch.hunksOf(null).isEmpty());
    }

    @Test
    void aSubsetKeepsOnlyTheAskedHunks() {
        String source = "part('x', () => {\n  place(a);\n  place(b);\n});\n";
        String answer = """
                <<<<<<< SEARCH
                  place(a);
                =======
                  place(A1);
                >>>>>>> REPLACE
                <<<<<<< SEARCH
                  place(b);
                =======
                  place(B1);
                >>>>>>> REPLACE
                """;
        List<String[]> hunks = ScriptPatch.hunksOf(answer);
        ScriptPatch.Result onlyFirst = ScriptPatch.applyHunks(source, java.util.List.<String[]>of(hunks.get(0)));
        assertTrue(onlyFirst.ok());
        assertTrue(onlyFirst.source().contains("place(A1);"));
        assertTrue(!onlyFirst.source().contains("place(B1);"), "the second hunk is not applied");
        ScriptPatch.Result both = ScriptPatch.applyHunks(source, hunks);
        assertTrue(both.source().contains("place(A1);"));
        assertTrue(both.source().contains("place(B1);"));
    }

    @Test
    void aNonMatchingHunkIsSkippedNotFatal() {
        String source = "part('x', () => {\n  place(a);\n});\n";
        List<String[]> hunks = List.of(
                new String[]{"place(ghost);", "place(nope);"},
                new String[]{"place(a);", "place(fixed);"});
        ScriptPatch.Result r = ScriptPatch.applyHunks(source, hunks);
        assertTrue(r.ok(), "a missing SEARCH skips the hunk instead of failing the list");
        assertEquals(1, r.hunks());
        assertTrue(r.source().contains("place(fixed);"));
        assertTrue(!r.source().contains("place(nope);"));
    }

    @Test
    void aLooseHunkIsSkippedToo() {
        String source = "part('x', () => {\n  place(a);\n});\n";
        List<String[]> hunks = java.util.List.<String[]>of(new String[]{"  place(a);\n", "  place(A2);\n"});
        ScriptPatch.Result r = ScriptPatch.applyHunks(source, hunks);
        assertTrue(r.ok());
        assertTrue(r.source().contains("place(A2);"));
    }

    @Test
    void strictModeKeepsTheOldFailureBehaviour() {
        String source = "part('x', () => {\n  place(a);\n});\n";
        List<String[]> hunks = java.util.List.<String[]>of(new String[]{"place(missing);", "place(x);"});
        ScriptPatch.Result r = ScriptPatch.applyHunks(source, hunks, true);
        assertEquals(source, r.source(), "strict mode leaves the source untouched on failure");
        assertTrue(r.error() != null && r.error().contains("not found"));
    }
}
