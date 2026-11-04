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
 * @param confinements per confined part path (see {@code confine()}): its writes and the ones dropped as out of scope
 * @param anchors   named points and boxes from mark() and the kits, in world coordinates (the build sheet)
 * @param notes     intent lines per part path, from note()
 */
public record ScriptResult(VoxelModel model, ScriptError error, JsonObject meta, List<Part> parts,
                           Map<Long, String> owners, Set<Long> smoothed, List<String> logs, List<String> warnings,
                           long outside, long instructions, long elapsedMs, List<Doorway> doorways,
                           List<Collision> collisions, Map<String, int[]> overlaps,
                           Map<String, Canvas.Confinement> confinements,
                           List<Canvas.Anchor> anchors, Map<String, List<String>> notes, List<Option> options) {

    /**
     * A design choice the script left open with option(name, [choices]): {@code choices} as JSON text, {@code picked}
     * the index this run used, {@code fixed} when a pick() chose it (otherwise the first choice was the default).
     */
    public record Option(String name, List<String> choices, int picked, boolean fixed) {
    }

    /**
     * The build sheet: per part its intent notes and its anchors in world coordinates, at most {@code maxRows}
     * rows, for every stage after the first design. Empty when the script set none.
     */
    public String sheet(int maxRows) {
        Map<String, List<String>> rows = new java.util.LinkedHashMap<>();
        notes.forEach((p, list) -> list.forEach(n -> rows.computeIfAbsent(p, q -> new java.util.ArrayList<>()).add("note: " + n)));
        for (Canvas.Anchor a : anchors) rows.computeIfAbsent(a.part(), q -> new java.util.ArrayList<>()).add(a.describe());
        if (rows.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int n = 0, total = rows.values().stream().mapToInt(List::size).sum();
        for (Map.Entry<String, List<String>> e : rows.entrySet()) {
            if (n >= maxRows) break;
            sb.append("- ").append(e.getKey()).append('\n');
            for (String r : e.getValue()) {
                if (n++ >= maxRows) break;
                sb.append("    ").append(r).append('\n');
            }
        }
        if (total > maxRows) sb.append("- … ").append(total - maxRows).append(" more rows\n");
        return sb.toString();
    }

    /** The sheet as a prompt section with its heading, or empty when there is no sheet. */
    public String sheetSection() {
        String s = sheet(60);
        return s.isEmpty() ? "" : "BUILD SHEET (each part's intent and its key points in world coordinates; in code,"
                + " anchor('name') returns one, so place details relative to it instead of typing coordinates):\n" + s;
    }

    /** The confinement of the part whose path ends in {@code name} (e.g. "keep" for "castle/keep"), or null. */
    public Canvas.Confinement confinement(String name) {
        for (Map.Entry<String, Canvas.Confinement> e : confinements.entrySet()) {
            String p = e.getKey();
            if (p.equals(name) || p.endsWith("/" + name)) return e.getValue();
        }
        return null;
    }

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
