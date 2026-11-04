package dev.aibuild.pipeline;

import dev.aibuild.analysis.AnalysisReport;
import dev.aibuild.analysis.AnalyzerOptions;
import dev.aibuild.analysis.StructureAnalyzer;
import dev.aibuild.model.ShapeResolver;
import dev.aibuild.model.StubBlockInfo;
import dev.aibuild.script.Canvas;
import dev.aibuild.script.ScriptPatch;
import dev.aibuild.script.ScriptResult;
import dev.aibuild.script.ScriptRuntime;
import dev.aibuild.script.Stdlib;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Replays a logged interior designer answer on a logged script, without and with confine('rooms'): how many
 * cells land outside the rooms, and whether the scoped run keeps the furnishing.
 * Usage: java dev.aibuild.pipeline.InteriorReplay <script.js.txt> <interior-transcript.md> <seed> [part] [colors.json]
 * (colors.json from plugins/AIBuild/textures/&lt;ver&gt;/ gives the stub every real block name).
 */
public final class InteriorReplay {
    /** The stub's name-based rules with the real block list, so scripts using any block family run. */
    static dev.aibuild.model.BlockInfo withNames(Path colors) throws Exception {
        List<String> names = new ArrayList<>(com.google.gson.JsonParser.parseString(Files.readString(colors)).getAsJsonObject().keySet());
        names.add("air");
        java.util.Collections.sort(names);
        StubBlockInfo s = new StubBlockInfo();
        return new dev.aibuild.model.BlockInfo() {
            public dev.aibuild.model.BlockSpec parse(String raw) { return s.parse(raw); }
            public dev.aibuild.model.BlockSpec with(dev.aibuild.model.BlockSpec spec, String k, String v) { return s.with(spec, k, v); }
            public dev.aibuild.model.BlockSpec canonical(dev.aibuild.model.BlockSpec spec) { return s.canonical(spec); }
            public boolean hasProperty(String b, String p) { return s.hasProperty(b, p); }
            public String getProp(dev.aibuild.model.BlockSpec spec, String p) { return s.getProp(spec, p); }
            public boolean isAir(dev.aibuild.model.BlockSpec spec) { return s.isAir(spec); }
            public boolean isSolid(dev.aibuild.model.BlockSpec spec) { return s.isSolid(spec); }
            public boolean isOccluding(dev.aibuild.model.BlockSpec spec) { return s.isOccluding(spec); }
            public boolean isSturdyFace(dev.aibuild.model.BlockSpec spec, dev.aibuild.model.Dir f) { return s.isSturdyFace(spec, f); }
            public boolean hasGravity(dev.aibuild.model.BlockSpec spec) { return s.hasGravity(spec); }
            public int lightEmission(dev.aibuild.model.BlockSpec spec) { return s.lightEmission(spec); }
            public boolean isLiquid(dev.aibuild.model.BlockSpec spec) { return s.isLiquid(spec); }
            public int mapColor(dev.aibuild.model.BlockSpec spec) { return s.mapColor(spec); }
            public dev.aibuild.model.BlockSpec rotate(dev.aibuild.model.BlockSpec spec, int q) { return s.rotate(spec, q); }
            public dev.aibuild.model.BlockSpec mirror(dev.aibuild.model.BlockSpec spec, char a) { return s.mirror(spec, a); }
            public List<String> blockNames() { return names; }
        };
    }

    public static void main(String[] args) throws Exception {
        String script = Files.readString(Path.of(args[0]));
        String md = Files.readString(Path.of(args[1]));
        long seed = Long.parseLong(args[2]);
        String part = args.length > 3 ? args[3] : "interiors";
        String answer = md.substring(md.lastIndexOf("## response") + "## response".length());
        dev.aibuild.model.BlockInfo info = args.length > 4 ? withNames(Path.of(args[4])) : new StubBlockInfo();
        ScriptRuntime rt = new ScriptRuntime(info, Stdlib.V2);
        ScriptRuntime.Budget budget = ScriptRuntime.Budget.extra();
        ScriptResult base = rt.run(script, seed, null, null, budget, null);
        if (!base.ok()) throw new IllegalStateException(base.error().describe());
        ShapeResolver.resolve(base.model(), info);
        AnalysisReport rep = StructureAnalyzer.analyze(base.model(), info, new AnalyzerOptions(true));
        List<AnalysisReport.Room> rooms = rep.roomList.stream().limit(10).toList();
        ScriptPatch.Result patch = ScriptPatch.putPart(script, part, answer);
        if (!patch.ok()) throw new IllegalStateException(patch.error());
        for (boolean scoped : new boolean[]{false, true}) {
            String src = scoped ? ScriptPatch.confine(patch.source(), part, Canvas.ROOMS) : patch.source();
            ScriptResult r = rt.run(src, seed, null, null, budget, null);
            if (!r.ok()) {
                System.out.println((scoped ? "scoped" : "free") + ": fails: " + r.error().describe());
                continue;
            }
            ShapeResolver.resolve(r.model(), info);
            List<String> samples = new ArrayList<>();
            int[] shell = new int[1];
            int outside = BuildPipeline.changesOutsideRooms(base.model(), r.model(), rooms, info, samples, shell);
            Canvas.Confinement cf = r.confinement(part);
            AnalysisReport nrep = StructureAnalyzer.analyze(r.model(), info, new AnalyzerOptions(true));
            int before = rep.roomList.stream().mapToInt(AnalysisReport.Room::furniture).sum();
            int after = nrep.roomList.stream().mapToInt(AnalysisReport.Room::furniture).sum();
            System.out.println((scoped ? "scoped" : "free  ") + ": outside=" + outside + " (shell " + shell[0] + ") " + samples
                    + (cf == null ? "" : " | skipped " + cf.dropped + " of " + cf.writes + ", too many=" + BuildPipeline.tooManySkipped(cf))
                    + " | furniture " + before + " -> " + after + " | rooms " + rooms.size());
        }
    }
}
