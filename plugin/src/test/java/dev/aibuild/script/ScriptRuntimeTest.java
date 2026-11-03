package dev.aibuild.script;

import dev.aibuild.model.BlockSpec;
import dev.aibuild.model.StubBlockInfo;
import dev.aibuild.model.VoxelModel;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ScriptRuntimeTest {
    private final StubBlockInfo info = new StubBlockInfo();
    private final ScriptRuntime runtime = new ScriptRuntime(info);

    private ScriptResult run(String src) {
        return runtime.run(src, 42, null, null, ScriptRuntime.Budget.standard(), new AtomicBoolean());
    }

    @Test void placesBoxesLinesAndBlocks() {
        ScriptResult r = run("""
                box(0,1,0, 4,3,4, 'stone_bricks', {hollow:true});
                line([0,4,0],[4,4,0],'oak_log');
                block(2,5,2,'lantern');
                """);
        assertTrue(r.ok(), () -> r.error().describe());
        assertEquals("stone_bricks", r.model().get(0, 1, 0).name());
        assertTrue(r.model().get(2, 2, 2).isAir(), "hollow box interior is cleared");
        assertEquals("x", r.model().get(2, 4, 0).prop("axis"), "logs follow the line direction");
        assertEquals("lantern", r.model().get(2, 5, 2).name());
    }

    @Test void dialectGapsAreRewrittenOrExplained() {
        ScriptResult ok = run("var s = 0; for (const v of [1,2,3]) { s += v; } box(0,1,0,s,1,0,'stone');");
        assertTrue(ok.ok(), () -> ok.error().describe());
        assertNotNull(ok.model().get(6, 1, 0));

        ScriptResult cls = run("class A {}\nbox(0,1,0,1,1,1,'stone');");
        assertFalse(cls.ok());
        assertTrue(cls.error().message().contains("class syntax is not supported"), cls.error().message());

        ScriptResult spread = run("var p = [0,1,0];\nblock(...p, 'stone');");
        assertFalse(spread.ok());
        assertTrue(spread.error().message().contains("spread"), spread.error().message());

        ScriptResult typo = run("cylinder([0,1,0],[0,5,0],2);");
        assertFalse(typo.ok());
        assertTrue(typo.error().message().contains("did you mean"), typo.error().message());
        assertEquals(1, typo.error().line());
    }

    @Test void constInLoopsAndSiblingBlocksBehavesLikeModernJs() {
        // Rhino's own const keeps its first value inside a loop and rejects the same name in two blocks.
        ScriptResult r = run("""
                var s = 0;
                for (let i = 0; i < 4; i++) { const a = i, b = a * 2; s += b; }
                for (let j = 0; j < 2; j++) { const a = 'x'; }
                var o = {const: 3}; var t = 'const k';  // a const here stays
                box(0,1,0, s + o.const, 1, 0, 'stone');
                block(0, 2, 0, t === 'const k' ? 'lantern' : 'dirt');
                """);
        assertTrue(r.ok(), () -> r.error().describe());
        assertNotNull(r.model().get(15, 1, 0), "loop const takes every iteration's value: 0+2+4+6 + 3");
        assertEquals("lantern", r.model().get(0, 2, 0).name());
        assertEquals(ScriptLint.prepare("const a = 1;").length(), "const a = 1;".length(), "columns are kept");
    }

    @Test void sandboxDeniesJavaAndDate() {
        for (String src : new String[]{
                "var x = java.lang.System; box(0,1,0,0,1,0,'stone');",
                "var x = Packages.java.io.File; box(0,1,0,0,1,0,'stone');",
                "var c = (function(){}).constructor('return this')(); c.java.lang.Runtime.getRuntime(); box(0,1,0,0,1,0,'stone');",
                "var d = new Date(); box(0,1,0,0,1,0,'stone');",
                "__box.getClass().forName('java.lang.Runtime'); box(0,1,0,0,1,0,'stone');"}) {
            ScriptResult r = run(src);
            assertFalse(r.ok(), "must fail: " + src);
        }
    }

    @Test void runawayScriptsStopWithinBudget() {
        long t = System.currentTimeMillis();
        ScriptResult loop = runtime.run("while (true) {}", 1, null, null,
                new ScriptRuntime.Budget(5_000_000, 5_000, 1L << 30, 1000, 1_000_000), new AtomicBoolean());
        assertFalse(loop.ok());
        assertEquals("budget", loop.error().kind());
        assertTrue(System.currentTimeMillis() - t < 5_000);

        ScriptResult cells = runtime.run("box(0,1,0,99,99,9,'stone');", 1, null, null,
                new ScriptRuntime.Budget(5_000_000, 5_000, 1L << 30, 1000, 1_000_000), new AtomicBoolean());
        assertFalse(cells.ok());
        assertTrue(cells.error().message().contains("cells"), cells.error().message());

        ScriptResult deep = run("function f(n){ return f(n+1); } f(0);");
        assertFalse(deep.ok());
    }

    @Test void sameSeedSameModel() {
        String src = """
                var m = mix({stone_bricks: 3, mossy_stone_bricks: 1, cracked_stone_bricks: 1});
                for (var i = 0; i < 20; i++) block(randInt(0, 9), 1, randInt(0, 9), m);
                place(sphere([5,6,5], 4).warp(1, 3), weather('stone_bricks', 0.3), {smooth: true});
                """;
        ScriptResult a = run(src), b = run(src);
        assertTrue(a.ok(), () -> a.error().describe());
        assertEquals(a.model().toJson(), b.model().toJson());
        ScriptResult c = runtime.run(src, 7, null, null, ScriptRuntime.Budget.standard(), new AtomicBoolean());
        assertNotEquals(a.model().toJson(), c.model().toJson(), "a different seed changes random choices");
    }

    @Test void smoothDomeUsesOrientedStairsAndSlabs() {
        ScriptResult r = run("place(sphere([0,1,0], 7).and(cuboid([-9,1,-9],[9,9,9])), 'stone_bricks', {smooth: true, shell: 2});");
        assertTrue(r.ok(), () -> r.error().describe());
        int stairs = 0, slabs = 0, wrong = 0;
        for (var e : r.model().entries().entrySet()) {
            BlockSpec s = e.getValue();
            int x = VoxelModel.kx(e.getKey()), z = VoxelModel.kz(e.getKey());
            if (s.name().equals("stone_brick_stairs")) {
                stairs++;
                assertTrue(r.smoothed().contains(e.getKey()));
                // a dome's stairs must face toward the centre (their tall side is uphill)
                String f = info.getProp(s, "facing");
                boolean inward = switch (f) {
                    case "north" -> z > 0;
                    case "south" -> z < 0;
                    case "west" -> x > 0;
                    case "east" -> x < 0;
                    default -> false;
                };
                if (!inward && "bottom".equals(info.getProp(s, "half"))) wrong++;
            }
            if (s.name().equals("stone_brick_slab")) slabs++;
        }
        assertTrue(stairs > 20, "stairs=" + stairs);
        assertTrue(slabs > 4, "slabs=" + slabs);
        assertEquals(0, wrong, "bottom stairs facing away from the dome centre");
    }

    @Test void framesRotateStatesAndCoordinates() {
        ScriptResult r = run("""
                withT({at:[10,0,0], rotY:90}, function(){ block(0,1,-2,'oak_stairs[facing=north]'); });
                sym('x', 5, function(){ block(1,1,0,'oak_stairs[facing=west]'); });
                """);
        assertTrue(r.ok(), () -> r.error().describe());
        // (0,-2) rotated clockwise -> (2, 0), then +10 on x
        assertEquals("east", info.getProp(r.model().get(12, 1, 0), "facing"));
        assertEquals("west", info.getProp(r.model().get(1, 1, 0), "facing"));
        assertEquals("east", info.getProp(r.model().get(9, 1, 0), "facing"), "mirrored copy flips east/west");
    }

    @Test void partsTrackOwnershipAndBounds() {
        ScriptResult r = run("""
                part('tower', function(){ place(cyl([0,1,0],[0,10,0],3), 'cobblestone'); });
                part('house', function(){ box(5,1,0, 12,5,6, 'oak_planks', {walls:true}); });
                """);
        assertTrue(r.ok(), () -> r.error().describe());
        assertEquals(2, r.parts().size());
        assertEquals("tower", r.owners().get(VoxelModel.key(0, 5, 0)));
        assertEquals(10, r.parts().getFirst().bounds()[4]);
    }

    @Test void everyKitRuns() {
        ScriptResult r = run("""
                meta({title: 'kit test'});
                part('body', function () {
                  box(0,0,0, 12,0,10, 'cobblestone');
                  box(0,1,0, 12,5,10, weather(mix({stone_bricks: 4, cobblestone: 1}), 0.2), {walls: true});
                  timber(0,1,11, 12,5,11, {braces: true});
                  windowAt(3,2,10,'south',{w:2, frame:'spruce_planks', sill:true, shutters:'spruce_trapdoor'});
                  door(6,1,10,'south',{frame:'stone_bricks', step:true, lamp:true});
                  arch(8,4,10,4,1,{style:'gothic', ring:'stone_bricks', depth:1});
                  roof(0,0,12,10,6,{type:'gable', m:'dark_oak_planks'});
                  chimney(11,8,6,12);
                  balcony(2,4,10,'south',{w:3});
                });
                part('tower', function () {
                  place(cyl([20,1,5],[20,14,5],3), 'stone_bricks', {shell: 1});
                  battlements(17,15,2,23,8,'stone_bricks');
                  roof(17,2,23,8,15,{type:'cone', m:'deepslate_tiles'});
                  spiralStair(20,1,5,12,2);
                  column(15,1,0,6,{base:'stone_bricks', capital:'stone_bricks'});
                });
                part('inside', function () {
                  bed(2,1,2,'north'); table(5,1,3,{w:2,d:2}); chair(4,1,3,'west');
                  fireplace(6,1,1,'south'); lamp(3,5,5,{hanging:true, chain:1}); rug(2,6,4,8,1,'red','white');
                  kitchen(9,1,1,'east',3,'south'); shelves(1,1,9,4,9,2);
                });
                part('garden', function () {
                  tree(-6,1,-6,{species:'oak'}); tree(-10,1,4,{species:'spruce'}); rock(-4,1,12,2);
                  path([[6,0,11],[6,0,16],[2,0,20]]); flowers(-8,12,0,20); bush(14,1,14,1.5); hill(30,20,6,5,4);
                  place(tube([[0,1,30],[5,6,32],[10,3,35]], function(t){ return 1.5 - t; }), 'oak_log');
                  place(revolve([30,1,0], [[2,0],[3,2],[1.5,5],[2.5,7]]), 'terracotta', {smooth: true});
                  place(extrude([[40,0],[46,0],[43,5]], 1, 3), 'stone');
                  place(loft([{c:[50,1,0], r:3},{c:[50,5,0], rx:2, rz:4}]), 'oak_planks');
                  place(blend(2, sphere([60,5,0],3), sphere([64,5,0],2.5)), 'white_wool');
                  place(torus([70,3,0],4,1), 'gold_block');
                  water([40,1,10, 42,1,12]);
                });
                """);
        assertTrue(r.ok(), () -> r.error().describe());
        assertEquals("kit test", r.meta().get("title").getAsString());
        assertTrue(r.model().nonAirCount() > 2000, "blocks=" + r.model().nonAirCount());
        assertFalse(r.doorways().isEmpty(), "door() registers a doorway");
        assertTrue(r.parts().stream().anyMatch(p -> p.name().equals("garden/tree")));
    }

    @Test void limitsDropOutsideWrites() {
        ScriptResult r = runtime.run("box(-20,1,-20, 20,1,20, 'stone');", 1, null, new int[]{0, 0, 0, 9, 9, 9},
                ScriptRuntime.Budget.standard(), new AtomicBoolean());
        assertTrue(r.ok(), () -> r.error().describe());
        assertEquals(100, r.model().nonAirCount());
        assertTrue(r.outside() > 0);
    }

    @Test void legacyOpsAndErrorsPointAtTheScriptLine() {
        ScriptResult r = run("""
                box(0,1,0, 8,4,6, 'oak_planks', {walls:true});
                op({op:'roof', from:[0,5,0], to:[8,5,6], style:'hip', stairs:'spruce_stairs', overhang:1});
                """);
        assertTrue(r.ok(), () -> r.error().describe());
        assertTrue(r.model().counts().keySet().stream().anyMatch(s -> s.name().equals("spruce_stairs")));

        ScriptResult bad = run("box(0,1,0,1,1,1,'stone');\nplace(sphere([0,0,0], -1), 'stone');");
        assertFalse(bad.ok());
        assertEquals(2, bad.error().line(), bad.error().describe());
        assertTrue(bad.error().message().contains("radius"), bad.error().message());
    }

    @Test void promptExamplesRunCleanly() throws Exception {
        for (String name : new String[]{"cottage", "fox", "bridge"}) {
            String src;
            try (var in = getClass().getResourceAsStream("/stdlib/examples/" + name + ".js")) {
                src = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            ScriptResult r = run(src);
            assertTrue(r.ok(), () -> name + ": " + r.error().describe());
            assertTrue(r.warnings().isEmpty(), name + ": " + r.warnings());
            assertTrue(r.model().nonAirCount() > 800, name + " blocks=" + r.model().nonAirCount());
            assertTrue(dev.aibuild.pipeline.ScriptPrompts.system(null).contains(src.strip()), name + " is shown to the model");
        }
    }
}
