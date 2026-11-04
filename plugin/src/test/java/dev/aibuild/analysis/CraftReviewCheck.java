package dev.aibuild.analysis;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aibuild.model.BlockSpec;
import dev.aibuild.model.StubBlockInfo;
import dev.aibuild.model.VoxelModel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Tool, not a test: the red pen on a bench result. Usage: CraftReviewCheck colors.json bench-variant.json [out.png [textureDir]]
 */
public final class CraftReviewCheck {
    public static void main(String[] args) throws Exception {
        JsonObject colours = JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
        Map<String, Integer> c = new HashMap<>();
        colours.entrySet().forEach(e -> c.put(e.getKey(), Integer.parseInt(e.getValue().getAsString(), 16)));
        IsoRenderer.useTextureColours(c);
        VoxelModel m = load(Path.of(args[1]));
        StubBlockInfo info = new StubBlockInfo();
        long t = System.nanoTime();
        CraftReview.Result r = CraftReview.review(m, info, null);
        System.out.println((System.nanoTime() - t) / 1_000_000 + " ms, " + m.nonAirCount() + " blocks");
        System.out.println(r.toText());
        if (args.length > 2) {
            if (args.length > 3) dev.aibuild.render.BlockTextures.install(dev.aibuild.render.BlockTextures.load(new java.io.File(args[3])));
            Files.write(Path.of(args[2]), dev.aibuild.render.RedPen.sheet(m, info, r.marks(), 1536));
        }
    }

    static VoxelModel load(Path p) throws Exception {
        JsonObject o = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
        JsonArray pal = o.getAsJsonArray("palette"), cells = o.getAsJsonArray("cells");
        VoxelModel m = new VoxelModel();
        for (int i = 0; i + 3 < cells.size(); i += 4) {
            m.set(cells.get(i).getAsInt(), cells.get(i + 1).getAsInt(), cells.get(i + 2).getAsInt(),
                    BlockSpec.parseRaw(pal.get(cells.get(i + 3).getAsInt()).getAsString()));
        }
        return m;
    }
}
