package dev.aibuild.render;

import dev.aibuild.model.BlockSpec;
import dev.aibuild.model.StubBlockInfo;
import dev.aibuild.model.VoxelModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VoxelRasterTest {
    private final StubBlockInfo info = new StubBlockInfo();
    private static final VoxelRaster.Options NO_GROUND = new VoxelRaster.Options(Integer.MAX_VALUE, 0, false);

    @AfterEach
    void reset() {
        BlockTextures.install(null);
    }

    @Test void elevationShowsTheRealHeightOfASlab() {
        VoxelModel m = new VoxelModel();
        for (int x = 0; x < 4; x++) m.set(x, 1, 0, BlockSpec.of("stone_slab"));
        m.set(0, 4, 0, BlockSpec.of("stone")); // makes the frame 4 tall: the slab fills only the lower half of y=1
        int[] b = {0, 1, 0, 3, 4, 0};
        BufferedImage img = VoxelRaster.render(m, info, VoxelRaster.elevation(b, 0, 200, 200), NO_GROUND);
        int[] col = column(img, 150);
        int filled = 0;
        for (int c : col) if (!isSky(c)) filled++;
        int blockPx = filled; // pixels of the slab in a column without the stone block
        assertTrue(blockPx > 10 && blockPx < 30, "a slab is half a block (~23 px of a 4-block, 188 px frame), got " + blockPx);
    }

    @Test void stairsHaveAStepOnTheFacingSide() {
        VoxelModel m = new VoxelModel();
        m.set(0, 1, 0, BlockSpec.of("stone_stairs", Map.of("facing", "north")));
        int[] b = {0, 1, 0, 0, 1, 0};
        // seen from the east (looking west), north is on the right: the full-height step is there
        BufferedImage img = VoxelRaster.render(m, info, VoxelRaster.elevation(b, 3, 100, 100), NO_GROUND);
        int south = height(img, 30), north = height(img, 70);
        assertTrue(north > south * 1.6, "north half is twice as tall as the south half: " + north + " vs " + south);
    }

    @Test void texturesAreSampledPerTexel() {
        int[] checker = new int[256];
        for (int i = 0; i < 256; i++) checker[i] = ((i % 16 + i / 16) % 2 == 0) ? 0xFFFFFFFF : 0xFF000000;
        Map<String, int[]> faces = new HashMap<>();
        faces.put("stone", new int[]{0, 0, 0});
        BlockTextures.install(new BlockTextures(List.of(checker), faces));
        VoxelModel m = new VoxelModel();
        m.set(0, 1, 0, BlockSpec.of("stone"));
        BufferedImage img = VoxelRaster.render(m, info, VoxelRaster.top(new int[]{0, 1, 0, 0, 1, 0}, 160, 160), NO_GROUND);
        int distinct = 0, prev = -1;
        for (int x = 20; x < 140; x++) {
            int c = img.getRGB(x, 80) & 0xFFFFFF;
            if (c != prev) distinct++;
            prev = c;
        }
        assertTrue(distinct >= 12, "a 16-texel checker shows its texels across the face, changes: " + distinct);
    }

    @Test void eyeLevelCameraFramesTheBuildAndShowsGround() {
        VoxelModel m = new VoxelModel();
        for (int y = 1; y <= 10; y++) m.set(0, y, 0, BlockSpec.of("stone"));
        int[] b = {0, 1, 0, 0, 10, 0};
        BufferedImage img = VoxelRaster.render(m, info, VoxelRaster.eye(b, 200, 150), new VoxelRaster.Options(Integer.MAX_VALUE, 60, false));
        assertFalse(isSky(img.getRGB(100, 75)), "the pillar is in the middle of the view");
        assertFalse(isSky(img.getRGB(10, 145)), "the ground fills the bottom of an eye-level view");
        assertTrue(isSky(img.getRGB(10, 5)), "sky above");
    }

    @Test void fencesConnectToNeighbours() {
        VoxelModel m = new VoxelModel();
        m.set(0, 1, 0, BlockSpec.of("oak_fence"));
        m.set(1, 1, 0, BlockSpec.of("oak_fence"));
        m.set(4, 1, 0, BlockSpec.of("oak_fence"));
        int[] b = {0, 1, 0, 4, 1, 0};
        BufferedImage img = VoxelRaster.render(m, info, VoxelRaster.elevation(b, 0, 250, 100), NO_GROUND);
        // between the two connected posts (x = 0.5..1.5) the rails are visible; between 1 and 4 nothing
        int y = img.getHeight() / 2 - 2;
        boolean railBetween = false, gap = true;
        for (int x = 0; x < img.getWidth(); x++) {
            double bx = x / (img.getWidth() / 5.3) - 0.15;
            if (bx > 0.75 && bx < 1.25 && !isSky(img.getRGB(x, y))) railBetween = true;
            if (bx > 2.2 && bx < 3.6 && !isSky(img.getRGB(x, y))) gap = false;
        }
        assertTrue(railBetween, "connected fences show rails between them");
        assertTrue(gap, "lonely fences do not connect across air");
    }

    private static int[] column(BufferedImage img, int x) {
        int[] c = new int[img.getHeight()];
        for (int y = 0; y < c.length; y++) c[y] = img.getRGB(x, y);
        return c;
    }

    private static int height(BufferedImage img, int x) {
        int n = 0;
        for (int c : column(img, x)) if (!isSky(c)) n++;
        return n;
    }

    private static boolean isSky(int argb) {
        int r = (argb >> 16) & 255, g = (argb >> 8) & 255, b = argb & 255;
        return b > 230 && g > 210 && r > 190 && b >= r;
    }
}
