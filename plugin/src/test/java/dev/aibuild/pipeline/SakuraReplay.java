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
import dev.aibuild.script.ScriptPatch;
import dev.aibuild.script.ScriptResult;
import dev.aibuild.script.ScriptRuntime;
import dev.aibuild.script.Stdlib;
import dev.aibuild.util.Json;

import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Reconstructs the sakura's post-edit model from the recorded answer, then shows what the new analyzer and
 * finisher do with it. The edit was never committed (it was rejected as a draft), so the defect it produced
 * only exists in the transcript; replaying the hunks against the stored script reproduces it exactly.
 * Usage: java dev.aibuild.pipeline.SakuraReplay <build.json.gz> <answer.txt>
 */
public final class SakuraReplay {
    public static void main(String[] args) throws Exception {
        Build build;
        try (var reader = new InputStreamReader(new GZIPInputStream(Files.newInputStream(Path.of(args[0]))))) {
            build = Build.fromJson(JsonParser.parseReader(reader).getAsJsonObject());
        }
        String answer = Files.readString(Path.of(args[1]));
        var info = new StubBlockInfo();
        var version = build.current();
        Stdlib lib = Stdlib.of(version.stdlibVersion);
        ScriptRuntime runtime = new ScriptRuntime(info, lib);
        int ext = 160;
        int[] limits = {-ext, -32, -ext, ext, Math.min(320, ext * 2), ext};
        // the stored script, then the edit applied to it
        ScriptResult base = runtime.run(version.script, version.seed, null, limits,
                ScriptRuntime.Budget.extra(), null);
        System.out.println("base run ok=" + base.ok() + " blocks=" + base.model().nonAirCount());
        ScriptPatch.Result patched = ScriptPatch.apply(version.script, answer);
        System.out.println("edit hunks apply: ok=" + patched.ok() + " hunks=" + patched.hunks()
                + (patched.error() == null ? "" : " err=" + patched.error()));
        if (!patched.ok()) return;
        ScriptResult edited = runtime.run(patched.source(), version.seed, null, limits,
                ScriptRuntime.Budget.extra(), null);
        System.out.println("edited run ok=" + edited.ok() + " blocks=" + edited.model().nonAirCount());
        if (!edited.ok()) {
            System.out.println(edited.error().describe());
            return;
        }
        JsonObject plan = build.plan instanceof JsonObject o ? o : new JsonObject();
        var opts = BuildRequirements.analyzerOptions(plan);
        VoxelModel model = edited.model();
        AnalysisReport before = StructureAnalyzer.analyze(model, info, opts);
        System.out.println("\\n== POST-EDIT MODEL, ANALYZER (the state the user was left with) ==");
        for (var i : before.issues) {
            if (i.severity() == Severity.MINOR) continue;
            System.out.println("  [" + i.severity() + "] " + i.code() + ": " + clip(i.message(), 100));
        }
        VoxelModel repaired = Finisher.repair(model, info, edited.collisions(), opts.expectInterior);
        AnalysisReport after = StructureAnalyzer.analyze(repaired, info, opts);
        System.out.println("\\n== POST-EDIT MODEL, AFTER THE NEW FINISHER ==");
        for (var i : after.issues) {
            if (i.severity() == Severity.MINOR) continue;
            System.out.println("  [" + i.severity() + "] " + i.code() + ": " + clip(i.message(), 100));
        }
        int bMaj = before.count(Severity.MAJOR) + before.count(Severity.CRITICAL);
        int aMaj = after.count(Severity.MAJOR) + after.count(Severity.CRITICAL);
        System.out.println("\\nblocking findings: " + bMaj + " -> " + aMaj);
        System.out.println("floating: " + before.metrics.get("floatingBlocks") + " -> " + after.metrics.get("floatingBlocks"));
        System.out.println("approved would be: before=" + !before.hasBlocking() + " after=" + !after.hasBlocking());
    }

    private static String clip(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
