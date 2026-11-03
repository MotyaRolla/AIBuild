package dev.aibuild.script;

import dev.aibuild.model.BlockFamilies;
import dev.aibuild.model.BlockPattern;
import dev.aibuild.model.BlockSpec;

import java.util.List;

/**
 * Chooses the block for one cell. Materials nest: a gradient of noise mixes, a weathered normal-based
 * material, and so on. All choices are deterministic functions of the cell and the script seed.
 */
public interface Material {

    BlockSpec pick(Sample s);

    /** The first block this material can produce, used for previews, families and smoothing. */
    BlockSpec representative();

    /**
     * Where a cell sits. {@code nx, ny, nz} is the outward surface normal when the cell lies on a shape surface
     * (all zero otherwise); {@code bounds} are the placed shape's cell bounds {minX,minY,minZ,maxX,maxY,maxZ};
     * {@code edge} marks cells on a crease of the surface; {@code occ} is how enclosed the surface point is
     * (about 0.5 on a flat surface, more in creases and hollows, less on ridges and tips; 0.5 when unknown).
     */
    record Sample(int x, int y, int z, double nx, double ny, double nz, int[] bounds, boolean edge, double occ) {
        public Sample(int x, int y, int z, double nx, double ny, double nz, int[] bounds, boolean edge) {
            this(x, y, z, nx, ny, nz, bounds, edge, 0.5);
        }

        public static Sample at(int x, int y, int z) {
            return new Sample(x, y, z, 0, 0, 0, null, false);
        }

        boolean hasNormal() {
            return nx != 0 || ny != 0 || nz != 0;
        }
    }

    record Fixed(BlockSpec spec) implements Material {
        @Override
        public BlockSpec pick(Sample s) {
            return spec;
        }

        @Override
        public BlockSpec representative() {
            return spec;
        }
    }

    /** A legacy weighted pattern ({@code "70%stone_bricks,30%cobblestone"}): white noise per cell. */
    record Pattern(BlockPattern pattern) implements Material {
        @Override
        public BlockSpec pick(Sample s) {
            return pattern.pick(s.x(), s.y(), s.z());
        }

        @Override
        public BlockSpec representative() {
            return pattern.first();
        }
    }

    /** Weighted choice driven by coherent noise of feature size {@code scale}, so variants form natural patches. */
    record Mix(List<Material> options, double[] weights, double scale, Noise noise) implements Material {
        @Override
        public BlockSpec pick(Sample s) {
            double u = scale <= 0 ? Noise.hash(31, s.x(), s.y(), s.z(), 7)
                    : noise.uniform(s.x() / scale, s.y() / scale, s.z() / scale);
            double total = 0;
            for (double w : weights) total += w;
            double acc = 0;
            for (int i = 0; i < options.size(); i++) {
                acc += weights[i] / total;
                if (u < acc) return options.get(i).pick(s);
            }
            return options.getLast().pick(s);
        }

        @Override
        public BlockSpec representative() {
            int best = 0;
            for (int i = 1; i < weights.length; i++) if (weights[i] > weights[best]) best = i;
            return options.get(best).representative();
        }
    }

    /**
     * Bands along an axis ({@code x}, {@code y}, {@code z}) with dithered transitions. Stops are positions in
     * [0, 1] across {@code from..to} (absolute coordinates) or, when those are NaN, across the shape's bounds.
     */
    record Gradient(char axis, double[] stops, List<Material> materials, double from, double to, Noise noise) implements Material {
        @Override
        public BlockSpec pick(Sample s) {
            int k = axis == 'x' ? 0 : axis == 'z' ? 2 : 1;
            double c = k == 0 ? s.x() : k == 1 ? s.y() : s.z();
            double lo = from, hi = to;
            if (Double.isNaN(lo) || Double.isNaN(hi)) {
                if (s.bounds() == null) return materials.getFirst().pick(s);
                lo = s.bounds()[k];
                hi = s.bounds()[k + 3];
            }
            double t = hi == lo ? 0 : (c - lo) / (hi - lo);
            double u = noise.uniform(s.x() / 2.5 + 40, s.y() / 2.5, s.z() / 2.5 - 40);
            if (t <= stops[0]) return materials.getFirst().pick(s);
            for (int i = 0; i + 1 < stops.length; i++) {
                if (t <= stops[i + 1]) {
                    double f = (t - stops[i]) / Math.max(1e-9, stops[i + 1] - stops[i]);
                    return (u < f ? materials.get(i + 1) : materials.get(i)).pick(s);
                }
            }
            return materials.getLast().pick(s);
        }

