package dev.aibuild.analysis;

import dev.aibuild.model.BlockSpec;
import dev.aibuild.model.StubBlockInfo;
import dev.aibuild.model.VoxelModel;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the main-body footprint and the size checks around it.
 *
 * <p>Live defect: a fachwerk house 10&times;10 with open windows was measured as a 2&times;6 body, because the
 * ring cut by windows at one level left a small wall fragment as the "largest component" and the vote
 * tie-break preferred the smallest box. The measured size then failed the user's "10 на 10" as a false
 * MAJOR, and the build was left a draft. These tests pin the fixed selection: the body is the maximal box
 * carrying the most wall cells, small sizes get a one-block tolerance, and a height expectation only applies
 * when the request actually names one.</p>
 */
class FootprintCheck {

    private final StubBlockInfo info = new StubBlockInfo();

    /** A fachwerk-style shell: 10x10 walls with window openings at the middle level and a porch column. */
    private VoxelModel house() {
        VoxelModel m = new VoxelModel();
        for (int y = 1; y <= 5; y++) {
            for (int x = 0; x <= 9; x++) {
                for (int z = 0; z <= 9; z++) {
                    boolean edge = x == 0 || x == 9 || z == 0 || z == 9;
                    if (!edge) continue;
                    // open windows cut the ring at the middle levels on the south and north walls
                    boolean window = y == 3 && (z == 0 || z == 9) && x >= 2 && x <= 7;
                    if (window) continue;
                    m.set(x, y, z, BlockSpec.of("white_terracotta"));
                }
            }
        }
        // a fragment left over at the window level: 2x6, exactly the live failure
        for (int z = 2; z <= 7; z++) m.set(1, 4, z, BlockSpec.of("white_terracotta"));
        // a porch column sticking out one block to the west
        for (int z = 3; z <= 6; z++) m.set(-1, 1, z, BlockSpec.of("dark_oak_log"));
        // ground layer under the walls so the model has a floor
        for (int x = 0; x <= 9; x++) for (int z = 0; z <= 9; z++) m.set(x, 0, z, BlockSpec.of("stone_bricks"));
        return m;
    }

    /** A terrain slab: solid, no hollow — must never be picked as a body over a real walled box. */
    private VoxelModel terrainWithHouse() {
        VoxelModel m = house();
        for (int x = 20; x <= 45; x++) {
            for (int z = -15; z <= 15; z++) {
                for (int y = 1; y <= 3; y++) m.set(x, y, z, BlockSpec.of("grass_block"));
            }
        }
        return m;
    }

    private AnalysisReport analyze(VoxelModel model, int ex, int ez) {
        AnalyzerOptions o = new AnalyzerOptions(true);
        o.expectedFootprint(ex, ez);
        return StructureAnalyzer.analyze(model, info, o);
    }

    @Test
    void aHouseCutByWindowsMeasuresItsRealFootprint() {
        AnalysisReport rep = analyze(house(), 10, 10);
        int fx = (int) rep.metrics.get("footprintX");
        int fz = (int) rep.metrics.get("footprintZ");
        // the walls are 10x10; the porch column may widen the box by one on the west
        assertTrue(Math.abs(fx - 10) <= 1, "footprintX=" + fx);
        assertEquals(10, fz, "footprintZ");
    }

    @Test
    void theWindowFragmentIsNotTheBody() {
        AnalysisReport rep = analyze(house(), 10, 10);
        int fx = (int) rep.metrics.get("footprintX");
        int fz = (int) rep.metrics.get("footprintZ");
        assertFalse(fx <= 6 && fz <= 6, "the 2x6 window fragment must never win: " + fx + "x" + fz);
    }

    @Test
    void theFootprintToleranceAbsorbsAPorch() {
        AnalysisReport rep = analyze(house(), 10, 10);
        assertTrue(rep.issues.stream().noneMatch(i -> "footprint_mismatch".equals(i.code())),
                () -> "no footprint mismatch: " + rep.issues);
    }

    @Test
    void aSolidTerrainSlabIsNotPickedOverTheWalledBody() {
        AnalysisReport rep = analyze(terrainWithHouse(), 10, 10);
        int fx = (int) rep.metrics.get("footprintX");
        int fz = (int) rep.metrics.get("footprintZ");
        assertTrue(fx < 20, "the terrain slab must not become the body: " + fx);
        assertTrue(Math.abs(fx - 10) <= 1, "footprintX=" + fx);
        assertEquals(10, fz);
    }

