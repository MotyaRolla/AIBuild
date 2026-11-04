package dev.aibuild.render;

import dev.aibuild.analysis.CraftReview;
import dev.aibuild.model.BlockInfo;
import dev.aibuild.model.BlockSpec;
import dev.aibuild.model.VoxelModel;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Draws the {@link CraftReview red pen} on renders: a numbered red box around each mark, in every view where the
 * marked surface can be seen, so the model connects what it sees to the coordinates it reads. A mark hidden in
 * every view is drawn dashed in the view that faces it best.
 */
public final class RedPen {
    private RedPen() {
    }

    private static final Color RED = new Color(0xE01B24), HALO = new Color(255, 255, 255, 220);
    private static final String[] NAMES = {"south-east", "south-west", "north-west", "north-east"};

    /** The four corner views (2 x 2) with the marks drawn on them. */
    public static byte[] sheet(VoxelModel model, BlockInfo info, List<CraftReview.Mark> marks, int maxSide) {
        int[] b = model.solidBounds();
        if (b == null) return ContactSheet.of(model, info, maxSide);
        int tw = maxSide / 2, th = tw * 3 / 4;
        BufferedImage sheet = new BufferedImage(tw * 2, th * 2, BufferedImage.TYPE_INT_RGB);
        VoxelRaster.Camera[] cams = new VoxelRaster.Camera[4];
        BufferedImage[] imgs = new BufferedImage[4];
        boolean[][] seen = new boolean[4][marks.size()];
        for (int v = 0; v < 4; v++) {
            cams[v] = VoxelRaster.iso(b, v, tw, th);
            imgs[v] = VoxelRaster.render(model, info, cams[v], VoxelRaster.Options.defaults());
            for (int i = 0; i < marks.size(); i++) seen[v][i] = visible(model, info, cams[v], marks.get(i));
        }
        for (int i = 0; i < marks.size(); i++) {
            boolean any = false;
            for (int v = 0; v < 4; v++) any |= seen[v][i];
            if (any) continue;
            int best = bestView(cams, marks.get(i));
            draw(imgs[best], cams[best], marks.get(i), true);
        }
        Graphics2D g = sheet.createGraphics();
        try {
            for (int v = 0; v < 4; v++) {
                for (int i = 0; i < marks.size(); i++) if (seen[v][i]) draw(imgs[v], cams[v], marks.get(i), false);
                int x = (v % 2) * tw, y = (v / 2) * th;
                g.drawImage(imgs[v], x, y, null);
                g.setColor(new Color(0xD5DCE6));
                g.drawRect(x, y, tw - 1, th - 1);
                label(g, "red pen: " + NAMES[v] + " (numbers = notes in the text)", x, y);
            }
        } finally {
            g.dispose();
        }
        return ContactSheet.png(sheet);
    }

    /** Draws the marks visible through {@code cam} onto an image rendered with it. */
    public static void annotate(BufferedImage img, VoxelModel model, BlockInfo info, VoxelRaster.Camera cam, List<CraftReview.Mark> marks) {
        for (CraftReview.Mark m : marks) if (visible(model, info, cam, m)) draw(img, cam, m, false);
    }

    private static int bestView(VoxelRaster.Camera[] cams, CraftReview.Mark m) {
        if (m.normal() == null) return 0;
        int best = 0;
        double bestDot = -2;
        for (int v = 0; v < cams.length; v++) {
            double[] t = VoxelRaster.towardCamera(cams[v], centre(m.box()));
            double d = t[0] * m.normal()[0] + t[1] * m.normal()[1] + t[2] * m.normal()[2];
            if (d > bestDot) {
                bestDot = d;
                best = v;
            }
        }
        return best;
    }

    /** Whether some point of the marked surface (or piece) can be seen from the camera. */
    static boolean visible(VoxelModel model, BlockInfo info, VoxelRaster.Camera cam, CraftReview.Mark m) {
        int[] b = m.box();
        int[] n = m.normal();
        int[] mb = model.solidBounds();
        if (mb == null) return false;
        for (double[] p : samples(b, n)) {
            double[] t = VoxelRaster.towardCamera(cam, p);
            if (n != null && t[0] * n[0] + t[1] * n[1] + t[2] * n[2] < 0.12) return false;   // the face looks away
            if (clear(model, info, p, t, b, mb)) return true;
        }
        return false;
    }

