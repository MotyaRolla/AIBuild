package dev.aibuild.live;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aibuild.config.BuildMode;
import dev.aibuild.model.BlockSpec;
import dev.aibuild.model.StubBlockInfo;
import dev.aibuild.model.VoxelModel;
import dev.aibuild.pipeline.Job;
import dev.aibuild.render.BlockTextures;
import dev.aibuild.render.SyntheticAtlas;
import dev.aibuild.script.ScriptResult;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The live view is only useful if the bytes the page decodes match what the pipeline encoded and if a browser
 * that connects late still sees a sane state. These tests pin both: the projection format (the v2 header, the
 * palette, sorted and flagged cells, the atlas) and the session journal (replay, progress, finish ordering).
 */
class LiveViewTest {

    private static VoxelModel cottage() {
        VoxelModel m = new VoxelModel();
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                m.set(x, 1, z, BlockSpec.of("oak_planks"));
            }
        }
        m.set(0, 2, -3, BlockSpec.of("oak_stairs", Map.of("facing", "south", "half", "bottom")));
        m.set(1, 2, -3, BlockSpec.of("oak_slab", Map.of("type", "bottom")));
        m.set(2, 3, 2, BlockSpec.of("glass"));
        m.set(-1, 3, 2, BlockSpec.of("torch"));
        m.set(3, 4, 0, BlockSpec.of("water"));
        return m;
    }

    // ---------------------------------------------------------------- pack layout helpers (v2)

    /** The JSON length field at offset 8, already padded to a multiple of 4 by the writer. */
    private static int payloadPadded(byte[] pack) {
        return ByteBuffer.wrap(pack).order(ByteOrder.LITTLE_ENDIAN).getInt(8);
    }

    /** The atlas section length: 0 when the pack references a URL instead of carrying the PNG. */
    private static int atlasLen(byte[] pack) {
        return ByteBuffer.wrap(pack).order(ByteOrder.LITTLE_ENDIAN).getInt(12);
    }

    /** First cell record: 20-byte header, padded JSON, then the atlas — which sits *before* the cells. */
    private static int cellsAt(byte[] pack) {
        return ModelPack.HEADER_BYTES + payloadPadded(pack) + atlasLen(pack);
    }

    private static JsonObject payload(byte[] pack) {
        String json = new String(pack, ModelPack.HEADER_BYTES, payloadPadded(pack), StandardCharsets.UTF_8).trim();
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static byte[] atlasBytes(byte[] pack) {
        int at = ModelPack.HEADER_BYTES + payloadPadded(pack);
        return Arrays.copyOfRange(pack, at, at + atlasLen(pack));
    }

    /** Cell records actually present, the way the page counts them: everything after the atlas. */
    private static int cellCount(byte[] pack) {
        return (pack.length - cellsAt(pack)) / ModelPack.CELL_BYTES;
    }

    /** Flags of every cell keyed by its model position, so assertions do not depend on cell order. */
    private static Map<List<Integer>, Integer> flagsByPosition(byte[] pack) {
        ByteBuffer c = ByteBuffer.wrap(pack).order(ByteOrder.LITTLE_ENDIAN);
        int offset = cellsAt(pack);
        Map<List<Integer>, Integer> out = new HashMap<>();
        for (int i = 0; i < cellCount(pack); i++) {
            int at = offset + i * ModelPack.CELL_BYTES;
            out.put(List.of((int) c.getShort(at), (int) c.getShort(at + 2), (int) c.getShort(at + 4)),
                    c.getInt(at + 10));
        }
        return out;
    }

    /** Runs {@code body} with exactly {@code textures} installed and restores the previous atlas afterwards. */
    private static void withTextures(BlockTextures textures, Body body) throws Exception {
        BlockTextures previous = BlockTextures.current();
        BlockTextures.install(textures);
        try {
            body.run();
        } finally {
            BlockTextures.install(previous);
        }
    }

    @FunctionalInterface
    private interface Body {
        void run() throws Exception;
    }

    // ---------------------------------------------------------------- tests

    @Test
    void packCarriesEveryCellInReadingOrder() throws Exception {
        withTextures(null, () -> {
            StubBlockInfo info = new StubBlockInfo();
            List<ScriptResult.Part> parts = List.of(
                    new ScriptResult.Part("floor", 49, new int[]{-3, 1, -3, 3, 1, 3}),
                    new ScriptResult.Part("trim", 2, new int[]{0, 2, -3, 1, 2, -3}));
            byte[] pack = ModelPackWriter.encode(cottage(), info, "stream", 42L, parts);

            assertTrue(ModelPackWriter.looksValid(pack), "magic and version");
            ByteBuffer header = ByteBuffer.wrap(pack).order(ByteOrder.LITTLE_ENDIAN);
            byte[] magic = new byte[8];
            header.get(magic);
            assertArrayEquals(ModelPack.MAGIC, magic);
            int padded = header.getInt();
            assertEquals(0, header.getInt(), "a session pack references the atlas, it does not embed it");
            assertEquals(ModelPack.VERSION, header.get());
            assertEquals(0, padded % 4, "the payload is padded to a multiple of 4");
            assertEquals(20, ModelPack.HEADER_BYTES, "the v2 header is 20 bytes");

            JsonObject payload = payload(pack);
            int cells = payload.getAsJsonObject("stats").get("cells").getAsInt();
            assertEquals(49 + 5, cells, "every non-air cell is in the pack");
            assertEquals(42, payload.get("seed").getAsInt());
            assertEquals("stream", payload.get("why").getAsString());
            int blockCount = payload.getAsJsonArray("blocks").size();
            assertTrue(blockCount >= 5, "palette has one entry per distinct block state: " + blockCount);

            assertEquals(ModelPack.HEADER_BYTES + padded, cellsAt(pack), "no atlas section here to skip");
            assertEquals(cells * ModelPack.CELL_BYTES, pack.length - cellsAt(pack),
                    "cells follow the padded payload and the atlas");

            // cells are sorted by y, then x, then z, so the page can extend a drawn build without flicker
            int offset = cellsAt(pack);
            int lastY = Integer.MIN_VALUE, lastX = Integer.MIN_VALUE, lastZ = Integer.MIN_VALUE;
            int stairs = 0, slab = 0, translucent = 0, noCollision = 0, liquid = 0;
            ByteBuffer c = ByteBuffer.wrap(pack).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < cells; i++) {
                int at = offset + i * ModelPack.CELL_BYTES;
                int x = c.getShort(at), y = c.getShort(at + 2), z = c.getShort(at + 4);
                int shape = c.get(at + 6) & 0xFF;
                int facing = c.get(at + 7) & 0xFF;
                int block = c.getShort(at + 8) & 0xFFFF;
                int flags = c.getInt(at + 10);
                assertTrue(block < blockCount, "block index inside the palette");
                if (y != lastY) {
                    lastX = Integer.MIN_VALUE;
                    lastZ = Integer.MIN_VALUE;
                } else if (x != lastX) {
                    lastZ = Integer.MIN_VALUE;
                }
                assertTrue(y > lastY || (y == lastY && x > lastX) || (y == lastY && x == lastX && z > lastZ),
                        "cells sorted by y, x, z");
                lastY = y;
                lastX = x;
                lastZ = z;
                if (shape == ModelPack.Shapes.STAIRS) {
                    stairs++;
                    assertEquals(ModelPack.Facing.SOUTH, facing, "stair facing survives the projection");
                }
                if (shape == ModelPack.Shapes.SLAB_BOTTOM || shape == ModelPack.Shapes.SLAB_TOP) slab++;
                if ((flags & ModelPack.Flags.TRANSLUCENT) != 0) translucent++;
                if ((flags & ModelPack.Flags.NO_COLLISION) != 0) noCollision++;
                if ((flags & ModelPack.Flags.LIQUID) != 0) liquid++;
            }
            assertEquals(1, stairs);
            assertEquals(1, slab);
            assertTrue(translucent >= 2, "glass and water are see-through");
            assertTrue(noCollision >= 1, "a torch has no collision");
            assertEquals(1, liquid);
        });
    }

    /**
     * The atlas is what makes the page draw real Minecraft textures, so its tiling has to be exactly what the
     * palette's tile indices assume: {@code cols} tiles per row, tile n at (n % cols, n / cols).
     *
     * <p>The writer has two modes and both are pinned here. Every frame carries the same ~190 KB PNG, so a
     * normal encode only references it by URL; a self-contained encode (no URL) embeds the bytes, and the
     * section has to sit between the padded JSON and the cell records, which is exactly what shifts the cell
     * offset. Testing the embedded form also proves the reader path for packs that still carry bytes.</p>
     */
    @Test
    void atlasGridIsEncodedWhenEmbeddedAndReferencedOtherwise() throws Exception {
        withTextures(SyntheticAtlas.of(130), () -> {
            // 130 tiles in rows of 64: 3 rows, so 192x48 pixels. Deliberately not a multiple of 64, so a writer
            // that sized the image from the tile count alone would be caught.
            byte[] embedded = ModelPackWriter.encode(cottage(), new StubBlockInfo(), "stream", 7L, List.of(),
                    Set.of(), null);
            assertTrue(ModelPackWriter.looksValid(embedded), "an embedded pack is a valid pack");
            assertTrue(atlasLen(embedded) > 0, "a pack without an atlas URL carries the PNG section");

            JsonObject payload = payload(embedded);
            JsonObject grid = payload.getAsJsonObject("atlas");
            assertNotNull(grid, "the payload describes the atlas tiling");
            assertEquals(BlockTextures.cols(), grid.get("cols").getAsInt(), "cols matches the tiles per row");
            assertEquals(BlockTextures.tileSize(), grid.get("size").getAsInt());
            assertFalse(grid.has("url"), "an embedded atlas needs no URL");

            int cols = grid.get("cols").getAsInt(), size = grid.get("size").getAsInt();
            assertEquals(ModelPack.HEADER_BYTES + payloadPadded(embedded) + atlasLen(embedded), cellsAt(embedded),
                    "the atlas section shifts the cells by exactly its own length");
            assertEquals(payload.getAsJsonObject("stats").get("cells").getAsInt(),
                    cellCount(embedded), "every cell survives the shifted offset");

            BufferedImage img = ImageIO.read(new ByteArrayInputStream(atlasBytes(embedded)));
            assertNotNull(img, "the atlas section decodes as an image");
            assertEquals(cols * size, img.getWidth(), "exactly cols tiles wide");
            assertEquals(((130 + cols - 1) / cols) * size, img.getHeight(), "one row per cols tiles");
            assertEquals(SyntheticAtlas.tileColour(1), img.getRGB(size, 0),
                    "tile 1 sits one tile to the right of tile 0");

            // the palette's tile indices address that same grid, and only blocks the atlas knows get them
            Set<String> textured = new HashSet<>();
            for (var el : payload.getAsJsonArray("blocks")) {
                JsonObject b = el.getAsJsonObject();
                String name = b.get("n").getAsString();
                if (!b.has("t")) {
                    assertFalse(name.startsWith("oak_planks"), "a block the atlas knows must carry tile indices");
                    continue;
                }
                textured.add(name.startsWith("oak_planks") ? "oak_planks" : name);
                for (var t : b.getAsJsonArray("t")) {
                    int tile = t.getAsInt();
                    assertTrue(tile >= 0 && tile < 130, "tile index inside the atlas: " + tile);
                }
                if (name.startsWith("oak_planks")) {
                    var t = b.getAsJsonArray("t");
                    assertEquals(List.of(0, 0, 0),
                            List.of(t.get(0).getAsInt(), t.get(1).getAsInt(), t.get(2).getAsInt()),
                            "oak_planks is tile 0 on every face");
                }
            }
            assertEquals(Set.of("oak_planks"), textured,
                    "only the blocks this atlas has faces for are textured; the rest fall back to flat colours");

            // the normal mode: a pointer instead of 190 KB repeated on every frame
            byte[] referenced = ModelPackWriter.encode(cottage(), new StubBlockInfo(), "stream", 7L, List.of(),
                    Set.of(), "/live/tok/atlas.png");
            assertEquals(0, atlasLen(referenced), "a referenced atlas is not embedded per frame");
            assertEquals(ModelPack.HEADER_BYTES + payloadPadded(referenced), cellsAt(referenced),
                    "with no atlas section the cells follow the JSON directly");
            assertEquals("/live/tok/atlas.png", payload(referenced).getAsJsonObject("atlas").get("url").getAsString());
            assertEquals(BlockTextures.cols(), payload(referenced).getAsJsonObject("atlas").get("cols").getAsInt());
            assertTrue(referenced.length < embedded.length, "referencing the atlas keeps the frame small");
        });
    }

    /**
     * A cell is flagged {@link ModelPack.Flags#CHANGED} when it is not in the previous frame, which is what the
     * page flashes. Pinning the exact set catches both a missing flag (no highlight) and a flag on every cell
     * (the whole build blinks).
     */
    @Test
    void changedFlagsMarkExactlyTheCellsThatAppeared() throws Exception {
        withTextures(null, () -> {
            StubBlockInfo info = new StubBlockInfo();
            VoxelModel first = new VoxelModel();
            first.set(0, 1, 0, BlockSpec.of("stone"));
            first.set(1, 1, 0, BlockSpec.of("stone"));
            first.set(2, 1, 0, BlockSpec.of("stone"));

            byte[] initial = ModelPackWriter.encode(first, info, "stream", 1L, List.of());
            for (int f : flagsByPosition(initial).values()) {
                assertEquals(0, f & ModelPack.Flags.CHANGED, "the first frame has no previous pack to differ from");
            }

            // the same model encoded again, with two cells reported as appeared since the previous frame
            Set<Long> changed = new HashSet<>();
            changed.add(VoxelModel.key(0, 1, 0));
            changed.add(VoxelModel.key(2, 1, 0));
            byte[] next = ModelPackWriter.encode(first, info, "stream", 1L, List.of(), changed);
            Map<List<Integer>, Integer> flags = flagsByPosition(next);
            assertEquals(3, flags.size(), "the second pack has the same three cells");
            assertTrue((flags.get(List.of(0, 1, 0)) & ModelPack.Flags.CHANGED) != 0, "cell (0,1,0) changed");
            assertTrue((flags.get(List.of(2, 1, 0)) & ModelPack.Flags.CHANGED) != 0, "cell (2,1,0) changed");
            assertEquals(0, flags.get(List.of(1, 1, 0)) & ModelPack.Flags.CHANGED, "cell (1,1,0) did not change");

            // a key the model no longer contains cannot revive a cell: it is simply not in the pack
            VoxelModel shrunk = first.copy();
            shrunk.set(2, 1, 0, null);
            Set<Long> vanished = new HashSet<>();
            vanished.add(VoxelModel.key(0, 1, 0));
            vanished.add(VoxelModel.key(2, 1, 0));
            byte[] afterRemoval = ModelPackWriter.encode(shrunk, info, "stream", 1L, List.of(), vanished);
            Map<List<Integer>, Integer> remaining = flagsByPosition(afterRemoval);
            assertEquals(2, remaining.size(), "the removed cell is gone from the pack");
            assertFalse(remaining.containsKey(List.of(2, 1, 0)), "a removed cell cannot be flagged");
            assertTrue((remaining.get(List.of(0, 1, 0)) & ModelPack.Flags.CHANGED) != 0,
                    "the surviving changed cell keeps its flag");
        });
    }

    /** Parts may nest, so the innermost box that contains a cell is the part the viewer names for it. */
    @Test
    void partsAreAssignedToTheSmallestBoxThatContainsTheCell() throws Exception {
        withTextures(null, () -> {
            StubBlockInfo info = new StubBlockInfo();
            VoxelModel m = new VoxelModel();
            m.set(0, 1, 0, BlockSpec.of("stone"));
            m.set(0, 2, 0, BlockSpec.of("oak_planks"));
            List<ScriptResult.Part> parts = List.of(
                    new ScriptResult.Part("house", 2, new int[]{0, 1, 0, 0, 2, 0}),
                    new ScriptResult.Part("roof", 1, new int[]{0, 2, 0, 0, 2, 0}));
            byte[] pack = ModelPackWriter.encode(m, info, "stage", 1L, parts);
            int offset = cellsAt(pack);
            int roofPart = -1, housePart = -1;
            ByteBuffer c = ByteBuffer.wrap(pack).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < 2; i++) {
                int at = offset + i * ModelPack.CELL_BYTES;
                int y = c.getShort(at + 2);
                int part = c.getShort(at + 14);
                if (y == 2) roofPart = part;
                if (y == 1) housePart = part;
            }
            assertEquals(0, housePart, "the only part that holds the floor cell is 'house'");
            assertEquals(1, roofPart, "the nesting roof part wins over the house for its own cells");
            assertEquals(2, payload(pack).getAsJsonObject("stats").get("parts").getAsInt());
        });
    }

    /** Ordering is the property the page relies on to grow a drawn build without flicker. */
    @Test
    void cellsAreStrictlySortedByYThenXThenZAcrossGaps() throws Exception {
        withTextures(null, () -> {
            VoxelModel m = new VoxelModel();
            // inserted in an adversarial order: descending, with gaps in y and x
            int[][] cells = {{5, 9, 1}, {-2, 0, 3}, {0, 0, -7}, {5, 0, 2}, {-2, 9, 1}, {5, 9, -1}, {0, 9, -7}};
            for (int[] p : cells) m.set(p[0], p[1], p[2], BlockSpec.of("stone"));
            byte[] pack = ModelPackWriter.encode(m, new StubBlockInfo(), "stream", 1L, List.of());

            ByteBuffer c = ByteBuffer.wrap(pack).order(ByteOrder.LITTLE_ENDIAN);
            int offset = cellsAt(pack);
            List<List<Integer>> seen = new ArrayList<>();
            for (int i = 0; i < cellCount(pack); i++) {
                int at = offset + i * ModelPack.CELL_BYTES;
                seen.add(List.of((int) c.getShort(at), (int) c.getShort(at + 2), (int) c.getShort(at + 4)));
            }
            assertEquals(cells.length, seen.size());
            List<List<Integer>> sorted = new ArrayList<>(seen);
            sorted.sort((a, b) -> a.get(1).equals(b.get(1))
                    ? (a.get(0).equals(b.get(0)) ? Integer.compare(a.get(2), b.get(2)) : Integer.compare(a.get(0), b.get(0)))
                    : Integer.compare(a.get(1), b.get(1)));
            assertEquals(sorted, seen, "cell order is exactly y, x, z");
            assertEquals(Set.copyOf(seen).size(), seen.size(), "and it is a strict order: no duplicate cells");
        });
    }

    /**
     * A stage's own progress has to reach the bar even before any blocks exist. The job counter is written for
     * the boss bar and stays at 0 for the whole program phase, so reporting only that would freeze the page at
     * "0%" while the AI works — the bug this pins.
     */
    @Test
    void stageProgressMovesBootstrapEvenWhenTheJobCounterIsZero() throws Exception {
        Job job = new Job("prg789", null, Job.Kind.CREATE, "a cottage", BuildMode.defaults().get(BuildMode.STANDARD));
        LiveSession session = new LiveSession("11112222333344445555666677778888", job, "Cottage", "Steve",
                new StubBlockInfo());
        try {
            assertEquals(0.0, job.progress, "the harness starts at the job's own zero");
            assertEquals(0.0, state(session).get("progress").getAsDouble(), "nothing happened yet");

            session.stage("program", "Writing the build program", 0.25);
            assertEquals(0.0, job.progress, "stage(...) must not need the job counter to move");
            assertEquals(0.25, state(session).get("progress").getAsDouble(),
                    "an explicit stage progress must reach the page even at job.progress == 0");

            session.stage("build", "Placing blocks", 0.6);
            assertEquals(0.6, state(session).get("progress").getAsDouble());

            // the further of the two wins, and the bar never rewinds
            job.progress = 0.9;
            assertEquals(0.9, state(session).get("progress").getAsDouble(), "the job counter may lead");
            job.progress = 0.1;
            assertEquals(0.6, state(session).get("progress").getAsDouble(), "a stale counter cannot rewind the bar");

            session.stage("build", "Placing blocks", Double.NaN);
            assertEquals(0.6, state(session).get("progress").getAsDouble(), "a non-finite progress is ignored");
        } finally {
            session.shutdown();
        }
    }

    /**
     * The page drops journal frames at or below the state's {@code seq}, so the snapshot must never be behind a
     * frame the same subscriber already received — otherwise a viewer that connects mid-stream silently loses
     * the events that fired as it connected.
     */
    @Test
    void bootstrapSeqCoversEveryFrameSentAtSubscribe() throws Exception {
        Job job = new Job("seq123", null, Job.Kind.CREATE, "a cottage", BuildMode.defaults().get(BuildMode.STANDARD));
        LiveSession session = new LiveSession("aaaabbbbccccddddeeeeffff00001111", job, "Cottage", "Steve",
                new StubBlockInfo());
        try {
            session.stage("program", "Writing the build program", 0.1);
            session.note("script-run-0", "ok=true");
            session.model(cottage(), "stream");

            Recording recorder = new Recording();
            LiveSubscriber.Handle handle = session.subscribe(recorder);
            // the replay is synchronous, but the worker may still be encoding: give it a moment, then stop
            // listening so the frames compared below are exactly the ones sent at subscribe time
            await(() -> recorder.of("model").size() > 0 && session.latestPack().length > ModelPack.HEADER_BYTES,
                    5_000);
            handle.close();

            JsonObject snapshot = state(session);
            assertTrue(snapshot.get("seq").getAsJsonPrimitive().isNumber(), "seq is a number");
            long seq = snapshot.get("seq").getAsLong();
            long maxId = 0;
            for (String frame : recorder.rawFrames()) {
                for (String line : frame.split("\n")) {
                    if (line.startsWith("id: ")) maxId = Math.max(maxId, Long.parseLong(line.substring(4).trim()));
                }
            }
            assertTrue(maxId > 0, "frames carry ids: " + recorder.types());
            assertTrue(seq >= maxId, "state seq " + seq + " must cover the last frame id " + maxId);
        } finally {
            session.shutdown();
        }
    }

    @Test
    void lateViewerGetsTheWholeStorySoFar() throws Exception {
        Job job = new Job("abc123", null, Job.Kind.CREATE, "a cottage", BuildMode.defaults().get(BuildMode.STANDARD));
        LiveSession session = new LiveSession("0123456789abcdef0123456789abcdef", job, "Cottage", "Steve",
                new StubBlockInfo());
        try {
            session.stage("program", "Writing the build program", 0.1);
            session.code("program", "part('walls', () => {\n");
            session.code("program", "part('walls', () => {\n  box(-3,1,-3, 3,1,3, 'oak_planks');\n})");
            session.note("script-run-0", "ok=true blocks=54");
            session.model(cottage(), "stream");
            session.parts(List.of(new ScriptResult.Part("walls", 49, new int[]{-3, 1, -3, 3, 1, 3})));

            Recording recorder = new Recording();
            LiveSubscriber.Handle handle = session.subscribe(recorder);
            assertNotNull(handle);
            await(() -> recorder.of("model").size() > 0 && recorder.of("parts").size() > 0
                    && session.latestPack().length > ModelPack.HEADER_BYTES, 5_000);

            assertTrue(recorder.of("stage").size() >= 1, "stage replay: " + recorder.types());
            assertTrue(recorder.of("note").size() >= 1, "transcript replay: " + recorder.types());
            String newestCode = recorder.of("code").get(recorder.of("code").size() - 1)
                    .getAsJsonObject().get("text").getAsString();
            assertTrue(newestCode.endsWith("})"), "only the newest program text is kept, got: " + newestCode);
            assertFalse(recorder.of("code").get(0).getAsJsonObject().get("text").getAsString().isEmpty());

            JsonObject model = recorder.of("model").get(0).getAsJsonObject();
            assertEquals("/live/0123456789abcdef0123456789abcdef/model.bin", model.get("url").getAsString());

            byte[] pack = session.latestPack();
            assertTrue(pack.length > ModelPack.HEADER_BYTES, "the pack is served to the page");
            assertTrue(ModelPackWriter.looksValid(pack), "the served pack is a valid v2 pack");
            assertEquals(49 + 5, payload(pack).getAsJsonObject("stats").get("cells").getAsInt(),
                    "the served pack holds the whole model");

            JsonObject snapshot = state(session);
            assertEquals("abc123", snapshot.get("buildId").getAsString());
            assertEquals("Cottage", snapshot.get("buildName").getAsString());
            assertFalse(snapshot.get("finished").getAsBoolean());
            assertEquals(49 + 5, snapshot.getAsJsonObject("model").get("cells").getAsInt());
            assertEquals("Steve", snapshot.get("owner").getAsString());
            handle.close();
        } finally {
            session.shutdown();
        }
    }

    @Test
    void finishAlwaysArrivesAfterTheLastModel() throws Exception {
        Job job = new Job("def456", null, Job.Kind.EDIT, "add a chimney", BuildMode.defaults().get(BuildMode.STANDARD));
        LiveSession session = new LiveSession("fedcba9876543210fedcba9876543210", job, "Cottage", "Steve",
                new StubBlockInfo());
        try {
            Recording recorder = new Recording();
            session.subscribe(recorder);
            session.model(cottage(), "critic");
            session.finish("rejected", "score 71/100");
            await(() -> recorder.of("finish").size() > 0, 5_000);
            List<String> types = recorder.types();
            int modelAt = types.lastIndexOf("model");
            int finishAt = types.lastIndexOf("finish");
            assertTrue(modelAt >= 0 && finishAt > modelAt, "model before finish: " + types);
            assertEquals("rejected", recorder.of("finish").get(0).getAsJsonObject().get("outcome").getAsString());
            assertTrue(session.finished());
            assertTrue(System.currentTimeMillis() - session.finishedAt() < 5_000);

            JsonObject snapshot = state(session);
            assertTrue(snapshot.get("finished").getAsBoolean());
            assertEquals("rejected", snapshot.get("outcome").getAsString());
        } finally {
            session.shutdown();
        }
    }

    @Test
    void registryIssuesOneLinkPerBuildAndCanRevokeIt() {
        StubBlockInfo info = new StubBlockInfo();
        LiveSession.Registry registry = new LiveSession.Registry(1, java.util.logging.Logger.getAnonymousLogger());
        Job first = new Job("aaa111", null, Job.Kind.CREATE, "a cottage", BuildMode.defaults().get(BuildMode.STANDARD));
        LiveSession a = registry.open(first, "Cottage", "Steve", info);
        assertEquals(a, registry.get(a.token()));
        assertEquals(a, registry.forBuild("aaa111"));
        assertEquals(32, a.token().length());
        assertTrue(a.token().matches("[0-9a-f]{32}"), "token is hex: " + a.token());

        // a new run of the same build replaces the old link: the old one must not show the new job
        Job second = new Job("aaa111", null, Job.Kind.EDIT, "add a chimney", BuildMode.defaults().get(BuildMode.STANDARD));
        LiveSession b = registry.open(second, "Cottage", "Steve", info);
        assertTrue(a.token().equals(b.token()) == false, "a rerun gets a fresh token");
        assertEquals(b, registry.forBuild("aaa111"));
        assertFalse(registry.get("00000000000000000000000000000000") != null);
        assertEquals(null, registry.get("nope"));
        registry.closeAll();
        assertEquals(null, registry.get(a.token()));
        assertEquals(0, registry.size());
    }

    // ---------------------------------------------------------------- helpers

    private static JsonObject state(LiveSession session) {
        return JsonParser.parseString(session.bootstrapJson()).getAsJsonObject();
    }

    /** A subscriber that decodes every frame into its event name and payload. */
    private static final class Recording implements LiveSubscriber {
        private final List<String> raw = new CopyOnWriteArrayList<>();

        @Override
        public void send(String frame) {
            raw.add(frame);
        }

        List<String> rawFrames() {
            return raw;
        }

        List<String> types() {
            List<String> out = new ArrayList<>();
            for (String f : raw) {
                for (String line : f.split("\n")) {
                    if (line.startsWith("event: ")) out.add(line.substring(7).trim());
                }
            }
            return out;
        }

        List<com.google.gson.JsonElement> of(String type) {
            List<com.google.gson.JsonElement> out = new ArrayList<>();
            for (String f : raw) {
                String ev = null, data = null;
                for (String line : f.split("\n")) {
                    if (line.startsWith("event: ")) ev = line.substring(7).trim();
                    else if (line.startsWith("data: ")) data = line.substring(6);
                }
                if (type.equals(ev) && data != null) out.add(JsonParser.parseString(data));
            }
            return out;
        }
    }

    private static void await(java.util.function.BooleanSupplier ready, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (ready.getAsBoolean()) return;
            Thread.sleep(10);
        }
        throw new AssertionError("condition not met within " + timeoutMs + " ms");
    }
}
