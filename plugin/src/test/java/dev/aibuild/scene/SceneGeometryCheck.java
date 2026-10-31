package dev.aibuild.scene;

import com.google.gson.JsonParser;
import dev.aibuild.model.BlockSpec;
import dev.aibuild.model.VoxelModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract checks for {@link SceneGeometry}: every object type produces blocks, openings really cut
 * their host, roofs are closed and well formed, the result is deterministic, and a Minecraft-sized
 * house builds fast enough for an iteration loop.
 *
 * <p>The tests build small scenes isomorphically to what the AI writes and inspect the resulting
 * {@link VoxelModel}, so a failure points at the geometry rather than at a rendering detail.</p>
 */
class SceneGeometryCheck {

    /** Builds a spec from a JSON fragment whose {@code objects} array is given inline. */
    private static SceneSpec spec(String objectsJson) {
        return spec(objectsJson, "");
    }

    private static SceneSpec spec(String objectsJson, String extra) {
        String json = "{\"name\":\"check\",\"seed\":42" + extra + ",\"objects\":[" + objectsJson + "]}";
        return SceneSpec.parse(JsonParser.parseString(json).getAsJsonObject());
    }

    private static SceneObject object(String objectJson) {
        SceneSpec s = spec(objectJson);
        assertTrue(s.errors().isEmpty(), () -> "spec rejected: " + s.errors());
        return s.objects().get(0);
    }

    /**
     * The 17 types of {@link SceneObject#TYPES} with the minimal fields each one needs. Kept in one
     * place so "add a type" cannot silently skip a test.
     */
    private static Map<String, SceneObject> oneOfEach() {
        Map<String, SceneObject> map = new LinkedHashMapish<>();
        map.put("box", object("{\"type\":\"box\",\"from\":[0,0,0],\"to\":[3,2,3],\"material\":\"wall\"}"));
        map.put("prism", object("{\"type\":\"prism\",\"from\":[0,3,0],\"to\":[6,3,4],\"style\":\"gable\","
                + "\"material\":\"roof\",\"overhang\":1}"));
        map.put("cylinder", object("{\"type\":\"cylinder\",\"center\":[0,0,0],\"radius\":3,\"height\":5,"
                + "\"material\":\"stone\"}"));
        map.put("cone", object("{\"type\":\"cone\",\"center\":[0,0,0],\"radius\":3,\"height\":5,"
                + "\"material\":\"roof\"}"));
        map.put("sphere", object("{\"type\":\"sphere\",\"center\":[0,3,0],\"radius\":3,"
                + "\"material\":\"stone\",\"half\":\"full\"}"));
        map.put("ring", object("{\"type\":\"ring\",\"from\":[0,4,0],\"to\":[6,4,6],\"y\":4,"
                + "\"material\":\"trim\",\"profile\":\"cornice\",\"thickness\":2}"));
        map.put("wall", object("{\"type\":\"wall\",\"from\":[0,0,0],\"to\":[6,0,0],\"height\":4,"
                + "\"material\":\"wall\"}"));
        map.put("floor", object("{\"type\":\"floor\",\"from\":[0,0,0],\"to\":[5,0,5],"
                + "\"material\":\"stone\"}"));
        map.put("stairs", object("{\"type\":\"stairs\",\"from\":[0,0,0],\"to\":[1,5,4],\"axis\":\"z\","
                + "\"material\":\"wood\"}"));
        map.put("window", object("{\"type\":\"window\",\"wall\":\"south\",\"at\":[0,1],\"w\":1,\"h\":2,"
                + "\"frame\":\"frame\",\"glass\":\"glass\"}"));
        map.put("door", object("{\"type\":\"door\",\"wall\":\"south\",\"at\":[0,1],\"w\":1,\"h\":2,"
                + "\"frame\":\"frame\"}"));
        map.put("arch", object("{\"type\":\"arch\",\"wall\":\"south\",\"at\":[0,0],\"w\":3,\"h\":3,"
                + "\"frame\":\"frame\"}"));
        map.put("balustrade", object("{\"type\":\"balustrade\",\"from\":[0,3,0],\"to\":[6,4,0],"
                + "\"material\":\"stone\"}"));
        map.put("beam", object("{\"type\":\"beam\",\"from\":[0,2,0],\"to\":[6,2,0],"
                + "\"material\":\"frame\"}"));
        map.put("column", object("{\"type\":\"column\",\"at\":[0,0,0],\"height\":5,\"radius\":1,"
                + "\"material\":\"stone\"}"));
        map.put("chimney", object("{\"type\":\"chimney\",\"at\":[0,0,0],\"height\":5,"
                + "\"material\":\"brick\"}"));
        map.put("detail", object("{\"type\":\"detail\",\"cells\":[[0,0,0],[0,1,0],[1,0,0]],"
                + "\"material\":\"leaf\"}"));
        return map;
    }

