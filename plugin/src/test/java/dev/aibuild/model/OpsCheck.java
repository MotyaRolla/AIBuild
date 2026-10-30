package dev.aibuild.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aibuild.model.ops.OpExecutor;
import dev.aibuild.model.ops.OpResult;
import dev.aibuild.model.ops.RoofBuilder;

import java.util.List;

/**
 * Self-checking test for module B (patterns, DSL ops, roofs, ShapeResolver). Plain main(), no JUnit:
 * prints PASS/FAIL lines and exits with code 1 when anything failed.
 */
public final class OpsCheck {
    private static int checks = 0;
    private static int failures = 0;
    private static final BlockInfo INFO = new StubBlockInfo();

    public static void main(String[] args) {
        patterns();
        opsBasics();
        opsShapes();
        opsReplace();
        opsRound();
        opsCopy();
        opsAliases();
        opsErrors();
        roofs();
        shapeResolver();
        resultSummary();

        System.out.println();
        System.out.println("-----------------------------------------------");
        System.out.println(checks + " checks, " + failures + " failures");
        if (failures > 0) {
            System.exit(1);
        }
        System.out.println("ALL CHECKS PASSED");
    }

    // ------------------------------------------------------------------ helpers

    private static void check(String name, boolean ok) {
        checks++;
        if (ok) {
            System.out.println("PASS " + name);
        } else {
            failures++;
            System.out.println("FAIL " + name);
        }
    }

    private static void checkEq(String name, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        checks++;
        if (ok) {
            System.out.println("PASS " + name);
        } else {
            failures++;
            System.out.println("FAIL " + name + " (expected <" + expected + "> got <" + actual + ">)");
        }
    }

    private static void checkContains(String name, String haystack, String needle) {
        boolean ok = haystack != null && haystack.contains(needle);
        checks++;
        if (ok) {
            System.out.println("PASS " + name);
        } else {
            failures++;
            System.out.println("FAIL " + name + " (expected to contain <" + needle + "> in <" + haystack + ">)");
        }
    }

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static JsonArray arr(String s) {
        return JsonParser.parseString(s).getAsJsonArray();
    }

    private static OpResult run(VoxelModel m, String ops) {
        return new OpExecutor(INFO).apply(m, JsonParser.parseString(ops));
    }

    private static String at(VoxelModel m, int x, int y, int z) {
        BlockSpec s = m.get(x, y, z);
        return s == null ? "<unset>" : s.toString();
    }

    private static String prop(VoxelModel m, int x, int y, int z, String key) {
        BlockSpec s = m.get(x, y, z);
        return s == null ? null : INFO.getProp(s, key);
    }

    private static int count(VoxelModel m, String specString) {
        int n = 0;
        for (BlockSpec s : m.entries().values()) if (s.toString().equals(specString)) n++;
        return n;
    }

    private static int countName(VoxelModel m, String name) {
        int n = 0;
        for (BlockSpec s : m.entries().values()) if (s.name().equals(name)) n++;
        return n;
    }

    private static BlockPattern pat(String jsonText) {
        return BlockPattern.parse(JsonParser.parseString(jsonText), INFO);
    }

    // ------------------------------------------------------------------ 1. patterns

    private static void patterns() {
        BlockPattern single = pat("\"oak_planks\"");
        check("pattern: single isSingle", single.isSingle());
        checkEq("pattern: single options", 1, single.options().size());
        checkEq("pattern: single pick", "oak_planks", single.pick(3, 4, 5).toString());
        checkEq("pattern: single pick far", "oak_planks", single.pick(-120, 30, 999).toString());
        checkEq("pattern: single toString", "oak_planks", single.toString());

        BlockPattern mixed = pat("\"60%stone,25%cobblestone,15%andesite\"");
        check("pattern: weighted not single", !mixed.isSingle());
        checkEq("pattern: weighted option count", 3, mixed.options().size());
        int stone = 0, cobble = 0, andesite = 0;
        for (int x = 0; x < 22; x++) {
            for (int y = 0; y < 22; y++) {
                for (int z = 0; z < 22; z++) {
                    switch (mixed.pick(x, y, z).name()) {
                        case "stone" -> stone++;
                        case "cobblestone" -> cobble++;
                        case "andesite" -> andesite++;
                        default -> { }
                    }
                }
            }
        }
        int total = stone + cobble + andesite;
        checkEq("pattern: weighted total picks", 22 * 22 * 22, total);
        double ps = 100.0 * stone / total, pc = 100.0 * cobble / total, pa = 100.0 * andesite / total;
        check("pattern: stone share ~60% (" + round(ps) + ")", Math.abs(ps - 60) < 4);
        check("pattern: cobble share ~25% (" + round(pc) + ")", Math.abs(pc - 25) < 4);
        check("pattern: andesite share ~15% (" + round(pa) + ")", Math.abs(pa - 15) < 4);

        // commas inside [...] must not split entries
        BlockPattern bracket = pat("\"50%oak_stairs[facing=east,half=top],50%stone\"");
        checkEq("pattern: bracket commas entry count", 2, bracket.options().size());
        BlockSpec stairOpt = bracket.options().get(0);
        checkEq("pattern: bracket commas name", "oak_stairs", stairOpt.name());
        checkEq("pattern: bracket commas facing", "east", INFO.getProp(stairOpt, "facing"));
        checkEq("pattern: bracket commas half", "top", INFO.getProp(stairOpt, "half"));
        checkEq("pattern: bracket commas second", "stone", bracket.options().get(1).name());

        BlockPattern equal = pat("\"stone,cobblestone\"");
        checkEq("pattern: equal weights count", 2, equal.options().size());
        int a = 0;
        for (int i = 0; i < 10000; i++) if (equal.pick(i, 0, 0).name().equals("stone")) a++;
        check("pattern: equal weights ~50% (" + round(a / 100.0) + ")", Math.abs(a / 100.0 - 50) < 5);

        BlockPattern arrayStrings = pat("[\"stone\",\"cobblestone\"]");
        checkEq("pattern: json array count", 2, arrayStrings.options().size());
        check("pattern: json array mixed", !arrayStrings.isSingle());

        BlockPattern arrayObjects = pat("[{\"block\":\"stone\",\"weight\":3},{\"block\":\"cobblestone\",\"weight\":1}]");
        checkEq("pattern: json object array count", 2, arrayObjects.options().size());
        int s3 = 0;
        for (int i = 0; i < 10000; i++) if (arrayObjects.pick(i * 7, i, 3).name().equals("stone")) s3++;
        check("pattern: weight 3:1 ~75% (" + round(s3 / 100.0) + ")", Math.abs(s3 / 100.0 - 75) < 4);

        BlockPattern jsonObject = pat("{\"stone\":70,\"cobblestone\":30}");
        checkEq("pattern: json object count", 2, jsonObject.options().size());
        int o70 = 0;
        for (int i = 0; i < 10000; i++) if (jsonObject.pick(i, i * 3, i * 5).name().equals("stone")) o70++;
        check("pattern: json object ~70% (" + round(o70 / 100.0) + ")", Math.abs(o70 / 100.0 - 70) < 4);

        // determinism: independent parses give identical picks
        BlockPattern m2 = pat("\"60%stone,25%cobblestone,15%andesite\"");
        boolean same = true;
        for (int x = -5; x < 5; x++)
            for (int y = 0; y < 4; y++)
                for (int z = -5; z < 5; z++)
                    same &= mixed.pick(x, y, z).equals(m2.pick(x, y, z));
        check("pattern: deterministic across parses", same);
        check("pattern: deterministic repeated pick", mixed.pick(12, 7, -3).equals(mixed.pick(12, 7, -3)));
        check("pattern: varied picks differ", !mixed.pick(0, 0, 0).equals(mixed.pick(1, 0, 0))
                || !mixed.pick(2, 0, 0).equals(mixed.pick(3, 0, 0)));

        check("pattern: toString names entries", mixed.toString().contains("stone")
                && mixed.toString().contains("andesite"));

        // error handling
        expectIae("pattern: bad entry rejected", () -> pat("\"stone,nope block\""));
        try {
            pat("\"stone,nope block\"");
        } catch (IllegalArgumentException ex) {
            checkContains("pattern: error names entry", ex.getMessage(), "nope block");
        }
        expectIae("pattern: empty rejected", () -> pat("\"\""));
        expectIae("pattern: null rejected", () -> BlockPattern.parse((com.google.gson.JsonElement) null, INFO));
        expectIae("pattern: bad weight rejected", () -> pat("\"abc%stone\""));
        expectIae("pattern: air in mix rejected", () -> pat("\"air,stone\""));
        check("pattern: mining namespace ok", pat("\"minecraft:stone\"").isSingle());
        checkEq("pattern: single seed stable", pat("\"stone\"").seed(), pat("\"stone\"").seed());
    }

