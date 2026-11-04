package dev.aibuild.live;

import com.google.gson.JsonObject;

/**
 * One observation of a running job, queued for the live page.
 *
 * <p>The pipeline stays free of web types: it calls the {@link LiveBroadcast} methods with primitives and a
 * {@link LiveSession} turns them into these events, which are encoded to Server-Sent-Events frames by
 * {@link LiveViewServer}. Immutable.</p>
 *
 * @param type     event name, e.g. {@code stage}, {@code code}, {@code model}
 * @param seq      per-session sequence number (0 for session-level events that are not journaled)
 * @param atMillis wall clock of the observation (epoch millis)
 * @param data     the event payload; never null
 */
public record LiveEvent(String type, long seq, long atMillis, JsonObject data) {

    public static final String STAGE = "stage";
    /** Whole answer text so far: {@code {kind, text}} — the partially written build program. */
    public static final String CODE = "code";
    /** Streamed reasoning text: {@code {text}}. */
    public static final String THINK = "think";
    /** A run of the program so far: {@code {ok, blocks, length}}; {@code ok} is false when it stopped. */
    public static final String PREVIEW = "preview";
    /** The build model changed: {@code {why, url, at, cells}} — the pack itself is fetched from {@code url}. */
    public static final String MODEL = "model";
    /** Named parts of the current model: {@code {parts:[{name,cells,bounds}]}}. */
    public static final String PARTS = "parts";
    /** One transcript line: {@code {tag, text}}. */
    public static final String NOTE = "note";
    /** A chat/console progress line: {@code {text}}. */
    public static final String SAY = "say";
    /** The job ended: {@code {outcome, detail}}; outcome is done, rejected, cancelled or failed. */
    public static final String FINISH = "finish";
    /**
     * A debuggable step finished: {@code {seq, kind, tag, title, status, startedAt, ms, summary, detailed, stats}}.
     * The step's inputs and outputs are fetched from {@code /live/<token>/step/<seq>} only when expanded.
     */
    public static final String STEP = "step";

    public LiveEvent {
        type = type == null ? "note" : type;
        data = data == null ? new JsonObject() : data;
        atMillis = atMillis <= 0 ? System.currentTimeMillis() : atMillis;
    }

    static LiveEvent of(String type, long seq, JsonObject data) {
        return new LiveEvent(type, seq, System.currentTimeMillis(), data);
    }
}
