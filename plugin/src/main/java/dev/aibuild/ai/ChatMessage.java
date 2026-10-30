package dev.aibuild.ai;

import java.util.Locale;

/**
 * One message of an OpenAI-style chat conversation.
 *
 * <p>Immutable and therefore thread-safe. The {@code role} is normalised in the canonical
 * lower-case form ({@code system}, {@code user}, {@code assistant}, {@code tool}); a missing or
 * blank role is rejected because it can never produce a valid request. A {@code null} content is
 * treated as the empty string — LLM-facing code frequently builds messages from optional text.</p>
 *
 * @param role    the speaker, normalised to lower case (never blank)
 * @param content the message text (never {@code null})
 */
public record ChatMessage(String role, String content) {

    /** Role of a system / instruction message. */
    public static final String ROLE_SYSTEM = "system";
    /** Role of an end-user message. */
    public static final String ROLE_USER = "user";
    /** Role of a model reply. */
    public static final String ROLE_ASSISTANT = "assistant";

    /**
     * Canonicalises the components.
     *
     * @throws IllegalArgumentException if {@code role} is {@code null} or blank
     */
    public ChatMessage {
        role = role == null ? "" : role.trim().toLowerCase(Locale.ROOT);
        if (role.isEmpty()) {
            throw new IllegalArgumentException("role must not be blank");
        }
        content = content == null ? "" : content;
    }

    /** Creates a {@code system} message. */
    public static ChatMessage system(String content) {
        return new ChatMessage(ROLE_SYSTEM, content);
    }

    /** Creates a {@code user} message. */
    public static ChatMessage user(String content) {
        return new ChatMessage(ROLE_USER, content);
    }

    /** Creates an {@code assistant} message. */
    public static ChatMessage assistant(String content) {
        return new ChatMessage(ROLE_ASSISTANT, content);
    }

    /** @return {@code true} if this message carries system instructions. */
    public boolean isSystem() {
        return ROLE_SYSTEM.equals(role);
    }
}
