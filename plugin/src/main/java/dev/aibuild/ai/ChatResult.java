package dev.aibuild.ai;

/**
 * The completed result of one LLM chat call.
 *
 * <p>Immutable and thread-safe. Token counters are non-negative; {@link #transport()} names the
 * transport that actually produced the answer ({@code "provider"} for the OpenAI-compatible
 * streaming endpoint, {@code "generate"} for the Command&nbsp;Code {@code /alpha/generate}
 * fallback).</p>
 *
 * @param content          the assistant answer (never {@code null}, possibly empty)
 * @param reasoning        the model's reasoning / thinking text, if the model exposes it (never {@code null})
 * @param finishReason     provider finish reason ({@code stop}, {@code length}, {@code eof}, {@code empty}, …)
 * @param promptTokens     input tokens billed for this call (0 if the provider did not report usage)
 * @param completionTokens output tokens billed for this call (0 if not reported)
 * @param cachedTokens     input tokens served from the provider's prompt cache (0 if not reported)
 * @param reasoningTokens  output tokens spent on reasoning (0 if not reported)
 * @param elapsedMs        wall-clock duration of the call in milliseconds
 * @param transport        name of the transport that produced the result
 */
public record ChatResult(String content,
                         String reasoning,
                         String finishReason,
                         int promptTokens,
                         int completionTokens,
                         int cachedTokens,
                         int reasoningTokens,
                         long elapsedMs,
                         String transport) {

    /** Canonicalises null / negative components. */
    public ChatResult {
        content = content == null ? "" : content;
        reasoning = reasoning == null ? "" : reasoning;
        finishReason = (finishReason == null || finishReason.isBlank()) ? "unknown" : finishReason.trim();
        promptTokens = Math.max(0, promptTokens);
        completionTokens = Math.max(0, completionTokens);
        cachedTokens = Math.max(0, cachedTokens);
        reasoningTokens = Math.max(0, reasoningTokens);
        elapsedMs = Math.max(0L, elapsedMs);
        transport = (transport == null || transport.isBlank()) ? "unknown" : transport.trim();
    }

    /** @return {@code promptTokens + completionTokens}. */
    public int totalTokens() {
        return promptTokens + completionTokens;
    }

    /** @return {@code true} if the model produced something other than whitespace. */
    public boolean hasContent() {
        return !content.isBlank();
    }

    /** @return a one-line, log-friendly summary (never contains more than ~160 characters of content). */
    public String summary() {
        String head = content.strip();
        if (head.length() > 120) {
            head = head.substring(0, 120) + "…";
        }
        return "transport=" + transport + ", finish=" + finishReason + ", tokens=" + promptTokens + "+"
                + completionTokens + " (cached=" + cachedTokens + ", reasoning=" + reasoningTokens + "), "
                + elapsedMs + " ms, chars=" + content.length() + (head.isEmpty() ? "" : ", head=\"" + head + "\"");
    }
}
