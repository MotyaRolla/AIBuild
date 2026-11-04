package dev.aibuild.render;

import dev.aibuild.analysis.IsoRenderer;
import dev.aibuild.model.BlockInfo;
import dev.aibuild.model.BlockSpec;
import dev.aibuild.model.VoxelModel;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * Ray-cast renderer for {@link VoxelModel}s: what a player would see, so the model (and people) can judge
 * micro-detail. Unlike the painter's {@link IsoRenderer} it supports perspective, eye-level and close-up cameras.
 *
 * <ul>
 *   <li>Faces are textured from the real client textures ({@link BlockTextures}) with mip-mapping; without
 *       textures every face uses the block's average colour.</li>
 *   <li>Partial blocks have their real geometry (slabs, stairs, fences/walls/panes connecting to neighbours,
 *       trapdoors, doors, carpets, lanterns, chains...); plants are crossed planes, leaves are cut-out cubes,
 *       glass and water are translucent.</li>
 *   <li>Lighting: a key light from the camera's upper left (every view is lit), shadows, Minecraft-style corner
 *       ambient occlusion, emissive blocks, and dark lines at depth and normal edges for readable silhouettes.</li>
 *   <li>Natural ground (unset cells at y &le; 0, see the script coordinate rules) is drawn as grass around the build.</li>
 * </ul>
 *
 * Rendering is read-only and thread-safe; rows are rendered in parallel.
 */
public final class VoxelRaster {
    private VoxelRaster() {
    }

    /** Render options. {@code clipY}: cells above this y are hidden (floor cutaway); {@code ground}: margin of drawn grass. */
    public record Options(int clipY, int ground, boolean outlines) {
        public static Options defaults() {
            return new Options(Integer.MAX_VALUE, 4, true);
        }

        public Options clip(int y) {
            return new Options(y, ground, outlines);
        }

        public Options ground(int margin) {
            return new Options(clipY, margin, outlines);
        }
    }

    // ------------------------------------------------------------------ cameras

    /** A pinhole (perspective) or orthographic camera with an image size. */
    public static final class Camera {
        final boolean ortho;
        final double[] pos, fwd, right, up;
        /** Ortho: world units per pixel. Perspective: focal length in pixels. */
        final double k;
        final int w, h;

        Camera(boolean ortho, double[] pos, double[] fwd, double k, int w, int h) {
            this.ortho = ortho;
            this.pos = pos;
            this.fwd = norm(fwd);
            double[] r = cross(this.fwd, new double[]{0, 1, 0});
            if (len(r) < 1e-6) r = new double[]{1, 0, 0};
            this.right = norm(r);
            this.up = cross(this.right, this.fwd);
            this.k = k;
            this.w = w;
            this.h = h;
        }

        public int width() {
            return w;
        }

        public int height() {
            return h;
        }
    }

    /** Azimuth (degrees, 0 = looking north from the south) of the four isometric views: SE, SW, NW, NE. */
    private static final double[] ISO_AZ = {-45, 45, 135, 225};

    /** Isometric-style orthographic view: 0 = from south-east, 1 = south-west, 2 = north-west, 3 = north-east. */
    public static Camera iso(int[] b, int view, int w, int h) {
        return orthoFit(b, dir(ISO_AZ[Math.floorMod(view, 4)], -30), w, h, 0.94);
    }

    /** Straight top-down orthographic view (north up). */
    public static Camera top(int[] b, int w, int h) {
        return orthoFit(b, new double[]{0, -1, -1e-4}, w, h, 0.94);
    }

    /** Orthographic elevation from the south (front) or another side: 0 = south, 1 = west, 2 = north, 3 = east. */
    public static Camera elevation(int[] b, int side, int w, int h) {
        return orthoFit(b, dir(new double[]{0, 90, 180, 270}[Math.floorMod(side, 4)], -8), w, h, 0.94);
    }

    /**
     * What a player standing in front of the build (south) sees: eye height 1.62 above the ground, far enough
     * back that the whole build fits a 70° view.
     */
    public static Camera eye(int[] b, int w, int h) {
        double fov = Math.toRadians(70), aspect = (double) w / h;
        double cx = (b[0] + b[3] + 1) / 2.0, cz = (b[2] + b[5] + 1) / 2.0;
        double width = b[3] - b[0] + 1, height = b[4] - Math.max(b[1], 1) + 1, depth = b[5] - b[2] + 1;
        double eyeY = 2.62;
        double vHalf = Math.atan(Math.tan(fov / 2) / Math.max(1, aspect));
        double hHalf = Math.atan(Math.tan(fov / 2) * Math.min(1, aspect));
        double dist = Math.max(width / 2 / Math.tan(hHalf), (height / 2 + 1) / Math.tan(vHalf * 0.95)) * 1.08 + depth / 2;
        double[] pos = {cx, eyeY, cz + Math.max(depth / 2 + 3, dist)};
        double aimY = Math.min(b[4] + 1, Math.max(eyeY, (Math.max(b[1], 1) + b[4] + 1) / 2.0));
        double[] at = {cx, aimY, cz};
        return look(pos, at, 70, w, h);
    }

    /**
     * A player standing in front of {@code target} (side 0 = south, 1 = west, 2 = north, 3 = east): eye 1.62 above the
     * surface where they stand, as close as lets the target fill the view, looking at its lower middle. Unlike
     * {@link #eye}, which fits a whole scene into the frame from far away, this is the view a visitor judges the
     * subject from; what stands in between stays in the picture, as it would in the game.
     */
    public static Camera stand(dev.aibuild.model.VoxelModel model, int[] target, int side, int w, int h) {
        double fov = Math.toRadians(70), aspect = (double) w / h;
        double vHalf = Math.atan(Math.tan(fov / 2) / Math.max(1, aspect));
        double hHalf = Math.atan(Math.tan(fov / 2) * Math.min(1, aspect));
        int sd = Math.floorMod(side, 4);
        boolean alongX = sd == 1 || sd == 3;
        double cx = (target[0] + target[3] + 1) / 2.0, cz = (target[2] + target[5] + 1) / 2.0;
        double base = Math.max(target[1], 0), height = target[4] + 1 - base;
        double width = alongX ? target[5] - target[2] + 1 : target[3] - target[0] + 1;
        double depth = alongX ? target[3] - target[0] + 1 : target[5] - target[2] + 1;
        double fit = Math.max(width / 2 / Math.tan(hHalf * 0.92), height * 0.62 / Math.tan(vHalf * 0.92));
        double dist = Math.max(depth / 2 + 4, fit + depth / 2 * 0.5);
        double[] off = switch (sd) {
            case 1 -> new double[]{-dist, 0};
            case 2 -> new double[]{0, -dist};
            case 3 -> new double[]{dist, 0};
            default -> new double[]{0, dist};
        };
        double px = cx + off[0], pz = cz + off[1];
        int ground = surface(model, (int) Math.floor(px), (int) Math.floor(pz));
        double eyeY = ground + 1 + 1.62;
        double[] at = {cx, Math.max(eyeY, base + height * 0.45), cz};
        return look(new double[]{px, eyeY, pz}, at, 70, w, h);
    }

