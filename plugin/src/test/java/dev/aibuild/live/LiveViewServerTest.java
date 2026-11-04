package dev.aibuild.live;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aibuild.config.BuildMode;
import dev.aibuild.model.BlockSpec;
import dev.aibuild.model.StubBlockInfo;
import dev.aibuild.model.VoxelModel;
import dev.aibuild.pipeline.Job;
import dev.aibuild.render.BlockTextures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for {@link LiveViewServer} over a real loopback socket.
 *
 * <p>The feed is a fake, because the point is the HTTP layer: routing, headers, the event stream and
 * shutdown. Only a real socket shows that {@code /events} flushes and that {@link LiveViewServer#close()}
 * actually releases the port.</p>
 *
 * <p>The model tests go further and decode what the socket actually delivered. {@code model.bin} is the one
 * response the page cannot sanity-check for itself: a header that disagrees with the body by one byte draws
 * garbage instead of an error, so the arithmetic of the v2 header (padded payload, atlas, cell records) is
 * asserted on the received bytes rather than on the encoder's own idea of them.</p>
 */
class LiveViewServerTest {

    private static final Logger QUIET = Logger.getLogger("AIBuild-live-test");
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    /** The ASCII magic every v2 pack starts with, spelled out so a format bump shows up here first. */
    private static final byte[] MAGIC_V2 = "AIBLV02X".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    /** Textures are process-global, so a test that installs an atlas must not leak it into the next one. */
    @BeforeEach
    @AfterEach
    void clearTextures() {
        BlockTextures.install(null);
    }

    @Test
    void unknownTokenIs404() throws Exception {
        try (LiveViewServer server = start(new FakeFeed(TOKEN), null)) {
            HttpResponse<String> res = get(server, "/live/" + "f".repeat(32) + "/state.json");
            assertEquals(404, res.statusCode(), "an unknown but well-shaped token must not exist");
            HttpResponse<String> page = get(server, "/live/" + "f".repeat(32) + "/");
            assertEquals(404, page.statusCode(), "the page of an unknown token is 404 too");
        }
    }

    @Test
    void stateJsonServesBootstrapWithJsonContentType() throws Exception {
        FakeFeed feed = new FakeFeed(TOKEN);
        feed.json = "{\"buildId\":\"b1\",\"seq\":3}";
        try (LiveViewServer server = start(feed, null)) {
            HttpResponse<String> res = get(server, "/live/" + TOKEN + "/state.json");
            assertEquals(200, res.statusCode());
            assertEquals(feed.json, res.body());
            assertTrue(res.headers().firstValue("Content-Type").orElse("").startsWith("application/json"),
                    "the page parses this as JSON");
            assertEquals("no-store", res.headers().firstValue("Cache-Control").orElse(""),
                    "state is a snapshot and must never be cached");
        }
    }

    @Test
    void modelBinServesPackBytesOnlyWhenTheFeedProvidesThem() throws Exception {
        // A minimal, well-shaped v2 pack: 20-byte header, then a 4-byte padded payload. The bytes are
        // arbitrary on purpose; this test is about transport, not about what the encoder produces.
        byte[] pack = {'A', 'I', 'B', 'L', 'V', '0', '2', 'X',
                4, 0, 0, 0,          // padded payload length
                0, 0, 0, 0,          // atlas length: no textures
                2, 0, 0, 0,          // version 2 at offset 16, then three zero bytes
                '{', '}', ' ', ' '};
        assertTrue(ModelPackWriter.looksValid(pack), "the fixture is a v2 pack");
        try (LiveViewServer server = start(new PackFeed(TOKEN, pack), null)) {
            HttpResponse<byte[]> res = getBytes(server, "/live/" + TOKEN + "/model.bin");
            assertEquals(200, res.statusCode());
            assertArrayEquals(pack, res.body(), "the pack must cross the wire byte for byte");
            assertTrue(res.headers().firstValue("Content-Type").orElse("").startsWith("application/octet-stream"));
            assertEquals("no-store", res.headers().firstValue("Cache-Control").orElse(""),
                    "a pack is the current model, not an immutable file");
        }
        try (LiveViewServer server = start(new FakeFeed(TOKEN), null)) {
            assertEquals(404, get(server, "/live/" + TOKEN + "/model.bin").statusCode(),
                    "a feed that is not a PackSource has no model to serve");
        }
    }

    @Test
    void modelBinCarriesAWholeV2Header() throws Exception {
        byte[] encoded = ModelPackWriter.encode(cottage(), new StubBlockInfo(), "stream", 7L, null);
        try (LiveViewServer server = start(new PackFeed(TOKEN, encoded), null)) {
            HttpResponse<byte[]> res = getBytes(server, "/live/" + TOKEN + "/model.bin");
            assertEquals(200, res.statusCode());
            assertTrue(res.headers().firstValue("Content-Type").orElse("").startsWith("application/octet-stream"),
                    "the page fetches this with arrayBuffer(), not as JSON");

            byte[] body = res.body();
            assertEquals(20, ModelPack.HEADER_BYTES, "a v2 header is 20 bytes");
            assertTrue(body.length > ModelPack.HEADER_BYTES, "the pack has a body, not just a header");
            assertEquals("AIBLV02X", new String(body, 0, MAGIC_V2.length, StandardCharsets.US_ASCII),
                    "the magic moved to v2 and the page sniffs it");
            assertEquals(2, body[16] & 0xFF, "the version byte sits at offset 16");
            assertEquals(0, ModelPack.HEADER_BYTES - MAGIC_V2.length - 4 - 4 - 1 - 3,
                    "magic + payload length + atlas length + version + reserved is the whole header");

            Pack pack = Pack.decode(body);
            assertEquals(0, pack.atlasLength(), "this session has no textures installed");
            assertEquals(0, body[12] | body[13] | body[14] | body[15],
                    "offset 12 is the atlas length, not the version");
            assertEquals(0, body[17] | body[18] | body[19], "the three reserved bytes are zero");
            // header + padded payload + atlas + cells*16 == byteLength, checked in decode(); pin the numbers
            // here as well so a failure names the actual terms instead of just the identity.
            int cells = pack.payload().getAsJsonObject("stats").get("cells").getAsInt();
            assertEquals(body.length, ModelPack.HEADER_BYTES + pack.payloadLength() + pack.atlasLength()
                            + cells * ModelPack.CELL_BYTES,
                    "header(" + ModelPack.HEADER_BYTES + ") + payload(" + pack.payloadLength() + ") + atlas("
                            + pack.atlasLength() + ") + " + cells + " cells*" + ModelPack.CELL_BYTES);
            assertEquals(0, pack.payloadLength() % 4, "the payload length is padded to a multiple of 4");
        }
    }

    @Test
    void sessionWithTexturesPointsAtTheAtlasInsteadOfEmbeddingIt(@TempDir File dir) throws Exception {
        BlockTextures.install(textures(dir));
        byte[] encoded = ModelPackWriter.encode(cottage(), new StubBlockInfo(), "stream", 7L, null, Set.of(),
                "/live/" + TOKEN + "/atlas.png");
        try (LiveViewServer server = start(new PackFeed(TOKEN, encoded), null)) {
            byte[] body = getBytes(server, "/live/" + TOKEN + "/model.bin").body();
            Pack pack = Pack.decode(body);
            assertEquals(0, pack.atlasLength(), "the per-frame atlas section is empty: it is fetched once, not repeated");
            assertArrayEquals(new byte[4], Arrays.copyOfRange(body, 12, 16),
                    "the atlas length field at offset 12 is zero");

            JsonObject meta = pack.payload().getAsJsonObject("atlas");
            assertNotNull(meta, "the pack still tells the page how the atlas is tiled");
            assertEquals(BlockTextures.cols(), meta.get("cols").getAsInt());
            assertEquals(BlockTextures.tileSize(), meta.get("size").getAsInt());
            assertEquals("/live/" + TOKEN + "/atlas.png", meta.get("url").getAsString(),
                    "the page fetches the atlas from this URL once and caches it");
            // The whole point of the change: a frame is cells and JSON, not 190 KB of repeated PNG.
            assertTrue(body.length < 64 * 1024, "a small frame stays small: " + body.length + " bytes");
        }
    }

    @Test
    void atlasEndpointServesThePngOnceAndCacheably(@TempDir File dir) throws Exception {
        BlockTextures textures = textures(dir);
        BlockTextures.install(textures);
        BufferedImage expected = ImageIO.read(new ByteArrayInputStream(textures.png()));
        Job job = new Job("atl123", null, Job.Kind.CREATE, "a cottage", BuildMode.defaults().get(BuildMode.STANDARD));
        LiveSession session = new LiveSession(TOKEN, job, "Cottage", "Steve", new StubBlockInfo());
        try {
            session.model(cottage(), "stream");
            awaitPack(session);
            try (LiveViewServer server = start(session, null)) {
                String url = "/live/" + TOKEN + "/atlas.png";
                HttpResponse<byte[]> res = getBytes(server, url);
                assertEquals(200, res.statusCode(), "the endpoint the pack points at must exist");
                assertTrue(res.headers().firstValue("Content-Type").orElse("").startsWith("image/png"),
                        "the page assigns this to an Image() or createImageBitmap()");
                assertArrayEquals(PNG_SIGNATURE, Arrays.copyOf(res.body(), PNG_SIGNATURE.length),
                        "the atlas is a real PNG");
                BufferedImage atlas = ImageIO.read(new ByteArrayInputStream(res.body()));
                assertNotNull(atlas, "the bytes decode as a PNG");
                assertEquals(expected.getWidth(), atlas.getWidth());
                assertEquals(expected.getHeight(), atlas.getHeight());
                assertEquals(BlockTextures.cols() * BlockTextures.tileSize(), atlas.getWidth(),
                        "one row of 16 px tiles, so tile n is at (n % cols, n / cols)");

                String cache = res.headers().firstValue("Cache-Control").orElse("");
                assertTrue(cache.contains("public") && cache.contains("immutable"),
                        "the atlas must be fetched once per tab, not per frame: " + cache);
                String etag = res.headers().firstValue("ETag").orElse("");
                assertFalse(etag.isBlank(), "an ETag lets the browser revalidate without a body");

                HttpResponse<String> revalidated = client().send(
                        HttpRequest.newBuilder(uri(server, url)).header("If-None-Match", etag)
                                .timeout(Duration.ofSeconds(5)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(304, revalidated.statusCode(), "a replayed ETag must not resend the PNG");

                // The pack and the endpoint must agree: both are the same session's atlas.
                Pack pack = Pack.decode(getBytes(server, "/live/" + TOKEN + "/model.bin").body());
                JsonObject meta = pack.payload().getAsJsonObject("atlas");
                assertEquals(url, meta.get("url").getAsString(), "the address the pack advertises is this one");
                assertEquals(meta.get("cols").getAsInt(), BlockTextures.cols());
            }
        } finally {
            session.shutdown();
        }
    }

    @Test
    void sessionWithoutTexturesServesNoAtlas() throws Exception {
        BlockTextures.install(null);
        Job job = new Job("noatl1", null, Job.Kind.CREATE, "a cottage", BuildMode.defaults().get(BuildMode.STANDARD));
        LiveSession session = new LiveSession(TOKEN, job, "Cottage", "Steve", new StubBlockInfo());
        try {
            session.model(cottage(), "stream");
            awaitPack(session);
            try (LiveViewServer server = start(session, null)) {
                assertEquals(404, get(server, "/live/" + TOKEN + "/atlas.png").statusCode(),
                        "a server without textures has no atlas to serve");
                Pack pack = Pack.decode(getBytes(server, "/live/" + TOKEN + "/model.bin").body());
                assertEquals(0, pack.atlasLength(), "and the pack must not pretend one is embedded");
                assertFalse(pack.payload().has("atlas"), "no atlas object when there is nothing to tile");
                assertTrue(pack.cellCount() > 0, "the pack still decodes into cells");
            }
        } finally {
            session.shutdown();
        }
    }

    @Test
    void embeddedAtlasIsStillDecodable(@TempDir File dir) throws Exception {
        // Encoded without a URL, the writer embeds the PNG: a self-contained pack has to stay valid, and the
        // reader (the page, or a future client) must accept both atlasLen == 0 and atlasLen > 0.
        BlockTextures.install(textures(dir));
        byte[] encoded = ModelPackWriter.encode(cottage(), new StubBlockInfo(), "stream", 7L, null);
        try (LiveViewServer server = start(new PackFeed(TOKEN, encoded), null)) {
            byte[] body = getBytes(server, "/live/" + TOKEN + "/model.bin").body();
            Pack pack = Pack.decode(body);
            assertTrue(pack.atlasLength() > PNG_SIGNATURE.length, "no url means the bytes travel with the pack");
            assertArrayEquals(PNG_SIGNATURE, Arrays.copyOf(pack.atlas(), PNG_SIGNATURE.length),
                    "the embedded section is a PNG");
            assertNotNull(ImageIO.read(new ByteArrayInputStream(pack.atlas())), "and it decodes");
            assertFalse(pack.payload().getAsJsonObject("atlas").has("url"),
                    "nothing to fetch when the bytes are embedded");
            assertTrue(pack.cellCount() > 0, "cells still follow the atlas section");
        }
    }

    @Test
    void stateSeqIsAtLeastEveryFrameIdTheStreamSends() throws Exception {
        Job job = new Job("seq123", null, Job.Kind.CREATE, "a cottage", BuildMode.defaults().get(BuildMode.STANDARD));
        LiveSession session = new LiveSession(TOKEN, job, "Cottage", "Steve", new StubBlockInfo());
        try {
            session.stage("program", "Writing the build program", 0.2);
            session.note("script-run-0", "ok=true blocks=11");
            session.model(cottage(), "stream");
            try (LiveViewServer server = start(session, null)) {
                HttpRequest open = HttpRequest.newBuilder(uri(server, "/live/" + TOKEN + "/events"))
                        .timeout(Duration.ofSeconds(10)).header("Accept", "text/event-stream").GET().build();
                HttpResponse<InputStream> res = client().send(open, HttpResponse.BodyHandlers.ofInputStream());
                assertEquals(200, res.statusCode());
                try (InputStream body = res.body()) {
                    List<Frame> frames = readFrames(
                            new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8)), 4, 5_000);
                    assertFalse(frames.isEmpty(), "the journal replay arrives as frames with ids");
                    assertEquals("model", frames.get(frames.size() - 1).event(),
                            "the stream always ends with a model notice: " + frames);
                    long newest = 0;
                    long previous = 0;
                    for (Frame frame : frames) {
                        assertTrue(frame.id() >= previous, "frame ids never go backwards: " + frames);
                        previous = frame.id();
                        newest = Math.max(newest, frame.id());
                    }
                    assertTrue(newest > 0, "the session numbers its frames: " + frames);

                    JsonObject state = JsonParser.parseString(get(server, "/live/" + TOKEN + "/state.json").body())
                            .getAsJsonObject();
                    assertTrue(state.has("seq"), "state.json carries the sequence boundary");
                    assertTrue(state.get("seq").isJsonPrimitive() && state.get("seq").getAsJsonPrimitive().isNumber(),
                            "seq is a number the page can compare with an id: " + state.get("seq"));
                    assertTrue(state.get("seq").getAsLong() >= newest,
                            "a page that hydrates from this state has already seen frame " + newest
                                    + ", so the snapshot must be at least that: " + state.get("seq"));
                }
            }
        } finally {
            session.shutdown();
        }
    }

    @Test
    void changedFlagsMarkExactlyTheCellsTheEncoderWasGiven() throws Exception {
        StubBlockInfo info = new StubBlockInfo();
        VoxelModel model = new VoxelModel();
        model.set(0, 1, 0, BlockSpec.of("stone"));
        model.set(1, 1, 0, BlockSpec.of("oak_planks"));
        model.set(2, 1, 1, BlockSpec.of("glass"));
        long first = VoxelModel.key(0, 1, 0);
        long second = VoxelModel.key(2, 1, 1);

        byte[] one = ModelPackWriter.encode(model, info, "stream", 7L, null, Set.of(first));
        byte[] two = ModelPackWriter.encode(model, info, "stream", 7L, null, Set.of(second));
        byte[] none = ModelPackWriter.encode(model, info, "stream", 7L, null, Set.of());

        PackFeed feed = new PackFeed(TOKEN, one);
        try (LiveViewServer server = start(feed, null)) {
            assertEquals(Set.of(first), Pack.decode(getBytes(server, "/live/" + TOKEN + "/model.bin").body())
                    .changedKeys(), "only the cell key that was passed is flagged");
            feed.pack(two);
            assertEquals(Set.of(second), Pack.decode(getBytes(server, "/live/" + TOKEN + "/model.bin").body())
                    .changedKeys(), "the flag follows the set, it does not accumulate");
            feed.pack(none);
            assertTrue(Pack.decode(getBytes(server, "/live/" + TOKEN + "/model.bin").body()).changedKeys().isEmpty(),
                    "an empty set clears the highlight");
        }
    }

    @Test
    void eventsStreamFramesInOrder() throws Exception {
        FakeFeed feed = new FakeFeed(TOKEN);
        try (LiveViewServer server = start(feed, null)) {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            HttpRequest request = HttpRequest.newBuilder(uri(server, "/live/" + TOKEN + "/events"))
                    .timeout(Duration.ofSeconds(10)).header("Accept", "text/event-stream").GET().build();
            HttpResponse<InputStream> res = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, res.statusCode());
            assertTrue(res.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
            assertTrue(res.headers().firstValue("Cache-Control").orElse("").contains("no-cache"));
            assertTrue(res.headers().firstValue("X-Accel-Buffering").orElse("").equals("no"));
            try (InputStream body = res.body()) {
                BufferedReader lines = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
                // The subscription is registered before the response headers, so a subscriber is available.
                LiveSubscriber subscriber = awaitSubscriber(feed);
                subscriber.send("event: stage\ndata: {\"n\":1}\n\n");
                subscriber.send("event: code\ndata: {\"n\":2}\n\n");
                subscriber.send("event: model\ndata: {\"n\":3}\n\n");
                List<String> events = readEventNames(lines, 3, 5_000);
                assertEquals(List.of("stage", "code", "model"), events, "frames must arrive whole and in order");
            }
            // Closing the body ends the connection. A server only learns that when a write fails, which is
            // why it keeps the subscriber attached until the next frame or heartbeat; feed it frames so the
            // failed write (and therefore the detach) happens now instead of up to a heartbeat later.
            LiveSubscriber gone = feed.subscribers.isEmpty() ? null : feed.subscribers.get(0);
            if (gone != null) gone.send("event: note\ndata: " + "x".repeat(16 * 1024) + "\n\n");
            assertTrue(awaitDetached(feed, 5_000), "an ended connection must be removed from the feed");
        }
    }

    @Test
    void eventsRefusesMoreViewersThanConfigured() throws Exception {
        FakeFeed feed = new FakeFeed(TOKEN);
        LiveHttpConfig config = LiveHttpConfig.defaults().withAddress("127.0.0.1", 0);
        config = new LiveHttpConfig(config.bindHost(), 0, "", 1, false);
        try (LiveViewServer server = new LiveViewServer(config, token -> feed, null, QUIET)) {
            server.start();
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            HttpRequest open = HttpRequest.newBuilder(uri(server, "/live/" + TOKEN + "/events"))
                    .timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<InputStream> first = client.send(open, HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200, first.statusCode());
            try (InputStream body = first.body()) {
                assertEquals(1, awaitSubscriber(feed) == null ? 0 : 1, "the first viewer is attached");
                HttpResponse<String> second = get(server, "/live/" + TOKEN + "/events");
                assertEquals(503, second.statusCode(), "the cap must hold beyond the configured viewer count");
            }
        }
    }

    @Test
    void stopIs405WithoutAHandlerAndCallsItOtherwise() throws Exception {
        FakeFeed feed = new FakeFeed(TOKEN);
        try (LiveViewServer server = start(feed, null)) {
            assertEquals(405, post(server, "/live/" + TOKEN + "/stop").statusCode(),
                    "no stop handler means the endpoint does not exist for this deployment");
        }
        List<String> stopped = new CopyOnWriteArrayList<>();
        try (LiveViewServer server = start(feed, token -> {
            stopped.add(token);
            return true;
        })) {
            assertEquals(200, post(server, "/live/" + TOKEN + "/stop").statusCode());
            assertEquals(List.of(TOKEN), stopped, "the handler receives the token of the session to stop");
        }
    }

    @Test
    void healthzAndThePageBehave() throws Exception {
        try (LiveViewServer server = start(new FakeFeed(TOKEN), null)) {
            HttpResponse<String> health = get(server, "/healthz");
            assertEquals(200, health.statusCode());
            assertEquals("ok", health.body());

            HttpResponse<String> page = get(server, "/live/" + TOKEN + "/");
            assertEquals(200, page.statusCode());
            assertTrue(page.headers().firstValue("Content-Type").orElse("").startsWith("text/html"),
                    "the page is HTML with a charset");
            assertFalse(page.body().isBlank(), "a page is always served, even when index.html is missing");

            assertEquals(200, get(server, "/live/" + TOKEN).statusCode(), "the trailing slash is optional");
            assertEquals(404, get(server, "/").statusCode(), "the root is not a directory listing");
        }
    }

    @Test
    void malformedPathsAre404AndNever500() throws Exception {
        try (LiveViewServer server = start(new FakeFeed(TOKEN), null)) {
            for (String path : List.of("/live/../etc/passwd", "/live/XYZ/state.json", "/live/", "/live",
                    "/live/" + TOKEN + "/../../etc/passwd", "/live/" + TOKEN + "/nope",
                    "/live/" + TOKEN + "x/state.json", "/live/" + "A".repeat(32) + "/state.json")) {
                int status = get(server, path).statusCode();
                assertTrue(status == 404 || status == 400, path + " must be refused, not served, but was " + status);
            }
            HttpResponse<String> after = get(server, "/healthz");
            assertEquals(200, after.statusCode(), "a bad path must not take the server down");
        }
    }

    @Test
    void closeIsIdempotentAndReleasesThePort() throws Exception {
        LiveViewServer server = start(new FakeFeed(TOKEN), null);
        int port = server.port();
        assertEquals(200, get(server, "/healthz").statusCode());
        server.close();
        server.close();
        assertThrows(Exception.class, () -> get(server, "/healthz").statusCode(),
                "a closed server must stop accepting connections");
        try (java.net.ServerSocket probe = new java.net.ServerSocket()) {
            probe.setReuseAddress(true);
            probe.bind(new java.net.InetSocketAddress("127.0.0.1", port));
            assertEquals(port, probe.getLocalPort(), "the port must be free again after close()");
        }
    }

    // ---------------------------------------------------------------- fixtures

    /** A model with a handful of distinct blocks, so every palette and shape branch has something to encode. */
    private static VoxelModel cottage() {
        VoxelModel m = new VoxelModel();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) m.set(x, 1, z, BlockSpec.of("oak_planks"));
        }
        m.set(0, 2, 0, BlockSpec.of("stone"));
        m.set(1, 2, 0, BlockSpec.of("oak_stairs", Map.of("facing", "south", "half", "bottom")));
        return m;
    }

    /**
     * A two-tile client atlas on disk, loaded back through the public {@link BlockTextures#load(File)}: the
     * constructor that builds one from raw tiles is package-private to {@code dev.aibuild.render}.
     */
    private static BlockTextures textures(File dir) throws IOException {
        int cols = BlockTextures.cols(), tile = BlockTextures.tileSize();
        BufferedImage atlas = new BufferedImage(cols * tile, tile, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < tile; x++) {
            for (int y = 0; y < tile; y++) {
                atlas.setRGB(x, y, 0xFF3C8C3C);          // tile 0, grass-ish
                atlas.setRGB(tile + x, y, 0xFF8C5A3C);   // tile 1, planks-ish
            }
        }
        ImageIO.write(atlas, "png", new File(dir, "atlas.png"));
        Files.writeString(new File(dir, "faces.json").toPath(),
                "{\"tiles\":2,\"blocks\":{\"stone\":[0,0,0],\"oak_planks\":[1,1,1]}}", StandardCharsets.UTF_8);
        BlockTextures loaded = BlockTextures.load(dir);
        assertNotNull(loaded, "the fixture atlas must load");
        return loaded;
    }

    /** Waits for the session's worker thread to publish its first encoded frame. */
    private static byte[] awaitPack(LiveSession session) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            byte[] pack = session.latestPack();
            if (pack != null && pack.length > ModelPack.HEADER_BYTES) return pack;
            Thread.sleep(10);
        }
        throw new AssertionError("the session encoded no pack within 5 s");
    }

    /** A manual feed: the test decides when frames are published and records who is attached. */
    private static final class FakeFeed implements LiveFeed {
        private final String token;
        private final List<LiveSubscriber> subscribers = new CopyOnWriteArrayList<>();
        private final AtomicBoolean detached = new AtomicBoolean();
        private volatile String json = "{}";

        FakeFeed(String token) {
            this.token = token;
        }

        @Override
        public String token() {
            return token;
        }

        @Override
        public String buildId() {
            return "build-1";
        }

        @Override
        public String bootstrapJson() {
            return json;
        }

        @Override
        public LiveSubscriber.Handle subscribe(LiveSubscriber subscriber) {
            subscribers.add(subscriber);
            return () -> {
                subscribers.remove(subscriber);
                detached.set(true);
            };
        }
    }

    /** The same feed, plus the encoded pack. The pack can be replaced so a test can follow the model. */
    private static final class PackFeed implements LiveViewServer.PackSource, LiveFeed {
        private final String token;
        private volatile byte[] pack;

        PackFeed(String token, byte[] pack) {
            this.token = token;
            this.pack = pack;
        }

        void pack(byte[] next) {
            this.pack = next;
        }

        @Override
        public String token() {
            return token;
        }

        @Override
        public String buildId() {
            return "pack-1";
        }

        @Override
        public String bootstrapJson() {
            return "{}";
        }

        @Override
        public LiveSubscriber.Handle subscribe(LiveSubscriber subscriber) {
            return () -> {
            };
        }

        @Override
        public byte[] latestPack() {
            return pack;
        }
    }

    /**
     * One decoded {@link ModelPack#MAGIC v2} pack. Decoding asserts the invariants the page relies on, so every
     * test that reads a pack also proves the format holds together.
     */
    private record Pack(int payloadLength, int atlasLength, int version, JsonObject payload, byte[] atlas,
                        byte[] cells) {

        static Pack decode(byte[] bytes) {
            assertTrue(bytes.length >= ModelPack.HEADER_BYTES,
                    "a pack is at least a header long, was " + bytes.length);
            ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            byte[] magic = new byte[ModelPack.MAGIC.length];
            header.get(magic);
            assertArrayEquals(ModelPack.MAGIC, magic, "the pack starts with the v2 magic");
            int payloadLength = header.getInt();
            int atlasLength = header.getInt();
            int version = header.get() & 0xFF;
            byte[] reserved = new byte[3];
            header.get(reserved);
            assertEquals(ModelPack.VERSION, version, "the version byte sits at offset 16");
            assertArrayEquals(new byte[3], reserved, "the reserved bytes are zero");
            assertEquals(0, payloadLength % 4, "the payload is padded to a multiple of 4");

            int atlasAt = ModelPack.HEADER_BYTES + payloadLength;
            int cellsAt = atlasAt + atlasLength;
            assertTrue(cellsAt <= bytes.length, "header + payload + atlas must fit in the pack ("
                    + ModelPack.HEADER_BYTES + " + " + payloadLength + " + " + atlasLength + " > " + bytes.length + ")");
            String json = new String(bytes, ModelPack.HEADER_BYTES, payloadLength, StandardCharsets.UTF_8).trim();
            JsonObject payload = JsonParser.parseString(json).getAsJsonObject();
            byte[] atlas = Arrays.copyOfRange(bytes, atlasAt, cellsAt);
            byte[] cells = Arrays.copyOfRange(bytes, cellsAt, bytes.length);

            int declared = payload.getAsJsonObject("stats").get("cells").getAsInt();
            assertEquals(0, cells.length % ModelPack.CELL_BYTES, "cells are whole 16-byte records");
            assertEquals(declared * ModelPack.CELL_BYTES, cells.length,
                    "header + padded payload + atlas + cells*16 must be the whole pack");
            if (atlasLength == 0) {
                assertEquals(0, atlas.length);
            } else {
                assertArrayEquals(PNG_SIGNATURE, Arrays.copyOf(atlas, PNG_SIGNATURE.length),
                        "a non-empty atlas section is a PNG");
            }

            int blocks = payload.getAsJsonArray("blocks").size();
            int parts = payload.getAsJsonArray("parts").size();
            ByteBuffer record = ByteBuffer.wrap(cells).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < cells.length / ModelPack.CELL_BYTES; i++) {
                int at = i * ModelPack.CELL_BYTES;
                int block = record.getShort(at + 8) & 0xFFFF;
                int part = record.getShort(at + 14);
                assertTrue(block < blocks, "cell " + i + " indexes block " + block + " of " + blocks);
                assertTrue(part >= -1 && part < parts, "cell " + i + " indexes part " + part + " of " + parts);
            }
            return new Pack(payloadLength, atlasLength, version, payload, atlas, cells);
        }

        int cellCount() {
            return cells.length / ModelPack.CELL_BYTES;
        }

        /** The {@link ModelPack.Flags#CHANGED} cells, keyed as {@link VoxelModel#key(int, int, int)} does. */
        Set<Long> changedKeys() {
            Set<Long> out = new HashSet<>();
            ByteBuffer record = ByteBuffer.wrap(cells).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < cellCount(); i++) {
                int at = i * ModelPack.CELL_BYTES;
                if ((record.getInt(at + 10) & ModelPack.Flags.CHANGED) != 0) {
                    out.add(VoxelModel.key(record.getShort(at), record.getShort(at + 2), record.getShort(at + 4)));
                }
            }
            return out;
        }
    }

    /** One SSE frame's type and sequence number, which is all the ordering tests need. */
    private record Frame(String event, long id) {
    }

    private static LiveViewServer start(LiveFeed feed, LiveViewServer.StopHandler stopper) throws Exception {
        LiveViewServer server = new LiveViewServer(LiveHttpConfig.defaults(), token -> token.equals(feed.token()) ? feed : null,
                stopper, QUIET);
        server.start();
        return server;
    }

    private static URI uri(LiveViewServer server, String path) {
        return URI.create("http://127.0.0.1:" + server.port() + path);
    }

    private static HttpClient client() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    private static HttpResponse<String> get(LiveViewServer server, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(server, path)).timeout(Duration.ofSeconds(5)).GET().build();
        return client().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<byte[]> getBytes(LiveViewServer server, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(server, path)).timeout(Duration.ofSeconds(5)).GET().build();
        return client().send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private static HttpResponse<String> post(LiveViewServer server, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(server, path)).timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        return client().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static LiveSubscriber awaitSubscriber(FakeFeed feed) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (!feed.subscribers.isEmpty()) return feed.subscribers.get(0);
            Thread.sleep(10);
        }
        return null;
    }

    private static boolean awaitDetached(FakeFeed feed, long millis) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofMillis(millis).toNanos();
        while (System.nanoTime() < deadline) {
            if (feed.subscribers.isEmpty() && feed.detached.get()) return true;
            Thread.sleep(10);
        }
        return false;
    }

    /** Reads lines until {@code count} event names have arrived or the deadline passes. */
    private static List<String> readEventNames(BufferedReader lines, int count, long millis) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMillis(millis).toNanos();
        List<String> names = new ArrayList<>();
        while (names.size() < count && System.nanoTime() < deadline) {
            String line = lines.readLine();
            if (line == null) break;
            if (line.startsWith("event:")) names.add(line.substring("event:".length()).trim());
        }
        return names;
    }

    /** Reads frames (event name plus numeric {@code id:}) until {@code count} have arrived. */
    private static List<Frame> readFrames(BufferedReader lines, int count, long millis) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMillis(millis).toNanos();
        List<Frame> frames = new ArrayList<>();
        String event = null;
        while (frames.size() < count && System.nanoTime() < deadline) {
            String line = lines.readLine();
            if (line == null) break;
            if (line.startsWith("event:")) {
                event = line.substring("event:".length()).trim();
            } else if (line.startsWith("id:")) {
                String raw = line.substring("id:".length()).trim();
                long id;
                try {
                    id = Long.parseLong(raw);
                } catch (NumberFormatException e) {
                    throw new AssertionError("a frame id must be a number, got '" + raw + "'");
                }
                frames.add(new Frame(event, id));
            }
        }
        return frames;
    }
}
