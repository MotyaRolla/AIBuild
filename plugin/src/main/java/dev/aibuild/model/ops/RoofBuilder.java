package dev.aibuild.model.ops;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.aibuild.model.BlockInfo;
import dev.aibuild.model.BlockPattern;
import dev.aibuild.model.BlockSpec;
import dev.aibuild.model.Dir;
import dev.aibuild.model.VoxelModel;

import java.util.Objects;

/**
 * Builds the {@code roof} op: turns the rectangle of a building's wall tops into a roof made of stairs,
 * slabs and optional filling material.
 *
 * <h2>Parameters</h2>
 * <table>
 *     <tr><td>{@code from},{@code to}</td><td>x/z rectangle of the wall tops; the roof base y is
 *     {@code min(from[1],to[1])} (the y of the first roof layer)</td></tr>
 *     <tr><td>{@code style}</td><td>{@code gable} (default), {@code hip}, {@code pyramid}, {@code shed},
 *     {@code flat}</td></tr>
 *     <tr><td>{@code stairs}</td><td>required for sloped roofs: pattern of stair blocks</td></tr>
 *     <tr><td>{@code slab}</td><td>optional: ridge cap (placed with {@code type=bottom})</td></tr>
 *     <tr><td>{@code fill}</td><td>optional: gable end triangles and ridge fallback</td></tr>
 *     <tr><td>{@code overhang}</td><td>0..3, default 1 (rectangle expanded on every side)</td></tr>
 *     <tr><td>{@code ridge}</td><td>{@code x} or {@code z}; default the longer side of the expanded
 *     rectangle</td></tr>
 *     <tr><td>{@code facing}</td><td>shed only: ascending direction, default north</td></tr>
 * </table>
 *
 * <p>Gable roofs place two rows of stairs per layer, facing the ridge; the rows move inward until they meet
 * (a cap of slabs/filling is placed on the ridge line) or cross (stop). The triangles under the roof on the
 * two wall planes (not on the overhang) are filled when {@code fill} is given. Hip/pyramid roofs place
 * shrinking rings of stairs facing inward and cap the remaining line or point with slabs (or filling).
 * Shed roofs place rows perpendicular to {@code facing}, rising one block per row. Flat roofs place a single
 * layer of slabs (or filling) over the expanded rectangle.</p>
 *
 * <p>Instances are cheap and stateless apart from the last build's change counter; not thread-safe, create
 * one per execution.</p>
 */
public final class RoofBuilder {
    /** Coordinate bound used by the no-limit constructor. */
    public static final int DEFAULT_MAX_ABS_COORD = 512;

    private final BlockInfo info;
    private final int maxAbsCoord;
    private int changed;

    /** Creates a builder bound to {@code info} with a defensive coordinate bound of 512. */
    public RoofBuilder(BlockInfo info) {
        this(info, DEFAULT_MAX_ABS_COORD);
    }

    /**
     * @param info        block registry
     * @param maxAbsCoord largest allowed |coordinate|; larger cells are silently skipped
     */
    public RoofBuilder(BlockInfo info, int maxAbsCoord) {
        this.info = Objects.requireNonNull(info, "info");
        this.maxAbsCoord = Math.max(64, maxAbsCoord);
    }

    /**
     * Builds the roof described by {@code op} into {@code model}.
     *
     * @param model target model (mutated)
     * @param op    the roof op object
     * @param out   receives the {@code roof: style=... top_y=N} info line and non fatal warnings
     * @return the number of cells whose value changed
     * @throws IllegalArgumentException when a parameter is missing or invalid; nothing is written in that case
     *                                  (all validation happens before the first write)
     */
    public int build(VoxelModel model, JsonObject op, OpResult out) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(op, "op");
        Objects.requireNonNull(out, "out");
        changed = 0;

