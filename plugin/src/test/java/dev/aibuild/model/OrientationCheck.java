package dev.aibuild.model;

import com.google.gson.JsonParser;
import dev.aibuild.analysis.TextRenderer;
import dev.aibuild.model.ops.OpExecutor;
import dev.aibuild.model.ops.OpResult;

/** Self-checking test for OrientationFixer, the trim/staircase ops and orientation glyph rendering. */
public final class OrientationCheck {
    static int fails, passes;

    static void check(boolean ok, String what) {
        if (ok) passes++; else { fails++; System.out.println("FAIL " + what); }
    }

    static String facing(BlockInfo info, VoxelModel m, int x, int y, int z) {
        BlockSpec s = m.get(x, y, z);
        return s == null ? "null" : info.getProp(s, "facing");
    }

    public static void main(String[] a) {
        BlockInfo info = new StubBlockInfo();
        OpExecutor ex = new OpExecutor(info);

        // --- house shell 10x10 with WRONG skirt (facing outward) and WRONG eaves
        VoxelModel m = new VoxelModel();
        OpResult r = ex.apply(m, JsonParser.parseString("""
            {"ops":[
             {"op":"walls","from":[0,1,0],"to":[9,8,9],"block":"stone_bricks"},
             {"op":"set","positions":[[3,1,10],[4,1,10]],"block":"stone_brick_stairs[facing=south]"},
             {"op":"set","positions":[[-1,1,3],[-1,1,4]],"block":"stone_brick_stairs[facing=west]"},
             {"op":"set","positions":[[3,8,10],[4,8,10]],"block":"spruce_stairs[facing=south,half=top]"},
             {"op":"set","pos":[5,3,10],"block":"spruce_stairs[half=top,facing=east]"},
             {"op":"roof","from":[0,9,0],"to":[9,9,9],"style":"gable","ridge":"x","stairs":"spruce_stairs","overhang":1}
            ]}""").getAsJsonObject());
        check(r.opsFailed == 0, "setup ops ok: " + r.summary());
        // break 2 roof stairs on purpose
        m.set(4, 9, 10, info.parse("spruce_stairs[facing=south]"));
        m.set(4, 10, -1 + 1, info.parse("spruce_stairs[facing=north]"));
        var changes = OrientationFixer.analyze(m, info);
        changes.forEach(c -> System.out.println("  " + c));
        OrientationFixer.fix(m, info);
        check("north".equals(facing(info, m, 3, 1, 10)), "south skirt faces north (into wall)");
        check("east".equals(facing(info, m, -1, 1, 3)), "west skirt faces east");
        check("north".equals(facing(info, m, 3, 8, 10)), "south eave faces north");
        check("top".equals(info.getProp(m.get(3, 8, 10), "half")), "eave stays upside-down");
        check("north".equals(facing(info, m, 5, 3, 10)), "sill faces wall");
        check("north".equals(facing(info, m, 4, 9, 10)), "broken south roof stair fixed uphill (north)");
        check("south".equals(facing(info, m, 4, 10, 0)), "broken north roof stair fixed uphill (south)");
        check(OrientationFixer.analyze(m, info).isEmpty(), "idempotent");

        // --- skirt with corner pillars + a woodpile pressed against it (row consensus)
        VoxelModel kk = new VoxelModel();
        ex.apply(kk, JsonParser.parseString("""
            [{"op":"walls","from":[0,1,0],"to":[9,4,9],"block":"oak_planks"},
             {"op":"set","positions":[[10,1,-1],[10,1,10],[-1,1,10],[-1,1,-1]],"block":"cobblestone"},
             {"op":"line","from":[10,1,0],"to":[10,1,9],"block":"cobblestone_stairs[facing=east]"},
             {"op":"line","from":[11,1,3],"to":[11,1,5],"block":"oak_log[axis=x]"},
             {"op":"set","pos":[-2,1,9],"block":"cobblestone"},
             {"op":"line","from":[-1,1,0],"to":[-1,1,9],"block":"cobblestone_stairs[facing=west]"}]"""));
        OrientationFixer.fix(kk, info);
        boolean allWest = true, allEast = true;
        for (int zz = 0; zz <= 9; zz++) {
            allWest &= "west".equals(facing(info, kk, 10, 1, zz));
            allEast &= "east".equals(facing(info, kk, -1, 1, zz));
        }
        check(allWest, "east skirt incl. corner-pillar and woodpile cells faces the wall (west)");
        check(allEast, "west skirt incl. cell next to a curb stone faces the wall (east)");
        check(OrientationFixer.analyze(kk, info).isEmpty(), "consensus is stable (idempotent)");

        // --- chair + table
        VoxelModel c = new VoxelModel();
        c.set(5, 1, 5, info.parse("oak_fence"));
        c.set(5, 2, 5, info.parse("oak_pressure_plate"));
        c.set(4, 1, 5, info.parse("oak_stairs[facing=east]")); // back toward table: wrong
        c.set(6, 1, 5, info.parse("oak_stairs[facing=east]")); // correct (east of table, back away)
        OrientationFixer.fix(c, info);
        check("west".equals(facing(info, c, 4, 1, 5)), "chair west of table faces west");
        check("east".equals(facing(info, c, 6, 1, 5)), "chair east of table untouched");

        // --- slabs
        VoxelModel s = new VoxelModel();
        s.set(2, 4, 2, info.parse("oak_planks"));
        s.set(2, 3, 2, info.parse("oak_slab")); // hanging bottom slab under a block -> top
        s.set(6, 1, 6, info.parse("stone"));
        s.set(6, 2, 6, info.parse("stone_slab[type=top]")); // gap above stone -> bottom
        OrientationFixer.fix(s, info);
        check("top".equals(info.getProp(s.get(2, 3, 2), "type")), "hanging slab -> top");
        check("bottom".equals(info.getProp(s.get(6, 2, 6), "type")), "gapped slab -> bottom");

        // --- staircase op
        VoxelModel st = new VoxelModel();
        st.set(3, 5, 4, info.parse("oak_planks")); // floor above that must be cut
        OpResult r2 = ex.apply(st, JsonParser.parseString("""
            [{"op":"staircase","from":[3,1,7],"direction":"north","steps":4,"block":"spruce_stairs[facing=south]","support":"spruce_planks"}]
            """));
        check(r2.opsFailed == 0, "staircase ok " + r2.summary());
        for (int k = 0; k < 4; k++) check("north".equals(facing(info, st, 3, 1 + k, 7 - k)), "step " + k + " faces north");
        check(st.get(3, 1, 6) != null && st.get(3, 1, 6).name().equals("spruce_planks"), "support under step 1");
        check(st.get(3, 5, 4) == null || st.get(3, 5, 4).isAir(), "headroom cut through floor");
        check(OrientationFixer.analyze(st, info).isEmpty(), "fixer agrees with staircase");

        // --- trim op
        VoxelModel t = new VoxelModel();
        ex.apply(t, JsonParser.parseString("""
            [{"op":"walls","from":[0,1,0],"to":[9,4,9],"block":"stone"},
             {"op":"trim","from":[0,1,0],"to":[9,1,9],"block":"stone_brick_stairs","half":"bottom","skip":[[4,1,10],[5,1,10]]},
             {"op":"trim","from":[0,5,0],"to":[9,5,9],"block":"spruce_stairs","half":"top"}]"""));
        check("north".equals(facing(info, t, 2, 1, 10)), "trim south faces north");
        check("east".equals(facing(info, t, -1, 1, 5)), "trim west faces east");
        check(t.get(4, 1, 10) == null, "trim skip leaves door gap");
        check("top".equals(info.getProp(t.get(2, 5, -1), "half")), "eave trim upside-down");
        ShapeResolver.resolve(t, info);
        check(String.valueOf(t.get(-1, 1, -1)).contains("outer"), "trim corner outer shape: " + t.get(-1, 1, -1));

        // --- renderer glyphs and alignment
        String txt = TextRenderer.render(t, info, new TextRenderer.Options().elevations(false).topView(false));
        check(txt.contains("v"), "render has glyphs");
        int w = -1;
        boolean aligned = true;
        for (String line : txt.split("\n")) {
            if (!line.startsWith("z=")) continue;
            if (w < 0) w = line.length(); else if (line.length() != w) aligned = false;
        }
        check(aligned, "all layer rows have equal width (air cells are 2 chars)");

        System.out.println("OrientationCheck: " + passes + " passed, " + fails + " failed");
        if (fails > 0) System.exit(1);
    }
}
