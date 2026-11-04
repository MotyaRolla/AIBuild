package dev.aibuild.pipeline;

import com.google.gson.JsonObject;
import dev.aibuild.analysis.AnalyzerOptions;
import dev.aibuild.util.Json;

import java.util.Locale;
import java.util.Set;

final class BuildRequirements {
    private static final Set<String> BUILDINGS = Set.of("building", "house", "tower", "castle", "shop");
    private static final Set<String> OPEN_OBJECTS = Set.of("statue", "sculpture", "monument", "dragon",
            "tree", "bridge", "terrain", "landscape", "ruin", "ruins");

    static boolean isBuilding(JsonObject plan) {
        String type = Json.str(plan, "type", "").toLowerCase(Locale.ROOT).trim();
        if (BUILDINGS.contains(type)) return true;
        if (OPEN_OBJECTS.contains(type) || type.equals("ship")) return false;
        return Json.bool(plan, "expect_interior", true);
    }

    static AnalyzerOptions analyzerOptions(JsonObject plan) {
        String type = Json.str(plan, "type", "").toLowerCase(Locale.ROOT).trim();
        AnalyzerOptions options = new AnalyzerOptions(Json.bool(plan, "expect_interior", !OPEN_OBJECTS.contains(type)));
        JsonObject body = Json.obj(plan, "body");
        if (isBuilding(plan) && body != null) {
            options.expectedFootprint(Json.integer(body, "x", 0), Json.integer(body, "z", 0));
        }
        options.maximumSize = dimensions(Json.obj(plan, "size_limits"));
        options.exactSize = dimensions(Json.obj(plan, "exact_size"));
        // The height axis is only enforced when the request itself names a height. The model's own plan
        // invents a height for a request like "дом 10 на 10" (it decided 17), and the built house then
        // measured 28 to the chimney top: a defect the user never asked about. A request that does say
        // "высотой 18" or "18 blocks tall" keeps its hard height expectation.
        if (options.exactSize != null && options.exactSize.length > 1 && options.exactSize[1] > 0
                && !heightAsked(Json.str(plan, "request", ""))) {
            options.exactSize[1] = 0;
        }
        return options;
    }

    /**
     * A stated height, in Russian or English: "высотой 18", "18 в высоту", "height 18", "18 blocks tall",
     * "10 х 10 х 12", "3 этажа", "3-этажный". The number must sit right next to the height word; a looser
     * match read "двухэтажный дом 10 на 10" and "50x50 with high walls" as heights, which brought back the
     * invented-height check for exactly the requests that name only a footprint.
     */
    private static final java.util.regex.Pattern HEIGHT = java.util.regex.Pattern.compile(String.join("|",
            "высот\\p{L}*\\s*(?:в|около|примерно|~)?\\s*\\d+",
            "\\d+\\s*(?:блок\\p{L}*\\s*)?в\\s*высоту",
            "\\d+\\s*[xх×*]\\s*\\d+\\s*[xх×*]\\s*\\d+",
            "height\\s*(?:of\\s*)?[:=]?\\s*\\d+",
            "\\d+\\s*(?:blocks?\\s*)?(?:high|tall)\\b",
            "\\d+\\s*-?\\s*этаж",
            "этаж(?:ей|а|и)?\\s*[:=]?\\s*\\d+"));

    /**
     * Whether the request states a height (or a full three-axis size). A two-axis size ("10 на 10") is a
     * footprint and does not count.
     */
    static boolean heightAsked(String request) {
        if (request == null || request.isBlank()) return false;
        return HEIGHT.matcher(request.toLowerCase(Locale.ROOT)).find();
    }

    private static int[] dimensions(JsonObject value) {
        if (value == null) return null;
        return new int[]{Math.max(0, Json.integer(value, "x", 0)), Math.max(0, Json.integer(value, "y", 0)),
                Math.max(0, Json.integer(value, "z", 0))};
    }
}
