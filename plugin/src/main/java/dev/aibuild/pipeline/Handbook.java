package dev.aibuild.pipeline;

import dev.aibuild.script.Stdlib;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The builder's handbook ({@code stdlib/v<n>/handbook/*.md}): what a professional Minecraft builder knows (scale,
 * massing, wall depth, roofs, palettes, micro detail, trees, terrain, interiors, organic forms, review), one
 * chapter per topic with principles and short tested snippets. The model reads a chapter when it needs it, so the
 * knowledge costs no context until it is used.
 */
public final class Handbook {
    /** Topic -> one-line summary, in reading order. */
    public static final Map<String, String> TOPICS = new LinkedHashMap<>();

    static {
        TOPICS.put("scale", "the grid in metres: doors, storeys, ceilings, building/tower/tree/statue sizes");
        TOPICS.put("massing", "silhouette first: primary/secondary/tertiary masses, rhythm, focal point");
        TOPICS.put("walls", "three depth layers: frame, infill, openings; plinth, cornice, half-timber");
        TOPICS.put("roofs", "pitch, overhang, eave lips, mixed roof blocks, ridges, dormers, chimneys");
        TOPICS.put("palette", "60/30/10 roles, gradients dark-to-light, texture, style palettes");
        TOPICS.put("micro", "the 1-block vocabulary: stairs, slabs, walls, trapdoors, lanterns; what reads at what distance");
        TOPICS.put("trees", "trunk, roots, branches, leaf clusters; sakura, willow, conifer, hero trees");
        TOPICS.put("terrain", "ground layers, paths, shores and water, rocks, plants, framing the subject");
        TOPICS.put("interiors", "rooms with a purpose, walking space, furniture from blocks, light");
        TOPICS.put("organic", "statues and creatures: armature, proportions, pose, shading, rocks");
        TOPICS.put("review", "how a professional checks a build: views, typical faults, when to stop");
    }

    private Handbook() {
    }

    /** The table of contents for a prompt: one line per chapter. */
    public static String contents() {
        StringBuilder sb = new StringBuilder("BUILDER'S HANDBOOK (read a chapter with a ```guide block naming the topic):\n");
        TOPICS.forEach((k, v) -> sb.append("- ").append(k).append(": ").append(v).append('\n'));
        return sb.toString();
    }

    /** One chapter's text, or null when there is no such topic. */
    public static String chapter(Stdlib lib, String topic) {
        String t = topic == null ? "" : topic.strip().toLowerCase(Locale.ROOT);
        if (!TOPICS.containsKey(t)) return null;
        String text = lib.optional("handbook/" + t + ".md");
        return text == null ? null : text.strip();
    }

    private static final Pattern SNIPPET = Pattern.compile("```js\\n(.*?)```", Pattern.DOTALL);

    /** The code snippets of a chapter (for tests: every snippet must run). */
    public static List<String> snippets(String chapter) {
        List<String> out = new ArrayList<>();
        if (chapter == null) return out;
        Matcher m = SNIPPET.matcher(chapter);
        while (m.find()) out.add(m.group(1));
        return out;
    }
}
