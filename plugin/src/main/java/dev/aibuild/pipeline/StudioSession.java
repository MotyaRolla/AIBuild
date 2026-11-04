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

        /** A short judging call (the blind check of a big change); by default the same call as the session's. */
        default String judge(List<ChatMessage> msgs, String tag) throws Exception {
            return call(msgs, tag);
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
        /** Unused since the session sends its state instead of the whole history; kept for configs that set it. */
        public int contextTokens = 150_000;
        /**
         * A blind comparison by an independent judge call for big changes (a rewrite, a large region, or a measured
         * regression): when the judge prefers the version before, that one stays current.
         */
        public boolean verify = true;
        /**
         * First drafts written in parallel for a new build; a blind tournament picks the one the session builds on.
         * The first design sets the ceiling of everything after it, and parallel drafts cost tokens, not wall time.
         */
        public int firstDrafts = 1;
        /**
         * Raw PNG bytes of all images in one request. Base64 makes them a third bigger and providers cap a request
         * body (32 MB at the gateway), so older turns lose their images first.
         */
        public long imageBytes = 14_000_000;
        public LongSupplier clock = System::nanoTime;
        public String tag = "studio";
    }

    /** One run of the script that succeeded. */
    /**
     * One run of the script that succeeded. {@code parent} is the version it was made from (0 for the first);
     * {@code why} is the model's own words for the change.
     */
    public record Version(int n, String source, ScriptResult result, AnalysisReport report, QualityVector quality, String origin,
                          CraftReview.Result craft, int parent, String why) {
    }

    /** How the session ended. {@code shipped} is null only when no version ever ran. */
    public record Outcome(Version shipped, List<Version> versions, int turns, boolean done, String reason, String diagnosis,
                          int checks, int rejected) {
        public Outcome(Version shipped, List<Version> versions, int turns, boolean done, String reason, String diagnosis) {
            this(shipped, versions, turns, done, reason, diagnosis, 0, 0);
        }
    }

    /** One model answer and the plugin's reply to it (without the state and the clock, which are added when sent). */
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
    private record Blind(Version a, Version b, Version ship, Version earlier) {
    }
    private Blind blind;
    /** Set when the model's PREFER confirms the version it was about to ship. */
    private Version preferShip;
    /** The model's latest edit when it did not run: further hunks apply to it, so a fix of the fix lands. */
    private String draft;
    private String draftError;
    /** The model's own working notes (its latest ```notes block): its memory across turns. */
    private String notes;
    /** One line per version, failure, revert and blind check: what the model sees of its past turns. */
    private final List<String> journal = new ArrayList<>();
    /** The model's words for the answer being carried out; stored with the versions it makes. */
    private String why = "";
    /** Overview sheets by version number (rendering one takes a while; a version is shown more than once). */
    private final Map<Integer, List<byte[]>> overviews = new java.util.HashMap<>();
    /** The red-pen mark kinds and counts of the version last reported, to show the red-pen image only when they change. */
    private String lastPen = "";
    /** What the tournament of first drafts found, for the first reply. */
    private String draftNote;
    /** Blind checks made, and how many of them kept the version before the change. */
    private int checks, rejected;

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
                opening += "\n\n" + report(a.version, null, null, imgs) + openFaults(a.version);
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
                answer = turn == 1 && initial == null && focus == null && s.firstDrafts > 1 && host.pool() != null
                        ? firstDraft(opening) : callWithin(opening, turn);
            } catch (Exception e) {
                if (current == null || cancellation(e)) throw e;
                // a failed call must not throw away the versions already built: ship what there is
                reason = "the model call failed: " + clip(String.valueOf(e.getMessage()), 200);
                break;
            }
            StudioProtocol.Turn t = StudioProtocol.parse(answer);
            String diag = diagnosis(answer);
            if (!diag.isEmpty()) lastDiagnosis = diag;
            why = diag;
            if (t.notes() != null && !t.notes().isBlank()) notes = clip(t.notes(), 6000);
            List<byte[]> images = new ArrayList<>();
            StringBuilder reply = new StringBuilder();
            if (draftNote != null) {
                reply.append(draftNote).append('\n');
                draftNote = null;
            }
            Version before = current;
            int made = versions.size();
            preferShip = null;
            boolean failedChange = act(t, reply, images);
            if (preferShip != null && !t.done() && !t.changes()) {
                chosen = preferShip;
                done = true;
                reason = "the model finished";
                break;
            }
            Version newest = versions.size() > made ? versions.getLast() : null;
            if (newest != null) {
                Version parent = pick(newest.parent);
                Delta d = parent == null ? null : delta(parent, newest);
                reply.append('\n').append(report(newest, parent, d, images));
                if (d != null && current == newest) verify(parent, newest, d, reply);
            }
            if (t.done() && !failedChange) {
                chosen = pick(t.doneVersion());
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
            if (images.isEmpty() && current != null && s.eyes) {
                // every turn shows the build as it stands: the model never works blind
                images.addAll(overview(current));
                reply.append("Images: ").append(overviewNames(current)).append('\n');
            }
            history.add(new Exchange(answer, reply.toString(), images));
        }
        stopHelpers();
        Version shipped = chosen != null ? chosen : current;
        return new Outcome(shipped, List.copyOf(versions), turn, done, reason, lastDiagnosis, checks, rejected);
    }

    // ------------------------------------------------------------------ one turn

    /** Carries out a turn; true when it asked for a change that did not produce a version. */
    private boolean act(StudioProtocol.Turn t, StringBuilder reply, List<byte[]> images) throws Exception {
        for (String p : t.problems()) reply.append("Could not read: ").append(p).append('\n');
        boolean failed = false;
        Integer back = t.revert();
        if (back == null && t.undo()) {
            if (current == null || current.parent == 0) reply.append("UNDO: there is no earlier version to go back to.\n");
            else back = current.parent;
        }
        if (back != null) {
            Version v = pick(back);
            if (v == null) reply.append("REVERT ").append(back).append(": there is no such version.\n");
            else {
                current = v;
                draft = null;
                draftError = null;
                if (focus == null) host.shown(v);
                journal.add("   -> back to v" + v.n + (why.isEmpty() ? "" : ": " + clip(why, 140)));
                reply.append("Reverted: version ").append(v.n).append(" is current again.\n");
            }
        }
        if (t.report() && current != null) reply.append('\n').append(fullReport(current)).append('\n');
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
        reply.append(lookups(t));
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
        String base = draft != null ? draft : current == null ? "" : current.source;
        String next = base;
        List<String> done = new ArrayList<>();
        List<String> missed = new ArrayList<>();
        // a whole script replaces everything: say so for what else the answer held, instead of mixing the two
        String whole = null;
        List<String> loose = new ArrayList<>();
        if (focus == null) {
            for (String blk : t.blocks()) {
                if (!base.isEmpty() && partsOnly(blk) != null) continue;
                // a whole script: the first one, one with meta(), or one at least half the size of the current script;
                // a smaller fragment (a helper function, a constant) would wipe the build, so it is named instead
                boolean wholeLike = base.isEmpty() || blk.contains("meta(") || blk.length() * 2 >= base.length();
                if (!wholeLike) loose.add(blk);
                else if (whole == null || blk.length() > whole.length()) whole = blk;
            }
        }
        for (String l : loose) {
            missed.add("a code block that is neither part() statements nor a whole script was NOT applied (it starts '"
                    + clip(firstLine(l.strip()), 80) + "'): put helper code inside a part, or change the script with hunks");
        }
        if (whole != null) {
            next = whole;
            done.add("whole script");
            int others = t.blocks().size() - 1 - loose.size() + t.takes().size() + (t.hunks().isEmpty() ? 0 : 1);
            if (others > 0) {
                missed.add("a whole script replaces the build, so the " + others + " other edit(s) in the same answer"
                        + " (hunks, part blocks or TAKE) were NOT applied; put those changes into the script itself");
            }
        } else {
            for (String[] tk : t.takes()) {
                Version from = pick(Integer.parseInt(tk[1]));
                String part = tk[0];
                String code = from == null ? null : ScriptPatch.partSource(from.source, part);
                if (from == null) missed.add("TAKE " + part + " FROM " + tk[1] + ": there is no version " + tk[1]);
                else if (code == null) missed.add("TAKE " + part + " FROM " + from.n + ": version " + from.n + " has no part('" + part + "')");
                else {
                    ScriptPatch.Result p = ScriptPatch.putPart(next, part, code);
                    if (!p.ok()) missed.add("TAKE " + part + " FROM " + from.n + ": " + p.error());
                    else {
                        next = p.source();
                        done.add("part('" + part + "') from v" + from.n);
                    }
                }
            }
            if (!t.hunks().isEmpty()) {
                if (next.isEmpty()) {
                    missed.add("there is no script yet to apply SEARCH/REPLACE hunks to: write the whole script in a ```js block");
                } else {
                    StringBuilder text = new StringBuilder();
                    for (String[] h : t.hunks()) text.append("<<<<<<< SEARCH\n").append(h[0]).append("\n=======\n").append(h[1]).append("\n>>>>>>> REPLACE\n");
                    ScriptPatch.Result p = ScriptPatch.apply(next, text.toString());
                    if (!p.ok() && draft != null && current != null && t.takes().isEmpty()) {
                        // hunks written against the version rather than the failed draft
                        ScriptPatch.Result q = ScriptPatch.apply(current.source, text.toString());
                        if (q.ok()) p = q;
                    }
                    if (!p.ok()) {
                        missed.add("the " + t.hunks().size() + " hunk(s) did not apply: " + p.error() + "\n" + nearest(next, p.error(), t.hunks()).strip());
                        journal.add("   x hunks did not apply: " + clip(firstLine(p.error()), 120));
                    } else {
                        next = p.source();
                        done.add(t.hunks().size() + " hunk" + (t.hunks().size() == 1 ? "" : "s"));
                    }
                }
            }
            if (focus != null && current != null && !t.blocks().isEmpty()) {
                String mine = t.script();
                ScriptPatch.Result p = ScriptPatch.replacePart(next, focus, mine);
                if (!p.ok()) missed.add("your part was not replaced: " + p.error());
                else {
                    next = p.source();
                    done.add("new part('" + focus + "')");
                }
                if (t.blocks().size() > 1) missed.add((t.blocks().size() - 1) + " smaller code block(s) were NOT applied: a helper writes one block, its part");
            } else {
                for (String blk : t.blocks()) {
                    List<ScriptLint.PartStatement> parts = partsOnly(blk);
                    if (parts == null) continue;
                    for (ScriptLint.PartStatement ps : parts) {
                        ScriptPatch.Result p = ScriptPatch.putPart(next, ps.name(), blk.substring(ps.start(), ps.end()));
                        if (!p.ok()) missed.add("part('" + ps.name() + "') was not replaced: " + p.error());
                        else {
                            next = p.source();
                            done.add("rewrote part('" + ps.name() + "')");
                        }
                    }
                }
            }
        }
        if (!missed.isEmpty()) {
            reply.append("NOT APPLIED from your answer:\n");
            for (String m : missed) reply.append("- ").append(m).append('\n');
        }
        if (done.isEmpty() || next.equals(base)) {
            if (missed.isEmpty()) reply.append("Your edit changes nothing: the script is the same as before.\n");
            reply.append("Nothing changed").append(current == null ? "" : "; version " + current.n + " stays current").append(".\n");
            return false;
        }
        String how = String.join(", ", done);
        Attempt a = execute(next, how);
        if (a.version == null) {
            draft = next;
            draftError = a.error;
            reply.append("The script failed").append(current == null ? "" : " (version " + current.n + " stays current)")
                    .append(":\n").append(a.error).append('\n')
                    .append("Your edit is kept as the DRAFT shown below: fix it with hunks against the draft, or REVERT/UNDO to drop it.\n");
            journal.add("   x " + how + " failed to run: " + clip(firstLine(a.error), 140) + " (kept as the draft)");
            return false;
        }
        draft = null;
        draftError = null;
        if (!missed.isEmpty()) journal.add("   ! v" + a.version.n + ": part of the answer was not applied (" + clip(firstLine(missed.getFirst()), 120) + ")");
        return missed.isEmpty();
    }


    /**
     * The top-level part() statements of {@code code} when that is all it holds (comments aside) and it has no
     * meta(): an answer that rewrites some parts and means to keep the rest. Null for anything else.
     */
    static List<ScriptLint.PartStatement> partsOnly(String code) {
        if (code == null || code.contains("meta(")) return null;
        List<ScriptLint.PartStatement> parts = ScriptLint.topLevelParts(code);
        if (parts == null || parts.isEmpty()) return null;
        StringBuilder rest = new StringBuilder();
        int at = 0;
        for (ScriptLint.PartStatement p : parts) {
            rest.append(code, at, p.start());
            at = p.end();
        }
        rest.append(code.substring(at));
        String left = rest.toString().replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "").strip();
        return left.isEmpty() ? parts : null;
    }

    /** For a SEARCH that was not found: the lines of the script that look most like its first line. */
    private static String nearest(String base, String error, List<String[]> hunks) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("hunk (\\d+)").matcher(error == null ? "" : error);
        if (!m.find() || !error.contains("not found")) return "";
        int i = Integer.parseInt(m.group(1)) - 1;
        if (i < 0 || i >= hunks.size()) return "";
        String want = hunks.get(i)[0].strip().lines().filter(l -> !l.isBlank()).findFirst().orElse("").strip();
        if (want.isEmpty()) return "";
        String[] lines = base.split("\n", -1);
        int best = -1;
        double score = 0.35;
        for (int k = 0; k < lines.length; k++) {
            double sc = similarity(want, lines[k].strip());
            if (sc > score) {
                score = sc;
                best = k;
            }
        }
        if (best < 0) return "No line of the script resembles its first line; copy SEARCH lines from THE SCRIPT below.\n";
        StringBuilder sb = new StringBuilder("The script has near line ").append(best + 1).append(" (copy SEARCH from here):\n");
        for (int k = Math.max(0, best - 2); k <= Math.min(lines.length - 1, best + 3); k++) sb.append(lines[k]).append('\n');
        return sb.toString();
    }

    /** Dice similarity of character bigrams, 0..1. */
    static double similarity(String a, String b) {
        if (a.length() < 2 || b.length() < 2) return a.equals(b) ? 1 : 0;
        Map<String, Integer> grams = new java.util.HashMap<>();
        for (int i = 0; i + 1 < a.length(); i++) grams.merge(a.substring(i, i + 2), 1, Integer::sum);
        int hit = 0;
        for (int i = 0; i + 1 < b.length(); i++) {
            String g = b.substring(i, i + 2);
            Integer n = grams.get(g);
            if (n != null && n > 0) {
                hit++;
                grams.put(g, n - 1);
            }
        }
        return 2.0 * hit / (a.length() - 1 + b.length() - 1);
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    private record Attempt(Version version, String error) {
    }

    private Attempt execute(String source, String origin) {
        ScriptResult r = runtime.run(source, seed, null, limits, budget, null);
        if (!r.ok()) return new Attempt(null, r.error().describe());
        host.prepare(r);
        AnalysisReport rep = StructureAnalyzer.analyze(r.model(), info, host.options(r));
        Version v = new Version(versions.size() + 1, source, r, rep, QualityVector.of(rep, r), origin, review(r),
                current == null ? 0 : current.n, why);
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
                    images.addAll(ContactSheet.closeupViews(o.shipped().result.model(), info, f, viewW()));
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
        // one view per image, each at the full image budget of the vision model, up to eight a turn
        List<JsonObject> all = views.subList(0, Math.min(8, views.size()));
        if (views.size() > all.size()) reply.append("look: only the first 8 views were rendered.\n");
        lookSheet(all, model, b, reply, images);
    }

    private void lookSheet(List<JsonObject> views, VoxelModel model, int[] b, StringBuilder reply, List<byte[]> images) {
        List<ContactSheet.Shot> shots = new ArrayList<>();
        int[] t = {viewW(), viewW() * 3 / 4};
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
            for (ContactSheet.Shot sh : shots) {
                VoxelRaster.Options opt = VoxelRaster.Options.defaults();
                if (sh.clipY() != null) opt = opt.clip(sh.clipY());
                if (sh.label().startsWith("eye level")) opt = opt.ground(groundReach(model));
                images.add(ContactSheet.single(model, info, sh.camera(), opt, sh.label()));
            }
            reply.append("Images (one per view): ").append(String.join(" | ", shots.stream().map(ContactSheet.Shot::label).toList())).append('\n');
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
            int sideIdx = switch (e) {
                case "west" -> 1;
                case "north" -> 2;
                case "east" -> 3;
                default -> 0;
            };
            if (!v.has("distance")) {
                Subject sub = subject(current);
                int[] target = sub != null ? sub.box : aboveGround(model);
                return new ContactSheet.Shot("eye level from the " + e + ", standing" + (sub != null ? " in front of " + sub.name : ""),
                        VoxelRaster.stand(model, target, sideIdx, w, h), null);
            }
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
            double fx = cx + d[0] * dist, fz = cz + d[1] * dist;
            double stand = VoxelRaster.surface(model, (int) Math.floor(fx), (int) Math.floor(fz)) + 1;
            double[] from = {fx, stand + 1.62, fz};
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

    /** What a change did, measured against the version it was made from. */
    record Delta(List<String> lines, List<String> warnings, int[] region, boolean big) {
    }

    /**
     * Before against after: blocks per part, the analyzer's counts and the red pen. A warning is a measured sign the
     * change may have made the build worse (pieces newly floating, a part that shrank or vanished, new critical
     * issues); {@code big} is a rewrite or a change over a large share of the build.
     */
    Delta delta(Version a, Version b) {
        List<String> lines = new ArrayList<>(), warn = new ArrayList<>();
        int na = a.result.model().nonAirCount(), nb = b.result.model().nonAirCount();
        lines.add("blocks " + na + " -> " + nb + " (" + signed(nb - na) + ")");
        Map<String, Integer> pa = new LinkedHashMap<>(), pb = new LinkedHashMap<>();
        for (ScriptResult.Part p : a.result.parts()) pa.put(p.name(), p.cells());
        for (ScriptResult.Part p : b.result.parts()) pb.put(p.name(), p.cells());
        java.util.Set<String> names = new java.util.LinkedHashSet<>(pa.keySet());
        names.addAll(pb.keySet());
        List<String[]> changes = new ArrayList<>();
        for (String n : names) {
            int x = pa.getOrDefault(n, 0), y = pb.getOrDefault(n, 0);
            if (x == y) continue;
            if (y == 0) {
                changes.add(new String[]{String.valueOf(x), "part " + n + " is gone (had " + x + " blocks)"});
                if (x >= 300) warn.add("part " + n + " is gone (had " + x + " blocks)");
            } else if (x == 0) {
                changes.add(new String[]{String.valueOf(y), "part " + n + " is new: " + y + " blocks"});
            } else if (Math.abs(y - x) >= Math.max(20, x / 20)) {
                int pct = (int) Math.round(100.0 * (y - x) / x);
                changes.add(new String[]{String.valueOf(Math.abs(y - x)), "part " + n + " " + x + " -> " + y + " (" + (pct > 0 ? "+" : "") + pct + "%)"});
                if (pct <= -30 && x >= 300) warn.add("part " + n + " lost " + (-pct) + "% of its blocks (" + x + " -> " + y + ")");
            }
        }
        changes.sort((u, v) -> Integer.compare(Integer.parseInt(v[0]), Integer.parseInt(u[0])));
        for (int i = 0; i < Math.min(10, changes.size()); i++) lines.add(changes.get(i)[1]);
        if (changes.size() > 10) lines.add("... " + (changes.size() - 10) + " more parts changed");
        QualityVector qa = a.quality, qb = b.quality;
        counts(lines, "floating pieces (blocks)", qa.floating(), qb.floating());
        counts(lines, "unsupported attachments", qa.unsupported(), qb.unsupported());
        counts(lines, "critical issues", qa.critical(), qb.critical());
        counts(lines, "dark spots", qa.dark(), qb.dark());
        counts(lines, "blocked doors", qa.blockedDoors(), qb.blockedDoors());
        counts(lines, "rooms", qa.rooms(), qb.rooms());
        if (qb.floating() - qa.floating() >= 20) warn.add("floating pieces " + qa.floating() + " -> " + qb.floating());
        if (qb.critical() > qa.critical()) warn.add("critical issues " + qa.critical() + " -> " + qb.critical());
        String penA = penKinds(a.craft), penB = penKinds(b.craft);
        if (!penA.equals(penB)) lines.add("red pen: " + (penA.isEmpty() ? "nothing" : penA) + " -> " + (penB.isEmpty() ? "nothing" : penB));
        int[] ba = a.result.model().solidBounds(), bb = b.result.model().solidBounds();
        if (ba != null && bb != null) {
            int ha = ba[4] - ba[1] + 1, hb = bb[4] - bb[1] + 1;
            if (ha != hb) lines.add("height " + ha + " -> " + hb);
            if (hb < ha * 0.85 && ha >= 10) warn.add("the build is " + (100 - 100 * hb / ha) + "% lower (" + ha + " -> " + hb + ")");
            if (nb < na * 0.75 && na >= 1000) warn.add("the build lost " + (100 - (int) (100L * nb / na)) + "% of its blocks");
        }
        List<int[]> regions = BuildPipeline.changedRegions(a.result.model(), b.result.model(), 3);
        int[] region = null;
        for (int[] r : regions) {
            if (region == null) region = r.clone();
            else for (int i = 0; i < 3; i++) {
                region[i] = Math.min(region[i], r[i]);
                region[i + 3] = Math.max(region[i + 3], r[i + 3]);
            }
        }
        if (region == null) lines.add("no visible change");
        else lines.add("changed: " + String.join("; ", regions.stream().map(StudioSession::box).toList()));
        boolean big = b.origin.equals("whole script");
        int[] whole = union(ba, bb);
        if (region != null && whole != null && volume(region) >= 0.4 * volume(whole)) big = true;
        return new Delta(lines, warn, region, big);
    }

    private static void counts(List<String> lines, String what, int a, int b) {
        if (a != b) lines.add(what + " " + a + " -> " + b);
    }

    private static String signed(int n) {
        return n >= 0 ? "+" + n : String.valueOf(n);
    }

    private static String penKinds(CraftReview.Result r) {
        Map<String, Integer> k = new java.util.TreeMap<>();
        for (CraftReview.Mark m : r.marks()) k.merge(m.kind(), 1, Integer::sum);
        return String.join(", ", k.entrySet().stream().map(e -> e.getKey() + " " + e.getValue()).toList());
    }

    private static int[] union(int[] a, int[] b) {
        if (a == null) return b;
        if (b == null) return a;
        return new int[]{Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2]), Math.max(a[3], b[3]), Math.max(a[4], b[4]), Math.max(a[5], b[5])};
    }

    private static double volume(int[] b) {
        return (double) (b[3] - b[0] + 1) * (b[4] - b[1] + 1) * (b[5] - b[2] + 1);
    }

    /**
     * What a new version is: its run, the check against the version it came from, the analyzer's issues, the red
     * pen, and with eyes the renders (appended to {@code images}): the overview, the change before and after from
     * the same cameras, and close-ups of the main parts for a first version or a rewrite.
     */
    private String report(Version v, Version before, Delta d, List<byte[]> images) {
        ScriptResult r = v.result;
        StringBuilder sb = new StringBuilder();
        sb.append("VERSION ").append(v.n).append(" (").append(v.origin).append(before == null ? "" : ", made from version " + before.n)
                .append("): ").append(r.model().nonAirCount()).append(" blocks, ran in ").append(r.elapsedMs()).append(" ms.\n");
        if (!r.warnings().isEmpty()) {
            sb.append("Warnings and auto-corrections:\n");
            for (String w : r.warnings().subList(0, Math.min(12, r.warnings().size()))) sb.append("- ").append(w).append('\n');
            if (r.warnings().size() > 12) sb.append("- ... ").append(r.warnings().size() - 12).append(" more\n");
        }
        if (!r.logs().isEmpty()) sb.append("log(): ").append(clip(String.join(" | ", r.logs()), 1200)).append('\n');
        if (d != null) {
            sb.append("CHECK against version ").append(before.n).append(" (measured):\n");
            for (String l : d.lines) sb.append("- ").append(l).append('\n');
            for (String w : d.warnings) sb.append("! POSSIBLE REGRESSION: ").append(w).append('\n');
            if (!d.warnings.isEmpty()) sb.append("  If the change made this worse, UNDO it and fix the problem a different way.\n");
        }
        boolean first = before == null || v.origin.equals("whole script");
        sb.append("PARTS:\n").append(r.partTable(first ? 40 : 20));
        if (first) {
            String sheet = r.sheetSection();
            if (!sheet.isEmpty()) sb.append(sheet).append('\n');
        }
        sb.append("ANALYSIS: ").append(issues(v.report, focus == null));
        List<CraftReview.Mark> marks = visibleMarks(v.craft);
        shownMarks = marks;
        String penKey = penKinds(new CraftReview.Result(marks, v.craft.metrics())) + marks.stream().map(m -> box(m.box())).toList();
        String pen = new CraftReview.Result(marks, v.craft.metrics()).toText();
        if (!marks.isEmpty() && penKey.equals(lastPen)) {
            // the same notes as last time, word for word: one line, so the new information is not buried under them
            sb.append("\nRED PEN: the same ").append(marks.size()).append(" marks as in the last report (")
                    .append(penKinds(new CraftReview.Result(marks, v.craft.metrics()))).append("), unchanged; KEEP n for one you intend.\n");
        } else if (!pen.isEmpty()) {
            sb.append('\n').append(pen);
            if (!marks.isEmpty()) sb.append("Clear a mark only where that makes the build better, never by sticking on clutter; KEEP n when a"
                    + " marked thing is what you intend.\n");
        }
        byte[] penImage = s.eyes && !marks.isEmpty() && !penKey.equals(lastPen) ? RedPen.sheet(r.model(), info, marks, s.imageSize) : null;
        lastPen = penKey;
        List<byte[]> optionImages = new ArrayList<>();
        options(v, sb, optionImages);
        List<dev.aibuild.script.Collision> between = between(r);
        if (!between.isEmpty()) {
            sb.append("Collisions between parts (one part wrote over another's blocks):\n");
            for (dev.aibuild.script.Collision c : between) sb.append("- ").append(c.describe()).append('\n');
        }
        Finisher.Result f = Finisher.plan(r.model(), info, between, host.options(r).expectInterior);
        if (f.statement() != null && f.edits() > 0) {
            sb.append("The finisher could repair ").append(f.edits()).append(" cells: ").append(String.join("; ", f.notes()))
                    .append(". Say FINISH to apply that as part('finish'), or fix them your way.\n");
        }
        journal.add("v" + v.n + (before == null ? "" : " <- v" + before.n) + " " + v.origin + ": " + r.model().nonAirCount() + " blocks"
                + (d == null ? "" : " (" + signed(r.model().nonAirCount() - before.result.model().nonAirCount()) + ")")
                + (d == null || d.warnings.isEmpty() ? "" : " ! " + String.join("; ", d.warnings))
                + (v.why.isEmpty() ? "" : ". You: " + clip(v.why, 160)));
        if (s.eyes) {
            List<String> names = new ArrayList<>();
            images.addAll(overview(v));
            names.add(overviewNames(v));
            if (d != null && d.region != null && focus == null) {
                int[] box = d.big ? null : pad(d.region, 2);
                images.addAll(ContactSheet.beforeAfterViews(before.result.model(), r.model(), "BEFORE v" + before.n, "AFTER v" + v.n, info, box, viewW()));
                names.add("the change, 2 images (south-east, north-west): version " + before.n + " on the left, version " + v.n
                        + " on the right, same camera" + (box == null ? ", whole build" : ", on " + box(box)));
            }
            if (penImage != null) {
                images.add(penImage);
                names.add("red pen (the numbered marks)");
            }
            images.addAll(optionImages);
            for (int i = 0; i < optionImages.size(); i++) names.add("option choices side by side");
            boolean roomsChanged = before == null || before.report.roomList.size() != v.report.roomList.size();
            if (!v.report.roomList.isEmpty() && roomsChanged) {
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
                    images.addAll(ContactSheet.closeupViews(r.model(), info, List.of(new ContactSheet.Focus(focus, pb)), viewW()));
                    names.add("close-up of your part");
                }
            } else if (first) {
                // a first look at every main part up close, where the details are judged
                List<ContactSheet.Focus> fs = new ArrayList<>();
                r.parts().stream().filter(p -> !p.name().contains("/")).sorted((x, y) -> Integer.compare(y.cells(), x.cells())).limit(4)
                        .forEach(p -> fs.add(new ContactSheet.Focus(p.name(), p.bounds())));
                if (fs.size() > 1) {
                    images.addAll(ContactSheet.closeupViews(r.model(), info, fs, viewW()));
                    names.add("close-ups, one per image: " + String.join(", ", fs.stream().map(ContactSheet.Focus::name).toList()));
                }
            }
            sb.append("Images: ").append(String.join("; ", names)).append('\n');
        }
        return sb.toString();
    }

    /** The analyzer's size line and its issues, without the metrics, facades and block lists of the full report. */
    private static String issues(AnalysisReport rep, boolean majorsInState) {
        StringBuilder sb = new StringBuilder();
        if (rep.bounds != null) {
            sb.append(rep.sizeX).append('x').append(rep.sizeY).append('x').append(rep.sizeZ).append(" (x").append(rep.bounds[0]).append("..")
                    .append(rep.bounds[3]).append(" y").append(rep.bounds[1]).append("..").append(rep.bounds[4]).append(" z").append(rep.bounds[2])
                    .append("..").append(rep.bounds[5]).append("), rooms ").append(rep.rooms).append(", doors ").append(rep.doors)
                    .append(", windows ").append(rep.windows).append(", lights ").append(rep.lightSources).append('\n');
        }
        // with a state below, the major and critical issues are its OPEN FAULTS, with their history: not twice
        List<dev.aibuild.analysis.Issue> list = rep.issues.stream()
                .filter(i -> !majorsInState || i.severity().rank > dev.aibuild.analysis.Severity.MAJOR.rank).toList();
        long majors = rep.issues.size() - list.size();
        if (list.isEmpty() && majors == 0) return sb.append("no issues found.\n").toString();
        int n = 0;
        for (dev.aibuild.analysis.Issue i : list) {
            if (n++ >= 10) {
                sb.append("... ").append(list.size() - 10).append(" more issues (REPORT lists all)\n");
                break;
            }
            sb.append(i.toLine()).append('\n');
        }
        if (majors > 0) sb.append("(").append(majors).append(" major/critical: listed under OPEN FAULTS below)\n");
        return sb.toString();
    }

    /** Everything the analyzer knows of a version, on REPORT. */
    private String fullReport(Version v) {
        StringBuilder sb = new StringBuilder("FULL REPORT of version ").append(v.n).append(":\n");
        sb.append(v.report.toText()).append('\n');
        String spatial = SpatialReport.render(v.result.model(), info);
        if (spatial != null && !spatial.isBlank()) sb.append(spatial).append('\n');
        sb.append("Quality: ").append(v.quality.describe()).append('\n');
        String sheet = v.result.sheetSection();
        if (!sheet.isEmpty()) sb.append(sheet).append('\n');
        return sb.toString();
    }

    /** Collisions between different top-level parts: a part overwriting its own blocks is how it is built. */
    private static List<dev.aibuild.script.Collision> between(ScriptResult r) {
        List<dev.aibuild.script.Collision> out = new ArrayList<>();
        for (dev.aibuild.script.Collision c : r.collisions()) {
            if (c.cells() < ScriptResult.COLLISION_MIN) continue;
            if (top(c.victim()).equals(top(c.culprit()))) continue;
            out.add(c);
        }
        return out;
    }

    private static String top(String part) {
        if (part == null) return "";
        int i = part.indexOf('/');
        return i < 0 ? part : part.substring(0, i);
    }

    private static int[] pad(int[] b, int by) {
        return new int[]{b[0] - by, b[1] - by, b[2] - by, b[3] + by, b[4] + by, b[5] + by};
    }

    // ------------------------------------------------------------------ open faults

    /** A measured fault of a version, keyed by kind and the top-level part it sits in, so other versions can be compared. */
    record Fault(String key, String part, String text) {
    }

    /** The analyzer's major and critical issues of a version, one per kind and part. */
    List<Fault> faults(Version v) {
        List<Fault> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (dev.aibuild.analysis.Issue i : v.report.issues) {
            if (i.severity().rank > dev.aibuild.analysis.Severity.MAJOR.rank) continue;
            int[] at = i.samples().isEmpty() ? null : i.samples().getFirst();
            String part = at == null ? "" : top(owner(v.result, at[0], at[1], at[2]));
            String key = i.code() + "|" + part;
            if (!seen.add(key)) continue;
            out.add(new Fault(key, part, clip(i.message(), 170)));
        }
        return out;
    }

    /** The smallest part whose bounds hold the cell: the part that most likely wrote it. */
    static String owner(ScriptResult r, int x, int y, int z) {
        String best = "";
        double vol = Double.MAX_VALUE;
        for (ScriptResult.Part p : r.parts()) {
            int[] b = p.bounds();
            if (b == null || x < b[0] || y < b[1] || z < b[2] || x > b[3] || y > b[4] || z > b[5]) continue;
            double v = volume(b);
            if (v < vol) {
                vol = v;
                best = p.name();
            }
        }
        return best;
    }

    /**
     * The faults still open in the current version, each with the version it has been open since (along the chain of
     * versions this one was made from) and any other version that did not have it: a fix that was lost when its version
     * was rejected or reverted stays in sight, with the TAKE that brings it back.
     */
    String openFaults(Version v) {
        List<Fault> open = faults(v);
        if (open.isEmpty()) return "\nOPEN FAULTS (measured on version " + v.n + "): none.\n";
        StringBuilder sb = new StringBuilder("\nOPEN FAULTS (measured on version ").append(v.n)
                .append("; each stays listed until the blocks show it is gone):\n");
        java.util.Set<Integer> chain = new java.util.HashSet<>();
        for (Version c = v; c != null; c = c.parent == 0 ? null : pick(c.parent)) {
            if (!chain.add(c.n)) break;
        }
        int shown = 0;
        for (Fault f : open) {
            if (shown++ >= 10) {
                sb.append("- ... ").append(open.size() - 10).append(" more (REPORT lists all)\n");
                break;
            }
            int since = v.n;
            for (Version c = v; c != null && c.parent != 0; ) {
                Version p = pick(c.parent);
                if (p == null || faults(p).stream().noneMatch(g -> g.key.equals(f.key))) break;
                since = p.n;
                c = p;
            }
            sb.append("- ").append(since == v.n && v.parent != 0 ? "new in v" + v.n : "open since v" + since).append(": ")
                    .append(f.part.isEmpty() ? "" : "part " + f.part + ": ").append(f.text);
            // a version off this chain that was free of it: its fix was lost on a rejection or a revert
            for (int k = versions.size() - 1; k >= 0; k--) {
                Version o = versions.get(k);
                if (chain.contains(o.n) || f.part.isEmpty() || ScriptPatch.partSource(o.source, f.part) == null) continue;
                if (faults(o).stream().noneMatch(g -> g.key.equals(f.key))) {
                    sb.append(" -- version ").append(o.n).append(" did not have it: TAKE ").append(f.part).append(" FROM ").append(o.n)
                            .append(" brings that part back");
                    break;
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private List<byte[]> overview(Version v) {
        return overviews.computeIfAbsent(v.n, n -> {
            Subject sub = subject(v);
            return ContactSheet.views(v.result.model(), info, sub == null ? null : sub.box, sub == null ? null : sub.name, viewW());
        });
    }

    private String overviewNames(Version v) {
        Subject sub = subject(v);
        return "version " + v.n + ", one view per image: the whole build from the south-east; "
                + (sub == null ? "the whole build from the north-west" : "the subject (" + sub.name + ") close from the south-west")
                + "; eye level standing in front" + (sub == null ? "" : " of " + sub.name)
                + "; a sheet of all four corners, the back at eye level and the top";
    }

    /** The main subject of a scene: a named group of parts with its box. */
    record Subject(String name, int[] box) {
    }

    /**
     * The subject inside a scene: the top-level part group that rises highest, when it covers clearly less of the
     * ground than the whole build (a tree on a landscaped plot, a castle in its grounds). Null when the build is
     * one subject, so the views frame all of it.
     */
    Subject subject(Version v) {
        Map<String, int[]> groups = new java.util.LinkedHashMap<>();
        for (ScriptResult.Part p : v.result.parts()) {
            if (p.bounds() == null || p.cells() == 0) continue;
            groups.merge(top(p.name()), p.bounds().clone(), StudioSession::union);
        }
        int[] all = v.result.model().solidBounds();
        if (all == null || groups.size() < 2) return null;
        String best = null;
        int[] box = null;
        for (var e : groups.entrySet()) {
            if (box == null || e.getValue()[4] > box[4]) {
                best = e.getKey();
                box = e.getValue();
            }
        }
        double area = (double) (box[3] - box[0] + 1) * (box[5] - box[2] + 1);
        double whole = (double) (all[3] - all[0] + 1) * (all[5] - all[2] + 1);
        return area <= 0.45 * whole ? new Subject(best, box) : null;
    }

    private static int[] aboveGround(VoxelModel m) {
        int[] out = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        m.forEach((x, y, z, b) -> {
            if (y < 1 || b == null || b.isAir()) return;
            out[0] = Math.min(out[0], x);
            out[1] = Math.min(out[1], y);
            out[2] = Math.min(out[2], z);
            out[3] = Math.max(out[3], x);
            out[4] = Math.max(out[4], y);
            out[5] = Math.max(out[5], z);
        });
        return out[0] == Integer.MAX_VALUE ? m.solidBounds() : out;
    }

    /** Width of a single-view image: 15/16 of the sheet size (1536 -> 1440, about the per-image pixel budget). */
    private int viewW() {
        return Math.max(320, s.imageSize * 15 / 16);
    }

    private static int groundReach(VoxelModel m) {
        int[] a = aboveGround(m);
        return a == null ? 80 : (int) Math.ceil(Math.max(a[3] - a[0], a[5] - a[2]) * 2.5 + 80);
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

    /** The answers to a turn's ```guide and ```blocks lookups ("" when it asked none). */
    private String lookups(StudioProtocol.Turn t) {
        StringBuilder sb = new StringBuilder();
        for (String g : t.guides()) {
            String text = Handbook.chapter(Stdlib.V2, g);
            sb.append(text == null ? "guide: no chapter '" + g + "'. Chapters: " + String.join(", ", Handbook.TOPICS.keySet()) + "\n"
                    : "\n" + text + "\n");
        }
        for (String q : t.blockQueries().subList(0, Math.min(8, t.blockQueries().size()))) sb.append('\n').append(BlockLookup.lookup(q, info)).append('\n');
        return sb.toString();
    }

    /**
     * The first turn, asked {@link Settings#firstDrafts} times in parallel: the drafts whose scripts run meet in a
     * blind tournament (each pair judged in both orders), and the winner's answer becomes the session's first turn.
     */
    private String firstDraft(String opening) throws Exception {
        List<ChatMessage> msgs = messages(opening);
        List<Future<String>> fs = new ArrayList<>();
        for (int i = 1; i < s.firstDrafts; i++) {
            String tag = s.tag + "-t1x" + (i + 1);
            fs.add(host.pool().submit(() -> host.call(msgs, tag)));
        }
        List<String> answers = new ArrayList<>();
        answers.add(host.call(msgs, s.tag + "-t1"));
        for (Future<String> f : fs) {
            try {
                answers.add(f.get(Math.max(1, left() / 1_000_000_000L), TimeUnit.SECONDS));
            } catch (Exception e) {
                if (cancellation(e)) throw e;
                f.cancel(true);
            }
        }
        // a draft that first looks things up (a guide chapter, block ids) gets its answers and writes its script
        // within the draft, instead of being dropped for having no script yet
        List<Integer> asked = new ArrayList<>();
        List<Future<String>> follow = new ArrayList<>();
        for (int i = 0; i < answers.size() && left() > TimeUnit.MINUTES.toNanos(8); i++) {
            StudioProtocol.Turn t = StudioProtocol.parse(answers.get(i));
            String found = t.script() == null ? lookups(t) : "";
            if (found.isBlank()) continue;
            List<ChatMessage> fm = new ArrayList<>(msgs);
            fm.add(ChatMessage.assistant(answers.get(i)));
            fm.add(ChatMessage.user(found.strip() + "\n\nNow write your ```notes and your first complete script: the whole composition at its final size."));
            String tag = s.tag + "-t1x" + (i + 1) + "-look";
            asked.add(i);
            follow.add(host.pool().submit(() -> host.call(fm, tag)));
        }
        for (int k = 0; k < follow.size(); k++) {
            try {
                String more = follow.get(k).get(Math.max(1, left() / 1_000_000_000L), TimeUnit.SECONDS);
                // both halves stay in the answer: the lookups are answered again in the reply, so later turns keep them
                answers.set(asked.get(k), answers.get(asked.get(k)) + "\n\n" + more);
            } catch (Exception e) {
                if (cancellation(e)) throw e;
                follow.get(k).cancel(true);
            }
        }
        List<String> ok = new ArrayList<>();
        List<ScriptResult> runs = new ArrayList<>();
        List<Integer> failedAt = new ArrayList<>();
        List<String> failedError = new ArrayList<>();
        for (int i = 0; i < answers.size(); i++) {
            String a = answers.get(i);
            StudioProtocol.Turn t = StudioProtocol.parse(a);
            if (t.script() == null) continue;
            ScriptResult r = runtime.run(t.script(), seed, null, limits, budget, null);
            if (!r.ok() || r.model().nonAirCount() == 0) {
                failedAt.add(i);
                failedError.add(r.ok() ? "the script ran but placed no blocks" : r.error().describe());
                continue;
            }
            host.prepare(r);
            ok.add(a);
            runs.add(r);
        }
        // a draft that fails on a slip is a whole design lost: each gets one fix call, all in parallel
        if (!failedAt.isEmpty() && answers.size() > 1 && left() > TimeUnit.MINUTES.toNanos(8)) {
            List<Future<String>> fixes = new ArrayList<>();
            for (int k = 0; k < failedAt.size(); k++) {
                String a = answers.get(failedAt.get(k));
                List<ChatMessage> fm = new ArrayList<>(msgs);
                fm.add(ChatMessage.assistant(a));
                fm.add(ChatMessage.user("Your script failed to run:\n" + failedError.get(k) + "\nFix only that: answer with SEARCH/REPLACE"
                        + " hunks against your script (or the whole corrected script in a ```js block). Change nothing else."));
                String tag = s.tag + "-t1fix" + (failedAt.get(k) + 1);
                fixes.add(host.pool().submit(() -> host.call(fm, tag)));
            }
            for (int k = 0; k < fixes.size(); k++) {
                try {
                    String fix = fixes.get(k).get(Math.max(1, left() / 1_000_000_000L), TimeUnit.SECONDS);
                    String a = answers.get(failedAt.get(k));
                    String src = StudioProtocol.parse(a).script();
                    StudioProtocol.Turn ft = StudioProtocol.parse(fix);
                    String fixed = null;
                    if (!ft.hunks().isEmpty()) {
                        StringBuilder text = new StringBuilder();
                        for (String[] h : ft.hunks()) text.append("<<<<<<< SEARCH\n").append(h[0]).append("\n=======\n").append(h[1]).append("\n>>>>>>> REPLACE\n");
                        ScriptPatch.Result p = ScriptPatch.apply(src, text.toString());
                        if (p.ok()) fixed = p.source();
                    } else if (ft.script() != null && partsOnly(ft.script()) == null) {
                        fixed = ft.script();
                    }
                    if (fixed == null) continue;
                    ScriptResult r = runtime.run(fixed, seed, null, limits, budget, null);
                    if (!r.ok() || r.model().nonAirCount() == 0) continue;
                    host.prepare(r);
                    // the fixed draft stands in for the answer, with its notes and the corrected script
                    ok.add(a.replace(src.strip(), fixed.strip()));
                    runs.add(r);
                } catch (Exception e) {
                    if (cancellation(e)) throw e;
                }
            }
        }
        if (ok.size() < 2) {
            if (answers.size() > 1) draftNote = "(" + answers.size() + " first drafts were written in parallel; " + ok.size() + " of them ran, and that one is built on.)";
            return ok.isEmpty() ? answers.getFirst() : ok.getFirst();
        }
        int best = 0;
        List<String> notesOut = new ArrayList<>();
        for (int c = 1; c < ok.size(); c++) {
            if (left() < TimeUnit.MINUTES.toNanos(4)) break;
            char w = tournamentMatch(runs.get(best).model(), runs.get(c).model(), "draft " + (best + 1) + " vs " + (c + 1), notesOut);
            if (w == 'B') best = c;
        }
        checks++;
        draftNote = "(" + ok.size() + " first drafts were written in parallel and compared blind; draft " + (best + 1)
                + " was preferred and is the one built on" + (notesOut.isEmpty() ? "" : ": " + clip(String.join(" / ", notesOut), 400)) + ")";
        journal.add("   first drafts: " + ok.size() + " ran, draft " + (best + 1) + " chosen blind");
        return ok.get(best);
    }

    /** One blind match, both orders in parallel: 'A' or 'B' when both agree, else 'A' (the incumbent stays). */
    private char tournamentMatch(VoxelModel a, VoxelModel b, String what, List<String> notesOut) {
        String ask = "BUILD REQUEST: \"" + request + "\"\nWhich version is the better build of this request?";
        List<ChatMessage> m1 = List.of(ChatMessage.system(JUDGE_SYSTEM), ChatMessage.user(ask, ContactSheet.pairViews(a, b, info, viewW())));
        List<ChatMessage> m2 = List.of(ChatMessage.system(JUDGE_SYSTEM), ChatMessage.user(ask, ContactSheet.pairViews(b, a, info, viewW())));
        try {
            Future<String> f2 = host.pool().submit(() -> host.judge(m2, s.tag + "-drafts-b"));
            String j1 = host.judge(m1, s.tag + "-drafts-a");
            String j2 = f2.get(5, TimeUnit.MINUTES);
            char v1 = verdict(j1), v2 = verdict(j2);
            notesOut.add(what + ": " + clip(v1 == 'B' ? relabel(reason(j1), "the first", "the second") : relabel(reason(j2), "the second", "the first"), 180));
            return v1 == 'B' && v2 == 'A' ? 'B' : 'A';
        } catch (Exception e) {
            return 'A';
        }
    }

    static final String JUDGE_SYSTEM = """
            You are an experienced Minecraft builder judging two versions of the same build. Each image is one camera,
            with version A on the left half and version B on the right half, the camera the same for both: two corners
            and a player's view from the front. Compare them half against half, closely. Judge them as a player would: does it
            read as the subject that was asked for, are the proportions and silhouette right, is there depth and detail
            where the eye goes, do the materials work, are there obvious mistakes (floating pieces, holes, noise,
            clutter)? A bigger or busier build is not better by itself.
            Answer with A, B or SAME on the first line, then one sentence naming the decisive difference; in it, call
            them "Version A" and "Version B".""";

    /**
     * The blind check of a big change (a rewrite, a large share of the build, or a measured regression): an
     * independent judge sees the two versions unlabelled, twice in parallel with the order swapped so the order
     * itself cannot decide. Only when both judgments prefer the version before does it stay current; the new one is
     * kept and REVERT n takes it anyway. Small changes are not checked: the model sees their before and after itself.
     */
    private void verify(Version before, Version after, Delta d, StringBuilder reply) {
        if (!s.verify || !s.eyes || focus != null || !(d.big || !d.warnings.isEmpty())) return;
        if (s.deadlineNanos != Long.MAX_VALUE && left() < TimeUnit.MINUTES.toNanos(3)) return;
        List<byte[]> ab = ContactSheet.pairViews(before.result.model(), after.result.model(), info, viewW());
        List<byte[]> ba = ContactSheet.pairViews(after.result.model(), before.result.model(), info, viewW());
        String ask = "BUILD REQUEST: \"" + request + "\"\nWhich version is the better build of this request?";
        List<ChatMessage> m1 = List.of(ChatMessage.system(JUDGE_SYSTEM), ChatMessage.user(ask, ab));
        List<ChatMessage> m2 = List.of(ChatMessage.system(JUDGE_SYSTEM), ChatMessage.user(ask, ba));
        String tag = s.tag + "-check-v" + after.n;
        String j1, j2;
        ExecutorService pool = host.pool();
        try {
            if (pool != null) {
                Future<String> f2 = pool.submit(() -> host.judge(m2, tag + "b"));
                j1 = host.judge(m1, tag + "a");
                j2 = f2.get(5, TimeUnit.MINUTES);
            } else {
                j1 = host.judge(m1, tag + "a");
                j2 = host.judge(m2, tag + "b");
            }
        } catch (Exception e) {
            if (cancellation(e)) return;
            reply.append("(The blind check of this change could not be made: ").append(clip(String.valueOf(e.getMessage()), 160)).append(")\n");
            return;
        }
        checks++;
        char v1 = verdict(j1), v2 = verdict(j2);
        // in the first call A is the version before, in the second B is
        boolean oldWins1 = v1 == 'A', oldWins2 = v2 == 'B', newWins1 = v1 == 'B', newWins2 = v2 == 'A';
        // the judge saw A and B; its reasons are told in version numbers, the same in both orders
        String why1 = clip(relabel(reason(j1), "version " + before.n, "version " + after.n), 220);
        String why2 = clip(relabel(reason(j2), "version " + after.n, "version " + before.n), 220);
        if (oldWins1 && oldWins2) {
            rejected++;
            current = before;
            host.shown(before);
            reply.append("BLIND CHECK: an independent judge compared version ").append(before.n).append(" and version ").append(after.n)
                    .append(" twice, unlabelled and in both orders, and preferred version ").append(before.n).append(" both times: \"")
                    .append(why1).append("\" / \"").append(why2).append("\"\nVersion ").append(before.n)
                    .append(" stays current. Version ").append(after.n).append(" is kept: REVERT ").append(after.n)
                    .append(" if you are sure it is better, or reach your goal from version ").append(before.n).append(" with a smaller change.\n");
            reply.append(salvage(before, after));
            journal.add("   blind check preferred v" + before.n + " over v" + after.n + ": " + clip(why1, 140) + " -> v" + before.n + " current");
        } else {
            String how = newWins1 && newWins2 ? "preferred the new version both times" : "found no consistent difference";
            reply.append("BLIND CHECK: an independent judge ").append(how).append("; version ").append(after.n).append(" stays current. (\"")
                    .append(why1).append("\" / \"").append(why2).append("\")\n");
            journal.add("   blind check: v" + after.n + " kept (" + how + ")");
        }
    }

    /**
     * What a rejected version got right, measured part by part: the faults it fixed and the parts it changed, each
     * with the TAKE that applies just that part to the current version. The judge rejects the whole; a good part of it
     * need not be lost with the rest.
     */
    String salvage(Version kept, Version rejected) {
        java.util.Set<String> now = new java.util.HashSet<>();
        for (Fault f : faults(rejected)) now.add(f.key);
        java.util.LinkedHashMap<String, List<String>> fixed = new java.util.LinkedHashMap<>();
        for (Fault f : faults(kept)) {
            if (now.contains(f.key) || f.part.isEmpty() || ScriptPatch.partSource(rejected.source, f.part) == null) continue;
            fixed.computeIfAbsent(f.part, k -> new ArrayList<>()).add(clip(f.text, 110));
        }
        List<String> changedParts = new ArrayList<>();
        List<ScriptLint.PartStatement> ps = ScriptLint.topLevelParts(rejected.source);
        if (ps != null) {
            for (ScriptLint.PartStatement p : ps) {
                String mine = rejected.source.substring(p.start(), p.end());
                String theirs = ScriptPatch.partSource(kept.source, p.name());
                if (!mine.equals(theirs)) changedParts.add(p.name());
            }
        }
        if (fixed.isEmpty() && changedParts.size() < 2) return "";
        StringBuilder sb = new StringBuilder("What version ").append(rejected.n).append(" did, part by part (the judgment was on the whole):\n");
        for (var e : fixed.entrySet()) {
            sb.append("- part ").append(e.getKey()).append(" FIXED: ").append(String.join("; ", e.getValue()))
                    .append(" -> TAKE ").append(e.getKey()).append(" FROM ").append(rejected.n).append('\n');
        }
        List<String> rest = changedParts.stream().filter(n -> !fixed.containsKey(n)).toList();
        if (!rest.isEmpty()) sb.append("- also changed: ").append(String.join(", ", rest)).append('\n');
        sb.append("Keep what worked: TAKE <part>[, <part>] FROM ").append(rejected.n)
                .append(" applies only those parts to the current version (and they are checked again).\n");
        return sb.toString();
    }

    /** A judge's sentence with "Version A/B" told as the given names. */
    static String relabel(String text, String a, String b) {
        if (text == null) return "";
        return text.replaceAll("\\b[Vv]ersion A\\b", "\u0000A").replaceAll("\\b[Vv]ersion B\\b", "\u0000B")
                .replaceAll("\\(A\\)", "\u0000A").replaceAll("\\(B\\)", "\u0000B")
                .replaceAll("\\bA(?=['’]s\\b)", "\u0000A").replaceAll("\\bB(?=['’]s\\b)", "\u0000B")
                .replaceAll("(?<![\\w.\u0000])A(?= (?:is|has|reads|shows|looks|keeps|loses|collapses|presents|wins|stays|gives|feels|lacks|offers|captures|matches|clearly|better|turns|buries|hides|makes)\\b)", "\u0000A")
                .replaceAll("(?<![\\w.\u0000])B(?= (?:is|has|reads|shows|looks|keeps|loses|collapses|presents|wins|stays|gives|feels|lacks|offers|captures|matches|clearly|better|turns|buries|hides|makes)\\b)", "\u0000B")
                .replaceAll("(?<=\\b(?:than|over|and|while|whereas|but) )A\\b", "\u0000A")
                .replaceAll("(?<=\\b(?:than|over|and|while|whereas|but) )B\\b", "\u0000B")
                .replace("\u0000A", a).replace("\u0000B", b);
    }

    /** A, B or S(ame) from a judge's answer; '?' when it gives none. */
    static char verdict(String answer) {
        if (answer == null) return '?';
        for (String line : answer.strip().split("\n")) {
            String t = line.replaceAll("[*#`_>\\s:.\\-]", "").toUpperCase(Locale.ROOT);
            if (t.isEmpty()) continue;
            if (t.startsWith("SAME") || t.startsWith("TIE") || t.startsWith("EQUAL")) return 'S';
            if (t.equals("A") || t.startsWith("AIS") || t.startsWith("VERSIONA") || t.startsWith("A(") || t.startsWith("A,")) return 'A';
            if (t.equals("B") || t.startsWith("BIS") || t.startsWith("VERSIONB") || t.startsWith("B(") || t.startsWith("B,")) return 'B';
            return '?';
        }
        return '?';
    }

    private static String reason(String answer) {
        if (answer == null) return "";
        String[] lines = answer.strip().split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < lines.length; i++) sb.append(lines[i].strip()).append(' ');
        String r = sb.toString().strip();
        return r.isEmpty() ? lines[0].strip() : r;
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
        sb.append(turn + 1).append(" of at most ").append(s.maxTurns).append(". ");
        sb.append(current == null ? "No version yet." : "Versions 1.." + versions.size() + ", current " + current.n + " (it ships if time runs out).");
        List<Helper> open = helpers.stream().filter(h -> !h.closed && !h.reported).toList();
        if (!open.isEmpty()) {
            sb.append(" Helpers working: ").append(String.join(", ", open.stream().map(h -> "d" + h.id + " " + h.part).toList())).append('.');
        }
        if (s.deadlineNanos != Long.MAX_VALUE && left() < TimeUnit.MINUTES.toNanos(3)) {
            sb.append("\nAlmost out of time: make this your last change, then DONE (the current version ships when time runs out).");
        }
        return sb.toString();
    }

    /**
     * Where the build stands, sent with every reply in place of the older turns: the model's own notes, the journal
     * of versions, and the script it edits (the current version, or its draft that does not run), verbatim, so
     * SEARCH lines are copied from the real text and nothing the model decided earlier is lost to a cut context.
     */
    String state(int turn) {
        StringBuilder sb = new StringBuilder("\n===== WHERE THE BUILD STANDS =====\n");
        sb.append("YOUR NOTES (your latest ```notes block; older turns are not shown again, so the notes are your memory: keep the intent,"
                + " key dimensions, palette, the plan and the open issues with their locations there):\n");
        sb.append(notes == null ? "(none yet: write a ```notes block in your next answer)\n" : notes + "\n");
        if (!journal.isEmpty()) {
            sb.append("\nVERSIONS (newest last; <- = made from):\n");
            int from = Math.max(0, journal.size() - 30);
            if (from > 0) sb.append("... ").append(from).append(" earlier lines\n");
            for (String l : journal.subList(from, journal.size())) sb.append(l).append('\n');
        }
        if (current != null) sb.append(openFaults(current));
        String src = draft != null ? draft : current == null ? null : current.source;
        if (src != null) {
            String shown = focus != null ? ScriptPatch.partSource(src, focus) : null;
            sb.append('\n');
            if (draft != null) {
                sb.append("DRAFT: your latest edit does not run (").append(clip(firstLine(draftError), 200)).append("). Version ")
                        .append(current == null ? "-" : String.valueOf(current.n))
                        .append(" is current. Below is the draft: hunks apply to it; REVERT n or UNDO drops it.\n");
            }
            sb.append(draft != null ? "THE DRAFT" : "THE SCRIPT OF VERSION " + current.n)
                    .append(shown != null ? ", your part (the rest is as in the first message)" : "")
                    .append(" (copy SEARCH lines exactly from here):\n```js\n").append(shown != null ? shown + "\n" : src)
                    .append(src.endsWith("\n") || shown != null ? "" : "\n").append("```\n");
        }
        sb.append('\n').append(footer(turn));
        return sb.toString();
    }

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

    /** The images that fit the budget, in order (the first ones are the most important: the overview, the change). */
    private List<byte[]> fit(List<byte[]> images) {
        long budget = imageBudget < 0 ? s.imageBytes : imageBudget;
        List<byte[]> out = new ArrayList<>();
        long used = 0;
        for (byte[] b : images) {
            if (used + b.length > budget) continue;   // a smaller image further on may still fit
            out.add(b);
            used += b.length;
        }
        return out;
    }

    /**
     * The conversation for the next call: the system prompt and the opening (a fixed prefix the provider caches),
     * then only the model's last answer, with its code elided, and the reply to it with the current state and the
     * newest images. The model's notes and the journal carry what older turns decided, at a fraction of the tokens
     * the verbatim history cost.
     */
    private List<ChatMessage> messages(String opening) {
        List<ChatMessage> out = new ArrayList<>();
        out.add(ChatMessage.system(system));
        if (history.isEmpty()) {
            out.add(startImages.isEmpty() ? ChatMessage.user(opening) : ChatMessage.user(opening, fit(startImages)));
            return out;
        }
        out.add(ChatMessage.user(opening));
        Exchange e = history.getLast();
        out.add(ChatMessage.assistant(elideCode(e.answer)));
        String text = e.reply + state(history.size());
        List<byte[]> imgs = fit(e.images);
        if (imgs.size() < e.images.size()) text += "\n[" + (e.images.size() - imgs.size()) + " image(s) left out to keep the request small; a look shows any view]";
        out.add(imgs.isEmpty() ? ChatMessage.user(text) : ChatMessage.user(text, imgs));
        return out;
    }

    private static String elideCode(String answer) {
        return answer.replaceAll("```(?:js|javascript)?\\s*\\n[\\s\\S]{400,}?```", "```js\n[code omitted: the script is shown below]\n```")
                .replaceAll("```(?:notes|note|plan)\\s*\\n[\\s\\S]*?```", "[notes: shown below]")
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
