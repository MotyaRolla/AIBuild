package dev.aibuild.script;

import com.google.gson.JsonObject;
import dev.aibuild.model.VoxelModel;
import dev.aibuild.model.ops.Doorway;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Outcome of one script run.
 *
 * @param model     the produced model (never null; may be partial when {@code error} is set)
 * @param error     the failure, or null when the script completed
 * @param meta      the object passed to {@code meta({...})}, or an empty object
 * @param parts     named parts with cell counts and bounds
 * @param owners    owning part per non-air cell key
 * @param smoothed  cells placed by the smoother (keep their orientation)
 * @param logs      {@code log(...)} output, clipped
 * @param warnings  non-fatal problems (cells outside the plot, unknown options...)
 * @param outside   number of writes dropped because they fell outside the plot
 * @param instructions interpreter instructions used
 * @param elapsedMs wall time of the run
 * @param doorways  doors placed through door()/op(), restored after post-processing
 * @param collisions damage the script did to blocks it had built (decoration in walls, stairs removed)
 * @param overlaps  wall cells one top-level part overwrote in another: "victim|culprit" -> {cells, x1, y1, z1, x2, y2, z2}
 */
public record ScriptResult(VoxelModel model, ScriptError error, JsonObject meta, List<Part> parts,
                           Map<Long, String> owners, Set<Long> smoothed, List<String> logs, List<String> warnings,
                           long outside, long instructions, long elapsedMs, List<Doorway> doorways,
                           List<Collision> collisions, Map<String, int[]> overlaps) {

    /** A named part: {@code bounds} = {minX,minY,minZ,maxX,maxY,maxZ}. */
    public record Part(String name, int cells, int[] bounds) {
        public String describe() {
            return name + ": " + cells + " blocks, x " + bounds[0] + ".." + bounds[3] + ", y " + bounds[1] + ".." + bounds[4]
                    + ", z " + bounds[2] + ".." + bounds[5];
        }
    }

    /** Collisions of 8 or more cells, the ones worth a fix, one line each; empty when there are none. */
    public String collisionText() {
        StringBuilder sb = new StringBuilder();
        for (Collision c : collisions) if (c.cells() >= COLLISION_MIN) sb.append("- ").append(c.describe()).append('\n');
        return sb.toString();
    }

    /** Damaged cells in collisions of 8 or more cells. */
    public int collisionCells() {
        return collisions.stream().filter(c -> c.cells() >= COLLISION_MIN).mapToInt(Collision::cells).sum();
    }

    public static final int COLLISION_MIN = 8;

    public boolean ok() {
        return error == null;
    }

    /** One line per part, largest first, for prompts and transcripts. */
    public String partTable(int max) {
        StringBuilder sb = new StringBuilder();
        parts.stream().sorted((a, b) -> Integer.compare(b.cells(), a.cells())).limit(max)
                .forEach(p -> sb.append("- ").append(p.describe()).append('\n'));
        if (parts.size() > max) sb.append("- … ").append(parts.size() - max).append(" smaller parts\n");
        return sb.toString();
    }
}
