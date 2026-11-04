package dev.aibuild.pipeline;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aibuild.ai.ChatMessage;
import dev.aibuild.ai.StreamListener;
import dev.aibuild.ai.ChatRequest;
import dev.aibuild.ai.ChatResult;
import dev.aibuild.ai.LlmClient;
import dev.aibuild.ai.LlmException;
import dev.aibuild.analysis.AnalysisReport;
import dev.aibuild.analysis.AnalyzerOptions;
import dev.aibuild.analysis.StructureAnalyzer;
import dev.aibuild.analysis.TextRenderer;
import dev.aibuild.analysis.SpatialReport;
import dev.aibuild.build.Build;
import dev.aibuild.build.BuildStore;
import dev.aibuild.build.BuildVersion;
import dev.aibuild.config.BuildMode;
import dev.aibuild.config.PluginConfig;
import dev.aibuild.live.LiveBroadcast;
import dev.aibuild.live.LiveSession;
import dev.aibuild.live.TraceStep;
import dev.aibuild.model.BlockInfo;
import dev.aibuild.model.BlockSpec;
import dev.aibuild.model.ShapeResolver;
import dev.aibuild.model.VoxelModel;
import dev.aibuild.model.ops.OpExecutor;
import dev.aibuild.model.ops.OpResult;
import dev.aibuild.render.ContactSheet;
import dev.aibuild.script.ScriptLint;
import dev.aibuild.script.ScriptPatch;
import dev.aibuild.script.ScriptResult;
import dev.aibuild.script.ScriptRuntime;
import dev.aibuild.util.Json;
import dev.aibuild.util.Msg;
import dev.aibuild.world.PlacementMath;
import dev.aibuild.world.PlaceResult;
import dev.aibuild.world.WorldPlacer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Orchestrates AI generation and editing: plan -> staged build -> self-review/fix loop -> placement.
 * Heavy work runs on a worker pool; every Build mutation and world access happens on the main thread.
 */
public final class BuildPipeline {
    /** Where a new build should go. y == null -> detect ground level. */
    public record Anchor(String world, int x, Integer y, int z, int rotation) {
    }