    private static List<double[]> samples(int[] b, int[] n) {
        List<double[]> out = new java.util.ArrayList<>();
        double[] lo = {b[0], b[1], b[2]}, hi = {b[3] + 1, b[4] + 1, b[5] + 1};
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                double[] p = new double[3];
                for (int a = 0; a < 3; a++) p[a] = lo[a] + (hi[a] - lo[a]) * 0.5;
                if (n == null) {
                    // a piece: its centre and points spread through its box
                    p[0] = lo[0] + (hi[0] - lo[0]) * (0.2 + 0.3 * i);
                    p[2] = lo[2] + (hi[2] - lo[2]) * (0.2 + 0.3 * j);
                } else {
                    int axis = n[0] != 0 ? 0 : n[1] != 0 ? 1 : 2;
                    int ua = axis == 0 ? 2 : 0, va = axis == 1 ? 2 : 1;
                    p[axis] = n[axis] > 0 ? hi[axis] + 0.02 : lo[axis] - 0.02;
                    p[ua] = lo[ua] + (hi[ua] - lo[ua]) * (0.2 + 0.3 * i);
                    p[va] = lo[va] + (hi[va] - lo[va]) * (0.2 + 0.3 * j);
                }
                out.add(p);
            }
        }
        return out;
    }

    /** Marches from {@code p} towards the camera; false when an opaque block outside the mark is in the way. */
    private static boolean clear(VoxelModel model, BlockInfo info, double[] p, double[] t, int[] box, int[] mb) {
        double[] q = p.clone();
        int lx = Integer.MIN_VALUE, ly = 0, lz = 0;
        for (int step = 0; step < 4000; step++) {
            q[0] += t[0] * 0.25;
            q[1] += t[1] * 0.25;
            q[2] += t[2] * 0.25;
            int x = (int) Math.floor(q[0]), y = (int) Math.floor(q[1]), z = (int) Math.floor(q[2]);
            if (x < mb[0] - 1 || x > mb[3] + 1 || y > mb[4] + 1 || z < mb[2] - 1 || z > mb[5] + 1 || y < mb[1] - 1) return true;
            if (x == lx && y == ly && z == lz) continue;
            lx = x;
            ly = y;
            lz = z;
            if (x >= box[0] && x <= box[3] && y >= box[1] && y <= box[4] && z >= box[2] && z <= box[5]) continue;
            BlockSpec s = model.get(x, y, z);
            if (s != null && !s.isAir() && info.isOccluding(s)) return false;
        }
        return true;
    }

    private static void draw(BufferedImage img, VoxelRaster.Camera cam, CraftReview.Mark m, boolean dashed) {
        int[] b = m.box();
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (int i = 0; i < 8; i++) {
            double[] p = VoxelRaster.project(cam, (i & 1) == 0 ? b[0] : b[3] + 1, (i & 2) == 0 ? b[1] : b[4] + 1,
                    (i & 4) == 0 ? b[2] : b[5] + 1);
            if (p == null) return;
            minX = Math.min(minX, p[0]);
            minY = Math.min(minY, p[1]);
            maxX = Math.max(maxX, p[0]);
            maxY = Math.max(maxY, p[1]);
        }
        int pad = 3;
        int x = (int) Math.floor(minX) - pad, y = (int) Math.floor(minY) - pad;
        int w = Math.max(14, (int) Math.ceil(maxX - minX) + 2 * pad), h = Math.max(14, (int) Math.ceil(maxY - minY) + 2 * pad);
        if (x + w < 0 || y + h < 0 || x > img.getWidth() || y > img.getHeight()) return;
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Stroke line = dashed ? new BasicStroke(3f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{8f, 6f}, 0f)
                    : new BasicStroke(3f);
            g.setColor(HALO);
            g.setStroke(new BasicStroke(5f));
            g.drawRect(x, y, w, h);
            g.setColor(RED);
            g.setStroke(line);
            g.drawRect(x, y, w, h);
            String num = String.valueOf(m.id());
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
            int tw = g.getFontMetrics().stringWidth(num);
            int d = Math.max(22, tw + 10);
            int cx = Math.max(0, Math.min(img.getWidth() - d, x - d / 2)), cy = Math.max(0, Math.min(img.getHeight() - d, y - d / 2));
            g.setColor(HALO);
            g.fillOval(cx - 2, cy - 2, d + 4, d + 4);
            g.setColor(RED);
            g.fillOval(cx, cy, d, d);
            g.setColor(Color.WHITE);
            g.drawString(num, cx + (d - tw) / 2, cy + d / 2 + 5);
        } finally {
            g.dispose();
        }
    }

    private static double[] centre(int[] b) {
        return new double[]{(b[0] + b[3] + 1) / 2.0, (b[1] + b[4] + 1) / 2.0, (b[2] + b[5] + 1) / 2.0};
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
}