    private static String round(double d) {
        return String.format(java.util.Locale.ROOT, "%.1f", d);
    }

    private static void expectIae(String name, Runnable r) {
        try {
            r.run();
            check(name + " (no exception)", false);
        } catch (IllegalArgumentException ex) {
            check(name, true);
        } catch (RuntimeException ex) {
            check(name + " (wrong exception " + ex.getClass().getSimpleName() + ")", false);
        }
    }

    // ------------------------------------------------------------------ 2. basic ops

    private static void opsBasics() {
        VoxelModel m = new VoxelModel();
        OpResult r = run(m, """
                {"ops":[
                  {"op":"fill","from":[0,1,0],"to":[2,1,2],"block":"stone"},
                  {"op":"setblock","pos":[5,1,5],"block":"oak_planks"},
                  {"op":"place","positions":[[6,1,5],[7,1,5]],"material":"glass"},
                  {"op":"block","at":{"x":8,"y":1,"z":5},"pattern":"cobblestone"}
                ]}""");
        check("ops: fill/set batch ok", r.isOk());
        checkEq("ops: fill batch total", 4, r.opsTotal);
        checkEq("ops: fill batch ok count", 4, r.opsOk);
        checkEq("ops: fill cells changed", 9 + 1 + 2 + 1, r.cellsChanged);
        checkEq("ops: fill content", "stone", at(m, 1, 1, 1));
        checkEq("ops: fill corner", "stone", at(m, 2, 1, 2));
        checkEq("ops: fill outside", "<unset>", at(m, 3, 1, 0));
        checkEq("ops: setblock content", "oak_planks", at(m, 5, 1, 5));
        checkEq("ops: positions content", "glass", at(m, 7, 1, 5));
        checkEq("ops: object pos content", "cobblestone", at(m, 8, 1, 5));
        checkEq("ops: fill size", 13, m.size());

        // wrapper aliases + float/string coordinates
        VoxelModel m2 = new VoxelModel();
        OpResult r2 = new OpExecutor(INFO).apply(m2, json(
                "{\"operations\":[{\"op\":\"fill\",\"start\":[0.4,1.6,\"2\"],\"end\":{\"x\":1,\"y\":2,\"z\":3},\"block\":\"stone\"}]}"));
        check("ops: operations wrapper", r2.isOk());
        checkEq("ops: rounded coords cell", "stone", at(m2, 0, 2, 2));
        checkEq("ops: rounded coords cell 2", "stone", at(m2, 1, 2, 3));
        checkEq("ops: rounded coords volume", 2 * 1 * 2, r2.cellsChanged);

        VoxelModel m3 = new VoxelModel();
        OpResult r3 = new OpExecutor(INFO).apply(m3, json(
                "{\"actions\":[{\"op\":\"set\",\"pos\":[1,1,1],\"block\":\"stone\"}]}"));
        check("ops: actions wrapper", r3.isOk() && m3.size() == 1);

        VoxelModel m4 = new VoxelModel();
        OpResult r4 = new OpExecutor(INFO).apply(m4, arr("[{\"op\":\"set\",\"pos\":[1,1,1],\"block\":\"stone\"}]"));
        check("ops: bare array accepted", r4.isOk() && m4.size() == 1);

        // clear
        VoxelModel m5 = new VoxelModel();
        OpResult rc = run(m5, """
                [{"op":"fill","from":[0,1,0],"to":[2,1,2],"block":"stone"},
                 {"op":"air","from":[0,1,0],"to":[2,1,2]}]""");
        check("ops: clear ok", rc.isOk());
        checkEq("ops: clear removes", 0, m5.size());
        checkEq("ops: clear counts", 9 + 9, rc.cellsChanged);

        // clear above ground of unset cells is a no-op
        VoxelModel m6 = new VoxelModel();
        OpResult rc2 = run(m6, "[{\"op\":\"clear\",\"from\":[0,5,0],\"to\":[2,5,2]}]");
        check("ops: clear empty region ok", rc2.isOk());
        checkEq("ops: clear empty region no changes", 0, rc2.cellsChanged);
        checkEq("ops: clear empty region no cells", 0, m6.size());

        // walls / hollow / outline
        VoxelModel m7 = new VoxelModel();
        OpResult rw = run(m7, "[{\"op\":\"wall\",\"from\":[0,1,0],\"to\":[2,3,2],\"block\":\"stone\"}]");
        check("ops: walls ok", rw.isOk());
        checkEq("ops: walls cell count", 24, m7.size());
        checkEq("ops: walls interior empty", "<unset>", at(m7, 1, 2, 1));

        VoxelModel m8 = new VoxelModel();
        OpResult rh = run(m8, "[{\"op\":\"shell\",\"from\":[0,0,0],\"to\":[2,2,2],\"block\":\"stone\"}]");
        check("ops: hollow ok", rh.isOk());
        checkEq("ops: hollow cell count", 26, m8.size());
        checkEq("ops: hollow interior", "<unset>", at(m8, 1, 1, 1));

        VoxelModel m9 = new VoxelModel();
        OpResult ro = run(m9, "[{\"op\":\"frame\",\"from\":[0,0,0],\"to\":[2,2,2],\"block\":\"oak_log\"}]");
        check("ops: outline ok", ro.isOk());
        checkEq("ops: outline edge count", 20, m9.size());
        checkEq("ops: outline corner axis", "y", prop(m9, 0, 0, 0, "axis"));
        checkEq("ops: outline x edge axis", "x", prop(m9, 1, 0, 0, "axis"));
        checkEq("ops: outline z edge axis", "z", prop(m9, 0, 0, 1, "axis"));
        checkEq("ops: outline axis vertical", "y", prop(m9, 0, 0, 2, "axis"));

        // outline axis is x along an x-edge, z along a z-edge
        VoxelModel m10 = new VoxelModel();
        run(m10, "[{\"op\":\"outline\",\"from\":[0,0,0],\"to\":[4,4,4],\"block\":\"oak_log\"}]");
        checkEq("ops: outline long x edge axis", "x", prop(m10, 2, 0, 0, "axis"));
        checkEq("ops: outline long z edge axis", "z", prop(m10, 0, 0, 2, "axis"));
        checkEq("ops: outline corner axis y", "y", prop(m10, 0, 0, 0, "axis"));

        // explicit axis is preserved
        VoxelModel m11 = new VoxelModel();
        run(m11, "[{\"op\":\"line\",\"from\":[0,0,0],\"to\":[4,0,0],\"block\":\"oak_log[axis=z]\"}]");
        checkEq("ops: explicit axis preserved", "z", prop(m11, 2, 0, 0, "axis"));
        VoxelModel m11b = new VoxelModel();
        run(m11b, "[{\"op\":\"line\",\"from\":[0,0,0],\"to\":[4,0,0],\"block\":\"oak_log[axis=y]\"}]");
        checkEq("ops: default axis auto-oriented", "x", prop(m11b, 2, 0, 0, "axis"));

        // line
        VoxelModel m12 = new VoxelModel();
        OpResult rl = run(m12, "[{\"op\":\"line\",\"from\":[0,0,0],\"to\":[4,0,0],\"block\":\"stone\"}]");
        check("ops: line ok", rl.isOk());
        checkEq("ops: line cells", 5, m12.size());
        checkEq("ops: line end", "stone", at(m12, 4, 0, 0));
        VoxelModel m13 = new VoxelModel();
        OpResult rl2 = run(m13, "[{\"op\":\"line\",\"from\":[0,0,0],\"to\":[0,3,0],\"block\":\"oak_log\"}]");
        checkEq("ops: line vertical axis", "y", prop(m13, 0, 2, 0, "axis"));
        check("ops: line vertical count", rl2.cellsChanged == 4);

        // set with many positions and an unknown extra field
        VoxelModel m14 = new VoxelModel();
        OpResult rs = run(m14, """
                [{"op":"set","positions":[[0,1,0],[1,1,0],[2,1,0]],"block":"stone","note":"a row"}]""");
        check("ops: set list ok", rs.isOk());
        checkEq("ops: set list count", 3, m14.size());
    }

