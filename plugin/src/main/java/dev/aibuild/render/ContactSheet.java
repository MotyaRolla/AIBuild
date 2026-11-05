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

    // ------------------------------------------------------------------ one view per image
    //
    // A vision model gets a fixed token budget per image (DeepSeek: about 1300x1300 pixels' worth, at most 1024 tokens),
    // so six views packed into one sheet get a sixth of that each and fine detail is lost. The views a model judges
    // from are sent one per image, each at about the full budget; only the all-sides context stays a sheet.

    /** Width of a single-view image; height is 3/4 of it (1440 x 1080, about the per-image pixel budget). */
    public static final int VIEW_W = 1440;   // the default; the studio derives its own from image-size

    /** One rendered view as an image of its own, with its label. */
    public static byte[] single(VoxelModel model, BlockInfo info, VoxelRaster.Camera cam, VoxelRaster.Options opt, String label) {
        BufferedImage img = new BufferedImage(cam.width(), cam.height(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            tile(g, VoxelRaster.render(model, info, cam, opt), 0, 0, label);
        } finally {
            g.dispose();
        }
        return png(img);
    }

    /**
     * How a version looks, one view per image: the whole build from the front corner; the subject up close from the
     * other front corner (or the whole build from the back when there is no separate subject); a player's view from
     * in front of the subject; and a sheet with all four corners, the top and the back at eye level for context.
     * {@code subject} is the box of the main subject (a hero inside a scene), or null when the whole build is it.
     */
    public static java.util.List<byte[]> views(VoxelModel model, BlockInfo info, int[] subject, String subjectName, int w) {
        java.util.List<byte[]> out = new java.util.ArrayList<>();
        int[] b = model.solidBounds();
        if (b == null) {
            out.add(of(model, info, w));
            return out;
        }
        int h = w * 3 / 4;
        VoxelRaster.Options opt = VoxelRaster.Options.defaults();
        out.add(single(model, info, VoxelRaster.iso(b, 0, w, h), opt, "whole build from the south-east (front right)"));
        int[] above = aboveGround(model);
        int[] target = subject != null ? subject : above;
        if (subject != null) {
            out.add(single(model, info, VoxelRaster.front(subject, w, h), opt, "the subject (" + subjectName + ") close, straight from the front (south)"));
        } else {
            out.add(single(model, info, VoxelRaster.iso(b, 2, w, h), opt, "whole build from the north-west (back left)"));
        }
        int reach = (int) Math.ceil(Math.max(above[3] - above[0], above[5] - above[2]) * 2.5 + 80);
        out.add(single(model, info, VoxelRaster.stand(model, target, 0, w, h), opt.ground(reach),
                "eye level: a player standing in front (south)" + (subject != null ? " of " + subjectName : "")));
        out.add(corners(model, info, target, w));
        return out;
    }

    /** Context sheet: the four corners and the top, plus the back at eye level (3 x 2 tiles). */
    public static byte[] corners(VoxelModel model, BlockInfo info, int[] target, int maxSide) {
        int[] b = model.solidBounds();
        int tw = Math.max(160, maxSide / 3), th = Math.max(120, tw * 3 / 4);
        BufferedImage sheet = new BufferedImage(tw * 3, th * 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        try {
            if (b == null) return png(sheet);
            VoxelRaster.Options opt = VoxelRaster.Options.defaults();
            String[] names = {"south-east", "south-west", "north-west", "north-east"};
            for (int v = 0; v < 4; v++) tile(g, VoxelRaster.render(model, info, VoxelRaster.iso(b, v, tw, th), opt), (v % 3) * tw, (v / 3) * th, names[v]);
            int[] above = aboveGround(model);
            int reach = (int) Math.ceil(Math.max(above[3] - above[0], above[5] - above[2]) * 2.5 + 80);
            tile(g, VoxelRaster.render(model, info, VoxelRaster.stand(model, target == null ? above : target, 2, tw, th), opt.ground(reach)),
                    tw, th, "eye level from the back (north)");
            tile(g, VoxelRaster.render(model, info, VoxelRaster.top(b, tw, th), opt), 2 * tw, th, "top (north up)");
        } finally {
            g.dispose();
        }
        return png(sheet);
    }

    /** Two models side by side from one camera: {@code a} on the left, {@code b} on the right. */
    public static byte[] sideBySide(VoxelModel a, VoxelModel b, BlockInfo info, VoxelRaster.Camera cam, VoxelRaster.Options opt,
                                    String labelA, String labelB) {
        BufferedImage img = new BufferedImage(cam.width() * 2, cam.height(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            tile(g, VoxelRaster.render(a, info, cam, opt), 0, 0, labelA);
            tile(g, VoxelRaster.render(b, info, cam, opt), cam.width(), 0, labelB);
        } finally {
            g.dispose();
        }
        return png(img);
    }

    /** Half-image side of a side-by-side pair for a view width: 1440 gives two 864 x 864 halves, about the per-image budget. */
    public static int half(int viewW) {
        return Math.max(160, viewW * 3 / 5);
    }

    /**
     * A change, one image per camera with the version before on the left and after on the right, so the only
     * difference within an image is the change. {@code box} frames a region; null frames the whole build.
     */
    public static java.util.List<byte[]> beforeAfterViews(VoxelModel before, VoxelModel after, String beforeLabel, String afterLabel,
                                                          BlockInfo info, int[] box, int viewW) {
        int HALF = half(viewW);
        int[] u = box != null ? box : union(before.solidBounds(), after.solidBounds());
        java.util.List<byte[]> out = new java.util.ArrayList<>();
        if (u == null) return out;
        VoxelRaster.Options opt = VoxelRaster.Options.defaults();
        String[] side = {"south-east", "north-west"};
        for (int c = 0; c < 2; c++) {
            VoxelRaster.Camera cam = box == null ? VoxelRaster.iso(u, c * 2, HALF, HALF) : VoxelRaster.closeup(u, c * 2, HALF, HALF);
            out.add(sideBySide(before, after, info, cam, opt, beforeLabel + ": " + side[c], afterLabel + ": " + side[c]));
        }
        return out;
    }

    /**
     * Two builds for a blind comparison, one image per camera with A on the left and B on the right: the south-east
     * and south-west corners, and a player's view from the front, each camera the same for both.
     */
    public static java.util.List<byte[]> pairViews(VoxelModel a, VoxelModel b, BlockInfo info, int viewW) {
        return pairViews(a, b, info, viewW, null);
    }

    /**
     * As above, plus the subject (a box) close and straight from the front for both, when {@code subject} is not
     * null: the details a player looks at (a face, a door, a sign) are a few pixels in the whole-build views.
     */
    public static java.util.List<byte[]> pairViews(VoxelModel a, VoxelModel b, BlockInfo info, int viewW, int[] subject) {
        java.util.List<byte[]> out = pairViewsWhole(a, b, info, viewW);
        if (subject != null && !out.isEmpty()) {
            int HALF = half(viewW);
            out.add(sideBySide(a, b, info, VoxelRaster.front(subject, HALF, HALF), VoxelRaster.Options.defaults(),
                    "A: the subject from the front, close", "B: the subject from the front, close"));
        }
        return out;
    }

    private static java.util.List<byte[]> pairViewsWhole(VoxelModel a, VoxelModel b, BlockInfo info, int viewW) {
        int HALF = half(viewW);
        int[] u = union(a.solidBounds(), b.solidBounds());
        java.util.List<byte[]> out = new java.util.ArrayList<>();
        if (u == null) return out;
        int[] above = union(aboveGround(a), aboveGround(b));
        VoxelRaster.Options opt = VoxelRaster.Options.defaults();
        out.add(sideBySide(a, b, info, VoxelRaster.iso(u, 0, HALF, HALF), opt, "A: south-east", "B: south-east"));
        out.add(sideBySide(a, b, info, VoxelRaster.iso(u, 1, HALF, HALF), opt, "A: south-west", "B: south-west"));
        int reach = (int) Math.ceil(Math.max(above[3] - above[0], above[5] - above[2]) * 2.5 + 80);
        // the same standing point for both: chosen on the union, so neither build moves the camera
        VoxelRaster.Camera eye = VoxelRaster.stand(a.nonAirCount() >= b.nonAirCount() ? a : b, above, 0, HALF, HALF);
        out.add(sideBySide(a, b, info, eye, opt.ground(reach), "A: eye level from the front", "B: eye level from the front"));
        return out;
    }

    /** Close-ups of parts, one image each, framed on the part from the front corner. */
    public static java.util.List<byte[]> closeupViews(VoxelModel model, BlockInfo info, java.util.List<Focus> parts, int w) {
        java.util.List<byte[]> out = new java.util.ArrayList<>();
        for (Focus f : parts) {
            out.add(single(model, info, VoxelRaster.closeup(f.box(), 0, w, w * 3 / 4), VoxelRaster.Options.defaults(), "close-up: " + f.name()));
        }
        return out;
    }

    private static int[] union(int[] a, int[] b) {
        if (a == null) return b;
        if (b == null) return a;
        return new int[]{Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2]), Math.max(a[3], b[3]), Math.max(a[4], b[4]), Math.max(a[5], b[5])};
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
     * A change: the build before it (top row) and after it (bottom row), from the same two opposite corners, so the
     * only difference between the rows is the change. {@code box} is the region to frame; null frames the whole build.
     */
    public static byte[] beforeAfter(VoxelModel before, VoxelModel after, String beforeLabel, String afterLabel, BlockInfo info,
                                     int[] box, int maxSide) {
        int[] u = box;
        if (u == null) {
            int[] ba = before.solidBounds(), bb = after.solidBounds();
            u = ba == null ? bb : bb == null ? ba : new int[]{Math.min(ba[0], bb[0]), Math.min(ba[1], bb[1]), Math.min(ba[2], bb[2]),
                    Math.max(ba[3], bb[3]), Math.max(ba[4], bb[4]), Math.max(ba[5], bb[5])};
        }
        int tw = Math.max(160, maxSide / 2), th = Math.max(120, tw * 5 / 8);
        BufferedImage sheet = new BufferedImage(tw * 2, th * 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        try {
            if (u == null) return png(sheet);
            VoxelRaster.Options opt = VoxelRaster.Options.defaults();
            VoxelRaster.Camera[] cams = box == null ? new VoxelRaster.Camera[]{VoxelRaster.iso(u, 0, tw, th), VoxelRaster.iso(u, 2, tw, th)}
                    : new VoxelRaster.Camera[]{VoxelRaster.closeup(u, 0, tw, th), VoxelRaster.closeup(u, 2, tw, th)};
            String[] side = {"south-east", "north-west"};
            VoxelModel[] m = {before, after};
            String[] name = {beforeLabel, afterLabel};
            for (int r = 0; r < 2; r++) {
                for (int c = 0; c < 2; c++) tile(g, VoxelRaster.render(m[r], info, cams[c], opt), c * tw, r * th, name[r] + ": " + side[c]);
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
