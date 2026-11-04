package dev.aibuild.pipeline;

import dev.aibuild.analysis.AnalysisReport;
import dev.aibuild.analysis.AnalyzerOptions;
import dev.aibuild.analysis.StructureAnalyzer;
import dev.aibuild.model.ShapeResolver;
import dev.aibuild.model.StubBlockInfo;
import dev.aibuild.model.VoxelModel;
import dev.aibuild.script.Collision;
import dev.aibuild.script.ScriptPatch;
import dev.aibuild.script.ScriptResult;
import dev.aibuild.script.ScriptRuntime;
import dev.aibuild.script.Stdlib;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 3.2: the collision ledger, the finisher, the quality vector and the analyzer's calibrated checks. */
class GroundedBuildTest {
    private final StubBlockInfo info = new StubBlockInfo();
    private final ScriptRuntime runtime = new ScriptRuntime(info, Stdlib.V2);

    /** A 12 x 10 room with walls y 2..5, a ceiling at 6 and a door in the south wall. */
    private static final String ROOM = """
            part('house', () => {
              box(0, 1, 0, 11, 1, 9, 'oak_planks');
              box(0, 2, 0, 11, 5, 9, 'white_terracotta', {walls: true});
              box(0, 6, 0, 11, 6, 9, 'spruce_planks');
              door(5, 2, 9, 'south');
            });
            """;

    private ScriptResult run(String src) {
        ScriptResult r = runtime.run(src, 7, null, null, ScriptRuntime.Budget.standard(), null);
        assertTrue(r.ok(), () -> r.error().describe());
        ShapeResolver.resolve(r.model(), info);
        return r;
    }

    private AnalysisReport analyze(VoxelModel m) {
        return StructureAnalyzer.analyze(m, info, new AnalyzerOptions(true));
    }

    @Test void theLedgerNamesDecorationInWallsAndRemovedStairsButNotJoins() {
        ScriptResult r = run(ROOM + """
                part('stair', () => { for (let i = 0; i < 3; i++) block(9, 2 + i, 2 + i, stair('oak_planks', 'south', 'bottom')); });
                part('tower', () => box(11, 1, 0, 13, 6, 2, 'stone_bricks'));
                part('decor', () => {
                  for (let x = 2; x <= 9; x++) block(x, 4, 0, 'red_wall_banner[facing=south]');
                  for (let i = 0; i < 3; i++) block(9, 2 + i, 2 + i, 'air');
                });
                """);
        List<Collision> c = r.collisions();
        assertTrue(c.stream().anyMatch(k -> k.kind().equals("embedded") && k.victim().equals("house") && k.culprit().equals("decor") && k.cells() == 8), c.toString());
        assertTrue(c.stream().anyMatch(k -> k.kind().equals("cleared") && k.victim().equals("stair") && k.cells() == 3), c.toString());
        assertTrue(c.stream().noneMatch(k -> k.culprit().equals("tower")), "a tower merging into the wall is a join: " + c);
        assertEquals(8, r.collisionCells(), "only collisions of 8+ cells count");
        assertTrue(r.collisionText().contains("decor put red_wall_banner into house's white_terracotta"), r.collisionText());
        // a cell rebuilt later is no longer damaged
        assertTrue(run(ROOM + "part('d', () => block(3, 4, 0, 'lantern')); part('fix', () => block(3, 4, 0, 'white_terracotta'));")
                .collisions().isEmpty());
    }

