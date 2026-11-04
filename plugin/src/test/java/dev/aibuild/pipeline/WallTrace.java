package dev.aibuild.pipeline;

import com.google.gson.JsonParser;
import dev.aibuild.build.Build;
import dev.aibuild.model.BlockSpec;
import dev.aibuild.model.StubBlockInfo;
import dev.aibuild.model.VoxelModel;

import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Replays StructureAnalyzer.wallFootprint() as it selects now: every candidate with maximality, hollow,
 * ring and wall mass, and the winner marked. Usage: WallTrace <build.json.gz>
 */
public final class WallTrace {
    private static final Set<String> NATURAL_GROUND = Set.of(
            "grass_block", "dirt", "coarse_dirt", "rooted_dirt", "podzol", "mycelium", "moss_block",
            "sand", "red_sand", "gravel", "clay", "mud", "stone", "granite", "diorite", "andesite",
            "tuff", "calcite", "deepslate", "snow_block", "ice", "packed_ice", "dirt_path", "farmland", "azalea");

    record C(int x0, int x1, int z0, int z1) {}

    public static void main(String[] args) throws Exception {
        Build build;
        try (var reader = new InputStreamReader(new GZIPInputStream(Files.newInputStream(Path.of(args[0]))))) {
            build = Build.fromJson(JsonParser.parseReader(reader).getAsJsonObject());
        }
        var info = new StubBlockInfo();
        VoxelModel g = build.current().model;
        int[] bb = g.bounds();
        int minX = bb[0], maxX = bb[3], minZ = bb[2], maxZ = bb[5];
        int minY = bb[1], maxY = bb[4];
        int sx = maxX - minX + 1, sz = maxZ - minZ + 1;

        List<boolean[]> layers = new ArrayList<>();
        boolean[] union = new boolean[sx * sz];
        for (int y = Math.max(1, minY); y <= Math.min(maxY, 5); y++) {
            boolean[] wall = new boolean[sx * sz];
            for (int z = minZ; z <= maxZ; z++) for (int x = minX; x <= maxX; x++) {
                BlockSpec s = g.get(x, y, z);
                if (s == null || s.isAir()) continue;
                String nm = s.name();
                if (NATURAL_GROUND.contains(nm)) continue;
                boolean w = info.isOccluding(s) || nm.contains("glass") || nm.endsWith("_door")
                        || nm.endsWith("_log") || nm.endsWith("_wood");
                if (w) {
                    wall[(z - minZ) * sx + (x - minX)] = true;
                    union[(z - minZ) * sx + (x - minX)] = true;
                }
            }
            layers.add(wall);
        }
        Map<String, C> boxes = new LinkedHashMap<>();
        for (boolean[] wall : layers) {
            boolean[] mask = largest(wall, sx, sz);
            if (mask == null || count(mask) < 8) continue;
            int[] box = trimmed(mask, sx, sz);
            if (box == null) continue;
            boxes.putIfAbsent(key(box), new C(box[0] + minX, box[1] + minX, box[2] + minZ, box[3] + minZ));
        }
        for (boolean[] comp : components(union, sx, sz, 8)) {
            int[] box = trimmed(comp, sx, sz);
            if (box == null) continue;
            boxes.putIfAbsent(key(box), new C(box[0] + minX, box[1] + minX, box[2] + minZ, box[3] + minZ));
        }
        System.out.println("candidates (" + boxes.size() + "):");
        C winner = null;
        double bestRing = 0;
        int bestCells = -1;
        for (C c : boxes.values()) {
            boolean maximal = maximal(c, layers, minX, minZ, sx, sz);
            double hollow = hollow(c, g, minY, maxY);
            double ring = ring(c, g, minY, maxY);
            int cells = cells(c, layers, minX, minZ, sx);
            boolean ok = maximal && hollow >= 0.25;
            boolean win = ok && (ring > bestRing + 0.01 || (Math.abs(ring - bestRing) <= 0.01 && cells > bestCells && ring > 0));
            if (win) {
                winner = c;
                bestRing = ring;
                bestCells = cells;
            }
            System.out.printf("  x%d..%d z%d..%d (%dx%d) maximal=%b hollow=%.2f ring=%.2f cells=%d %s%n",
                    c.x0, c.x1, c.z0, c.z1, c.x1 - c.x0 + 1, c.z1 - c.z0 + 1, maximal, hollow, ring, cells,
                    win ? "<-- WINNER" : "");
        }
        System.out.println(winner == null ? "no winner: fallback" : "winner: " + (winner.x1 - winner.x0 + 1) + "x" + (winner.z1 - winner.z0 + 1));
    }

    static String key(int[] b) { return b[0] + "," + b[1] + "," + b[2] + "," + b[3]; }
    static int count(boolean[] m) { int c = 0; for (boolean b : m) if (b) c++; return c; }

    static boolean[] largest(boolean[] mask, int sx, int sz) {
        List<boolean[]> all = components(mask, sx, sz, 1);
        return all.isEmpty() ? null : all.get(0);
    }

    static List<boolean[]> components(boolean[] mask, int sx, int sz, int min) {
        boolean[] seen = new boolean[mask.length];
        List<boolean[]> out = new ArrayList<>();
        for (int start = 0; start < mask.length; start++) {
            if (!mask[start] || seen[start]) continue;
            boolean[] comp = rebuild(mask, start, sx, sz);
            System.arraycopy(comp, 0, seen, 0, seen.length);
            if (count(comp) >= min) out.add(comp);
        }
        out.sort((a, b) -> Integer.compare(count(b), count(a)));
        return out;
    }