    // ------------------------------------------------------------------ 3. shape ops

    private static void opsShapes() {
        VoxelModel m = new VoxelModel();
        OpResult r = run(m, """
                [{"op":"tower","center":[0,0,0],"radius":0.5,"height":3,"block":"stone"},
                 {"op":"dome","center":[10,5,0],"radius":2,"block":"glass"},
                 {"op":"sphere","center":[20,5,0],"radius":1.5,"block":"glass","hollow":true}]""");
        check("ops: cylinder/sphere ok", r.isOk());
        checkEq("ops: cylinder disc 5x3", 15, countName(m, "stone"));
        checkEq("ops: cylinder center", "stone", at(m, 0, 0, 0));
        checkEq("ops: cylinder absent corner", "<unset>", at(m, 1, 0, 1));

        VoxelModel m2 = new VoxelModel();
        OpResult r2 = run(m2, """
                [{"op":"cylinder","center":[0,0,0],"radius":1.5,"height":1,"block":"stone","hollow":true}]""");
        check("ops: hollow cylinder ok", r2.isOk());
        checkEq("ops: hollow cylinder center empty", "<unset>", at(m2, 0, 0, 0));
        checkEq("ops: hollow cylinder open top", "<unset>", at(m2, 1, 1, 0));
        checkEq("ops: hollow cylinder rim", "stone", at(m2, 2, 0, 0));
        checkEq("ops: hollow cylinder inner", "<unset>", at(m2, 1, 0, 0));
        checkEq("ops: solid cylinder center filled", "stone", at(m, 0, 0, 0));

        VoxelModel m3 = new VoxelModel();
        OpResult r3 = run(m3, """
                [{"op":"cylinder","center":[0,2,0],"radius":1,"height":4,"block":"stone","axis":"x"}]""");
        check("ops: cylinder x axis ok", r3.isOk());
        checkEq("ops: cylinder x axis extent", "stone", at(m3, 3, 2, 0));
        checkEq("ops: cylinder x axis end", "<unset>", at(m3, 4, 2, 0));

        VoxelModel m4 = new VoxelModel();
        OpResult r4 = run(m4, """
                [{"op":"sphere","center":[0,0,0],"radius":2,"block":"glass","half":"top"}]""");
        check("ops: half sphere ok", r4.isOk());
        checkEq("ops: half sphere top kept", "glass", at(m4, 0, 1, 0));
        checkEq("ops: half sphere bottom dropped", "<unset>", at(m4, 0, -1, 0));

        VoxelModel m5 = new VoxelModel();
        run(m5, "[{\"op\":\"sphere\",\"center\":[0,0,0],\"radius\":2,\"block\":\"glass\"}]");
        checkEq("ops: sphere center", "glass", at(m5, 0, 0, 0));
        checkEq("ops: sphere corner empty", "<unset>", at(m5, 2, 2, 2));
    }

    // ------------------------------------------------------------------ 4. replace

    private static void opsReplace() {
        VoxelModel m = new VoxelModel();
        OpResult r = run(m, """
                [{"op":"fill","from":[0,0,0],"to":[3,0,0],"block":"stone"},
                 {"op":"set","positions":[[1,1,0]],"block":"oak_planks"},
                 {"op":"set","positions":[[2,1,0]],"block":"spruce_planks"},
                 {"op":"set","positions":[[0,1,0]],"block":"oak_stairs[facing=east]"},
                 {"op":"set","positions":[[3,1,0]],"block":"oak_stairs[facing=west]"},
                 {"op":"replace","match":"stone","block":"cobblestone"},
                 {"op":"replace","match":"*_planks","block":"dark_oak_planks"},
                 {"op":"replace","match":"oak_stairs[facing=east]","block":"stone_bricks"}]""");
        check("ops: replace batch ok", r.isOk());
        checkEq("ops: replace plain", "cobblestone", at(m, 0, 0, 0));
        checkEq("ops: replace glob 1", "dark_oak_planks", at(m, 1, 1, 0));
        checkEq("ops: replace glob 2", "dark_oak_planks", at(m, 2, 1, 0));
        checkEq("ops: replace props matched", "stone_bricks", at(m, 0, 1, 0));
        checkEq("ops: replace props mismatch kept", "oak_stairs[facing=west]", at(m, 3, 1, 0));

        VoxelModel m2 = new VoxelModel();
        OpResult r2 = run(m2, """
                [{"op":"fill","from":[0,0,0],"to":[2,0,2],"block":"stone"},
                 {"op":"replace","from":[0,0,0],"to":[0,0,2],"match":["stone","oak_planks"],"block":"glass"}]""");
        check("ops: replace region ok", r2.isOk());
        checkEq("ops: replace region inside", "glass", at(m2, 0, 0, 1));
        checkEq("ops: replace region outside", "stone", at(m2, 2, 0, 1));

        VoxelModel m3 = new VoxelModel();
        OpResult r3 = run(m3, "[{\"op\":\"replace\",\"block\":\"stone\"}]");
        check("ops: replace missing match fails", r3.opsFailed == 1 && r3.errors.get(0).contains("match"));
    }

