package dev.aibuild.scene;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the parts of the 3D build loop that are pure code: applying and rejecting AI patches, the
 * prompts, the critic-answer normalisation and the stopping rule.
 *
 * <p>These are the places where a bad model answer turns into a lost iteration or a corrupted scene, so
 * they are checked without a server: no Bukkit, no world, no network. The renderer, geometry and analysis
 * have their own checks ({@code SceneGeometryCheck}, {@code VoxelRendererCheck}, {@code SceneAnalysisCheck}).</p>
 */
class ScenePatchCheck {

    // ------------------------------------------------------------------ fixtures

    private static SceneSpec spec(String json) {
        return SceneSpec.parse(JsonParser.parseString(json).getAsJsonObject());
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    /** A tiny but complete house: body, roof, door, window, and stable ids. */
    private static SceneSpec house() {
        return spec("""
                {"name":"house","objects":[
                  {"id":"body","type":"box","from":[0,0,0],"to":[9,6,9],"material":"wall","hollow":true},
                  {"id":"roof","type":"prism","from":[0,7,0],"to":[9,7,9],"style":"gable","material":"roof"},
                  {"id":"door","type":"door","wall":"south","at":[4,1],"w":2,"h":3,"material":"wood"},
                  {"id":"win1","type":"window","wall":"south","at":[1,3],"w":1,"h":2,"material":"glass"},
                  {"id":"win2","type":"window","wall":"south","at":[7,3],"w":1,"h":2,"material":"glass"}]}
                """);
    }

    private static List<String> ids(SceneSpec spec) {
        List<String> out = new ArrayList<>();
        for (SceneObject o : spec.objects()) out.add(o.id());
        return out;
    }

    // ------------------------------------------------------------------ patch parsing

    @Test
    void parsesPatchFromABarePatchObject() {
        ScenePatch patch = ScenePatch.parse(json("""
                {"add":[{"type":"chimney","at":[2,0,2],"height":6}],
                 "replace":[{"id":"roof","type":"prism","from":[0,7,0],"to":[9,7,9],"overhang":2}],
                 "remove":["win2"]}
                """));
        assertEquals(1, patch.add().size());
        assertEquals(1, patch.replace().size());
        assertEquals(List.of("win2"), patch.remove());
        assertEquals(3, patch.size());
        assertFalse(patch.isEmpty());
    }

    @Test
    void parsesPatchNestedInACritiqueAnswer() {
        ScenePatch patch = ScenePatch.parse(json("""
                {"issues":[{"severity":"major","what":"нет трубы","where":"крыша"}],
                 "scores":{"shape":6,"detail":4,"style":5,"structure":7},
                 "verdict":"сносно",
                 "patch":{"remove":["#win2"],"add":[]}}
                """));
        assertEquals(List.of("#win2"), patch.remove());
        assertTrue(patch.add().isEmpty());
    }

    @Test
    void toleratesMissingAndMalformedPatchMembers() {
        assertTrue(ScenePatch.parse(null).isEmpty());
        assertTrue(ScenePatch.parse(json("{}")).isEmpty());
        assertTrue(ScenePatch.parse(json("{\"patch\":null}")).isEmpty());
        // not arrays -> ignored instead of throwing
        assertTrue(ScenePatch.parse(json("{\"add\":\"oops\",\"replace\":7,\"remove\":{}}")).isEmpty());
        // non-objects inside add/replace are dropped
        ScenePatch p = ScenePatch.parse(json("{\"add\":[1,\"x\",null,{\"type\":\"beam\"}]}"));
        assertEquals(1, p.add().size());
        assertEquals("beam", p.add().get(0).get("type").getAsString());
    }

    @Test
    void removesMayBeIdsNumbersOrObjectRefs() {
        ScenePatch patch = ScenePatch.parse(json("""
                {"remove":["#roof",3,{"id":"door"},{"index":1},"  win1  ",null,""]}
                """));
        assertEquals(List.of("#roof", "3", "door", "1", "win1"), patch.remove());
    }

    @Test
    void capsTheNumberOfOperations() {
        StringBuilder adds = new StringBuilder();
        for (int i = 0; i < ScenePatch.MAX_OPERATIONS + 10; i++) {
            if (i > 0) adds.append(',');
            adds.append("{\"type\":\"beam\",\"from\":[0,0,0],\"to\":[1,0,1]}");
        }
        ScenePatch patch = ScenePatch.parse(json("{\"add\":[" + adds + "]}"));
        assertEquals(ScenePatch.MAX_OPERATIONS, patch.add().size());
    }

    // ------------------------------------------------------------------ patch application

    @Test
    void removesByIdAndKeepsEverythingElse() {
        ScenePatch patch = ScenePatch.parse(json("{\"remove\":[\"win2\"]}"));
        ScenePatch.Result result = patch.apply(house(), SceneBuildJob.MAX_OBJECTS);
        assertTrue(result.applied());
        assertEquals(List.of("body", "roof", "door", "win1"), ids(result.spec()));
        assertEquals(1, result.changes().size());
        assertTrue(result.errors().isEmpty());
        assertTrue(result.summary().contains("правки применены"));
    }

    @Test
    void resolvesRemovesByHashIdZeroBasedAndOneBasedIndex() {
        // References resolve against the scene as it shrinks, in order: #roof by id, then win2 by id,
        // then 0 as a 0-based index (now pointing at body).
        SceneSpec byRef = ScenePatch.parse(json("{\"remove\":[\"#roof\",\"win2\",0]}"))
                .apply(house(), SceneBuildJob.MAX_OBJECTS).spec();
        assertEquals(List.of("door", "win1"), ids(byRef));

        // 5 is out of the 0-based range (size 5, max index 4) so it is read as the 5th object: win2
        SceneSpec oneBased = ScenePatch.parse(json("{\"remove\":[5]}"))
                .apply(house(), SceneBuildJob.MAX_OBJECTS).spec();
        assertEquals(List.of("body", "roof", "door", "win1"), ids(oneBased));
    }

    @Test
    void replacesKeepTheIdOfTheTargetAndAcceptIndexReferences() {
        ScenePatch.Result byId = ScenePatch.parse(json("""
                {"replace":[{"id":"roof","type":"prism","from":[0,7,0],"to":[9,7,9],"style":"hip","overhang":2,"material":"roof"}]}
                """)).apply(house(), SceneBuildJob.MAX_OBJECTS);
        assertTrue(byId.applied());
        SceneObject roof = byId.spec().objects().get(1);
        assertEquals("roof", roof.id(), "the replacement keeps the target id");
        assertEquals("hip", roof.raw().get("style").getAsString());

        ScenePatch.Result byIndex = ScenePatch.parse(json("""
                {"replace":[{"index":4,"id":"win2","type":"window","wall":"east","at":[2,3],"w":2,"h":2,"material":"glass"}]}
                """)).apply(house(), SceneBuildJob.MAX_OBJECTS);
        assertTrue(byIndex.applied());
        assertEquals("east", byIndex.spec().objects().get(4).raw().get("wall").getAsString());
        assertEquals(List.of("body", "roof", "door", "win1", "win2"), ids(byIndex.spec()));
    }

    @Test
    void addsGetAFreshIdWhenMissingAndKeepTheGivenOne() {
        ScenePatch.Result result = ScenePatch.parse(json("""
                {"add":[{"type":"chimney","at":[2,0,2],"height":6,"material":"stone"},
                        {"id":"porch","type":"stairs","from":[3,0,10],"to":[6,0,12],"material":"wood"}]}
                """)).apply(house(), SceneBuildJob.MAX_OBJECTS);
        assertTrue(result.applied());
        assertEquals(7, result.spec().objects().size());
        assertEquals("porch", result.spec().objects().get(6).id());
        assertTrue(result.spec().objects().get(5).id().startsWith("ai-"),
                "a generated id, was " + result.spec().objects().get(5).id());
    }

    @Test
    void rejectsAnUnknownIdWithoutLosingTheRestOfThePatch() {
        ScenePatch.Result result = ScenePatch.parse(json("""
                {"remove":["nope","win2"],"add":[{"id":"flag","type":"beam","from":[0,9,0],"to":[0,12,0],"material":"wood"}]}
                """)).apply(house(), SceneBuildJob.MAX_OBJECTS);
        assertTrue(result.applied(), "the valid parts still land");
        assertFalse(result.errors().isEmpty());
        assertTrue(result.errors().get(0).contains("nope"));
        assertEquals(List.of("body", "roof", "door", "win1", "flag"), ids(result.spec()));
    }

    @Test
    void rejectsInvalidObjectsButCommitsTheValidOnes() {
        // unknown type, a window without a wall, a box without corners: all must be dropped
        ScenePatch.Result result = ScenePatch.parse(json("""
                {"add":[{"id":"bad1","type":"portal","from":[0,0,0],"to":[1,1,1]},
                        {"id":"bad2","type":"window","at":[1,1]},
                        {"id":"bad3","type":"box"},
                        {"id":"good","type":"column","at":[0,0,10],"height":4,"material":"stone"}]}
                """)).apply(house(), SceneBuildJob.MAX_OBJECTS);
        assertTrue(result.applied());
        assertEquals(3, result.errors().size());
        assertEquals(List.of("body", "roof", "door", "win1", "win2", "good"), ids(result.spec()));
    }

    @Test
    void rejectsADuplicateIdOnAdd() {
        ScenePatch.Result result = ScenePatch.parse(json("""
                {"add":[{"id":"roof","type":"prism","from":[0,7,0],"to":[9,7,9],"material":"roof"}]}
                """)).apply(house(), SceneBuildJob.MAX_OBJECTS);
        assertFalse(result.applied());
        assertTrue(result.rejected());
        assertTrue(result.errors().get(0).contains("уже занят"));
        assertEquals(5, result.spec().objects().size());
    }

    @Test
    void refusesToEmptyTheScene() {
        ScenePatch.Result result = ScenePatch.parse(json("{\"remove\":[\"body\",\"roof\",\"door\",\"win1\",\"win2\"]}"))
                .apply(house(), SceneBuildJob.MAX_OBJECTS);
        // removing everything is invalid: the last removal is rejected, one object survives
        assertTrue(result.applied());
        assertEquals(1, result.spec().objects().size());
        assertEquals("win2", result.spec().objects().get(0).id());
        assertFalse(result.errors().isEmpty());
    }

    @Test
    void refusesToGrowPastTheObjectBudget() {
        StringBuilder adds = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            if (i > 0) adds.append(',');
            adds.append("{\"id\":\"c").append(i).append("\",\"type\":\"column\",\"at\":[").append(i)
                    .append(",0,10],\"height\":3,\"material\":\"stone\"}");
        }
        ScenePatch patch = ScenePatch.parse(json("{\"add\":[" + adds + "]}"));
        ScenePatch.Result result = patch.apply(house(), 7);
        assertTrue(result.applied());
        assertEquals(7, result.spec().objects().size());
        assertFalse(result.errors().isEmpty());
    }

