package dev.aibuild.analysis;

import dev.aibuild.model.BlockSpec;
import dev.aibuild.model.VoxelModel;

/**
 * Flat-array snapshot of a rectangular region of a {@link VoxelModel}, used internally by
 * {@link StructureAnalyzer} and the renderers to keep hot loops free of {@code HashMap} lookups.
 *
 * <p>Index layout: {@code i = (y - minY) * sx * sz + (z - minZ) * sx + (x - minX)}; +x east, +z south,
 * +y up. The array holds <b>raw set specs only</b> ({@code null} = unset); the effective value
 * (terrain below y=0, air above) must be derived by the caller with {@link #eff(int)}, because terrain is a
 * pseudo block that must not be aliased into the array.</p>
 *
 * <p>Immutable after construction (the {@code BlockSpec} array is private); safe to share between threads.
 * The block-index range is validated once so lookups inside {@code [minX..maxX] x [minY..maxY] x [minZ..maxZ]}
 * never throw.</p>
 */
final class Grid {
    final int minX, minY, minZ, maxX, maxY, maxZ;
    final int sx, sy, sz;
    /** Number of cells; may be 0 for an empty grid. */
    final long volume;
    private final BlockSpec[] cells;

    /** Maximum number of cells a grid may hold; guards against absurd coordinates / OOM. */
    static final long MAX_CELLS = 32_000_000L;

    Grid(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        if (maxX < minX || maxY < minY || maxZ < minZ) {
            // Normalize inverted boxes to an empty grid anchored at min.
            maxX = minX - 1;
            maxY = minY - 1;
            maxZ = minZ - 1;
        }
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        this.sx = maxX - minX + 1;
        this.sy = maxY - minY + 1;
        this.sz = maxZ - minZ + 1;
        long v = (long) sx * sy * sz;
        if (v > MAX_CELLS) throw new IllegalArgumentException("region too large: " + sx + "x" + sy + "x" + sz);
        this.volume = v;
        this.cells = new BlockSpec[(int) v];
    }

    /** A copy of {@code g} padded by {@code pad} cells horizontally and vertically. */
    Grid(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int pad) {
        this(minX - pad, minY - pad, minZ - pad, maxX + pad, maxY + pad, maxZ + pad);
    }

    boolean isEmpty() {
        return volume == 0;
    }

    int index(int x, int y, int z) {
        return ((y - minY) * sz + (z - minZ)) * sx + (x - minX);
    }

    /** True when the coordinate lies inside the grid. */
    boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    /** Raw set spec, or null when unset / outside the grid. */
    BlockSpec raw(int x, int y, int z) {
        if (!contains(x, y, z)) return null;
        return cells[index(x, y, z)];
    }

    /** Raw set spec by flat index (no bounds check). */
    BlockSpec at(int i) {
        return cells[i];
    }

    /**
     * Effective block by flat index: the set spec, else {@link VoxelModel#TERRAIN} when {@code y <= 0}, else
     * {@link BlockSpec#AIR}. Never null.
     */
    BlockSpec eff(int i) {
        BlockSpec s = cells[i];
        if (s != null) return s;
        return yOf(i) <= 0 ? VoxelModel.TERRAIN : BlockSpec.AIR;
    }

    /** Effective block by coordinate; outside the grid y&lt;=0 counts as terrain, else air. */
    BlockSpec eff(int x, int y, int z) {
        if (!contains(x, y, z)) return y <= 0 ? VoxelModel.TERRAIN : BlockSpec.AIR;
        return eff(index(x, y, z));
    }

    /** True when a block is present (set non-air). Terrain below y=0 is <b>not</b> reported by this method. */
    boolean solidAt(int x, int y, int z) {
        BlockSpec s = raw(x, y, z);
        return s != null && !s.isAir();
    }

    /** True when the effective cell is terrain (unset and {@code y <= 0}). */
    boolean terrainAt(int x, int y, int z) {
        return y <= 0 && raw(x, y, z) == null;
    }

    int yOf(int i) {
        return minY + i / (sx * sz);
    }

    int zOf(int i) {
        return minZ + (i / sx) % sz;
    }

    int xOf(int i) {
        return minX + i % sx;
    }

    // ------------------------------------------------------------------ construction

    /**
     * Builds a grid over the non-air bounding box of {@code model} expanded by {@code pad} cells on every side.
     * Explicit air cells inside the region are stored as {@link BlockSpec#AIR} (they override terrain, which is
     * exactly the model semantics). Returns {@code null} when the model has no non-air cells or when the padded
     * region is unreasonably large (callers treat both as "nothing to analyse").
     */
    static Grid ofNonAir(VoxelModel model, int pad) {
        int[] b = model.solidBounds();
        if (b == null) return null;
        long spanX = (long) b[3] - b[0] + 1 + 2L * pad;
        long spanY = (long) b[4] - b[1] + 1 + 2L * pad;
        long spanZ = (long) b[5] - b[2] + 1 + 2L * pad;
        if (spanX <= 0 || spanY <= 0 || spanZ <= 0
                || spanX * spanY * spanZ > MAX_CELLS
                || spanX > 1 << 13 || spanY > 1 << 13 || spanZ > 1 << 13) {
            return null;
        }
        Grid g = new Grid(b[0] - pad, b[1] - pad, b[2] - pad, b[3] + pad, b[4] + pad, b[5] + pad);
        fillFrom(g, model, b[0] - pad, b[1] - pad, b[2] - pad, b[3] + pad, b[4] + pad, b[5] + pad);
        return g;
    }

    /** Like {@link #ofNonAir(VoxelModel, int)} but clipped to {@code region} = {minX,minY,minZ,maxX,maxY,maxZ}. */
    static Grid ofRegion(VoxelModel model, int[] region) {
        int minX = Math.min(region[0], region[3]);
        int minY = Math.min(region[1], region[4]);
        int minZ = Math.min(region[2], region[5]);
        int maxX = Math.max(region[0], region[3]);
        int maxY = Math.max(region[1], region[4]);
        int maxZ = Math.max(region[2], region[5]);
        long sx = (long) maxX - minX + 1, sy = (long) maxY - minY + 1, sz = (long) maxZ - minZ + 1;
        if (sx <= 0 || sy <= 0 || sz <= 0 || sx * sy * sz > MAX_CELLS) {
            throw new IllegalArgumentException("region too large: " + sx + "x" + sy + "x" + sz);
        }
        Grid g = new Grid(minX, minY, minZ, maxX, maxY, maxZ);
        fillFrom(g, model, minX, minY, minZ, maxX, maxY, maxZ);
        return g;
    }

    /** Copies the set cells of {@code model} inside the inclusive box into {@code g}. */
    private static void fillFrom(Grid g, VoxelModel model, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        for (var e : model.entries().entrySet()) {
            long k = e.getKey();
            int x = VoxelModel.kx(k), y = VoxelModel.ky(k), z = VoxelModel.kz(k);
            if (x < minX || x > maxX || y < minY || y > maxY || z < minZ || z > maxZ) continue;
            BlockSpec s = e.getValue();
            if (s == null) continue;
            g.cells[g.index(x, y, z)] = s;
        }
    }
}