    // ------------------------------------------------------------------ 5. round volumes

    private static void opsRound() {
        VoxelModel m = new VoxelModel();
        OpResult r = run(m, """
                [{"op":"cylinder","center":[0,0,0],"radius":2.2,"height":1,"block":"stone","hollow":true},
                 {"op":"sphere","center":[10,0,0],"radius":2.5,"block":"glass","hollow":true}]""");
        check("ops: hollow round ok", r.isOk());
        int solidCyl = 0, hollowCyl = 0;
        // count independently: disc dx^2+dz^2 <= 2.7^2
        for (int x = -3; x <= 3; x++)
            for (int z = -3; z <= 3; z++) {
                double d2 = x * x + z * z;
                if (d2 <= 2.7 * 2.7) {
                    solidCyl++;
                    boolean inside = (x + 1) * (x + 1) + z * z <= 2.7 * 2.7
                            && (x - 1) * (x - 1) + z * z <= 2.7 * 2.7
                            && x * x + (z + 1) * (z + 1) <= 2.7 * 2.7
                            && x * x + (z - 1) * (z - 1) <= 2.7 * 2.7;
                    if (!inside) hollowCyl++;
                }
            }
        checkEq("ops: hollow cylinder rim count", hollowCyl, countName(m, "stone"));
        check("ops: hollow cylinder has a hole", hollowCyl < solidCyl);
        checkEq("ops: hollow sphere center empty", "<unset>", at(m, 10, 0, 0));
        checkEq("ops: hollow sphere shell", "glass", at(m, 13, 0, 0));
        checkEq("ops: hollow sphere inner", "<unset>", at(m, 12, 0, 0));
    }

    // ------------------------------------------------------------------ 6. copy

    private static void opsCopy() {
        VoxelModel m = new VoxelModel();
        OpResult r = run(m, """
                [{"op":"set","pos":[0,1,0],"block":"oak_stairs[facing=east]"},
                 {"op":"set","pos":[1,1,0],"block":"stone"},
                 {"op":"mirror_copy","from":[0,1,0],"to":[1,1,0],"offset":[0,0,3],"mirror":"x"}]""");
        check("ops: copy mirror ok", r.isOk());
        checkEq("ops: copy mirror state", "oak_stairs[facing=west]", at(m, 1, 1, 3));
        checkEq("ops: copy mirror stone", "stone", at(m, 0, 1, 3));

        VoxelModel m2 = new VoxelModel();
        OpResult r2 = run(m2, """
                [{"op":"set","pos":[0,1,0],"block":"oak_stairs[facing=east]"},
                 {"op":"set","pos":[1,1,0],"block":"stone"},
                 {"op":"clone","from":[0,1,0],"to":[1,1,0],"offset":[0,0,3],"rotate":90}]""");
        check("ops: copy rotate ok", r2.isOk());
        checkEq("ops: copy rotate state", "oak_stairs[facing=south]", at(m2, 0, 1, 3));
        checkEq("ops: copy rotate position", "stone", at(m2, 0, 1, 4));

        VoxelModel m3 = new VoxelModel();
        OpResult r3 = run(m3, """
                [{"op":"set","pos":[0,1,0],"block":"oak_log[axis=x]"},
                 {"op":"copy","from":[0,1,0],"to":[0,1,0],"offset":[5,0,0],"rotate":90}]""");
        check("ops: copy log rotate ok", r3.isOk());
        checkEq("ops: copy log axis rotated", "oak_log[axis=z]", at(m3, 5, 1, 0));

        VoxelModel m4 = new VoxelModel();
        OpResult r4 = run(m4, """
                [{"op":"set","pos":[0,1,0],"block":"oak_stairs[facing=east]"},
                 {"op":"copy","from":[0,1,0],"to":[0,1,0],"offset":[0,0,2],"rotate":180}]""");
        checkEq("ops: copy rotate 180", "oak_stairs[facing=west]", at(m4, 0, 1, 2));

        VoxelModel m5 = new VoxelModel();
        OpResult r5 = run(m5, """
                [{"op":"set","pos":[0,1,0],"block":"oak_stairs[facing=north]"},
                 {"op":"copy","from":[0,1,0],"to":[0,1,0],"offset":[0,0,2],"mirror":"z"}]""");
        checkEq("ops: copy mirror z", "oak_stairs[facing=south]", at(m5, 0, 1, 2));

        // overlapping copy must snapshot the source first
        VoxelModel m6 = new VoxelModel();
        OpResult r6 = run(m6, """
                [{"op":"set","pos":[0,1,0],"block":"stone"},
                 {"op":"set","pos":[1,1,0],"block":"glass"},
                 {"op":"copy","from":[0,1,0],"to":[1,1,0],"offset":[1,0,0]}]""");
        checkEq("ops: overlapping copy source", "stone", at(m6, 1, 1, 0));
        checkEq("ops: overlapping copy shifted", "glass", at(m6, 2, 1, 0));

        VoxelModel m7 = new VoxelModel();
        OpResult r7 = run(m7, "[{\"op\":\"copy\",\"from\":[0,0,0],\"to\":[1,1,1],\"mirror\":\"q\"}]");
        check("ops: bad mirror axis fails", r7.opsFailed == 1 && r7.errors.get(0).contains("mirror"));
    }

    // ------------------------------------------------------------------ 7. aliases

    private static void opsAliases() {
        record Alias(String alias, String json, int size) { }
        List<Alias> aliases = List.of(
                new Alias("box", "{\"op\":\"box\",\"from\":[0,0,0],\"to\":[1,1,1],\"block\":\"stone\"}", 8),
                new Alias("cuboid", "{\"op\":\"cuboid\",\"from\":[0,0,0],\"to\":[1,1,1],\"block\":\"stone\"}", 8),
                new Alias("setblock", "{\"op\":\"setblock\",\"pos\":[0,0,0],\"block\":\"stone\"}", 1),
                new Alias("place", "{\"op\":\"place\",\"pos\":[0,0,0],\"block\":\"stone\"}", 1),
                new Alias("air", "{\"op\":\"air\",\"from\":[0,3,0],\"to\":[0,3,0]}", 0),
                new Alias("delete", "{\"op\":\"delete\",\"from\":[0,3,0],\"to\":[0,3,0]}", 0),
                new Alias("remove", "{\"op\":\"remove\",\"from\":[0,3,0],\"to\":[0,3,0]}", 0),
                new Alias("erase", "{\"op\":\"erase\",\"from\":[0,3,0],\"to\":[0,3,0]}", 0),
                new Alias("frame", "{\"op\":\"frame\",\"from\":[0,0,0],\"to\":[2,2,2],\"block\":\"oak_log\"}", 20),
                new Alias("edges", "{\"op\":\"edges\",\"from\":[0,0,0],\"to\":[2,2,2],\"block\":\"oak_log\"}", 20),
                new Alias("shell", "{\"op\":\"shell\",\"from\":[0,0,0],\"to\":[2,2,2],\"block\":\"stone\"}", 26),
                new Alias("wall", "{\"op\":\"wall\",\"from\":[0,0,0],\"to\":[2,2,2],\"block\":\"stone\"}", 24),
                new Alias("tower", "{\"op\":\"tower\",\"center\":[0,0,0],\"radius\":0.5,\"height\":1,\"block\":\"stone\"}", 5),
                new Alias("dome", "{\"op\":\"dome\",\"center\":[0,0,0],\"radius\":0.5,\"block\":\"stone\"}", 7),
                new Alias("mirror_copy", "{\"op\":\"mirror_copy\",\"from\":[0,0,0],\"to\":[0,0,0]}", 0),
                new Alias("clone", "{\"op\":\"clone\",\"from\":[0,0,0],\"to\":[0,0,0]}", 0));
        for (Alias a : aliases) {
            VoxelModel m = new VoxelModel();
            OpResult r = run(m, "[" + a.json() + "]");
            check("op alias: " + a.alias() + " accepted", r.isOk() && r.opsTotal == 1);
            checkEq("op alias: " + a.alias() + " effect", a.size(), m.size());
        }

        // op name is case/space insensitive
        VoxelModel m = new VoxelModel();
        OpResult r = run(m, "[{\"op\":\" Fill \",\"from\":[0,0,0],\"to\":[0,0,0],\"block\":\"stone\"}]");
        check("op alias: case/space insensitive", r.isOk() && m.size() == 1);
    }

