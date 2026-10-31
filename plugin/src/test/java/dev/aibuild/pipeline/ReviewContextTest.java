package dev.aibuild.pipeline;

import com.google.gson.JsonParser;
import dev.aibuild.analysis.AnalyzerOptions;
import dev.aibuild.analysis.StructureAnalyzer;
import dev.aibuild.model.BlockSpec;
import dev.aibuild.model.StubBlockInfo;
import dev.aibuild.model.VoxelModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReviewContextTest {
    final StubBlockInfo info = new StubBlockInfo();

    VoxelModel largeModel() {
        VoxelModel model = new VoxelModel();
        for (int y = 0; y < 24; y++) for (int z = -20; z <= 20; z++) for (int x = -20; x <= 20; x++) {
            if (y == 0 || x == -20 || x == 20 || z == -20 || z == 20 || y % 6 == 0) {
                model.set(x,y,z,BlockSpec.of(x % 3 == 0 ? "deepslate" : "stone"));
            }
        }
        return model;
    }

    ReviewContext context(VoxelModel now, VoxelModel before, boolean full) {
        return ReviewContext.create(now,before,info,StructureAnalyzer.analyze(now,info,new AnalyzerOptions(false)),full,List.of());
    }

    @Test void focusedContextRetainsGlobalEvidenceAndRestrictsWritesToInspectedCells() {
        VoxelModel before = largeModel(), now = before.copy();
        now.set(-20,4,0,BlockSpec.of("gold_block"));
        ReviewContext focused = context(now,before,false);
        assertFalse(focused.full,focused.reason);
        assertTrue(focused.text.contains("GLOBAL EXTERIOR VIEWS"));
        assertTrue(focused.text.contains("gold_block"));
        assertTrue(focused.text.length() < focused.fullCharacters * 0.8);
        assertTrue(focused.permits(before,now));
        VoxelModel repair = now.copy();
        repair.set(-20,5,0,BlockSpec.of("gold_block"));
        assertTrue(focused.permits(now,repair));
        repair.set(20,5,0,BlockSpec.of("gold_block"));
        assertFalse(focused.permits(now,repair));
        assertEquals(focused.text,context(now,before,false).text);
    }

    @Test void deletionsAreIncludedAndMissingOrWidespreadEvidenceFallsBackToFull() {
        VoxelModel before = largeModel(), now = before.copy();
        now.set(-20,4,0,null);
        assertFalse(context(now,before,false).full);
        assertTrue(context(now,before,true).full);
        assertTrue(context(now,null,false).full);
        assertTrue(context(now,now,false).full);
        for (int x = -20; x <= 20; x++) for (int z = -20; z <= 20; z++) now.set(x,0,z,BlockSpec.of("gold_block"));
        assertTrue(context(now,before,false).full);
    }

    @Test void reviewerRegionsAreBoundedAndInvalidCoordinatesCannotAuthorizePatches() {
        var response = JsonParser.parseString("""
                {"issues":[{"region":{"from":[3,5,-8],"to":[1,4,-9]}},
                  {"region":{"from":[0,0,0],"to":[1e100,0,0]}},
                  {"region":{"from":[0,0,0],"to":[1.5,0,0]}}]}
                """).getAsJsonObject();
        var regions = ReviewContext.issueRegions(response);
        assertEquals(1,regions.size());
        assertArrayEquals(new int[]{-1,2,-11,5,7,-6},regions.get(0));
    }

    @Test void focusedRepairAndPostReviewMutationsCannotGrantFinalApproval() {
        ReviewSchedule schedule = new ReviewSchedule();
        assertTrue(schedule.requireFull(1,6));
        schedule.record(true,20,false,false);
        assertFalse(schedule.assessedCurrentModel());
        assertFalse(schedule.requireFull(2,6));
        schedule.record(false,0,true,false);
        assertFalse(schedule.assessedCurrentModel());
        assertTrue(schedule.requireFull(3,6));
        schedule.record(true,1,true,false);
        assertFalse(schedule.assessedCurrentModel());
        schedule.record(true,0,true,false);
        assertTrue(schedule.assessedCurrentModel());
        schedule.record(true,0,true,true);
        assertFalse(schedule.assessedCurrentModel());
    }

    @Test void focusedRoundCapsAndRequestsAlwaysReturnToFullInspection() {
        ReviewSchedule schedule = new ReviewSchedule();
        schedule.record(true,20,false,false);
        schedule.record(false,3,false,false);
        schedule.record(false,2,false,false);
        assertTrue(schedule.requireFull(4,6));
        schedule.record(true,20,false,false);
        assertTrue(schedule.requireFull(6,6));
        schedule.record(false,0,false,true);
        assertTrue(schedule.requireFull(3,6));
        assertFalse(schedule.assessedCurrentModel());
    }

    @Test void aFullAssessmentCannotReuseChecklistPassesFromEarlierRegions() {
        Checklist checklist = Checklist.forCreate(new com.google.gson.JsonObject());
        var all = new com.google.gson.JsonArray();
        for (int i = 1; i <= checklist.size(); i++) {
            var checked = new com.google.gson.JsonObject();
            checked.addProperty("id","C"+i); checked.addProperty("ok",true); all.add(checked);
        }
        checklist.apply(all,1);
        assertTrue(checklist.allVerified());
        assertTrue(checklist.coveredBy(all,"all"));
        all.remove(all.size()-1);
        assertFalse(checklist.coveredBy(all,"all"));
        assertTrue(checklist.allVerified());
        assertTrue(checklist.coveredBy(all,"structure"));
    }

    @Test void unlocalizedBlockersAndSmallModelsUseFullContext() {
        VoxelModel before = largeModel(), now = before.copy();
        now.set(-20,4,0,BlockSpec.of("gold_block"));
        var report = StructureAnalyzer.analyze(now,info,new AnalyzerOptions(false).expectedFootprint(70,70));
        assertTrue(report.hasBlocking());
        assertTrue(ReviewContext.create(now,before,info,report,false,List.of()).full);
        before = new VoxelModel(); before.set(0,0,0,BlockSpec.of("stone"));
        now = before.copy(); now.set(0,0,0,BlockSpec.of("gold_block"));
        assertTrue(context(now,before,false).full);
    }
}
