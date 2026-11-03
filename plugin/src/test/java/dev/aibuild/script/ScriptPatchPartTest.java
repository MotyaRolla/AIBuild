package dev.aibuild.script;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScriptPatchPartTest {
    private static final String SRC = """
            const H = 6; // part('fake', in a comment)
            part('base', () => { box(0, 1, 0, 4, 1, 4, 'stone'); });
            part('tower', () => {
              const s = "part('tower')) in a string";
              box(0, 2, 0, 0, H, 0, 'stone_bricks'); /* ) */
              part('tower/roof', () => block(0, H + 1, 0, 'oak_planks'));
            });
            log('done');
            """;

    @Test void findsPartsOutsideCommentsAndStrings() {
        assertEquals(List.of("base", "tower", "tower/roof"), ScriptLint.partNames(SRC));
        String tower = ScriptPatch.partSource(SRC, "tower");
        assertTrue(tower.startsWith("part('tower'") && tower.endsWith("});"), tower);
        assertTrue(tower.contains("tower/roof"), "nested parts belong to their parent");
    }

    @Test void replacesOnePartAndKeepsTheRest() {
        String answer = "Here it is:\n```js\nconst junk = 1;\npart('tower', () => {\n  box(0, 2, 0, 1, H + 4, 1, 'deepslate_bricks');\n});\n```";
        ScriptPatch.Result r = ScriptPatch.replacePart(SRC, "tower", answer);
        assertTrue(r.ok(), r.error());
        assertTrue(r.source().contains("deepslate_bricks") && !r.source().contains("stone_bricks"));
        assertFalse(r.source().contains("junk"), "top-level helpers of the answer are dropped");
        assertTrue(r.source().contains("part('base'") && r.source().contains("log('done')"));
        ScriptResult run = new ScriptRuntime(new dev.aibuild.model.StubBlockInfo()).run(r.source(), 1, null, null, ScriptRuntime.Budget.standard(), null);
        assertTrue(run.ok(), () -> run.error().describe());
    }

    @Test void rejectsRewritesOfAnotherPart() {
        assertFalse(ScriptPatch.replacePart(SRC, "tower", "```js\npart('base', () => {});\n```").ok());
        assertFalse(ScriptPatch.replacePart(SRC, "wall", "```js\npart('wall', () => {});\n```").ok());
    }
}
