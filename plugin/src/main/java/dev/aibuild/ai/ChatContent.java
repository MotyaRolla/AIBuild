package dev.aibuild.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * Ordered, immutable payload of one chat message: text parts and inline PNG image parts.
 *
 * <p>OpenAI-compatible gateways accept two content shapes for a message:</p>
 * <ul>
 *   <li>a plain JSON string — {@code "content": "hello"} — for text-only messages;</li>
 *   <li>an array of typed parts — {@code [{"type":"text","text":"…"},
 *       {"type":"image_url","image_url":{"url":"data:image/png;base64,…"}}]} — when the model must
 *       <em>see</em> pictures (renders of its own build).</li>
 * </ul>
 *
 * <p>This type models the second shape and keeps the order the caller wrote. The first shape is kept
 * for text-only content so that adding vision support cannot change any existing request: a content
 * that holds no image serialises to a plain string, exactly like before.</p>
 *
 * <p>Instances are immutable and therefore thread-safe. Image bytes are copied on the way in and on
 * the way out, so a caller cannot mutate a request after it was built (and a request cannot mutate
 * the caller's buffers).</p>
 *
 * <p>Vision requests are <b>large</b>: base64 inflates a PNG by a third and the bytes are billed as
 * prompt tokens. Downscaling and frame selection are the renderer's job; within this type
 * {@link #imageParts(int)} drops images beyond a caller-supplied budget.</p>
 */
public final class ChatContent {

    /** Prefix of an inline PNG data URL understood by OpenAI-compatible gateways. */
    public static final String PNG_DATA_URL_PREFIX = "data:image/png;base64,";

    /** One ordered element of a message: either {@link TextPart} or {@link ImagePart}. */
    public sealed interface Part permits TextPart, ImagePart {

        /** @return {@code true} for an image part, {@code false} for a text part. */
        boolean isImage();
    }

    /**
     * A text fragment of a message.
     *
     * @param text the fragment, never {@code null} ({@code null} becomes {@code ""})
     */
    public record TextPart(String text) implements Part {

        /** Canonicalises the fragment. */
        public TextPart {
            text = text == null ? "" : text;
        }

        @Override
        public boolean isImage() {
            return false;
        }
    }

    /**
     * A PNG image carried inline as a base64 data URL.
     *
     * <p>The record defends its byte array: the constructor clones it and {@link #png()} returns a
     * fresh clone on every call, so neither the caller nor the gateway can mutate shared state.
     * Equality compares the bytes, not the array identity.</p>
     *
     * @param png the encoded PNG file bytes (never {@code null} or empty)
     */
    public record ImagePart(byte[] png) implements Part {

        /**
         * Defensively copies the image bytes.
         *
         * @throws IllegalArgumentException if {@code png} is {@code null} or empty
         */
        public ImagePart {
            if (png == null || png.length == 0) {
                throw new IllegalArgumentException("png must not be null or empty");
            }
            png = png.clone();
        }

        /** @return a fresh copy of the PNG bytes, never {@code null} */
        @Override
        public byte[] png() {
            return png.clone();
        }

        @Override
        public boolean isImage() {
            return true;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ImagePart that && Arrays.equals(this.png, that.png);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(png);
        }

        @Override
        public String toString() {
            return "ImagePart[png=" + png.length + " bytes]";
        }
    }

    private final List<Part> parts;

    private ChatContent(List<Part> parts) {
        this.parts = List.copyOf(parts);
    }

    /**
     * Creates text-only content.
     *
     * @param text the message text, may be {@code null} (treated as {@code ""})
     * @return content holding one text part
     */
    public static ChatContent text(String text) {
        return new ChatContent(List.of(new TextPart(text)));
    }

    /**
     * Creates content from a leading text and a list of PNG images in that order.
     *
     * <p>An empty image list produces exactly {@link #text(String)}. When images are present but
     * {@code text} is {@code null} or empty, no text part is added at all (an images-only message,
     * which the gateway accepts). The images appear in list order after the text.</p>
     *
     * @param text  the message text, may be {@code null}
     * @param pngs  PNG files to attach, may be {@code null} (= no images); elements must not be
     *              {@code null} or empty
     * @return immutable content
     * @throws IllegalArgumentException if an image element is {@code null} or empty
     */
    public static ChatContent of(String text, List<byte[]> pngs) {
        if (pngs == null || pngs.isEmpty()) {
            return text(text);
        }
        List<Part> parts = new ArrayList<>(pngs.size() + 1);
        if (text != null && !text.isEmpty()) {
            parts.add(new TextPart(text));
        }
        for (byte[] png : pngs) {
            parts.add(new ImagePart(png));
        }
        return new ChatContent(parts);
    }

    /**
     * Creates content from an explicit ordered list of parts.
     *
     * @param parts the parts in message order; {@code null} or empty means empty text content
     * @return immutable content
     * @throws IllegalArgumentException if the list contains {@code null}
     */
    public static ChatContent ofParts(List<Part> parts) {
        if (parts == null || parts.isEmpty()) {
            return text("");
        }
        for (Part part : parts) {
            if (part == null) {
                throw new IllegalArgumentException("parts must not contain null elements");
            }
        }
        return new ChatContent(parts);
    }

    /** @return a builder for content that interleaves text and images in a caller-chosen order. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Mutable builder for mixed-order content.
     *
     * <p>Parts are appended in call order; nothing is reordered or merged. A builder that never
     * receives a part builds {@link #text(String) text("")}, so an accidental empty build still
     * serialises as a plain string and cannot produce {@code "content": []}.</p>
     */
    public static final class Builder {

        private final List<Part> parts = new ArrayList<>(4);

        private Builder() {
        }

        /**
         * Appends a text part.
         *
         * @param text the fragment, may be {@code null} (treated as {@code ""})
         * @return this builder
         */
        public Builder text(String text) {
            parts.add(new TextPart(text));
            return this;
        }

        /**
         * Appends a PNG image part.
         *
         * @param png the PNG bytes, must not be {@code null} or empty
         * @return this builder
         * @throws IllegalArgumentException if {@code png} is {@code null} or empty
         */
        public Builder image(byte[] png) {
            parts.add(new ImagePart(png));
            return this;
        }

        /**
         * Appends an already-built part.
         *
         * @param part the part, must not be {@code null}
         * @return this builder
         * @throws IllegalArgumentException if {@code part} is {@code null}
         */
        public Builder part(Part part) {
            if (part == null) {
                throw new IllegalArgumentException("part must not be null");
            }
            parts.add(part);
            return this;
        }

        /** @return immutable content with the parts appended so far */
        public ChatContent build() {
            return parts.isEmpty() ? ChatContent.text("") : new ChatContent(parts);
        }
    }

    /** @return the parts in message order, never {@code null} and never empty. */
    public List<Part> parts() {
        return parts;
    }

    /** @return the number of parts. */
    public int size() {
        return parts.size();
    }

    /** @return {@code true} when no part is an image (and therefore serialises as a plain string). */
    public boolean isTextOnly() {
        for (Part part : parts) {
            if (part.isImage()) {
                return false;
            }
        }
        return true;
    }

    /** @return {@code true} when at least one image is attached. */
    public boolean hasImages() {
        return !isTextOnly();
    }

    /** @return the number of image parts. */
    public int imageCount() {
        int n = 0;
        for (Part part : parts) {
            if (part.isImage()) {
                n++;
            }
        }
        return n;
    }

    /**
     * Returns the text of this content, concatenated without a separator.
     *
     * <p>Used by the text-oriented APIs ({@link ChatMessage#content()}, system prompt extraction,
     * logging) so that adding images cannot change their behaviour.</p>
     *
     * @return the concatenated text, never {@code null}
     */
    public String text() {
        StringBuilder sb = new StringBuilder();
        for (Part part : parts) {
            if (part instanceof TextPart t) {
                sb.append(t.text());
            }
        }
        return sb.toString();
    }

    /** @return copies of the image bytes in message order, never {@code null}. */
    public List<byte[]> images() {
        List<byte[]> out = new ArrayList<>(imageCount());
        for (Part part : parts) {
            if (part instanceof ImagePart img) {
                out.add(img.png());
            }
        }
        return List.copyOf(out);
    }

    /**
     * Returns content limited to at most {@code maxImages} images, keeping the first ones in order.
     *
     * <p>Text parts are never dropped and the relative order of what remains is unchanged. When the
     * budget is zero or the content becomes text-only, the result serialises as a plain string
     * again. This is the cheap guard against sending a dozen full-size renders in one request; the
     * caller still owns picking the most useful views and downscaling them.</p>
     *
     * @param maxImages maximum number of image parts to keep; negative values are treated as zero
     * @return this content when it already fits the budget, otherwise a truncated copy
     */
    public ChatContent imageParts(int maxImages) {
        int limit = Math.max(0, maxImages);
        if (imageCount() <= limit) {
            return this;
        }
        List<Part> kept = new ArrayList<>(parts.size());
        int seen = 0;
        for (Part part : parts) {
            if (part.isImage()) {
                if (seen >= limit) {
                    continue;
                }
                seen++;
            }
            kept.add(part);
        }
        return kept.isEmpty() ? text("") : new ChatContent(kept);
    }

    /**
     * Serialises this content into the JSON value the OpenAI gateway expects: a plain string for
     * text-only content, an array of typed parts when images are present.
     *
     * @return a {@link JsonPrimitive} or a {@link JsonArray}, never {@code null}
     */
    public JsonElement toJson() {
        return hasImages() ? toPartsJson() : new JsonPrimitive(text());
    }

    /**
     * Serialises this content as an array of typed parts, whether or not images are present.
     *
     * <p>Used by transports whose envelope always uses the array form (the {@code /alpha/generate}
     * body), where a text-only message must still become {@code [{"type":"text","text":"…"}]}.</p>
     *
     * @return a fresh JSON array of {@code text} / {@code image_url} parts, never {@code null}
     */
    public JsonArray toPartsJson() {
        JsonArray array = new JsonArray();
        for (Part part : parts) {
            if (part instanceof TextPart t) {
                JsonObject o = new JsonObject();
                o.addProperty("type", "text");
                o.addProperty("text", t.text());
                array.add(o);
            } else if (part instanceof ImagePart img) {
                JsonObject url = new JsonObject();
                url.addProperty("url", PNG_DATA_URL_PREFIX + Base64.getEncoder().encodeToString(img.png()));
                JsonObject o = new JsonObject();
                o.addProperty("type", "image_url");
                o.add("image_url", url);
                array.add(o);
            }
        }
        return array;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ChatContent that && parts.equals(that.parts);
    }

    @Override
    public int hashCode() {
        return parts.hashCode();
    }

    /** @return a short description; image bytes are never printed (they are huge and base64). */
    @Override
    public String toString() {
        return "ChatContent[parts=" + parts.size() + ", images=" + imageCount() + "]";
    }
}
