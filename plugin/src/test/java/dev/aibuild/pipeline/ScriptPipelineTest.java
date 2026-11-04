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

    @Test void conceptsSteerCandidatesAndRefiningSculptorsKeepStructure() {
        var lib = dev.aibuild.script.Stdlib.V2;
        String ask = ScriptPrompts.concepts(lib, "уютный домик с садом, 14 на 11", "Make it rich.", 2);
        assertTrue(ask.contains("Write 2 design concept(s)") && ask.contains("differ in layout") && ask.contains("RECIPE: house"), ask);
        assertFalse(ScriptPrompts.concepts(lib, "a fox", "", 1).contains("differ in layout"), "one concept has nothing to differ from");
        String create = ScriptPrompts.create(lib, "a fox", "Make it rich.", "Lean fox: sitting, tail wrapped");
        assertTrue(create.contains("DESIGN CONCEPT for your version") && create.contains("tail wrapped"), create);
        assertFalse(ScriptPrompts.create(lib, "a fox", "Make it rich.").contains("DESIGN CONCEPT"));
        String refine = ScriptPrompts.sculptor("a fox", "Lean fox", "head", "90 blocks", "sharper snout", "part('head', () => {});",
                "part('head', () => {});\n", true, true);
        assertTrue(refine.contains("Refine ONE part") && refine.contains("never hand-roll") && refine.contains("DESIGN CONCEPT: Lean fox")
                && refine.contains("part('head', () => { ... });"), refine);
        String director = ScriptPrompts.director("a fox", null, "- head: 90 blocks", "no issues", 3, true);
        assertTrue(director.contains("refines the part") && !director.contains("DESIGN CONCEPT"), director);
    }

    @Test void interiorDesignerGetsRoomsAndTheFurnishContract() {
        String p = ScriptPrompts.interior("таверна у дороги", null, "- y=2 x -7..7 z -4..4: 99 floor cells", "interiors", null,
                "part('house', () => {});\n", true);
        assertTrue(p.contains("furnish(room, type, {replace: true") && p.contains("rooms()") && p.contains("y=2 x -7..7")
                && p.contains("part('interiors', () => { ... });") && p.contains("added at the end"), p);
        String own = ScriptPrompts.interior("a house", "Cosy cottage: beams", "- room", "interior", "part('interior', () => {});", "", false);
        assertTrue(own.contains("replaces the script's interior part") && own.contains("DESIGN CONCEPT: Cosy cottage"), own);
        assertEquals("interior", BuildPipeline.interiorPart("part('house', () => {});\npart('interior', () => {});\n"));
        assertNull(BuildPipeline.interiorPart("part('house', () => {});\n"));
        assertTrue(ScriptPrompts.system(dev.aibuild.script.Stdlib.V2, null).contains("furnish(x1,z1,x2,z2, y, type"));
    }

        @Test void conceptsParseFromFencedJsonAndSkipEmptyBriefs() {
        String answer = """
                Here you go:
                ```json
                {"concepts":[{"name":"Фахверк с эркером","brief":"Two-storey timber frame 14x11, jettied upper floor, stone chimney on the east gable."},
                             {"name":"x","brief":"short"},
                             {"brief":"L-shaped cottage 14x11 with a steep thatched-look roof and a well in the yard."}]}
                ```""";
        var list = Concepts.parse(answer, 3);
        assertEquals(2, list.size());
        assertEquals("Фахверк с эркером", list.get(0).name());
        assertEquals("concept 2", list.get(1).name());
        assertEquals(1, Concepts.parse(answer, 1).size());
        assertTrue(Concepts.parse("no json at all", 2).isEmpty());
        assertTrue(Concepts.parse("{\"parts\":[]}", 2).isEmpty());
    }
}
