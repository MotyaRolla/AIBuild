package dev.aibuild.script;

import dev.aibuild.model.BlockInfo;
import dev.aibuild.model.BlockSpec;
import dev.aibuild.model.Dir;
import dev.aibuild.model.StubBlockInfo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Data mistakes in a script are corrected with a warning instead of stopping the run. Live builds lost ~45 attempts
 * (each a model call of minutes) to 'polished_andesite_wall', 'iron_chain[hanging=true]', stairs of hay_block, a NaN
 * coordinate in a loop, or a point object passed to mark().
 */
class LenientTest {
    /** The stub, but strict like the server: only listed blocks exist, and only their own properties. */
    static BlockInfo strict() {
        StubBlockInfo s = new StubBlockInfo();
        List<String> names = new ArrayList<>(s.blockNames());
        names.addAll(List.of("andesite_wall", "polished_andesite", "polished_andesite_slab", "polished_andesite_stairs",
                "hay_block", "andesite_stairs", "andesite_slab"));
        return new BlockInfo() {
            // the stub accepts any well-formed name; these are the wrong names live builds used
            final java.util.Set<String> missing = java.util.Set.of("polished_andesite_wall", "cobblestone_wal", "block", "spruce");

            public BlockSpec parse(String raw) {
                BlockSpec spec = BlockSpec.parseRaw(raw);
                if (missing.contains(spec.name())) throw new IllegalArgumentException("unknown block '" + spec.name() + "'");
                if (spec.name().equals("lantern")) {
                    for (String k : spec.props().keySet()) {
                        if (!k.equals("hanging")) throw new IllegalArgumentException("block 'lantern' has no property '" + k + "'");
                    }
                }
                return s.parse(raw);
            }
            public BlockSpec with(BlockSpec spec, String k, String v) {
                if (spec.name().equals("lantern") && !k.equals("hanging")) throw new IllegalArgumentException("no property " + k);
                return s.with(spec, k, v);
            }
            public BlockSpec canonical(BlockSpec spec) { return s.canonical(spec); }
            public boolean hasProperty(String b, String p) { return StubBlockInfo.defaults(b).containsKey(p); }
            public String getProp(BlockSpec spec, String p) { return s.getProp(spec, p); }
            public boolean isAir(BlockSpec spec) { return s.isAir(spec); }
            public boolean isSolid(BlockSpec spec) { return s.isSolid(spec); }
            public boolean isOccluding(BlockSpec spec) { return s.isOccluding(spec); }
            public boolean isSturdyFace(BlockSpec spec, Dir f) { return s.isSturdyFace(spec, f); }
            public boolean hasGravity(BlockSpec spec) { return s.hasGravity(spec); }
            public int lightEmission(BlockSpec spec) { return s.lightEmission(spec); }
            public boolean isLiquid(BlockSpec spec) { return s.isLiquid(spec); }
            public int mapColor(BlockSpec spec) { return s.mapColor(spec); }
            public BlockSpec rotate(BlockSpec spec, int q) { return s.rotate(spec, q); }
            public BlockSpec mirror(BlockSpec spec, char a) { return s.mirror(spec, a); }
            public List<String> blockNames() { return names; }
        };
    }

    private final ScriptRuntime runtime = new ScriptRuntime(strict(), Stdlib.V2);

    private ScriptResult run(String src) {
        ScriptResult r = runtime.run(src, 3, null, null, ScriptRuntime.Budget.standard(), null);
        assertTrue(r.ok(), () -> r.error().describe());
        return r;
    }

    private static String at(ScriptResult r, int x, int y, int z) {
        BlockSpec s = r.model().get(x, y, z);
        return s == null || s.isAir() ? "air" : s.name();
    }

    private static boolean warned(ScriptResult r, String text) {
        return r.warnings().stream().anyMatch(w -> w.contains(text));
    }

    @Test void aShapeThatDoesNotExistInThatMaterialUsesTheNearestOne() {
        ScriptResult r = run("block(0, 1, 0, 'polished_andesite_wall');");
        assertEquals("andesite_wall", at(r, 0, 1, 0));
        assertTrue(warned(r, "auto-corrected: 'polished_andesite_wall' -> 'andesite_wall'"), () -> r.warnings().toString());
    }