    /** A garden pergola of logs leaning on the corner: not the body, even though it is wall-like. */
    @Test
    void aLogPergolaBesideTheHouseIsNotTheBody() {
        VoxelModel m = house();
        // three log columns right against the east wall, spanning y=1..3 (a live build had exactly this)
        for (int z = 2; z <= 4; z++) {
            for (int y = 1; y <= 3; y++) {
                m.set(10, y, z, BlockSpec.of("oak_log"));
                m.set(11, y, z, BlockSpec.of("spruce_log"));
            }
        }
        AnalysisReport rep = analyze(m, 10, 10);
        int fx = (int) rep.metrics.get("footprintX");
        int fz = (int) rep.metrics.get("footprintZ");
        assertTrue(Math.abs(fx - 10) <= 1, "the pergola must not widen the body: footprintX=" + fx);
        assertEquals(10, fz);
    }

    /** A raised podzol garden bed at wall level, occluding and touching the wall. */
    @Test
    void aRaisedGardenBedIsNotAWall() {
        VoxelModel m = house();
        for (int x = 10; x <= 12; x++) {
            for (int z = 0; z <= 9; z++) {
                m.set(x, 1, z, BlockSpec.of("podzol"));
            }
        }
        AnalysisReport rep = analyze(m, 10, 10);
        int fx = (int) rep.metrics.get("footprintX");
        assertTrue(Math.abs(fx - 10) <= 1, "the garden bed must not widen the body: footprintX=" + fx);
    }

    @Test
    void aWrongFootprintIsStillReported() {
        // the same house checked against an ask of 20x20 must fail — the check itself stays strict
        AnalysisReport rep = analyze(house(), 20, 20);
        assertTrue(rep.issues.stream().anyMatch(i -> "footprint_mismatch".equals(i.code())),
                "a real mismatch is still a finding");
    }

    @Test
    void theSizeToleranceIsNeverZeroForSmallBuilds() {
        // one block of porch or jetty on a 10-wide ask is not a defect; a real half-size miss still is
        assertTrue(0 < StructureAnalyzer.sizeToleranceFor(10));
        assertTrue(StructureAnalyzer.sizeToleranceFor(10) <= 1, "a 10-wide ask tolerates one block");
        assertEquals(2, StructureAnalyzer.sizeToleranceFor(25), "an 8% share for larger asks");
    }

    @Test
    void aZeroedHeightExpectationIsNotChecked() {
        // BuildRequirements zeroes the model-invented y for a request without a height ask; the analyzer
        // then has no y expectation at all and must not report one
        AnalyzerOptions o = new AnalyzerOptions(true);
        o.exactSize = new int[]{10, 0, 10};
        AnalysisReport rep = StructureAnalyzer.analyze(house(), info, o);
        assertTrue(rep.issues.stream().noneMatch(i -> "overall_size_mismatch".equals(i.code()) && i.message().contains("y size")),
                "no y mismatch without a height ask");
    }

    @Test
    void aHeightAskStillEnforces() {
        AnalyzerOptions o = new AnalyzerOptions(true);
        o.exactSize = new int[]{10, 18, 10};
        AnalysisReport rep = StructureAnalyzer.analyze(house(), info, o);
        assertTrue(rep.issues.stream().anyMatch(i -> "overall_size_mismatch".equals(i.code()) && i.message().contains("y size")),
                "an asked height is still checked");
    }

    @Test
    void floatingCellsAreExposedForTheFinisher() {
        VoxelModel m = new VoxelModel();
        m.set(0, 0, 0, BlockSpec.of("stone_bricks"));
        m.set(5, 6, 5, BlockSpec.of("oak_leaves"));
        m.set(5, 7, 5, BlockSpec.of("oak_leaves"));
        AnalysisReport rep = StructureAnalyzer.analyze(m, info, new AnalyzerOptions(false));
        assertTrue(rep.cells.containsKey("floating_all"), "all floating cells are exposed");
        assertTrue(rep.cells.get("floating_all").size() >= 2, "the whole cluster, not just samples");
        assertTrue(rep.cells.containsKey("floating_small"));
    }
}
