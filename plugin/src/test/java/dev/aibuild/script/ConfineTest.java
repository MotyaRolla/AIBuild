package dev.aibuild.script;

import dev.aibuild.model.BlockSpec;
import dev.aibuild.model.StubBlockInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * confine(): a rewritten part's stray writes are skipped instead of the whole rewrite being thrown away.
 *
 * <p>Live defect: 11 of 14 failed interior passes were rejected for changing 1-2 blocks outside the rooms, and 7
 * sculptor passes for touching a neighbour's wall, so a whole furnishing or a whole refined facade was lost for a
 * few cells. The runtime now drops exactly those cells and reports them.</p>
 */
class ConfineTest {
    private final ScriptRuntime runtime = new ScriptRuntime(new StubBlockInfo(), Stdlib.V2);

    private ScriptResult run(String src) {
        ScriptResult r = runtime.run(src, 7, null, null, ScriptRuntime.Budget.standard(), null);
        assertTrue(r.ok(), () -> r.error().describe());
        return r;
    }

    private static String name(BlockSpec s) {
        return s == null || s.isAir() ? "air" : s.name();
    }

    private static final String HOUSE = """
            part('house', () => { box(0, 1, 0, 9, 4, 0, 'stone_bricks'); });
            """;

    @Test void aConfinedPartCannotBreakItsNeighboursWall() {
        String deco = """
                part('deco', () => {
                    block(3, 2, 0, 'air');             // a hole in the house's wall
                    block(4, 2, 0, 'lantern');         // a lantern written into it
                    box(0, 1, -2, 9, 1, -1, 'oak_planks');  // its own porch
                });
                """;
        ScriptResult free = run(HOUSE + deco);
        assertEquals("air", name(free.model().get(3, 2, 0)), "without the rule the wall is broken");
        ScriptResult r = run(ScriptPatch.confine(HOUSE + deco, "deco", Canvas.OTHERS));
        assertEquals("stone_bricks", name(r.model().get(3, 2, 0)));
        assertEquals("stone_bricks", name(r.model().get(4, 2, 0)));
        assertEquals("oak_planks", name(r.model().get(5, 1, -2)), "the rest of the part is kept");
        Canvas.Confinement cf = r.confinement("deco");
        assertNotNull(cf);
        assertEquals(2, cf.dropped);
        assertEquals(22, cf.writes);
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("left its scope")), () -> r.warnings().toString());
    }

    @Test void oneWallBlockMayStillReplaceAnother() {
        // re-facing a neighbour's wall with other masonry is a join, not breakage
        ScriptResult r = run(ScriptPatch.confine(HOUSE + "part('deco', () => { block(3, 2, 0, 'mossy_stone_bricks'); });",
                "deco", Canvas.OTHERS));
        assertEquals("mossy_stone_bricks", name(r.model().get(3, 2, 0)));
        assertEquals(0, r.confinement("deco").dropped);
    }

    @Test void aNestedPartMayChangeItsEnclosingPartsBlocks() {
        String src = """
                part('castle', () => {
                    box(0, 1, 0, 9, 4, 0, 'stone_bricks');
                    part('keep', () => { block(3, 2, 0, 'air'); });
                });
                """;
        ScriptResult r = run(ScriptPatch.confine(src, "keep", Canvas.OTHERS));
        assertEquals("air", name(r.model().get(3, 2, 0)), "the keep belongs to the castle; carving it is its own business");
        assertEquals(0, r.confinement("keep").dropped);
    }

    /** A 12 x 10 room with walls y 2..5, a ceiling at 6 and a door in the south wall. */
    private static final String ROOM = """
            part('shell', () => {
                box(0, 1, 0, 11, 1, 9, 'oak_planks');
                box(0, 2, 0, 11, 5, 9, 'white_terracotta', {walls: true});
                box(0, 6, 0, 11, 6, 9, 'spruce_planks');
                door(5, 2, 9, 'south');
            });
            """;

    @Test void anInteriorConfinedToRoomsKeepsItsFurnitureAndDropsTheStrayBlock() {
        String furnish = """
                part('interiors', () => {
                    block(3, 2, 3, 'crafting_table');
                    block(4, 2, 3, 'barrel');
                    block(15, 2, 5, 'lantern');        // in the garden: outside every room
                });
                """;
        ScriptResult r = run(ScriptPatch.confine(ROOM + furnish, "interiors", Canvas.ROOMS));
        assertEquals("crafting_table", name(r.model().get(3, 2, 3)));
        assertEquals("barrel", name(r.model().get(4, 2, 3)));
        assertEquals("air", name(r.model().get(15, 2, 5)), "the block outside is skipped");
        Canvas.Confinement cf = r.confinement("interiors");
        assertEquals(1, cf.dropped);
        assertEquals(3, cf.writes);
    }

    @Test void theRuleIsWrittenOnceAndNeverOverAScriptsOwnConfine() {
        String once = ScriptPatch.confine(HOUSE, "house", Canvas.OTHERS);
        assertTrue(once.startsWith("confine('house', 'others');\n"));
        assertEquals(once, ScriptPatch.confine(once, "house", Canvas.OTHERS));
        String own = "function confine(a) { return a; }\n" + HOUSE;
        assertEquals(own, ScriptPatch.confine(own, "house", Canvas.OTHERS));
    }

    @Test void aConfinedScriptReproducesItsModel() {
        String src = ScriptPatch.confine(HOUSE + "part('deco', () => { block(3, 2, 0, 'air'); block(3, 1, -1, 'oak_planks'); });",
                "deco", Canvas.OTHERS);
        assertEquals(run(src).model().nonAirCount(), run(src).model().nonAirCount());
        assertEquals(run(src).owners(), run(src).owners());
    }
}