    @Test void aPropertyTheBlockDoesNotHaveIsDropped() {
        ScriptResult r = run("block(0, 1, 0, 'lantern[hanging=true,color=red]');");
        assertEquals("lantern", at(r, 0, 1, 0));
        assertEquals("true", r.model().get(0, 1, 0).props().get("hanging"), "the valid property is kept");
        assertTrue(warned(r, "auto-corrected"), () -> r.warnings().toString());
    }

    @Test void aNameOneLetterOffBecomesTheRealBlock() {
        ScriptResult r = run("box(0, 1, 0, 2, 1, 2, 'cobblestone_wal');");
        assertEquals("cobblestone_wall", at(r, 1, 1, 1));
    }

    @Test void aNameNothingLikeAnyBlockStillStopsTheScript() {
        ScriptResult r = runtime.run("block(0, 1, 0, 'block');", 3, null, null, ScriptRuntime.Budget.standard(), null);
        assertFalse(r.ok(), "guessing 'bedrock' for 'block' would build something the model did not mean");
        assertTrue(r.error().message().contains("unknown block"), r.error().message());
    }

    @Test void stairsOfABlockWithoutStairsUseTheFullBlock() {
        ScriptResult r = run("block(0, 1, 0, stair('hay_block', 'north'));");
        assertEquals("hay_block", at(r, 0, 1, 0));
        assertTrue(warned(r, "hay_block has no stairs"), () -> r.warnings().toString());
    }

    @Test void stairsOfASlabAreTheStairsOfItsMaterial() {
        ScriptResult r = run("block(0, 1, 0, stair('spruce_slab', 'north'));");
        assertEquals("spruce_stairs", at(r, 0, 1, 0));
    }

    @Test void aNanCoordinateSkipsOnlyThatWrite() {
        ScriptResult r = run("""
                for (let i = 0; i < 3; i++) block(i, 1, 0, 'stone');
                block(NaN, 1, 0, 'stone');
                box(0, 2, 0, 0 / 0, 2, 0, 'stone');
                block(5, 1, 0, 'oak_planks');
                """);
        assertEquals("stone", at(r, 2, 1, 0));
        assertEquals("oak_planks", at(r, 5, 1, 0), "the script went on after the bad write");
        assertTrue(warned(r, "NaN or infinite"), () -> r.warnings().toString());
    }

    @Test void markTakesThePointAndBoxObjectsTheKitsReturn() {
        ScriptResult r = run("""
                part('house', () => {
                    box(0, 1, 0, 4, 3, 4, 'stone');
                    mark('gate', {x: 2, y: 1, z: 0}, 'the gate');
                    mark('court', {x1: 1, y1: 1, z1: 1, x2: 3, y2: 1, z2: 3});
                });
                """);
        assertTrue(r.anchors().stream().anyMatch(a -> a.key().endsWith("gate") && a.point()), () -> r.anchors().toString());
        assertTrue(r.anchors().stream().anyMatch(a -> a.key().endsWith("court") && !a.point()), () -> r.anchors().toString());
    }

    @Test void correctedScriptsReRunToTheSameModel() {
        String src = "block(0, 1, 0, 'polished_andesite_wall'); block(1, 1, 0, stair('hay_block', 'east')); block(NaN, 0, 0, 'stone');";
        ScriptResult a = run(src), b = run(src);
        assertEquals(a.model().nonAirCount(), b.model().nonAirCount());
        assertEquals(at(a, 0, 1, 0), at(b, 0, 1, 0));
        assertEquals(a.warnings(), b.warnings());
    }

    @Test void aLongComputationNowRuns() {
        // ~1.3B instructions under the old 300M cap would stop after a second of work
        ScriptResult r = run("let s = 0; for (let i = 0; i < 40000000; i++) { s += i % 7; } block(0, 1, 0, 'stone');");
        assertEquals("stone", at(r, 0, 1, 0));
        assertTrue(r.instructions() > 300_000_000L, "instructions " + r.instructions());
    }

    @Test void aBareWoodNameWithAnAxisIsItsLog() {
        ScriptResult r = run("block(0, 1, 0, 'spruce[axis=x]'); block(1, 1, 0, 'spruce');");
        assertEquals("spruce_log", at(r, 0, 1, 0));
        assertEquals("x", r.model().get(0, 1, 0).props().get("axis"));
        assertEquals("spruce_planks", at(r, 1, 1, 0));
    }
}
