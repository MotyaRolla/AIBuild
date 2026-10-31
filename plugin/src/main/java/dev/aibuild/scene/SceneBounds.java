package dev.aibuild.scene;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import java.util.Locale;

/**
 * Inclusive integer bounding box of a {@link SceneSpec} in model space (+x = east, +y = up,
 * +z = south), computed from the object primitives without building any geometry.
 *
 * <p>This is the cheap "where is the building?" query the geometry builder needs before it can
 * resolve a standalone opening: a window that is not nested in a wall has to search for its host
 * from outside the whole scene inwards. It is also handy for tests and for the renderer's camera
 * framing, which is why it lives in its own small type instead of a private helper.</p>
 *
 * <p>Instances are immutable; {@link #min()} and friends return copies so callers cannot corrupt
 * the box. An empty spec yields {@link #isEmpty()} with all coordinates at zero, never a
 * {@code MAX_VALUE} sentinel.</p>
 */
public final class SceneBounds {

    private final int[] min;
    private final int[] max;
    private final boolean empty;

    private SceneBounds(int[] min, int[] max, boolean empty) {
        this.min = min;
        this.max = max;
        this.empty = empty;
    }

    /**
     * Computes the bounds of every primitive of a spec.
     *
     * <p>Uses the same inputs as {@link SceneSpec#bounds()} plus a correct treatment of {@code at}:
     * for windows, doors and arches {@code at} is a two-element {@code [u, y]} wall coordinate, so it
     * is projected onto the wall's u axis instead of being mistaken for a 3D point; for columns and
     * chimneys {@code at} is a real {@code [x, y, z]} anchor and its height is included.</p>
     *
     * @param spec the spec to measure; must not be {@code null}
     * @return the inclusive bounds, or an empty box when the spec has no usable coordinates
     */
    public static SceneBounds from(SceneSpec spec) {
        int[] lo = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] hi = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        boolean[] any = {false};
        for (SceneObject o : spec.objects()) {
            grow(lo, hi, any, o.from());
            grow(lo, hi, any, o.to());
            grow(lo, hi, any, o.center());
            String type = o.type();
            int[] at = o.at();
            if (at != null) {
                if (type.equals("window") || type.equals("door") || type.equals("arch")) {
                    growAperture(lo, hi, any, o, at);
                } else {
                    grow(lo, hi, any, new int[]{at[0], at.length > 1 ? at[1] : 0, at.length > 2 ? at[2] : 0});
                    int height = Math.max(1, SceneObject.num(o.raw(), "height", 1));
                    if (type.equals("column") || type.equals("chimney")) {
                        int[] top = {at[0], (at.length > 1 ? at[1] : 0) + height - 1,
                                at.length > 2 ? at[2] : 0};
                        grow(lo, hi, any, top);
                    }
                }
            }
            JsonElement cells = o.raw().get("cells");
            if (cells != null && cells.isJsonArray()) {
                for (JsonElement c : cells.getAsJsonArray()) {
                    if (!c.isJsonArray()) continue;
                    JsonArray a = c.getAsJsonArray();
                    if (a.size() < 3) continue;
                    grow(lo, hi, any, new int[]{a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()});
                }
            }
        }
        if (!any[0]) return new SceneBounds(new int[]{0, 0, 0}, new int[]{0, 0, 0}, true);
        return new SceneBounds(lo, hi, false);
    }

    /**
     * Creates a box from explicit corners.
     *
     * @param min minimal corner, three entries
     * @param max maximal corner, three entries
     * @return the normalised box (corners are reordered so {@code min <= max})
     */
    public static SceneBounds of(int[] min, int[] max) {
        int[] lo = new int[3];
        int[] hi = new int[3];
        for (int k = 0; k < 3; k++) {
            lo[k] = Math.min(min[k], max[k]);
            hi[k] = Math.max(min[k], max[k]);
        }
        return new SceneBounds(lo, hi, false);
    }

    /** @return true when the spec contributed no coordinates at all */
    public boolean isEmpty() {
        return empty;
    }

    /**
     * @param axis 0 = x, 1 = y, 2 = z
     * @return the minimal coordinate on that axis (0 for an empty box)
     */
    public int min(int axis) {
        return min[axis];
    }

    /**
     * @param axis 0 = x, 1 = y, 2 = z
     * @return the maximal coordinate on that axis (0 for an empty box)
     */
    public int max(int axis) {
        return max[axis];
    }

    /**
     * @param axis 0 = x, 1 = y, 2 = z
     * @return the inclusive size on that axis (1 for an empty box)
     */
    public int size(int axis) {
        return max[axis] - min[axis] + 1;
    }

    /** @return a copy of the minimal corner */
    public int[] min() {
        return min.clone();
    }

    /** @return a copy of the maximal corner */
    public int[] max() {
        return max.clone();
    }

    /** @return a copy of {@code [sizeX, sizeY, sizeZ]} */
    public int[] size() {
        return new int[]{size(0), size(1), size(2)};
    }

    /**
     * Returns a copy grown by the same margin on every side.
     *
     * @param margin cells to add on each side; negative values shrink the box (clamped so it never
     *               collapses below one cell)
     * @return a new box
     */
    public SceneBounds expand(int margin) {
        if (empty) return this;
        int[] lo = new int[3];
        int[] hi = new int[3];
        for (int k = 0; k < 3; k++) {
            lo[k] = min[k] - margin;
            hi[k] = Math.max(lo[k], max[k] + margin);
        }
        return new SceneBounds(lo, hi, false);
    }

    /**
     * @param x world x
     * @param y world y
     * @param z world z
     * @return true when the cell lies inside the inclusive box
     */
    public boolean contains(int x, int y, int z) {
        return x >= min[0] && x <= max[0] && y >= min[1] && y <= max[1] && z >= min[2] && z <= max[2];
    }

    @Override
    public String toString() {
        if (empty) return "SceneBounds[empty]";
        return "SceneBounds[min=" + min[0] + "," + min[1] + "," + min[2]
                + " max=" + max[0] + "," + max[1] + "," + max[2]
                + " size=" + size(0) + "x" + size(1) + "x" + size(2) + "]";
    }

    private static void grow(int[] lo, int[] hi, boolean[] any, int[] v) {
        if (v == null) return;
        any[0] = true;
        for (int k = 0; k < 3 && k < v.length; k++) {
            if (v[k] < lo[k]) lo[k] = v[k];
            if (v[k] > hi[k]) hi[k] = v[k];
        }
    }

    /**
     * Projects a wall opening's {@code [u, y]} anchor onto world axes and covers its whole aperture
     * (including the arched top and the one-cell frame), so the bounds contain everything the
     * opening may build.
     */
    private static void growAperture(int[] lo, int[] hi, boolean[] any, SceneObject o, int[] at) {
        String wall = SceneObject.str(o.raw(), "wall").toLowerCase(Locale.ROOT);
        int uAxis = wall.equals("east") || wall.equals("west") ? 2 : 0;
        int y0 = at.length > 1 ? at[1] : 0;
        int w = Math.max(1, (int) Math.round(SceneObject.dbl(o.raw(), "w", 1)));
        int h = Math.max(1, (int) Math.round(SceneObject.dbl(o.raw(), "h", 2)));
        int extra = SceneObject.bool(o.raw(), "arch", false) ? (int) Math.ceil(w / 2.0) : 0;
        for (int u = at[0] - 1; u <= at[0] + w; u++) {
            for (int y = y0 - 1; y <= y0 + h - 1 + extra + 1; y++) {
                int[] p = {0, y, 0};
                p[uAxis] = u;
                grow(lo, hi, any, p);
            }
        }
    }
}
