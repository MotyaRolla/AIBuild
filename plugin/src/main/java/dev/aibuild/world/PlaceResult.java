package dev.aibuild.world;

/**
 * Outcome of one {@link WorldPlacer#place} call.
 *
 * @param changed   number of world block states actually modified (cells already in the desired state
 *                  are not counted)
 * @param captured  number of terrain backup entries newly recorded in {@code build.terrain}
 * @param millis    wall-clock duration of the placement in milliseconds
 * @param cancelled whether the placement stopped early because {@link WorldPlacer#cancel(String)} was called
 */
public record PlaceResult(int changed, int captured, long millis, boolean cancelled) {

    /** True when the placement ran to completion without being cancelled. */
    public boolean completed() {
        return !cancelled;
    }

    /** A short human readable form for command output, e.g. {@code "placed 1240 blocks in 0.4s"}. */
    public String describe() {
        String base = "changed " + changed + " block" + (changed == 1 ? "" : "s")
                + " (" + captured + " captured) in " + millis + " ms";
        return cancelled ? base + " [cancelled]" : base;
    }
}
