package dev.aibuild.render;

import dev.aibuild.model.BlockInfo;
import dev.aibuild.model.VoxelModel;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * PNG images sent to the model: the overview sheet of a build, close-ups of named parts and floor cutaways,
 * fitted into {@code maxSide} pixels (the gateway bills an image at about 1k tokens regardless of size, so larger
 * images only cost bandwidth), and the canary image used to check that the gateway really delivers images.
 */
public final class ContactSheet {
    private ContactSheet() {
    }

    /**
     * The overview sheet of a build: four isometric views (front = south), what a player standing in front of it
     * sees, and the plan from above — rendered with real textures by {@link VoxelRaster}, fitted into
     * {@code maxSide} pixels (3 × 2 labelled tiles).
     */
    public static byte[] of(VoxelModel model, BlockInfo info, int maxSide) {
        return png(overview(model, info, maxSide));
    }

    public static BufferedImage overview(VoxelModel model, BlockInfo info, int maxSide) {
        int[] b = model.solidBounds();
        int tw = Math.max(160, maxSide / 3), th = Math.max(120, tw * 3 / 4);
        BufferedImage sheet = new BufferedImage(tw * 3, th * 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        try {
            if (b == null) {
                label(g, "empty build", 0, 0);
                return sheet;
            }
            VoxelRaster.Options opt = VoxelRaster.Options.defaults();
            String[] names = {"south-east (front right)", "south-west (front left)", "north-west (back left)", "north-east (back right)"};
            for (int v = 0; v < 4; v++) {
                tile(g, VoxelRaster.render(model, info, VoxelRaster.iso(b, v, tw, th), opt), (v % 3) * tw, (v / 3) * th, names[v]);
            }
            int[] above = aboveGround(model);
            VoxelRaster.Camera eye = VoxelRaster.eye(above, tw, th);
            int reach = (int) Math.ceil(Math.max(above[3] - above[0], above[4] - above[1]) * 2.5 + 80);
            tile(g, VoxelRaster.render(model, info, eye, opt.ground(reach)), tw, th, "eye level from the front (south)");
            tile(g, VoxelRaster.render(model, info, VoxelRaster.top(b, tw, th), opt), 2 * tw, th, "top (north up)");
        } finally {
            g.dispose();
        }
        return sheet;
    }

    /** A named box to show up close. */
    public record Focus(String name, int[] box) {
    }

    /**
     * Close-ups of up to four parts (2 × 2 tiles), each framed on its box from the side that faces the viewer
     * most (front views first), with the rest of the build still visible around it.
     */
    public static byte[] closeups(VoxelModel model, BlockInfo info, java.util.List<Focus> parts, int maxSide) {
        int n = Math.min(4, parts.size());
        if (n == 0) return of(model, info, maxSide);
        int cols = n == 1 ? 1 : 2, rows = n <= 2 ? 1 : 2;
        int tw = maxSide / cols, th = n == 1 ? maxSide * 3 / 4 : tw * 3 / 4;
        BufferedImage sheet = new BufferedImage(tw * cols, th * rows, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        try {
            for (int i = 0; i < n; i++) {
                Focus f = parts.get(i);
                VoxelRaster.Camera cam = VoxelRaster.closeup(f.box(), 0, tw, th);
                tile(g, VoxelRaster.render(model, info, cam, VoxelRaster.Options.defaults()), (i % cols) * tw, (i / cols) * th,
                        "close-up: " + f.name());
            }
        } finally {
            g.dispose();
        }
        return png(sheet);
    }

    /** One view for {@link #shots}: a camera, a label, and an optional height above which blocks are removed. */
    public record Shot(String label, VoxelRaster.Camera camera, Integer clipY) {
    }

    /** Tile size of a sheet of {@code n} views fitted into {@code maxSide}: {width, height, cols}. */
    public static int[] tileSize(int n, int maxSide) {
        int cols = n == 1 ? 1 : 2;
        int tw = maxSide / cols, th = n == 1 ? maxSide * 3 / 4 : tw * 3 / 4;
        return new int[]{tw, th, cols};
    }

    /**
     * Up to four free views (2 x 2 tiles), each from its own camera: what the model asked to look at (eye level
     * from where a player stands, a part from a chosen side, the plan from above). Cameras must be made with
     * {@link #tileSize} for the same count.
     */
    public static byte[] shots(VoxelModel model, BlockInfo info, java.util.List<Shot> shots, int maxSide) {
        int n = Math.min(4, shots.size());
        if (n == 0) return of(model, info, maxSide);
        int[] t = tileSize(n, maxSide);
        int tw = t[0], th = t[1], cols = t[2], rows = n <= 2 ? 1 : 2;
        BufferedImage sheet = new BufferedImage(tw * cols, th * rows, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        try {
            for (int i = 0; i < n; i++) {
                Shot s = shots.get(i);
                VoxelRaster.Options o = s.clipY() == null ? VoxelRaster.Options.defaults() : VoxelRaster.Options.defaults().clip(s.clipY());
                tile(g, VoxelRaster.render(model, info, s.camera(), o), (i % cols) * tw, (i / cols) * th, s.label());
            }
        } finally {
            g.dispose();
        }
        return png(sheet);
    }

    /**
     * Alternatives of one design choice side by side: each model from the same camera on {@code box}, up to six
     * tiles, so the only difference between tiles is the choice.
     */
    public static byte[] compare(java.util.List<VoxelModel> models, java.util.List<String> labels, BlockInfo info, int[] box, int maxSide) {
        int n = Math.min(6, models.size());
        int cols = n <= 2 ? n : n <= 4 ? 2 : 3, rows = (n + cols - 1) / cols;
        int tw = maxSide / cols, th = tw * 3 / 4;
        BufferedImage sheet = new BufferedImage(tw * cols, th * rows, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        try {
            VoxelRaster.Camera cam = VoxelRaster.closeup(box, 0, tw, th);
            for (int i = 0; i < n; i++) {
                tile(g, VoxelRaster.render(models.get(i), info, cam, VoxelRaster.Options.defaults()), (i % cols) * tw, (i / cols) * th,
                        labels.get(i));
            }
        } finally {
            g.dispose();
        }
        return png(sheet);
    }

    /**
     * Two builds for a blind comparison: A on the top row, B below, each from the south-east and south-west corners
     * and at eye level from the front, all with the same cameras.
     */
    public static byte[] pair(VoxelModel a, VoxelModel b, BlockInfo info, int maxSide) {
        int[] ba = a.solidBounds(), bb = b.solidBounds();
        int[] u = ba == null ? bb : bb == null ? ba : new int[]{Math.min(ba[0], bb[0]), Math.min(ba[1], bb[1]), Math.min(ba[2], bb[2]),
                Math.max(ba[3], bb[3]), Math.max(ba[4], bb[4]), Math.max(ba[5], bb[5])};
        int tw = Math.max(160, maxSide / 3), th = Math.max(120, tw * 3 / 4);
        BufferedImage sheet = new BufferedImage(tw * 3, th * 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        try {
            if (u == null) return png(sheet);
            int[] ga = aboveGround(a), gb = aboveGround(b);
            int[] above = ga == null ? gb : gb == null ? ga : new int[]{Math.min(ga[0], gb[0]), Math.min(ga[1], gb[1]), Math.min(ga[2], gb[2]),
                    Math.max(ga[3], gb[3]), Math.max(ga[4], gb[4]), Math.max(ga[5], gb[5])};
            VoxelRaster.Camera se = VoxelRaster.iso(u, 0, tw, th), sw = VoxelRaster.iso(u, 1, tw, th), eye = VoxelRaster.eye(above, tw, th);
            int reach = (int) Math.ceil(Math.max(above[3] - above[0], above[4] - above[1]) * 2.5 + 80);
            VoxelRaster.Options opt = VoxelRaster.Options.defaults();
            VoxelModel[] m = {a, b};
            String[] name = {"A", "B"};
            for (int r = 0; r < 2; r++) {
                tile(g, VoxelRaster.render(m[r], info, se, opt), 0, r * th, name[r] + ": south-east");
                tile(g, VoxelRaster.render(m[r], info, sw, opt), tw, r * th, name[r] + ": south-west");
                tile(g, VoxelRaster.render(m[r], info, eye, opt.ground(reach)), 2 * tw, r * th, name[r] + ": eye level from the front");
            }
        } finally {
            g.dispose();
        }
        return png(sheet);
    }

    /**
     * Interiors: up to four rooms (2 × 2 tiles), each seen steeply from above with everything higher than its
     * furniture removed, so the model sees what stands in the room. {@code rooms} are {x1,z1,x2,z2,y,label...}
     * boxes; y is the layer players stand in.
     */
    public static byte[] interiors(VoxelModel model, BlockInfo info, java.util.List<int[]> rooms, java.util.List<String> labels, int maxSide) {
        int n = Math.min(4, rooms.size());
        if (n == 0) return of(model, info, maxSide);
        int cols = n == 1 ? 1 : 2, rows = n <= 2 ? 1 : 2;
        int tw = maxSide / cols, th = n == 1 ? maxSide * 3 / 4 : tw * 3 / 4;
        BufferedImage sheet = new BufferedImage(tw * cols, th * rows, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        try {
            for (int i = 0; i < n; i++) {
                int[] r = rooms.get(i);
                int clip = r[4] + 2;
                int[] box = {r[0] - 1, r[4] - 1, r[1] - 1, r[2] + 1, clip, r[3] + 1};
                tile(g, VoxelRaster.render(model, info, VoxelRaster.room(box, 0, tw, th), VoxelRaster.Options.defaults().clip(clip)),
                        (i % cols) * tw, (i / cols) * th, labels.get(i));
            }
        } finally {
            g.dispose();
        }
        return png(sheet);
    }

        /** One floor seen from above with everything higher removed (interiors). */
    public static BufferedImage cutaway(VoxelModel model, BlockInfo info, int clipY, int w, int h) {
        int[] b = model.solidBounds();
        if (b == null) return new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] cut = b.clone();
        cut[4] = Math.min(b[4], clipY);
        return VoxelRaster.render(model, info, VoxelRaster.iso(cut, 0, w, h), VoxelRaster.Options.defaults().clip(clipY));
    }

    /** Bounds of the blocks above the ground layer (y >= 1), or all solid bounds when there are none. */
    static int[] aboveGround(VoxelModel model) {
        int[] out = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        model.forEach((x, y, z, s) -> {
            if (y < 1 || s == null || s.isAir()) return;
            out[0] = Math.min(out[0], x);
            out[1] = Math.min(out[1], y);
            out[2] = Math.min(out[2], z);
            out[3] = Math.max(out[3], x);
            out[4] = Math.max(out[4], y);
            out[5] = Math.max(out[5], z);
        });
        return out[0] == Integer.MAX_VALUE ? model.solidBounds() : out;
    }

    private static void tile(Graphics2D g, BufferedImage img, int x, int y, String name) {
        g.drawImage(img, x, y, null);
        g.setColor(new Color(0xD5DCE6));
        g.drawRect(x, y, img.getWidth() - 1, img.getHeight() - 1);
        label(g, name, x, y);
    }

    private static void label(Graphics2D g, String text, int x, int y) {
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        int w = g.getFontMetrics().stringWidth(text);
        g.setColor(new Color(255, 255, 255, 200));
        g.fillRect(x + 4, y + 4, w + 10, 20);
        g.setColor(new Color(0x1F2A44));
        g.drawString(text, x + 9, y + 19);
    }

    /** A large rendered number for {@link #visionProbe}. */
    public static byte[] digits(String text) {
        BufferedImage img = new BufferedImage(360, 160, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(new Color(0x1F2A44));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 110));
            int w = g.getFontMetrics().stringWidth(text);
            g.drawString(text, (img.getWidth() - w) / 2, 120);
        } finally {
            g.dispose();
        }
        return png(img);
    }

    static BufferedImage fit(BufferedImage img, int maxSide) {
        int w = img.getWidth(), h = img.getHeight();
        double k = Math.min(1.0, (double) maxSide / Math.max(w, h));
        if (k >= 1.0) return img;
        int nw = Math.max(1, (int) Math.round(w * k)), nh = Math.max(1, (int) Math.round(h * k));
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, nw, nh);
            g.drawImage(img, 0, 0, nw, nh, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    static byte[] png(BufferedImage img) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
