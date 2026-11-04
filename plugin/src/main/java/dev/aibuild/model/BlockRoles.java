package dev.aibuild.model;

import java.util.Set;

/** What a block does in a build: shell, circulation, furniture or loose decoration. */
public final class BlockRoles {
    private BlockRoles() {
    }

    /** Full blocks that are furniture rather than walls, though they are opaque. */
    public static final Set<String> FURNITURE_BLOCKS = Set.of("barrel", "bookshelf", "chiseled_bookshelf", "crafting_table",
            "furnace", "smoker", "blast_furnace", "loom", "cartography_table", "fletching_table", "smithing_table", "hay_block",
            "jukebox", "note_block", "beehive", "target", "pumpkin", "carved_pumpkin", "melon");

    /** Part of the shell: a full wall block (not furniture), or a window or door. */
    public static boolean structural(BlockSpec s, BlockInfo info) {
        if (s == null || s.isAir()) return false;
        String n = s.name();
        return info.isOccluding(s) && !FURNITURE_BLOCKS.contains(n) || n.contains("glass") || n.endsWith("_bars") || n.endsWith("_door");
    }

    /** The way through and up: stairs, ladders, doors, scaffolding. */
    public static boolean access(BlockSpec s) {
        if (s == null || s.isAir()) return false;
        String n = s.name();
        return n.endsWith("_stairs") || n.equals("ladder") || n.equals("scaffolding") || n.endsWith("_door");
    }

    /**
     * Loose decoration that never belongs inside a wall: lights, banners, signs, carpets, plants, leaves, candles,
     * pots, beds, chains. Shape blocks (stairs, slabs, walls, fences, panes, trapdoors) are trim, not decoration.
     */
    public static boolean decor(BlockSpec s, BlockInfo info) {
        if (s == null || s.isAir() || info.isOccluding(s)) return false;
        String n = s.name();
        if (n.endsWith("_stairs") || n.endsWith("_slab") || n.endsWith("_wall") || n.endsWith("_fence") || n.endsWith("_fence_gate")
                || n.contains("pane") || n.contains("glass") || n.endsWith("_bars") || n.endsWith("_door") || n.endsWith("_trapdoor")
                || n.equals("ladder") || n.equals("scaffolding") || n.equals("water") || n.equals("lava")) return false;
        return true;
    }

    /**
     * Whether writing {@code now} over {@code was} breaks a part's shell or way through: a wall, floor, window or
     * door emptied or covered with loose decoration, or a stair, ladder or door replaced by something else. Wood
     * and leaves giving way (a tree moved aside) and one wall block replacing another are not breakage.
     */
    public static boolean breaks(BlockSpec was, BlockSpec now, BlockInfo info) {
        if (was == null || was.isAir()) return false;
        if (access(was)) return !access(now);
        if (!structural(was, info)) return false;
        String n = was.name();
        if (n.endsWith("_leaves") || n.endsWith("_log") || n.endsWith("_wood")) return false;
        return now == null || now.isAir() || decor(now, info);
    }
}
