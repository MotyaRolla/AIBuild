package dev.aibuild.pipeline;

import dev.aibuild.analysis.AnalysisReport;
import dev.aibuild.analysis.AnalyzerOptions;
import dev.aibuild.analysis.StructureAnalyzer;
import dev.aibuild.model.BlockInfo;
import dev.aibuild.model.BlockRoles;
import dev.aibuild.model.BlockSpec;
import dev.aibuild.model.Dir;
import dev.aibuild.model.VoxelModel;
import dev.aibuild.script.Collision;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The last step of a script build, done by code rather than the model: mechanical defects that the analyzer can
 * point at cell by cell are repaired where they are. In order:
 * <ol>
 *   <li>collisions: wall cells that decoration was written into get their wall back (a wall banner or torch moves
 *       onto the face in front), and stairs, ladders or doors another part removed come back;</li>
 *   <li>ladders: a column keeps one facing, hangs on a real wall block (not glass or air) and is not capped by a
 *       floor, so the climb goes through;</li>
 *   <li>unsupported attachments: hanging lights move up to the ceiling, wall-mounted blocks turn to a wall that
 *       holds them, and what still hangs in the air is removed;</li>
 *   <li>blocked doors: loose furniture in front of or behind a door is taken away (walls and stairs stay);</li>
 *   <li>floating crumbs of up to 3 blocks are removed;</li>
 *   <li>dark interiors: lanterns under the ceiling (or wall torches) over the dark cells, a few passes until
 *       every reachable room is lit.</li>
 * </ol>
 * The repairs come back as a {@code part('finish', ...)} statement of plain block() calls, appended to the
 * script, so the stored script still reproduces the build exactly and later edits keep working.
 */
public final class Finisher {
    private Finisher() {
    }

    /** The statement to append (null when there is nothing to repair) and what was done, one note per kind. */
    public record Result(String statement, int edits, List<String> notes) {
    }

    public static final String PART = "finish";

    public static Result plan(VoxelModel model, BlockInfo info, List<Collision> collisions, boolean interior) {
        Finisher.Run r = new Finisher.Run(model.copy(), info);
        r.collisions(collisions);
        r.ladders();
        AnalysisReport rep = r.analyze(interior);
        r.attachments(rep);
        r.doors(rep);
        r.crumbs(rep);
        if (interior) r.light();
        if (r.edits.isEmpty()) return new Result(null, 0, r.notes());
        StringBuilder js = new StringBuilder("part('" + PART + "', () => {\n");
        js.append("  // mechanical repairs made after review: walls restored, attachments supported, doors cleared, rooms lit\n");
        r.edits.forEach((k, s) -> js.append("  block(").append(VoxelModel.kx(k)).append(", ").append(VoxelModel.ky(k)).append(", ")
                .append(VoxelModel.kz(k)).append(", '").append(s == null ? "air" : s.toString()).append("');\n"));
        js.append("});\n");
        return new Result(js.toString(), r.edits.size(), r.notes());
    }

    private static final class Run {
        final VoxelModel m;
        final BlockInfo info;
        final Map<Long, BlockSpec> edits = new LinkedHashMap<>();
        final Map<String, Integer> counts = new LinkedHashMap<>();

        Run(VoxelModel m, BlockInfo info) {
            this.m = m;
            this.info = info;
        }

        List<String> notes() {
            List<String> out = new ArrayList<>();
            counts.forEach((k, v) -> out.add(k + ": " + v));
            return out;
        }

        AnalysisReport analyze(boolean interior) {
            return StructureAnalyzer.analyze(m, info, new AnalyzerOptions(interior));
        }

        void set(int x, int y, int z, BlockSpec s, String why) {
            BlockSpec v = s == null || s.isAir() ? null : s;
            m.set(x, y, z, v == null ? BlockSpec.AIR : v);
            edits.put(VoxelModel.key(x, y, z), v);
            counts.merge(why, 1, Integer::sum);
        }

        BlockSpec at(int x, int y, int z) {
            BlockSpec s = m.get(x, y, z);
            return s == null || s.isAir() ? null : s;
        }

        boolean sturdy(int x, int y, int z, Dir face) {
            BlockSpec s = at(x, y, z);
            return s == null ? y <= 0 : info.isSturdyFace(s, face);
        }

        void collisions(List<Collision> collisions) {
            for (Collision c : collisions) {
                boolean embedded = c.kind().equals("embedded");
                for (Map.Entry<Long, BlockSpec> e : c.before().entrySet()) {
                    long k = e.getKey();
                    int x = VoxelModel.kx(k), y = VoxelModel.ky(k), z = VoxelModel.kz(k);
                    BlockSpec cur = at(x, y, z);
                    if (cur != null && cur.name().endsWith("_bed")) continue;
                    if (embedded) {
                        if (!BlockRoles.decor(cur, info)) continue;
                        set(x, y, z, e.getValue(), "wall cells restored where decoration had replaced them");
                        Dir f = cur.hasProp("facing") ? Dir.parse(cur.prop("facing")) : null;
                        if (f != null && f.dy == 0 && cur.name().contains("wall_") && at(x + f.dx, y, z + f.dz) == null) {
                            set(x + f.dx, y, z + f.dz, cur, "wall decoration moved onto the wall face");
                        }
                    } else if (!BlockRoles.access(cur) && !BlockRoles.structural(cur, info)) {
                        set(x, y, z, e.getValue(), "removed stairs, ladders or doors restored");
                    }
                }
            }
        }