    /** The highest solid cell of the column (water counts, plants and leaves do not), or 0 for open ground. */
    public static int surface(dev.aibuild.model.VoxelModel model, int x, int z) {
        int[] b = model.solidBounds();
        if (b == null) return 0;
        for (int y = b[4]; y >= Math.min(0, b[1]); y--) {
            dev.aibuild.model.BlockSpec s = model.get(x, y, z);
            if (s == null || s.isAir()) continue;
            String n = s.name();
            if (n.contains("leaves") || n.contains("grass") && !n.equals("grass_block") || n.contains("flower") || n.contains("petals")
                    || n.contains("fern") || n.contains("carpet") || n.contains("vine") || n.endsWith("_log") || n.endsWith("_wood")) continue;
            return y;
        }
        return 0;
    }

    /** Perspective camera at {@code from} looking at {@code at} with a vertical/horizontal field of view (larger side). */
    public static Camera look(double[] from, double[] at, double fovDeg, int w, int h) {
        double[] f = sub(at, from);
        double focal = Math.max(w, h) / 2.0 / Math.tan(Math.toRadians(fovDeg) / 2);
        return new Camera(false, from, f, focal, w, h);
    }

    /** Steep orthographic view down into a room box (walls and floors above it clipped by the caller). */
    public static Camera room(int[] box, int view, int w, int h) {
        return orthoFit(box, dir(ISO_AZ[Math.floorMod(view, 4)], -58), w, h, 0.96);
    }

        /** Perspective close-up of a box (a part), from one of the four iso directions, slightly above. */
    public static Camera closeup(int[] box, int view, int w, int h) {
        double[] c = {(box[0] + box[3] + 1) / 2.0, (box[1] + box[4] + 1) / 2.0, (box[2] + box[5] + 1) / 2.0};
        double rx = (box[3] - box[0] + 1) / 2.0, ry = (box[4] - box[1] + 1) / 2.0, rz = (box[5] - box[2] + 1) / 2.0;
        double radius = Math.sqrt(rx * rx + ry * ry + rz * rz) + 1;
        double fov = 45;
        double dist = radius / Math.sin(Math.toRadians(fov) / 2) * 0.92;
        double[] d = dir(ISO_AZ[Math.floorMod(view, 4)], -22);
        double[] pos = {c[0] - d[0] * dist, c[1] - d[1] * dist, c[2] - d[2] * dist};
        return look(pos, c, fov, w, h);
    }

    /**
     * Where a world point lands in the image: {px, py, depth along the view}, or null when it is behind a
     * perspective camera. The inverse of the ray each pixel shoots.
     */
    public static double[] project(Camera c, double x, double y, double z) {
        double[] v = {x - c.pos[0], y - c.pos[1], z - c.pos[2]};
        double depth = dot(v, c.fwd);
        double sx, sy;
        if (c.ortho) {
            sx = dot(v, c.right) / c.k;
            sy = -dot(v, c.up) / c.k;
        } else {
            if (depth <= 1e-3) return null;
            sx = dot(v, c.right) * c.k / depth;
            sy = -dot(v, c.up) * c.k / depth;
        }
        return new double[]{sx + c.w / 2.0, sy + c.h / 2.0, depth};
    }

    /** Unit direction from a world point towards the camera. */
    static double[] towardCamera(Camera c, double[] p) {
        return c.ortho ? scale(c.fwd, -1) : norm(sub(c.pos, p));
    }

    private static Camera orthoFit(int[] b, double[] d, int w, int h, double fill) {
        Camera probe = new Camera(true, new double[3], d, 1, w, h);
        double minU = Double.MAX_VALUE, maxU = -Double.MAX_VALUE, minV = Double.MAX_VALUE, maxV = -Double.MAX_VALUE;
        for (int i = 0; i < 8; i++) {
            double[] p = {(i & 1) == 0 ? b[0] : b[3] + 1, (i & 2) == 0 ? b[1] : b[4] + 1, (i & 4) == 0 ? b[2] : b[5] + 1};
            double u = dot(p, probe.right), v = dot(p, probe.up);
            minU = Math.min(minU, u);
            maxU = Math.max(maxU, u);
            minV = Math.min(minV, v);
            maxV = Math.max(maxV, v);
        }
        double k = Math.max((maxU - minU) / (w * fill), (maxV - minV) / (h * fill));
        double cu = (minU + maxU) / 2, cv = (minV + maxV) / 2;
        double[] center = add(scale(probe.right, cu), scale(probe.up, cv));
        double back = 2000;
        double[] pos = sub(center, scale(probe.fwd, back));
        return new Camera(true, pos, d, Math.max(1e-3, k), w, h);
    }

    /** Unit view direction for an azimuth (0 = looking north, i.e. camera south of the target) and pitch. */
    private static double[] dir(double azDeg, double pitchDeg) {
        double az = Math.toRadians(azDeg), p = Math.toRadians(pitchDeg);
        return new double[]{Math.sin(az) * Math.cos(p), Math.sin(p), -Math.cos(az) * Math.cos(p)};
    }

    // ------------------------------------------------------------------ appearance

    private static final byte SOLID = 0, CUTOUT = 1, GLASS = 2, WATER = 3, CROSS = 4;

    /** How one cell looks: sub-boxes in cell units, textures, kind. */
    private static final class Look {
        float[][] boxes = {{0, 0, 0, 1, 1, 1}};
        int top = -1, side = -1, bottom = -1;
        int colour;
        byte kind = SOLID;
        /** Counts as a full occluder for ambient occlusion and connections. */
        boolean occludes;
        float emission;
        /** Log axis: which axis carries the end texture (0 = x, 1 = y, 2 = z). */
        int axis = 1;
        String connectGroup;
    }

    private static final float P = 1f / 16;

    /** Minecraft dye colours, for blocks whose texture is not a plain cube face (candles, banners). */
    private static int dye(String colour, int fallback) {
        return switch (colour) {
            case "white" -> 0xF9FFFE;
            case "orange" -> 0xF9801D;
            case "magenta" -> 0xC74EBD;
            case "light_blue" -> 0x3AB3DA;
            case "yellow" -> 0xFED83D;
            case "lime" -> 0x80C71F;
            case "pink" -> 0xF38BAA;
            case "gray" -> 0x474F52;
            case "light_gray" -> 0x9D9D97;
            case "cyan" -> 0x169C9C;
            case "purple" -> 0x8932B8;
            case "blue" -> 0x3C44AA;
            case "brown" -> 0x835432;
            case "green" -> 0x5E7C16;
            case "red" -> 0xB02E26;
            case "black" -> 0x1D1D21;
            default -> fallback;
        };
    }

