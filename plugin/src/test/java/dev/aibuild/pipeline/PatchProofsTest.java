package dev.aibuild.pipeline;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import dev.aibuild.model.BlockSpec;
import dev.aibuild.model.StubBlockInfo;
import dev.aibuild.model.VoxelModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PatchProofsTest {
    private final StubBlockInfo info = new StubBlockInfo();

    @Test void checksEntireRegionsAndPreservesExplicitDefaultProperties() {
        VoxelModel model = new VoxelModel();
        model.set(0,1,0,info.parse("oak_log[axis=z]"));
        var wrong = proof("{\"pos\":[0,1,0],\"block\":\"oak_log[axis=y]\"}");
        assertEquals(0,PatchProofs.verify(wrong,model,info).certified());
        var right = proof("{\"pos\":[0,1,0],\"block\":\"oak_log[axis=z]\"}");
        assertEquals(1,PatchProofs.verify(right,model,info).certified());
        assertFalse(right.get(0).getAsJsonObject().get("ok").getAsBoolean());
        model.set(1,1,0,BlockSpec.of("stone"));
        var region = proof("{\"from\":[0,1,0],\"to\":[1,1,0],\"block\":[\"oak_log\",\"stone\"]}");
        assertEquals(1,PatchProofs.verify(region,model,info).certified());
        model.set(1,1,0,null);
        assertEquals(0,PatchProofs.verify(region,model,info).certified());
    }

    @Test void invalidMissingAndOversizedProofsCannotCertify() {
        for (String condition : new String[]{"{}", "{\"pos\":[0,1,0],\"block\":\"stone\"}",
                "{\"from\":[-500,-500,-500],\"to\":[500,500,500],\"block\":\"air\"}",
                "{\"pos\":[0.5,1,0],\"block\":\"air\"}",
                "{\"pos\":[0,0,0],\"block\":\"air\"}"}) {
            assertEquals(0,PatchProofs.verify(proof(condition),new VoxelModel(),info).certified(),condition);
        }
    }

    private static JsonArray proof(String predicate) {
        return JsonParser.parseString("[{\"id\":\"C1\",\"ok\":false,\"proof\":["+predicate+"]}]").getAsJsonArray();
    }
}
