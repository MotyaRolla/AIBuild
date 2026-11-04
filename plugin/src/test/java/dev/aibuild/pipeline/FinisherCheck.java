package dev.aibuild.pipeline;

import dev.aibuild.analysis.AnalysisReport;
import dev.aibuild.analysis.AnalyzerOptions;
import dev.aibuild.analysis.StructureAnalyzer;
import dev.aibuild.model.BlockSpec;
import dev.aibuild.model.StubBlockInfo;
import dev.aibuild.model.VoxelModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the mechanical finisher's handling of loose foliage and ladder rungs.
 *
 * <p>Live defect, from the sakura edit: the canopy edit left 13 floating blossom blocks. In Minecraft such
 * leaves decay and disappear, so the build develops holes later; the finisher only removed crumbs of up to 3
 * cells and left the rest. Loose organic material now settles onto the mass below it, and floating stone
 * stays for the critic (that is a design error, not a mechanical one).</p>
 *
 * <p>Second live defect: a ladder column in a fachwerk house kept three unsupported rungs after repair,
 * because each rung was excused by the rung below it — but a ladder rung hangs on the wall behind it, not on
 * the rung under it.</p>
 */
class FinisherCheck {

    private final StubBlockInfo info = new StubBlockInfo();

    @Test
    void looseFoliageSettlesOntoTheMassBelow() {
        VoxelModel m = new VoxelModel();
        // ground and a branch-like shelf
        m.set(0, 0, 0, BlockSpec.of("stone_bricks"));
        m.set(0, 1, 0, BlockSpec.of("cherry_log"));
        m.set(1, 1, 0, BlockSpec.of("cherry_log"));
        // a blossom cluster floating five above the shelf — leaves decay in game, so it must settle
        for (int x = 0; x <= 1; x++) {
            m.set(x, 6, 0, BlockSpec.of("cherry_leaves"));
            m.set(x, 7, 0, BlockSpec.of("cherry_leaves"));
        }
        Finisher.Result r = Finisher.plan(m, info, List.of(), false);
        assertNotNull(r.statement(), "the settle must produce a repair part");
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("settled")), () -> String.join("; ", r.notes()));
        // after the repair the cluster rests on the shelf
        AnalysisReport rep = StructureAnalyzer.analyze(repaired(m), info, new AnalyzerOptions(false));
        assertEquals(0, rep.metrics.get("floatingBlocks"), "nothing floats after the settle");
    }

    @Test
    void foliageThatCannotLandIsRemoved() {
        VoxelModel m = new VoxelModel();
        m.set(0, 0, 0, BlockSpec.of("stone_bricks"));
        m.set(9, 9, 9, BlockSpec.of("cherry_leaves"));
        Finisher.Result r = Finisher.plan(m, info, List.of(), false);
        AnalysisReport rep = StructureAnalyzer.analyze(repaired(m), info, new AnalyzerOptions(false));
        assertEquals(0, rep.metrics.get("floatingBlocks"), "an unsettled cluster is removed, not left to decay");
    }

    @Test
    void floatingStoneIsLeftForTheCritic() {
        VoxelModel m = new VoxelModel();
        m.set(0, 0, 0, BlockSpec.of("stone_bricks"));
        for (int x = 0; x <= 1; x++) for (int y = 6; y <= 7; y++) m.set(x, y, 0, BlockSpec.of("bricks"));
        Finisher.Result r = Finisher.plan(m, info, List.of(), false);
        AnalysisReport rep = StructureAnalyzer.analyze(repaired(m), info, new AnalyzerOptions(false));
        assertEquals(4, rep.metrics.get("floatingBlocks"),
                "stone is a design error the critic sees; the finisher does not touch it");
    }

    @Test
    void everyLadderRungGetsItsOwnWall() {
        VoxelModel m = new VoxelModel();
        // a ladder column facing south with air behind, and a solid wall to the west
        for (int y = 1; y <= 4; y++) {
            m.set(0, y, 0, BlockSpec.of("ladder", java.util.Map.of("facing", "south")));
            m.set(1, y, 0, BlockSpec.of("white_terracotta"));
        }
        m.set(0, 0, 0, BlockSpec.of("stone_bricks"));
        Finisher.Result r = Finisher.plan(m, info, List.of(), false);
        AnalysisReport rep = StructureAnalyzer.analyze(repaired(m), info, new AnalyzerOptions(false));
        assertEquals(0, rep.metrics.get("unsupportedAttachments"),
                "every rung is turned to the wall that holds it: " + rep.metrics.get("unsupportedAttachments"));
        // and the rungs are still ladders, not deleted
        VoxelModel after = repaired(m);
        int rungs = 0;
        for (int y = 1; y <= 4; y++) {
            BlockSpec s = after.get(0, y, 0);
            if (s != null && s.name().equals("ladder")) rungs++;
        }
        assertEquals(4, rungs, "the ladder survives, now attached to the real wall");
    }

    /** The repaired model, straight from the finisher. */
    private VoxelModel repaired(VoxelModel base) {
        return Finisher.repair(base, info, List.of(), false);
    }
}