        @Override
        public BlockSpec representative() {
            return materials.getFirst().representative();
        }
    }

    /** Different blocks for upward, sideways and downward facing surface cells (interior cells use {@code side}). */
    record ByNormal(Material top, Material side, Material bottom) implements Material {
        @Override
        public BlockSpec pick(Sample s) {
            if (s.hasNormal()) {
                if (s.ny() > 0.6) return top.pick(s);
                if (s.ny() < -0.6) return bottom.pick(s);
            }
            return side.pick(s);
        }

        @Override
        public BlockSpec representative() {
            return side.representative();
        }
    }

    /** Replaces a share of cells by mossy/cracked/exposed variants in coherent patches. */
    record Weather(Material base, double amount, Noise noise, BlockFamilies families) implements Material {
        @Override
        public BlockSpec pick(Sample s) {
            BlockSpec b = base.pick(s);
            if (amount <= 0) return b;
            double u = noise.uniform(s.x() / 3.0 - 91, s.y() / 3.0 + 13, s.z() / 3.0 + 57);
            if (u >= amount) return b;
            List<String> v = families.weathered(b.name());
            if (v.isEmpty()) return b;
            String name = v.get((int) (Noise.hash(77, s.x(), s.y(), s.z(), 3) * v.size()));
            return BlockSpec.of(name, b.props());
        }

        @Override
        public BlockSpec representative() {
            return base.representative();
        }
    }

    /**
     * Sculptural shading: picks a step of a dark-to-light {@code ramp} per cell from how the surface faces the
     * light (wrapped Lambert), its height within the shape (darker toward the ground) and how enclosed it is
     * (creases darker, ridges lighter), with a dithered transition between steps — the way builders shade statues.
     */
    record Shade(List<Material> ramp, double[] light, double heightWeight, double cavityWeight, Noise noise) implements Material {
        @Override
        public BlockSpec pick(Sample s) {
            int n = ramp.size();
            double v;
            if (s.hasNormal()) {
                double lam = (s.nx() * light[0] + s.ny() * light[1] + s.nz() * light[2] + 1) / 2;
                v = 0.62 * lam + 0.38 * 0.5;
            } else {
                v = 0.45;
            }
            if (s.bounds() != null && s.bounds()[4] > s.bounds()[1]) {
                double t = (s.y() - s.bounds()[1]) / (double) (s.bounds()[4] - s.bounds()[1]);
                v += heightWeight * (t - 0.5);
            }
            v -= cavityWeight * (s.occ() - 0.5);
            double dither = (noise.uniform(s.x() / 1.7 + 11, s.y() / 1.7 - 5, s.z() / 1.7 + 3) - 0.5) / Math.max(2, n - 1);
            int i = (int) Math.floor(Math.clamp(v + dither, 0, 0.9999) * n);
            return ramp.get(i).pick(s);
        }

        @Override
        public BlockSpec representative() {
            return ramp.get(ramp.size() / 2).representative();
        }
    }

    /** {@code edge} on creases and corners of the surface, {@code base} elsewhere. */
    record Trim(Material base, Material edge) implements Material {
        @Override
        public BlockSpec pick(Sample s) {
            return (s.edge() ? edge : base).pick(s);
        }

        @Override
        public BlockSpec representative() {
            return base.representative();
        }
    }
}
