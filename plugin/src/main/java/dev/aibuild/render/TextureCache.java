package dev.aibuild.render;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Real block colours for renders, derived from the vanilla client's block textures.
 *
 * <p>On first use the vanilla client jar is downloaded from Mojang's official launcher metadata
 * ({@code piston-meta.mojang.com}), its sha1 is verified, and every block's blockstate → model → texture chain
 * is resolved to an average colour (alpha-weighted, with the default plains biome tint for grass, foliage
 * and water). Only the derived colour table ({@code colors.json}, a few kilobytes) is kept; the jar is deleted.
 * The game assets remain Mojang's property and their use is governed by the Minecraft EULA; this mirrors what
 * map renderers such as BlueMap do on the operator's own machine. Disable with {@code render.textures: off}.</p>
 */
public final class TextureCache {
    private static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final Map<String, Integer> TINT = Map.ofEntries(
            Map.entry("grass", 0x91BD59), Map.entry("foliage", 0x77AB2F), Map.entry("spruce", 0x619961),
            Map.entry("birch", 0x80A755), Map.entry("water", 0x3F76E4));
    private static final Set<String> GRASS_TINTED = Set.of("grass_block_top", "short_grass", "tall_grass_top", "tall_grass_bottom",
            "fern", "large_fern_top", "large_fern_bottom", "sugar_cane", "grass_block_side_overlay");
    private static final Set<String> FOLIAGE_TINTED = Set.of("oak_leaves", "jungle_leaves", "acacia_leaves", "dark_oak_leaves",
            "mangrove_leaves", "vine");

    private TextureCache() {
    }

    /** Loads {@code colors.json} for {@code version}, building it from the client jar when missing. */
    public static Map<String, Integer> load(File dataFolder, String version, Logger log) throws IOException, InterruptedException {
        File dir = new File(dataFolder, "textures/" + version);
        File colors = new File(dir, "colors.json");
        if (colors.isFile()) return read(colors);
        Files.createDirectories(dir.toPath());
        File jar = new File(dir, "client.jar.part");
        try {
            download(version, jar, log);
            Map<String, Integer> table = fromClientJar(jar);
            JsonObject o = new JsonObject();
            table.forEach((k, v) -> o.addProperty(k, String.format("%06X", v)));
            Files.writeString(colors.toPath(), o.toString(), StandardCharsets.UTF_8);
            log.info("Derived render colours for " + table.size() + " blocks from the Minecraft " + version + " client");
            return table;
        } finally {
            Files.deleteIfExists(jar.toPath());
        }
    }

