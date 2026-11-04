package dev.aibuild.script;

import dev.aibuild.analysis.AnalysisReport;
import dev.aibuild.analysis.AnalyzerOptions;
import dev.aibuild.analysis.StructureAnalyzer;
import dev.aibuild.model.BlockSpec;
import dev.aibuild.model.ShapeResolver;
import dev.aibuild.model.StubBlockInfo;
import dev.aibuild.model.VoxelModel;
import dev.aibuild.pipeline.BuildPipeline;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The 3.1 interior kit: furnish() presets, volume() rooms and stairs, and the analyzer's room check. */
class InteriorKitTest {
    private final StubBlockInfo info = new StubBlockInfo();
    private final ScriptRuntime runtime = new ScriptRuntime(info, Stdlib.V2);

    /** A 12 x 10 room with walls y 2..5, a ceiling at 6, a door in the south wall and two windows. */
    private static final String ROOM = """
            box(0, 1, 0, 11, 1, 9, 'oak_planks');
            box(0, 2, 0, 11, 5, 9, 'white_terracotta', {walls: true});
            box(0, 6, 0, 11, 6, 9, 'spruce_planks');
            door(5, 2, 9, 'south');
            block(0, 3, 4, 'glass_pane'); block(11, 3, 5, 'glass_pane');
            """;

    private ScriptResult run(String src) {
        ScriptResult r = runtime.run(src, 7, null, null, ScriptRuntime.Budget.standard(), null);
        assertTrue(r.ok(), () -> r.error().describe());
        ShapeResolver.resolve(r.model(), info);
        return r;
    }

    private static AnalysisReport analyze(VoxelModel m) {
        return StructureAnalyzer.analyze(m, new StubBlockInfo(), new AnalyzerOptions(true));
    }

    @Test void everyRoomTypeFurnishesInsideItsWallsAndKeepsTheDoorClear() {
        for (String type : List.of("bedroom", "kitchen", "dining", "living", "hall", "tavern", "library", "study", "workshop",
                "smithy", "storage", "shop", "chapel", "throne")) {
            VoxelModel bare = run(ROOM).model();
            ScriptResult r = run(ROOM + "log(furnish(1, 1, 10, 8, 1, '" + type + "').pieces.join(' '));");
            VoxelModel m = r.model();
            int added = 0;
            for (int x = -1; x <= 12; x++) for (int y = 0; y <= 8; y++) for (int z = -1; z <= 10; z++) {
                BlockSpec a = bare.get(x, y, z), b = m.get(x, y, z);
                boolean same = a == null || a.isAir() ? b == null || b.isAir() : a.equals(b);
                if (same) continue;
                assertTrue(x >= 1 && x <= 10 && z >= 1 && z <= 8 && y >= 1 && y <= 5, type + ": changed " + x + "," + y + "," + z);
                if (y >= 2) added++;
            }
            assertTrue(added >= 10, type + " placed only " + added + " blocks: " + r.logs());
            for (int y = 2; y <= 3; y++) {
                BlockSpec inFront = m.get(5, y, 8);
                assertTrue(inFront == null || inFront.isAir() || inFront.name().endsWith("_carpet"), type + ": the door is blocked by " + inFront);
            }
            AnalysisReport rep = analyze(m);
            assertFalse(rep.hasCode("bare_room"), type + ": " + rep.toText());
            assertEquals(0, rep.darkSpots, type + " leaves dark corners: " + rep.toText());
        }
    }

    @Test void analyzerFlagsAnEmptyRoomAndCountsFurniture() {
        AnalysisReport bare = analyze(run(ROOM).model());
        assertEquals(1, bare.roomList.size(), bare.toText());
        assertTrue(bare.hasCode("bare_room"), bare.toText());
        AnalysisReport.Room room = bare.roomList.getFirst();
        assertEquals(2, room.y());
        assertEquals(List.of(1, 1, 10, 8), List.of(room.x1(), room.z1(), room.x2(), room.z2()));
        AnalysisReport full = analyze(run(ROOM + "furnish(1, 1, 10, 8, 1, 'living');").model());
        assertFalse(full.hasCode("bare_room"), full.toText());
        assertTrue(full.roomList.getFirst().furniture() > 15, full.toText());
    }

    @Test void volumeFurnishesItsFloorsWithStairsAndPartitionsAndReplaceTakesARoomBack() {
        String house = "let h = volume(-9, -6, 9, 6, 1, {palette: 'medieval', floors: 2, rooms: [['kitchen', 'dining'], 'bedroom']});\n";
        ScriptResult r = run(house + "log(h.rooms.map(r => r.type + ':' + r.pieces.length).join(' '));");
        assertTrue(r.logs().getFirst().matches("kitchen:\\d+ dining:\\d+ bedroom:\\d+"), r.logs().toString());
        long stairs = r.model().counts().entrySet().stream().filter(e -> e.getKey().name().equals("spruce_stairs")).count();
        assertTrue(stairs > 0, "a staircase or chairs of the house wood");
        AnalysisReport rep = analyze(r.model());
        assertFalse(rep.hasCode("bare_room") || rep.hasCode("no_enclosed_interior"), rep.toText());
        // the partition's doorway splits the ground floor: kitchen, dining and the bedroom above
        assertEquals(3, rep.roomList.size(), rep.toText());
        assertEquals(2, rep.roomList.stream().filter(room -> room.y() == 2).count(), rep.toText());

        // refurnishing the ground floor as a tavern keeps the shell closed and leaves the upper floor alone
        ScriptResult t = run(house + "furnish(h.rooms[0].x1, h.rooms[0].z1, h.rooms[1].x2, h.rooms[1].z2, h.rooms[0].y, 'tavern', {replace: true});");
        AnalysisReport trep = analyze(t.model());
        assertTrue(trep.interiorVolume > 0 && !trep.hasCode("no_enclosed_interior"), trep.toText());
        for (int x = -7; x <= 7; x++) for (int z = -4; z <= 4; z++) for (int y = 6; y <= 12; y++) {
            BlockSpec a = r.model().get(x, y, z), b = t.model().get(x, y, z);
            assertEquals(a == null || a.isAir() ? null : a, b == null || b.isAir() ? null : b, "upper floor changed at " + x + "," + y + "," + z);
        }
        int outside = BuildPipeline.changesOutsideRooms(r.model(), t.model(), rep.roomList, info);
        assertEquals(0, outside, "refurnishing stays inside the rooms");
    }

