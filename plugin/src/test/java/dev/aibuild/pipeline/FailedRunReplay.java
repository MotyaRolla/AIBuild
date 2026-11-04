package dev.aibuild.pipeline;

import dev.aibuild.model.BlockInfo;
import dev.aibuild.model.BlockSpec;
import dev.aibuild.model.Dir;
import dev.aibuild.model.StubBlockInfo;
import dev.aibuild.script.ScriptResult;
import dev.aibuild.script.ScriptRuntime;
import dev.aibuild.script.Stdlib;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Tool, not a test: re-runs logged scripts that failed live, under today's runtime, with the server's real block
 * names (colors.json) for suggestions, rejecting only the names the live server rejected (one per line in missing.txt).
 * Usage: FailedRunReplay colors.json missing.txt script.js.txt...
 */
public final class FailedRunReplay {
    public static void main(String[] args) throws Exception {
        Set<String> names = new java.util.HashSet<>(com.google.gson.JsonParser.parseString(Files.readString(Path.of(args[0])))
                .getAsJsonObject().keySet());
        names.add("air");
        List<String> list = new ArrayList<>(names);
        java.util.Collections.sort(list);
        Set<String> missing = new java.util.HashSet<>(Files.readAllLines(Path.of(args[1])));
        StubBlockInfo s = new StubBlockInfo();
        BlockInfo info = new BlockInfo() {
            public BlockSpec parse(String raw) {
                BlockSpec b = BlockSpec.parseRaw(raw);
                String n = b.name().startsWith("minecraft:") ? b.name().substring(10) : b.name();
                if (missing.contains(n)) throw new IllegalArgumentException("unknown block '" + n + "'");
                return s.parse(raw);
            }
            public BlockSpec with(BlockSpec spec, String k, String v) { return s.with(spec, k, v); }
            public BlockSpec canonical(BlockSpec spec) { return s.canonical(spec); }
            public boolean hasProperty(String b, String p) { return s.hasProperty(b, p); }
            public String getProp(BlockSpec spec, String p) { return s.getProp(spec, p); }
            public boolean isAir(BlockSpec spec) { return s.isAir(spec); }
            public boolean isSolid(BlockSpec spec) { return s.isSolid(spec); }
            public boolean isOccluding(BlockSpec spec) { return s.isOccluding(spec); }
            public boolean isSturdyFace(BlockSpec spec, Dir f) { return s.isSturdyFace(spec, f); }
            public boolean hasGravity(BlockSpec spec) { return s.hasGravity(spec); }
            public int lightEmission(BlockSpec spec) { return s.lightEmission(spec); }
            public boolean isLiquid(BlockSpec spec) { return s.isLiquid(spec); }
            public int mapColor(BlockSpec spec) { return s.mapColor(spec); }
            public BlockSpec rotate(BlockSpec spec, int q) { return s.rotate(spec, q); }
            public BlockSpec mirror(BlockSpec spec, char a) { return s.mirror(spec, a); }
            public List<String> blockNames() { return list; }
        };
        ScriptRuntime rt = new ScriptRuntime(info, Stdlib.V2);
        for (int i = 2; i < args.length; i++) {
            String src = Files.readString(Path.of(args[i]));
            ScriptResult r = rt.run(src, 7, null, null, ScriptRuntime.Budget.standard(), null);
            String fixes = String.join(" | ", r.warnings().stream().filter(w -> w.startsWith("auto-corrected")).toList());
            System.out.println((r.ok() ? "OK   " : "FAIL ") + args[i].replaceAll(".*/logs/", "") + " blocks=" + r.model().nonAirCount()
                    + " ms=" + r.elapsedMs() + " instr=" + r.instructions() / 1_000_000 + "M"
                    + (r.ok() ? "" : "\n     " + r.error().message().lines().findFirst().orElse(""))
                    + (fixes.isEmpty() ? "" : "\n     " + (fixes.length() > 300 ? fixes.substring(0, 300) : fixes)));
        }
    }
}
