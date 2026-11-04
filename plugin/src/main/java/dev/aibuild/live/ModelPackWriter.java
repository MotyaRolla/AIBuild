package dev.aibuild.live;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.aibuild.analysis.IsoRenderer;
import dev.aibuild.model.BlockInfo;
import dev.aibuild.model.BlockSpec;
import dev.aibuild.model.Dir;
import dev.aibuild.model.VoxelModel;
import dev.aibuild.render.BlockTextures;
import dev.aibuild.script.ScriptResult;
import dev.aibuild.util.Json;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Encodes a {@link VoxelModel} into the {@link ModelPack} wire format.
 *
 * <p>Pure function of its arguments: no world state, no scheduler, no I/O. Runs on the live session's own
 * thread so a slow encode never stalls a build.</p>
 */
public final class ModelPackWriter {

    /** Blocks whose faces the page draws see-through. */
    private static final String[] SEE_THROUGH = {"glass", "pane", "bars", "ice", "water", "leaves", "vine", "cobweb",
            "ladder", "chain", "scaffolding"};

    private ModelPackWriter() {
    }

    /**
     * @param model the model to encode; read only
     * @param info  block knowledge (colours fall back to {@link BlockInfo#mapColor})
     * @param why   why this pack was sent ({@code stream}, {@code stage}, {@code critic}, {@code final})
     * @param seed  the job's script seed
     * @param parts named parts of the model, may be null
     */
    public static byte[] encode(VoxelModel model, BlockInfo info, String why, long seed,
                                List<ScriptResult.Part> parts) {
        return encode(model, info, why, seed, parts, Set.of());
    }

    /**
     * Encodes a projection, flagging the given cell keys with {@link ModelPack.Flags#CHANGED} so the page can
     * highlight what the last program run actually touched.
     *
     * @param changed {@link VoxelModel#key(int, int, int)} values that appeared or moved since the previous
     *                frame; empty disables the highlight
     */
    public static byte[] encode(VoxelModel model, BlockInfo info, String why, long seed,
                                List<ScriptResult.Part> parts, Set<Long> changed) {
        return encode(model, info, why, seed, parts, changed, null);
    }

