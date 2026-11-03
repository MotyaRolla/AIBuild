package dev.aibuild.render;

import dev.aibuild.analysis.IsoRenderer;
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
 * PNG images sent to the model: a four-view sheet of a build, fitted into {@code maxSide} pixels (the gateway
 * bills an image at about 1k tokens regardless of size, so larger images only cost bandwidth), and the canary
 * image used to check that the gateway really delivers images.
 */
public final class ContactSheet {
    private ContactSheet() {
    }

    /** Four isometric views (front = south) of {@code model}, at most {@code maxSide} pixels on the longer side. */
    public static byte[] of(VoxelModel model, BlockInfo info, int maxSide) {
        int[] b = model.solidBounds();
        int span = b == null ? 8 : Math.max(8, (b[3] - b[0] + 1) + (b[5] - b[2] + 1) + (b[4] - b[1] + 1));
        int scale = Math.clamp(maxSide / span, 2, 24);
        BufferedImage sheet = IsoRenderer.renderSheet(model, info, scale);
        return png(fit(sheet, maxSide));
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
