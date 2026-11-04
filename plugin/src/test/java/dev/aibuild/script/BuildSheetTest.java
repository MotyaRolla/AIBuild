package dev.aibuild.script;

import dev.aibuild.model.StubBlockInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The build sheet: the intent and key points every stage after the first design reads, kept by the runtime.
 *
 * <p>Why: the first design's reasoning (53k tokens in a live sakura build) is thrown away, so sculptors and the
 * critic re-guessed where the stairs, doors and walls were and broke them. Anchors are measured, not remembered.</p>
 */
class BuildSheetTest {
    private final ScriptRuntime runtime = new ScriptRuntime(new StubBlockInfo(), Stdlib.V2);

    private ScriptResult run(String src) {
        ScriptResult r = runtime.run(src, 3, null, null, ScriptRuntime.Budget.standard(), null);
        assertTrue(r.ok(), () -> r.error().describe());
        return r;
    }

    @Test void volumeRegistersItsBodyAndDoor() {
        ScriptResult r = run("part('house', () => { note('main house, entrance south'); volume(0, 0, 9, 7, 1, {floors: 2, door: {face: 'south'}}); });");
        Canvas.Anchor body = r.anchors().stream().filter(a -> a.key().equals("house.body")).findFirst().orElseThrow();
        assertArrayEquals(new int[]{0, 1, 0}, java.util.Arrays.copyOf(body.box(), 3));
        assertTrue(body.note().startsWith("floors y="), body.note());
        assertTrue(r.anchors().stream().anyMatch(a -> a.key().equals("house.door") && a.point()), () -> r.sheet(60));
        String sheet = r.sheet(60);
        assertTrue(sheet.contains("note: main house, entrance south"), sheet);
        assertTrue(sheet.contains("house.body box x 0..9"), sheet);
    }

    @Test void anotherPartReadsAMarkBack() {
        ScriptResult r = run("""
                part('gate', () => { box(0, 1, 0, 4, 5, 0, 'stone_bricks'); mark('gate arch', [1, 1, 0, 3, 4, 0], 'keep clear'); });
                part('banner', () => {
                    const g = anchor('gate arch');
                    if (!g) throw new Error('no anchor');
                    block(g.x1 + 1, g.y2 + 1, g.z1 - 1, 'lantern');
                    log(g.note);
                });
                """);
        assertEquals("lantern", r.model().get(2, 5, -1).name());
        assertEquals("keep clear", r.logs().get(0));
    }

    @Test void marksFollowTheFrame() {
        ScriptResult r = run("withT({at: [10, 0, 20]}, () => { part('p', () => { block(0, 1, 0, 'stone'); mark('spot', 0, 1, 0); }); });");
        Canvas.Anchor a = r.anchors().getFirst();
        assertArrayEquals(new int[]{10, 1, 20, 10, 1, 20}, a.box());
    }

    @Test void kitAnchorsAreNumberedAndFoundByTheirShortName() {
        ScriptResult r = run("part('town', () => { door(0, 1, 0, 'south'); door(5, 1, 0, 'south'); log(anchor('door2').x); });");
        assertEquals("5", r.logs().getFirst());
    }

    @Test void towersAndCurtainWallsMarkThemselves() {
        ScriptResult r = run("""
                part('keep', () => { tower(0, 1, 0, 3, 12); });
                part('walls', () => { curtainWall([[10, 0], [30, 0], [30, 20]], 1, 6); });
                """);
        Canvas.Anchor t = r.anchors().stream().filter(a -> a.key().equals("keep/tower.tower")).findFirst().orElseThrow(() -> new AssertionError(r.sheet(60)));
        assertArrayEquals(new int[]{-3, 1, -3, 3, 13, 3}, t.box());
        Canvas.Anchor w = r.anchors().stream().filter(a -> a.key().equals("walls/wall.wall")).findFirst().orElseThrow(() -> new AssertionError(r.sheet(60)));
        assertEquals(7, w.box()[4], "the wall top is y + h");
        assertTrue(w.note().contains("walkway y=7"), w.note());
    }

    @Test void noSheetWithoutMarksOrNotes() {
        assertEquals("", run("part('a', () => { block(0, 1, 0, 'stone'); });").sheetSection());
    }
}
