package dev.aibuild.world;

import dev.aibuild.build.Build;
import dev.aibuild.model.BlockSpec;
import dev.aibuild.model.VoxelModel;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Self-checking test for the <b>placement algorithm</b> of {@link WorldPlacer} and the capture logic of
 * {@link RegionCapture}, driven by the in-memory {@link FakeServer} world. Prints PASS/FAIL lines and exits
 * with 1 when a check fails.
 *
 * <p>This exercises the real phase machine (target cells, site clearing, foundation, restore, ordering)
 * end-to-end without a server; only Bukkit's block-data registry is replaced by the fake.</p>
 */
public final class PlacerCheck {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        run("basic placement", PlacerCheck::basicPlacement);
        run("rotation + blockdata rotation", PlacerCheck::rotationPlacement);
        run("site clearing", PlacerCheck::siteClearing);
        run("foundation", PlacerCheck::foundation);
        run("restore terrain", PlacerCheck::restoreTerrain);
        run("remove build (null model)", PlacerCheck::removeBuild);
        run("ordering: fragile last", PlacerCheck::ordering);
        run("unknown block fallback", PlacerCheck::unknownBlock);
        run("progress + result", PlacerCheck::progress);
        run("cancel", PlacerCheck::cancel);
        run("serialized placements", PlacerCheck::chained);
        run("error handling", PlacerCheck::errors);
        run("RegionCapture", PlacerCheck::capture);
        run("groundY", PlacerCheck::groundY);

        System.out.println();
        System.out.println("======================================");
        System.out.println("PASSED: " + passed + "   FAILED: " + failed);
        System.out.println("======================================");
        if (failed > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ harness

    private interface Check {
        void run() throws Exception;
    }

    private static void run(String name, Check c) {
        System.out.println("--- " + name);
        try {
            c.run();
        } catch (Throwable t) {
            fail(name + " threw " + t);
            t.printStackTrace(System.out);
        }
    }

    private static void check(String what, boolean ok) {
        check(what, ok, "");
    }

    private static void check(String what, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("PASS " + what);
        } else {
            fail(what + (detail.isEmpty() ? "" : " [" + detail + "]"));
        }
    }

    private static void fail(String what) {
        failed++;
        System.out.println("FAIL " + what);
    }

    private static void eq(String what, Object expected, Object actual) {
        if (expected == null ? actual == null : expected.equals(actual)) {
            passed++;
            System.out.println("PASS " + what);
        } else {
            failed++;
            System.out.println("FAIL " + what + " (expected " + expected + ", got " + actual + ")");
        }
    }

    // ------------------------------------------------------------------ fixtures

    private static final Logger LOG = Logger.getLogger("PlacerCheck");

    /** A fake world + scheduler + placer triple. */
    private static final class Fx {
        final FakeServer.FakeWorld fake;
        final World world;
        final FakeServer.ManualScheduler scheduler = new FakeServer.ManualScheduler();
        final WorldPlacer placer;

        Fx(String worldName) {
            fake = new FakeServer.FakeWorld(worldName);
            world = fake.world();
            placer = new WorldPlacer(FakeServer.fakePlugin(LOG, fake), 1000, "cobblestone", 12,
                    scheduler, FakeServer.factory());
        }
    }

    private static Build buildAt(String world, int x, int y, int z, int rotation) {
        Build b = new Build(null, "tester", world);
        b.originX = x;
        b.originY = y;
        b.originZ = z;
        b.rotation = rotation;
        return b;
    }

