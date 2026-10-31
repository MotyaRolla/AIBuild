package dev.aibuild.pipeline;

import com.google.gson.JsonObject;
import dev.aibuild.analysis.AnalysisReport;
import dev.aibuild.analysis.Severity;
import dev.aibuild.analysis.SpatialReport;
import dev.aibuild.analysis.TextRenderer;
import dev.aibuild.model.BlockInfo;
import dev.aibuild.model.VoxelModel;
import dev.aibuild.util.Json;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

final class ReviewContext {
    final boolean full;
    final String text;
    final int fullCharacters;
    final String reason;
    private final List<int[]> regions;

    private ReviewContext(boolean full, String text, int fullCharacters, String reason, List<int[]> regions) {
        this.full = full;
        this.text = text;
        this.fullCharacters = fullCharacters;
        this.reason = reason;
        this.regions = regions;
    }

    static ReviewContext create(VoxelModel current, VoxelModel previous, BlockInfo info,
                                AnalysisReport report, boolean requireFull, List<int[]> pendingRegions) {
        String spatial = SpatialReport.render(current, info);
        String complete = spatial + "\nEXACT COORDINATE LAYERS\n"
                + TextRenderer.render(current, info, new TextRenderer.Options().compact(true));
        if (requireFull || previous == null) return full(complete, "initial/final whole-build assessment");
        List<int[]> boxes = changedRegions(previous, current);
        if (boxes == null) return full(complete, "repair affects too many cells");
        for (int[] region : pendingRegions) boxes.add(region.clone());
        for (var issue : report.issues) {
            if (issue.severity() != Severity.CRITICAL && issue.severity() != Severity.MAJOR) continue;
            if (issue.sampleCount() == 0) return full(complete, "unlocalized analyzer blocker: " + issue.code());
            for (int[] p : issue.samples()) boxes.add(padded(p[0], p[1], p[2]));
        }
        mergeBoxes(boxes);
        if (boxes.isEmpty() || boxes.size() > 8) return full(complete, "no bounded repair region");
        long volume = 0;
        for (int[] box : boxes) volume += volume(box);
        if (volume > 24_000) return full(complete, "repair regions exceed inspection budget");
        StringBuilder focused = new StringBuilder("FOCUSED REPAIR INSPECTION\n"
                + "Whole-model summaries follow; exact internal blocks are supplied ONLY in the listed regions.\n"
                + "Do not infer unseen internal cells. Repair only within these regions; request needs_full_review\n"
                + "if another region or whole-build judgment is needed. Final approval requires a full assessment.\n");
        focused.append(spatial).append("\nGLOBAL EXTERIOR VIEWS (not interior evidence)\n")
                .append(TextRenderer.render(current, info, new TextRenderer.Options().layers(false).compact(true)));
        for (int[] box : boxes) {
            focused.append("\nEXACT REPAIR REGION ").append(Arrays.toString(box)).append('\n')
                    .append(TextRenderer.render(current, info, TextRenderer.Options.layersOnly().compact(true).region(box)));
        }
        if (focused.length() >= complete.length() * 0.8) return full(complete, "full context is similarly compact");
        return new ReviewContext(false, focused.toString(), complete.length(), "changed cells and issue regions", boxes);
    }

    private static ReviewContext full(String text, String reason) {
        return new ReviewContext(true, text, text.length(), reason, List.of());
    }

    boolean permits(VoxelModel before, VoxelModel after) {
        if (full) return true;
        var keys = new HashSet<>(before.entries().keySet());
        keys.addAll(after.entries().keySet());
        for (long key : keys) {
            if (Objects.equals(before.entries().get(key), after.entries().get(key))) continue;
            int x = VoxelModel.kx(key), y = VoxelModel.ky(key), z = VoxelModel.kz(key);
            if (regions.stream().noneMatch(b -> x >= b[0] && y >= b[1] && z >= b[2]
                    && x <= b[3] && y <= b[4] && z <= b[5])) return false;
        }
        return true;
    }

    static List<int[]> issueRegions(JsonObject response) {
        List<int[]> regions = new ArrayList<>();
        for (var element : Json.arr(response, "issues")) {
            if (!element.isJsonObject() || regions.size() >= 16) continue;
            JsonObject region = Json.obj(element.getAsJsonObject(), "region");
            if (region == null) continue;
            try {
                var from = Json.arr(region, "from");
                var to = Json.arr(region, "to");
                if (from.size() != 3 || to.size() != 3) continue;
                int[] box = new int[6];
                boolean valid = true;
                for (int i = 0; i < 3; i++) {
                    double a = from.get(i).getAsDouble(), b = to.get(i).getAsDouble();
                    valid &= Double.isFinite(a) && Double.isFinite(b) && a == Math.rint(a) && b == Math.rint(b)
                            && Math.abs(a) < 100_000 && Math.abs(b) < 100_000;
                    box[i] = (int) Math.min(a, b) - 2;
                    box[i + 3] = (int) Math.max(a, b) + 2;
                }
                if (valid) regions.add(box);
            } catch (RuntimeException invalid) {
                // Unusable regions cannot authorize a local repair.
            }
        }
        return regions;
    }

    private static List<int[]> changedRegions(VoxelModel before, VoxelModel after) {
        var keys = new HashSet<>(before.entries().keySet());
        keys.addAll(after.entries().keySet());
        var tiles = new TreeMap<Long, int[]>();
        int changed = 0;
        for (long key : keys) {
            if (Objects.equals(before.entries().get(key), after.entries().get(key))) continue;
            if (++changed > 1024) return null;
            int x = VoxelModel.kx(key), y = VoxelModel.ky(key), z = VoxelModel.kz(key);
            long tile = VoxelModel.key(Math.floorDiv(x, 8), Math.floorDiv(y, 8), Math.floorDiv(z, 8));
            int[] bounds = padded(x, y, z);
            tiles.merge(tile, bounds, ReviewContext::union);
        }
        return new ArrayList<>(tiles.values());
    }

    private static int[] padded(int x, int y, int z) {
        return new int[]{x - 2, y - 2, z - 2, x + 2, y + 2, z + 2};
    }

    private static int[] union(int[] a, int[] b) {
        int[] result = new int[6];
        for (int i = 0; i < 3; i++) {
            result[i] = Math.min(a[i], b[i]);
            result[i + 3] = Math.max(a[i + 3], b[i + 3]);
        }
        return result;
    }

    private static long volume(int[] b) {
        return ((long) b[3] - b[0] + 1) * ((long) b[4] - b[1] + 1) * ((long) b[5] - b[2] + 1);
    }

    private static void mergeBoxes(List<int[]> boxes) {
        boolean merged;
        do {
            merged = false;
            outer: for (int i = 0; i < boxes.size(); i++) {
                for (int j = i + 1; j < boxes.size(); j++) {
                    int[] a = boxes.get(i), b = boxes.get(j);
                    boolean overlaps = true;
                    for (int k = 0; k < 3; k++) overlaps &= a[k] <= b[k + 3] && b[k] <= a[k + 3];
                    if (overlaps) {
                        boxes.set(i, union(a, b)); boxes.remove(j);
                        merged = true;
                        break outer;
                    }
                }
            }
        } while (merged);
    }
}
