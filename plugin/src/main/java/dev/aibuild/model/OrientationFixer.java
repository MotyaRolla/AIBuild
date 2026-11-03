package dev.aibuild.model;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Context-aware correction of stair facing/half and slab type — the two block families an LLM most often
 * gets wrong. Only high-confidence situations are changed; everything is derived from the neighbourhood:
 *
 * <ol>
 *   <li><b>Slopes</b> (roofs, staircases): a bottom stair that sits on a diagonal line of bottom stairs rising
 *   toward direction d must face d (you walk up toward the tall back side). Hip corners (two perpendicular
 *   rising directions) keep one of the two valid facings.</li>
 *   <li><b>Wall-backed</b> (eaves, window sills, plinth skirts, door steps, wall benches, brackets): a stair with
 *   exactly one solid wall neighbour and a free cell on the opposite side must have its back to the wall
 *   (facing toward the wall). Applies to both halves.</li>
 *   <li><b>Chairs</b>: a free-standing bottom stair next to exactly one table-like block (fence/wall post, top
 *   slab, top trapdoor) faces away from the table (seat toward the table).</li>
 *   <li><b>Hanging slab</b>: an isolated bottom slab with nothing below and a solid block above becomes a top
 *   slab (flush with the ceiling/beam).</li>
 *   <li><b>Gapped slab</b>: an isolated top slab resting on a full block with nothing above becomes a bottom
 *   slab (no half-block gap).</li>
 * </ol>
 * Deterministic and idempotent. Run before {@link ShapeResolver} (which then computes stair corner shapes).
 */
public final class OrientationFixer {
    private OrientationFixer() {
    }

    /** A proposed / applied change with a short reason. */
    public record Change(int x, int y, int z, BlockSpec from, BlockSpec to, String rule) {
        @Override
        public String toString() {
            return rule + " (" + x + "," + y + "," + z + ") " + from + " -> " + to;
        }
    }

    /** Computes the changes without modifying the model (local rules, then row-consensus propagation). */
    public static List<Change> analyze(VoxelModel model, BlockInfo info) {
        VoxelModel work = model.copy();
        Map<Long, Change> merged = new LinkedHashMap<>();
        List<Change> first = new ArrayList<>();
        work.forEach((x, y, z, s) -> {
            if (s == null || s.isAir()) return;
            String n = s.name();
            Change c = null;
            if (n.endsWith("_stairs")) c = stair(work, info, x, y, z, s);
            else if (n.endsWith("_slab")) c = slab(work, info, x, y, z, s);
            if (c != null) first.add(c);
        });
        for (Change c : first) {
            work.set(c.x, c.y, c.z, c.to);
            merged.put(VoxelModel.key(c.x, c.y, c.z), c);
        }
        // row consensus: a wall-backed row whose neighbours were corrected pulls blocked members along
        for (int iter = 0; iter < 16; iter++) {
            List<Change> more = new ArrayList<>();
            work.forEach((x, y, z, s) -> {
                if (s == null || !s.name().endsWith("_stairs")) return;
                Change prior = merged.get(VoxelModel.key(x, y, z));
                if (prior != null) return; // already decided (local rule or earlier consensus): never flip back
                if (inSlope(work, info, x, y, z, s)) return;
                Change c = consensus(work, info, x, y, z, s);
                if (c != null) more.add(c);
            });
            if (more.isEmpty()) break;
            for (Change c : more) {
                work.set(c.x, c.y, c.z, c.to);
                long k = VoxelModel.key(c.x, c.y, c.z);
                Change prev = merged.get(k);
                BlockSpec orig = prev != null ? prev.from : model.get(c.x, c.y, c.z);
                merged.put(k, new Change(c.x, c.y, c.z, orig, c.to, c.rule));
            }
        }
        List<Change> out = new ArrayList<>();
        for (Change c : merged.values()) if (!c.to.equals(c.from)) out.add(c);
        out.sort((a, b) -> a.y != b.y ? Integer.compare(a.y, b.y) : a.z != b.z ? Integer.compare(a.z, b.z) : Integer.compare(a.x, b.x));
        return out;
    }