    private final Plugin plugin;
    private final BuildStore store;
    private final WorldPlacer placer;
    private final BlockInfo info;
    private final Supplier<LlmClient> llm;
    private final Supplier<PluginConfig> cfg;
    private final Supplier<String> blockList;
    private final ExecutorService pool;
    /** Extra threads for parallel stage calls (never used for whole jobs, so no deadlock with {@link #pool}). */
    private final ExecutorService side = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "AIBuild-llm");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Job> jobsByBuild = new ConcurrentHashMap<>();
    private final AtomicInteger threadNo = new AtomicInteger();
    /**
     * Live-view links (null when the feature is off). Set by the plugin after construction; every use is
     * null-checked, so a plain pipeline (benchmarks, tests) needs no live view at all.
     */
    private volatile LiveSession.Registry liveLinks;
    /** Human-readable status of the live-link server for {@code /aib watch}, null when off. */
    private volatile java.util.function.Supplier<String> liveStatus;
    /** Resolves the user-facing live URL of a session (tunnel / reverse proxy address), null when not announced. */
    private volatile java.util.function.Function<LiveSession, String> liveUrlResolver;

    public BuildPipeline(Plugin plugin, BuildStore store, WorldPlacer placer, BlockInfo info, Supplier<LlmClient> llm,
                         Supplier<PluginConfig> cfg, Supplier<String> blockList) {
        this.plugin = plugin;
        this.store = store;
        this.placer = placer;
        this.info = info;
        this.llm = llm;
        this.cfg = cfg;
        this.blockList = blockList;
        this.pool = Executors.newFixedThreadPool(cfg.get().maxConcurrentJobs, r -> {
            Thread t = new Thread(r, "AIBuild-worker-" + threadNo.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    public Collection<Job> jobs() {
        return jobsByBuild.values();
    }

    /** Attaches live-view links; {@code null} turns the feature off. */
    public void liveLinks(LiveSession.Registry registry) {
        this.liveLinks = registry;
    }

    /** Sets the one-line status text {@code /aib watch} shows (null when the server is off). */
    public void liveStatus(java.util.function.Supplier<String> status) {
        this.liveStatus = status;
    }

    /**
     * Sets the resolver for the URL shown to a person (null disables live links in chat). It may return null for
     * a link nobody outside the machine could open, in which case only the console URL is logged.
     */
    public void liveUrl(java.util.function.Function<LiveSession, String> resolver) {
        this.liveUrlResolver = resolver;
    }

    /** One-line status of the live-view server, or null when the feature is off. */
    public String liveStatus() {
        java.util.function.Supplier<String> s = liveStatus;
        return s == null ? null : s.get();
    }

    /** The current live link of a build, or null. */
    public LiveSession liveFor(String buildId) {
        LiveSession.Registry r = liveLinks;
        return r == null ? null : r.forBuild(buildId);
    }

    /**
     * A listener that shows the streamed answer of a non-script-stage call (a critic's rewrite, an edit) on the
     * page, so the viewer always sees what the model is writing, not just the initial program.
     */
    private StreamListener codeStream(Job job, String kind) {
        LiveBroadcast lb = live(job);
        if (lb == LiveBroadcast.NOOP) return null;
        return new StreamListener() {
            @Override
            public void onProgress(int contentChars, int reasoningChars) {
            }

            @Override
            public void onText(CharSequence textSoFar) {
                lb.code(kind, textSoFar);
            }

            @Override
            public void reasoning(String textSoFar) {
                lb.reasoning(textSoFar);
            }
        };
    }

    /** The trace of a job, or null when it has no viewer. */
    private LiveBroadcast traceOf(Job job) {
        return live(job);
    }

    /**
     * Records one debuggable step. Wrapped so a broken viewer can never break a build, and so callers can stay
     * one-liners at the point where the interesting data actually exists.
     */
    private TraceStep.Builder trace(Job job, TraceStep.Kind kind, String tag) {
        return new TraceStep.Builder(kind, tag);
    }

    /** Publishes a finished step to the viewer, if there is one. */
    private void traceDone(Job job, TraceStep.Builder step) {
        LiveBroadcast lb = traceOf(job);
        if (lb != LiveBroadcast.NOOP) lb.trace(step);
    }

    /**
     * The prompt as a person needs to read it: every message, with the role, so a wrong instruction or a missing
     * block list is visible instead of guessed at.
     */
    private static String renderMessages(List<ChatMessage> msgs) {
        StringBuilder b = new StringBuilder();
        for (ChatMessage m : msgs) {
            b.append("[").append(m.role()).append("]");
            if (m.hasImages()) b.append(" +").append(m.imageCount()).append(" image(s)");
            b.append("\n").append(m.content()).append("\n\n");
        }
        return b.toString();
    }

    /** One-line description of a model answer: enough to recognise it in a list of a hundred. */
    private static String summarise(String answer, String unit, long ms) {
        String flat = answer == null ? "" : answer.replaceAll("\\s+", " ").strip();
        String head = flat.length() <= 160 ? flat : flat.substring(0, 160) + "…";
        return head + (unit == null ? "" : "  · " + answer.length() + " " + unit) + " · " + ms + " ms";
    }

    /** Reports a pipeline stage to the live page. */
    private void stage(Job job, String stage, String title, double progress) {
        LiveBroadcast lb = live(job);
        if (lb != LiveBroadcast.NOOP) lb.stage(stage, title, progress);
    }

    /**
     * Coarse stage labels for the page's timeline. The pipeline still sets its own Russian status lines for the
     * boss bar; these are short, stable English ids the page can group on (stage id + human title).
     */
    private void stageOf(Job job, String stage, String title) {
        stage(job, stage, title, job.progress);
    }

    /** The live session of a running job, or {@link LiveBroadcast#NOOP} when the build has no viewer. */
    private LiveBroadcast live(Job job) {
        LiveSession.Registry r = liveLinks;
        if (r == null || job == null || job.quiet) return LiveBroadcast.NOOP;
        LiveSession s = r.forBuild(job.buildId);
        return s == null ? LiveBroadcast.NOOP : s;
    }

    public Job jobFor(String buildId) {
        return jobsByBuild.get(buildId);
    }

    public Job jobOf(UUID owner) {
        for (Job j : jobsByBuild.values()) if (java.util.Objects.equals(j.owner, owner)) return j;
        return null;
    }

    public void shutdown() {
        for (Job j : jobsByBuild.values()) j.cancelled.set(true);
        pool.shutdownNow();
        side.shutdownNow();
        try {
            pool.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    // =================================================================== public entry points (main thread)

    public Job startCreate(Build build, String prompt, Anchor anchor, BuildMode mode) {
        Job job = new Job(build.id, build.owner, Job.Kind.CREATE, prompt, mode);
        jobsByBuild.put(build.id, job);
        // The link comes first: the "queued" stage is published through the session, and resolving it before
        // the old session is superseded would write this job's first event into the previous run's page.
        openLiveLink(job, build);
        stage(job, "queued", "Job queued", 0);
        job.future = pool.submit(() -> runGuarded(job, build, () -> runCreate(job, build, prompt, anchor, false)));
        return job;
    }

    public Job startEdit(Build build, String change, BuildMode mode) {
        Job job = new Job(build.id, build.owner, Job.Kind.EDIT, change, mode);
        jobsByBuild.put(build.id, job);
        openLiveLink(job, build);
        stage(job, "queued", "Edit queued", 0);
        job.future = pool.submit(() -> runGuarded(job, build, () -> runEdit(job, build, change)));
        return job;
    }

    /**
     * Issues the one-time link a player can open to watch this job. The link is announced only to the owner
     * (and to the console, which has nobody else to tell), because it is an unauthenticated URL: whoever holds
     * it sees the plot. Quiet jobs (benchmarks) get no link.
     */
    private void openLiveLink(Job job, Build build) {
        LiveSession.Registry r = liveLinks;
        if (r == null || job.quiet) return;
        try {
            LiveSession session = r.open(job, build == null ? "Build" : build.name,
                    build == null || build.ownerName == null ? "console" : build.ownerName, info);
            job.live = session;
            String url = liveUrl(session);
            if (url != null) {
                sayLink(job, "<gray>Смотреть в браузере (одна ссылка на эту постройку, живёт до её конца): </gray>", url);
                Msg.toLink(job.owner, "<gray>Прямая ссылка: </gray>", url);
            } else {
                // Never the token itself: this log line lands in logs/latest.log and in pasted bug reports, and
                // the token is the only thing protecting the page. The prefix identifies the session for
                // troubleshooting; the operator can get the full link from /aib watch while the job runs.
                plugin.getLogger().info("Live view for build " + job.buildId + " opened (token " + session.tokenPrefix()
                        + "…) — set generation.live-view.public-url to give players a clickable link");
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "live view could not be opened for build " + job.buildId, e);
        }
    }

    /** The URL to show a person, or null when the link would not work from their machine. */
    private String liveUrl(LiveSession session) {
        java.util.function.Function<LiveSession, String> r = liveUrlResolver;
        if (r == null) return null;
        try {
            String url = r.apply(session);
            return url == null || url.isBlank() ? null : url;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private interface Task {
        void run() throws Exception;
    }

    private void runGuarded(Job job, Build build, Task task) {
        BuildMetadata previous = null;
        String outcome = "done";
        try {
            previous = sync(() -> new BuildMetadata(build.name, build.originalPrompt,
                    build.plan == null ? null : build.plan.deepCopy()));
            task.run();
        } catch (VerificationRejectedException e) {
            outcome = "rejected";
            restoreCurrent(build, previous);
            say(job, "<yellow>Правка <white><v0></white> не прошла проверку. Прежняя версия восстановлена; черновик сохранён. <v1></yellow>",
                    build.id, clip(e.getMessage(),180));
        } catch (CancelledException | InterruptedException e) {
            outcome = "cancelled";
            say(job, "<yellow>Задача для постройки <white><v0></white> отменена.</yellow>", build.id);
            restoreCurrent(build, previous);
        } catch (Exception e) {
            if (job.cancelled.get()) {
                outcome = "cancelled";
                say(job, "<yellow>Задача для постройки <white><v0></white> отменена.</yellow>", build.id);
            } else {
                outcome = "failed";
                plugin.getLogger().log(Level.WARNING, "AI job failed for build " + build.id, e);
                say(job, "<red>Ошибка ИИ: <v0></red>", shortError(e));
            }
            restoreCurrent(build, previous);
        } finally {
            job.finished = true;
            jobsByBuild.remove(build.id, job);
            live(job).finish(outcome, job.status);
        }
    }

    /** After a failure/cancel, put the world back to the last committed version. */
    private record BuildMetadata(String name, String originalPrompt, JsonObject plan) { }

    private static final class VerificationRejectedException extends Exception {
        VerificationRejectedException(String message) { super(message); }
    }

    private void restoreCurrent(Build build, BuildMetadata previous) {
        try {
            sync(() -> {
                if (previous != null) {
                    build.name = previous.name(); build.originalPrompt = previous.originalPrompt(); build.plan = previous.plan();
                }
                return null;
            });
            VoxelModel cur = sync(build::currentModel);
            if (build.placed || cur != null) placer.place(build, cur, p -> { }).get(10,TimeUnit.MINUTES);
            sync(() -> {
                if (build.versions.isEmpty()) {
                    store.delete(build.id);
                } else {
                    store.save(build);
                }
                return null;
            });
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "restore failed for " + build.id, e);
        }
    }

    // =================================================================== create

    /** Result of planning + staged construction (shared by creation and the review benchmark). */
    static final class Staged {
        JsonObject plan;
        String planText;
        VoxelModel model;
        int[] limits;
    }

    /** Builds the prompt for one stage / edit step. */
    private interface StagePrompt {
        String make(int index, int total, String name, String goal, String render, String analysis, String parallelNote);
    }

    private void runCreate(Job job, Build build, String prompt, Anchor anchor, boolean isRebuild) throws Exception {
        PluginConfig c = cfg.get();
        Transcript tr = new Transcript(build.id, c.saveTranscripts);
        tr.liveJob = job;
        Staged st = switch (c.createStrategy) {
            case "script" -> designAndScript(job, tr, prompt, build, anchor, isRebuild);
            case "compiled" -> designAndCompile(job, tr, prompt, build, anchor, isRebuild);
            default -> designAndStage(job, tr, prompt, build, anchor, isRebuild);
        };
        Verdict v;
        if (job.script != null) {
            v = verifyScript(job, tr, build, st, prompt);
        } else {
            OpExecutor exec = new OpExecutor(info).withLimits(st.limits);
            String strategy = c.reviewStrategy;
            int cap = "adaptive".equals(strategy) ? c.adaptiveMaxRounds : job.mode.verifyRounds;
            v = verify(job, tr, exec, st.model, st.plan, st.planText, prompt, null, Checklist.forCreate(st.plan),
                    strategy, cap, 0.68);
        }
        commit(job, build, st.model, isRebuild ? "Пересоздано: " + prompt : prompt, v, tr);
    }

    /**
     * Script strategy: the model writes one JavaScript build program, the sandboxed runtime executes it, and
     * failures go back to the model as a traceback (up to {@code script.repair-attempts} calls). The plot is
     * derived from what the script actually built. Falls back to the JSON-op program when the script cannot be
     * repaired and {@code script.fallback-to-ops} is on.
     */
    private Staged designAndScript(Job job, Transcript tr, String prompt, Build build, Anchor anchor,
                                   boolean isRebuild) throws Exception {
        PluginConfig c = cfg.get();
        job.status("ИИ пишет программу постройки…", 0.02);
        stageOf(job, "program", "Writing the build program");
        int ext = c.maxExtent;
        int[] safety = {-ext, -32, -ext, ext, Math.min(320, ext * 2), ext};
        ScriptRuntime runtime = new ScriptRuntime(info, job.stdlib);
        ScriptRuntime.Budget budget = BuildMode.EXTRA.equals(job.mode.id) ? ScriptRuntime.Budget.extra() : ScriptRuntime.Budget.standard();
        long seed = build == null ? 1L : build.id.hashCode();
        int n = Math.max(1, c.scriptCandidates(job.mode.id));
        List<Concepts.Concept> concepts = concepts(job, tr, prompt, runtime, n);
        List<Future<ScriptCandidate>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            long candidateSeed = seed + i * 7919L;
            String tag = n == 1 ? "script" : "script-c" + (i + 1);
            Consumer<ScriptResult> show = i == 0 ? streamingPreview(job, build, anchor) : null;
            // fewer concepts than candidates: the rest get the plain request
            String concept = i < concepts.size() ? concepts.get(i).forPrompt() : null;
            futures.add(side.submit(() -> writeScript(job, tr, prompt, concept, candidateSeed, tag, runtime, budget, safety, show)));
        }
        if (n > 1) say(job, "<gray>ИИ пишет <v0> независимых варианта программы, лучший выберет проверка.</gray>", n);
        List<ScriptCandidate> ok = new ArrayList<>();
        Exception failure = null;
        for (Future<ScriptCandidate> f : futures) {
            try {
                ScriptCandidate cand = await(f, job);
                if (cand != null) ok.add(cand);
            } catch (CancelledException e) {
                throw e;
            } catch (Exception e) {
                // one failed candidate (network, budget) must not sink the others
                plugin.getLogger().log(Level.WARNING, "script candidate failed", e);
                failure = e;
            }
        }
        if (ok.isEmpty() && failure != null && !c.scriptFallback) throw failure;
        if (ok.isEmpty()) {
            if (c.scriptFallback) {
                say(job, "<yellow>Программа не заработала, строим классическим способом.</yellow>");
                return designAndCompile(job, tr, prompt, build, anchor, isRebuild);
            }
            throw new IllegalStateException("программа постройки не выполнилась");
        }
        ScriptCandidate best = pickCandidate(job, tr, prompt, ok);
        job.concept = best.concept();
        ScriptResult result = best.result();
        String source = best.source();
        seed = best.seed();
        VoxelModel model = result.model();
        JsonObject plan = planFromScript(result, prompt);
        int[] plot = plotBounds(plan);
        sync(() -> {
            if (build != null) {
                build.name = Json.str(plan, "title", "Постройка");
                build.plan = plan;
                if (build.originalPrompt == null || isRebuild) build.originalPrompt = prompt;
                if (anchor != null && build.versions.isEmpty() && (!build.placed || job.liveAnchored)) {
                    // re-anchor on the real plot; keep the ground height measured before any preview blocks stood
                    int gy = build.originY;
                    place(build, anchor, plot);
                    if (job.liveAnchored && anchor.y() == null) build.originY = gy;
                }
                store.save(build);
            }
            return null;
        });
        job.plotLimits = new int[]{plot[0] - 3, plot[1] - 2, plot[2] - 3, plot[3] + 3, plot[4] + 2, plot[5] + 3};
        job.script = source;
        job.scriptSeed = seed;
        job.scriptParts = result.partTable(30);
        job.smoothed = result.smoothed();
        job.doorways.addAll(result.doorways());
        tr.note("script result", "parts:\n" + result.partTable(40) + "\nresolver: " + postProcess(job, model));
        job.scriptModel = model.copy();
        liveParts(job, result);
        // "script result" rather than "final": commit() emits the final model later, and two rows with the same
        // title made it look like the trace was duplicating entries.
        liveModel(job, build, model, "script result");
        say(job, "<gray>Программа выполнена: <white><v0></white>, <v1> блоков, частей: <v2>. Проверяем постройку.</gray>",
                Json.str(plan, "title", "Постройка"), model.nonAirCount(), result.parts().size());
        Staged st = new Staged();
        st.plan = plan;
        st.planText = Json.GSON.toJson(planForPrompt(plan)) + "\nPARTS (from the build script):\n" + result.partTable(30);
        st.model = model;
        st.limits = job.plotLimits;
        return st;
    }

    /** A script that ran successfully, with its brief, analysis and the design concept it followed (or null). */
    private record ScriptCandidate(String source, ScriptResult result, long seed, JsonObject plan, AnalysisReport report,
                                   String concept) {
        int defects() {
            return report.count(dev.aibuild.analysis.Severity.CRITICAL) * 10 + report.count(dev.aibuild.analysis.Severity.MAJOR);
        }
    }

    /**
     * The lead designer's concepts for {@code n} candidates (3.0 library only, when enabled): one cheap call before
     * the scripts. Empty when off or when the answer is unusable, so the candidates get the plain request.
     */
    private List<Concepts.Concept> concepts(Job job, Transcript tr, String prompt, ScriptRuntime runtime, int n) throws CancelledException {
        PluginConfig c = cfg.get();
        boolean on = job.concepts != null ? job.concepts : c.concepts(job.mode.id);
        if (!on || runtime.stdlib() == dev.aibuild.script.Stdlib.V1) return List.of();
        job.status("ИИ продумывает замысел постройки…", 0.01);
        stageOf(job, "concept", "Designing a concept");
        try {
            String answer = callRaw(job, tr, "concepts", List.of(
                    ChatMessage.system(ScriptPrompts.system(runtime.stdlib(), c.includeBlockList ? blockList.get() : null)),
                    ChatMessage.user(ScriptPrompts.concepts(runtime.stdlib(), prompt, scaleHint(job.mode, runtime.stdlib()), n))),
                    conceptEffort(job), job.mode.maxTokens, true);
            List<Concepts.Concept> list = Concepts.parse(answer, n);
            tr.note("concepts", list.isEmpty() ? "unreadable:\n" + answer
                    : String.join("\n\n", list.stream().map(Concepts.Concept::forPrompt).toList()));
            if (!list.isEmpty()) {
                say(job, "<gray>Замысел: <white><v0></white>.</gray>", String.join(" / ", list.stream().map(Concepts.Concept::name).toList()));
            }
            return list;
        } catch (CancelledException e) {
            throw e;
        } catch (Exception e) {
            // the concepts only steer the candidates; without them the build goes on from the plain request
            plugin.getLogger().log(Level.WARNING, "concept call failed", e);
            tr.note("concepts", "failed: " + e);
            return List.of();
        }
    }

    /** The concept call is the plan of a script build: the mode's plan effort when it thinks, else script.concept-effort. */
    private String conceptEffort(Job job) {
        String plan = job.mode.effortPlan;
        return plan != null && !plan.equals("off") ? plan : cfg.get().conceptEffort;
    }

    /** Writes and runs one script, feeding failures back as tracebacks; null when it never runs. */
    private ScriptCandidate writeScript(Job job, Transcript tr, String prompt, String concept, long seed, String tag, ScriptRuntime runtime,
                                        ScriptRuntime.Budget budget, int[] safety, Consumer<ScriptResult> show) throws Exception {
        PluginConfig c = cfg.get();
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.system(ScriptPrompts.system(runtime.stdlib(), c.includeBlockList ? blockList.get() : null)));
        msgs.add(ChatMessage.user(ScriptPrompts.create(runtime.stdlib(), prompt, scaleHint(job.mode, runtime.stdlib()), concept)));
        for (int attempt = 0; attempt <= c.scriptRepairAttempts; attempt++) {
            LiveScript live = new LiveScript(runtime, seed, safety, budget, job.cancelled, side,
                    partial -> {
                        prepare(partial);
                        if (show != null) show.accept(partial);
                        // The page always sees the partial build, whatever generation.live-preview says: that
                        // switch governs the in-world preview, and a viewer who opened the live link still
                        // expects to watch the AI's program take shape.
                        LiveBroadcast lb = live(job);
                        if (lb != LiveBroadcast.NOOP) {
                            lb.preview(true, partial.model().nonAirCount(), partial.model().size());
                            lb.model(partial.model(), "stream");
                        }
                    },
                    part -> {
                        job.status("ИИ пишет часть «" + clip(part, 40) + "»…", job.progress);
                        LiveSession s = liveLinks == null ? null : liveLinks.forBuild(job.buildId);
                        if (s != null) s.part(part);
                    });
            live.codeKind(attempt == 0 ? "program" : "repair" + attempt).broadcast(live(job));
            String answer;
            try {
                answer = callText(job, tr, attempt == 0 ? tag : tag + "-repair" + attempt, job.mode.effortBuild, msgs, live);
            } finally {
                live.close();
            }
            String source = ScriptLint.extract(answer);
            job.status("Выполнение программы постройки…", 0.05 + attempt * 0.02);
            ScriptResult result = runtime.run(source, seed, null, safety, budget, job.cancelled);
            tr.note(tag + "-run-" + attempt, "ok=" + result.ok() + " blocks=" + result.model().nonAirCount() + " ms=" + result.elapsedMs()
                    + " instructions=" + result.instructions() + "\nwarnings: " + result.warnings() + "\nlogs: " + result.logs()
                    + (result.ok() ? "\nparts:\n" + result.partTable(40) : "\n" + result.error().describe()));
            tr.note(tag + "-" + attempt + ".js", source);
            // The program and the run are the two things a person debugging a bad build needs side by side:
            // what the AI wrote, and exactly how the sandbox rejected or accepted it.
            traceDone(job, trace(job, TraceStep.Kind.SCRIPT, tag + "-run-" + attempt)
                    .title("Script run · " + tag + (attempt > 0 ? " (attempt " + (attempt + 1) + ")" : ""))
                    .ok(result.ok())
                    .took(result.elapsedMs())
                    .summary(result.ok()
                            ? result.model().nonAirCount() + " blocks · " + result.instructions() + " instructions"
                            : "failed: " + clip(result.error().message(), 140))
                    .field("source", source)
                    .field("ok", result.ok())
                    .field("blocks", result.model().nonAirCount())
                    .field("instructions", result.instructions())
                    .field("elapsed ms", result.elapsedMs())
                    .field("warnings", result.warnings())
                    .field("logs", result.logs())
                    .field("parts", result.ok() ? result.partTable(40) : null)
                    .field("error", result.ok() ? null : result.error().describe()));
            if (result.ok()) {
                prepare(result);
                if (show != null) show.accept(result);
                JsonObject plan = planFromScript(result, prompt);
                return new ScriptCandidate(source, result, seed, plan,
                        StructureAnalyzer.analyze(result.model(), info, analyzerOptions(plan)), concept);
            }
            checkCancelled(job);
            // The page shows a failed run too: a viewer watching the program stop needs to know it stopped,
            // and the event carries `ok` precisely so the two outcomes are distinguishable.
            LiveBroadcast failed = live(job);
            if (failed != LiveBroadcast.NOOP) failed.preview(false, result.model().nonAirCount(), source.length());
            plugin.getLogger().info("[" + tag + "] attempt " + attempt + " failed: " + result.error().message());
            if (attempt < c.scriptRepairAttempts) {
                say(job, "<gray>Программа остановилась: <white><v0></white>. ИИ исправляет…</gray>", clip(result.error().message(), 160));
                msgs.add(ChatMessage.assistant("```js\n" + source + "```"));
                msgs.add(ChatMessage.user(ScriptPrompts.repair(result.error(), attempt + 1)));
            }
        }
        return null;
    }

    /**
     * Shows a prepared script result in the world while candidates are still being written (null when there is no
     * world build or live preview is off). The first preview positions the build; later ones only replace blocks.
     */
    private Consumer<ScriptResult> streamingPreview(Job job, Build build, Anchor anchor) {
        if (build == null || !cfg.get().livePreview) return null;
        return result -> {
            VoxelModel m = result.model().copy();
            try {
                if (!job.liveAnchored) {
                    int[] plot = livePlot(m);
                    sync(() -> {
                        if (anchor != null && !build.placed && build.versions.isEmpty()) {
                            place(build, anchor, plot);
                            job.liveAnchored = true;
                        }
                        return null;
                    });
                }
                if (build.placed || job.liveAnchored) livePreview(build, m);
                LiveBroadcast lb = live(job);
                if (lb != LiveBroadcast.NOOP) lb.model(m, "stream");
            } catch (Exception e) {
                plugin.getLogger().log(Level.FINE, "streaming preview failed", e);
            }
        };
    }

    /**
     * Plot for positioning a build from its first streamed parts. Scripts usually build around x = z = 0 and
     * grow outwards, so the front edge is mirrored from the back and padded; the final plot re-anchors anyway.
     */
    static int[] livePlot(VoxelModel m) {
        int[] b = m.solidBounds();
        if (b == null) return new int[]{-4, 0, -4, 4, 8, 4};
        int front = Math.max(b[5], -b[2]) + 3;
        return new int[]{b[0], b[1], b[2], b[3], b[4], front};
    }

    /** Deterministic post-processing of a script result in place (doorways, orientation, connections). */
    private void prepare(ScriptResult result) {
        VoxelModel m = result.model();
        for (var d : result.doorways()) d.restore(m, info);
        if (cfg.get().autoOrient) dev.aibuild.model.OrientationFixer.fix(m, info, result.smoothed());
        ShapeResolver.resolve(m, info);
    }

    /** Fewest analyzer defects first; among equals, the vision judge decides when the gateway delivers images. */
    private ScriptCandidate pickCandidate(Job job, Transcript tr, String prompt, List<ScriptCandidate> list) throws Exception {
        List<ScriptCandidate> sorted = new ArrayList<>(list);
        sorted.sort(java.util.Comparator.comparingInt(ScriptCandidate::defects));
        int minCritical = sorted.getFirst().report().count(dev.aibuild.analysis.Severity.CRITICAL);
        sorted.removeIf(cand -> cand.report().count(dev.aibuild.analysis.Severity.CRITICAL) > minCritical);
        StringBuilder log = new StringBuilder();
        for (ScriptCandidate cand : list) {
            log.append("seed ").append(cand.seed()).append(": blocks=").append(cand.result().model().nonAirCount())
                    .append(" defects=").append(cand.defects()).append(" parts=").append(cand.result().parts().size()).append('\n');
        }
        ScriptCandidate best = sorted.getFirst();
        if (sorted.size() > 1 && cfg.get().visionJudge && vision(job, tr)) {
            job.status("Сравнение вариантов по изображениям…", 0.4);
            for (int i = 1; i < sorted.size(); i++) {
                ScriptCandidate other = sorted.get(i);
                int pref = judge(job, tr, prompt, sheet(best.result().model()), sheet(other.result().model()));
                log.append("judge seed ").append(best.seed()).append(" vs ").append(other.seed()).append(": ").append(pref).append('\n');
                if (pref > 0) best = other;
            }
        }
        tr.note("candidates", log + "chosen seed " + best.seed());
        if (list.size() > 1) {
            say(job, "<gray>Выбран вариант <v0> из <v1>.</gray>", list.indexOf(best) + 1, list.size());
        }
        return best;
    }

    // =================================================================== build scripts: vision and critic

    private final java.util.concurrent.atomic.AtomicReference<Boolean> visionOk = new java.util.concurrent.atomic.AtomicReference<>();

    /** Whether images reach the model: checked once per session with a rendered random number. */
    private boolean vision(Job job, Transcript tr) {
        PluginConfig c = cfg.get();
        if (!c.visionEnabled) return false;
        Boolean known = visionOk.get();
        if (known != null) return known;
        synchronized (visionOk) {
            if (visionOk.get() != null) return visionOk.get();
            String digits = String.valueOf(100 + java.util.concurrent.ThreadLocalRandom.current().nextInt(900));
            boolean ok;
            try {
                String answer = callRaw(job, tr, "vision-probe", List.of(ChatMessage.user(ScriptPrompts.visionProbe(),
                        List.of(ContactSheet.digits(digits)))), "off", 400, false);
                ok = answer.replaceAll("\\D", "").contains(digits);
            } catch (Exception e) {
                plugin.getLogger().log(Level.FINE, "vision probe failed", e);
                ok = false;
            }
            visionOk.set(ok);
            plugin.getLogger().info(ok ? "Vision probe passed: the model receives rendered images"
                    : "Vision probe failed: images do not reach the model; visual checks are disabled for this session");
            return ok;
        }
    }

    private byte[] sheet(VoxelModel model) {
        return ContactSheet.of(model, info, cfg.get().visionImageSize);
    }

    /**
     * Pairwise judgement: -1 when A is better, +1 when B is better, 0 for a tie. Both orders are asked in
     * parallel; disagreement (position bias) counts as a tie.
     */
    private int judge(Job job, Transcript tr, String request, byte[] a, byte[] b) throws Exception {
        String effort = cfg.get().visionJudgeEffort;
        Future<String> ab = side.submit(() -> callRaw(job, tr, "judge-ab", List.of(ChatMessage.system(ScriptPrompts.JUDGE_SYSTEM),
                ChatMessage.user(ScriptPrompts.judge(request), List.of(a, b))), effort, 16000, true));
        Future<String> ba = side.submit(() -> callRaw(job, tr, "judge-ba", List.of(ChatMessage.system(ScriptPrompts.JUDGE_SYSTEM),
                ChatMessage.user(ScriptPrompts.judge(request), List.of(b, a))), effort, 16000, true));
        int first = winner(await(ab, job));
        int second = -winner(await(ba, job));
        return first == second ? first : 0;
    }

    /** -1 for "A", +1 for "B", 0 for a tie or an unreadable answer. */
    static int winner(String answer) {
        try {
            JsonElement el = Json.extract(answer).value();
            String w = el.isJsonObject() ? Json.str(el.getAsJsonObject(), "winner", "tie").trim().toUpperCase(Locale.ROOT) : "";
            return w.equals("A") ? -1 : w.equals("B") ? 1 : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** One plain call without retries (probes, judges). */
    private String callRaw(Job job, Transcript tr, String tag, List<ChatMessage> msgs, String effort, int maxTokens, boolean json) throws Exception {
        PluginConfig c = cfg.get();
        checkCancelled(job);
        job.calls.incrementAndGet();
        ChatResult res;
        try {
            res = llm.get().chat(new ChatRequest(c.model, msgs, maxTokens, c.temperature, effort, json), (cc, rc) -> { }, job.cancelled);
        } catch (LlmException e) {
            if ("cancelled".equals(e.code()) || job.cancelled.get()) throw new CancelledException();
            throw e;
        }
        job.promptTokens.addAndGet(res.promptTokens());
        job.completionTokens.addAndGet(res.completionTokens());
        job.cachedTokens.addAndGet(res.cachedTokens());
        job.reasoningTokens.addAndGet(res.reasoningTokens());
        tr.exchange(tag + "-" + effort, msgs.getLast().content() + (msgs.getLast().hasImages() ? "\n[" + msgs.getLast().imageCount() + " image(s)]" : ""), res);
        return res.content() == null ? "" : res.content();
    }

    /** A script revision that applied and ran. */
    private record Revision(String source, ScriptResult result, String diagnosis) {
    }

    /** Applies the model's hunks and runs the script; one repair call when the patch or the run fails. */
    private Revision revise(Job job, Transcript tr, ScriptRuntime runtime, ScriptRuntime.Budget budget, long seed, int[] limits,
                            String source, String answer, List<ChatMessage> msgs, String tag, String effort) throws Exception {
        String diagnosis = diagnosis(answer);
        for (int attempt = 0; attempt < 2; attempt++) {
            ScriptPatch.Result patch = ScriptPatch.apply(source, answer);
            String problem;
            if (patch.ok()) {
                ScriptResult result = runtime.run(patch.source(), seed, null, limits, budget, job.cancelled);
                tr.note(tag + "-run-" + attempt, "hunks=" + patch.hunks() + " rewrite=" + patch.fullRewrite() + " ok=" + result.ok()
                        + " blocks=" + result.model().nonAirCount() + (result.ok() ? "" : "\n" + result.error().describe()));
                if (result.ok()) {
                    prepare(result);
                    return new Revision(patch.source(), result, diagnosis);
                }
                problem = "the revised script fails:\n" + result.error().describe();
            } else {
                problem = patch.error();
            }
            if (attempt == 1) break;
            msgs = new ArrayList<>(msgs);
            msgs.add(ChatMessage.assistant(answer));
            msgs.add(ChatMessage.user(ScriptPrompts.revisionFailed(problem)));
            answer = callText(job, tr, tag + "-fix", effort, msgs);
        }
        return null;
    }

    /** One sculptor's accepted rewrite of a part. */
    private record PartRewrite(String name, String statement, int crit, int maj) {
    }

    /**
     * Art director + sculptors: the director picks the parts that would gain most, sculptors rewrite one part each
     * in parallel (each sees a close-up of its part), and the rewrites are merged. Every rewrite must run and add
     * no analyzer defects on its own; the merged build must too, and the judge must not prefer the old one.
     * Returns null when nothing was accepted.
     */
    private Revision sculpt(Job job, Transcript tr, String prompt, ScriptRuntime runtime, ScriptRuntime.Budget budget,
                            int[] limits, String system, String source, VoxelModel model, AnalysisReport rep,
                            AnalyzerOptions aopts, int max) throws Exception {
        PluginConfig c = cfg.get();
        String effort = c.sculptEffort;
        ScriptResult base = runtime.run(source, job.scriptSeed, null, limits, budget, job.cancelled);
        if (!base.ok()) return null;
        Map<String, ScriptResult.Part> parts = sculptable(base.parts(), source);
        if (parts.isEmpty()) return null;
        StringBuilder table = new StringBuilder();
        parts.values().stream().sorted((a, b) -> Integer.compare(b.cells(), a.cells())).limit(40)
                .forEach(p -> table.append("- ").append(p.describe()).append('\n'));
        job.status("Арт-директор выбирает, что доработать…", 0.5);
        stageOf(job, "director", "Art director picks parts to refine");
        String evidence = withAutoNotes(job, rep.toText());
        String dirAnswer = callRaw(job, tr, "director", List.of(ChatMessage.system(system),
                ChatMessage.user(ScriptPrompts.director(prompt, job.concept, table.toString(), evidence, max, refine(job)),
                        List.of(sheet(model)))), effort, 30000, true);
        List<String[]> picks = new ArrayList<>();
        try {
            JsonObject o = JsonParser.parseString(dirAnswer.substring(dirAnswer.indexOf('{'), dirAnswer.lastIndexOf('}') + 1)).getAsJsonObject();
            for (var e : o.getAsJsonArray("parts")) {
                JsonObject p = e.getAsJsonObject();
                String name = Json.str(p, "name", "");
                if (!parts.containsKey(name) || picks.stream().anyMatch(q -> q[0].equals(name) || name.startsWith(q[0] + "/") || q[0].startsWith(name + "/"))) continue;
                picks.add(new String[]{name, Json.str(p, "brief", "")});
                if (picks.size() >= max) break;
            }
        } catch (RuntimeException e) {
            tr.note("director unreadable", dirAnswer);
        }
        if (picks.isEmpty()) {
            tr.note("director", "nothing to rework");
            return null;
        }
        say(job, "<gray>Арт-директор: дорабатываем <white><v0></white>.</gray>", String.join(", ", picks.stream().map(q -> q[0]).toList()));
        job.status("Скульпторы дорабатывают части: " + clip(String.join(", ", picks.stream().map(q -> q[0]).toList()), 60) + "…", 0.58);
        stageOf(job, "sculpt", "Sculptors refine parts");
        prepare(base);
        QualityVector q0 = QualityVector.of(rep, base);
        List<Future<PartRewrite>> futures = new ArrayList<>();
        // one stop flag per sculptor: set when the job is cancelled or the sculptors' time is up
        List<java.util.concurrent.atomic.AtomicBoolean> stops = new ArrayList<>();
        for (int i = 0; i < picks.size(); i++) {
            String name = picks.get(i)[0], brief = picks.get(i)[1], tag = "sculpt" + (i + 1);
            ScriptResult.Part part = parts.get(name);
            var stop = new java.util.concurrent.atomic.AtomicBoolean();
            stops.add(stop);
            futures.add(side.submit(() -> sculptPart(job, tr, tag, effort, prompt, system, runtime, budget, limits, source, model,
                    part, brief, aopts, base, q0, stop)));
        }
        long deadline = System.nanoTime() + c.sculptTimeout * 1_000_000_000L;
        List<PartRewrite> ok = new ArrayList<>();
        try {
            for (int i = 0; i < futures.size(); i++) {
                try {
                    PartRewrite r = awaitUntil(futures.get(i), job, deadline);
                    if (r != null) ok.add(r);
                } catch (java.util.concurrent.TimeoutException e) {
                    tr.note("sculpt" + (i + 1) + " timed out", picks.get(i)[0] + ": still working after " + c.sculptTimeout + " s, part left as it was");
                } catch (CancelledException e) {
                    if (job.cancelled.get()) throw e;
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "sculptor failed", e);
                }
            }
        } finally {
            stops.forEach(f -> f.set(true));
        }
        if (ok.isEmpty()) {
            say(job, "<gray>Доработка частей не удалась, оставляем как было.</gray>");
            return null;
        }
        // merge greedily: keep every rewrite that still runs cleanly together with the ones before it
        String merged = source;
        ScriptResult mergedResult = null;
        List<String> kept = new ArrayList<>();
        for (PartRewrite r : ok) {
            ScriptPatch.Result patch = ScriptPatch.replacePart(merged, r.name(), r.statement());
            if (!patch.ok()) continue;
            ScriptResult run = runtime.run(patch.source(), job.scriptSeed, null, limits, budget, job.cancelled);
            if (!run.ok()) continue;
            prepare(run);
            AnalysisReport nrep = StructureAnalyzer.analyze(run.model(), info, aopts);
            List<String> worse = QualityVector.of(nrep, run).regressions(q0);
            if (!worse.isEmpty()) {
                tr.note("sculpt merge dropped " + r.name(), "together with the other rewrites: " + String.join(", ", worse));
                continue;
            }
            merged = patch.source();
            mergedResult = run;
            kept.add(r.name());
        }
        if (mergedResult == null) return null;
        if (c.visionJudge) {
            int pref = judge(job, tr, prompt, sheet(model), sheet(mergedResult.model()));
            if (pref < 0) {
                tr.note("sculpt rejected", "the judge preferred the previous build; parts: " + kept);
                say(job, "<gray>Доработка частей отклонена: прежний вариант смотрится лучше.</gray>");
                return null;
            }
        }
        tr.note("sculpt accepted", "parts: " + kept);
        say(job, "<gray>Доработаны части: <white><v0></white> (<v1> блоков).</gray>", String.join(", ", kept), mergedResult.model().nonAirCount());
        return new Revision(merged, mergedResult, "sculpted " + kept);
    }

    /**
     * Parts a sculptor can rewrite, by path ("house/roof"), largest first: every part and every enclosing part (a
     * part whose blocks all sit in nested parts has none of its own, yet is often the subject), with totals over
     * its nested parts. Only parts whose name is written once in the script, so the rewrite has one place to go.
     */
    static Map<String, ScriptResult.Part> sculptable(List<ScriptResult.Part> runParts, String source) {
        Map<String, int[]> box = new java.util.LinkedHashMap<>();
        Map<String, Integer> cells = new java.util.HashMap<>();
        for (ScriptResult.Part p : runParts) {
            String[] seg = p.name().split("/");
            for (int i = 1; i <= seg.length; i++) {
                String path = String.join("/", java.util.Arrays.copyOf(seg, i));
                cells.merge(path, p.cells(), Integer::sum);
                int[] b = box.get(path);
                if (b == null) box.put(path, p.bounds().clone());
                else for (int k = 0; k < 3; k++) {
                    b[k] = Math.min(b[k], p.bounds()[k]);
                    b[k + 3] = Math.max(b[k + 3], p.bounds()[k + 3]);
                }
            }
        }
        Map<String, Integer> leaves = new java.util.HashMap<>();
        for (String path : box.keySet()) leaves.merge(leaf(path), 1, Integer::sum);
        List<String> written = dev.aibuild.script.ScriptLint.partNames(source);
        Map<String, ScriptResult.Part> out = new java.util.LinkedHashMap<>();
        box.keySet().stream().filter(path -> leaves.get(leaf(path)) == 1 && written.contains(leaf(path)))
                .sorted((a, b) -> Integer.compare(cells.get(b), cells.get(a)))
                .forEach(path -> out.put(path, new ScriptResult.Part(path, cells.get(path), box.get(path))));
        return out;
    }

    /** Whether this job's sculptors refine parts rather than redesign them. */
    private boolean refine(Job job) {
        return job.sculptRefine != null ? job.sculptRefine : cfg.get().sculptRefine;
    }

    private static String leaf(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /** One sculptor: rewrite one part, run it, one repair on failure; null unless it runs and adds no defects. */
    private PartRewrite sculptPart(Job job, Transcript tr, String tag, String effort, String prompt, String system,
                                   ScriptRuntime runtime, ScriptRuntime.Budget budget, int[] limits, String source,
                                   VoxelModel model, ScriptResult.Part part, String brief, AnalyzerOptions aopts,
                                   ScriptResult base, QualityVector q0, java.util.concurrent.atomic.AtomicBoolean stop) throws Exception {
        String name = leaf(part.name());
        String current = ScriptPatch.partSource(source, name);
        if (current == null) return null;
        byte[] img = ContactSheet.closeups(model, info, List.of(new ContactSheet.Focus(part.name(), part.bounds())), cfg.get().visionImageSize);
        String bounds = part.cells() + " blocks, x " + part.bounds()[0] + ".." + part.bounds()[3] + ", y " + part.bounds()[1] + ".."
                + part.bounds()[4] + ", z " + part.bounds()[2] + ".." + part.bounds()[5];
        List<ChatMessage> msgs = new ArrayList<>(List.of(ChatMessage.system(system),
                ChatMessage.user(ScriptPrompts.sculptor(prompt, job.concept, name, bounds, brief, current, source, true, refine(job)), List.of(img))));
        String answer = callText(job, tr, tag, effort, msgs, null, stop);
        for (int attempt = 0; attempt < 2; attempt++) {
            ScriptPatch.Result patch = ScriptPatch.replacePart(source, name, answer);
            String problem;
            if (patch.ok()) {
                ScriptResult run = runtime.run(patch.source(), job.scriptSeed, null, limits, budget, job.cancelled);
                if (run.ok()) {
                    prepare(run);
                    AnalysisReport nrep = StructureAnalyzer.analyze(run.model(), info, aopts);
                    int crit = nrep.count(dev.aibuild.analysis.Severity.CRITICAL), maj = nrep.count(dev.aibuild.analysis.Severity.MAJOR);
                    QualityVector nq = QualityVector.of(nrep, run);
                    List<String> worse = nq.regressions(q0);
                    int lost = foreignLoss(base, run, part.name(), info);
                    tr.note(tag + "-run-" + attempt, "ok blocks=" + run.model().nonAirCount() + " critical=" + crit + " major=" + maj
                            + " other parts lost " + lost + "\n" + nq.describe());
                    if (worse.isEmpty() && lost <= FOREIGN_LOSS_MAX) {
                        return new PartRewrite(name, ScriptPatch.partSource(patch.source(), name), crit, maj);
                    }
                    problem = lost > FOREIGN_LOSS_MAX ? "your rewrite removes or covers " + lost + " blocks that OTHER parts built (their"
                            + " walls, floors, stairs). Those cells are locked: change only your own part's blocks and leave the"
                            + " neighbours' cells as they are; use partBox(name) to see where a neighbour stands."
                            : "the rewrite makes the build worse: " + String.join(", ", worse) + "\n"
                            + (run.collisionText().isEmpty() ? "" : "Collisions:\n" + run.collisionText()) + clip(nrep.toText(), 1500);
                } else {
                    problem = "the script with your rewrite fails:\n" + run.error().describe();
                }
            } else {
                problem = patch.error();
            }
            tr.note(tag + "-problem-" + attempt, problem);
            if (attempt == 1) break;
            msgs = new ArrayList<>(msgs);
            msgs.add(ChatMessage.assistant(answer));
            msgs.add(ChatMessage.user(ScriptPrompts.revisionFailed(problem)));
            answer = callText(job, tr, tag + "-fix", effort, msgs, null, stop);
        }
        return null;
    }

    /** How many cells of other parts a part's rewrite may empty before it counts as breaking them. */
    static final int FOREIGN_LOSS_MAX = 12;

    /**
     * Walls, floors, stairs and doors other parts built in {@code before} that are empty, or covered with loose
     * decoration, in {@code after}: what a rewrite of {@code part} broke around it. Plants and trees giving way to
     * a moved wall are not breakage.
     */
    static int foreignLoss(ScriptResult before, ScriptResult after, String part, BlockInfo info) {
        int n = 0;
        for (Map.Entry<Long, String> e : before.owners().entrySet()) {
            String o = e.getValue();
            if (o.equals(part) || o.startsWith(part + "/") || part.startsWith(o + "/")) continue;
            long k = e.getKey();
            BlockSpec was = before.model().get(VoxelModel.kx(k), VoxelModel.ky(k), VoxelModel.kz(k));
            BlockSpec now = after.model().get(VoxelModel.kx(k), VoxelModel.ky(k), VoxelModel.kz(k));
            if (was == null || was.isAir() || !dev.aibuild.model.BlockRoles.structural(was, info) && !dev.aibuild.model.BlockRoles.access(was)
                    || was.name().endsWith("_leaves") || was.name().endsWith("_log") || was.name().endsWith("_wood")) continue;
            if (now == null || now.isAir() || dev.aibuild.model.BlockRoles.decor(now, info) && !dev.aibuild.model.BlockRoles.decor(was, info)) n++;
        }
        return n;
    }

    /** Up to four rooms seen from above with everything higher removed, largest first. */
    private byte[] interiorSheet(VoxelModel model, List<AnalysisReport.Room> rooms) {
        List<int[]> boxes = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (AnalysisReport.Room r : rooms.stream().limit(4).toList()) {
            boxes.add(new int[]{r.x1(), r.z1(), r.x2(), r.z2(), r.y()});
            labels.add("room y=" + r.y() + " x " + r.x1() + ".." + r.x2() + " z " + r.z1() + ".." + r.z2());
        }
        return ContactSheet.interiors(model, info, boxes, labels, cfg.get().visionImageSize);
    }

    /** The script's own interior part (interior, interiors, furniture, rooms...), or null when it has none. */
    static String interiorPart(String source) {
        for (String n : dev.aibuild.script.ScriptLint.partNames(source)) {
            if (dev.aibuild.script.Canvas.interiorName(n)) return n;
        }
        return null;
    }

    /**
     * Cells that differ between two models outside every room box (the room's floor cells, from its floor layer up
     * to 8 above): an interior rewrite must change nothing there. The ring of cells around a box counts as inside for
     * furniture, since a bed or shelf against the wall hides the floor it stands on; walls, windows and doors there
     * stay protected.
     */
    public static int changesOutsideRooms(VoxelModel before, VoxelModel after, List<AnalysisReport.Room> rooms, BlockInfo info) {
        return changesOutsideRooms(before, after, rooms, info, new ArrayList<>(), new int[1]);
    }

    /**
     * As above, with up to six of the changed cells described into {@code samples} and how many of them touch a
     * wall, floor, window or door (not just loose decoration) in {@code shell[0]}.
     */
    static int changesOutsideRooms(VoxelModel before, VoxelModel after, List<AnalysisReport.Room> rooms, BlockInfo info,
                                   List<String> samples, int[] shell) {
        int[] n = {0};
        VoxelModel.CellConsumer check = (x, y, z, s) -> {
            BlockSpec a = before.get(x, y, z), b = after.get(x, y, z);
            // by block type: a pane or fence in the wall reconnects to furniture placed beside it
            boolean same = a == null || a.isAir() ? b == null || b.isAir() : b != null && a.name().equals(b.name());
            if (same) return;
            boolean furniture = !dev.aibuild.model.BlockRoles.structural(a, info) && !dev.aibuild.model.BlockRoles.structural(b, info);
            for (AnalysisReport.Room r : rooms) {
                int ring = furniture ? 1 : 0;
                if (x >= r.x1() - ring && x <= r.x2() + ring && z >= r.z1() - ring && z <= r.z2() + ring
                        && y >= r.y() - 1 && y <= r.y() + 8) return;
            }
            if (!furniture) shell[0]++;
            if (n[0]++ < 6) samples.add("(" + x + "," + y + "," + z + ") " + (a == null || a.isAir() ? "air" : a.name()) + " -> "
                    + (b == null || b.isAir() ? "air" : b.name()));
        };
        before.forEach(check);
        after.forEach((x, y, z, s) -> {
            BlockSpec a = before.get(x, y, z);
            if (a == null) check.accept(x, y, z, s);
        });
        return n[0];
    }

    /** Full blocks that are furniture inside a room rather than part of its walls. */
    /**
     * Interior designer: one call that furnishes every room found inside the build, seeing the rooms from above.
     * Its statement replaces the script's interior part or is appended. Kept only when it runs, changes nothing
     * outside the rooms and adds no analyzer defects; one repair on failure. Null when nothing usable came back.
     */
    private PartRewrite designInterior(Job job, Transcript tr, String prompt, String system, ScriptRuntime runtime,
                                       ScriptRuntime.Budget budget, int[] limits, String source, VoxelModel model,
                                       AnalysisReport rep, AnalyzerOptions aopts, boolean eyes, QualityVector q0,
                                       java.util.concurrent.atomic.AtomicBoolean stop) throws Exception {
        List<AnalysisReport.Room> rooms = rep.roomList.stream().limit(10).toList();
        String target = interiorPart(source), name = target != null ? target : "interiors";
        StringBuilder list = new StringBuilder();
        for (AnalysisReport.Room r : rooms) list.append("- ").append(r.describe()).append('\n');
        String user = ScriptPrompts.interior(prompt, job.concept, list.toString(), name,
                target == null ? null : ScriptPatch.partSource(source, target), source, eyes);
        List<ChatMessage> msgs = new ArrayList<>(List.of(ChatMessage.system(system),
                eyes ? ChatMessage.user(user, List.of(interiorSheet(model, rooms))) : ChatMessage.user(user)));
        String answer = callText(job, tr, "interior", cfg.get().sculptEffort, msgs, null, stop);
        for (int attempt = 0; attempt < 2; attempt++) {
            ScriptPatch.Result patch = ScriptPatch.putPart(source, name, answer);
            String problem;
            if (patch.ok()) {
                ScriptResult run = runtime.run(patch.source(), job.scriptSeed, null, limits, budget, job.cancelled);
                if (run.ok()) {
                    prepare(run);
                    AnalysisReport nrep = StructureAnalyzer.analyze(run.model(), info, aopts);
                    int crit = nrep.count(dev.aibuild.analysis.Severity.CRITICAL), maj = nrep.count(dev.aibuild.analysis.Severity.MAJOR);
                    List<String> outsideCells = new ArrayList<>();
                    int[] shell = new int[1];
                    int outside = changesOutsideRooms(model, run.model(), rooms, info, outsideCells, shell);
                    // a few chandelier chains or lanterns above a room box are not the building
                    if (shell[0] == 0 && outside <= 12) outside = 0;
                    QualityVector nq = QualityVector.of(nrep, run);
                    List<String> worse = nq.regressions(q0);
                    tr.note("interior-run-" + attempt, "ok blocks=" + run.model().nonAirCount() + " critical=" + crit + " major=" + maj
                            + " outside=" + outside + "\n" + nq.describe());
                    if (worse.isEmpty() && outside == 0) {
                        return new PartRewrite(name, ScriptPatch.partSource(patch.source(), name), crit, maj);
                    }
                    problem = outside > 0 ? "your statement changes " + outside + " blocks outside the rooms (walls, roofs, floors or"
                            + " the outside), e.g. " + String.join(", ", outsideCells) + "; place blocks only inside the rooms: loop over rooms() and use furnish(room, type),"
                            + " onWall() and hangFrom() instead of typed coordinates"
                            : "it makes the build worse: " + String.join(", ", worse) + "\n"
                            + (run.collisionText().isEmpty() ? "" : "Collisions:\n" + run.collisionText()) + clip(nrep.toText(), 1500);
                } else {
                    problem = "the script with your statement fails:\n" + run.error().describe();
                }
            } else {
                problem = patch.error();
            }
            tr.note("interior-problem-" + attempt, problem);
            if (attempt == 1) break;
            msgs = new ArrayList<>(msgs);
            msgs.add(ChatMessage.assistant(answer));
            msgs.add(ChatMessage.user(ScriptPrompts.revisionFailed(problem)));
            answer = callText(job, tr, "interior-fix", cfg.get().sculptEffort, msgs, null, stop);
        }
        return null;
    }

    /**
     * Applies the interior designer's statement on top of the current script (after any sculptor rewrites) and
     * keeps it when it still runs and adds no analyzer defects there.
     */
    private Revision mergeInterior(Job job, Transcript tr, String prompt, ScriptRuntime runtime, ScriptRuntime.Budget budget,
                                   int[] limits, String source, VoxelModel model, AnalysisReport rep, AnalyzerOptions aopts,
                                   PartRewrite r, QualityVector q0) {
        ScriptPatch.Result patch = ScriptPatch.putPart(source, r.name(), r.statement());
        if (!patch.ok()) return null;
        ScriptResult run = runtime.run(patch.source(), job.scriptSeed, null, limits, budget, job.cancelled);
        if (!run.ok()) {
            tr.note("interior rejected", "fails on the current script:\n" + run.error().describe());
            return null;
        }
        prepare(run);
        AnalysisReport nrep = StructureAnalyzer.analyze(run.model(), info, aopts);
        List<String> worse = QualityVector.of(nrep, run).regressions(q0);
        if (!worse.isEmpty()) {
            tr.note("interior rejected", "makes the current build worse: " + String.join(", ", worse));
            return null;
        }
        int before = rep.roomList.stream().mapToInt(AnalysisReport.Room::furniture).sum();
        int after = nrep.roomList.stream().mapToInt(AnalysisReport.Room::furniture).sum();
        tr.note("interior accepted", "part " + r.name() + ", furniture " + before + " -> " + after);
        say(job, "<gray>Интерьеры обставлены: мебели <v0> → <v1>.</gray>", before, after);
        return new Revision(patch.source(), run, "furnished interiors");
    }

    private static String diagnosis(String answer) {
        String a = answer == null ? "" : answer;
        int cut = a.indexOf("<<<<<<<");
        int fence = a.indexOf("```");
        int end = cut < 0 ? fence : fence < 0 ? cut : Math.min(cut, fence);
        return clip(end < 0 ? a : a.substring(0, end), 400);
    }

    private static boolean isDone(String answer) {
        String a = answer == null ? "" : answer.strip();
        return (a.equals("DONE") || a.startsWith("DONE") && a.length() < 200) && !ScriptPatch.hasHunks(a);
    }

    /** Makes a kept revision the job's current script and shows it. */
    private void adopt(Job job, Build build, Staged st, Revision r) {
        job.script = r.source();
        job.smoothed = r.result().smoothed();
        job.scriptParts = r.result().partTable(30);
        synchronized (job) {
            job.doorways.clear();
            job.doorways.addAll(r.result().doorways());
        }
        st.model = r.result().model();
        liveModel(job, build, st.model, "critic");
        // Revisions can add or drop parts; the page's part legend and the pack's per-cell part indices come from
        // this list, so a stale one highlights parts that no longer exist.
        liveParts(job, r.result());
    }

    /** A stage's quality row and picture in the job log. */
    private void snapshot(Transcript tr, String stage, VoxelModel model, QualityVector q) {
        tr.quality(stage, q);
        if (cfg.get().stageSnapshots) tr.image("stage-" + stage, sheet(model));
    }

    /**
     * The finisher's mechanical repairs, appended to the script as its last part and kept when the script still
     * runs and nothing got worse. Null when there was nothing to repair.
     */
    private Revision finish(Job job, Transcript tr, ScriptRuntime runtime, ScriptRuntime.Budget budget, int[] limits,
                            String source, ScriptResult cur, AnalyzerOptions aopts, QualityVector q) {
        Finisher.Result f = Finisher.plan(cur.model(), info, cur.collisions(), aopts.expectInterior);
        if (f.statement() == null) return null;
        ScriptPatch.Result patch = ScriptPatch.putPart(source, Finisher.PART, f.statement());
        if (!patch.ok()) {
            tr.note("finish rejected", patch.error());
            return null;
        }
        ScriptResult run = runtime.run(patch.source(), job.scriptSeed, null, limits, budget, job.cancelled);
        if (!run.ok()) {
            tr.note("finish rejected", run.error().describe());
            return null;
        }
        prepare(run);
        AnalysisReport nrep = StructureAnalyzer.analyze(run.model(), info, aopts);
        List<String> worse = QualityVector.of(nrep, run).regressions(q);
        if (!worse.isEmpty()) {
            tr.note("finish rejected", String.join(", ", worse) + "\n" + f.notes());
            return null;
        }
        tr.note("finish", f.edits() + " repairs: " + String.join("; ", f.notes()));
        say(job, "<gray>Доводка: <v0> исправлений (<v1>).</gray>", f.edits(), clip(String.join("; ", f.notes()), 200));
        return new Revision(patch.source(), run, "finisher");
    }

    /**
     * Script review: critic rounds over the rendered build, the analyzer findings and the script. A revision is
     * kept only when it runs, does not add analyzer defects, keeps at least half the blocks, and (when images
     * reach the model) the pairwise judge does not prefer the previous build. Approval is deterministic: no
     * critical or major analyzer findings; the model's self-score is not used.
     */
    private Verdict verifyScript(Job job, Transcript tr, Build build, Staged st, String prompt) throws Exception {
        PluginConfig c = cfg.get();
        int rounds = job.criticRounds != null ? job.criticRounds : c.criticRounds(job.mode.id);
        AnalyzerOptions aopts = analyzerOptions(st.plan);
        ScriptRuntime runtime = new ScriptRuntime(info, job.stdlib);
        ScriptRuntime.Budget budget = BuildMode.EXTRA.equals(job.mode.id) ? ScriptRuntime.Budget.extra() : ScriptRuntime.Budget.standard();
        int ext = c.maxExtent;
        int[] limits = {-ext, -32, -ext, ext, Math.min(320, ext * 2), ext};
        int sculptors = job.stdlib == dev.aibuild.script.Stdlib.V1 ? 0 : c.sculptors(job.mode.id);
        boolean eyes = (rounds > 0 || sculptors > 0) && vision(job, tr);
        String system = ScriptPrompts.system(job.stdlib, c.includeBlockList ? blockList.get() : null);
        String source = job.script;
        VoxelModel model = st.model;
        AnalysisReport rep = StructureAnalyzer.analyze(model, info, aopts);
        List<String> rejected = new ArrayList<>();
        Verdict v = new Verdict();
        int accepted = 0;
        boolean furnish = job.stdlib != dev.aibuild.script.Stdlib.V1 && aopts.expectInterior
                && (job.interiors != null ? job.interiors : c.interiors(job.mode.id));
        // the current script's run: its collision ledger is part of the quality every revision is measured against
        ScriptResult cur = runtime.run(source, job.scriptSeed, null, limits, budget, job.cancelled);
        if (cur.ok()) prepare(cur);
        else cur = null;
        QualityVector q = QualityVector.of(rep, cur);
        snapshot(tr, "design", model, q);
        if (sculptors > 0 && eyes) {
            Revision sr = sculpt(job, tr, prompt, runtime, budget, limits, system, source, model, rep, aopts, sculptors);
            if (sr != null) {
                accepted++;
                source = sr.source();
                model = sr.result().model();
                rep = StructureAnalyzer.analyze(model, info, aopts);
                cur = sr.result();
                q = QualityVector.of(rep, cur);
                adopt(job, build, st, sr);
                snapshot(tr, "sculpt", model, q);
            }
        }
        // the interior designer works on the sculpted build, not on a copy from before the sculptors: rooms they
        // moved or reshaped are the rooms it furnishes
        if (furnish && !rep.roomList.isEmpty()) {
            var interiorStop = new java.util.concurrent.atomic.AtomicBoolean();
            long interiorDeadline = System.nanoTime() + c.sculptTimeout * 1_000_000_000L;
            String interiorBase = source;
            VoxelModel interiorModel = model;
            AnalysisReport interiorRep = rep;
            QualityVector interiorQ = q;
            job.status("ИИ обставляет интерьеры…", 0.62);
            stageOf(job, "interior", "Interior designer furnishes the rooms");
            Future<PartRewrite> interior = side.submit(() -> designInterior(job, tr, prompt, system, runtime, budget, limits,
                    interiorBase, interiorModel, interiorRep, aopts, eyes, interiorQ, interiorStop));
            Revision ir = null;
            try {
                PartRewrite r = awaitUntil(interior, job, interiorDeadline);
                if (r != null) ir = mergeInterior(job, tr, prompt, runtime, budget, limits, source, model, rep, aopts, r, q);
            } catch (java.util.concurrent.TimeoutException e) {
                tr.note("interior timed out", "still working after " + c.sculptTimeout + " s, rooms left as they were");
            } catch (CancelledException e) {
                if (job.cancelled.get()) throw e;
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "interior designer failed", e);
            } finally {
                interiorStop.set(true);
            }
            if (ir != null) {
                accepted++;
                source = ir.source();
                model = ir.result().model();
                rep = StructureAnalyzer.analyze(model, info, aopts);
                cur = ir.result();
                q = QualityVector.of(rep, cur);
                adopt(job, build, st, ir);
                snapshot(tr, "interior", model, q);
            }
        }
        // the frozen 2.0 bench variant fixes its rounds and keeps them all
        int deadline = job.criticRounds != null ? 0 : c.reviewDeadline(job.mode.id);
        for (int round = 1; round <= rounds; round++) {
            checkCancelled(job);
            if (deadline > 0 && job.elapsedSeconds() > deadline && rep.count(dev.aibuild.analysis.Severity.CRITICAL) == 0) {
                tr.note("critic skipped", "past the review deadline of " + deadline + " s");
                break;
            }
            v.rounds = round;
            job.status("ИИ критикует постройку: раунд " + round + " из " + rounds + "…", 0.5 + 0.4 * (round - 1) / rounds);
            stageOf(job, "critic", "Critic round " + round + " of " + rounds);
            byte[] img = eyes ? sheet(model) : null;
            byte[] rooms = eyes && aopts.expectInterior && !rep.roomList.isEmpty() ? interiorSheet(model, rep.roomList) : null;
            // what the finisher repairs anyway (lighting, loose attachments, crumbs, decoration in walls) is left out:
            // the critic spends its round on what only a designer can fix
            Finisher.Result pending = c.finisher && cur != null ? Finisher.plan(model, info, cur.collisions(), aopts.expectInterior) : null;
            String evidence = withAutoNotes(job, rep.toText()) + "\n" + SpatialReport.render(model, info)
                    + "\nQuality now: " + q.describe() + "\n"
                    + (cur != null && !cur.collisionText().isEmpty() ? "Collisions between parts:\n" + cur.collisionText() : "")
                    + (cur != null && !cur.warnings().isEmpty() ? "Script warnings (the library already worked around these; fix the"
                    + " placement so they go away):\n- " + String.join("\n- ", cur.warnings().subList(0, Math.min(10, cur.warnings().size()))) + "\n" : "")
                    + (pending != null && pending.edits() > 0 ? "Repaired automatically after review, no need to touch: "
                    + String.join("; ", pending.notes()) + "\n" : "");
            String user = ScriptPrompts.critic(prompt, source, evidence, job.scriptParts, img != null, round, rounds, rejected);
            List<ChatMessage> msgs = new ArrayList<>(List.of(ChatMessage.system(system),
                    img == null ? ChatMessage.user(user) : ChatMessage.user(user, rooms == null ? List.of(img) : List.of(img, rooms))));
            String effort = round == 1 ? job.mode.effortReview : job.mode.effortReviewFollowup;
            String answer = callText(job, tr, "critic" + round, effort, msgs, codeStream(job, "critic" + round), null);
            v.reviewerCalls++;
            if (isDone(answer)) {
                tr.note("critic" + round + " done", "the critic found nothing left to improve");
                say(job, "<gray>Критик: улучшать больше нечего.</gray>");
                break;
            }
            Revision r = revise(job, tr, runtime, budget, job.scriptSeed, limits, source, answer, msgs, "critic" + round, effort);
            String what = diagnosis(answer);
            if (r == null) {
                rejected.add(clip(what, 200) + " — did not apply or run");
                say(job, "<gray>Раунд <v0>: правку не удалось применить.</gray>", round);
                continue;
            }
            VoxelModel next = r.result().model();
            AnalysisReport nrep = StructureAnalyzer.analyze(next, info, aopts);
            QualityVector nq = QualityVector.of(nrep, r.result());
            List<String> worse = nq.regressions(q);
            String gate = worse.isEmpty() ? null : "made it worse: " + String.join(", ", worse);
            if (gate == null && eyes && c.visionJudge) {
                int pref = judge(job, tr, prompt, sheet(model), sheet(next));
                if (pref < 0) gate = "the judge preferred the previous version";
                else if (pref == 0 && !nq.fixesDefects(q)) gate = "no visible improvement according to the judge";
            }
            if (gate != null) {
                rejected.add(clip(what, 200) + " — rejected: " + gate);
                tr.note("critic" + round + " rejected", gate + "\n" + what);
                say(job, "<gray>Раунд <v0>: правка отклонена (<v1>).</gray>", round, gate);
                continue;
            }
            accepted++;
            source = r.source();
            model = next;
            rep = nrep;
            cur = r.result();
            q = nq;
            adopt(job, build, st, r);
            snapshot(tr, "critic" + round, model, q);
            say(job, "<gray>Раунд <v0>: улучшено — <white><v1></white></gray>", round, clip(what.replace("DIAGNOSIS:", "").strip(), 180));
        }
        if (c.finisher && cur != null) {
            Revision fr = finish(job, tr, runtime, budget, limits, source, cur, aopts, q);
            if (fr != null) {
                source = fr.source();
                model = fr.result().model();
                rep = StructureAnalyzer.analyze(model, info, aopts);
                q = QualityVector.of(rep, fr.result());
                adopt(job, build, st, fr);
                snapshot(tr, "finish", model, q);
            }
        }
        job.scriptModel = model.copy();
        v.summary = Json.str(st.plan, "summary", "");
        v.checklist = "critic " + accepted + "/" + v.rounds;
        v.issuesFixed = accepted;
        v.finalReport = rep;
        v.approved = !rep.hasBlocking();
        v.score = -1;
        return v;
    }

    /**
     * Edits a script build by changing its script. Returns false (and leaves the build untouched) when the edit
     * cannot be applied, so the caller falls back to the JSON-op edit pipeline.
     */
    private boolean editScript(Job job, Transcript tr, Build build, BuildVersion cur, VoxelModel model, JsonObject plan,
                               String original, String change) throws Exception {
        PluginConfig c = cfg.get();
        job.stdlib = dev.aibuild.script.Stdlib.of(cur.stdlibVersion);
        ScriptRuntime runtime = new ScriptRuntime(info, job.stdlib);
        ScriptRuntime.Budget budget = BuildMode.EXTRA.equals(job.mode.id) ? ScriptRuntime.Budget.extra() : ScriptRuntime.Budget.standard();
        int ext = c.maxExtent;
        int[] limits = {-ext, -32, -ext, ext, Math.min(320, ext * 2), ext};
        ScriptResult base = runtime.run(cur.script, cur.seed, null, limits, budget, job.cancelled);
        if (!base.ok()) {
            tr.note("edit-script skipped", "stored script no longer runs: " + base.error().describe());
            return false;
        }
        job.status("ИИ изменяет программу постройки…", 0.1);
        stageOf(job, "edit", "Rewriting the program");
        AnalyzerOptions aopts = analyzerOptions(plan);
        AnalysisReport rep = StructureAnalyzer.analyze(model, info, aopts);
        boolean eyes = vision(job, tr);
        byte[] img = eyes ? sheet(model) : null;
        String user = ScriptPrompts.edit(original, change, cur.script, withAutoNotes(job, rep.toText()), base.partTable(30), img != null);
        List<ChatMessage> msgs = new ArrayList<>(List.of(ChatMessage.system(ScriptPrompts.system(job.stdlib, c.includeBlockList ? blockList.get() : null)),
                img == null ? ChatMessage.user(user) : ChatMessage.user(user, List.of(img))));
        String answer = callText(job, tr, "edit-script", job.mode.effortEdit, msgs, codeStream(job, "edit-script"), null);
        Revision r = revise(job, tr, runtime, budget, cur.seed, limits, cur.script, answer, msgs, "edit-script", job.mode.effortEdit);
        if (r == null) {
            say(job, "<yellow>Правку программы применить не удалось, используем обычную правку.</yellow>");
            return false;
        }
        VoxelModel next = r.result().model();
        AnalysisReport nrep = StructureAnalyzer.analyze(next, info, aopts);
        job.script = r.source();
        job.scriptSeed = cur.seed;
        job.smoothed = r.result().smoothed();
        job.scriptParts = r.result().partTable(30);
        job.scriptModel = next.copy();
        synchronized (job) {
            job.doorways.clear();
            job.doorways.addAll(r.result().doorways());
        }
        liveModel(job, build, next, "critic");
        liveParts(job, r.result());
        Verdict v = new Verdict();
        v.rounds = 1;
        v.summary = clip(r.diagnosis().replace("CHANGE:", "").strip(), 300);
        v.finalReport = nrep;
        v.approved = nrep.count(dev.aibuild.analysis.Severity.CRITICAL) <= rep.count(dev.aibuild.analysis.Severity.CRITICAL)
                && nrep.count(dev.aibuild.analysis.Severity.MAJOR) <= rep.count(dev.aibuild.analysis.Severity.MAJOR);
        if (!v.approved) v.blockers.put("regression", "the change added analyzer defects");
        v.score = -1;
        commit(job, build, next, change, v, tr);
        return true;
    }

    /** The brief of a script build: its meta() call plus the real bounds of what it placed. */
    private static JsonObject planFromScript(ScriptResult result, String prompt) {
        JsonObject meta = result.meta();
        JsonObject plan = new JsonObject();
        plan.addProperty("title", Json.str(meta, "title", "Постройка"));
        plan.addProperty("summary", Json.str(meta, "summary", ""));
        plan.addProperty("type", Json.str(meta, "type", "object").toLowerCase(Locale.ROOT));
        plan.addProperty("style", Json.str(meta, "style", ""));
        plan.addProperty("expect_interior", Json.bool(meta, "expect_interior", false));
        if (Json.obj(meta, "exact_size") != null) plan.add("exact_size", Json.obj(meta, "exact_size"));
        if (Json.obj(meta, "size_limits") != null) plan.add("size_limits", Json.obj(meta, "size_limits"));
        JsonArray rooms = Json.arr(meta, "rooms");
        if (!rooms.isEmpty()) {
            java.util.Map<String, JsonArray> byLevel = new java.util.LinkedHashMap<>();
            for (JsonElement r : rooms) {
                if (!r.isJsonObject()) continue;
                byLevel.computeIfAbsent(Json.str(r.getAsJsonObject(), "level", "ground floor"), k -> new JsonArray()).add(r);
            }
            JsonArray layout = new JsonArray();
            byLevel.forEach((level, list) -> {
                JsonObject lv = new JsonObject();
                lv.addProperty("level", level);
                lv.add("rooms", list);
                layout.add(lv);
            });
            plan.add("layout", layout);
        }
        int[] b = result.model().solidBounds();
        if (b == null) b = new int[]{0, 0, 0, 0, 0, 0};
        JsonObject plot = new JsonObject();
        JsonArray mn = new JsonArray(), mx = new JsonArray();
        for (int i = 0; i < 3; i++) {
            mn.add(b[i]);
            mx.add(b[i + 3]);
        }
        plot.add("min", mn);
        plot.add("max", mx);
        plan.add("plot", plot);
        plan.addProperty("request", prompt);
        return plan;
    }

    /** Block-count guidance for the script prompt, by thinking mode. */
    private static String scaleHint(BuildMode mode, dev.aibuild.script.Stdlib lib) {
        if (lib != dev.aibuild.script.Stdlib.V1) {
            return switch (mode.id) {
                case BuildMode.FAST -> "Keep it complete but compact (usually 3,000-30,000 blocks).";
                case BuildMode.EXTRA -> "This is a showcase build: spend detail generously and use the space (often 40,000-400,000 blocks).";
                default -> "Make it rich and complete (usually 10,000-150,000 blocks; more for castles, ships and scenes).";
            };
        }
        return switch (mode.id) {
            case BuildMode.FAST -> "Keep it compact but complete (usually 1,500-20,000 blocks).";
            case BuildMode.EXTRA -> "Spend detail generously: large, rich builds are welcome (up to ~250,000 blocks when the subject deserves it).";
            default -> "Make it rich and complete (usually 4,000-80,000 blocks; more for castles, ships and scenes).";
        };
    }

    private Staged designAndCompile(Job job, Transcript tr, String prompt, Build build, Anchor anchor,
                                    boolean isRebuild) throws Exception {
        job.status("ИИ проектирует цельную постройку…", 0.02);
        stageOf(job, "program", "Writing the build program");
        JsonObject program = callJson(job, tr, "compile", job.mode.effortBuild, Prompts.compile(prompt));
        JsonObject plan = Json.obj(program, "plan");
        if (plan == null || Json.arr(program, "ops").isEmpty()) {
            throw new IllegalStateException("build program must contain a plan and non-empty ops");
        }
        normalizePlan(plan, cfg.get());
        plan.remove("stages");
        int[] plot = plotBounds(plan);
        if (build != null) {
            sync(() -> {
                build.name = Json.str(plan, "title", "Постройка");
                build.plan = plan;
                if (build.originalPrompt == null || isRebuild) build.originalPrompt = prompt;
                if (anchor != null && !build.placed && build.versions.isEmpty()) place(build, anchor, plot);
                store.save(build);
                return null;
            });
        }
        job.plotLimits = new int[]{plot[0] - 3, plot[1] - 2, plot[2] - 3, plot[3] + 3, plot[4] + 2, plot[5] + 3};
        Staged st = new Staged();
        st.plan = plan;
        st.planText = Json.GSON.toJson(planForPrompt(plan));
        st.model = new VoxelModel();
        st.limits = job.plotLimits;
        OpResult result = applyWithRepair(job, tr, new OpExecutor(info).withLimits(st.limits), st.model, program, "compile");
        for (var doorway : result.doorways) job.doorways.add(doorway.followCompiledEntrance(st.model,info));
        tr.note("compile result", result.summary() + "\nresolver: " + postProcess(job, st.model));
        liveModel(job, build, st.model, "stage");
        say(job, "<gray>Программа постройки готова: <white><v0></white>, <v1> блоков. Проверяем конструкцию, фасады и интерьер.</gray>",
                Json.str(plan, "title", "Постройка"), st.model.nonAirCount());
        return st;
    }

    /** Plan + all construction stages. {@code build} may be null (benchmark: no world, no store). */
    private Staged designAndStage(Job job, Transcript tr, String prompt, Build build, Anchor anchor, boolean isRebuild) throws Exception {
        PluginConfig c = cfg.get();
        job.status("Проектирование постройки…", 0.02);
        JsonObject plan = callJson(job, tr, "plan", job.mode.effortPlan, Prompts.plan(prompt, null));
        normalizePlan(plan, c);
        String title = Json.str(plan, "title", "Постройка");
        JsonArray stages = plan.getAsJsonArray("stages");
        int[] plot = plotBounds(plan);
        String planText = Json.PRETTY.toJson(planForPrompt(plan));
        say(job, "<gray>План готов: <white><v0></white> <dark_gray>(<v1>)</dark_gray> — <v2> этапов, размер <v3>×<v4>×<v5></gray>",
                title, Json.str(plan, "style", "?"), stages.size(), plot[3] - plot[0] + 1, plot[4] - plot[1] + 1, plot[5] - plot[2] + 1);
        if (build != null) {
            sync(() -> {
                build.name = title;
                build.plan = plan;
                if (build.originalPrompt == null || isRebuild) build.originalPrompt = prompt;
                if (anchor != null && !build.placed && build.versions.isEmpty()) place(build, anchor, plot);
                store.save(build);
                return null;
            });
        }
        VoxelModel model = new VoxelModel();
        job.plotLimits = new int[]{plot[0] - 3, plot[1] - 2, plot[2] - 3, plot[3] + 3, plot[4] + 2, plot[5] + 3};
        OpExecutor exec = new OpExecutor(info).withLimits(job.plotLimits);
        executeStages(job, tr, exec, model, analyzerOptions(plan), stages, build, "stage", 0.06, 0.62,
                (k, total, name, goal, render, analysis, note) ->
                        Prompts.stage(prompt, planText, k, total, name, goal, render, analysis, note),
                job.mode.effortBuild);
        Staged st = new Staged();
        st.plan = plan;
        st.planText = planText;
        st.model = model;
        st.limits = job.plotLimits;
        return st;
    }

    /**
     * Executes ordered stages (consecutive "parallel" stages concurrently against the same snapshot), applying
     * ops, post-processing and live preview after each. Used for construction stages and edit steps.
     */
    private void executeStages(Job job, Transcript tr, OpExecutor exec, VoxelModel model, AnalyzerOptions aopts,
                               JsonArray stages, Build build, String tagPrefix, double p0, double pSpan,
                               StagePrompt promptFn, String effort) throws Exception {
        int total = stages.size();
        double stageSpan = pSpan / Math.max(1, total);
        String label = tagPrefix.equals("stage") ? "Этап" : "Шаг";
        int i = 0;
        while (i < total) {
            checkCancelled(job);
            int j = i + 1;
            if (Json.bool(stages.get(i).getAsJsonObject(), "parallel", false)) {
                while (j < total && Json.bool(stages.get(j).getAsJsonObject(), "parallel", false)) j++;
            }
            String render = model.isEmpty() ? null : render(model);
            String analysis = model.isEmpty() ? null : withAutoNotes(job, StructureAnalyzer.analyze(model, info, aopts).toText());
            List<String> names = new ArrayList<>();
            for (int k = i; k < j; k++) names.add(Json.str(stages.get(k).getAsJsonObject(), "name", label + " " + (k + 1)));
            job.status(j - i == 1 ? label + " " + (i + 1) + "/" + total + ": " + names.get(0)
                    : label + " " + (i + 1) + "–" + j + "/" + total + " параллельно: " + String.join(", ", names), p0 + stageSpan * i);
            // Staged builds and stepwise edits have no stage rows otherwise: the timeline would sit on one line
            // for the whole construction while the build visibly grows.
            stageOf(job, tagPrefix + (i + 1), (j - i == 1 ? names.get(0) : String.join(" + ", names))
                    + " (" + (i + 1) + "/" + total + ")");
            List<Future<JsonObject>> futures = new ArrayList<>();
            for (int k = i; k < j; k++) {
                JsonObject st = stages.get(k).getAsJsonObject();
                String name = names.get(k - i);
                String note = null;
                if (j - i > 1) {
                    List<String> others = new ArrayList<>(names);
                    others.remove(name);
                    note = "PARALLEL WORK: other builders simultaneously do: " + String.join("; ", others)
                            + ". Stay strictly inside the area of YOUR part; do not touch their areas.";
                }
                String user = promptFn.make(k + 1, total, name, Json.str(st, "goal", name), render == null ? null : render,
                        analysis, note);
                String tag = tagPrefix + (k + 1);
                futures.add(side.submit(() -> callJson(job, tr, tag, effort, user)));
            }
            for (int k = i; k < j; k++) {
                JsonObject res = await(futures.get(k - i), job);
                OpResult r = applyWithRepair(job, tr, exec, model, res, tagPrefix + (k + 1));
                List<String> auto = postProcess(job, model);
                tr.note(tagPrefix + (k + 1) + " result", r.summary() + "\nresolver: " + auto);
                say(job, "<gray>✔ " + label + " <white><v0>/<v1></white> <v2>: <dark_gray><v3></dark_gray> <green>(+<v4> блоков)</green></gray>",
                        k + 1, total, names.get(k - i), clip(Json.str(res, "notes", ""), 120), r.cellsChanged);
                liveModel(job, build, model, "stage");
            }
            i = j;
        }
    }

    // =================================================================== edit

    /** Outcome of an edit strategy before verification. */
    static final class EditResult {
        VoxelModel model;
        String editPlanText = "";
        JsonObject plan;
        String planText;
        String summary;
        String rebuildRequest;
        Checklist checklist;
    }

    private void runEdit(Job job, Build build, String change) throws Exception {
        PluginConfig c = cfg.get();
        Transcript tr = new Transcript(build.id, c.saveTranscripts);
        tr.liveJob = job;
        VoxelModel model = sync(() -> {
            VoxelModel m = build.currentModel();
            return m == null ? null : m.copy();
        });
        if (model == null) throw new IllegalStateException("у постройки нет версии для редактирования");
        JsonObject plan = sync(() -> build.plan == null ? null : build.plan.deepCopy());
        if (plan == null) plan = syntheticPlan(build.name);
        String original = build.originalPrompt == null ? Json.str(plan, "title", "") : build.originalPrompt;
        String history = sync(() -> String.join(" | ", build.history));

        BuildVersion cur = sync(build::current);
        if (cur != null && cur.script != null && cur.scriptExact && editScript(job, tr, build, cur, model, plan, original, change)) return;

        EditResult er = switch (c.editStrategy) {
            case "steps" -> editSteps(job, tr, model, plan, original, history, change, build);
            case "plan" -> editPlanOnce(job, tr, model, plan, original, history, change, build);
            default -> editDirect(job, tr, model, plan, original, history, change, build);
        };
        if (er.rebuildRequest != null) {
            say(job, "<gray>Запрос требует перестройки с нуля: <white><v0></white></gray>", clip(er.rebuildRequest, 160));
            runCreate(job, build, er.rebuildRequest, null, true);
            return;
        }
        final JsonObject planFinal = er.plan;
        sync(() -> {
            build.plan = planFinal;
            return null;
        });
        OpExecutor exec = new OpExecutor(info).withLimits(job.plotLimits);
        String strategy = c.reviewStrategy;
        int cap = "adaptive".equals(strategy) ? c.adaptiveMaxRounds : job.mode.editVerifyRounds;
        Verdict v = verify(job, tr, exec, er.model, er.plan, er.planText, original, change, er.checklist, strategy, cap, 0.55);
        if (v.summary == null || v.summary.isBlank()) v.summary = er.summary;
        commit(job, build, er.model, change, v, tr);
    }

    static JsonObject syntheticPlan(String name) {
        JsonObject plan = new JsonObject();
        plan.addProperty("title", name == null ? "Импортированная постройка" : name);
        plan.addProperty("summary", "Imported / hand-made structure without a design brief; infer style and layout from the model.");
        plan.addProperty("expect_interior", true);
        return plan;
    }

    private static void applyPlanUpdate(JsonObject plan, JsonObject res) {
        JsonObject upd = Json.obj(res, "plan_update");
        if (upd != null) for (Map.Entry<String, JsonElement> e : upd.entrySet()) plan.add(e.getKey(), e.getValue());
    }

    /** Edit area limits: plan plot ∪ current bounds ∪ the edit plan's declared area, plus a generous margin. */
    private static void setEditLimits(Job job, VoxelModel model, JsonObject plan, JsonObject area) {
        int[] mb = model.bounds();
        int[] pb = plan.has("plot") ? plotBounds(plan) : mb;
        if (mb == null) mb = pb;
        if (mb == null) return;
        if (pb == null) pb = mb;
        int[] L = {Math.min(mb[0], pb[0]) - 12, Math.min(mb[1], pb[1]) - 4, Math.min(mb[2], pb[2]) - 12,
                Math.max(mb[3], pb[3]) + 12, Math.max(mb[4], pb[4]) + 16, Math.max(mb[5], pb[5]) + 12};
        if (area != null) {
            try {
                JsonArray mn = area.getAsJsonArray("min"), mx = area.getAsJsonArray("max");
                for (int i = 0; i < 3; i++) {
                    int a = (int) Math.round(mn.get(i).getAsDouble()), b = (int) Math.round(mx.get(i).getAsDouble());
                    L[i] = Math.min(L[i], Math.min(a, b) - 3);
                    L[i + 3] = Math.max(L[i + 3], Math.max(a, b) + 3);
                }
            } catch (RuntimeException ignored) {
                // malformed area: keep defaults
            }
        }
        job.plotLimits = L;
    }

    /** Old strategy: one call writes all ops for the change. */
    private EditResult editDirect(Job job, Transcript tr, VoxelModel model, JsonObject plan, String original,
                                  String history, String change, Build build) throws Exception {
        job.status("ИИ анализирует запрос на изменение…", 0.05);
        stageOf(job, "plan", "Planning the change");
        AnalyzerOptions aopts = analyzerOptions(plan);
        String planText = Json.PRETTY.toJson(planForPrompt(plan));
        JsonObject res = callJson(job, tr, "edit", job.mode.effortEdit, Prompts.edit(original, planText, history, render(model),
                withAutoNotes(job, StructureAnalyzer.analyze(model, info, aopts).toText()), change));
        EditResult er = new EditResult();
        if (Json.bool(res, "rebuild", false)) {
            er.rebuildRequest = Json.str(res, "new_request", original + ". Изменение: " + change);
            return er;
        }
        applyPlanUpdate(plan, res);
        setEditLimits(job, model, plan, null);
        OpExecutor exec = new OpExecutor(info).withLimits(job.plotLimits);
        job.status("Применение изменений…", 0.35);
        OpResult r = applyWithRepair(job, tr, exec, model, res, "edit");
        postProcess(job, model);
        say(job, "<gray>✔ Изменение: <white><v0></white> <green>(<v1> блоков изменено)</green></gray>",
                clip(Json.str(res, "summary", change), 160), r.cellsChanged);
        liveModel(job, build, model, "stage");
        er.model = model;
        er.plan = plan;
        er.planText = Json.PRETTY.toJson(planForPrompt(plan));
        er.summary = Json.str(res, "summary", change);
        er.checklist = Checklist.forEdit(change, new JsonArray(), new JsonArray());
        return er;
    }

    /** Hybrid: plan the change once (area, steps, acceptance criteria), then build it in ONE call that sees the
     *  plan, and verify against the criteria. */
    private EditResult editPlanOnce(Job job, Transcript tr, VoxelModel model, JsonObject plan, String original,
                                    String history, String change, Build build) throws Exception {
        EditResult er = planEdit(job, tr, model, plan, original, history, change);
        if (er.rebuildRequest != null) return er;
        String planText = er.planText;
        job.status("ИИ выполняет изменение по плану…", 0.35);
        AnalyzerOptions aopts = analyzerOptions(er.plan);
        String user = Prompts.edit(original, planText, history, render(model),
                withAutoNotes(job, StructureAnalyzer.analyze(model, info, aopts).toText()), change)
                + "\n\nTHE PLAN ABOVE IS ALREADY MADE — implement ALL of it now in one go (write the complete set of ops),\n"
                + "so that every acceptance criterion is satisfied.\nEDIT PLAN:\n" + er.editPlanText + "\n";
        JsonObject res = callJson(job, tr, "edit", job.mode.effortBuild, user);
        OpExecutor exec = new OpExecutor(info).withLimits(job.plotLimits);
        OpResult r = applyWithRepair(job, tr, exec, model, res, "edit");
        postProcess(job, model);
        say(job, "<gray>✔ Изменение по плану: <white><v0></white> <green>(<v1> блоков изменено)</green></gray>",
                clip(Json.str(res, "summary", change), 160), r.cellsChanged);
        liveModel(job, build, model, "stage");
        er.summary = Json.str(res, "summary", er.summary);
        return er;
    }

    /** Shared first half of the planned strategies: edit plan + limits + checklist. */
    private EditResult planEdit(Job job, Transcript tr, VoxelModel model, JsonObject plan, String original,
                                String history, String change) throws Exception {
        job.status("ИИ планирует изменение…", 0.04);
        stageOf(job, "plan", "Planning the change");
        AnalyzerOptions aopts = analyzerOptions(plan);
        String planText = Json.PRETTY.toJson(planForPrompt(plan));
        JsonObject ep = callJson(job, tr, "edit-plan", job.mode.effortEdit, Prompts.editPlan(original, planText, history,
                render(model), withAutoNotes(job, StructureAnalyzer.analyze(model, info, aopts).toText()), change));
        EditResult er = new EditResult();
        if (Json.bool(ep, "rebuild", false)) {
            er.rebuildRequest = Json.str(ep, "new_request", original + ". Изменение: " + change);
            return er;
        }
        applyPlanUpdate(plan, ep);
        JsonArray steps = Json.arr(ep, "steps");
        if (steps.isEmpty()) {
            JsonObject one = new JsonObject();
            one.addProperty("name", "Изменение");
            one.addProperty("goal", change);
            steps.add(one);
        }
        while (steps.size() > 6) steps.remove(steps.size() - 1);
        JsonObject epShown = ep.deepCopy();
        epShown.remove("plan_update");
        er.editPlanText = Json.PRETTY.toJson(epShown);
        tr.note("edit-plan", er.editPlanText);
        say(job, "<gray>План изменения: <white><v0></white> — <v1> шаг(ов), критериев приёмки: <v2></gray>",
                clip(Json.str(ep, "summary", change), 140), steps.size(), Json.arr(ep, "acceptance").size());
        setEditLimits(job, model, plan, Json.obj(ep, "area"));
        er.model = model;
        er.plan = plan;
        er.planText = Json.PRETTY.toJson(planForPrompt(plan));
        er.summary = Json.str(ep, "summary", change);
        er.checklist = Checklist.forEdit(change, Json.arr(ep, "acceptance"), Json.arr(ep, "preserve"));
        return er;
    }

    /** Plan the change, then execute its steps one by one. */
    private EditResult editSteps(Job job, Transcript tr, VoxelModel model, JsonObject plan, String original,
                                 String history, String change, Build build) throws Exception {
        EditResult er = planEdit(job, tr, model, plan, original, history, change);
        if (er.rebuildRequest != null) return er;
        JsonObject epShown = JsonParser.parseString(er.editPlanText).getAsJsonObject();
        JsonArray steps = Json.arr(epShown, "steps");
        AnalyzerOptions aopts = analyzerOptions(er.plan);
        OpExecutor exec = new OpExecutor(info).withLimits(job.plotLimits);
        executeStages(job, tr, exec, model, aopts, steps, build, "step", 0.1, 0.45,
                (k, total, name, goal, render, analysis, note) ->
                        Prompts.editStep(original, change, er.editPlanText, k, total, name, goal, render, analysis, note),
                job.mode.effortBuild);
        return er;
    }

    // =================================================================== verification

    static final class Verdict {
        int score = -1;
        boolean approved;
        String summary;
        int rounds;
        int issuesFixed;
        int reviewerCalls;
        String checklist = "";
        AnalysisReport finalReport;
        final JsonObject quality = new JsonObject();
        final Map<String,String> blockers = new java.util.LinkedHashMap<>();
        final JsonArray reviewContexts = new JsonArray();

        void finishAnalysis(AnalysisReport report) {
            finalReport = report;
            blockers.keySet().removeIf(key -> key.startsWith("analyzer:"));
            if (report == null) return;
            int index = 0;
            for (var issue : report.issues) {
                if (issue.severity() != dev.aibuild.analysis.Severity.CRITICAL
                        && issue.severity() != dev.aibuild.analysis.Severity.MAJOR) continue;
                blockers.put("analyzer:" + issue.code() + ":" + index++, issue.toLine());
            }
            if (report.hasBlocking()) approved = false;
        }

        String failureReason() {
            if (!blockers.isEmpty()) return String.join("; ", blockers.values());
            return "Review acceptance incomplete; checklist " + checklist;
        }
    }

    /**
     * Verification dispatcher. {@code change} is null for new builds (three parallel experts) and the user's
     * change request for edits (one change-focused reviewer).
     */
    private Verdict verify(Job job, Transcript tr, OpExecutor exec, VoxelModel model, JsonObject plan, String planText,
                           String request, String change, Checklist cl, String strategy, int cap, double p0) throws Exception {
        if ("adaptive".equalsIgnoreCase(strategy)) {
            return verifyAdaptive(job, tr, exec, model, plan, planText, request, change, cl, Math.max(1, cap), p0);
        }
        String focus = change == null ? null : "FOCUS: the user just asked to change the build: \"" + Prompts.escape(change)
                + "\". Verify this change is fully and beautifully implemented and nothing else got broken.";
        return verifyFixed(job, tr, exec, model, plan, planText, request, focus, cap, p0);
    }

    /**
     * Adaptive verification: reviewers work through the checklist and decide themselves when everything is
     * verified. A reviewer is finished when it reports done with no failed items and (almost) no changes; finished
     * reviewers are not called again. When all are finished but checklist items are still unverified (or critical
     * automated issues exist) the responsible reviewer is reopened. {@code cap} bounds the number of rounds.
     */
    private Verdict verifyAdaptive(Job job, Transcript tr, OpExecutor exec, VoxelModel model, JsonObject plan,
                                   String planText, String request, String change, Checklist cl, int cap, double p0) throws Exception {
        PluginConfig c = cfg.get();
        AnalyzerOptions aopts = analyzerOptions(plan);
        Verdict v = new Verdict();
        Build build = store.get(job.buildId);
        List<String> roles = change != null ? List.of("change")
                : job.mode.parallelReview ? List.of("structure", "exterior", "interior") : List.of("all");
        Map<String, Boolean> finished = new java.util.LinkedHashMap<>();
        for (String r : roles) finished.put(r, false);
        Map<String, Integer> lastScore = new java.util.LinkedHashMap<>();
        List<String> history = new ArrayList<>();
        List<String> summaries = new ArrayList<>();
        double span = (0.97 - p0) / Math.max(1, cap);
        int round = 0;
        boolean stalled = false;
        ReviewSchedule schedule = new ReviewSchedule();
        VoxelModel previousReview = null;
        List<int[]> pendingRegions = List.of();
        while (round < cap) {
            checkCancelled(job);
            AnalysisReport rep = StructureAnalyzer.analyze(model, info, aopts);
            List<String> active = new ArrayList<>();
            for (Map.Entry<String, Boolean> e : finished.entrySet()) if (!e.getValue()) active.add(e.getKey());
            if (active.isEmpty()) {
                boolean critical = rep.hasBlocking();
                List<String> open = cl.openAreas();
                if (open.isEmpty() && !critical && schedule.assessedCurrentModel()) break;
                for (String a : open) {
                    String r = roles.contains(a) ? a : roles.contains("structure") ? "structure" : roles.get(0);
                    finished.put(r, false);
                }
                if (critical) finished.put(roles.contains("structure") ? "structure" : roles.get(0), false);
                for (Map.Entry<String, Boolean> e : finished.entrySet()) if (!e.getValue()) active.add(e.getKey());
                if (active.isEmpty()) break;
                history.add("program: reviewers reopened for unverified checklist items / critical findings: " + active);
            }
            round++;
            job.status("Самопроверка ИИ, раунд " + round + " · чек-лист " + cl.progress() + " · экспертов в работе "
                    + active.size() + "/" + roles.size(), p0 + span * (round - 1));
            ReviewContext context = ReviewContext.create(model, previousReview, info, rep,
                    schedule.requireFull(round, cap) || round > 1 && cl.allVerified(), pendingRegions);
            job.status((context.full ? "Общая проверка ИИ" : "Проверка исправлений ИИ") + ", раунд " + round
                    + " · чек-лист " + cl.progress(), p0 + span * (round - 1));
            String render = context.text;
            JsonObject contextMetric = new JsonObject();
            contextMetric.addProperty("round", round);
            contextMetric.addProperty("scope", context.full ? "full" : "focused");
            contextMetric.addProperty("characters", render.length());
            contextMetric.addProperty("fullCharacters", context.fullCharacters);
            contextMetric.addProperty("reason", context.reason);
            v.reviewContexts.add(contextMetric);
            tr.note("areview" + round + " context", Json.GSON.toJson(contextMetric));
            String analysis = withAutoNotes(job, rep.toText());
            String hist = String.join("\n", history.subList(Math.max(0, history.size() - roles.size()*2), history.size()));
            String effort = round == 1 ? job.mode.effortReview : job.mode.effortReviewFollowup;
            List<Future<JsonObject>> futures = new ArrayList<>();
            List<String> tags = new ArrayList<>();
            for (String role : active) {
                String title, instr;
                switch (role) {
                    case "change" -> {
                        title = Prompts.EDIT_REVIEWER_TITLE;
                        instr = Prompts.editReviewerInstructions(change);
                    }
                    case "all" -> {
                        title = "SENIOR REVIEWER";
                        instr = "Your area: everything — correctness, exterior, interior.";
                    }
                    default -> {
                        Prompts.ReviewRole rr = Prompts.ReviewRole.valueOf(role.toUpperCase(Locale.ROOT));
                        title = rr.title;
                        instr = rr.instructions;
                    }
                }
                String checklistText = cl.render(role.equals("all") || role.equals("change") ? null : role);
                String user = Prompts.reviewAdaptive(title, instr, request, planText, round, render, analysis,
                        checklistText, hist, c.minScore) + Prompts.reviewScope(context.full);
                String tag = "areview" + round + "-" + role;
                tags.add(tag);
                futures.add(side.submit(() -> callJson(job, tr, tag, effort, user)));
            }
            int changed = 0, issues = 0;
            VoxelModel reviewSnapshot = model.copy();
            List<int[]> nextRegions = new ArrayList<>();
            boolean requestFull = false;
            Map<String,JsonArray> roundProofs = new java.util.LinkedHashMap<>();
            for (int k = 0; k < futures.size(); k++) {
                String role = active.get(k);
                JsonObject res = await(futures.get(k), job);
                requestFull |= Json.bool(res, "needs_full_review", false);
                nextRegions.addAll(ReviewContext.issueRegions(res));
                v.reviewerCalls++;
                VoxelModel proposed = reviewSnapshot.copy();
                int baseBlocks = Math.max(1, reviewSnapshot.nonAirCount());
                OpResult r = applyWithRepair(job, tr, exec, proposed, res, tags.get(k));
                if (r.cellsChanged > 2000 && r.cellsChanged > 0.35 * baseBlocks) {
                    plugin.getLogger().warning("[" + tags.get(k) + "] review rewrote " + r.cellsChanged + " of " + baseBlocks + " blocks - rejected");
                    finished.put(role, false);
                    v.blockers.put(role,"review patch exceeded the allowed rewrite size");
                    history.add("round " + round + " " + role + ": response REJECTED (rewrote " + r.cellsChanged + " blocks) - polish, do not rebuild");
                    continue;
                }
                postProcess(job, proposed);
                if (!context.permits(reviewSnapshot, proposed)) {
                    finished.put(role, false);
                    requestFull = true;
                    v.blockers.put(role, "repair outside inspected region; full assessment required");
                    history.add("round " + round + " " + role + ": patch rejected outside supplied regions; inspect full model next round");
                    continue;
                }
                AnalysisReport candidateReport=StructureAnalyzer.analyze(proposed,info,aopts);
                if (r.opsFailed>0 || ReviewDecision.introducesBlocking(rep,candidateReport)) {
                    finished.put(role,false);
                    v.blockers.put(role,"repair had failed operations or introduced structural defects");
                    history.add("round "+round+" "+role+": patch rejected; failed operations or new structural defects. Correct the patch against current blocks.");
                    continue;
                }
                ReviewPatch.Result merged = ReviewPatch.merge(reviewSnapshot, proposed, model);
                r.cellsChanged = merged.changed();
                PatchProofs.Result proofs = PatchProofs.verify(Json.arr(res,"checked"),model,info);
                if (proofs.certified()>0) roundProofs.put(role,Json.arr(res,"checked"));
                int failed = merged.conflicts() == 0 && r.opsFailed == 0
                        ? cl.apply(proofs.checked(), round, role) : 1;
                boolean proven = Json.bool(res,"done_after_proofs",false) && proofs.certified()>0
                        && !StructureAnalyzer.analyze(model,info,aopts).hasBlocking();
                if (proofs.certified()>0) tr.note(tags.get(k)+" proofs",proofs.certified()+" checklist corrections verified against actual placed-model block states");
                if (merged.conflicts() > 0) history.add("round " + round + " " + role + ": " + merged.conflicts()
                        + " stale patch cells skipped; another expert already changed them. Inspect the updated model next round.");
                int sc = Json.integer(res, "score", -1);
                if (sc >= 0) lastScore.put(role, sc);
                int iss = Json.arr(res, "issues").size();
                List<String> qualityFailures = ReviewDecision.qualityFailures(res,role);
                JsonObject assessment=Json.obj(res,"quality");
                if (assessment!=null) v.quality.add(role,assessment.deepCopy());
                boolean fin = failed == 0 && ReviewDecision.canFinish(res, proofs.checked(),role,c.minScore,
                        r.opsFailed,merged.conflicts(),cl.allVerified(role),proven)
                        && (!context.full || cl.coveredBy(proofs.checked(),role));
                if (!qualityFailures.isEmpty()) history.add("round "+round+" "+role+" quality remains: "+String.join("; ",qualityFailures));
                finished.put(role, fin);
                if (fin) v.blockers.remove(role);
                else {
                    List<String> pending=new ArrayList<>(qualityFailures);
                    if (ReviewDecision.hasUnresolvedSerious(res,proofs.checked())) pending.add("unresolved critical/major reviewer finding");
                    if (!cl.allVerified(role)) pending.add("checklist not fully verified");
                    if (context.full && !cl.coveredBy(proofs.checked(),role)) pending.add("whole-build assessment omitted checklist items");
                    if (merged.conflicts()>0) pending.add("conflicting repair needs inspection");
                    if (sc<c.minScore) pending.add("score below acceptance threshold");
                    v.blockers.put(role,pending.isEmpty()?"reviewer has not verified completion":String.join("; ",pending));
                }
                changed += r.cellsChanged;
                issues += iss;
                String sm = Json.str(res, "summary", "");
                if (!sm.isBlank()) summaries.add(sm);
                StringBuilder dis = new StringBuilder();
                for (JsonElement d : Json.arr(res, "dismissed")) {
                    if (d.isJsonObject()) dis.append(Json.str(d.getAsJsonObject(), "code", "?")).append(' ');
                }
                history.add("round " + round + " " + role + ": " + iss + " issues, " + failed
                        + " checklist items failed, changed " + r.cellsChanged + " blocks, " + (fin ? "DONE" : "continues")
                        + (dis.isEmpty() ? "" : ", dismissed: " + dis.toString().trim()) + " - " + clip(sm, 160));
            }
            postProcess(job,model);
            changed = ReviewPatch.changedCells(reviewSnapshot, model);
            previousReview = reviewSnapshot;
            pendingRegions = nextRegions;
            for (var entry : roundProofs.entrySet()) {
                PatchProofs.Result finalProofs = PatchProofs.verify(entry.getValue(),model,info);
                if (cl.apply(finalProofs.checked(),round,entry.getKey())>0) finished.put(entry.getKey(),false);
            }
            v.rounds = round;
            v.issuesFixed += issues;
            int fin = 0;
            for (boolean b : finished.values()) if (b) fin++;
            schedule.record(context.full, changed, fin == roles.size() && !(changed == 0 && rep.hasBlocking()), requestFull);
            contextMetric.addProperty("changedCells", changed);
            contextMetric.addProperty("wholeBuildAssessed", schedule.assessedCurrentModel());
            if (!schedule.assessedCurrentModel()) {
                finished.replaceAll((role, value) -> false);
                v.blockers.put("review:whole", "final whole-build assessment of the current model is pending");
            } else {
                v.blockers.remove("review:whole");
            }
            if (!lastScore.isEmpty()) v.score = (int) Math.round(lastScore.values().stream().mapToInt(Integer::intValue).average().orElse(-1));
            stageOf(job, "review", "Self-review round " + round);
            say(job, "<gray>🔍 Проверка, раунд <white><v0></white>: чек-лист <v1>, экспертов завершило <v2>/<v3>, исправлено блоков <v4>, оценка <v5><v6></gray>",
                    round, cl.progress(), fin, roles.size(), changed, scoreColor(v.score),
                    schedule.assessedCurrentModel() ? "" : " · общая проверка ещё не пройдена");
            liveModel(job, build, model, "critic");
            tr.note("areview" + round + " state", String.join("\n", history) + "\n\nCHECKLIST\n" + cl.render(null));
            if (context.full && changed==0 && finished.containsValue(false)) {
                stalled=true;
                tr.note("verification stopped","No model changes and acceptance still fails; stop retrying unchanged evidence.");
                break;
            }
        }
        boolean allFinished = !finished.containsValue(false);
        v.approved = allFinished && schedule.assessedCurrentModel() && cl.allVerified() && v.score >= c.minScore;
        v.summary = summaries.isEmpty() ? null : summaries.get(summaries.size() - 1);
        v.checklist = cl.progress() + (v.approved ? " (all verified)" : stalled ? " (no progress)" : allFinished ? "" : " (round cap reached)");
        v.finishAnalysis(StructureAnalyzer.analyze(model, info, aopts));
        return v;
    }

    private Verdict verifyFixed(Job job, Transcript tr, OpExecutor exec, VoxelModel model, JsonObject plan, String planText,
                               String request, String focus, int rounds, double p0) throws Exception {
        PluginConfig c = cfg.get();
        AnalyzerOptions aopts = analyzerOptions(plan);
        Verdict v = new Verdict();
        double span = (0.97 - p0) / Math.max(1, rounds);
        // creation: three specialist reviewers in parallel (faster, deeper); edits: one change-focused reviewer
        boolean parallel = focus == null && job.mode.parallelReview;
        Build build = store.get(job.buildId);
        int startBlocks = Math.max(1, model.nonAirCount());
        int cumulative = 0;
        for (int round = 1; round <= rounds; round++) {
            checkCancelled(job);
            AnalysisReport rep = StructureAnalyzer.analyze(model, info, aopts);
            VoxelModel roundSnapshot = model.copy();
            job.status("Самопроверка ИИ " + round + "/" + rounds + (parallel ? " (3 эксперта)" : "")
                    + " · проблем найдено автоматически: " + rep.issues.size(), p0 + span * (round - 1));
            stageOf(job, "review-" + round, "Review " + round + "/" + rounds
                    + (parallel ? " (3 experts)" : "") + " · " + rep.issues.size() + " issues found");
            String render = render(model);
            String analysis = withAutoNotes(job, rep.toText());
            List<String> tags = new ArrayList<>();
            List<Future<JsonObject>> futures = new ArrayList<>();
            String effort = round == 1 ? job.mode.effortReview : job.mode.effortReviewFollowup;
            if (parallel) {
                for (Prompts.ReviewRole role : Prompts.ReviewRole.values()) {
                    String tag = "review" + round + "-" + role.name().toLowerCase(Locale.ROOT);
                    String user = Prompts.reviewAs(role, request, planText, round, rounds, render, analysis, c.minScore);
                    tags.add(tag);
                    futures.add(side.submit(() -> callJson(job, tr, tag, effort, user)));
                }
            } else {
                String tag = "review" + round;
                String user = Prompts.review(request, planText, round, rounds, render, analysis, focus, c.minScore);
                tags.add(tag);
                futures.add(side.submit(() -> callJson(job, tr, tag, effort, user)));
            }
            int scoreSum = 0, scored = 0, issues = 0, changed = 0;
            boolean allApproved = true;
            List<String> summaries = new ArrayList<>();
            for (int k = 0; k < futures.size(); k++) {
                JsonObject res = await(futures.get(k), job);
                int sc = Json.integer(res, "score", -1);
                if (sc >= 0) {
                    scoreSum += sc;
                    scored++;
                }
                String role = parallel ? Prompts.ReviewRole.values()[k].name().toLowerCase(Locale.ROOT) : "all";
                boolean roleApproved = Json.bool(res, "approved", false) && sc >= c.minScore
                        && ReviewDecision.qualityFailures(res,role).isEmpty()
                        && !ReviewDecision.hasUnresolvedSerious(res,new JsonArray());
                allApproved &= roleApproved;
                if (roleApproved) v.blockers.remove(role);
                else v.blockers.put(role,"review approval, quality criteria or unresolved findings did not pass");
                String sm = Json.str(res, "summary", null);
                if (sm != null && !sm.isBlank()) summaries.add(sm);
                issues += Json.arr(res, "issues").size();
                // guard: a single review must polish, not rebuild — reject responses that rewrite too much
                VoxelModel before = model.copy();
                int baseBlocks = Math.max(1, before.nonAirCount());
                OpResult r = applyWithRepair(job, tr, exec, model, res, tags.get(k));
                if (r.opsFailed>0 || r.cellsChanged>0) allApproved=false;
                boolean overBudget = cumulative + r.cellsChanged > Math.max(300, 0.5 * startBlocks);
                if ((r.cellsChanged > 2000 && r.cellsChanged > 0.35 * baseBlocks) || overBudget) {
                    plugin.getLogger().warning("[" + tags.get(k) + "] review rewrote " + r.cellsChanged + " of " + baseBlocks
                            + " blocks (cumulative " + (cumulative + r.cellsChanged) + "/" + startBlocks + ") - rejected as destructive");
                    tr.note(tags.get(k) + " rejected", "review changed " + r.cellsChanged + " cells of " + baseBlocks + " - reverted");
                    model.clear();
                    before.forEach(model::set);
                    allApproved = false;
                    continue;
                }
                changed += r.cellsChanged;
                cumulative += r.cellsChanged;
                postProcess(job, model);
            }
            v.rounds = round;
            if (scored > 0) v.score = Math.round((float) scoreSum / scored);
            changed = ReviewPatch.changedCells(roundSnapshot,model);
            v.approved = allApproved && changed == 0;
            if (!v.approved) v.blockers.put("review:whole","final whole-build assessment of the current model is pending");
            else v.blockers.remove("review:whole");
            if (!summaries.isEmpty()) v.summary = String.join(" ", summaries);
            v.issuesFixed += issues;
            say(job, v.approved
                            ? "<gray>🔍 Проверка <white><v0>/<v1></white>: оценка <v2>, замечаний <v3>, исправлено блоков <v4> — <green>одобрено</green></gray>"
                            : "<gray>🔍 Проверка <white><v0>/<v1></white>: оценка <v2>, замечаний <v3>, исправлено блоков <v4> — <yellow>нужны доработки</yellow></gray>",
                    round, rounds, scoreColor(v.score), issues, changed);
            liveModel(job, build, model, "critic");
            boolean blocking = StructureAnalyzer.analyze(model, info, aopts).hasBlocking();
            tr.note("review" + round + " verdict", "score=" + v.score + " approved=" + v.approved + " issues=" + issues
                    + " changed=" + changed + " blockingAfter=" + blocking);
            if (v.approved && v.score >= c.minScore && !blocking) break;
        }
        v.finishAnalysis(StructureAnalyzer.analyze(model, info, aopts));
        return v;
    }

    // =================================================================== benchmarks (A/B on identical inputs)

    /** Metrics of one benchmark variant. */
    static final class Variant {
        String name;
        long seconds;
        int calls;
        long promptTokens, completionTokens, reasoningTokens;
        int rounds;
        int aiScore = -1;
        boolean approved;
        String checklist = "";
        int critical, major, minor, detail, blocks, changedVsStart;
        String error;
        VoxelModel model;
        JsonObject judge;
        int judgeTotal = -1;
        String reason;
    }

    private interface VariantBody {
        Verdict run(Job child, Transcript tr, VoxelModel model) throws Exception;
    }

    private final java.util.Random benchRandom = new java.util.Random();

    /** Review-strategy benchmark: one shared plan+construction, then fixed vs adaptive verification from the same model. */
    public String startReviewBench(String prompt, BuildMode mode) {
        String id = "bench-" + Integer.toHexString(benchRandom.nextInt(0xFFFFFF) | 0x100000);
        Job job = new Job(id, null, Job.Kind.BENCH, prompt, mode);
        job.quiet = true;
        jobsByBuild.put(id, job);
        job.future = pool.submit(() -> {
            try {
                runReviewBench(job, id, prompt);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "bench " + id + " failed", e);
                Msg.to(null, "<red>Бенчмарк <v0> упал: <v1></red>", id, shortError(e));
            } finally {
                job.finished = true;
                jobsByBuild.remove(id, job);
            }
        });
        return id;
    }

    /** Edit benchmark: old pipeline (direct + fixed) vs new pipeline (planned + adaptive) on the build's current model. */
    public String startEditBench(Build build, String change, BuildMode mode) {
        String id = "bench-" + Integer.toHexString(benchRandom.nextInt(0xFFFFFF) | 0x100000);
        VoxelModel start = build.currentModel().copy();
        JsonObject plan = build.plan == null ? syntheticPlan(build.name) : build.plan.deepCopy();
        String original = build.originalPrompt == null ? Json.str(plan, "title", "") : build.originalPrompt;
        String history = String.join(" | ", build.history);
        Job job = new Job(id, null, Job.Kind.BENCH, change, mode);
        job.quiet = true;
        jobsByBuild.put(id, job);
        job.future = pool.submit(() -> {
            try {
                runEditBench(job, id, build.id, start, plan, original, history, change);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "bench " + id + " failed", e);
                Msg.to(null, "<red>Бенчмарк <v0> упал: <v1></red>", id, shortError(e));
            } finally {
                job.finished = true;
                jobsByBuild.remove(id, job);
            }
        });
        return id;
    }

    /**
     * Creation benchmark: the classic compiled JSON-op pipeline and the build-script pipeline run the same prompt
     * in parallel, nothing is placed. Writes both models, real-colour render sheets, metrics and a report;
     * {@code tools/blind_ballot.py} turns any number of these into one blind human ballot.
     */
    /** Pipelines /aib bench create can compare. */
    public static final List<String> BENCH_VARIANTS = List.of("script-v2", "script-v3", "script-v3.1", "compiled");

    public String startCreateBench(String prompt, BuildMode mode) {
        return startCreateBench(prompt, mode, "script-v2", "script-v3.1");
    }

    public String startCreateBench(String prompt, BuildMode mode, String va, String vb) {
        if (!BENCH_VARIANTS.contains(va) || !BENCH_VARIANTS.contains(vb) || va.equals(vb)) {
            throw new IllegalArgumentException("варианты бенчмарка: " + String.join(", ", BENCH_VARIANTS) + " (два разных)");
        }
        String id = "bench-" + Integer.toHexString(benchRandom.nextInt(0xFFFFFF) | 0x100000);
        Job job = new Job(id, null, Job.Kind.BENCH, prompt, mode);
        job.quiet = true;
        jobsByBuild.put(id, job);
        job.future = pool.submit(() -> {
            try {
                runCreateBench(job, id, prompt, va, vb);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "bench " + id + " failed", e);
                Msg.to(null, "<red>Бенчмарк <v0> упал: <v1></red>", id, shortError(e));
            } finally {
                job.finished = true;
                jobsByBuild.remove(id, job);
            }
        });
        return id;
    }

    /** The 2.0 script pipeline exactly as shipped: library 2.0, low build effort, 60k tokens, two critic rounds, no sculptors. */
    private static BuildMode v2Mode(BuildMode m) {
        return new BuildMode(m.id, m.title, m.eta, m.effortPlan, BuildMode.STANDARD.equals(m.id) ? "low" : m.effortBuild,
                m.effortReview, m.effortReviewFollowup, m.effortEdit, m.verifyRounds, m.editVerifyRounds,
                BuildMode.STANDARD.equals(m.id) ? 60000 : m.maxTokens, m.parallelReview);
    }

    /** The 3.0 efforts as first shipped: no plan thinking, medium (standard) or high (extra) scripts, 120k/200k tokens. */
    private static BuildMode v3Mode(BuildMode m) {
        boolean std = BuildMode.STANDARD.equals(m.id), extra = BuildMode.EXTRA.equals(m.id);
        if (!std && !extra) return m;
        return new BuildMode(m.id, m.title, m.eta, std ? "off" : "low", std ? "medium" : "high", m.effortReview,
                m.effortReviewFollowup, m.effortEdit, m.verifyRounds, m.editVerifyRounds, std ? 120000 : 200000, m.parallelReview);
    }

    private Future<Variant> benchVariant(Job job, String id, String name, String prompt) {
        PluginConfig c = cfg.get();
        return side.submit(() -> {
            if (name.equals("compiled")) {
                return runVariant(job, id, name, new VoxelModel(), new JsonObject(), (cj, ctr, m) -> {
                    Staged st = designAndCompile(cj, ctr, prompt, null, null, false);
                    String strategy = c.reviewStrategy;
                    int cap = "adaptive".equals(strategy) ? c.adaptiveMaxRounds : cj.mode.verifyRounds;
                    Verdict v = verify(cj, ctr, new OpExecutor(info).withLimits(st.limits), st.model, st.plan, st.planText, prompt, null,
                            Checklist.forCreate(st.plan), strategy, cap, 0.68);
                    m.clear();
                    st.model.forEach(m::set);
                    return v;
                });
            }
            boolean v2 = name.equals("script-v2");
            return runVariant(job, id, name, new VoxelModel(), new JsonObject(), v2 ? v2Mode(job.mode) : name.equals("script-v3") ? v3Mode(job.mode) : job.mode, (cj, ctr, m) -> {
                if (v2) {
                    cj.stdlib = dev.aibuild.script.Stdlib.V1;
                    cj.criticRounds = Math.min(2, c.criticRounds(cj.mode.id) + 1);
                }
                // 3.0 as first shipped: its efforts, no design concepts, sculptors redesign their part; 3.1 adds both
                boolean v31 = name.equals("script-v3.1");
                if (name.startsWith("script-v3")) {
                    cj.concepts = v31;
                    cj.sculptRefine = v31;
                    cj.interiors = v31;
                }
                Staged st = designAndScript(cj, ctr, prompt, null, null, false);
                Verdict v = cj.script != null ? verifyScript(cj, ctr, null, st, prompt) : new Verdict();
                m.clear();
                st.model.forEach(m::set);
                return v;
            });
        });
    }

    /**
     * Runs the fixed prompt suite ({@code plugins/AIBuild/bench_suite.txt}, else the bundled one) one bench after
     * another in the background, {@code repeat} times each. Returns the number of benches queued.
     */
    public int startBenchSuite(BuildMode mode, String va, String vb, int repeat) {
        if (!BENCH_VARIANTS.contains(va) || !BENCH_VARIANTS.contains(vb) || va.equals(vb)) {
            throw new IllegalArgumentException("варианты бенчмарка: " + String.join(", ", BENCH_VARIANTS) + " (два разных)");
        }
        List<String> prompts = new ArrayList<>();
        try {
            File custom = new File(plugin.getDataFolder(), "bench_suite.txt");
            List<String> lines;
            if (custom.isFile()) lines = Files.readAllLines(custom.toPath(), StandardCharsets.UTF_8);
            else try (var in = BuildPipeline.class.getResourceAsStream("/bench_suite.txt")) {
                lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
            }
            for (String l : lines) if (!l.isBlank() && !l.startsWith("#")) prompts.add(l.strip());
        } catch (IOException | NullPointerException e) {
            throw new IllegalStateException("нет набора запросов: " + e.getMessage());
        }
        String suiteId = "suite-" + Integer.toHexString(benchRandom.nextInt(0xFFFFFF) | 0x100000);
        Job job = new Job(suiteId, null, Job.Kind.BENCH, "suite", mode);
        job.quiet = true;
        jobsByBuild.put(suiteId, job);
        int total = prompts.size() * repeat;
        job.future = pool.submit(() -> {
            int done = 0;
            try {
                for (int r = 0; r < repeat; r++) {
                    for (String p : prompts) {
                        if (job.cancelled.get()) return;
                        String id = "bench-" + Integer.toHexString(benchRandom.nextInt(0xFFFFFF) | 0x100000);
                        job.status("Набор бенчмарков: " + (done + 1) + " из " + total + " — " + clip(p, 50), (double) done / total);
                        try {
                            runCreateBench(job.child(id.substring(6)), id, p, va, vb);
                        } catch (Exception e) {
                            plugin.getLogger().log(Level.WARNING, "suite bench " + id + " failed", e);
                        }
                        done++;
                    }
                }
                Msg.to(null, "<green>Набор бенчмарков <v0> готов</green> <gray>(<v1> сравнений). Голосование: "
                        + "python3 tools/blind_ballot.py make plugins/AIBuild/bench/bench-* --output ballot</gray>", suiteId, done);
            } finally {
                job.finished = true;
                jobsByBuild.remove(suiteId, job);
            }
        });
        return total;
    }

    private void runCreateBench(Job job, String id, String prompt, String va, String vb) throws Exception {
        PluginConfig c = cfg.get();
        job.status("Бенчмарк: " + va + " и " + vb + " параллельно", 0.05);
        Future<Variant> fa = benchVariant(job, id, va, prompt), fb = benchVariant(job, id, vb, prompt);
        Variant a = await(fa, job), b = await(fb, job);
        StringBuilder md = new StringBuilder("# " + id + " — " + va + " vs " + vb + "\n\n* Запрос: " + prompt + "\n* Режим: " + job.mode.id
                + "\n* Модель: " + c.model + "\n* Рендер: " + (dev.aibuild.analysis.IsoRenderer.hasTextureColours() ? "цвета текстур клиента" : "приблизительные цвета") + "\n\n");
        md.append("| вариант | блоков | время, с | вызовов | токены вход/выход/мысли | critical | major | раундов | одобрено | ошибка |\n|---|---|---|---|---|---|---|---|---|---|\n");
        for (Variant v : List.of(a, b)) {
            md.append("| ").append(v.name).append(" | ").append(v.blocks).append(" | ").append(v.seconds).append(" | ").append(v.calls)
                    .append(" | ").append(v.promptTokens).append('/').append(v.completionTokens).append('/').append(v.reasoningTokens)
                    .append(" | ").append(v.critical).append(" | ").append(v.major).append(" | ").append(v.rounds)
                    .append(" | ").append(v.approved).append(" | ").append(v.error == null ? "" : v.error).append(" |\n");
            if (v.model != null && !v.model.isEmpty()) {
                try {
                    dev.aibuild.analysis.IsoRenderer.writePng(ContactSheet.overview(v.model, info, 2400),
                            new File(benchDir(id), "strategy-" + v.name + ".png"));
                } catch (IOException e) {
                    plugin.getLogger().log(Level.WARNING, "bench render failed", e);
                }
            }
        }
        JsonObject meta = new JsonObject();
        meta.addProperty("prompt", prompt);
        meta.addProperty("mode", job.mode.id);
        meta.addProperty("model", c.model);
        for (Variant v : List.of(a, b)) {
            JsonObject o = new JsonObject();
            o.addProperty("blocks", v.blocks);
            o.addProperty("seconds", v.seconds);
            o.addProperty("calls", v.calls);
            o.addProperty("promptTokens", v.promptTokens);
            o.addProperty("completionTokens", v.completionTokens);
            o.addProperty("reasoningTokens", v.reasoningTokens);
            o.addProperty("critical", v.critical);
            o.addProperty("major", v.major);
            o.addProperty("approved", v.approved);
            if (v.error != null) o.addProperty("error", v.error);
            meta.add(v.name, o);
        }
        Files.writeString(new File(benchDir(id), "report.md").toPath(), md.toString(), StandardCharsets.UTF_8);
        meta.addProperty("a", a.name);
        meta.addProperty("b", b.name);
        Files.writeString(new File(benchDir(id), "bench.json").toPath(), Json.PRETTY.toJson(meta), StandardCharsets.UTF_8);
        Msg.to(null, "<green>Бенчмарк <v0> готов:</green> <gray><v1> <v2> бл. за <v3> с, <v4> <v5> бл. за <v6> с. "
                + "Рендеры: plugins/AIBuild/bench/<v0>/ (слепое голосование: tools/blind_ballot.py)</gray>",
                id, a.name, a.blocks, a.seconds, b.name, b.blocks, b.seconds);
    }

    private void runReviewBench(Job job, String id, String prompt) throws Exception {
        PluginConfig c = cfg.get();
        Transcript tr = new Transcript(id + "/build", true);
        long t0 = System.currentTimeMillis();
        Staged st = designAndStage(job, tr, prompt, null, null, false);
        long buildSecs = (System.currentTimeMillis() - t0) / 1000;
        writeModel(id, "start", st.model);
        Checklist proto = Checklist.forCreate(st.plan);
        int checklistSize = proto.size();
        job.status("Бенчмарк: две стратегии проверки параллельно", 0.7);
        Future<Variant> fFixed = side.submit(() -> runVariant(job, id, "fixed", st.model, st.plan, (cj, ctr, m) ->
                verify(cj, ctr, new OpExecutor(info).withLimits(st.limits), m, st.plan, st.planText, prompt, null,
                        Checklist.forCreate(st.plan), "fixed", job.mode.verifyRounds, 0.7)));
        Future<Variant> fAdaptive = side.submit(() -> runVariant(job, id, "adaptive", st.model, st.plan, (cj, ctr, m) ->
                verify(cj, ctr, new OpExecutor(info).withLimits(st.limits), m, st.plan, st.planText, prompt, null,
                        Checklist.forCreate(st.plan), "adaptive", c.adaptiveMaxRounds, 0.7)));
        Variant a = await(fFixed, job), b = await(fAdaptive, job);
        job.status("Бенчмарк: слепое сравнение судьёй", 0.95);
        String[] verdict = judge(job, id, prompt, null, null, a, b);
        writeReport(id, "Стратегии самопроверки (fixed vs adaptive)", prompt, null, job.mode,
                "Общий план и этапы: " + buildSecs + " c, " + job.calls.get() + " вызовов, " + st.model.nonAirCount()
                        + " блоков; чек-лист: " + checklistSize + " пунктов", a, b, verdict);
    }

    private void runEditBench(Job job, String id, String buildId, VoxelModel start, JsonObject plan, String original,
                              String history, String change) throws Exception {
        PluginConfig c = cfg.get();
        writeModel(id, "start", start);
        job.status("Бенчмарк правки: старая и новая схема параллельно", 0.1);
        Future<Variant> fOld = side.submit(() -> runVariant(job, id, "direct+fixed", start, plan, (cj, ctr, m) -> {
            JsonObject p = plan.deepCopy();
            EditResult er = editDirect(cj, ctr, m, p, original, history, change, null);
            if (er.rebuildRequest != null) throw new IllegalStateException("модель запросила перестройку: " + er.rebuildRequest);
            return verify(cj, ctr, new OpExecutor(info).withLimits(cj.plotLimits), er.model, er.plan, er.planText, original,
                    change, er.checklist, "fixed", job.mode.editVerifyRounds, 0.55);
        }));
        Future<Variant> fPlan = side.submit(() -> runVariant(job, id, "plan-once+adaptive", start, plan, (cj, ctr, m) -> {
            JsonObject p = plan.deepCopy();
            EditResult er = editPlanOnce(cj, ctr, m, p, original, history, change, null);
            if (er.rebuildRequest != null) throw new IllegalStateException("модель запросила перестройку: " + er.rebuildRequest);
            return verify(cj, ctr, new OpExecutor(info).withLimits(cj.plotLimits), er.model, er.plan, er.planText, original,
                    change, er.checklist, "adaptive", c.adaptiveMaxRounds, 0.55);
        }));
        Future<Variant> fSteps = side.submit(() -> runVariant(job, id, "steps+adaptive", start, plan, (cj, ctr, m) -> {
            JsonObject p = plan.deepCopy();
            EditResult er = editSteps(cj, ctr, m, p, original, history, change, null);
            if (er.rebuildRequest != null) throw new IllegalStateException("модель запросила перестройку: " + er.rebuildRequest);
            return verify(cj, ctr, new OpExecutor(info).withLimits(cj.plotLimits), er.model, er.plan, er.planText, original,
                    change, er.checklist, "adaptive", c.adaptiveMaxRounds, 0.55);
        }));
        Variant a = await(fOld, job), b = await(fPlan, job), cc = await(fSteps, job);
        job.status("Бенчмарк: слепое сравнение судьёй", 0.95);
        Variant best = judge3(job, id, original, change, start, a, b, cc);
        writeReport3(id, "Схемы правки (direct+fixed vs plan-once+adaptive vs steps+adaptive)", original, change, job.mode,
                "Исходная постройка " + buildId + ": " + start.nonAirCount() + " блоков; победитель: " + best.name,
                List.of(a, b, cc));
    }

    private Variant runVariant(Job parent, String benchId, String name, VoxelModel start, JsonObject plan, VariantBody body) {
        return runVariant(parent, benchId, name, start, plan, parent.mode, body);
    }

    private Variant runVariant(Job parent, String benchId, String name, VoxelModel start, JsonObject plan, BuildMode mode, VariantBody body) {
        Variant vr = new Variant();
        vr.name = name;
        Job child = parent.child(name.replace('+', '-'), mode);
        Transcript tr = new Transcript(benchId + "/" + name.replace('+', '-'), true);
        VoxelModel model = start.copy();
        long t0 = System.currentTimeMillis();
        try {
            Verdict v = body.run(child, tr, model);
            vr.rounds = v.rounds;
            vr.aiScore = v.score;
            vr.approved = v.approved;
            vr.checklist = v.checklist;
        } catch (Exception e) {
            vr.error = shortError(e);
            plugin.getLogger().log(Level.WARNING, "bench variant " + name + " failed", e);
        }
        vr.seconds = (System.currentTimeMillis() - t0) / 1000;
        vr.calls = child.calls.get();
        vr.promptTokens = child.promptTokens.get();
        vr.completionTokens = child.completionTokens.get();
        vr.reasoningTokens = child.reasoningTokens.get();
        AnalysisReport rep = StructureAnalyzer.analyze(model, info, analyzerOptions(plan));
        vr.critical = rep.count(dev.aibuild.analysis.Severity.CRITICAL);
        vr.major = rep.count(dev.aibuild.analysis.Severity.MAJOR);
        vr.minor = rep.count(dev.aibuild.analysis.Severity.MINOR);
        vr.detail = rep.metricInt("detailScore", -1);
        vr.blocks = model.nonAirCount();
        vr.changedVsStart = diffCount(start, model);
        vr.model = model;
        writeModel(benchId, name.replace('+', '-'), model);
        return vr;
    }

    private static int diffCount(VoxelModel a, VoxelModel b) {
        int n = 0;
        java.util.Set<Long> keys = new java.util.HashSet<>(a.entries().keySet());
        keys.addAll(b.entries().keySet());
        for (long k : keys) {
            BlockSpec x = a.entries().get(k), y = b.entries().get(k);
            boolean ax = x == null || x.isAir(), ay = y == null || y.isAir();
            if (ax && ay) continue;
            if (!java.util.Objects.equals(x, y)) n++;
        }
        return n;
    }

    /** Blind pairwise judgement (A/B order randomized). Returns {winnerName, reason}. */
    private String[] judge(Job parent, String benchId, String request, String change, VoxelModel before, Variant x, Variant y) {
        if (x.error != null || y.error != null) return new String[]{x.error != null ? y.name : x.name, "у другого варианта ошибка"};
        boolean swap = benchRandom.nextBoolean();
        Variant A = swap ? y : x, B = swap ? x : y;
        try {
            AnalyzerOptions ao = new AnalyzerOptions();
            String prompt = Prompts.judge(request, change, before == null ? null : render(before),
                    render(A.model), StructureAnalyzer.analyze(A.model, info, ao).toText(),
                    render(B.model), StructureAnalyzer.analyze(B.model, info, ao).toText());
            Job jj = new Job(benchId + "/judge", null, Job.Kind.BENCH, request, cfg.get().mode(BuildMode.EXTRA), parent.cancelled);
            jj.quiet = true;
            JsonObject res = callJson(jj, new Transcript(benchId + "/judge", true), "judge", cfg.get().judgeEffort, prompt);
            A.judge = Json.obj(res, "A");
            B.judge = Json.obj(res, "B");
            A.judgeTotal = Json.integer(A.judge, "total", -1);
            B.judgeTotal = Json.integer(B.judge, "total", -1);
            String w = Json.str(res, "winner", "tie").trim().toUpperCase(Locale.ROOT);
            String winner = w.startsWith("A") ? A.name : w.startsWith("B") ? B.name : "ничья";
            return new String[]{winner, Json.str(res, "reason", "") + " (порядок для судьи: A=" + A.name + ", B=" + B.name + ")"};
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "judge failed", e);
            return new String[]{"?", "судья не ответил: " + shortError(e)};
        }
    }

    /** Blind comparison of three variants (order randomized). */
    private Variant judge3(Job parent, String benchId, String request, String change, VoxelModel before, Variant... vs) {
        List<Variant> live = new ArrayList<>();
        for (Variant v : vs) if (v.error == null) live.add(v);
        if (live.size() < 2) return live.isEmpty() ? vs[0] : live.get(0);
        java.util.Collections.shuffle(live);
        StringBuilder parts = new StringBuilder();
        char letter = 'A';
        Map<String, Variant> byLetter = new java.util.LinkedHashMap<>();
        AnalyzerOptions ao = new AnalyzerOptions();
        for (Variant v : live) {
            parts.append("\n===== BUILD ").append(letter).append(" =====\n").append(render(v.model))
                    .append("\nAUTOMATED ANALYSIS ").append(letter).append(":\n")
                    .append(StructureAnalyzer.analyze(v.model, info, ao).toText());
            byLetter.put(String.valueOf(letter), v);
            letter++;
        }
        String judgePrompt = Prompts.judge(request, change, before == null ? null : render(before), parts.toString());
        try {
            Job jj = new Job(benchId + "/judge", null, Job.Kind.BENCH, request, cfg.get().mode(BuildMode.EXTRA), parent.cancelled);
            jj.quiet = true;
            JsonObject res = callJson(jj, new Transcript(benchId + "/judge", true), "judge", cfg.get().judgeEffort, judgePrompt);
            for (Map.Entry<String, Variant> e : byLetter.entrySet()) {
                JsonObject o = Json.obj(res, e.getKey());
                e.getValue().judge = o;
                e.getValue().judgeTotal = Json.integer(o, "total", -1);
            }
            String w = Json.str(res, "winner", "tie").trim().toUpperCase(Locale.ROOT);
            String first = w.replaceAll("[^A-Z].*$", "");
            Variant winner = byLetter.get(first.isEmpty() ? "A" : first);
            if (winner != null) winner.reason = Json.str(res, "reason", "");
            return winner != null ? winner : live.get(0);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "judge failed", e);
            return live.get(0);
        }
    }

    private void writeReport3(String id, String title, String request, String change, BuildMode mode, String shared,
                              List<Variant> vs) {
        StringBuilder md = new StringBuilder();
        md.append("# ").append(id).append(" — ").append(title).append("\n\n");
        md.append("* Запрос: ").append(request).append('\n');
        if (change != null) md.append("* Правка: ").append(change).append('\n');
        md.append("* Режим: ").append(mode.title).append(" (").append(mode.id).append(")\n");
        md.append("* ").append(shared).append("\n\n");
        md.append("| Метрика | ").append(String.join(" | ", vs.stream().map(v -> v.name).toList())).append(" |\n");
        md.append("|---|").append("---|".repeat(vs.size())).append('\n');
        rowN(md, "Время, c", vs, v -> v.seconds);
        rowN(md, "Вызовов модели", vs, v -> v.calls);
        rowN(md, "Токены: вход", vs, v -> v.promptTokens);
        rowN(md, "Токены: выход (из них мыслей)", vs, v -> v.completionTokens + " (" + v.reasoningTokens + ")");
        rowN(md, "Раундов проверки", vs, v -> v.rounds);
        rowN(md, "Самооценка ИИ", vs, v -> v.aiScore);
        rowN(md, "Одобрено / чек-лист", vs, v -> v.approved + " " + v.checklist);
        rowN(md, "Анализатор: крит/серьёзн/мелкие", vs, v -> v.critical + "/" + v.major + "/" + v.minor);
        rowN(md, "Изменено блоков от старта", vs, v -> v.changedVsStart);
        rowN(md, "Оценка судьи (0-100)", vs, v -> v.judgeTotal);
        for (String k : new String[]{"fulfilment", "structure", "orientation", "detail", "interior", "exterior", "beauty", "preservation"}) {
            if (vs.stream().anyMatch(v -> v.judge != null && v.judge.has(k))) rowN(md, "  судья: " + k, vs, v -> Json.integer(v.judge, k, -1));
        }
        for (Variant v : vs) if (v.error != null) md.append("\n* Ошибка ").append(v.name).append(": ").append(v.error);
        md.append("\n**Победитель по мнению судьи: ").append(vs.stream().filter(v -> v.judgeTotal >= 0).max((x, y) -> Integer.compare(x.judgeTotal, y.judgeTotal)).map(v -> v.name).orElse("?")).append("**");
        for (Variant v : vs) {
            if (v.reason != null && !v.reason.isBlank()) md.append("\n\n").append(v.name).append(": ").append(v.reason);
        }
        md.append('\n');
        for (Variant v : vs) md.append("\n* ").append(v.name).append(": ").append(Json.str(v.judge, "notes", "-"));
        try {
            Files.writeString(new File(benchDir(id), "report.md").toPath(), md.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "bench report write failed", e);
        }
        for (String line : md.toString().split("\n")) if (!line.isBlank()) plugin.getLogger().info("[" + id + "] " + line);
        Msg.to(null, "<green>Бенчмарк <v0> готов. Отчёт: plugins/AIBuild/bench/<v0>/report.md</green>", id);
    }

    private interface Metric {
        Object of(Variant v);
    }

    private static void rowN(StringBuilder md, String name, List<Variant> vs, Metric m) {
        md.append("| ").append(name).append(" |");
        for (Variant v : vs) md.append(' ').append(m.of(v)).append(" |");
        md.append('\n');
    }

    private File benchDir(String id) {
        File d = new File(plugin.getDataFolder(), "bench/" + id);
        d.mkdirs();
        return d;
    }

    private void writeModel(String id, String name, VoxelModel m) {
        try {
            Files.writeString(new File(benchDir(id), name + ".json").toPath(), Json.GSON.toJson(m.toJson()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "bench write failed", e);
        }
    }

    private void writeReport(String id, String title, String request, String change, BuildMode mode, String shared,
                             Variant a, Variant b, String[] verdict) {
        StringBuilder md = new StringBuilder();
        md.append("# ").append(id).append(" — ").append(title).append("\n\n");
        md.append("* Запрос: ").append(request).append('\n');
        if (change != null) md.append("* Правка: ").append(change).append('\n');
        md.append("* Режим: ").append(mode.title).append(" (").append(mode.id).append(")\n");
        md.append("* ").append(shared).append("\n\n");
        md.append("| Метрика | ").append(a.name).append(" | ").append(b.name).append(" |\n|---|---|---|\n");
        row(md, "Время, c", a.seconds, b.seconds);
        row(md, "Вызовов модели", a.calls, b.calls);
        row(md, "Токены: вход", a.promptTokens, b.promptTokens);
        row(md, "Токены: выход (из них мыслей)", a.completionTokens + " (" + a.reasoningTokens + ")", b.completionTokens + " (" + b.reasoningTokens + ")");
        row(md, "Раундов проверки", a.rounds, b.rounds);
        row(md, "Самооценка ИИ", a.aiScore, b.aiScore);
        row(md, "Одобрено / чек-лист", a.approved + " " + a.checklist, b.approved + " " + b.checklist);
        row(md, "Анализатор: крит/серьёзн/мелкие", a.critical + "/" + a.major + "/" + a.minor, b.critical + "/" + b.major + "/" + b.minor);
        row(md, "Детализация (метрика)", a.detail, b.detail);
        row(md, "Блоков", a.blocks, b.blocks);
        row(md, "Изменено блоков от старта", a.changedVsStart, b.changedVsStart);
        row(md, "Оценка судьи (0-100)", a.judgeTotal, b.judgeTotal);
        for (String k : new String[]{"fulfilment", "structure", "orientation", "detail", "interior", "exterior", "beauty", "preservation"}) {
            if ((a.judge != null && a.judge.has(k)) || (b.judge != null && b.judge.has(k))) {
                row(md, "  судья: " + k, Json.integer(a.judge, k, -1), Json.integer(b.judge, k, -1));
            }
        }
        if (a.error != null) md.append("\n* Ошибка ").append(a.name).append(": ").append(a.error);
        if (b.error != null) md.append("\n* Ошибка ").append(b.name).append(": ").append(b.error);
        md.append("\n**Победитель по мнению судьи: ").append(verdict[0]).append("** — ").append(verdict[1]).append('\n');
        md.append("\nЗаметки судьи:\n* ").append(a.name).append(": ").append(Json.str(a.judge, "notes", "-"))
                .append("\n* ").append(b.name).append(": ").append(Json.str(b.judge, "notes", "-")).append('\n');
        try {
            Files.writeString(new File(benchDir(id), "report.md").toPath(), md.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "bench report write failed", e);
        }
        for (String line : md.toString().split("\n")) if (!line.isBlank()) plugin.getLogger().info("[" + id + "] " + line);
        Msg.to(null, "<green>Бенчмарк <v0> готов: победитель — <white><v1></white>. Отчёт: plugins/AIBuild/bench/<v0>/report.md</green>", id, verdict[0]);
    }

    private static void row(StringBuilder md, String name, Object a, Object b) {
        md.append("| ").append(name).append(" | ").append(a).append(" | ").append(b).append(" |\n");
    }

    /** Chat/console progress message unless the job is quiet (benchmarks). */
    /**
     * Announces a clickable link. The URL is attached as a real click event rather than written into the
     * MiniMessage template, because a placeholder inside a tag argument is not substituted and the player would
     * see raw markup instead of a link.
     */
    private void sayLink(Job job, String template, String url, Object... args) {
        if (job.quiet) return;
        Msg.toLink(job.owner, template, url, args);
        LiveBroadcast lb = live(job);
        if (lb != LiveBroadcast.NOOP) lb.say(plainOf(template, args) + url);
        if (job.owner == null) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (var player : Bukkit.getOnlinePlayers()) {
                    // no permission filter: the link is already owner-only, this is the console fallback
                    Msg.sendLink(player, template, url, args);
                }
            });
        }
    }

    /** The template with {@code <vN>} filled in, for the page, which wants prose and not markup. */
    private static String plainOf(String template, Object... args) {
        String plain = template;
        for (int i = 0; i < args.length; i++) plain = plain.replace("<v" + i + ">", String.valueOf(args[i]));
        return plain;
    }

    private void say(Job job, String template, Object... args) {
        if (job.quiet) return;
        Msg.to(job.owner, template, args);
        LiveBroadcast lb = live(job);
        if (lb != LiveBroadcast.NOOP) {
            // the page wants the sentence, not the MiniMessage markup the chat layer understands
            String plain = template;
            for (int i = 0; i < args.length; i++) plain = plain.replace("<v" + i + ">", String.valueOf(args[i]));
            lb.say(plain);
        }
        if (job.owner == null) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                for (var player : Bukkit.getOnlinePlayers()) {
                    if (player.isOp() || player.hasPermission("aibuild.admin")) Msg.send(player, template, args);
                }
            });
        }
    }

    private static String scoreColor(int score) {
        return score < 0 ? "?" : score + "/100";
    }

    // =================================================================== commit

    private void commit(Job job, Build build, VoxelModel model, String request, Verdict v, Transcript tr) throws Exception {
        checkCancelled(job);
        job.status("Размещение постройки…", 0.97);
        stageOf(job, "place", "Placing the build");
        if (model.nonAirCount() == 0) throw new IllegalStateException("ИИ не создал ни одного блока");
        VoxelModel fin = model.copy();
        v.finishAnalysis(v.finalReport);
        if (!v.approved) {
            // One emit here and one after the branch was a duplicate: an unapproved build fell through and
            // showed the same model twice in the trace. Emit once, for both outcomes.
            writeDraft(build,fin,request,v);
            writeMetrics(job,build,fin,v,tr);
            if (!build.versions.isEmpty()) throw new VerificationRejectedException(v.failureReason());
        }
        live(job).model(fin, "final");
        PlaceResult pr = placer.place(build, fin, p -> job.progress = 0.97 + p * 0.0003).get(10, TimeUnit.MINUTES);
        checkCancelled(job);
        sync(() -> {
            BuildVersion ver = new BuildVersion();
            ver.number = build.versions.isEmpty() ? 1 : build.versions.get(build.versions.size() - 1).number + 1;
            ver.request = request;
            ver.summary = v.summary;
            ver.model = fin;
            ver.createdAt = System.currentTimeMillis();
            ver.score = v.score;
            ver.mode = job.mode.id;
            ver.approved = v.approved;
            if (job.script != null) {
                ver.script = job.script;
                ver.seed = job.scriptSeed;
                ver.stdlibVersion = job.stdlib.version();
                ver.scriptExact = job.scriptModel != null && job.scriptModel.entries().equals(fin.entries());
            }
            // drop redo branch
            while (build.versions.size() > build.currentVersion + 1) build.versions.remove(build.versions.size() - 1);
            build.versions.add(ver);
            build.currentVersion = build.versions.size() - 1;
            build.history.add(request);
            build.updatedAt = System.currentTimeMillis();
            return null;
        });
        sync(() -> {
            store.save(build);
            return null;
        });
        AnalysisReport rep = v.finalReport;
        writeMetrics(job,build,fin,v,tr);
        long secs = job.elapsedSeconds();
        String issuesLine = rep == null ? "" : "крит/серьёзных: " + (rep.count(dev.aibuild.analysis.Severity.CRITICAL) + rep.count(dev.aibuild.analysis.Severity.MAJOR));
        say(job, v.approved
                        ? "<green><bold>Готово, проверка пройдена.</bold></green> <white><v0></white> <gray>[<v1>] v<v2> — <v3> блоков, оценка ИИ <v4>, <v5> раунд(а), <v6> c, режим «<v10>», токены <v7>/<v8> (мыслей <v11>). <v9></gray>"
                        : "<yellow><bold>Черновик: проверка не пройдена.</bold></yellow> <white><v0></white> <gray>[<v1>] v<v2> — <v3> блоков, оценка ИИ <v4>, <v5> раунд(а), <v6> c, режим «<v10>», токены <v7>/<v8> (мыслей <v11>). <v9></gray>",
                build.name, build.id, build.versions.get(build.currentVersion).number, fin.nonAirCount(), scoreColor(v.score),
                v.rounds, secs, job.promptTokens.get(), job.completionTokens.get(), issuesLine, job.mode.title, job.reasoningTokens.get());
        if (v.summary != null && !v.summary.isBlank()) say(job, "<gray>ИИ: <italic><v0></italic></gray>", clip(v.summary, 220));
        if (!v.approved) say(job,"<yellow>Не пройдено: <v0></yellow>",clip(v.failureReason(),400));
        say(job, "<click:suggest_command:'/aib edit '><hover:show_text:'Изменить постройку'><aqua>[✎ Изменить]</aqua></hover></click> "
                + "<click:run_command:'/aib undo'><hover:show_text:'Откатить к предыдущей версии'><yellow>[↶ Отменить]</yellow></hover></click> "
                + "<click:run_command:'/aib render'><hover:show_text:'Сохранить изометрический рендер PNG'><light_purple>[🖼 Рендер]</light_purple></hover></click> "
                + "<click:run_command:'/aib info'><hover:show_text:'Информация'><gray>[ℹ Инфо]</gray></hover></click>");
        plugin.getLogger().info("Build " + build.id + " committed (mode " + job.mode.id + ", approved="+v.approved+"): " + fin.nonAirCount() + " blocks, score " + v.score
                + ", changed " + pr.changed() + " world blocks in " + secs + "s");
    }

    private void writeDraft(Build build,VoxelModel model,String request,Verdict verdict) throws IOException {
        File folder=new File(plugin.getDataFolder(),"drafts");
        Files.createDirectories(folder.toPath());
        JsonObject draft=new JsonObject();
        draft.addProperty("buildId",build.id); draft.addProperty("request",request);
        draft.addProperty("approved",false); draft.addProperty("score",verdict.score);
        draft.addProperty("checklist",verdict.checklist);
        draft.add("quality",verdict.quality.deepCopy());
        draft.add("blockers",Json.GSON.toJsonTree(verdict.blockers));
        draft.addProperty("analysis",verdict.finalReport==null?"":verdict.finalReport.toText());
        draft.add("plan",build.plan); draft.add("model",model.toJson());
        File file=new File(folder,build.id+"-"+System.currentTimeMillis()+".json.gz");
        try (var writer=new java.io.OutputStreamWriter(new java.util.zip.GZIPOutputStream(Files.newOutputStream(file.toPath())),StandardCharsets.UTF_8)) {
            Json.GSON.toJson(draft,writer);
        }
    }

    private void writeMetrics(Job job,Build build,VoxelModel fin,Verdict v,Transcript tr) throws IOException {
        AnalysisReport rep=v.finalReport;
        tr.note("final", "score=" + v.score + " approved=" + v.approved + "\n" + (rep == null ? "" : rep.toText()));
        JsonObject metrics = new JsonObject();
        metrics.addProperty("buildId", build.id);
        metrics.addProperty("mode", job.mode.id);
        metrics.addProperty("kind", job.kind.name());
        if (job.kind == Job.Kind.CREATE) {
            metrics.addProperty("createStrategy", cfg.get().createStrategy);
        }
        metrics.addProperty("elapsedMs", System.currentTimeMillis() - job.startedAt);
        metrics.addProperty("calls", job.calls.get());
        metrics.addProperty("callsWithoutUsage", job.callsWithoutUsage.get());
        metrics.addProperty("promptTokens", job.promptTokens.get());
        metrics.addProperty("completionTokens", job.completionTokens.get());
        metrics.addProperty("reasoningTokens", job.reasoningTokens.get());
        metrics.addProperty("blocks", fin.nonAirCount());
        metrics.addProperty("reviewRounds", v.rounds);
        metrics.addProperty("approved", v.approved);
        metrics.addProperty("selfScore", v.score);
        metrics.addProperty("pipelineRevision","focused-review-v2");
        metrics.addProperty("model",cfg.get().model);
        metrics.add("quality",v.quality.deepCopy());
        metrics.add("blockers",Json.GSON.toJsonTree(v.blockers));
        metrics.add("reviewContexts",v.reviewContexts.deepCopy());
        if (rep != null) metrics.add("analyzerIssues",Json.GSON.toJsonTree(rep.issues));
        if (rep != null) {
            metrics.addProperty("critical", rep.count(dev.aibuild.analysis.Severity.CRITICAL));
            metrics.addProperty("major", rep.count(dev.aibuild.analysis.Severity.MAJOR));
            metrics.addProperty("detailScore", rep.metricInt("detailScore", 0));
        }
        tr.write("metrics.json", Json.PRETTY.toJson(metrics));
    }

    // =================================================================== helpers: placement

    /** Main thread: decide rotation + origin for a new build from the anchor and the planned plot. */
    private void place(Build build, Anchor a, int[] plot) {
        World w = Bukkit.getWorld(a.world());
        if (w == null) throw new IllegalStateException("мир не найден: " + a.world());
        build.worldName = w.getName();
        build.rotation = a.rotation();
        int[] o = PlacementMath.computeOrigin(a.x(), a.z(), 0, a.rotation(), plot);
        build.originX = o[0];
        build.originZ = o[2];
        int gy;
        if (a.y() != null) {
            gy = a.y();
        } else {
            build.originY = 0;
            int[] wb = PlacementMath.worldBounds(build, plot);
            gy = PlacementMath.groundY(w, new int[]{wb[0], wb[2], wb[3], wb[5]});
        }
        build.originY = gy;
    }

    private void livePreview(Build build, VoxelModel model) {
        if (build == null || !cfg.get().livePreview) return;
        try {
            placer.place(build, model.copy(), p -> { });
        } catch (Exception e) {
            plugin.getLogger().log(Level.FINE, "live preview failed", e);
        }
    }

    /**
     * Shows an intermediate model in the world <b>and</b> on the live page. Everything that used to call
     * {@link #livePreview} for a job goes through here, so the browser sees exactly what the players see, in
     * the same order, including placements from critics and sculptors that never touch the world when live
     * preview is off (the page still shows them: it is about watching the AI, not the world).
     */
    private void liveModel(Job job, Build build, VoxelModel model, String why) {
        if (model == null) return;
        LiveBroadcast lb = live(job);
        if (lb != LiveBroadcast.NOOP) lb.model(model, why);
        if (build != null && cfg.get().livePreview) {
            try {
                placer.place(build, model.copy(), p -> { });
            } catch (Exception e) {
                plugin.getLogger().log(Level.FINE, "live preview failed", e);
            }
        }
    }

    /** Publishes the named parts of the model being shown. */
    private void liveParts(Job job, ScriptResult result) {
        if (result == null) return;
        LiveBroadcast lb = live(job);
        if (lb != LiveBroadcast.NOOP) lb.parts(result.parts());
    }

    // =================================================================== helpers: LLM

    /** One step less reasoning (used when a call ran out of output budget while still thinking). */
    static String lowerEffort(String effort) {
        if (effort == null) return "medium";
        return switch (effort) {
            case "max" -> "xhigh";
            case "xhigh" -> "high";
            case "high" -> "medium";
            case "medium" -> "low";
            default -> "off";
        };
    }

    /**
     * Free-text call (build scripts): sends {@code msgs} as given, retries once on an empty or truncated answer and
     * lowers reasoning when the whole budget went into thinking. Returns the answer text.
     */
    private String callText(Job job, Transcript tr, String tag, String effort, List<ChatMessage> msgs) throws Exception {
        return callText(job, tr, tag, effort, msgs, null, null);
    }

    private String callText(Job job, Transcript tr, String tag, String effort, List<ChatMessage> msgs,
                            StreamListener live) throws Exception {
        return callText(job, tr, tag, effort, msgs, live, null);
    }

    /**
     * As above; {@code live} additionally receives the streamed answer text (see {@link LiveScript}). A non-null
     * {@code stop} replaces the job's cancel flag for the call (the caller sets it on cancel too).
     */
    private String callText(Job job, Transcript tr, String tag, String effort, List<ChatMessage> msgs,
                            StreamListener live, java.util.concurrent.atomic.AtomicBoolean stop) throws Exception {
        PluginConfig c = cfg.get();
        String eff = effort;
        String lastErr = null;
        String lastUser = msgs.getLast().content();
        for (int attempt = 0; attempt < 3; attempt++) {
            checkCancelled(job);
            ChatRequest req = new ChatRequest(c.model, msgs, job.mode.maxTokens, c.temperature, eff, false);
            job.calls.incrementAndGet();
            ChatResult res;
            StringBuilder think = new StringBuilder();
            try {
                res = llm.get().chat(req, new StreamListener() {
                    @Override
                    public void onProgress(int cc, int rc) {
                        job.streamChars = cc;
                        job.reasoningChars = rc;
                    }

                    @Override
                    public void onText(CharSequence text) {
                        if (live != null) live.onText(text);
                    }

                    @Override
                    public void reasoning(String textSoFar) {
                        // Captured whole for the debug trace: the page only ever shows the tail, but "why did it
                        // write this" is usually answered by the middle of the reasoning, not the end.
                        think.setLength(0);
                        think.append(textSoFar == null ? "" : textSoFar);
                        if (live != null) live.reasoning(textSoFar);
                    }
                }, stop != null ? stop : job.cancelled);
            } catch (LlmException e) {
                if ("cancelled".equals(e.code()) || job.cancelled.get()) throw new CancelledException();
                throw e;
            }
            if (stop != null && stop.get()) throw new CancelledException();
            job.promptTokens.addAndGet(res.promptTokens());
            job.completionTokens.addAndGet(res.completionTokens());
            job.cachedTokens.addAndGet(res.cachedTokens());
            job.reasoningTokens.addAndGet(res.reasoningTokens());
            String stepTag = tag + (attempt > 0 ? "-retry" + attempt : "");
            tr.exchange(stepTag + "-" + (eff == null ? "default" : eff), lastUser, res);
            TraceStep.Builder tb = trace(job, TraceStep.Kind.LLM, stepTag)
                    .title("LLM · " + tag + (attempt > 0 ? " (retry " + attempt + ")" : ""))
                    .took(res.elapsedMs())
                    .field("model", c.model)
                    .field("effort", eff == null ? "default" : eff)
                    .field("max tokens", job.mode.maxTokens)
                    .field("attempt", attempt + 1)
                    .field("usage", "prompt=" + res.promptTokens() + " completion=" + res.completionTokens()
                            + " cached=" + res.cachedTokens() + " reasoning=" + res.reasoningTokens()
                            + " finish=" + res.finishReason() + " transport=" + res.transport())
                    .field("prompt", renderMessages(msgs))
                    .field("reasoning", res.reasoning() != null ? res.reasoning() : think.toString())
                    .field("answer", res.content());
            boolean truncated = "length".equals(res.finishReason());
            boolean empty = res.content() == null || res.content().isBlank();
            if (!empty && !truncated) {
                tb.status(TraceStep.Status.OK).summary(summarise(res.content(), "chars", res.elapsedMs()));
                traceDone(job, tb);
                return res.content();
            }
            tb.status(truncated ? TraceStep.Status.FAILED : TraceStep.Status.FAILED)
                    .summary(truncated ? "output truncated at " + job.mode.maxTokens + " tokens" : "empty answer");
            traceDone(job, tb);
            lastErr = truncated ? "output truncated" : "empty answer";
            if (truncated && empty && !"off".equals(eff)) {
                String lower = lowerEffort(eff);
                if ((job.mode.id.equals(BuildMode.STANDARD) || job.mode.id.equals(BuildMode.EXTRA)) && lower.equals("off")) {
                    throw new IllegalStateException("thinking budget exhausted; refusing to downgrade " + job.mode.id + " to no-thinking");
                }
                plugin.getLogger().warning("[" + tag + "] output budget exhausted while thinking (effort " + eff + "), retrying with " + lower);
                eff = lower;
                continue;
            }
            if (truncated && !empty) {
                // the script was cut off: ask for a more compact version in the same conversation
                msgs = new ArrayList<>(msgs);
                msgs.add(ChatMessage.assistant(clip(res.content(), 2000)));
                msgs.add(ChatMessage.user("Your script was cut off by the output limit. Write the COMPLETE script again, more compactly: "
                        + "use loops, functions, shapes and kits instead of long lists of blocks."));
            }
        }
        throw new IllegalStateException("модель не вернула программу: " + lastErr);
    }

    private JsonObject callJson(Job job, Transcript tr, String tag, String effort, String userMsg) throws Exception {
        PluginConfig c = cfg.get();
        String system = Prompts.system(c.includeBlockList ? blockList.get() : null);
        if (tag.contains("review")) {
            String role=tag.endsWith("-structure")?"structure":tag.endsWith("-exterior")?"exterior":tag.endsWith("-interior")?"interior":"all";
            userMsg += Prompts.qualityReview(role);
        }
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.system(system));
        msgs.add(ChatMessage.user(userMsg));
        String lastErr = null;
        String eff = effort;
        for (int attempt = 0; attempt < 3; attempt++) {
            checkCancelled(job);
            ChatRequest req = new ChatRequest(c.model, msgs, job.mode.maxTokens, c.temperature, eff, true);
            job.calls.incrementAndGet();
            ChatResult res;
            StringBuilder think = new StringBuilder();
            try {
                res = llm.get().chat(req, new StreamListener() {
                    @Override
                    public void onProgress(int cc, int rc) {
                        job.streamChars = cc;
                        job.reasoningChars = rc;
                    }

                    @Override
                    public void reasoning(String textSoFar) {
                        think.setLength(0);
                        think.append(textSoFar == null ? "" : textSoFar);
                        LiveBroadcast lb = live(job);
                        if (lb != LiveBroadcast.NOOP) lb.reasoning(textSoFar);
                    }
                }, job.cancelled);
            } catch (LlmException e) {
                if ("cancelled".equals(e.code()) || job.cancelled.get()) throw new CancelledException();
                if (LlmException.CODE_INVALID_RESPONSE.equals(e.code()) && e.getMessage().contains("repetitive")) {
                    lastErr = "repeated identical operations";
                    job.callsWithoutUsage.incrementAndGet();
                    tr.note(tag + " aborted", "Repetitive output stopped before exhausting the token budget; usage unavailable.");
                    msgs.add(ChatMessage.user("The previous stream was aborted because you repeated identical operations. "
                            + "Start again: emit a compact COMPLETE JSON object. Use building/roof/trim/repeat for geometry, "
                            + "never repeat identical ops. For review, fix only real defects, no redesign or extra decoration."));
                    continue;
                }
                throw e;
            }
            job.promptTokens.addAndGet(res.promptTokens());
            job.completionTokens.addAndGet(res.completionTokens());
            job.cachedTokens.addAndGet(res.cachedTokens());
            job.reasoningTokens.addAndGet(res.reasoningTokens());
            String stepTag = tag + (attempt > 0 ? "-retry" + attempt : "");
            tr.exchange(stepTag + "-" + (eff == null ? "default" : eff), userMsg, res);
            TraceStep.Builder tb = trace(job, TraceStep.Kind.LLM, stepTag)
                    .title("LLM · " + tag + (attempt > 0 ? " (retry " + attempt + ")" : ""))
                    .took(res.elapsedMs())
                    .field("model", c.model)
                    .field("effort", eff == null ? "default" : eff)
                    .field("max tokens", job.mode.maxTokens)
                    .field("attempt", attempt + 1)
                    .field("usage", "prompt=" + res.promptTokens() + " completion=" + res.completionTokens()
                            + " cached=" + res.cachedTokens() + " reasoning=" + res.reasoningTokens()
                            + " finish=" + res.finishReason() + " transport=" + res.transport())
                    .field("prompt", renderMessages(msgs))
                    .field("reasoning", res.reasoning() != null ? res.reasoning() : think.toString())
                    .field("answer", res.content());
            boolean truncated = "length".equals(res.finishReason());
            boolean empty = res.content() == null || res.content().isBlank();
            if (!empty && !truncated) {
                try {
                    Json.Extracted ex = Json.extract(res.content());
                    if (ex.repaired()) plugin.getLogger().info("[" + tag + "] JSON repaired: " + ex.note() + " (finish=" + res.finishReason() + ")");
                    // A repair means the model emitted something that only parses after fixing. That is a defect
                    // worth seeing in the trace even though the build continues.
                    if (ex.repaired()) tb.field("json repair", ex.note());
                    JsonElement el = ex.value();
                    if (el.isJsonObject()) {
                        tb.status(TraceStep.Status.OK).summary(summarise(res.content(), "chars", res.elapsedMs()));
                        traceDone(job, tb);
                        return el.getAsJsonObject();
                    }
                    if (el.isJsonArray()) {
                        JsonObject o = new JsonObject();
                        o.add("ops", el);
                        tb.status(TraceStep.Status.OK).summary(summarise(res.content(), "chars", res.elapsedMs()));
                        traceDone(job, tb);
                        return o;
                    }
                    lastErr = "response is not a JSON object";
                } catch (IllegalArgumentException e) {
                    lastErr = e.getMessage();
                }
            } else {
                lastErr = truncated ? "output truncated; incomplete program rejected" : "empty answer";
            }
            if (truncated && empty && !"off".equals(eff)) {
                // the whole output budget went into thinking: retry the same request with less reasoning
                String lower = lowerEffort(eff);
                if ((job.mode.id.equals(BuildMode.STANDARD) || job.mode.id.equals(BuildMode.EXTRA)) && lower.equals("off")) {
                    throw new IllegalStateException("thinking budget exhausted; refusing to downgrade " + job.mode.id + " to no-thinking");
                }
                plugin.getLogger().warning("[" + tag + "] output budget (" + job.mode.maxTokens + " tokens) exhausted while thinking (effort "
                        + eff + "), retrying with effort " + lower);
                eff = lower;
                continue;
            }
            tb.status(TraceStep.Status.FAILED).summary("invalid JSON: " + lastErr);
            traceDone(job, tb);
            plugin.getLogger().warning("[" + tag + "] invalid JSON from model (" + lastErr + "), finish=" + res.finishReason() + ", retrying");
            if (attempt >= 1) break;
            msgs.add(ChatMessage.assistant(clip(res.content(), 4000)));
            msgs.add(ChatMessage.user("Your previous answer was not valid JSON (" + lastErr + "). Answer again with ONE complete valid JSON object only. Be more concise if needed."));
        }
        throw new IllegalStateException("модель вернула некорректный JSON: " + lastErr);
    }

    private OpResult applyWithRepair(Job job, Transcript tr, OpExecutor exec, VoxelModel model, JsonObject res, String tag) throws Exception {
        PluginConfig c = cfg.get();
        JsonElement ops = res.has("ops") ? res.get("ops") : res;
        OpResult r = exec.apply(model, ops);
        if (r.opsFailed > 0 && c.repairFailedOps && !job.cancelled.get()) {
            plugin.getLogger().info("[" + tag + "] " + r.opsFailed + " ops failed, asking for repair");
            // attach the JSON of the failed ops so the model can correct them precisely
            StringBuilder failed = new StringBuilder(r.summary()).append("\nFAILED OPS JSON:\n");
            if (ops.isJsonArray()) {
                JsonArray arr = ops.getAsJsonArray();
                java.util.TreeSet<Integer> idx = new java.util.TreeSet<>();
                Matcher m = Pattern.compile("op\\[(\\d+)]").matcher(String.join("\n", r.errors));
                while (m.find()) idx.add(Integer.parseInt(m.group(1)));
                int n = 0;
                for (int i : idx) {
                    if (i >= 0 && i < arr.size() && n++ < 60) failed.append("op[").append(i).append("] ").append(Json.GSON.toJson(arr.get(i))).append('\n');
                }
            }
            JsonObject fix = callJson(job, tr, tag + "-repair", job.mode.effortBuild, Prompts.repair(failed.toString()));
            OpResult r2 = exec.apply(model, fix.has("ops") ? fix.get("ops") : fix);
            tr.note(tag + "-repair result", r2.summary());
            traceDone(job, trace(job, TraceStep.Kind.OPS, tag + "-repair")
                    .title("Op repair · " + tag)
                    .ok(!r2.hasErrors())
                    .summary(r2.summary())
                    .field("ops sent", failed.toString())
                    .field("result", r2.summary())
                    .field("errors", String.join("\n", r2.errors)));
            r.merge(r2);
        }
        // The batch as applied, including the per-op errors: an op that silently failed to place blocks is the
        // single most common reason a build looks wrong while the transcript says everything succeeded.
        traceDone(job, trace(job, TraceStep.Kind.OPS, tag)
                .title("Ops · " + tag)
                .ok(!r.hasErrors())
                .summary(r.opsTotal() + " ops · " + r.cellsChanged() + " cells changed"
                        + (r.opsFailed > 0 ? " · " + r.opsFailed + " failed" : ""))
                .field("ops total", r.opsTotal())
                .field("ops ok", r.opsOk())
                .field("ops failed", r.opsFailed())
                .field("cells changed", r.cellsChanged())
                .field("errors", String.join("\n", r.errors))
                .field("summary", r.summary()));
        return r;
    }

    /**
     * Deterministic post-processing after every batch of ops: stair/slab orientation correction (the most
     * common LLM mistake), then multi-block completion, connections and stair shapes. The orientation notes are
     * remembered on the job and shown to the model in its next prompt so it learns the convention in-context.
     */
    private List<String> postProcess(Job job, VoxelModel model) {
        List<String> notes = new ArrayList<>();
        if (job != null) {
            int corrected = 0;
            for (var doorway : job.doorways) corrected += doorway.restore(model,info);
            if (corrected > 0) notes.add("restored " + corrected + " entrance cells required by the architectural envelope");
        }
        if (cfg.get().autoOrient) {
            List<String> o = dev.aibuild.model.OrientationFixer.fix(model, info, job == null ? java.util.Set.of() : job.smoothed);
            notes.addAll(o);
            if (job != null && !o.isEmpty()) {
                synchronized (job) {
                    job.autoNotes.addAll(o);
                    while (job.autoNotes.size() > 12) job.autoNotes.remove(0);
                }
            }
        }
        notes.addAll(ShapeResolver.resolve(model, info));
        return notes;
    }

    /** Analysis text plus the recent automatic orientation corrections (feedback for the model). */
    private String withAutoNotes(Job job, String analysis) {
        List<String> notes;
        synchronized (job) {
            notes = new ArrayList<>(job.autoNotes);
        }
        int[] L = job.plotLimits;
        if (L != null) {
            analysis = "ALLOWED BUILD AREA: x " + L[0] + ".." + L[3] + ", y " + L[1] + ".." + L[4] + ", z " + L[2] + ".." + L[5]
                    + " (blocks outside are rejected)\n" + analysis;
        }
        if (notes.isEmpty()) return analysis;
        return analysis + "\nAUTOMATIC ORIENTATION CORRECTIONS the program had to make to earlier ops (you had these"
                + " stairs/slabs the wrong way round — apply the rules so it does not happen again):\n- "
                + String.join("\n- ", notes) + "\n";
    }

    private String render(VoxelModel model) {
        TextRenderer.Options o = new TextRenderer.Options().compact(true);
        return SpatialReport.render(model,info)+"\nEXACT COORDINATE LAYERS\n"+TextRenderer.render(model, info, o);
    }

    /** Waits for a side-call, unwrapping its exception; cancels it when the job is cancelled. */
    private static <T> T await(Future<T> f, Job job) throws Exception {
        while (true) {
            if (job.cancelled.get()) {
                f.cancel(true);
                throw new CancelledException();
            }
            try {
                return f.get(500, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException ignored) {
                // poll again
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable c = e.getCause();
                if (c instanceof Exception ex) throw ex;
                throw new RuntimeException(c);
            }
        }
    }

    /** As {@link #await} but gives up at {@code deadline} ({@link System#nanoTime()}) without cancelling {@code f}. */
    private static <T> T awaitUntil(Future<T> f, Job job, long deadline) throws Exception {
        while (true) {
            if (job.cancelled.get()) throw new CancelledException();
            try {
                return f.get(Math.min(500, Math.max(0, (deadline - System.nanoTime()) / 1_000_000)), TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                if (deadline - System.nanoTime() <= 0) throw e;
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable c = e.getCause();
                if (c instanceof Exception ex) throw ex;
                throw new RuntimeException(c);
            }
        }
    }

    private static void checkCancelled(Job job) throws CancelledException {
        if (job != null && job.cancelled.get()) throw new CancelledException();
    }

    static final class CancelledException extends Exception {
        CancelledException() {
            super("cancelled", null, false, false);
        }
    }

    // =================================================================== helpers: plan

    private static final Pattern SIZE = Pattern.compile("(\\d{1,3})\\s*(?:x|х|×|\\*|на|by)\\s*(\\d{1,3})(?:\\s*(?:x|х|×|\\*|на|by)\\s*(\\d{1,3}))?", Pattern.CASE_INSENSITIVE);

    /** Fills missing fields and clamps the plot. */
    private void normalizePlan(JsonObject plan, PluginConfig c) {
        JsonArray stages = Json.arr(plan, "stages");
        if (stages.isEmpty()) {
            for (String[] s : new String[][]{
                    {"Фундамент и полы", "foundation, plinth and floors of the main body"},
                    {"Стены и каркас", "walls, frame, window and door openings, windows and doors"},
                    {"Крыша", "roof with overhang, eaves, gables, chimney"},
                    {"Интерьер", "furnish every room, lighting, stairs/ladders"},
                    {"Экстерьер", "path, garden, decorations around the build"}}) {
                JsonObject o = new JsonObject();
                o.addProperty("name", s[0]);
                o.addProperty("goal", s[1]);
                stages.add(o);
            }
        }
        while (stages.size() > 10) stages.remove(stages.size() - 1);
        plan.add("stages", stages);
        int[] p = plotBounds(plan);
        int ext = c.maxExtent;
        for (int i = 0; i < 3; i++) if (p[i + 3] - p[i] + 1 > ext) p[i + 3] = p[i] + ext - 1;
        JsonObject plot = new JsonObject();
        JsonArray mn = new JsonArray(), mx = new JsonArray();
        for (int i = 0; i < 3; i++) {
            mn.add(p[i]);
            mx.add(p[i + 3]);
        }
        plot.add("min", mn);
        plot.add("max", mx);
        plan.add("plot", plot);
    }

    /** Plot bounds {minX,minY,minZ,maxX,maxY,maxZ} from the plan (fallback: body size). */
    static int[] plotBounds(JsonObject plan) {
        JsonObject plot = Json.obj(plan, "plot");
        try {
            if (plot != null) {
                JsonArray mn = plot.getAsJsonArray("min"), mx = plot.getAsJsonArray("max");
                int[] b = new int[6];
                for (int i = 0; i < 3; i++) {
                    int a = (int) Math.round(mn.get(i).getAsDouble()), z = (int) Math.round(mx.get(i).getAsDouble());
                    b[i] = Math.min(a, z);
                    b[i + 3] = Math.max(a, z);
                }
                return b;
            }
        } catch (RuntimeException ignored) {
            // fall back
        }
        JsonObject body = Json.obj(plan, "body");
        int bx = Math.max(3, Json.integer(body, "x", 10)), bz = Math.max(3, Json.integer(body, "z", 10));
        int top = Math.max(6, Json.integer(body, "wall_top_y", 6) + bx / 2 + 2);
        return new int[]{-2, -1, -2, bx + 1, top, bz + 1};
    }

    private static AnalyzerOptions analyzerOptions(JsonObject plan) {
        return BuildRequirements.analyzerOptions(plan);
    }

    /** The brief as sent to the model (stages omitted for brevity after planning? keep all — they carry intent). */
    private static JsonObject planForPrompt(JsonObject plan) {
        return plan;
    }

    /** Extracts an explicit "AxB" size from a prompt, or null. */
    public static int[] sizeFromPrompt(String prompt) {
        Matcher m = SIZE.matcher(prompt.toLowerCase(Locale.ROOT));
        if (!m.find()) return null;
        return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
    }

    // =================================================================== misc

    private <T> T sync(Callable<T> c) throws Exception {
        if (Bukkit.isPrimaryThread()) return c.call();
        Future<T> f = Bukkit.getScheduler().callSyncMethod(plugin, c);
        return f.get(2, TimeUnit.MINUTES);
    }

    static String clip(String s, int n) {
        if (s == null) return "";
        s = s.replace('\n', ' ').trim();
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    private static String shortError(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t && (t instanceof java.util.concurrent.ExecutionException)) t = t.getCause();
        String m = t.getMessage();
        return clip((m == null ? t.getClass().getSimpleName() : m), 200);
    }

    // =================================================================== transcripts

    private final class Transcript {
        private final File dir;
        private final boolean enabled;
        private final AtomicInteger seq = new AtomicInteger();
        /** The job that owns this transcript; set right after construction, read by {@link #note}. */
        volatile Job liveJob;

        Transcript(String buildId, boolean enabled) {
            this.enabled = enabled;
            this.dir = new File(plugin.getDataFolder(), "logs/" + buildId + "/" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
            if (enabled) dir.mkdirs();
        }

        void exchange(String tag, String user, ChatResult res) {
            if (!enabled) return;
            String s = "# " + tag + "\n\n## usage\nprompt=" + res.promptTokens() + " completion=" + res.completionTokens()
                    + " cached=" + res.cachedTokens() + " reasoning=" + res.reasoningTokens() + " ms=" + res.elapsedMs()
                    + " finish=" + res.finishReason() + " transport=" + res.transport()
                    + "\n\n## user\n" + user + "\n\n## reasoning\n" + (res.reasoning() == null ? "" : res.reasoning())
                    + "\n\n## response\n" + res.content() + "\n";
            write(String.format("%02d-%s.md", seq.incrementAndGet(), tag), s);
        }

        void note(String tag, String text) {
            live(liveJob).note(tag, text);
            if (!enabled) return;
            write(String.format("%02d-%s.txt", seq.incrementAndGet(), tag.replace(' ', '_')), text);
        }

        void image(String tag, byte[] png) {
            if (!enabled || png == null) return;
            try {
                Files.write(new File(dir, String.format("%02d-%s.png", seq.incrementAndGet(), tag)).toPath(), png);
            } catch (IOException e) {
                plugin.getLogger().log(Level.FINE, "transcript write failed", e);
            }
        }

        /** One row per accepted stage in quality.txt, so a run shows where the build got better or worse. */
        void quality(String stage, QualityVector q) {
            if (!enabled) return;
            try {
                Files.writeString(new File(dir, "quality.txt").toPath(), stage + ": " + q.describe() + "\n", StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException e) {
                plugin.getLogger().log(Level.FINE, "transcript write failed", e);
            }
        }

        private void write(String name, String content) {
            try {
                Files.writeString(new File(dir, name).toPath(), content, StandardCharsets.UTF_8);
            } catch (IOException e) {
                plugin.getLogger().log(Level.FINE, "transcript write failed", e);
            }
        }
    }
}
