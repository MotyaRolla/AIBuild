package dev.aibuild.model;

import com.google.gson.JsonParser;
import dev.aibuild.analysis.AnalyzerOptions;
import dev.aibuild.analysis.StructureAnalyzer;
import dev.aibuild.analysis.TextRenderer;
import dev.aibuild.model.ops.OpExecutor;
import dev.aibuild.model.ops.OpResult;

/** Self-check: castle-sized models render compactly; the plot guard stops runaway ops. */
public final class LargeBuildCheck {
    static int pass, fail;

    static void check(boolean ok, String what) {
        if (ok) pass++; else { fail++; System.out.println("FAIL " + what); }
    }

    public static void main(String[] a) {
        BlockInfo info = new StubBlockInfo();
        VoxelModel m = new VoxelModel();
        OpResult r = new OpExecutor(info).apply(m, JsonParser.parseString("""
            [{"op":"fill","from":[-5,0,-5],"to":[54,0,54],"block":"60%stone_bricks,40%cobblestone"},
             {"op":"walls","from":[0,1,0],"to":[49,12,49],"block":"70%stone_bricks,30%cracked_stone_bricks"},
             {"op":"cylinder","center":[0,1,0],"radius":4,"height":18,"block":"stone_bricks","hollow":true},
             {"op":"cylinder","center":[49,1,0],"radius":4,"height":18,"block":"stone_bricks","hollow":true},
             {"op":"cylinder","center":[0,1,49],"radius":4,"height":18,"block":"stone_bricks","hollow":true},
             {"op":"cylinder","center":[49,1,49],"radius":4,"height":18,"block":"stone_bricks","hollow":true},
             {"op":"hollow","from":[17,1,17],"to":[32,24,32],"block":"deepslate_bricks"},
             {"op":"roof","from":[17,25,17],"to":[32,25,32],"style":"pyramid","stairs":"dark_oak_stairs","overhang":1},
             {"op":"trim","from":[0,13,0],"to":[49,13,49],"block":"stone_brick_stairs","half":"top"}]"""));
        check(r.opsFailed == 0, "castle ops ok: " + r.summary());
        long t0 = System.nanoTime();
        String txt = TextRenderer.render(m, info, new TextRenderer.Options());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("castle model: " + m.nonAirCount() + " blocks, render " + txt.length() + " chars in " + ms + " ms");
        check(txt.contains("LAYERS (compact"), "compact mode used for 60x60 build");
        check(txt.length() < 250_000, "render size reasonable: " + txt.length());
        check(!txt.contains("truncated"), "not truncated");
        check(txt.contains("z=  0: 0-49 ") || txt.matches("(?s).*z=  0: -?\\d+.*"), "rows use coordinate runs");
        String grid = TextRenderer.render(m, info, new TextRenderer.Options().compact(false));
        System.out.println("full grid would be " + grid.length() + " chars");
        StructureAnalyzer.analyze(m, info, new AnalyzerOptions());

        // plot guard
        VoxelModel h = new VoxelModel();
        OpExecutor ex = new OpExecutor(info).withLimits(new int[]{-3, -2, -3, 12, 18, 12});
        OpResult g = ex.apply(h, JsonParser.parseString("""
            [{"op":"fill","from":[0,1,0],"to":[9,5,9],"block":"stone"},
             {"op":"set","positions":[[1,19,4],[1,20,4],[1,30,4]],"block":"dark_oak_slab"},
             {"op":"fill","from":[4,15,4],"to":[5,25,5],"block":"dark_oak_planks"}]"""));
        check(g.opsFailed == 2, "2 runaway ops reported failed: " + g.summary());
        check(h.get(1, 30, 4) == null && h.get(4, 25, 4) == null, "nothing written above the plot");
        check(h.get(4, 18, 4) != null, "cells inside the plot still written");
        check(g.summary().contains("outside the allowed build area"), "clear error message");
        System.out.println("LargeBuildCheck: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }
}
