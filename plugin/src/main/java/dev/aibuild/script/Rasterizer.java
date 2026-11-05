package dev.aibuild.script;

import dev.aibuild.model.BlockSpec;
import dev.aibuild.script.geom.Shape;

import java.util.Map;

/**
 * Turns a shape into blocks. Shapes are evaluated in world space (the canvas frame is applied to the shape,
 * not to the cells), so rotated and scaled solids stay watertight.
 *
 * <p><b>Smoothing</b> works like a builder terracing a dome by hand. Cells whose centre is inside the solid are
 * blocks. For every top cell the exact surface height in that column is found by bisection: a surface that
 * ends in the lower quarter of the cell turns the cell into a bottom slab, one that reaches the upper quarter
 * adds a bottom slab on top. A slab that sits against a full block of the same layer becomes stairs facing
 * that block (the tall side uphill). Undersides get top slabs and upside-down stairs the same way. Stairs and
 * slabs come from the material's block family ({@code stone_bricks -> stone_brick_stairs}); materials without
 * one keep whole blocks.</p>
 */
public final class Rasterizer {
    /** Hard cap on distance evaluations for one placement (cells x shape cost). */
    public static final long MAX_SAMPLES = 6_000_000_000L;
    /** Cells one placement may cover: about the whole build space (the grid keeps 6 bytes per cell). */
    private static final int MAX_VOLUME = 40_000_000;

