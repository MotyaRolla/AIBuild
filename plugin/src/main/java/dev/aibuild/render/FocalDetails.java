package dev.aibuild.render;

import dev.aibuild.analysis.IsoRenderer;
import dev.aibuild.model.BlockInfo;
import dev.aibuild.model.BlockSpec;
import dev.aibuild.model.VoxelModel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The details a viewer's eye goes to first: small marks of a clearly different colour set into a larger surface
 * (eyes and a nose on a head, a door or a sign on a wall, a buckle on a band). In the whole-build views they are a
 * few pixels, so whether they read as what they are, are level, symmetric and sit in the surface is only seen up
 * close. Found from colour alone: visible blocks are grouped into patches of similar colour; a small patch that a
 * much larger patch of contrasting colour holds is a mark, and marks within two blocks of each other are one detail.
 */
public final class FocalDetails {
    /** A detail: its box, the direction it faces (towards the viewer), its block count and its colours by name. */
    public record Detail(int[] box, double[] facing, int cells, List<String> blocks, double score) {
    }

    /** Largest patch that still counts as a mark rather than a surface. */
    static final int MAX_MARK = 40;
    /** Largest detail (box side) worth a close-up of its own; a bigger cluster of marks is a pattern on a surface. */
    static final int MAX_SIDE = 12;
    private static final int SIMILAR = 60 * 60, CONTRAST = 110 * 110;
    private static final int[][] N6 = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private FocalDetails() {
    }

