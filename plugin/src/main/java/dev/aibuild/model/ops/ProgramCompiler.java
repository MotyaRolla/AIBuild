package dev.aibuild.model.ops;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Set;

public final class ProgramCompiler {
    private static final int MAX_OPS = 2048;
    private static final Set<String> POINTS = Set.of("from", "to", "pos", "center", "start", "end",
            "p1", "p2", "pos1", "pos2", "a", "b", "at", "bottom", "top", "dest", "target", "destination", "to_pos");
    private static final Set<String> POINT_LISTS = Set.of("positions", "points", "list", "skip");

    private ProgramCompiler() { }

    public static JsonArray expand(JsonObject repeat) {
        JsonArray result = new JsonArray();
        expandRepeat(repeat, 0, 0, 0, 1, result);
        return result;
    }

    private static void expandRepeat(JsonObject repeat, int dx, int dy, int dz, int depth, JsonArray result) {
        if (depth > 4) throw new IllegalArgumentException("repeat nesting exceeds 4 levels");
        JsonElement offsets = repeat.get("offsets"), children = repeat.get("ops");
        if (offsets == null || !offsets.isJsonArray() || offsets.getAsJsonArray().isEmpty()
                || offsets.getAsJsonArray().size() > 128) {
            throw new IllegalArgumentException("repeat needs 1..128 offsets");
        }
        if (children == null || !children.isJsonArray() || children.getAsJsonArray().isEmpty()) {
            throw new IllegalArgumentException("repeat needs a non-empty ops array");
        }
        for (JsonElement offset : offsets.getAsJsonArray()) {
            int[] p = OpArgs.point(offset, "repeat offset");
            int tx = Math.addExact(dx, p[0]), ty = Math.addExact(dy, p[1]), tz = Math.addExact(dz, p[2]);
            for (JsonElement child : children.getAsJsonArray()) {
                if (!child.isJsonObject()) throw new IllegalArgumentException("repeat children must be objects");
                JsonObject op = child.getAsJsonObject();
                String name = OpArgs.str(op, "", "op", "action", "cmd", "type", "operation", "command");
                if ("repeat".equalsIgnoreCase(name)) {
                    expandRepeat(op, tx, ty, tz, depth + 1, result);
                } else {
                    if (result.size() >= MAX_OPS) throw new IllegalArgumentException("repeat exceeds 2048 expanded operations");
                    JsonObject translated = op.deepCopy();
                    for (String key : POINTS) {
                        if (translated.has(key)) translated.add(key, translate(translated.get(key), tx, ty, tz));
                    }
                    for (String key : POINT_LISTS) {
                        if (!translated.has(key)) continue;
                        JsonElement list = translated.get(key);
                        if (!list.isJsonArray()) throw new IllegalArgumentException(key + " must be an array");
                        JsonArray points = new JsonArray();
                        for (JsonElement point : list.getAsJsonArray()) points.add(translate(point, tx, ty, tz));
                        translated.add(key, points);
                    }
                    result.add(translated);
                }
            }
        }
    }

    private static JsonArray translate(JsonElement point, int dx, int dy, int dz) {
        int[] p = OpArgs.point(point, "module coordinate");
        JsonArray result = new JsonArray();
        result.add(Math.addExact(p[0], dx));
        result.add(Math.addExact(p[1], dy));
        result.add(Math.addExact(p[2], dz));
        return result;
    }
}