        int[][] region = OpArgs.region(op);
        if (region == null) throw new IllegalArgumentException("missing 'from'/'to' roof rectangle");
        int[] from = region[0];
        int[] to = region[1];
        int base = Math.min(from[1], to[1]);
        int x1 = Math.min(from[0], to[0]);
        int x2 = Math.max(from[0], to[0]);
        int z1 = Math.min(from[2], to[2]);
        int z2 = Math.max(from[2], to[2]);
        String style = OpArgs.str(op, "gable", "style", "kind", "type").toLowerCase(java.util.Locale.ROOT);
        if (!style.equals("gable") && !style.equals("hip") && !style.equals("pyramid")
                && !style.equals("shed") && !style.equals("flat")) {
            throw new IllegalArgumentException("unknown roof style '" + style
                    + "' (expected gable, hip, pyramid, shed or flat)");
        }

        int overhang = OpArgs.integer(op, 1, "overhang", "expand", "eaves");
        if (overhang < 0) {
            out.warning("roof: overhang " + overhang + " clamped to 0");
            overhang = 0;
        } else if (overhang > 3) {
            out.warning("roof: overhang " + overhang + " clamped to 3");
            overhang = 3;
        }

        JsonElement stairsEl = OpArgs.field(op, "stairs", "stair", "stair_block", "block", "material", "pattern", "blocks");
        BlockPattern stairs = null;
        if (stairsEl != null) {
            stairs = BlockPattern.parse(stairsEl, info);
            for (BlockSpec s : stairs.options()) {
                if (!info.hasProperty(s.name(), "facing")) {
                    throw new IllegalArgumentException("'stairs' entry '" + s + "' is not a stair block (no facing property)");
                }
            }
        }
        if (stairs == null && !style.equals("flat")) {
            throw new IllegalArgumentException("missing 'stairs' pattern for a " + style + " roof");
        }

        BlockPattern slab = null;
        JsonElement slabEl = OpArgs.field(op, "slab", "slabs", "cap", "ridge_block");
        if (slabEl != null) slab = BlockPattern.parse(slabEl, info);

        BlockPattern fill = null;
        JsonElement fillEl = OpArgs.field(op, "fill", "filler", "wall_block");
        if (fillEl != null) fill = BlockPattern.parse(fillEl, info);

        if (style.equals("flat") && slab == null && fill == null) {
            throw new IllegalArgumentException("flat roof needs a 'slab' or a 'fill' pattern");
        }

        int minX = x1 - overhang, maxX = x2 + overhang;
        int minZ = z1 - overhang, maxZ = z2 + overhang;
        if (Math.abs(minX) > maxAbsCoord || Math.abs(maxX) > maxAbsCoord
                || Math.abs(minZ) > maxAbsCoord || Math.abs(maxZ) > maxAbsCoord
                || Math.abs(base) > maxAbsCoord) {
            throw new IllegalArgumentException("roof region exceeds the coordinate limit of " + maxAbsCoord);
        }

