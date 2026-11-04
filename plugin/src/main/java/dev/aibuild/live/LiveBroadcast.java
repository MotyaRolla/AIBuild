package dev.aibuild.live;

import com.google.gson.JsonObject;

/**
 * Receives the pipeline's live observations of one job.
 *
 * <p>Every method must be cheap, non-blocking and safe to call from any thread: the pipeline calls them
 * straight from worker and transport I/O threads. Implementations hand the work to their own executor and
 * never throw (a broken viewer must not disturb a build). {@link #NOOP} is the receiver used when the live
 * view is off or the build has no viewer.</p>
 */
public interface LiveBroadcast {

    /** A coarse pipeline stage, e.g. {@code "program"} with a human title. Also updates {@code progress} (0..1). */
    void stage(String stage, String title, double progress);

    /** The model's answer text so far while a call streams ({@code kind} = {@code "program"}, {@code "critic"}…). */
    void code(String kind, CharSequence textSoFar);

    /** The streamed reasoning text so far of the call whose answer streams as {@code kind}. */
    void reasoning(String kind, CharSequence textSoFar);

    /** A streamed prefix of the program was run and shown. */
    void preview(boolean ok, int blocks, int length);

    /** The build model changed. {@code model} is only read during the call, so pass a finished instance. */
    void model(dev.aibuild.model.VoxelModel model, String why);

    /** Named parts and their sizes. */
    void parts(java.util.List<dev.aibuild.script.ScriptResult.Part> parts);

    /** One transcript line (LLM call, script run, analysis…). */
    void note(String tag, String text);

    /** One human-facing progress line, the same text the chat receives. */
    void say(String text);

    /** The job ended: {@code outcome} is one of {@code done}, {@code rejected}, {@code cancelled}, {@code failed}. */
    void finish(String outcome, String detail);

    /**
     * Records one debuggable step: an LLM call with its prompt, reasoning and answer, a script run with its
     * traceback, an op batch, a model change. The step is kept whole for later inspection, while only its
     * summary rides on the event stream.
     */
    void trace(TraceStep.Builder step);

    /**
     * A review stage ended: {@code kept} says whether its result became the build, {@code why} what decided it,
     * {@code quality} the measured defects of the build after it (in display order), {@code model} the build after
     * a kept stage (null for a rejected one), so a viewer can step back through the stages.
     */
    void verdict(String stage, String title, boolean kept, String why, java.util.Map<String, Integer> quality,
                 dev.aibuild.model.VoxelModel model);

    /** A receiver that does nothing. */
    LiveBroadcast NOOP = new LiveBroadcast() {
        @Override
        public void stage(String stage, String title, double progress) {
        }

        @Override
        public void code(String kind, CharSequence textSoFar) {
        }

        @Override
        public void reasoning(String kind, CharSequence textSoFar) {
        }

        @Override
        public void preview(boolean ok, int blocks, int length) {
        }

        @Override
        public void model(dev.aibuild.model.VoxelModel model, String why) {
        }

        @Override
        public void parts(java.util.List<dev.aibuild.script.ScriptResult.Part> parts) {
        }

        @Override
        public void note(String tag, String text) {
        }

        @Override
        public void say(String text) {
        }

        @Override
        public void finish(String outcome, String detail) {
        }

        @Override
        public void trace(TraceStep.Builder step) {
        }

        @Override
        public void verdict(String stage, String title, boolean kept, String why, java.util.Map<String, Integer> quality,
                            dev.aibuild.model.VoxelModel model) {
        }
    };

    /** Payload helper for implementations: {@code {text: …}} without repeating null handling. */
    static JsonObject text(String key, String value) {
        JsonObject o = new JsonObject();
        o.addProperty(key, value == null ? "" : value);
        return o;
    }
}
