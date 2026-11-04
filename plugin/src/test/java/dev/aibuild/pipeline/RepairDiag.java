package dev.aibuild.pipeline;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aibuild.analysis.AnalysisReport;
import dev.aibuild.analysis.AnalyzerOptions;
import dev.aibuild.analysis.Severity;
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
 * Deterministic before/after on a saved build: analyzes it, then runs the mechanical finisher and analyzes
 * again. No LLM calls — the analyzer and the finisher are pure code, so this is an exact comparison.
 * Usage: java dev.aibuild.pipeline.RepairDiag <build.json.gz>
 */
public final class RepairDiag {
    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0]);
        Build build;
        try (var reader = new InputStreamReader(new GZIPInputStream(Files.newInputStream(file)))) {
            build = Build.fromJson(JsonParser.parseReader(reader).getAsJsonObject());
        }
        var info = new StubBlockInfo();
        VoxelModel model = build.current().model;
        JsonObject plan = build.plan instanceof JsonObject o ? o : new JsonObject();
        var opts = BuildRequirements.analyzerOptions(plan);
        System.out.println("build: " + build.name + "  blocks=" + model.nonAirCount());
        AnalysisReport before = StructureAnalyzer.analyze(model, info, opts);
        report("ANALYZER (before finisher)", before);
        VoxelModel repaired = Finisher.repair(model, info, java.util.List.of(), opts.expectInterior);
        AnalysisReport after = StructureAnalyzer.analyze(repaired, info, opts);
        report("ANALYZER (after finisher) ", after);
        int bMaj = before.count(Severity.MAJOR) + before.count(Severity.CRITICAL);
        int aMaj = after.count(Severity.MAJOR) + after.count(Severity.CRITICAL);
        System.out.println("blocking findings: " + bMaj + " -> " + aMaj
                + "   footprint " + before.metrics.get("footprintX") + "x" + before.metrics.get("footprintZ")
                + " -> " + after.metrics.get("footprintX") + "x" + after.metrics.get("footprintZ"));
        System.out.println("approved would be: before=" + !before.hasBlocking() + " after=" + !after.hasBlocking());
    }

    private static void report(String title, AnalysisReport rep) {
        System.out.println("== " + title + " ==");
        for (var i : rep.issues) {
            if (i.severity() == Severity.MINOR) continue;
            System.out.println("  [" + i.severity() + "] " + i.code() + ": " + clip(i.message(), 110));
        }
    }

    private static String clip(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
