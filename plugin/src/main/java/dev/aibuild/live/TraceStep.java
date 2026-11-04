package dev.aibuild.live;

import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One thing the pipeline did, with everything needed to explain it after the fact.
 *
 * <p>The live view answers "what is the build doing right now"; this answers "why did it do that, and where did
 * it go wrong". A step is recorded for every LLM call, every script run, every applied op batch and every model
 * change, and carries the actual inputs and outputs — the system and user prompt, the raw answer, the model's
 * reasoning, the traceback, the usage counters. That is a different order of magnitude from a log line, so the
 * detail is kept out of the event stream: {@link #summary()} rides on the {@code step} event and the rest is
 * fetched per step from {@code /live/<token>/step/<n>} when a person expands it.</p>
 *
 * <p>Fields are capped in {@link Builder}: a prompt that embeds the whole block list is large, and an unbounded
 * trace would keep every build's prompts in memory until the link expires.</p>
 */
public record TraceStep(
        int seq,
        Kind kind,
        String tag,
        String title,
        Status status,
        long startedAt,
        long ms,
        String summary,
        Map<String, String> fields) {

    /** What produced the step; the page groups and colours by this. */
    public enum Kind {
        LLM, SCRIPT, OPS, ANALYSIS, MODEL, PIPELINE
    }

    /** How it ended. {@code RUNNING} only exists while the call is in flight. */
    public enum Status {
        RUNNING, OK, FAILED, SKIPPED
    }

    /** Largest value kept per field; the rest is truncated with a marker so the page never lies about length. */
    static final int FIELD_LIMIT = 48_000;

    public TraceStep {
        tag = tag == null ? "" : tag;
        title = title == null ? tag : title;
        status = status == null ? Status.OK : status;
        summary = summary == null ? "" : summary;
        fields = fields == null ? Map.of() : Map.copyOf(fields);
    }

    /** The list entry: enough for a row in the debug panel without fetching the detail. */
    public JsonObject summaryJson() {
        JsonObject o = new JsonObject();
        o.addProperty("seq", seq);
        o.addProperty("kind", kind.name().toLowerCase());
        o.addProperty("tag", tag);
        o.addProperty("title", title);
        o.addProperty("status", status.name().toLowerCase());
        o.addProperty("startedAt", startedAt);
        o.addProperty("ms", ms);
        o.addProperty("summary", summary);
        o.addProperty("detailed", !fields.isEmpty());
        return o;
    }

    /** The whole step, including every recorded field. */
    public JsonObject toJson() {
        JsonObject o = summaryJson();
        JsonObject f = new JsonObject();
        for (Map.Entry<String, String> e : fields.entrySet()) f.addProperty(e.getKey(), e.getValue());
        o.add("fields", f);
        return o;
    }

    /** Accumulates a step while the work runs, then freezes it. */
    public static final class Builder {
        private final Kind kind;
        private final String tag;
        private String title;
        private Status status = Status.RUNNING;
        private final long startedAt = System.currentTimeMillis();
        private long ms;
        private String summary = "";
        private final Map<String, String> fields = new LinkedHashMap<>();

        public Builder(Kind kind, String tag) {
            this.kind = kind;
            this.tag = tag;
            this.title = tag;
        }

        public Builder title(String t) {
            this.title = t;
            return this;
        }

        public Builder summary(String s) {
            this.summary = s == null ? "" : s;
            return this;
        }

        public Builder status(Status s) {
            this.status = s;
            return this;
        }

        public Builder ok(boolean success) {
            this.status = success ? Status.OK : Status.FAILED;
            return this;
        }

        /** Adds a section; null and blank values are dropped so the page shows only what exists. */
        public Builder field(String name, String value) {
            if (value == null || value.isEmpty()) return this;
            fields.put(name, value.length() <= FIELD_LIMIT ? value
                    : value.substring(0, FIELD_LIMIT) + "\n… (" + (value.length() - FIELD_LIMIT) + " more chars)");
            return this;
        }

        /** Adds a section only when the caller has one to add. */
        public Builder field(String name, Object value) {
            return value == null ? this : field(name, String.valueOf(value));
        }

        /** Freezes the step, filling in the elapsed time. */
        public TraceStep build(int seq) {
            return new TraceStep(seq, kind, tag, title, status, startedAt, ms > 0 ? ms : System.currentTimeMillis() - startedAt,
                    summary, fields);
        }

        /** Records how long the step took, when the caller measured it itself (an LLM call reports its own). */
        public Builder took(long millis) {
            this.ms = Math.max(0, millis);
            return this;
        }
    }
}
