package dev.aibuild.script;

import java.util.Arrays;
import java.util.SplittableRandom;

/**
 * Seeded 3D gradient noise (Perlin's improved noise, two octaves) with a uniformizing transform.
 *
 * <p>Raw gradient noise piles up around zero, so weighted material mixes would ignore their weights.
 * {@link #uniform} maps a sample through the empirical distribution measured once per instance, which
 * makes {@code mix({a:3, b:1})} cover roughly 75% / 25% of a surface while keeping spatial clusters.</p>
 */
public final class Noise {
    private static final int[][] GRAD = {{1, 1, 0}, {-1, 1, 0}, {1, -1, 0}, {-1, -1, 0}, {1, 0, 1}, {-1, 0, 1},
            {1, 0, -1}, {-1, 0, -1}, {0, 1, 1}, {0, -1, 1}, {0, 1, -1}, {0, -1, -1}};

    private final int[] perm = new int[512];
    private final double[] cdf;

    public Noise(long seed) {
        int[] p = new int[256];
        for (int i = 0; i < 256; i++) p[i] = i;
        SplittableRandom r = new SplittableRandom(seed);
        for (int i = 255; i > 0; i--) {
            int j = r.nextInt(i + 1);
            int t = p[i];
            p[i] = p[j];
            p[j] = t;
        }
        for (int i = 0; i < 512; i++) perm[i] = p[i & 255];
        cdf = new double[2048];
        SplittableRandom s = new SplittableRandom(seed ^ 0x5DEECE66DL);
        for (int i = 0; i < cdf.length; i++) {
            cdf[i] = fbm(s.nextDouble() * 256, s.nextDouble() * 256, s.nextDouble() * 256);
        }
        Arrays.sort(cdf);
    }

    /** Two-octave noise in roughly [-1, 1]. */
    public double fbm(double x, double y, double z) {
        return (noise(x, y, z) + 0.5 * noise(x * 2.03 + 17.1, y * 2.03 + 3.7, z * 2.03 + 9.3)) / 1.5;
    }

    /** Spatially coherent value in [0, 1) with an approximately uniform distribution. */
    public double uniform(double x, double y, double z) {
        double v = fbm(x, y, z);
        int i = Arrays.binarySearch(cdf, v);
        if (i < 0) i = -i - 1;
        return Math.min(0.999999, (double) i / cdf.length);
    }

    public double noise(double x, double y, double z) {
        int xi = (int) Math.floor(x), yi = (int) Math.floor(y), zi = (int) Math.floor(z);
        double xf = x - xi, yf = y - yi, zf = z - zi;
        xi &= 255;
        yi &= 255;
        zi &= 255;
        double u = fade(xf), v = fade(yf), w = fade(zf);
        int a = perm[xi] + yi, aa = perm[a] + zi, ab = perm[a + 1] + zi;
        int b = perm[xi + 1] + yi, ba = perm[b] + zi, bb = perm[b + 1] + zi;
        return lerp(w,
                lerp(v, lerp(u, grad(perm[aa], xf, yf, zf), grad(perm[ba], xf - 1, yf, zf)),
                        lerp(u, grad(perm[ab], xf, yf - 1, zf), grad(perm[bb], xf - 1, yf - 1, zf))),
                lerp(v, lerp(u, grad(perm[aa + 1], xf, yf, zf - 1), grad(perm[ba + 1], xf - 1, yf, zf - 1)),
                        lerp(u, grad(perm[ab + 1], xf, yf - 1, zf - 1), grad(perm[bb + 1], xf - 1, yf - 1, zf - 1))));
    }

    /** Deterministic white noise in [0, 1) for a cell and a salt. */
    public static double hash(long seed, int x, int y, int z, int salt) {
        long h = seed * 0x9E3779B97F4A7C15L + x * 0x632BE59BD9B4E019L + y * 0x85157AF5L + z * 0x5851F42D4C957F2DL + salt * 0x14057B7EF767814FL;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    private static double grad(int h, double x, double y, double z) {
        int[] g = GRAD[h % 12];
        return g[0] * x + g[1] * y + g[2] * z;
    }
}