        boolean ladder(int x, int y, int z) {
            BlockSpec s = at(x, y, z);
            return s != null && s.name().equals("ladder");
        }

        Dir facing(BlockSpec s) {
            Dir d = s.hasProp("facing") ? Dir.parse(s.prop("facing")) : Dir.NORTH;
            return d == null ? Dir.NORTH : d;
        }

        /** Climbs that go through: one facing per column, a wall block behind every rung, no floor across the shaft. */
        void ladders() {
            List<int[]> bottoms = new ArrayList<>();
            m.forEach((x, y, z, s) -> {
                if (s.name().equals("ladder") && !ladder(x, y - 1, z)) bottoms.add(new int[]{x, y, z});
            });
            for (int[] b : bottoms) {
                int x = b[0], z = b[2], y = b[1];
                Dir f = facing(at(x, y, z));
                // the column, bridging single floor blocks between two runs of ladder
                while (true) {
                    if (ladder(x, y + 1, z)) {
                        y++;
                    } else if (at(x, y + 1, z) != null && BlockRoles.structural(at(x, y + 1, z), info) && ladder(x, y + 2, z)) {
                        set(x, y + 1, z, BlockSpec.of("ladder", Map.of("facing", f.id())), "floors across a ladder shaft opened");
                        y++;
                    } else break;
                }
                int top = y;
                for (int yy = b[1]; yy <= top; yy++) {
                    BlockSpec s = at(x, yy, z);
                    if (s != null && s.name().equals("ladder") && facing(s) != f) set(x, yy, z, s.with("facing", f.id()), "ladders turned to face one way up the column");
                    if (!sturdy(x - f.dx, yy, z - f.dz, f)) {
                        BlockSpec wall = wallAround(x - f.dx, yy, z - f.dz);
                        if (wall != null) set(x - f.dx, yy, z - f.dz, wall, "wall blocks put behind ladders hanging on glass or air");
                    }
                }
                // a floor capping the top rung with a room above: open a hatch
                BlockSpec cap = at(x, top + 1, z);
                if (top > b[1] && cap != null && BlockRoles.structural(cap, info) && !cap.name().endsWith("_door")
                        && at(x, top + 2, z) == null && at(x, top + 3, z) == null) {
                    set(x, top + 1, z, BlockSpec.of("ladder", Map.of("facing", f.id())), "floors across a ladder shaft opened");
                    if (!sturdy(x - f.dx, top + 1, z - f.dz, f)) {
                        BlockSpec wall = wallAround(x - f.dx, top + 1, z - f.dz);
                        if (wall != null) set(x - f.dx, top + 1, z - f.dz, wall, "wall blocks put behind ladders hanging on glass or air");
                    }
                }
            }
        }