    static boolean[] rebuild(boolean[] mask, int start, int sx, int sz) {
        boolean[] seen = new boolean[mask.length];
        Deque<Integer> q = new ArrayDeque<>();
        q.add(start);
        seen[start] = true;
        while (!q.isEmpty()) {
            int c = q.poll();
            int cx = c % sx, cz = c / sx;
            int[][] nb = {{cx + 1, cz}, {cx - 1, cz}, {cx, cz + 1}, {cx, cz - 1}};
            for (int[] n : nb) {
                if (n[0] < 0 || n[1] < 0 || n[0] >= sx || n[1] >= sz) continue;
                int ni = n[1] * sx + n[0];
                if (mask[ni] && !seen[ni]) { seen[ni] = true; q.add(ni); }
            }
        }
        return seen;
    }

    static int[] trimmed(boolean[] mask, int sx, int sz) {
        int x0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z0 = Integer.MAX_VALUE, z1 = Integer.MIN_VALUE;
        for (int i = 0; i < mask.length; i++) {
            if (!mask[i]) continue;
            int x = i % sx, z = i / sx;
            x0 = Math.min(x0, x); x1 = Math.max(x1, x); z0 = Math.min(z0, z); z1 = Math.max(z1, z);
        }
        if (x0 > x1) return null;
        boolean changed = true;
        while (changed && x1 - x0 >= 2 && z1 - z0 >= 2) {
            changed = false;
            if (colOcc(mask, sx, x0, z0, z1) < 0.4) { x0++; changed = true; }
            if (x1 - x0 >= 2 && colOcc(mask, sx, x1, z0, z1) < 0.4) { x1--; changed = true; }
            if (rowOcc(mask, sx, z0, x0, x1) < 0.4) { z0++; changed = true; }
            if (z1 - z0 >= 2 && rowOcc(mask, sx, z1, x0, x1) < 0.4) { z1--; changed = true; }
        }
        return new int[]{x0, x1, z0, z1};
    }

    static double colOcc(boolean[] m, int sx, int x, int z0, int z1) {
        int c = 0;
        for (int z = z0; z <= z1; z++) if (m[z * sx + x]) c++;
        return (double) c / (z1 - z0 + 1);
    }

    static double rowOcc(boolean[] m, int sx, int z, int x0, int x1) {
        int c = 0;
        for (int x = x0; x <= x1; x++) if (m[z * sx + x]) c++;
        return (double) c / (x1 - x0 + 1);
    }

    static boolean maximal(C c, List<boolean[]> layers, int minX, int minZ, int sx, int sz) {
        for (boolean[] wall : layers) {
            for (int side = 0; side < 2; side++) {
                int x = side == 0 ? c.x0 - 1 : c.x1 + 1;
                int wallish = 0, total = 0;
                for (int z = c.z0; z <= c.z1; z++) {
                    total++;
                    if (x >= minX && x < minX + sx && z >= minZ && z < minZ + sz && wall[(z - minZ) * sx + (x - minX)]) wallish++;
                }
                if (total > 0 && (double) wallish / total > 0.60) return false;
            }
            for (int side = 0; side < 2; side++) {
                int z = side == 0 ? c.z0 - 1 : c.z1 + 1;
                int wallish = 0, total = 0;
                for (int x = c.x0; x <= c.x1; x++) {
                    total++;
                    if (x >= minX && x < minX + sx && z >= minZ && z < minZ + sz && wall[(z - minZ) * sx + (x - minX)]) wallish++;
                }
                if (total > 0 && (double) wallish / total > 0.60) return false;
            }
        }
        return true;
    }

    static double hollow(C c, VoxelModel g, int minY, int maxY) {
        int top = 0;
        for (int y = Math.max(1, minY); y <= Math.min(maxY, 5); y++) {
            for (int x = c.x0; x <= c.x1; x++) {
                for (int z = c.z0; z <= c.z1; z++) {
                    BlockSpec s = g.get(x, y, z);
                    if (s != null && !s.isAir()) { top = Math.max(top, y); break; }
                }
            }
        }
        if (top <= 0) return 0;
        int inner = 0, air = 0;
        for (int y = Math.max(1, minY); y <= top; y++)
            for (int x = c.x0 + 1; x <= c.x1 - 1; x++)
                for (int z = c.z0 + 1; z <= c.z1 - 1; z++) {
                    inner++;
                    BlockSpec s = g.get(x, y, z);
                    if (s == null || s.isAir()) air++;
                }
        return inner == 0 ? 0 : (double) air / inner;
    }

    static double ring(C c, VoxelModel g, int minY, int maxY) {
        List<int[]> ring = new ArrayList<>();
        for (int x = c.x0; x <= c.x1; x++) {
            ring.add(new int[]{x, c.z0});
            if (c.z1 != c.z0) ring.add(new int[]{x, c.z1});
        }
        for (int z = c.z0 + 1; z <= c.z1 - 1; z++) {
            ring.add(new int[]{c.x0, z});
            if (c.x1 != c.x0) ring.add(new int[]{c.x1, z});
        }
        int best = 0;
        for (int y = Math.max(1, minY); y <= Math.min(maxY, 5); y++) {
            int pc = 0;
            for (int[] p : ring) {
                BlockSpec s = g.get(p[0], y, p[1]);
                if (s != null && !s.isAir()) pc++;
            }
            if (pc > best) best = pc;
        }
        return ring.isEmpty() ? 0 : (double) best / ring.size();
    }

    static int cells(C c, List<boolean[]> layers, int minX, int minZ, int sx) {
        int cells = 0;
        for (boolean[] wall : layers)
            for (int x = c.x0; x <= c.x1; x++)
                for (int z = c.z0; z <= c.z1; z++)
                    if (wall[(z - minZ) * sx + (x - minX)]) cells++;
        return cells;
    }
}
