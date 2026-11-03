package dev.aibuild.analysis;

import dev.aibuild.model.BlockInfo;
import dev.aibuild.model.BlockSpec;
import dev.aibuild.model.VoxelModel;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Classic isometric preview renderer for a {@link VoxelModel}: a PNG the player or a reviewer can look at.
 *
 * <p>Every non-air block becomes a small cube drawn with the painter's algorithm (bottom-up, back-to-front), so
 * nearer blocks correctly cover farther ones. Each cube shows three faces with fixed lighting (top brightest, then
 * the right/east face, then the left/north face) derived from the block color. Shapes are approximated:</p>
 * <ul>
 *   <li>full blocks: complete cube with a thin dark outline;</li>
 *   <li>glass / panes / iron bars: translucent (alpha ~0.45); panes, bars, fences and walls are thin posts;</li>
 *   <li>slabs: half-height box at the bottom (or top for {@code type=top}); {@code double} slabs are cubes;</li>
 *   <li>stairs: lower/upper half plus a half-block step toward the {@code facing} side;</li>
 *   <li>carpets, pressure plates and snow: thin tiles; torches, flowers, lanterns, chains: small sprites;</li>
 *   <li>everything else: a cube using the keyword color table, falling back to {@link BlockInfo#mapColor}.</li>
 * </ul>
 *
 * <p>The image is auto-sized to the model bounds and starts from a light sky gradient. {@code view} rotates the
 * projection: 0 = south-east (front on the right), 1 = south-west (front on the left), 2 = north-west,
 * 3 = north-east. Rendering is stateless and thread-safe as long as the model is not mutated concurrently; every
 * call allocates its own image and scratch state.</p>
 */
public final class IsoRenderer {

    private IsoRenderer() {
    }

    private static final int SKY_TOP = 0xFFEAF3FD;
    private static final int SKY_BOTTOM = 0xFFF9FCFE;
    /** Outline color of a cube edge (semi-transparent dark). */
    private static final int OUTLINE = 0x66000000;
    /** Alpha for glass-like blocks (0..255). */
    private static final int GLASS_ALPHA = 115;
    /** Maximum image dimension; larger models are clamped (rendering is a preview, not a screenshot). */
    private static final int MAX_DIM = 8192;

    /**
     * Renders one isometric view.
     *
     * @param model the model to render (read-only; not mutated)
     * @param info  block knowledge used for colors and light emission
     * @param view  0 = from south-east (front on the right), 1 = from south-west (front on the left),
     *              2 = from north-west, 3 = from north-east; other values are normalized modulo 4
     * @param scale block edge length in pixels, clamped to 3..64
     * @return a new ARGB image, never null
     * @throws IllegalArgumentException if {@code model} or {@code info} is null
     */
    public static BufferedImage render(VoxelModel model, BlockInfo info, int view, int scale) {
        if (model == null) throw new IllegalArgumentException("model is null");
        if (info == null) throw new IllegalArgumentException("info is null");
        int edge = Math.max(3, Math.min(64, scale));
        int v = Math.floorMod(view, 4);
        int[] bb = model.solidBounds();
        if (bb == null) return blankImage(edge, "empty model");

        int w = bb[3] - bb[0] + 1;
        int d = bb[5] - bb[2] + 1;
        int hgt = bb[4] - bb[1] + 1;
        boolean flipX = v == 1 || v == 2;
        boolean flipZ = v == 2 || v == 3;

        int ew = Math.max(2, (int) Math.round(edge * 0.866)); // half width of the top diamond
        int eh = Math.max(1, edge / 2);                        // half height of the top diamond
        int margin = Math.max(4, edge / 2);

        long widthPx = (long) (w + d) * ew + margin * 2L;
        long heightPx = (long) (w + d) * eh + (long) hgt * edge + margin * 2L;
        int imgW = (int) Math.max(32, Math.min(MAX_DIM, widthPx));
        int imgH = (int) Math.max(32, Math.min(MAX_DIM, heightPx));

        BufferedImage img = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g2.setStroke(new BasicStroke(Math.max(0.6f, edge * 0.05f)));
            paintSky(g2, imgW, imgH);

            // screen anchors: top face centre of block (rx'=0, rz'=0, y=maxY)
            int originX = margin + (d - 1) * ew;
            int originY = margin + (hgt - 1) * edge + eh;
            ColourTable colours = new ColourTable(info);
            // In an isometric view only the top face and the two camera-facing sides can be seen. A block whose
            // three visible faces are all covered by occluding neighbours is invisible: skip it. This keeps large
            // solid builds cheap to render (whole interiors are skipped).
            int dirRx = flipX ? -1 : 1;
            int dirRz = flipZ ? -1 : 1;

            // Painter's algorithm in true depth order. The camera looks along (1,1,1) rotated into the view
            // frame, so a cell is nearer the camera the larger (rx + rz + y) is: paint ascending. Counting-sort
            // the visible cells by that key (no comparison sort, no boxing).
            byte[] visible = new byte[w * hgt * d];
            int keyCount = Math.max(1, (w - 1) + (d - 1) + (hgt - 1) + 1);
            int[] starts = new int[keyCount + 1];
            int visCount = 0;
            for (int y = bb[1]; y <= bb[4]; y++) {
                int ry = y - bb[1];
                for (int rx = 0; rx < w; rx++) {
                    for (int rz = 0; rz < d; rz++) {
                        int x = flipX ? bb[3] - rx : bb[0] + rx;
                        int z = flipZ ? bb[5] - rz : bb[2] + rz;
                        BlockSpec s = model.get(x, y, z);
                        if (s == null || s.isAir()) continue;
                        if (hidden(model, s, info, x, y, z, dirRx, dirRz)) continue;
                        int idx = (ry * d + rz) * w + rx;
                        visible[idx] = 1;
                        starts[rx + rz + ry]++;
                        visCount++;
                    }
                }
            }
            int[] order = new int[visCount];
            int[] cursor = new int[keyCount + 1];
            int acc = 0;
            for (int k = 0; k < keyCount; k++) {
                int c = starts[k];
                cursor[k] = acc;
                acc += c;
                starts[k] = cursor[k];
            }
            for (int ry = 0; ry < hgt; ry++) {
                for (int rx = 0; rx < w; rx++) {
                    for (int rz = 0; rz < d; rz++) {
                        int idx = (ry * d + rz) * w + rx;
                        if (visible[idx] == 0) continue;
                        int key = rx + rz + ry;
                        order[cursor[key]++] = idx;
                    }
                }
            }
            for (int k = 0; k < keyCount; k++) {
                int from = starts[k];
                int to = k + 1 < keyCount ? starts[k + 1] : visCount;
                for (int oi = from; oi < to; oi++) {
                    int idx = order[oi];
                    int rx = idx % w;
                    int rz = (idx / w) % d;
                    int ry = idx / (w * d);
                    int x = flipX ? bb[3] - rx : bb[0] + rx;
                    int z = flipZ ? bb[5] - rz : bb[2] + rz;
                    int y = bb[1] + ry;
                    BlockSpec s = model.get(x, y, z);
                    if (s == null || s.isAir()) continue;
                    int cx = originX + (rx - rz) * ew;
                    int cy = originY + (rx + rz) * eh - ry * edge;
                    Shape shape = shapeOf(s, info);
                    drawBlock(g2, cx, cy, ew, eh, edge, colours.colorOf(s), shape, s, info);
                }
            }
        } finally {
            g2.dispose();
        }
        return img;
    }

    /**
     * Renders the four views in a 2x2 grid with small labels underneath each view.
     *
     * @param model the model to render
     * @param info  block knowledge
     * @param scale block edge length in pixels (clamped to 2..24 so the sheet stays a reasonable size)
     * @return a new ARGB image, never null
     */
    public static BufferedImage renderSheet(VoxelModel model, BlockInfo info, int scale) {
        int sc = Math.max(2, Math.min(24, scale));
        BufferedImage[] views = new BufferedImage[4];
        for (int i = 0; i < 4; i++) views[i] = render(model, info, i, sc);
        String[] names = {"V0 south-east (front right)", "V1 south-west (front left)",
                "V2 north-west (back left)", "V3 north-east (back right)"};
        int fontSize = Math.max(10, Math.min(18, sc + 2));
        java.awt.Font font = new java.awt.Font(Font.MONOSPACED, Font.BOLD, fontSize);

        // measure once with a throw-away image so cells are wide enough for their labels (no overlap)
        int labelW = 0, labelH = fontSize + 4;
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = probe.createGraphics();
        try {
            pg.setFont(font);
            java.awt.FontMetrics fm = pg.getFontMetrics();
            for (String nm : names) labelW = Math.max(labelW, fm.stringWidth(nm));
            labelH = fm.getHeight() + 2;
        } finally {
            pg.dispose();
        }
        int cellW = Math.max(labelW + 8, 0);
        int cellH = 0;
        for (BufferedImage im : views) {
            cellW = Math.max(cellW, im.getWidth());
            cellH = Math.max(cellH, im.getHeight());
        }
        int pad = 8;
        int sheetW = Math.min(MAX_DIM, cellW * 2 + pad * 3);
        int sheetH = Math.min(MAX_DIM, (cellH + labelH) * 2 + pad * 3);
        BufferedImage sheet = new BufferedImage(Math.max(64, sheetW), Math.max(64, sheetH),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = sheet.createGraphics();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setColor(new Color(0xF4F7FB));
            g2.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
            g2.setFont(font);
            g2.setColor(new Color(0x33415C));
            for (int i = 0; i < 4; i++) {
                BufferedImage im = views[i];
                int cx = pad + (i % 2) * (cellW + pad);
                int cy = pad + (i / 2) * (cellH + labelH + pad);
                if (cx + im.getWidth() > sheet.getWidth() || cy + im.getHeight() > sheet.getHeight()) continue;
                g2.drawImage(im, cx + (cellW - im.getWidth()) / 2, cy, null);
                int tw = g2.getFontMetrics().stringWidth(names[i]);
                g2.drawString(names[i], cx + Math.max(0, (cellW - tw) / 2),
                        Math.min(sheet.getHeight() - 3, cy + cellH + labelH - 3));
            }
        } finally {
            g2.dispose();
        }
        return sheet;
    }

    /**
     * Writes a PNG file, creating parent directories as needed.
     *
     * @param img image to write
     * @param f   target file
     * @throws IOException              when the file cannot be written or encoded
     * @throws IllegalArgumentException when an argument is null
     */
    public static void writePng(BufferedImage img, File f) throws IOException {
        if (img == null) throw new IllegalArgumentException("image is null");
        if (f == null) throw new IllegalArgumentException("file is null");
        File parent = f.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("cannot create directory " + parent);
        }
        if (!ImageIO.write(img, "png", f)) throw new IOException("no PNG writer available for " + f);
    }

    /**
     * True when the block at (x,y,z) cannot be seen from this view: the cell above and the cells in front along the
     * two camera-facing axes are all present and occluding.
     */
    private static boolean hidden(VoxelModel model, BlockSpec self, BlockInfo info, int x, int y, int z,
                                  int dirRx, int dirRz) {
        if (!occludes(info, self)) return false; // glass, slabs, plants: never assume they hide anything
        return occludes(info, model.effective(x, y + 1, z))
                && occludes(info, model.effective(x + dirRx, y, z))
                && occludes(info, model.effective(x, y, z + dirRz));
    }

    private static boolean occludes(BlockInfo info, BlockSpec s) {
        if (s == null || s.isAir()) return false;
        try {
            return info.isOccluding(s);
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ shapes

    /** Approximate visual shape of a block. */
    private enum Shape { CUBE, GLASS, SLAB_BOTTOM, SLAB_TOP, SLAB_DOUBLE, STAIRS, CARPET, SPRITE, POST }

    private static Shape shapeOf(BlockSpec s, BlockInfo info) {
        String n = s.name();
        if (n.endsWith("_slab")) {
            return switch (prop(s, info, "type", "bottom")) {
                case "top" -> Shape.SLAB_TOP;
                case "double" -> Shape.SLAB_DOUBLE;
                default -> Shape.SLAB_BOTTOM;
            };
        }
        if (n.endsWith("_stairs")) return Shape.STAIRS;
        if (Names.isPaneLike(n) || n.endsWith("_fence") || n.equals("iron_chain")
                || (n.endsWith("_wall") && !n.contains("wall_"))) {
            return Shape.POST;
        }
        if (Names.isCarpet(s) || n.endsWith("_pressure_plate") || n.equals("lily_pad") || n.equals("redstone_wire")) {
            return Shape.CARPET;
        }
        if (n.contains("torch") || n.endsWith("_head") || n.endsWith("_skull") || n.endsWith("_candle")
                || n.endsWith("_sapling") || Names.FLOOR_PLANTS.contains(n) || n.equals("flower_pot")
                || n.endsWith("_button") || n.equals("lever") || n.endsWith("_sign") || n.endsWith("_banner")
                || n.equals("lantern") || n.equals("soul_lantern") || n.endsWith("campfire")
                || n.equals("end_rod") || n.equals("lightning_rod") || n.startsWith("potted_")) {
            return Shape.SPRITE;
        }
        if (Names.isGlassLike(n)) return Shape.GLASS;
        return Shape.CUBE;
    }

    private static String prop(BlockSpec s, BlockInfo info, String key, String def) {
        String v = s.prop(key);
        if (v != null) return v;
        try {
            v = info.getProp(s, key);
        } catch (RuntimeException ignored) {
            // hard default below
        }
        return v == null ? def : v;
    }

    // ------------------------------------------------------------------ drawing primitives

    /** Draws the whole block occupying one cell: top face centred at (cx,cy), extending down by {@code edge}. */
    private static void drawBlock(Graphics2D g2, int cx, int cy, int ew, int eh, int edge, int rgb, Shape shape,
                                  BlockSpec spec, BlockInfo info) {
        int translucent = (shape == Shape.GLASS) ? GLASS_ALPHA : 255;
        switch (shape) {
            case CUBE -> box(g2, cx, cy, ew, eh, edge, rgb, translucent, true);
            case GLASS -> box(g2, cx, cy, ew, eh, edge, rgb, translucent, true);
            case SLAB_BOTTOM -> box(g2, cx, cy + edge / 2, ew, eh, Math.max(1, edge / 2), rgb, 255, true);
            case SLAB_TOP -> box(g2, cx, cy, ew, eh, Math.max(1, edge / 2), rgb, 255, true);
            case SLAB_DOUBLE -> box(g2, cx, cy, ew, eh, edge, rgb, 255, true);
            case STAIRS -> {
                String half = prop(spec, info, "half", "bottom");
                String facing = prop(spec, info, "facing", "north");
                int fdx = switch (facing) {
                    case "east" -> 1;
                    case "west" -> -1;
                    default -> 0;
                };
                int fdz = switch (facing) {
                    case "south" -> 1;
                    case "north" -> -1;
                    default -> 0;
                };
                int ox = (fdx - fdz) * (ew / 2);
                int oy = (fdx + fdz) * (eh / 2);
                int halfH = Math.max(1, edge / 2);
                if ("top".equals(half)) {
                    box(g2, cx, cy, ew, eh, halfH, rgb, 255, true);                       // full top slab
                    box(g2, cx + ox, cy + halfH + oy, ew, eh, halfH, shade(rgb, 0.92), 255, true); // step below
                } else {
                    box(g2, cx, cy + halfH, ew, eh, halfH, shade(rgb, 0.92), 255, true);  // full bottom slab
                    box(g2, cx + ox, cy + oy, ew, eh, halfH, rgb, 255, true);             // step on top
                }
            }
            case CARPET -> {
                int t = Math.max(1, edge / 5);
                box(g2, cx, cy + edge - t, ew, eh, t, rgb, 255, true);
            }
            case POST -> {
                int pw = Math.max(1, ew / 3);
                int ph = Math.max(1, eh / 3);
                box(g2, cx, cy, pw, ph, edge, rgb, 255, true);
            }
            case SPRITE -> {
                boolean hanging = "true".equals(prop(spec, info, "hanging", "false"))
                        || spec.name().contains("chain") || spec.name().endsWith("_hanging_sign");
                int sw = Math.max(2, edge / 5);
                int sh = Math.max(3, (int) (edge * 0.75));
                int topY = hanging ? cy : cy + edge - sh;
                int botY = hanging ? cy + sh : cy + edge - 1;
                g2.setColor(new Color(rgb, true));
                g2.fillRect(cx - sw / 2, Math.max(0, topY), sw, Math.max(1, botY - topY));
                g2.setColor(new Color(shade(rgb, 1.3), true));
                g2.fillOval(cx - sw, Math.max(0, topY - sw), sw * 2 + 1, sw * 2 + 1);
                if (lightEmissionSafe(info, spec) > 0) {
                    g2.setColor(new Color(0xFFE08A, true));
                    int glow = Math.max(4, edge);
                    g2.fillOval(cx - glow / 2, Math.max(0, topY - glow / 3), glow, glow);
                }
            }
        }
        if (shape == Shape.GLASS) {
            g2.setColor(new Color(0x40FFFFFF, true));
            int[] xs = {cx + ew / 3, cx + ew, cx + ew, cx + ew / 3};
            int[] ys = {cy + eh / 3, cy + eh, cy + eh + edge / 3, cy + eh / 3 + edge / 3};
            g2.fillPolygon(xs, ys, 4);
        }
    }

    private static int lightEmissionSafe(BlockInfo info, BlockSpec s) {
        try {
            return info.lightEmission(s);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /**
     * Draws an axis-aligned box whose <b>top face</b> is a diamond centred at (cx,cy) (half width {@code ew}, half
     * height {@code eh}) and whose sides drop by {@code height} pixels. Top face is lightened, right face is
     * slightly darkened, left face is darkened more.
     */
    private static void box(Graphics2D g2, int cx, int cy, int ew, int eh, int height, int rgb, int alpha,
                            boolean drawTop) {
        int top = withAlpha(shade(rgb, 1.18), alpha);
        int right = withAlpha(shade(rgb, 0.84), alpha);
        int left = withAlpha(shade(rgb, 0.64), alpha);
        int botY = cy + eh;
        // right face: (cx, botY) (cx+ew, cy) (cx+ew, cy+height) (cx, botY+height)
        fillPoly(g2, right, cx, botY, cx + ew, cy, cx + ew, cy + height, cx, botY + height);
        // left face
        fillPoly(g2, left, cx, botY, cx - ew, cy, cx - ew, cy + height, cx, botY + height);
        if (drawTop) fillPoly(g2, top, cx - ew, cy, cx, cy - eh, cx + ew, cy, cx, cy + eh);
        // outline
        g2.setColor(new Color(OUTLINE, true));
        g2.drawPolygon(new int[]{cx - ew, cx, cx + ew, cx}, new int[]{cy, cy - eh, cy, cy + eh}, 4);
        g2.drawLine(cx - ew, cy, cx - ew, cy + height);
        g2.drawLine(cx + ew, cy, cx + ew, cy + height);
        g2.drawLine(cx, botY, cx, botY + height);
        g2.drawLine(cx - ew, cy + height, cx, botY + height);
        g2.drawLine(cx + ew, cy + height, cx, botY + height);
    }

    private static void fillPoly(Graphics2D g2, int argb, int... pts) {
        g2.setColor(new Color(argb, true));
        g2.fillPolygon(xs(pts), ys(pts), pts.length / 2);
    }

    private static int[] xs(int... pts) {
        int[] a = new int[pts.length / 2];
        for (int i = 0; i < a.length; i++) a[i] = pts[i * 2];
        return a;
    }

    private static int[] ys(int... pts) {
        int[] a = new int[pts.length / 2];
        for (int i = 0; i < a.length; i++) a[i] = pts[i * 2 + 1];
        return a;
    }

    private static int withAlpha(int rgb, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (rgb & 0xFFFFFF);
    }

    /** Multiplies the color channels by {@code f} and clamps. */
    private static int shade(int rgb, double f) {
        int r = (int) Math.min(255, Math.round(((rgb >> 16) & 0xFF) * f));
        int g = (int) Math.min(255, Math.round(((rgb >> 8) & 0xFF) * f));
        int b = (int) Math.min(255, Math.round((rgb & 0xFF) * f));
        return (r << 16) | (g << 8) | b;
    }

    private static void paintSky(Graphics2D g2, int w, int h) {
        g2.setPaint(new GradientPaint(0, 0, new Color(SKY_TOP, true), 0, h, new Color(SKY_BOTTOM, true)));
        g2.fillRect(0, 0, w, h);
    }

    private static BufferedImage blankImage(int sc, String text) {
        int w = Math.max(200, text.length() * sc * 2);
        int h = Math.max(90, sc * 8);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        try {
            paintSky(g2, w, h);
            g2.setColor(new Color(0x556070));
            g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(11, sc)));
            g2.drawString(text, 10, h / 2);
        } finally {
            g2.dispose();
        }
        return img;
    }

    // ------------------------------------------------------------------ colour table

    /** Block colours derived from the real client textures ({@code render.TextureCache}), or null until loaded. */
    private static volatile Map<String, Integer> textureColours;

    /** Installs real texture colours; they take precedence over the built-in keyword table. */
    public static void useTextureColours(Map<String, Integer> colours) {
        textureColours = colours == null ? null : Map.copyOf(colours);
    }

    /** Render colour of a block: real texture colour, keyword table, then {@link BlockInfo#mapColor}. */
    public static int colourOf(BlockSpec s, BlockInfo info) {
        return new ColourTable(info).colorOf(s);
    }

    /** Whether renders use colours derived from real textures. */
    public static boolean hasTextureColours() {
        return textureColours != null;
    }

    /**
     * Keyword color table for ~100 common building blocks, used instead of {@link BlockInfo#mapColor} (which is a
     * rough estimate). Lookup order: exact name, dye/terracotta families, wood families, generic suffix rules;
     * {@code null} means "fall back to {@code BlockInfo.mapColor}".
     */
    private static final class ColourTable {
        private final BlockInfo info;
        private final Map<String, Integer> cache = new HashMap<>();

        ColourTable(BlockInfo info) {
            this.info = info;
        }

        int colorOf(BlockSpec s) {
            String n = s.name();
            Integer c = cache.get(n);
            if (c != null) return c;
            Map<String, Integer> real = textureColours;
            c = real == null ? null : real.get(n);
            if (c == null) c = lookup(n);
            if (c == null) {
                int m;
                try {
                    m = info.mapColor(s);
                } catch (RuntimeException e) {
                    m = 0x808080;
                }
                c = (m == 0) ? 0x9A9A9A : (m & 0xFFFFFF);
            }
            cache.put(n, c);
            return c;
        }

        private static Integer lookup(String n) {
            switch (n) {
                case "stone": return 0x7D7D7D;
                case "cobblestone": return 0x7A7A7A;
                case "mossy_cobblestone": return 0x6B7A5A;
                case "stone_bricks": return 0x7B7B7B;
                case "mossy_stone_bricks": return 0x6E7B61;
                case "cracked_stone_bricks": return 0x74716C;
                case "chiseled_stone_bricks": return 0x77756F;
                case "smooth_stone": return 0x9E9E9E;
                case "granite": return 0x9A6B5A;
                case "polished_granite": return 0xA4705C;
                case "diorite": return 0xC9C9CB;
                case "polished_diorite": return 0xD5D5D7;
                case "andesite": return 0x84878B;
                case "polished_andesite": return 0x8E9195;
                case "deepslate": return 0x585858;
                case "cobbled_deepslate": return 0x4F4F52;
                case "polished_deepslate": return 0x4A4A4D;
                case "deepslate_bricks": return 0x504F53;
                case "deepslate_tiles": return 0x4B4A4E;
                case "tuff": return 0x6F6F68;
                case "calcite": return 0xE0DFDA;
                case "dripstone_block": return 0x8B7358;
                case "blackstone": return 0x3A2C33;
                case "polished_blackstone": return 0x35303A;
                case "basalt": return 0x4C4C52;
                case "netherrack": return 0x6E2A2A;
                case "quartz_block": return 0xEDE8E0;
                case "smooth_quartz": return 0xEFEAE2;
                case "bricks": return 0x96594A;
                case "mud_bricks": return 0x8A6A55;
                case "packed_mud": return 0x9B8262;
                case "sandstone": return 0xD9CA9C;
                case "smooth_sandstone": return 0xDCCFA3;
                case "cut_sandstone": return 0xD8C99B;
                case "red_sandstone": return 0xB96A34;
                case "prismarine": return 0x6A9A8B;
                case "prismarine_bricks": return 0x7AAEA0;
                case "dark_prismarine": return 0x3F6E62;
                case "purpur_block": return 0xA97DA9;
                case "end_stone": return 0xD9D9A0;
                case "end_stone_bricks": return 0xDCDCA5;
                case "amethyst_block": return 0x8A6BC1;
                case "obsidian": return 0x1B1526;
                case "crying_obsidian": return 0x2C1B4A;
                case "bedrock": return 0x3A3A3A;
                case "sponge": return 0xC7C34B;
                case "gravel": return 0x8B8079;
                case "dirt": return 0x8B6A46;
                case "coarse_dirt": return 0x7C5F3E;
                case "grass_block": return 0x6E9C45;
                case "podzol": return 0x6B4F27;
                case "mycelium": return 0x8A7A85;
                case "moss_block": return 0x5C7D36;
                case "clay": return 0xA0A5B4;
                case "snow": return 0xF4FBFB;
                case "snow_block": return 0xF2F8F8;
                case "ice": return 0xA6C8F0;
                case "packed_ice": return 0x8FB6E8;
                case "blue_ice": return 0x76A7EA;
                case "sand": return 0xDCCB93;
                case "red_sand": return 0xB4602C;
                case "dirt_path": return 0x9A7E4A;
                case "farmland": return 0x6B4A2B;
                case "soul_sand": return 0x4C3A2C;
                case "soul_soil": return 0x4A3B2E;
                case "sculk": return 0x0D2224;
                case "glowstone": return 0xE3B85B;
                case "sea_lantern": return 0xC8E6DC;
                case "shroomlight": return 0xE9A04C;
                case "ochre_froglight": return 0xEAE2B4;
                case "verdant_froglight": return 0xB6D9B0;
                case "pearlescent_froglight": return 0xE9CFE3;
                case "magma_block": return 0x9A4A1F;
                case "lava": return 0xE86B1A;
                case "water": return 0x3E6FDE;
                case "netherite_block": return 0x453F42;
                case "ancient_debris": return 0x5E4A44;
                case "iron_block": return 0xD8D8D8;
                case "gold_block": return 0xF3D54A;
                case "diamond_block": return 0x60E4D4;
                case "emerald_block": return 0x36C95F;
                case "lapis_block": return 0x2A4FA8;
                case "redstone_block": return 0xA51A1A;
                case "copper_block": return 0xC0704B;
                case "oxidized_copper": return 0x4F9E86;
                case "weathered_copper": return 0x6E9C81;
                case "exposed_copper": return 0xA57A65;
                case "coal_block": return 0x1A1A1A;
                case "raw_iron_block": return 0xA88E75;
                case "hay_block": return 0xC9A63B;
                case "bookshelf": return 0x9A7B52;
                case "crafting_table": return 0x9C6B3F;
                case "barrel": return 0x8B6A42;
                case "chest": return 0x9C6B2F;
                case "trapped_chest": return 0x9C6B2F;
                case "furnace": return 0x8C8C8C;
                case "smoker": return 0x6E6461;
                case "blast_furnace": return 0x7C7C7C;
                case "lectern": return 0x9A7448;
                case "anvil": return 0x55565A;
                case "bell": return 0xE0B33C;
                case "campfire": return 0xC98B33;
                case "soul_campfire": return 0x3FAEA5;
                case "cauldron": return 0x4E4E50;
                case "decorated_pot": return 0xA87256;
                case "ladder": return 0xA07F4C;
                case "scaffolding": return 0xC5B36B;
                case "iron_chain": return 0x54575C;
                case "iron_bars": return 0x6A6E73;
                case "torch": case "wall_torch": return 0xFFCC55;
                case "soul_torch": case "soul_wall_torch": return 0x55D6E0;
                case "redstone_torch": case "redstone_wall_torch": return 0xC4382E;
                case "copper_torch": case "copper_wall_torch": return 0xE08A3C;
                case "lantern": return 0xF0C063;
                case "soul_lantern": return 0x6FD3D8;
                case "copper_lantern": return 0xE0A05A;
                case "jack_o_lantern": return 0xE68A2E;
                case "candle": return 0xE8D6A8;
                case "lily_pad": return 0x3E7A34;
                case "cobweb": return 0xE6E6E6;
                case "vine": case "vines": return 0x3E7A2E;
                case "glow_lichen": return 0x7FB8A0;
                case "terracotta": return 0x985F43;
                case "flower_pot": return 0x9C6B3F;
                case "air": return 0xFFFFFF;
                default:
                    break;
            }
            if (n.startsWith("potted_")) return 0x9C6B3F;
            if (n.endsWith("_terracotta")) {
                Integer col = DYE.get(n.substring(0, n.length() - "_terracotta".length()));
                if (col != null) return shade(col, 0.78);
            }
            if (n.endsWith("_wool") || n.endsWith("_carpet") || n.endsWith("_bed") || n.endsWith("_banner")
                    || n.endsWith("_candle") || n.endsWith("_concrete") || n.endsWith("_concrete_powder")
                    || n.endsWith("_stained_glass") || n.endsWith("_stained_glass_pane")
                    || n.endsWith("_glazed_terracotta") || n.endsWith("_shulker_box")) {
                int us = n.indexOf('_');
                Integer col = us > 0 ? DYE.get(n.substring(0, us)) : null;
                if (col != null) {
                    if (n.endsWith("_concrete")) return col;
                    if (n.endsWith("_concrete_powder")) return shade(col, 1.12);
                    if (n.contains("glazed")) return shade(col, 1.05);
                    if (n.endsWith("_wool") || n.endsWith("_carpet")) return shade(col, 1.08);
                    return col;
                }
            }
            for (String wood : Names.WOODS) {
                if (!n.startsWith(wood + "_")) continue;
                String rest = n.substring(wood.length() + 1);
                int base = WOOD.getOrDefault(wood, 0x9A7B52);
                return switch (rest) {
                    case "log", "wood", "stem", "hyphae", "bamboo_block", "stripped_log", "stripped_wood",
                         "stripped_stem", "stripped_hyphae", "stripped_bamboo_block" -> shade(base, 0.82);
                    case "leaves", "sapling" -> 0x4F8B2E;
                    default -> base;
                };
            }
            if (n.endsWith("_leaves")) return 0x4F8B2E;
            if (n.endsWith("_planks")) return 0x9A7B52;
            if (n.endsWith("_log") || n.endsWith("_wood")) return 0x6E5433;
            if (n.contains("glass") || n.endsWith("_pane")) return 0xC8E4FF;
            if (n.endsWith("_door") || n.endsWith("_trapdoor") || n.endsWith("_fence") || n.endsWith("_fence_gate")
                    || n.endsWith("_sign") || n.endsWith("_hanging_sign")) return 0x9A7B52;
            if (n.endsWith("_stairs") || n.endsWith("_slab")) return 0x8E8E8E;
            if (n.endsWith("_banner")) return 0xDDDDDD;
            if (n.endsWith("_button") || n.equals("lever") || n.endsWith("_pressure_plate")) return 0x8A8A8A;
            if (n.endsWith("_rail")) return 0x8C8C8C;
            if (n.endsWith("_coral") || n.endsWith("_coral_block") || n.endsWith("_coral_fan")) return 0xE06A6A;
            if (n.endsWith("_tulip") || n.endsWith("_orchid") || n.endsWith("_bluet") || n.equals("dandelion")
                    || n.equals("poppy") || n.endsWith("_flower")) return 0xE0C040;
            if (n.endsWith("_mushroom")) return 0xC24B3A;
            return null;
        }

        /** Immutable color tables, built once at class init and only ever read (safe across threads). */
        private static final Map<String, Integer> DYE = dyeTable();
        private static final Map<String, Integer> WOOD = woodTable();

        private static Map<String, Integer> dyeTable() {
            Map<String, Integer> m = new HashMap<>(24);
            m.put("white", 0xE9ECEC);
            m.put("orange", 0xF07613);
            m.put("magenta", 0xBD44B3);
            m.put("light_blue", 0x3AAFD9);
            m.put("yellow", 0xF8C627);
            m.put("lime", 0x70B919);
            m.put("pink", 0xED8DAC);
            m.put("gray", 0x3E4447);
            m.put("light_gray", 0x8E8E86);
            m.put("cyan", 0x158991);
            m.put("purple", 0x792AAC);
            m.put("blue", 0x35399D);
            m.put("brown", 0x724728);
            m.put("green", 0x546D1B);
            m.put("red", 0xA12722);
            m.put("black", 0x141519);
            return java.util.Collections.unmodifiableMap(m);
        }

        private static Map<String, Integer> woodTable() {
            Map<String, Integer> m = new HashMap<>(16);
            m.put("oak", 0xA0834F);
            m.put("spruce", 0x77593A);
            m.put("birch", 0xD5C98B);
            m.put("jungle", 0xA87B58);
            m.put("acacia", 0xB3642B);
            m.put("dark_oak", 0x4E3520);
            m.put("mangrove", 0x7A3F35);
            m.put("cherry", 0xE0A6A6);
            m.put("bamboo", 0xC7B54A);
            m.put("crimson", 0x8A3A50);
            m.put("warped", 0x2E7F79);
            m.put("pale_oak", 0xD9D2C4);
            return java.util.Collections.unmodifiableMap(m);
        }
    }
}