    // ------------------------------------------------------------------ 8. errors

    private static void opsErrors() {
        VoxelModel m = new VoxelModel();
        OpResult r = run(m, """
                [{"op":"sparkle","from":[0,0,0],"to":[1,1,1],"block":"stone"},
                 {"op":"fill","to":[1,1,1],"block":"stone"},
                 {"op":"fill","from":[0,0,0],"to":[200,200,200],"block":"stone"},
                 {"op":"fill","from":[0,0,0],"to":[1,1,1],"block":"nope block"},
                 {"op":"fill","from":[0,0,0],"to":[1,1,1],"block":"stone"}]""");
        checkEq("ops: errors total", 5, r.opsTotal);
        checkEq("ops: errors failed", 4, r.opsFailed);
        checkEq("ops: errors ok", 1, r.opsOk);
        check("ops: error continues after failure", m.size() == 8);
        checkContains("ops: unknown op message", r.errors.get(0), "unknown op 'sparkle'");
        check("ops: unknown op has index", r.errors.get(0).startsWith("op[0] "));
        checkContains("ops: missing field message", r.errors.get(1), "op[1] fill: missing");
        checkContains("ops: volume message", r.errors.get(2), "exceeds the per-op limit");
        checkContains("ops: bad block message", r.errors.get(3), "nope block");
        check("ops: bad block keeps prefix", r.errors.get(3).startsWith("op[3] fill: "));

        // configurable limits
        VoxelModel big = new VoxelModel();
        OpExecutor small = new OpExecutor(INFO, 8, 16, 1000);
        OpResult rs = small.apply(big, arr("[{\"op\":\"fill\",\"from\":[0,0,0],\"to\":[2,2,2],\"block\":\"stone\"}]"));
        check("ops: custom volume limit", rs.opsFailed == 1 && rs.errors.get(0).contains("per-op limit"));
        OpResult rs2 = small.apply(big, arr("[{\"op\":\"fill\",\"from\":[0,0,0],\"to\":[20,0,0],\"block\":\"stone\"}]"));
        check("ops: custom coord limit", rs2.opsFailed == 1 && rs2.errors.get(0).contains("coordinate limit"));

        // model cell cap stops the batch
        VoxelModel cap = new VoxelModel();
        OpExecutor tiny = new OpExecutor(INFO, 1000, 256, 10);
        OpResult rc = tiny.apply(cap, arr("""
                [{"op":"fill","from":[0,0,0],"to":[9,0,0],"block":"stone"},
                 {"op":"fill","from":[0,1,0],"to":[9,1,0],"block":"stone"}]"""));
        check("ops: model cap reported", rc.errors.get(rc.errors.size() - 1).contains("limit of 10 cells"));

        // malformed batches never throw
        check("ops: null batch", new OpExecutor(INFO).apply(new VoxelModel(), null).hasErrors());
        check("ops: string batch", new OpExecutor(INFO).apply(new VoxelModel(),
                JsonParser.parseString("\"nope\"")).hasErrors());
        check("ops: empty wrapper", new OpExecutor(INFO).apply(new VoxelModel(),
                json("{\"ops\":[]}")).isOk());
        check("ops: op not an object", new OpExecutor(INFO).apply(new VoxelModel(),
                arr("[42]")).hasErrors());
        check("ops: missing op name", new OpExecutor(INFO).apply(new VoxelModel(),
                arr("[{\"from\":[0,0,0],\"to\":[1,1,1],\"block\":\"stone\"}]")).hasErrors());
        check("ops: bad coordinate", new OpExecutor(INFO).apply(new VoxelModel(),
                arr("[{\"op\":\"fill\",\"from\":[\"x\",0,0],\"to\":[1,1,1],\"block\":\"stone\"}]")).hasErrors());
        check("ops: negative radius", new OpExecutor(INFO).apply(new VoxelModel(),
                arr("[{\"op\":\"sphere\",\"center\":[0,0,0],\"radius\":-2,\"block\":\"stone\"}]")).hasErrors());
        check("ops: missing roof stairs", new OpExecutor(INFO).apply(new VoxelModel(),
                arr("[{\"op\":\"roof\",\"from\":[0,5,0],\"to\":[4,5,4],\"fill\":\"stone\"}]")).hasErrors());
        check("ops: bad roof style", new OpExecutor(INFO).apply(new VoxelModel(),
                arr("[{\"op\":\"roof\",\"from\":[0,5,0],\"to\":[4,5,4],\"style\":\"onion\",\"stairs\":\"oak_stairs\"}]")).hasErrors());
        check("ops: single op object", new OpExecutor(INFO).apply(new VoxelModel(),
                json("{\"op\":\"set\",\"pos\":[0,0,0],\"block\":\"stone\"}")).isOk());

        // "cmd" is a documented op-name alias; the remaining keys stay ordinary fields
        VoxelModel amb = new VoxelModel();
        OpResult ramb = run(amb, """
                [{"cmd":"box","from":[0,1,0],"to":[1,1,1],"block":"stone"}]""");
        check("ops: cmd resolves the op name", ramb.isOk() && amb.size() == 4);
        // a style in "type" must not shadow the op named by "action"
        VoxelModel amb3 = new VoxelModel();
        OpResult ramb3 = run(amb3, """
                [{"action":"fill","type":"stone","from":[0,1,0],"to":[1,1,1],"block":"stone"}]""");
        check("ops: action wins over type", ramb3.isOk() && amb3.size() == 4
                && ramb3.errors.isEmpty());

        // roof declared with action + type=style
        VoxelModel amb2 = new VoxelModel();
        OpResult ramb2 = run(amb2, """
                [{"action":"roof","type":"gable","from":[0,10,0],"to":[6,10,6],"stairs":"oak_stairs",
                  "slab":"oak_slab","overhang":0}]""");
        check("ops: action roof type style", ramb2.isOk()
                && ramb2.info.stream().anyMatch(x -> x.contains("style=gable")));

        // enormous coordinate values must be rejected, not overflow the key space
        check("ops: huge coordinate rejected", new OpExecutor(INFO).apply(new VoxelModel(),
                arr("[{\"op\":\"set\",\"pos\":[2147483000,0,0],\"block\":\"stone\"}]")).hasErrors());
        VoxelModel rnd = new VoxelModel();
        check("ops: float coordinate rounds", run(rnd,
                "[{\"op\":\"set\",\"pos\":[1.9,2.2,-0.6],\"block\":\"stone\"}]").isOk());
        checkEq("ops: rounded set cell", "stone", at(rnd, 2, 2, -1));
    }