    /** A tiny insertion-ordered map, so the type list reads as documentation. */
    private static final class LinkedHashMapish<K, V> extends java.util.LinkedHashMap<K, V> {
    }

    // ------------------------------------------------------------------ types

    @Test
    void everyTypeYieldsBlocks() {
        Map<String, SceneObject> objects = oneOfEach();
        assertEquals(SceneObject.TYPES.size(), objects.size(),
                "the check must cover every type in SceneObject.TYPES");
        for (String type : SceneObject.TYPES) {
            assertTrue(objects.containsKey(type), () -> "missing sample for type " + type);
        }
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, SceneObject> e : objects.entrySet()) {
            VoxelModel model = SceneGeometry.buildObject(e.getValue(), spec(""));
            if (model.isEmpty()) {
                problems.add(e.getKey() + " produced no voxels");
                continue;
            }
            for (BlockSpec block : model.entries().values()) {
                if (block.isAir()) problems.add(e.getKey() + " produced air at a solid cell");
                if (block.name().startsWith("minecraft:")) {
                    problems.add(e.getKey() + " produced a namespaced block id: " + block);
                }
            }
        }
        assertTrue(problems.isEmpty(), () -> String.join("; ", problems));
    }

    @Test
    void standaloneOpeningsYieldFramesAndGlass() {
        VoxelModel window = SceneGeometry.buildObject(
                object("{\"type\":\"window\",\"wall\":\"south\",\"at\":[2,2],\"w\":2,\"h\":2,"
                        + "\"frame\":\"frame\",\"glass\":\"glass\"}"), spec(""));
        Set<String> names = new LinkedHashSet<>();
        for (BlockSpec b : window.entries().values()) names.add(b.name());
        assertTrue(names.contains("dark_oak_log"), () -> "no frame blocks: " + names);
        assertTrue(names.contains("glass_pane") || names.contains("glass"),
                () -> "no glass blocks: " + names);
    }

    // ------------------------------------------------------------------ openings cut

    @Test
    void openingReducesTheVoxelCount() {
        int withDoor = SceneGeometry.build(spec(
                "{\"type\":\"box\",\"from\":[0,0,0],\"to\":[9,5,9],\"material\":\"wall\",\"hollow\":true,"
                        + "\"openings\":[{\"wall\":\"south\",\"x\":4,\"y\":1,\"w\":2,\"h\":3,"
                        + "\"kind\":\"door\"}]}")).nonAirCount();
        int withoutDoor = SceneGeometry.build(spec(
                "{\"type\":\"box\",\"from\":[0,0,0],\"to\":[9,5,9],\"material\":\"wall\","
                        + "\"hollow\":true}")).nonAirCount();

        assertTrue(withDoor < withoutDoor,
                () -> "the door should remove material: " + withDoor + " vs " + withoutDoor);
        // A 2x3 door in a 1-thick wall removes exactly six cells, and its frame only recolours the
        // ring around them, so the difference is precisely the hole.
        assertEquals(6, withoutDoor - withDoor,
                "a 2x3 door in a one-cell wall should remove six cells");
    }

    @Test
    void standaloneWindowReallyOpensTheWall() {
        SceneSpec s = spec(""
                + "{\"id\":\"body\",\"type\":\"box\",\"from\":[0,0,0],\"to\":[7,4,7],\"material\":\"wall\","
                + "\"hollow\":true},"
                + "{\"id\":\"w\",\"type\":\"window\",\"wall\":\"south\",\"at\":[3,2],\"w\":2,\"h\":2,"
                + "\"frame\":\"frame\",\"glass\":\"glass\"}");
        VoxelModel model = SceneGeometry.build(s);
        assertTrue(model.isSet(3, 2, 7), "the window frame/glass should occupy the wall plane");

        // Every aperture cell must have lost its wall material: the cells are either glass or frame,
        // never the plain wall block, and the wall behind them must be gone.
        for (int x = 3; x <= 4; x++) {
            for (int y = 2; y <= 3; y++) {
                BlockSpec here = model.get(x, y, 7);
                String where = "aperture cell " + x + "," + y;
                assertNotNull(here, where + " was not filled");
                assertFalse("white_terracotta".equals(here.name()), "wall was not cut at " + where + ": " + here);
                assertTrue(isTransparentBlock(here.name()), where + " is opaque: " + here);
            }
        }
    }

    /** @return true for the blocks an aperture may legitimately hold */
    private static boolean isTransparentBlock(String name) {
        return name.contains("glass") || name.equals("air");
    }

    @Test
    void archCutsAnArchedHole() {
        int withArch = SceneGeometry.build(spec(
                "{\"type\":\"box\",\"from\":[0,0,0],\"to\":[9,6,9],\"material\":\"wall\",\"hollow\":true},"
                        + "{\"type\":\"arch\",\"wall\":\"south\",\"at\":[3,1],\"w\":4,\"h\":3}")).size();
        int plain = SceneGeometry.build(spec(
                "{\"type\":\"box\",\"from\":[0,0,0],\"to\":[9,6,9],\"material\":\"wall\",\"hollow\":true}")).size();
        assertTrue(withArch < plain, () -> "the arch should remove material: " + withArch + " vs " + plain);
    }

    // ------------------------------------------------------------------ roofs

    @Test
    void gableRoofHasNoHolesAboveTheWalls() {
        // A closed box topped by a gable roof: every column of the roof footprint must be solid from
        // the wall top all the way to the roof surface, so no sky is visible through the roof.
        SceneSpec s = spec(""
                + "{\"id\":\"walls\",\"type\":\"box\",\"from\":[0,0,0],\"to\":[9,5,9],\"material\":\"wall\","
                + "\"hollow\":true},"
                + "{\"id\":\"roof\",\"type\":\"prism\",\"from\":[0,6,0],\"to\":[9,6,9],\"style\":\"gable\","
                + "\"ridge\":\"x\",\"material\":\"roof\",\"overhang\":1}");
        VoxelModel model = SceneGeometry.build(s);

        List<String> holes = new ArrayList<>();
        for (int x = 0; x <= 9; x++) {
            for (int z = 0; z <= 9; z++) {
                // Walk down from high above: the first solid cell must be the roof, not a wall below
                // a gap. If a gap exists, the loop finds the wall top while the roof is above it.
                int firstSolid = -1;
                for (int y = 20; y >= 6; y--) {
                    BlockSpec b = model.get(x, y, z);
                    if (b != null && !b.isAir()) {
                        firstSolid = y;
                        break;
                    }
                }
                if (firstSolid < 0) {
                    holes.add("no roof over " + x + "," + z);
                    continue;
                }
                int roofY = firstSolid;
                for (int y = 6; y <= roofY; y++) {
                    BlockSpec b = model.get(x, y, z);
                    if (b == null || b.isAir()) {
                        holes.add("gap at " + x + "," + y + "," + z);
                        break;
                    }
                }
            }
        }
        assertTrue(holes.isEmpty(), () -> "roof is not closed: " + holes.subList(0, Math.min(6, holes.size())));
    }

    @Test
    void roofOverhangExtendsPastTheFootprint() {
        VoxelModel roof = SceneGeometry.buildObject(object(
                "{\"type\":\"prism\",\"from\":[2,6,2],\"to\":[7,6,7],\"style\":\"gable\","
                        + "\"material\":\"roof\",\"overhang\":1}"), spec(""));
        int[] bounds = roof.solidBounds();
        assertNotNull(bounds);
        assertTrue(bounds[0] <= 1 && bounds[2] <= 1,
                () -> "the overhang should reach one cell outside the footprint: "
                        + java.util.Arrays.toString(bounds));
        assertTrue(bounds[3] >= 8 && bounds[5] >= 8,
                () -> "the overhang should reach one cell outside the footprint: "
                        + java.util.Arrays.toString(bounds));
    }

    @Test
    void allRoofStylesProduceSlopedRoofBlocks() {
        for (String style : List.of("gable", "hip", "shed", "pyramid")) {
            VoxelModel model = SceneGeometry.buildObject(object(
                    "{\"type\":\"prism\",\"from\":[0,4,0],\"to\":[7,4,5],\"style\":\"" + style + "\","
                            + "\"material\":\"roof\"}"), spec(""));
            assertFalse(model.isEmpty(), () -> style + " roof is empty");
            long stairs = model.entries().values().stream()
                    .filter(b -> b.name().endsWith("_stairs")).count();
            long slabs = model.entries().values().stream()
                    .filter(b -> b.name().endsWith("_slab")).count();
            assertTrue(stairs > 0, () -> style + " roof produced no stairs (slabs=" + slabs + ")");
        }
    }

    @Test
    void steepRoofsBecomeStairsAndShallowOnesSlabs() {
        VoxelModel steep = SceneGeometry.buildObject(object(
                "{\"type\":\"prism\",\"from\":[0,4,0],\"to\":[7,4,7],\"style\":\"gable\","
                        + "\"material\":\"roof\"}"), spec(""));
        assertTrue(steep.entries().values().stream().anyMatch(b -> b.name().endsWith("_stairs")),
                "a 1:1 gable roof should produce stairs");

        VoxelModel shallow = SceneGeometry.buildObject(object(
                "{\"type\":\"prism\",\"from\":[0,4,0],\"to\":[15,4,15],\"style\":\"shed\","
                        + "\"material\":\"roof\",\"pitch\":0.25}"), spec(""));
        List<String> kinds = new ArrayList<>();
        for (BlockSpec b : shallow.entries().values()) kinds.add(b.name());
        assertTrue(kinds.stream().anyMatch(n -> n.endsWith("_slab")),
                () -> "a shallow roof should read as slabs, got " + new HashSet<>(kinds));
    }

    @Test
    void stairsFaceUphill() {
        // A gable roof with ridge along x rises towards the north for the south half: the stair's
        // full face must point north there, and south on the north half.
        SceneSpec s = spec("{\"type\":\"prism\",\"from\":[0,4,0],\"to\":[5,4,8],\"style\":\"gable\","
                + "\"ridge\":\"x\",\"material\":\"roof\"}");
        VoxelModel model = SceneGeometry.build(s);
        int checked = 0;
        for (long k : model.entries().keySet()) {
            BlockSpec b = model.get(VoxelModel.kx(k), VoxelModel.ky(k), VoxelModel.kz(k));
            if (!b.name().endsWith("_stairs")) continue;
            int z = VoxelModel.kz(k);
            String facing = b.prop("facing");
            String where = "stair at " + VoxelModel.kx(k) + "," + VoxelModel.ky(k) + "," + z + " faces " + facing;
            if (z < 4) {
                // North half: the surface rises towards +z, so uphill (and the stair's full side)
                // points south, towards the ridge.
                assertEquals("south", facing, "north slope must step up towards the ridge; " + where);
                checked++;
            } else if (z > 4) {
                assertEquals("north", facing, "south slope must step up towards the ridge; " + where);
                checked++;
            }
        }
        int facingChecks = checked;
        assertTrue(facingChecks >= 8, "checked only " + facingChecks + " stair facings");
    }

    // ------------------------------------------------------------------ structural soundness

    @Test
    void houseHasNoFloatingBlocks() {
        VoxelModel model = SceneGeometry.build(house10x10x15());
        int floating = countFloating(model);
        assertEquals(0, floating, () -> floating + " block(s) are not connected to the ground");
    }

    @Test
    void oddObjectsDoNotFloatEither() {
        // Cornices, balustrades and chimneys are the usual suspects: they overhang by design and must
        // still connect to the structure through their own cells.
        SceneSpec s = spec(""
                + "{\"id\":\"body\",\"type\":\"box\",\"from\":[0,0,0],\"to\":[9,7,9],\"material\":\"wall\","
                + "\"hollow\":true},"
                + "{\"id\":\"roof\",\"type\":\"prism\",\"from\":[0,8,0],\"to\":[9,8,9],\"style\":\"hip\","
                + "\"material\":\"roof\",\"overhang\":1},"
                + "{\"id\":\"cornice\",\"type\":\"ring\",\"from\":[0,7,0],\"to\":[9,7,9],\"y\":7,"
                + "\"material\":\"trim\",\"profile\":\"cornice\",\"thickness\":2},"
                + "{\"id\":\"chimney\",\"type\":\"chimney\",\"at\":[8,8,1],\"height\":5,"
                + "\"material\":\"brick\"}");
        VoxelModel model = SceneGeometry.build(s);
        assertEquals(0, countFloating(model), "decorative parts must stay attached");
    }

    @Test
    void structureIsASingleConnectedComponent() {
        // A cornice deliberately cantilevers one cell, so "supported straight down" is too strict for
        // architecture; "one connected piece" is the property that actually matters. An island would
        // mean an object floated free of the building.
        VoxelModel model = SceneGeometry.build(house10x10x15());
        Set<Long> solid = new HashSet<>();
        for (Map.Entry<Long, BlockSpec> e : model.entries().entrySet()) {
            if (!e.getValue().isAir()) solid.add(e.getKey());
        }
        assertFalse(solid.isEmpty(), "the house should have blocks");

        Set<Long> seen = new HashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        long start = solid.iterator().next();
        seen.add(start);
        queue.add(start);
        int[][] steps = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!queue.isEmpty()) {
            long k = queue.poll();
            int x = VoxelModel.kx(k);
            int y = VoxelModel.ky(k);
            int z = VoxelModel.kz(k);
            for (int[] st : steps) {
                long n = VoxelModel.key(x + st[0], y + st[1], z + st[2]);
                if (solid.contains(n) && seen.add(n)) queue.add(n);
            }
        }
        assertEquals(solid.size(), seen.size(),
                () -> (solid.size() - seen.size()) + " block(s) form a detached island");
    }

    // ------------------------------------------------------------------ determinism and speed

    @Test
    void sameSpecBuildsTheSameModelTwice() {
        SceneSpec s = house10x10x15();
        VoxelModel first = SceneGeometry.build(s);
        VoxelModel second = SceneGeometry.build(s);
        assertEquals(first.toJson(), second.toJson(), "two builds of one spec must be identical");
        assertEquals(first.size(), second.size());

        // A spec with seeded random detail must be just as reproducible.
        SceneSpec noisy = spec("{\"type\":\"detail\",\"pattern\":\"sparse\",\"material\":\"leaf\","
                + "\"cells\":[[0,0,0],[1,0,0],[2,0,0],[3,0,0],[4,0,0],[5,0,0],[6,0,0],[7,0,0]]}",
                ",\"seed\":99");
        assertEquals(SceneGeometry.build(noisy).toJson(), SceneGeometry.build(noisy).toJson(),
                "the seeded detail pattern must be deterministic");
    }

    @Test
    void buildIntoIsAdditive() {
        SceneSpec base = spec("{\"type\":\"floor\",\"from\":[0,0,0],\"to\":[4,0,4],"
                + "\"material\":\"stone\"}");
        SceneSpec patch = spec("{\"type\":\"beam\",\"from\":[0,3,0],\"to\":[4,3,0],"
                + "\"material\":\"frame\"}");
        VoxelModel model = SceneGeometry.build(base);
        int before = model.size();
        SceneGeometry.buildInto(patch, model);
        assertTrue(model.size() > before, "the patch should add blocks");
        assertThrows(NullPointerException.class, () -> SceneGeometry.buildInto(base, null));
        assertDoesNotThrowForNullSpec(model);
    }

    private static void assertDoesNotThrowForNullSpec(VoxelModel model) {
        int before = model.size();
        SceneGeometry.buildInto(null, model);
        assertEquals(before, model.size(), "a null spec must be a no-op");
        assertTrue(SceneGeometry.info(null).isEmpty());
        assertTrue(SceneGeometry.build(null).isEmpty());
    }

    @Test
    void houseBuildsInUnder300Millis() {
        SceneSpec s = house10x10x15();
        for (int warmup = 0; warmup < 3; warmup++) SceneGeometry.buildScene(s);
        long best = Long.MAX_VALUE;
        for (int run = 0; run < 5; run++) {
            long t0 = System.nanoTime();
            SceneGeometry.buildScene(s);
            best = Math.min(best, (System.nanoTime() - t0) / 1_000_000L);
        }
        long fastest = best;
        assertTrue(fastest < 300, "10x10x15 house took " + fastest + " ms, budget is 300 ms");
    }

    // ------------------------------------------------------------------ info()

    @Test
    void infoReportsSlopedNormalsOnRoofCells() {
        SceneSpec s = spec(""
                + "{\"id\":\"walls\",\"type\":\"box\",\"from\":[0,0,0],\"to\":[9,5,9],\"material\":\"wall\","
                + "\"hollow\":true},"
                + "{\"id\":\"roof\",\"type\":\"prism\",\"from\":[0,6,0],\"to\":[9,6,9],\"style\":\"gable\","
                + "\"ridge\":\"x\",\"material\":\"roof\",\"overhang\":1}");
        Map<Long, SceneGeometry.CellInfo> info = SceneGeometry.info(s);
        assertFalse(info.isEmpty(), "info() must describe the built cells");

        long sloped = 0;
        long roofCells = 0;
        for (Map.Entry<Long, SceneGeometry.CellInfo> e : info.entrySet()) {
            SceneGeometry.CellInfo cell = e.getValue();
            if (cell.exposed() != 0) {
                assertEquals(1.0, Math.sqrt(cell.nx() * cell.nx() + cell.ny() * cell.ny()
                        + cell.nz() * cell.nz()), 1e-9, "every exposed normal must be unit length");
            }
            if (!"roof".equals(cell.material())) continue;
            roofCells++;
            if (Math.abs(cell.ny()) < SceneGeometry.SLOPE_NY
                    && Math.hypot(cell.nx(), cell.nz()) > SceneGeometry.VERTICAL_NY) {
                sloped++;
            }
        }
        assertTrue(roofCells > 0, "no roof cells in info()");
        long slopedCells = sloped;
        long totalRoof = roofCells;
        assertTrue(slopedCells >= 20, "only " + slopedCells + " of " + totalRoof + " roof cells report a slope");
    }

    @Test
    void infoMatchesTheModelAndReportsExposure() {
        SceneSpec s = house10x10x15();
        SceneGeometry.Scene scene = SceneGeometry.buildScene(s);
        VoxelModel model = scene.model();
        Map<Long, SceneGeometry.CellInfo> info = scene.info();

        assertEquals(model.size(), info.size(), "info() must have one entry per block");
        for (Map.Entry<Long, SceneGeometry.CellInfo> e : info.entrySet()) {
            long k = e.getKey();
            BlockSpec block = model.get(VoxelModel.kx(k), VoxelModel.ky(k), VoxelModel.kz(k));
            assertNotNull(block, "info() describes a cell the model does not have");
            SceneGeometry.CellInfo cell = e.getValue();
            if (cell.exposed() == 0) {
                assertTrue(cell.up() && cell.down(), "an interior cell must be covered above and below");
            }
            // `up`/`down` must agree with what the model actually contains.
            boolean above = model.isSet(VoxelModel.kx(k), VoxelModel.ky(k) + 1, VoxelModel.kz(k));
            String where = "cell " + VoxelModel.kx(k) + "," + VoxelModel.ky(k) + "," + VoxelModel.kz(k);
            assertEquals(above, cell.up(), "up flag disagrees with the model at " + where);
        }
    }

    @Test
    void glassCellsArePanesWithConnections() {
        SceneSpec s = spec(""
                + "{\"id\":\"body\",\"type\":\"box\",\"from\":[0,0,0],\"to\":[7,4,7],\"material\":\"wall\","
                + "\"hollow\":true},"
                + "{\"id\":\"w1\",\"type\":\"window\",\"wall\":\"south\",\"at\":[2,2],\"w\":3,\"h\":2,"
                + "\"frame\":\"frame\",\"glass\":\"glass\"}");
        VoxelModel model = SceneGeometry.build(s);
        List<String> panes = new ArrayList<>();
        for (BlockSpec b : model.entries().values()) {
            if (b.name().contains("glass")) panes.add(b.toString());
        }
        assertFalse(panes.isEmpty(), "the window should be glazed");
        boolean anyConnected = panes.stream().anyMatch(p -> p.contains("east=true") || p.contains("west=true"));
        assertTrue(anyConnected, () -> "a 3-wide window should produce connected panes: " + panes);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The benchmark scene: a 10x10 footprint, 15 cells tall including the roof, with the decorations
     * a real iteration produces (door, windows, cornice, chimney, balustrade, stairs, beams).
     */
    private static SceneSpec house10x10x15() {
        return spec(""
                + "{\"id\":\"floor\",\"type\":\"floor\",\"from\":[0,0,0],\"to\":[9,0,9],\"material\":\"stone\"},"
                + "{\"id\":\"body\",\"type\":\"box\",\"from\":[0,0,0],\"to\":[9,8,9],\"material\":\"wall\","
                + "\"hollow\":true,\"openings\":[{\"wall\":\"south\",\"x\":4,\"y\":1,\"w\":2,\"h\":3,"
                + "\"kind\":\"door\",\"frame\":\"frame\"}]},"
                + "{\"id\":\"roof\",\"type\":\"prism\",\"from\":[0,9,0],\"to\":[9,9,9],\"style\":\"gable\","
                + "\"ridge\":\"x\",\"material\":\"roof\",\"overhang\":1},"
                + "{\"id\":\"w1\",\"type\":\"window\",\"wall\":\"south\",\"at\":[1,3],\"w\":1,\"h\":2,"
                + "\"frame\":\"frame\",\"glass\":\"glass\"},"
                + "{\"id\":\"w2\",\"type\":\"window\",\"wall\":\"south\",\"at\":[7,3],\"w\":1,\"h\":2,"
                + "\"frame\":\"frame\",\"glass\":\"glass\"},"
                + "{\"id\":\"w3\",\"type\":\"window\",\"wall\":\"east\",\"at\":[3,3],\"w\":1,\"h\":2,"
                + "\"frame\":\"frame\",\"glass\":\"glass\"},"
                + "{\"id\":\"w4\",\"type\":\"window\",\"wall\":\"west\",\"at\":[3,3],\"w\":1,\"h\":2,"
                + "\"frame\":\"frame\",\"glass\":\"glass\"},"
                + "{\"id\":\"cornice\",\"type\":\"ring\",\"from\":[-1,8,-1],\"to\":[10,8,10],\"y\":8,"
                + "\"material\":\"trim\",\"profile\":\"cornice\",\"thickness\":2},"
                + "{\"id\":\"chimney\",\"type\":\"chimney\",\"at\":[8,9,1],\"height\":5,"
                + "\"material\":\"brick\"},"
                + "{\"id\":\"porch\",\"type\":\"beam\",\"from\":[0,9,-2],\"to\":[9,9,-2],"
                + "\"material\":\"frame\"},"
                + "{\"id\":\"rail\",\"type\":\"balustrade\",\"from\":[0,10,-2],\"to\":[9,11,-2],"
                + "\"material\":\"stone\"},"
                + "{\"id\":\"beam\",\"type\":\"beam\",\"from\":[0,8,0],\"to\":[9,8,0],"
                + "\"material\":\"frame\"},"
                + "{\"id\":\"col1\",\"type\":\"column\",\"at\":[0,0,-2],\"height\":9,\"radius\":1,"
                + "\"material\":\"stone\"},"
                + "{\"id\":\"col2\",\"type\":\"column\",\"at\":[9,0,-2],\"height\":9,\"radius\":1,"
                + "\"material\":\"stone\"},"
                + "{\"id\":\"steps\",\"type\":\"stairs\",\"from\":[4,0,-3],\"to\":[5,2,-1],\"axis\":\"z\","
                + "\"material\":\"wood\"}");
    }

    /**
     * Counts solid blocks that cannot be reached from the ground.
     *
     * <p>Flood fill starts at every solid cell on the ground layer (and the layer below it, which is
     * terrain) and walks through face neighbours. Anything the fill never reaches is floating.</p>
     *
     * @param model the built model
     * @return the number of unreachable solid cells
     */
    private static int countFloating(VoxelModel model) {
        Set<Long> solid = new HashSet<>();
        for (Map.Entry<Long, BlockSpec> e : model.entries().entrySet()) {
            if (!e.getValue().isAir()) solid.add(e.getKey());
        }
        int[] bounds = model.solidBounds();
        if (bounds == null) return 0;
        Set<Long> seen = new HashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        for (long k : solid) {
            if (VoxelModel.ky(k) <= 0) {
                seen.add(k);
                queue.add(k);
            }
        }
        int[][] steps = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!queue.isEmpty()) {
            long k = queue.poll();
            int x = VoxelModel.kx(k);
            int y = VoxelModel.ky(k);
            int z = VoxelModel.kz(k);
            for (int[] s : steps) {
                long n = VoxelModel.key(x + s[0], y + s[1], z + s[2]);
                if (solid.contains(n) && seen.add(n)) queue.add(n);
            }
        }
        return solid.size() - seen.size();
    }
}
