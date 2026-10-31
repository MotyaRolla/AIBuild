package dev.aibuild.ai;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

final class OperationLoopGuard {
    private final Deque<Integer> objects = new ArrayDeque<>();
    private final Deque<String> recent = new ArrayDeque<>();
    private final Map<String,Integer> counts = new HashMap<>();
    private int cursor;
    private boolean quoted, escaped;

    boolean looping(StringBuilder text) {
        while (cursor < text.length()) {
            int index = cursor++;
            char c = text.charAt(index);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
                continue;
            }
            if (c == '"') quoted = true;
            else if (c == '{') objects.push(index);
            else if (c == '}' && !objects.isEmpty()) {
                int start = objects.pop();
                if (objects.size() > 1) continue;
                if (index-start > 16_384) continue;
                try {
                    JsonObject object = JsonParser.parseString(text.substring(start,index+1)).getAsJsonObject();
                    if (!object.has("op") || !object.get("op").isJsonPrimitive()) continue;
                    TreeMap<String,JsonElement> sorted = new TreeMap<>();
                    object.entrySet().forEach(e -> sorted.put(e.getKey(),e.getValue()));
                    String key = sorted.toString();
                    recent.addLast(key);
                    int count = counts.merge(key,1,Integer::sum);
                    if (recent.size() > 64) counts.computeIfPresent(recent.removeFirst(),(k,v) -> v == 1 ? null : v-1);
                    if (count >= 6) return true;
                } catch (RuntimeException ignored) {
                }
            }
        }
        return false;
    }
}