    @Test void furnitureAgainstTheWallsMayChangeButTheShellMayNot() {
        VoxelModel bare = run(ROOM).model();
        VoxelModel shelf = run(ROOM + "block(1, 3, 4, 'bookshelf');").model();
        // on the server the pane in the wall gains east=true once a shelf stands beside it
        shelf.set(0, 3, 4, BlockSpec.of("glass_pane", java.util.Map.of("north", "true", "south", "true", "east", "true")));
        assertNotEquals(bare.get(0, 3, 4), shelf.get(0, 3, 4));
        assertEquals(0, BuildPipeline.changesOutsideRooms(bare, shelf, analyze(bare).roomList, info));
        VoxelModel hole = run(ROOM + "block(0, 3, 4, 'air');").model();
        assertEquals(1, BuildPipeline.changesOutsideRooms(bare, hole, analyze(bare).roomList, info), "removing the pane is a change");

        // barrels line the whole west wall, so the room box starts one cell further in; taking them out is still inside
        VoxelModel lined = run(ROOM + "for (let z = 1; z <= 8; z++) block(1, 2, z, 'barrel');").model();
        List<AnalysisReport.Room> rooms = analyze(lined).roomList;
        assertEquals(2, rooms.getFirst().x1(), analyze(lined).toText());
        assertEquals(0, BuildPipeline.changesOutsideRooms(lined, bare, rooms, info));
        VoxelModel breached = run(ROOM + "for (let z = 1; z <= 8; z++) block(1, 2, z, 'barrel'); block(0, 2, 2, 'air');").model();
        assertEquals(1, BuildPipeline.changesOutsideRooms(lined, breached, rooms, info), "a hole in the wall is a change");
    }

    @Test void refurnishingKeepsAStairBuiltOverEarlierFurniture() {
        // volume() furnishes the floors, then a later part puts a stair through the furniture; the interior
        // designer refurnishes the hall with replace:true and must not take the stair out
        String src = ROOM + "furnish(1, 1, 10, 8, 1, 'hall');\n"
                + "for (let i = 0; i < 4; i++) block(9, 2 + i, 1 + i, stair('oak_planks', 'south', 'bottom'));\n"
                + "furnish(1, 1, 10, 8, 1, 'tavern', {replace: true});\n";
        VoxelModel m = run(src).model();
        for (int i = 0; i < 4; i++) assertEquals("oak_stairs", m.get(9, 2 + i, 1 + i).name(), "stair step " + i);
    }

    @Test void wallDecorSnapsToTheRealWallAndLightsToTheRealCeiling() {
        // aimed into the wall (z = 0 is the north wall) and one cell too far out: both land on the inner face
        VoxelModel m = run(ROOM + "banner(4, 4, 0, 'south', 'red'); banner(6, 4, 2, 'south', 'blue');"
                + "lamp(5, 3, 5, {hanging: true}); chandelier(3, 2, 6, {drop: 1});").model();
        assertEquals("white_terracotta", m.get(4, 4, 0).name(), "the wall stays whole");
        assertEquals("red_wall_banner", m.get(4, 4, 1).name());
        assertEquals("blue_wall_banner", m.get(6, 4, 1).name());
        assertEquals("lantern", m.get(5, 5, 5).name(), "the lantern hangs under the ceiling at y 6");
        assertEquals("iron_chain", m.get(3, 5, 6).name());
        assertEquals("lantern", m.get(3, 4, 6).name());
        AnalysisReport rep = analyze(m);
        assertFalse(rep.hasCode("unsupported_attachment"), rep.toText());
    }

    @Test void scriptsReadTheRoomsTheyBuilt() {
        ScriptResult r = run(ROOM + "part('hall', () => block(1, 2, 1, 'barrel'));\n"
                + "const rs = rooms(); log(rs.length + ' ' + rs[0].x1 + ' ' + rs[0].z2 + ' ' + rs[0].y);\n"
                + "furnish(rs[0], 'library'); log(String(partBox('hall')));");
        assertEquals(List.of("1 1 8 2", "1,2,1,1,2,1"), r.logs());
        assertFalse(analyze(r.model()).hasCode("bare_room"));
    }

    @Test void partsAreReplacedOrAppended() {
        String src = "part('house', () => { box(0,1,0,1,1,1,'stone'); });\n";
        ScriptPatch.Result add = ScriptPatch.putPart(src, "interiors", "```js\npart('interiors', () => { block(0,2,0,'lantern'); });\n```");
        assertTrue(add.ok(), add.error());
        assertTrue(add.source().startsWith(src) && add.source().contains("part('interiors'"), add.source());
        ScriptPatch.Result again = ScriptPatch.putPart(add.source(), "interiors", "part('interiors', () => { block(1,2,0,'lantern'); });");
        assertTrue(again.ok() && again.source().contains("block(1,2,0") && !again.source().contains("block(0,2,0"), again.source());
        assertFalse(ScriptPatch.putPart(src, "interiors", "part('other', () => {});").ok());
    }
}
