package dev.aibuild.pipeline;

import com.google.gson.JsonObject;
import dev.aibuild.analysis.AnalyzerOptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for which expectations a plan turns into analyzer checks.
 *
 * <p>Live defect: for the request "средневековый дом 10 на 10" the model's own plan invented a height of 17,
 * the analyzer then enforced it as a hard equality, and the built house (28 to the chimney top) failed a
 * check the user never asked for. The height axis is now enforced only when the request itself names one.</p>
 */
class BuildRequirementsCheck {

    private static JsonObject plan(String type, String request, JsonObject exact) {
        JsonObject p = new JsonObject();
        p.addProperty("type", type);
        p.addProperty("request", request);
        p.addProperty("expect_interior", "house".equals(type));
        JsonObject body = new JsonObject();
        body.addProperty("x", 10);
        body.addProperty("z", 10);
        p.add("body", body);
        if (exact != null) p.add("exact_size", exact);
        return p;
    }

    private static JsonObject exact(int x, int y, int z) {
        JsonObject o = new JsonObject();
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("z", z);
        return o;
    }

    @Test
    void heightIsOnlyEnforcedWhenTheRequestAsksForIt() {
        assertTrue(BuildRequirements.heightAsked("башня высотой 18 блоков"));
        assertTrue(BuildRequirements.heightAsked("дом 10 х 10 х 12"));
        assertTrue(BuildRequirements.heightAsked("tower height 18"));
        assertTrue(BuildRequirements.heightAsked("18 blocks tall"));
        assertTrue(BuildRequirements.heightAsked("дом в 3 этажа"));
        assertTrue(BuildRequirements.heightAsked("3-этажный дом"));
        assertFalse(BuildRequirements.heightAsked("средневековый дом 10 на 10 с фахверком"));
        assertFalse(BuildRequirements.heightAsked("Огромная проработанная сакура с озером вокруг"));
        assertFalse(BuildRequirements.heightAsked(""));
        assertFalse(BuildRequirements.heightAsked(null));
    }

    @Test
    void aFootprintOnlyRequestKeepsXAndZAndDropsTheInventedY() {
        JsonObject plan = plan("house", "средневековый дом 10 на 10 с фахверком", exact(10, 17, 10));
        AnalyzerOptions o = BuildRequirements.analyzerOptions(plan);
        assertEquals(10, o.exactSize[0]);
        assertEquals(0, o.exactSize[1], "the model's invented 17 must not become a hard check");
        assertEquals(10, o.exactSize[2]);
        assertEquals(10, o.expectedFootprint[0]);
        assertEquals(10, o.expectedFootprint[1]);
    }

    @Test
    void aHeightAskKeepsItsHeight() {
        JsonObject plan = plan("tower", "башня высотой 18 блоков", exact(8, 18, 8));
        AnalyzerOptions o = BuildRequirements.analyzerOptions(plan);
        assertEquals(18, o.exactSize[1]);
    }

    @Test
    void aTreeHasNoFootprintExpectation() {
        JsonObject plan = plan("tree", "Огромная проработанная сакура с озером вокруг", exact(20, 20, 20));
        AnalyzerOptions o = BuildRequirements.analyzerOptions(plan);
        assertEquals(20, o.exactSize[0], "an explicit size is still a size");
        assertEquals(0, o.exactSize[1], "the sakura request names no height, so even its own meta's y is not enforced");
        assertTrue(o.expectedFootprint == null || o.expectedFootprint[0] == 0,
                "an open object has no wall-footprint expectation");
    }
}
