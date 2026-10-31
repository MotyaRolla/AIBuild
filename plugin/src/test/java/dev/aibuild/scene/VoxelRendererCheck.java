package dev.aibuild.scene;

import dev.aibuild.model.VoxelModel;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Self-check for {@link VoxelRenderer}: the picture is a real PNG of the requested size, it is richly textured,
 * views differ, an empty scene renders, the ray enters the grid instead of walking empty space, and a draft frame
 * is fast.
 *
 * <p>The test builds its voxel maps by hand instead of calling {@link SceneGeometry}, so a change in the geometry
 * builder cannot silently weaken the renderer checks. It is a normal JUnit 5 test and also runs standalone
 * ({@code java dev.aibuild.scene.VoxelRendererCheck}) in the plugin's traditional plain-main style, which is handy
 * when Maven cannot resolve the surefire provider offline.</p>
 */
class VoxelRendererCheck {

    private static final int W = 512;
    private static final int H = 384;

    /** A 10x10x10 house: floor, walls with a door opening and glass windows, gable roof, stone chimney. */
    private static Map<Long, SceneGeometry.CellInfo> house() {
        Map<Long, SceneGeometry.CellInfo> m = new HashMap<>();
        for (int x = 0; x < 10; x++) {
            for (int z = 0; z < 10; z++) put(m, x, 0, z, "wood");
        }
        for (int y = 1; y <= 5; y++) {
            for (int x = 0; x < 10; x++) {
                for (int z = 0; z < 10; z++) {
                    boolean perimeter = x == 0 || x == 9 || z == 0 || z == 9;
                    if (!perimeter) continue;
                    boolean corner = (x == 0 || x == 9) && (z == 0 || z == 9);
                    put(m, x, y, z, corner ? "frame" : "wall");
                }
            }
        }
        // The door: cut a real hole in the south facade (+z).
        for (int y = 1; y <= 2; y++) {
            for (int x = 4; x <= 5; x++) m.remove(VoxelModel.key(x, y, 9));
        }
        // Two glazed windows next to it.
        for (int y = 2; y <= 3; y++) {
            put(m, 1, y, 9, "glass");
            put(m, 8, y, 9, "glass");
            put(m, 0, y, 4, "glass");
        }
        // Gable roof along x, one layer per step, plus a ridge cap.
        for (int k = 0; k <= 4; k++) {
            for (int x = k; x <= 9 - k; x++) {
                for (int z = 0; z < 10; z++) put(m, x, 6 + k, z, "roof");
            }
        }
        // Stone chimney, deliberately above the ridge so the silhouette is not a plain wedge.
        for (int y = 6; y <= 11; y++) {
            put(m, 7, y, 2, "stone");
            put(m, 8, y, 2, "stone");
        }
        return m;
    }

    private static void put(Map<Long, SceneGeometry.CellInfo> m, int x, int y, int z, String material) {
        m.put(VoxelModel.key(x, y, z), new SceneGeometry.CellInfo(material, true, false, 0, 1, 0, 0x04));
    }

    private static SceneSpec emptySpec() {
        return SceneSpec.of("empty", List.of());
    }

    // ------------------------------------------------------------------ tests

    @Test
    void pngIsValidAndHasRequestedSize() throws Exception {
        BufferedImage img = VoxelRenderer.render(emptySpec(), house(), VoxelRenderer.VIEWS.get("se"), 1f, 400, 300);
        byte[] png = VoxelRenderer.toPng(img);
        assertTrue(png.length > 100, "PNG is suspiciously small: " + png.length);
        byte[] sig = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        for (int i = 0; i < sig.length; i++) assertEquals(sig[i], png[i], "PNG signature byte " + i);

        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(decoded, "ImageIO could not read the PNG back");
        assertEquals(400, decoded.getWidth());
        assertEquals(300, decoded.getHeight());
    }