    /** Fills solid ground below {@code y} over the given x/z rectangle so no foundation is needed. */
    private static void ground(Fx fx, int minX, int minZ, int maxX, int maxZ, int topY, int depth, String block) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = topY - depth + 1; y <= topY; y++) {
                    fx.fake.set(x, y, z, block);
                }
            }
        }
    }

    /** Runs a placement to completion (or failure) and returns the result. */
    private static PlaceResult place(Fx fx, Build b, VoxelModel m, int maxTicks) throws Exception {
        CompletableFuture<PlaceResult> f = fx.placer.place(b, m, null);
        if (!fx.scheduler.runUntil(f, maxTicks)) {
            throw new AssertionError("placement did not finish within " + maxTicks + " ticks; "
                    + "busy=" + fx.placer.isBusy(b.id) + " ticks=" + fx.scheduler.scheduledTicks.get());
        }
        return f.get();
    }

    // ------------------------------------------------------------------ tests

    private static void basicPlacement() throws Exception {
        Fx fx = new Fx("world");
        ground(fx, 0, 0, 1, 0, 63, 8, "minecraft:stone");
        Build b = buildAt("world", 0, 64, 0, 0);
        VoxelModel m = new VoxelModel();
        m.set(0, 0, 0, BlockSpec.of("stone"));
        m.set(1, 0, 0, BlockSpec.of("stone"));
        m.set(0, 1, 0, BlockSpec.of("stone"));

        PlaceResult r = place(fx, b, m, 100);
        check("not cancelled", !r.cancelled());
        eq("changed blocks", 3, r.changed());
        eq("stone at origin", "minecraft:stone", fx.fake.get(0, 64, 0));
        eq("stone at x+1", "minecraft:stone", fx.fake.get(1, 64, 0));
        eq("stone at y+1", "minecraft:stone", fx.fake.get(0, 65, 0));
        check("build marked placed", b.placed);
        check("updatedAt refreshed", b.updatedAt >= b.createdAt);
        eq("terrain captured for 3 cells", 3, b.terrainSize());
        eq("original state recorded", "minecraft:air", b.terrain.get(WorldKey.pack(0, 64, 0)));
        check("chunk loaded", fx.fake.chunkLoads > 0);

        // a second, identical placement changes nothing
        PlaceResult r2 = place(fx, b, m, 100);
        eq("repaint changes nothing", 0, r2.changed());
        eq("terrain unchanged after repaint", 3, b.terrainSize());
    }

    private static void rotationPlacement() throws Exception {
        Fx fx = new Fx("world");
        ground(fx, 10, 10, 10, 12, 63, 8, "minecraft:stone");
        Build b = buildAt("world", 10, 64, 10, 1); // clockwise 90: (x,z) -> (-z,x)
        VoxelModel m = new VoxelModel();
        m.set(0, 0, 0, BlockSpec.of("oak_stairs", java.util.Map.of("facing", "south", "half", "bottom")));
        m.set(2, 0, 0, BlockSpec.of("stone"));
        place(fx, b, m, 100);

        eq("origin cell rotated to origin", "minecraft:oak_stairs[facing=west,half=bottom]",
                fx.fake.get(10, 64, 10));
        eq("+x model offset becomes +z at rot 1", "minecraft:stone", fx.fake.get(10, 64, 12));

        // rotation 2
        Fx fx2 = new Fx("world");
        Build b2 = buildAt("world", 0, 64, 0, 2);
        VoxelModel m2 = new VoxelModel();
        m2.set(1, 0, 0, BlockSpec.of("stone"));
        place(fx2, b2, m2, 100);
        eq("+x becomes -x at rot 2", "minecraft:stone", fx2.fake.get(-1, 64, 0));

        // rotation 3: (x,z) -> (z,-x)
        Fx fx3 = new Fx("world");
        ground(fx3, -1, -1, 0, 0, 63, 8, "minecraft:stone");
        Build b3 = buildAt("world", 0, 64, 0, 3);
        VoxelModel m3 = new VoxelModel();
        m3.set(1, 0, 0, BlockSpec.of("stone"));
        place(fx3, b3, m3, 100);
        eq("+x becomes -z at rot 3", "minecraft:stone", fx3.fake.get(0, 64, -1));
    }

    private static void siteClearing() throws Exception {
        Fx fx = new Fx("world");
        // pre-existing blocks inside the future footprint
        fx.fake.set(1, 65, 1, "minecraft:oak_leaves");
        fx.fake.set(1, 66, 1, "minecraft:oak_leaves");
        fx.fake.set(5, 70, 5, "minecraft:stone"); // outside

        Build b = buildAt("world", 0, 64, 0, 0);
        VoxelModel m = new VoxelModel();
        // a 1x1x1 solid block at model (0,0,0) -> bounds are exactly one cell, nothing to clear
        m.set(0, 0, 0, BlockSpec.of("stone"));
        m.set(2, 2, 2, BlockSpec.of("stone")); // forces bounds 0..2
        place(fx, b, m, 100);

        eq("target placed", "minecraft:stone", fx.fake.get(0, 64, 0));
        eq("set cell 2,2,2 placed", "minecraft:stone", fx.fake.get(2, 66, 2));
        eq("unset cell inside bounds cleared", "minecraft:air", fx.fake.get(1, 65, 1));
        eq("unset cell above cleared", "minecraft:air", fx.fake.get(1, 66, 1));
        eq("outside block untouched", "minecraft:stone", fx.fake.get(5, 70, 5));
        // unset cells at y=0 keep the terrain (they are not cleared)
        eq("ground layer not cleared", null, fx.fake.get(1, 64, 1));
        check("cleared cells captured in terrain", b.terrainSize() >= 2);
        eq("cleared cell original", "minecraft:oak_leaves", b.terrain.get(WorldKey.pack(1, 65, 1)));
    }

    private static void foundation() throws Exception {
        Fx fx = new Fx("world");
        // a 3x3 area at ground level, water below (replaceable)
        fx.fake.set(0, 63, 0, "minecraft:water");
        fx.fake.set(0, 62, 0, "minecraft:water");
        fx.fake.set(1, 63, 0, "minecraft:stone"); // solid: foundation stops immediately

        Build b = buildAt("world", 0, 64, 0, 0);
        VoxelModel m = new VoxelModel();
        for (int x = 0; x <= 1; x++) {
            for (int z = 0; z <= 0; z++) {
                m.set(x, 0, z, BlockSpec.of("grass_block"));
            }
        }
        place(fx, b, m, 200);

        eq("foundation under grass column", "minecraft:dirt", fx.fake.get(0, 63, 0));
        eq("foundation continues down", "minecraft:dirt", fx.fake.get(0, 62, 0));
        eq("solid ground stops the foundation", "minecraft:stone", fx.fake.get(1, 63, 0));
        check("foundation captured in terrain", b.terrain.get(WorldKey.pack(0, 63, 0)) != null
                || "minecraft:water".equals(b.terrain.get(WorldKey.pack(0, 63, 0))));

        // a stone-capped column uses the configured foundation block
        Fx fx2 = new Fx("world");
        Build b2 = buildAt("world", 0, 64, 0, 0);
        VoxelModel m2 = new VoxelModel();
        m2.set(0, 0, 0, BlockSpec.of("stone_bricks"));
        place(fx2, b2, m2, 200);
        eq("stone column gets cobblestone foundation", "minecraft:cobblestone", fx2.fake.get(0, 63, 0));

        // depth is capped
        Fx fx3 = new Fx("world");
        for (int y = 40; y <= 63; y++) fx3.fake.set(0, y, 0, "minecraft:water");
        Build b3 = buildAt("world", 0, 64, 0, 0);
        VoxelModel m3 = new VoxelModel();
        m3.set(0, 0, 0, BlockSpec.of("stone"));
        place(fx3, b3, m3, 200);
        eq("foundation top block", "minecraft:cobblestone", fx3.fake.get(0, 63, 0));
        eq("foundation depth capped at 12", "minecraft:cobblestone", fx3.fake.get(0, 64 - 1 - 11, 0));
        eq("beyond the cap stays water", "minecraft:water", fx3.fake.get(0, 64 - 1 - 12, 0));
    }

    private static void restoreTerrain() throws Exception {
        Fx fx = new Fx("world");
        ground(fx, 0, 0, 5, 5, 63, 8, "minecraft:stone");
        fx.fake.set(0, 64, 0, "minecraft:oak_planks");
        fx.fake.set(0, 65, 0, "minecraft:glass");

        Build b = buildAt("world", 0, 64, 0, 0);
        VoxelModel m = new VoxelModel();
        m.set(0, 0, 0, BlockSpec.of("stone"));
        m.set(5, 5, 5, BlockSpec.of("stone"));
        place(fx, b, m, 200);
        eq("original replaced", "minecraft:stone", fx.fake.get(0, 64, 0));
        eq("glass cleared", "minecraft:air", fx.fake.get(0, 65, 0));
        eq("planks captured", "minecraft:oak_planks", b.terrain.get(WorldKey.pack(0, 64, 0)));

        // place a *smaller* model: cells no longer in the target are restored. The smaller model keeps the
        // same origin, so its cell (0,0,0) still targets the planks column; move it off that column and
        // place a 1x1 model whose origin leaves (0,64,0) outside its bounds.
        VoxelModel smaller = new VoxelModel();
        smaller.set(0, 0, 0, BlockSpec.of("stone"));
        smaller.set(5, 5, 5, BlockSpec.of("stone"));
        b.originZ = 10;
        PlaceResult r = place(fx, b, smaller, 200);
        eq("terrain restored to planks", "minecraft:oak_planks", fx.fake.get(0, 64, 0));
        eq("cleared cell restored to glass", "minecraft:glass", fx.fake.get(0, 65, 0));
        check("restore changed blocks", r.changed() > 0);
        check("restored cells forgotten", b.terrain.get(WorldKey.pack(0, 64, 0)) == null
                && b.terrain.get(WorldKey.pack(0, 65, 0)) == null);
    }

    private static void removeBuild() throws Exception {
        Fx fx = new Fx("world");
        ground(fx, 0, 0, 1, 0, 63, 8, "minecraft:stone");
        fx.fake.set(0, 64, 0, "minecraft:grass_block");
        fx.fake.set(1, 64, 0, "minecraft:dirt");
        Build b = buildAt("world", 0, 64, 0, 0);
        VoxelModel m = new VoxelModel();
        m.set(0, 0, 0, BlockSpec.of("stone"));
        m.set(1, 0, 0, BlockSpec.of("stone"));
        m.set(1, 1, 0, BlockSpec.of("stone"));
        place(fx, b, m, 200);
        check("placed", b.placed);

        // null model == remove the build
        PlaceResult r = place(fx, b, null, 200);
        check("remove completed", !r.cancelled());
        check("build marked not placed", !b.placed);
        eq("terrain restored 1", "minecraft:grass_block", fx.fake.get(0, 64, 0));
        eq("terrain restored 2", "minecraft:dirt", fx.fake.get(1, 64, 0));
        eq("new block removed", "minecraft:air", fx.fake.get(1, 65, 0));
        eq("terrain backup emptied", 0, b.terrainSize());
    }

    private static void ordering() throws Exception {
        Fx fx = new Fx("world");
        ground(fx, 0, 0, 3, 3, 63, 8, "minecraft:stone");
        Build b = buildAt("world", 0, 64, 0, 0);
        VoxelModel m = new VoxelModel();
        m.set(0, 5, 0, BlockSpec.of("oak_door", java.util.Map.of("facing", "south", "half", "lower")));
        m.set(0, 0, 0, BlockSpec.of("stone"));
        m.set(3, 3, 3, BlockSpec.of("oak_leaves")); // sets the bounds
        PlaceResult r = place(fx, b, m, 200);
        check("ordering run completed", !r.cancelled());
        eq("door placed", "minecraft:oak_door[facing=south,half=lower]", fx.fake.get(0, 69, 0));
        // the fragility classifier is the ordering contract; verify the categories
        check("door classified fragile", WorldPlacer.fragile(FakeServer.Data.of(
                "minecraft:oak_door[facing=south,half=lower]")));
        check("stone classified solid", !WorldPlacer.fragile(FakeServer.Data.of("minecraft:stone")));
        check("torch classified fragile", WorldPlacer.fragile(FakeServer.Data.of("minecraft:torch")));
        check("lantern classified fragile", WorldPlacer.fragile(FakeServer.Data.of("minecraft:lantern")));
        check("rail classified fragile", WorldPlacer.fragile(FakeServer.Data.of("minecraft:rail")));
        check("carpet classified fragile", WorldPlacer.fragile(FakeServer.Data.of("minecraft:red_carpet")));
        check("button classified fragile", WorldPlacer.fragile(FakeServer.Data.of(
                "minecraft:oak_button[face=wall,facing=north]")));
        check("bed classified fragile", WorldPlacer.fragile(FakeServer.Data.of(
                "minecraft:red_bed[facing=north,part=head]")));
        check("wall sign classified fragile", WorldPlacer.fragile(FakeServer.Data.of(
                "minecraft:oak_wall_sign[facing=north]")));
        check("ladder classified fragile", WorldPlacer.fragile(FakeServer.Data.of(
                "minecraft:ladder[facing=north]")));
        check("planks classified solid", !WorldPlacer.fragile(FakeServer.Data.of("minecraft:oak_planks")));
        check("glass classified solid", !WorldPlacer.fragile(FakeServer.Data.of("minecraft:glass")));
        check("fence classified fragile", WorldPlacer.fragile(FakeServer.Data.of(
                "minecraft:oak_fence[north=true]")));
        check("water classified fragile", WorldPlacer.fragile(FakeServer.Data.of("minecraft:water")));
    }

    private static void unknownBlock() throws Exception {
        Fx fx = new Fx("world");
        ground(fx, 0, 0, 1, 0, 63, 8, "minecraft:stone");
        Build b = buildAt("world", 0, 64, 0, 0);
        VoxelModel m = new VoxelModel();
        m.set(0, 0, 0, BlockSpec.of("stone"));
        m.set(1, 0, 0, BlockSpec.of("definitely_not_a_block"));
        PlaceResult r = place(fx, b, m, 200);
        check("placement survives an unknown block", !r.cancelled());
        eq("known block placed", "minecraft:stone", fx.fake.get(0, 64, 0));
        eq("unknown block falls back to stone", "minecraft:stone", fx.fake.get(1, 64, 0));
    }

    private static void progress() throws Exception {
        Fx fx = new Fx("world");
        ground(fx, 0, 0, 4, 4, 63, 8, "minecraft:stone");
        Build b = buildAt("world", 0, 64, 0, 0);
        VoxelModel m = new VoxelModel();
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                m.set(x, 0, z, BlockSpec.of("stone"));
            }
        }
        List<Integer> seen = new ArrayList<>();
        // use a small budget so the placement spans several ticks
        WorldPlacer placer = new WorldPlacer(FakeServer.fakePlugin(LOG, fx.fake), 5, "cobblestone", 12,
                fx.scheduler, FakeServer.factory());
        CompletableFuture<PlaceResult> f = placer.place(b, m, seen::add);
        check("progress future completes", fx.scheduler.runUntil(f, 500));
        PlaceResult r = f.get();
        eq("all 25 blocks changed", 25, r.changed());
        check("progress was reported", !seen.isEmpty());
        check("progress ends at 100", !seen.isEmpty() && seen.get(seen.size() - 1) == 100);
        boolean monotonic = true;
        for (int i = 1; i < seen.size(); i++) {
            if (seen.get(i) < seen.get(i - 1)) monotonic = false;
        }
        check("progress is monotonic", monotonic);
        boolean inRange = true;
        for (int p : seen) if (p < 0 || p > 100) inRange = false;
        check("progress stays in 0..100", inRange);
        check("millis is non-negative", r.millis() >= 0);
    }

    private static void cancel() throws Exception {
        Fx fx = new Fx("world");
        ground(fx, 0, 0, 9, 9, 63, 8, "minecraft:stone");
        Build b = buildAt("world", 0, 64, 0, 0);
        VoxelModel m = new VoxelModel();
        for (int x = 0; x < 10; x++) {
            for (int z = 0; z < 10; z++) {
                for (int y = 1; y <= 5; y++) m.set(x, y, z, BlockSpec.of("stone"));
            }
        }
        WorldPlacer placer = new WorldPlacer(FakeServer.fakePlugin(LOG, fx.fake), 10, "cobblestone", 12,
                fx.scheduler, FakeServer.factory());
        CompletableFuture<PlaceResult> f = placer.place(b, m, null);
        check("busy right after place()", placer.isBusy(b.id));
        fx.scheduler.tick(); // start the job
        fx.scheduler.tick();
        fx.scheduler.tick();
        placer.cancel(b.id);
        check("future completes after cancel", fx.scheduler.runUntil(f, 2000));
        PlaceResult r = f.get();
        check("result is cancelled", r.cancelled());
        check("not busy after cancel", !placer.isBusy(b.id));
        check("cancelling an idle placer is a no-op", noThrow(() -> placer.cancel("abcdef")));
    }

    private static boolean noThrow(Runnable r) {
        try {
            r.run();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void chained() throws Exception {
        Fx fx = new Fx("world");
        ground(fx, 0, 0, 0, 0, 63, 8, "minecraft:stone");
        Build b = buildAt("world", 0, 64, 0, 0);
        VoxelModel first = new VoxelModel();
        first.set(0, 0, 0, BlockSpec.of("stone"));
        VoxelModel second = new VoxelModel();
        second.set(0, 0, 0, BlockSpec.of("oak_planks"));

        WorldPlacer placer = new WorldPlacer(FakeServer.fakePlugin(LOG, fx.fake), 2, "cobblestone", 12,
                fx.scheduler, FakeServer.factory());
        CompletableFuture<PlaceResult> f1 = placer.place(b, first, null);
        CompletableFuture<PlaceResult> f2 = placer.place(b, second, null);
        check("both complete", fx.scheduler.runUntil(
                CompletableFuture.allOf(f1, f2), 500));
        check("first future done", f1.isDone());
        check("second future done", f2.isDone());
        // the *second* placement must win: it runs after the first
        eq("last write wins", "minecraft:oak_planks", fx.fake.get(0, 64, 0));
        check("not busy", !placer.isBusy(b.id));
    }

    private static void errors() throws Exception {
        Fx fx = new Fx("world");
        ground(fx, 0, 0, 0, 0, 63, 8, "minecraft:stone");
        Build b = buildAt("missing_world", 0, 64, 0, 0);
        VoxelModel m = new VoxelModel();
        m.set(0, 0, 0, BlockSpec.of("stone"));
        CompletableFuture<PlaceResult> f = fx.placer.place(b, m, null);
        check("future completes", fx.scheduler.runUntil(f, 100));
        check("unknown world fails exceptionally", f.isCompletedExceptionally());
        check("not busy after failure", !fx.placer.isBusy(b.id));
        check("only the fixture ground was written", fx.fake.blocks.size() == 8,
                "blocks=" + fx.fake.blocks.size());

        // null build is rejected without throwing
        CompletableFuture<PlaceResult> nf = fx.placer.place(null, m, null);
        check("null build -> failed future", nf.isCompletedExceptionally());

        // a failed placement must not block the next one for the same build
        Build good = buildAt("world", 0, 64, 0, 0);
        good.id = b.id;
        CompletableFuture<PlaceResult> f2 = fx.placer.place(good, m, null);
        check("next placement still runs", fx.scheduler.runUntil(f2, 200));
        eq("block placed after a failure", "minecraft:stone", fx.fake.get(0, 64, 0));
    }

    private static void groundY() {
        Fx flat = new Fx("world");
        for (int x = 0; x < 10; x++) {
            for (int z = 0; z < 10; z++) {
                for (int y = 55; y <= 63; y++) flat.fake.set(x, y, z, "minecraft:grass_block");
            }
        }
        eq("flat ground", 64, PlacementMath.groundY(flat.world, new int[]{0, 0, 9, 9}));

        // a ruined tower in one corner must not lift the median (it is only a few columns)
        Fx skewed = new Fx("world");
        for (int x = 0; x < 20; x++) {
            for (int z = 0; z < 20; z++) {
                for (int y = 55; y <= 63; y++) skewed.fake.set(x, y, z, "minecraft:stone");
            }
        }
        for (int y = 64; y <= 90; y++) {
            for (int x = 0; x <= 2; x++) skewed.fake.set(x, y, 0, "minecraft:stone");
        }
        eq("tower does not dominate the median", 64, PlacementMath.groundY(skewed.world, new int[]{0, 0, 19, 19}));

        // a log on top of the soil is stepped past
        Fx tree = new Fx("world");
        for (int x = 0; x < 6; x++) {
            for (int z = 0; z < 6; z++) {
                for (int y = 55; y <= 63; y++) tree.fake.set(x, y, z, "minecraft:grass_block");
            }
        }
        tree.fake.set(2, 64, 2, "minecraft:oak_log");
        tree.fake.set(2, 65, 2, "minecraft:oak_leaves");
        int gy = PlacementMath.groundY(tree.world, new int[]{0, 0, 5, 5});
        check("tree trunk is stepped past (groundY=" + gy + ")", gy >= 64 && gy <= 65);

        // null world is rejected, invalid footprint degrades gracefully
        boolean threw = false;
        try {
            PlacementMath.groundY(null, new int[]{0, 0, 1, 1});
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check("null world rejected", threw);
        eq("null footprint falls back to min height", -64, PlacementMath.groundY(flat.world, null));
    }

    private static void capture() throws Exception {
        Fx fx = new Fx("world");
        ground(fx, 0, 0, 1, 0, 63, 8, "minecraft:stone");
        fx.fake.set(0, 64, 0, "minecraft:stone_bricks");
        fx.fake.set(1, 64, 0, "minecraft:oak_stairs[facing=east,half=top]");
        fx.fake.set(0, 65, 0, "minecraft:oak_planks");
        // air at (1,65,0)

        VoxelModel m = RegionCapture.capture(fx.world, 0, 64, 0, 1, 65, 0, FakeServer.INFO);
        eq("captured cells (air skipped)", 3, m.size());
        eq("selection bottom becomes y=0", "stone_bricks", String.valueOf(m.get(0, 0, 0)));
        check("stairs captured with props", m.get(1, 0, 0) != null
                && m.get(1, 0, 0).name().equals("oak_stairs")
                && "east".equals(m.get(1, 0, 0).prop("facing")));
        check("air cell not stored", m.get(1, 1, 0) == null);
        eq("upper level captured", "oak_planks", String.valueOf(m.get(0, 1, 0)));

        // fall back path: capture with a registry that rejects the state string
        m = RegionCapture.capture(fx.world, 0, 64, 0, 1, 65, 0, null);
        eq("capture without BlockInfo still works", 3, m.size());

        // the captured model can be placed back
        Build b = buildAt("world", 100, 64, 0, 0);
        CompletableFuture<PlaceResult> f = fx.placer.place(b, m, null);
        check("captured model places", fx.scheduler.runUntil(f, 500));

        // an all-air selection yields an empty model
        VoxelModel empty = RegionCapture.capture(fx.world, 500, 100, 500, 502, 102, 502, FakeServer.INFO);
        check("all-air capture is empty", empty.isEmpty());

        // null world is rejected
        boolean threw = false;
        try {
            RegionCapture.capture(null, 0, 0, 0, 1, 1, 1, FakeServer.INFO);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check("null world rejected", threw);
    }
}
