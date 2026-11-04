package dev.aibuild.live;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards on the live page's renderer and controls.
 *
 * <p>The page is plain JavaScript served from a resource, so these read the shipped file rather than a copy.
 * They cover the defects that were found by looking at a real build: the horizontal drag was inverted, one of
 * the two vertical block faces was picked from the wrong sign, and {@code fit()} sized the picture from the
 * model's spans with hand-tuned factors instead of its projected outline. Each of those is a one-line change
 * that looks harmless in review and is only obvious on screen, which is exactly what a test is for.</p>
 */
class LivePageRendererTest {

    private static String page() throws IOException {
        try (InputStream in = LivePageRendererTest.class.getResourceAsStream("/live/index.html")) {
            assertNotNull(in, "the page must ship in the jar");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The body of one top-level {@code function name(...)} in the page source. */
    private static String function(String source, String name) {
        int start = source.indexOf("function " + name + "(");
        assertTrue(start >= 0, "function " + name + " must exist");
        int depth = 0;
        int i = source.indexOf('{', start);
        int from = i;
        for (; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (ch == '{') depth++;
            else if (ch == '}' && --depth == 0) return source.substring(from, i + 1);
        }
        throw new AssertionError("unbalanced braces in " + name);
    }

    @Test
    void horizontalDragFollowsTheCursor() throws IOException {
        Matcher m = Pattern.compile("cam\\.yaw = drag\\.yaw ([+-]) dx \\* ([0-9.]+)").matcher(page());
        assertTrue(m.find(), "the drag formula must be findable");
        // Dragging right must turn the model right. With this projection that means yaw decreases, so the
        // formula has to subtract; a plus here is the inversion players reported.
        assertTrue(m.group(1).equals("-"),
                "horizontal drag must subtract from yaw, otherwise the model turns the wrong way");
    }

    @Test
    void bothVerticalFacesPickTheNearerOneForTheCamera() throws IOException {
        String box = function(page(), "box");
        // The vertical-face choice along z reads off cos(yaw); along x it reads off the negated sin. Getting
        // that second sign wrong draws the face on the far side of the block, which is what made blocks look
        // wrong rather than merely a different colour.
        assertTrue(box.contains("const nx = -Math.sin(cam.yaw)"),
                "the x face sign must be negated: the near face along x is the one at low x when sin(yaw) > 0");
        assertTrue(box.contains("nz = Math.cos(cam.yaw)"), "the z face sign is cosine");
        assertFalse(box.contains("const nx = Math.sin(cam.yaw)"),
                "an un-negated sin is the bug: it draws the far x face");
    }

    @Test
    void fitUsesTheProjectedOutlineNotASpanGuess() throws IOException {
        String fit = function(page(), "fit");
        assertTrue(fit.contains("project(x, y, z)"),
                "fit must project the bounding-box corners; estimating from spans under-filled the canvas");
        assertFalse(fit.contains("span * 2.1"),
                "the old hand-tuned factors left a real build at 46% width and 94px off centre");
        assertTrue(fit.contains("cam.panX = 0") && fit.contains("cam.panY = 0"),
                "fit must clear the pan, or a panned view will zoom to the wrong place");
    }

    @Test
    void theDrawLoopDoesNotCallTrigInsideTheComparator() throws IOException {
        String draw = function(page(), "draw");
        assertFalse(draw.contains("order.sort((a, b) => depth("),
                "depth() in the comparator runs four Math.cos per comparison: precompute the keys instead");
        assertTrue(draw.contains("keyOf"), "the sort must use precomputed depth keys");
        assertTrue(draw.matches("(?s).*Math\\.cos\\(cam\\.yaw\\).*"),
                "the trig is hoisted out of the loop, once per frame");
    }

    @Test
    void facesAreDrawnFromACachedSprite() throws IOException {
        String page = page();
        assertTrue(page.contains("function faceSprite("), "the sprite cache must exist");
        String box = function(page, "box");
        assertTrue(box.contains("blit("), "box() must draw from the sprite cache on the fast path");
        // The fallback still has to exist: without an atlas, shading is all that carries the shape.
        assertTrue(box.contains("filled("), "the flat fallback must remain for a session with no textures");
    }

    @Test
    void spritesAreDiscardedWhenTheCameraMoves() throws IOException {
        String draw = function(page(), "draw");
        assertTrue(draw.contains("resetSprites()"),
                "sprites encode the camera; a moved camera must invalidate them or the picture skews");
        assertTrue(draw.contains("cam.pitch"), "pitch matters as much as yaw and scale");
    }

    @Test
    void everyFaceIsShadedOrTheBlockLooksFlat() throws IOException {
        String sprite = function(page(), "faceSprite");
        // The faces all sample the same atlas tile. Without a tint the top and both sides come out at the
        // texture's own brightness, which is what made solid blocks read as a flat cut-out.
        assertTrue(sprite.contains("source-atop"),
                "each face must be tinted by its brightness, confined to the sprite's own pixels");
        assertTrue(sprite.contains("1 - brightness"),
                "the tint must be derived from the face brightness");
    }

    @Test
    void aSpriteIsKeyedByTheBoxShapeItWasRenderedFrom() throws IOException {
        String key = function(page(), "spriteKey");
        // A slab is half a cube's height and a post a tenth of its footprint. Sharing a sprite across shapes
        // draws the taller shape with the shorter one's proportions.
        assertTrue(key.contains("geom"), "the box geometry must be part of the sprite key");
        String box = function(page(), "box");
        assertTrue(box.contains("const geom = sx + \"|\" + sz + \"|\" + h"),
                "box() must pass its own geometry to the cache");
    }

    @Test
    void spritesAreRenderedFromTheOriginNotTheAskingCell() throws IOException {
        String box = function(page(), "box");
        // The crop rectangle is in screen space. Anchoring it to whichever cell asked first bakes that cell's
        // position into the image, and every other cell is displaced by a different amount per face type.
        assertTrue(box.contains("boxRef(sx, sz, h)"),
                "sprite geometry must come from a box at the origin");
        assertTrue(box.contains("ref.b0") && box.contains("ref.t0"),
                "faces must be built from the reference corners");
    }

    @Test
    void hiddenFacesAreStillDrawnBecauseSkippingThemLeavesHoles() throws IOException {
        String box = function(page(), "box");
        // A cell draws its top and two camera-facing sides, never its bottom, so the neighbour that would
        // justify skipping a face does not itself draw that plane. Skipping on a 7 755-cell build left holes
        // up to 295 px wide; the overdraw is deliberate.
        assertFalse(box.contains("occl"),
                "per-face occlusion skipping is unsound for a three-face renderer");
        assertTrue(box.contains("holes up to 295 px"),
                "the reason the faces are all drawn must stay recorded next to the code");
    }

    @Test
    void theAtlasIsStillTheThingBeingDrawn() throws IOException {
        String sprite = function(page(), "faceSprite");
        assertTrue(sprite.contains("atlas.bitmap"),
                "faces must come from the real block atlas, not palette colours");
        assertTrue(sprite.contains("tileRect("), "each face samples its own tile");
    }

    @Test
    void everyShapeStillDrawsSomething() throws IOException {
        String draw = function(page(), "draw");
        for (String shape : List.of("PLATE", "CROSS", "POST", "PANE", "SLAB_BOTTOM", "SLAB_TOP", "STAIRS")) {
            assertTrue(draw.contains("SHAPE." + shape), "shape " + shape + " must still be handled");
        }
        assertTrue(draw.contains("box(g,"), "the common case must still draw a box");
    }
}
