package dev.aibuild.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-face block textures for {@link VoxelRaster}: 16×16 ARGB tiles (biome tint already applied) and, per block,
 * the tile of its top, side and bottom face. Built by {@link TextureCache} from the client jar and stored as
 * {@code atlas.png} + {@code faces.json} next to {@code colors.json}. Immutable and thread-safe.
 */
public final class BlockTextures {
    public static final int S = 16;
    private static final int COLS = 64;
    private static volatile BlockTextures current;

    private final int[][] tiles;
    /** Mip levels per tile: 16, 8, 4, 2, 1 pixels wide. */
    private final int[][][] mips;
    private final Map<String, int[]> faces;
    /** Lazily encoded {@link #png()}; the atlas never changes, so this is computed at most once. */
    private volatile byte[] png;

    BlockTextures(List<int[]> tiles, Map<String, int[]> faces) {
        this.tiles = tiles.toArray(int[][]::new);
        this.faces = Map.copyOf(faces);
        this.mips = new int[this.tiles.length][][];
        for (int i = 0; i < this.tiles.length; i++) mips[i] = mipChain(this.tiles[i]);
    }

    /** Textures used by renders, or null when only colours (or nothing) are available. */
    public static BlockTextures current() {
        return current;
    }

    public static void install(BlockTextures t) {
        current = t;
    }

    /** {top, side, bottom} tile indices of a block, or null when unknown. */
    public int[] faces(String block) {
        return faces.get(block);
    }

    /** Tiles per atlas row; tile {@code n} sits at column {@code n % cols()}, row {@code n / cols()}. */
    public static int cols() {
        return COLS;
    }

    /** Tile edge in pixels. */
    public static int tileSize() {
        return S;
    }

    /**
     * The whole atlas as a PNG, for clients that draw with real textures (the live view).
     *
     * <p>Encoded once and cached: the atlas is immutable, and re-encoding it for every live frame would cost
     * more than the frame itself. Returns null when no textures are installed.</p>
     */
    public byte[] png() {
        byte[] cached = png;
        if (cached != null) return cached;
        synchronized (this) {
            if (png == null) {
                try {
                    png = writePng();
                } catch (IOException e) {
                    return null;
                }
            }
            return png;
        }
    }

    private byte[] writePng() throws IOException {
        int rows = (tiles.length + COLS - 1) / COLS;
        BufferedImage atlas = new BufferedImage(COLS * S, Math.max(1, rows) * S, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < tiles.length; i++) atlas.setRGB((i % COLS) * S, (i / COLS) * S, S, S, tiles[i], 0, S);
        ByteArrayOutputStream out = new ByteArrayOutputStream(256 * 1024);
        ImageIO.write(atlas, "png", out);
        return out.toByteArray();
    }

    public int tileCount() {
        return tiles.length;
    }

    /**
     * Texel of a tile at (u, v) in [0,1) (u to the right, v downward in texture space), at mip {@code level}
     * (0 = full 16×16 detail, 4 = one averaged texel). Alpha is kept, so cut-out textures (leaves, flowers) work.
     */
    public int sample(int tile, double u, double v, int level) {
        int[][] chain = mips[tile];
        int lv = Math.clamp(level, 0, chain.length - 1);
        int size = S >> lv;
        int x = Math.clamp((int) (u * size), 0, size - 1);
        int y = Math.clamp((int) (v * size), 0, size - 1);
        return chain[lv][y * size + x];
    }

    private static int[][] mipChain(int[] base) {
        int[][] chain = new int[5][];
        chain[0] = base;
        for (int lv = 1; lv < 5; lv++) {
            int size = S >> lv, prev = size * 2;
            int[] src = chain[lv - 1], out = new int[size * size];
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    long a = 0, r = 0, g = 0, b = 0;
                    for (int k = 0; k < 4; k++) {
                        int c = src[(y * 2 + k / 2) * prev + x * 2 + k % 2], ca = c >>> 24;
                        a += ca;
                        r += (long) ((c >> 16) & 255) * ca;
                        g += (long) ((c >> 8) & 255) * ca;
                        b += (long) (c & 255) * ca;
                    }
                    out[y * size + x] = a == 0 ? 0 : (int) (a / 4) << 24 | (int) (r / a) << 16 | (int) (g / a) << 8 | (int) (b / a);
                }
            }
            chain[lv] = out;
        }
        return chain;
    }

    void save(File dir) throws IOException {
        int rows = (tiles.length + COLS - 1) / COLS;
        BufferedImage atlas = new BufferedImage(COLS * S, Math.max(1, rows) * S, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < tiles.length; i++) atlas.setRGB((i % COLS) * S, (i / COLS) * S, S, S, tiles[i], 0, S);
        ImageIO.write(atlas, "png", new File(dir, "atlas.png"));
        JsonObject o = new JsonObject();
        o.addProperty("tiles", tiles.length);
        JsonObject blocks = new JsonObject();
        faces.forEach((k, v) -> {
            JsonArray a = new JsonArray();
            for (int t : v) a.add(t);
            blocks.add(k, a);
        });
        o.add("blocks", blocks);
        Files.writeString(new File(dir, "faces.json").toPath(), o.toString(), StandardCharsets.UTF_8);
    }

    /** Reads a saved atlas, or returns null when it is missing. */
    public static BlockTextures load(File dir) throws IOException {
        File png = new File(dir, "atlas.png"), json = new File(dir, "faces.json");
        if (!png.isFile() || !json.isFile()) return null;
        BufferedImage atlas = ImageIO.read(png);
        JsonObject o = JsonParser.parseString(Files.readString(json.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
        int n = o.get("tiles").getAsInt();
        List<int[]> tiles = new ArrayList<>(n);
        for (int i = 0; i < n; i++) tiles.add(atlas.getRGB((i % COLS) * S, (i / COLS) * S, S, S, null, 0, S));
        Map<String, int[]> faces = new HashMap<>();
        for (var e : o.getAsJsonObject("blocks").entrySet()) {
            JsonArray a = e.getValue().getAsJsonArray();
            faces.put(e.getKey(), new int[]{a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()});
        }
        return new BlockTextures(tiles, faces);
    }
}
