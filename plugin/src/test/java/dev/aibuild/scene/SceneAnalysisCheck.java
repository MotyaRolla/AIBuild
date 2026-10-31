package dev.aibuild.scene;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the scene format and the objective analysis.
 *
 * <p>These are the checks the AI cannot fake: whatever the model writes, the analysis reports whether a door,
 * a roof and windows exist, whether the size matches the request and whether the palette is rich enough. The
 * build loop stops early only when this analysis is clean, so a false negative here would let the loop finish
 * on a broken building.</p>
 */
class SceneAnalysisCheck {

    private static SceneSpec spec(String json) {
        return SceneSpec.parse(JsonParser.parseString(json).getAsJsonObject());
    }

    @Test
    void parsesObjectsAndNormalisesCorners() {
        SceneSpec s = spec("""
                {"name":"test","objects":[
                  {"id":"walls","type":"box","from":[9,6,9],"to":[0,0,0],"material":"wall"},
                  {"type":"roof","from":[0,7,0],"to":[9,7,9],"style":"gable","material":"roof"}]}
                """);
        assertEquals(2, s.objects().size());
        assertEquals(0, s.errors().size());
        // corners are swapped into from<=to
        int[] from = s.objects().get(0).from();
        int[] to = s.objects().get(0).to();
        assertEquals(0, from[0]);
        assertEquals(9, to[0]);
        // "roof" is an alias of prism
        assertEquals("prism", s.objects().get(1).type());
    }

    @Test
    void keepsGoodObjectsAndReportsBrokenOnes() {
        SceneSpec s = spec("""
                {"objects":[
                  {"type":"box","from":[0,0,0],"to":[5,5,5],"material":"wall"},
                  {"type":"nonsense","from":[0,0,0],"to":[1,1,1]},
                  {"type":"window","at":[2,3],"material":"glass"},
                  {"type":"door","wall":"south","at":[2,1],"material":"wood"}]}
                """);
        assertEquals(2, s.objects().size(), "box and the valid door are kept");
        assertTrue(s.errors().size() >= 2, "unknown type and a window without a wall are reported: " + s.errors());
        assertTrue(s.errors().stream().anyMatch(e -> e.contains("unknown object type")));
        assertTrue(s.errors().stream().anyMatch(e -> e.contains("wall must be")));
    }

    @Test
    void rejectsASpecWithoutObjects() {
        boolean threw = false;
        try {
            spec("{\"name\":\"x\"}");
        } catch (IllegalArgumentException e) {
            threw = true;
            assertTrue(e.getMessage().contains("objects"));
        }
        assertTrue(threw, "a spec without an objects array must be rejected");
    }

    @Test
    void paletteMergesDefaultsAndCustomColours() {
        SceneSpec s = spec("""
                {"palette":{"wall":"#ff0000"},"objects":[{"type":"box","from":[0,0,0],"to":[1,1,1],"material":"wall"}]}
                """);
        assertEquals("#ff0000", s.color("wall"), "custom colour wins");
        assertNotNull(s.color("roof"), "default keys stay available");
    }

    @Test
    void boundsCoverAllObjectsIncludingDetailCells() {
        SceneSpec s = spec("""
                {"objects":[
                  {"type":"box","from":[0,0,0],"to":[9,5,9],"material":"wall"},
                  {"type":"detail","material":"frame","cells":[[-2,6,-2],[12,7,12]]}]}
                """);
        int[][] b = s.bounds();
        assertEquals(-2, b[0][0]);
        assertEquals(12, b[1][0]);
        assertEquals(15, b[2][0], "width spans -2..12");
    }

    @Test
    void analysisIsCleanForACompleteHouse() {
        SceneSpec s = spec("""
                {"objects":[
                  {"id":"body","type":"box","from":[0,0,0],"to":[9,5,9],"material":"wall"},
                  {"id":"roof","type":"prism","from":[0,5,0],"to":[9,5,9],"style":"gable","material":"roof"},
                  {"id":"door","type":"door","wall":"south","at":[4,1],"w":2,"h":3,"material":"wood"},
                  {"id":"w1","type":"window","wall":"south","at":[1,2],"w":1,"h":2,"material":"glass"},
                  {"id":"w2","type":"window","wall":"east","at":[3,2],"w":1,"h":2,"material":"glass"},
                  {"id":"st","type":"stairs","from":[1,1,7],"to":[2,3,7],"axis":"z","material":"wood"},
                  {"id":"b1","type":"beam","from":[0,3,0],"to":[9,3,0],"material":"frame"},
                  {"id":"b2","type":"beam","from":[0,0,9],"to":[9,0,9],"material":"frame"},
                  {"id":"ch","type":"chimney","at":[1,5,1],"height":4,"material":"stone"}]}
                """);
        SceneAnalysis a = SceneAnalysis.analyze(s, "дом 10 на 10");
        assertTrue(a.ok(), "a complete house must pass: " + a.render());
        assertEquals(0, a.count(SceneAnalysis.Severity.CRITICAL));
        assertEquals(0, a.count(SceneAnalysis.Severity.MAJOR));
    }