    private static Look lookOf(BlockSpec s, BlockInfo info, BlockTextures tex, int mask) {
        String n = s.name();
        Look l = new Look();
        l.colour = IsoRenderer.colourOf(s, info);
        int[] f = tex == null ? null : tex.faces(n);
        if (f != null) {
            l.top = f[0];
            l.side = f[1];
            l.bottom = f[2];
        }
        try {
            l.emission = info.lightEmission(s) / 15f;
        } catch (RuntimeException ignored) {
            l.emission = 0;
        }
        String axis = s.prop("axis");
        if (axis != null) l.axis = "x".equals(axis) ? 0 : "z".equals(axis) ? 2 : 1;
        String facing = prop(s, info, "facing", "north"), half = prop(s, info, "half", "bottom");
        if (n.equals("water") || n.equals("bubble_column")) {
            l.kind = WATER;
            l.colour = 0x3F76E4;
            l.top = l.side = l.bottom = -1;
        } else if (n.endsWith("_leaves")) {
            l.kind = CUTOUT;
            l.occludes = true;
        } else if (n.endsWith("_slab")) {
            String type = prop(s, info, "type", "bottom");
            l.boxes = switch (type) {
                case "top" -> new float[][]{{0, .5f, 0, 1, 1, 1}};
                case "double" -> new float[][]{{0, 0, 0, 1, 1, 1}};
                default -> new float[][]{{0, 0, 0, 1, .5f, 1}};
            };
            l.occludes = type.equals("double");
        } else if (n.endsWith("_stairs")) {
            boolean top = half.equals("top");
            float[] slab = top ? new float[]{0, .5f, 0, 1, 1, 1} : new float[]{0, 0, 0, 1, .5f, 1};
            float y0 = top ? 0 : .5f, y1 = top ? .5f : 1;
            float[] step = switch (facing) {
                case "south" -> new float[]{0, y0, .5f, 1, y1, 1};
                case "east" -> new float[]{.5f, y0, 0, 1, y1, 1};
                case "west" -> new float[]{0, y0, 0, .5f, y1, 1};
                default -> new float[]{0, y0, 0, 1, y1, .5f};
            };
            l.boxes = new float[][]{slab, step};
            l.occludes = true;
        } else if (isPane(n) || n.endsWith("_fence") || (n.endsWith("_wall") && !n.contains("wall_"))) {
            boolean fence = n.endsWith("_fence"), wall = n.endsWith("_wall");
            float pw = fence ? 2 * P : wall ? 4 * P : P, aw = fence ? P : wall ? 3 * P : P;
            float ay0 = fence ? 6 * P : 0, ay1 = fence ? 15 * P : wall ? 14 * P : 1;
            List<float[]> bx = new ArrayList<>();
            bx.add(new float[]{.5f - pw, 0, .5f - pw, .5f + pw, 1, .5f + pw});
            if ((mask & 1) != 0) bx.add(new float[]{.5f - aw, ay0, 0, .5f + aw, ay1, .5f});
            if ((mask & 2) != 0) bx.add(new float[]{.5f, ay0, .5f - aw, 1, ay1, .5f + aw});
            if ((mask & 4) != 0) bx.add(new float[]{.5f - aw, ay0, .5f, .5f + aw, ay1, 1});
            if ((mask & 8) != 0) bx.add(new float[]{0, ay0, .5f - aw, .5f, ay1, .5f + aw});
            if (fence && (mask & 1) != 0) bx.add(new float[]{.5f - aw, 6 * P, 0, .5f + aw, 9 * P, .5f});
            l.boxes = bx.toArray(float[][]::new);
            l.connectGroup = fence ? "fence" : wall ? "wall" : "pane";
            if (isPane(n) && n.contains("glass")) l.kind = GLASS;
        } else if (n.endsWith("_fence_gate")) {
            boolean alongX = facing.equals("north") || facing.equals("south");
            l.boxes = alongX ? new float[][]{{0, 5 * P, 7 * P, 1, 15 * P, 9 * P}} : new float[][]{{7 * P, 5 * P, 0, 9 * P, 15 * P, 1}};
        } else if (n.endsWith("_trapdoor")) {
            boolean open = "true".equals(prop(s, info, "open", "false"));
            l.boxes = new float[][]{open ? switch (facing) {
                case "south" -> new float[]{0, 0, 0, 1, 1, 3 * P};
                case "east" -> new float[]{0, 0, 0, 3 * P, 1, 1};
                case "west" -> new float[]{13 * P, 0, 0, 1, 1, 1};
                default -> new float[]{0, 0, 13 * P, 1, 1, 1};
            } : half.equals("top") ? new float[]{0, 13 * P, 0, 1, 1, 1} : new float[]{0, 0, 0, 1, 3 * P, 1}};
            l.kind = CUTOUT;
        } else if (n.endsWith("_door")) {
            l.boxes = new float[][]{switch (facing) {
                case "south" -> new float[]{0, 0, 0, 1, 1, 3 * P};
                case "west" -> new float[]{13 * P, 0, 0, 1, 1, 1};
                case "north" -> new float[]{0, 0, 13 * P, 1, 1, 1};
                default -> new float[]{0, 0, 0, 3 * P, 1, 1};
            }};
            l.kind = CUTOUT;
        } else if (n.endsWith("_carpet") || n.endsWith("_pressure_plate") || n.equals("lily_pad") || n.equals("moss_carpet")
                || n.equals("pale_moss_carpet") || n.equals("redstone_wire") || n.equals("pink_petals") || n.equals("leaf_litter")
                || n.equals("wildflowers")) {
            l.boxes = new float[][]{{0, 0, 0, 1, P, 1}};
            if (!n.endsWith("_carpet") && !n.endsWith("_plate")) l.kind = CUTOUT;
        } else if (n.equals("snow")) {
            int layers = Integer.parseInt(prop(s, info, "layers", "1"));
            l.boxes = new float[][]{{0, 0, 0, 1, Math.min(1, layers * 2 * P), 1}};
        } else if (n.equals("lantern") || n.equals("soul_lantern")) {
            boolean hanging = "true".equals(prop(s, info, "hanging", "false"));
            l.boxes = hanging ? new float[][]{{5 * P, P, 5 * P, 11 * P, 8 * P, 11 * P}, {7 * P, 8 * P, 7 * P, 9 * P, 1, 9 * P}}
                    : new float[][]{{5 * P, 0, 5 * P, 11 * P, 7 * P, 11 * P}, {6 * P, 7 * P, 6 * P, 10 * P, 9 * P, 10 * P}};
            // the lantern texture is a sprite sheet, not a cube face: draw the lit glass as a flat warm (or soul) glow
            l.top = l.side = l.bottom = -1;
            l.colour = n.startsWith("soul") ? 0x7FE3E8 : 0xF4B848;
            l.emission = Math.max(l.emission, .9f);
        } else if (n.equals("iron_chain") || n.equals("chain") || n.equals("end_rod") || n.equals("lightning_rod")) {
            l.boxes = new float[][]{{6.5f * P, 0, 6.5f * P, 9.5f * P, 1, 9.5f * P}};
        } else if (n.contains("torch") && !n.startsWith("torchflower")) {
            l.boxes = new float[][]{{7 * P, 0, 7 * P, 9 * P, 10 * P, 9 * P}};
            l.top = l.side = l.bottom = -1;
            l.colour = 0xF6C453;
            l.emission = 1;
        } else if (n.endsWith("campfire")) {
            l.boxes = new float[][]{{0, 0, 0, 1, 7 * P, 1}};
            l.emission = Math.max(l.emission, .8f);
        } else if (n.equals("flower_pot") || n.startsWith("potted_")) {
            l.boxes = new float[][]{{5 * P, 0, 5 * P, 11 * P, 6 * P, 11 * P}};
            if (f == null || n.startsWith("potted_")) l.colour = 0x8E4E33;
            l.top = l.side = l.bottom = -1;
        } else if (n.endsWith("_head") || n.endsWith("_skull")) {
            l.boxes = new float[][]{{4 * P, 0, 4 * P, 12 * P, 8 * P, 12 * P}};
        } else if (n.endsWith("_candle") || n.equals("candle")) {
            l.boxes = new float[][]{{7 * P, 0, 7 * P, 9 * P, 6 * P, 9 * P}};
            l.top = l.side = l.bottom = -1;
            l.colour = n.equals("candle") ? 0xE2CFA0 : dye(n.substring(0, n.length() - "_candle".length()), 0xE2CFA0);
        } else if (n.endsWith("_button") || n.equals("lever") || n.equals("tripwire_hook")) {
            l.boxes = new float[0][];
        } else if (n.equals("ladder") || n.endsWith("_wall_sign") || n.endsWith("_wall_banner") || n.equals("vine")
                || n.equals("glow_lichen") || n.endsWith("_wall_hanging_sign")) {
            l.boxes = new float[][]{switch (facing) {
                case "south" -> new float[]{0, 0, 0, 1, 1, 2 * P};
                case "east" -> new float[]{0, 0, 0, 2 * P, 1, 1};
                case "west" -> new float[]{14 * P, 0, 0, 1, 1, 1};
                default -> new float[]{0, 0, 14 * P, 1, 1, 1};
            }};
            l.kind = CUTOUT;
        } else if (n.endsWith("_banner")) {
            // banners are entity textures (not in the block atlas): their dye colour on the cloth
            l.boxes = n.endsWith("_wall_banner") ? new float[][]{switch (facing) {
                case "south" -> new float[]{P, 0, 0, 15 * P, 1, 2 * P};
                case "east" -> new float[]{0, 0, P, 2 * P, 1, 15 * P};
                case "west" -> new float[]{14 * P, 0, P, 1, 1, 15 * P};
                default -> new float[]{P, 0, 14 * P, 15 * P, 1, 1};
            }} : new float[][]{{7 * P, 0, 7 * P, 9 * P, 1, 9 * P}, {P, 2 * P, 7 * P, 15 * P, 1, 9 * P}};
            l.top = l.side = l.bottom = -1;
            l.colour = dye(n.substring(0, n.length() - (n.endsWith("_wall_banner") ? "_wall_banner" : "_banner").length()), 0xE8E8E8);
        } else if (n.endsWith("_sign") || n.endsWith("_hanging_sign")) {
            l.boxes = new float[][]{{7 * P, 0, 7 * P, 9 * P, 9 * P, 9 * P}, {0, 8 * P, 7 * P, 1, 1, 9 * P}};
        } else if (n.endsWith("_bed")) {
            l.boxes = new float[][]{{0, 3 * P, 0, 1, 9 * P, 1}};
        } else if (n.equals("chest") || n.equals("trapped_chest") || n.equals("ender_chest")) {
            l.boxes = new float[][]{{P, 0, P, 15 * P, 14 * P, 15 * P}};
        } else if (isCross(n)) {
            l.kind = CROSS;
        } else if (n.contains("glass") || n.equals("ice") || n.equals("iron_bars")) {
            l.kind = GLASS;
        } else {
            boolean occ;
            try {
                occ = info.isOccluding(s);
            } catch (RuntimeException e) {
                occ = true;
            }
            l.occludes = occ;
            if (!occ && l.top >= 0 && hasAlpha(tex, l.side)) l.kind = CUTOUT;
        }
        return l;
    }