    @Test void theFinisherRestoresWallsHangsLightsClearsDoorsAndLightsRooms() {
        String src = ROOM + """
                part('decor', () => {
                  for (let x = 2; x <= 9; x++) block(x, 4, 0, 'red_wall_banner[facing=south]');
                  block(3, 3, 5, 'lantern[hanging=true]');
                  block(5, 2, 8, 'barrel');
                  block(7, 5, 7, 'torch');
                });
                """;
        ScriptResult r = run(src);
        AnalysisReport before = analyze(r.model());
        assertTrue(before.hasCode("unsupported_attachment") && before.hasCode("door_blocked"), before.toText());
        Finisher.Result f = Finisher.plan(r.model(), info, r.collisions(), true);
        assertNotNull(f.statement());
        ScriptPatch.Result patched = ScriptPatch.putPart(src, Finisher.PART, f.statement());
        assertTrue(patched.ok(), patched.error());
        ScriptResult done = run(patched.source());
        AnalysisReport after = analyze(done.model());
        assertFalse(after.hasCode("unsupported_attachment") || after.hasCode("door_blocked") || after.hasCode("dark_interior"), after.toText() + "\n" + f.notes());
        assertEquals("white_terracotta", done.model().get(4, 4, 0).name(), "the wall is whole again");
        assertEquals("red_wall_banner", done.model().get(4, 4, 1).name(), "the banner hangs on its face");
        assertEquals("lantern", done.model().get(3, 5, 5).name(), "the lantern moved up under the ceiling");
        assertEquals(0, done.collisionCells());
        // an unlit room gets a lantern under its ceiling
        ScriptResult bare = run(ROOM);
        assertTrue(analyze(bare.model()).hasCode("dark_interior"));
        Finisher.Result lit = Finisher.plan(bare.model(), info, bare.collisions(), true);
        AnalysisReport litRep = analyze(run(ScriptPatch.putPart(ROOM, Finisher.PART, lit.statement()).source()).model());
        assertFalse(litRep.hasCode("dark_interior"), litRep.toText() + "\n" + lit.notes());
        assertTrue(lit.statement().contains("lantern[hanging=true]"), lit.statement());
        QualityVector q0 = QualityVector.of(before, r), q1 = QualityVector.of(after, done);
        assertTrue(q1.regressions(q0).isEmpty(), q1.regressions(q0).toString());
        assertTrue(q1.fixesDefects(q0));
    }

    @Test void aRevisionThatLosesARoomIsWorse() {
        ScriptResult two = run(ROOM + "part('b', () => { box(0, 6, 0, 11, 10, 9, 'white_terracotta', {walls: true}); box(0, 11, 0, 11, 11, 9, 'spruce_planks');"
                + " for (let y = 2; y <= 8; y++) block(10, y, 8, 'ladder[facing=north]'); });");
        ScriptResult one = run(ROOM);
        QualityVector q2 = QualityVector.of(analyze(two.model()), two), q1 = QualityVector.of(analyze(one.model()), one);
        assertTrue(q2.rooms() > q1.rooms(), q2.describe());
        assertTrue(q1.regressions(q2).stream().anyMatch(s -> s.startsWith("rooms")), q1.regressions(q2).toString());
    }

    @Test void aSteppedHollowRoofIsNotFloatingButACornerTouchIs() {
        // a hollow stepped pyramid: each ring one in from the one below sits on it only along edges
        ScriptResult r = run("""
                box(0, 1, 0, 10, 4, 10, 'stone_bricks', {walls: true});
                for (let i = 0; i < 5; i++) box(i, 5 + i, i, 10 - i, 5 + i, 10 - i, 'dark_oak_planks', {walls: true});
                """);
        assertFalse(analyze(r.model()).hasCode("floating_blocks"), analyze(r.model()).toText());
        ScriptResult corner = run("box(0, 1, 0, 2, 3, 2, 'stone'); block(3, 4, 3, 'stone');");
        assertTrue(analyze(corner.model()).hasCode("floating_blocks"), "held by one corner only");
    }

    @Test void rewritingOnePartLeavesTheOtherPartsRandomDetailAlone() {
        String yard = "part('yard', () => { for (let i = 0; i < 40; i++) block(randInt(-20, 20), 1, randInt(-20, 20), pick(['barrel', 'hay_block', 'composter'])); });";
        ScriptResult a = run("part('wall', () => { for (let i = 0; i < 5; i++) block(randInt(30, 40), 1, 0, 'stone'); });" + yard);
        ScriptResult b = run("part('wall', () => { for (let i = 0; i < 17; i++) block(randInt(30, 40), 2, 0, 'stone'); });" + yard);
        a.owners().forEach((k, o) -> {
            if (o.equals("yard")) assertEquals(a.model().get(VoxelModel.kx(k), VoxelModel.ky(k), VoxelModel.kz(k)),
                    b.model().get(VoxelModel.kx(k), VoxelModel.ky(k), VoxelModel.kz(k)), "the yard's random barrels did not move");
        });
    }

