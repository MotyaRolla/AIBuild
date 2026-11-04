package dev.aibuild.pipeline;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.aibuild.ai.ChatMessage;
import dev.aibuild.ai.LlmException;
import dev.aibuild.analysis.AnalysisReport;
import dev.aibuild.analysis.AnalyzerOptions;
import dev.aibuild.analysis.CraftReview;
import dev.aibuild.analysis.SpatialReport;
import dev.aibuild.analysis.StructureAnalyzer;
import dev.aibuild.analysis.TextRenderer;
import dev.aibuild.model.BlockInfo;
import dev.aibuild.model.BlockSpec;
import dev.aibuild.model.VoxelModel;
import dev.aibuild.render.ContactSheet;
import dev.aibuild.render.RedPen;
import dev.aibuild.render.VoxelRaster;
import dev.aibuild.script.ScriptLint;
import dev.aibuild.script.ScriptPatch;
import dev.aibuild.script.ScriptResult;
import dev.aibuild.script.ScriptRuntime;
import dev.aibuild.script.Stdlib;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * The studio: one conversation in which the model runs its own build loop. It writes the script, sees what it
 * built (renders, analysis, warnings), looks wherever it wants, reads the builder's handbook, hands parts to
 * helpers, and decides itself when the build is done and which version ships.
 *
 * <p>The plugin only executes and reports. Nothing the model makes is rejected, gated or edited behind its back:
 * checks are information, the finisher is a proposal, and a failed run leaves the current version in place with
 * the traceback. The only limits are the wall clock the player waits on and a fuse on the number of turns.</p>
 */
public final class StudioSession {

    /** What the session needs from the pipeline: model calls, preparation of a run, and the live views. */
    public interface Host {
        /** One model call with these messages; {@code tag} names it in logs and traces. */
        String call(List<ChatMessage> msgs, String tag) throws Exception;

        /** Doors, orientation and shape resolution, as every run that is shown or stored gets. */
        void prepare(ScriptResult r);

        /** Analyzer options for a run (from its meta()). */
        AnalyzerOptions options(ScriptResult r);

        /** A new current version: update the live view and the world preview. */
        default void shown(Version v) {
        }

        default void status(String text) {
        }

        default boolean cancelled() {
            return false;
        }

        /** Threads for helper sessions; null disables delegation. */
        default ExecutorService pool() {
            return null;
        }
    }

    /** Session limits and switches. */
    public static final class Settings {
        public long deadlineNanos = Long.MAX_VALUE;
        public int maxTurns = 40;
        public int imageSize = 1536;
        public boolean eyes = true;
        public boolean delegate = true;
        public int helperTurns = 12;
        public int contextTokens = 150_000;
        /**
         * Raw PNG bytes of all images in one request. Base64 makes them a third bigger and providers cap a request
         * body (32 MB at the gateway), so older turns lose their images first.
         */
        public long imageBytes = 14_000_000;
        public LongSupplier clock = System::nanoTime;
        public String tag = "studio";
    }

    /** One run of the script that succeeded. */
    public record Version(int n, String source, ScriptResult result, AnalysisReport report, QualityVector quality, String origin,
                          CraftReview.Result craft) {
    }

    /** How the session ended. {@code shipped} is null only when no version ever ran. */
    public record Outcome(Version shipped, List<Version> versions, int turns, boolean done, String reason, String diagnosis) {
    }

    /** One model answer and the plugin's reply to it. */
    private record Exchange(String answer, String reply, List<byte[]> images) {
    }

    private final class Helper {
        final int id;
        final String part, brief;
        final Future<Outcome> future;
        boolean reported, closed;

        Helper(int id, String part, String brief, Future<Outcome> future) {
            this.id = id;
            this.part = part;
            this.brief = brief;
            this.future = future;
        }
    }

    private final Host host;
    private final BlockInfo info;
    private final ScriptRuntime runtime;
    private final long seed;
    private final int[] limits;
    private final ScriptRuntime.Budget budget;
    private final Settings s;
    private final String system;
    private final String start;
    /** The part a helper session works on; null for the lead. */
    private final String focus;
    private final String request;

    private final List<Version> versions = new ArrayList<>();
    private final List<Exchange> history = new ArrayList<>();
    private final List<Helper> helpers = new ArrayList<>();
    private List<byte[]> startImages = List.of();
    /** Exchanges before this index carry no images any more; it only moves forward, so the cached prefix stays put. */
    private int imageCutoff;
    /** The image budget for the next call: halved after a request was too large. */
    private long imageBudget = -1;
    private Version current;
    private String lastDiagnosis = "";
    /** Red-pen marks the model said are intended (KEEP n): not marked again. */
    private final List<CraftReview.Mark> kept = new ArrayList<>();
    /** The marks of the last report, by the numbers the model saw. */
    private List<CraftReview.Mark> shownMarks = List.of();
    /** Options (name + choices) already built side by side. */
    private final java.util.Set<String> explored = new java.util.HashSet<>();
    /** Versions a blind comparison was already made for. */
    private final java.util.Set<Integer> blindChecked = new java.util.HashSet<>();
    private record Blind(Version a, Version b, Version ship, Version earlier) {
    }
    private Blind blind;
    /** Set when the model's PREFER confirms the version it was about to ship. */
    private Version preferShip;

    public StudioSession(Host host, BlockInfo info, ScriptRuntime runtime, long seed, int[] limits, ScriptRuntime.Budget budget,
                         Settings settings, String system, String start, String request) {
        this(host, info, runtime, seed, limits, budget, settings, system, start, request, null);
    }

    private StudioSession(Host host, BlockInfo info, ScriptRuntime runtime, long seed, int[] limits, ScriptRuntime.Budget budget,
                          Settings settings, String system, String start, String request, String focus) {
        this.host = host;
        this.info = info;
        this.runtime = runtime;
        this.seed = seed;
        this.limits = limits;
        this.budget = budget;
        this.s = settings;
        this.system = system;
        this.start = start;
        this.request = request;
        this.focus = focus;
    }

    public List<Version> versions() {
        return versions;
    }

