package dev.aibuild.script;

import dev.aibuild.analysis.IsoRenderer;
import dev.aibuild.model.BlockInfo;
import dev.aibuild.model.BlockSpec;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Dark-to-light block ramps of one hue for sculptural shading, chosen from real block colours (client textures
 * when loaded) among full, non-glowing, non-falling building blocks.
 */
final class Ramps {
    private Ramps() {
    }

    /** Full-cube building blocks that read well on large surfaces (no ores, glowing or falling blocks). */
    static final List<String> CANDIDATES = List.of(
            "white_concrete", "light_gray_concrete", "gray_concrete", "black_concrete", "brown_concrete", "red_concrete",
            "orange_concrete", "yellow_concrete", "lime_concrete", "green_concrete", "cyan_concrete", "light_blue_concrete",
            "blue_concrete", "purple_concrete", "magenta_concrete", "pink_concrete",
            "white_wool", "light_gray_wool", "gray_wool", "black_wool", "brown_wool", "red_wool", "orange_wool", "yellow_wool",
            "lime_wool", "green_wool", "cyan_wool", "light_blue_wool", "blue_wool", "purple_wool", "magenta_wool", "pink_wool",
            "terracotta", "white_terracotta", "light_gray_terracotta", "gray_terracotta", "black_terracotta", "brown_terracotta",
            "red_terracotta", "orange_terracotta", "yellow_terracotta", "lime_terracotta", "green_terracotta", "cyan_terracotta",
            "light_blue_terracotta", "blue_terracotta", "purple_terracotta", "magenta_terracotta", "pink_terracotta",
            "stone", "smooth_stone", "andesite", "polished_andesite", "diorite", "polished_diorite", "granite", "polished_granite",
            "cobblestone", "stone_bricks", "tuff", "polished_tuff", "tuff_bricks", "calcite", "deepslate", "cobbled_deepslate",
            "polished_deepslate", "deepslate_bricks", "deepslate_tiles", "blackstone", "polished_blackstone", "polished_blackstone_bricks",
            "basalt", "smooth_basalt", "sandstone", "smooth_sandstone", "red_sandstone", "smooth_red_sandstone", "end_stone",
            "end_stone_bricks", "purpur_block", "prismarine", "prismarine_bricks", "dark_prismarine", "quartz_block", "bricks",
            "mud_bricks", "packed_mud", "nether_bricks", "red_nether_bricks", "netherrack", "mossy_cobblestone", "mossy_stone_bricks",
            "oak_planks", "spruce_planks", "birch_planks", "jungle_planks", "acacia_planks", "dark_oak_planks", "mangrove_planks",
            "cherry_planks", "pale_oak_planks", "bamboo_planks", "crimson_planks", "warped_planks",
            "stripped_oak_log", "stripped_spruce_log", "stripped_birch_log", "stripped_dark_oak_log", "stripped_cherry_log",
            "stripped_acacia_log", "stripped_jungle_log", "stripped_mangrove_log", "stripped_pale_oak_log",
            "dirt", "coarse_dirt", "rooted_dirt", "mud", "clay", "moss_block", "pale_moss_block", "snow_block", "packed_ice",
            "blue_ice", "hay_block", "dried_kelp_block", "nether_wart_block", "warped_wart_block", "honeycomb_block",
            "copper_block", "exposed_copper", "weathered_copper", "oxidized_copper", "resin_bricks", "iron_block", "gold_block",
            "raw_iron_block", "raw_copper_block", "raw_gold_block", "lapis_block", "emerald_block", "diamond_block", "coal_block",
            "obsidian", "crying_obsidian", "amethyst_block", "bone_block", "mushroom_stem", "brown_mushroom_block", "red_mushroom_block");

