package dev.aibuild.world;

/**
 * Packs world block coordinates into a single {@code long} exactly like Minecraft's
 * {@code net.minecraft.core.BlockPos#asLong(int, int, int)}:
 *
 * <pre>
 *   bits 63..38 : x (26 bit, sign extended on unpack)
 *   bits 37..12 : z (26 bit, sign extended on unpack)
 *   bits 11.. 0 : y (12 bit, sign extended on unpack)
 * </pre>
 *
 * <p>The packing is deliberately identical to the vanilla format so keys can be exchanged with
 * {@code org.bukkit.block.Block#getBlockKey()} / {@code World#getBlockAtKey(long)} without conversion.</p>
 *
 * <p>Valid coordinate ranges are x,z in {@code [-33554432, 33554431]} and y in {@code [-2048, 2047]},
 * which comfortably covers every vanilla dimension (including the -64..319 overworld range) and the
 * full build height of custom datapacks. Values outside the range are silently truncated like vanilla
 * does; callers that need strictness should validate before packing.</p>
 *
 * <p>Stateless and thread-safe.</p>
 */
public final class WorldKey {

    /** Smallest representable x/z coordinate. */
    public static final int MIN_HORIZONTAL = -(1 << 25);
    /** Largest representable x/z coordinate. */
    public static final int MAX_HORIZONTAL = (1 << 25) - 1;
    /** Smallest representable y coordinate. */
    public static final int MIN_Y = -(1 << 11);
    /** Largest representable y coordinate. */
    public static final int MAX_Y = (1 << 11) - 1;

    private static final long X_MASK = 0x3FFFFFFL;
    private static final long Z_MASK = 0x3FFFFFFL;
    private static final long Y_MASK = 0xFFFL;

    private WorldKey() {
    }

    /**
     * Packs a block position into a long.
     *
     * @param x block x coordinate (26 bit signed range)
     * @param y block y coordinate (12 bit signed range)
     * @param z block z coordinate (26 bit signed range)
     * @return the packed key
     */
    public static long pack(int x, int y, int z) {
        return ((long) x & X_MASK) << 38 | ((long) z & Z_MASK) << 12 | ((long) y & Y_MASK);
    }

    /** Packs the block position of {@code block}. Equivalent to {@code block.getBlockKey()}. */
    public static long pack(org.bukkit.block.Block block) {
        return pack(block.getX(), block.getY(), block.getZ());
    }

    /** Unpacks the x coordinate (sign extended). */
    public static int x(long packed) {
        return (int) (packed >> 38);
    }

    /** Unpacks the y coordinate (sign extended). */
    public static int y(long packed) {
        return (int) (packed << 52 >> 52);
    }

    /** Unpacks the z coordinate (sign extended). */
    public static int z(long packed) {
        return (int) (packed << 26 >> 38);
    }

    /** Returns {@code true} when the coordinate fits into the 26/12/12 bit block position encoding. */
    public static boolean isRepresentable(int x, int y, int z) {
        return x >= MIN_HORIZONTAL && x <= MAX_HORIZONTAL
                && z >= MIN_HORIZONTAL && z <= MAX_HORIZONTAL
                && y >= MIN_Y && y <= MAX_Y;
    }

    /** Human readable form, mostly for logging: {@code "world(12, 64, -7)"} style coordinates only. */
    public static String describe(long packed) {
        return x(packed) + "," + y(packed) + "," + z(packed);
    }
}