    @Test void treesGiveWayToWalls() {
        String wall = "part('wall', () => box(-1, 1, -10, 1, 12, 10, 'stone_bricks'));";
        // planted into the wall: left out
        ScriptResult into = run(wall + "part('woods', () => tree(0, 1, 0, {species: 'oak', h: 8}));");
        assertTrue(into.model().entries().values().stream().noneMatch(s -> s.name().startsWith("oak_")), "no oak logs or leaves");
        assertEquals(756, into.model().nonAirCount(), "the wall is whole");
        assertTrue(into.warnings().stream().anyMatch(w -> w.contains("would grow into wall")), into.warnings().toString());
        // a wall built through a tree afterwards takes the whole tree out
        ScriptResult cut = run("part('woods', () => tree(0, 1, 0, {species: 'oak', h: 8}));" + wall);
        assertEquals(756, cut.model().nonAirCount(), "only the wall is left");
        assertTrue(cut.warnings().stream().anyMatch(w -> w.contains("cut through by wall")), cut.warnings().toString());
        // in open ground the tree stands
        assertTrue(run(wall + "part('woods', () => tree(20, 1, 0, {species: 'oak', h: 8}));").model().nonAirCount() > 150);
    }

    @Test void theInteriorPartCannotBreakTheBuildingAroundTheRooms() {
        ScriptResult r = run(ROOM + """
                part('stair', () => { for (let i = 0; i < 3; i++) block(9, 2 + i, 2 + i, stair('oak_planks', 'south', 'bottom')); });
                part('interiors', () => {
                  box(1, 2, 1, 10, 4, 8, 'air');
                  block(2, 2, 2, 'barrel');
                  block(0, 3, 4, 'bookshelf');
                });
                """);
        assertEquals("oak_stairs", r.model().get(9, 3, 3).name(), "the stair stays");
        assertEquals("white_terracotta", r.model().get(0, 3, 4).name(), "the wall stays");
        assertEquals("barrel", r.model().get(2, 2, 2).name(), "furniture in the room is placed");
        assertTrue(r.collisions().isEmpty(), r.collisionText());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("interior writes into the building")), r.warnings().toString());
    }

    @Test void aMovedWallMayDisplaceGrassButNotANeighboursWall() {
        String ground = "part('ground', () => { box(-10, 0, -10, 10, 0, 10, 'grass_block'); box(-10, 1, -10, 10, 1, 10, 'short_grass'); });";
        String house = "part('house', () => box(0, 1, 0, 4, 4, 4, 'stone_bricks', {walls: true}));";
        ScriptResult before = run(ground + "part('wall', () => box(-6, 1, -6, -6, 4, 6, 'cobblestone'));" + house);
        ScriptResult grassOnly = run(ground + "part('wall', () => box(-8, 1, -6, -8, 4, 6, 'cobblestone'));" + house);
        ScriptResult intoHouse = run(ground + "part('wall', () => box(-6, 1, -6, -6, 4, 6, 'cobblestone')); part('wall2', () => {});"
                + "part('house', () => box(0, 1, 0, 4, 4, 4, 'stone_bricks', {walls: true}));"
                + "part('cut', () => box(2, 1, 0, 2, 4, 0, 'air'));");
        assertEquals(0, BuildPipeline.foreignLoss(before, grassOnly, "wall", info), "grass under the new wall line is not breakage");
        assertEquals(4, BuildPipeline.foreignLoss(before, intoHouse, "cut", info));
    }

    @Test void theFinisherTakesTallPlantsAwayWhole() {
        String src = "part('moat', () => { box(-3, -2, -3, 3, -1, 3, 'water'); box(4, 0, -3, 6, 0, 3, 'grass_block'); box(-3, 0, 2, 3, 3, 3, 'stone'); block(0, 3, 0, 'tall_grass'); block(1, 3, 0, 'sugar_cane'); block(1, 4, 0, 'sugar_cane'); });";
        ScriptResult r = run(src);
        Finisher.Result f = Finisher.plan(r.model(), info, r.collisions(), true);
        ScriptResult done = run(ScriptPatch.putPart(src, Finisher.PART, f.statement()).source());
        for (int y = 3; y <= 4; y++) {
            assertNull(done.model().get(0, y, 0) == null || done.model().get(0, y, 0).isAir() ? null : done.model().get(0, y, 0), "tall grass y=" + y);
            assertNull(done.model().get(1, y, 0) == null || done.model().get(1, y, 0).isAir() ? null : done.model().get(1, y, 0), "sugar cane y=" + y);
        }
    }

    @Test void aHouseStandsAgainstTheCurtainWallInsteadOfCuttingIntoIt() {
        String wall = "part('wall', () => box(-20, 1, 0, 20, 10, 2, 'stone_bricks'));";
        ScriptResult r = run(wall + "part('smithy', () => volume(-4, 1, 4, 8, 1, {floors: 1}));");
        for (int x = -20; x <= 20; x++) for (int y = 1; y <= 10; y++) for (int z = 0; z <= 2; z++)
            assertEquals("stone_bricks", r.model().get(x, y, z).name(), "the wall is whole at " + x + "," + y + "," + z);
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("ran into wall")), r.warnings().toString());
        // built the other way round, the wall cuts the house and the critic is told
        ScriptResult late = run("part('smithy', () => volume(-4, 1, 4, 8, 1, {floors: 1}));" + wall);
        assertTrue(late.warnings().stream().anyMatch(w -> w.contains("cut through by wall")), late.warnings().toString());
    }

    @Test void theFinisherMakesLadderColumnsClimbable() {
        String src = """
                part('tower', () => {
                  box(0, 0, 0, 6, 0, 6, 'stone_bricks');
                  box(0, 1, 0, 6, 12, 6, 'stone_bricks', {walls: true});
                  box(1, 6, 1, 5, 6, 5, 'spruce_planks');
                  box(1, 12, 1, 5, 12, 5, 'spruce_planks');
                  block(3, 3, 0, 'glass_pane');
                  for (let y = 1; y <= 5; y++) block(3, y, 1, 'ladder[facing=south]');
                  for (let y = 7; y <= 11; y++) block(3, y, 1, y === 9 ? 'ladder[facing=west]' : 'ladder[facing=south]');
                });
                """;
        ScriptResult r = run(src);
        Finisher.Result f = Finisher.plan(r.model(), info, r.collisions(), true);
        VoxelModel m = run(ScriptPatch.putPart(src, Finisher.PART, f.statement()).source()).model();
        for (int y = 1; y <= 12; y++) {
            assertEquals("ladder", m.get(3, y, 1).name(), "rung at y=" + y + " " + f.notes());
            assertEquals("south", m.get(3, y, 1).prop("facing"), "facing at y=" + y);
        }
        assertEquals("stone_bricks", m.get(3, 3, 0).name(), "the pane behind the ladder became wall");
    }

    @Test void theExactSizeMeasuresTheBuildingNotItsLandscapedPlot() {
        String house = "box(-8, 1, -8, 8, 10, 8, 'stone_bricks', {walls: true}); box(-8, 11, -8, 8, 11, 8, 'stone_bricks');";
        AnalyzerOptions o = new AnalyzerOptions(false);
        o.exactSize = new int[]{17, 11, 17};
        AnalysisReport plot = StructureAnalyzer.analyze(run("box(-40, 0, -40, 40, 0, 40, 'grass_block');"
                + "for (let x = -36; x <= 36; x += 12) tree(x, 1, -34, {species: 'oak', h: 8});" + house).model(), info, o);
        assertFalse(plot.hasCode("overall_size_mismatch"), plot.toText());
        o.exactSize = new int[]{30, 11, 30};
        assertTrue(StructureAnalyzer.analyze(run(house).model(), info, o).hasCode("overall_size_mismatch"));
        // a scene sized as a whole: a lantern found as the "main body" must not make it a mismatch
        o.exactSize = new int[]{81, 11, 81};
        AnalysisReport scene = StructureAnalyzer.analyze(run("box(-40, 0, -40, 40, 0, 40, 'grass_block');"
                + "box(-1, 1, -1, 1, 3, 1, 'stone_bricks', {walls: true}); box(-1, 4, -1, 1, 4, 1, 'stone_bricks');"
                + "for (let x = -36; x <= 36; x += 12) tree(x, 1, -34, {species: 'oak', h: 10});").model(), info, o);
        assertFalse(scene.issues.stream().anyMatch(i -> "overall_size_mismatch".equals(i.code()) && !i.message().contains("y size")),
                scene.toText());
    }
}
