package dev.aibuild.pipeline;
import com.google.gson.JsonParser;
import dev.aibuild.build.Build;
import dev.aibuild.model.*;
import dev.aibuild.pipeline.*;
import dev.aibuild.analysis.*;
import dev.aibuild.script.*;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.util.zip.GZIPInputStream;

public class What {
    public static void main(String[] a) throws Exception {
        Build build;
        try (var r = new InputStreamReader(new GZIPInputStream(Files.newInputStream(Path.of(".scratch/server/plugins/AIBuild/builds/99ea47.json.gz"))))) {
            build = Build.fromJson(JsonParser.parseReader(r).getAsJsonObject());
        }
        String answer = Files.readString(Path.of(".scratch/sakura-edit-answer.txt"));
        var info = new StubBlockInfo();
        var version = build.current();
        ScriptRuntime runtime = new ScriptRuntime(info, Stdlib.of(version.stdlibVersion));
        int[] limits = {-160, -32, -160, 160, 320, 160};
        ScriptPatch.Result patched = ScriptPatch.apply(version.script, answer);
        ScriptResult edited = runtime.run(patched.source(), version.seed, null, limits, ScriptRuntime.Budget.extra(), null);
        var opts = dev.aibuild.pipeline.BuildRequirements.analyzerOptions((com.google.gson.JsonObject) build.plan);
        AnalysisReport rep = StructureAnalyzer.analyze(edited.model(), info, opts);
        VoxelModel repaired = dev.aibuild.pipeline.Finisher.repair(edited.model(), info, edited.collisions(), opts.expectInterior);
        for (int[] p : new int[][]{{18,25,15},{19,25,15},{19,25,16},{20,25,16}}) {
            System.out.println(p[0]+","+p[1]+","+p[2]+" before=" + name(edited.model(), p) + " after=" + name(repaired, p)
                + " | below=" + name(repaired, new int[]{p[0], p[1]-1, p[2]}));
        }
    }
    static String name(VoxelModel m, int[] p) {
        BlockSpec s = m.get(p[0], p[1], p[2]);
        return s == null || s.isAir() ? "air" : s.name();
    }
}