    /**
     * A stair in a row (neighbours along one horizontal axis) adopts facing F when at least one row neighbour
     * of the same half already faces F (perpendicular to the row), and the cell behind it (p+F) is a wall.
     */
    private static Change consensus(VoxelModel m, BlockInfo info, int x, int y, int z, BlockSpec s) {
        Dir facing = Dir.parse(info.getProp(s, "facing"));
        String half = info.getProp(s, "half");
        if (facing == null || !facing.isHorizontal()) return null;
        for (Dir along : new Dir[]{Dir.EAST, Dir.SOUTH}) {
            Dir agreed = null;
            boolean conflict = false;
            for (Dir side : new Dir[]{along, along.opposite()}) {
                BlockSpec n = m.get(x + side.dx, y, z + side.dz);
                if (n == null || !n.name().endsWith("_stairs") || !java.util.Objects.equals(half, info.getProp(n, "half"))) continue;
                Dir f = Dir.parse(info.getProp(n, "facing"));
                if (f == null || f.axis() == along.axis()) continue;
                if (f == facing) continue;
                if (agreed != null && agreed != f) conflict = true;
                agreed = f;
            }
            if (agreed == null || conflict) continue;
            if (!isWall(m, info, x + agreed.dx, y, z + agreed.dz, agreed.opposite())) continue;
            return change(info, x, y, z, s, "facing", agreed, "wall-backed stairs (row consensus)");
        }
        return null;
    }

    /**
     * Applies all changes. Decisions are made on the unmodified snapshot so the result does not depend on
     * iteration order. Returns compact notes for the LLM, e.g. "auto-oriented 12 roof stairs, 3 eave stairs".
     */
    public static List<String> fix(VoxelModel model, BlockInfo info) {
        return fix(model, info, java.util.Set.of());
    }

    /** Like {@link #fix(VoxelModel, BlockInfo)} but never touches cells in {@code keep} (deliberately oriented). */
    public static List<String> fix(VoxelModel model, BlockInfo info, java.util.Set<Long> keep) {
        List<Change> changes = analyze(model, info);
        if (!keep.isEmpty()) changes.removeIf(c -> keep.contains(VoxelModel.key(c.x, c.y, c.z)));
        for (Change c : changes) model.set(c.x, c.y, c.z, c.to);
        return summarize(changes);
    }

    public static List<String> summarize(List<Change> changes) {
        if (changes.isEmpty()) return List.of();
        Map<String, List<Change>> byRule = new LinkedHashMap<>();
        for (Change c : changes) byRule.computeIfAbsent(c.rule, k -> new ArrayList<>()).add(c);
        List<String> notes = new ArrayList<>();
        for (Map.Entry<String, List<Change>> e : byRule.entrySet()) {
            StringBuilder sb = new StringBuilder("auto-fixed ").append(e.getValue().size()).append(' ').append(e.getKey()).append(':');
            int shown = 0;
            for (Change c : e.getValue()) {
                if (shown++ >= 6) {
                    sb.append(" …");
                    break;
                }
                sb.append(" (").append(c.x).append(',').append(c.y).append(',').append(c.z).append(")->").append(describe(c.to));
            }
            notes.add(sb.toString());
        }
        return notes;
    }

    private static String describe(BlockSpec s) {
        StringBuilder sb = new StringBuilder();
        for (String k : new String[]{"facing", "half", "type"}) {
            String v = s.prop(k);
            if (v != null) sb.append(sb.isEmpty() ? "" : ",").append(k).append('=').append(v);
        }
        return sb.isEmpty() ? "default" : sb.toString();
    }

    // ------------------------------------------------------------------ stairs

