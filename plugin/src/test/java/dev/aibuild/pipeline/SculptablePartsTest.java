package dev.aibuild.pipeline;

import dev.aibuild.script.ScriptResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SculptablePartsTest {
    @Test void offersEnclosingPartsAndSkipsAmbiguousNames() {
        String source = """
                part('site', () => { box(-30, 0, -24, 32, 2, 30, 'grass_block'); });
                part('house', () => {
                  part('shell', () => { box(-7, 0, -5, 6, 9, 6, 'stone'); });
                  part('roof', () => { box(-8, 9, -6, 7, 16, 7, 'spruce_planks'); });
                });
                part('garden', () => { part('tree', () => {}); });
                part('surroundings', () => { part('tree', () => {}); });
                """;
        var parts = BuildPipeline.sculptable(List.of(
                new ScriptResult.Part("site", 3090, new int[]{-30, 0, -24, 32, 2, 30}),
                new ScriptResult.Part("house/shell", 485, new int[]{-7, 0, -5, 6, 9, 6}),
                new ScriptResult.Part("house/roof", 310, new int[]{-8, 9, -6, 7, 16, 7}),
                new ScriptResult.Part("garden", 277, new int[]{-10, 0, 1, 10, 4, 16}),
                new ScriptResult.Part("garden/tree", 105, new int[]{-8, 0, 7, 9, 11, 13}),
                new ScriptResult.Part("surroundings/tree", 1587, new int[]{-26, 0, -16, 19, 17, 29})), source);
        // the house has no blocks of its own but is the subject
        var house = parts.get("house");
        assertNotNull(house);
        assertEquals(795, house.cells());
        assertArrayEquals(new int[]{-8, 0, -6, 7, 16, 7}, house.bounds());
        assertTrue(parts.containsKey("house/roof"));
        assertEquals(382, parts.get("garden").cells(), "totals include nested parts");
        // 'tree' is written twice: a rewrite would have no single place to go
        assertFalse(parts.containsKey("garden/tree"));
        assertFalse(parts.containsKey("surroundings/tree"));
        assertEquals("site", parts.keySet().iterator().next(), "largest first");
    }
}