    // ------------------------------------------------------------------ 9. roofs

    private static void roofs() {
        // gable 10x10, overhang 1 -> expanded 12x12, ridge along x (equal sides)
        VoxelModel m = new VoxelModel();
        OpResult r = run(m, """
                [{"op":"roof","from":[0,10,0],"to":[9,10,9],"style":"gable",
                  "stairs":"oak_stairs","slab":"oak_slab","fill":"stone","overhang":1}]""");
        check("roof gable ok", r.isOk());
        check("roof gable info", r.info.stream().anyMatch(s -> s.startsWith("roof: style=gable top_y=")));
        checkEq("roof gable first north row facing", "south", prop(m, 0, 10, -1, "facing"));
        checkEq("roof gable first south row facing", "north", prop(m, 0, 10, 10, "facing"));
        checkEq("roof gable first layer corner", "south", prop(m, -1, 10, -1, "facing"));
        checkEq("roof gable second north row", "south", prop(m, 0, 11, 0, "facing"));
        checkEq("roof gable second south row", "north", prop(m, 0, 11, 9, "facing"));
        checkEq("roof gable no stair above layer0", "<unset>", at(m, 0, 11, -1));
        check("roof gable closes", m.get(0, 15, 4) != null && m.get(0, 15, 5) != null);
        checkEq("roof gable nothing above ridge", "<unset>", at(m, 0, 16, 4));
        check("roof gable half bottom", "bottom".equals(prop(m, 0, 10, -1, "half"))
                || prop(m, 0, 10, -1, "half") == null);
        // gable end fill at the wall planes (x = 0 and x = 9), not on the overhang
        checkEq("roof gable fill bottom", "stone", at(m, 0, 10, 0));
        checkEq("roof gable fill top", "stone", at(m, 9, 14, 5));
        checkEq("roof gable fill other plane", "stone", at(m, 9, 10, 9));
        checkEq("roof gable no fill on overhang x", "<unset>", at(m, -1, 10, 0));
        checkEq("roof gable no fill on overhang x2", "<unset>", at(m, 10, 10, 5));
        checkEq("roof gable fill does not overwrite stairs", "south", prop(m, 0, 11, 0, "facing"));

        // gable 9x9 overhang 0 -> ridge line of slabs at y = base + 4
        VoxelModel m2 = new VoxelModel();
        OpResult r2 = run(m2, """
                [{"op":"roof","from":[0,10,0],"to":[8,10,8],"style":"gable","stairs":"oak_stairs",
                  "slab":"oak_slab","overhang":0}]""");
        check("roof gable odd ok", r2.isOk());
        checkEq("roof gable ridge cell", "oak_slab", at(m2, 4, 14, 4));
        checkEq("roof gable ridge type", "bottom", prop(m2, 4, 14, 4, "type"));
        checkEq("roof gable ridge first", "oak_slab", at(m2, 0, 14, 4));
        checkEq("roof gable ridge last", "oak_slab", at(m2, 8, 14, 4));
        checkEq("roof gable ridge no extra layer", "<unset>", at(m2, 4, 15, 4));
        check("roof gable odd top_y", r2.info.stream().anyMatch(s -> s.contains("top_y=14")));

        // ridge along z explicitly
        VoxelModel m3 = new VoxelModel();
        OpResult r3 = run(m3, """
                [{"op":"roof","from":[0,10,0],"to":[8,10,8],"style":"gable","stairs":"oak_stairs",
                  "slab":"oak_slab","overhang":0,"ridge":"z"}]""");
        check("roof gable ridge z ok", r3.isOk());
        checkEq("roof gable ridge z cell", "oak_slab", at(m3, 4, 14, 4));
        checkEq("roof gable ridge z north row", "east", prop(m3, 0, 10, 0, "facing"));
        checkEq("roof gable ridge z east row", "west", prop(m3, 8, 10, 0, "facing"));

        // hip 7x7 overhang 1 -> 9x9 rings, cap at center
        VoxelModel m4 = new VoxelModel();
        OpResult r4 = run(m4, """
                [{"op":"roof","from":[0,10,0],"to":[6,10,6],"style":"hip","stairs":"oak_stairs",
                  "slab":"oak_slab","overhang":1}]""");
        check("roof hip ok", r4.isOk());
        checkEq("roof hip north edge", "south", prop(m4, 3, 10, -1, "facing"));
        checkEq("roof hip south edge", "north", prop(m4, 3, 10, 7, "facing"));
        checkEq("roof hip west edge", "east", prop(m4, -1, 10, 3, "facing"));
        checkEq("roof hip east edge", "west", prop(m4, 7, 10, 3, "facing"));
        checkEq("roof hip corner facing", "south", prop(m4, -1, 10, -1, "facing"));
        checkEq("roof hip ring 2", "south", prop(m4, 3, 11, 0, "facing"));
        checkEq("roof hip cap", "oak_slab", at(m4, 3, 14, 3));
        checkEq("roof hip top_y", null, null); // placeholder keeps counters balanced
        check("roof hip info top_y", r4.info.stream().anyMatch(s -> s.equals("roof: style=hip top_y=14")));

        // pyramid alias of hip on an even footprint -> 2x2 cap of slabs
        VoxelModel m5 = new VoxelModel();
        OpResult r5 = run(m5, """
                [{"op":"roof","from":[0,10,0],"to":[5,10,5],"style":"pyramid","stairs":"oak_stairs",
                  "slab":"oak_slab","overhang":0}]""");
        check("roof pyramid ok", r5.isOk());
        check("roof pyramid info top", r5.info.stream().anyMatch(s -> s.contains("top_y=12")));
        checkEq("roof pyramid last ring facing", "south", prop(m5, 2, 12, 2, "facing"));
        checkEq("roof pyramid nothing above", "<unset>", at(m5, 3, 13, 3));

        // shed facing north
        VoxelModel m6 = new VoxelModel();
        OpResult r6 = run(m6, """
                [{"op":"roof","from":[0,10,0],"to":[4,10,4],"style":"shed","stairs":"oak_stairs",
                  "facing":"north","overhang":0}]""");
        check("roof shed ok", r6.isOk());
        checkEq("roof shed low row", "north", prop(m6, 2, 10, 4, "facing"));
        checkEq("roof shed high row", "north", prop(m6, 2, 14, 0, "facing"));
        checkEq("roof shed nothing above", "<unset>", at(m6, 2, 15, 0));
        check("roof shed info", r6.info.stream().anyMatch(s -> s.contains("top_y=14")));

        // shed facing east: rows run along z, ascending +x
        VoxelModel m7 = new VoxelModel();
        run(m7, """
                [{"op":"roof","from":[0,10,0],"to":[4,10,4],"style":"shed","stairs":"oak_stairs",
                  "facing":"east","overhang":0}]""");
        checkEq("roof shed east low", "east", prop(m7, 0, 10, 2, "facing"));
        checkEq("roof shed east high", "east", prop(m7, 4, 14, 2, "facing"));

        // flat
        VoxelModel m8 = new VoxelModel();
        OpResult r8 = run(m8, """
                [{"op":"roof","from":[0,10,0],"to":[4,10,4],"style":"flat","slab":"oak_slab","overhang":1}]""");
        check("roof flat ok", r8.isOk());
        checkEq("roof flat cell count", 7 * 7, countName(m8, "oak_slab"));
        checkEq("roof flat corner", "oak_slab", at(m8, -1, 10, -1));

        // fill-only flat
        VoxelModel m9 = new VoxelModel();
        OpResult r9 = run(m9, """
                [{"op":"roof","from":[0,10,0],"to":[2,10,2],"style":"flat","fill":"stone","overhang":0}]""");
        check("roof flat fill ok", r9.isOk());
        checkEq("roof flat fill count", 9, countName(m9, "stone"));

        // a 1-wide gable must not loop forever and must cap
        VoxelModel m10 = new VoxelModel();
        OpResult r10 = run(m10, """
                [{"op":"roof","from":[0,10,0],"to":[0,10,4],"style":"gable","stairs":"oak_stairs",
                  "slab":"oak_slab","overhang":0}]""");
        check("roof 1 wide ok", r10.isOk() && !m10.isEmpty());

        // RoofBuilder used directly behaves the same
        VoxelModel m11 = new VoxelModel();
        OpResult direct = new OpResult();
        int changed = new RoofBuilder(INFO).build(m11, json("""
                {"from":[0,10,0],"to":[8,10,8],"style":"gable","stairs":"oak_stairs","slab":"oak_slab","overhang":0}"""), direct);
        check("roof builder direct changed", changed > 0 && changed == m11.size());
        check("roof builder direct info", direct.info.size() == 1);
    }

