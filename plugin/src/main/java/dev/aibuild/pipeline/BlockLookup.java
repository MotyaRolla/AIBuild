package dev.aibuild.pipeline;

import dev.aibuild.analysis.IsoRenderer;
import dev.aibuild.model.BlockInfo;
import dev.aibuild.model.BlockSpec;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Block ids on demand: the studio's answer to a ```blocks query. Each valid id is described by its name, its kind
 * (wood, stone, glass...) and its colour as the renderer draws it, so a model that cannot see textures can still pick
 * blocks by how they look ("pink", "dark wood", "white stone") instead of by name alone.
 */
public final class BlockLookup {
    private BlockLookup() {
    }

    static final int MAX = 60;

    private static final String[] SHAPES = {"_stairs", "_slab", "_wall", "_fence", "_fence_gate", "_trapdoor", "_door", "_button",
            "_pressure_plate", "_pane", "_carpet", "_sign", "_hanging_sign", "_wall_sign", "_wall_hanging_sign", "_banner", "_wall_banner",
            "_bed", "_candle", "_shelf", "_bars", "_chain"};

    /** The ids matching every word of {@code query} (in the name, the kind or the colour), with their descriptions. */
    public static String lookup(String query, BlockInfo info) {
        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return "blocks: give words to look for, e.g. `pink`, `dark wood`, `_stairs`.";
        String[] words = q.split("[\\s,]+");
        Set<String> names = new LinkedHashSet<>(info.blockNames());
        List<String> hits = new ArrayList<>();
        for (String n : names) {
            if (n.equals("air") || n.endsWith("_wall_sign") || n.endsWith("_wall_banner") || n.endsWith("_wall_hanging_sign")
                    || n.startsWith("potted_") || n.contains("_wall_head") || n.startsWith("infested_")) continue;
            String desc = (n + " " + kind(n) + " " + colourWords(n, info)).toLowerCase(Locale.ROOT);
            boolean all = true;
            for (String w : words) {
                if (!desc.contains(w)) {
                    all = false;
                    break;
                }
            }
            if (all) hits.add(n);
        }
        StringBuilder sb = new StringBuilder("BLOCKS matching '").append(q).append("'");
        if (hits.isEmpty()) {
            return sb.append(": none. Try fewer or other words (a colour, a kind such as wood/stone/glass, or part of a name).").toString();
        }
        sb.append(" (").append(hits.size()).append(hits.size() > MAX ? ", the first " + MAX + " shown; add a word to narrow it" : "").append("):\n");
        for (String n : hits.subList(0, Math.min(MAX, hits.size()))) {
            sb.append("- ").append(n).append(": ").append(colourWords(n, info));
            String forms = forms(n, names);
            if (!forms.isEmpty()) sb.append("; forms: ").append(forms);
            sb.append('\n');
        }
        return sb.toString();
    }

    /** The shaped forms of a full block that exist (oak_planks -> oak_stairs, oak_slab...). */
    static String forms(String n, Set<String> names) {
        for (String s : SHAPES) if (n.endsWith(s)) return "";
        List<String> stems = new ArrayList<>();
        stems.add(n);
        for (String suf : new String[]{"_planks", "_block", "s"}) {
            if (n.endsWith(suf) && n.length() > suf.length()) stems.add(n.substring(0, n.length() - suf.length()));
        }
        List<String> out = new ArrayList<>();
        for (String shape : new String[]{"_stairs", "_slab", "_wall", "_fence", "_trapdoor", "_door", "_pane"}) {
            for (String st : stems) {
                if (names.contains(st + shape)) {
                    out.add(st + shape);
                    break;
                }
            }
        }
        return String.join(", ", out);
    }

    /** A rough material kind for searching: wood, stone, glass, metal, plant... */
    static String kind(String n) {
        if (n.matches(".*(_planks|_log|_wood|_stem|_hyphae|bamboo_block|bamboo_mosaic).*") || n.startsWith("stripped_")) return "wood";
        if (n.contains("glass")) return "glass";
        if (n.contains("wool") || n.contains("carpet")) return "wool cloth";
        if (n.contains("concrete")) return "concrete";
        if (n.contains("terracotta")) return "terracotta clay";
        if (n.contains("leaves")) return "leaves plant";
        if (n.matches(".*(copper|iron|gold|netherite|chain|bars|anvil|lantern).*")) return "metal";
        if (n.matches(".*(stone|brick|deepslate|andesite|diorite|granite|tuff|basalt|blackstone|cobble|quartz|sandstone|prismarine|purpur|calcite|obsidian|tiles).*")) return "stone";
        if (n.matches(".*(dirt|grass_block|podzol|mud|sand|gravel|clay|moss_block|mycelium|soil).*")) return "ground earth";
        if (n.matches(".*(flower|tulip|rose|daisy|orchid|allium|bluet|poppy|dandelion|lily|petals|fern|grass|vine|bush|sapling|mushroom|azalea|roots|kelp|seagrass).*")) return "plant";
        return "";
    }

    /** The block's colour as the renderer draws it, in words and hex. */
    static String colourWords(String n, BlockInfo info) {
        int rgb;
        try {
            BlockSpec s = info.parse(n);
            rgb = IsoRenderer.colourOf(s, info);
        } catch (RuntimeException e) {
            return "";
        }
        return name(rgb) + String.format(" #%06X", rgb & 0xFFFFFF);
    }

    /** A colour in plain words: lightness, saturation and hue ("dark muted green", "light pink", "near-white"). */
    static String name(int rgb) {
        float r = ((rgb >> 16) & 255) / 255f, g = ((rgb >> 8) & 255) / 255f, b = (rgb & 255) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        float l = (max + min) / 2, d = max - min;
        float sat = d == 0 ? 0 : d / (1 - Math.abs(2 * l - 1));
        float h = 0;
        if (d != 0) {
            if (max == r) h = 60 * (((g - b) / d) % 6);
            else if (max == g) h = 60 * ((b - r) / d + 2);
            else h = 60 * ((r - g) / d + 4);
        }
        if (h < 0) h += 360;
        String light = l < 0.18 ? "very dark" : l < 0.35 ? "dark" : l > 0.82 ? "very light" : l > 0.65 ? "light" : "";
        if (sat < 0.12 || d < 0.06) {
            String grey = l < 0.12 ? "black" : l > 0.88 ? "white" : l > 0.7 ? "light gray" : l < 0.3 ? "dark gray" : "gray";
            return grey;
        }
        String hue;
        if (h < 15 || h >= 345) hue = "red";
        else if (h < 40) hue = l < 0.5 ? "brown" : "orange";
        else if (h < 65) hue = l < 0.42 ? "brown olive" : "yellow";
        else if (h < 90) hue = "yellow-green";
        else if (h < 150) hue = "green";
        else if (h < 190) hue = "teal cyan";
        else if (h < 250) hue = "blue";
        else if (h < 290) hue = "purple";
        else if (h < 330) hue = "magenta";
        else hue = "pink";
        // pale reds and magentas are what a builder calls pink
        if ((hue.equals("red") || hue.equals("magenta")) && l > 0.62) hue = "pink";
        if (hue.equals("orange") && sat < 0.45 && l > 0.55) hue = "tan beige";
        String muted = sat < 0.3 ? "muted " : "";
        return (light.isEmpty() ? "" : light + " ") + muted + hue;
    }
}