    private static Change stair(VoxelModel m, BlockInfo info, int x, int y, int z, BlockSpec s) {
        Dir facing = Dir.parse(info.getProp(s, "facing"));
        String half = info.getProp(s, "half");
        if (facing == null || !facing.isHorizontal()) return null;
        boolean bottom = !"top".equals(half);

        // 1) slope membership (bottom stairs only)
        if (bottom) {
            Set<Dir> rising = EnumSet.noneOf(Dir.class);
            for (Dir d : Dir.HORIZONTAL) {
                if (isBottomStair(m, info, x + d.dx, y + 1, z + d.dz) || isBottomStair(m, info, x - d.dx, y - 1, z - d.dz)) {
                    rising.add(d);
                }
            }
            if (!rising.isEmpty()) {
                if (rising.size() == 1) {
                    Dir d = rising.iterator().next();
                    return d == facing ? null : change(info, x, y, z, s, "facing", d, "slope stairs (must face uphill)");
                }
                if (rising.size() == 2) {
                    Dir[] two = rising.toArray(new Dir[0]);
                    if (two[0].axis() != two[1].axis()) { // hip corner
                        if (rising.contains(facing)) return null;
                        Dir pick = two[0].axis() == 'z' ? two[0] : two[1];
                        return change(info, x, y, z, s, "facing", pick, "slope corner stairs");
                    }
                }
                return null; // ridge/valley or ambiguous: leave it
            }
        }

        // 2) wall-backed: exactly one direction with a wall on that side AND a free cell on the opposite side
        //    (handles corner pillars next to a skirt and stairs squeezed against posts)
        // a stair that continues a row of stairs along axis A has its back perpendicular to A
        boolean rowX = isStair(m, x + 1, y, z) || isStair(m, x - 1, y, z);
        boolean rowZ = isStair(m, x, y, z + 1) || isStair(m, x, y, z - 1);
        Dir wall = null;
        int candidates = 0, walls = 0;
        for (Dir d : Dir.HORIZONTAL) {
            if (!isWall(m, info, x + d.dx, y, z + d.dz, d.opposite())) continue;
            if ((d.axis() == 'x' && rowX) || (d.axis() == 'z' && rowZ)) continue;
            walls++;
            // the front may hold a bush, lantern post, path... but not another wall (then it is squeezed)
            if (!isWall(m, info, x - d.dx, y, z - d.dz, d)) {
                candidates++;
                wall = d;
            }
        }
        if (candidates == 1) {
            if (facing == wall) return null;
            return change(info, x, y, z, s, "facing", wall, bottom ? "wall-backed stairs (back to the wall)" : "upside-down stairs (back to the wall)");
        }

        // 3) chairs at tables
        if (bottom && walls == 0) {
            Dir table = null;
            int tables = 0;
            for (Dir d : Dir.HORIZONTAL) {
                if (isTableLike(m, info, x + d.dx, y, z + d.dz)) {
                    tables++;
                    table = d;
                }
            }
            if (tables == 1 && isFree(m, info, x - table.dx, y, z - table.dz)) {
                Dir want = table.opposite();
                if (facing == want) return null;
                return change(info, x, y, z, s, "facing", want, "chair stairs (seat toward the table)");
            }
        }
        return null;
    }

    private static boolean isStair(VoxelModel m, int x, int y, int z) {
        BlockSpec b = m.get(x, y, z);
        return b != null && b.name().endsWith("_stairs");
    }

    private static boolean inSlope(VoxelModel m, BlockInfo info, int x, int y, int z, BlockSpec s) {
        if ("top".equals(info.getProp(s, "half"))) return false;
        for (Dir d : Dir.HORIZONTAL) {
            if (isBottomStair(m, info, x + d.dx, y + 1, z + d.dz) || isBottomStair(m, info, x - d.dx, y - 1, z - d.dz)) return true;
        }
        return false;
    }

    private static boolean isBottomStair(VoxelModel m, BlockInfo info, int x, int y, int z) {
        BlockSpec b = m.get(x, y, z);
        return b != null && b.name().endsWith("_stairs") && !"top".equals(info.getProp(b, "half"));
    }

