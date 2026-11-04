package dev.aibuild.pipeline;

import com.google.gson.JsonObject;
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
import java.util.zip.GZIPInputStream;

/**
 * Replays {@code StructureAnalyzer.wallFootprint()} with the new body scoring, printing every candidate box
 * with its ring layers, ring ratio, interior air share and score, so the choice can be checked by hand.
 * Usage: java dev.aibuild.pipeline.WallTrace <build.json.gz>
 */
public final class WallTrace {
    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0]);
        Build build;
        try (var reader = new InputStreamReader(new GZIPInputStream(Files.newInputStream(file)))) {
            build = Build.fromJson(JsonParser.parseReader(reader).getAsJsonObject());
        }
        var info = new StubBlockInfo();
        VoxelModel g = build.current().model;
        int[] bb = g.bounds();
        int minX = bb[0], maxX = bb[3], minZ = bb[2], maxZ = bb[5];
        int minY = bb[1], maxY = bb[4];
        int sx = maxX - minX + 1, sz = maxZ - minZ + 1;

        boolean[] present = new boolean[sx * sz * (maxY - minY + 1)];
        for (int x = minX; x <= maxX; x++) for (int y = minY; y <= maxY; y++) for (int z = minZ; z <= maxZ; z++) {
            BlockSpec s = g.get(x, y, z);
            if (s != null && !s.isAir()) present[idx(x - minX, y - minY, z - minZ, sx, sz)] = true;
        }
        final int mnX = minX, mxX = maxX, mnY = minY, mxY = maxY, mnZ = minZ, mxZ = maxZ;
        At at = (x, y, z) -> {
            if (x < mnX || x > mxX || y < mnY || y > mxY || z < mnZ || z > mxZ) return false;
            return present[idx(x - mnX, y - mnY, z - mnZ, sx, sz)];
        };

        Map<String, int[]> boxes = new LinkedHashMap<>();
        boolean[] union = new boolean[sx * sz];
        for (int y = Math.max(1, minY); y <= Math.min(maxY, 5); y++) {
            boolean[] wall = new boolean[sx * sz];
            for (int z = minZ; z <= maxZ; z++) for (int x = minX; x <= maxX; x++) {
                BlockSpec s = g.get(x, y, z);
                if (s == null || s.isAir()) continue;
                String nm = s.name();
                wall[(z - minZ) * sx + (x - minX)] = info.isOccluding(s) || nm.contains("glass")
                        || nm.endsWith("_door") || nm.endsWith("_log") || nm.endsWith("_wood");
            }
            for (int i = 0; i < wall.length; i++) if (wall[i]) union[i] = true;
            boolean[] mask = largest(wall, sx, sz);
            if (mask == null || count(mask) < 8) continue;
            int[] box = trimmed(mask, sx, sz);
            if (box == null) continue;
            int[] world = {box[0] + minX, box[1] + minX, box[2] + minZ, box[3] + minZ};
            boxes.putIfAbsent(key(world), world);
        }
        boolean[] unionMask = largest(union, sx, sz);
        int[] unionBox = null;
        if (unionMask != null && count(unionMask) >= 8) {
            int[] box = trimmed(unionMask, sx, sz);
            if (box != null) {
                unionBox = new int[]{box[0] + minX, box[1] + minX, box[2] + minZ, box[3] + minZ};
                boxes.putIfAbsent(key(unionBox), unionBox);
            }
        }
        System.out.println("candidates:");
        for (int[] b : boxes.values()) {
            int layers = ringLayers(b, at);
            double ring = ringRatio(b, at, minY, maxY);
            int inner = 0, air = 0;
            for (int y = Math.max(1, minY); y <= Math.min(maxY, 5); y++)
                for (int x = b[0] + 1; x <= b[1] - 1; x++)
                    for (int z = b[2] + 1; z <= b[3] - 1; z++) {
                        inner++;
                        if (!at.test(x, y, z)) air++;
                    }
            double score = layers == 0 || ring < 0.6 || inner == 0 ? 0 : layers * ring * ((double) air / inner);
            System.out.printf("  box x%d..%d z%d..%d (%dx%d) layers=%d ring=%.2f air=%d/%d score=%.3f%n",
                    b[0], b[1], b[2], b[3], b[1] - b[0] + 1, b[3] - b[2] + 1, layers, ring, air, inner, score);
        }
    }

    static int idx(int x, int y, int z, int sx, int sz) { return (y * sz + z) * sx + x; }
    static String key(int[] w) { return w[0] + "," + w[1] + "," + w[2] + "," + w[3]; }
    static int count(boolean[] m) { int c = 0; for (boolean b : m) if (b) c++; return c; }

    static boolean[] largest(boolean[] mask, int sx, int sz) {
        boolean[] seen = new boolean[mask.length];
        boolean[] best = null;
        int bestSize = 0;
        Deque<Integer> q = new ArrayDeque<>();
        for (int start = 0; start < mask.length; start++) {
            if (!mask[start] || seen[start]) continue;
            q.clear();
            q.add(start);
            seen[start] = true;
            int size = 0;
            while (!q.isEmpty()) {
                int c = q.poll();
                size++;
                int cx = c % sx, cz = c / sx;
                int[][] nb = {{cx + 1, cz}, {cx - 1, cz}, {cx, cz + 1}, {cx, cz - 1}};
                for (int[] n : nb) {
                    if (n[0] < 0 || n[1] < 0 || n[0] >= sx || n[1] >= sz) continue;
                    int ni = n[1] * sx + n[0];
                    if (mask[ni] && !seen[ni]) { seen[ni] = true; q.add(ni); }
                }
            }
            if (size > bestSize) {
                bestSize = size;
                best = new boolean[mask.length];
                for (int i = 0; i < mask.length; i++) best[i] = seen[i] && inComponent(mask, seen, i, bestSize, size);
                // rebuild properly: redo the BFS marking only this component
                best = rebuild(mask, start, sx, sz);
            }
        }
        return best;
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

    static boolean inComponent(boolean[] mask, boolean[] seen, int i, int a, int b) { return seen[i]; }

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

    static double colOcc(boolean[] mask, int sx, int x, int z0, int z1) {
        int c = 0;
        for (int z = z0; z <= z1; z++) if (mask[z * sx + x]) c++;
        return (double) c / (z1 - z0 + 1);
    }

    static double rowOcc(boolean[] mask, int sx, int z, int x0, int x1) {
        int c = 0;
        for (int x = x0; x <= x1; x++) if (mask[z * sx + x]) c++;
        return (double) c / (x1 - x0 + 1);
    }

    static int ringLayers(int[] fp, At at) {
        List<int[]> ring = perimeter(fp);
        if (ring.isEmpty()) return 0;
        int layers = 0;
        for (int y = 1; y <= 5; y++) {
            int presentCount = 0;
            for (int[] p : ring) if (at.test(p[0], y, p[1])) presentCount++;
            if ((double) presentCount / ring.size() >= 0.7) layers++;
        }
        return layers;
    }

    static double ringRatio(int[] fp, At at, int minY, int maxY) {
        List<int[]> ring = perimeter(fp);
        if (ring.isEmpty()) return 0;
        int best = 0;
        for (int y = 1; y <= 5; y++) {
            int presentCount = 0;
            for (int[] p : ring) if (at.test(p[0], y, p[1])) presentCount++;
            if (presentCount > best) best = presentCount;
        }
        return (double) best / ring.size();
    }

    static List<int[]> perimeter(int[] fp) {
        List<int[]> pts = new ArrayList<>();
        for (int x = fp[0]; x <= fp[1]; x++) {
            pts.add(new int[]{x, fp[2]});
            if (fp[3] != fp[2]) pts.add(new int[]{x, fp[3]});
        }
        for (int z = fp[2] + 1; z <= fp[3] - 1; z++) {
            pts.add(new int[]{fp[0], z});
            if (fp[1] != fp[0]) pts.add(new int[]{fp[1], z});
        }
        return pts;
    }

    interface At {
        boolean test(int x, int y, int z);
    }
}
