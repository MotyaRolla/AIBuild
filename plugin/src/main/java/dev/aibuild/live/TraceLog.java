package dev.aibuild.live;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The ordered record of everything a job did, kept for as long as the link lives.
 *
 * <p>Two views of the same data. {@link #summaries(int)} is what the list polls or receives with each event;
 * {@link #detail(int)} is fetched only when a person opens a step, because a single LLM step carries a full
 * prompt and answer and a build makes dozens of them.</p>
 *
 * <p>Threading: appended from pipeline and transport threads, read from HTTP threads. Every method is
 * synchronized on the instance — the structures are small and the contention is one writer — so a reader can
 * never see a half-added step.</p>
 */
public final class TraceLog {

    /** Steps kept per job. A long build makes a few hundred; the cap only matters for runaway loops. */
    static final int LIMIT = 600;

    /** Bytes of field text kept across the whole trace, so a pathological job cannot exhaust the heap. */
    static final long FIELD_BUDGET = 24L * 1024 * 1024;

    private final Deque<TraceStep> steps = new ArrayDeque<>();
    private int nextSeq = 1;
    private long fieldBytes;

    /** Starts a step and returns its builder; the caller freezes and records it with {@link #add}. */
    public TraceStep.Builder begin(TraceStep.Kind kind, String tag) {
        return new TraceStep.Builder(kind, tag);
    }

    /**
     * Records a finished step and returns it, so the caller can publish it without rebuilding it.
     *
     * <p>When the caps are reached the oldest steps are dropped and the sequence numbers keep counting, so
     * {@code seq} is a stable identity for a step even after its neighbours are gone.</p>
     */
    public synchronized TraceStep add(TraceStep.Builder builder) {
        TraceStep step = builder.build(nextSeq++);
        steps.addLast(step);
        fieldBytes += weight(step);
        while (steps.size() > LIMIT || (fieldBytes > FIELD_BUDGET && steps.size() > 1)) {
            TraceStep dropped = steps.removeFirst();
            fieldBytes -= weight(dropped);
        }
        return step;
    }

    /** Adds a step that is already complete (pipeline stages and model changes, which have no fields). */
    public TraceStep add(TraceStep step) {
        synchronized (this) {
            TraceStep numbered = new TraceStep(nextSeq++, step.kind(), step.tag(), step.title(), step.status(),
                    step.startedAt(), step.ms(), step.summary(), step.fields());
            steps.addLast(numbered);
            fieldBytes += weight(numbered);
            while (steps.size() > LIMIT || (fieldBytes > FIELD_BUDGET && steps.size() > 1)) {
                TraceStep dropped = steps.removeFirst();
                fieldBytes -= weight(dropped);
            }
            return numbered;
        }
    }

    /** The newest steps in order, at most {@code limit}, for the list view. */
    public synchronized List<TraceStep> since(int afterSeq, int limit) {
        List<TraceStep> out = new ArrayList<>();
        for (TraceStep s : steps) {
            if (s.seq() > afterSeq) out.add(s);
        }
        if (out.size() > limit) return out.subList(out.size() - limit, out.size());
        return out;
    }

    /** One step by its sequence number, or null when it has been dropped by the caps. */
    public synchronized TraceStep bySeq(int seq) {
        for (TraceStep s : steps) {
            if (s.seq() == seq) return s;
        }
        return null;
    }

    public synchronized int lastSeq() {
        return nextSeq - 1;
    }

    public synchronized int size() {
        return steps.size();
    }

    /** The list payload: what the page shows before anything is expanded. */
    public synchronized JsonArray summariesJson(int afterSeq, int limit) {
        JsonArray arr = new JsonArray();
        for (TraceStep s : since(afterSeq, limit)) arr.add(s.summaryJson());
        return arr;
    }

    /** Counters for the header: how much of each kind of work the job has done. */
    public synchronized JsonObject statsJson() {
        JsonObject o = new JsonObject();
        int llm = 0, failed = 0;
        long ms = 0;
        for (TraceStep s : steps) {
            if (s.kind() == TraceStep.Kind.LLM) llm++;
            if (s.status() == TraceStep.Status.FAILED) failed++;
            ms += s.ms();
        }
        o.addProperty("steps", steps.size());
        o.addProperty("llm", llm);
        o.addProperty("failed", failed);
        o.addProperty("ms", ms);
        o.addProperty("lastSeq", nextSeq - 1);
        return o;
    }

    /** Rendered as one markdown file, so a debug session can be attached to a bug report. */
    public String toMarkdown(String buildName, String buildId) {
        return toMarkdown(buildName, buildId, "");
    }

    /** Rendered as one markdown file, with the job's final outcome in the header when it is known. */
    public synchronized String toMarkdown(String buildName, String buildId, String outcome) {
        StringBuilder b = new StringBuilder();
        b.append("# AIBuild trace — ").append(buildName == null ? "" : buildName).append('\n');
        if (buildId != null) b.append("\n- build: `").append(buildId).append("`\n");
        if (outcome != null && !outcome.isEmpty()) b.append("- outcome: ").append(outcome).append('\n');
        b.append("- steps: ").append(steps.size()).append('\n');
        for (TraceStep s : steps) {
            b.append("\n## ").append(s.seq()).append(". ").append(s.title())
                    .append("  \n`").append(s.kind().name().toLowerCase()).append("` · ")
                    .append(s.status().name().toLowerCase()).append(" · ").append(s.ms()).append(" ms");
            if (!s.tag().isEmpty() && !s.tag().equals(s.title())) b.append(" · `").append(s.tag()).append('`');
            b.append('\n');
            if (!s.summary().isEmpty()) b.append('\n').append(s.summary()).append('\n');
            for (var e : s.fields().entrySet()) {
                b.append("\n### ").append(e.getKey()).append("\n\n```\n").append(e.getValue()).append("\n```\n");
            }
        }
        return b.toString();
    }

    /** Roughly the retained size of a step; the point is to bound growth, not to measure it exactly. */
    static long weight(TraceStep step) {
        long n = 200L + step.summary().length();
        for (var e : step.fields().entrySet()) n += e.getKey().length() + e.getValue().length() + 32L;
        return n;
    }
}
