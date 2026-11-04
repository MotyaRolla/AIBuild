package dev.aibuild.live;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.aibuild.model.BlockInfo;
import dev.aibuild.model.VoxelModel;
import dev.aibuild.pipeline.Job;
import dev.aibuild.render.BlockTextures;
import dev.aibuild.script.ScriptResult;
import dev.aibuild.util.Json;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One viewable build run: its journal, its latest projection and the browsers watching it.
 *
 * <p>The pipeline calls {@link LiveBroadcast} methods straight from worker and transport I/O threads, so every
 * method here only records state and queues work; a single private daemon thread turns that state into events,
 * encodes projections and fans them out. High-frequency inputs coalesce instead of queueing: the streamed
 * program text and the build model each keep only their newest value, so a slow encode never builds a backlog
 * and a browser always receives the newest state rather than a replay of stale frames.</p>
 *
 * <p>The journal is bounded. A browser that connects late receives the recent history (last stages, the current
 * program text, the last few transcript lines) plus the newest model, which is what a viewer actually needs to
 * follow a build that has been running for ten minutes.</p>
 */
public final class LiveSession implements LiveFeed, LiveBroadcast, LiveViewServer.PackSource,
        LiveViewServer.AtlasSource, LiveViewServer.TraceSource {

    /** Journal entries kept for late subscribers (events, not bytes; the model is stored separately). */
    static final int JOURNAL_LIMIT = 400;
    static final int NOTE_LIMIT = 120;
    static final int SAY_LIMIT = 60;

    private final String token;
    private final String buildId;
    private final String buildName;
    private final String modeId;
    private final String kind;
    private final String ownerName;
    private final long startedAt = System.currentTimeMillis();
    private final Job job;
    private final BlockInfo info;
    private final long seed;

    private final List<LiveSubscriber> subscribers = new CopyOnWriteArrayList<>();
    private final LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
    private final Thread worker;

    private final Object lock = new Object();
    private final Deque<LiveEvent> journal = new ArrayDeque<>();
    private long seq;
    /** Every debuggable step of this job, kept whole for the debug panel. */
    private final TraceLog trace = new TraceLog();
    /** The last model row shown, so re-announcing the same version does not add a duplicate trace entry. */
    private String lastModelRow = "";
    private String lastModelTag = "";
    /** Guards {@link #finish} so it publishes exactly one banner even when several paths end the job. */
    private final AtomicBoolean finishClaimed = new AtomicBoolean();
    /** The newest progress value the pipeline published for a stage; see {@link #progress()}. */
    private volatile double stageProgress;
    /** The model of the previously encoded frame, used to flag the cells that changed since. */
    private final AtomicReference<VoxelModel> lastEncoded = new AtomicReference<>();
    private long lastAtMs;
    private final Deque<StageEntry> timeline = new ArrayDeque<>();
    private final Deque<String[]> notes = new ArrayDeque<>();
    private final Deque<String> says = new ArrayDeque<>();
    private final JsonArray parts = new JsonArray();

    private final ConcurrentHashMap<String, String> liveText = new ConcurrentHashMap<>();
    private final AtomicBoolean textScheduled = new AtomicBoolean();
    private final AtomicReference<String> latestThink = new AtomicReference<>("");
    private final AtomicBoolean thinkScheduled = new AtomicBoolean();
    private final AtomicReference<VoxelModel> pendingModel = new AtomicReference<>();
    private final AtomicBoolean modelScheduled = new AtomicBoolean();
    private volatile String pendingWhy = "stage";

    private volatile byte[] latestPack = new byte[0];
    private volatile String latestModelWhy;
    private volatile long latestModelAt;
    private volatile int latestModelCells;
    private volatile String stage = "queued";
    private volatile String title = "Job queued";
    private volatile String detail = "";
    private volatile String outcome;
    private volatile boolean finished;
    private volatile long finishedAt;
    private volatile String part = "";

    /** One timeline row: a stage that started at {@code at} (epoch millis) and ran for {@code ms}. */
    record StageEntry(String stage, String title, long at, long ms) {
    }

    LiveSession(String token, Job job, String buildName, String ownerName, BlockInfo info) {
        this.token = token;
        this.job = job;
        this.buildId = job.buildId;
        this.buildName = buildName;
        this.ownerName = ownerName;
        this.modeId = job.mode == null ? "" : job.mode.id;
        this.kind = job.kind == null ? "" : job.kind.name();
        this.info = info;
        this.seed = job.scriptSeed;
        this.worker = new Thread(this::loop, "AIBuild-live-" + token.substring(0, 6));
        this.worker.setDaemon(true);
        this.worker.start();
    }

    // ------------------------------------------------------------------ LiveFeed

    /** The name the build was given, for the trace header and the page. */
    public String buildName() {
        return buildName;
    }

    /** The whole trace of this job, for the debug endpoints. */
    public TraceLog trace() {
        return trace;
    }

    /** The first six characters of the token, safe to log; see {@link LiveViewServer} on why never the whole. */
    public String tokenPrefix() {
        return token.length() <= 6 ? token : token.substring(0, 6);
    }


    public String token() {
        return token;
    }

    @Override
    public String buildId() {
        return buildId;
    }

    /** The newest encoded projection (the HTTP layer serves it as {@code model.bin}). */
    @Override
    public byte[] latestPack() {
        return latestPack;
    }

    /** The block atlas for {@code /atlas.png}, or null when this server has no textures installed. */
    @Override
    public byte[] atlasPng() {
        BlockTextures t = info == null ? null : BlockTextures.current();
        return t == null ? null : t.png();
    }

    @Override
    public LiveSubscriber.Handle subscribe(LiveSubscriber subscriber) {
        synchronized (lock) {
            for (LiveEvent e : journal) subscriber.send(frame(e));
            subscriber.send(frame(new LiveEvent(LiveEvent.MODEL, seq, System.currentTimeMillis(), modelNotice())));
            subscribers.add(subscriber);
        }
        return () -> subscribers.remove(subscriber);
    }

    @Override
    public String bootstrapJson() {
        // A consistent snapshot: seq, finished and the timeline are written by the worker thread under the
        // lock, so reading them one by one lets the page miss the watermark and re-apply replayed frames.
        synchronized (lock) {
            return bootstrapLocked();
        }
    }

    private String bootstrapLocked() {
        JsonObject o = new JsonObject();
        o.addProperty("token", token);
        // the sequence number of the state snapshot: the page drops any journal frame at or below it, so a
        // viewer that hydrated does not re-apply history the state already contained
        o.addProperty("seq", seq);
        o.addProperty("buildId", buildId);
        o.addProperty("buildName", buildName);
        o.addProperty("mode", modeId);
        o.addProperty("kind", kind);
        o.addProperty("owner", ownerName);
        o.addProperty("startedAt", startedAt);
        o.addProperty("elapsedMs", System.currentTimeMillis() - startedAt);
        o.addProperty("finished", finished);
        if (outcome != null) o.addProperty("outcome", outcome); else o.add("outcome", null);
        o.addProperty("status", title);
        o.addProperty("stage", stage);
        o.addProperty("progress", progress());
        o.addProperty("part", part);
        JsonObject counters = new JsonObject();
        counters.addProperty("calls", job.calls.get());
        counters.addProperty("promptTokens", job.promptTokens.get());
        counters.addProperty("completionTokens", job.completionTokens.get());
        counters.addProperty("cachedTokens", job.cachedTokens.get());
        counters.addProperty("reasoningTokens", job.reasoningTokens.get());
        counters.addProperty("streamChars", job.streamChars);
        o.add("counters", counters);
        JsonArray code = new JsonArray();
        JsonArray partsSnapshot = parts.deepCopy();
        liveText.forEach((k, v) -> {
            JsonObject c = new JsonObject();
            c.addProperty("kind", k);
            c.addProperty("text", v);
            code.add(c);
        });
        o.add("code", code);
        o.addProperty("think", latestThink.get());
        JsonArray noteArr = new JsonArray();
        synchronized (lock) {
            for (String[] n : notes) {
                JsonObject j = new JsonObject();
                j.addProperty("tag", n[0]);
                j.addProperty("text", n[1]);
                noteArr.add(j);
            }
            JsonArray sayArr = new JsonArray();
            for (String s : says) sayArr.add(s);
            o.add("says", sayArr);
            JsonArray tl = new JsonArray();
            for (StageEntry e : timeline) {
                JsonObject j = new JsonObject();
                j.addProperty("stage", e.stage());
                j.addProperty("title", e.title());
                j.addProperty("at", e.at());
                j.addProperty("ms", e.ms());
                tl.add(j);
            }
            o.add("timeline", tl);
        }
        o.add("notes", noteArr);
        o.add("parts", partsSnapshot);
        o.add("trace", trace.summariesJson(0, 400));
        o.add("traceStats", trace.statsJson());
        o.add("model", modelNotice());
        BlockTextures t = BlockTextures.current();
        o.addProperty("hasTextures", t != null);
        if (detail != null && !detail.isEmpty()) o.addProperty("detail", detail);
        return Json.GSON.toJson(o);
    }

    private JsonObject modelNotice() {
        JsonObject m = new JsonObject();
        if (latestModelWhy != null) m.addProperty("why", latestModelWhy);
        m.addProperty("url", "/live/" + token + "/model.bin");
        m.addProperty("at", latestModelAt);
        m.addProperty("cells", latestModelCells);
        return m;
    }

    /**
     * Bar position: the stage's own progress when the pipeline supplied one, else the job's counter. The two
     * disagree by design — a job's counter is written for the boss bar and jumps at coarse moments, while the
     * stage progress says "this phase is 10% done" before any blocks exist.
     */
    private double progress() {
        double byJob = 0;
        try {
            byJob = job.progress;
        } catch (RuntimeException ignored) {
            // a job whose counters are not readable yet is simply at zero
        }
        if (!Double.isFinite(byJob)) byJob = 0;
        return Math.max(0, Math.min(1, Math.max(byJob, stageProgress)));
    }

    // ------------------------------------------------------------------ LiveBroadcast (any thread, cheap)

    /**
     * Records a phase change as a trace step as well as a stage event.
     *
     * <p>Doing it here rather than in the pipeline means every caller — including ones added later — gets it.
     * The step carries no fields, so it costs a few dozen bytes and gives the debug list the context that makes
     * the expensive steps readable: which phase a failing call happened in.</p>
     */
    @Override
    public void stage(String stage, String title, double progress) {
        if (stage == null) return;
        this.stageProgress = Double.isFinite(progress) ? Math.max(0, Math.min(1, progress)) : this.stageProgress;
        long now = System.currentTimeMillis();
        synchronized (lock) {
            StageEntry last = timeline.peekLast();
            if (last != null && last.stage().equals(stage) && last.title().equals(safe(title))) {
                // same stage again: just extend it, no new row
                timeline.pollLast();
                timeline.addLast(new StageEntry(last.stage(), last.title(), last.at(), Math.max(0, now - last.at())));
            } else {
                if (last != null && last.ms() == 0) {
                    timeline.pollLast();
                    timeline.addLast(new StageEntry(last.stage(), last.title(), last.at(), Math.max(0, now - last.at())));
                }
                timeline.addLast(new StageEntry(stage, safe(title), now, 0));
                while (timeline.size() > 40) timeline.removeFirst();
            }
        }
        this.stage = stage;
        this.title = safe(title);
        JsonObject data = new JsonObject();
        data.addProperty("stage", stage);
        data.addProperty("title", safe(title));
        data.addProperty("progress", progress());
        publish(LiveEvent.of(LiveEvent.STAGE, 0, data));
        // Recorded here rather than by the pipeline, so any caller of stage() gets a trace row for free and the
        // debug list has the phase context around the expensive steps. Published after the stage frame so the
        // stream keeps the order the pipeline actually performed.
        trace(new TraceStep.Builder(TraceStep.Kind.PIPELINE, stage)
                .title(safe(title))
                .status(TraceStep.Status.OK)
                .summary("progress " + Math.round(progress() * 100) + "%"));
    }

    @Override
    public void code(String kind, CharSequence textSoFar) {
        if (kind == null) return;
        liveText.put(kind, textSoFar == null ? "" : textSoFar.toString());
        scheduleText();
    }

    @Override
    public void reasoning(CharSequence textSoFar) {
        latestThink.set(textSoFar == null ? "" : textSoFar.toString());
        if (thinkScheduled.compareAndSet(false, true)) queue.offer(this::emitThink);
    }

    @Override
    public void preview(boolean ok, int blocks, int length) {
        JsonObject data = new JsonObject();
        data.addProperty("ok", ok);
        data.addProperty("blocks", blocks);
        data.addProperty("length", length);
        publish(LiveEvent.of(LiveEvent.PREVIEW, 0, data));
    }

    @Override
    public void model(VoxelModel model, String why) {
        if (model == null) return;
        // Only the noteworthy changes get a trace row. A streaming build emits a model per partial program run,
        // and those are already visible as the build growing; recording them here would push the LLM calls — the
        // rows a person debug with — out of the bounded trace within a minute.
        // Repeats are dropped too: callers re-announce "the current model" (before the commit banner, after a
        // rejection), and three identical rows make the trace look like it is losing track of the build.
        if (!"stream".equals(why)) {
            String row = model.nonAirCount() + " blocks, " + model.size() + " cells";
            String tag = why == null ? "model" : why;
            if (trace.lastSeq() == 0 || !row.equals(lastModelRow) || !tag.equals(lastModelTag)) {
                lastModelRow = row;
                lastModelTag = tag;
                trace(new TraceStep.Builder(TraceStep.Kind.MODEL, tag)
                        .title("Model · " + (why == null ? "update" : why))
                        .status(TraceStep.Status.OK)
                        .summary(row));
            }
        }
        pendingModel.set(model.copy());
        pendingWhy = why == null ? "stage" : why;
        scheduleModel();
    }

    @Override
    public void parts(List<ScriptResult.Part> list) {
        if (list == null) return;
        JsonArray arr = new JsonArray();
        JsonObject data = new JsonObject();
        for (ScriptResult.Part p : list) {
            JsonObject o = new JsonObject();
            o.addProperty("name", p.name());
            o.addProperty("cells", p.cells());
            JsonArray b = new JsonArray();
            for (int v : p.bounds()) b.add(v);
            o.add("bounds", b);
            arr.add(o);
        }
        synchronized (lock) {
            while (!parts.isEmpty()) parts.remove(0);
            for (var el : arr) parts.add(el);
        }
        data.add("parts", arr.deepCopy());
        publish(LiveEvent.of(LiveEvent.PARTS, 0, data));
        // The parts usually arrive after the last model frame (the pipeline only knows them once the program
        // ran), and the pack's part indices and per-part bounds are computed at encode time. Re-encode the
        // newest model so the page's part legend and its per-cell part indices agree instead of showing zero.
        VoxelModel latest = lastEncoded.get();
        if (latest != null) {
            pendingModel.compareAndSet(null, latest);
            pendingWhy = latestModelWhy == null ? "stage" : latestModelWhy;
            scheduleModel();
        }
    }

    @Override
    public void note(String tag, String text) {
        String t = clip(text, 2000);
        synchronized (lock) {
            notes.addLast(new String[]{safe(tag), t});
            while (notes.size() > NOTE_LIMIT) notes.removeFirst();
        }
        JsonObject data = new JsonObject();
        data.addProperty("tag", safe(tag));
        data.addProperty("text", t);
        publish(LiveEvent.of(LiveEvent.NOTE, 0, data));
    }

    @Override
    public void say(String text) {
        String plain = plain(stripTags(text));
        if (plain.isEmpty()) return;
        synchronized (lock) {
            says.addLast(plain);
            while (says.size() > SAY_LIMIT) says.removeFirst();
        }
        JsonObject data = new JsonObject();
        data.addProperty("text", plain);
        publish(LiveEvent.of(LiveEvent.SAY, 0, data));
    }

    @Override
    public void trace(TraceStep.Builder step) {
        if (step == null) return;
        // Recorded synchronously so the sequence order matches the order things happened, then published on the
        // detail-free summary. The page fetches the rest per step; putting prompts on the stream would push
        // hundreds of kilobytes through the journal and evict the events a viewer actually needs.
        TraceStep done = trace.add(step);
        JsonObject data = done.summaryJson();
        data.add("stats", trace.statsJson());
        publish(LiveEvent.of(LiveEvent.STEP, 0, data));
    }

    @Override
    public void finish(String outcome, String detail) {
        // Exactly once, and never through the work queue: the caller is usually the job's last act, after which
        // the previous session is shut down (which interrupts the worker), so a queued finish could be dropped
        // and the page would stay on "working" forever. Idempotent so a superseded session and its own
        // runGuarded finally cannot both publish a finish the page would flip-flop on.
        if (!finishClaimed.compareAndSet(false, true)) return;
        this.detail = clip(stripTags(detail), 600);
        this.outcome = outcome == null ? "done" : outcome;
        // Anything the worker has queued is stale once the job is over.
        queue.clear();
        modelScheduled.set(false);
        // The banner is published under the same lock the worker's frames take, together with the `finished`
        // flag, so a frame can never be published after it: the worker either got the lock first (its frame is
        // in the journal ahead of the banner) or finds `finished` set and stops. Without this the worker can be
        // inside emitModel when finish runs, and the page keeps redrawing behind a completion banner.
        synchronized (lock) {
            emitModelLocked(false);
            this.finished = true;
            this.finishedAt = System.currentTimeMillis();
            this.title = "Finished";
            this.stage = "finished";
            this.part = "";
            JsonObject data = new JsonObject();
            data.addProperty("outcome", this.outcome);
            data.addProperty("detail", this.detail);
            publish(LiveEvent.of(LiveEvent.FINISH, 0, data));
        }
    }

    /** Sets the name of the part the model is writing right now (shown next to the program text). */
    public void part(String name) {
        this.part = safe(name);
    }

    public boolean finished() {
        return finished;
    }

    /** When the run ended (epoch millis), 0 while it is still running. */
    public long finishedAt() {
        return finishedAt;
    }

    public String outcome() {
        return outcome;
    }

    // ------------------------------------------------------------------ worker

    private void loop() {
        while (!Thread.currentThread().isInterrupted()) {
            Runnable r;
            try {
                r = queue.poll(15, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (r == null) {
                heartbeat();
                continue;
            }
            try {
                r.run();
            } catch (RuntimeException e) {
                // a viewer must never break a build
            }
            Runnable more;
            while ((more = queue.poll()) != null) {
                try {
                    more.run();
                } catch (RuntimeException ignored) {
                    // as above
                }
            }
        }
    }

    private void scheduleText() {
        if (textScheduled.compareAndSet(false, true)) queue.offer(this::emitText);
    }

    private void emitText() {
        textScheduled.set(false);
        liveText.forEach((kind, text) -> {
            JsonObject data = new JsonObject();
            data.addProperty("kind", kind);
            data.addProperty("text", text);
            publish(LiveEvent.of(LiveEvent.CODE, 0, data));
        });
    }

    private void emitThink() {
        thinkScheduled.set(false);
        String t = latestThink.get();
        if (t.isEmpty()) return;
        JsonObject data = new JsonObject();
        data.addProperty("text", t);
        publish(LiveEvent.of(LiveEvent.THINK, 0, data));
    }

    private void scheduleModel() {
        if (modelScheduled.compareAndSet(false, true)) queue.offer(this::emitModel);
    }

    private void emitModel() {
        modelScheduled.set(false);
        synchronized (lock) {
            emitModelLocked(true);
        }
    }

    /**
     * Encodes and publishes the pending projection. Callers hold {@link #lock}.
     *
     * @param mayPublish false while the session is being finished: the pack is still refreshed for a viewer that
     *                   loads the page later, but no event is published, because the banner has already gone out
     *                   or is going out in this same critical section
     */
    private void emitModelLocked(boolean mayPublish) {
        VoxelModel m = pendingModel.getAndSet(null);
        if (m == null) return;
        String why = pendingWhy;
        byte[] pack;
        try {
            // Diff against the previous frame so the page can flash exactly the cells that appeared, moved or
            // changed, instead of guessing from cell counts.
            Set<Long> before = new HashSet<>();
            VoxelModel prev = lastEncoded.getAndSet(m);
            if (prev != null && prev != m) {
                prev.forEach((x, y, z, spec) -> before.add(VoxelModel.key(x, y, z)));
            }
            Set<Long> changed = new HashSet<>();
            if (!before.isEmpty()) {
                m.forEach((x, y, z, spec) -> {
                    long k = VoxelModel.key(x, y, z);
                    if (!before.remove(k)) changed.add(k);
                });
                // whatever is left in `before` disappeared: its cells are not in the pack any more
            }
            pack = ModelPackWriter.encode(m, info, why, seed, snapshotParts(), changed,
                    "/live/" + token + "/atlas.png");
        } catch (RuntimeException e) {
            return; // a projection that cannot be built simply is not sent; the next one will be
        }
        latestPack = pack;
        latestModelWhy = why;
        latestModelAt = System.currentTimeMillis();
        latestModelCells = m.nonAirCount();
        JsonObject data = new JsonObject();
        data.addProperty("why", why);
        data.addProperty("url", "/live/" + token + "/model.bin");
        data.addProperty("at", latestModelAt);
        data.addProperty("cells", latestModelCells);
        if (mayPublish && !finished) publish(LiveEvent.of(LiveEvent.MODEL, 0, data));
    }

    private List<ScriptResult.Part> snapshotParts() {
        JsonArray arr;
        synchronized (lock) {
            arr = parts.deepCopy();
        }
        List<ScriptResult.Part> out = new ArrayList<>(arr.size());
        for (var el : arr) {
            JsonObject o = el.getAsJsonObject();
            JsonArray b = o.getAsJsonArray("bounds");
            int[] bounds = new int[b.size()];
            for (int i = 0; i < bounds.length; i++) bounds[i] = b.get(i).getAsInt();
            out.add(new ScriptResult.Part(o.get("name").getAsString(), o.get("cells").getAsInt(), bounds));
        }
        return out;
    }

    /**
     * Appends to the journal and hands the frame to every subscriber. The journal replay and the subscriber
     * list are updated under one lock so a browser that connects mid-stream never misses or duplicates an event.
     */
    private void publish(LiveEvent event) {
        String frame;
        synchronized (lock) {
            LiveEvent e = new LiveEvent(event.type(), ++seq, event.atMillis(), event.data());
            lastAtMs = Math.max(lastAtMs, e.atMillis());
            journal.addLast(e);
            while (journal.size() > JOURNAL_LIMIT) journal.removeFirst();
            frame = frame(e);
            for (LiveSubscriber s : subscribers) {
                try {
                    s.send(frame);
                } catch (RuntimeException ex) {
                    subscribers.remove(s);
                }
            }
        }
    }

    private void heartbeat() {
        for (LiveSubscriber s : subscribers) {
            try {
                s.send(": keep-alive\n\n");
            } catch (RuntimeException e) {
                subscribers.remove(s);
            }
        }
    }

    /** Formats one Server-Sent-Events frame; JSON is single-line, so no data-line splitting is needed. */
    static String frame(LiveEvent e) {
        return "event: " + e.type() + "\nid: " + e.seq() + "\ndata: " + Json.GSON.toJson(e.data()) + "\n\n";
    }

    /** Stops the worker; the journal and the last model stay readable until the link expires. */
    /**
     * Ends the session for good: detaches viewers, stops the worker and releases the biggest thing it holds.
     *
     * <p>{@code latestPack} is a full encoded build — tens of megabytes for a large one — and a finished session
     * would otherwise keep it (and a waking daemon thread) for the whole expiry window. Nothing can ask for it
     * after shutdown: the token is gone from the registry, so no request can reach this instance.</p>
     */
    void shutdown() {
        subscribers.clear();
        worker.interrupt();
        latestPack = null;
        lastEncoded.set(null);
        pendingModel.set(null);
        synchronized (lock) {
            journal.clear();
            notes.clear();
            says.clear();
            timeline.clear();
        }
    }

    // ------------------------------------------------------------------ text helpers

    /** MiniMessage tags the chat layer adds are noise on the page. */
    static String stripTags(String s) {
        if (s == null) return "";
        return s.replaceAll("</?[a-zA-Z#!_][^>]*>", "").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }

    private static String plain(String s) {
        return s == null ? "" : s.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    // ------------------------------------------------------------------ registry

    /**
     * One live link per build, replaced when the build starts a new run.
     *
     * <p>A link is unguessable ({@value #TOKEN_BYTES}·8 bits of {@link java.security.SecureRandom}), valid for
     * one session and revoked {@code expireMinutes} after the run ends. Revoking is what "one-time" means here:
     * the URL is useless once the build is done and the grace window has passed, so a link pasted into Discord
     * does not keep exposing a player's plot forever. Fresh runs get a fresh link, so old links never show the
     * wrong job.</p>
     */
    public static final class Registry {
        private static final int TOKEN_BYTES = 16;
        private final java.util.concurrent.ConcurrentHashMap<String, LiveSession> sessions =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.concurrent.ConcurrentHashMap<String, LiveSession> byBuild =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final java.security.SecureRandom random = new java.security.SecureRandom();
        private final long ttlMillis;
        private final java.util.logging.Logger log;

        public Registry(int expireMinutes, java.util.logging.Logger log) {
            this.ttlMillis = Math.max(1, expireMinutes) * 60_000L;
            this.log = log;
        }

        /** Issues a fresh link for {@code job}, revoking the build's previous one. */
        public LiveSession open(Job job, String buildName, String ownerName, BlockInfo info) {
            LiveSession previous = byBuild.remove(job.buildId);
            if (previous != null) {
                sessions.values().remove(previous);
                previous.finish("cancelled", "Superseded by a newer run of this build.");
                previous.shutdown();
            }
            byte[] raw = new byte[TOKEN_BYTES];
            random.nextBytes(raw);
            StringBuilder sb = new StringBuilder(TOKEN_BYTES * 2);
            for (byte b : raw) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            LiveSession s = new LiveSession(sb.toString(), job, buildName, ownerName, info);
            sessions.put(s.token(), s);
            byBuild.put(job.buildId, s);
            log.fine(() -> "live link opened for build " + job.buildId + " token " + s.token().substring(0, 6) + "…");
            return s;
        }

        /** The session for a token, or null when unknown or expired. Constant-time token comparison. */
        public LiveSession get(String token) {
            if (token == null || token.length() != TOKEN_BYTES * 2) return null;
            LiveSession s = sessions.get(token);
            if (s == null) return null;
            if (!java.security.MessageDigest.isEqual(token.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    s.token().getBytes(java.nio.charset.StandardCharsets.US_ASCII))) return null;
            if (s.finished() && s.finishedAt() > 0 && System.currentTimeMillis() - s.finishedAt() > ttlMillis) {
                sessions.remove(token, s);
                byBuild.remove(s.buildId(), s);
                s.shutdown();
                return null;
            }
            return s;
        }

        /** The build's current session, or null. */
        public LiveSession forBuild(String buildId) {
            return byBuild.get(buildId);
        }

        /** Closes every link (plugin disable). */
        public void closeAll() {
            sessions.values().forEach(LiveSession::shutdown);
            sessions.clear();
            byBuild.clear();
        }

        /** Drops expired links; called periodically by the plugin. */
        public void sweep() {
            long now = System.currentTimeMillis();
            for (LiveSession s : sessions.values()) {
                if (s.finished() && s.finishedAt() > 0 && now - s.finishedAt() > ttlMillis) {
                    sessions.remove(s.token(), s);
                    byBuild.remove(s.buildId(), s);
                    s.shutdown();
                }
            }
        }

        public int size() {
            return sessions.size();
        }
    }
}
