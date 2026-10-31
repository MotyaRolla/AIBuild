package dev.aibuild.pipeline;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.aibuild.ai.ChatMessage;
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
import dev.aibuild.model.BlockInfo;
import dev.aibuild.model.BlockSpec;
import dev.aibuild.model.ShapeResolver;
import dev.aibuild.model.VoxelModel;
import dev.aibuild.model.ops.OpExecutor;
import dev.aibuild.model.ops.OpResult;
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
import java.util.Locale;
import java.util.Map;
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
        job.future = pool.submit(() -> runGuarded(job, build, () -> runCreate(job, build, prompt, anchor, false)));
        return job;
    }

    public Job startEdit(Build build, String change, BuildMode mode) {
        Job job = new Job(build.id, build.owner, Job.Kind.EDIT, change, mode);
        jobsByBuild.put(build.id, job);
        job.future = pool.submit(() -> runGuarded(job, build, () -> runEdit(job, build, change)));
        return job;
    }

    private interface Task {
        void run() throws Exception;
    }

    private void runGuarded(Job job, Build build, Task task) {
        BuildMetadata previous = null;
        try {
            previous = sync(() -> new BuildMetadata(build.name, build.originalPrompt,
                    build.plan == null ? null : build.plan.deepCopy()));
            task.run();
        } catch (VerificationRejectedException e) {
            restoreCurrent(build, previous);
            say(job, "<yellow>Правка <white><v0></white> не прошла проверку. Прежняя версия восстановлена; черновик сохранён. <v1></yellow>",
                    build.id, clip(e.getMessage(),180));
        } catch (CancelledException | InterruptedException e) {
            say(job, "<yellow>Задача для постройки <white><v0></white> отменена.</yellow>", build.id);
            restoreCurrent(build, previous);
        } catch (Exception e) {
            if (job.cancelled.get()) {
                say(job, "<yellow>Задача для постройки <white><v0></white> отменена.</yellow>", build.id);
            } else {
                plugin.getLogger().log(Level.WARNING, "AI job failed for build " + build.id, e);
                say(job, "<red>Ошибка ИИ: <v0></red>", shortError(e));
            }
            restoreCurrent(build, previous);
        } finally {
            job.finished = true;
            jobsByBuild.remove(build.id, job);
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
        Staged st = c.createStrategy.equals("compiled")
                ? designAndCompile(job, tr, prompt, build, anchor, isRebuild)
                : designAndStage(job, tr, prompt, build, anchor, isRebuild);
        OpExecutor exec = new OpExecutor(info).withLimits(st.limits);
        String strategy = c.reviewStrategy;
        int cap = "adaptive".equals(strategy) ? c.adaptiveMaxRounds : job.mode.verifyRounds;
        Verdict v = verify(job, tr, exec, st.model, st.plan, st.planText, prompt, null, Checklist.forCreate(st.plan),
                strategy, cap, 0.68);
        commit(job, build, st.model, isRebuild ? "Пересоздано: " + prompt : prompt, v, tr);
    }

    private Staged designAndCompile(Job job, Transcript tr, String prompt, Build build, Anchor anchor,
                                    boolean isRebuild) throws Exception {
        job.status("ИИ проектирует цельную постройку…", 0.02);
        JsonObject program = callJson(job, tr, "compile", job.mode.effortBuild, Prompts.compile(prompt));
        JsonObject plan = Json.obj(program, "plan");
        if (plan == null || Json.arr(program, "ops").isEmpty()) {
            throw new IllegalStateException("build program must contain a plan and non-empty ops");
        }
        normalizePlan(plan, cfg.get());
        plan.remove("stages");
        int[] plot = plotBounds(plan);
        sync(() -> {
            build.name = Json.str(plan, "title", "Постройка");
            build.plan = plan;
            if (build.originalPrompt == null || isRebuild) build.originalPrompt = prompt;
            if (anchor != null && !build.placed && build.versions.isEmpty()) place(build, anchor, plot);
            store.save(build);
            return null;
        });
        job.plotLimits = new int[]{plot[0] - 3, plot[1] - 2, plot[2] - 3, plot[3] + 3, plot[4] + 2, plot[5] + 3};
        Staged st = new Staged();
        st.plan = plan;
        st.planText = Json.GSON.toJson(planForPrompt(plan));
        st.model = new VoxelModel();
        st.limits = job.plotLimits;
        OpResult result = applyWithRepair(job, tr, new OpExecutor(info).withLimits(st.limits), st.model, program, "compile");
        for (var doorway : result.doorways) job.doorways.add(doorway.followCompiledEntrance(st.model,info));
        tr.note("compile result", result.summary() + "\nresolver: " + postProcess(job, st.model));
        livePreview(build, st.model);
        say(job, "<gray>Программа постройки готова: <white><v0></white>, <v1> блоков. Проверяем конструкцию, фасады и интерьер.</gray>",
                build.name, st.model.nonAirCount());
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
                livePreview(build, model);
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
        VoxelModel model = sync(() -> {
            VoxelModel m = build.currentModel();
            return m == null ? null : m.copy();
        });
        if (model == null) throw new IllegalStateException("у постройки нет версии для редактирования");
        JsonObject plan = sync(() -> build.plan == null ? null : build.plan.deepCopy());
        if (plan == null) plan = syntheticPlan(build.name);
        String original = build.originalPrompt == null ? Json.str(plan, "title", "") : build.originalPrompt;
        String history = sync(() -> String.join(" | ", build.history));

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
        livePreview(build, model);
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
        livePreview(build, model);
        er.summary = Json.str(res, "summary", er.summary);
        return er;
    }

    /** Shared first half of the planned strategies: edit plan + limits + checklist. */
    private EditResult planEdit(Job job, Transcript tr, VoxelModel model, JsonObject plan, String original,
                                String history, String change) throws Exception {
        job.status("ИИ планирует изменение…", 0.04);
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
        while (round < cap) {
            checkCancelled(job);
            AnalysisReport rep = StructureAnalyzer.analyze(model, info, aopts);
            List<String> active = new ArrayList<>();
            for (Map.Entry<String, Boolean> e : finished.entrySet()) if (!e.getValue()) active.add(e.getKey());
            if (active.isEmpty()) {
                boolean critical = rep.count(dev.aibuild.analysis.Severity.CRITICAL) > 0;
                List<String> open = cl.openAreas();
                if (open.isEmpty() && !critical) break; // every reviewer is satisfied and everything is verified
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
            String render = render(model);
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
                String user = Prompts.reviewAdaptive(title, instr, request, planText, round, render, analysis, checklistText, hist, c.minScore);
                String tag = "areview" + round + "-" + role;
                tags.add(tag);
                futures.add(side.submit(() -> callJson(job, tr, tag, effort, user)));
            }
            int changed = 0, issues = 0;
            VoxelModel reviewSnapshot = model.copy();
            Map<String,JsonArray> roundProofs = new java.util.LinkedHashMap<>();
            for (int k = 0; k < futures.size(); k++) {
                String role = active.get(k);
                JsonObject res = await(futures.get(k), job);
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
                        r.opsFailed,merged.conflicts(),cl.allVerified(role),proven);
                if (!qualityFailures.isEmpty()) history.add("round "+round+" "+role+" quality remains: "+String.join("; ",qualityFailures));
                finished.put(role, fin);
                if (fin) v.blockers.remove(role);
                else {
                    List<String> pending=new ArrayList<>(qualityFailures);
                    if (ReviewDecision.hasUnresolvedSerious(res,proofs.checked())) pending.add("unresolved critical/major reviewer finding");
                    if (!cl.allVerified(role)) pending.add("checklist not fully verified");
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
            for (var entry : roundProofs.entrySet()) {
                PatchProofs.Result finalProofs = PatchProofs.verify(entry.getValue(),model,info);
                if (cl.apply(finalProofs.checked(),round,entry.getKey())>0) finished.put(entry.getKey(),false);
            }
            v.rounds = round;
            v.issuesFixed += issues;
            int fin = 0;
            for (boolean b : finished.values()) if (b) fin++;
            if (!lastScore.isEmpty()) v.score = (int) Math.round(lastScore.values().stream().mapToInt(Integer::intValue).average().orElse(-1));
            say(job, "<gray>🔍 Проверка, раунд <white><v0></white>: чек-лист <v1>, экспертов завершило <v2>/<v3>, исправлено блоков <v4>, оценка <v5></gray>",
                    round, cl.progress(), fin, roles.size(), changed, scoreColor(v.score));
            livePreview(build, model);
            tr.note("areview" + round + " state", String.join("\n", history) + "\n\nCHECKLIST\n" + cl.render(null));
            if (round>1 && changed==0 && finished.containsValue(false)) {
                stalled=true;
                tr.note("verification stopped","No model changes and acceptance still fails; stop retrying unchanged evidence.");
                break;
            }
        }
        boolean allFinished = !finished.containsValue(false);
        v.approved = allFinished && cl.allVerified() && v.score >= c.minScore;
        v.summary = summaries.isEmpty() ? null : summaries.get(summaries.size() - 1);
        v.checklist = cl.progress() + (v.approved ? " (all verified)" : stalled ? " (no progress)" : allFinished ? "" : " (round cap reached)");
        v.finalReport = StructureAnalyzer.analyze(model, info, aopts);
        if (v.finalReport.hasBlocking()) v.approved = false;
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
            job.status("Самопроверка ИИ " + round + "/" + rounds + (parallel ? " (3 эксперта)" : "")
                    + " · проблем найдено автоматически: " + rep.issues.size(), p0 + span * (round - 1));
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
                allApproved &= Json.bool(res, "approved", false) && sc >= c.minScore
                        && ReviewDecision.qualityFailures(res,role).isEmpty()
                        && !ReviewDecision.hasUnresolvedSerious(res,new JsonArray());
                String sm = Json.str(res, "summary", null);
                if (sm != null && !sm.isBlank()) summaries.add(sm);
                issues += Json.arr(res, "issues").size();
                // guard: a single review must polish, not rebuild — reject responses that rewrite too much
                VoxelModel before = model.copy();
                int baseBlocks = Math.max(1, before.nonAirCount());
                OpResult r = applyWithRepair(job, tr, exec, model, res, tags.get(k));
                if (r.opsFailed>0 || r.cellsChanged>12) allApproved=false;
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
            v.approved = allApproved;
            if (!summaries.isEmpty()) v.summary = String.join(" ", summaries);
            v.issuesFixed += issues;
            say(job, v.approved
                            ? "<gray>🔍 Проверка <white><v0>/<v1></white>: оценка <v2>, замечаний <v3>, исправлено блоков <v4> — <green>одобрено</green></gray>"
                            : "<gray>🔍 Проверка <white><v0>/<v1></white>: оценка <v2>, замечаний <v3>, исправлено блоков <v4> — <yellow>нужны доработки</yellow></gray>",
                    round, rounds, scoreColor(v.score), issues, changed);
            livePreview(build, model);
            boolean blocking = StructureAnalyzer.analyze(model, info, aopts).hasBlocking();
            tr.note("review" + round + " verdict", "score=" + v.score + " approved=" + v.approved + " issues=" + issues
                    + " changed=" + changed + " blockingAfter=" + blocking);
            if (v.approved && v.score >= c.minScore && !blocking) break;
        }
        v.finalReport = StructureAnalyzer.analyze(model, info, aopts);
        if (v.finalReport.hasBlocking()) v.approved=false;
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
        Variant vr = new Variant();
        vr.name = name;
        Job child = parent.child(name.replace('+', '-'));
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
    private void say(Job job, String template, Object... args) {
        if (job.quiet) return;
        Msg.to(job.owner, template, args);
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
        if (model.nonAirCount() == 0) throw new IllegalStateException("ИИ не создал ни одного блока");
        VoxelModel fin = model.copy();
        if (v.finalReport != null && v.finalReport.hasBlocking()) v.approved=false;
        if (!v.approved) {
            writeDraft(build,fin,request,v);
            writeMetrics(job,build,fin,v,tr);
            if (!build.versions.isEmpty()) throw new VerificationRejectedException(v.checklist+" "+v.blockers);
        }
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
        if (!v.approved) say(job,"<yellow>Не пройдено: <v0></yellow>",clip(v.blockers.isEmpty()?v.checklist:v.blockers.toString(),260));
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
        metrics.addProperty("pipelineRevision","text-quality-v1");
        metrics.addProperty("model",cfg.get().model);
        metrics.add("quality",v.quality.deepCopy());
        metrics.add("blockers",Json.GSON.toJsonTree(v.blockers));
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
            try {
                res = llm.get().chat(req, (cc, rc) -> {
                    job.streamChars = cc;
                    job.reasoningChars = rc;
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
            tr.exchange(tag + (attempt > 0 ? "-retry" + attempt : "") + "-" + (eff == null ? "default" : eff), userMsg, res);
            boolean truncated = "length".equals(res.finishReason());
            boolean empty = res.content() == null || res.content().isBlank();
            if (!empty && !truncated) {
                try {
                    Json.Extracted ex = Json.extract(res.content());
                    if (ex.repaired()) plugin.getLogger().info("[" + tag + "] JSON repaired: " + ex.note() + " (finish=" + res.finishReason() + ")");
                    JsonElement el = ex.value();
                    if (el.isJsonObject()) return el.getAsJsonObject();
                    if (el.isJsonArray()) {
                        JsonObject o = new JsonObject();
                        o.add("ops", el);
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
            r.merge(r2);
        }
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
            List<String> o = dev.aibuild.model.OrientationFixer.fix(model, info);
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
        AnalyzerOptions o = new AnalyzerOptions();
        o.expectInterior = Json.bool(plan, "expect_interior", true);
        JsonObject body = Json.obj(plan, "body");
        if (body != null && body.has("x") && body.has("z")) {
            o.expectedFootprint = new int[]{Json.integer(body, "x", 0), Json.integer(body, "z", 0)};
            if (o.expectedFootprint[0] <= 0 || o.expectedFootprint[1] <= 0) o.expectedFootprint = null;
        }
        return o;
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
            if (!enabled) return;
            write(String.format("%02d-%s.txt", seq.incrementAndGet(), tag.replace(' ', '_')), text);
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
