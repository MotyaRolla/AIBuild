package dev.aibuild.pipeline;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.aibuild.model.BlockInfo;
import dev.aibuild.model.BlockSpec;
import dev.aibuild.model.VoxelModel;
import dev.aibuild.util.Json;

import java.util.ArrayList;
import java.util.List;

final class PatchProofs {
    record Result(JsonArray checked, int certified) { }

    static Result verify(JsonArray source, VoxelModel model, BlockInfo info) {
        JsonArray checked = source.deepCopy();
        int certified = 0;
        int[] budget = {20_000};
        for (JsonElement item : checked) {
            if (!item.isJsonObject()) continue;
            JsonObject check = item.getAsJsonObject();
            if (Json.bool(check,"ok",false)) continue;
            JsonArray proof = Json.arr(check,"proof");
            if (proof.isEmpty() || proof.size() > 32) continue;
            boolean passed = true;
            for (JsonElement predicate : proof) {
                if (!predicate.isJsonObject() || !holds(predicate.getAsJsonObject(),model,info,budget)) {
                    passed = false;
                    break;
                }
            }
            if (passed) {
                check.addProperty("ok",true);
                certified++;
            }
        }
        return new Result(checked,certified);
    }

    private static boolean holds(JsonObject predicate, VoxelModel model, BlockInfo info, int[] budget) {
        try {
            int[] from = point(predicate.has("pos") ? predicate.get("pos") : predicate.get("from"));
            int[] to = predicate.has("pos") ? from : point(predicate.get("to"));
            long volume = 1;
            for (int i=0;i<3;i++) {
                if (Math.abs((long)from[i])>512 || Math.abs((long)to[i])>512 || from[i]>to[i]) return false;
                volume *= (long)to[i]-from[i]+1;
            }
            if (volume>budget[0] || !predicate.has("block")) return false;
            budget[0] -= (int)volume;
            JsonElement block = predicate.get("block");
            JsonArray options = block.isJsonArray() ? block.getAsJsonArray() : new JsonArray();
            if (!block.isJsonArray()) options.add(block);
            if (options.isEmpty() || options.size()>32) return false;
            List<BlockSpec> expected = new ArrayList<>();
            for (JsonElement option : options) {
                String raw = option.getAsString();
                BlockSpec validated = info.parse(raw);
                expected.add(BlockSpec.of(validated.name(),BlockSpec.parseRaw(raw).props()));
            }
            for (int x=from[0];x<=to[0];x++) for (int y=from[1];y<=to[1];y++) for (int z=from[2];z<=to[2];z++) {
                BlockSpec actual = model.effective(x,y,z);
                boolean match = false;
                for (BlockSpec option : expected) {
                    if (!actual.name().equals(option.name())) continue;
                    boolean properties = true;
                    for (var entry : option.props().entrySet()) {
                        if (!entry.getValue().equals(info.getProp(actual,entry.getKey()))) properties = false;
                    }
                    if (properties) { match = true; break; }
                }
                if (!match) return false;
            }
            return true;
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static int[] point(JsonElement element) {
        JsonArray array = element.getAsJsonArray();
        if (array.size()!=3) throw new IllegalArgumentException("expected three coordinates");
        int[] point = new int[3];
        for (int i=0;i<3;i++) {
            double number = array.get(i).getAsDouble();
            if (!Double.isFinite(number) || number != Math.rint(number) || Math.abs(number)>512) {
                throw new IllegalArgumentException("invalid coordinate");
            }
            point[i]=(int)number;
        }
        return point;
    }
}
