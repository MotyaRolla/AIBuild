package dev.aibuild.model;

import java.util.List;

/**
 * Knowledge about blocks: validation/canonicalization and physical properties.
 *
 * <p>At runtime the implementation is {@code dev.aibuild.bukkit.BukkitBlockInfo} (backed by the server's
 * block registry). For offline unit tests use {@link StubBlockInfo} (heuristic, name based).</p>
 *
 * <p>All methods must be thread-safe: they are called from async worker threads.</p>
 */
public interface BlockInfo {

    /**
     * Validates and canonicalizes a Minecraft block-state string such as
     * {@code "minecraft:oak_stairs[facing=east,half=top]"} or {@code "stone"}.
     * Applies aliases for renamed blocks (e.g. {@code chain -> iron_chain}, {@code grass -> short_grass}).
     * The returned spec omits properties that equal the block's default value.
     *
     * @throws IllegalArgumentException with a human/LLM-readable message (unknown block with suggestions,
     *                                  unknown property, invalid value with allowed values, non-block item...)
     */
    BlockSpec parse(String raw) throws IllegalArgumentException;

    /** Returns the canonical spec with property {@code key} set to {@code value}. Throws IAE if invalid. */
    BlockSpec with(BlockSpec spec, String key, String value) throws IllegalArgumentException;

    /** Canonicalizes an already-valid spec (strips default-valued props). */
    BlockSpec canonical(BlockSpec spec);

    /** True if the block type has this state property (e.g. "facing", "axis", "waterlogged"). */
    boolean hasProperty(String blockName, String property);

    /** Value of the property: explicit value from spec, else the block default; null if no such property. */
    String getProp(BlockSpec spec, String property);

    boolean isAir(BlockSpec spec);

    /** Has a collision box (Material#isSolid): walls, slabs, stairs, fences, glass... but not torches/flowers/carpet. */
    boolean isSolid(BlockSpec spec);

    /** Full opaque cube that blocks light and vision (BlockData#isOccluding). */
    boolean isOccluding(BlockSpec spec);

    /** True if the given face is a full sturdy face (can hold wall torches, ladders, signs...). */
    boolean isSturdyFace(BlockSpec spec, Dir face);

    boolean hasGravity(BlockSpec spec);

    /** Emitted block light level 0..15. */
    int lightEmission(BlockSpec spec);

    /** Water / lava / bubble column (not waterlogged blocks). */
    boolean isLiquid(BlockSpec spec);

    /** Approximate color 0xRRGGBB for previews. */
    int mapColor(BlockSpec spec);

    /** Rotates directional state (facing, axis, rotation, shape, connections...) clockwise seen from above. */
    BlockSpec rotate(BlockSpec spec, int clockwiseQuarterTurns);

    /**
     * Mirrors directional state. axis 'x' = flip along the X axis (east &lt;-&gt; west),
     * axis 'z' = flip along the Z axis (north &lt;-&gt; south).
     */
    BlockSpec mirror(BlockSpec spec, char axis);

    /** All placeable block names (no namespace), sorted. Used for prompts. */
    List<String> blockNames();
}