    private static Map<String, Integer> read(File f) throws IOException {
        JsonObject o = JsonParser.parseString(Files.readString(f.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
        Map<String, Integer> out = new HashMap<>();
        for (var e : o.entrySet()) out.put(e.getKey(), Integer.parseInt(e.getValue().getAsString(), 16));
        return out;
    }

    private static void download(String version, File target, Logger log) throws IOException, InterruptedException {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build();
        JsonObject manifest = json(http, MANIFEST);
        String url = null;
        for (JsonElement v : manifest.getAsJsonArray("versions")) {
            JsonObject o = v.getAsJsonObject();
            if (version.equals(o.get("id").getAsString())) url = o.get("url").getAsString();
        }
        if (url == null) {
            String latest = manifest.getAsJsonObject("latest").get("release").getAsString();
            log.warning("Minecraft " + version + " is not in the launcher manifest; using block colours from " + latest);
            for (JsonElement v : manifest.getAsJsonArray("versions")) {
                JsonObject o = v.getAsJsonObject();
                if (latest.equals(o.get("id").getAsString())) url = o.get("url").getAsString();
            }
        }
        if (url == null) throw new IOException("no client found in the launcher manifest");
        JsonObject client = json(http, url).getAsJsonObject("downloads").getAsJsonObject("client");
        String sha1 = client.get("sha1").getAsString();
        log.info("Downloading the Minecraft client once to derive render colours (" + client.get("size").getAsLong() / 1_000_000 + " MB)");
        HttpResponse<InputStream> res = http.send(HttpRequest.newBuilder(URI.create(client.get("url").getAsString()))
                .timeout(Duration.ofMinutes(5)).build(), HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() != 200) throw new IOException("client download failed: HTTP " + res.statusCode());
        try (InputStream in = res.body()) {
            Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        String actual = sha1(target.toPath());
        if (!actual.equalsIgnoreCase(sha1)) throw new IOException("client jar checksum mismatch");
    }

    private static JsonObject json(HttpClient http, String url) throws IOException, InterruptedException {
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IOException("HTTP " + res.statusCode() + " for " + url);
        return JsonParser.parseString(res.body()).getAsJsonObject();
    }

    private static String sha1(Path p) throws IOException {
        try (InputStream in = Files.newInputStream(p)) {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /** Resolves every blockstate in a client (or resource-pack) zip to an average colour. */
    public static Map<String, Integer> fromClientJar(File jar) throws IOException {
        Map<String, Integer> out = new HashMap<>();
        try (ZipFile zip = new ZipFile(jar)) {
            Map<String, JsonObject> models = new HashMap<>();
            Map<String, Integer> textureColours = new HashMap<>();
            String statesDir = "assets/minecraft/blockstates/";
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String n = e.getName();
                if (!n.startsWith(statesDir) || !n.endsWith(".json")) continue;
                String block = n.substring(statesDir.length(), n.length() - 5);
                try {
                    JsonObject state = parse(zip, e);
                    String model = firstModel(state);
                    if (model == null) continue;
                    Map<String, String> tex = textures(zip, models, model, 0);
                    String side = pickTexture(tex, "side", "all", "texture", "cross", "plant", "north", "front", "wool", "pattern", "particle");
                    String top = pickTexture(tex, "top", "end", "up", "all", "particle");
                    boolean topFirst = block.endsWith("grass_block") || block.equals("podzol") || block.equals("mycelium")
                            || block.endsWith("_carpet") || block.endsWith("pressure_plate");
                    String chosen = topFirst && top != null ? top : side != null ? side : top;
                    if (chosen == null) continue;
                    Integer c = textureColour(zip, textureColours, chosen);
                    if (c != null) out.put(block, c);
                } catch (RuntimeException ignored) {
                    // a malformed or unusual blockstate keeps the fallback colour
                }
            }
        }
        out.put("water", TINT.get("water"));
        return out;
    }

    private static String firstModel(JsonObject state) {
        if (state.has("variants")) {
            for (var e : state.getAsJsonObject("variants").entrySet()) {
                JsonElement v = e.getValue();
                JsonObject o = v.isJsonArray() ? v.getAsJsonArray().get(0).getAsJsonObject() : v.getAsJsonObject();
                if (o.has("model")) return o.get("model").getAsString();
            }
        }
        if (state.has("multipart")) {
            for (JsonElement part : state.getAsJsonArray("multipart")) {
                JsonElement apply = part.getAsJsonObject().get("apply");
                JsonObject o = apply.isJsonArray() ? apply.getAsJsonArray().get(0).getAsJsonObject() : apply.getAsJsonObject();
                if (o.has("model")) return o.get("model").getAsString();
            }
        }
        return null;
    }

    private static Map<String, String> textures(ZipFile zip, Map<String, JsonObject> cache, String model, int depth) throws IOException {
        Map<String, String> out = new HashMap<>();
        if (depth > 12 || model == null) return out;
        String path = "assets/minecraft/models/" + strip(model) + ".json";
        JsonObject m = cache.get(path);
        if (m == null) {
            ZipEntry e = zip.getEntry(path);
            if (e == null) return out;
            m = parse(zip, e);
            cache.put(path, m);
        }
        if (m.has("parent")) out.putAll(textures(zip, cache, m.get("parent").getAsString(), depth + 1));
        if (m.has("textures")) {
            for (var e : m.getAsJsonObject("textures").entrySet()) out.put(e.getKey(), e.getValue().getAsString());
        }
        return out;
    }

    private static String pickTexture(Map<String, String> tex, String... keys) {
        for (String k : keys) {
            String v = tex.get(k);
            for (int i = 0; v != null && v.startsWith("#") && i < 8; i++) v = tex.get(v.substring(1));
            if (v != null && !v.startsWith("#")) return v;
        }
        return null;
    }

    private static Integer textureColour(ZipFile zip, Map<String, Integer> cache, String texture) throws IOException {
        Integer known = cache.get(texture);
        if (known != null) return known;
        ZipEntry e = zip.getEntry("assets/minecraft/textures/" + strip(texture) + ".png");
        if (e == null) return null;
        BufferedImage img;
        try (InputStream in = zip.getInputStream(e)) {
            img = ImageIO.read(new ByteArrayInputStream(in.readAllBytes()));
        }
        if (img == null) return null;
        long r = 0, g = 0, b = 0, wsum = 0;
        int h = Math.min(img.getHeight(), img.getWidth() * 4);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int argb = img.getRGB(x, y), a = argb >>> 24;
                if (a < 16) continue;
                r += (long) ((argb >> 16) & 255) * a;
                g += (long) ((argb >> 8) & 255) * a;
                b += (long) (argb & 255) * a;
                wsum += a;
            }
        }
        if (wsum == 0) return null;
        int c = ((int) (r / wsum) << 16) | ((int) (g / wsum) << 8) | (int) (b / wsum);
        String name = strip(texture).substring(strip(texture).lastIndexOf('/') + 1);
        if (GRASS_TINTED.contains(name)) c = multiply(c, TINT.get("grass"));
        else if (FOLIAGE_TINTED.contains(name)) c = multiply(c, TINT.get("foliage"));
        else if (name.equals("spruce_leaves")) c = multiply(c, TINT.get("spruce"));
        else if (name.equals("birch_leaves")) c = multiply(c, TINT.get("birch"));
        else if (name.startsWith("water_")) c = multiply(c, TINT.get("water"));
        cache.put(texture, c);
        return c;
    }

    private static int multiply(int c, int tint) {
        int r = ((c >> 16) & 255) * ((tint >> 16) & 255) / 255;
        int g = ((c >> 8) & 255) * ((tint >> 8) & 255) / 255;
        int b = (c & 255) * (tint & 255) / 255;
        return (r << 16) | (g << 8) | b;
    }

    private static String strip(String ref) {
        return ref.startsWith("minecraft:") ? ref.substring(10) : ref;
    }

    private static JsonObject parse(ZipFile zip, ZipEntry e) throws IOException {
        try (InputStream in = zip.getInputStream(e)) {
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
