package dev.aibuild.world;

import dev.aibuild.build.Build;
import org.bukkit.block.BlockFace;
import org.bukkit.block.structure.StructureRotation;

/**
 * Pure geometry for placing a model into the world.
 *
 * <h2>Coordinate conventions</h2>
 * Model space: +x = east, +y = up, +z = south; the model's front (main entrance) faces <b>south (+z)</b>.
 * World space uses the same axis orientation, so no mirroring is ever needed — only a clockwise rotation
 * around the y axis.
 *
 * <h2>Rotation</h2>
 * A {@code rotation} of {@code n} means {@code n} clockwise quarter turns seen from above, matching
 * Minecraft's {@code StructureRotation}. The mapping used here is the vanilla one:
 * <pre>
 *   0 NONE              : ( x,  z)
 *   1 CLOCKWISE_90      : (-z,  x)
 *   2 CLOCKWISE_180     : (-x, -z)
 *   3 COUNTERCLOCKWISE_90: ( z, -x)
 * </pre>
 * The model front points south at rotation 0 and, after {@code n} clockwise quarter turns, to
 * <b>south, west, north, east</b> for {@code n = 0..3}. A build is placed in front of the player, i.e. the
 * model front has to face the player, so the player's facing maps to the <em>opposite</em> front direction:
 * player facing north → model front south → rotation 0, east → 1, south → 2, west → 3
 * (see {@link #rotationForPlayerFacing(BlockFace)}).
 *
 * <p>All methods are pure functions; there is no Bukkit state involved except the enum conversions.</p>
 */
public final class PlacementMath {

    private PlacementMath() {
    }

    /**
     * Rotates a model-space x/z offset by {@code rotation} clockwise quarter turns.
     *
     * @param x        model x offset (relative to the model origin)
     * @param z        model z offset
     * @param rotation 0..3; other values are wrapped modulo 4
     * @return {@code {rotatedX, rotatedZ}} (a fresh array)
     */
    public static int[] rotate(int x, int z, int rotation) {
        return switch (norm(rotation)) {
            case 0 -> new int[]{x, z};
            case 1 -> new int[]{-z, x};
            case 2 -> new int[]{-x, -z};
            default -> new int[]{z, -x};
        };
    }

    /**
     * Rotates a single (non-offset) direction vector, e.g. the model's front normal {@code (0, 1)}.
     * Identical to {@link #rotate(int, int, int)} but semantically clearer at call sites that pass a
     * direction rather than a position.
     */
    public static int[] rotateDirection(int dx, int dz, int rotation) {
        return rotate(dx, dz, rotation);
    }

    /** Normalizes a rotation value into {@code 0..3}. */
    public static int norm(int rotation) {
        return Math.floorMod(rotation, 4);
    }

    /** Bukkit equivalent of a rotation index (never {@code null}). */
    public static StructureRotation toStructureRotation(int rot) {
        return switch (norm(rot)) {
            case 0 -> StructureRotation.NONE;
            case 1 -> StructureRotation.CLOCKWISE_90;
            case 2 -> StructureRotation.CLOCKWISE_180;
            default -> StructureRotation.COUNTERCLOCKWISE_90;
        };
    }

    /**
     * Rotation index for a Bukkit rotation enum (inverse of {@link #toStructureRotation(int)}).
     *
     * @throws IllegalArgumentException when {@code rotation} is null
     */
    public static int fromStructureRotation(StructureRotation rotation) {
        if (rotation == null) throw new IllegalArgumentException("rotation is null");
        return switch (rotation) {
            case NONE -> 0;
            case CLOCKWISE_90 -> 1;
            case CLOCKWISE_180 -> 2;
            case COUNTERCLOCKWISE_90 -> 3;
        };
    }

