package dev.aibuild.pipeline;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aibuild.analysis.AnalyzerOptions;
import dev.aibuild.analysis.StructureAnalyzer;
import dev.aibuild.build.Build;
import dev.aibuild.model.OrientationFixer;
import dev.aibuild.model.ShapeResolver;
import dev.aibuild.model.StubBlockInfo;
import dev.aibuild.model.VoxelModel;
import dev.aibuild.model.ops.OpExecutor;
import dev.aibuild.util.Json;

import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPInputStream;

/** Replays recorded Standard compile/review operations without calling a provider. */
public final class ReviewReplay {
    public static void main(String[] args) throws Exception {
        Path run = Path.of(args[0]);
        var info = new StubBlockInfo();
        List<Path> responses;
        try (var files = Files.list(run.resolve("api"))) {
            responses = files.filter(p -> p.getFileName().toString().endsWith("-content.txt")).sorted().toList();
        }
        JsonObject program = Json.extract(Files.readString(responses.getFirst())).value().getAsJsonObject();
        JsonObject plan = program.getAsJsonObject("plan");
        var options = BuildRequirements.analyzerOptions(plan);
        VoxelModel model = new VoxelModel(), previous = null;
        var executor = new OpExecutor(info);
        var result = executor.apply(model, program);
        if (result.opsFailed > 0) throw new IllegalStateException(result.summary());
        OrientationFixer.fix(model,info); ShapeResolver.resolve(model,info);
        JsonArray contexts = new JsonArray();
        for (int i = 1; i < responses.size(); i++) {
            var report = StructureAnalyzer.analyze(model,info,options);
            // Potential full/focused/full context sizes for the EXISTING recorded patches, not a new AI run.
            var context = ReviewContext.create(model,previous,info,report,i == 1 || i == responses.size()-1,List.of());
            JsonObject row = new JsonObject();
            row.addProperty("round",i); row.addProperty("scope",context.full ? "full" : "focused");
            row.addProperty("characters",context.text.length()); row.addProperty("fullCharacters",context.fullCharacters);
            row.addProperty("reason",context.reason);
            contexts.add(row);
            previous = model.copy();
            JsonObject response = Json.extract(Files.readString(responses.get(i))).value().getAsJsonObject();
            result = executor.apply(model,response);
            if (result.opsFailed > 0) throw new IllegalStateException(result.summary());
            OrientationFixer.fix(model,info); ShapeResolver.resolve(model,info);
            row.addProperty("changedCells",ReviewPatch.changedCells(previous,model));
            row.addProperty("patchWithinContext",context.permits(previous,model));
        }
        Build saved;
        try (var reader = new InputStreamReader(new GZIPInputStream(Files.newInputStream(run.resolve("build.json.gz"))))) {
            saved = Build.fromJson(JsonParser.parseReader(reader).getAsJsonObject());
        }
        VoxelModel expected = new VoxelModel();
        saved.currentModel().forEach((x,y,z,s) -> expected.set(x,y,z,info.parse(s.toString())));
        var finalReport = StructureAnalyzer.analyze(expected,info,options);
        var oldOptions = new AnalyzerOptions(Json.bool(plan,"expect_interior",true));
        JsonObject body = Json.obj(plan,"body");
        oldOptions.expectedFootprint(Json.integer(body,"x",0),Json.integer(body,"z",0));
        JsonObject output = new JsonObject();
        output.addProperty("type","offline response replay; no new model calls or latency measurement");
        output.addProperty("replayDifferenceCells",ReviewPatch.changedCells(expected,model));
        output.addProperty("savedBlocks",expected.nonAirCount());
        output.addProperty("oldAnalyzerBlocking",StructureAnalyzer.analyze(expected,info,oldOptions).hasBlocking());
        output.addProperty("newAnalyzerBlocking",finalReport.hasBlocking());
        output.addProperty("newAnalysis",finalReport.toText());
        output.add("contexts",contexts);
        Files.writeString(Path.of(args[1]),Json.PRETTY.toJson(output)+"\n");
        System.out.println(Json.PRETTY.toJson(output));
    }
}
