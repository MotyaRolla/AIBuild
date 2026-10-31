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
import dev.aibuild.model.BlockSpec;
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
import java.util.LinkedHashMap;
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
    /** The design brief as JSON, kept for the critic and the detailing pass. */
    private volatile String planJson = "";
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
            // Score of the last version whose patch survived the next critique. A patch that lowers the
            // score is rolled back: without this the loop walked 55 -> 35 -> 30 in a live test, because
            // every individual patch looked like an improvement to the model that wrote it.
            double acceptedScore = Double.NaN;
            SceneSpec acceptedSpec = spec;
            int rollbacks = 0;
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

                // Roll back a patch that made the building worse. The critic is not perfect: it can ask for
                // a change that its own next review scores lower. Keeping the better version and dropping
                // the patch is what makes the loop monotonic instead of a random walk.
                boolean regressed = !Double.isNaN(acceptedScore) && score < acceptedScore - 0.5;
                if (regressed) {
                    rollbacks++;
                    plugin.getLogger().info("scene iteration " + iteration + ": score " + Math.round(score)
                            + " < accepted " + Math.round(acceptedScore) + ", rolling back");
                    say("<gray>итерация <white><v0>/<v1></white>: оценка <v2> ниже принятой <v3> — откатываю правку</gray>",
                            iteration, maxIterations, (int) Math.round(score), (int) Math.round(acceptedScore));
                    spec = acceptedSpec;
                }

                // The scene that was just rendered and judged is the one that goes into history: its
                // score belongs to it. The patch it asked for becomes the NEXT iteration's input, so a
                // version is only ever built in the world after the critic has seen it.
                SceneSpec evaluated = regressed ? acceptedSpec : spec;
                ScenePatch.Result applied = ScenePatch.EMPTY.apply(evaluated, MAX_OBJECTS);
                if (!regressed && !critique.failed() && !cancelled.get()) {
                    applied = critique.patch().apply(evaluated, MAX_OBJECTS);
                }
                if (applied.applied()) {
                    spec = applied.spec();
                    acceptedSpec = evaluated;
                    acceptedScore = Math.max(Double.isNaN(acceptedScore) ? score : acceptedScore, score);
                }

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

            plugin.getLogger().info("scene loop finished: " + rollbacks + " patch(es) rolled back");
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
            // ---- post-transfer detailing: the small blocks a 3D scene cannot express ----------
            DetailingResult detailing = detailAfterPlacement(prompt, best, outcome, mode);
            long seconds = (System.currentTimeMillis() - startedAt) / 1000;
            String id = buildId == null ? "?" : buildId;
            if (detailing != null && !detailing.decor().isEmpty()) {
                say("<gray>Доработка мелкими блоками: <white><v0></white></gray>", detailing.text());
            }
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

    /** Outcome of the detailing pass. */
    private record DetailingResult(List<SceneDetailing.Decor> decor, String text) {
    }

    /**
     * Runs the post-transfer detailing pass: asks the model for the small blocks that make a build look finished,
     * validates them against the placed structure and puts them into the world.
     *
     * <p>This is the step that has no equivalent inside the 3D scene. A renderer can show a wall, but a lantern on
     * a chain, a trapdoor used as a shutter, a flower pot on a sill or a button beside a door are smaller than a
     * voxel or purely decorative, so they never appear in the scene and no iteration inside three.js can invent
     * them. They are chosen last, on real coordinates, against the placed blocks.</p>
     *
     * @param prompt  the original request
     * @param best    the version that was placed
     * @param outcome the placement result (gives the world bounds and the placed model)
     * @param mode    the thinking mode
     * @return the applied decorations, or {@code null} when the pass was skipped or failed
     */
    private DetailingResult detailAfterPlacement(String prompt, Version best, PlaceOutcome outcome, BuildMode mode) {
        if (cancelled.get()) return null;
        VoxelModel placed = SceneGeometry.build(best.spec());
        if (placed == null || placed.isEmpty()) return null;
        try {
            status = "Доработка мелкими блоками…";
            progress = 0.93;

            int[] placeBounds = placed.bounds();
            String placedText = "placed structure bounds: x " + placeBounds[0] + ".." + placeBounds[3]
                    + ", y " + placeBounds[1] + ".." + placeBounds[4]
                    + ", z " + placeBounds[2] + ".." + placeBounds[5]
                    + " (" + (placeBounds[3] - placeBounds[0] + 1) + "x" + (placeBounds[4] - placeBounds[1] + 1)
                    + "x" + (placeBounds[5] - placeBounds[2] + 1) + " blocks)\n"
                    + "palette: " + best.spec().palette() + "\n"
                    + "the door faces south; the ground level is y=" + placeBounds[1];

            // renders of the placed structure, so the model sees what it is decorating
            Render render = renderViews(best.spec(), 99);
            List<byte[]> pictures = render.ok() ? render.pngs() : List.of();
            List<String> viewNames = render.ok() ? render.viewNames() : List.of();

            List<ChatMessage> messages = new ArrayList<>();
            messages.add(ChatMessage.system(ScenePrompts.system()));
            messages.add(ChatMessage.user(ScenePlan.detailing(prompt, planJson, placedText,
                    "(the structure is complete; judge only where small decorations belong)", viewNames), pictures));

            ChatResult result;
            try {
                result = chat(new ChatRequest(config.model, messages, mode.maxTokens, config.temperature,
                        effort(mode.effortEdit, "medium"), true));
            } catch (LlmException e) {
                if (e.isCancellation() || cancelled.get()) return null;
                plugin.getLogger().warning("detailing call failed: " + shortError(e));
                return null;
            }
            JsonObject json = extractObject(result.content());
            List<String> errors = new ArrayList<>();
            List<SceneDetailing.Decor> proposed = SceneDetailing.parse(json, errors);
            if (proposed.isEmpty()) {
                plugin.getLogger().info("detailing: nothing usable (" + String.join("; ", errors) + ")");
                return null;
            }
            List<SceneDetailing.Decor> accepted = SceneDetailing.filter(proposed, placed,
                    id -> {
                        try {
                            org.bukkit.Material m = org.bukkit.Material.matchMaterial(id);
                            return m != null && m.isBlock();
                        } catch (RuntimeException e) {
                            return false;
                        }
                    }, errors);
            if (!errors.isEmpty()) {
                plugin.getLogger().info("detailing: " + errors.size() + " rejected (" 
                        + clip(String.join("; ", errors), 400) + ")");
            }
            if (accepted.isEmpty()) return null;
            if (cancelled.get()) return null;

            // place them through the same point-placement path the placer uses for single blocks
            int applied = 0;
            World world = worldForActor();
            if (world == null) return null;
            for (SceneDetailing.Decor d : accepted) {
                if (cancelled.get()) break;
                try {
                    BlockSpec spec = BlockSpec.parseRaw(d.block());
                    org.bukkit.block.Block block = world.getBlockAt(d.x(), d.y(), d.z());
                    var data = org.bukkit.Bukkit.createBlockData(spec.name());
                    block.setBlockData(data, false);
                    applied++;
                } catch (RuntimeException e) {
                    plugin.getLogger().fine("detailing: could not place " + d.block() + " at "
                            + d.x() + "," + d.y() + "," + d.z() + ": " + e.getMessage());
                }
            }
            if (applied == 0) return null;
            plugin.getLogger().info("detailing: placed " + applied + " of " + proposed.size()
                    + " (rejected " + errors.size() + ")");
            return new DetailingResult(accepted.subList(0, Math.min(applied, accepted.size())),
                    SceneDetailing.describe(accepted.subList(0, Math.min(applied, accepted.size()))));
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "detailing pass failed", t);
            return null;
        }
    }

    // ------------------------------------------------------------------ 1. design

    /**
     * Designs the build the way an architect does: a full brief first, then the scene stage by stage.
     *
     * <p>The first version of this pipeline asked for the whole building in one answer and produced mediocre
     * results: the model never decided dimensions, floors or the detail list, and one answer has to spread its
     * attention over everything. This method mirrors the block pipeline: {@link ScenePlan#plan} produces a brief
     * with exact sizes, floors with rooms, palette roles, at least 25 located details and an ordered stage
     * schedule; then {@link #buildStage} builds each stage in its own call, seeing the renders of what already
     * stands.</p>
     *
     * @param prompt the user request
     * @param mode   the thinking mode (effort and token budget)
     * @return the scene, or {@code null} when the plan itself could not be produced
     */
    private SceneSpec design(String prompt, BuildMode mode) throws Exception {
        JsonObject plan = makePlan(prompt, mode);
        if (plan == null) {
            return null;
        }
        planJson = plan.toString();
        plugin.getLogger().info("scene plan: " + planText(plan, "title") + ", body "
                + bodySize(plan) + ", stages " + stageCount(plan) + ", details " + detailCount(plan));

        List<JsonObject> stages = stages(plan);
        if (stages.isEmpty()) {
            // no schedule: fall back to a single shot, better than nothing
            return designOneShot(prompt, mode);
        }

        SceneSpec spec = SceneSpec.of(planText(plan, "title"), List.of()).withPalette(paletteFrom(plan));
        int total = stages.size();
        int built = 0;
        for (int i = 0; i < total; i++) {
            if (cancelled.get()) throw new CancelledException();
            JsonObject stage = stages.get(i);
            String name = planText(stage, "name");
            String goal = planText(stage, "goal");
            progress = 0.02 + 0.20 * (i + 1) / total;
            status = "Строю " + (i + 1) + "/" + total + ": " + name;
            say("<gray>этап <white><v0>/<v1></white>: <v2></gray>", i + 1, total, name);

            SceneSpec next = buildStage(prompt, plan, i, total, name, goal, spec, mode);
            if (next == null) {
                plugin.getLogger().warning("stage " + (i + 1) + "/" + total + " (" + name + ") produced nothing, skipping");
                say("<gray>этап <v0> не дал объектов, пропускаю</gray>", name);
                continue;
            }
            spec = next;
            built++;
            plugin.getLogger().info("stage " + (i + 1) + "/" + total + " (" + name + "): "
                    + spec.objects().size() + " objects total");
        }
        if (built == 0 || spec.objects().isEmpty()) {
            plugin.getLogger().warning("every stage failed, falling back to a one-shot design");
            return designOneShot(prompt, mode);
        }
        spec = ensureRoof(spec, plan);
        say("<gray>Сцена собрана по плану: <white><v0></white> объектов, размер <white><v1></white>. "
                + "Начинаю итерации с рендерами.</gray>",
                spec.objects().size(), spec.bounds()[2][0] + "×" + spec.bounds()[2][1] + "×" + spec.bounds()[2][2]);
        return spec;
    }

    /**
     * Produces the design brief.
     *
     * @return the parsed brief, or {@code null} when every attempt failed
     */
    private JsonObject makePlan(String prompt, BuildMode mode) throws Exception {
        int[] asked = SceneAnalysis.parseRequestedSize(prompt);
        String hint = asked == null ? null : "The request names the size " + asked[0] + "x" + asked[1]
                + ": the body MUST be exactly that.";
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(ScenePrompts.system()));
        messages.add(ChatMessage.user(ScenePlan.plan(prompt, hint)));
        String problem = null;
        for (int attempt = 1; attempt <= DESIGN_ATTEMPTS; attempt++) {
            if (cancelled.get()) throw new CancelledException();
            status = attempt == 1 ? "Планирую постройку…" : "Повтор плана (" + attempt + "/" + DESIGN_ATTEMPTS + ")…";
            ChatResult result;
            try {
                result = chat(new ChatRequest(config.model, messages, mode.maxTokens, config.temperature,
                        effort(mode.effortPlan, "medium"), true));
            } catch (LlmException e) {
                if (e.isCancellation() || cancelled.get()) throw new CancelledException();
                problem = shortError(e);
                plugin.getLogger().warning("scene plan call failed (attempt " + attempt + "): " + problem);
                if (attempt >= DESIGN_ATTEMPTS) break;
                messages.add(ChatMessage.user(ScenePrompts.repair("transport error: " + problem)));
                continue;
            }
            JsonObject json = extractObject(result.content());
            if (json != null && json.has("stages")) {
                return json;
            }
            problem = json == null ? "the answer is not a JSON object" : "the brief has no stages";
            if (attempt >= DESIGN_ATTEMPTS) break;
            plugin.getLogger().warning("scene plan attempt " + attempt + " rejected: " + problem);
            messages.add(ChatMessage.assistant(clip(result.content(), 3000)));
            messages.add(ChatMessage.user(ScenePrompts.repair(problem)));
        }
        return null;
    }

    /**
     * Guarantees the building has one closed roof.
     *
     * <p>Live runs kept producing "roofs" made of separate beams with gaps between them: the model reads the
     * brief, then builds the roof as a dozen {@code beam} objects laid along the slope. Words in the prompt did
     * not fix it, because the model believes a bundle of beams is a roof. So the code enforces the invariant
     * that matters: if no single {@code prism} covers the body footprint, one is added on top of the walls before
     * anything is rendered or critiqued. The model keeps its beams as decoration on top of a real roof, and the
     * building can no longer end up with holes.</p>
     *
     * @param spec the scene built from the stages
     * @param plan the brief (supplies the roof material role and the body size)
     * @return a scene that definitely has a roof covering the body
     */
    private SceneSpec ensureRoof(SceneSpec spec, JsonObject plan) {
        try {
            int[] body = bodyBox(spec.objects());
            if (body == null) return spec;
            // does an existing prism already cover the body footprint?
            for (SceneObject o : spec.objects()) {
                if (!o.type().equals("prism")) continue;
                int[] from = o.from();
                int[] to = o.to();
                if (from == null || to == null) continue;
                boolean covers = from[0] <= body[0] + 1 && to[0] >= body[1] - 1
                        && from[2] <= body[2] + 1 && to[2] >= body[3] - 1;
                if (covers) return spec;
            }
            // no covering prism: add one on top of the walls
            int topY = body[4];
            JsonObject roof = new JsonObject();
            roof.addProperty("id", "roof_guaranteed");
            roof.addProperty("type", "prism");
            JsonArray from = new JsonArray();
            from.add(body[0] - 1);
            from.add(topY);
            from.add(body[2] - 1);
            JsonArray to = new JsonArray();
            to.add(body[1] + 1);
            to.add(topY);
            to.add(body[3] + 1);
            roof.add("from", from);
            roof.add("to", to);
            roof.addProperty("style", "gable");
            roof.addProperty("ridge", (body[1] - body[0]) >= (body[3] - body[2]) ? "x" : "z");
            roof.addProperty("overhang", 1);
            roof.addProperty("pitch", 1.0);
            roof.addProperty("material", roofMaterial(plan, spec));
            plugin.getLogger().info("no closed roof found: adding a guaranteed gable prism over the body "
                    + "(" + (body[1] - body[0] + 1) + "x" + (body[3] - body[2] + 1) + " at y=" + topY + ")");
            List<JsonObject> all = new ArrayList<>();
            for (SceneObject o : spec.objects()) all.add(o.toJson());
            all.add(roof);
            JsonObject merged = spec.toJson();
            JsonArray arr = new JsonArray();
            for (JsonObject o : all) arr.add(o);
            merged.add("objects", arr);
            SceneSpec parsed = SceneSpec.parse(merged);
            return parsed.objects().isEmpty() ? spec : parsed;
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "ensureRoof failed, keeping the scene as is", e);
            return spec;
        }
    }

    /**
     * @return the palette key to use for a guaranteed roof: the brief's roof role when it exists in the palette,
     *         otherwise any existing key that looks like a roof, otherwise "roof"
     */
    private static String roofMaterial(JsonObject plan, SceneSpec spec) {
        Map<String, String> palette = spec.palette();
        JsonElement pe = plan == null ? null : plan.get("palette");
        if (pe != null && pe.isJsonObject()) {
            JsonElement r = pe.getAsJsonObject().get("roof");
            if (r != null && r.isJsonPrimitive()) {
                String value = r.getAsString();
                // the brief names a material role; reuse the key when the stage palette has it
                if (palette.containsKey(value)) return value;
            }
        }
        for (String key : palette.keySet()) {
            String k = key.toLowerCase(Locale.ROOT);
            if (k.contains("roof") || k.contains("tile") || k.contains("shingle")) return key;
        }
        return "roof";
    }

    /**
     * @return {@code {minX, maxX, minZ, maxZ, topY}} of the largest wall object, or {@code null}
     */
    private static int[] bodyBox(List<SceneObject> objects) {
        int bestArea = 0;
        int[] best = null;
        for (SceneObject o : objects) {
            if (!o.type().equals("box")) continue;
            int[] from = o.from();
            int[] to = o.to();
            if (from == null || to == null) continue;
            int area = (to[0] - from[0] + 1) * (to[2] - from[2] + 1);
            if (area > bestArea) {
                bestArea = area;
                best = new int[]{from[0], to[0], from[2], to[2], to[1]};
            }
        }
        return best;
    }

    /**
     * Builds one stage and merges its objects into the scene.
     *
     * @return the extended scene, or {@code null} when the stage produced nothing usable
     */
    private SceneSpec buildStage(String prompt, JsonObject plan, int index, int total, String name, String goal,
                                 SceneSpec current, BuildMode mode) throws Exception {
        Render render = renderViews(current, index + 1);
        List<byte[]> pictures = render.ok() ? render.pngs() : List.of();
        List<String> viewNames = render.ok() ? render.viewNames() : List.of();
        String existing = current.objects().isEmpty() ? "(nothing yet)" : current.digest(80);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(ScenePrompts.system()));
        String stagePrompt = ScenePlan.stage(prompt, plan.toString(), index + 1, total, name, goal,
                existing, viewNames);
        messages.add(ChatMessage.user(stagePrompt, pictures));

        String problem = null;
        for (int attempt = 1; attempt <= DESIGN_ATTEMPTS; attempt++) {
            if (cancelled.get()) throw new CancelledException();
            ChatResult result;
            try {
                result = chat(new ChatRequest(config.model, messages, mode.maxTokens, config.temperature,
                        effort(mode.effortBuild, "medium"), true));
            } catch (LlmException e) {
                if (e.isCancellation() || cancelled.get()) throw new CancelledException();
                problem = shortError(e);
                if (attempt >= DESIGN_ATTEMPTS) return null;
                messages.add(ChatMessage.user(ScenePrompts.repair("transport error: " + problem)));
                continue;
            }
            JsonObject json = extractObject(result.content());
            if (json == null) {
                problem = "the answer is not a JSON object";
            } else {
                JsonElement objects = json.get("objects");
                if (objects == null || !objects.isJsonArray() || objects.getAsJsonArray().isEmpty()) {
                    problem = "the answer has no objects array";
                } else {
                    SceneSpec merged = mergeObjects(current, objects.getAsJsonArray(), "s" + (index + 1));
                    if (merged != null) {
                        String notes = planText(json, "notes");
                        if (!notes.isBlank() && actor != null) {
                            plugin.getLogger().info("stage " + (index + 1) + "/" + total + " notes: " + clip(notes, 200));
                        }
                        return merged;
                    }
                    problem = "the stage objects are invalid";
                }
            }
            if (attempt >= DESIGN_ATTEMPTS) return null;
            messages.add(ChatMessage.assistant(clip(result.content(), 3000)));
            messages.add(ChatMessage.user(ScenePrompts.repair(problem)));
        }
        return null;
    }

    /**
     * Merges a stage's objects into the scene, prefixing ids so two stages cannot collide.
     *
     * @return the merged scene, or {@code null} when nothing survived validation
     */
    private SceneSpec mergeObjects(SceneSpec current, JsonArray objects, String prefix) {
        List<JsonObject> accepted = new ArrayList<>();
        for (JsonElement e : objects) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject().deepCopy();
            String id = SceneObject.str(o, "id");
            if (id.isBlank()) {
                id = prefix + "_" + (accepted.size() + 1);
            } else if (!id.startsWith(prefix + "_") && !id.startsWith(prefix)) {
                id = prefix + "_" + id;
            }
            o.addProperty("id", id);
            accepted.add(o);
        }
        if (accepted.isEmpty()) return null;
        // validate by round-tripping through the spec parser: bad objects are dropped, not fatal
        List<JsonObject> all = new ArrayList<>();
        for (SceneObject o : current.objects()) all.add(o.toJson());
        all.addAll(accepted);
        JsonObject merged = current.toJson();
        JsonArray arr = new JsonArray();
        for (JsonObject o : all) arr.add(o);
        merged.add("objects", arr);
        SceneSpec parsed = SceneSpec.parse(merged);
        if (parsed.objects().isEmpty()) return null;
        if (!parsed.errors().isEmpty()) {
            plugin.getLogger().info("stage merge dropped " + parsed.errors().size() + " invalid object(s): "
                    + clip(String.join("; ", parsed.errors()), 300));
        }
        return parsed;
    }

    /**
     * Single-shot design, used only when the brief or every stage failed.
     *
     * @return the scene, or {@code null}
     */
    private SceneSpec designOneShot(String prompt, BuildMode mode) throws Exception {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(ScenePrompts.system()));
        messages.add(ChatMessage.user(ScenePrompts.initial(prompt)));
        for (int attempt = 1; attempt <= DESIGN_ATTEMPTS; attempt++) {
            if (cancelled.get()) throw new CancelledException();
            ChatResult result;
            try {
                result = chat(new ChatRequest(config.model, messages, mode.maxTokens, config.temperature,
                        effort(mode.effortPlan, "medium"), true));
            } catch (LlmException e) {
                if (e.isCancellation() || cancelled.get()) throw new CancelledException();
                if (attempt >= DESIGN_ATTEMPTS) return null;
                messages.add(ChatMessage.user(ScenePrompts.repair(shortError(e))));
                continue;
            }
            JsonObject json = extractObject(result.content());
            if (json != null) {
                try {
                    SceneSpec spec = SceneSpec.parse(json);
                    if (!spec.objects().isEmpty()) return spec;
                } catch (RuntimeException ignored) {
                    // fall through to the repair message
                }
            }
            if (attempt >= DESIGN_ATTEMPTS) return null;
            messages.add(ChatMessage.assistant(clip(result.content(), 3000)));
            messages.add(ChatMessage.user(ScenePrompts.repair("invalid scene")));
        }
        return null;
    }

    /**
     * Extracts the first JSON object from a model answer.
     *
     * @param content the raw answer
     * @return the object, or {@code null}
     */
    private static JsonObject extractObject(String content) {
        try {
            JsonElement value = Json.extract(content).value();
            if (value.isJsonObject()) return value.getAsJsonObject();
        } catch (RuntimeException ignored) {
            // not JSON at all
        }
        return null;
    }

    /** @return the stages of a brief, in order */
    private static List<JsonObject> stages(JsonObject plan) {
        JsonElement e = plan.get("stages");
        List<JsonObject> out = new ArrayList<>();
        if (e == null || !e.isJsonArray()) return out;
        for (JsonElement s : e.getAsJsonArray()) {
            if (s.isJsonObject()) out.add(s.getAsJsonObject());
        }
        return out;
    }

    /** @return a string field of a plan fragment, or "" */
    private static String planText(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    /** @return "WxD" from the brief's body, or "?" */
    private static String bodySize(JsonObject plan) {
        JsonElement e = plan.get("body");
        if (e == null || !e.isJsonObject()) return "?";
        JsonObject b = e.getAsJsonObject();
        return SceneObject.num(b, "x", 0) + "x" + SceneObject.num(b, "z", 0);
    }

    /** @return the number of stages in the brief */
    private static int stageCount(JsonObject plan) {
        return stages(plan).size();
    }

    /** @return the number of details listed in the brief */
    private static int detailCount(JsonObject plan) {
        JsonElement e = plan.get("details");
        return e != null && e.isJsonArray() ? e.getAsJsonArray().size() : 0;
    }

    /** @return the palette from the brief, mapped to hex colours where possible */
    private static Map<String, String> paletteFrom(JsonObject plan) {
        Map<String, String> out = new LinkedHashMap<>();
        JsonElement e = plan.get("palette");
        if (e == null || !e.isJsonObject()) return out;
        for (Map.Entry<String, JsonElement> entry : e.getAsJsonObject().entrySet()) {
            if (!entry.getValue().isJsonPrimitive()) continue;
            String value = entry.getValue().getAsString();
            if (value.startsWith("#")) out.put(entry.getKey(), value);
        }
        return out;
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
