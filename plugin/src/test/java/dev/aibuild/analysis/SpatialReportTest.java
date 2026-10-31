package dev.aibuild.analysis;

import com.google.gson.JsonParser;
import dev.aibuild.model.BlockSpec;
import dev.aibuild.model.StubBlockInfo;
import dev.aibuild.model.ShapeResolver;
import dev.aibuild.model.VoxelModel;
import dev.aibuild.model.ops.OpExecutor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpatialReportTest {
    final StubBlockInfo info=new StubBlockInfo();

    VoxelModel house() {
        VoxelModel model=new VoxelModel();
        new OpExecutor(info).apply(model,JsonParser.parseString("{\"op\":\"building\",\"from\":[0,0,0],\"to\":[8,4,8]}"));
        model.set(3,1,3,info.parse("chest[facing=south]"));
        ShapeResolver.resolve(model,info);
        return model;
    }

    @Test void actualDoorAndFurnitureEvidenceChangesWhenTheRouteIsBlocked() {
        VoxelModel model=house();
        String before=SpatialReport.render(model,info);
        assertTrue(before.contains("[3,1,3] chest[facing=south] reachable-approach=true"),before);
        assertTrue(before.contains("[4,1,8] oak_door facing=north connects Z"),before);
        model.set(4,1,7,BlockSpec.of("stone"));
        model.set(4,2,7,BlockSpec.of("stone"));
        String after=SpatialReport.render(model,info);
        assertTrue(after.contains("[3,1,3] chest[facing=south] reachable-approach=false"),after);
        assertTrue(after.contains("opaque-block-above=false"),after);
        model.set(3,2,3,BlockSpec.of("stone"));
        assertTrue(SpatialReport.render(model,info).contains("opaque-block-above=true"));
    }

    @Test void individualFurnitureCanBeInaccessibleInsideAnAccessibleRoom() {
        VoxelModel model=house();
        for (int[] p : new int[][]{{2,3},{4,3},{3,2},{3,4}}) {
            model.set(p[0],1,p[1],BlockSpec.of("stone"));
            model.set(p[0],2,p[1],BlockSpec.of("stone"));
        }
        String report=SpatialReport.render(model,info);
        assertTrue(report.contains("[3,1,3] chest[facing=south] reachable-approach=false"),report);
        assertTrue(report.contains("oak_door facing=north connects Z"));
        assertTrue(report.contains("reachable-approach=true"));
    }

    @Test void boundedEvidenceIsDeterministicAndOversizedModelsSkipNavigation() {
        VoxelModel model=house();
        String first=SpatialReport.render(model,info);
        assertEquals(first,SpatialReport.render(model,info));
        assertTrue(first.length()<=SpatialReport.TEXT_LIMIT);
        assertTrue(first.contains("East-west skyline: x"));
        model.set(300,100,300,BlockSpec.of("stone"));
        assertTrue(SpatialReport.render(model,info).contains("volume limit"));
        assertTrue(SpatialReport.render(new VoxelModel(),info).contains("empty model"));
    }

    @Test void analyzerMutationsExposeActualStructuralDefects() {
        VoxelModel model=house();
        model.set(4,1,7,BlockSpec.of("stone")); model.set(4,2,7,BlockSpec.of("stone"));
        assertTrue(StructureAnalyzer.analyze(model,info,new AnalyzerOptions()).hasCode("door_blocked"));
        model=house(); model.set(20,20,20,BlockSpec.of("stone"));
        assertTrue(StructureAnalyzer.analyze(model,info,new AnalyzerOptions()).hasBlocking());
        model=house(); model.set(4,3,3,info.parse("wall_torch[facing=east]"));
        assertTrue(StructureAnalyzer.analyze(model,info,new AnalyzerOptions()).hasBlocking());
    }
}
