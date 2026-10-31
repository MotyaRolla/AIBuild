package dev.aibuild.model;

import com.google.gson.JsonParser;
import dev.aibuild.model.ops.OpExecutor;
import dev.aibuild.model.ops.ProgramCompiler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProgramCompilerTest {
    @Test void modulesCarveExistingWallsAndPreserveRelativeCopyOffsets() {
        VoxelModel model = new VoxelModel();
        OpExecutor exec = new OpExecutor(new StubBlockInfo());
        var result = exec.apply(model, JsonParser.parseString("""
                {"ops":[
                  {"op":"walls","from":[0,1,0],"to":[8,4,8],"block":"oak_planks"},
                  {"op":"repeat","offsets":[[2,0,8],[5,0,8]],"ops":[
                    {"op":"clear","from":[0,2,0],"to":[1,3,0]},
                    {"op":"set","pos":[0,2,0],"block":"glass"},
                    {"op":"copy","from":[0,2,0],"to":[0,2,0],"offset":[1,0,0]}
                  ]}
                ]}
                """));
        assertTrue(result.isOk(), result.summary());
        for (int x : new int[]{2,3,5,6}) {
            assertEquals("glass", model.get(x,2,8).name());
            assertNull(model.get(x,3,8));
        }
        assertEquals("oak_planks", model.get(4,3,8).name());
    }

    @Test void nestedOffsetsComposeWithoutChangingTheSourceProgram() {
        var source = JsonParser.parseString("""
                {"op":"repeat","offsets":[[10,0,0]],"ops":[
                  {"op":"repeat","offsets":[[0,2,3],[0,4,3]],"ops":[
                    {"op":"set","positions":[[1,1,1],[2,1,1]],"block":"stone"}
                  ]}
                ]}
                """).getAsJsonObject();
        String before = source.toString();
        var expanded = ProgramCompiler.expand(source);
        assertEquals(before, source.toString());
        assertEquals("[11,3,4]", expanded.get(0).getAsJsonObject().getAsJsonArray("positions").get(0).toString());
        assertEquals("[12,5,4]", expanded.get(1).getAsJsonObject().getAsJsonArray("positions").get(1).toString());
    }

    @Test void malformedExpansionIsRejectedBeforeAnyWrite() {
        VoxelModel model = new VoxelModel();
        var result = new OpExecutor(new StubBlockInfo()).apply(model, JsonParser.parseString("""
                {"op":"repeat","offsets":[[0,0,0],[2,0,0]],"ops":[
                  {"op":"set","pos":[0,1,0],"block":"stone"},
                  {"op":"set","pos":"broken","block":"stone"}
                ]}
                """));
        assertTrue(result.hasErrors());
        assertTrue(model.isEmpty());
    }

    @Test void repeatsRespectPlotAndExpansionBudgets() {
        VoxelModel model = new VoxelModel();
        var result = new OpExecutor(new StubBlockInfo()).withLimits(new int[]{0,0,0,2,2,2})
                .apply(model, JsonParser.parseString("""
                {"op":"repeat","offsets":[[0,0,0],[5,0,0]],"ops":[
                  {"op":"set","pos":[0,1,0],"block":"stone"}
                ]}
                """));
        assertTrue(result.hasErrors());
        assertNull(model.get(5,1,0));
        var nested = JsonParser.parseString("""
                {"op":"repeat","offsets":[[0,0,0]],"ops":[{"op":"set","pos":[0,1,0],"block":"stone"}]}
                """).getAsJsonObject();
        var offsets = nested.getAsJsonArray("offsets");
        for (int i=0;i<128;i++) offsets.add(JsonParser.parseString("[0,0,0]"));
        assertThrows(IllegalArgumentException.class, () -> ProgramCompiler.expand(nested));
    }
    @Test void exponentialExpansionIsBoundedBeforeExecutingAnyChildren() {
        var leaf = JsonParser.parseString("{\"op\":\"set\",\"pos\":[0,1,0],\"block\":\"stone\"}");
        for (int depth=0;depth<3;depth++) {
            var repeat = JsonParser.parseString("{\"op\":\"repeat\",\"offsets\":[],\"ops\":[]}").getAsJsonObject();
            for (int i=0;i<16;i++) repeat.getAsJsonArray("offsets").add(JsonParser.parseString("[0,0,0]"));
            repeat.getAsJsonArray("ops").add(leaf);
            leaf=repeat;
        }
        VoxelModel model = new VoxelModel();
        var result = new OpExecutor(new StubBlockInfo()).apply(model,leaf);
        assertTrue(result.hasErrors());
        assertTrue(model.isEmpty());
        assertTrue(result.summary().contains("2048"));
    }
}