    private static boolean hasAlpha(BlockTextures tex, int tile) {
        if (tex == null || tile < 0) return false;
        for (int i = 0; i < 16; i++) if ((tex.sample(tile, (i % 4 + .5) / 4, (i / 4 + .5) / 4, 2) >>> 24) < 200) return true;
        return false;
    }

    private static boolean isPane(String n) {
        return n.equals("glass_pane") || n.endsWith("_glass_pane") || n.equals("iron_bars") || n.endsWith("_bars");
    }

    private static boolean isCross(String n) {
        return n.endsWith("_sapling") || n.endsWith("_tulip") || n.endsWith("_mushroom") || n.endsWith("_fungus")
                || n.endsWith("_roots") || n.endsWith("_coral") || n.endsWith("_coral_fan") || n.endsWith("_grass")
                || n.equals("short_grass") || n.equals("tall_grass") || n.equals("fern") || n.equals("large_fern") || n.equals("dead_bush")
                || n.equals("bush") || n.equals("firefly_bush") || n.equals("sweet_berry_bush") || n.equals("sugar_cane")
                || n.equals("wheat") || n.equals("carrots") || n.equals("potatoes") || n.equals("beetroots") || n.equals("cobweb")
                || n.equals("dandelion") || n.equals("poppy") || n.equals("blue_orchid") || n.equals("allium") || n.equals("azure_bluet")
                || n.equals("oxeye_daisy") || n.equals("cornflower") || n.equals("lily_of_the_valley") || n.equals("wither_rose")
                || n.equals("torchflower") || n.equals("sunflower") || n.equals("lilac") || n.equals("rose_bush") || n.equals("peony")
                || n.equals("pitcher_plant") || n.equals("nether_sprouts") || n.equals("hanging_roots") || n.equals("spore_blossom")
                || n.equals("kelp") || n.equals("kelp_plant") || n.equals("seagrass") || n.equals("tall_seagrass")
                || n.equals("bamboo_sapling") || n.equals("pale_hanging_moss") || n.equals("cave_vines") || n.equals("weeping_vines")
                || n.equals("twisting_vines") || n.equals("small_dripleaf") || n.equals("closed_eyeblossom") || n.equals("open_eyeblossom")
                || n.equals("cactus_flower") || n.equals("short_dry_grass") || n.equals("tall_dry_grass");
    }

