package dev.aibuild.script.geom;

/**
 * An affine frame {@code p' = M·p + t} used both for shape transforms and for the script's coordinate stack.
 *
 * <p>Rotation angles follow the plugin's {@code copy rotate} convention: a positive Y angle turns clockwise
 * seen from above, so one quarter turn maps north to east and {@code (x, z)} to {@code (-z, x)}.
 * Besides the matrix, the frame tracks the exact block-state transform it implies ({@link #quarterTurns()},
 * {@link #mirrorX()}), or marks that no exact state transform exists ({@link #exactStates()} false) when the
 * frame contains a non-quarter rotation, a tilt or a non-unit scale.</p>
 */
public final class Frame {
    private static final Frame IDENTITY = new Frame(new double[][]{{1, 0, 0}, {0, 1, 0}, {0, 0, 1}},
            new double[3], 0, false, true);

    private final double[][] m;
    private final double[][] inv;
    private final double[] t;
    private final int quarterTurns;
    private final boolean mirrorX;
    private final boolean exactStates;

    private Frame(double[][] m, double[] t, int quarterTurns, boolean mirrorX, boolean exactStates) {
        this.m = m;
        this.t = t;
        this.quarterTurns = Math.floorMod(quarterTurns, 4);
        this.mirrorX = mirrorX;
        this.exactStates = exactStates;
        double d = m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1])
                - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
                + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
        if (!Double.isFinite(d) || Math.abs(d) < 1e-9) throw new IllegalArgumentException("singular transform");
        inv = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                inv[j][i] = (m[(i + 1) % 3][(j + 1) % 3] * m[(i + 2) % 3][(j + 2) % 3]
                        - m[(i + 1) % 3][(j + 2) % 3] * m[(i + 2) % 3][(j + 1) % 3]) / d;
            }
        }
    }

    public static Frame identity() {
        return IDENTITY;
    }

    public static Frame translate(double x, double y, double z) {
        return new Frame(IDENTITY.m, new double[]{x, y, z}, 0, false, true);
    }

    /** Clockwise rotation (seen from above) by {@code degrees} around the vertical axis through the origin. */
    public static Frame rotY(double degrees) {
        double r = Math.toRadians(degrees), c = clean(Math.cos(r)), s = clean(Math.sin(r));
        double q = degrees / 90.0;
        boolean quarter = Math.abs(q - Math.rint(q)) < 1e-9;
        return new Frame(new double[][]{{c, 0, -s}, {0, 1, 0}, {s, 0, c}}, new double[3],
                quarter ? (int) Math.rint(q) : 0, false, quarter);
    }

    /** Rotation around the X axis (tilt north/south); block states cannot follow it exactly. */
    public static Frame rotX(double degrees) {
        double r = Math.toRadians(degrees), c = clean(Math.cos(r)), s = clean(Math.sin(r));
        return new Frame(new double[][]{{1, 0, 0}, {0, c, -s}, {0, s, c}}, new double[3], 0, false, isZeroAngle(degrees));
    }

    /** Rotation around the Z axis (tilt east/west); block states cannot follow it exactly. */
    public static Frame rotZ(double degrees) {
        double r = Math.toRadians(degrees), c = clean(Math.cos(r)), s = clean(Math.sin(r));
        return new Frame(new double[][]{{c, -s, 0}, {s, c, 0}, {0, 0, 1}}, new double[3], 0, false, isZeroAngle(degrees));
    }

    public static Frame scale(double sx, double sy, double sz) {
        for (double v : new double[]{sx, sy, sz}) {
            if (!Double.isFinite(v) || Math.abs(v) < 0.05 || Math.abs(v) > 32) {
                throw new IllegalArgumentException("scale must be 0.05..32");
            }
        }
        boolean unit = sx == 1 && sy == 1 && sz == 1;
        return new Frame(new double[][]{{sx, 0, 0}, {0, sy, 0}, {0, 0, sz}}, new double[3], 0, false, unit);
    }

    /** Mirror across the plane {@code axis = 0}; 'x' flips east/west, 'z' flips north/south. */
    public static Frame mirror(char axis) {
        double[][] mm = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
        switch (axis) {
            case 'x' -> {
                mm[0][0] = -1;
                return new Frame(mm, new double[3], 0, true, true);
            }
            case 'z' -> {
                // mirror z == rotate 180° then mirror x
                mm[2][2] = -1;
                return new Frame(mm, new double[3], 2, true, true);
            }
            case 'y' -> {
                mm[1][1] = -1;
                return new Frame(mm, new double[3], 0, false, false);
            }
            default -> throw new IllegalArgumentException("mirror axis must be x, y or z");
        }
    }

    /** {@code this ∘ local}: points are first transformed by {@code local}, then by this frame. */
    public Frame then(Frame local) {
        double[][] a = new double[3][3];
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) for (int k = 0; k < 3; k++) a[i][j] += m[i][k] * local.m[k][j];
        // state transform: S(this) ∘ S(local), with S = rot^q ∘ mirror^b
        // rot^q ∘ mx^b ∘ rot^r ∘ mx^c = rot^(q + (b ? -r : r)) ∘ mx^(b xor c)
        int q = quarterTurns + (mirrorX ? -local.quarterTurns : local.quarterTurns);
        return new Frame(a, point(local.t), q, mirrorX ^ local.mirrorX, exactStates && local.exactStates);
    }

    public double[] point(double[] p) {
        return point(p[0], p[1], p[2]);
    }

    public double[] point(double x, double y, double z) {
        return new double[]{t[0] + m[0][0] * x + m[0][1] * y + m[0][2] * z,
                t[1] + m[1][0] * x + m[1][1] * y + m[1][2] * z,
                t[2] + m[2][0] * x + m[2][1] * y + m[2][2] * z};
    }

    /** Maps a world point back into this frame's local space. */
    public double[] inverse(double x, double y, double z) {
        x -= t[0];
        y -= t[1];
        z -= t[2];
        return new double[]{inv[0][0] * x + inv[0][1] * y + inv[0][2] * z,
                inv[1][0] * x + inv[1][1] * y + inv[1][2] * z,
                inv[2][0] * x + inv[2][1] * y + inv[2][2] * z};
    }

    /** Smallest per-axis factor by which this frame can shrink distances (keeps SDFs conservative). */
    public double minStretch() {
        double best = Double.POSITIVE_INFINITY;
        for (int j = 0; j < 3; j++) {
            double len = Math.sqrt(m[0][j] * m[0][j] + m[1][j] * m[1][j] + m[2][j] * m[2][j]);
            best = Math.min(best, len);
        }
        return best;
    }

    /** Bounds of the transformed box {@code b = {minX,minY,minZ,maxX,maxY,maxZ}}. */
    public double[] bounds(double[] b) {
        double[] out = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (int i = 0; i < 8; i++) {
            double[] p = point((i & 1) == 0 ? b[0] : b[3], (i & 2) == 0 ? b[1] : b[4], (i & 4) == 0 ? b[2] : b[5]);
            for (int k = 0; k < 3; k++) {
                out[k] = Math.min(out[k], p[k]);
                out[k + 3] = Math.max(out[k + 3], p[k]);
            }
        }
        return out;
    }

    public boolean isIdentity() {
        return this == IDENTITY || (t[0] == 0 && t[1] == 0 && t[2] == 0 && m[0][0] == 1 && m[1][1] == 1 && m[2][2] == 1
                && m[0][1] == 0 && m[0][2] == 0 && m[1][0] == 0 && m[1][2] == 0 && m[2][0] == 0 && m[2][1] == 0);
    }

    /** Clockwise quarter turns block states must be rotated by (valid when {@link #exactStates()}). */
    public int quarterTurns() {
        return quarterTurns;
    }

    /** Whether block states must be mirrored east/west before rotating. */
    public boolean mirrorX() {
        return mirrorX;
    }

    /** False when the frame tilts, scales or turns by a non-quarter angle: states then keep their own facing. */
    public boolean exactStates() {
        return exactStates;
    }

    private static double clean(double v) {
        return Math.abs(v) < 1e-12 ? 0 : Math.abs(v - Math.rint(v)) < 1e-12 ? Math.rint(v) : v;
    }


    private static boolean isZeroAngle(double degrees) {
        return Math.abs(Math.IEEEremainder(degrees, 360)) < 1e-9;
    }
}
