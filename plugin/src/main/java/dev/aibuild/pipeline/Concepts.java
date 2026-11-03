package dev.aibuild.pipeline;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.aibuild.util.Json;

import java.util.ArrayList;
import java.util.List;

/**
 * Design concepts written before the parallel script candidates: one short, concrete brief per candidate, so the
 * candidates explore different layouts of the same request instead of sampling one idea twice.
 */
public final class Concepts {
    private Concepts() {
    }

    /** One concept: a short name in the user's language and an English brief for the script writer. */
    public record Concept(String name, String brief) {
        public String forPrompt() {
            return name + ": " + brief;
        }
    }

    /** The concepts in {@code answer} ({"concepts":[{name, brief}]}), at most {@code max}; empty when unreadable. */
    public static List<Concept> parse(String answer, int max) {
        List<Concept> out = new ArrayList<>();
        try {
            JsonElement el = Json.extract(answer).value();
            if (!el.isJsonObject() || !el.getAsJsonObject().has("concepts")) return out;
            for (JsonElement e : el.getAsJsonObject().getAsJsonArray("concepts")) {
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                String brief = Json.str(o, "brief", "").strip();
                if (brief.length() < 20) continue;
                String name = Json.str(o, "name", "").strip();
                out.add(new Concept(name.isEmpty() ? "concept " + (out.size() + 1) : name, brief));
                if (out.size() >= max) break;
            }
        } catch (RuntimeException ignored) {
            // an unreadable answer means no concepts: the candidates get the plain request
        }
        return out;
    }
}
