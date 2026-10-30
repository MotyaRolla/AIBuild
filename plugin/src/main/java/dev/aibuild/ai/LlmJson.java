package dev.aibuild.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * Package-private JSON helpers shared by the LLM transports.
 *
 * <p>Every accessor is defensive: LLM gateways change their payload shapes without notice, so a
 * missing, wrongly-typed or {@code null} field must never throw. Parsing failures return
 * {@code null} and are handled by the callers.</p>
 */
final class LlmJson {

    /** Lenient, HTML-escaping-disabled Gson used for request serialisation. */
    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private LlmJson() {
    }

    /** Parses text into an object, or returns {@code null} if it is not a JSON object. */
    static JsonObject parseObject(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            JsonElement e = JsonParser.parseString(text);
            return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** @return the named member if it is a JSON object, else {@code null}. */
    static JsonObject obj(JsonObject parent, String key) {
        if (parent == null || key == null || !parent.has(key)) {
            return null;
        }
        JsonElement e = parent.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    /** @return the named member if it is a JSON array, else {@code null}. */
    static JsonArray array(JsonObject parent, String key) {
        if (parent == null || key == null || !parent.has(key)) {
            return null;
        }
        JsonElement e = parent.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }

    /** @return the first present member of the array with the given name, else {@code null}. */
    static JsonObject firstObject(JsonArray array) {
        if (array == null || array.isEmpty()) {
            return null;
        }
        JsonElement first = array.get(0);
        return first != null && first.isJsonObject() ? first.getAsJsonObject() : null;
    }

    /**
     * Reads the first present, string-valued member among {@code keys}.
     *
     * @return the value, or {@code null} when none of the keys holds a string/number
     */
    static String string(JsonObject parent, String... keys) {
        if (parent == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (key == null || !parent.has(key)) {
                continue;
            }
            JsonElement e = parent.get(key);
            if (e == null || e.isJsonNull()) {
                continue;
            }
            if (e.isJsonPrimitive()) {
                JsonPrimitive p = e.getAsJsonPrimitive();
                if (p.isString()) {
                    return p.getAsString();
                }
                if (p.isNumber() || p.isBoolean()) {
                    return p.getAsString();
                }
            }
        }
        return null;
    }

    /**
     * Reads the first present integer-valued member among {@code keys}.
     *
     * @return the value clamped to {@code int}, or {@code null} when absent/unparseable
     */
    static Integer integer(JsonObject parent, String... keys) {
        if (parent == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (key == null || !parent.has(key)) {
                continue;
            }
            JsonElement e = parent.get(key);
            if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) {
                continue;
            }
            JsonPrimitive p = e.getAsJsonPrimitive();
            try {
                if (p.isNumber()) {
                    long v = p.getAsLong();
                    return (int) Math.clamp(v, 0L, (long) Integer.MAX_VALUE);
                }
                if (p.isString()) {
                    String s = p.getAsString().trim();
                    if (!s.isEmpty()) {
                        long v = Long.parseLong(s);
                        return (int) Math.clamp(v, 0L, (long) Integer.MAX_VALUE);
                    }
                }
            } catch (RuntimeException ignored) {
                // Try the next key.
            }
        }
        return null;
    }

    /**
     * Extracts the text of a streaming delta that may be a plain string or an array of content
     * parts ({@code [{"type":"text","text":"…"}]}).
     *
     * @param element the raw JSON member, may be {@code null}
     * @return the concatenated text, or {@code null} when there is none
     */
    static String textOf(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            return element.getAsString();
        }
        if (element.isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonElement part : element.getAsJsonArray()) {
                if (part == null || part.isJsonNull()) {
                    continue;
                }
                if (part.isJsonPrimitive() && part.getAsJsonPrimitive().isString()) {
                    sb.append(part.getAsString());
                } else if (part.isJsonObject()) {
                    String t = string(part.getAsJsonObject(), "text", "content");
                    if (t != null) {
                        sb.append(t);
                    }
                }
            }
            return sb.isEmpty() ? null : sb.toString();
        }
        if (element.isJsonObject()) {
            return string(element.getAsJsonObject(), "text", "content");
        }
        return null;
    }

    /**
     * Extracts an error object from a provider payload.
     *
     * <p>Understands {@code {"error":{"message":…,"code":…}}}, {@code {"error":"text"}}, a bare
     * {@code {"message":…,"code":…}} and the {@code /alpha/generate} variant
     * {@code {"success":false,"error":{…}}}. The HTTP status carried inside the body (if any) is
     * returned in {@link ApiError#status()}.</p>
     *
     * @param root parsed body, may be {@code null}
     * @return the extracted error, or {@code null} when the payload has no recognisable error
     */
    static ApiError error(JsonObject root) {
        if (root == null) {
            return null;
        }
        JsonElement raw = root.has("error") ? root.get("error") : null;
        String message = null;
        String code = null;
        Integer status = null;
        if (raw != null && raw.isJsonObject()) {
            JsonObject err = raw.getAsJsonObject();
            message = string(err, "message", "detail", "msg");
            code = string(err, "code", "type", "error_code");
            status = integer(err, "status", "statusCode", "status_code");
        } else if (raw != null && raw.isJsonPrimitive() && raw.getAsJsonPrimitive().isString()) {
            message = raw.getAsString();
        }
        if (message == null) {
            message = string(root, "message", "detail", "msg", "error_description");
        }
        if (code == null) {
            code = string(root, "code", "type", "error_code");
        }
        if (status == null) {
            status = integer(root, "status", "statusCode");
        }
        if (message == null && code == null && status == null) {
            return null;
        }
        return new ApiError(message, code, status == null ? 0 : status);
    }

    /**
     * Truncates a string for inclusion in an exception message / log line.
     *
     * @param s      input, may be {@code null}
     * @param maxLen maximum length including the ellipsis
     * @return a single-line, truncated rendering (never {@code null})
     */
    static String oneLine(String s, int maxLen) {
        if (s == null) {
            return "";
        }
        String cleaned = s.replace('\r', ' ').replace('\n', ' ').strip();
        if (cleaned.length() <= maxLen) {
            return cleaned;
        }
        return cleaned.substring(0, Math.max(0, maxLen - 1)) + "…";
    }

    /**
     * Minimal representation of a provider error payload.
     *
     * @param message provider text, may be {@code null}
     * @param code    provider code, may be {@code null}
     * @param status  HTTP status carried in the body, or {@code 0} when absent
     */
    record ApiError(String message, String code, int status) {
    }
}