    private static final byte EMPTY = 0, FULL = 1, SLAB_BOTTOM = 2, SLAB_TOP = 3, STAIR_BOTTOM = 4, STAIR_TOP = 5;
    private static final String[] SIDES = {"north", "south", "west", "east"};
    private static final int[][] SIDE_DELTA = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};

    private Rasterizer() {
    }

    /**
     * Placement options.
     *
     * @param smooth    use slabs and stairs on sloped tops and undersides
     * @param shell     wall thickness for a hollow solid (0 = solid)
     * @param carve     write air instead of blocks (cut the shape out)
     * @param onlyAir   only fill cells that are currently empty
     * @param onlySolid only replace cells that currently hold a block (repaint)
     * @param from      with onlySolid: the side the surface is seen from (0..5 = {@link #FROM}), or -1. Only the first
     *                  block along each straight line from that side is repainted, so a pattern lies as one layer on
     *                  the surface a viewer sees (eyes on a round head, a sign on a curved wall), never wrapped
     *                  around the curve into the blocks behind it
     */
    public record Options(boolean smooth, double shell, boolean carve, boolean onlyAir, boolean onlySolid, int from) {
        public static final Options SOLID = new Options(false, 0, false, false, false, -1);

        public Options(boolean smooth, double shell, boolean carve, boolean onlyAir, boolean onlySolid) {
            this(smooth, shell, carve, onlyAir, onlySolid, -1);
        }
    }

    /** The sides a repaint can be seen from, as {@link Options#from} indices, with the step towards the viewer. */
    public static final java.util.List<String> FROM = java.util.List.of("north", "south", "west", "east", "up", "down");
    private static final int[][] FROM_STEP = {{0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}, {0, 1, 0}, {0, -1, 0}};

    /** @return number of cells written */
    public static int place(Canvas canvas, Shape local, Material material, Options o, long[] sampleBudget) {
        Shape s = Shape.transform(local, canvas.frame());
        double[] b = s.bounds();
        int pad = o.smooth ? 1 : 0;
        int x0 = (int) Math.ceil(b[0] - 0.5), y0 = (int) Math.ceil(b[1] - 0.5) - pad, z0 = (int) Math.ceil(b[2] - 0.5);
        int x1 = (int) Math.floor(b[3] + 0.5), y1 = (int) Math.floor(b[4] + 0.5) + pad, z1 = (int) Math.floor(b[5] + 0.5);
        long volume = (long) Math.max(0, x1 - x0 + 1) * Math.max(0, y1 - y0 + 1) * Math.max(0, z1 - z0 + 1);
        long work = volume * s.cost() * (o.smooth ? 2L : 1L);
        if (work > MAX_SAMPLES || volume > MAX_VOLUME) {
            throw new IllegalArgumentException("shape is too large or complex to sample (" + volume + " cells x cost " + s.cost()
                    + "); split it into smaller shapes or use box() for large plain volumes");
        }
        sampleBudget[0] -= work;
        if (sampleBudget[0] < 0) {
            throw new ScriptBudgetException("geometry sampling budget exhausted; use fewer or smaller shapes, or box() for plain volumes");
        }
        if (volume == 0) return 0;
        Grid g = new Grid(s, x0, y0, z0, x1, y1, z1, o.shell);
        if (o.smooth && !o.carve) g.smooth();
        return g.write(canvas, material, o);
    }

    /** Dense per-placement cell classification. */
    private static final class Grid {
        final Shape s;
        final int x0, y0, z0, w, h, dep;
        final float[] dist;
        final byte[] kind;
        final byte[] facing;

        Grid(Shape s, int x0, int y0, int z0, int x1, int y1, int z1, double shell) {
            this.s = s;
            this.x0 = x0;
            this.y0 = y0;
            this.z0 = z0;
            this.w = x1 - x0 + 1;
            this.h = y1 - y0 + 1;
            this.dep = z1 - z0 + 1;
            int n = w * h * dep;
            dist = new float[n];
            kind = new byte[n];
            facing = new byte[n];
            for (int y = 0; y < h; y++) {
                for (int z = 0; z < dep; z++) {
                    for (int x = 0; x < w; x++) {
                        int i = idx(x, y, z);
                        double d = s.d(x0 + x, y0 + y, z0 + z);
                        dist[i] = (float) d;
                        if (d <= 0 && !(shell > 0 && d <= -shell)) kind[i] = FULL;
                    }
                }
            }
        }

        int idx(int x, int y, int z) {
            return (y * dep + z) * w + x;
        }

        byte at(int x, int y, int z) {
            if (x < 0 || y < 0 || z < 0 || x >= w || y >= h || z >= dep) return EMPTY;
            return kind[idx(x, y, z)];
        }

        boolean solidCentre(int x, int y, int z) {
            if (x < 0 || y < 0 || z < 0 || x >= w || y >= h || z >= dep) return false;
            return dist[idx(x, y, z)] <= 0;
        }

        void smooth() {
            byte[] base = kind.clone();
            // tops and undersides become slabs where the surface only covers part of the cell
            for (int y = 0; y < h; y++) {
                for (int z = 0; z < dep; z++) {
                    for (int x = 0; x < w; x++) {
                        int i = idx(x, y, z);
                        if (base[i] != FULL) continue;
                        boolean top = !solidCentre(x, y + 1, z), bottom = !solidCentre(x, y - 1, z);
                        double cx = x0 + x, cy = y0 + y, cz = z0 + z;
                        if (top) {
                            double t = surface(cx, cz, cy, cy + 1);
                            if (t < cy + 0.25 && !bottom) kind[i] = SLAB_BOTTOM;
                            else if (t >= cy + 0.75 && y + 1 < h && kind[idx(x, y + 1, z)] == EMPTY) kind[idx(x, y + 1, z)] = SLAB_BOTTOM;
                        }
                        if (bottom) {
                            double t = surface(cx, cz, cy, cy - 1);
                            if (t > cy - 0.25 && !top) kind[i] = SLAB_TOP;
                            else if (t <= cy - 0.75 && y - 1 >= 0 && kind[idx(x, y - 1, z)] == EMPTY) kind[idx(x, y - 1, z)] = SLAB_TOP;
                        }
                    }
                }
            }
            // a slab against a full block of its own layer is the tread of a terrace step: make it stairs
            for (int y = 0; y < h; y++) {
                for (int z = 0; z < dep; z++) {
                    for (int x = 0; x < w; x++) {
                        int i = idx(x, y, z);
                        byte k = kind[i];
                        if (k != SLAB_BOTTOM && k != SLAB_TOP) continue;
                        int best = -1;
                        double bestScore = Double.NEGATIVE_INFINITY;
                        for (int sIdx = 0; sIdx < 4; sIdx++) {
                            int nx = x + SIDE_DELTA[sIdx][0], nz = z + SIDE_DELTA[sIdx][1];
                            if (at(nx, y, nz) != FULL) continue;
                            int ox = x - SIDE_DELTA[sIdx][0], oz = z - SIDE_DELTA[sIdx][1];
                            if (at(ox, y, oz) == FULL) continue; // wedged between two blocks: stay a slab
                            // prefer the side the shape rises toward (distance decreases fastest)
                            double score = -(s.d(x0 + nx, y0 + y, z0 + nz) - dist[i]);
                            if (score > bestScore) {
                                bestScore = score;
                                best = sIdx;
                            }
                        }
                        if (best >= 0) {
                            kind[i] = k == SLAB_BOTTOM ? STAIR_BOTTOM : STAIR_TOP;
                            facing[i] = (byte) best;
                        }
                    }
                }
            }
        }

        /** Height in [from, to] (either order) where the solid ends in this column; {@code from} is inside. */
        double surface(double x, double z, double from, double to) {
            double in = from, out = to;
            if (s.d(x, out, z) <= 0) return out;
            for (int k = 0; k < 7; k++) {
                double mid = (in + out) / 2;
                if (s.d(x, mid, z) <= 0) in = mid;
                else out = mid;
            }
            return (in + out) / 2;
        }

        int write(Canvas canvas, Material material, Options o) {
            canvas.repainting(o.onlySolid);
            try {
                return writeCells(canvas, material, o, o.onlySolid && o.from >= 0 ? facing(canvas, o.from) : null);
            } finally {
                canvas.repainting(false);
            }
        }

        /**
         * The cells seen first from a side: along each line from that side through the grid, the first cell holding a
         * block, when it is inside the shape. A block of the build in front (inside the grid) hides what is behind it.
         */
        private boolean[] facing(Canvas canvas, int from) {
            int[] st = FROM_STEP[from];
            boolean[] out = new boolean[w * h * dep];
            int[] size = {w, h, dep};
            int axis = st[0] != 0 ? 0 : st[1] != 0 ? 1 : 2;
            int ua = axis == 0 ? 1 : 0, va = axis == 2 ? 1 : 2;
            int n = size[axis];
            for (int u = 0; u < size[ua]; u++) {
                for (int v = 0; v < size[va]; v++) {
                    for (int t = 0; t < n; t++) {
                        int along = st[axis] > 0 ? n - 1 - t : t;   // start at the viewer's side
                        int[] c = new int[3];
                        c[axis] = along;
                        c[ua] = u;
                        c[va] = v;
                        BlockSpec cur = canvas.getWorld(x0 + c[0], y0 + c[1], z0 + c[2]);
                        if (cur == null || cur.isAir()) continue;
                        int i = idx(c[0], c[1], c[2]);
                        if (kind[i] != EMPTY) out[i] = true;
                        break;
                    }
                }
            }
            return out;
        }

        private int writeCells(Canvas canvas, Material material, Options o, boolean[] seen) {
            int written = 0;
            int[] cellBounds = {x0, y0, z0, x0 + w - 1, y0 + h - 1, z0 + dep - 1};
            for (int y = 0; y < h; y++) {
                for (int z = 0; z < dep; z++) {
                    for (int x = 0; x < w; x++) {
                        int i = idx(x, y, z);
                        byte k = kind[i];
                        if (k == EMPTY || seen != null && !seen[i]) continue;
                        int wx = x0 + x, wy = y0 + y, wz = z0 + z;
                        if (o.onlyAir || o.onlySolid) {
                            BlockSpec cur = canvas.getWorld(wx, wy, wz);
                            boolean solid = cur != null && !cur.isAir();
                            if (o.onlyAir && solid) continue;
                            if (o.onlySolid && !solid) continue;
                        }
                        if (o.carve) {
                            canvas.setWorld(wx, wy, wz, BlockSpec.AIR, false);
                            written++;
                            continue;
                        }
                        boolean surface = at(x + 1, y, z) == EMPTY || at(x - 1, y, z) == EMPTY || at(x, y + 1, z) == EMPTY
                                || at(x, y - 1, z) == EMPTY || at(x, y, z + 1) == EMPTY || at(x, y, z - 1) == EMPTY;
                        Material.Sample sm = surface ? sample(wx, wy, wz, cellBounds) : new Material.Sample(wx, wy, wz, 0, 0, 0, cellBounds, false);
                        BlockSpec pick = material.pick(sm);
                        if (k == FULL) {
                            canvas.setWorld(wx, wy, wz, canvas.orient(pick), false);
                            written++;
                            continue;
                        }
                        BlockSpec partial = partial(canvas, pick, k, facing[i]);
                        if (partial != null) {
                            canvas.setWorld(wx, wy, wz, partial, true);
                            written++;
                        } else if (k == STAIR_BOTTOM || k == STAIR_TOP || solidCentre(x, y, z)) {
                            canvas.setWorld(wx, wy, wz, canvas.orient(pick), false);
                            written++;
                        }
                    }
                }
            }
            return written;
        }

        private static BlockSpec partial(Canvas c, BlockSpec base, byte k, byte face) {
            if (base.name().endsWith("_stairs") || base.name().endsWith("_slab")) return null;
            try {
                if (k == SLAB_BOTTOM || k == SLAB_TOP) {
                    String slab = c.families.slab(base.name());
                    if (slab == null) return null;
                    return c.info.canonical(BlockSpec.of(slab, Map.of("type", k == SLAB_BOTTOM ? "bottom" : "top")));
                }
                String stairs = c.families.stairs(base.name());
                if (stairs == null) return null;
                return c.info.canonical(BlockSpec.of(stairs, Map.of("facing", SIDES[face], "half", k == STAIR_BOTTOM ? "bottom" : "top",
                        "shape", "straight")));
            } catch (RuntimeException e) {
                return null;
            }
        }

        /** Surface sample: outward normal from central differences and a crease flag. */
        private Material.Sample sample(int x, int y, int z, int[] bounds) {
            double e = 0.5;
            double gx = s.d(x + e, y, z) - s.d(x - e, y, z);
            double gy = s.d(x, y + e, z) - s.d(x, y - e, z);
            double gz = s.d(x, y, z + e) - s.d(x, y, z - e);
            double len = Math.sqrt(gx * gx + gy * gy + gz * gz);
            double nx = 0, ny = 0, nz = 0;
            if (len > 1e-9) {
                nx = gx / len;
                ny = gy / len;
                nz = gz / len;
            }
            int open = 0;
            if (s.d(x + 1, y, z) > 0 || s.d(x - 1, y, z) > 0) open++;
            if (s.d(x, y + 1, z) > 0 || s.d(x, y - 1, z) > 0) open++;
            if (s.d(x, y, z + 1) > 0 || s.d(x, y, z - 1) > 0) open++;
            // enclosure: share of 8 diagonal probes 2 blocks away that are inside (0.5 on a flat surface)
            int inside = 0;
            for (int k = 0; k < 8; k++) {
                if (s.d(x + ((k & 1) == 0 ? -1.8 : 1.8), y + ((k & 2) == 0 ? -1.8 : 1.8), z + ((k & 4) == 0 ? -1.8 : 1.8)) < 0) inside++;
            }
            return new Material.Sample(x, y, z, nx, ny, nz, bounds, open >= 2, inside / 8.0);
        }
    }
}
