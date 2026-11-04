package dev.aibuild.script;

import dev.aibuild.model.StubBlockInfo;
import dev.aibuild.model.VoxelModel;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Data slips in materials are corrected with a warning instead of failing the whole run. */
class LenientMaterialTest {
    private final StubBlockInfo info = new StubBlockInfo();
    private final ScriptRuntime runtime = new ScriptRuntime(info, Stdlib.V2);

    private ScriptResult run(String src) {
        return runtime.run(src, 42, null, null, ScriptRuntime.Budget.standard(), new AtomicBoolean());
    }

    @Test void aZeroWeightInMixLeavesThatBlockOut() {
        ScriptResult r = run("box(0,1,0,9,1,9, mix({stone: 3, dirt: 0, andesite: 1}));");
        assertTrue(r.ok(), () -> r.error().describe());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("weight of 0")), r.warnings().toString());
        VoxelModel m = r.model();
        m.forEach((x, y, z, s) -> assertNotEquals("dirt", s.name()));
    }

    @Test void gradStopsOutOfOrderAreSorted() {
        ScriptResult r = run("box(0,1,0,0,10,0, grad('y', [[1, 'andesite'], [0, 'stone']], {from: 1, to: 10}));");
        assertTrue(r.ok(), () -> r.error().describe());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("sorted")), r.warnings().toString());
        assertEquals("stone", r.model().get(0, 1, 0).name());
        assertEquals("andesite", r.model().get(0, 10, 0).name());
    }

    @Test void aWeightObjectGivenAsAMaterialIsReadAsMix() {
        ScriptResult r = run("box(0,1,0,4,1,4, {stone: 2, andesite: 1});");
        assertTrue(r.ok(), () -> r.error().describe());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("read as mix")), r.warnings().toString());
        assertEquals(25, r.model().nonAirCount());
    }

    @Test void aHillIsRollingGroundWithGrassOnEveryStep() {
        ScriptResult r = run("hill(0, 0, 12, 10, 6);");
        assertTrue(r.ok(), () -> r.error().describe());
        int[] exposedDirt = {0};
        VoxelModel m = r.model();
        m.forEach((x, y, z, s) -> {
            if (!s.name().equals("dirt")) return;
            if (m.get(x, y + 1, z) == null) exposedDirt[0]++;   // a dirt top would be a bare band
        });
        assertEquals(0, exposedDirt[0], "every column ends in grass");
        assertTrue(m.get(0, 6, 0) != null, "it reaches its height in the middle");
    }
}
