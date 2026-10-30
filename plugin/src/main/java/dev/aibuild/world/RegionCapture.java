package dev.aibuild.world;

import dev.aibuild.model.BlockInfo;
import dev.aibuild.model.BlockSpec;
import dev.aibuild.model.VoxelModel;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

/**
 * Captures an existing region of a world into a {@link VoxelModel} so it can be edited by the AI pipeline
 * (the {@code /aib wand} + {@code /aib import} workflow).
 *
 * <p>Model space is produced as described in the {@link VoxelModel} contract: coordinates are relative to
 * the selection's minimum corner, so the selection's bottom layer becomes {@code y = 0} and the selection
 * front (its highest z) becomes the model front. Air (and other "empty" states) is not stored: unset cells
 * inside the bounds clear to air on placement, and unset cells below the bottom layer keep the terrain,
 * which is exactly what a captured building needs.</p>
 *
 * <p>All methods require the server main thread; they touch the world directly.</p>
 */
public final class RegionCapture {

    private RegionCapture() {
    }

    /**
     * Captures a world region into a new model.
     *
     * @param w    world to read (main thread)
     * @param x1   first corner x (any order)
     * @param y1   first corner y (any order)
     * @param z1   first corner z (any order)
     * @param x2   second corner x (any order)
     * @param y2   second corner y (any order)
     * @param z2   second corner z (any order)
     * @param info block registry used to canonicalize states; {@code null} falls back to the server's
     *             {@code Bukkit.createBlockData} parsing. When {@code info} cannot parse a state the raw
     *             material name is used as a last resort.
     * @return the captured model (possibly empty, never {@code null}); its bounds are the selection size
     *         minus empty cells, so a fully-air selection yields an empty model
     * @throws IllegalArgumentException when {@code w} is null
     */
    public static VoxelModel capture(World w, int x1, int y1, int z1, int x2, int y2, int z2, BlockInfo info) {
        if (w == null) throw new IllegalArgumentException("world is null");
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        VoxelModel model = new VoxelModel();
        // Load all chunks in the selection up front so the loop does no lazy loading per block.
        for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
            for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                try {
                    w.getChunkAt(cx, cz);
                } catch (RuntimeException ignored) {
                    // a broken chunk just yields air cells
                }
            }
        }
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    Block block;
                    try {
                        block = w.getBlockAt(x, y, z);
                    } catch (RuntimeException e) {
                        continue;
                    }
                    BlockData data;
                    try {
                        data = block.getBlockData();
                    } catch (RuntimeException e) {
                        continue;
                    }
                    if (isEmpty(data)) continue;
                    BlockSpec spec = toSpec(data, info);
                    if (spec == null || spec.isAir()) continue;
                    model.set(x - minX, y - minY, z - minZ, spec);
                }
            }
        }
        return model;
    }

    /**
     * Captures a region using the world's own block registry only ({@code info == null}).
     */
    public static VoxelModel capture(World w, int x1, int y1, int z1, int x2, int y2, int z2) {
        return capture(w, x1, y1, z1, x2, y2, z2, null);
    }

    /**
     * Converts world block data into a canonical spec.
     *
     * @param info registry to canonicalize with, or {@code null} to use the raw state string
     * @return a spec, or {@code null} when nothing usable could be produced
     */
    static BlockSpec toSpec(BlockData data, BlockInfo info) {
        if (data == null) return null;
        String state = null;
        try {
            state = data.getAsString();
        } catch (RuntimeException ignored) {
            // fall through to the material name
        }
        if (info != null) {
            if (state != null) {
                try {
                    return info.parse(state);
                } catch (RuntimeException ignored) {
                    // fall back to the material name below
                }
            }
            Material m = data.getMaterial();
            String name = m == null ? null : m.name().toLowerCase(java.util.Locale.ROOT);
            if (name != null) {
                try {
                    return info.parse(name);
                } catch (RuntimeException ignored) {
                    // fall through to the raw spec
                }
            }
        }
        try {
            return state != null ? BlockSpec.parseRaw(state) : BlockSpec.of(data.getMaterial().name());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** True for blocks that should not become explicit model cells. */
    private static boolean isEmpty(BlockData data) {
        if (data == null || WorldPlacer.isAirData(data)) return true;
        Material m;
        try {
            m = data.getMaterial();
        } catch (RuntimeException e) {
            return false;
        }
        return m == Material.WATER || m == Material.LAVA || m == Material.BUBBLE_COLUMN
                || m == Material.STRUCTURE_VOID || m == Material.LIGHT;
    }
}
