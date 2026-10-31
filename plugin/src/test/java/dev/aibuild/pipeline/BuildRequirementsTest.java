package dev.aibuild.pipeline;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aibuild.analysis.AnalyzerOptions;
import dev.aibuild.analysis.StructureAnalyzer;
import dev.aibuild.model.BlockSpec;
import dev.aibuild.model.StubBlockInfo;
import dev.aibuild.model.VoxelModel;
import dev.aibuild.model.ops.OpExecutor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BuildRequirementsTest {
    final StubBlockInfo info = new StubBlockInfo();

    @Test void sculptureDoesNotNeedWallsOrInteriorButMustRespectMaximumDimensions() {
        JsonObject plan = JsonParser.parseString("""
                {"type":"statue","body":{"x":45,"z":31},
                 "size_limits":{"x":48,"y":36,"z":48}}
                """).getAsJsonObject();
        AnalyzerOptions options = BuildRequirements.analyzerOptions(plan);
        assertFalse(options.expectInterior);
        assertNull(options.expectedFootprint);
        VoxelModel model = new VoxelModel();
        new OpExecutor(info).apply(model, JsonParser.parseString("""
                {"op":"fill","from":[-2,0,-2],"to":[2,2,2],"block":"stone"}
                """));
        for (int y = 3; y <= 35; y++) model.set(0,y,0,BlockSpec.of("stone"));
        var valid = StructureAnalyzer.analyze(model,info,options);
        assertFalse(valid.hasCode("footprint_mismatch"));
        assertFalse(valid.hasBlocking(),valid.toText());
        model.set(0,36,0,BlockSpec.of("stone"));
        var tooTall = StructureAnalyzer.analyze(model,info,options);
        assertTrue(tooTall.hasCode("size_limit_exceeded"),tooTall.toText());
        assertTrue(tooTall.hasBlocking());
    }

    @Test void buildingWallDimensionsStillRejectUndersizedHouses() {
        JsonObject plan = JsonParser.parseString("""
                {"type":"house","body":{"x":15,"z":15}}
                """).getAsJsonObject();
        VoxelModel model = new VoxelModel();
        new OpExecutor(info).apply(model,JsonParser.parseString("""
                {"op":"building","from":[0,0,0],"to":[8,4,8]}
                """));
        assertTrue(StructureAnalyzer.analyze(model,info,BuildRequirements.analyzerOptions(plan))
                .hasCode("footprint_mismatch"));
        assertArrayEquals(new int[]{15,15},BuildRequirements.analyzerOptions(plan).expectedFootprint);
        plan.remove("type");
        assertArrayEquals(new int[]{15,15},BuildRequirements.analyzerOptions(plan).expectedFootprint);
    }

    @Test void onlyUsableInteriorsRequireLightingAndExactOverallSizeIsSeparate() {
        VoxelModel model = new VoxelModel();
        new OpExecutor(info).apply(model,JsonParser.parseString("""
                {"op":"fill","from":[0,0,0],"to":[4,4,4],"block":"stone"}
                """));
        model.set(2,1,2,BlockSpec.AIR); model.set(2,2,2,BlockSpec.AIR);
        var interior = StructureAnalyzer.analyze(model,info,new AnalyzerOptions(true));
        var sculpture = StructureAnalyzer.analyze(model,info,new AnalyzerOptions(false));
        assertTrue(interior.hasCode("dark_interior"));
        assertFalse(sculpture.hasCode("dark_interior"));
        JsonObject plan = JsonParser.parseString("""
                {"type":"statue","exact_size":{"x":6},"size_limits":{"x":8}}
                """).getAsJsonObject();
        var options = BuildRequirements.analyzerOptions(plan);
        assertTrue(StructureAnalyzer.analyze(model,info,options).hasCode("overall_size_mismatch"));
        assertFalse(StructureAnalyzer.analyze(model,info,options).hasCode("size_limit_exceeded"));
        assertEquals(options,options.copy());
        options.copy().maximumSize[0] = 1;
        assertEquals(8,options.maximumSize[0]);
        plan.addProperty("expect_interior",true);
        assertTrue(BuildRequirements.analyzerOptions(plan).expectInterior);
    }

    @Test void finalAnalyzerReasonsCannotDisappearBehindACompletedChecklist() {
        VoxelModel model = new VoxelModel();
        model.set(0,0,0,BlockSpec.of("stone"));
        model.set(20,20,20,BlockSpec.of("stone"));
        var verdict = new BuildPipeline.Verdict();
        verdict.approved = true;
        verdict.checklist = "16/16 (all verified)";
        verdict.finishAnalysis(StructureAnalyzer.analyze(model,info,new AnalyzerOptions(false)));
        assertFalse(verdict.approved);
        assertTrue(verdict.failureReason().contains("floating"),verdict.failureReason());
        int count = verdict.blockers.size();
        verdict.finishAnalysis(verdict.finalReport);
        assertEquals(count,verdict.blockers.size());
        model.set(20,20,20,null);
        verdict.finishAnalysis(StructureAnalyzer.analyze(model,info,new AnalyzerOptions(false)));
        assertTrue(verdict.blockers.isEmpty());
    }
}