    /**
     * Runs the session. {@code initial} is an existing script to start from (an edit, a helper), shown to the model
     * as version 1 with its renders; null for a new build.
     */
    public Outcome run(String initial) throws Exception {
        String opening = start;
        if (initial != null) {
            Attempt a = execute(initial, "the build as it is now");
            if (a.version == null) {
                opening += "\n\nThe current script does not run:\n" + a.error;
            } else {
                List<byte[]> imgs = new ArrayList<>();
                opening += "\n\n" + report(a.version, null, imgs);
                startImages = imgs;
            }
        }
        int turn = 0;
        String reason = "turn limit";
        boolean done = false;
        Version chosen = null;
        while (turn < s.maxTurns) {
            if (host.cancelled()) {
                reason = "cancelled";
                break;
            }
            if (left() <= 0) {
                reason = "time is up";
                break;
            }
            turn++;
            host.status((focus == null ? "" : "helper " + focus + ": ") + "turn " + turn + (current == null ? "" : ", version " + current.n));
            String answer;
            try {
                answer = callWithin(opening, turn);
            } catch (Exception e) {
                if (current == null || cancellation(e)) throw e;
                // a failed call must not throw away the versions already built: ship what there is
                reason = "the model call failed: " + clip(String.valueOf(e.getMessage()), 200);
                break;
            }
            StudioProtocol.Turn t = StudioProtocol.parse(answer);
            String diag = diagnosis(answer);
            if (!diag.isEmpty()) lastDiagnosis = diag;
            List<byte[]> images = new ArrayList<>();
            StringBuilder reply = new StringBuilder();
            Version before = current;
            preferShip = null;
            boolean failedChange = act(t, reply, images);
            if (preferShip != null && !t.done() && !t.changes()) {
                chosen = preferShip;
                done = true;
                reason = "the model finished";
                break;
            }
            if (t.done() && !failedChange) {
                Version want = pick(t.doneVersion());
                if (want != null && blindCheck(want, reply, images)) {
                    reply.append('\n').append(footer(turn));
                    history.add(new Exchange(answer, reply.toString(), images));
                    continue;
                }
                chosen = want;
                if (chosen != null || current == null) {
                    done = true;
                    reason = "the model finished";
                    break;
                }
                reply.append("DONE ").append(t.doneVersion()).append(": there is no such version.\n");
            } else if (t.done()) {
                reply.append("DONE was not taken: the change in the same answer failed, so the build is not what you"
                        + " finished. Fix it, or say DONE to ship version ").append(current == null ? "-" : current.n).append(".\n");
            }
            if (current != null && current != before) reply.append('\n').append(report(current, before, images));
            reply.append('\n').append(footer(turn));
            history.add(new Exchange(answer, reply.toString(), images));
        }
        stopHelpers();
        Version shipped = chosen != null ? chosen : current;
        return new Outcome(shipped, List.copyOf(versions), turn, done, reason, lastDiagnosis);
    }

    // ------------------------------------------------------------------ one turn

    /** Carries out a turn; true when it asked for a change that did not produce a version. */
    private boolean act(StudioProtocol.Turn t, StringBuilder reply, List<byte[]> images) throws Exception {
        for (String p : t.problems()) reply.append("Could not read: ").append(p).append('\n');
        boolean failed = false;
        if (t.revert() != null) {
            Version v = pick(t.revert());
            if (v == null) reply.append("REVERT ").append(t.revert()).append(": there is no such version.\n");
            else {
                current = v;
                if (focus == null) host.shown(v);
                reply.append("Reverted: version ").append(v.n).append(" is current again.\n");
            }
        }
        if (t.changes()) failed = !change(t, reply);
        if (!t.picks().isEmpty()) failed |= !decide(t.picks(), reply);
        for (int n : t.keeps()) {
            CraftReview.Mark m = n >= 1 && n <= shownMarks.size() ? shownMarks.get(n - 1) : null;
            if (m == null) reply.append("KEEP ").append(n).append(": the last red pen has no mark ").append(n).append(".\n");
            else {
                kept.add(m);
                reply.append("Mark ").append(n).append(" (").append(m.kind()).append(") is kept as intended; it will not be marked again.\n");
            }
        }
        if (t.prefer() != null) prefer(t.prefer(), reply);
        if (t.finish()) finish(reply);
        for (int id : t.accepts()) accept(id, reply);
        for (int id : t.discards()) {
            Helper h = helper(id);
            if (h == null) reply.append("DISCARD d").append(id).append(": no such helper.\n");
            else {
                h.closed = true;
                h.future.cancel(true);
                reply.append("Helper d").append(id).append(" discarded.\n");
            }
        }
        for (StudioProtocol.Delegate d : t.delegates()) delegate(d, reply);
        if (t.waitHelpers()) waitForHelpers(reply);
        for (String g : t.guides()) {
            String text = Handbook.chapter(Stdlib.V2, g);
            reply.append(text == null ? "guide: no chapter '" + g + "'. Chapters: " + String.join(", ", Handbook.TOPICS.keySet()) + "\n"
                    : "\n" + text + "\n");
        }
        for (int[] box : t.inspects()) reply.append('\n').append(inspect(box)).append('\n');
        if (!t.looks().isEmpty()) look(t.looks(), reply, images);
        helperNews(reply, images);
        if (!t.acts()) {
            reply.append("Nothing to do in that answer. Write the script (```js) or SEARCH/REPLACE hunks, look, inspect, guide"
                    + (s.delegate && focus == null ? ", delegate" : "") + ", or say DONE when the build is finished.\n");
        }
        return failed;
    }