    /** A wall = full sturdy face toward the stair, not itself a stair/slab/leaves (those are not walls). */
    private static boolean isWall(VoxelModel m, BlockInfo info, int x, int y, int z, Dir faceTowardStair) {
        BlockSpec b = m.get(x, y, z);
        if (b == null || b.isAir()) return false;
        String n = b.name();
        if (n.endsWith("_stairs") || n.endsWith("_slab") || n.endsWith("_leaves") || n.endsWith("_door")) return false;
        return info.isSturdyFace(b, faceTowardStair);
    }

    private static boolean isFree(VoxelModel m, BlockInfo info, int x, int y, int z) {
        BlockSpec b = m.get(x, y, z);
        return b == null || b.isAir() || !info.isSolid(b) || b.name().endsWith("_carpet");
    }

    private static boolean isTableLike(VoxelModel m, BlockInfo info, int x, int y, int z) {
        BlockSpec b = m.get(x, y, z);
        if (b == null || b.isAir()) return false;
        String n = b.name();
        if (n.endsWith("_slab")) return "top".equals(info.getProp(b, "type"));
        if (n.endsWith("_trapdoor")) return "top".equals(info.getProp(b, "half")) && !"true".equals(info.getProp(b, "open"));
        if (n.endsWith("_fence") || (n.endsWith("_wall") && info.hasProperty(n, "up"))) {
            BlockSpec above = m.get(x, y + 1, z);
            // a post with a top on it (pressure plate, carpet, slab) — or a bare post at seat height
            return above == null || above.isAir() || above.name().endsWith("_pressure_plate")
                    || above.name().endsWith("_carpet") || above.name().endsWith("_slab");
        }
        return false;
    }

    // ------------------------------------------------------------------ slabs

    private static Change slab(VoxelModel m, BlockInfo info, int x, int y, int z, BlockSpec s) {
        String type = info.getProp(s, "type");
        if (type == null || "double".equals(type) || y < 1) return null;
        if (!isolatedSlab(m, info, x, y, z, type)) return null;
        BlockSpec below = m.get(x, y - 1, z);
        BlockSpec above = m.get(x, y + 1, z);
        boolean belowEmpty = (below == null && y - 1 >= 1) || (below != null && (below.isAir() || !info.isSolid(below)));
        boolean aboveEmpty = above == null || above.isAir() || !info.isSolid(above);
        if ("bottom".equals(type)) {
            if (belowEmpty && above != null && !above.isAir() && info.isSturdyFace(above, Dir.DOWN)) {
                return change(info, x, y, z, s, "type", null, "hanging slab -> top (flush with the block above)");
            }
        } else if ("top".equals(type)) {
            boolean belowFull = below == null ? y - 1 <= 0 : info.isOccluding(below);
            if (belowFull && aboveEmpty && (above == null || !above.name().endsWith("_carpet"))) {
                return change(info, x, y, z, s, "type", null, "gapped slab -> bottom (resting on the block below)");
            }
        }
        return null;
    }

    /** True when no horizontal neighbour is a slab of the same type (i.e. not part of a slab surface). */
    private static boolean isolatedSlab(VoxelModel m, BlockInfo info, int x, int y, int z, String type) {
        for (Dir d : Dir.HORIZONTAL) {
            BlockSpec n = m.get(x + d.dx, y, z + d.dz);
            if (n != null && n.name().endsWith("_slab") && type.equals(info.getProp(n, "type"))) return false;
        }
        return true;
    }

    private static Change change(BlockInfo info, int x, int y, int z, BlockSpec s, String key, Dir facing, String rule) {
        try {
            BlockSpec to;
            if (key.equals("facing")) {
                to = info.with(s, "facing", facing.id());
            } else {
                String cur = info.getProp(s, "type");
                to = info.with(s, "type", "top".equals(cur) ? "bottom" : "top");
            }
            return to.equals(s) ? null : new Change(x, y, z, s, to, rule);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