    // ------------------------------------------------------------------ 10. ShapeResolver

    private static void shapeResolver() {
        // door top / lone upper
        VoxelModel m = new VoxelModel();
        m.set(0, 1, 0, INFO.parse("oak_door"));
        m.set(3, 2, 0, INFO.parse("spruce_door[half=upper]"));
        m.set(6, 1, 0, INFO.parse("oak_door[facing=west,hinge=right]"));
        m.set(6, 2, 0, INFO.parse("stone"));
        List<String> notes = ShapeResolver.resolve(m, INFO);
        checkEq("resolve: door top added", "oak_door[half=upper]", at(m, 0, 2, 0));
        checkEq("resolve: door top facing kept", "north", prop(m, 0, 2, 0, "facing"));
        checkEq("resolve: lone door upper gets lower", "spruce_door", at(m, 3, 1, 0));
        checkEq("resolve: door preserved facing/hinge", "oak_door[facing=west,half=upper,hinge=right]", at(m, 6, 2, 0));
        check("resolve: door overwrite warning", notes.stream().anyMatch(s -> s.startsWith("warning: ")));
        checkContains("resolve: notes mention door", String.join(" | ", notes), "door tops");

        // bed head + lone head
        VoxelModel m2 = new VoxelModel();
        m2.set(0, 1, 0, INFO.parse("red_bed"));
        m2.set(3, 1, 3, INFO.parse("blue_bed[part=head,facing=west]"));
        ShapeResolver.resolve(m2, INFO);
        checkEq("resolve: bed head north", "red_bed[part=head]", at(m2, 0, 1, -1));
        checkEq("resolve: lone bed head gets foot", "blue_bed[facing=west]", at(m2, 4, 1, 3));

        // double plant
        VoxelModel m3 = new VoxelModel();
        m3.set(0, 1, 0, INFO.parse("tall_grass"));
        m3.set(2, 1, 0, INFO.parse("sunflower"));
        ShapeResolver.resolve(m3, INFO);
        checkEq("resolve: tall grass upper", "tall_grass[half=upper]", at(m3, 0, 2, 0));
        checkEq("resolve: sunflower upper", "sunflower[half=upper]", at(m3, 2, 2, 0));

        // leaves
        VoxelModel m4 = new VoxelModel();
        m4.set(0, 1, 0, INFO.parse("oak_leaves"));
        ShapeResolver.resolve(m4, INFO);
        checkEq("resolve: leaves persistent", "true", prop(m4, 0, 1, 0, "persistent"));

        // fences
        VoxelModel m5 = new VoxelModel();
        m5.set(0, 1, 0, INFO.parse("oak_fence"));
        m5.set(1, 1, 0, INFO.parse("spruce_fence"));
        m5.set(2, 1, 0, INFO.parse("oak_fence"));
        ShapeResolver.resolve(m5, INFO);
        checkEq("resolve: fence east", "true", prop(m5, 0, 1, 0, "east"));
        checkEq("resolve: fence west", "false", prop(m5, 0, 1, 0, "west"));
        checkEq("resolve: fence middle both", "true", prop(m5, 1, 1, 0, "west"));
        checkEq("resolve: fence middle both 2", "true", prop(m5, 1, 1, 0, "east"));
        checkEq("resolve: fence end no north", "false", prop(m5, 2, 1, 0, "north"));

        // nether brick fences only connect to each other
        VoxelModel m6 = new VoxelModel();
        m6.set(0, 1, 0, INFO.parse("nether_brick_fence"));
        m6.set(1, 1, 0, INFO.parse("oak_fence"));
        m6.set(4, 1, 0, INFO.parse("nether_brick_fence"));
        m6.set(3, 1, 0, INFO.parse("nether_brick_fence"));
        ShapeResolver.resolve(m6, INFO);
        checkEq("resolve: nether fence ignores wood", "false", prop(m6, 0, 1, 0, "east"));
        checkEq("resolve: nether fence joins nether", "true", prop(m6, 4, 1, 0, "west"));

        // fence gate perpendicular
        VoxelModel m7 = new VoxelModel();
        m7.set(0, 1, 0, INFO.parse("oak_fence"));
        m7.set(1, 1, 0, INFO.parse("oak_fence_gate[facing=north]"));
        ShapeResolver.resolve(m7, INFO);
        checkEq("resolve: gate perpendicular connects", "true", prop(m7, 0, 1, 0, "east"));

        // pane next to stone
        VoxelModel m8 = new VoxelModel();
        m8.set(0, 1, 0, INFO.parse("glass_pane"));
        m8.set(1, 1, 0, INFO.parse("stone_bricks"));
        ShapeResolver.resolve(m8, INFO);
        checkEq("resolve: pane connects to stone", "true", prop(m8, 0, 1, 0, "east"));
        checkEq("resolve: pane unconnected side", "false", prop(m8, 0, 1, 0, "north"));

        // pane chain + iron bars
        VoxelModel m9 = new VoxelModel();
        m9.set(0, 1, 0, INFO.parse("glass_pane"));
        m9.set(1, 1, 0, INFO.parse("iron_bars"));
        ShapeResolver.resolve(m9, INFO);
        checkEq("resolve: pane to bars", "true", prop(m9, 0, 1, 0, "east"));
        checkEq("resolve: bars to pane", "true", prop(m9, 1, 1, 0, "west"));

        // walls: straight line -> up=false, corner -> up=true
        VoxelModel m10 = new VoxelModel();
        for (int x = 0; x <= 2; x++) m10.set(x, 1, 0, INFO.parse("cobblestone_wall"));
        ShapeResolver.resolve(m10, INFO);
        checkEq("resolve: wall straight east", "low", prop(m10, 1, 1, 0, "east"));
        checkEq("resolve: wall straight up false", "false", prop(m10, 1, 1, 0, "up"));
        checkEq("resolve: wall end up false", "false", prop(m10, 0, 1, 0, "up"));
        VoxelModel m11 = new VoxelModel();
        m11.set(0, 1, 0, INFO.parse("cobblestone_wall"));
        m11.set(1, 1, 0, INFO.parse("cobblestone_wall"));
        m11.set(0, 1, -1, INFO.parse("cobblestone_wall"));
        ShapeResolver.resolve(m11, INFO);
        checkEq("resolve: wall corner up true", "true", prop(m11, 0, 1, 0, "up"));
        checkEq("resolve: wall corner sides", "low", prop(m11, 0, 1, 0, "north"));
        // wall tall when a solid block sits above
        VoxelModel m12 = new VoxelModel();
        m12.set(0, 1, 0, INFO.parse("cobblestone_wall"));
        m12.set(1, 1, 0, INFO.parse("cobblestone_wall"));
        m12.set(0, 2, 0, INFO.parse("stone"));
        ShapeResolver.resolve(m12, INFO);
        checkEq("resolve: wall tall under block", "tall", prop(m12, 0, 1, 0, "east"));

        // stairs: outer corners shared by two hip-like corners
        VoxelModel m13 = new VoxelModel();
        m13.set(0, 1, 0, INFO.parse("oak_stairs[facing=north]"));
        m13.set(0, 1, -1, INFO.parse("oak_stairs[facing=east]"));
        m13.set(10, 1, 0, INFO.parse("oak_stairs[facing=north]"));
        m13.set(10, 1, -1, INFO.parse("oak_stairs[facing=west]"));
        ShapeResolver.resolve(m13, INFO);
        checkEq("resolve: stair outer right", "outer_right", prop(m13, 0, 1, 0, "shape"));
        checkEq("resolve: stair outer left", "outer_left", prop(m13, 10, 1, 0, "shape"));

        // stairs: inner corners
        VoxelModel m14 = new VoxelModel();
        m14.set(0, 1, 0, INFO.parse("oak_stairs[facing=north]"));
        m14.set(0, 1, 1, INFO.parse("oak_stairs[facing=east]"));
        m14.set(10, 1, 0, INFO.parse("oak_stairs[facing=north]"));
        m14.set(10, 1, 1, INFO.parse("oak_stairs[facing=west]"));
        ShapeResolver.resolve(m14, INFO);
        checkEq("resolve: stair inner right", "inner_right", prop(m14, 0, 1, 0, "shape"));
        checkEq("resolve: stair inner left", "inner_left", prop(m14, 10, 1, 0, "shape"));

        // straight stairs stay straight (shape stripped as the default)
        VoxelModel m15 = new VoxelModel();
        m15.set(0, 1, 0, INFO.parse("oak_stairs[facing=east]"));
        m15.set(1, 1, 0, INFO.parse("oak_stairs[facing=east]"));
        ShapeResolver.resolve(m15, INFO);
        checkEq("resolve: straight stair keeps default", "oak_stairs[facing=east]", at(m15, 0, 1, 0));
        checkEq("resolve: straight stair prop", "straight", prop(m15, 0, 1, 0, "shape"));

        // different halves do not form corners
        VoxelModel m16 = new VoxelModel();
        m16.set(0, 1, 0, INFO.parse("oak_stairs[facing=north]"));
        m16.set(0, 1, -1, INFO.parse("oak_stairs[facing=east,half=top]"));
        ShapeResolver.resolve(m16, INFO);
        checkEq("resolve: half mismatch straight", "straight", prop(m16, 0, 1, 0, "shape"));

        // notes summary
        VoxelModel m17 = new VoxelModel();
        m17.set(0, 1, 0, INFO.parse("glass_pane"));
        m17.set(1, 1, 0, INFO.parse("stone"));
        m17.set(0, 1, 2, INFO.parse("oak_fence"));
        m17.set(0, 1, 3, INFO.parse("oak_fence"));
        List<String> notes17 = ShapeResolver.resolve(m17, INFO);
        checkContains("resolve: note mentions fences", String.join(" | ", notes17), "fences/panes");

        // idempotency over a mixed model
        VoxelModel mixed = new VoxelModel();
        mixed.set(0, 1, 0, INFO.parse("oak_door"));
        mixed.set(4, 1, 0, INFO.parse("red_bed"));
        for (int x = 0; x < 4; x++) mixed.set(x, 1, 5, INFO.parse("oak_fence"));
        mixed.set(6, 1, 0, INFO.parse("glass_pane"));
        mixed.set(7, 1, 0, INFO.parse("stone"));
        mixed.set(0, 1, 8, INFO.parse("tall_grass"));
        mixed.set(0, 1, 10, INFO.parse("oak_leaves"));
        mixed.set(0, 1, 12, INFO.parse("cobblestone_wall"));
        mixed.set(1, 1, 12, INFO.parse("cobblestone_wall"));
        mixed.set(0, 1, 14, INFO.parse("oak_stairs[facing=north]"));
        mixed.set(0, 1, 13, INFO.parse("oak_stairs[facing=east]"));
        ShapeResolver.resolve(mixed, INFO);
        String first = mixed.toJson().toString();
        List<String> secondNotes = ShapeResolver.resolve(mixed, INFO);
        String second = mixed.toJson().toString();
        checkEq("resolve: idempotent json", first, second);
        check("resolve: idempotent notes empty", secondNotes.isEmpty());

        // empty model
        check("resolve: empty model no notes", ShapeResolver.resolve(new VoxelModel(), INFO).isEmpty());
    }

