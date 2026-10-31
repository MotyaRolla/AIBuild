package dev.aibuild.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the wire format of vision requests.
 *
 * <p>These tests pin the one property that must not regress: a text-only message serialises exactly
 * as it did before vision support existed ({@code "content": "…"}), and only a message with images
 * becomes an array of {@code text} / {@code image_url} parts. If that breaks, every existing prompt
 * silently changes shape — or worse, stops being accepted by the gateway.</p>
 *
 * <p>All assertions run against the JSON that the transports really build: the provider body is
 * produced by {@link OpenAiCompatClient#buildBody(ChatRequest)} and parsed back with Gson, so a
 * mistake in the serialiser cannot be hidden by an intermediate helper.</p>
 */
class ChatContentCheck {

    /** A tiny valid PNG header + body; the transports never decode it, they only base64 it. */
    private static byte[] png(int seed) {
        byte[] head = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        byte[] body = ("pixel-" + seed).getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[head.length + body.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(body, 0, out, head.length, body.length);
        return out;
    }

    /** Parses a serialised message back into JSON. */
    private static JsonObject messageJson(ChatMessage message) {
        return JsonParser.parseString(LlmJson.GSON.toJson(message.toJson())).getAsJsonObject();
    }

    /** Builds the exact body the provider transport would POST. */
    private static JsonObject providerBody(ChatRequest request) {
        String json = new OpenAiCompatClient("http://localhost:1/provider/v1", "k", null, null, null)
                .buildBody(request);
        return JsonParser.parseString(json).getAsJsonObject();
    }

    // ------------------------------------------------------------------ text-only stays string

    @Test
    void textOnlyMessageSerialisesAsAPlainString() {
        ChatMessage message = ChatMessage.user("design a cottage");
        JsonObject json = messageJson(message);
        assertEquals("user", json.get("role").getAsString());
        assertTrue(json.get("content").isJsonPrimitive(), "text-only content must stay a JSON string");
        assertTrue(json.get("content").getAsJsonPrimitive().isString());
        assertEquals("design a cottage", json.get("content").getAsString());
        assertFalse(message.hasImages());
        assertEquals(0, message.imageCount());
        assertEquals(ChatContent.class, message.contentParts().getClass());
        // Byte-level check: the old wire shape had exactly these two members.
        assertEquals("{\"role\":\"user\",\"content\":\"design a cottage\"}", LlmJson.GSON.toJson(json));
    }

    @Test
    void textOnlyRequestBodyMatchesTheLegacyShape() {
        ChatRequest request = new ChatRequest("m", List.of(ChatMessage.system("be brief"),
                ChatMessage.user("hi")), 128, 0.2, "high", true);
        JsonObject body = providerBody(request);
        JsonArray messages = body.getAsJsonArray("messages");
        assertEquals(2, messages.size());
        for (JsonElement element : messages) {
            JsonObject message = element.getAsJsonObject();
            assertTrue(message.get("content").isJsonPrimitive(),
                    "no message may become an array in a text-only request");
            assertTrue(message.get("content").getAsJsonPrimitive().isString());
        }
        assertEquals("be brief", messages.get(0).getAsJsonObject().get("content").getAsString());
        assertEquals("hi", messages.get(1).getAsJsonObject().get("content").getAsString());
        assertFalse(request.hasImages());
        assertEquals(0, request.imageCount());
    }

    @Test
    void emptyImageListBehavesLikeTextOnly() {
        ChatMessage viaNull = ChatMessage.user("same", null);
        ChatMessage viaEmpty = ChatMessage.user("same", List.of());
        assertEquals(ChatMessage.user("same"), viaNull);
        assertEquals(ChatMessage.user("same"), viaEmpty);
        assertTrue(messageJson(viaEmpty).get("content").isJsonPrimitive());
        assertEquals("same", messageJson(viaNull).get("content").getAsString());
        assertFalse(viaEmpty.hasImages());
        // ChatContent.of with no images is ChatContent.text.
        assertEquals(ChatContent.text("same"), ChatContent.of("same", List.of()));
        assertEquals(ChatContent.text("same"), ChatContent.of("same", null));
    }

    // ------------------------------------------------------------------ images become parts

    @Test
    void twoImagesSerialiseToOneTextPartAndTwoImageParts() {
        byte[] first = png(1);
        byte[] second = png(2);
        ChatMessage message = ChatMessage.user("critique these renders", List.of(first, second));
        JsonObject json = messageJson(message);
        assertEquals("user", json.get("role").getAsString());
        assertEquals(2, message.imageCount());
        assertTrue(message.hasImages());

        JsonElement content = json.get("content");
        assertTrue(content.isJsonArray(), "content with images must be an array of parts");
        JsonArray parts = content.getAsJsonArray();
        assertEquals(3, parts.size(), "one text part followed by two image parts");

        JsonObject textPart = parts.get(0).getAsJsonObject();
        assertEquals("text", textPart.get("type").getAsString());
        assertEquals("critique these renders", textPart.get("text").getAsString());

        for (int i = 1; i <= 2; i++) {
            JsonObject imagePart = parts.get(i).getAsJsonObject();
            assertEquals("image_url", imagePart.get("type").getAsString());
            JsonObject imageUrl = imagePart.getAsJsonObject("image_url");
            assertNotNull(imageUrl, "image part must wrap an image_url object");
            String url = imageUrl.get("url").getAsString();
            assertTrue(url.startsWith("data:image/png;base64,"),
                    "url must be an inline PNG data URL, was: " + url.substring(0, Math.min(40, url.length())));
            // The payload after the prefix is plain base64 of the original bytes.
            byte[] decoded = Base64.getDecoder().decode(url.substring("data:image/png;base64,".length()));
            assertArrayEquals(i == 1 ? first : second, decoded);
        }
    }

    @Test
    void imageOnlyMessageHasNoTextPart() {
        ChatMessage message = ChatMessage.user(null, List.of(png(7)));
        JsonArray parts = messageJson(message).getAsJsonArray("content");
        assertEquals(1, parts.size());
        assertEquals("image_url", parts.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("", message.content());
    }

    @Test
    void base64RoundTripsThroughSerialisation() {
        byte[] original = png(42);
        ChatMessage message = ChatMessage.user("look", List.of(original));
        JsonObject imagePart = messageJson(message).getAsJsonArray("content").get(1).getAsJsonObject();
        String url = imagePart.getAsJsonObject("image_url").get("url").getAsString();
        String encoded = url.substring("data:image/png;base64,".length());
        assertArrayEquals(original, Base64.getDecoder().decode(encoded));
        // And back inside the model: images() returns the very same bytes.
        List<byte[]> images = message.contentParts().images();
        assertEquals(1, images.size());
        assertArrayEquals(original, images.get(0));
    }

    @Test
    void builderPreservesMixedOrder() {
        byte[] a = png(1);
        byte[] b = png(2);
        ChatContent content = ChatContent.builder()
                .text("before")
                .image(a)
                .text("between")
                .image(b)
                .text("after")
                .build();
        assertEquals(5, content.size());
        assertEquals(2, content.imageCount());
        assertEquals("beforebetweenafter", content.text());

        JsonArray parts = content.toJson().getAsJsonArray();
        assertEquals(5, parts.size());
        assertEquals(List.of("text", "image_url", "text", "image_url", "text"), types(parts));
        assertArrayEquals(a, content.images().get(0));
        assertArrayEquals(b, content.images().get(1));
        assertEquals("before", parts.get(0).getAsJsonObject().get("text").getAsString());
        assertEquals("between", parts.get(2).getAsJsonObject().get("text").getAsString());
        assertEquals("after", parts.get(4).getAsJsonObject().get("text").getAsString());
    }

    @Test
    void ofKeepsImageOrder() {
        byte[] a = png(1);
        byte[] b = png(2);
        byte[] c = png(3);
        ChatContent content = ChatContent.of("views", List.of(a, b, c));
        JsonArray parts = content.toJson().getAsJsonArray();
        assertEquals(4, parts.size());
        for (int i = 0; i < 3; i++) {
            String url = parts.get(i + 1).getAsJsonObject().getAsJsonObject("image_url").get("url").getAsString();
            byte[] decoded = Base64.getDecoder().decode(url.substring("data:image/png;base64,".length()));
            assertArrayEquals(content.images().get(i), decoded, "image " + i + " is in list order");
        }
        assertArrayEquals(a, content.images().get(0));
        assertArrayEquals(b, content.images().get(1));
        assertArrayEquals(c, content.images().get(2));
    }

    private static List<String> types(JsonArray parts) {
        List<String> out = new ArrayList<>(parts.size());
        for (JsonElement part : parts) {
            out.add(part.getAsJsonObject().get("type").getAsString());
        }
        return out;
    }

    // ------------------------------------------------------------------ immutability / guard rails

    @Test
    void contentIsImmutableAndDefensive() {
        byte[] original = png(5);
        ChatContent content = ChatContent.of("x", List.of(original));
        original[0] = (byte) 0x00; // caller mutates its buffer afterwards
        assertEquals((byte) 0x89, content.images().get(0)[0], "image bytes are copied on the way in");
        content.images().get(0)[0] = (byte) 0x00; // caller mutates the returned copy
        assertEquals((byte) 0x89, content.images().get(0)[0], "image bytes are copied on the way out");
        assertThrows(UnsupportedOperationException.class, () -> content.parts().clear());
        assertThrows(IllegalArgumentException.class, () -> ChatContent.of("x", Arrays.asList(png(1), null)));
        assertThrows(IllegalArgumentException.class,
                () -> ChatContent.of("x", List.of(new byte[0])));
        assertEquals("", ChatContent.text(null).text(), "null text canonicalises to the empty string");
        assertEquals("", ChatContent.builder().build().text(), "an empty builder still serialises as a string");
        assertTrue(ChatContent.builder().build().toJson().isJsonPrimitive());
    }

    @Test
    void imagePartsTruncatesAndKeepsOrder() {
        byte[] a = png(1);
        byte[] b = png(2);
        byte[] c = png(3);
        ChatContent content = ChatContent.of("text", List.of(a, b, c));
        ChatContent limited = content.imageParts(2);
        assertEquals(2, limited.imageCount());
        assertEquals(3, limited.size(), "text part plus two images");
        assertEquals("text", limited.text());
        assertArrayEquals(a, limited.images().get(0));
        assertArrayEquals(b, limited.images().get(1));

        ChatContent none = content.imageParts(0);
        assertEquals(0, none.imageCount());
        assertTrue(none.isTextOnly());
        assertTrue(none.toJson().isJsonPrimitive(), "without images the content is a plain string again");
        assertEquals("text", none.text());

        ChatContent negative = content.imageParts(-5);
        assertEquals(0, negative.imageCount());

        assertSame(content, content.imageParts(3), "no truncation needed returns the same instance");
        assertSame(content, content.imageParts(99));
    }

    @Test
    void withImageLimitTrimsEveryMessage() {
        ChatRequest request = ChatRequest.vision("system", "judge", List.of(png(1), png(2), png(3)), 512, true);
        assertEquals(3, request.imageCount());
        assertTrue(request.hasImages());
        ChatRequest limited = request.withImageLimit(1);
        assertEquals(1, limited.imageCount());
        assertEquals(2, limited.messages().size(), "system + user");
        assertEquals("system", limited.messages().get(0).content(), "text is untouched");
        assertEquals("judge", limited.messages().get(1).content());
        assertTrue(limited.jsonMode());
        assertEquals(512, limited.maxTokens());
        assertSame(request, request.withImageLimit(3), "a request within budget is reused");
        assertSame(request, request.withImageLimit(10));
        assertEquals(0, request.withImageLimit(0).imageCount());
    }

    // ------------------------------------------------------------------ ChatRequest plumbing

    @Test
    void chatRequestWithImagesKeepsValidationAndFlags() {
        ChatRequest request = new ChatRequest("deepseek/deepseek-v4.1-flash",
                List.of(ChatMessage.system("critic"), ChatMessage.user("views", List.of(png(1), png(2)))),
                1024, 0.4, "medium", true);
        assertEquals("deepseek/deepseek-v4.1-flash", request.model());
        assertEquals(1024, request.maxTokens());
        assertEquals(0.4d, request.temperature());
        assertEquals("medium", request.reasoningEffort());
        assertTrue(request.jsonMode());
        assertTrue(request.hasImages());
        assertEquals(2, request.imageCount());
        assertEquals("critic", request.systemPrompt(), "system text is unaffected by image parts");

        JsonObject body = providerBody(request);
        assertEquals("deepseek/deepseek-v4.1-flash", body.get("model").getAsString());
        assertEquals(1024, body.get("max_tokens").getAsInt());
        assertEquals("medium", body.get("reasoning_effort").getAsString());
        assertEquals("json_object", body.getAsJsonObject("response_format").get("type").getAsString());
        JsonArray messages = body.getAsJsonArray("messages");
        assertEquals(2, messages.size());
        assertTrue(messages.get(0).getAsJsonObject().get("content").isJsonPrimitive(),
                "the system message stays a plain string");
        JsonArray parts = messages.get(1).getAsJsonObject().getAsJsonArray("content");
        assertEquals(3, parts.size());
        assertEquals("text", parts.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("image_url", parts.get(1).getAsJsonObject().get("type").getAsString());
        assertEquals("image_url", parts.get(2).getAsJsonObject().get("type").getAsString());
    }

    @Test
    void visionFactoryBuildsAValidRequest() {
        ChatRequest request = ChatRequest.vision("judge this", "renders attached", List.of(png(1)), 256, true);
        assertEquals(ChatRequest.DEFAULT_MODEL, request.model());
        assertEquals(2, request.messages().size());
        assertTrue(request.messages().get(0).isSystem());
        assertEquals(1, request.imageCount());
        assertTrue(request.jsonMode());

        ChatRequest textOnly = ChatRequest.vision(null, "no renders", List.of(), 128, false);
        assertEquals(1, textOnly.messages().size());
        assertFalse(textOnly.hasImages());
        assertFalse(textOnly.jsonMode());
    }

    @Test
    void messagesJsonMatchesMessageToJson() {
        ChatRequest request = new ChatRequest("m", List.of(ChatMessage.user("a"),
                ChatMessage.user("b", List.of(png(9)))), 64, null, null, false);
        JsonArray messages = request.messagesJson();
        assertEquals(2, messages.size());
        assertEquals(LlmJson.GSON.toJson(ChatMessage.user("a").toJson()),
                LlmJson.GSON.toJson(messages.get(0)));
        assertTrue(messages.get(1).getAsJsonObject().get("content").isJsonArray());
    }

    @Test
    void generateEnvelopeKeepsTheTypedPartArray() {
        // The /alpha/generate transport always uses a parts array; images must survive that too.
        ChatRequest request = new ChatRequest("m",
                List.of(ChatMessage.system("ignored"), ChatMessage.user("render", List.of(png(1)))),
                128, null, null, false);
        String body = new CommandCodeGenerateClient("http://localhost:1", "k", () -> "1.0.0", null, null)
                .previewBody(request);
        JsonObject params = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("params");
        JsonArray messages = params.getAsJsonArray("messages");
        assertEquals(1, messages.size(), "the system message is hoisted, as before");
        JsonArray parts = messages.get(0).getAsJsonObject().getAsJsonArray("content");
        assertEquals(2, parts.size());
        assertEquals("text", parts.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("image_url", parts.get(1).getAsJsonObject().get("type").getAsString());
        assertTrue(parts.get(1).getAsJsonObject().getAsJsonObject("image_url").get("url").getAsString()
                .startsWith("data:image/png;base64,"));
        // A text-only request still produces exactly one text part, as before.
        String plain = new CommandCodeGenerateClient("http://localhost:1", "k", () -> "1.0.0", null, null)
                .previewBody(ChatRequest.simple("hi", 32));
        JsonArray plainParts = JsonParser.parseString(plain).getAsJsonObject()
                .getAsJsonObject("params").getAsJsonArray("messages").get(0)
                .getAsJsonObject().getAsJsonArray("content");
        assertEquals(1, plainParts.size());
        assertEquals("text", plainParts.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("hi", plainParts.get(0).getAsJsonObject().get("text").getAsString());
    }

    @Test
    void imagePartEqualityAndValidation() {
        assertThrows(IllegalArgumentException.class, () -> new ChatContent.ImagePart(null));
        assertThrows(IllegalArgumentException.class, () -> new ChatContent.ImagePart(new byte[0]));
        assertEquals(new ChatContent.ImagePart(png(1)), new ChatContent.ImagePart(png(1)));
        assertEquals(new ChatContent.ImagePart(png(1)).hashCode(), new ChatContent.ImagePart(png(1)).hashCode());
        assertNotEquals(new ChatContent.ImagePart(png(1)), new ChatContent.ImagePart(png(2)));
        assertInstanceOf(ChatContent.TextPart.class, ChatContent.text("x").parts().get(0));
        assertTrue(ChatContent.text("x").parts().get(0).isImage() == false);
    }

    // ------------------------------------------------------------------ end to end over HTTP

    /**
     * Sends a vision request through the real transport against a local HTTP server and inspects the
     * bytes that arrived. This is the check the gateway itself would perform: the body must be valid
     * JSON, the image part must be a PNG data URL, and the answer must come back.
     */
    @Test
    void visionRequestTravelsAsMultipartContent() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/provider/v1/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(body);
            byte[] response = ("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":"
                    + "\"{\\\"ok\\\":true}\"}}]}\n\n"
                    + "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                    + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            OpenAiCompatClient client = new OpenAiCompatClient(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/provider/v1", "k",
                    Duration.ofSeconds(5), Duration.ofSeconds(10), null);
            ChatRequest request = ChatRequest.vision("You are a critic.", "judge these renders",
                    List.of(png(1), png(2)), 512, true);
            ChatResult result = client.chat(request, null, null);
            assertEquals("{\"ok\":true}", result.content(), "the vision answer is read back as text");
            assertEquals(1, received.size());

            JsonObject body = JsonParser.parseString(received.get(0)).getAsJsonObject();
            assertEquals("json_object", body.getAsJsonObject("response_format").get("type").getAsString());
            JsonArray messages = body.getAsJsonArray("messages");
            assertEquals(2, messages.size());
            assertTrue(messages.get(0).getAsJsonObject().get("content").isJsonPrimitive());
            JsonArray parts = messages.get(1).getAsJsonObject().getAsJsonArray("content");
            assertEquals(3, parts.size());
            assertEquals("text", parts.get(0).getAsJsonObject().get("type").getAsString());
            for (int i = 1; i <= 2; i++) {
                String url = parts.get(i).getAsJsonObject().getAsJsonObject("image_url").get("url").getAsString();
                Base64.getDecoder().decode(url.substring("data:image/png;base64,".length()));
            }
        } finally {
            server.stop(0);
        }
    }
}