    /** Up to {@code max} details inside {@code within} (x0,y0,z0,x1,y1,z1), strongest first. */
    public static List<Detail> find(VoxelModel m, BlockInfo info, int[] within, int max) {
        if (within == null || max <= 0) return List.of();
        Map<Long, Integer> colour = new HashMap<>();
        Map<String, Integer> byName = new HashMap<>();
        for (int x = within[0]; x <= within[3]; x++) {
            for (int y = within[1]; y <= within[4]; y++) {
                for (int z = within[2]; z <= within[5]; z++) {
                    BlockSpec s = m.get(x, y, z);
                    if (!cube(s) || !inSight(m, x, y, z)) continue;
                    colour.put(VoxelModel.key(x, y, z), byName.computeIfAbsent(s.name(), n -> IsoRenderer.colourOf(s, info)));
                }
            }
        }
        if (colour.isEmpty() || colour.size() > 400_000) return List.of();
        // patches of similar colour, joined across edges and corners so a mark stepping over a curve stays one patch
        Map<Long, Integer> patch = new HashMap<>();
        List<List<Long>> patches = new ArrayList<>();
        for (long k : colour.keySet()) {
            if (patch.containsKey(k)) continue;
            int id = patches.size();
            List<Long> cells = new ArrayList<>();
            ArrayDeque<Long> q = new ArrayDeque<>();
            q.add(k);
            patch.put(k, id);
            while (!q.isEmpty()) {
                long c = q.poll();
                cells.add(c);
                int cx = VoxelModel.kx(c), cy = VoxelModel.ky(c), cz = VoxelModel.kz(c);
                int cc = colour.get(c);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            long n = VoxelModel.key(cx + dx, cy + dy, cz + dz);
                            Integer nc = colour.get(n);
                            if (nc == null || patch.containsKey(n) || dist2(cc, nc) > SIMILAR) continue;
                            patch.put(n, id);
                            q.add(n);
                        }
                    }
                }
            }
            patches.add(cells);
        }
        // marks: small patches held by a patch at least three times their size, in a contrasting colour
        List<Integer> marks = new ArrayList<>();
        double[] markScore = new double[patches.size()];
        for (int id = 0; id < patches.size(); id++) {
            List<Long> cells = patches.get(id);
            if (cells.size() > MAX_MARK) continue;
            Map<Integer, Integer> touch = new HashMap<>();
            double contrast = 0;
            int contacts = 0;
            for (long c : cells) {
                int cx = VoxelModel.kx(c), cy = VoxelModel.ky(c), cz = VoxelModel.kz(c);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            long n = VoxelModel.key(cx + dx, cy + dy, cz + dz);
                            Integer other = patch.get(n);
                            if (other == null || other == id) continue;
                            int d = dist2(colour.get(c), colour.get(n));
                            if (d < CONTRAST || patches.get(other).size() < 3 * cells.size()) continue;
                            touch.merge(other, 1, Integer::sum);
                            contrast += Math.sqrt(d);
                            contacts++;
                        }
                    }
                }
            }
            if (contacts == 0) continue;
            marks.add(id);
            markScore[id] = contrast / contacts * Math.sqrt(cells.size());
        }
        // details: marks within two blocks of each other
        List<Detail> out = new ArrayList<>();
        boolean[] used = new boolean[patches.size()];
        for (int id : marks) {
            if (used[id]) continue;
            List<Integer> group = new ArrayList<>(List.of(id));
            used[id] = true;
            int[] box = bounds(patches.get(id));
            boolean grew = true;
            while (grew) {
                grew = false;
                for (int o : marks) {
                    if (used[o]) continue;
                    int[] ob = bounds(patches.get(o));
                    if (!near(box, ob, 2)) continue;
                    used[o] = true;
                    group.add(o);
                    box = union(box, ob);
                    grew = true;
                }
            }
            if (Math.max(box[3] - box[0], Math.max(box[4] - box[1], box[5] - box[2])) + 1 > MAX_SIDE) continue;
            double score = 0;
            int cells = 0;
            double[] facing = new double[3];
            int side = 0, flat = 0;
            Map<String, Integer> names = new java.util.LinkedHashMap<>();
            for (int g : group) {
                score += markScore[g];
                for (long c : patches.get(g)) {
                    cells++;
                    int cx = VoxelModel.kx(c), cy = VoxelModel.ky(c), cz = VoxelModel.kz(c);
                    names.merge(m.get(cx, cy, cz).name(), 1, Integer::sum);
                    for (int[] d : N6) {
                        BlockSpec n = m.get(cx + d[0], cy + d[1], cz + d[2]);
                        if (n == null || n.isAir()) {
                            if (d[1] == 0) side++;
                            else flat++;
                            facing[0] += d[0];
                            facing[1] += d[1];
                            facing[2] += d[2];
                        }
                    }
                }
            }
            List<String> blocks = names.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).map(Map.Entry::getKey).limit(4).toList();
            // a mark on a side faces the way its side faces; one round a body (a band round a hat, a stripe round a
            // tower) faces every way and is shown from the front, the south; one on a top is seen from above
            double[] dir;
            if (flat > side) dir = new double[]{0, facing[1] >= 0 ? 1 : -1, 0};
            else {
                double reach = Math.hypot(facing[0], facing[2]);
                dir = reach < 0.25 * side ? new double[]{0, 0, 1} : new double[]{facing[0] / reach, 0, facing[2] / reach};
            }
            out.add(new Detail(box, dir, cells, blocks, score));
        }
        out.sort((a, b) -> Double.compare(b.score(), a.score()));
        return out.size() > max ? List.copyOf(out.subList(0, max)) : out;
    }

    /**
     * A close-up of a detail as a visitor stepping up to it sees it: from the side it faces, turned 35 degrees so its
     * depth shows (a mark standing out of the surface or wrapping around a curve), a little above, framed with a few
     * blocks of the surface around it.
     */
    public static VoxelRaster.Camera camera(Detail d, int w, int h) {
        int[] b = d.box();
        double[] c = {(b[0] + b[3] + 1) / 2.0, (b[1] + b[4] + 1) / 2.0, (b[2] + b[5] + 1) / 2.0};
        double[] f = d.facing();
        double az = Math.atan2(f[0], f[2]);
        boolean flat = Math.abs(f[1]) > 0.8;   // a detail on a top surface: seen from above at a slant
        double yaw = az + Math.toRadians(35), pitch = Math.toRadians(flat ? 55 : 18);
        double[] dir = {Math.sin(yaw) * Math.cos(pitch), Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch)};
        double radius = Math.max(5.5, Math.max(b[3] - b[0], Math.max(b[4] - b[1], b[5] - b[2])) / 2.0 + 4);
        double fov = 40;
        double dist = radius / Math.tan(Math.toRadians(fov) / 2);
        return VoxelRaster.look(new double[]{c[0] + dir[0] * dist, c[1] + dir[1] * dist, c[2] + dir[2] * dist}, c, fov, w, h);
    }

    /** Where a detail is and what it is made of, for an image label. */
    public static String describe(Detail d) {
        int[] b = d.box();
        return String.join(", ", d.blocks()) + " at x " + b[0] + ".." + b[3] + " y " + b[1] + ".." + b[4] + " z " + b[2] + ".." + b[5]
                + ", seen from " + side(d.facing());
    }

    static String side(double[] f) {
        if (Math.abs(f[1]) > 0.8) return f[1] > 0 ? "above" : "below";
        String ns = f[2] > 0.38 ? "south" : f[2] < -0.38 ? "north" : "";
        String ew = f[0] > 0.38 ? "east" : f[0] < -0.38 ? "west" : "";
        return ns.isEmpty() && ew.isEmpty() ? "the front" : ns.isEmpty() ? ew : ew.isEmpty() ? ns : ns + "-" + ew;
    }

    private static boolean cube(BlockSpec s) {
        if (s == null || s.isAir()) return false;
        String n = s.name();
        return !(n.endsWith("_stairs") || n.endsWith("_slab") || n.endsWith("_wall") || n.endsWith("_fence") || n.contains("pane")
                || n.endsWith("_button") || n.endsWith("_trapdoor") || n.endsWith("_door") || n.contains("torch") || n.contains("lantern")
                || n.contains("grass") && !n.equals("grass_block") || n.contains("flower") || n.contains("fern") || n.contains("mushroom")
                && !n.endsWith("_block") || n.contains("carpet") || n.contains("leaves") || n.contains("vine") || n.contains("sign")
                || n.contains("banner") || n.contains("water") || n.contains("lava"));
    }

    private static boolean inSight(VoxelModel m, int x, int y, int z) {
        for (int[] d : N6) {
            BlockSpec n = m.get(x + d[0], y + d[1], z + d[2]);
            if (!cube(n)) return true;
        }
        return false;
    }

    private static int dist2(int p, int q) {
        int dr = (p >> 16 & 255) - (q >> 16 & 255), dg = (p >> 8 & 255) - (q >> 8 & 255), db = (p & 255) - (q & 255);
        return dr * dr + dg * dg + db * db;
    }

    private static int[] bounds(List<Long> cells) {
        int[] b = null;
        for (long c : cells) {
            int x = VoxelModel.kx(c), y = VoxelModel.ky(c), z = VoxelModel.kz(c);
            b = b == null ? new int[]{x, y, z, x, y, z} : new int[]{Math.min(b[0], x), Math.min(b[1], y), Math.min(b[2], z),
                    Math.max(b[3], x), Math.max(b[4], y), Math.max(b[5], z)};
        }
        return b;
    }

    private static boolean near(int[] a, int[] b, int gap) {
        return a[0] - gap <= b[3] && b[0] - gap <= a[3] && a[1] - gap <= b[4] && b[1] - gap <= a[4] && a[2] - gap <= b[5] && b[2] - gap <= a[5];
    }

    private static int[] union(int[] a, int[] b) {
        return new int[]{Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2]), Math.max(a[3], b[3]), Math.max(a[4], b[4]), Math.max(a[5], b[5])};
    }
}
