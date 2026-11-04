package dev.aibuild.pipeline;

import dev.aibuild.ai.ChatResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A max request answered without any thinking is asked again at high; short or thought-through answers are kept. */
class SkippedThinkingTest {
    private static ChatResult res(int completion, int reasoning, String text) {
        return new ChatResult("answer", text, "stop", 9000, completion, 0, reasoning, 5000, "provider");
    }

    @Test void aLongMaxAnswerWithNoReasoningIsRetried() {
        assertTrue(BuildPipeline.skippedThinking("max", res(1864, 0, ""), ""));
    }

    @Test void thoughtThroughOrShortAnswersAreKept() {
        assertFalse(BuildPipeline.skippedThinking("max", res(40000, 32000, "..."), ""), "it thought");
        assertFalse(BuildPipeline.skippedThinking("max", res(150, 0, ""), ""), "a short answer such as DONE");
        assertFalse(BuildPipeline.skippedThinking("max", res(1864, 0, ""), "streamed thoughts"), "reasoning text arrived");
        assertFalse(BuildPipeline.skippedThinking("high", res(1864, 0, ""), ""), "only max is checked");
    }
}
