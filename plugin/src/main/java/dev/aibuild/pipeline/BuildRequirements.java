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
        return options;
    }

    private static int[] dimensions(JsonObject value) {
        if (value == null) return null;
        return new int[]{Math.max(0, Json.integer(value, "x", 0)), Math.max(0, Json.integer(value, "y", 0)),
                Math.max(0, Json.integer(value, "z", 0))};
    }
}
