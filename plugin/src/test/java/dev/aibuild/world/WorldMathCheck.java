package dev.aibuild.world;

import dev.aibuild.build.Build;
import dev.aibuild.build.BuildStore;
import dev.aibuild.build.BuildVersion;
import dev.aibuild.model.BlockSpec;
import dev.aibuild.model.StubBlockInfo;
import dev.aibuild.model.VoxelModel;
import org.bukkit.block.BlockFace;
import org.bukkit.block.structure.StructureRotation;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Self-checking test for the pure (server-free) parts of module D: {@link WorldKey},
 * {@link PlacementMath}, {@link Build}/{@link BuildVersion} JSON round trips and {@link BuildStore}
 * persistence. Plain {@code main()} — no JUnit, no server. Prints PASS/FAIL lines and exits with 1 when
 * anything fails.
 */
public final class WorldMathCheck {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        run("WorldKey", WorldMathCheck::worldKey);
        run("PlacementMath.rotate", WorldMathCheck::rotate);
        run("PlacementMath.rotationForPlayerFacing", WorldMathCheck::playerFacing);
        run("PlacementMath.computeOrigin", WorldMathCheck::computeOrigin);
        run("PlacementMath.worldBounds", WorldMathCheck::worldBounds);
        run("Build/BuildVersion json", WorldMathCheck::jsonRoundTrip);
        run("BuildStore persistence", WorldMathCheck::store);
        run("BuildStore corrupt files", WorldMathCheck::corruptFiles);
        run("WorldPlacer pure helpers", WorldMathCheck::placerHelpers);

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
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (!ok) {
            fail(what + " (expected " + expected + ", got " + actual + ")");
        } else {
            passed++;
            System.out.println("PASS " + what);
        }
    }

    // ------------------------------------------------------------------ WorldKey

    private static void worldKey() {
        int[][] samples = {
                {0, 0, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1},
                {12, 64, -7}, {-12, -64, 7}, {33554431, 2047, 33554431}, {-33554432, -2048, -33554432},
                {100, 319, -100}, {-100, -64, 100}, {12345, -64, -54321}, {-999999, 255, 999999},
        };
        for (int[] s : samples) {
            long k = WorldKey.pack(s[0], s[1], s[2]);
            check("pack/unpack " + s[0] + "," + s[1] + "," + s[2],
                    WorldKey.x(k) == s[0] && WorldKey.y(k) == s[1] && WorldKey.z(k) == s[2]);
        }
        // y sweep across the full overworld range
        boolean ok = true;
        for (int y = -64; y <= 319; y++) {
            long k = WorldKey.pack(17, y, -23);
            if (WorldKey.x(k) != 17 || WorldKey.y(k) != y || WorldKey.z(k) != -23) ok = false;
        }
        check("y round-trip for -64..319", ok);

        // distinctness across the whole y range at a fixed column
        java.util.HashSet<Long> keys = new java.util.HashSet<>();
        for (int y = -2048; y <= 2047; y++) keys.add(WorldKey.pack(0, y, 0));
        eq("2048+2048 y values are distinct", 4096, keys.size());

        // match Bukkit's BlockPos.asLong formula literally
        long expected = ((long) (5 & 0x3FFFFFF) << 38) | ((long) (-9 & 0x3FFFFFF) << 12) | (long) (70 & 0xFFF);
        eq("matches BlockPos.asLong bit layout", expected, WorldKey.pack(5, 70, -9));
        eq("negative y sign extension", -1, WorldKey.y(WorldKey.pack(0, -1, 0)));
        check("isRepresentable in range", WorldKey.isRepresentable(1000, 300, -1000));
        check("isRepresentable out of range", !WorldKey.isRepresentable(0, 5000, 0));
        eq("describe", "5,70,-9", WorldKey.describe(WorldKey.pack(5, 70, -9)));
    }

    // ------------------------------------------------------------------ rotate

    private static void rotate() {
        // north unit vector (0,-1) rotated once clockwise becomes east (1,0)
        int[] north = {0, -1};
        int[] east = PlacementMath.rotate(north[0], north[1], 1);
        check("N (0,-1) rot1 -> E (1,0)", east[0] == 1 && east[1] == 0);
        int[] rot2 = PlacementMath.rotate(north[0], north[1], 2);
        check("N rot2 -> S", rot2[0] == 0 && rot2[1] == 1);
        int[] rot3 = PlacementMath.rotate(north[0], north[1], 3);
        check("N rot3 -> W", rot3[0] == -1 && rot3[1] == 0);

        // the model front is south (0,1); one clockwise quarter turn takes S -> W (compass order N,E,S,W)
        check("front S rot1 -> W", same(PlacementMath.rotate(0, 1, 1), -1, 0));
        check("front S rot2 -> N", same(PlacementMath.rotate(0, 1, 2), 0, -1));
        check("front S rot3 -> E", same(PlacementMath.rotate(0, 1, 3), 1, 0));

        // four rotations are the identity, for random points and all rotations
        boolean identity = true;
        java.util.Random rnd = new java.util.Random(42);
        for (int i = 0; i < 200; i++) {
            int x = rnd.nextInt(400) - 200, z = rnd.nextInt(400) - 200;
            int cx = x, cz = z;
            for (int r = 0; r < 4; r++) {
                int[] p = PlacementMath.rotate(cx, cz, 1);
                cx = p[0];
                cz = p[1];
            }
            if (cx != x || cz != z) identity = false;
        }
        check("four rotations are identity", identity);

        // composition: rot1 then rot2 == rot3
        boolean composed = true;
        for (int i = 0; i < 100; i++) {
            int x = rnd.nextInt(100) - 50, z = rnd.nextInt(100) - 50;
            int[] a = PlacementMath.rotate(x, z, 1);
            int[] b = PlacementMath.rotate(a[0], a[1], 2);
            int[] c = PlacementMath.rotate(x, z, 3);
            if (b[0] != c[0] || b[1] != c[1]) composed = false;
        }
        check("rot1 ++ rot2 == rot3", composed);

        // unrotate inverts rotate
        boolean inverse = true;
        for (int i = 0; i < 100; i++) {
            int x = rnd.nextInt(100) - 50, z = rnd.nextInt(100) - 50;
            for (int r = 0; r < 4; r++) {
                int[] p = PlacementMath.rotate(x, z, r);
                int[] u = PlacementMath.unrotate(p[0], p[1], r);
                if (u[0] != x || u[1] != z) inverse = false;
            }
        }
        check("unrotate inverts rotate", inverse);

        // -4 and 8 wrap
        check("negative rotation wraps", same(PlacementMath.rotate(3, -7, -4), 3, -7));
        check("rotation 8 wraps to 0", same(PlacementMath.rotate(3, -7, 8), 3, -7));

        eq("toStructureRotation(0)", StructureRotation.NONE, PlacementMath.toStructureRotation(0));
        eq("toStructureRotation(1)", StructureRotation.CLOCKWISE_90, PlacementMath.toStructureRotation(1));
        eq("toStructureRotation(2)", StructureRotation.CLOCKWISE_180, PlacementMath.toStructureRotation(2));
        eq("toStructureRotation(3)", StructureRotation.COUNTERCLOCKWISE_90, PlacementMath.toStructureRotation(3));
        eq("toStructureRotation(7)", StructureRotation.COUNTERCLOCKWISE_90, PlacementMath.toStructureRotation(7));
        for (int r = 0; r < 4; r++) {
            eq("fromStructureRotation(toStructureRotation(" + r + "))", r,
                    PlacementMath.fromStructureRotation(PlacementMath.toStructureRotation(r)));
        }
    }

    private static boolean same(int[] a, int x, int z) {
        return a != null && a.length >= 2 && a[0] == x && a[1] == z;
    }

    // ------------------------------------------------------------------ player facing

    private static void playerFacing() {
        eq("player facing NORTH -> 0", 0, PlacementMath.rotationForPlayerFacing(BlockFace.NORTH));
        eq("player facing EAST -> 1", 1, PlacementMath.rotationForPlayerFacing(BlockFace.EAST));
        eq("player facing SOUTH -> 2", 2, PlacementMath.rotationForPlayerFacing(BlockFace.SOUTH));
        eq("player facing WEST -> 3", 3, PlacementMath.rotationForPlayerFacing(BlockFace.WEST));
        eq("null facing -> 0", 0, PlacementMath.rotationForPlayerFacing(null));

        // diagonals collapse to the closest cardinal
        eq("NORTH_EAST -> 0", 0, PlacementMath.rotationForPlayerFacing(BlockFace.NORTH_EAST));
        eq("SOUTH_EAST -> 2 (ties break towards z)", 2, PlacementMath.rotationForPlayerFacing(BlockFace.SOUTH_EAST));
        eq("SOUTH_WEST -> 2", 2, PlacementMath.rotationForPlayerFacing(BlockFace.SOUTH_WEST));
        eq("WEST_NORTH_WEST -> 3", 3, PlacementMath.rotationForPlayerFacing(BlockFace.WEST_NORTH_WEST));

        // yaw: 0 = south, 90 = west, 180 = north, -90 = east (Minecraft convention)
        eq("yaw 0 -> SOUTH", BlockFace.SOUTH, PlacementMath.yawToFacing(0f));
        eq("yaw 90 -> WEST", BlockFace.WEST, PlacementMath.yawToFacing(90f));
        eq("yaw 180 -> NORTH", BlockFace.NORTH, PlacementMath.yawToFacing(180f));
        eq("yaw -90 -> EAST", BlockFace.EAST, PlacementMath.yawToFacing(-90f));
        eq("yaw 270 -> EAST", BlockFace.EAST, PlacementMath.yawToFacing(270f));
        eq("yaw 360 -> SOUTH", BlockFace.SOUTH, PlacementMath.yawToFacing(360f));
        eq("yaw -540 -> NORTH", BlockFace.NORTH, PlacementMath.yawToFacing(-540f));
        eq("NaN yaw -> SOUTH", BlockFace.SOUTH, PlacementMath.yawToFacing(Float.NaN));
        eq("player yaw 90 -> rotation 3", 3, PlacementMath.rotationForPlayerYaw(90f));

        // the model front must face the player: player at (0,0), model front points to +z when rot 0...
        // ...so for each rotation, the rotated front direction must be the opposite of the player's look dir.
        boolean facesPlayer = true;
        BlockFace[] faces = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};
        int[][] look = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
        for (int i = 0; i < faces.length; i++) {
            int rot = PlacementMath.rotationForPlayerFacing(faces[i]);
            int[] front = PlacementMath.rotate(0, 1, rot); // model front is south at rot 0
            // player looks along `look[i]`; the model must face them, i.e. front == -look
            if (front[0] != -look[i][0] || front[1] != -look[i][1]) facesPlayer = false;
        }
        check("rotated front opposes the player's look direction", facesPlayer);
    }

    // ------------------------------------------------------------------ computeOrigin

    private static void computeOrigin() {
        int[][] boxes = {
                {0, 0, 0, 0, 0, 0},          // single cell
                {0, 0, 0, 9, 6, 7},          // 10 x 8 footprint
                {-3, 0, -5, 4, 12, 2},       // offset model
                {0, 0, 0, 4, 3, 4},          // 5 x 5 footprint
                {0, 0, 0, 1, 1, 1},          // 2 x 2 footprint
        };
        int[][] anchors = {{0, 0}, {100, -37}, {-250, 42}};
        boolean ok = true;
        String detail = "";
        for (int[] box : boxes) {
            int cx = Math.floorDiv(box[0] + box[3], 2);
            int cz = box[5] + 1;
            for (int rot = 0; rot < 4; rot++) {
                for (int[] a : anchors) {
                    int[] origin = PlacementMath.computeOrigin(a[0], a[1], 64, rot, box);
                    int[] r = PlacementMath.rotate(cx, cz, rot);
                    int frontX = r[0] + origin[0];
                    int frontZ = r[1] + origin[2];
                    if (frontX != a[0] || frontZ != a[1] || origin[1] != 64) {
                        ok = false;
                        detail = " box=" + java.util.Arrays.toString(box) + " rot=" + rot + " anchor="
                                + java.util.Arrays.toString(a) + " origin=" + java.util.Arrays.toString(origin)
                                + " front=" + frontX + "," + frontZ;
                    }
                }
            }
        }
        check("front-center maps to anchor for every rotation" + detail, ok);

        // concrete example: 5x5 model at rotation 0 -> origin is one block north of the anchor's z
        int[] box = {0, 0, 0, 4, 3, 4};
        int[] o0 = PlacementMath.computeOrigin(0, 0, 64, 0, box);
        eq("rot0 origin x", -2, o0[0]);
        eq("rot0 origin z", -5, o0[2]);
        eq("rot0 origin y", 64, o0[1]);
        int[] o1 = PlacementMath.computeOrigin(0, 0, 64, 1, box);
        // rot1 front-center (2,5) -> (-5,2); origin = anchor - (-5,2) = (5,-2)
        eq("rot1 origin x", 5, o1[0]);
        eq("rot1 origin z", -2, o1[2]);

        // groundY is honoured (model layer 0 sits on the ground)
        int[] oy = PlacementMath.computeOrigin(7, 9, -20, 2, box);
        eq("originY == groundY", -20, oy[1]);

        // invalid bounds must throw
        boolean threw = false;
        try {
            PlacementMath.computeOrigin(0, 0, 64, 0, new int[]{1, 2, 3});
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check("computeOrigin rejects short bounds", threw);
    }

    // ------------------------------------------------------------------ worldBounds

    private static void worldBounds() {
        Build b = new Build();
        b.originX = 100;
        b.originY = 64;
        b.originZ = -50;
        b.rotation = 0;
        int[] box = {0, 0, 0, 9, 5, 7};
        int[] wb = PlacementMath.worldBounds(b, box);
        check("worldBounds rot0 " + java.util.Arrays.toString(wb),
                wb[0] == 100 && wb[1] == 64 && wb[2] == -50 && wb[3] == 109 && wb[4] == 69 && wb[5] == -43);

        b.rotation = 1; // (x,z) -> (-z,x): x range [-7,0], z range [0,9]
        wb = PlacementMath.worldBounds(b, box);
        check("worldBounds rot1 " + java.util.Arrays.toString(wb),
                wb[0] == 93 && wb[1] == 64 && wb[2] == -50 && wb[3] == 100 && wb[4] == 69 && wb[5] == -41);

        b.rotation = 2; // (-x,-z)
        wb = PlacementMath.worldBounds(b, box);
        check("worldBounds rot2 " + java.util.Arrays.toString(wb),
                wb[0] == 91 && wb[2] == -57 && wb[3] == 100 && wb[5] == -50 && wb[1] == 64 && wb[4] == 69);

        b.rotation = 3; // (z,-x)
        wb = PlacementMath.worldBounds(b, box);
        check("worldBounds rot3 " + java.util.Arrays.toString(wb),
                wb[0] == 100 && wb[2] == -59 && wb[3] == 107 && wb[5] == -50);

        // rotation preserves the footprint area
        boolean areaOk = true;
        for (int r = 0; r < 4; r++) {
            int[] x = PlacementMath.worldBounds(0, 0, 0, r, box);
            long a = (long) (x[3] - x[0] + 1) * (x[5] - x[2] + 1);
            if (a != 10L * 8L) areaOk = false;
        }
        check("rotation preserves footprint area", areaOk);

        // contains() (needs a model: bounds come from the current version)
        b.rotation = 0;
        VoxelModel bm = new VoxelModel();
        bm.set(0, 0, 0, BlockSpec.of("stone"));
        bm.set(9, 5, 7, BlockSpec.of("stone"));
        b.addVersion(bm, "", "", 0);
        check("contains inside", PlacementMath.contains(b, 105, 66, -47));
        check("contains outside", !PlacementMath.contains(b, 111, 66, -47));
        check("contains below", !PlacementMath.contains(b, 105, 63, -47));

        // worldFootprint agrees with worldBounds
        int[] fp = PlacementMath.worldFootprint(b.originX, b.originZ, b.rotation, box);
        int[] wb0 = PlacementMath.worldBounds(b, box);
        check("worldFootprint matches worldBounds",
                fp[0] == wb0[0] && fp[1] == wb0[2] && fp[2] == wb0[3] && fp[3] == wb0[5]);

        // unsorted input corners are normalized
        int[] unsorted = {9, 5, 7, 0, 0, 0};
        int[] wbU = PlacementMath.worldBounds(b, unsorted);
        check("unsorted bounds are normalized",
                wbU[0] == 100 && wbU[3] == 109 && wbU[2] == -50 && wbU[5] == -43);
    }

    // ------------------------------------------------------------------ json

    private static void jsonRoundTrip() {
        VoxelModel m = new VoxelModel();
        m.set(0, 0, 0, BlockSpec.of("stone"));
        m.set(1, 0, 0, BlockSpec.of("oak_stairs", Map.of("facing", "east")));
        m.set(1, 1, 0, BlockSpec.of("air"));
        m.set(-2, 5, 3, BlockSpec.of("grass_block"));

        BuildVersion v = new BuildVersion(3, "add a chimney", "added a brick chimney", m, 1730000000000L, 87);
        BuildVersion v2 = BuildVersion.fromJson(v.toJson());
        eq("version number", 3, v2.number);
        eq("version request", "add a chimney", v2.request);
        eq("version summary", "added a brick chimney", v2.summary);
        eq("version createdAt", 1730000000000L, v2.createdAt);
        eq("version score", 87, v2.score);
        eq("version model size", m.size(), v2.model.size());
        boolean modelsEqual = true;
        for (Map.Entry<Long, BlockSpec> e : m.entries().entrySet()) {
            int x = VoxelModel.kx(e.getKey()), y = VoxelModel.ky(e.getKey()), z = VoxelModel.kz(e.getKey());
            BlockSpec got = v2.model.get(x, y, z);
            if (!e.getValue().equals(got)) modelsEqual = false;
        }
        check("version model cells survive the round trip", modelsEqual);
        check("version display", v2.display().startsWith("#3 (score 87)"), "display=" + v2.display());

        Build b = new Build(UUID.randomUUID(), "Steve", "world_nether");
        b.name = "Brick House";
        b.originX = 120;
        b.originY = 70;
        b.originZ = -33;
        b.rotation = 3;
        b.placed = true;
        b.originalPrompt = "a small brick house with a chimney, 9x7";
        com.google.gson.JsonObject plan = new com.google.gson.JsonObject();
        plan.addProperty("title", "Brick House");
        b.plan = plan;
        b.history.add("create a brick house");
        b.history.add("add a chimney");
        b.versions.add(v);
        b.versions.add(new BuildVersion(4, "add a chimney", "chimney added", m.copy(), 1730000001000L, 91));
        b.currentVersion = 1;
        b.createdAt = 1730000000000L;
        b.updatedAt = 1730000002000L;
        // terrain: positive/negative coords and various states
        b.terrain.put(WorldKey.pack(0, 64, 0), "minecraft:grass_block[snowy=false]");
        b.terrain.put(WorldKey.pack(-5, -64, 9), "minecraft:stone");
        b.terrain.put(WorldKey.pack(1234, 319, -1234), "minecraft:oak_log[axis=y]");
        b.terrain.put(WorldKey.pack(-33554432, -2048, 33554431), "minecraft:air");

        Build r = Build.fromJson(b.toJson());
        eq("build id", b.id, r.id);
        eq("build owner", b.owner, r.owner);
        eq("build ownerName", "Steve", r.ownerName);
        eq("build name", "Brick House", r.name);
        eq("build worldName", "world_nether", r.worldName);
        eq("build origin", "120,70,-33", r.originX + "," + r.originY + "," + r.originZ);
        eq("build rotation", 3, r.rotation);
        check("build placed", r.placed);
        eq("build originalPrompt", b.originalPrompt, r.originalPrompt);
        eq("build plan title", "Brick House", r.plan.get("title").getAsString());
        eq("build history size", 2, r.history.size());
        eq("build history last", "add a chimney", r.history.get(1));
        eq("build versions size", 2, r.versions.size());
        eq("build currentVersion", 1, r.currentVersion);
        eq("build createdAt", 1730000000000L, r.createdAt);
        eq("build updatedAt", 1730000002000L, r.updatedAt);
        eq("build terrain size", 4, r.terrain.size());
        eq("terrain grass", "minecraft:grass_block[snowy=false]", r.terrain.get(WorldKey.pack(0, 64, 0)));
        eq("terrain negative y", "minecraft:stone", r.terrain.get(WorldKey.pack(-5, -64, 9)));
        eq("terrain extreme", "minecraft:air", r.terrain.get(WorldKey.pack(-33554432, -2048, 33554431)));
        check("current() of restored build is version 4", r.current() != null && r.current().number == 4);
        check("currentModel() not null", r.currentModel() != null);

        // console build: no owner
        Build c = new Build(null, null, null);
        c.versions.add(BuildVersion.of(1, "console build", m));
        c.currentVersion = 0;
        Build cr = Build.fromJson(c.toJson());
        check("console owner null", cr.owner == null);
        eq("blank ownerName defaults to console", "console", cr.ownerName);
        eq("blank worldName defaults to world", "world", cr.worldName);

        // version/undo/redo bookkeeping
        Build u = new Build(null, "console", "world");
        eq("empty build has no current", null, u.current());
        eq("empty build canUndo", false, u.canUndo());
        eq("empty build canRedo", false, u.canRedo());
        u.addVersion(m, "first", "first version", 50);
        eq("after addVersion currentVersion", 0, u.currentVersion);
        check("after addVersion canUndo", u.canUndo());
        check("after addVersion !canRedo", !u.canRedo());
        eq("history recorded", 1, u.history.size());
        u.addVersion(m, "second", "second version", 60);
        eq("second version number", 2, u.current().number);
        u.undo();
        eq("undo currentVersion", 0, u.currentVersion);
        check("after undo canRedo", u.canRedo());
        u.redo();
        eq("redo currentVersion", 1, u.currentVersion);
        boolean threw = false;
        try {
            u.redo();
        } catch (IllegalStateException e) {
            threw = true;
        }
        check("redo past the end throws", threw);

        // defensive parsing: missing fields, wrong types, corrupt versions
        Build def = Build.fromJson(new com.google.gson.JsonObject());
        check("empty json yields a usable build", def.id != null && def.versions != null && def.currentVersion == -1);
        com.google.gson.JsonObject weird = new com.google.gson.JsonObject();
        weird.addProperty("id", "ZZZ");
        weird.addProperty("rotation", 17);
        weird.addProperty("currentVersion", 99);
        weird.addProperty("versions", "not an array");
        weird.addProperty("terrain", "nope");
        Build wb2 = Build.fromJson(weird);
        check("invalid id is replaced", Build.isValidId(wb2.id), "id=" + wb2.id);
        eq("rotation is normalized", 1, wb2.rotation);
        eq("out-of-range currentVersion clamps to -1", -1, wb2.currentVersion);
        check("no crash on corrupt terrain", wb2.terrain.isEmpty());

        // ids
        for (int i = 0; i < 200; i++) {
            String id = Build.randomId();
            if (!Build.isValidId(id) || id.length() != 6) {
                fail("randomId produced invalid id " + id);
                break;
            }
        }
        eq("sanitizeId lower-cases", "3fa8c1", Build.sanitizeId(" 3FA8C1 "));
        eq("sanitizeId strips suffix", "3fa8c1", Build.sanitizeId("3fa8c1.json.gz"));
        check("sanitizeId replaces garbage", Build.isValidId(Build.sanitizeId("hello world!")));
    }

    // ------------------------------------------------------------------ store

    private static void store() throws Exception {
        Path tmp = Files.createTempDirectory("aibuild-store-test");
        try {
            File folder = tmp.toFile();
            Logger log = Logger.getLogger("BuildStoreTest");
            BuildStore store = new BuildStore(folder, log);
            store.loadAll();
            eq("empty store size", 0, store.size());
            check("findAt on empty store", store.findAt("world", 0, 64, 0) == null);

            UUID owner = UUID.randomUUID();
            Build b = store.create(owner, "Steve", "world");
            check("created build has a valid id", Build.isValidId(b.id));
            eq("created build owner", owner, b.owner);
            eq("created build ownerName", "Steve", b.ownerName);
            eq("created build world", "world", b.worldName);
            check("store.get finds created build", store.get(b.id) == b);
            check("store.get is case-insensitive", store.get(b.id.toUpperCase()) == b);

            VoxelModel m = new VoxelModel();
            for (int x = 0; x <= 4; x++) {
                for (int z = 0; z <= 4; z++) {
                    m.set(x, 0, z, BlockSpec.of("stone_bricks"));
                }
            }
            m.set(2, 1, 2, BlockSpec.of("oak_door", Map.of("facing", "south", "half", "lower")));
            m.set(0, 1, 0, BlockSpec.of("oak_stairs", Map.of("facing", "east", "half", "top")));
            b.originX = 100;
            b.originY = 64;
            b.originZ = -100;
            b.rotation = 1;
            b.placed = true;
            b.name = "Tower";
            b.originalPrompt = "a stone tower";
            b.history.add("a stone tower");
            b.addVersion(m, "a stone tower", "built a tower", 88);
            b.terrain.put(WorldKey.pack(100, 64, -100), "minecraft:grass_block");
            b.terrain.put(WorldKey.pack(101, 64, -100), "minecraft:dirt");
            store.save(b);
            store.setActive(owner, b.id);

            Build second = store.create(null, "console", "world_nether");
            second.name = "Nether Base";
            store.save(second);
            eq("store size after two creates", 2, store.size());

            // findAt: build occupies world x 96..100, z -100..-96 (rot 1 of 0..4 box)
            int[] wb = PlacementMath.worldBounds(b, m.bounds());
            System.out.println("   build world bounds = " + java.util.Arrays.toString(wb));
            Build at = store.findAt("world", (wb[0] + wb[3]) / 2, 65, (wb[2] + wb[5]) / 2);
            check("findAt finds the build inside its bounds", at == b);
            check("findAt ignores points outside", store.findAt("world", wb[3] + 100, 65, wb[5]) == null);
            check("findAt ignores the wrong world", store.findAt("other_world", (wb[0] + wb[3]) / 2, 65,
                    (wb[2] + wb[5]) / 2) == null);
            check("findAt ignores unplaced builds", unplacedAt(store) == null);

            // shutdown flushes; reload from a fresh store instance
            store.shutdown();
            check("build file written", new File(folder, "builds/" + b.id + ".json.gz").isFile());
            check("players file written", new File(folder, "players.json").isFile());

            BuildStore reloaded = new BuildStore(folder, log);
            reloaded.loadAll();
            eq("reloaded size", 2, reloaded.size());
            Build rb = reloaded.get(b.id);
            check("reloaded build present", rb != null);
            eq("reloaded name", "Tower", rb.name);
            eq("reloaded origin", "100,64,-100", rb.originX + "," + rb.originY + "," + rb.originZ);
            eq("reloaded rotation", 1, rb.rotation);
            check("reloaded placed flag", rb.placed);
            eq("reloaded versions", 1, rb.versions.size());
            eq("reloaded currentVersion", 0, rb.currentVersion);
            eq("reloaded model size", m.size(), rb.currentModel().size());
            eq("reloaded terrain size", 2, rb.terrain.size());
            eq("reloaded terrain value", "minecraft:dirt", rb.terrain.get(WorldKey.pack(101, 64, -100)));
            eq("reloaded active player", b.id, reloaded.getActive(owner));
            eq("byOwner", 1, reloaded.byOwner(owner).size());
            eq("byOwner of console", 0, reloaded.byOwner(null).size());

            // active player persistence across a setActive(null)
            reloaded.setActive(owner, null);
            BuildStore reloaded2 = new BuildStore(folder, log);
            reloaded2.loadAll();
            check("cleared active selection persists", reloaded2.getActive(owner) == null);
            reloaded2.shutdown();

            // delete
            check("delete returns true", reloaded.delete(b.id));
            check("deleted build is gone", reloaded.get(b.id) == null);
            reloaded.shutdown();
            check("build file removed", !new File(folder, "builds/" + b.id + ".json.gz").exists());

            BuildStore reloaded3 = new BuildStore(folder, log);
            reloaded3.loadAll();
            eq("deleted build does not come back", 1, reloaded3.size());
            reloaded3.shutdown();
        } finally {
            deleteRecursively(tmp);
        }
    }

    private static Build unplacedAt(BuildStore store) {
        // a build that is in the store but not placed must not be found
        Build fresh = store.create(null, "console", "world");
        fresh.originX = 0;
        fresh.originY = 64;
        fresh.originZ = 0;
        fresh.placed = false;
        fresh.addVersion(singleCell(), "x", "x", 0);
        return store.findAt("world", 0, 64, 0);
    }

    private static VoxelModel singleCell() {
        VoxelModel m = new VoxelModel();
        m.set(0, 0, 0, BlockSpec.of("stone"));
        return m;
    }

    // ------------------------------------------------------------------ corrupt files

    private static void corruptFiles() throws Exception {
        Path tmp = Files.createTempDirectory("aibuild-corrupt-test");
        try {
            File folder = tmp.toFile();
            File builds = new File(folder, "builds");
            check("builds dir created", builds.isDirectory() || builds.mkdirs());
            Files.writeString(new File(builds, "aaaaaa.json.gz").toPath(), "this is not gzip");
            Files.writeString(new File(builds, "bbbbbb.json.gz").toPath(), "also not gzip at all");
            Files.writeString(new File(builds, "notes.txt").toPath(), "ignored");
            Files.writeString(new File(builds, "zz-not-hex.json.gz").toPath(), "ignored");
            Files.writeString(new File(builds, "stale.json.gz.tmp123").toPath(), "stale temp");
            Logger log = Logger.getLogger("BuildStoreCorruptTest");
            BuildStore store = new BuildStore(folder, log);
            store.loadAll(); // must not throw
            eq("corrupt files are skipped", 0, store.size());
            check("stale temp file removed", !new File(builds, "stale.json.gz.tmp123").exists());
            store.shutdown();
        } finally {
            deleteRecursively(tmp);
        }
    }

    // ------------------------------------------------------------------ placer helpers

    private static void placerHelpers() {
        // The fragility classifier is pure in BlockData, but BlockData needs a server; verify the
        // classifier's name-based half via reflection-free smoke checks on the public surface instead.
        check("WorldPlacer.fragile exists as a static helper", hasMethod("fragile"));

        PlaceResult r = new PlaceResult(10, 3, 250, false);
        eq("PlaceResult changed", 10, r.changed());
        eq("PlaceResult captured", 3, r.captured());
        eq("PlaceResult millis", 250L, r.millis());
        eq("PlaceResult cancelled", false, r.cancelled());
        check("PlaceResult.completed", r.completed());
        check("cancelled result is not completed", !new PlaceResult(1, 0, 5, true).completed());
        check("describe mentions blocks", r.describe().contains("10 block"));

        VoxelModel model = new VoxelModel();
        model.set(0, 0, 0, BlockSpec.of("stone"));
        model.set(2, 3, 2, BlockSpec.of("stone"));
        int[] b = model.bounds();
        eq("model bounds", "[0, 0, 0, 2, 3, 2]", java.util.Arrays.toString(b));

        // StubBlockInfo sanity for the capture fallback path (parse of a material name)
        StubBlockInfo info = new StubBlockInfo();
        check("stub parses a plain name", info.parse("oak_log") != null);
        boolean threw = false;
        try {
            info.parse("not a block [");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check("stub rejects malformed state", threw);
    }

    private static boolean hasMethod(String name) {
        for (var m : WorldPlacer.class.getDeclaredMethods()) {
            if (m.getName().equals(name) && java.lang.reflect.Modifier.isStatic(m.getModifiers())) return true;
        }
        return false;
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> s = Files.walk(root)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // best effort
                }
            });
        } catch (Exception ignored) {
            // best effort
        }
    }

    static List<String> unused() {
        return List.of();
    }
}
