package dev.aibuild.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Post-processing pass that makes an LLM-authored model <b>look correct in Minecraft</b>, no matter how
 * sloppy the individual block states were.
 *
 * <p>Run it after every batch of DSL ops ({@link dev.aibuild.model.ops.OpExecutor#apply}). The pass is
 * deterministic and idempotent: running it twice on the same model changes nothing and yields the same
 * {@link VoxelModel#toJson()} the second time.</p>
 *
 * <p>Steps, in order:</p>
 * <ol>
 *     <li><b>Leaves</b>: every {@code *_leaves} block gets {@code persistent=true} (so they do not decay).</li>
 *     <li><b>Multi-block completion</b>: door upper halves, bed heads ({@code facing} = foot to head) and
 *     double-plant upper halves are added where only one half was placed; a lone upper/head also gets its
 *     lower/foot counterpart when that cell is free. Overwriting a non-air block is reported as a warning.</li>
 *     <li><b>Connections</b>: fence / glass pane / iron bars / wall side connections are recomputed from the
 *     neighbourhood, walls get {@code low}/{@code tall}/{@code none} sides plus their {@code up} flag.</li>
 *     <li><b>Stair shapes</b>: the vanilla algorithm computes {@code straight}, {@code inner_left/right} and
 *     {@code outer_left/right} from neighbouring stairs.</li>
 * </ol>
 *
 * <p>All reads use {@link VoxelModel#get} (unset cells count as air). All writes go through
 * {@link BlockInfo#with} and {@link BlockInfo#canonical} so the stored specs stay canonical. The class is
 * stateless and thread-safe as long as the model itself is confined to one thread.</p>
 */
public final class ShapeResolver {
    /** Plants that are always two blocks tall, even when a {@link BlockInfo} does not model their half. */
    private static final Set<String> DOUBLE_PLANT_NAMES = Set.of(
            "tall_grass", "large_fern", "sunflower", "lilac", "rose_bush", "peony", "pitcher_plant",
            "tall_seagrass", "small_dripleaf", "big_dripleaf");
    private static final Set<String> NON_SUPPORTING = Set.of(
            "pumpkin", "carved_pumpkin", "jack_o_lantern", "melon", "barrier", "structure_void", "light");
    private static final int MAX_NOTES = 12;

    private ShapeResolver() {
    }

    /**
     * Resolves multi-block parts, connections and stair shapes of {@code model} in place.
     *
     * @param model the model to fix (mutated)
     * @param info  block registry used for property knowledge and state transforms
     * @return compact, human/LLM readable notes about what was changed, e.g.
     * {@code auto-completed 2 door tops, 1 bed head; connected 40 fences/panes; 8 stair corners};
     * empty when the model already was consistent. Notes starting with {@code warning: } describe
     * non-air blocks that had to be overwritten.
     */
    public static List<String> resolve(VoxelModel model, BlockInfo info) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(info, "info");
        List<String> notes = new ArrayList<>(4);
        if (model.isEmpty()) return notes;

        long[] keys = model.entries().keySet().stream().mapToLong(Long::longValue).sorted().toArray();
        Counters c = new Counters();

        stepLeaves(model, info, keys, c);
        stepDoors(model, info, keys, c);
        stepBeds(model, info, keys, c);
        stepPlants(model, info, keys, c);
        stepConnections(model, info, keys, c);
        stepStairs(model, info, keys, c);

        List<String> completion = new ArrayList<>(3);
        if (c.doorTops > 0) completion.add(c.doorTops + " door tops");
        if (c.bedHeads > 0) completion.add(c.bedHeads + (c.bedHeads == 1 ? " bed head" : " bed heads"));
        if (c.plantTops > 0) completion.add(c.plantTops + " plant tops");
        StringBuilder head = new StringBuilder();
        if (!completion.isEmpty()) head.append("auto-completed ").append(String.join(", ", completion));
        if (c.connections > 0) {
            if (head.length() > 0) head.append("; ");
            head.append("connected ").append(c.connections).append(" fences/panes");
        }
        if (c.stairShapes > 0) {
            if (head.length() > 0) head.append("; ");
            head.append(c.stairShapes).append(" stair corners");
        }
        if (c.leaves > 0) {
            if (head.length() > 0) head.append("; ");
            head.append(c.leaves).append(" leaves made persistent");
        }
        if (head.length() > 0) notes.add(head.toString());
        int warned = 0;
        for (String w : c.warnings) {
            if (warned++ >= MAX_NOTES) break;
            notes.add("warning: " + w);
        }
        if (c.warnings.size() > warned) {
            notes.add("warning: ... " + (c.warnings.size() - warned) + " more completion conflicts");
        }
        return notes;
    }

    private static final class Counters {
        int doorTops, bedHeads, plantTops, connections, stairShapes, leaves;
        final List<String> warnings = new ArrayList<>(2);
    }

    // ------------------------------------------------------------------ 1. leaves

    private static void stepLeaves(VoxelModel model, BlockInfo info, long[] keys, Counters c) {
        for (long k : keys) {
            int x = VoxelModel.kx(k), y = VoxelModel.ky(k), z = VoxelModel.kz(k);
            BlockSpec s = model.get(x, y, z);
            if (s == null || !s.name().endsWith("_leaves")) continue;
            if (!info.hasProperty(s.name(), "persistent")) continue;
            if ("true".equals(info.getProp(s, "persistent"))) continue;
            BlockSpec updated = with(model, info, x, y, z, s, "persistent", "true", c);
            if (updated != null) c.leaves++;
        }
    }

    // ------------------------------------------------------------------ 2. multi-block completion

    private static void stepDoors(VoxelModel model, BlockInfo info, long[] keys, Counters c) {
        for (long k : keys) {
            int x = VoxelModel.kx(k), y = VoxelModel.ky(k), z = VoxelModel.kz(k);
            BlockSpec s = model.get(x, y, z);
            if (s == null || !isDoor(s)) continue;
            String half = info.getProp(s, "half");
            if (half == null) half = "lower";
            if (half.equals("lower")) {
                BlockSpec above = model.get(x, y + 1, z);
                if (matchesHalf(above, info, s.name(), "upper")) continue;
                if (above != null && isDoor(above)) continue; // stacked doors / already complete
                BlockSpec upper = info.with(s, "half", "upper");
                if (writeCompletion(model, info, x, y + 1, z, above, upper, c)) c.doorTops++;
            } else if (half.equals("upper")) {
                BlockSpec below = model.get(x, y - 1, z);
                if (matchesHalf(below, info, s.name(), "lower")) continue;
                if (below != null && !below.isAir()) continue;
                BlockSpec lower = info.with(s, "half", "lower");
                if (writeCompletion(model, info, x, y - 1, z, below, lower, c)) c.doorTops++;
            }
        }
    }

    private static void stepBeds(VoxelModel model, BlockInfo info, long[] keys, Counters c) {
        for (long k : keys) {
            int x = VoxelModel.kx(k), y = VoxelModel.ky(k), z = VoxelModel.kz(k);
            BlockSpec s = model.get(x, y, z);
            if (s == null || !isBed(s)) continue;
            String part = info.getProp(s, "part");
            if (part == null) part = "foot";
            Dir facing = Dir.parse(info.getProp(s, "facing"));
            if (facing == null || !facing.isHorizontal()) continue;
            if (part.equals("foot")) {
                int hx = x + facing.dx, hz = z + facing.dz;
                BlockSpec head = model.get(hx, y, hz);
                if (matchesPart(head, info, s.name(), "head")) continue;
                if (head != null && isBed(head)) continue; // do not clobber another bed
                BlockSpec target = info.with(s, "part", "head");
                if (writeCompletion(model, info, hx, y, hz, head, target, c)) c.bedHeads++;
            } else if (part.equals("head")) {
                int fx = x - facing.dx, fz = z - facing.dz;
                BlockSpec foot = model.get(fx, y, fz);
                if (matchesPart(foot, info, s.name(), "foot")) continue;
                if (foot != null && !foot.isAir()) continue;
                BlockSpec target = info.with(s, "part", "foot");
                if (writeCompletion(model, info, fx, y, fz, foot, target, c)) c.bedHeads++;
            }
        }
    }

    private static void stepPlants(VoxelModel model, BlockInfo info, long[] keys, Counters c) {
        for (long k : keys) {
            int x = VoxelModel.kx(k), y = VoxelModel.ky(k), z = VoxelModel.kz(k);
            BlockSpec s = model.get(x, y, z);
            if (!isDoublePlant(info, s)) continue;
            String half = info.getProp(s, "half");
            if (half == null) half = "lower";
            if (half.equals("lower")) {
                BlockSpec above = model.get(x, y + 1, z);
                if (matchesHalf(above, info, s.name(), "upper")) continue;
                BlockSpec upper = info.with(s, "half", "upper");
                if (writeCompletion(model, info, x, y + 1, z, above, upper, c)) c.plantTops++;
            } else if (half.equals("upper")) {
                BlockSpec below = model.get(x, y - 1, z);
                if (matchesHalf(below, info, s.name(), "lower")) continue;
                if (below != null && !below.isAir()) continue;
                BlockSpec lower = info.with(s, "half", "lower");
                if (writeCompletion(model, info, x, y - 1, z, below, lower, c)) c.plantTops++;
            }
        }
    }

    /**
     * Writes a completion block, unless it is already identical. Records a warning when a non-air block is
     * replaced. Returns true when the model changed.
     */
    private static boolean writeCompletion(VoxelModel model, BlockInfo info, int x, int y, int z,
                                           BlockSpec existing, BlockSpec desired, Counters c) {
        BlockSpec canonical = info.canonical(desired);
        if (Objects.equals(existing, canonical)) return false;
        if (existing != null && !existing.isAir()) {
            if (c.warnings.size() < MAX_NOTES) {
                c.warnings.add(existing + " at (" + x + "," + y + "," + z + ") replaced by " + canonical);
            }
        }
        model.set(x, y, z, canonical);
        return true;
    }

    // ------------------------------------------------------------------ 3. connections

    private static void stepConnections(VoxelModel model, BlockInfo info, long[] keys, Counters c) {
        for (long k : keys) {
            int x = VoxelModel.kx(k), y = VoxelModel.ky(k), z = VoxelModel.kz(k);
            BlockSpec s = model.get(x, y, z);
            if (s == null || s.isAir()) continue;
            if (isFence(s)) c.connections += fenceConnections(model, info, x, y, z, s);
            else if (isPane(s)) c.connections += paneConnections(model, info, x, y, z, s);
            else if (isWall(info, s)) c.connections += wallConnections(model, info, x, y, z, s);
        }
    }

    private static int fenceConnections(VoxelModel model, BlockInfo info, int x, int y, int z, BlockSpec s) {
        boolean changed = false;
        BlockSpec out = s;
        for (Dir d : Dir.HORIZONTAL) {
            if (!info.hasProperty(s.name(), d.id())) continue;
            boolean connect = fenceConnectsTo(model.get(x + d.dx, y, z + d.dz), info, d, s.name());
            String want = connect ? "true" : "false";
            if (!want.equals(info.getProp(out, d.id()))) {
                out = safeWith(info, out, d.id(), want);
                changed = true;
            }
        }
        if (!changed) return 0;
        model.set(x, y, z, info.canonical(out));
        return 1;
    }

    private static boolean fenceConnectsTo(BlockSpec neighbor, BlockInfo info, Dir side, String self) {
        if (neighbor == null || neighbor.isAir()) return false;
        if (isFence(neighbor)) return sameFenceFamily(self, neighbor.name());
        if (isFenceGate(neighbor)) return isPerpendicular(info, neighbor, side);
        if (self.equals("nether_brick_fence")) return false; // nether brick fences ignore solid blocks
        return sturdySupport(info, neighbor, side.opposite());
    }

    /**
     * Wooden fences of any species connect to each other; {@code nether_brick_fence} only connects to
     * another nether brick fence.
     */
    private static boolean sameFenceFamily(String self, String other) {
        boolean selfNether = self.equals("nether_brick_fence");
        boolean otherNether = other.equals("nether_brick_fence");
        return selfNether == otherNether;
    }

    private static int paneConnections(VoxelModel model, BlockInfo info, int x, int y, int z, BlockSpec s) {
        boolean changed = false;
        BlockSpec out = s;
        for (Dir d : Dir.HORIZONTAL) {
            if (!info.hasProperty(s.name(), d.id())) continue;
            boolean connect = paneConnectsTo(model.get(x + d.dx, y, z + d.dz), info, d);
            String want = connect ? "true" : "false";
            if (!want.equals(info.getProp(out, d.id()))) {
                out = safeWith(info, out, d.id(), want);
                changed = true;
            }
        }
        if (!changed) return 0;
        model.set(x, y, z, info.canonical(out));
        return 1;
    }

    private static boolean paneConnectsTo(BlockSpec neighbor, BlockInfo info, Dir side) {
        if (neighbor == null || neighbor.isAir()) return false;
        if (isPane(neighbor)) return true;
        if (isWall(info, neighbor)) return true;
        return sturdySupport(info, neighbor, side.opposite());
    }

    private static int wallConnections(VoxelModel model, BlockInfo info, int x, int y, int z, BlockSpec s) {
        boolean changed = false;
        BlockSpec out = s;
        int sides = 0;
        boolean ns = false, ew = false;
        for (Dir d : Dir.HORIZONTAL) {
            if (!info.hasProperty(s.name(), d.id())) continue;
            BlockSpec neighbor = model.get(x + d.dx, y, z + d.dz);
            boolean connect = wallConnectsTo(neighbor, info, d);
            if (connect) {
                sides++;
                if (d == Dir.NORTH || d == Dir.SOUTH) ns = true;
                else ew = true;
            }
            String want = "none";
            if (connect) {
                BlockSpec above = model.get(x, y + 1, z);
                boolean aboveSolid = above != null && !above.isAir();
                boolean tall = aboveSolid && (sturdySupport(info, above, Dir.DOWN) || isWall(info, above));
                want = tall ? "tall" : "low";
            }
            if (!want.equals(info.getProp(out, d.id()))) {
                out = safeWith(info, out, d.id(), want);
                changed = true;
            }
        }
        if (info.hasProperty(s.name(), "up")) {
            BlockSpec above = model.get(x, y + 1, z);
            boolean aboveAir = above == null || above.isAir();
            boolean straight = (ns != ew);
            String wantUp = (straight && aboveAir) ? "false" : "true";
            if (!wantUp.equals(info.getProp(out, "up"))) {
                out = safeWith(info, out, "up", wantUp);
                changed = true;
            }
        }
        if (!changed) return 0;
        model.set(x, y, z, info.canonical(out));
        return 1;
    }

    private static boolean wallConnectsTo(BlockSpec neighbor, BlockInfo info, Dir side) {
        if (neighbor == null || neighbor.isAir()) return false;
        if (isWall(info, neighbor)) return true;
        if (isPane(neighbor)) return true;
        if (isFenceGate(neighbor)) return isPerpendicular(info, neighbor, side);
        return sturdySupport(info, neighbor, side.opposite());
    }

    // ------------------------------------------------------------------ 4. stair shapes

    private static void stepStairs(VoxelModel model, BlockInfo info, long[] keys, Counters c) {
        for (long k : keys) {
            int x = VoxelModel.kx(k), y = VoxelModel.ky(k), z = VoxelModel.kz(k);
            BlockSpec s = model.get(x, y, z);
            if (s == null || !isStairs(s) || !info.hasProperty(s.name(), "shape")) continue;
            Dir facing = Dir.parse(info.getProp(s, "facing"));
            if (facing == null || !facing.isHorizontal()) continue;
            String half = info.getProp(s, "half");
            if (half == null) half = "bottom";

            String shape = "straight";
            boolean decided = false;
            // vanilla: a stair behind us (in facing direction) that is perpendicular -> outer corner
            BlockSpec behind = stairsAt(model, x + facing.dx, y, z + facing.dz);
            if (behind != null && half.equals(halfOf(info, behind))) {
                Dir bf = Dir.parse(info.getProp(behind, "facing"));
                if (bf != null && bf.isHorizontal() && bf.axis() != facing.axis()
                        && canTakeShape(model, info, x, y, z, facing, half, bf.opposite())) {
                    shape = bf == facing.ccw() ? "outer_left" : "outer_right";
                    decided = true;
                }
            }
            // otherwise a perpendicular stair in front of us -> inner corner
            if (!decided) {
                BlockSpec front = stairsAt(model, x - facing.dx, y, z - facing.dz);
                if (front != null && half.equals(halfOf(info, front))) {
                    Dir ff = Dir.parse(info.getProp(front, "facing"));
                    if (ff != null && ff.isHorizontal() && ff.axis() != facing.axis()
                            && canTakeShape(model, info, x, y, z, facing, half, ff)) {
                        shape = ff == facing.ccw() ? "inner_left" : "inner_right";
                    }
                }
            }
            if (shape.equals(info.getProp(s, "shape"))) continue;
            BlockSpec updated = safeWith(info, s, "shape", shape);
            if (updated == null || Objects.equals(updated, s)) continue;
            model.set(x, y, z, info.canonical(updated));
            c.stairShapes++;
        }
    }

    private static boolean canTakeShape(VoxelModel model, BlockInfo info, int x, int y, int z,
                                        Dir facing, String half, Dir d) {
        BlockSpec neighbor = model.get(x + d.dx, y, z + d.dz);
        if (neighbor == null || !isStairs(neighbor)) return true;
        Dir nf = Dir.parse(info.getProp(neighbor, "facing"));
        String nh = halfOf(info, neighbor);
        return nf == null || nf != facing || !half.equals(nh);
    }

    private static String halfOf(BlockInfo info, BlockSpec stair) {
        String h = info.getProp(stair, "half");
        return h == null ? "bottom" : h;
    }

    private static BlockSpec stairsAt(VoxelModel model, int x, int y, int z) {
        BlockSpec s = model.get(x, y, z);
        return isStairs(s) ? s : null;
    }

    // ------------------------------------------------------------------ helpers

    private static boolean isDoor(BlockSpec s) {
        return s != null && s.name().endsWith("_door");
    }

    private static boolean isBed(BlockSpec s) {
        return s != null && s.name().endsWith("_bed");
    }

    private static boolean isStairs(BlockSpec s) {
        return s != null && s.name().endsWith("_stairs");
    }

    private static boolean isTrapdoor(BlockSpec s) {
        return s != null && s.name().endsWith("_trapdoor");
    }

    private static boolean isFence(BlockSpec s) {
        return s != null && s.name().endsWith("_fence") && !s.name().endsWith("_fence_gate");
    }

    private static boolean isFenceGate(BlockSpec s) {
        return s != null && s.name().endsWith("_fence_gate");
    }

    private static boolean isPane(BlockSpec s) {
        return s != null && (s.name().endsWith("glass_pane") || s.name().equals("iron_bars"));
    }

    private static boolean isWall(BlockInfo info, BlockSpec s) {
        return s != null && s.name().endsWith("_wall") && info.hasProperty(s.name(), "up");
    }

    private static boolean isDoublePlant(BlockInfo info, BlockSpec s) {
        if (s == null || isDoor(s) || isTrapdoor(s) || isStairs(s) || isBed(s)) return false;
        String n = s.name();
        boolean knownHalf = info.hasProperty(n, "half");
        if (!knownHalf && !DOUBLE_PLANT_NAMES.contains(n)) return false;
        if (knownHalf && !DOUBLE_PLANT_NAMES.contains(n)) {
            // only accept blocks whose half really is lower/upper (not e.g. a chest-like "half")
            String h = info.getProp(s, "half");
            return "lower".equals(h) || "upper".equals(h);
        }
        return true;
    }

    /** True when a fence gate can connect to a fence at {@code side}: its facing axis is perpendicular. */
    private static boolean isPerpendicular(BlockInfo info, BlockSpec gate, Dir side) {
        Dir f = Dir.parse(info.getProp(gate, "facing"));
        if (f == null || !f.isHorizontal()) return false;
        return f.axis() != side.axis();
    }

    /** True when the block provides a full sturdy face against {@code face} and may carry connections. */
    private static boolean sturdySupport(BlockInfo info, BlockSpec neighbor, Dir face) {
        if (neighbor == null || neighbor.isAir()) return false;
        if (NON_SUPPORTING.contains(neighbor.name()) || neighbor.name().endsWith("_leaves")) return false;
        try {
            return info.isSturdyFace(neighbor, face);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static boolean matchesHalf(BlockSpec spec, BlockInfo info, String name, String half) {
        if (spec == null || !spec.name().equals(name)) return false;
        String h = info.getProp(spec, "half");
        if (h == null) h = "lower";
        return h.equals(half);
    }

    private static boolean matchesPart(BlockSpec spec, BlockInfo info, String name, String part) {
        if (spec == null || !spec.name().equals(name)) return false;
        String p = info.getProp(spec, "part");
        if (p == null) p = "foot";
        return p.equals(part);
    }

    /** Sets a property, returning the unchanged spec if the block info rejects it (never throws). */
    private static BlockSpec safeWith(BlockInfo info, BlockSpec spec, String key, String value) {
        try {
            return info.with(spec, key, value);
        } catch (IllegalArgumentException ex) {
            return spec;
        }
    }

    private static BlockSpec with(VoxelModel model, BlockInfo info, int x, int y, int z,
                                  BlockSpec spec, String key, String value, Counters c) {
        BlockSpec out = safeWith(info, spec, key, value);
        if (out == null || out.equals(spec)) return null;
        out = info.canonical(out);
        model.set(x, y, z, out);
        return out;
    }
}