        int topY;
        switch (style) {
            case "flat" -> topY = flat(model, base, minX, maxX, minZ, maxZ, slab, fill);
            case "shed" -> topY = shed(model, op, base, minX, maxX, minZ, maxZ, stairs);
            case "hip", "pyramid" -> topY = ringed(model, base, minX, maxX, minZ, maxZ, stairs, slab, fill);
            default -> topY = gable(model, op, base, x1, x2, z1, z2, minX, maxX, minZ, maxZ, stairs, slab, fill);
        }
        out.info("roof: style=" + style + " top_y=" + topY);
        return changed;
    }

    // ------------------------------------------------------------------ styles

    private int gable(VoxelModel model, JsonObject op, int base, int x1, int x2, int z1, int z2,
                      int minX, int maxX, int minZ, int maxZ, BlockPattern stairs,
                      BlockPattern slab, BlockPattern fill) {
        String ridge = OpArgs.str(op, "", "ridge", "ridge_axis", "along");
        boolean alongX;
        String r = normalizeRidge(ridge);
        if (r == null) {
            alongX = (maxX - minX) >= (maxZ - minZ);
        } else if (r.equals("x")) {
            alongX = true;
        } else if (r.equals("z")) {
            alongX = false;
        } else {
            throw new IllegalArgumentException("ridge must be 'x' or 'z', got '" + ridge + "'");
        }

        int topY = base;
        if (alongX) {
            for (int k = 0; ; k++) {
                int zN = minZ + k;
                int zS = maxZ - k;
                if (zN > zS) break; // rows crossed: the previous layer was the ridge
                int y = base + k;
                if (zN == zS) {
                    for (int x = minX; x <= maxX; x++) {
                        put(model, x, y, zN, capAt(slab, fill, stairs, x, y, zN, Dir.SOUTH));
                    }
                    topY = Math.max(topY, y);
                    break;
                }
                for (int x = minX; x <= maxX; x++) {
                    put(model, x, y, zN, stairAt(stairs, x, y, zN, Dir.SOUTH));
                    put(model, x, y, zS, stairAt(stairs, x, y, zS, Dir.NORTH));
                }
                topY = Math.max(topY, y);
            }
            if (fill != null) {
                for (int z = z1; z <= z2; z++) {
                    int k = Math.min(z - minZ, maxZ - z);
                    if (k <= 0) continue;
                    int stairY = base + k;
                    for (int x : new int[]{x1, x2}) {
                        gableEnd(model, fill, x, base, stairY - 1, z);
                    }
                }
            }
        } else {
            for (int k = 0; ; k++) {
                int xW = minX + k;
                int xE = maxX - k;
                if (xW > xE) break; // rows crossed: the previous layer was the ridge
                int y = base + k;
                if (xW == xE) {
                    for (int z = minZ; z <= maxZ; z++) {
                        put(model, xW, y, z, capAt(slab, fill, stairs, xW, y, z, Dir.EAST));
                    }
                    topY = Math.max(topY, y);
                    break;
                }
                for (int z = minZ; z <= maxZ; z++) {
                    put(model, xW, y, z, stairAt(stairs, xW, y, z, Dir.EAST));
                    put(model, xE, y, z, stairAt(stairs, xE, y, z, Dir.WEST));
                }
                topY = Math.max(topY, y);
            }
            if (fill != null) {
                for (int x = x1; x <= x2; x++) {
                    int k = Math.min(x - minX, maxX - x);
                    if (k <= 0) continue;
                    int stairY = base + k;
                    for (int z : new int[]{z1, z2}) {
                        gableEnd(model, fill, x, base, stairY - 1, z);
                    }
                }
            }
        }
        return topY;
    }

    /** Fills a vertical run of gable-end wall on a wall plane, without overwriting existing blocks. */
    private void gableEnd(VoxelModel model, BlockPattern fill, int x, int yFrom, int yTo, int z) {
        for (int y = yFrom; y <= yTo; y++) {
            BlockSpec cur = model.get(x, y, z);
            if (cur != null && !cur.isAir()) continue;
            put(model, x, y, z, fill.pick(x, y, z));
        }
    }

    private int ringed(VoxelModel model, int base, int minX, int maxX, int minZ, int maxZ,
                       BlockPattern stairs, BlockPattern slab, BlockPattern fill) {
        int topY = base;
        for (int k = 0; ; k++) {
            int aX = minX + k, bX = maxX - k;
            int aZ = minZ + k, bZ = maxZ - k;
            if (aX > bX || aZ > bZ) break;
            int y = base + k;
            int w = bX - aX + 1;
            int d = bZ - aZ + 1;
            if (w == 1 && d == 1) {
                put(model, aX, y, aZ, capAt(slab, fill, stairs, aX, y, aZ, Dir.SOUTH));
                topY = Math.max(topY, y);
                break;
            }
            if (w == 1 || d == 1) {
                // final band (1 or 2 wide): a flat cap of slabs / filling closes the roof
                for (int x = aX; x <= bX; x++) {
                    for (int z = aZ; z <= bZ; z++) {
                        put(model, x, y, z, capAt(slab, fill, stairs, x, y, z, Dir.SOUTH));
                    }
                }
                topY = Math.max(topY, y);
                break;
            }
            for (int x = aX; x <= bX; x++) {
                put(model, x, y, aZ, stairAt(stairs, x, y, aZ, Dir.SOUTH));
                put(model, x, y, bZ, stairAt(stairs, x, y, bZ, Dir.NORTH));
            }
            for (int z = aZ + 1; z <= bZ - 1; z++) {
                put(model, aX, y, z, stairAt(stairs, aX, y, z, Dir.EAST));
                put(model, bX, y, z, stairAt(stairs, bX, y, z, Dir.WEST));
            }
            topY = Math.max(topY, y);
        }
        return topY;
    }

    private int shed(VoxelModel model, JsonObject op, int base, int minX, int maxX, int minZ, int maxZ,
                     BlockPattern stairs) {
        String facingStr = OpArgs.str(op, "north", "facing", "ascend", "direction", "slope");
        Dir facing = Dir.parse(facingStr);
        if (facing == null || !facing.isHorizontal()) {
            throw new IllegalArgumentException("shed roof facing must be north, east, south or west, got '" + facingStr + "'");
        }
        int topY = base;
        int k = 0;
        while (true) {
            int y = base + k;
            switch (facing) {
                case NORTH -> {
                    int z = maxZ - k;
                    if (z < minZ) return topY;
                    for (int x = minX; x <= maxX; x++) put(model, x, y, z, stairAt(stairs, x, y, z, facing));
                }
                case SOUTH -> {
                    int z = minZ + k;
                    if (z > maxZ) return topY;
                    for (int x = minX; x <= maxX; x++) put(model, x, y, z, stairAt(stairs, x, y, z, facing));
                }
                case EAST -> {
                    int x = minX + k;
                    if (x > maxX) return topY;
                    for (int z = minZ; z <= maxZ; z++) put(model, x, y, z, stairAt(stairs, x, y, z, facing));
                }
                default -> {
                    int x = maxX - k;
                    if (x < minX) return topY;
                    for (int z = minZ; z <= maxZ; z++) put(model, x, y, z, stairAt(stairs, x, y, z, facing));
                }
            }
            topY = Math.max(topY, y);
            k++;
        }
    }

    private int flat(VoxelModel model, int base, int minX, int maxX, int minZ, int maxZ,
                     BlockPattern slab, BlockPattern fill) {
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                BlockSpec spec = slab != null ? slabAt(slab, x, base, z) : fill.pick(x, base, z);
                put(model, x, base, z, spec);
            }
        }
        return base;
    }

    // ------------------------------------------------------------------ helpers

    private static String normalizeRidge(String ridge) {
        if (ridge == null) return null;
        String r = ridge.trim().toLowerCase(java.util.Locale.ROOT);
        if (r.isEmpty() || r.equals("auto") || r.equals("long") || r.equals("longer")) return null;
        return switch (r) {
            case "x", "along_x", "east-west", "east_west", "ew" -> "x";
            case "z", "along_z", "north-south", "north_south", "ns" -> "z";
            default -> r; // reported as invalid by the caller
        };
    }

    private BlockSpec stairAt(BlockPattern stairs, int x, int y, int z, Dir facing) {
        BlockSpec s = stairs.pick(x, y, z);
        s = info.with(s, "facing", facing.id());
        if (info.hasProperty(s.name(), "half")) {
            s = info.with(s, "half", "bottom");
        }
        return info.canonical(s);
    }

    private BlockSpec slabAt(BlockPattern slab, int x, int y, int z) {
        BlockSpec s = slab.pick(x, y, z);
        if (info.hasProperty(s.name(), "type")) {
            s = info.with(s, "type", "bottom");
        }
        return info.canonical(s);
    }

    /** Ridge/cap block: slab if given, else fill, else the stair pattern. */
    private BlockSpec capAt(BlockPattern slab, BlockPattern fill, BlockPattern stairs,
                            int x, int y, int z, Dir fallbackFacing) {
        if (slab != null) return slabAt(slab, x, y, z);
        if (fill != null) return fill.pick(x, y, z);
        if (stairs != null) return stairAt(stairs, x, y, z, fallbackFacing);
        return baseCapFallback(x, y, z);
    }

    private BlockSpec baseCapFallback(int x, int y, int z) {
        // Only reachable for a flat roof without slab/fill, which is rejected earlier.
        return BlockSpec.of("stone");
    }

    private boolean put(VoxelModel model, int x, int y, int z, BlockSpec spec) {
        if (Math.abs(x) > maxAbsCoord || Math.abs(y) > maxAbsCoord || Math.abs(z) > maxAbsCoord) return false;
        BlockSpec old = model.get(x, y, z);
        if (Objects.equals(old, spec)) return false;
        model.set(x, y, z, spec);
        changed++;
        return true;
    }
}
