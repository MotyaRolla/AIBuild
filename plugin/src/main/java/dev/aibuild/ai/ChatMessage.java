package dev.aibuild.ai;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Locale;

/**
 * One message of an OpenAI-style chat conversation, optionally carrying inline PNG images.
 *
 * <p>Immutable and therefore thread-safe. The {@code role} is normalised in the canonical
 * lower-case form ({@code system}, {@code user}, {@code assistant}, {@code tool}); a missing or
 * blank role is rejected because it can never produce a valid request. A {@code null} content is
 * treated as the empty string — LLM-facing code frequently builds messages from optional text.</p>
 *
 * <p>The payload is a {@link ChatContent}: an ordered list of text and image parts. The plain-text
 * view stays available through {@link #content()}, and a message without images serialises to the
 * same JSON string as before this type grew vision support. Only a message that actually holds an
 * image serialises its {@code content} as an array of typed parts:</p>
 *
 * <pre>{@code
 * {"role":"user","content":"look at this"}
 * {"role":"user","content":[{"type":"text","text":"look at this"},
 *                          {"type":"image_url","image_url":{"url":"data:image/png;base64,..."}}]}
 * }</pre>
 *
 * <p>The canonical constructor takes the payload explicitly; the two-argument constructor keeps
 * every existing call site source-compatible and produces text-only content.</p>
 *
 * @param role         the speaker, normalised to lower case (never blank)
 * @param content      the concatenated text of the payload (never {@code null})
 * @param contentParts the ordered payload (never {@code null}, always consistent with {@code content})
 */
public record ChatMessage(String role, String content, ChatContent contentParts) {

    /** Role of a system / instruction message. */
    public static final String ROLE_SYSTEM = "system";
    /** Role of an end-user message. */
    public static final String ROLE_USER = "user";
    /** Role of a model reply. */
    public static final String ROLE_ASSISTANT = "assistant";

    /**
     * Canonicalises the components.
     *
     * <p>{@code content} is always derived from the payload, so the two views of the message cannot
     * disagree even when a caller passes a stale text next to images.</p>
     *
     * @throws IllegalArgumentException if {@code role} is {@code null} or blank
     */
    public ChatMessage {
        role = role == null ? "" : role.trim().toLowerCase(Locale.ROOT);
        if (role.isEmpty()) {
            throw new IllegalArgumentException("role must not be blank");
        }
        ChatContent parts = contentParts == null ? ChatContent.text(content) : contentParts;
        content = parts.text();
        contentParts = parts;
    }

    /**
     * Creates a text-only message.
     *
     * @param role    the speaker; must not be blank
     * @param content the message text, may be {@code null}
     */
    public ChatMessage(String role, String content) {
        this(role, content, content == null ? ChatContent.text("") : ChatContent.text(content));
    }

    /** Creates a {@code system} message. */
    public static ChatMessage system(String content) {
        return new ChatMessage(ROLE_SYSTEM, content);
    }

    /** Creates a text-only {@code user} message. */
    public static ChatMessage user(String content) {
        return new ChatMessage(ROLE_USER, content);
    }

    /**
     * Creates a {@code user} message with PNG images attached after the text.
     *
     * <p>This is the vision form of {@link #user(String)}: the renderer hands its views to the model
     * together with the critique prompt. An empty (or {@code null}) image list produces exactly the
     * text-only message, so callers do not need a branch for "no renders this round".</p>
     *
     * @param text   the prompt text, may be {@code null}
     * @param images PNG files in display order, may be {@code null}; elements must not be empty
     * @return an immutable user message
     * @throws IllegalArgumentException if an image is {@code null} or empty
     */
    public static ChatMessage user(String text, List<byte[]> images) {
        return new ChatMessage(ROLE_USER, text, ChatContent.of(text, images));
    }

    /** Creates an {@code assistant} message. */
    public static ChatMessage assistant(String content) {
        return new ChatMessage(ROLE_ASSISTANT, content);
    }

    /** @return {@code true} if this message carries system instructions. */
    public boolean isSystem() {
        return ROLE_SYSTEM.equals(role);
    }

    /** @return {@code true} if this message has at least one image attached. */
    public boolean hasImages() {
        return contentParts.hasImages();
    }

    /** @return the number of attached images. */
    public int imageCount() {
        return contentParts.imageCount();
    }

    /**
     * Serialises this message into the OpenAI chat-completions message shape.
     *
     * <p>The {@code content} member is a plain JSON string for text-only messages and an array of
     * typed parts when images are attached — exactly the two forms the gateway documents.</p>
     *
     * @return a fresh JSON object, never {@code null}
     */
    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.add("content", contentParts.toJson());
        return o;
    }
}