    @Test
    void analysisCatchesMissingRoofDoorAndWindows() {
        SceneSpec s = spec("""
                {"objects":[{"id":"body","type":"box","from":[0,0,0],"to":[9,5,9],"material":"wall"}]}
                """);
        SceneAnalysis a = SceneAnalysis.analyze(s, "дом 10 на 10");
        assertFalse(a.ok());
        String text = a.render();
        assertTrue(text.contains("крыш"), text);
        assertTrue(text.contains("вход"), text);
        assertTrue(text.contains("окон"), text);
    }

    @Test
    void analysisCatchesSizeMismatchAgainstTheRequest() {
        SceneSpec s = spec("""
                {"objects":[
                  {"type":"box","from":[0,0,0],"to":[19,5,19],"material":"wall"},
                  {"type":"prism","from":[0,5,0],"to":[19,5,19],"style":"gable","material":"roof"},
                  {"type":"door","wall":"south","at":[9,1],"material":"wood"},
                  {"type":"window","wall":"south","at":[3,2],"material":"glass"}]}
                """);
        SceneAnalysis a = SceneAnalysis.analyze(s, "дом 10 на 10");
        assertTrue(a.render().contains("размер не совпадает"), a.render());
    }

    @Test
    void analysisIgnoresPropsWhenCheckingSize() {
        // the path and the chimney stick out, but the body is exactly 10x10
        SceneSpec s = spec("""
                {"objects":[
                  {"id":"body","type":"box","from":[0,0,0],"to":[9,5,9],"material":"wall"},
                  {"id":"roof","type":"prism","from":[0,5,0],"to":[9,5,9],"style":"gable","material":"roof"},
                  {"id":"door","type":"door","wall":"south","at":[4,1],"material":"wood"},
                  {"id":"w1","type":"window","wall":"south","at":[1,2],"material":"glass"},
                  {"type":"box","from":[4,0,10],"to":[5,0,18],"material":"stone"}]}
                """);
        SceneAnalysis a = SceneAnalysis.analyze(s, "дом 10 на 10");
        assertFalse(a.render().contains("размер не совпадает"), a.render());
    }

    @Test
    void emptySpecIsCritical() {
        SceneSpec s = SceneSpec.of("empty", java.util.List.of());
        SceneAnalysis a = SceneAnalysis.analyze(s, "дом");
        assertFalse(a.ok());
        assertEquals(1, a.count(SceneAnalysis.Severity.CRITICAL));
    }

    @Test
    void parseRequestedSizeUnderstandsRussianAndSymbols() {
        assertArrayEqualsInt(new int[]{10, 10}, SceneAnalysis.parseRequestedSize("дом 10 на 10 блоков"));
        assertArrayEqualsInt(new int[]{12, 8}, SceneAnalysis.parseRequestedSize("12x8 tower"));
        assertArrayEqualsInt(new int[]{20, 20}, SceneAnalysis.parseRequestedSize("замок 20 Х 20"));
        assertNull(SceneAnalysis.parseRequestedSize("средневековый дом"));
        assertNull(SceneAnalysis.parseRequestedSize(null), "null request is safe");
    }

    private static void assertArrayEqualsInt(int[] expected, int[] actual) {
        assertNotNull(actual);
        assertEquals(expected[0], actual[0]);
        assertEquals(expected[1], actual[1]);
    }

    @Test
    void detailShareIsChecked() {
        // ten plain boxes and no decoration at all -> the "flat building" warning
        StringBuilder sb = new StringBuilder("{\"objects\":[");
        for (int i = 0; i < 12; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"id\":\"b").append(i).append("\",\"type\":\"box\",\"from\":[0,").append(i)
                    .append(",0],\"to\":[9,").append(i).append(",9],\"material\":\"wall\"}");
        }
        sb.append("]}");
        SceneAnalysis a = SceneAnalysis.analyze(spec(sb.toString()), "башня");
        assertTrue(a.render().contains("мало деталей"), a.render());
    }
}