    /**
     * The closest cardinal of any block face, computed from the face's integer direction vector so that
     * near-cardinals such as {@code WEST_NORTH_WEST} resolve to their dominant axis rather than to a
     * hand-written guess.
     *
     * <p>Exact diagonals (45°, e.g. {@code SOUTH_EAST}) have no unique closest cardinal; the tie is broken
     * towards the z axis ({@code NORTH_EAST}/{@code NORTH_WEST} → {@code NORTH},
     * {@code SOUTH_EAST}/{@code SOUTH_WEST} → {@code SOUTH}). Vertical faces ({@code UP}, {@code DOWN}) and
     * {@link BlockFace#SELF} have no horizontal component and map to {@link BlockFace#NORTH} so callers always
     * receive a horizontal direction.</p>
     */
    public static BlockFace cardinal(BlockFace face) {
        if (face == null) return BlockFace.NORTH;
        int dx = face.getModX();
        int dz = face.getModZ();
        if (dx == 0 && dz == 0) return BlockFace.NORTH;
        int adx = Math.abs(dx), adz = Math.abs(dz);
        if (adx > adz) return dx > 0 ? BlockFace.EAST : BlockFace.WEST;
        if (adz > adx) return dz > 0 ? BlockFace.SOUTH : BlockFace.NORTH;
        // exact diagonal: deterministic tie-break towards the z axis
        return dz > 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    /**
     * Closest cardinal of a yaw angle in degrees (Minecraft convention: 0 = south, 90 = west, 180 = north,
     * 270 = east, i.e. yaw grows clockwise when seen from above with north at -z).
     * A player looking at the model is {@code facing = cardinal(yaw)}, and the model must face the player.
     */
    public static BlockFace cardinal(float yaw) {
        return cardinal(yawToFacing(yaw));
    }

    /**
     * Converts a yaw (degrees, any value, Minecraft convention) to the cardinal direction the entity looks
     * along. 0 → south, 90 → west, ±180 → north, 270/-90 → east.
     */
    public static BlockFace yawToFacing(float yaw) {
        if (Float.isNaN(yaw) || Float.isInfinite(yaw)) return BlockFace.SOUTH;
        float y = yaw % 360.0f;
        if (y < 0) y += 360.0f;
        if (y >= 315 || y < 45) return BlockFace.SOUTH;
        if (y < 135) return BlockFace.WEST;
        if (y < 225) return BlockFace.NORTH;
        return BlockFace.EAST;
    }

    /**
     * Rotation that makes the model front face the player standing with the given facing.
     * The model front is south at rotation 0, so: player facing NORTH → 0, EAST → 1, SOUTH → 2, WEST → 3.
     * Non-cardinal faces are reduced with {@link #cardinal(BlockFace)}.
     */
    public static int rotationForPlayerFacing(BlockFace facing) {
        return switch (cardinal(facing)) {
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> 0; // NORTH
        };
    }

    /** Convenience overload: rotation for a player standing at {@code yaw}. */
    public static int rotationForPlayerYaw(float yaw) {
        return rotationForPlayerFacing(cardinal(yaw));
    }

    /**
     * Rotates a model-space bounding box into world-space offsets relative to the model origin and returns
     * the resulting world AABB.
     *
     * @param b           build supplying the world origin and rotation
     * @param modelBounds {@code {minX,minY,minZ,maxX,maxY,maxZ}} in model space (inclusive)
     * @return {@code {minX,minY,minZ,maxX,maxY,maxZ}} in world space (inclusive)
     * @throws IllegalArgumentException when the bounds array is null or too short
     */
    public static int[] worldBounds(Build b, int[] modelBounds) {
        if (b == null) throw new IllegalArgumentException("build is null");
        return worldBounds(b.originX, b.originY, b.originZ, b.rotation, modelBounds);
    }

    /**
     * Same as {@link #worldBounds(Build, int[])} but with explicit placement parameters.
     */
    public static int[] worldBounds(int originX, int originY, int originZ, int rotation, int[] modelBounds) {
        int[] box = normalizeBounds(modelBounds);
        // Rotating a box by multiples of 90° maps the rectangle to another axis-aligned rectangle;
        // rotating the two opposite corners is enough.
        int[] a = rotate(box[0], box[2], rotation);
        int[] c = rotate(box[3], box[5], rotation);
        int minX = Math.min(a[0], c[0]) + originX;
        int minZ = Math.min(a[1], c[1]) + originZ;
        int maxX = Math.max(a[0], c[0]) + originX;
        int maxZ = Math.max(a[1], c[1]) + originZ;
        return new int[]{minX, box[1] + originY, minZ, maxX, box[4] + originY, maxZ};
    }

    /**
     * Computes the model origin so that the model's front-center lands on the given anchor column.
     *
     * <p>The front-center is the mid-point of the model's front edge, just outside the model:
     * {@code x = floorDiv(minX+maxX, 2)}, {@code z = maxZ + 1}, at the model's ground layer ({@code y = 0}).
     * The returned origin is chosen such that this front-center is rotated with the build rotation,
     * translated by the origin, and ends up exactly at {@code (anchorX, anchorZ)}. Per the model
     * convention {@code originY == groundY}: model layer {@code y = 0} replaces the top terrain block,
     * so a model whose lowest cell is at {@code y = 0} rests on the ground.
     * (A model that starts above {@code y = 0} keeps its offset and floats accordingly — models are
     * expected to be authored with their ground layer at {@code y = 0}.)</p>
     *
     * @param anchorX    world x of the column where the model's front-center should be
     * @param anchorZ    world z of that column
     * @param groundY    world y of the ground surface the model should stand on
     * @param rotation   0..3 clockwise quarter turns
     * @param modelBounds {@code {minX,minY,minZ,maxX,maxY,maxZ}} in model space
     * @return {@code {originX, originY, originZ}}
     */
    public static int[] computeOrigin(int anchorX, int anchorZ, int groundY, int rotation, int[] modelBounds) {
        int[] box = normalizeBounds(modelBounds);
        int frontCenterX = Math.floorDiv(box[0] + box[3], 2);
        int frontCenterZ = box[5] + 1;
        int[] rotated = rotate(frontCenterX, frontCenterZ, rotation);
        int originX = anchorX - rotated[0];
        int originY = groundY;
        int originZ = anchorZ - rotated[1];
        return new int[]{originX, originY, originZ};
    }

    /**
     * Footprint (world x/z AABB) of a model placed with the given parameters: {@code {minX,minZ,maxX,maxZ}}.
     */
    public static int[] worldFootprint(int originX, int originZ, int rotation, int[] modelBounds) {
        int[] box = normalizeBounds(modelBounds);
        int[] a = rotate(box[0], box[2], rotation);
        int[] c = rotate(box[3], box[5], rotation);
        return new int[]{
                Math.min(a[0], c[0]) + originX,
                Math.min(a[1], c[1]) + originZ,
                Math.max(a[0], c[0]) + originX,
                Math.max(a[1], c[1]) + originZ};
    }

    /** Footprint of a build's current model, or {@code null} when there is no model. */
    public static int[] worldFootprint(Build b) {
        if (b == null) return null;
        int[] mb = b.modelBounds();
        if (mb == null) return null;
        return worldFootprint(b.originX, b.originZ, b.rotation, mb);
    }

    /**
     * Estimates the ground level for a world footprint: the <b>median</b> of the terrain height over the
     * footprint columns, sampling at most ~200 evenly spaced columns so the cost is bounded for large
     * selections.
     *
     * <p>The height of a column is {@code World#getHighestBlockYAt(x, z, MOTION_BLOCKING_NO_LEAVES)} (the
     * first air block above the highest motion-blocking, non-leaf block), then stepped down past logs, wood
     * and other natural obstacles so that a build placed under a tree lands on the soil rather than on the
     * canopy. Using the median (instead of the mean or the maximum) keeps a single tall tree, cliff or hole
     * from dominating the result.</p>
     *
     * @param w              world to sample (main thread; chunks load lazily)
     * @param worldFootprint {@code {minX,minZ,maxX,maxZ}} world columns to sample
     * @return the median ground y, or the world's sea-level guess when the footprint is empty/invalid
     */
    public static int groundY(org.bukkit.World w, int[] worldFootprint) {
        if (w == null) throw new IllegalArgumentException("world is null");
        if (worldFootprint == null || worldFootprint.length < 4) return w.getMinHeight();
        int minX = Math.min(worldFootprint[0], worldFootprint[2]);
        int maxX = Math.max(worldFootprint[0], worldFootprint[2]);
        int minZ = Math.min(worldFootprint[1], worldFootprint[3]);
        int maxZ = Math.max(worldFootprint[1], worldFootprint[3]);
        long cols = (long) (maxX - minX + 1) * (long) (maxZ - minZ + 1);
        if (cols <= 0) return w.getMinHeight();

        // roughly sqrt(200) x sqrt(200) samples, at least one column
        int side = Math.max(1, (int) Math.round(Math.sqrt(200.0)));
        int stepX = Math.max(1, (maxX - minX + 1) / side);
        int stepZ = Math.max(1, (maxZ - minZ + 1) / side);
        java.util.ArrayList<Integer> heights = new java.util.ArrayList<>(256);
        int guard = 0;
        outer:
        for (int x = minX; x <= maxX; x += stepX) {
            for (int z = minZ; z <= maxZ; z += stepZ) {
                if (guard++ > 512) break outer;
                heights.add(columnGroundY(w, x, z));
            }
        }
        if (heights.isEmpty()) {
            // degenerate footprint: sample the center column
            return columnGroundY(w, (minX + maxX) / 2, (minZ + maxZ) / 2);
        }
        heights.sort(Integer::compare);
        int n = heights.size();
        int median = (n % 2 == 1) ? heights.get(n / 2)
                : (int) Math.round((heights.get(n / 2 - 1) + (double) heights.get(n / 2)) / 2.0);
        return Math.max(w.getMinHeight(), Math.min(w.getMaxHeight() - 1, median));
    }

    /**
     * Height of a single column: the highest motion-blocking, leaf-free block (as reported by
     * {@link org.bukkit.HeightMap#MOTION_BLOCKING_NO_LEAVES}) stepped down past logs/wood so trees and
     * natural debris do not lift the ground estimate.
     */
    private static int columnGroundY(org.bukkit.World w, int x, int z) {
        int y;
        try {
            y = w.getHighestBlockYAt(x, z, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES);
        } catch (RuntimeException e) {
            return w.getMinHeight();
        }
        int floor = w.getMinHeight();
        for (int steps = 0; steps < 32 && y > floor; steps++) {
            org.bukkit.Material m;
            try {
                m = w.getBlockAt(x, y, z).getType();
            } catch (RuntimeException e) {
                return y;
            }
            if (!isWoody(m)) return y;
            y--;
        }
        return y;
    }

    /** Logs, stems and other natural vegetation that should not count as ground. */
    private static boolean isWoody(org.bukkit.Material m) {
        if (m == null) return false;
        String n = m.name();
        if (n.endsWith("_LOG") || n.endsWith("_WOOD") || n.endsWith("_STEM")
                || n.endsWith("_HYPHAE") || n.endsWith("_LEAVES")) {
            return true;
        }
        return m == org.bukkit.Material.MUSHROOM_STEM || m == org.bukkit.Material.BAMBOO_BLOCK
                || m == org.bukkit.Material.CACTUS || m == org.bukkit.Material.SUGAR_CANE
                || m == org.bukkit.Material.BAMBOO;
    }

    /** Returns {@code true} when the given world point lies inside the rotated model bounding box. */
    public static boolean contains(Build b, int x, int y, int z) {
        if (b == null) return false;
        int[] mb = b.modelBounds();
        if (mb == null) return false;
        int[] wb = worldBounds(b, mb);
        return x >= wb[0] && x <= wb[3] && y >= wb[1] && y <= wb[4] && z >= wb[2] && z <= wb[5];
    }

    /**
     * Inverse of {@link #rotate(int, int, int)}: maps a rotated (world-relative) x/z back to model space.
     */
    public static int[] unrotate(int x, int z, int rotation) {
        return switch (norm(rotation)) {
            case 0 -> new int[]{x, z};
            case 1 -> new int[]{z, -x};
            case 2 -> new int[]{-x, -z};
            default -> new int[]{-z, x};
        };
    }

    /** Validates and copies a {@code {minX,minY,minZ,maxX,maxY,maxZ}} array, sorting the corners. */
    public static int[] normalizeBounds(int[] modelBounds) {
        if (modelBounds == null || modelBounds.length < 6) {
            throw new IllegalArgumentException("modelBounds must contain 6 values (minX,minY,minZ,maxX,maxY,maxZ)");
        }
        int minX = Math.min(modelBounds[0], modelBounds[3]);
        int minY = Math.min(modelBounds[1], modelBounds[4]);
        int minZ = Math.min(modelBounds[2], modelBounds[5]);
        int maxX = Math.max(modelBounds[0], modelBounds[3]);
        int maxY = Math.max(modelBounds[1], modelBounds[4]);
        int maxZ = Math.max(modelBounds[2], modelBounds[5]);
        return new int[]{minX, minY, minZ, maxX, maxY, maxZ};
    }
}