    private boolean change(StudioProtocol.Turn t, StringBuilder reply) {
        String base = current == null ? "" : current.source;
        String next;
        String how;
        if (!t.hunks().isEmpty()) {
            if (current == null) {
                reply.append("There is no script yet to apply SEARCH/REPLACE hunks to: write the whole script in a ```js block.\n");
                return false;
            }
            StringBuilder text = new StringBuilder();
            for (String[] h : t.hunks()) text.append("<<<<<<< SEARCH\n").append(h[0]).append("\n=======\n").append(h[1]).append("\n>>>>>>> REPLACE\n");
            ScriptPatch.Result p = ScriptPatch.apply(base, text.toString());
            if (!p.ok()) {
                reply.append("The hunks did not apply (nothing changed, version ").append(current.n).append(" stays current): ")
                        .append(p.error()).append('\n');
                return false;
            }
            next = p.source();
            how = t.hunks().size() + " hunk" + (t.hunks().size() == 1 ? "" : "s");
        } else if (focus != null && current != null) {
            ScriptPatch.Result p = ScriptPatch.replacePart(base, focus, t.script());
            if (!p.ok()) {
                reply.append("Your part was not replaced: ").append(p.error()).append('\n');
                return false;
            }
            next = p.source();
            how = "new part('" + focus + "')";
        } else {
            next = t.script();
            how = "whole script";
        }
        Attempt a = execute(next, how);
        if (a.version == null) {
            reply.append("The script failed").append(current == null ? "" : " (version " + current.n + " stays current)")
                    .append(":\n").append(a.error).append('\n');
            return false;
        }
        return true;
    }

    private record Attempt(Version version, String error) {
    }

    private Attempt execute(String source, String origin) {
        ScriptResult r = runtime.run(source, seed, null, limits, budget, null);
        if (!r.ok()) return new Attempt(null, r.error().describe());
        host.prepare(r);
        AnalysisReport rep = StructureAnalyzer.analyze(r.model(), info, host.options(r));
        Version v = new Version(versions.size() + 1, source, r, rep, QualityVector.of(rep, r), origin, review(r));
        versions.add(v);
        current = v;
        if (focus == null) host.shown(v);     // a helper's versions are its own until the lead accepts them
        return new Attempt(v, null);
    }

    private void finish(StringBuilder reply) {
        if (current == null) {
            reply.append("FINISH: there is no build yet.\n");
            return;
        }
        Finisher.Result f = Finisher.plan(current.result.model(), info, current.result.collisions(), host.options(current.result).expectInterior);
        if (f.statement() == null) {
            reply.append("FINISH: the finisher has nothing to repair.\n");
            return;
        }
        ScriptPatch.Result p = ScriptPatch.putPart(current.source, Finisher.PART, f.statement());
        if (!p.ok()) {
            reply.append("FINISH failed: ").append(p.error()).append('\n');
            return;
        }
        Attempt a = execute(p.source(), "finisher repairs");
        reply.append(a.version == null ? "FINISH: the repaired script fails: " + a.error + "\n"
                : "FINISH applied as part('finish'): " + String.join("; ", f.notes()) + "\n");
    }

    // ------------------------------------------------------------------ helpers

    private void delegate(StudioProtocol.Delegate d, StringBuilder reply) {
        ExecutorService pool = host.pool();
        if (focus != null || !s.delegate || pool == null) {
            reply.append("delegate: not available here; do the work yourself.\n");
            return;
        }
        if (current == null || ScriptLint.partSpan(current.source, d.part()) == null) {
            reply.append("delegate: the current script has no part('").append(d.part()).append("').\n");
            return;
        }
        int id = helpers.size() + 1;
        String lead = current.source;
        Settings hs = new Settings();
        // a helper ends 90 s before the lead, so the lead still has time to look at its part and decide
        hs.deadlineNanos = s.deadlineNanos == Long.MAX_VALUE ? Long.MAX_VALUE : s.deadlineNanos - TimeUnit.SECONDS.toNanos(90);
        hs.maxTurns = s.helperTurns;
        hs.imageSize = s.imageSize;
        hs.eyes = s.eyes;
        hs.delegate = false;
        hs.contextTokens = s.contextTokens;
        hs.imageBytes = s.imageBytes;
        hs.clock = s.clock;
        hs.tag = s.tag + "-d" + id;
        Map<String, String> bounds = new LinkedHashMap<>();
        for (ScriptResult.Part p : current.result.parts()) bounds.put(p.name(), "x " + p.bounds()[0] + ".." + p.bounds()[3]
                + ", y " + p.bounds()[1] + ".." + p.bounds()[4] + ", z " + p.bounds()[2] + ".." + p.bounds()[5]);
        String folded = ScriptLint.foldOtherParts(lead, d.part(), bounds);
        String helperStart = ScriptPrompts.studioHelper(request, d.part(), d.brief(), folded, current.result.sheetSection());
        StudioSession sub = new StudioSession(host, info, runtime, seed, limits, budget, hs, system, helperStart, request, d.part());
        Future<Outcome> f = pool.submit(() -> sub.run(lead));
        helpers.add(new Helper(id, d.part(), d.brief(), f));
        reply.append("Helper d").append(id).append(" is refining part('").append(d.part()).append("') in parallel. Keep working;")
                .append(" when it finishes you will see its result here and decide ACCEPT d").append(id).append(" or DISCARD d").append(id)
                .append(". WAIT blocks until a helper finishes.\n");
    }

    private Helper helper(int id) {
        return id >= 1 && id <= helpers.size() ? helpers.get(id - 1) : null;
    }