    @Test
    void texturedHouseHasManyDistinctColours() {
        BufferedImage img = VoxelRenderer.render(emptySpec(), house(), VoxelRenderer.VIEWS.get("se"), 2f, W, H);
        Set<Integer> colours = new HashSet<>();
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) colours.add(img.getRGB(x, y));
        }
        assertTrue(colours.size() > 150,
                "only " + colours.size() + " distinct colours: the render is not textured");
    }

    @Test
    void viewsDiffer() {
        BufferedImage se = VoxelRenderer.render(emptySpec(), house(), VoxelRenderer.VIEWS.get("se"), 1f, 320, 240);
        BufferedImage front = VoxelRenderer.render(emptySpec(), house(), VoxelRenderer.VIEWS.get("front"), 1f, 320, 240);
        BufferedImage top = VoxelRenderer.render(emptySpec(), house(), VoxelRenderer.VIEWS.get("top"), 1f, 320, 240);

        assertNotEquals(digest(se), digest(front), "south-east and front views are identical");
        assertNotEquals(digest(se), digest(top), "south-east and top views are identical");
        assertNotEquals(digest(front), digest(top), "front and top views are identical");
    }

    @Test
    void emptySpecRendersFlatBackground() {
        BufferedImage img = assertDoesNotThrow(
                () -> VoxelRenderer.render(emptySpec(), Map.of(), VoxelRenderer.VIEWS.get("se"), 2f, 256, 192));
        assertEquals(256, img.getWidth());
        assertEquals(192, img.getHeight());
        // The background is a very light sky gradient: every pixel must be light and none may be the
        // near-black of a shaded block.
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                assertTrue(r > 200 && g > 200 && b > 200,
                        "unexpected dark pixel at " + x + "," + y + ": " + Integer.toHexString(rgb));
            }
        }
        // A null voxel map must behave exactly like an empty one.
        assertDoesNotThrow(() -> VoxelRenderer.render(emptySpec(), null,
                VoxelRenderer.VIEWS.get("se"), 1f, 64, 48));
    }

    @Test
    void singleBlockIsVisibleThroughTheGridEntryTest() {
        Map<Long, SceneGeometry.CellInfo> one = new HashMap<>();
        put(one, 0, 0, 0, "stone");
        for (float scale : new float[]{1f, 2f}) {
            BufferedImage img = VoxelRenderer.render(emptySpec(), one, VoxelRenderer.VIEWS.get("se"), scale, 200, 150);
            int centre = img.getRGB(100, 75);
            int corner = img.getRGB(1, 1);
            assertNotEquals(corner, centre, "the lone block did not cover the centre of the frame (scale " + scale + ")");
            assertTrue(isBlockColoured(centre), "centre pixel is background, not the block: "
                    + Integer.toHexString(centre));
        }
        // A block far from the origin must be framed just as well: the camera is built from the voxel bounds.
        Map<Long, SceneGeometry.CellInfo> far = new HashMap<>();
        put(far, 300, 40, -200, "stone");
        BufferedImage img = VoxelRenderer.render(emptySpec(), far, VoxelRenderer.VIEWS.get("se"), 1f, 200, 150);
        assertTrue(isBlockColoured(img.getRGB(100, 75)), "a distant block was not framed by the camera");
    }

    @Test
    void draftFrameIsFast() {
        Map<Long, SceneGeometry.CellInfo> model = house();
        // Warm up: the JIT must not be counted as render time.
        VoxelRenderer.render(emptySpec(), model, VoxelRenderer.VIEWS.get("se"), 1f, 400, 300);

        long t0 = System.nanoTime();
        BufferedImage draft = VoxelRenderer.render(emptySpec(), model, VoxelRenderer.VIEWS.get("se"), 1f, 400, 300);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        assertNotNull(draft);
        assertTrue(ms < 3000, "400x300 draft took " + ms + " ms (budget 3000 ms)");
        System.out.println("draft 400x300 x1: " + ms + " ms");
    }

    @Test
    void draftSuperSampledFrameIsStillFast() {
        Map<Long, SceneGeometry.CellInfo> model = house();
        VoxelRenderer.render(emptySpec(), model, VoxelRenderer.VIEWS.get("se"), 2f, W, H);
        long t0 = System.nanoTime();
        VoxelRenderer.render(emptySpec(), model, VoxelRenderer.VIEWS.get("se"), 2f, W, H);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        System.out.println("full 512x384 x2: " + ms + " ms");
        // Target is ~1.5 s on one thread; the assertion stays generous for slow CI machines.
        assertTrue(ms < 6000, "512x384 with 2x supersampling took " + ms + " ms (budget 6000 ms)");
    }

    @Test
    void renderAllReturnsOneImagePerViewInOrder() {
        List<String> names = List.of("se", "sw", "ne", "front");
        List<BufferedImage> images = VoxelRenderer.renderAll(emptySpec(), house(), names, 256, 192);
        assertEquals(names.size(), images.size());
        for (BufferedImage img : images) {
            assertEquals(256, img.getWidth());
            assertEquals(192, img.getHeight());
        }
        assertNotEquals(digest(images.get(0)), digest(images.get(1)), "renderAll produced identical se and sw");
        assertEquals(8, VoxelRenderer.VIEWS.size(), "all eight named views must be available");
        for (String name : List.of("se", "sw", "ne", "nw", "front", "back", "top", "side")) {
            assertTrue(VoxelRenderer.VIEWS.containsKey(name), "missing view: " + name);
        }
        assertTrue(assertThrowsIllegalArgument(() -> VoxelRenderer.renderAll(emptySpec(), house(), List.of("nope"), 32, 32)),
                "an unknown view name must be rejected");
    }

    @Test
    void glassShowsWhatIsBehindIt() {
        Map<Long, SceneGeometry.CellInfo> m = new HashMap<>();
        // A solid stone wall, with a single glass pane in front of it.
        for (int x = -2; x <= 2; x++) {
            for (int y = -2; y <= 2; y++) {
                put(m, x, y, 1, "stone");
            }
        }
        put(m, 0, 0, 0, "glass");
        BufferedImage withGlass = VoxelRenderer.render(emptySpec(), m, VoxelRenderer.VIEWS.get("front"), 1f, 200, 150);
        m.remove(VoxelModel.key(0, 0, 0));
        BufferedImage withoutGlass = VoxelRenderer.render(emptySpec(), m, VoxelRenderer.VIEWS.get("front"), 1f, 200, 150);
        // Glass is a pane in front of stone: the pixel must change (transparency + its own frame), but the
        // stone behind must still be visible rather than a hole to the sky.
        assertNotEquals(withoutGlass.getRGB(100, 75), withGlass.getRGB(100, 75),
                "the glass pane was not rendered");
        assertTrue(isBlockColoured(withGlass.getRGB(100, 75)), "the glass turned the pixel into sky");
    }

    // ------------------------------------------------------------------ integration

    /**
     * The production path end to end: a real spec goes through {@link SceneGeometry#buildScene}, and the
     * resulting cells are rendered. This is the contract the critic depends on — the renderer must accept
     * the geometry builder's map without translation and produce a picture.
     */
    @Test
    void rendersRealGeometryFromTheSceneBuilder() {
        SceneSpec spec = SceneSpec.parse(com.google.gson.JsonParser.parseString("""
                {"name":"probe-house","seed":7,
                 "palette":{"wall":"#d9cfba","frame":"#3a2a1c","roof":"#6b452f","stone":"#8a8a8a","glass":"#a9c9d9"},
                 "objects":[
                   {"id":"foundation","type":"box","from":[0,-1,0],"to":[9,0,9],"material":"stone"},
                   {"id":"walls","type":"box","from":[0,1,0],"to":[9,5,9],"material":"wall","hollow":true,
                    "openings":[{"wall":"south","at":[4,1],"w":2,"h":3,"kind":"door"}]},
                   {"id":"roof","type":"prism","from":[0,6,0],"to":[9,6,9],"style":"gable","ridge":"x","material":"roof"},
                   {"id":"window","type":"window","wall":"south","at":[1,2],"w":1,"h":2,"frame":"frame","glass":"glass"}
                 ]}
                """).getAsJsonObject());
        assertEquals(List.of(), spec.errors(), "the probe spec must parse cleanly");

        SceneGeometry.Scene scene = SceneGeometry.buildScene(spec);
        assertFalse(scene.info().isEmpty(), "the geometry builder produced no cells");

        BufferedImage img = VoxelRenderer.render(spec, scene.info(), VoxelRenderer.VIEWS.get("se"), 1f, 320, 240);
        assertEquals(320, img.getWidth());
        assertEquals(240, img.getHeight());

        Set<Integer> colours = new HashSet<>();
        int blockish = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int rgb = img.getRGB(x, y);
                colours.add(rgb);
                if (isBlockColoured(rgb)) blockish++;
            }
        }
        assertTrue(colours.size() > 40, "real geometry rendered only " + colours.size() + " colours");
        // The building must occupy a meaningful part of the frame, not be a speck in the middle.
        double share = (double) blockish / (img.getWidth() * img.getHeight());
        assertTrue(share > 0.10, "the build covers only " + Math.round(share * 100) + "% of the frame");
        System.out.println("real build: " + scene.info().size() + " cells, " + colours.size()
                + " colours, " + Math.round(share * 100) + "% of frame covered");

        // The PNG must survive the trip to the chat message.
        byte[] png = VoxelRenderer.toPng(img);
        assertTrue(png.length > 1000, "PNG of a real build is only " + png.length + " bytes");
    }

    // ------------------------------------------------------------------ helpers

    private static boolean isBlockColoured(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        // Sky is a very light blue (>= 238 on every channel); any shaded block is clearly darker.
        return r < 225 || g < 225 || b < 225;
    }

    private static long digest(BufferedImage img) {
        long h = 1125899906842597L;
        for (int y = 0; y < img.getHeight(); y += 2) {
            for (int x = 0; x < img.getWidth(); x += 2) {
                h = 31 * h + img.getRGB(x, y);
            }
        }
        return h;
    }

    private static boolean assertThrowsIllegalArgument(Runnable r) {
        try {
            r.run();
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    // ------------------------------------------------------------------ standalone runner

    /**
     * Runs every check without JUnit, in the plugin's traditional style.
     *
     * @param args ignored
     */
    public static void main(String[] args) {
        VoxelRendererCheck t = new VoxelRendererCheck();
        List<String> failures = new ArrayList<>();
        int checks = 0;
        checks = run("pngIsValidAndHasRequestedSize", () -> t.pngIsValidAndHasRequestedSize(), failures, checks);
        checks = run("texturedHouseHasManyDistinctColours", t::texturedHouseHasManyDistinctColours, failures, checks);
        checks = run("viewsDiffer", t::viewsDiffer, failures, checks);
        checks = run("emptySpecRendersFlatBackground", t::emptySpecRendersFlatBackground, failures, checks);
        checks = run("singleBlockIsVisibleThroughTheGridEntryTest", t::singleBlockIsVisibleThroughTheGridEntryTest, failures, checks);
        checks = run("draftFrameIsFast", t::draftFrameIsFast, failures, checks);
        checks = run("draftSuperSampledFrameIsStillFast", t::draftSuperSampledFrameIsStillFast, failures, checks);
        checks = run("renderAllReturnsOneImagePerViewInOrder", t::renderAllReturnsOneImagePerViewInOrder, failures, checks);
        checks = run("glassShowsWhatIsBehindIt", t::glassShowsWhatIsBehindIt, failures, checks);
        checks = run("rendersRealGeometryFromTheSceneBuilder", t::rendersRealGeometryFromTheSceneBuilder, failures, checks);
        System.out.println();
        System.out.println("-----------------------------------------------");
        System.out.println(checks + " checks, " + failures.size() + " failures");
        for (String f : failures) System.out.println("  FAILED " + f);
        if (!failures.isEmpty()) System.exit(1);
        System.out.println("ALL CHECKS PASSED");
    }

    private static int run(String name, ThrowingRunnable body, List<String> failures, int checks) {
        try {
            body.run();
            System.out.println("PASS " + name);
        } catch (Throwable e) {
            failures.add(name + ": " + e);
            System.out.println("FAIL " + name + " — " + e);
        }
        return checks + 1;
    }

    /** A test body that may throw. */
    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
