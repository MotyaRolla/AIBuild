package dev.aibuild.analysis;

import dev.aibuild.model.BlockSpec;

import java.util.Set;

/**
 * Name-based block classification used by {@link StructureAnalyzer} and the renderers.
 *
 * <p>Deliberately conservative: everything here is a heuristic over the canonical block name and a few
 * state properties (read through a {@code BlockInfo} when the caller has one). Unknown blocks classify as
 * "ordinary solid" so that a malformed or modded block name can never produce an invalid analysis — at worst it
 * is analysed as a plain cube. All methods are pure/thread-safe.</p>
 */
final class Names {

    private Names() {
    }

    // ------------------------------------------------------------------ families

    static final Set<String> WOODS = Set.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove",
            "cherry", "bamboo", "crimson", "warped", "pale_oak");

    /** Plants that must stand on a block. */
    static final Set<String> FLOOR_PLANTS = Set.of("dandelion", "poppy", "blue_orchid", "allium",
            "azure_bluet", "red_tulip", "orange_tulip", "white_tulip", "pink_tulip", "oxeye_daisy", "cornflower",
            "lily_of_the_valley", "wither_rose", "torchflower", "short_grass", "fern", "dead_bush", "bush",
            "firefly_bush", "sweet_berry_bush", "sugar_cane", "wheat", "carrots", "potatoes", "beetroots",
            "nether_wart", "pumpkin_stem", "melon_stem", "cactus", "bamboo", "pink_petals", "wildflowers",
            "leaf_litter", "dripleaf", "small_dripleaf", "sea_pickle", "kelp", "tall_grass", "large_fern",
            "sunflower", "lilac", "rose_bush", "peony", "pitcher_plant", "crimson_roots", "warped_roots",
            "nether_sprouts", "chorus_flower", "brown_mushroom", "red_mushroom", "crimson_fungus", "warped_fungus",
            "lily_pad", "moss_carpet", "pale_hanging_moss");

    private static final Set<String> DECORATIVE_HINTS = Set.of("lantern", "flower_pot", "potted_", "carpet",
            "barrel", "chest", "bookshelf", "sign", "banner", "candle", "trapdoor", "button", "lever", "poppy",
            "dandelion", "torch", "chain", "campfire", "cauldron", "pot", "skull", "head", "painting", "item_frame",
            "armor_stand", "composter", "lectern", "grindstone", "bell", "anvil", "loom", "stonecutter", "brewing",
            "decorated_pot", "vase", "cobweb", "ladder", "scaffolding", "vine", "lichen", "moss", "wreath", "garland",
            "candle_cake", "cake", "hay_block", "spruce", "window", "curtain", "rug", "table");

    /** Props that make a block "oriented" for the detail metric. */
    static final Set<String> ORIENTED_PROPS = Set.of("facing", "axis", "half", "type", "rotation", "face",
            "hinge", "orientation", "part", "attach", "vertical_direction", "up");

    // ------------------------------------------------------------------ basic predicates

    static boolean isAir(BlockSpec s) {
        return s == null || s.isAir();
    }

    static boolean isGlassLike(String n) {
        return n.equals("glass") || n.endsWith("_glass") || n.equals("glass_pane") || n.endsWith("_glass_pane")
                || n.equals("tinted_glass");
    }

    static boolean isPaneLike(String n) {
        return n.equals("glass_pane") || n.endsWith("_glass_pane") || n.equals("iron_bars") || n.endsWith("_bars");
    }

    static boolean isCarpet(BlockSpec s) {
        if (s == null) return false;
        String n = s.name();
        return n.endsWith("_carpet") || n.equals("moss_carpet") || n.equals("snow") || n.equals("pale_moss_carpet");
    }

    /** Water, lava, bubble column. */
    static boolean isLiquidName(String n) {
        return n.equals("water") || n.equals("lava") || n.equals("bubble_column");
    }

    /** Thin blocks that read as "detail" rather than mass (for the detail score). */
    static boolean isDetailish(BlockSpec s) {
        if (s == null || s.isAir()) return false;
        String n = s.name();
        if (n.contains("torch") || n.contains("lantern") || n.equals("iron_chain") || n.contains("candle")
                || n.endsWith("_sign") || n.endsWith("_banner") || n.endsWith("_button") || n.equals("lever")
                || n.endsWith("_pressure_plate") || n.endsWith("_carpet") || n.endsWith("_trapdoor")
                || n.endsWith("_fence") || n.endsWith("_fence_gate") || isPaneLike(n) || n.equals("ladder")
                || n.endsWith("_pot") || n.startsWith("potted_") || n.endsWith("_head") || n.endsWith("_skull")
                || n.equals("campfire") || n.equals("soul_campfire") || n.equals("cauldron") || n.equals("barrel")
                || n.equals("chest") || n.equals("trapped_chest") || n.equals("bookshelf") || n.equals("lectern")
                || n.equals("grindstone") || n.equals("bell") || n.equals("anvil") || n.equals("loom")
                || n.equals("stonecutter") || n.equals("composter") || n.equals("brewing_stand")
                || n.equals("decorated_pot") || n.equals("scaffolding") || n.equals("cobweb") || n.equals("vine")
                || n.endsWith("_vines") || n.equals("glow_lichen") || n.endsWith("_leaves") || n.endsWith("_bed")
                || n.equals("item_frame") || n.equals("painting") || n.equals("armor_stand") || n.equals("candle_cake")
                || n.equals("cake") || n.equals("hay_block")) {
            return true;
        }
        return DECORATIVE_HINTS.stream().anyMatch(n::contains);
    }

    // ------------------------------------------------------------------ facing helpers

}
