package dev.aibuild.analysis;

import dev.aibuild.model.BlockSpec;
import dev.aibuild.model.StubBlockInfo;
import dev.aibuild.model.VoxelModel;
import dev.aibuild.render.RedPen;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

/** The red pen marks what a professional sees at a glance, and stays quiet on work that is fine. */
class CraftReviewTest {
    private final StubBlockInfo info = new StubBlockInfo();

    private static void box(VoxelModel m, int x1, int y1, int z1, int x2, int y2, int z2, String b) {
        for (int x = x1; x <= x2; x++) for (int y = y1; y <= y2; y++) for (int z = z1; z <= z2; z++) m.set(x, y, z, BlockSpec.of(b));
    }

    /** A 12 x 6 x 8 hollow house of planks with a slab roof, overhanging by one or flush. */
    private static VoxelModel house(boolean overhang) {
        VoxelModel m = new VoxelModel();
        box(m, 0, 1, 0, 11, 6, 7, "oak_planks");
        box(m, 1, 1, 1, 10, 6, 6, "air");
        int o = overhang ? 1 : 0;
        box(m, -o, 7, -o, 11 + o, 7, 7 + o, "spruce_slab");
        return m;
    }

    private CraftReview.Result review(VoxelModel m) {
        return CraftReview.review(m, info, null);
    }

    @Test void aPieceHangingInTheAirIsMarkedWithWhereItIs() {
        VoxelModel m = house(true);
        box(m, 2, 10, 2, 6, 10, 2, "hay_block");
        CraftReview.Result r = review(m);
        CraftReview.Mark f = r.marks().stream().filter(x -> x.kind().equals("floating")).findFirst().orElseThrow();
        assertArrayEquals(new int[]{2, 10, 2, 6, 10, 2}, f.box());
        assertTrue(f.note().contains("hay_block"), f.note());
    }

    @Test void aFlatWallIsMarkedAndAFramedOneIsNot() {
        CraftReview.Result flat = review(house(true));
        assertTrue(flat.count("flat-wall") >= 2, flat.toText());
        VoxelModel framed = house(true);
        // log pillars every 3 blocks stand proud of the south and north faces
        for (int x = 0; x <= 11; x += 3) {
            box(framed, x, 1, -1, x, 6, -1, "oak_log");
            box(framed, x, 1, 8, x, 6, 8, "oak_log");
        }
        CraftReview.Result r = review(framed);
        assertTrue(r.marks().stream().noneMatch(x -> x.kind().equals("flat-wall") && x.normal()[2] != 0), r.toText());
    }

    @Test void buttonsAndTrapdoorsStuckOnAWallDoNotClearTheMark() {
        VoxelModel m = house(true);
        for (int x = 0; x <= 11; x += 2) for (int y = 1; y <= 6; y += 2) m.set(x, y, -1, BlockSpec.parseRaw("oak_button[face=wall,facing=north]"));
        for (int x = 1; x <= 11; x += 2) m.set(x, 3, 8, BlockSpec.parseRaw("spruce_trapdoor[facing=south,open=true]"));
        CraftReview.Result r = review(m);
        assertTrue(r.marks().stream().anyMatch(x -> x.kind().equals("flat-wall") && x.normal()[2] == -1), "north face: " + r.toText());
        assertTrue(r.marks().stream().anyMatch(x -> x.kind().equals("flat-wall") && x.normal()[2] == 1), r.toText());
    }

    @Test void aRoofFlushWithTheWallHasNoEave() {
        assertTrue(review(house(false)).count("no-eave") >= 1, review(house(false)).toText());
        assertEquals(0, review(house(true)).count("no-eave"), review(house(true)).toText());
    }

    @Test void randomDotsOfContrastingGroundAreSpeckleAndPatchesAreNot() {
        VoxelModel noisy = house(true), patchy = house(true);
        SplittableRandom rnd = new SplittableRandom(1);
        for (int x = -12; x < 24; x++) {
            for (int z = 12; z < 36; z++) {
                noisy.set(x, 0, z, BlockSpec.of(rnd.nextInt(2) == 0 ? "podzol" : "grass_block"));
                // the same share of podzol, in two big blobs
                boolean blob = Math.hypot(x - 0, z - 20) < 7 || Math.hypot(x - 14, z - 28) < 6;
                patchy.set(x, 0, z, BlockSpec.of(blob ? "podzol" : "grass_block"));
            }
        }
        assertTrue(review(noisy).count("speckle") >= 1, review(noisy).toText());
        assertEquals(0, review(patchy).count("speckle"), review(patchy).toText());
    }

    @Test void interiorWallsAreNotFacades() {
        VoxelModel m = house(true);
        box(m, 0, 1, 0, 11, 6, 0, "oak_planks");       // closed box: the inside faces see no outside air
        CraftReview.Result r = review(m);
        assertTrue(r.marks().stream().filter(x -> x.kind().equals("flat-wall"))
                .allMatch(x -> x.box()[2] <= 0 || x.box()[5] >= 7 || x.box()[0] <= 0 || x.box()[3] >= 11), r.toText());
    }

    @Test void theMarksAreDrawnOnTheViews() {
        VoxelModel m = house(false);
        box(m, 2, 10, 2, 6, 10, 2, "hay_block");
        CraftReview.Result r = review(m);
        byte[] png = RedPen.sheet(m, info, r.marks(), 480);
        assertTrue(png.length > 1000);
        assertFalse(r.toText().isBlank());
    }
}
