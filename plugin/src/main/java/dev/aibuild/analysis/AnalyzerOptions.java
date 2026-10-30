package dev.aibuild.analysis;

import java.util.Objects;

/**
 * Options for {@link StructureAnalyzer#analyze}. Mutable public-field bean with fluent setters so callers can
 * write {@code new AnalyzerOptions().expectInterior(false).expectedFootprint(11, 9)}.
 *
 * <p>Not thread-safe (intended to be configured once, then read). {@link #copy()} hands a snapshot to
 * another thread. A null {@code opts} passed to the analyzer is equivalent to {@code new AnalyzerOptions()}.</p>
 */
public class AnalyzerOptions {

    /**
     * When true (default) the analyzer expects a walkable interior: reports {@code no_enclosed_interior} and
     * {@code no_door} when missing. Set to false for open builds (ruins, bridges, monuments, terraces).
     */
    public boolean expectInterior = true;

    /**
     * Expected {@code {sizeX, sizeZ}} of the main body as seen at the wall layer (y = 1), or null to skip the
     * check. A mismatch of more than one block on either horizontal axis is reported as
     * {@code footprint_mismatch}. The roof may overhang further (that is not a mismatch).
     */
    public int[] expectedFootprint = null;

    /** Default options: interior expected, no footprint check. */
    public AnalyzerOptions() {
    }

    /** @param expectInterior see {@link #expectInterior} */
    public AnalyzerOptions(boolean expectInterior) {
        this.expectInterior = expectInterior;
    }

    /** Fluent setter for {@link #expectInterior}. Returns {@code this}. */
    public AnalyzerOptions expectInterior(boolean value) {
        this.expectInterior = value;
        return this;
    }

    /**
     * Fluent setter for {@link #expectedFootprint}.
     *
     * @param sizeX expected x size, {@code <= 0} disables, or both values {@code <= 0} / null array to disable
     * @param sizeZ expected z size, {@code <= 0} disables
     * @return {@code this}
     */
    public AnalyzerOptions expectedFootprint(int sizeX, int sizeZ) {
        this.expectedFootprint = (sizeX <= 0 || sizeZ <= 0) ? null : new int[]{sizeX, sizeZ};
        return this;
    }

    /** Fluent setter taking a raw {@code {sizeX,sizeZ}} array (null or invalid disables the check). */
    public AnalyzerOptions expectedFootprint(int[] footprint) {
        if (footprint == null || footprint.length < 2 || footprint[0] <= 0 || footprint[1] <= 0) {
            this.expectedFootprint = null;
        } else {
            this.expectedFootprint = new int[]{footprint[0], footprint[1]};
        }
        return this;
    }

    /** Deep copy (the footprint array is duplicated). */
    public AnalyzerOptions copy() {
        AnalyzerOptions o = new AnalyzerOptions(expectInterior);
        o.expectedFootprint = expectedFootprint == null ? null : expectedFootprint.clone();
        return o;
    }

    /** The expected footprint as a copy, or null. */
    public int[] footprintCopy() {
        return expectedFootprint == null ? null : expectedFootprint.clone();
    }

    @Override
    public String toString() {
        String fp = expectedFootprint == null ? "null" : expectedFootprint[0] + "x" + expectedFootprint[1];
        return "AnalyzerOptions{expectInterior=" + expectInterior + ", expectedFootprint=" + fp + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AnalyzerOptions other)) return false;
        return expectInterior == other.expectInterior
                && java.util.Arrays.equals(expectedFootprint, other.expectedFootprint);
    }

    @Override
    public int hashCode() {
        return Objects.hash(expectInterior, java.util.Arrays.hashCode(expectedFootprint));
    }
}
