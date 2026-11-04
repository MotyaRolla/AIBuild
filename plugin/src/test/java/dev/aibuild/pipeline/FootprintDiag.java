package dev.aibuild.pipeline;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aibuild.analysis.AnalysisReport;
import dev.aibuild.analysis.AnalyzerOptions;
import dev.aibuild.analysis.StructureAnalyzer;
import dev.aibuild.build.Build;
import dev.aibuild.model.StubBlockInfo;
import dev.aibuild.model.VoxelModel;
import dev.aibuild.util.Json;

import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

/**
 * Diagnostic: runs the real analyzer on a saved build and prints why the footprint is what it is.
 * Usage: java dev.aibuild.pipeline.FootprintDiag <build.json.gz>
 */
public final class FootprintDiag {
    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0]);
        Build build;
        try (var reader = new InputStreamReader(new GZIPInputStream(Files.newInputStream(file)))) {
            build = Build.fromJson(JsonParser.parseReader(reader).getAsJsonObject());
        }
        var info = new StubBlockInfo();
        VoxelModel model = build.current().model;
        JsonObject plan = build.plan instanceof JsonObject o ? o : null;
        var opts = plan != null ? BuildRequirements.analyzerOptions(plan) : new AnalyzerOptions();
        AnalysisReport report = StructureAnalyzer.analyze(model, info, opts);
        System.out.println("== metrics ==");
        for (var e : report.metrics.entrySet()) System.out.println("  " + e.getKey() + " = " + e.getValue());
        System.out.println("== issues ==");
        for (var i : report.issues) System.out.println("  [" + i.severity() + "] " + i.code() + ": " + i.message());
        System.out.println("== layer trace (stub occlusion) ==");
        int[] b = model.bounds();
        for (int y = Math.max(1, b[1]); y <= Math.min(b[4], 5); y++) {
            int count = 0;
            for (int x = b[0]; x <= b[3]; x++) for (int z = b[2]; z <= b[5]; z++) {
                var s = model.get(x, y, z);
                if (s != null && !s.isAir() && (info.isOccluding(s) || s.name().contains("glass")
                        || s.name().endsWith("_door") || s.name().endsWith("_log") || s.name().endsWith("_wood"))) count++;
            }
            System.out.println("  y=" + y + " wall-like cells=" + count);
        }
    }
}