    @Test
    void anEmptyPatchIsNotARejection() {
        ScenePatch.Result result = ScenePatch.EMPTY.apply(house(), SceneBuildJob.MAX_OBJECTS);
        assertFalse(result.applied());
        assertTrue(result.empty());
        assertFalse(result.rejected());
        assertTrue(result.errors().isEmpty());
        assertEquals("правок не нужно", result.summary());
        assertEquals("house", result.spec().name());
    }

    @Test
    void aPatchOfOnlyUnusableOperationsIsReportedAsRejected() {
        ScenePatch.Result result = ScenePatch.parse(json("{\"remove\":[\"ghost\"]}"))
                .apply(house(), SceneBuildJob.MAX_OBJECTS);
        assertTrue(result.rejected());
        assertFalse(result.empty());
        assertTrue(result.summary().startsWith("правки отклонены"));
    }

    @Test
    void applyNeverMutatesTheInputSpec() {
        SceneSpec original = house();
        String before = original.toJson().toString();
        ScenePatch.parse(json("{\"remove\":[\"roof\"],\"add\":[{\"type\":\"column\",\"at\":[0,0,0],\"height\":2}]}"))
                .apply(original, SceneBuildJob.MAX_OBJECTS);
        assertEquals(before, original.toJson().toString());
        assertEquals(5, original.objects().size());
    }

