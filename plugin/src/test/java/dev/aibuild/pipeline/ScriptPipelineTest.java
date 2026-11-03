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
}