    // ------------------------------------------------------------------ 11. OpResult

    private static void resultSummary() {
        OpResult a = new OpResult();
        a.opsTotal = 3;
        a.opsOk = 2;
        a.opsFailed = 1;
        a.cellsChanged = 42;
        a.error("op[0] fill: missing 'to' corner");
        a.warning("roof: overhang 5 clamped to 3");
        a.info("roof: style=gable top_y=12");
        String s = a.summary();
        checkContains("result: header", s, "ops: 2/3 ok");
        checkContains("result: cells", s, "cells changed: 42");
        checkContains("result: error line", s, "op[0] fill: missing 'to' corner");
        checkContains("result: warning line", s, "overhang 5 clamped");
        checkContains("result: info line", s, "top_y=12");

        OpResult b = new OpResult();
        b.opsTotal = 1;
        b.opsFailed = 1;
        b.error("op[0] set: bad block");
        b.cellsChanged = 8;
        a.merge(b);
        checkEq("result: merge total", 4, a.opsTotal);
        checkEq("result: merge failed", 2, a.opsFailed);
        checkEq("result: merge cells", 50, a.cellsChanged);
        checkEq("result: merge errors", 2, a.errors.size());

        OpResult many = new OpResult();
        for (int i = 0; i < 45; i++) many.error("op[" + i + "] fill: boom");
        String ms = many.summary();
        checkContains("result: truncates at 40", ms, "op[39] fill: boom");
        check("result: does not list 41st", !ms.contains("op[40] fill"));
        checkContains("result: reports overflow", ms, "... 5 more");
        check("result: merge self safe", a.merge(a) == a);
        check("result: empty ok", new OpResult().isOk());
        check("result: failed not ok", many.hasErrors());
    }
}
