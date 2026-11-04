package dev.aibuild.live;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The embedded HTTP server behind a one-time live URL: the page, its session state, the encoded model and the
 * Server-Sent-Events stream that drives it.
 *
 * <h2>Why the JDK's {@code HttpServer}, and why SSE</h2>
 * <p>{@code jdk.httpserver} ships with the JVM, needs no dependency and handles chunked responses, which is
 * all this feature uses; a framework would add megabytes and a lifecycle the plugin does not need. The traffic
 * is one-way (the job publishes, the browser draws), so Server-Sent Events beat a WebSocket: plain HTTP
 * survives proxies and tunnels without an upgrade handshake, reconnects on its own, and needs no framing
 * library on either side. A socket would only pay off if the page also sent commands, which it does not (the
 * optional stop button is one POST).</p>
 *
 * <h2>Security model</h2>
 * <ul>
 *   <li>The listener binds to loopback by default ({@link LiveHttpConfig#bindHost()}). Exposing the view is
 *       the operator's decision, made by pointing a tunnel or reverse proxy at that loopback port; the plugin
 *       never opens a public interface on its own. The public URL and the origin check are configured
 *       separately because a proxy terminates TLS and may rewrite the host.</li>
 *   <li>The token is the only credential. It is 128 random bits (32 lowercase hex) and that is the point of a
 *       one-time URL: it is handed to the requester of one build, expires with the session, and grants read
 *       access to that build — nothing else. Tokens go through a registry callback and never into a path, so
 *       no request can traverse the filesystem, and the match is re-checked in constant time so timing does
 *       not reveal how much of a guessed token was right.</li>
 *   <li>Tokens are logged only as a 6-character prefix and only at {@code FINE}: INFO lines end up in server
 *       logs and bug reports, and a token there is a working link.</li>
 * </ul>
 *
 * <h2>Why the per-viewer queue is bounded</h2>
 * <p>{@link LiveSubscriber#send} is called by the session's fan-out thread, which also feeds the build. A
 * viewer whose connection stalls on a phone leaving Wi-Fi would otherwise grow the queue forever or block the
 * fan-out and slow the build. Each stream holds at most {@value #MAX_QUEUE_EVENTS} frames /
 * {@value #MAX_QUEUE_BYTES_MB} MB; a viewer that falls past that is disconnected rather than buffered, and its
 * browser reconnects and reloads {@code state.json}. Memory and the build are never hostage to a browser.</p>
 *
 * <h2>Endpoints</h2>
 * <pre>
 * GET  /live/&lt;token&gt;/            page (index.html from the jar, or a placeholder if it is missing)
 * GET  /live/&lt;token&gt;/state.json  {@link LiveFeed#bootstrapJson()}
 * GET  /live/&lt;token&gt;/model.bin   {@link PackSource#latestPack()}, 404 unless the feed is a PackSource
 * GET  /live/&lt;token&gt;/events      Server-Sent Events, 15 s heartbeat, 8 concurrent viewers by default
 * POST /live/&lt;token&gt;/stop        calls {@link StopHandler}, 405 when no handler is registered
 * GET  /healthz                 "ok"
 * </pre>
 * <p>Everything else is 404, including unknown tokens: nothing reveals whether a token merely looks valid.</p>
 */
public final class LiveViewServer implements AutoCloseable {

    /** The token registry: the session layer decides which tokens exist and when they expire. */
    public interface Registry {
        /** The feed for {@code token}, or {@code null} when the token is unknown or expired. */
        LiveFeed get(String token);
    }

    /** Optional viewer-initiated cancellation of the running job. */
    public interface StopHandler {
        /** Cancels the job behind {@code token}; returns false when nothing is running any more. */
        boolean stopByViewer(String token);
    }

    /**
     * Narrow extension of {@link LiveFeed} for the encoded model bytes. The feed interface stays free of the
     * wire format; a session that can serve {@code model.bin} implements this too and the server finds it with
     * {@code instanceof}.
     */
    /** A feed that can serve the block atlas image. */
    public interface AtlasSource {
        /** PNG bytes of the atlas, or {@code null} when this session has no textures. */
        byte[] atlasPng();
    }

    public interface PackSource {
        /** The most recently encoded {@link ModelPack}, or {@code null} when none has been produced yet. */
        byte[] latestPack();
    }

    /**
     * The debug trace behind a token. Kept separate from {@link LiveFeed} so the HTTP layer can serve the trace
     * of a job that has already ended: the session is still in the registry during its grace window, and that is
     * exactly when a person wants to read what went wrong.
     */
    public interface TraceSource {
        /** The whole trace, or {@code null} when nothing recorded one. */
        TraceLog trace();
    }

    private static final String LIVE_PREFIX = "/live/";
    private static final String PAGE_RESOURCE = "/live/index.html";
    private static final String TOKEN_ALPHABET = "0123456789abcdef";
    private static final int TOKEN_LENGTH = 32;
    private static final int MAX_QUEUE_EVENTS = 512;
    private static final int MAX_QUEUE_BYTES_MB = 8;
    private static final long MAX_QUEUE_BYTES = (long) MAX_QUEUE_BYTES_MB << 20;
    private static final long HEARTBEAT_MILLIS = 15_000;
    private static final int MAX_IN_FLIGHT_PER_TOKEN = 4;
    private static final String TEXT = "text/plain; charset=utf-8";
    private static final String JSON = "application/json; charset=utf-8";
    private static final String HTML = "text/html; charset=utf-8";
    private static final String OCTETS = "application/octet-stream";
    private static final String PNG = "image/png";
    private static final String[] CACHE_OFF = {"Cache-Control", "no-store", "Referrer-Policy", "no-referrer"};
    private static final byte[] NOT_FOUND = utf8("not found\n");
    private static final byte[] HEARTBEAT = ascii(": keep-alive\n\n");
    /** Sent when the stream opens; {@code retry} tells the browser how fast to reconnect after a drop. */
    private static final byte[] OPEN = ascii("retry: 2000\n\n");
    /** Sentinel pushed into a stream's queue to wake its writer when the stream is torn down. */
    private static final byte[] POISON = new byte[0];

    private final LiveHttpConfig config;
    private final Registry registry;
    private final StopHandler stopper;
    private final Logger log;
    private final HttpServer server;
    private final ExecutorService pool;
    private final Semaphore viewerSlots;
    private final ConcurrentHashMap<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();
    private final Set<Stream> streams = ConcurrentHashMap.newKeySet();
    private final byte[] page;
    private final boolean pageFromJar;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final ReentrantLock lifecycle = new ReentrantLock();

    /**
     * Binds the listener and loads the page. The constructor allocates the socket, so a port conflict is
     * reported here rather than at {@link #start()}; call {@link #start()} to begin accepting requests.
     *
     * @param config   listener and exposure settings; see {@link LiveHttpConfig#defaults()} for the safe set
     * @param registry resolves a token to its session; {@code null} behaves as "no sessions"
     * @param stopper  optional handler for the stop button; {@code null} makes {@code /stop} a 405
     * @param log      where the server reports; {@code null} falls back to a named JUL logger
     * @throws IOException when the bind address is unusable or the port is taken
     */
    public LiveViewServer(LiveHttpConfig config, Registry registry, StopHandler stopper, Logger log) throws IOException {
        this.config = config == null ? LiveHttpConfig.defaults() : config;
        this.registry = registry == null ? token -> null : registry;
        this.stopper = stopper;
        this.log = log == null ? Logger.getLogger("AIBuild-live") : log;
        this.viewerSlots = new Semaphore(this.config.maxSubscribers());
        byte[] loaded = readPage();
        this.pageFromJar = loaded != null;
        this.page = loaded != null ? loaded : fallbackPage();
        this.server = HttpServer.create(new InetSocketAddress(this.config.bindHost(), this.config.port()), 0);
        // Every open /events stream parks a thread for its whole lifetime, so the pool must hold all of them
        // plus free slots for the page, state and model requests that race a reconnect.
        this.pool = Executors.newFixedThreadPool(Math.max(4, this.config.maxSubscribers() + 2), daemonThreads());
        this.server.setExecutor(this.pool);
        this.server.createContext("/", this::handle);
    }

    /** Starts accepting connections. Calling it twice is harmless. */
    public void start() {
        if (started.compareAndSet(false, true)) {
            server.start();
            log.info("Live view listening on " + config.bindHost() + ":" + port() + " (public " + publicBaseUrl()
                    + "), page " + (pageFromJar ? "from " + PAGE_RESOURCE : "PLACEHOLDER: " + PAGE_RESOURCE + " is missing"));
        }
    }

    /** The port actually bound, which differs from {@link LiveHttpConfig#port()} when that was 0. */
    public int port() {
        return server.getAddress().getPort();
    }

    /**
     * The base URL to put in front of a link. A configured public URL wins, because behind a tunnel the
     * loopback address is useless to the viewer; without one, the bound address is the honest answer.
     */
    public String publicBaseUrl() {
        String configured = config.publicBaseUrl();
        if (!configured.isEmpty()) return configured;
        String host = config.bindHost();
        if (host.equals("0.0.0.0") || host.equals("::") || host.isEmpty()) host = LiveHttpConfig.LOOPBACK;
        if (host.indexOf(':') >= 0 && !host.startsWith("[")) host = "[" + host + "]";
        return "http://" + host + ":" + port();
    }

    /** The one-time URL for {@code token}: {@code publicBaseUrl() + "/live/" + token + "/"}. */
    public String urlFor(String token) {
        return publicBaseUrl() + "/live/" + token + "/";
    }

    /**
     * The URL to show a person, or {@code null} when there is nothing they could open: no public URL is
     * configured and the listener is loopback-only, so the link would fail on any other machine. Callers
     * reject it instead of pasting a dead link into chat.
     */
    public String urlForUser(String token) {
        return config.publicBaseUrl().isEmpty() && isLoopbackBind() ? null : urlFor(token);
    }

    /** True when the listener is reachable only from this machine. */
    public boolean isLoopbackBind() {
        String host = config.bindHost();
        return host.equals(LiveHttpConfig.LOOPBACK) || host.equals("localhost") || host.startsWith("127.")
                || host.equals("::1");
    }

    /**
     * Stops the listener, disconnects viewers and joins the worker pool. Idempotent: later calls return after
     * the first has finished. Safe to call without a prior {@link #start()}.
     */
    @Override
    public void close() {
        lifecycle.lock();
        try {
            if (stopping.get()) return;
            stopping.set(true);
            // Close viewer streams before the pool: their writers sit in poll() and in socket writes, and
            // closing the exchange is what unblocks them.
            for (Stream stream : streams) stream.shutdown();
            server.stop(1);
            pool.shutdown();
            try {
                if (!pool.awaitTermination(2, TimeUnit.SECONDS)) pool.shutdownNow();
            } catch (InterruptedException e) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.fine("Live view server on port " + port() + " stopped");
        } finally {
            lifecycle.unlock();
        }
    }

    // ---------------------------------------------------------------- routing

    private void handle(HttpExchange exchange) {
        try {
            String path = exchange.getRequestURI() == null ? null : exchange.getRequestURI().getRawPath();
            if (path == null) {
                send(exchange, 404, TEXT, NOT_FOUND);
                return;
            }
            if (path.equals("/healthz")) {
                send(exchange, 200, TEXT, utf8("ok"));
                return;
            }
            Route route = Route.parse(path);
            LiveFeed feed = route == null ? null : resolve(route.token);
            if (feed == null) {
                send(exchange, 404, TEXT, NOT_FOUND);
                return;
            }
            if (crossOrigin(exchange)) {
                log.fine(() -> "live " + prefix(route.token) + " refused: cross-origin request");
                send(exchange, 403, TEXT, utf8("forbidden\n"));
                return;
            }
            switch (route.endpoint) {
                case "" -> {
                    if (require(exchange, "GET")) send(exchange, 200, HTML, page, "Referrer-Policy", "no-referrer");
                }
                case "state.json" -> serveState(exchange, route.token, feed);
                case "model.bin" -> serveModel(exchange, route.token, feed);
                case "atlas.png" -> serveAtlas(exchange, route.token, feed);
                case "events" -> serveEvents(exchange, route.token, feed);
                case "stop" -> serveStop(exchange, route.token);
                case "step" -> serveStep(exchange, route.token, feed, route.arg);
                case "trace.md" -> serveTrace(exchange, route.token, feed);
                default -> send(exchange, 404, TEXT, NOT_FOUND);
            }
        } catch (IOException e) {
            // The client went away mid-response; there is nothing to report and nothing to clean up.
            log.fine(() -> "live request aborted: " + e.getClass().getName());
        } catch (RuntimeException e) {
            log.log(Level.FINE, "live request failed", e);
            try {
                send(exchange, 500, TEXT, utf8("internal error\n"));
            } catch (IOException | RuntimeException ignored) {
                // the connection is already broken
            }
        } finally {
            try {
                exchange.close();
            } catch (RuntimeException ignored) {
                // closing an exchange the handler already closed is not guaranteed to be a no-op
            }
        }
    }

    /** The trace behind {@code feed}, or null when the feed does not keep one. */
    private static TraceLog traceOf(LiveFeed feed) {
        return feed instanceof TraceSource src ? src.trace() : null;
    }

    /**
     * One step with all of its fields. This is the request the debug panel makes when a row is expanded, so it
     * carries the prompts, the raw answer and the traceback that a summary deliberately leaves out.
     */
    private void serveStep(HttpExchange exchange, String token, LiveFeed feed, String arg) throws IOException {
        if (!require(exchange, "GET") || !admit(exchange, token)) return;
        try {
            TraceLog trace = traceOf(feed);
            int seq;
            try {
                seq = Integer.parseInt(arg);
            } catch (NumberFormatException e) {
                send(exchange, 404, TEXT, NOT_FOUND);
                return;
            }
            TraceStep step = trace == null ? null : trace.bySeq(seq);
            if (step == null) {
                send(exchange, 404, TEXT, NOT_FOUND);
                return;
            }
            send(exchange, 200, JSON, utf8(step.toJson().toString()), CACHE_OFF);
        } finally {
            exit(token);
        }
    }

    /**
     * The whole trace as markdown. A person debugging a build wants to attach it to a report or read it in an
     * editor without clicking through every row, and a build makes a few hundred entries.
     */
    private void serveTrace(HttpExchange exchange, String token, LiveFeed feed) throws IOException {
        if (!require(exchange, "GET") || !admit(exchange, token)) return;
        try {
            TraceLog trace = traceOf(feed);
            if (trace == null) {
                send(exchange, 404, TEXT, NOT_FOUND);
                return;
            }
            String name = feed instanceof LiveSession ses ? ses.buildName() : null;
            String outcome = feed instanceof LiveSession ses2 ? ses2.outcome() : null;
            String md = trace.toMarkdown(name, feed.buildId(), outcome);
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"aibuild-trace.md\"");
            send(exchange, 200, "text/markdown; charset=utf-8", utf8(md), CACHE_OFF);
        } finally {
            exit(token);
        }
    }

    private void serveState(HttpExchange exchange, String token, LiveFeed feed) throws IOException {
        if (!require(exchange, "GET") || !admit(exchange, token)) return;
        try {
            String json = "{}";
            try {
                String state = feed.bootstrapJson();
                if (state != null) json = state;
            } catch (RuntimeException e) {
                // A broken session must not become a 500 page: an empty state still lets the viewer connect
                // to the event stream and recover.
                log.log(Level.FINE, "live " + prefix(token) + " bootstrapJson failed", e);
            }
            send(exchange, 200, JSON, utf8(json), CACHE_OFF);
        } finally {
            exit(token);
        }
    }

    private void serveModel(HttpExchange exchange, String token, LiveFeed feed) throws IOException {
        if (!require(exchange, "GET")) return;
        if (!(feed instanceof PackSource source)) {
            send(exchange, 404, TEXT, NOT_FOUND);
            return;
        }
        if (!admit(exchange, token)) return;
        try {
            byte[] pack;
            try {
                pack = source.latestPack();
            } catch (RuntimeException e) {
                log.log(Level.FINE, "live " + prefix(token) + " latestPack failed", e);
                send(exchange, 500, TEXT, utf8("model unavailable\n"));
                return;
            }
            if (pack == null) {
                // No model encoded yet: 204 tells the page to keep drawing the last one it has.
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            send(exchange, 200, OCTETS, pack, CACHE_OFF);
        } finally {
            exit(token);
        }
    }

    /**
     * The block atlas, sent once and cached by the browser.
     *
     * <p>It is ~190 KB and identical for every frame and every build, so it is a separate cacheable resource
     * rather than a section repeated in each {@code model.bin}. {@code immutable} is honest here: a plugin
     * version bump that changes the atlas changes the URL's token, because the session is new.</p>
     */
    private void serveAtlas(HttpExchange exchange, String token, LiveFeed feed) throws IOException {
        if (!require(exchange, "GET")) return;
        if (!(feed instanceof AtlasSource source)) {
            send(exchange, 404, TEXT, NOT_FOUND);
            return;
        }
        if (!admit(exchange, token)) return;
        try {
            byte[] png;
            try {
                png = source.atlasPng();
            } catch (RuntimeException e) {
                log.log(Level.FINE, "live " + prefix(token) + " atlasPng failed", e);
                send(exchange, 500, TEXT, utf8("atlas unavailable\n"));
                return;
            }
            if (png == null) {
                send(exchange, 404, TEXT, NOT_FOUND);
                return;
            }
            // The bytes never change for a session, so validation is a length-and-hash ETag in practice;
            // the browser re-uses them without a round trip while the tab lives.
            String etag = "\"" + png.length + "-" + Integer.toHexString(java.util.Arrays.hashCode(png)) + "\"";
            String ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
            if (etag.equals(ifNoneMatch)) {
                exchange.getResponseHeaders().set("ETag", etag);
                exchange.sendResponseHeaders(304, -1);
                return;
            }
            send(exchange, 200, PNG, png, "Cache-Control", "public, max-age=86400, immutable",
                    "ETag", etag, "Referrer-Policy", "no-referrer");
        } finally {
            exit(token);
        }
    }

    private void serveStop(HttpExchange exchange, String token) throws IOException {
        if (!require(exchange, "POST")) return;
        if (stopper == null) {
            send(exchange, 405, TEXT, utf8("stop is not enabled\n"), "Allow", "GET");
            return;
        }
        boolean stopped;
        try {
            stopped = stopper.stopByViewer(token);
        } catch (RuntimeException e) {
            log.log(Level.FINE, "live " + prefix(token) + " stop handler failed", e);
            send(exchange, 500, TEXT, utf8("internal error\n"));
            return;
        }
        if (stopped) log.fine(() -> "live " + prefix(token) + " stopped by a viewer");
        send(exchange, stopped ? 200 : 409, TEXT, utf8(stopped ? "stopped\n" : "no running job\n"), CACHE_OFF);
    }

    // ---------------------------------------------------------------- event stream

    private void serveEvents(HttpExchange exchange, String token, LiveFeed feed) throws IOException {
        if (!require(exchange, "GET")) return;
        if (!viewerSlots.tryAcquire()) {
            log.fine(() -> "live " + prefix(token) + " refused: " + config.maxSubscribers() + " viewers already attached");
            send(exchange, 503, TEXT, utf8("too many live viewers\n"), "Retry-After", "5");
            return;
        }
        boolean admitted = false;
        try {
            if (!admit(exchange, token)) return;
            admitted = true;
            Stream stream = new Stream(exchange, token);
            streams.add(stream);
            LiveSubscriber.Handle handle = null;
            try {
                // Subscribe before opening the response, so the journal replay is already queued when the
                // browser starts parsing and no frame can arrive before the stream exists.
                handle = feed.subscribe(stream);
                stream.open();
                stream.pump();
            } catch (IOException e) {
                log.fine(() -> "live " + prefix(token) + " viewer disconnected: " + e.getClass().getName());
            } finally {
                stream.shutdown();
                streams.remove(stream);
                closeQuietly(handle, token);
            }
        } finally {
            if (admitted) exit(token);
            viewerSlots.release();
        }
    }

    private void closeQuietly(LiveSubscriber.Handle handle, String token) {
        if (handle == null) return;
        try {
            handle.close();
        } catch (RuntimeException e) {
            log.log(Level.FINE, "live " + prefix(token) + " detach failed", e);
        }
    }

    /**
     * One open {@code /events} connection: a bounded queue between the session's fan-out thread and the
     * socket, plus the writer loop that drains it.
     */
    private final class Stream implements LiveSubscriber {
        private final HttpExchange exchange;
        private final String token;
        private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(MAX_QUEUE_EVENTS);
        private final AtomicLong queuedBytes = new AtomicLong();
        private volatile boolean closed;

        Stream(HttpExchange exchange, String token) {
            this.exchange = exchange;
            this.token = token;
        }

        @Override
        public void send(String frame) {
            if (frame == null || closed) return;
            byte[] bytes = frame.getBytes(StandardCharsets.UTF_8);
            if (bytes.length == 0) return;
            // The byte budget is checked before the queue so a few huge model frames cannot sit in memory;
            // the queue bound catches a slow client before either the heap or the fan-out thread notices.
            if (queuedBytes.addAndGet(bytes.length) > MAX_QUEUE_BYTES || !queue.offer(bytes)) {
                queuedBytes.addAndGet(-bytes.length);
                overflow();
            }
        }

        void open() throws IOException {
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", "text/event-stream; charset=utf-8");
            headers.set("Cache-Control", "no-cache, no-transform");
            headers.set("Connection", "keep-alive");
            headers.set("X-Accel-Buffering", "no");
            headers.set("Referrer-Policy", "no-referrer");
            exchange.sendResponseHeaders(200, 0);
            // Flush a comment frame at once: the JDK holds the response head until the body is first
            // written, and every proxy on the way should see a live stream before the first real event.
            OutputStream out = exchange.getResponseBody();
            out.write(OPEN);
            out.flush();
        }

        void pump() throws IOException {
            OutputStream out = exchange.getResponseBody();
            long lastWrite = System.nanoTime();
            while (!closed && !stopping.get()) {
                byte[] frame;
                try {
                    frame = queue.poll(250, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (frame == POISON) break;
                if (frame == null) {
                    if (System.nanoTime() - lastWrite >= TimeUnit.MILLISECONDS.toNanos(HEARTBEAT_MILLIS)) {
                        out.write(HEARTBEAT);
                        out.flush();
                        lastWrite = System.nanoTime();
                    }
                    continue;
                }
                out.write(frame);
                out.flush();
                queuedBytes.updateAndGet(v -> Math.max(0L, v - frame.length));
                lastWrite = System.nanoTime();
            }
        }

        /**
         * Called from the fan-out thread, the handler's cleanup and {@link LiveViewServer#close()}; stays
         * cheap and idempotent. Closing the exchange is what unblocks a writer stuck on a socket that stopped
         * draining, instead of pinning a pool thread forever.
         */
        void shutdown() {
            if (closed) return;
            closed = true;
            queue.clear();
            queuedBytes.set(0);
            queue.offer(POISON);
            try {
                exchange.close();
            } catch (RuntimeException ignored) {
                // the connection is already gone
            }
        }

        private void overflow() {
            if (closed) return;
            log.fine(() -> "live " + prefix(token) + " overflowed its bounded queue and was dropped");
            shutdown();
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * One parsed request path: a token that passed the shape check, the endpoint below it and, for the endpoints
     * that address one item, its argument.
     */
    private record Route(String token, String endpoint, String arg) {

        Route(String token, String endpoint) {
            this(token, endpoint, "");
        }

        static Route parse(String path) {
            if (!path.startsWith(LIVE_PREFIX)) return null;
            String rest = path.substring(LIVE_PREFIX.length());
            int slash = rest.indexOf('/');
            String token = slash < 0 ? rest : rest.substring(0, slash);
            if (token.length() != TOKEN_LENGTH) return null;
            for (int i = 0; i < TOKEN_LENGTH; i++) {
                if (TOKEN_ALPHABET.indexOf(token.charAt(i)) < 0) return null;
            }
            String endpoint = slash < 0 ? "" : rest.substring(slash + 1);
            int sub = endpoint.indexOf('/');
            String head = sub < 0 ? endpoint : endpoint.substring(0, sub);
            String arg = sub < 0 ? "" : endpoint.substring(sub + 1);
            return switch (head) {
                case "", "state.json", "model.bin", "atlas.png", "events", "stop", "trace.md" -> new Route(token, head, arg);
                // /step/<n> addresses one recorded step
                case "step" -> arg.matches("\\d{1,9}") ? new Route(token, "step", arg) : null;
                default -> null;
            };
        }
    }

    /**
     * Looks the token up and then re-compares it in constant time. The comparison defends against a registry
     * that matches loosely (a prefix, or case-insensitively): nothing short of the exact token gets a 200.
     * Tokens go through this callback and never into a path, so no request can traverse the filesystem.
     */
    private LiveFeed resolve(String token) {
        try {
            LiveFeed feed = registry.get(token);
            if (feed == null || feed.token() == null) return null;
            return MessageDigest.isEqual(utf8(token), utf8(feed.token())) ? feed : null;
        } catch (RuntimeException e) {
            log.log(Level.FINE, "live " + prefix(token) + " registry lookup failed", e);
            return null;
        }
    }

    /**
     * True when the request must be refused for its origin. Off by default: the page is normally opened from
     * the tunnel URL, and curl (or a privacy-hardened browser) sends no {@code Origin} at all.
     */
    private boolean crossOrigin(HttpExchange exchange) {
        if (!config.requireSameOrigin()) return false;
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null || origin.isBlank()) return false;
        String normalized = normalizeOrigin(origin);
        if (normalized == null) return true;
        if (normalized.equals(normalizeOrigin(config.publicBaseUrl()))) return false;
        // A page opened locally posts its stop button from the loopback origin the server itself answers on.
        String port = ":" + port();
        return !normalized.equals("http://127.0.0.1" + port) && !normalized.equals("http://localhost" + port)
                && !normalized.equals("http://[::1]" + port);
    }

    /** Reduces {@code scheme://host[:port]} to a canonical origin; null when it is not an origin at all. */
    private static String normalizeOrigin(String value) {
        if (value == null || value.isBlank()) return null;
        String v = value.trim();
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        try {
            URI uri = URI.create(v);
            if (uri.getScheme() == null || uri.getHost() == null) return null;
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            return port <= 0 || port == ("https".equals(scheme) ? 443 : 80)
                    ? scheme + "://" + host : scheme + "://" + host + ":" + port;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Enforces the HTTP method, answering 405 itself; true when the request may proceed. */
    private static boolean require(HttpExchange exchange, String method) throws IOException {
        if (method.equalsIgnoreCase(exchange.getRequestMethod())) return true;
        send(exchange, 405, TEXT, utf8("method not allowed\n"), "Allow", method);
        return false;
    }

    /**
     * Admits one unit of work against the token's cap, or answers 429 itself. A leaked or refreshed URL must
     * not turn into an unbounded number of concurrent requests against the small worker pool, and per-token
     * is the right granularity for a one-time link.
     */
    private boolean admit(HttpExchange exchange, String token) throws IOException {
        AtomicInteger counter = inFlight.computeIfAbsent(token, ignored -> new AtomicInteger());
        for (; ; ) {
            int current = counter.get();
            if (current < MAX_IN_FLIGHT_PER_TOKEN) {
                if (counter.compareAndSet(current, current + 1)) return true;
                continue;
            }
            send(exchange, 429, TEXT, utf8("too many requests\n"), "Retry-After", "1");
            return false;
        }
    }

    private void exit(String token) {
        AtomicInteger counter = inFlight.get(token);
        if (counter != null && counter.decrementAndGet() <= 0) {
            // Dropped at rest so a long-lived server does not keep an entry per build ever viewed. A
            // concurrent admit that grabbed this instance just before the removal only makes the cap
            // briefly generous, which is harmless.
            inFlight.remove(token, counter);
        }
    }

    private static void send(HttpExchange exchange, int code, String contentType, byte[] body, String... headers)
            throws IOException {
        Headers response = exchange.getResponseHeaders();
        response.set("Content-Type", contentType);
        response.set("X-Content-Type-Options", "nosniff");
        for (int i = 0; i + 1 < headers.length; i += 2) response.set(headers[i], headers[i + 1]);
        exchange.sendResponseHeaders(code, body.length);
        if (body.length > 0) {
            OutputStream out = exchange.getResponseBody();
            out.write(body);
            out.flush();
        }
    }

    private byte[] readPage() {
        try (InputStream in = LiveViewServer.class.getResourceAsStream(PAGE_RESOURCE)) {
            if (in == null) return null;
            byte[] bytes = in.readAllBytes();
            return bytes.length == 0 ? null : bytes;
        } catch (IOException e) {
            log.log(Level.FINE, "could not read " + PAGE_RESOURCE, e);
            return null;
        }
    }

    /** Never 500s on a missing page resource: the operator gets a readable message instead. */
    private static byte[] fallbackPage() {
        return utf8("""
                <!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <title>AIBuild live view</title>
                <body style="font:16px/1.5 system-ui,sans-serif;max-width:40rem;margin:10vh auto;padding:0 1.5rem">
                <h1>AIBuild live view</h1>
                <p>The page resource <code>/live/index.html</code> is missing from this build of the plugin, but the
                session is still running: <a href="state.json">state.json</a>, <a href="model.bin">model.bin</a> and
                the event stream at <code>events</code> are served.
                """);
    }

    private static ThreadFactory daemonThreads() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "AIBuild-live-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /** The first six characters of a token: enough to correlate logs, useless as a link. */
    private static String prefix(String token) {
        if (token == null) return "??????";
        return token.length() <= 6 ? token : token.substring(0, 6);
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}
