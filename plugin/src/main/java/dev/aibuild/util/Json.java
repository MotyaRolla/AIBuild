package dev.aibuild.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;

import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.Deque;

/** JSON helpers, including tolerant extraction of a JSON value from LLM output. */
public final class Json {
    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    public static final Gson PRETTY = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    private Json() {
    }

    /** Result of {@link #extract}: the value and whether repairs (e.g. truncation closing) were needed. */
    public record Extracted(JsonElement value, boolean repaired, String note) {
    }

    /**
     * Extracts the first JSON object/array from free-form model output: strips markdown fences and prose,
     * tolerates comments / trailing commas / single quotes (lenient mode) and repairs truncated output by
     * cutting back to the last complete element and closing open brackets.
     *
     * @throws IllegalArgumentException when no JSON could be recovered
     */
    public static Extracted extract(String text) {
        if (text == null) throw new IllegalArgumentException("empty response");
        String s = text.trim();
        // strip ```json fences
        int fence = s.indexOf("```");
        if (fence >= 0) {
            int nl = s.indexOf('\n', fence);
            int end = s.indexOf("```", nl < 0 ? fence + 3 : nl);
            if (nl >= 0) s = end > nl ? s.substring(nl + 1, end) : s.substring(nl + 1);
        }
        int start = firstBracket(s);
        if (start < 0) throw new IllegalArgumentException("no JSON object in response");
        s = s.substring(start);
        try {
            return new Extracted(parseLenient(s), false, null);
        } catch (RuntimeException first) {
            String cleaned = stripTrailingCommas(s);
            try {
                return new Extracted(parseLenient(cleaned), true, "removed trailing commas");
            } catch (RuntimeException ignored) {
                // fall through to truncation repair
            }
            String closed = closeTruncated(cleaned);
            if (closed != null) {
                try {
                    return new Extracted(parseLenient(closed), true, "closed truncated JSON");
                } catch (RuntimeException ignored) {
                    // fall through
                }
            }
            throw new IllegalArgumentException("invalid JSON: " + first.getMessage());
        }
    }

    private static int firstBracket(String s) {
        int o = s.indexOf('{');
        int a = s.indexOf('[');
        if (o < 0) return a;
        if (a < 0) return o;
        return Math.min(o, a);
    }

    private static JsonElement parseLenient(String s) {
        JsonReader r = new JsonReader(new StringReader(s));
        r.setStrictness(Strictness.LENIENT);
        return JsonParser.parseReader(r);
    }

    private static String stripTrailingCommas(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean inStr = false, esc = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                out.append(c);
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') {
                inStr = true;
                out.append(c);
                continue;
            }
            if (c == ',') {
                int j = i + 1;
                while (j < s.length() && Character.isWhitespace(s.charAt(j))) j++;
                if (j < s.length() && (s.charAt(j) == '}' || s.charAt(j) == ']')) continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * Repairs JSON cut off mid-stream: finds the last position where a value inside an array/object ended
     * cleanly, truncates there and appends the closing brackets.
     */
    static String closeTruncated(String s) {
        Deque<Character> stack = new ArrayDeque<>();
        boolean inStr = false, esc = false;
        int lastSafe = -1; // index after which we can cut (exclusive end)
        Deque<Character> stackAtSafe = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            switch (c) {
                case '"' -> inStr = true;
                case '{', '[' -> stack.push(c);
                case '}', ']' -> {
                    if (!stack.isEmpty()) stack.pop();
                    if (stack.isEmpty()) return s.substring(0, i + 1); // complete value; trailing junk dropped
                    lastSafe = i + 1;
                    stackAtSafe = new ArrayDeque<>(stack);
                }
                case ',' -> {
                    lastSafe = i; // cut before the comma
                    stackAtSafe = new ArrayDeque<>(stack);
                }
                default -> {
                }
            }
        }
        if (lastSafe < 0 || stackAtSafe == null) return null;
        StringBuilder sb = new StringBuilder(stripTrailingCommas(s.substring(0, lastSafe)).trim());
        // if we cut right after a key (e.g. {"a":), drop the dangling key
        while (!sb.isEmpty() && (sb.charAt(sb.length() - 1) == ':' || sb.charAt(sb.length() - 1) == ',')) {
            sb.setLength(sb.length() - 1);
        }
        for (char open : stackAtSafe) sb.append(open == '{' ? '}' : ']');
        return sb.toString();
    }

    // -------------------------------------------------------------- small accessors

    public static String str(JsonObject o, String key, String def) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return def;
        JsonElement e = o.get(key);
        return e.isJsonPrimitive() ? e.getAsString() : e.toString();
    }

    public static int integer(JsonObject o, String key, int def) {
        try {
            return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? (int) Math.round(o.get(key).getAsDouble()) : def;
        } catch (RuntimeException e) {
            return def;
        }
    }

    public static boolean bool(JsonObject o, String key, boolean def) {
        try {
            if (o == null || !o.has(key) || !o.get(key).isJsonPrimitive()) return def;
            var p = o.get(key).getAsJsonPrimitive();
            return p.isBoolean() ? p.getAsBoolean() : Boolean.parseBoolean(p.getAsString());
        } catch (RuntimeException e) {
            return def;
        }
    }

    public static JsonArray arr(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
    }

    public static JsonObject obj(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : null;
    }
}
