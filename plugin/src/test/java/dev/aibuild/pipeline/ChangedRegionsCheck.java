package dev.aibuild.pipeline;

import dev.aibuild.model.BlockSpec;
import dev.aibuild.model.VoxelModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The regions the revision judge sees up close. Live defect: 3 of 9 critic rounds were rejected as "no visible
 * improvement" because the judge compared two whole-build overviews where a refined facade is a few pixels.
 */
class ChangedRegionsCheck {

    private static VoxelModel scene() {
        VoxelModel m = new VoxelModel();
        for (int x = 0; x <= 80; x++) for (int z = 0; z <= 80; z++) m.set(x, 0, z, BlockSpec.of("grass_block"));
        return m;
    }

    @Test void identicalBuildsHaveNoRegion() {
        assertTrue(BuildPipeline.changedRegions(scene(), scene(), 3).isEmpty());
    }

    @Test void twoSeparateEditsAreTwoRegionsLargestFirst() {
        VoxelModel a = scene(), b = scene();
        for (int y = 1; y <= 4; y++) b.set(5, y, 5, BlockSpec.of("stone_bricks"));          // a small post
        for (int x = 60; x <= 70; x++) for (int y = 1; y <= 3; y++) b.set(x, y, 70, BlockSpec.of("oak_planks"));  // a wall
        List<int[]> r = BuildPipeline.changedRegions(a, b, 3);
        assertEquals(2, r.size());
        assertArrayEquals(new int[]{58, -1, 68, 72, 5, 72}, r.get(0), "the bigger edit first, padded by 2");
        assertArrayEquals(new int[]{3, -1, 3, 7, 6, 7}, r.get(1));
    }

    @Test void aChangeAcrossABucketEdgeStaysOneRegion() {
        VoxelModel a = scene(), b = scene();
        for (int x = 8; x <= 16; x++) b.set(x, 1, 3, BlockSpec.of("stone_brick_slab"));
        assertEquals(1, BuildPipeline.changedRegions(a, b, 3).size());
    }

    @Test void removedBlocksCountToo() {
        VoxelModel a = scene(), b = scene();
        b.set(40, 0, 40, BlockSpec.AIR);
        assertEquals(1, BuildPipeline.changedRegions(a, b, 3).size());
    }
}