        /** The wall block most of a cell's sides are made of, when the cell sits in a wall line (2+ full neighbours). */
        BlockSpec wallAround(int x, int y, int z) {
            BlockSpec cur = at(x, y, z);
            if (cur != null && (BlockRoles.access(cur) || cur.name().endsWith("_bed"))) return null;
            Map<BlockSpec, Integer> n = new LinkedHashMap<>();
            for (int[] d : new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}}) {
                BlockSpec s = at(x + d[0], y + d[1], z + d[2]);
                if (s != null && info.isOccluding(s) && BlockRoles.structural(s, info) && !s.hasProp("facing") && !s.hasProp("axis")) n.merge(s, 1, Integer::sum);
            }
            int total = n.values().stream().mapToInt(Integer::intValue).sum();
            if (total < 2) return null;
            return n.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
        }

        void attachments(AnalysisReport rep) {
            for (int[] p : rep.cells.getOrDefault("unsupported_ceiling", List.of())) {
                BlockSpec s = at(p[0], p[1], p[2]);
                if (s == null) continue;
                int t = p[1] + 1;
                while (t < p[1] + 7 && at(p[0], t, p[2]) == null) t++;
                if (at(p[0], t, p[2]) != null && t - 1 > p[1]) {
                    set(p[0], p[1], p[2], null, "hanging lights moved up to the ceiling");
                    set(p[0], t - 1, p[2], s, "hanging lights moved up to the ceiling");
                } else if (s.hasProp("hanging") && at(p[0], p[1] - 1, p[2]) != null) {
                    set(p[0], p[1], p[2], s.with("hanging", "false"), "hanging lights with no ceiling stood on the floor");
                } else set(p[0], p[1], p[2], null, "attachments with nothing to hold them removed");
            }
            for (int[] p : rep.cells.getOrDefault("unsupported_floor", List.of())) {
                BlockSpec s = at(p[0], p[1], p[2]);
                if (s == null || s.name().endsWith("_door")) continue;
                if (s.hasProp("hanging") && at(p[0], p[1] + 1, p[2]) != null) {
                    set(p[0], p[1], p[2], s.with("hanging", "true"), "lights under a ceiling hung from it");
                } else {
                    set(p[0], p[1], p[2], null, "attachments with nothing to hold them removed");
                    // the upper half of a tall plant, or the rest of a sugar cane stack, would float on its own
                    for (int t = p[1] + 1; ; t++) {
                        BlockSpec up = at(p[0], t, p[2]);
                        if (up == null || !BlockRoles.decor(up, info) || !(up.name().equals(s.name()) || "upper".equals(up.prop("half")))) break;
                        set(p[0], t, p[2], null, "attachments with nothing to hold them removed");
                    }
                }
            }
            for (int[] p : rep.cells.getOrDefault("unsupported_wall", List.of())) {
                BlockSpec s = at(p[0], p[1], p[2]);
                if (s == null || s.name().equals("ladder") && (ladder(p[0], p[1] - 1, p[2]) || ladder(p[0], p[1] + 1, p[2]))) continue;
                Dir best = null;
                for (Dir d : Dir.HORIZONTAL) if (sturdy(p[0] - d.dx, p[1], p[2] - d.dz, d)) { best = d; break; }
                if (best != null && s.hasProp("facing")) set(p[0], p[1], p[2], s.with("facing", best.id()), "wall-mounted blocks turned to the wall that holds them");
                else set(p[0], p[1], p[2], null, "attachments with nothing to hold them removed");
            }
        }

        void doors(AnalysisReport rep) {
            for (int[] p : rep.cells.getOrDefault("door_blocked", List.of())) {
                BlockSpec s = at(p[0], p[1], p[2]);
                if (s == null || BlockRoles.structural(s, info) || BlockRoles.access(s)) continue;
                if (s.name().endsWith("_bed")) {
                    for (Dir d : Dir.HORIZONTAL) {
                        BlockSpec o = at(p[0] + d.dx, p[1], p[2] + d.dz);
                        if (o != null && o.name().equals(s.name())) set(p[0] + d.dx, p[1], p[2] + d.dz, null, "furniture blocking doors moved away");
                    }
                }
                set(p[0], p[1], p[2], null, "furniture blocking doors moved away");
            }
        }

        void crumbs(AnalysisReport rep) {
            for (int[] p : rep.cells.getOrDefault("floating_small", List.of())) {
                if (at(p[0], p[1], p[2]) != null) set(p[0], p[1], p[2], null, "floating crumbs removed");
            }
        }

        void light() {
            for (int pass = 0; pass < 4; pass++) {
                AnalysisReport rep = analyze(true);
                Set<Long> sealed = new HashSet<>();
                for (int[] p : rep.cells.getOrDefault("sealed", List.of())) sealed.add(VoxelModel.key(p[0], p[1], p[2]));
                List<int[]> placed = new ArrayList<>();
                for (int[] p : rep.cells.getOrDefault("dark", List.of())) {
                    if (sealed.contains(VoxelModel.key(p[0], p[1], p[2]))) continue;
                    if (placed.stream().anyMatch(q -> Math.abs(q[0] - p[0]) + Math.abs(q[1] - p[1]) + Math.abs(q[2] - p[2]) < 9)) continue;
                    if (lightAt(p[0], p[1], p[2])) placed.add(p);
                    if (placed.size() >= 40) break;
                }
                if (placed.isEmpty()) return;
            }
        }

        /** A lantern under the ceiling above a dark floor cell, else a wall torch beside it at head height. */
        boolean lightAt(int x, int y, int z) {
            for (int t = y + 2; t <= y + 6; t++) {
                if (at(x, t - 1, z) != null) break;
                if (at(x, t, z) == null && at(x, t + 1, z) != null) {
                    set(x, t, z, BlockSpec.of("lantern", Map.of("hanging", "true")), "lanterns hung in dark rooms");
                    return true;
                }
            }
            if (at(x, y + 1, z) != null) return false;
            for (Dir d : Dir.HORIZONTAL) {
                // support at pos - facing: a torch facing d leans on the wall behind it
                if (BlockRoles.structural(at(x - d.dx, y + 1, z - d.dz), info) && sturdy(x - d.dx, y + 1, z - d.dz, d)) {
                    set(x, y + 1, z, BlockSpec.of("wall_torch", Map.of("facing", d.id())), "wall torches in dark corners");
                    return true;
                }
            }
            return false;
        }
    }
}