    private void waitForHelpers(StringBuilder reply) {
        List<Helper> open = helpers.stream().filter(h -> !h.closed && !h.future.isDone()).toList();
        if (open.isEmpty()) {
            reply.append("WAIT: no helper is working.\n");
            return;
        }
        while (open.stream().noneMatch(h -> h.future.isDone()) && left() > 0 && !host.cancelled()) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Reports helpers that finished since the last reply. */
    private void helperNews(StringBuilder reply, List<byte[]> images) {
        for (Helper h : helpers) {
            if (h.reported || h.closed || !h.future.isDone()) continue;
            h.reported = true;
            Outcome o;
            try {
                o = h.future.get();
            } catch (Exception e) {
                reply.append("Helper d").append(h.id).append(" (part ").append(h.part).append(") failed: ").append(e.getMessage()).append('\n');
                h.closed = true;
                continue;
            }
            if (o.shipped() == null || o.shipped().n == 1) {
                reply.append("Helper d").append(h.id).append(" (part ").append(h.part).append(") finished without a change.\n");
                h.closed = true;
                continue;
            }
            reply.append("Helper d").append(h.id).append(" finished part('").append(h.part).append("') after ").append(o.turns())
                    .append(" turns: ").append(o.diagnosis().isEmpty() ? "(no summary)" : clip(o.diagnosis(), 400))
                    .append("\nSay ACCEPT d").append(h.id).append(" to put its part into your current script, or DISCARD d").append(h.id).append(".\n");
            if (s.eyes) {
                int[] box = partBox(o.shipped().result, h.part);
                if (box != null) {
                    List<ContactSheet.Focus> f = List.of(new ContactSheet.Focus("helper d" + h.id + ": " + h.part, box));
                    images.add(ContactSheet.closeups(o.shipped().result.model(), info, f, s.imageSize));
                }
            }
        }
    }

    private void accept(int id, StringBuilder reply) {
        Helper h = helper(id);
        if (h == null || !h.future.isDone() || h.closed) {
            reply.append("ACCEPT d").append(id).append(": that helper has no finished result.\n");
            return;
        }
        try {
            Outcome o = h.future.get();
            String part = ScriptPatch.partSource(o.shipped().source, h.part);
            if (part == null || current == null) throw new IllegalStateException("its part is missing");
            ScriptPatch.Result p = ScriptPatch.replacePart(current.source, h.part, part);
            if (!p.ok()) throw new IllegalStateException(p.error());
            Attempt a = execute(p.source(), "helper d" + id + " (" + h.part + ")");
            h.closed = true;
            reply.append(a.version == null ? "ACCEPT d" + id + ": your script with its part fails:\n" + a.error + "\n"
                    : "Accepted helper d" + id + ": part('" + h.part + "') replaced.\n");
        } catch (Exception e) {
            reply.append("ACCEPT d").append(id).append(" failed: ").append(e.getMessage()).append('\n');
        }
    }

    private void stopHelpers() {
        for (Helper h : helpers) if (!h.future.isDone()) h.future.cancel(true);
    }

    // ------------------------------------------------------------------ seeing

    private void look(List<JsonObject> views, StringBuilder reply, List<byte[]> images) {
        if (current == null) {
            reply.append("look: there is no build yet.\n");
            return;
        }
        if (!s.eyes) {
            reply.append("look: images are not available with this model; use inspect for exact layers.\n");
            return;
        }
        VoxelModel model = current.result.model();
        int[] b = model.solidBounds();
        if (b == null) {
            reply.append("look: the build is empty.\n");
            return;
        }
        // four views to a sheet, up to three sheets a turn
        List<JsonObject> all = views.subList(0, Math.min(12, views.size()));
        if (views.size() > all.size()) reply.append("look: only the first 12 views were rendered.\n");
        for (int from = 0; from < all.size(); from += 4) lookSheet(all.subList(from, Math.min(all.size(), from + 4)), model, b, reply, images);
    }

    private void lookSheet(List<JsonObject> views, VoxelModel model, int[] b, StringBuilder reply, List<byte[]> images) {
        List<ContactSheet.Shot> shots = new ArrayList<>();
        int n = (int) views.stream().filter(v -> !v.has("rooms")).count();
        int[] t = ContactSheet.tileSize(Math.max(1, n), s.imageSize);
        for (JsonObject v : views) {
            try {
                if (v.has("rooms")) {
                    List<int[]> boxes = new ArrayList<>();
                    List<String> labels = new ArrayList<>();
                    for (AnalysisReport.Room r : current.report.roomList.stream().limit(4).toList()) {
                        boxes.add(new int[]{r.x1(), r.z1(), r.x2(), r.z2(), r.y()});
                        labels.add("room y=" + r.y() + " x " + r.x1() + ".." + r.x2() + " z " + r.z1() + ".." + r.z2());
                    }
                    if (boxes.isEmpty()) reply.append("look rooms: the analyzer finds no enclosed rooms.\n");
                    else images.add(ContactSheet.interiors(model, info, boxes, labels, s.imageSize));
                    continue;
                }
                shots.add(shot(v, model, b, t[0], t[1]));
            } catch (RuntimeException e) {
                reply.append("look ").append(v).append(": ").append(e.getMessage()).append('\n');
            }
        }
        if (!shots.isEmpty()) {
            images.add(ContactSheet.shots(model, info, shots, s.imageSize));
            reply.append("Image: ").append(String.join(" | ", shots.stream().map(ContactSheet.Shot::label).toList())).append('\n');
        }
    }

    private static final String[] SIDES = {"se", "sw", "nw", "ne"};

    private ContactSheet.Shot shot(JsonObject v, VoxelModel model, int[] b, int w, int h) {
        int side = 0;
        if (v.has("side")) {
            String sd = v.get("side").getAsString().toLowerCase(Locale.ROOT).replace("south", "s").replace("north", "n")
                    .replace("east", "e").replace("west", "w").replace("-", "");
            for (int i = 0; i < 4; i++) if (SIDES[i].equals(sd)) side = i;
        }
        if (v.has("from") && v.has("at")) {
            double[] from = vec(v.getAsJsonArray("from")), at = vec(v.getAsJsonArray("at"));
            double fov = v.has("fov") ? v.get("fov").getAsDouble() : 70;
            return new ContactSheet.Shot("from " + fmt(from) + " at " + fmt(at), VoxelRaster.look(from, at, fov, w, h), null);
        }
        if (v.has("eye")) {
            // eye level from where a player arrives on that side
            String e = v.get("eye").getAsString().toLowerCase(Locale.ROOT);
            double cx = (b[0] + b[3] + 1) / 2.0, cz = (b[2] + b[5] + 1) / 2.0;
            double span = Math.max(b[3] - b[0], b[5] - b[2]) + 1;
            double dist = v.has("distance") ? v.get("distance").getAsDouble() : span * 0.9 + 12;
            double[] d = switch (e) {
                case "north" -> new double[]{0, -1};
                case "east" -> new double[]{1, 0};
                case "west" -> new double[]{-1, 0};
                default -> new double[]{0, 1};
            };
            double ground = Math.max(b[1], 0) + 1;
            double[] from = {cx + d[0] * dist, ground + 1.6, cz + d[1] * dist};
            double[] at = {cx, ground + Math.min(10, (b[4] - ground) / 2.0), cz};
            return new ContactSheet.Shot("eye level from the " + e, VoxelRaster.look(from, at, 70, w, h), null);
        }
        if (v.has("top")) return new ContactSheet.Shot("top (north up)", VoxelRaster.top(b, w, h), null);
        if (v.has("cut")) {
            int y = v.get("cut").getAsInt();
            int[] cut = b.clone();
            cut[4] = Math.min(b[4], y);
            return new ContactSheet.Shot("cut at y=" + y + " from " + SIDES[side], VoxelRaster.iso(cut, side, w, h), y);
        }
        if (v.has("elevation")) {
            String e = v.get("elevation").getAsString().toLowerCase(Locale.ROOT);
            int idx = switch (e) {
                case "west" -> 1;
                case "north" -> 2;
                case "east" -> 3;
                default -> 0;
            };
            return new ContactSheet.Shot("elevation from the " + e, VoxelRaster.elevation(b, idx, w, h), null);
        }
        int[] box;
        String label;
        if (v.has("part")) {
            String part = v.get("part").getAsString();
            box = partBox(current.result, part);
            if (box == null) throw new IllegalArgumentException("no part named '" + part + "'");
            label = part + " from " + SIDES[side];
        } else if (v.has("box")) {
            double[] c = vec(v.getAsJsonArray("box"));
            if (c.length != 6) throw new IllegalArgumentException("box needs six numbers");
            box = new int[6];
            for (int i = 0; i < 6; i++) box[i] = (int) Math.round(c[i]);
            label = "box " + fmt(c) + " from " + SIDES[side];
        } else {
            return new ContactSheet.Shot("whole build from " + SIDES[side], VoxelRaster.iso(b, side, w, h), null);
        }
        return new ContactSheet.Shot(label, VoxelRaster.closeup(box, side, w, h), null);
    }

    /** Layers of a box as text (exact blocks), at most 32768 cells so the answer stays readable. */
    private String inspect(int[] box) {
        if (current == null) return "inspect: there is no build yet.";
        int x1 = Math.min(box[0], box[3]), y1 = Math.min(box[1], box[4]), z1 = Math.min(box[2], box[5]);
        int x2 = Math.max(box[0], box[3]), y2 = Math.max(box[1], box[4]), z2 = Math.max(box[2], box[5]);
        long cells = (long) (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1);
        if (cells > 32_768) return "inspect: " + cells + " cells is too many to read as text; ask for a box of at most 32768 cells"
                + " (e.g. 32 x 32 x 32), or look at it.";
        VoxelModel sub = new VoxelModel();
        VoxelModel m = current.result.model();
        for (int x = x1; x <= x2; x++) {
            for (int y = y1; y <= y2; y++) {
                for (int z = z1; z <= z2; z++) {
                    BlockSpec b = m.get(x, y, z);
                    if (b != null && !b.isAir()) sub.set(x, y, z, b);
                }
            }
        }
        if (sub.nonAirCount() == 0) return "inspect x " + x1 + ".." + x2 + " y " + y1 + ".." + y2 + " z " + z1 + ".." + z2 + ": all air.";
        return "inspect x " + x1 + ".." + x2 + " y " + y1 + ".." + y2 + " z " + z1 + ".." + z2 + ":\n"
                + TextRenderer.render(sub, info, TextRenderer.Options.layersOnly());
    }

    // ------------------------------------------------------------------ the reply

    /** What a version is: its run, its analysis and, with eyes, its renders (appended to {@code images}). */
    private String report(Version v, Version before, List<byte[]> images) {
        ScriptResult r = v.result;
        StringBuilder sb = new StringBuilder();
        sb.append("VERSION ").append(v.n).append(" (").append(v.origin).append("): ").append(r.model().nonAirCount())
                .append(" blocks, ran in ").append(r.elapsedMs()).append(" ms.\n");
        if (!r.warnings().isEmpty()) {
            sb.append("Warnings and auto-corrections:\n");
            for (String w : r.warnings().subList(0, Math.min(20, r.warnings().size()))) sb.append("- ").append(w).append('\n');
        }
        if (!r.logs().isEmpty()) sb.append("log(): ").append(clip(String.join(" | ", r.logs()), 1500)).append('\n');
        sb.append("PARTS:\n").append(r.partTable(40));
        String sheet = r.sheetSection();
        if (!sheet.isEmpty()) sb.append(sheet).append('\n');
        List<int[]> regions = List.of();
        if (before != null) {
            regions = BuildPipeline.changedRegions(before.result.model(), r.model(), 3);
            sb.append("Changed from version ").append(before.n).append(": ").append(regions.isEmpty() ? "no visible change"
                    : regions.size() + " region(s) " + String.join("; ", regions.stream().map(StudioSession::box).toList())).append('\n');
        }
        sb.append("\nANALYSIS (information for you; nothing here is enforced):\n").append(v.report.toText()).append('\n');
        String spatial = SpatialReport.render(r.model(), info);
        if (spatial != null && !spatial.isBlank()) sb.append(spatial).append('\n');
        sb.append("Quality: ").append(v.quality.describe()).append('\n');
        List<CraftReview.Mark> marks = visibleMarks(v.craft);
        shownMarks = marks;
        String pen = new CraftReview.Result(marks, v.craft.metrics()).toText();
        if (!pen.isEmpty()) {
            sb.append('\n').append(pen);
            if (!marks.isEmpty()) sb.append("Clear a mark only where that makes the build better, never by sticking on clutter; KEEP n when a"
                    + " marked thing is what you intend.\n");
        }
        byte[] penImage = s.eyes && !marks.isEmpty() ? RedPen.sheet(r.model(), info, marks, s.imageSize) : null;
        List<byte[]> optionImages = new ArrayList<>();
        options(v, sb, optionImages);
        if (!r.collisionText().isEmpty()) sb.append("Collisions between parts:\n").append(r.collisionText()).append('\n');
        Finisher.Result f = Finisher.plan(r.model(), info, r.collisions(), host.options(r).expectInterior);
        if (f.statement() != null && f.edits() > 0) {
            sb.append("The finisher could repair ").append(f.edits()).append(" cells: ").append(String.join("; ", f.notes()))
                    .append(". Say FINISH to apply that as part('finish'), or fix them your way.\n");
        }
        if (s.eyes) {
            List<String> names = new ArrayList<>();
            images.add(ContactSheet.of(r.model(), info, s.imageSize));
            names.add("overview (4 corners, eye level from the south, top)");
            if (penImage != null) {
                images.add(penImage);
                names.add("red pen (the numbered marks)");
            }
            images.addAll(optionImages);
            for (int i = 0; i < optionImages.size(); i++) names.add("option choices side by side");
            if (!v.report.roomList.isEmpty()) {
                List<int[]> boxes = new ArrayList<>();
                List<String> labels = new ArrayList<>();
                for (AnalysisReport.Room room : v.report.roomList.stream().limit(4).toList()) {
                    boxes.add(new int[]{room.x1(), room.z1(), room.x2(), room.z2(), room.y()});
                    labels.add("room y=" + room.y() + " x " + room.x1() + ".." + room.x2() + " z " + room.z1() + ".." + room.z2());
                }
                images.add(ContactSheet.interiors(r.model(), info, boxes, labels, s.imageSize));
                names.add("rooms from above");
            }
            if (focus != null) {
                int[] pb = partBox(r, focus);
                if (pb != null) {
                    images.add(ContactSheet.closeups(r.model(), info, List.of(new ContactSheet.Focus(focus, pb)), s.imageSize));
                    names.add("close-up of your part");
                }
            } else if (!regions.isEmpty()) {
                List<ContactSheet.Focus> fs = new ArrayList<>();
                for (int i = 0; i < regions.size(); i++) fs.add(new ContactSheet.Focus("changed " + (i + 1), regions.get(i)));
                images.add(ContactSheet.closeups(r.model(), info, fs, s.imageSize));
                names.add("close-ups of what changed");
            }
            sb.append("Images: ").append(String.join("; ", names)).append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ red pen, options, blind check

    private CraftReview.Result review(ScriptResult r) {
        try {
            Map<Long, String> owners = r.owners();
            return CraftReview.review(r.model(), info, (x, y, z) -> owners.get(VoxelModel.key(x, y, z)));
        } catch (RuntimeException e) {
            return CraftReview.Result.EMPTY;     // advice only: a failure here never costs the model its version
        }
    }

    /** The marks minus those the model kept as intended, renumbered so the text and the image agree. */
    private List<CraftReview.Mark> visibleMarks(CraftReview.Result craft) {
        List<CraftReview.Mark> out = new ArrayList<>();
        for (CraftReview.Mark m : craft.marks()) {
            if (kept.stream().anyMatch(k -> k.kind().equals(m.kind()) && overlaps(k.box(), m.box()))) continue;
            String note = m.note();
            out.add(new CraftReview.Mark(out.size() + 1, m.kind(), note, m.box(), m.normal()));
        }
        return out;
    }

    private static boolean overlaps(int[] a, int[] b) {
        return a[0] <= b[3] && b[0] <= a[3] && a[1] <= b[4] && b[1] <= a[4] && a[2] <= b[5] && b[2] <= a[5];
    }

    static final int MAX_OPTIONS = 3, MAX_CHOICES = 6;

    /** Lists the script's options; builds every choice of an open option the model has not seen yet, side by side. */
    private void options(Version v, StringBuilder sb, List<byte[]> images) {
        List<ScriptResult.Option> opts = v.result.options();
        if (opts.isEmpty()) return;
        sb.append("OPTIONS:\n");
        for (ScriptResult.Option o : opts) {
            sb.append("- ").append(o.name()).append(" = ").append(o.choices().get(o.picked())).append(o.fixed() ? " (decided)" : " (open: the first choice)")
                    .append("; choices ").append(String.join(", ", o.choices())).append('\n');
        }
        int built = 0;
        for (ScriptResult.Option o : opts) {
            if (o.fixed() || o.choices().size() < 2 || built >= MAX_OPTIONS) continue;
            String key = o.name() + "|" + String.join("|", o.choices());
            if (!explored.add(key)) continue;
            built++;
            List<VoxelModel> models = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            List<int[]> changed = new ArrayList<>();
            int n = Math.min(MAX_CHOICES, o.choices().size());
            for (int i = 0; i < n; i++) {
                String c = o.choices().get(i);
                ScriptResult alt;
                if (i == o.picked()) alt = v.result;
                else {
                    alt = runtime.run(withDecision(v.source, o.name(), c), seed, null, limits, budget, null);
                    if (!alt.ok()) {
                        sb.append("  ").append(o.name()).append(" = ").append(c).append(" does not run: ").append(clip(alt.error().message(), 200)).append('\n');
                        continue;
                    }
                    host.prepare(alt);
                    changed.addAll(BuildPipeline.changedRegions(v.result.model(), alt.model(), 4));
                }
                CraftReview.Result pen = i == o.picked() ? v.craft : review(alt);
                models.add(alt.model());
                labels.add(o.name() + " = " + c + (i == o.picked() ? " (now)" : "") + ", " + alt.model().nonAirCount() + " blocks, "
                        + pen.marks().size() + " red-pen mark" + (pen.marks().size() == 1 ? "" : "s"));
            }
            if (changed.isEmpty()) {
                sb.append("  option ").append(o.name()).append(": the choices build the same blocks.\n");
                continue;
            }
            int[] box = changed.getFirst().clone();
            for (int[] c : changed) {
                for (int i = 0; i < 3; i++) {
                    box[i] = Math.min(box[i], c[i]);
                    box[i + 3] = Math.max(box[i + 3], c[i + 3]);
                }
            }
            for (int i = 0; i < 3; i++) {
                box[i] -= 2;
                box[i + 3] += 2;
            }
            sb.append("  option ").append(o.name()).append(": every choice built; the image shows them from the same camera on ").append(box(box))
                    .append(". Compare, then PICK ").append(o.name()).append("=<choice> to keep one.\n");
            if (s.eyes) images.add(ContactSheet.compare(models, labels, info, box, s.imageSize));
        }
    }

    /** PICK: writes the choices into the decide({...}) line at the top of the script and runs it. */
    private boolean decide(List<String[]> picks, StringBuilder reply) {
        if (current == null) {
            reply.append("PICK: there is no build yet.\n");
            return false;
        }
        String src = current.source;
        List<String> done = new ArrayList<>();
        for (String[] p : picks) {
            ScriptResult.Option o = current.result.options().stream().filter(x -> x.name().equals(p[0])).findFirst().orElse(null);
            if (o == null) {
                reply.append("PICK ").append(p[0]).append(": the script has no option('").append(p[0]).append("', [...]).\n");
                continue;
            }
            String c = choice(o, p[1]);
            if (c == null) {
                reply.append("PICK ").append(p[0]).append('=').append(p[1]).append(": not one of ").append(String.join(", ", o.choices())).append(".\n");
                continue;
            }
            src = withDecision(src, o.name(), c);
            done.add(o.name() + "=" + c);
        }
        if (done.isEmpty()) return false;
        Attempt a = execute(src, "PICK " + String.join(", ", done));
        if (a.version == null) {
            reply.append("PICK: the script with that choice fails:\n").append(a.error).append('\n');
            return false;
        }
        reply.append("Picked ").append(String.join(", ", done)).append(" (written as decide({...}) on the first line of the script).\n");
        return true;
    }

    /** The choice (as JSON text) a PICK value means: the JSON itself, the bare string, or a 1-based number. */
    static String choice(ScriptResult.Option o, String value) {
        for (String c : o.choices()) {
            if (c.equals(value)) return c;
            if (c.startsWith("\"") && c.length() >= 2 && c.substring(1, c.length() - 1).equals(value)) return c;
        }
        for (String c : o.choices()) if (c.equalsIgnoreCase(value) || c.equalsIgnoreCase("\"" + value + "\"")) return c;
        return null;
    }

    private static final java.util.regex.Pattern DECIDE = java.util.regex.Pattern.compile("(?m)^decide\\((\\{.*\\})\\);[ \\t]*// decided in the studio[ \\t]*\\n?");

    /** The script with {@code name} decided as {@code json} on its first line (merged into an existing decide line). */
    static String withDecision(String source, String name, String json) {
        java.util.regex.Matcher m = DECIDE.matcher(source);
        JsonObject o = new JsonObject();
        String rest = source;
        if (m.find()) {
            try {
                o = com.google.gson.JsonParser.parseString(m.group(1)).getAsJsonObject();
            } catch (RuntimeException e) {
                o = new JsonObject();
            }
            rest = source.substring(0, m.start()) + source.substring(m.end());
        }
        o.add(name, com.google.gson.JsonParser.parseString(json));
        return "decide(" + o + "); // decided in the studio\n" + rest;
    }

    /**
     * Before a version ships, once: it and the version from a few changes earlier side by side, unlabelled, and the
     * model says which is the better build. Models judge a pair far better than a single image, so this catches late
     * changes that made the build worse in the model's own eyes. True when the comparison was asked for.
     */
    private boolean blindCheck(Version ship, StringBuilder reply, List<byte[]> images) {
        if (focus != null || !s.eyes || versions.size() < 3 || !blindChecked.add(ship.n)) return false;
        if (s.deadlineNanos != Long.MAX_VALUE && left() < TimeUnit.MINUTES.toNanos(4)) return false;
        Version earlier = versions.get(Math.max(0, ship.n - 4));
        if (earlier == ship || BuildPipeline.changedRegions(earlier.result.model(), ship.result.model(), 1).isEmpty()) return false;
        boolean flip = ((seed ^ ship.n) & 1) == 1;
        Version a = flip ? earlier : ship, b = flip ? ship : earlier;
        blind = new Blind(a, b, ship, earlier);
        images.add(ContactSheet.pair(a.result.model(), b.result.model(), info, s.imageSize));
        reply.append("Before this ships: a blind comparison. The image shows two versions of this build, A (top row) and B (bottom"
                + " row), from the same cameras. Judge them as a player would: which is the better build? Answer PREFER A or PREFER B"
                + " on a line of its own.\n");
        return true;
    }

    private void prefer(char which, StringBuilder reply) {
        if (blind == null) {
            reply.append("PREFER: there is no comparison open.\n");
            return;
        }
        Version chosen = which == 'A' ? blind.a : blind.b;
        Blind b = blind;
        blind = null;
        if (chosen == b.ship) {
            reply.append("You preferred the version you chose to ship (version ").append(b.ship.n).append(").\n");
            preferShip = b.ship;
        } else {
            reply.append("You preferred version ").append(b.earlier.n).append(", from before your last changes, over version ").append(b.ship.n)
                    .append(" that you were about to ship: in your own eyes those changes made the build worse. REVERT ").append(b.earlier.n)
                    .append(" and build on it, DONE ").append(b.earlier.n).append(" to ship it, or improve version ").append(b.ship.n).append(".\n");
        }
    }

    private String footer(int turn) {
        StringBuilder sb = new StringBuilder();
        if (s.deadlineNanos == Long.MAX_VALUE) sb.append("No time limit; turn ");
        else {
            long sec = TimeUnit.NANOSECONDS.toSeconds(Math.max(0, left()));
            sb.append("Time left: ").append(sec / 60).append(" min ").append(sec % 60).append(" s; turn ");
        }
        sb.append(turn).append(" of at most ").append(s.maxTurns).append(". ");
        sb.append(current == null ? "No version yet." : "Versions 1.." + versions.size() + ", current " + current.n + ".");
        List<Helper> open = helpers.stream().filter(h -> !h.closed && !h.reported).toList();
        if (!open.isEmpty()) {
            sb.append(" Helpers working: ").append(String.join(", ", open.stream().map(h -> "d" + h.id + " " + h.part).toList())).append('.');
        }
        long total = s.deadlineNanos == Long.MAX_VALUE ? -1 : s.deadlineNanos;
        if (total != -1 && left() < TimeUnit.MINUTES.toNanos(3)) {
            sb.append("\nAlmost out of time: make this your last change, then DONE (the current version ships when time runs out).");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ the conversation

    /**
     * The conversation for the next call: verbatim while it fits, so the provider's prompt cache keeps working; past
     * the budget, older turns keep the model's own words with code blocks elided, the plugin's replies shrink to
     * their headlines, only the newest images stay, and the current script is restated in full.
     */
    /** One model call; a request too large for the provider is retried with half the image budget, then a quarter. */
    private String callWithin(String opening, int turn) throws Exception {
        if (imageBudget < 0) imageBudget = s.imageBytes;
        for (int attempt = 0; ; attempt++) {
            try {
                return host.call(messages(opening), s.tag + "-t" + turn);
            } catch (Exception e) {
                if (!tooLarge(e) || attempt >= 3) throw e;
                imageBudget /= 2;
            }
        }
    }

    private static boolean tooLarge(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) if (t instanceof LlmException l && l.status() == 413) return true;
        return false;
    }

    private static boolean cancellation(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof InterruptedException || t instanceof BuildPipeline.CancelledException
                    || t instanceof LlmException l && l.isCancellation()) return true;
        }
        return false;
    }

    /**
     * Moves {@link #imageCutoff} so the images that stay fit {@link #imageBudget}, keeping the newest turns' images
     * whole. Returns whether the opening's images still fit.
     */
    private boolean fitImages() {
        long budget = imageBudget < 0 ? s.imageBytes : imageBudget;
        long used = 0;
        int cut = history.size();
        for (int i = history.size() - 1; i >= imageCutoff; i--) {
            long b = bytes(history.get(i).images);
            // the newest turn's images always go: they are what the model is answering
            if (used + b > budget && i < history.size() - 1) break;
            used += b;
            cut = i;
        }
        imageCutoff = Math.max(imageCutoff, cut);
        return imageCutoff == 0 && used + bytes(startImages) <= budget;
    }

    private static long bytes(List<byte[]> images) {
        long n = 0;
        for (byte[] b : images) n += b.length;
        return n;
    }

    private List<ChatMessage> messages(String opening) {
        List<ChatMessage> out = new ArrayList<>();
        out.add(ChatMessage.system(system));
        boolean startFits = fitImages();
        out.add(startImages.isEmpty() || !startFits ? ChatMessage.user(opening) : ChatMessage.user(opening, startImages));
        long tokens = (system.length() + opening.length()) / 3 + startImages.size() * 1000L;
        for (Exchange e : history) tokens += (e.answer.length() + e.reply.length()) / 3 + e.images.size() * 1000L;
        int keep = history.size();
        if (tokens > s.contextTokens && history.size() > 2) {
            keep = 2;
            if (!startImages.isEmpty()) out.set(1, ChatMessage.user(opening));
        }
        int firstFull = history.size() - keep;
        for (int i = 0; i < history.size(); i++) {
            Exchange e = history.get(i);
            if (i < firstFull) {
                out.add(ChatMessage.assistant(elideCode(e.answer)));
                String headline = e.reply.lines().limit(6).reduce("", (a, b) -> a + b + "\n");
                String note = i == firstFull - 1 && current != null
                        ? headline + "\n[earlier turns were shortened to fit the context; the CURRENT SCRIPT, version " + current.n
                        + ", in full:]\n```js\n" + current.source + "```\n"
                        : headline;
                out.add(ChatMessage.user(note));
            } else {
                out.add(ChatMessage.assistant(e.answer));
                if (e.images.isEmpty()) out.add(ChatMessage.user(e.reply));
                else if (i < imageCutoff) out.add(ChatMessage.user(e.reply + "\n[the images of this turn were left out to keep the request small;"
                        + " a look shows any view again]"));
                else out.add(ChatMessage.user(e.reply, e.images));
            }
        }
        return out;
    }

    private static String elideCode(String answer) {
        return answer.replaceAll("```(?:js|javascript)?\\s*\\n[\\s\\S]{400,}?```", "```js\n[code omitted]\n```")
                .replaceAll("<{5,9} ?SEARCH[\\s\\S]*?>{5,9} ?REPLACE", "[hunk omitted]");
    }

    // ------------------------------------------------------------------ small things

    private long left() {
        return s.deadlineNanos == Long.MAX_VALUE ? Long.MAX_VALUE : s.deadlineNanos - s.clock.getAsLong();
    }

    private Version pick(Integer n) {
        if (n == null) return current;
        return n >= 1 && n <= versions.size() ? versions.get(n - 1) : null;
    }

    static int[] partBox(ScriptResult r, String name) {
        for (ScriptResult.Part p : r.parts()) {
            if (p.name().equals(name) || p.name().endsWith("/" + name)) return p.bounds();
        }
        return null;
    }

    /** The model's own summary of a turn: its prose outside code, first lines. */
    static String diagnosis(String answer) {
        if (answer == null) return "";
        String prose = answer.replaceAll("```[\\s\\S]*?```", " ").replaceAll("<{5,9} ?SEARCH[\\s\\S]*?>{5,9} ?REPLACE", " ").strip();
        return clip(prose.replaceAll("\\s+", " "), 600);
    }

    private static String box(int[] b) {
        return "x " + b[0] + ".." + b[3] + " y " + b[1] + ".." + b[4] + " z " + b[2] + ".." + b[5];
    }

    private static double[] vec(JsonArray a) {
        double[] out = new double[a.size()];
        for (int i = 0; i < out.length; i++) out[i] = a.get(i).getAsDouble();
        return out;
    }

    private static String fmt(double[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) sb.append(i == 0 ? "" : ",").append(Math.round(v[i]));
        return sb.append(']').toString();
    }

    private static String clip(String s, int n) {
        return s == null ? "" : s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }
}
