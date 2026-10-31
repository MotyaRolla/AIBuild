package dev.aibuild.model.ops;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.aibuild.model.BlockInfo;
import dev.aibuild.model.BlockPattern;
import dev.aibuild.model.BlockSpec;
import dev.aibuild.model.Dir;
import dev.aibuild.model.VoxelModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Executes the build DSL: a JSON array of operation objects (or a wrapper object with an {@code ops} array)
 * is applied to a {@link VoxelModel}. Designed for untrusted LLM output: every field is read leniently, every
 * operation is validated before the first write, and a failing operation never aborts the batch.
 *
 * <h2>Batch format</h2>
 * <pre>{@code
 * {"ops": [{"op":"fill","from":[0,1,0],"to":[9,3,9],"block":"oak_planks"}, ...]}
 * }</pre>
 * The elements may also be given directly as a array. The wrapper key may be {@code ops}, {@code operations}
 * or {@code actions}. Each operation needs an {@code op} field (aliases {@code type}, {@code action},
 * {@code cmd}).
 *
 * <h2>Operations</h2>
 * <ul>
 *     <li>{@code fill} ({@code box}, {@code cuboid}) – solid box from/to.</li>
 *     <li>{@code set} ({@code setblock}, {@code place}, {@code block}) – single {@code pos} or a list of
 *     {@code positions}.</li>
 *     <li>{@code clear} ({@code air}, {@code delete}, {@code remove}, {@code erase}) – box of air.</li>
 *     <li>{@code walls} ({@code wall}) – the four vertical side faces.</li>
 *     <li>{@code hollow} ({@code shell}) – six-face shell, interior air.</li>
 *     <li>{@code outline} ({@code frame}, {@code edges}) – the twelve edges, with automatic log axis.</li>
 *     <li>{@code line} – 3D Bresenham line, with automatic log axis.</li>
 *     <li>{@code replace} – replace matching blocks; region optional (whole model).</li>
 *     <li>{@code roof} – see {@link RoofBuilder}.</li>
 *     <li>{@code cylinder} ({@code tower}) and {@code sphere} ({@code dome}) – round volumes.</li>
 *     <li>{@code copy} ({@code mirror_copy}, {@code clone}) – copy a region with mirror / rotation.</li>
 * </ul>
 *
 * <p>Coordinate values accept {@code [x,y,z]} arrays (numbers, numeric strings, floats rounded) and
 * {@code {"x":..,"y":..,"z":..}} objects; region fields accept {@code from}/{@code to} and their aliases
 * ({@code start}, {@code p1}, {@code pos1}, {@code a} / {@code end}, {@code p2}, {@code pos2}, {@code b}).
 * Unknown fields are ignored, so explanatory keys such as {@code "note"} are harmless.</p>
 *
 * <h2>Limits</h2>
 * Defaults: at most 300 000 cells per operation, coordinates within ±256 and at most 1 500 000 cells in the
 * model. Violations are reported as errors and the operation is skipped; exceeding the model cell limit stops
 * the batch (the already applied operations stay applied).
 *
 * <p>Instances are stateless apart from the constructor configuration and can be reused, but a single
 * {@link VoxelModel} must not be shared between threads. {@link #apply} is not thread-safe for the same model.</p>
 */
public final class OpExecutor {
    /** Default maximum number of cells a single operation may touch. */
    public static final int DEFAULT_MAX_OP_VOLUME = 300_000;
    /** Default maximum absolute coordinate value. */
    public static final int DEFAULT_MAX_ABS_COORD = 256;
    /** Default maximum number of stored cells in the model. */
    public static final int DEFAULT_MAX_MODEL_CELLS = 1_500_000;

    private static final List<String> KNOWN_OPS = List.of(
            "box", "clear", "copy", "cylinder", "fill", "hollow", "line", "outline", "replace",
            "roof", "set", "sphere", "walls", "repeat", "trim", "staircase", "building");

    private final BlockInfo info;
    private final int maxOpVolume;
    private final int maxAbsCoord;
    private final int maxModelCells;

    /** Creates an executor with the default limits (300 000 cells/op, ±256, 1 500 000 model cells). */
    /** Allowed box {minX,minY,minZ,maxX,maxY,maxZ} or null = unlimited; writes outside are skipped. */
    private int[] limits;
    private int skippedInOp;

    /**
     * Restricts all writes to a box (the planned plot plus a margin). Cells outside are skipped and the op is
     * reported as failed with a clear message — this stops runaway/repetitive model output (e.g. endless stacks).
     */
    public OpExecutor withLimits(int[] box) {
        this.limits = box == null ? null : box.clone();
        return this;
    }

    public OpExecutor(BlockInfo info) {
        this(info, DEFAULT_MAX_OP_VOLUME, DEFAULT_MAX_ABS_COORD, DEFAULT_MAX_MODEL_CELLS);
    }

    /**
     * Creates an executor with explicit limits. Non-positive values fall back to the defaults.
     *
     * @param info          block registry used to validate patterns and transform states
     * @param maxOpVolume   maximum number of cells one operation may touch
     * @param maxAbsCoord   maximum absolute x/y/z coordinate
     * @param maxModelCells maximum number of stored cells in the model
     */
    public OpExecutor(BlockInfo info, int maxOpVolume, int maxAbsCoord, int maxModelCells) {
        this.info = Objects.requireNonNull(info, "info");
        this.maxOpVolume = maxOpVolume > 0 ? maxOpVolume : DEFAULT_MAX_OP_VOLUME;
        this.maxAbsCoord = maxAbsCoord > 0 ? maxAbsCoord : DEFAULT_MAX_ABS_COORD;
        this.maxModelCells = maxModelCells > 0 ? maxModelCells : DEFAULT_MAX_MODEL_CELLS;
    }

    public BlockInfo info() {
        return info;
    }

    public int maxOpVolume() {
        return maxOpVolume;
    }

    public int maxAbsCoord() {
        return maxAbsCoord;
    }

    public int maxModelCells() {
        return maxModelCells;
    }

    /**
     * Applies a batch of operations to {@code model}. Never throws for malformed input; every problem ends up
     * in {@link OpResult#errors} and processing continues with the next operation.
     *
     * @param model       target model, mutated in place
     * @param opsOrWrapper a JSON array of operations, a wrapper object with an {@code ops}/{@code operations}/
     *                     {@code actions} array, or a single operation object
     * @return the accumulated result (never null)
     */
    public OpResult apply(VoxelModel model, JsonElement opsOrWrapper) {
        Objects.requireNonNull(model, "model");
        OpResult res = new OpResult();
        JsonArray ops = extractOps(opsOrWrapper, res);
        if (ops == null) return res;
        if (ops.isEmpty()) {
            res.info("no operations to apply");
            return res;
        }
        for (int i = 0; i < ops.size(); i++) {
            JsonElement el = ops.get(i);
            res.opsTotal++;
            if (el == null || el.isJsonNull()) {
                res.opsFailed++;
                res.error("op[" + i + "]: empty operation (expected an object)");
                continue;
            }
            if (!el.isJsonObject()) {
                res.opsFailed++;
                res.error("op[" + i + "]: expected an object, got " + OpArgs.typeName(el));
                continue;
            }
            JsonObject op = el.getAsJsonObject();
            // several keys may name the operation; prefer the first one that is a known op so that
            // {"action":"roof","type":"gable"} still resolves to the roof op
            String rawName = null;
            String canonical = null;
            for (String key : new String[]{"op", "action", "cmd", "type", "operation", "command"}) {
                String candidate = OpArgs.str(op, null, key);
                if (candidate == null || candidate.isBlank()) continue;
                String mapped = canonicalOp(candidate);
                if (mapped != null) {
                    rawName = candidate;
                    canonical = mapped;
                    break;
                }
                if (rawName == null) rawName = candidate;
            }
            if (rawName == null || rawName.isBlank()) {
                res.opsFailed++;
                res.error("op[" + i + "]: missing 'op' field" + hint(op));
                continue;
            }
            if (canonical == null) {
                res.opsFailed++;
                res.error("op[" + i + "] " + rawName + ": unknown op '" + rawName + "' (expected one of "
                        + String.join(", ", KNOWN_OPS) + ")");
                continue;
            }
            String prefix = "op[" + i + "] " + canonical;
            boolean ok;
            skippedInOp = 0;
            try {
                ok = execute(model, op, canonical, prefix, res);
                if (skippedInOp > 0) {
                    res.error(prefix + ": " + skippedInOp + " cell(s) outside the allowed build area x" + limits[0] + ".." + limits[3]
                            + " y" + limits[1] + ".." + limits[4] + " z" + limits[2] + ".." + limits[5]
                            + " were skipped — keep everything inside the plot");
                    ok = false;
                }
            } catch (IllegalArgumentException ex) {
                res.error(prefix + ": " + message(ex));
                ok = false;
            } catch (RuntimeException ex) {
                res.error(prefix + ": internal error: " + message(ex));
                ok = false;
            }
            if (ok) {
                res.opsOk++;
            } else {
                res.opsFailed++;
            }
            if (model.size() > maxModelCells) {
                res.error("model exceeds the limit of " + maxModelCells + " cells (" + model.size()
                        + "); remaining operations were skipped");
                break;
            }
        }
        return res;
    }

    private static String message(Throwable ex) {
        String m = ex.getMessage();
        return m == null || m.isBlank() ? ex.getClass().getSimpleName() : m;
    }

    private static String hint(JsonObject op) {
        for (String k : op.keySet()) {
            if (!k.equalsIgnoreCase("op") && !k.equalsIgnoreCase("type") && !k.equalsIgnoreCase("action")) {
                return " (found field '" + k + "')";
            }
        }
        return "";
    }

    /** Maps an op name/alias to its canonical name, or null when unknown. */
    static String canonicalOp(String name) {
        String n = name.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return switch (n) {
            case "fill", "box", "cuboid", "cube" -> "fill";
            case "set", "setblock", "set_block", "place", "block", "blocks" -> "set";
            case "clear", "air", "delete", "remove", "erase" -> "clear";
            case "outline", "frame", "edges", "border" -> "outline";
            case "hollow", "shell" -> "hollow";
            case "walls", "wall" -> "walls";
            case "copy", "mirror_copy", "mirrorcopy", "clone" -> "copy";
            case "sphere", "dome", "ball" -> "sphere";
            case "cylinder", "tower", "tube" -> "cylinder";
            case "line" -> "line";
            case "replace" -> "replace";
            case "roof" -> "roof";
            case "repeat" -> "repeat";
            case "building" -> "building";
            case "staircase", "stairway", "flight", "stair_flight", "stairs_flight", "steps" -> "staircase";
            case "trim", "skirt", "eaves", "eave", "cornice", "stair_ring", "ring" -> "trim";
            default -> null;
        };
    }

    private JsonArray extractOps(JsonElement el, OpResult res) {
        if (el == null || el.isJsonNull()) {
            res.error("no operations given (expected an array or an object with an 'ops' array)");
            return null;
        }
        if (el.isJsonArray()) return el.getAsJsonArray();
        if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            if ("repeat".equalsIgnoreCase(OpArgs.str(o, "", "op", "action", "cmd", "type"))) {
                JsonArray a = new JsonArray();
                a.add(o);
                return a;
            }
            JsonElement inner = OpArgs.field(o, "ops", "operations", "actions", "commands");
            if (inner != null) {
                if (inner.isJsonArray()) return inner.getAsJsonArray();
                if (inner.isJsonObject()) {
                    JsonArray a = new JsonArray();
                    a.add(inner);
                    return a;
                }
                res.error("'ops' must be an array of operations, got " + OpArgs.typeName(inner));
                return null;
            }
            // a single operation object without a wrapper
            if (OpArgs.field(o, "op", "type", "action", "cmd") != null
                    || OpArgs.field(o, "from", "start", "p1", "pos1", "pos") != null) {
                JsonArray a = new JsonArray();
                a.add(o);
                return a;
            }
            res.error("no 'ops' array found (keys: " + String.join(", ", o.keySet()) + ")");
            return null;
        }
        res.error("operations must be a JSON array or an object with an 'ops' array, got " + OpArgs.typeName(el));
        return null;
    }

    // ------------------------------------------------------------------ dispatch

    private boolean execute(VoxelModel model, JsonObject op, String name, String prefix, OpResult res) {
        switch (name) {
            case "repeat", "building" -> {
                JsonArray expanded = name.equals("repeat") ? ProgramCompiler.expand(op) : BuildingCompiler.compile(op, info);
                OpResult child = new OpExecutor(info, maxOpVolume, maxAbsCoord, maxModelCells)
                        .withLimits(limits).apply(model, expanded);
                res.cellsChanged += child.cellsChanged;
                for (String error : child.errors) res.error(prefix + ": " + error);
                res.warnings.addAll(child.warnings);
                res.info.addAll(child.info);
                res.doorways.addAll(child.doorways);
                if (name.equals("building") && !child.hasErrors()) {
                    int[][] region = requireRegion(op);
                    int x = OpArgs.integer(op,(region[0][0]+region[1][0])/2,"door_x");
                    int y = Math.min(region[0][1],region[1][1])+1, z = Math.max(region[0][2],region[1][2]);
                    res.doorways.add(new Doorway(x,y,z,info.parse(OpArgs.str(op,"oak_door","door")+"[facing=north]")));
                }
                return !child.hasErrors();
            }
            case "fill" -> {
                int[][] r = requireRegion(op);
                int[] from = r[0], to = r[1];
                int[] b = box(prefix, from, to);
                BlockPattern pattern = requirePattern(op);
                for (int x = b[0]; x <= b[3]; x++)
                    for (int y = b[1]; y <= b[4]; y++)
                        for (int z = b[2]; z <= b[5]; z++)
                            write(model, x, y, z, pattern.pick(x, y, z), res);
                return true;
            }
            case "clear" -> {
                int[][] r = requireRegion(op);
                int[] b = box(prefix, r[0], r[1]);
                for (int x = b[0]; x <= b[3]; x++)
                    for (int y = b[1]; y <= b[4]; y++)
                        for (int z = b[2]; z <= b[5]; z++)
                            write(model, x, y, z, BlockSpec.AIR, res);
                return true;
            }
            case "walls" -> {
                int[][] r = requireRegion(op);
                int[] b = box(prefix, r[0], r[1]);
                BlockPattern pattern = requirePattern(op);
                for (int x = b[0]; x <= b[3]; x++) {
                    for (int z = b[2]; z <= b[5]; z++) {
                        if (x != b[0] && x != b[3] && z != b[2] && z != b[5]) continue;
                        for (int y = b[1]; y <= b[4]; y++) write(model, x, y, z, pattern.pick(x, y, z), res);
                    }
                }
                return true;
            }
            case "hollow" -> {
                int[][] r = requireRegion(op);
                int[] b = box(prefix, r[0], r[1]);
                BlockPattern pattern = requirePattern(op);
                for (int x = b[0]; x <= b[3]; x++) {
                    for (int y = b[1]; y <= b[4]; y++) {
                        for (int z = b[2]; z <= b[5]; z++) {
                            boolean shell = x == b[0] || x == b[3] || y == b[1] || y == b[4]
                                    || z == b[2] || z == b[5];
                            write(model, x, y, z, shell ? pattern.pick(x, y, z) : BlockSpec.AIR, res);
                        }
                    }
                }
                return true;
            }
            case "outline" -> {
                int[][] r = requireRegion(op);
                int[] b = box(prefix, r[0], r[1]);
                BlockPattern pattern = requirePattern(op);
                outline(model, b, pattern, res);
                return true;
            }
            case "set" -> {
                BlockPattern pattern = requirePattern(op);
                List<int[]> pts = new ArrayList<>();
                JsonElement list = OpArgs.field(op, "positions", "points", "list");
                if (list != null) {
                    if (!list.isJsonArray()) {
                        throw new IllegalArgumentException("'positions' must be an array of coordinates, got "
                                + OpArgs.typeName(list));
                    }
                    JsonArray a = list.getAsJsonArray();
                    for (int i = 0; i < a.size(); i++) {
                        pts.add(OpArgs.point(a.get(i), "positions[" + i + "]"));
                    }
                } else {
                    JsonElement posEl = OpArgs.field(op, "pos", "position", "at", "p", "point", "coord", "location");
                    if (posEl != null && posEl.isJsonArray() && posEl.getAsJsonArray().size() > 0
                            && posEl.getAsJsonArray().get(0).isJsonArray()) {
                        JsonArray a = posEl.getAsJsonArray();
                        for (int i = 0; i < a.size(); i++) {
                            pts.add(OpArgs.point(a.get(i), "pos[" + i + "]"));
                        }
                    } else {
                        int[] single = OpArgs.optionalPoint(op, "pos", "position", "at", "p", "point", "coord", "location");
                        if (single == null) {
                            throw new IllegalArgumentException("missing 'pos' or 'positions'");
                        }
                        pts.add(single);
                    }
                }
                if (pts.isEmpty()) throw new IllegalArgumentException("empty 'positions' list");
                if (pts.size() > maxOpVolume) {
                    throw new IllegalArgumentException("too many positions: " + pts.size() + " (limit " + maxOpVolume + ")");
                }
                for (int i = 0; i < pts.size(); i++) {
                    int[] p = pts.get(i);
                    checkCoord("positions[" + i + "]", p[0], p[1], p[2]);
                    write(model, p[0], p[1], p[2], pattern.pick(p[0], p[1], p[2]), res);
                }
                return true;
            }
            case "line" -> {
                int[][] r = requireRegion(op);
                int[] from = r[0], to = r[1];
                checkCoord("from", from[0], from[1], from[2]);
                checkCoord("to", to[0], to[1], to[2]);
                BlockPattern pattern = requirePattern(op);
                int maxDelta = Math.max(Math.abs(to[0] - from[0]), Math.max(Math.abs(to[1] - from[1]), Math.abs(to[2] - from[2])));
                if (maxDelta + 1 > maxOpVolume) {
                    throw new IllegalArgumentException("line too long: " + (maxDelta + 1) + " cells (limit " + maxOpVolume + ")");
                }
                char axis = dominantAxis(from, to);
                int steps = maxDelta;
                for (int i = 0; i <= steps; i++) {
                    double t = steps == 0 ? 0 : (double) i / steps;
                    int x = (int) Math.round(from[0] + (to[0] - from[0]) * t);
                    int y = (int) Math.round(from[1] + (to[1] - from[1]) * t);
                    int z = (int) Math.round(from[2] + (to[2] - from[2]) * t);
                    write(model, x, y, z, oriented(pattern.pick(x, y, z), axis), res);
                }
                return true;
            }
            case "replace" -> {
                List<MatchRule> rules = parseMatch(op);
                if (rules.isEmpty()) throw new IllegalArgumentException("missing 'match'");
                BlockPattern pattern = requirePattern(op);
                int[][] r = OpArgs.region(op);
                int[] b = null;
                if (r != null) {
                    b = box(prefix, r[0], r[1]);
                } else {
                    b = model.bounds();
                    if (b == null) {
                        res.info("replace: model is empty");
                        return true;
                    }
                    checkVolume(prefix, volume(b));
                }
                int changes = 0;
                for (int x = b[0]; x <= b[3]; x++) {
                    for (int y = b[1]; y <= b[4]; y++) {
                        for (int z = b[2]; z <= b[5]; z++) {
                            BlockSpec cur = model.get(x, y, z);
                            if (cur == null || cur.isAir()) continue;
                            if (!matchesAny(cur, rules)) continue;
                            if (write(model, x, y, z, pattern.pick(x, y, z), res)) changes++;
                        }
                    }
                }
                if (changes == 0) res.info("replace: no matching blocks found");
                return true;
            }
            case "sphere" -> {
                return sphere(model, op, prefix, res);
            }
            case "cylinder" -> {
                return cylinder(model, op, prefix, res);
            }
            case "copy" -> {
                return copy(model, op, prefix, res);
            }
            case "roof" -> {
                int[][] r = OpArgs.region(op);
                if (r == null) throw new IllegalArgumentException("missing 'from'/'to' roof rectangle");
                int[] rb = box(prefix, r[0], r[1]);
                checkVolume(prefix, volume(rb));
                if (limits != null && (rb[0] < limits[0] - 3 || rb[3] > limits[3] + 3 || rb[2] < limits[2] - 3
                        || rb[5] > limits[5] + 3 || rb[1] > limits[4] || rb[1] < limits[1])) {
                    throw new IllegalArgumentException("roof rectangle x" + rb[0] + ".." + rb[3] + " z" + rb[2] + ".." + rb[5]
                            + " at y=" + rb[1] + " is outside the allowed build area");
                }
                RoofBuilder builder = new RoofBuilder(info, maxAbsCoord);
                int changed = builder.build(model, op, res);
                res.cellsChanged += changed;
                return true;
            }
            case "staircase" -> {
                return staircase(model, op, res);
            }
            case "trim" -> {
                return trim(model, op, prefix, res);
            }
            default -> throw new IllegalStateException("unhandled op " + name);
        }
    }

    // ------------------------------------------------------------------ stairs helpers (orientation by construction)

    /**
     * {"op":"staircase","from":[x,y,z],"direction":"north","steps":4,"width":1,"block":"spruce_stairs",
     *  "support":"spruce_planks","headroom":3}: a walkable flight. Step k sits at from + k*direction + (0,k,0)
     * and faces {@code direction} (the climbing direction). Width extends to the right-hand side of the
     * climber. The optional support fills the space under every step down to from.y; headroom clears the cells
     * above each step (cuts the opening through an upper floor).
     */
    private boolean staircase(VoxelModel model, JsonObject op, OpResult res) {
        int[] from = OpArgs.optionalPoint(op, "from", "start", "pos", "at", "bottom");
        if (from == null) throw new IllegalArgumentException("missing 'from' (lowest step)");
        Dir dir = Dir.parse(OpArgs.str(op, null, "direction", "dir", "facing", "towards", "up"));
        if (dir == null || !dir.isHorizontal()) {
            throw new IllegalArgumentException("'direction' must be north|east|south|west (the climbing direction)");
        }
        int steps = OpArgs.integer(op, -1, "steps", "length", "count", "height");
        int[] to = OpArgs.optionalPoint(op, "to", "end", "top");
        if (steps <= 0 && to != null) steps = Math.abs(to[1] - from[1]) + 1;
        if (steps <= 0 || steps > 64) throw new IllegalArgumentException("'steps' must be 1..64");
        int width = Math.max(1, Math.min(8, OpArgs.integer(op, 1, "width", "wide")));
        int headroom = Math.max(0, Math.min(6, OpArgs.integer(op, 3, "headroom", "clearance")));
        BlockPattern pattern = requirePattern(op);
        JsonElement supEl = OpArgs.field(op, "support", "fill", "base", "under");
        BlockPattern support = supEl == null || supEl.isJsonNull() || (supEl.isJsonPrimitive() && supEl.getAsString().equalsIgnoreCase("none"))
                ? null : BlockPattern.parse(supEl, info);
        Dir right = dir.cw();
        java.util.Set<Long> stepCells = new java.util.HashSet<>();
        for (int k = 0; k < steps; k++) {
            for (int w = 0; w < width; w++) {
                int x = from[0] + dir.dx * k + right.dx * w, y = from[1] + k, z = from[2] + dir.dz * k + right.dz * w;
                stepCells.add(VoxelModel.key(x, y, z));
            }
        }
        for (int k = 0; k < steps; k++) {
            for (int w = 0; w < width; w++) {
                int x = from[0] + dir.dx * k + right.dx * w, y = from[1] + k, z = from[2] + dir.dz * k + right.dz * w;
                checkCoord("step " + k, x, y, z);
                BlockSpec stair = pattern.pick(x, y, z);
                if (info.hasProperty(stair.name(), "facing")) stair = info.with(stair, "facing", dir.id());
                if (info.hasProperty(stair.name(), "half")) stair = info.with(stair, "half", "bottom");
                write(model, x, y, z, stair, res);
                if (support != null) {
                    for (int yy = from[1]; yy < y; yy++) {
                        if (!stepCells.contains(VoxelModel.key(x, yy, z))) write(model, x, yy, z, support.pick(x, yy, z), res);
                    }
                }
                for (int h = 1; h <= headroom; h++) {
                    if (!stepCells.contains(VoxelModel.key(x, y + h, z))) write(model, x, y + h, z, BlockSpec.AIR, res);
                }
            }
        }
        res.info("staircase: " + steps + " steps facing " + dir.id() + " from (" + from[0] + "," + from[1] + "," + from[2]
                + ") to (" + (from[0] + dir.dx * (steps - 1)) + "," + (from[1] + steps - 1) + "," + (from[2] + dir.dz * (steps - 1)) + ")");
        return true;
    }

    /**
     * {"op":"trim","from":[x1,y,z1],"to":[x2,y,z2],"block":"stone_brick_stairs","half":"bottom",
     *  "sides":"nesw","skip":[[x,y,z],...]}: a ring of stairs on the cells just OUTSIDE the wall rectangle
     * x1..x2 / z1..z2 at height y, every stair with its back against the walls (facing inward). half=bottom
     * gives a plinth skirt, half=top gives eaves / cornices / window-sill bands. Corners become outer corners.
     */
    private boolean trim(VoxelModel model, JsonObject op, String prefix, OpResult res) {
        int[][] r = requireRegion(op);
        int[] b = box(prefix, r[0], r[1]);
        int y = b[1];
        BlockPattern pattern = requirePattern(op);
        String half = OpArgs.str(op, "bottom", "half").toLowerCase(Locale.ROOT);
        if (!half.equals("top") && !half.equals("bottom")) half = half.contains("top") || half.contains("upside") ? "top" : "bottom";
        String sides = OpArgs.str(op, "nesw", "sides").toLowerCase(Locale.ROOT);
        java.util.Set<Long> skip = new java.util.HashSet<>();
        JsonElement sk = OpArgs.field(op, "skip", "except", "gaps");
        if (sk != null && sk.isJsonArray()) {
            for (JsonElement e : sk.getAsJsonArray()) {
                int[] p = OpArgs.point(e, "skip");
                skip.add(VoxelModel.key(p[0], y, p[2]));
            }
        }
        int x1 = b[0] - 1, x2 = b[3] + 1, z1 = b[2] - 1, z2 = b[5] + 1;
        int placed = 0;
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                boolean north = z == z1, south = z == z2, west = x == x1, east = x == x2;
                if (!(north || south || west || east)) continue;
                Dir facing;
                if (north) facing = Dir.SOUTH;
                else if (south) facing = Dir.NORTH;
                else if (west) facing = Dir.EAST;
                else facing = Dir.WEST;
                char side = north ? 'n' : south ? 's' : west ? 'w' : 'e';
                boolean cornerOk = sides.indexOf(side) >= 0
                        || (west && sides.indexOf('w') >= 0) || (east && sides.indexOf('e') >= 0);
                if (!cornerOk) continue;
                if (skip.contains(VoxelModel.key(x, y, z))) continue;
                checkCoord("trim", x, y, z);
                BlockSpec st = pattern.pick(x, y, z);
                if (info.hasProperty(st.name(), "facing")) st = info.with(st, "facing", facing.id());
                if (info.hasProperty(st.name(), "half")) st = info.with(st, "half", half);
                if (write(model, x, y, z, st, res)) placed++;
            }
        }
        res.info("trim: " + placed + " " + half + " stairs around x" + b[0] + ".." + b[3] + " z" + b[2] + ".." + b[5] + " at y=" + y);
        return true;
    }

    // ------------------------------------------------------------------ shapes

    private void outline(VoxelModel model, int[] b, BlockPattern pattern, OpResult res) {
        int x1 = b[0], y1 = b[1], z1 = b[2], x2 = b[3], y2 = b[4], z2 = b[5];
        int lenX = x2 - x1 + 1, lenY = y2 - y1 + 1, lenZ = z2 - z1 + 1;
        // axis per cell: the longest edge through it wins; ties prefer y (corner posts), then x, then z
        Map<Long, EdgeAxis> axes = new HashMap<>();
        for (int y : new int[]{y1, y2}) {
            for (int z : new int[]{z1, z2}) {
                for (int x = x1; x <= x2; x++) preferAxis(axes, VoxelModel.key(x, y, z), 'x', lenX);
            }
        }
        for (int x : new int[]{x1, x2}) {
            for (int z : new int[]{z1, z2}) {
                for (int y = y1; y <= y2; y++) preferAxis(axes, VoxelModel.key(x, y, z), 'y', lenY);
            }
        }
        for (int x : new int[]{x1, x2}) {
            for (int y : new int[]{y1, y2}) {
                for (int z = z1; z <= z2; z++) preferAxis(axes, VoxelModel.key(x, y, z), 'z', lenZ);
            }
        }
        for (Map.Entry<Long, EdgeAxis> e : axes.entrySet()) {
            long k = e.getKey();
            int x = VoxelModel.kx(k), y = VoxelModel.ky(k), z = VoxelModel.kz(k);
            write(model, x, y, z, oriented(pattern.pick(x, y, z), e.getValue().axis), res);
        }
    }

    private record EdgeAxis(char axis, int len) { }

    private static void preferAxis(Map<Long, EdgeAxis> axes, long key, char axis, int len) {
        EdgeAxis cur = axes.get(key);
        if (cur == null || len > cur.len() || (len == cur.len() && priority(axis) > priority(cur.axis()))) {
            axes.put(key, new EdgeAxis(axis, len));
        }
    }

    private static int priority(char axis) {
        return axis == 'y' ? 3 : axis == 'x' ? 2 : 1;
    }

    private boolean sphere(VoxelModel model, JsonObject op, String prefix, OpResult res) {
        int[] c = OpArgs.optionalPoint(op, "center", "centre", "pos", "at", "origin");
        if (c == null) throw new IllegalArgumentException("missing 'center'");
        checkCoord("center", c[0], c[1], c[2]);
        double radius = requireRadius(op);
        BlockPattern pattern = requirePattern(op);
        boolean hollow = OpArgs.bool(op, false, "hollow", "shell", "hollow_only");
        String half = OpArgs.str(op, "", "half", "hemisphere").toLowerCase(Locale.ROOT);
        if (!half.isEmpty() && !half.equals("top") && !half.equals("bottom") && !half.equals("full")) {
            throw new IllegalArgumentException("half must be 'top' or 'bottom', got '" + half + "'");
        }
        int r = (int) Math.ceil(radius) + 1;
        if (Math.abs(c[0]) + r > maxAbsCoord || Math.abs(c[1]) + r > maxAbsCoord || Math.abs(c[2]) + r > maxAbsCoord) {
            throw new IllegalArgumentException("sphere exceeds the coordinate limit of " + maxAbsCoord);
        }
        long vol = (long) (2 * r + 1) * (2 * r + 1) * (2 * r + 1);
        if (vol > maxOpVolume) {
            throw new IllegalArgumentException("sphere volume " + vol + " exceeds the per-op limit of " + maxOpVolume);
        }
        double rr = (radius + 0.5) * (radius + 0.5);
        int ri = (int) Math.ceil(radius);
        for (int x = c[0] - ri; x <= c[0] + ri; x++) {
            for (int y = c[1] - ri; y <= c[1] + ri; y++) {
                for (int z = c[2] - ri; z <= c[2] + ri; z++) {
                    int dx = x - c[0], dy = y - c[1], dz = z - c[2];
                    double d2 = dx * dx + dy * dy + dz * dz;
                    if (d2 > rr) continue;
                    if (half.equals("top") && dy < 0) continue;
                    if (half.equals("bottom") && dy > 0) continue;
                    if (hollow && sphereInside(dx, dy, dz, rr)) continue;
                    write(model, x, y, z, pattern.pick(x, y, z), res);
                }
            }
        }
        return true;
    }

    private static boolean sphereInside(int dx, int dy, int dz, double rr) {
        return (dx + 1) * (dx + 1) + dy * dy + dz * dz <= rr
                && (dx - 1) * (dx - 1) + dy * dy + dz * dz <= rr
                && dx * dx + (dy + 1) * (dy + 1) + dz * dz <= rr
                && dx * dx + (dy - 1) * (dy - 1) + dz * dz <= rr
                && dx * dx + dy * dy + (dz + 1) * (dz + 1) <= rr
                && dx * dx + dy * dy + (dz - 1) * (dz - 1) <= rr;
    }

    private boolean cylinder(VoxelModel model, JsonObject op, String prefix, OpResult res) {
        int[] c = OpArgs.optionalPoint(op, "center", "centre", "pos", "at", "origin", "base", "start");
        if (c == null) throw new IllegalArgumentException("missing 'center'");
        checkCoord("center", c[0], c[1], c[2]);
        double radius = requireRadius(op);
        int height = OpArgs.integer(op, 1, "height", "h", "length");
        if (height <= 0) throw new IllegalArgumentException("height must be positive, got " + height);
        if (height > maxOpVolume) throw new IllegalArgumentException("height " + height + " exceeds the per-op limit");
        BlockPattern pattern = requirePattern(op);
        boolean hollow = OpArgs.bool(op, false, "hollow", "shell", "hollow_only");
        String axisStr = OpArgs.str(op, "y", "axis", "along", "direction").toLowerCase(Locale.ROOT);
        char axis = axisChar(axisStr);
        if (axis == 0) throw new IllegalArgumentException("axis must be x, y or z, got '" + axisStr + "'");
        int r = (int) Math.ceil(radius) + 1;
        int hx = axis == 'x' ? height : r;
        int hy = axis == 'y' ? height : r;
        int hz = axis == 'z' ? height : r;
        int x0 = c[0] - (axis == 'x' ? 0 : r), x1 = c[0] + (axis == 'x' ? height - 1 : r);
        int y0 = c[1] - (axis == 'y' ? 0 : r), y1 = c[1] + (axis == 'y' ? height - 1 : r);
        int z0 = c[2] - (axis == 'z' ? 0 : r), z1 = c[2] + (axis == 'z' ? height - 1 : r);
        if (Math.abs(x0) > maxAbsCoord || Math.abs(x1) > maxAbsCoord || Math.abs(y0) > maxAbsCoord
                || Math.abs(y1) > maxAbsCoord || Math.abs(z0) > maxAbsCoord || Math.abs(z1) > maxAbsCoord) {
            throw new IllegalArgumentException("cylinder exceeds the coordinate limit of " + maxAbsCoord);
        }
        long vol = (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
        if (vol > maxOpVolume) {
            throw new IllegalArgumentException("cylinder volume " + vol + " exceeds the per-op limit of " + maxOpVolume);
        }
        double rr = (radius + 0.5) * (radius + 0.5);
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    int a, b;
                    switch (axis) {
                        case 'x' -> { a = y - c[1]; b = z - c[2]; }
                        case 'z' -> { a = x - c[0]; b = y - c[1]; }
                        default -> { a = x - c[0]; b = z - c[2]; }
                    }
                    double d2 = a * a + b * b;
                    if (d2 > rr) continue;
                    if (hollow && inDisc(a + 1, b, rr) && inDisc(a - 1, b, rr)
                            && inDisc(a, b + 1, rr) && inDisc(a, b - 1, rr)) {
                        continue; // side wall only: a hollow cylinder is a tube with open ends
                    }
                    write(model, x, y, z, pattern.pick(x, y, z), res);
                }
            }
        }
        return true;
    }

    private static boolean inDisc(int a, int b, double rr) {
        return a * a + b * b <= rr;
    }

    private boolean copy(VoxelModel model, JsonObject op, String prefix, OpResult res) {
        int[][] r = OpArgs.region(op);
        if (r == null) throw new IllegalArgumentException("missing 'from'/'to' source region");
        int[] b = box(prefix, r[0], r[1]);
        int offsetX = 0, offsetY = 0, offsetZ = 0;
        JsonElement offEl = OpArgs.field(op, "offset", "move", "shift", "translate", "by");
        if (offEl != null) {
            int[] off = OpArgs.point(offEl, "offset");
            offsetX = off[0];
            offsetY = off[1];
            offsetZ = off[2];
        } else {
            int[] to = OpArgs.optionalPoint(op, "dest", "target", "destination", "to_pos");
            if (to != null) {
                offsetX = to[0] - b[0];
                offsetY = to[1] - b[1];
                offsetZ = to[2] - b[2];
            }
        }
        String mirrorStr = OpArgs.str(op, "", "mirror", "flip", "mirror_axis").toLowerCase(Locale.ROOT);
        char mirrorAxis = 0;
        if (!mirrorStr.isEmpty() && !mirrorStr.equals("none") && !mirrorStr.equals("false")
                && !mirrorStr.equals("null")) {
            if (mirrorStr.equals("x") || mirrorStr.equals("mirror_x") || mirrorStr.equals("east_west")) {
                mirrorAxis = 'x';
            } else if (mirrorStr.equals("z") || mirrorStr.equals("mirror_z") || mirrorStr.equals("north_south")) {
                mirrorAxis = 'z';
            } else {
                throw new IllegalArgumentException("mirror must be 'x' or 'z', got '" + mirrorStr + "'");
            }
        }
        int rotateTurns = 0;
        JsonElement rotEl = OpArgs.field(op, "rotate", "rotation", "rot", "turn");
        if (rotEl != null) {
            Double degEl = OpArgs.number(op, "rotate", "rotation", "rot", "turn");
            int deg = (int) Math.round(degEl == null ? 0 : degEl.doubleValue());
            if (deg % 90 != 0) throw new IllegalArgumentException("rotate must be a multiple of 90, got " + deg);
            rotateTurns = Math.floorMod(deg / 90, 4);
        }

        // snapshot the source first: the destination may overlap it
        List<long[]> cells = new ArrayList<>();
        List<BlockSpec> specs = new ArrayList<>();
        for (int x = b[0]; x <= b[3]; x++) {
            for (int y = b[1]; y <= b[4]; y++) {
                for (int z = b[2]; z <= b[5]; z++) {
                    BlockSpec s = model.get(x, y, z);
                    if (s == null) continue;
                    cells.add(new long[]{x, y, z});
                    specs.add(s);
                }
            }
        }
        int w = b[3] - b[0] + 1;
        int d = b[5] - b[2] + 1;
        int baseX = b[0] + offsetX;
        int baseY = b[1] + offsetY;
        int baseZ = b[2] + offsetZ;
        if (Math.abs(baseX) > maxAbsCoord || Math.abs(baseY) > maxAbsCoord || Math.abs(baseZ) > maxAbsCoord) {
            throw new IllegalArgumentException("copy destination exceeds the coordinate limit of " + maxAbsCoord);
        }
        for (int i = 0; i < cells.size(); i++) {
            long[] p = cells.get(i);
            int lx = (int) (p[0] - b[0]);
            int ly = (int) (p[1] - b[1]);
            int lz = (int) (p[2] - b[2]);
            BlockSpec spec = specs.get(i);
            if (mirrorAxis == 'x') {
                lx = w - 1 - lx;
                spec = info.mirror(spec, 'x');
            } else if (mirrorAxis == 'z') {
                lz = d - 1 - lz;
                spec = info.mirror(spec, 'z');
            }
            if (rotateTurns == 1) {
                int nlx = d - 1 - lz;
                int nlz = lx;
                lx = nlx;
                lz = nlz;
            } else if (rotateTurns == 2) {
                lx = w - 1 - lx;
                lz = d - 1 - lz;
            } else if (rotateTurns == 3) {
                int nlx = lz;
                int nlz = w - 1 - lx;
                lx = nlx;
                lz = nlz;
            }
            if (rotateTurns != 0) spec = info.rotate(spec, rotateTurns);
            spec = info.canonical(spec);
            write(model, baseX + lx, baseY + ly, baseZ + lz, spec, res);
        }
        return true;
    }

    // ------------------------------------------------------------------ replace matching

    private static final class MatchRule {
        final Pattern glob;
        final Map<String, String> props;

        MatchRule(Pattern glob, Map<String, String> props) {
            this.glob = glob;
            this.props = props;
        }

        boolean matches(BlockSpec spec, BlockInfo info) {
            if (!glob.matcher(spec.name()).matches()) return false;
            for (Map.Entry<String, String> e : props.entrySet()) {
                String v = info.getProp(spec, e.getKey());
                if (v == null || !v.equalsIgnoreCase(e.getValue())) return false;
            }
            return true;
        }
    }

    private List<MatchRule> parseMatch(JsonObject op) {
        JsonElement el = OpArgs.field(op, "match", "matches", "replace", "target", "from_block", "old");
        List<MatchRule> rules = new ArrayList<>();
        if (el == null) return rules;
        if (el.isJsonArray()) {
            for (JsonElement e : el.getAsJsonArray()) addMatch(rules, e);
        } else {
            addMatch(rules, el);
        }
        return rules;
    }

    private void addMatch(List<MatchRule> rules, JsonElement e) {
        if (e == null || e.isJsonNull()) return;
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            JsonElement nameEl = OpArgs.field(o, "block", "name", "id", "state");
            if (nameEl == null || !nameEl.isJsonPrimitive()) {
                throw new IllegalArgumentException("match entry needs a 'block' name");
            }
            Map<String, String> props = new java.util.LinkedHashMap<>();
            JsonElement propsEl = OpArgs.field(o, "props", "properties", "state_props");
            if (propsEl != null && propsEl.isJsonObject()) {
                for (String k : propsEl.getAsJsonObject().keySet()) {
                    JsonElement v = propsEl.getAsJsonObject().get(k);
                    if (v != null && v.isJsonPrimitive()) props.put(k, v.getAsString());
                }
            }
            rules.add(new MatchRule(globToPattern(nameEl.getAsString()), props));
            return;
        }
        if (!e.isJsonPrimitive()) throw new IllegalArgumentException("match entries must be strings");
        String raw = e.getAsString().trim();
        if (raw.isEmpty()) return;
        for (String part : splitEntries(raw)) {
            String s = part.trim();
            if (s.isEmpty()) continue;
            Map<String, String> props = new java.util.LinkedHashMap<>();
            String name = s;
            int br = s.indexOf('[');
            if (br >= 0) {
                if (!s.endsWith("]")) throw new IllegalArgumentException("bad match entry '" + s + "': missing ']'");
                name = s.substring(0, br).trim();
                String inner = s.substring(br + 1, s.length() - 1).trim();
                if (!inner.isEmpty()) {
                    for (String kv : inner.split(",")) {
                        int eq = kv.indexOf('=');
                        if (eq <= 0) throw new IllegalArgumentException("bad property in match entry '" + s + "'");
                        props.put(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
                    }
                }
            }
            rules.add(new MatchRule(globToPattern(name), props));
        }
    }

    private static List<String> splitEntries(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') depth = Math.max(0, depth - 1);
            if (c == ',' && depth == 0) {
                if (!cur.toString().isBlank()) out.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (!cur.toString().isBlank()) out.add(cur.toString().trim());
        return out;
    }

    private boolean matchesAny(BlockSpec spec, List<MatchRule> rules) {
        for (MatchRule r : rules) if (r.matches(spec, info)) return true;
        return false;
    }

    private static Pattern globToPattern(String glob) {
        String g = glob.trim().toLowerCase(Locale.ROOT).replace("-", "_");
        if (g.startsWith("minecraft:")) g = g.substring("minecraft:".length());
        StringBuilder sb = new StringBuilder("^");
        for (int i = 0; i < g.length(); i++) {
            char c = g.charAt(i);
            if (c == '*') sb.append(".*");
            else if (c == '?') sb.append('.');
            else if ("\\.[]{}()<>+-=!$^|".indexOf(c) >= 0) sb.append('\\').append(c);
            else sb.append(c);
        }
        return Pattern.compile(sb.append('$').toString());
    }

    // ------------------------------------------------------------------ validation helpers

    private BlockPattern requirePattern(JsonObject op) {
        JsonElement el = OpArgs.field(op, "block", "material", "blocks", "pattern", "state", "block_state",
                "blockstate", "palette", "with");
        if (el == null) throw new IllegalArgumentException("missing 'block'");
        return BlockPattern.parse(el, info);
    }

    private static int[][] requireRegion(JsonObject op) {
        int[][] r = OpArgs.region(op);
        if (r == null) throw new IllegalArgumentException("missing 'from' and 'to' coordinates");
        return r;
    }

    private double requireRadius(JsonObject op) {
        Double rad = OpArgs.number(op, "radius", "r", "size");
        if (rad == null) throw new IllegalArgumentException("missing 'radius'");
        if (!(rad > 0) || !Double.isFinite(rad)) {
            throw new IllegalArgumentException("radius must be a positive number, got " + rad);
        }
        if (rad > maxAbsCoord) throw new IllegalArgumentException("radius " + rad + " is too large (limit " + maxAbsCoord + ")");
        return rad;
    }

    /** Normalizes corners and validates coordinate + volume limits. */
    private int[] box(String prefix, int[] a, int[] b) {
        int x1 = Math.min(a[0], b[0]), x2 = Math.max(a[0], b[0]);
        int y1 = Math.min(a[1], b[1]), y2 = Math.max(a[1], b[1]);
        int z1 = Math.min(a[2], b[2]), z2 = Math.max(a[2], b[2]);
        checkCoord("from", a[0], a[1], a[2]);
        checkCoord("to", b[0], b[1], b[2]);
        int[] out = {x1, y1, z1, x2, y2, z2};
        checkVolume(prefix, volume(out));
        return out;
    }

    private void checkCoord(String label, int x, int y, int z) {
        if (Math.abs(x) > maxAbsCoord || Math.abs(y) > maxAbsCoord || Math.abs(z) > maxAbsCoord) {
            throw new IllegalArgumentException(label + " (" + x + "," + y + "," + z
                    + ") exceeds the coordinate limit of +/-" + maxAbsCoord);
        }
    }

    private void checkVolume(String prefix, long vol) {
        if (vol > maxOpVolume) {
            throw new IllegalArgumentException("volume " + vol + " exceeds the per-op limit of " + maxOpVolume
                    + " cells");
        }
    }

    private static long volume(int[] b) {
        return (long) (b[3] - b[0] + 1) * (b[4] - b[1] + 1) * (b[5] - b[2] + 1);
    }

    private static char dominantAxis(int[] from, int[] to) {
        int dx = Math.abs(to[0] - from[0]);
        int dy = Math.abs(to[1] - from[1]);
        int dz = Math.abs(to[2] - from[2]);
        if (dx >= dy && dx >= dz) return 'x';
        if (dy >= dz) return 'y';
        return 'z';
    }

    private static char axisChar(String s) {
        return switch (s) {
            case "x", "east-west", "east_west", "ew" -> 'x';
            case "z", "north-south", "north_south", "ns" -> 'z';
            case "y", "up", "vertical" -> 'y';
            default -> 0;
        };
    }

    /**
     * Sets the {@code axis} property of a log/pillar-like block to {@code axis} unless the pattern entry
     * already specified one. Never throws; returns the input unchanged when the block has no axis property.
     */
    private BlockSpec oriented(BlockSpec spec, char axis) {
        if (spec.hasProp("axis")) return spec;
        if (!info.hasProperty(spec.name(), "axis")) return spec;
        try {
            return info.canonical(info.with(spec, "axis", String.valueOf(axis)));
        } catch (IllegalArgumentException ex) {
            return spec;
        }
    }

    /**
     * Stores a cell, counting only real changes. Writing air over an unset cell above the terrain layer is a
     * no-op (unset already means air there); writing air at y &lt;= 0 is a real change (digging).
     */
    private boolean write(VoxelModel model, int x, int y, int z, BlockSpec spec, OpResult res) {
        if (limits != null && (x < limits[0] || y < limits[1] || z < limits[2] || x > limits[3] || y > limits[4] || z > limits[5])) {
            skippedInOp++;
            return false;
        }
        BlockSpec old = model.get(x, y, z);
        if (Objects.equals(old, spec)) return false;
        if (spec.isAir() && y >= 1) {
            // above ground an unset cell already means air, so "clear" un-sets instead of storing air
            if (old == null) return false;
            model.set(x, y, z, null);
            res.cellsChanged++;
            return true;
        }
        model.set(x, y, z, spec);
        res.cellsChanged++;
        return true;
    }
}
