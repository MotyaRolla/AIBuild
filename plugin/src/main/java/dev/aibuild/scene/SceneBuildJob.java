package dev.aibuild.scene;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.aibuild.ai.ChatMessage;
import dev.aibuild.ai.ChatRequest;
import dev.aibuild.ai.ChatResult;
import dev.aibuild.ai.LlmClient;
import dev.aibuild.ai.LlmException;
import dev.aibuild.build.Build;
import dev.aibuild.build.BuildStore;
import dev.aibuild.build.BuildVersion;
import dev.aibuild.config.BuildMode;
import dev.aibuild.config.PluginConfig;
import dev.aibuild.model.VoxelModel;
import dev.aibuild.pipeline.BuildPipeline;
import dev.aibuild.util.Json;
import dev.aibuild.util.Msg;
import dev.aibuild.world.PlaceResult;
import dev.aibuild.world.PlacementMath;
import dev.aibuild.world.WorldPlacer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * The in-game 3D build: design a scene, look at renders of it, critique, patch, repeat, then build the
 * best version in the world.
 *
 * <p>This is the plugin side of the loop that lives in {@code three/packages/core/src/loop.mjs}. The flow
 * follows the JavaScript reference exactly:</p>
 *
 * <ol>
 *   <li><b>Design.</b> One call asks for a first {@link SceneSpec}; a rejected answer is retried up to
 *       three times, telling the model what was wrong with its JSON.</li>
 *   <li><b>Render.</b> The spec is voxelised with {@link SceneGeometry#buildScene} and four views are
 *       drawn <em>from the voxels</em> with {@link VoxelRenderer}. The pictures therefore show the blocks
 *       that will exist in the world, not a smooth preview of the primitives (see {@code RENDER.md}).</li>
 *   <li><b>Critique.</b> The PNGs, a digest of the scene and the objective analysis go to the model,
 *       which answers with issues, per-category scores, a verdict and a {@link ScenePatch}.</li>
 *   <li><b>Patch.</b> The patch is applied operation by operation and re-validated; a rejected operation
 *       is dropped with a message, so one bad id costs one object instead of the whole iteration.</li>
 *   <li><b>Stop.</b> The loop stops when the critic reports no critical/major issue, the objective checks
 *       are clean and the score gained less than the threshold for two consecutive iterations — or when
 *       the iteration cap is reached.</li>
 *   <li><b>Place.</b> <b>Only the best version</b> (highest score, not the last one) is built into the
 *       world through {@link WorldPlacer} and registered in {@link BuildStore}, so {@code /aib undo}
 *       works on it like on any other build.</li>
 * </ol>
 *
 * <p>Nothing is placed during the loop: the loop only writes pictures and history. That keeps the world
 * untouched until a version actually deserves to be built, and it makes cancellation free.</p>
 *
 * <h2>Threading</h2>
 * {@link #start} returns immediately and runs the whole job on an async scheduler task. Rendering and
 * LLM calls never touch the main thread; {@link Build} mutation, world lookups and placement are routed
 * through {@link #sync} and {@link WorldPlacer} (which owns its own tick loop). The actor is messaged on
 * the main thread. Cancellation is cooperative: {@link #cancel()} raises the volatile flag that the loop
 * checks between calls and that {@link LlmClient#chat} polls while streaming, then stops a running
 * placement.
 */
public final class SceneBuildJob {

    /** The four views the critic sees every iteration, in attachment order. */
    public static final List<String> VIEWS = List.of("se", "sw", "front", "top");

    /** Maximum objects a patch may leave in a scene (guardrail against a runaway model). */
    public static final int MAX_OBJECTS = 300;

    /** How many times a malformed first design is retried with the parse error. */
    public static final int DESIGN_ATTEMPTS = 3;

    /** Consecutive low-gain iterations needed to stop when the critic is also happy. */
    public static final int STALL_LIMIT = 2;

    /** Iterations rendered at supersampling 1 (fast preview) before switching to the configured quality. */
    public static final int DRAFT_ITERATIONS = 2;

    private final JavaPlugin plugin;
    private final PluginConfig config;
    private final LlmClient llm;
    private final WorldPlacer placer;
    private final BuildStore store;
    private final CommandSender actor;
    /** Where the build must be placed; when {@code null} the job anchors in front of the actor. */
    private final BuildPipeline.Anchor anchor;
    /** The build record this job fills; when {@code null} the job creates its own. */
    private final Build presetBuild;

    /** The cooperative cancellation flag: checked between calls and polled by the LLM transport. */
    private final AtomicBoolean cancelled = new AtomicBoolean();

    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicLong promptTokens = new AtomicLong();
    private final AtomicLong completionTokens = new AtomicLong();
    private final AtomicLong cachedTokens = new AtomicLong();
    private final AtomicLong reasoningTokens = new AtomicLong();

    private final AtomicBoolean running = new AtomicBoolean();
    private volatile String status = "Ожидание";
    private volatile double progress;
    private volatile String buildId;
    private volatile int streamChars;
    private volatile int reasoningChars;
    private final List<Version> history = new ArrayList<>();

    /**
     * Creates a job. Nothing is started and no Bukkit state is touched until {@link #start}.
     *
     * @param plugin owning plugin (scheduler handle); must not be {@code null}
     * @param config a config snapshot (thinking mode budget, scene settings); must not be {@code null}
     * @param llm    the chat client; must not be {@code null}
     * @param placer the world placer used for the final placement; must not be {@code null}
     * @param store  the build store the finished build is registered in; must not be {@code null}
     * @param actor  who started the job and receives the Russian progress messages; may be {@code null}
     * @throws IllegalArgumentException if a required collaborator is {@code null}
     */
    public SceneBuildJob(JavaPlugin plugin, PluginConfig config, LlmClient llm, WorldPlacer placer,
                         BuildStore store, CommandSender actor) {
        this(plugin, config, llm, placer, store, actor, null, null);
    }

    /**
     * Creates a job bound to an existing build and a fixed anchor.
     *
     * <p>The command creates the {@link Build} record first (so it can answer with the id immediately and
     * register the job for {@code /aib cancel}) and passes it here; a {@code null} build or anchor keeps the
     * standalone behaviour.</p>
     *
     * @param plugin  the owning plugin
     * @param config  the configuration snapshot
     * @param llm     the model client
     * @param placer  the world placer
     * @param store   the build store
     * @param actor   who gets the progress messages
     * @param anchor  the placement anchor, or {@code null} to derive it from the actor
     * @param build   the build record to fill, or {@code null} to create one
     */
    public SceneBuildJob(JavaPlugin plugin, PluginConfig config, LlmClient llm, WorldPlacer placer,
                         BuildStore store, CommandSender actor, BuildPipeline.Anchor anchor, Build build) {
        if (plugin == null) throw new IllegalArgumentException("plugin is null");
        if (config == null) throw new IllegalArgumentException("config is null");
        if (llm == null) throw new IllegalArgumentException("llm is null");
        if (placer == null) throw new IllegalArgumentException("placer is null");
        if (store == null) throw new IllegalArgumentException("store is null");
        this.plugin = plugin;
        this.config = config;
        this.llm = llm;
        this.placer = placer;
        this.store = store;
        this.actor = actor;
        this.anchor = anchor;
        this.presetBuild = build;
    }

    /**
     * Starts the asynchronous job: design, iterate on renders, then place the best version.
     *
     * <p>Returns immediately; progress is reported to the actor in Russian and technical detail goes to
     * the plugin log in English.</p>
     *
     * @param prompt        the user request ("средневековый дом 10 на 10")
     * @param mode          the thinking mode: reasoning effort and output budget for every call
     * @param maxIterations iteration cap (values below 1 are raised to 1, above 20 clamped to 20)
     * @throws IllegalArgumentException if {@code prompt} is blank or {@code mode} is {@code null}
     * @throws IllegalStateException    if this job is already running
     */
    public void start(String prompt, BuildMode mode, int maxIterations) {
        if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("prompt is blank");
        if (mode == null) throw new IllegalArgumentException("mode is null");
        if (!running.compareAndSet(false, true)) throw new IllegalStateException("job is already running");
        int cap = Math.max(1, Math.min(20, maxIterations));
        status = "Проектирование…";
        try {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    run(prompt.trim(), mode, cap);
                } finally {
                    running.set(false);
                }
            });
        } catch (RuntimeException e) {
            running.set(false);
            throw new IllegalStateException("could not schedule the scene job", e);
        }
    }

    /**
     * Requests cancellation: the loop stops at the next check between calls, an in-flight LLM call aborts
     * through the shared flag, and a placement that is already running is cancelled through the placer.
     * Safe to call from any thread and any number of times.
     */
    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            String id = buildId;
            if (id != null) {
                try {
                    placer.cancel(id);
                } catch (RuntimeException e) {
                    plugin.getLogger().log(Level.FINE, "placer.cancel failed for " + id, e);
                }
            }
            plugin.getLogger().info("Scene job cancelled by request");
        }
    }

    // ------------------------------------------------------------------ state for the UI

    /** @return {@code true} while the job is running */
    public boolean isRunning() {
        return running.get();
    }

    /** @return {@code true} after {@link #cancel()} was requested */
    public boolean isCancelled() {
        return cancelled.get();
    }

    /** @return a short Russian status line, refreshed as the job moves through its phases */
    public String status() {
        return status;
    }

    /** @return progress in {@code 0..1}, monotonic enough for a boss bar */
    public double progress() {
        return progress;
    }

    /** @return the build id registered in the store once the build was created, or {@code null} */
    public String buildId() {
        return buildId;
    }

    /** @return the number of model calls made so far */
    public int calls() {
        return calls.get();
    }

    /** @return input tokens billed so far */
    public long promptTokens() {
        return promptTokens.get();
    }

    /** @return output tokens billed so far (including reasoning tokens) */
    public long completionTokens() {
        return completionTokens.get();
    }

    /** @return input tokens served from the provider cache so far */
    public long cachedTokens() {
        return cachedTokens.get();
    }

    /** @return output tokens spent on reasoning so far */
    public long reasoningTokens() {
        return reasoningTokens.get();
    }

    /** @return how many characters of the current answer arrived (for a live indicator) */
    public int streamChars() {
        return streamChars;
    }

    /** @return how many characters of reasoning arrived (for a live indicator) */
    public int reasoningChars() {
        return reasoningChars;
    }

    /** @return a snapshot of the evaluated versions, oldest first */
    public List<Version> history() {
        synchronized (history) {
            return List.copyOf(history);
        }
    }

    // ------------------------------------------------------------------ the loop

    /** Runs the whole pipeline; called on an async scheduler thread. */
    private void run(String prompt, BuildMode mode, int maxIterations) {
        long startedAt = System.currentTimeMillis();
        try {
            SceneSpec spec = design(prompt, mode);
            if (cancelled.get()) {
                finishCancelled();
                return;
            }
            if (spec == null) {
                fail("не удалось спроектировать сцену: модель не вернула корректный JSON", null);
                return;
            }
            say("<gray>Сцена спроектирована: <white><v0></white>, объектов <v1>, размер <v2>×<v3>×<v4>. "
                            + "Начинаю итерации с рендерами.</gray>",
                    spec.name(), spec.objects().size(),
                    spec.bounds()[2][0], spec.bounds()[2][1], spec.bounds()[2][2]);

            double prevScore = Double.NaN;
            int stalls = 0;
            String stopReason = "достигнут лимит итераций";
            boolean renderingWorked = false;

            for (int iteration = 1; iteration <= maxIterations; iteration++) {
                if (cancelled.get()) {
                    finishCancelled();
                    return;
                }
                progress = 0.04 + 0.86 * (iteration - 1) / Math.max(1, maxIterations);
                status = "Итерация " + iteration + "/" + maxIterations + ": рендер…";

                SceneAnalysis analysis = SceneAnalysis.analyze(spec, prompt);
                Render render = renderViews(spec, iteration);
                renderingWorked |= render.ok();

                status = "Итерация " + iteration + "/" + maxIterations + ": критик смотрит картинки…";
                Critique critique = critique(prompt, spec, analysis, render, iteration, mode);

                double score = critique.score() >= 0
                        ? critique.score()
                        : heuristicScore(analysis, spec);
                double gain = Double.isNaN(prevScore) ? score : Math.round((score - prevScore) * 10.0) / 10.0;
                prevScore = score;

                // The scene that was just rendered and judged is the one that goes into history: its
                // score belongs to it. The patch it asked for becomes the NEXT iteration's input, so a
                // version is only ever built in the world after the critic has seen it.
                SceneSpec evaluated = spec;
                ScenePatch.Result applied = ScenePatch.EMPTY.apply(evaluated, MAX_OBJECTS);
                if (!critique.failed() && !cancelled.get()) {
                    applied = critique.patch().apply(evaluated, MAX_OBJECTS);
                }
                if (applied.applied()) spec = applied.spec();

                Version version = new Version(iteration, evaluated, (int) Math.round(score), critique.critical(),
                        critique.major(), critique.minor(), applied, critique.verdict(),
                        analysis.ok(), critique.failed(), critique.scores(), render.viewNames());
                synchronized (history) {
                    history.add(version);
                }

                boolean clean = !critique.failed() && critique.critical() == 0 && critique.major() == 0;
                stalls = stalledAfter(stalls, gain, config.sceneScoreGain);
                boolean converged = shouldStop(clean, analysis.ok(), stalls, STALL_LIMIT);
                int severe = critique.critical() + critique.major();

                status = "Итерация " + iteration + "/" + maxIterations;
                say("<gray>итерация <white><v0>/<v1></white>: оценка <v2><v3>, серьёзных замечаний <v4>, <v5></gray>",
                        iteration, maxIterations, (int) Math.round(score),
                        critique.failed() ? " (эвристика)" : "", severe, applied.summary());

                plugin.getLogger().info("scene iteration " + iteration + "/" + maxIterations + ": score "
                        + Math.round(score) + (critique.failed() ? " (heuristic)" : "") + ", issues "
                        + critique.critical() + "c/" + critique.major() + "m/" + critique.minor() + "min, patch "
                        + (applied.applied() ? applied.changes().size() + " applied" : applied.rejected() ? "rejected" : "none")
                        + ", views " + render.viewNames().size() + ", gain " + gain);

                if (converged) {
                    stopReason = "критик и объективные проверки чисты, прирост оценки меньше "
                            + config.sceneScoreGain + " две итерации подряд";
                    break;
                }
            }

            Version best = SceneBuildJob.best(history());
            if (best == null) {
                fail("ни одна итерация не дала версию для постройки", null);
                return;
            }
            if (cancelled.get()) {
                finishCancelled();
                return;
            }

            status = "Размещение лучшей версии…";
            progress = 0.9;
            PlaceOutcome outcome = place(prompt, mode, best, renderingWorked);
            PlaceResult placed = outcome.result();
            if (placed == null || placed.cancelled()) {
                finishCancelled();
                return;
            }
            long seconds = (System.currentTimeMillis() - startedAt) / 1000;
            String id = buildId == null ? "?" : buildId;
            say("<green><bold>Готово!</bold></green> <white><v0></white> <gray>[<v1>]</gray> — лучшая версия: итерация "
                            + "<white><v2></white>, оценка <white><v3>/100</white>, <white><v4></white> блоков, "
                            + "изменено <white><v5></white> в мире, <v6> c.</gray>",
                    best.spec().name(), id, best.iteration(), best.score(),
                    outcome.blocks(), placed.changed(), seconds);
            say("<gray>Лучшая версия — итерация <v0> из <v1> (<v2>): последняя версия не обязательно лучшая. "
                            + "Токены: <v3>/<v4>.</gray>",
                    best.iteration(), history().size(), stopReason, promptTokens.get(), completionTokens.get());
            say("<click:run_command:'/aib undo'><hover:show_text:'Откатить к предыдущему состоянию'><yellow>[↶ Отменить]</yellow></hover></click> "
                    + "<click:run_command:'/aib info'><hover:show_text:'Информация о постройке'><gray>[ℹ Инфо]</gray></hover></click>");
            plugin.getLogger().info("scene build " + id + " committed: iteration " + best.iteration() + " score "
                    + best.score() + ", changed " + placed.changed() + " world blocks in " + seconds + "s, calls "
                    + calls.get() + ", tokens " + promptTokens.get() + "+" + completionTokens.get()
                    + (renderingWorked ? "" : " (no renders!)"));
        } catch (CancelledException e) {
            finishCancelled();
        } catch (Throwable t) {
            if (cancelled.get()) {
                finishCancelled();
            } else {
                plugin.getLogger().log(Level.WARNING, "scene build failed", t);
                fail(shortError(t), t);
            }
        }
    }

    // ------------------------------------------------------------------ 1. design

    /**
     * Asks for the first scene, retrying a malformed answer with the parse error.
     *
     * @return the parsed scene, or {@code null} when every attempt failed
     */
    private SceneSpec design(String prompt, BuildMode mode) throws Exception {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(ScenePrompts.system()));
        messages.add(ChatMessage.user(ScenePrompts.initial(prompt)));
        String problem = null;
        for (int attempt = 1; attempt <= DESIGN_ATTEMPTS; attempt++) {
            if (cancelled.get()) throw new CancelledException();
            status = attempt == 1 ? "Проектирование сцены…" : "Повтор проектирования (" + attempt + "/" + DESIGN_ATTEMPTS + ")…";
            ChatResult result;
            try {
                result = chat(new ChatRequest(config.model, messages, mode.maxTokens, config.temperature,
                        effort(mode.effortPlan, "medium"), true));
            } catch (LlmException e) {
                if (e.isCancellation() || cancelled.get()) throw new CancelledException();
                problem = shortError(e);
                plugin.getLogger().warning("scene design call failed (attempt " + attempt + "): " + problem);
                if (attempt >= DESIGN_ATTEMPTS) break;
                messages.add(ChatMessage.user("Your previous answer was lost to a transport error ("
                        + problem + "). Return the JSON scene again, concise."));
                continue;
            }
            if (cancelled.get()) throw new CancelledException();
            String content = result.content();
            JsonObject json = null;
            try {
                JsonElement value = Json.extract(content).value();
                if (value.isJsonObject()) json = value.getAsJsonObject();
                else problem = "the answer is not a JSON object";
            } catch (RuntimeException e) {
                problem = "invalid JSON: " + e.getMessage();
            }
            if (json != null) {
                JsonElement wrapped = json.get("spec");
                if (wrapped != null && wrapped.isJsonObject() && !json.has("objects")) {
                    json = wrapped.getAsJsonObject();
                }
                try {
                    SceneSpec spec = SceneSpec.parse(json);
                    if (spec.objects().isEmpty()) {
                        problem = "the scene has no valid objects (" + String.join("; ", spec.errors()) + ")";
                    } else if (!spec.errors().isEmpty()) {
                        problem = "these objects are invalid: " + String.join("; ", spec.errors());
                    } else {
                        plugin.getLogger().info("scene designed: " + spec.objects().size() + " objects, "
                                + spec.bounds()[2][0] + "x" + spec.bounds()[2][1] + "x" + spec.bounds()[2][2]);
                        return spec;
                    }
                } catch (RuntimeException e) {
                    problem = e.getMessage();
                }
            }
            if (attempt >= DESIGN_ATTEMPTS) break;
            plugin.getLogger().warning("scene design attempt " + attempt + " rejected: " + problem);
            messages.add(ChatMessage.assistant(clip(content, 3000)));
            messages.add(ChatMessage.user(ScenePrompts.repair(problem == null ? "unknown problem" : problem)));
        }
        return null;
    }

    // ------------------------------------------------------------------ 2. render

    /** One rendered frame set. */
    private record Render(List<String> viewNames, List<byte[]> pngs, String error) {

        boolean ok() {
            return error == null && !pngs.isEmpty();
        }
    }

    /**
     * Voxelises the scene and draws the four critic views from the voxels.
     *
     * <p>The scale is a draft (1&times; supersampling) for the first iterations and the configured quality
     * afterwards: the critic needs to read roof edges and window frames, but eight draft rounds must stay
     * inside a few seconds each.</p>
     */
    private Render renderViews(SceneSpec spec, int iteration) {
        List<String> names = new ArrayList<>(VIEWS.size());
        List<byte[]> pngs = new ArrayList<>(VIEWS.size());
        String error = null;
        try {
            Map<Long, SceneGeometry.CellInfo> voxels = SceneGeometry.info(spec);
            boolean draft = iteration <= DRAFT_ITERATIONS;
            int ss = draft ? 1 : Math.max(1, config.sceneSupersample);
            int width = Math.max(64, config.sceneRenderWidth);
            int height = Math.max(64, config.sceneRenderHeight);
            for (String name : VIEWS) {
                if (cancelled.get()) break;
                VoxelRenderer.View view = VoxelRenderer.VIEWS.get(name);
                if (view == null) continue;
                try {
                    BufferedImage image = VoxelRenderer.render(spec, voxels, view, ss, width, height);
                    pngs.add(VoxelRenderer.toPng(image));
                    names.add(name);
                } catch (RuntimeException e) {
                    plugin.getLogger().log(Level.WARNING, "render of view " + name + " failed", e);
                    error = "render of view " + name + " failed: " + shortError(e);
                }
            }
            progress = Math.min(0.9, progress + 0.01);
            plugin.getLogger().fine("rendered " + pngs.size() + " views at " + width + "x" + height + " ss=" + ss
                    + " in iteration " + iteration);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "scene voxelisation failed", e);
            error = "scene build failed: " + shortError(e);
        }
        return new Render(List.copyOf(names), List.copyOf(pngs), error);
    }

    // ------------------------------------------------------------------ 3. critique

    /** One parsed critique. */
    private record Critique(int critical, int major, int minor, int score, JsonObject scores, String verdict,
                            ScenePatch patch, boolean failed, String error) {
    }

    /**
     * Sends the renders, the digest and the objective analysis to the model and parses the answer.
     *
     * <p>A failure degrades to the objective findings (score {@code -1}, no patch) instead of ending the
     * build: one bad answer must not throw away seven good iterations.</p>
     */
    private Critique critique(String prompt, SceneSpec spec, SceneAnalysis analysis, Render render,
                              int iteration, BuildMode mode) {
        try {
            if (cancelled.get()) throw new CancelledException();
            String effort = effort(iteration == 1 ? mode.effortReview : mode.effortReviewFollowup,
                    effort(mode.effortReview, "medium"));
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(ChatMessage.system(ScenePrompts.criticSystem()));
            String text = ScenePrompts.critique(prompt, spec, analysis.render(), render.viewNames(), iteration);
            messages.add(ChatMessage.user(text, render.pngs()));
            ChatRequest request = new ChatRequest(config.model, messages, mode.maxTokens, config.temperature,
                    effort, true).withImageLimit(VIEWS.size());
            ChatResult result = chat(request);
            if (cancelled.get()) throw new CancelledException();
            return parseCritique(result.content());
        } catch (CancelledException e) {
            throw e;
        } catch (LlmException e) {
            if (e.isCancellation() || cancelled.get()) {
                throw new CancelledException();
            }
            plugin.getLogger().warning("critic call failed: " + e.getMessage());
            return new Critique(0, 0, 0, -1, null, "", ScenePatch.EMPTY, true, shortError(e));
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "critique failed", e);
            return new Critique(0, 0, 0, -1, null, "", ScenePatch.EMPTY, true, shortError(e));
        }
    }

    /**
     * Parses the critic answer: issues, scores, verdict and patch.
     *
     * <p>Severities are normalised because models spell them every way ("critical", "blocker", "high",
     * "medium", "polish"); whatever is not clearly critical or major counts as minor so a verbose critic
     * cannot keep the loop running forever on word choice.</p>
     */
    private Critique parseCritique(String content) {
        JsonObject json;
        try {
            JsonElement value = Json.extract(content).value();
            if (value == null || !value.isJsonObject()) {
                return new Critique(0, 0, 0, -1, null, "", ScenePatch.EMPTY, true, "answer is not a JSON object");
            }
            json = value.getAsJsonObject();
        } catch (RuntimeException e) {
            return new Critique(0, 0, 0, -1, null, "", ScenePatch.EMPTY, true, "invalid JSON: " + e.getMessage());
        }
        JsonElement reviewed = json.get("review");
        if (reviewed != null && reviewed.isJsonObject() && !json.has("issues") && !json.has("patch")) {
            json = reviewed.getAsJsonObject();
        }
        int critical = 0;
        int major = 0;
        int minor = 0;
        JsonArray issues = json.has("issues") && json.get("issues").isJsonArray()
                ? json.getAsJsonArray("issues") : new JsonArray();
        for (JsonElement element : issues) {
            String severity = severityOf(element);
            switch (severity) {
                case "critical" -> critical++;
                case "major" -> major++;
                default -> minor++;
            }
        }
        JsonObject scores = json.has("scores") && json.get("scores").isJsonObject()
                ? json.getAsJsonObject("scores") : null;
        int score = scoreFromScores(scores);
        String verdict = Json.str(json, "verdict", "");
        ScenePatch patch = ScenePatch.parse(json);
        return new Critique(critical, major, minor, score, scores, verdict, patch, false, null);
    }

    // ------------------------------------------------------------------ 4. place

    /**
     * Builds the winning scene into the world: registers the build, adds its version, places it and saves.
     *
     * @return the placement result, or {@code null} when no world could be resolved
     */
    private PlaceOutcome place(String prompt, BuildMode mode, Version best, boolean renderingWorked) throws Exception {
        VoxelModel model = SceneGeometry.build(best.spec());
        if (model.isEmpty()) throw new IllegalStateException("лучшая версия не содержит блоков");
        String summary = best.verdict() == null || best.verdict().isBlank()
                ? "сцена, итерация " + best.iteration()
                : clip(best.verdict(), 180);

        Build build = sync(() -> {
            World world = anchor != null ? plugin.getServer().getWorld(anchor.world()) : worldForActor();
            if (world == null) return null;
            String ownerName = actor == null ? "console" : actor.getName();
            Build b = presetBuild != null ? presetBuild : store.create(owner(), ownerName, world.getName());
            b.originalPrompt = prompt;
            b.name = best.spec().name();
            b.worldName = world.getName();
            b.rotation = anchor != null ? anchor.rotation() : rotationForActor();
            int[] column = anchor != null ? new int[]{anchor.x(), anchor.z()} : anchorForActor(world);
            int[] mb = model.bounds();
            // Ground is resolved before the origin: originY is the world y of model layer 0.
            int[] probe = PlacementMath.computeOrigin(column[0], column[1], 0, b.rotation, mb);
            int[] wb = PlacementMath.worldBounds(probe[0], 0, probe[2], b.rotation, mb);
            int ground = anchor != null && anchor.y() != null
                    ? anchor.y()
                    : PlacementMath.groundY(world, new int[]{wb[0], wb[2], wb[3], wb[5]});
            int[] origin = PlacementMath.computeOrigin(column[0], column[1], ground, b.rotation, mb);
            b.originX = origin[0];
            b.originY = origin[1];
            b.originZ = origin[2];
            BuildVersion version = b.addVersion(model, prompt, summary, best.score());
            version.mode = mode.id;
            store.save(b);
            return b;
        });
        if (build == null) {
            throw new IllegalStateException("мир не найден для размещения постройки");
        }
        buildId = build.id;
        if (actor != null && actor instanceof Player player) {
            sync(() -> {
                store.setActive(player.getUniqueId(), build.id);
                return null;
            });
        }
        int blocks = model.nonAirCount();
        say("<gray>Ставлю постройку в мир: <white><v0></white> блоков. Прогресс — в чате.</gray>", blocks);

        CompletableFuture<PlaceResult> future = placer.place(build, model, p -> {
            progress = 0.9 + 0.09 * Math.max(0, Math.min(100, p)) / 100.0;
            status = "Размещение: " + p + "%";
        });
        PlaceResult result = awaitPlacement(future, build.id);
        if (result != null && !result.cancelled()) {
            sync(() -> {
                store.save(build);
                return null;
            });
        }
        plugin.getLogger().info("placed scene build " + build.id + ": " + blocks + " blocks, "
                + (result == null ? "no result" : result.describe())
                + (renderingWorked ? "" : " (renders unavailable during the loop)"));
        return new PlaceOutcome(result, blocks);
    }

    /**
     * What the final placement produced.
     *
     * @param result  the placer outcome, or {@code null} when no placement ran
     * @param blocks  the number of blocks of the winning version
     */
    private record PlaceOutcome(PlaceResult result, int blocks) {
    }

    /** Waits for placement, polling so a cancel request can stop it; {@code null} on failure. */
    private PlaceResult awaitPlacement(CompletableFuture<PlaceResult> future, String id) throws Exception {
        try {
            while (true) {
                if (cancelled.get()) {
                    placer.cancel(id);
                    try {
                        return future.get(30, TimeUnit.SECONDS);
                    } catch (TimeoutException e) {
                        return new PlaceResult(0, 0, 0L, true);
                    }
                }
                try {
                    return future.get(500, TimeUnit.MILLISECONDS);
                } catch (TimeoutException ignored) {
                    // keep polling: progress and cancellation
                }
            }
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            plugin.getLogger().log(Level.WARNING, "placement of " + id + " failed", cause);
            throw new IllegalStateException("не удалось поставить постройку: " + shortError(cause), cause);
        } catch (CompletionException e) {
            throw new IllegalStateException("не удалось поставить постройку: " + shortError(e), e);
        }
    }

    /** @return the world a player-actor stands in, or the first loaded world for a console actor */
    private World worldForActor() {
        if (actor instanceof Player player) return player.getWorld();
        List<World> worlds = plugin.getServer().getWorlds();
        return worlds.isEmpty() ? null : worlds.get(0);
    }

    /** @return {@code {x, z}} of the anchor column: in front of the player, spawn for the console */
    private int[] anchorForActor(World world) {
        if (actor instanceof Player player) {
            BlockFace facing = PlacementMath.cardinal(player.getLocation().getYaw());
            Location location = player.getLocation();
            int distance = config.distance + 1;
            return new int[]{location.getBlockX() + facing.getModX() * distance,
                    location.getBlockZ() + facing.getModZ() * distance};
        }
        Location spawn = world.getSpawnLocation();
        return new int[]{spawn.getBlockX(), spawn.getBlockZ()};
    }

    /** @return the rotation that turns the build's south front towards the player */
    private int rotationForActor() {
        if (actor instanceof Player player) {
            return PlacementMath.rotationForPlayerFacing(PlacementMath.cardinal(player.getLocation().getYaw()));
        }
        return 0;
    }

    /** @return the owner uuid of the actor, or {@code null} for the console */
    private UUID owner() {
        return actor instanceof Player player ? player.getUniqueId() : null;
    }

    // ------------------------------------------------------------------ pure helpers (tested)

    /**
     * Normalises the severity of one issue.
     *
     * @param element the issue object (or a bare severity string); may be {@code null}
     * @return {@code "critical"}, {@code "major"} or {@code "minor"} — never {@code null}
     */
    public static String severityOf(JsonElement element) {
        String raw = "";
        if (element != null && element.isJsonObject()) {
            raw = Json.str(element.getAsJsonObject(), "severity",
                    Json.str(element.getAsJsonObject(), "level", "minor"));
        } else if (element != null && element.isJsonPrimitive()) {
            raw = element.getAsString();
        }
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("crit") || s.equals("blocker") || s.equals("fatal") || s.equals("high") || s.equals("error")) {
            return "critical";
        }
        if (s.startsWith("maj") || s.equals("serious") || s.equals("medium") || s.equals("moderate")
                || s.equals("warn") || s.equals("warning") || s.equals("important")) {
            return "major";
        }
        return "minor";
    }

    /**
     * Converts the critic's per-category 0..10 scores into the loop's 0..100 quality score.
     *
     * <p>Known categories are averaged; unknown ones are ignored, values outside 0..10 are clamped, and a
     * missing/empty score object yields {@code -1} ("no score", the caller then uses the heuristic).</p>
     *
     * @param scores the {@code scores} object of the critique; may be {@code null}
     * @return the score in {@code 0..100}, or {@code -1} when no usable category was present
     */
    public static int scoreFromScores(JsonObject scores) {
        if (scores == null || scores.isEmpty()) return -1;
        String[] keys = {"shape", "detail", "style", "structure", "fidelity"};
        double sum = 0;
        int n = 0;
        for (String key : keys) {
            JsonElement e = scores.get(key);
            if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) continue;
            double value;
            try {
                value = e.getAsDouble();
            } catch (RuntimeException ex) {
                continue;
            }
            if (!Double.isFinite(value) || value < 0) continue;
            sum += Math.min(10.0, value);
            n++;
        }
        if (n == 0) return -1;
        return (int) Math.round(Math.max(0, Math.min(100, sum / n * 10.0)));
    }

    /**
     * Deterministic fallback score used when the critic gave no numbers: completeness rewards, defect
     * penalties. Same spirit as the heuristic in {@code loop.mjs}.
     *
     * @param analysis the objective analysis of the scene
     * @param spec     the scene itself
     * @return a score in {@code 0..100}
     */
    public static int heuristicScore(SceneAnalysis analysis, SceneSpec spec) {
        if (analysis == null || spec == null) return 0;
        double score = 30;
        score += Math.min(24, analysis.materials() * 3);
        score += Math.min(18, analysis.objects() * 1.2);
        score -= analysis.count(SceneAnalysis.Severity.CRITICAL) * 22;
        score -= analysis.count(SceneAnalysis.Severity.MAJOR) * 7;
        score -= analysis.count(SceneAnalysis.Severity.MINOR) * 1.5;
        return (int) Math.round(Math.max(0, Math.min(100, score)));
    }

    /**
     * Updates the stall counter for one iteration: a gain below the threshold counts as "no progress".
     *
     * @param stalls       consecutive stalled iterations so far
     * @param gain         score gain of this iteration (negative when the score dropped)
     * @param requiredGain minimum gain that still counts as progress
     * @return the new consecutive stall count
     */
    public static int stalledAfter(int stalls, double gain, double requiredGain) {
        return gain < requiredGain ? Math.max(0, stalls) + 1 : 0;
    }

    /**
     * The stopping rule, as a pure function of the facts of one iteration.
     *
     * @param criticClean  the critic found no critical and no major issue
     * @param analysisOk   the objective checks are clean
     * @param stalls       consecutive stalled iterations <em>including</em> this one
     * @param stallLimit   how many stalled iterations in a row are required
     * @return {@code true} when the loop may stop
     */
    public static boolean shouldStop(boolean criticClean, boolean analysisOk, int stalls, int stallLimit) {
        return criticClean && analysisOk && stalls >= Math.max(1, stallLimit);
    }

    /**
     * Picks the version worth building: highest score; ties go to the version with fewer critical+major
     * issues, then to the earlier iteration (a later patch that fixed nothing is not progress).
     *
     * @param versions the evaluated versions, oldest first; may be {@code null}
     * @return the winning version, or {@code null} for an empty list
     */
    public static Version best(List<Version> versions) {
        if (versions == null || versions.isEmpty()) return null;
        Version best = versions.get(0);
        for (int i = 1; i < versions.size(); i++) {
            Version candidate = versions.get(i);
            if (better(candidate, best)) best = candidate;
        }
        return best;
    }

    private static boolean better(Version candidate, Version current) {
        if (candidate.score() != current.score()) return candidate.score() > current.score();
        int candidateSevere = candidate.critical() + candidate.major();
        int currentSevere = current.critical() + current.major();
        if (candidateSevere != currentSevere) return candidateSevere < currentSevere;
        return candidate.iteration() < current.iteration();
    }

    /**
     * One evaluated version of the scene.
     *
     * @param iteration    1-based iteration number
     * @param spec         the scene as it looked when it was judged (after the patch of this iteration)
     * @param score        quality score 0..100 (critic average, or the heuristic when the critic failed)
     * @param critical     number of critical issues reported
     * @param major        number of major issues reported
     * @param minor        number of minor issues reported
     * @param patch        what the patch of this iteration did
     * @param verdict      the critic's one-sentence verdict (Russian)
     * @param analysisOk   whether the objective checks were clean
     * @param criticFailed whether the critic call failed (score is then the heuristic)
     * @param scores       the raw per-category scores, or {@code null}
     * @param views        the names of the views the critic saw
     */
    public record Version(int iteration, SceneSpec spec, int score, int critical, int major, int minor,
                          ScenePatch.Result patch, String verdict, boolean analysisOk, boolean criticFailed,
                          JsonObject scores, List<String> views) {

        /** Canonicalises the list and the verdict. */
        public Version {
            verdict = verdict == null ? "" : verdict;
            views = views == null ? List.of() : List.copyOf(views);
        }

        /** @return a short Russian line for chat output */
        public String describe() {
            return "итерация " + iteration + ": оценка " + score + ", серьёзных замечаний "
                    + (critical + major) + ", " + patch.summary();
        }
    }

    // ------------------------------------------------------------------ LLM plumbing

    /** Performs one counted chat call with the shared cancellation flag and a streaming progress listener. */
    private ChatResult chat(ChatRequest request) throws LlmException {
        calls.incrementAndGet();
        ChatResult result = llm.chat(request, (content, reasoning) -> {
            streamChars = content;
            reasoningChars = reasoning;
        }, cancelled);
        promptTokens.addAndGet(result.promptTokens());
        completionTokens.addAndGet(result.completionTokens());
        cachedTokens.addAndGet(result.cachedTokens());
        reasoningTokens.addAndGet(result.reasoningTokens());
        plugin.getLogger().fine("scene call: " + result.summary());
        return result;
    }

    /** @return {@code primary} when it is usable, otherwise {@code fallback} */
    private static String effort(String primary, String fallback) {
        return primary == null || primary.isBlank() ? fallback : primary;
    }

    // ------------------------------------------------------------------ reporting

    /** Sends one Russian message to the actor, on the main thread when possible. */
    private void say(String template, Object... args) {
        Runnable send = () -> {
            try {
                Msg.send(actor, template, args);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.FINE, "could not message the actor", e);
            }
        };
        try {
            if (plugin.isEnabled() && !plugin.getServer().isPrimaryThread()) {
                plugin.getServer().getScheduler().runTask(plugin, send);
                return;
            }
        } catch (RuntimeException ignored) {
            // fall through to the inline send
        }
        send.run();
    }

    private void finishCancelled() {
        status = "Отменено";
        progress = 1;
        plugin.getLogger().info("scene job cancelled after " + calls.get() + " calls");
        discardUnusedBuild();
        say("<yellow>Постройка отменена: ничего не размещено. Итераций сделано: <v0>.</yellow>",
                history().size());
    }

    private void fail(String message, Throwable error) {
        status = "Ошибка";
        progress = 1;
        if (error != null) plugin.getLogger().log(Level.WARNING, "scene job failed: " + message, error);
        discardUnusedBuild();
        say("<red>Не удалось построить: <v0></red>", message);
    }

    /**
     * Removes a build record the command created up front when the job never got to place anything.
     *
     * <p>The command registers the build before the job starts so the player gets an id immediately and
     * {@code /aib cancel} can find the job. If this job ends without a placed version, that record would
     * otherwise stay in the store forever as an empty build with no versions.</p>
     */
    private void discardUnusedBuild() {
        Build build = presetBuild;
        if (build == null || buildId != null) return;
        try {
            sync(() -> {
                if (build.versions == null || build.versions.isEmpty()) {
                    store.delete(build.id);
                    plugin.getLogger().info("discarded the unused build record " + build.id);
                }
                return null;
            });
        } catch (Exception e) {
            plugin.getLogger().log(Level.FINE, "could not discard build " + build.id, e);
        }
    }

    /** Runs {@code task} on the server main thread when called from a worker. */
    private <T> T sync(Callable<T> task) throws Exception {
        if (plugin.getServer().isPrimaryThread()) return task.call();
        Future<T> future = plugin.getServer().getScheduler().callSyncMethod(plugin, task);
        return future.get(2, TimeUnit.MINUTES);
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        String one = s.replace('\n', ' ').replace('\r', ' ').trim();
        return one.length() <= max ? one : one.substring(0, Math.max(0, max - 1)) + "…";
    }

    private static String shortError(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String message = t.getMessage();
        return clip(message == null || message.isBlank() ? t.getClass().getSimpleName() : message, 200);
    }

    /** Internal cancellation signal: not an error, never logged as one. */
    private static final class CancelledException extends RuntimeException {
        CancelledException() {
            super("cancelled", null, false, false);
        }
    }
}