    // ------------------------------------------------------------------ prompts

    @Test
    void systemPromptsAreStableAndNonNull() {
        assertNotNull(ScenePrompts.system());
        assertNotNull(ScenePrompts.criticSystem());
        assertEquals(ScenePrompts.system(), ScenePrompts.system(), "must be cacheable byte for byte");
        assertEquals(ScenePrompts.criticSystem(), ScenePrompts.criticSystem());
        assertTrue(ScenePrompts.system().contains("JSON"));
        assertTrue(ScenePrompts.criticSystem().contains("\"patch\""));
    }

    @Test
    void initialPromptCarriesTheRequest() {
        String prompt = ScenePrompts.initial("средневековый дом 10 на 10");
        assertTrue(prompt.contains("средневековый дом 10 на 10"));
        assertTrue(prompt.contains("JSON"));
    }

    @Test
    void critiquePromptCarriesViewsAnalysisAndTheScene() {
        SceneSpec spec = house();
        String analysis = SceneAnalysis.analyze(spec, "дом 10 на 10").render();
        String prompt = ScenePrompts.critique("дом 10 на 10", spec, analysis,
                List.of("se", "sw", "front", "top"), 3);
        assertTrue(prompt.contains("iteration 3"));
        assertTrue(prompt.contains("se, sw, front, top"));
        assertTrue(prompt.contains("OBJECTIVE CHECKS"));
        assertTrue(prompt.contains("\"id\":\"roof\"") || prompt.contains("\"id\": \"roof\""),
                "the scene JSON is inlined for a small spec");
        assertTrue(prompt.contains("patch"));
        for (String view : List.of("se", "sw", "front", "top")) {
            assertTrue(SceneBuildJob.VIEWS.contains(view), "VIEWS must match the attachment order");
        }
    }

