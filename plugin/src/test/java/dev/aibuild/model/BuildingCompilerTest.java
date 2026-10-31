package dev.aibuild.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aibuild.analysis.AnalyzerOptions;
import dev.aibuild.analysis.StructureAnalyzer;
import dev.aibuild.model.ops.OpExecutor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BuildingCompilerTest {
    @Test void parametricEnvelopesStayClosedLitAndAccessible() {
        StubBlockInfo info = new StubBlockInfo();
        for (int width : new int[]{7,8,9,12,17}) for (int depth : new int[]{7,9,14}) {
            VoxelModel m = new VoxelModel();
            JsonObject op = JsonParser.parseString("{\"op\":\"building\",\"from\":[0,0,0],\"to\":["
                    +(width-1)+",4,"+(depth-1)+"]}").getAsJsonObject();
            var r = new OpExecutor(info).apply(m,op);
            assertTrue(r.isOk(),r.summary());
            ShapeResolver.resolve(m,info);
            var report = StructureAnalyzer.analyze(m,info,new AnalyzerOptions());
            assertFalse(report.hasCode("no_enclosed_interior"),width+"x"+depth+"\n"+report.toText());
            assertFalse(report.hasCode("door_blocked"),report.toText());
            assertFalse(report.hasCode("unsupported_attachment"),report.toText());
            assertEquals(1,report.doors);
            assertTrue(report.windows>=4);
            assertEquals(0,report.darkSpots,report.toText());
        }
    }

    @Test void grammarComposesAtTranslatedOriginsWithCustomMaterials() {
        VoxelModel m = new VoxelModel();
        var r = new OpExecutor(new StubBlockInfo()).apply(m,JsonParser.parseString("""
                {"op":"building","from":[-12,5,20],"to":[-4,9,28],"wall":"calcite","floor":"birch_planks",
                 "door_x":-8,"roof":{"style":"hip","stairs":"dark_oak_stairs","slab":"dark_oak_slab"}}
                """));
        assertTrue(r.isOk(),r.summary());
        assertEquals("birch_planks",m.get(-8,5,24).name());
        assertEquals("oak_door",m.get(-8,6,28).name());
        assertEquals("calcite",m.get(-12,7,24).name());
    }
    @Test void entranceConstraintsRepairDecorationsThatObstructTheDoor() {
        StubBlockInfo info = new StubBlockInfo();
        VoxelModel m = new VoxelModel();
        var r = new OpExecutor(info).apply(m,JsonParser.parseString("""
                {"op":"building","from":[0,0,0],"to":[8,4,8]}
                """));
        assertTrue(r.isOk(),r.summary());
        assertEquals(1,r.doorways.size());
        m.set(4,1,8,null);
        m.set(4,1,9,BlockSpec.of("cobblestone_stairs"));
        m.set(4,2,7,BlockSpec.of("oak_planks"));
        assertTrue(r.doorways.getFirst().restore(m,info)>0);
        var report = StructureAnalyzer.analyze(m,info,new AnalyzerOptions());
        assertFalse(report.hasCode("door_blocked"),report.toText());
        assertEquals(1,report.doors);
        assertEquals(0,r.doorways.getFirst().restore(m,info));
    }

    @Test void restrainedStylesCanOmitCottageDecorationsWithoutLosingTheShell() {
        StubBlockInfo info=new StubBlockInfo();
        VoxelModel model=new VoxelModel();
        var result=new OpExecutor(info).apply(model,JsonParser.parseString("""
                {"op":"building","from":[0,0,0],"to":[10,4,8],"wall":"white_concrete","floor":"smooth_stone",
                 "framing":false,"windows":false,"shutters":false,"planters":false,"sills":false,"porch":false,
                 "roof":{"style":"flat","stairs":"quartz_stairs","slab":"quartz_slab","fill":"white_concrete","overhang":0}}
                """));
        assertTrue(result.isOk(),result.summary());
        ShapeResolver.resolve(model,info);
        var report=StructureAnalyzer.analyze(model,info,new AnalyzerOptions());
        assertEquals(1,report.doors);
        assertFalse(report.hasCode("no_enclosed_interior"),report.toText());
        assertEquals("white_concrete",model.get(0,2,0).name());
        assertNull(model.get(5,4,10));
        model.forEach((x,y,z,s) -> assertFalse(s.name().endsWith("_log") || s.name().startsWith("potted_")
                || s.name().endsWith("_trapdoor"),s.toString()));
    }

    @Test void anExplicitRecessedEntranceDoesNotGetReplacedByAGhostFrontDoor() {
        StubBlockInfo info=new StubBlockInfo();
        VoxelModel model=new VoxelModel();
        var result=new OpExecutor(info).apply(model,JsonParser.parseString("""
                {"ops":[{"op":"building","from":[0,0,0],"to":[8,4,8],"porch":false},
                  {"op":"clear","from":[3,1,6],"to":[5,3,8]},
                  {"op":"set","pos":[4,1,6],"block":"dark_oak_door[facing=north]"}]}
                """));
        assertTrue(result.isOk(),result.summary());
        var entrance=result.doorways.getFirst().followCompiledEntrance(model,info);
        assertEquals(6,entrance.z());
        entrance.restore(model,info);
        assertEquals("dark_oak_door",model.get(4,1,6).name());
        assertFalse(model.isSet(4,1,8) && model.get(4,1,8).name().endsWith("_door"));
    }
}