    private static String prop(BlockSpec s, BlockInfo info, String key, String def) {
        String v = s.prop(key);
        if (v != null) return v;
        try {
            v = info.getProp(s, key);
        } catch (RuntimeException ignored) {
            // default below
        }
        return v == null ? def : v;
    }

    // ------------------------------------------------------------------ scene grid

    /** Chunked cell grid of look indices (0 = empty) with fast skipping of empty 16³ chunks. */
    private static final class Scene {
        final int ox, oy, oz, nx, ny, nz; // grid origin and size in chunks
        final short[][] chunks;
        final List<Look> looks = new ArrayList<>();
        final BlockTextures tex;

        Scene(VoxelModel model, BlockInfo info, Options opt) {
            tex = BlockTextures.current();
            int[] b = model.solidBounds();
            if (b == null) b = new int[]{0, 0, 0, 0, 0, 0};
            int g = Math.max(0, opt.ground());
            int x0 = b[0] - g, z0 = b[2] - g, x1 = b[3] + g, z1 = b[5] + g;
            int y0 = Math.min(b[1], g > 0 ? -2 : b[1]), y1 = Math.min(b[4], opt.clipY());
            ox = Math.floorDiv(x0, 16) * 16;
            oy = Math.floorDiv(y0, 16) * 16;
            oz = Math.floorDiv(z0, 16) * 16;
            nx = Math.floorDiv(x1, 16) - ox / 16 + 1;
            ny = Math.max(1, Math.floorDiv(Math.max(y1, y0), 16) - oy / 16 + 1);
            nz = Math.floorDiv(z1, 16) - oz / 16 + 1;
            chunks = new short[nx * ny * nz][];
            Map<String, Integer> ids = new HashMap<>();
            looks.add(null);
            int clip = opt.clipY();
            List<long[]> connect = new ArrayList<>();
            model.forEach((x, y, z, s) -> {
                if (s == null || s.isAir() || y > clip) return;
                int id = ids.computeIfAbsent(s.toString(), k -> {
                    looks.add(lookOf(s, info, tex, 0));
                    return looks.size() - 1;
                });
                set(x, y, z, id);
                if (looks.get(id).connectGroup != null) connect.add(new long[]{x, y, z});
            });
            if (g > 0) {
                Look grass = lookOf(BlockSpec.of("grass_block"), info, tex, 0);
                Look dirt = lookOf(BlockSpec.of("dirt"), info, tex, 0);
                grass.occludes = dirt.occludes = true;
                looks.add(grass);
                int gid = looks.size() - 1;
                looks.add(dirt);
                int did = gid + 1;
                for (int x = x0; x <= x1; x++) {
                    for (int z = z0; z <= z1; z++) {
                        for (int y = -2; y <= Math.min(0, clip); y++) {
                            if (!model.isSet(x, y, z)) set(x, y, z, y == 0 ? gid : did);
                        }
                    }
                }
            }
            // fences, walls and panes connect to their neighbours like in the game
            Map<String, Integer> connected = new HashMap<>();
            for (long[] c : connect) {
                int x = (int) c[0], y = (int) c[1], z = (int) c[2];
                Look self = looks.get(get(x, y, z));
                int mask = 0;
                int[][] nb = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
                for (int i = 0; i < 4; i++) {
                    int id = get(x + nb[i][0], y, z + nb[i][1]);
                    if (id == 0) continue;
                    Look o = looks.get(id);
                    if (o.occludes || self.connectGroup.equals(o.connectGroup)
                            || ("pane".equals(self.connectGroup) && "pane".equals(o.connectGroup))) mask |= 1 << i;
                }
                if (mask == 0) continue;
                BlockSpec s = model.get(x, y, z);
                int m = mask;
                int id = connected.computeIfAbsent(s + "#" + m, k -> {
                    looks.add(lookOf(s, info, tex, m));
                    return looks.size() - 1;
                });
                set(x, y, z, id);
            }
        }

        private void set(int x, int y, int z, int id) {
            int cx = Math.floorDiv(x - ox, 16), cy = Math.floorDiv(y - oy, 16), cz = Math.floorDiv(z - oz, 16);
            if (cx < 0 || cy < 0 || cz < 0 || cx >= nx || cy >= ny || cz >= nz) return;
            int ci = (cx * ny + cy) * nz + cz;
            short[] ch = chunks[ci];
            if (ch == null) chunks[ci] = ch = new short[4096];
            ch[((x - ox) & 15) << 8 | ((y - oy) & 15) << 4 | ((z - oz) & 15)] = (short) id;
        }

        int get(int x, int y, int z) {
            int cx = Math.floorDiv(x - ox, 16), cy = Math.floorDiv(y - oy, 16), cz = Math.floorDiv(z - oz, 16);
            if (cx < 0 || cy < 0 || cz < 0 || cx >= nx || cy >= ny || cz >= nz) return 0;
            short[] ch = chunks[(cx * ny + cy) * nz + cz];
            return ch == null ? 0 : ch[((x - ox) & 15) << 8 | ((y - oy) & 15) << 4 | ((z - oz) & 15)] & 0xFFFF;
        }

        boolean occ(int x, int y, int z) {
            int id = get(x, y, z);
            return id != 0 && looks.get(id).occludes;
        }

        double minX() {
            return ox;
        }

        double maxX() {
            return ox + nx * 16;
        }

        double minY() {
            return oy;
        }

        double maxY() {
            return oy + ny * 16;
        }

        double minZ() {
            return oz;
        }

        double maxZ() {
            return oz + nz * 16;
        }
    }

    // ------------------------------------------------------------------ tracing

    /** Result of tracing one ray. */
    private static final class Hit {
        double t = Double.POSITIVE_INFINITY;
        double r, g, b;
        double trans = 1;
        int normal = -1;
        boolean opaque;
    }