    /**
     * Encodes a projection, referencing the atlas at {@code atlasUrl} instead of embedding it.
     *
     * <p>Every frame carries the same ~190 KB atlas, so embedding it would make a small build's frame almost
     * entirely atlas and cost one PNG download per update per viewer. With a URL the page fetches it once and
     * the browser caches it; the pack keeps only the pointer. Pass null to embed the bytes (self-contained
     * packs for tests and offline renders).</p>
     */
    public static byte[] encode(VoxelModel model, BlockInfo info, String why, long seed,
                                List<ScriptResult.Part> parts, Set<Long> changed, String atlasUrl) {
        List<ScriptResult.Part> partList = parts == null ? List.of() : parts;
        Set<Long> changedKeys = changed == null ? Set.of() : changed;
        Map<Long, Integer> partOf = assignParts(model, partList);

        // cells, sorted so the page can extend a drawn build without flicker
        record Cell(int x, int y, int z, BlockSpec spec, int part) {
        }
        List<Cell> cells = new ArrayList<>();
        model.forEach((x, y, z, spec) -> cells.add(new Cell(x, y, z, spec, partOf.getOrDefault(VoxelModel.key(x, y, z), -1))));
        cells.sort((a, b) -> a.y() != b.y() ? Integer.compare(a.y(), b.y())
                : a.x() != b.x() ? Integer.compare(a.x(), b.x()) : Integer.compare(a.z(), b.z()));

        Map<String, Integer> palette = new LinkedHashMap<>();
        Map<String, BlockSpec> sample = new HashMap<>();
        ByteArrayOutputStream raw = new ByteArrayOutputStream(cells.size() * ModelPack.CELL_BYTES);
        ByteBuffer cb = ByteBuffer.allocate(ModelPack.CELL_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        BlockTextures textures = BlockTextures.current();
        int nonAir = 0;
        for (Cell c : cells) {
            String key = c.spec().toString();
            Integer idx = palette.get(key);
            if (idx == null) {
                idx = palette.size();
                palette.put(key, idx);
                sample.put(key, c.spec());
            }
            cb.clear();
            cb.putShort(clamp16(c.x()));
            cb.putShort(clamp16(c.y()));
            cb.putShort(clamp16(c.z()));
            int[] shape = shape(c.spec(), info);
            cb.put((byte) shape[0]);
            cb.put((byte) shape[1]);
            cb.putShort((short) (int) idx);
            int flags = flags(c.spec(), info);
            if (c.part() < 0) flags |= ModelPack.Flags.NO_PART;
            if (changedKeys.contains(VoxelModel.key(c.x(), c.y(), c.z()))) flags |= ModelPack.Flags.CHANGED;
            cb.putInt(flags);
            cb.putShort((short) Math.clamp(c.part(), -1, Short.MAX_VALUE));
            raw.write(cb.array(), 0, ModelPack.CELL_BYTES);
            if (!info.isAir(c.spec())) nonAir++;
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("why", why == null ? "stage" : why);
        payload.addProperty("at", System.currentTimeMillis());
        payload.addProperty("seed", seed);
        JsonArray blocks = new JsonArray();
        for (Map.Entry<String, Integer> e : palette.entrySet()) {
            BlockSpec s = sample.get(e.getKey());
            JsonObject b = new JsonObject();
            b.addProperty("n", e.getKey());
            b.addProperty("c", colour(s, info));
            if (textures != null) {
                int[] faces = textures.faces(s.name());
                if (faces != null) {
                    JsonArray t = new JsonArray();
                    for (int f : faces) t.add(f);
                    b.add("t", t);
                }
            }
            blocks.add(b);
        }
        payload.add("blocks", blocks);
        payload.add("materials", materials(model, info, textures));

        int[] b = model.solidBounds();
        JsonArray bounds = new JsonArray();
        if (b != null) for (int v : b) bounds.add(v);
        payload.add("bounds", bounds);
        JsonObject stats = new JsonObject();
        stats.addProperty("cells", nonAir);
        stats.addProperty("parts", partList.size());
        payload.add("stats", stats);
        JsonArray ps = new JsonArray();
        for (ScriptResult.Part p : partList) {
            JsonObject po = new JsonObject();
            po.addProperty("name", p.name());
            po.addProperty("cells", p.cells());
            JsonArray pb = new JsonArray();
            for (int v : p.bounds()) pb.add(v);
            po.add("bounds", pb);
            ps.add(po);
        }
        payload.add("parts", ps);

        // The atlas is ~190 KB of PNG and identical for every frame of every job, so it is referenced, not
        // repeated: a 1.4k-cell build would otherwise be 99% atlas on every single frame. The page fetches it
        // once from its own cacheable URL; the pack carries only where to find it. A pack built without a token
        // (tests, offline renders) leaves atlasUrl null and embeds the bytes instead, so a self-contained pack
        // is still possible.
        byte[] atlas = textures == null ? null : textures.png();
        boolean embedded = true;
        if (atlas != null) {
            JsonObject at = new JsonObject();
            at.addProperty("cols", BlockTextures.cols());
            at.addProperty("size", BlockTextures.tileSize());
            if (atlasUrl != null) {
                at.addProperty("url", atlasUrl);
                embedded = false;
            }
            payload.add("atlas", at);
        }

        byte[] json = Json.GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
        int padded = (json.length + 3) & ~3;
        int atlasLen = embedded && atlas != null ? atlas.length : 0;
        ByteBuffer out = ByteBuffer.allocate(ModelPack.HEADER_BYTES + padded + atlasLen + raw.size())
                .order(ByteOrder.LITTLE_ENDIAN);
        out.put(ModelPack.MAGIC);
        out.putInt(padded);
        out.putInt(atlasLen);
        out.put((byte) ModelPack.VERSION);
        out.put(new byte[3]);
        out.put(json);
        out.put(new byte[padded - json.length]);
        if (atlasLen > 0) out.put(atlas);
        out.put(raw.toByteArray());
        return out.array();
    }

    /** Palette index of every non-air cell's block, with texture tiles when the client atlas is loaded. */
    public static JsonArray materials(VoxelModel model, BlockInfo info, BlockTextures textures) {
        Map<String, BlockSpec> seen = new LinkedHashMap<>();
        model.forEach((x, y, z, spec) -> {
            if (!info.isAir(spec)) seen.putIfAbsent(spec.toString(), spec);
        });
        JsonArray out = new JsonArray();
        for (Map.Entry<String, BlockSpec> e : seen.entrySet()) {
            JsonObject o = new JsonObject();
            o.addProperty("n", e.getKey());
            o.addProperty("c", colour(e.getValue(), info));
            if (textures != null) {
                int[] faces = textures.faces(e.getValue().name());
                if (faces != null) {
                    JsonArray t = new JsonArray();
                    for (int f : faces) t.add(f);
                    o.add("t", t);
                }
            }
            out.add(o);
        }
        return out;
    }

    /** Texture-accurate colour when renders have the client atlas, else the server's map colour. */
    public static int colour(BlockSpec spec, BlockInfo info) {
        try {
            int c = IsoRenderer.colourOf(spec, info);
            if (c != 0) return c & 0xFFFFFF;
        } catch (RuntimeException ignored) {
            // fall through to the map colour
        }
        return info.mapColor(spec) & 0xFFFFFF;
    }

    /** {shape, facing} as defined by {@link ModelPack.Shapes} and {@link ModelPack.Facing}. */
    static int[] shape(BlockSpec spec, BlockInfo info) {
        // Dispatch on the block name, not on state properties: the states differ between blocks (stairs have
        // facing/half, slabs have type), and a server-independent BlockInfo may not expose every property.
        String name = spec.name();
        if (name.endsWith("_stairs")) {
            // upside-down stairs (eaves, arches) set bit 3, so the page puts the step under the full half
            int up = "top".equals(prop(spec, info, "half")) ? ModelPack.Facing.UPSIDE_DOWN : 0;
            return new int[]{ModelPack.Shapes.STAIRS, facing(prop(spec, info, "facing")) | up};
        }
        if (name.endsWith("_slab")) {
            boolean top = "top".equals(prop(spec, info, "type")) || "top".equals(prop(spec, info, "half"));
            return new int[]{top ? ModelPack.Shapes.SLAB_TOP : ModelPack.Shapes.SLAB_BOTTOM, ModelPack.Facing.NONE};
        }
        if (name.endsWith("_pane") || name.equals("iron_bars")) {
            return new int[]{ModelPack.Shapes.PANE, facing(prop(spec, info, "facing"))};
        }
        if (name.contains("fence") && !name.contains("gate") || name.endsWith("_wall") && !name.endsWith("wall_torch")) {
            return new int[]{ModelPack.Shapes.POST, ModelPack.Facing.NONE};
        }
        if (isCross(name)) {
            return new int[]{ModelPack.Shapes.CROSS, ModelPack.Facing.NONE};
        }
        if (isPlate(name)) {
            return new int[]{ModelPack.Shapes.PLATE, ModelPack.Facing.NONE};
        }
        return new int[]{ModelPack.Shapes.CUBE, ModelPack.Facing.NONE};
    }

    private static boolean isCross(String name) {
        for (String part : new String[]{"torch", "flower", "sapling", "fern", "mushroom", "dandelion", "poppy",
                "tulip", "orchid", "allium", "bluet", "cornflower", "azalea", "candle", "short_grass", "tall_grass",
                "lily_of_the_valley", "wither_rose", "sunflower", "lilac", "rose_bush", "peony", "vine", "cobweb"}) {
            if (name.contains(part)) return true;
        }
        return false;
    }

    private static boolean isPlate(String name) {
        return name.contains("carpet") || name.contains("pressure_plate") || name.equals("lily_pad")
                || name.equals("snow") || name.contains("redstone_wire") || name.contains("rail");
    }

    static int facing(String raw) {
        Dir d = Dir.parse(raw);
        if (d == null) return ModelPack.Facing.NONE;
        return switch (d) {
            case NORTH -> ModelPack.Facing.NORTH;
            case EAST -> ModelPack.Facing.EAST;
            case SOUTH -> ModelPack.Facing.SOUTH;
            case WEST -> ModelPack.Facing.WEST;
            case UP -> ModelPack.Facing.UP;
            case DOWN -> ModelPack.Facing.DOWN;
        };
    }

    static int flags(BlockSpec spec, BlockInfo info) {
        int f = 0;
        if (info.isAir(spec)) return ModelPack.Flags.AIR;
        if (!info.isSolid(spec)) f |= ModelPack.Flags.NO_COLLISION;
        if (info.lightEmission(spec) > 0) f |= ModelPack.Flags.LIGHT;
        if (info.isLiquid(spec)) {
            f |= ModelPack.Flags.LIQUID | ModelPack.Flags.TRANSLUCENT;
        } else {
            String n = spec.name().toLowerCase(Locale.ROOT);
            for (String s : SEE_THROUGH) {
                if (n.contains(s)) {
                    f |= ModelPack.Flags.TRANSLUCENT;
                    break;
                }
            }
        }
        return f;
    }

    private static String prop(BlockSpec spec, BlockInfo info, String name) {
        try {
            String v = info.getProp(spec, name);
            return v == null ? "" : v;
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static short clamp16(int v) {
        return (short) Math.max(Short.MIN_VALUE, Math.min(ModelPack.MAX_COORD, v));
    }

    /**
     * For every cell, the index of the smallest named part whose box contains it ({@code -1} when none does).
     * Parts may nest (a roof inside a house), so the smallest wins — that is the part the viewer asks about.
     */
    private static Map<Long, Integer> assignParts(VoxelModel model, List<ScriptResult.Part> parts) {
        if (parts.isEmpty()) return Map.of();
        long[] volume = new long[parts.size()];
        for (int i = 0; i < parts.size(); i++) {
            int[] b = parts.get(i).bounds();
            volume[i] = (long) (b[3] - b[0] + 1) * (b[4] - b[1] + 1) * (b[5] - b[2] + 1);
        }
        Map<Long, Integer> out = new HashMap<>();
        model.forEach((x, y, z, spec) -> {
            int best = -1;
            long bestVolume = Long.MAX_VALUE;
            int bestCells = Integer.MAX_VALUE;
            for (int i = 0; i < parts.size(); i++) {
                int[] b = parts.get(i).bounds();
                if (x < b[0] || x > b[3] || y < b[1] || y > b[4] || z < b[2] || z > b[5]) continue;
                int cells = parts.get(i).cells();
                // innermost box wins; on equal volumes (a nested part that is exactly as big) the part that
                // actually wrote fewer cells is the more specific one, and the later part wins a full tie
                if (volume[i] < bestVolume || (volume[i] == bestVolume && cells <= bestCells)) {
                    best = i;
                    bestVolume = volume[i];
                    bestCells = cells;
                }
            }
            if (best >= 0) out.put(VoxelModel.key(x, y, z), best);
        });
        return out;
    }

    /** Small helper for tests: the magic and version in a produced pack. */
    public static boolean looksValid(byte[] pack) {
        return pack != null && pack.length >= ModelPack.HEADER_BYTES
                && Arrays.equals(Arrays.copyOf(pack, ModelPack.MAGIC.length), ModelPack.MAGIC)
                && pack[16] == ModelPack.VERSION;
    }
}