    @Test
    void critiquePromptFallsBackToTheDigestForAHugeScene() {
        StringBuilder objects = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            if (i > 0) objects.append(',');
            objects.append("{\"id\":\"b").append(i)
                    .append("\",\"type\":\"beam\",\"from\":[0,").append(i % 40)
                    .append(",0],\"to\":[9,").append(i % 40).append(",0],\"material\":\"wood\",\"note\":\"")
                    .append("x".repeat(60)).append("\"}");
        }
        SceneSpec spec = spec("{\"objects\":[" + objects + "]}");
        String prompt = ScenePrompts.critique("дом", spec, "bounds: 10x40x1", List.of("se"), 1);
        assertTrue(prompt.contains("too large to inline"));
        assertTrue(prompt.contains("objects: 400"));
    }

    @Test
    void repairPromptExplainsTheProblem() {
        String prompt = ScenePrompts.repair("objects[3]: unknown object type 'portal'");
        assertTrue(prompt.contains("portal"));
        assertTrue(prompt.contains("JSON"));
    }

    // ------------------------------------------------------------------ critic answer

    @Test
    void normalisesEverySeveritySpelling() {
        assertEquals("critical", SceneBuildJob.severityOf(json("{\"severity\":\"blocker\"}")));
        assertEquals("critical", SceneBuildJob.severityOf(json("{\"severity\":\"CRITICAL\"}")));
        assertEquals("critical", SceneBuildJob.severityOf(json("{\"severity\":\"high\"}")));
        assertEquals("major", SceneBuildJob.severityOf(json("{\"severity\":\"serious\"}")));
        assertEquals("major", SceneBuildJob.severityOf(json("{\"level\":\"medium\"}")));
        assertEquals("minor", SceneBuildJob.severityOf(json("{\"severity\":\"polish\"}")));
        assertEquals("minor", SceneBuildJob.severityOf(json("{}")));
        assertEquals("minor", SceneBuildJob.severityOf(null));
    }

    @Test
    void convertsCategoryScoresToTheHundredPointScale() {
        assertEquals(60, SceneBuildJob.scoreFromScores(json(
                "{\"shape\":6,\"detail\":6,\"style\":6,\"structure\":6,\"fidelity\":6}")));
        assertEquals(-1, SceneBuildJob.scoreFromScores(null));
        assertEquals(-1, SceneBuildJob.scoreFromScores(json("{}")));
        assertEquals(-1, SceneBuildJob.scoreFromScores(json("{\"shape\":\"n/a\"}")));
        // unknown categories are ignored, values are clamped into 0..10
        assertEquals(70, SceneBuildJob.scoreFromScores(json("{\"shape\":10,\"detail\":4,\"bogus\":1}")));
        assertEquals(100, SceneBuildJob.scoreFromScores(json("{\"shape\":99}")));
        assertEquals(0, SceneBuildJob.scoreFromScores(json("{\"shape\":0}")));
    }

    @Test
    void heuristicScoreFallsBackToTheObjectiveFacts() {
        SceneSpec bare = spec("{\"objects\":[{\"type\":\"box\",\"from\":[0,0,0],\"to\":[9,6,9]}]}");
        int bareScore = SceneBuildJob.heuristicScore(SceneAnalysis.analyze(bare, "дом"), bare);
        SceneSpec complete = house();
        int completeScore = SceneBuildJob.heuristicScore(SceneAnalysis.analyze(complete, "дом"), complete);
        assertTrue(bareScore >= 0 && bareScore <= 100);
        assertTrue(completeScore > bareScore,
                "a house with a roof, a door and windows must beat a bare box: " + bareScore + " vs " + completeScore);
    }

    // ------------------------------------------------------------------ stopping rule

    @Test
    void stallCounterResetsOnRealProgress() {
        assertEquals(1, SceneBuildJob.stalledAfter(0, 0.0, 3));
        assertEquals(2, SceneBuildJob.stalledAfter(1, -4, 3));
        assertEquals(0, SceneBuildJob.stalledAfter(2, 3, 3), "a gain equal to the threshold is progress");
        assertEquals(1, SceneBuildJob.stalledAfter(0, 2.9, 3));
    }

    @Test
    void stopsOnlyWhenTheCriticIsCleanAndTheScoreStalled() {
        // clean critic, clean analysis, two stalled iterations -> stop
        assertTrue(SceneBuildJob.shouldStop(true, true, 2, 2));
        // one stalled iteration is not enough
        assertFalse(SceneBuildJob.shouldStop(true, true, 1, 2));
        // issues remain -> keep going, however flat the score is
        assertFalse(SceneBuildJob.shouldStop(false, true, 5, 2));
        // objective problems remain -> keep going
        assertFalse(SceneBuildJob.shouldStop(true, false, 5, 2));
        // a degenerate limit still requires one stalled iteration
        assertFalse(SceneBuildJob.shouldStop(true, true, 0, 0));
        assertTrue(SceneBuildJob.shouldStop(true, true, 1, 0));
    }

    @Test
    void bestVersionIsTheHighestScoreNotTheLast() {
        SceneSpec s = house();
        ScenePatch.Result none = ScenePatch.EMPTY.apply(s, SceneBuildJob.MAX_OBJECTS);
        List<SceneBuildJob.Version> versions = List.of(
                version(1, s, 41, 2, 3, none),
                version(2, s, 68, 0, 0, none),
                version(3, s, 52, 0, 1, none));
        assertEquals(2, SceneBuildJob.best(versions).iteration(), "a late regression must not ship");
        assertEquals(null, SceneBuildJob.best(List.of()));
    }

    @Test
    void tiesGoToTheCleanerThenTheEarlierVersion() {
        SceneSpec s = house();
        ScenePatch.Result none = ScenePatch.EMPTY.apply(s, SceneBuildJob.MAX_OBJECTS);
        List<SceneBuildJob.Version> sharper = List.of(
                version(1, s, 70, 1, 2, none),
                version(2, s, 70, 0, 0, none));
        assertEquals(2, SceneBuildJob.best(sharper).iteration(), "fewer serious issues wins a tie");

        List<SceneBuildJob.Version> same = List.of(
                version(1, s, 70, 0, 1, none),
                version(2, s, 70, 0, 1, none));
        assertEquals(1, SceneBuildJob.best(same).iteration(), "equal versions keep the earlier one");
    }

    @Test
    void theWinnerIsTheSceneThatWasJudgedNotTheSceneAfterItsPatch() {
        // A patch that would add a column: history must keep the version the critic actually scored,
        // otherwise the pipeline would build a scene nobody ever looked at.
        SceneSpec evaluated = house();
        ScenePatch.Result applied = ScenePatch.parse(json("""
                {"add":[{"id":"spire","type":"column","at":[4,7,4],"height":3,"material":"stone"}]}
                """)).apply(evaluated, SceneBuildJob.MAX_OBJECTS);
        assertTrue(applied.applied());
        SceneBuildJob.Version judged = version(1, evaluated, 64, 0, 1, applied);
        assertEquals(5, judged.spec().objects().size(), "the judged scene, not the patched one");
        assertEquals(6, applied.spec().objects().size());
        assertEquals(1, SceneBuildJob.best(List.of(judged)).iteration());
    }

    private static SceneBuildJob.Version version(int iteration, SceneSpec spec, int score, int critical,
                                                 int major, ScenePatch.Result patch) {
        return new SceneBuildJob.Version(iteration, spec, score, critical, major, 1, patch, "ok", true,
                false, null, List.of("se"));
    }
}