    /** Renders the model through a camera. */
    public static BufferedImage render(VoxelModel model, BlockInfo info, Camera cam, Options opt) {
        Scene scene = new Scene(model, info, opt);
        int w = cam.w, h = cam.h;
        int[] rgb = new int[w * h];
        float[] depth = new float[w * h];
        byte[] normals = new byte[w * h];
        double[] light = lightDir(cam);
        IntStream.range(0, h).parallel().forEach(py -> {
            double[] o = new double[3], d = new double[3];
            Hit hit = new Hit();
            for (int px = 0; px < w; px++) {
                ray(cam, px + .5, py + .5, o, d);
                hit.t = Double.POSITIVE_INFINITY;
                hit.r = hit.g = hit.b = 0;
                hit.trans = 1;
                hit.normal = -1;
                hit.opaque = false;
                trace(scene, o, d, hit, light, cam, true);
                int skyc = skyColour(d);
                double t = hit.trans;
                double r = hit.r + t * ((skyc >> 16) & 255) / 255.0;
                double g = hit.g + t * ((skyc >> 8) & 255) / 255.0;
                double b = hit.b + t * (skyc & 255) / 255.0;
                if (!cam.ortho && hit.opaque) {
                    double fog = Math.clamp((hit.t - 60) / 260, 0, 0.85);
                    r = r * (1 - fog) + fog * ((skyc >> 16) & 255) / 255.0;
                    g = g * (1 - fog) + fog * ((skyc >> 8) & 255) / 255.0;
                    b = b * (1 - fog) + fog * (skyc & 255) / 255.0;
                }
                rgb[py * w + px] = pack(r, g, b);
                depth[py * w + px] = hit.opaque ? (float) hit.t : Float.POSITIVE_INFINITY;
                normals[py * w + px] = (byte) hit.normal;
            }
        });
        if (opt.outlines()) outline(cam, rgb, depth, normals, w, h);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, w, h, rgb, 0, w);
        return img;
    }

    private static double[] lightDir(Camera cam) {
        // key light from the viewer's upper left, slightly behind the camera, so every view is lit
        double[] l = add(add(scale(cam.right, -0.55), new double[]{0, 1.1, 0}), scale(cam.fwd, -0.65));
        return norm(l);
    }

    private static void ray(Camera c, double px, double py, double[] o, double[] d) {
        double sx = px - c.w / 2.0, sy = py - c.h / 2.0;
        if (c.ortho) {
            for (int i = 0; i < 3; i++) {
                o[i] = c.pos[i] + c.right[i] * sx * c.k - c.up[i] * sy * c.k;
                d[i] = c.fwd[i];
            }
        } else {
            for (int i = 0; i < 3; i++) {
                o[i] = c.pos[i];
                d[i] = c.fwd[i] * c.k + c.right[i] * sx - c.up[i] * sy;
            }
            double l = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
            for (int i = 0; i < 3; i++) d[i] /= l;
        }
    }

    /**
     * Walks the ray through the grid (Amanatides–Woo DDA, jumping over empty chunks) and composites what it hits
     * front to back into {@code hit}. With {@code shade == false} it only answers "is anything opaque in the way"
     * (shadow rays), setting {@code hit.opaque}.
     */
    private static void trace(Scene s, double[] o, double[] d, Hit hit, double[] light, Camera cam, boolean shade) {
        double t0 = 0, t1 = Double.POSITIVE_INFINITY;
        double[] lo = {s.minX(), s.minY(), s.minZ()}, hi = {s.maxX(), s.maxY(), s.maxZ()};
        for (int i = 0; i < 3; i++) {
            if (Math.abs(d[i]) < 1e-12) {
                if (o[i] < lo[i] || o[i] > hi[i]) return;
            } else {
                double a = (lo[i] - o[i]) / d[i], b = (hi[i] - o[i]) / d[i];
                t0 = Math.max(t0, Math.min(a, b));
                t1 = Math.min(t1, Math.max(a, b));
            }
        }
        if (t0 > t1) return;
        double t = t0 + 1e-4;
        int lastWater = 0;
        while (t < t1) {
            int ix = (int) Math.floor(o[0] + d[0] * t), iy = (int) Math.floor(o[1] + d[1] * t), iz = (int) Math.floor(o[2] + d[2] * t);
            int sx = d[0] > 0 ? 1 : -1, sy = d[1] > 0 ? 1 : -1, sz = d[2] > 0 ? 1 : -1;
            double tdx = Math.abs(1 / d[0]), tdy = Math.abs(1 / d[1]), tdz = Math.abs(1 / d[2]);
            double tmx = d[0] == 0 ? Double.POSITIVE_INFINITY : ((sx > 0 ? ix + 1 : ix) - o[0]) / d[0];
            double tmy = d[1] == 0 ? Double.POSITIVE_INFINITY : ((sy > 0 ? iy + 1 : iy) - o[1]) / d[1];
            double tmz = d[2] == 0 ? Double.POSITIVE_INFINITY : ((sz > 0 ? iz + 1 : iz) - o[2]) / d[2];
            boolean jumped = false;
            while (t < t1) {
                int cx = Math.floorDiv(ix - s.ox, 16), cy = Math.floorDiv(iy - s.oy, 16), cz = Math.floorDiv(iz - s.oz, 16);
                if (cx < 0 || cy < 0 || cz < 0 || cx >= s.nx || cy >= s.ny || cz >= s.nz) return;
                short[] ch = s.chunks[(cx * s.ny + cy) * s.nz + cz];
                if (ch == null) {
                    // leave this empty chunk in one step
                    double ex = d[0] == 0 ? Double.POSITIVE_INFINITY : ((s.ox + (cx + (sx > 0 ? 1 : 0)) * 16) - o[0]) / d[0];
                    double ey = d[1] == 0 ? Double.POSITIVE_INFINITY : ((s.oy + (cy + (sy > 0 ? 1 : 0)) * 16) - o[1]) / d[1];
                    double ez = d[2] == 0 ? Double.POSITIVE_INFINITY : ((s.oz + (cz + (sz > 0 ? 1 : 0)) * 16) - o[2]) / d[2];
                    t = Math.min(ex, Math.min(ey, ez)) + 1e-4;
                    jumped = true;
                    lastWater = 0;
                    break;
                }
                int id = ch[((ix - s.ox) & 15) << 8 | ((iy - s.oy) & 15) << 4 | ((iz - s.oz) & 15)] & 0xFFFF;
                double tNext = Math.min(tmx, Math.min(tmy, tmz));
                if (id != 0) {
                    Look l = s.looks.get(id);
                    if (l.kind == WATER) {
                        if (shade) {
                            double len = tNext - t;
                            double a = 1 - Math.exp(-(lastWater == 0 ? 0.55 : 0) - 0.22 * len);
                            shadeWater(s, l, o, d, t, ix, iy, iz, lastWater == 0, hit, a, light);
                        }
                        lastWater = 1;
                    } else {
                        lastWater = 0;
                        if (cell(s, l, ix, iy, iz, o, d, t, tNext, hit, light, cam, shade)) return;
                    }
                    if (hit.trans < 0.02) {
                        hit.opaque = true;
                        return;
                    }
                } else {
                    lastWater = 0;
                }
                if (tmx < tmy && tmx < tmz) {
                    ix += sx;
                    t = tmx;
                    tmx += tdx;
                } else if (tmy < tmz) {
                    iy += sy;
                    t = tmy;
                    tmy += tdy;
                } else {
                    iz += sz;
                    t = tmz;
                    tmz += tdz;
                }
            }
            if (!jumped) return;
        }
    }

    private static void shadeWater(Scene s, Look l, double[] o, double[] d, double t, int x, int y, int z, boolean surface,
                                   Hit hit, double a, double[] light) {
        double k = surface ? 0.9 + 0.25 * Math.max(0, light[1]) : 0.75;
        int c = l.colour;
        double r = ((c >> 16) & 255) / 255.0 * k, g = ((c >> 8) & 255) / 255.0 * k, b = (c & 255) / 255.0 * k;
        if (hit.t == Double.POSITIVE_INFINITY) {
            hit.t = t;
            hit.normal = 2;
        }
        hit.r += hit.trans * a * r;
        hit.g += hit.trans * a * g;
        hit.b += hit.trans * a * b;
        hit.trans *= 1 - a;
    }

    /** Intersects the ray with one cell's geometry; returns true when the ray is finished (opaque hit). */
    private static boolean cell(Scene s, Look l, int x, int y, int z, double[] o, double[] d, double tIn, double tOut,
                                Hit hit, double[] light, Camera cam, boolean shade) {
        if (l.kind == CROSS) return cross(s, l, x, y, z, o, d, tIn, tOut, hit, light, cam, shade);
        // candidate faces sorted by t: entries of every box (and exits for cut-out cubes, whose back faces show)
        double[] ts = new double[l.boxes.length * 2];
        int[] faces = new int[l.boxes.length * 2];
        int n = 0;
        for (float[] bx : l.boxes) {
            double near = -Double.MAX_VALUE, far = Double.MAX_VALUE;
            int nf = -1, ff = -1;
            boolean miss = false;
            for (int i = 0; i < 3 && !miss; i++) {
                double lo = (i == 0 ? x : i == 1 ? y : z) + bx[i], hi = (i == 0 ? x : i == 1 ? y : z) + bx[i + 3];
                if (Math.abs(d[i]) < 1e-12) {
                    if (o[i] < lo || o[i] > hi) miss = true;
                    continue;
                }
                double a = (lo - o[i]) / d[i], b = (hi - o[i]) / d[i];
                int fa = i * 2, fb = i * 2 + 1; // face index: axis*2 + (0 = min side, 1 = max side)
                if (a > b) {
                    double tmp = a;
                    a = b;
                    b = tmp;
                    int tf = fa;
                    fa = fb;
                    fb = tf;
                }
                if (a > near) {
                    near = a;
                    nf = fa;
                }
                if (b < far) {
                    far = b;
                    ff = fb;
                }
            }
            if (miss || near > far || far < tIn - 1e-7 || near > tOut + 1e-7) continue;
            if (near >= tIn - 1e-7) {
                ts[n] = near;
                faces[n++] = nf;
            }
            if (l.kind != SOLID && l.kind != GLASS && far <= tOut + 1e-7 && far >= tIn - 1e-7) {
                ts[n] = far;
                faces[n++] = ff | 8; // back face
            }
        }
        // insertion sort (n is tiny)
        for (int i = 1; i < n; i++) {
            for (int j = i; j > 0 && ts[j] < ts[j - 1]; j--) {
                double tt = ts[j];
                ts[j] = ts[j - 1];
                ts[j - 1] = tt;
                int ff = faces[j];
                faces[j] = faces[j - 1];
                faces[j - 1] = ff;
            }
        }
        boolean glassDone = false;
        for (int i = 0; i < n; i++) {
            double t = ts[i];
            int face = faces[i] & 7;
            boolean back = (faces[i] & 8) != 0;
            double px = o[0] + d[0] * t, py = o[1] + d[1] * t, pz = o[2] + d[2] * t;
            double fx = clamp01(px - x), fy = clamp01(py - y), fz = clamp01(pz - z);
            int axis = face / 2;
            boolean pos = (face & 1) == 1;
            if (back) pos = !pos; // the back face is seen from inside: shade it as facing the viewer
            double u, v;
            if (axis == 1) {
                u = fx;
                v = fz;
            } else if (axis == 0) {
                u = pos ? 1 - fz : fz;
                v = 1 - fy;
            } else {
                u = pos ? fx : 1 - fx;
                v = 1 - fy;
            }
            int tile = tileFor(l, axis, axis == 1 && pos);
            int texel = texel(s, l, tile, u, v, cam, t);
            int alpha = texel >>> 24;
            if (l.kind == CUTOUT && alpha < 128) continue;
            if (!shade) {
                if (l.kind == GLASS) continue;
                hit.opaque = true;
                return true;
            }
            if (l.kind == GLASS) {
                if (glassDone) continue;
                glassDone = true;
                double a = Math.max(alpha / 255.0, 0.16);
                if (a > 0.92) a = 1;
                double lt = lighting(s, l, x, y, z, axis, pos, fx, fy, fz, px, py, pz, light, cam, false);
                composite(hit, texel, a, lt * 1.05, t, face);
                if (a >= 1) {
                    hit.opaque = true;
                    return true;
                }
                continue;
            }
            double lt = lighting(s, l, x, y, z, axis, pos, fx, fy, fz, px, py, pz, light, cam, true);
            composite(hit, texel, 1, lt, t, face);
            hit.opaque = true;
            return true;
        }
        return false;
    }

    private static boolean cross(Scene s, Look l, int x, int y, int z, double[] o, double[] d, double tIn, double tOut,
                                 Hit hit, double[] light, Camera cam, boolean shade) {
        double best = Double.POSITIVE_INFINITY;
        int bestTex = 0;
        for (int plane = 0; plane < 2; plane++) {
            // plane A: fx = fz; plane B: fx = 1 - fz
            double ox = o[0] - x, oz = o[2] - z;
            double denom = plane == 0 ? d[0] - d[2] : d[0] + d[2];
            if (Math.abs(denom) < 1e-9) continue;
            double t = plane == 0 ? (oz - ox) / denom : (1 - ox - oz) / denom;
            if (t < tIn - 1e-7 || t > tOut + 1e-7 || t >= best) continue;
            double fx = ox + d[0] * t, fy = o[1] - y + d[1] * t;
            if (fx < 0.08 || fx > 0.92 || fy < 0 || fy > 1) continue;
            int tile = l.side;
            int texel = texel(s, l, tile, fx, 1 - fy, cam, t);
            if (tile >= 0 && (texel >>> 24) < 128) continue;
            if (tile < 0 && (fx < 0.3 || fx > 0.7 || fy > 0.8)) continue;
            best = t;
            bestTex = texel;
        }
        if (best == Double.POSITIVE_INFINITY) return false;
        if (!shade) return false; // plants do not cast shadows here
        double px = o[0] + d[0] * best, py = o[1] + d[1] * best, pz = o[2] + d[2] * best;
        boolean lit = !shadowed(s, px, py, pz, light);
        double lt = (0.62 + 0.38 * (lit ? 1 : 0.35)) * (0.85 + 0.15 * (py - y));
        composite(hit, bestTex, 1, lt, best, 2);
        hit.opaque = true;
        return true;
    }

    private static int tileFor(Look l, int faceAxis, boolean positive) {
        if (l.axis == faceAxis) return faceAxis == 1 ? (positive ? l.top : l.bottom) : l.top;
        return l.side;
    }

    private static int texel(Scene s, Look l, int tile, double u, double v, Camera cam, double t) {
        if (s.tex == null || tile < 0) return 0xFF000000 | l.colour;
        if (cam == null) return s.tex.sample(tile, u, v, 1);
        double ppb = cam.ortho ? 1 / cam.k : cam.k / Math.max(0.5, t); // pixels per block at the hit
        int level = ppb >= 16 ? 0 : (int) Math.min(4, Math.floor(Math.log(16 / ppb) / Math.log(2) + 0.5));
        return s.tex.sample(tile, u, v, level);
    }

    /** Light factor for a face hit: key light (with shadow), sky ambient, corner AO, emission. */
    private static double lighting(Scene s, Look l, int x, int y, int z, int axis, boolean pos, double fx, double fy, double fz,
                                   double px, double py, double pz, double[] light, Camera cam, boolean ao) {
        double nx = axis == 0 ? (pos ? 1 : -1) : 0, ny = axis == 1 ? (pos ? 1 : -1) : 0, nz = axis == 2 ? (pos ? 1 : -1) : 0;
        double lam = Math.max(0, nx * light[0] + ny * light[1] + nz * light[2]);
        boolean lit = lam > 0 && !shadowed(s, px + nx * 1e-3, py + ny * 1e-3, pz + nz * 1e-3, light);
        double amb = 0.50 + 0.12 * ny;
        double occ = ao ? ambientOcclusion(s, x, y, z, axis, pos, fx, fy, fz) : 1;
        double v = occ * (amb + 0.62 * lam * (lit ? 1 : 0.18));
        return Math.max(v, l.emission * 1.05);
    }

    private static boolean shadowed(Scene s, double px, double py, double pz, double[] light) {
        Hit h = new Hit();
        trace(s, new double[]{px + light[0] * 1e-3, py + light[1] * 1e-3, pz + light[2] * 1e-3}, light, h, light, null, false);
        return h.opaque;
    }

    /** Minecraft-style smooth lighting: occlusion of the 8 cells around the face, interpolated across it. */
    private static double ambientOcclusion(Scene s, int x, int y, int z, int axis, boolean pos, double fx, double fy, double fz) {
        boolean boundary = switch (axis) {
            case 0 -> pos ? fx > 0.999 : fx < 0.001;
            case 1 -> pos ? fy > 0.999 : fy < 0.001;
            default -> pos ? fz > 0.999 : fz < 0.001;
        };
        int px = x, py = y, pz = z;
        if (boundary) {
            if (axis == 0) px += pos ? 1 : -1;
            else if (axis == 1) py += pos ? 1 : -1;
            else pz += pos ? 1 : -1;
        }
        // in-plane axes a, b with fractional coordinates fa, fb
        int a = axis == 0 ? 1 : 0, b = axis == 2 ? 1 : 2;
        double fa = a == 0 ? fx : fy, fb = b == 2 ? fz : fy;
        double[] corner = new double[4];
        for (int c = 0; c < 4; c++) {
            int da = (c & 1) == 0 ? -1 : 1, db = (c & 2) == 0 ? -1 : 1;
            boolean s1 = occAt(s, px, py, pz, a, da, b, 0), s2 = occAt(s, px, py, pz, a, 0, b, db), cc = occAt(s, px, py, pz, a, da, b, db);
            int level = s1 && s2 ? 0 : 3 - ((s1 ? 1 : 0) + (s2 ? 1 : 0) + (cc ? 1 : 0));
            corner[c] = 0.52 + 0.16 * level;
        }
        double top = corner[0] * (1 - fa) + corner[1] * fa, bottom = corner[2] * (1 - fa) + corner[3] * fa;
        return top * (1 - fb) + bottom * fb;
    }

    private static boolean occAt(Scene s, int x, int y, int z, int a, int da, int b, int db) {
        int[] p = {x, y, z};
        p[a] += da;
        p[b] += db;
        return s.occ(p[0], p[1], p[2]);
    }

    private static void composite(Hit hit, int texel, double a, double light, double t, int face) {
        double r = ((texel >> 16) & 255) / 255.0 * light, g = ((texel >> 8) & 255) / 255.0 * light, b = (texel & 255) / 255.0 * light;
        if (hit.t == Double.POSITIVE_INFINITY || a >= 1) {
            if (hit.t == Double.POSITIVE_INFINITY) hit.t = t;
            if (hit.normal < 0) hit.normal = face;
        }
        hit.r += hit.trans * a * r;
        hit.g += hit.trans * a * g;
        hit.b += hit.trans * a * b;
        hit.trans *= 1 - a;
    }

    private static int skyColour(double[] d) {
        double k = Math.clamp((d[1] + 0.2) / 1.2, 0, 1);
        int r = (int) (0xF4 + (0xC9 - 0xF4) * k), g = (int) (0xF7 + (0xDF - 0xF7) * k), b = (int) (0xFB + (0xF6 - 0xFB) * k);
        return r << 16 | g << 8 | b;
    }

    /** Darkens pixels at depth discontinuities (silhouettes) and, lightly, at normal changes (block edges). */
    private static void outline(Camera cam, int[] rgb, float[] depth, byte[] normals, int w, int h) {
        int[] out = rgb.clone();
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                float dc = depth[i];
                double k = 1;
                for (int j : new int[]{i - 1, i + 1, i - w, i + w}) {
                    float dn = depth[j];
                    if (Float.isInfinite(dc) && Float.isInfinite(dn)) continue;
                    double tol = cam.ortho ? 0.9 : Math.max(0.9, dc * 0.04);
                    if (Float.isInfinite(dn) || dn - dc > tol) k = Math.min(k, 0.55);
                    else if (normals[j] != normals[i] && !Float.isInfinite(dc)) k = Math.min(k, 0.86);
                }
                if (k < 1) out[i] = scaleRgb(rgb[i], k);
            }
        }
        System.arraycopy(out, 0, rgb, 0, rgb.length);
    }

    // ------------------------------------------------------------------ small vector helpers

    private static int scaleRgb(int c, double k) {
        return (int) (((c >> 16) & 255) * k) << 16 | (int) (((c >> 8) & 255) * k) << 8 | (int) ((c & 255) * k);
    }

    private static int pack(double r, double g, double b) {
        return (int) Math.round(Math.clamp(r, 0, 1) * 255) << 16 | (int) Math.round(Math.clamp(g, 0, 1) * 255) << 8
                | (int) Math.round(Math.clamp(b, 0, 1) * 255);
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : v > 0.999999 ? 0.999999 : v;
    }

    private static double[] norm(double[] v) {
        double l = len(v);
        return new double[]{v[0] / l, v[1] / l, v[2] / l};
    }

    private static double len(double[] v) {
        return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double[] add(double[] a, double[] b) {
        return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    private static double[] sub(double[] a, double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static double[] scale(double[] a, double k) {
        return new double[]{a[0] * k, a[1] * k, a[2] * k};
    }
}
