package dev.aibuild.ai;

import com.google.gson.JsonArray;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One immutable, validated LLM chat request.
 *
 * <p>Instances are thread-safe and may be shared/reused between calls. The record's compact
 * constructor is defensive on purpose: the request is built from LLM-generated and config-driven
 * data, so malformed input (blank model id, empty message list, out-of-range token budget,
 * non-finite temperature, unknown reasoning effort) is repaired or rejected immediately with
 * {@link IllegalArgumentException} instead of failing deep inside a transport.</p>
 *
 * <p>A request may carry images (see {@link ChatMessage#user(String, List)}): the message list is
 * the only place where that lives, so every existing factory and accessor keeps its text-only
 * meaning and {@link #messagesJson()} is the single serialisation entry point for transports.</p>
 *
 * @param model           model id sent to the gateway, e.g. {@code "deepseek/deepseek-v4.1-flash"} (never blank)
 * @param messages        conversation, oldest first; must contain at least one message
 * @param maxTokens       upper bound for generated tokens, clamped to {@code [1, 200_000]}
 * @param temperature     sampling temperature in {@code [0, 2]} or {@code null} to use the provider default
 * @param reasoningEffort {@code "low"|"medium"|"high"|"max"} or {@code null} for the provider default
 * @param jsonMode        when {@code true}, ask for an OpenAI {@code response_format={"type":"json_object"}}
 */
public record ChatRequest(String model,
                          List<ChatMessage> messages,
                          int maxTokens,
                          Double temperature,
                          String reasoningEffort,
                          boolean jsonMode) {

    /** Model id used by AIBuild unless configured otherwise. */
    public static final String DEFAULT_MODEL = "deepseek/deepseek-v4.1-flash";
    /** Lowest accepted {@link #maxTokens()} value. */
    public static final int MIN_TOKENS = 1;
    /** Highest accepted {@link #maxTokens()} value; guards against absurd hand-written config. */
    public static final int MAX_TOKENS_LIMIT = 200_000;

    /** Values accepted by the Command Code gateway for DeepSeek V4.x ("off" disables thinking). */
    private static final Set<String> EFFORTS = Set.of("off", "low", "medium", "high", "xhigh", "max");

    /** Canonicalises and validates every component. */
    public ChatRequest {
        model = model == null ? "" : model.trim();
        if (model.isEmpty()) {
            model = DEFAULT_MODEL;
        }
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages must contain at least one message");
        }
        List<ChatMessage> copy = new ArrayList<>(messages.size());
        for (ChatMessage m : messages) {
            if (m == null) {
                throw new IllegalArgumentException("messages must not contain null elements");
            }
            copy.add(m);
        }
        messages = List.copyOf(copy);
        maxTokens = Math.clamp(maxTokens, MIN_TOKENS, MAX_TOKENS_LIMIT);
        if (temperature != null) {
            double t = temperature;
            if (Double.isNaN(t) || Double.isInfinite(t)) {
                throw new IllegalArgumentException("temperature must be finite, was " + t);
            }
            temperature = Math.clamp(t, 0.0d, 2.0d);
        }
        if (reasoningEffort != null) {
            String e = reasoningEffort.trim().toLowerCase(Locale.ROOT);
            reasoningEffort = (e.isEmpty() || !EFFORTS.contains(e)) ? null : e;
        }
    }

    /**
     * Convenience factory for a single-turn request with the default model, no temperature,
     * no reasoning effort and JSON mode off.
     *
     * @param prompt the user prompt
     * @param maxTokens token budget, clamped as documented on this record
     * @return a validated request
     */
    public static ChatRequest simple(String prompt, int maxTokens) {
        return new ChatRequest(DEFAULT_MODEL, List.of(ChatMessage.user(prompt)), maxTokens, null, null, false);
    }

    /**
     * Convenience factory for a system + user single-turn request.
     *
     * @param system  system instructions (may be {@code null}/{@code ""} to omit)
     * @param prompt  user prompt
     * @param maxTokens token budget, clamped as documented on this record
     * @param jsonMode whether to request a JSON object response
     * @return a validated request
     */
    public static ChatRequest of(String system, String prompt, int maxTokens, boolean jsonMode) {
        List<ChatMessage> msgs = new ArrayList<>(2);
        if (system != null && !system.isBlank()) {
            msgs.add(ChatMessage.system(system));
        }
        msgs.add(ChatMessage.user(prompt));
        return new ChatRequest(DEFAULT_MODEL, msgs, maxTokens, null, null, jsonMode);
    }

    /**
     * Convenience factory for a system + user single-turn request whose user message carries PNG
     * images — the shape of a vision critique ("here are the renders of your design, judge them").
     *
     * @param system    system instructions (may be {@code null}/{@code ""} to omit)
     * @param prompt    user prompt describing the pictures
     * @param images    PNG files in display order; {@code null}/empty yields a text-only request
     * @param maxTokens token budget, clamped as documented on this record
     * @param jsonMode  whether to request a JSON object response
     * @return a validated request
     * @throws IllegalArgumentException if an image is {@code null} or empty
     */
    public static ChatRequest vision(String system, String prompt, List<byte[]> images,
                                     int maxTokens, boolean jsonMode) {
        List<ChatMessage> msgs = new ArrayList<>(2);
        if (system != null && !system.isBlank()) {
            msgs.add(ChatMessage.system(system));
        }
        msgs.add(ChatMessage.user(prompt, images));
        return new ChatRequest(DEFAULT_MODEL, msgs, maxTokens, null, null, jsonMode);
    }

    /** @return a copy of this request with the given model id. */
    public ChatRequest withModel(String newModel) {
        return new ChatRequest(newModel, messages, maxTokens, temperature, reasoningEffort, jsonMode);
    }

    /** @return a copy of this request with the given token budget. */
    public ChatRequest withMaxTokens(int newMaxTokens) {
        return new ChatRequest(model, messages, newMaxTokens, temperature, reasoningEffort, jsonMode);
    }

    /** @return a copy of this request with the given reasoning effort ({@code null} to clear it). */
    public ChatRequest withReasoningEffort(String effort) {
        return new ChatRequest(model, messages, maxTokens, temperature, effort, jsonMode);
    }

    /** @return a copy of this request with JSON mode toggled. */
    public ChatRequest withJsonMode(boolean enabled) {
        return new ChatRequest(model, messages, maxTokens, temperature, reasoningEffort, enabled);
    }

    /** @return the concatenated text of all system messages, separated by blank lines (never {@code null}). */
    public String systemPrompt() {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : messages) {
            if (m.isSystem()) {
                if (!sb.isEmpty()) {
                    sb.append("\n\n");
                }
                sb.append(m.content());
            }
        }
        return sb.toString();
    }

    /** @return the number of characters of user + assistant text (used for cheap request logging). */
    public int conversationChars() {
        int n = 0;
        for (ChatMessage m : messages) {
            n += m.content().length();
        }
        return n;
    }

    /**
     * Serialises the conversation into the OpenAI {@code messages} array.
     *
     * <p>This is the single place where a message becomes wire JSON, so both transports encode a
     * vision message identically. Text-only messages keep the plain string form.</p>
     *
     * @return a fresh JSON array, oldest message first, never {@code null}
     */
    public JsonArray messagesJson() {
        JsonArray arr = new JsonArray(messages.size());
        for (ChatMessage m : messages) {
            arr.add(m.toJson());
        }
        return arr;
    }

    /** @return {@code true} when at least one message carries an image. */
    public boolean hasImages() {
        for (ChatMessage m : messages) {
            if (m.hasImages()) {
                return true;
            }
        }
        return false;
    }

    /** @return the total number of attached images across all messages. */
    public int imageCount() {
        int n = 0;
        for (ChatMessage m : messages) {
            n += m.imageCount();
        }
        return n;
    }

    /**
     * Returns a copy of this request with every message limited to {@code maxImagesPerMessage}
     * images, keeping the first ones in order.
     *
     * <p>A vision request is easy to make too expensive: each render is billed as prompt tokens and
     * base64 inflates it by a third. Callers use this as the last guard before sending, typically
     * with the number of views the renderer was asked for. Messages that already fit are reused
     * unchanged, so the text-only path allocates nothing.</p>
     *
     * @param maxImagesPerMessage image budget per message; negative values are treated as zero
     * @return this request when nothing had to be truncated, otherwise an equivalent copy
     */
    public ChatRequest withImageLimit(int maxImagesPerMessage) {
        int limit = Math.max(0, maxImagesPerMessage);
        boolean changed = false;
        List<ChatMessage> limited = new ArrayList<>(messages.size());
        for (ChatMessage m : messages) {
            if (m.imageCount() > limit) {
                ChatContent trimmed = m.contentParts().imageParts(limit);
                limited.add(new ChatMessage(m.role(), trimmed.text(), trimmed));
                changed = true;
            } else {
                limited.add(m);
            }
        }
        if (!changed) {
            return this;
        }
        return new ChatRequest(model, limited, maxTokens, temperature, reasoningEffort, jsonMode);
    }
}