    /**
     * {@code n} blocks of the hue of {@code base} (a block id or "#rrggbb"), darkest first. {@code spread} (0..1)
     * sets the lightness range around the base: 0 = ±8 L*, 1 = ±34 L* (capped by what the hue offers).
     */
    static List<String> ramp(String base, int n, double spread, BlockInfo info) {
        n = Math.clamp(n, 2, 8);
        double[] lab0 = lab(colour(base, info));
        record C(String name, double[] lab) {
        }
        List<C> all = new ArrayList<>();
        for (String name : CANDIDATES) {
            try {
                BlockSpec s = info.parse(name);
                all.add(new C(s.name(), lab(IsoRenderer.colourOf(s, info))));
            } catch (RuntimeException ignored) {
                // block missing in this version
            }
        }
        // keep one texture family (flat wool/concrete/terracotta, wood, stone) so the shading reads as one surface
        String fam = base.startsWith("#") ? "flat" : family(info.parse(base).name());
        List<C> same = all.stream().filter(c -> family(c.name).equals(fam)).toList();
        if (same.size() >= n + 2) all = new ArrayList<>(same);
        boolean grey = Math.hypot(lab0[1], lab0[2]) < 9;
        List<C> near = new ArrayList<>();
        for (double tol = 7; tol <= 40 && near.size() < n + 1; tol += 5) {
            near.clear();
            for (C c : all) {
                double chroma = Math.hypot(c.lab[1], c.lab[2]);
                double hueGap = Math.abs(Math.atan2(c.lab[2], c.lab[1]) - Math.atan2(lab0[2], lab0[1]));
                hueGap = Math.min(hueGap, 2 * Math.PI - hueGap);
                double d = grey ? chroma : Math.hypot(c.lab[1] - lab0[1], c.lab[2] - lab0[2]) * 0.6 + hueGap * 30;
                if (d < tol) near.add(c);
            }
        }
        near.sort(Comparator.comparingDouble(c -> c.lab[0]));
        if (near.isEmpty()) return List.of(base, base);
        double lo = near.getFirst().lab[0], hi = near.getLast().lab[0];
        double half = 8 + 26 * Math.clamp(spread, 0, 1);
        double from = Math.max(lo, lab0[0] - half), to = Math.min(hi, lab0[0] + half);
        Set<String> out = new LinkedHashSet<>();
        for (int i = 0; i < n; i++) {
            double target = n == 1 ? lab0[0] : from + (to - from) * i / (n - 1);
            C best = null;
            for (C c : near) if (!out.contains(c.name) && (best == null || Math.abs(c.lab[0] - target) < Math.abs(best.lab[0] - target))) best = c;
            if (best != null) out.add(best.name);
        }
        List<String> list = new ArrayList<>(out);
        list.sort(Comparator.comparingDouble(name -> near.stream().filter(c -> c.name.equals(name)).findFirst().orElseThrow().lab[0]));
        if (list.size() == 1) list.add(list.getFirst());
        return list;
    }

    private static String family(String n) {
        if (n.endsWith("_wool") || n.endsWith("_concrete") || n.endsWith("terracotta")) return "flat";
        if (n.endsWith("_planks") || n.endsWith("_log") || n.endsWith("_wood")) return "wood";
        return "stone";
    }

    private static int colour(String base, BlockInfo info) {
        if (base.startsWith("#") && base.length() == 7) return Integer.parseInt(base.substring(1), 16);
        return IsoRenderer.colourOf(info.parse(base), info);
    }

    /** sRGB → CIELAB (D65). */
    static double[] lab(int rgb) {
        double r = lin(((rgb >> 16) & 255) / 255.0), g = lin(((rgb >> 8) & 255) / 255.0), b = lin((rgb & 255) / 255.0);
        double x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047, y = 0.2126 * r + 0.7152 * g + 0.0722 * b,
                z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883;
        double fx = f(x), fy = f(y), fz = f(z);
        return new double[]{116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)};
    }

    private static double lin(double c) {
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static double f(double t) {
        return t > 0.008856 ? Math.cbrt(t) : 7.787 * t + 16.0 / 116;
    }
}
