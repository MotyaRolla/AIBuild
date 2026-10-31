package dev.aibuild.pipeline;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aibuild.analysis.AnalysisReport;
import dev.aibuild.analysis.AnalyzerOptions;
import dev.aibuild.analysis.StructureAnalyzer;
import dev.aibuild.model.BlockSpec;
import dev.aibuild.model.StubBlockInfo;
import dev.aibuild.model.VoxelModel;
import dev.aibuild.model.ops.OpExecutor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReviewDecisionTest {
    private JsonObject passing() {
        JsonObject response=new JsonObject();
        response.addProperty("done",true); response.addProperty("score",99);
        JsonObject quality=new JsonObject();
        for (String name : ReviewDecision.criteria("all")) {
            JsonObject item=new JsonObject(); item.addProperty("rating",3);
            item.addProperty("evidence","Observed main volume 9x9 with clear route from [4,1,8] to [4,1,4]");
            quality.add(name,item);
        }
        response.add("quality",quality);
        return response;
    }

    private boolean finish(JsonObject response,JsonArray verified,boolean proven) {
        return ReviewDecision.canFinish(response,verified,"all",86,0,0,true,proven);
    }

    @Test void highScoreAndDoneCannotOverrideSeriousFindings() {
        JsonObject response=passing();
        assertTrue(finish(response,new JsonArray(),false));
        response.add("issues",JsonParser.parseString("[{\"severity\":\"major\",\"check\":\"C4\",\"what\":\"door blocked\"}]"));
        assertFalse(finish(response,new JsonArray(),false));
        response.getAsJsonArray("issues").get(0).getAsJsonObject().addProperty("severity","critical");
        assertFalse(finish(response,new JsonArray(),false));
    }

    @Test void unrelatedAndFailedProofsCannotCertifyASeriousRepair() {
        JsonObject response=passing();
        response.addProperty("done",false);
        response.add("issues",JsonParser.parseString("[{\"severity\":\"major\",\"check\":\"C4\"}]"));
        response.add("checked",JsonParser.parseString("[{\"id\":\"C3\",\"ok\":false,\"proof\":[{\"pos\":[1,1,1],\"block\":\"stone\"}]}]"));
        JsonArray verified=JsonParser.parseString("[{\"id\":\"C3\",\"ok\":true}]").getAsJsonArray();
        assertFalse(finish(response,verified,true));
        response.getAsJsonArray("checked").get(0).getAsJsonObject().addProperty("id","C4");
        assertFalse(finish(response,verified,true));
        verified.get(0).getAsJsonObject().addProperty("id","C4");
        assertTrue(finish(response,verified,true));
        verified.get(0).getAsJsonObject().addProperty("ok",false);
        assertFalse(finish(response,verified,true));
    }

    @Test void everyCriterionNeedsAnAnchoredRatingAndEvidence() {
        JsonObject response=passing();
        JsonObject composition=response.getAsJsonObject("quality").getAsJsonObject("composition");
        composition.addProperty("rating",2);
        assertFalse(finish(response,new JsonArray(),false));
        composition.addProperty("rating",4.5);
        assertFalse(finish(response,new JsonArray(),false));
        composition.addProperty("rating",4);
        composition.addProperty("evidence","beautiful");
        assertFalse(finish(response,new JsonArray(),false));
        response.remove("quality");
        assertEquals(6,ReviewDecision.qualityFailures(response,"all").size());
    }

    @Test void unfinishedChecklistFailedOpsAndMergeConflictsBlockAcceptance() {
        JsonObject response=passing();
        assertFalse(ReviewDecision.canFinish(response,new JsonArray(),"all",86,1,0,true,false));
        assertFalse(ReviewDecision.canFinish(response,new JsonArray(),"all",86,0,1,true,false));
        assertFalse(ReviewDecision.canFinish(response,new JsonArray(),"all",86,0,0,false,false));
    }

    @Test void aPatchMayRepairAnExistingDefectButCannotIntroduceANewOne() {
        StubBlockInfo info=new StubBlockInfo();
        VoxelModel model=new VoxelModel();
        new OpExecutor(info).apply(model,JsonParser.parseString("{\"op\":\"building\",\"from\":[0,0,0],\"to\":[8,4,8]}"));
        AnalysisReport before=StructureAnalyzer.analyze(model,info,new AnalyzerOptions());
        model.set(20,20,20,BlockSpec.of("stone"));
        AnalysisReport broken=StructureAnalyzer.analyze(model,info,new AnalyzerOptions());
        assertTrue(ReviewDecision.introducesBlocking(before,broken));
        assertFalse(ReviewDecision.introducesBlocking(broken,before));
    }
}
