package dev.aibuild.script;

import dev.aibuild.model.BlockSpec;
import dev.aibuild.model.StubBlockInfo;
import dev.aibuild.model.VoxelModel;
import dev.aibuild.render.FocalDetails;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Small marks on a surface (eyes, a sign, a stripe): painted as one layer on the side they are seen from, warned
 * about when a later part covers them, and found for a close-up so the model judges them where they can be seen.
 */
class SurfaceDetailTest {
    private final ScriptRuntime runtime = new ScriptRuntime(new StubBlockInfo(), Stdlib.V2);

    private ScriptResult run(String src) {
        ScriptResult r = runtime.run(src, 3, null, null, ScriptRuntime.Budget.standard(), null);
        assertTrue(r.ok(), () -> r.error().describe());
        return r;
    }

    private static int count(VoxelModel m, String name) {
        int[] n = {0};
        m.forEach((x, y, z, s) -> {
            if (s.name().equals(name)) n[0]++;
        });
        return n[0];
    }

    @Test void aPaintSeenFromASideLiesAsOneLayerOnTheSurface() {
        String head = "place(sphere([0, 10, 0], 4), 'black_concrete');\n";
        ScriptResult plain = run(head + "paint(cuboid([-1, 10, 0], [1, 11, 5]), 'lime_concrete');");
        ScriptResult front = run(head + "paint(cuboid([-1, 10, 0], [1, 11, 5]), 'lime_concrete', {from: 'south'});");
        assertTrue(count(plain.model(), "lime_concrete") > 6, "a plain paint reaches into the head");
        assertEquals(6, count(front.model(), "lime_concrete"), "one block per line from the south: 3 wide x 2 high");
        front.model().forEach((x, y, z, s) -> {
            if (!s.name().equals("lime_concrete")) return;
            BlockSpec before = front.model().get(x, y, z + 1);
            assertTrue(before == null || before.isAir(), "each painted block is the one seen first from the south");
        });
    }

    @Test void anUnknownSideStillPaintsWithAWarning() {
        ScriptResult r = run("place(cuboid([0, 1, 0], [2, 3, 2]), 'stone');\npaint(cuboid([0, 1, 0], [2, 3, 2]), 'dirt', {from: 'front'});");
        assertEquals(27, count(r.model(), "dirt"));
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("from must be one of")), () -> r.warnings().toString());
    }

    @Test void aLaterPartCoveringAnotherPartsDetailIsNamed() {
        ScriptResult r = run("""
                part('head', () => { place(cuboid([-3, 1, -3], [3, 7, 3]), 'black_concrete'); });
                part('eyes', () => { place(cuboid([-2, 5, 3], [-1, 5, 3]), 'lime_concrete'); place(cuboid([1, 5, 3], [2, 5, 3]), 'lime_concrete'); });
                part('ears', () => { place(cuboid([-2, 5, 3], [-2, 8, 3]), 'black_concrete'); });
                """);
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("part('ears') covers 1 of part('eyes')'s 4 blocks")), () -> r.warnings().toString());
    }

    @Test void partsMeetingOrRepaintingAreNotNamed() {
        ScriptResult r = run("""
                part('wall', () => { place(cuboid([0, 1, 0], [20, 6, 0]), 'stone_bricks'); });
                part('window', () => { place(cuboid([5, 3, 0], [6, 4, 0]), 'glass'); });
                part('face', () => { paint(cuboid([10, 3, 0], [11, 3, 0]), 'lime_concrete'); });
                """);
        assertTrue(r.warnings().stream().noneMatch(w -> w.contains("covers")), () -> r.warnings().toString());
    }

    @Test void aPaintThatLaterWritesHideEntirelyIsNamed() {
        ScriptResult r = run("""
                part('cat', () => {
                  place(cuboid([-3, 1, -3], [3, 7, 3]), 'black_concrete');
                  paint(cuboid([0, 4, 3], [0, 4, 3]), 'pink_concrete', {from: 'south'});
                  paint(cuboid([-1, 5, 3], [-1, 5, 3]), 'lime_concrete');
                  paint(cuboid([-1, 5, 3], [-1, 5, 3]), 'black_concrete');
                  paint(cuboid([-3, 3, -3], [3, 4, 3]), 'purple_wool');
                });
                """);
        List<String> hidden = r.warnings().stream().filter(w -> w.contains("entirely written over later")).toList();
        assertEquals(2, hidden.size(), () -> r.warnings().toString());
        assertTrue(hidden.get(0).contains("a paint of pink_concrete (1 block, e.g. at 0,4,3)") && hidden.get(0).contains("now purple_wool"), hidden.get(0));
        assertTrue(hidden.get(1).contains("lime_concrete"), hidden.get(1));
    }

    @Test void aMarkPaintedPartlyOverIsNotNamed() {
        ScriptResult r = run("""
                place(cuboid([-3, 1, -3], [3, 7, 3]), 'black_concrete');
                paint(cuboid([-2, 5, 3], [-1, 6, 3]), 'gold_block');
                paint(cuboid([-1, 5, 3], [-1, 6, 3]), 'black_concrete');
                """);
        assertTrue(r.warnings().stream().noneMatch(w -> w.contains("written over")), () -> r.warnings().toString());
    }

    @Test void theMarksOnAFaceAreFoundAsOneDetailFacingTheViewer() {
        ScriptResult r = run("""
                place(cuboid([-4, 1, -4], [4, 9, 4]), 'black_concrete');
                paint(cuboid([-2, 6, 4], [-1, 6, 4]), 'lime_concrete');
                paint(cuboid([1, 6, 4], [2, 6, 4]), 'lime_concrete');
                paint(cuboid([0, 4, 4], [0, 4, 4]), 'pink_concrete');
                """);
        List<FocalDetails.Detail> d = FocalDetails.find(r.model(), new StubBlockInfo(), r.model().solidBounds(), 2);
        assertEquals(1, d.size(), () -> d.toString());
        FocalDetails.Detail face = d.get(0);
        assertEquals(5, face.cells());
        assertArrayEquals(new int[]{-2, 4, 4, 2, 6, 4}, face.box());
        assertTrue(face.facing()[2] > 0.9, "it faces south, towards the viewer");
        assertTrue(FocalDetails.describe(face).contains("seen from south"), FocalDetails.describe(face));
    }

    @Test void aPlainBuildHasNoDetails() {
        ScriptResult r = run("place(cuboid([-4, 1, -4], [4, 9, 4]), mix({stone: 3, andesite: 2}));");
        assertTrue(FocalDetails.find(r.model(), new StubBlockInfo(), r.model().solidBounds(), 2).isEmpty());
    }
}
