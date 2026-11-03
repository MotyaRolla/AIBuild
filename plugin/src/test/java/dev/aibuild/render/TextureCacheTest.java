package dev.aibuild.render;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class TextureCacheTest {
    @TempDir File dir;

    @Test void resolvesBlockstateModelParentsAndTints() throws Exception {
        File jar = new File(dir, "client.jar");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(jar))) {
            put(z, "assets/minecraft/blockstates/oak_stairs.json", "{\"variants\":{\"facing=east\":{\"model\":\"minecraft:block/oak_stairs\"}}}");
            put(z, "assets/minecraft/models/block/oak_stairs.json", "{\"parent\":\"minecraft:block/stairs\",\"textures\":{\"side\":\"minecraft:block/oak_planks\",\"top\":\"minecraft:block/oak_planks\"}}");
            put(z, "assets/minecraft/models/block/stairs.json", "{\"textures\":{\"particle\":\"#side\"}}");
            put(z, "assets/minecraft/blockstates/oak_leaves.json", "{\"variants\":{\"\":{\"model\":\"minecraft:block/oak_leaves\"}}}");
            put(z, "assets/minecraft/models/block/oak_leaves.json", "{\"parent\":\"minecraft:block/leaves\",\"textures\":{\"all\":\"minecraft:block/oak_leaves\"}}");
            put(z, "assets/minecraft/blockstates/stone_brick_wall.json", "{\"multipart\":[{\"apply\":{\"model\":\"minecraft:block/stone_brick_wall_post\"}}]}");
            put(z, "assets/minecraft/models/block/stone_brick_wall_post.json", "{\"textures\":{\"wall\":\"minecraft:block/stone_bricks\",\"particle\":\"#wall\"}}");
            png(z, "assets/minecraft/textures/block/oak_planks.png", 0xA0824E);
            png(z, "assets/minecraft/textures/block/oak_leaves.png", 0x909090);
            png(z, "assets/minecraft/textures/block/stone_bricks.png", 0x7A7A7A);
        }
        Map<String, Integer> c = TextureCache.fromClientJar(jar);
        assertEquals(0xA0824E, c.get("oak_stairs"));
        assertEquals(0x7A7A7A, c.get("stone_brick_wall"), "multipart blockstates resolve through #references");
        int leaves = c.get("oak_leaves");
        assertTrue(((leaves >> 8) & 255) > ((leaves >> 16) & 255), "grey leaf texture is tinted green");
    }

    private static void put(ZipOutputStream z, String name, String text) throws Exception {
        z.putNextEntry(new ZipEntry(name));
        z.write(text.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    private static void png(ZipOutputStream z, String name, int rgb) throws Exception {
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < 4; x++) for (int y = 0; y < 4; y++) img.setRGB(x, y, 0xFF000000 | rgb);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        z.putNextEntry(new ZipEntry(name));
        z.write(out.toByteArray());
        z.closeEntry();
    }
}
