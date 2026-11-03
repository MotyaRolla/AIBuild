package dev.aibuild.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScriptPipelineTest {
    @Test void judgeAnswersMapToPreference() {
        assertEquals(-1, BuildPipeline.winner("{\"winner\":\"A\",\"reasons\":[\"better silhouette\"]}"));
        assertEquals(1, BuildPipeline.winner("```json\n{\"winner\": \"b\"}\n```"));
        assertEquals(0, BuildPipeline.winner("{\"winner\":\"tie\"}"));
        assertEquals(0, BuildPipeline.winner("I cannot decide"));
    }

    @Test void scriptPromptsCarryTheApiAndExamples() {
        String system = ScriptPrompts.system(null);
        assertTrue(system.contains("BUILD SCRIPT API"));
        assertTrue(system.contains("place(shape, m, {smooth:true"));
        assertTrue(system.contains("STAIRS"), "block-state rules are shared with the op prompt");
        String critic = ScriptPrompts.critic("a castle", "box(0,1,0,1,1,1,'stone');\n", "no issues", "- (main): 4 blocks", true, 1, 2,
                java.util.List.of("raised the towers — rejected: added major analyzer findings"));
        assertTrue(critic.contains("   1| box(0,1,0,1,1,1,'stone');"));
        assertTrue(critic.contains("REJECTED"));
        assertTrue(critic.contains("<<<<<<< SEARCH"));
    }

    @Test void v3PromptsCarryCraftRecipesAndSculptorContract() {
        String v3 = ScriptPrompts.system(dev.aibuild.script.Stdlib.V2, null);
        assertTrue(v3.contains("FAILURE MODES") && v3.contains("volume(x1,z1,x2,z2"), "3.0 craft and kits");
        assertFalse(ScriptPrompts.system(dev.aibuild.script.Stdlib.V1, null).contains("volume("), "2.0 keeps its own card");
        String create = ScriptPrompts.create(dev.aibuild.script.Stdlib.V2, "каменный арочный мост через ручей", "Make it rich.");
        assertTrue(create.contains("RECIPE: bridge"), create);
        String director = ScriptPrompts.director("a fox", "- head: 90 blocks", "no issues", 3);
        assertTrue(director.contains("at most 3 parts") && director.contains("\"parts\""));
        String sculptor = ScriptPrompts.sculptor("a fox", "head", "90 blocks", "sharper snout", "part('head', () => {});",
                "part('head', () => {});\n", true);
        assertTrue(sculptor.contains("part('head', () => { ... });") && sculptor.contains("sharper snout"));
    }
}
