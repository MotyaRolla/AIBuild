package dev.aibuild.scene;

import dev.aibuild.model.VoxelModel;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Voxel pixel renderer — the renderer the AI critic looks at.
 *
 * <p>The scene is <b>not</b> rasterised as smooth polygons. The single source of truth is the voxel grid
 * produced by {@link SceneGeometry#info(SceneSpec)}: for every screen pixel a ray is cast into that grid
 * (Amanatides &amp; Woo DDA traversal), the first solid cell is taken, and its block face is shaded with a
 * procedural 16&times;16 pixel texture. One block is exactly one cube, every face is textured, block edges stay
 * visible, glass is transparent. That is what makes the picture honest: if the render shows a hole, the hole
 * will be in Minecraft too — so a vision critic looking at these images fixes real defects instead of silhouette
 * cosmetics (see {@code three/RENDER.md}).</p>
 *
 * <h2>Per-pixel shading</h2>
 * <ul>
 *     <li>procedural 16&times;16 texture per material family (stone, planks, roof tiles, bricks, glass, leaves,
 *     metal, plain), generated from the palette colour — no external files, fully deterministic;</li>
 *     <li>sun light {@code n·sun}, ambient occlusion from the three neighbouring cells, a shadow ray towards the
 *     sun and a slight distance fog;</li>
 *     <li>glass is translucent and shows the block behind it;</li>
 *     <li>a dark outline wherever the depth buffer jumps — that is what makes individual blocks readable;</li>
 *     <li>optional supersampling: the frame is rendered at {@code width*scale} and box-filtered down.</li>
 * </ul>
 *
 * <h2>Performance</h2>
 * <p>The expensive mistake in a naive voxel tracer is stepping through hundreds of empty cells before the ray
 * reaches the model. Every ray here first enters the grid through a slab (AABB) test and starts the DDA exactly
 * at the entry face; the measured effect in the JavaScript original was a 90&times; speed-up. A 10&times;10 house
 * renders at 512&times;384 with supersampling&nbsp;2 in well under the 1.5&nbsp;s budget on one thread.</p>
 *
 * <p>Coordinates follow the plugin convention: +x east, +y up, +z south. Cell {@code (x,y,z)} covers
 * {@code [x,x+1] x [y,y+1] x [z,z+1]}. The class is stateless — all scratch buffers live inside one call — so
 * the static methods are safe to use from several threads at once.</p>
 */
public final class VoxelRenderer {

    /**
     * One orbit view of the model.
     *
     * @param name      the view name, as used in {@link #VIEWS} and {@link #renderAll}
     * @param azimuth   degrees around the model: 0 looks from the south (+z), 90 from the east (+x)
     * @param elevation degrees above the horizon: 0 is eye level with the model centre
     */
    public record View(String name, int azimuth, int elevation) {
    }

    /** The standard orbit views, in the order the pipeline asks for them. */
    public static final Map<String, View> VIEWS;

    static {
        Map<String, View> v = new LinkedHashMap<>();
        v.put("se", new View("se", 45, 26));
        v.put("sw", new View("sw", -45, 26));
        v.put("ne", new View("ne", 135, 26));
        v.put("nw", new View("nw", -135, 26));
        v.put("front", new View("front", 0, 12));
        v.put("back", new View("back", 180, 12));
        v.put("top", new View("top", 45, 78));
        v.put("side", new View("side", 90, 16));
        VIEWS = Map.copyOf(v);
    }

    /** Sky colour behind the model (a very light blue, like a soft studio backdrop). */
    public static final String BACKGROUND = "#eef3f8";

    /** Texture resolution per block face, exactly like Minecraft. */
    private static final int TEX = 16;

    /** Vertical field of view of the orbit camera, in degrees. */
    private static final double FOV = 45.0;

    /** Supersampling used by {@link #renderAll} (antialiased, still one frame per view). */
    private static final float DEFAULT_SUPERSAMPLE = 2f;

    /** Direction towards the sun (normalised): high, slightly from the east and the south. */
    private static final double[] SUN = normalise(0.45, 0.82, 0.35);

    /** Hard cap on DDA steps; a ray that needs more has left the grid long ago. */
    private static final int MAX_STEPS = 4096;

    /** Shadow rays stop after this distance: no build is taller than this many blocks. */
    private static final double SHADOW_DISTANCE = 96.0;

    /**
     * Largest voxel grid that is expanded into a dense array. A Minecraft-sized build is at most a few million
     * cells; anything beyond this is a corrupt spec, and expanding it would exhaust the server heap.
     */
    private static final long MAX_GRID_CELLS = 16_000_000L;

    /** Largest accepted image dimension, to keep one bad call from allocating gigabytes. */
    private static final int MAX_DIM = 8192;

    private VoxelRenderer() {
    }

    /**
     * Renders one view of the voxel grid.
     *
     * @param spec   the spec the voxels were built from; used for the palette (may be {@code null}, then a
     *               built-in colour table is used)
     * @param voxels the voxel cells, keyed by {@link VoxelModel#key(int, int, int)}; may be empty
     * @param view   the orbit view to render; must not be {@code null}
     * @param scale  supersampling factor: 1 draws one ray per pixel (draft), 2 or 3 render larger and downsample
     *               for antialiasing; values are clamped to 1..3 and rounded
     * @param width  output width in pixels, clamped to 1..8192
     * @param height output height in pixels, clamped to 1..8192
     * @return a new RGB image of exactly {@code width x height} pixels, never {@code null}
     * @throws IllegalArgumentException if {@code view} is {@code null} or the voxel set spans more than
     *                                  {@value #MAX_GRID_CELLS} cells
     */
    public static BufferedImage render(SceneSpec spec, Map<Long, SceneGeometry.CellInfo> voxels, View view,
                                       float scale, int width, int height) {
        if (view == null) throw new IllegalArgumentException("view is null");
        int w = clamp(width, 1, MAX_DIM);
        int h = clamp(height, 1, MAX_DIM);
        int ss = clamp(Math.round(scale), 1, 3);
        int rw = w * ss;
        int rh = h * ss;

        int[] background = parseHex(BACKGROUND);
        if (background == null) background = new int[]{238, 243, 248};

        // Scratch buffers for the supersampled frame. RGB is kept as double so the bloom of several
        // lighting terms and the supersampling average do not clip prematurely.
        double[] colours = new double[rw * rh * 3];
        for (int y = 0; y < rh; y++) {
            double t = rh <= 1 ? 0 : (double) y / rh;
            double r = background[0] * (0.92 + 0.08 * t);
            double gch = background[1] * (0.94 + 0.06 * t);
            double b = background[2] * (0.96 + 0.04 * t);
            int o = y * rw * 3;
            for (int x = 0; x < rw; x++, o += 3) {
                colours[o] = r;
                colours[o + 1] = gch;
                colours[o + 2] = b;
            }
        }

        Grid grid = Grid.of(spec, voxels);
        int[] out = new int[w * h];
        if (grid == null) {
            // Nothing to draw: the sky gradient alone still makes a valid, calm image.
            downsample(colours, rw, w, h, ss, out);
            return toImage(out, w, h);
        }

        double[] eye = new double[3];
        double[] fwd = new double[3];
        double[] right = new double[3];
        double[] up = new double[3];
        double radius = camera(grid, view, eye, fwd, right, up, rw, rh);
        double tanF = Math.tan(Math.toRadians(FOV) / 2);
        double aspect = (double) rw / rh;
        double maxDist = radius * 8 + 512;

        float[] depth = new float[rw * rh];
        boolean[] lit = new boolean[rw * rh];
        Hit hit = new Hit();
        Hit behind = new Hit();

        for (int py = 0; py < rh; py++) {
            double sy = (1 - 2 * (py + 0.5) / rh) * tanF;
            for (int px = 0; px < rw; px++) {
                double sx = (2 * (px + 0.5) / rw - 1) * tanF * aspect;
                double dx = fwd[0] + right[0] * sx + up[0] * sy;
                double dy = fwd[1] + right[1] * sx + up[1] * sy;
                double dz = fwd[2] + right[2] * sx + up[2] * sy;
                double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (len < 1e-12) continue;
                dx /= len;
                dy /= len;
                dz /= len;

                // The sky gradient is already in `colours`; a hit overwrites it with the shaded block.
                if (!trace(grid, eye[0], eye[1], eye[2], dx, dy, dz, maxDist, false, hit)) continue;
                int o = py * rw + px;
                setRgb(colours, o, shade(grid, hit, dx, dy, dz, radius, behind));
                lit[o] = true;
                depth[o] = (float) hit.t;
            }
        }

        outline(colours, depth, lit, rw, rh);
        downsample(colours, rw, w, h, ss, out);
        return toImage(out, w, h);
    }

    /**
     * Renders several named views of the same grid, in the requested order.
     *
     * <p>Uses a 2&times; supersampling factor, which is the quality the critic sees: enough antialiasing to read
     * window frames and roof edges, still well inside a one-second budget per view.</p>
     *
     * @param spec      the spec the voxels were built from (palette; may be {@code null})
     * @param voxels    the voxel cells, keyed by {@link VoxelModel#key(int, int, int)}
     * @param viewNames names from {@link #VIEWS}; must not be {@code null}
     * @param width     output width in pixels
     * @param height    output height in pixels
     * @return the images, one per requested name, in the same order
     * @throws IllegalArgumentException if a name is not a known view
     */
    public static List<BufferedImage> renderAll(SceneSpec spec, Map<Long, SceneGeometry.CellInfo> voxels,
                                                List<String> viewNames, int width, int height) {
        if (viewNames == null) throw new IllegalArgumentException("viewNames is null");
        List<BufferedImage> images = new ArrayList<>(viewNames.size());
        for (String name : viewNames) {
            View v = VIEWS.get(name);
            if (v == null) {
                throw new IllegalArgumentException("unknown view '" + name + "' (known: "
                        + String.join(", ", VIEWS.keySet()) + ")");
            }
            images.add(render(spec, voxels, v, DEFAULT_SUPERSAMPLE, width, height));
        }
        return images;
    }

    /**
     * Encodes an image as a PNG, ready for a {@code data:image/png;base64} chat message.
     *
     * @param image the image to encode; must not be {@code null}
     * @return the PNG file bytes
     * @throws IllegalStateException if the PNG encoder fails (it should not for an in-memory image)
     */
    public static byte[] toPng(BufferedImage image) {
        if (image == null) throw new IllegalArgumentException("image is null");
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException("PNG encoding failed", e);
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ camera

    /**
     * Places the orbit camera for a view and fills the camera basis.
     *
     * <p>The distance is solved from the projected bounding box instead of a fixed multiple of the model
     * radius: the eight corners of the grid are transformed into camera space, and the camera is moved back
     * until the widest and tallest corner fit the frustum with a small margin. The result is that the
     * building always fills most of the frame — for the vision critic a large subject means readable roof
     * edges and window frames, and a fixed distance leaves a tall build small in a wide frame.</p>
     *
     * @return the model radius (half its diagonal), used for distance fog
     */
    private static double camera(Grid g, View view, double[] eye, double[] fwd, double[] right, double[] up,
                                 int rw, int rh) {
        double cx = g.minX + g.sx / 2.0;
        double cy = g.minY + g.sy * 0.5;
        double cz = g.minZ + g.sz / 2.0;
        double radius = Math.sqrt((double) g.sx * g.sx + (double) g.sy * g.sy + (double) g.sz * g.sz) * 0.5;
        if (radius < 0.5) radius = 0.5;

        double az = Math.toRadians(view.azimuth());
        double el = Math.toRadians(view.elevation());
        double dirX = Math.sin(az) * Math.cos(el);
        double dirY = Math.sin(el);
        double dirZ = Math.cos(az) * Math.cos(el);

        // Aim slightly below the geometric centre so a tall build does not sit in the top half of the frame.
        // The offset is a fraction of the model height, not of the absolute y coordinate: a one-block model
        // far from y=0 must still be centred (an absolute offset would aim the camera below the frame).
        double tx = cx;
        double ty = cy - g.sy * 0.04;
        double tz = cz;

        normaliseInto(fwd, -dirX, -dirY, -dirZ);
        // right = fwd x worldUp, up = right x fwd  (a right-handed camera basis)
        normaliseInto(right, -fwd[2], 0, fwd[0]);
        up[0] = right[1] * fwd[2] - right[2] * fwd[1];
        up[1] = right[2] * fwd[0] - right[0] * fwd[2];
        up[2] = right[0] * fwd[1] - right[1] * fwd[0];
        if (right[0] == 0 && right[1] == 0 && right[2] == 0) {
            normaliseInto(right, 1, 0, 0);
            normaliseInto(up, 0, 1, 0);
        }

        // Fit the camera to the actual voxel silhouette. Using the eight corners of the bounding box would
        // include the empty corners of the box and leave a visible gap all round; walking the occupied cells
        // costs one pass over the grid (much less than the ray casting that follows) and frames the building
        // tightly, which is what matters for a vision critic.
        double halfW = 0;
        double halfH = 0;
        double halfD = 0;
        for (int iy = 0; iy < g.sy; iy++) {
            for (int iz = 0; iz < g.sz; iz++) {
                int row = g.sx * (iz + g.sz * iy);
                for (int ix = 0; ix < g.sx; ix++) {
                    if (g.data[row + ix] == 0) continue;
                    double vx = g.minX + ix + 0.5 - tx;
                    double vy = g.minY + iy + 0.5 - ty;
                    double vz = g.minZ + iz + 0.5 - tz;
                    double cw = Math.abs(vx * right[0] + vy * right[1] + vz * right[2]);
                    double ch = Math.abs(vx * up[0] + vy * up[1] + vz * up[2]);
                    double cd = Math.abs(vx * fwd[0] + vy * fwd[1] + vz * fwd[2]);
                    if (cw > halfW) halfW = cw;
                    if (ch > halfH) halfH = ch;
                    if (cd > halfD) halfD = cd;
                }
            }
        }
        // Half the diagonal of one cube: the corners of an edge cell stick out past its centre.
        double cellPad = 0.9;
        halfW += cellPad;
        halfH += cellPad;
        halfD += cellPad;

        double tanF = Math.tan(Math.toRadians(FOV) / 2);
        double aspect = rw <= 0 || rh <= 0 ? 1.0 : (double) rw / rh;
        double needV = halfH / tanF;
        double needH = halfW / (tanF * aspect);
        double dist = Math.max(needV, needH) * 1.05 + halfD;
        if (dist < radius * 1.2) dist = radius * 1.2;

        eye[0] = tx + dirX * dist;
        eye[1] = ty + dirY * dist;
        eye[2] = tz + dirZ * dist;
        return radius;
    }

    // ------------------------------------------------------------------ shading

    /**
     * Shades one hit: texture sample, sun, ambient occlusion, shadow ray, fog and glass.
     *
     * @return the packed RGB colour of the pixel (0xRRGGBB)
     */
    private static int shade(Grid g, Hit h, double dx, double dy, double dz, double radius, Hit behind) {
        int cell = h.index - 1;
        int mat = g.cellMat[cell];
        byte[] tex = g.textures[mat];
        int tu = clamp((int) Math.floor(h.u * TEX), 0, TEX - 1);
        int tv = clamp((int) Math.floor((1 - h.v) * TEX), 0, TEX - 1);
        int ti = (tv * TEX + tu) * 3;
        double tr = tex[ti] & 0xFF;
        double tg = tex[ti + 1] & 0xFF;
        double tb = tex[ti + 2] & 0xFF;

        double ndl = Math.max(0, h.nx * SUN[0] + h.ny * SUN[1] + h.nz * SUN[2]);
        double faceTint = 0.72 + 0.16 * (h.ny > 0 ? 1 : h.ny < 0 ? -1 : 0);
        double shade = 0.42 + 0.68 * ndl;

        // Ambient occlusion, sampled at the face quadrant the pixel falls into.
        int du = h.u < 0.5 ? -1 : 1;
        int dv = h.v < 0.5 ? -1 : 1;
        shade *= ambientOcclusion(g, h.hx, h.hy, h.hz, h.nx, h.ny, h.nz, du, dv);
        shade *= faceTint;

        // Shadow ray towards the sun: one quick extra traversal per lit face.
        if (ndl > 0 && (h.nx | h.ny | h.nz) != 0) {
            double ox = h.px + h.nx * 1e-3;
            double oy = h.py + h.ny * 1e-3;
            double oz = h.pz + h.nz * 1e-3;
            if (trace(g, ox, oy, oz, SUN[0], SUN[1], SUN[2], SHADOW_DISTANCE, false, behind)) {
                shade *= 0.55;
            }
        }

        // Distance fog pulls the far side of the build towards the sky colour.
        double fog = Math.min(0.35, h.t / (radius * 9));
        shade = shade * (1 - fog) + 1.02 * fog;

        // A very slight per-cell tint keeps large same-material walls from looking like one flat decal.
        double tint = 0.97 + 0.06 * hash3(h.hx, h.hy, h.hz, 91);

        double r = tr * shade * tint;
        double gc = tg * shade * tint;
        double b = tb * shade * tint;

        if (g.cellGlass[cell]) {
            // Transparent: continue behind the glass and mix what is there into the colour.
            double ox = h.px + h.nx * 1e-3;
            double oy = h.py + h.ny * 1e-3;
            double oz = h.pz + h.nz * 1e-3;
            if (trace(g, ox, oy, oz, dx, dy, dz, 512, true, behind)) {
                int bcell = behind.index - 1;
                byte[] btex = g.textures[g.cellMat[bcell]];
                int btu = clamp((int) Math.floor(behind.u * TEX), 0, TEX - 1);
                int btv = clamp((int) Math.floor((1 - behind.v) * TEX), 0, TEX - 1);
                int bi = (btv * TEX + btu) * 3;
                double bndl = Math.max(0.35, behind.nx * SUN[0] + behind.ny * SUN[1] + behind.nz * SUN[2]);
                r = r * 0.45 + (btex[bi] & 0xFF) * 0.55 * bndl;
                gc = gc * 0.45 + (btex[bi + 1] & 0xFF) * 0.55 * bndl;
                b = b * 0.45 + (btex[bi + 2] & 0xFF) * 0.55 * bndl;
            }
        }
        return (clamp255(r) << 16) | (clamp255(gc) << 8) | clamp255(b);
    }

    /**
     * Occlusion of a face corner: how many of the three cells around it are solid.
     *
     * @return a factor in {@code [0.46, 1]}, 1 = fully open corner
     */
    private static double ambientOcclusion(Grid g, int x, int y, int z, int nx, int ny, int nz, int du, int dv) {
        int tx;
        int ty;
        int tz;
        int ux;
        int uy;
        int uz;
        if (nx != 0) {
            tx = 0;
            ty = 1;
            tz = 0;
            ux = 0;
            uy = 0;
            uz = 1;
        } else if (ny != 0) {
            tx = 1;
            ty = 0;
            tz = 0;
            ux = 0;
            uy = 0;
            uz = 1;
        } else {
            tx = 1;
            ty = 0;
            tz = 0;
            ux = 0;
            uy = 1;
            uz = 0;
        }
        int bx = x + nx;
        int by = y + ny;
        int bz = z + nz;
        boolean s1 = gridAt(g, bx + tx * du, by + ty * du, bz + tz * du) > 0;
        boolean s2 = gridAt(g, bx + ux * dv, by + uy * dv, bz + uz * dv) > 0;
        boolean corner = gridAt(g, bx + tx * du + ux * dv, by + ty * du + uy * dv, bz + tz * du + uz * dv) > 0;
        if (s1 && s2) return 0.55;
        return 1 - 0.18 * ((s1 ? 1 : 0) + (s2 ? 1 : 0) + (corner ? 1 : 0));
    }

    // ------------------------------------------------------------------ traversal

    /**
     * Amanatides &amp; Woo voxel traversal with an AABB entry test.
     *
     * <p>The ray is advanced to where it enters the grid before the first cell is examined. Without that step most
     * pixels walk hundreds of empty cells outside the build — the 90&times; slow-down the JavaScript version
     * measured.</p>
     *
     * @param skipGlass when {@code true} glass cells are stepped through instead of returned (used to look behind
     *                  a window)
     * @param out       filled with the hit when the method returns {@code true}
     * @return {@code true} when a solid (or, with {@code skipGlass} off, the first solid) cell was hit
     */
    private static boolean trace(Grid g, double ox, double oy, double oz,
                                 double dx, double dy, double dz,
                                 double maxDist, boolean skipGlass, Hit out) {
        out.hit = false;
        double bx0 = g.minX;
        double by0 = g.minY;
        double bz0 = g.minZ;
        double bx1 = g.minX + g.sx;
        double by1 = g.minY + g.sy;
        double bz1 = g.minZ + g.sz;

        double tEnter = 0;
        double tExit = maxDist;
        int axis = -1;
        if (Math.abs(dx) < 1e-9) {
            if (ox < bx0 || ox > bx1) return false;
        } else {
            double t1 = (bx0 - ox) / dx;
            double t2 = (bx1 - ox) / dx;
            double lo = Math.min(t1, t2);
            double hi = Math.max(t1, t2);
            if (lo > tEnter) {
                tEnter = lo;
                axis = 0;
            }
            tExit = Math.min(tExit, hi);
        }
        if (Math.abs(dy) < 1e-9) {
            if (oy < by0 || oy > by1) return false;
        } else {
            double t1 = (by0 - oy) / dy;
            double t2 = (by1 - oy) / dy;
            double lo = Math.min(t1, t2);
            double hi = Math.max(t1, t2);
            if (lo > tEnter) {
                tEnter = lo;
                axis = 1;
            }
            tExit = Math.min(tExit, hi);
        }
        if (Math.abs(dz) < 1e-9) {
            if (oz < bz0 || oz > bz1) return false;
        } else {
            double t1 = (bz0 - oz) / dz;
            double t2 = (bz1 - oz) / dz;
            double lo = Math.min(t1, t2);
            double hi = Math.max(t1, t2);
            if (lo > tEnter) {
                tEnter = lo;
                axis = 2;
            }
            tExit = Math.min(tExit, hi);
        }
        if (tEnter > tExit || tExit < 0) return false;

        // Move the traversal origin to the entry point; the DDA then starts in a real cell.
        double px = ox;
        double py = oy;
        double pz = oz;
        double base = 0;
        int nx = 0;
        int ny = 0;
        int nz = 0;
        if (tEnter > 0) {
            base = tEnter + 1e-6;
            px += dx * base;
            py += dy * base;
            pz += dz * base;
            if (axis == 0) {
                nx = dx > 0 ? -1 : 1;
            } else if (axis == 1) {
                ny = dy > 0 ? -1 : 1;
            } else if (axis == 2) {
                nz = dz > 0 ? -1 : 1;
            }
        }

        int x = (int) Math.floor(px);
        int y = (int) Math.floor(py);
        int z = (int) Math.floor(pz);
        int stepX = dx > 0 ? 1 : -1;
        int stepY = dy > 0 ? 1 : -1;
        int stepZ = dz > 0 ? 1 : -1;
        double tDeltaX = dx == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dx);
        double tDeltaY = dy == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dy);
        double tDeltaZ = dz == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dz);
        double tMaxX = dx == 0 ? Double.POSITIVE_INFINITY : (stepX > 0 ? x + 1 - px : px - x) * tDeltaX;
        double tMaxY = dy == 0 ? Double.POSITIVE_INFINITY : (stepY > 0 ? y + 1 - py : py - y) * tDeltaY;
        double tMaxZ = dz == 0 ? Double.POSITIVE_INFINITY : (stepZ > 0 ? z + 1 - pz : pz - z) * tDeltaZ;
        double t = 0;

        int maxX = g.minX + g.sx;
        int maxY = g.minY + g.sy;
        int maxZ = g.minZ + g.sz;

        for (int guard = 0; guard < MAX_STEPS; guard++) {
            int ix = x - g.minX;
            int iy = y - g.minY;
            int iz = z - g.minZ;
            if (ix >= 0 && iy >= 0 && iz >= 0 && ix < g.sx && iy < g.sy && iz < g.sz) {
                int idx = g.data[ix + g.sx * (iz + g.sz * iy)];
                if (idx > 0 && !(skipGlass && g.cellGlass[idx - 1])) {
                    if (nx == 0 && ny == 0 && nz == 0) {
                        // The camera started inside the cell (only possible for a shadow ray origin):
                        // derive a face from the dominant ray direction so u,v are still meaningful.
                        double ax = Math.abs(dx);
                        double ay = Math.abs(dy);
                        double az = Math.abs(dz);
                        if (ax >= ay && ax >= az) {
                            nx = dx > 0 ? -1 : 1;
                        } else if (ay >= az) {
                            ny = dy > 0 ? -1 : 1;
                        } else {
                            nz = dz > 0 ? -1 : 1;
                        }
                    }
                    double hx = px + dx * t;
                    double hy = py + dy * t;
                    double hz = pz + dz * t;
                    double u;
                    double v;
                    if (nx != 0) {
                        u = hz - Math.floor(hz);
                        v = hy - Math.floor(hy);
                    } else if (ny != 0) {
                        u = hx - Math.floor(hx);
                        v = hz - Math.floor(hz);
                    } else {
                        u = hx - Math.floor(hx);
                        v = hy - Math.floor(hy);
                    }
                    out.hit = true;
                    out.index = idx;
                    out.t = base + t;
                    out.nx = nx;
                    out.ny = ny;
                    out.nz = nz;
                    out.u = u;
                    out.v = v;
                    out.px = hx;
                    out.py = hy;
                    out.pz = hz;
                    out.hx = x;
                    out.hy = y;
                    out.hz = z;
                    return true;
                }
            }
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                x += stepX;
                t = tMaxX;
                tMaxX += tDeltaX;
                nx = -stepX;
                ny = 0;
                nz = 0;
            } else if (tMaxY < tMaxZ) {
                y += stepY;
                t = tMaxY;
                tMaxY += tDeltaY;
                nx = 0;
                ny = -stepY;
                nz = 0;
            } else {
                z += stepZ;
                t = tMaxZ;
                tMaxZ += tDeltaZ;
                nx = 0;
                ny = 0;
                nz = -stepZ;
            }
            if (x < g.minX || y < g.minY || z < g.minZ || x >= maxX || y >= maxY || z >= maxZ) break;
            if (base + t > maxDist) break;
        }
        return false;
    }

    // ------------------------------------------------------------------ post-processing

    /**
     * Darkens pixels whose depth jumps to a neighbour, or that border the sky.
     *
     * <p>These one-pixel edges are what make the grid of cubes read as blocks instead of a single painted surface;
     * they are the visual language of Minecraft and the critic must see it.</p>
     */
    private static void outline(double[] colours, float[] depth, boolean[] lit, int rw, int rh) {
        for (int py = 0; py < rh; py++) {
            for (int px = 0; px < rw; px++) {
                int o = py * rw + px;
                if (!lit[o]) continue;
                int right = px + 1 < rw ? o + 1 : -1;
                int down = py + 1 < rh ? o + rw : -1;
                boolean edge = false;
                if (right >= 0 && lit[right] && Math.abs(depth[o] - depth[right]) > 0.35) edge = true;
                if (down >= 0 && lit[down] && Math.abs(depth[o] - depth[down]) > 0.35) edge = true;
                if (right >= 0 && !lit[right]) edge = true;
                if (down >= 0 && !lit[down]) edge = true;
                if (edge) {
                    colours[o * 3] *= 0.62;
                    colours[o * 3 + 1] *= 0.62;
                    colours[o * 3 + 2] *= 0.62;
                }
            }
        }
    }

    /** Box-filters the supersampled frame into the output RGB array. */
    private static void downsample(double[] colours, int rw, int w, int h, int ss, int[] out) {
        int samples = ss * ss;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double r = 0;
                double gc = 0;
                double b = 0;
                for (int dy = 0; dy < ss; dy++) {
                    int row = (y * ss + dy) * rw;
                    for (int dx = 0; dx < ss; dx++) {
                        int o = (row + x * ss + dx) * 3;
                        r += colours[o];
                        gc += colours[o + 1];
                        b += colours[o + 2];
                    }
                }
                out[y * w + x] = (clamp255(r / samples) << 16) | (clamp255(gc / samples) << 8) | clamp255(b / samples);
            }
        }
    }

    private static BufferedImage toImage(int[] rgb, int w, int h) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, w, h, rgb, 0, w);
        return image;
    }

    private static void setRgb(double[] colours, int o, int rgb) {
        colours[o * 3] = (rgb >> 16) & 0xFF;
        colours[o * 3 + 1] = (rgb >> 8) & 0xFF;
        colours[o * 3 + 2] = rgb & 0xFF;
    }

    // ------------------------------------------------------------------ grid

    /** Immutable per-render snapshot of the voxel set: dense cells plus their textures. */
    private static final class Grid {
        final int minX;
        final int minY;
        final int minZ;
        final int sx;
        final int sy;
        final int sz;
        final int[] data;
        final int[] cellMat;
        final boolean[] cellGlass;
        final byte[][] textures;

        private Grid(int minX, int minY, int minZ, int sx, int sy, int sz,
                     int[] data, int[] cellMat, boolean[] cellGlass, byte[][] textures) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
            this.data = data;
            this.cellMat = cellMat;
            this.cellGlass = cellGlass;
            this.textures = textures;
        }

        /**
         * Expands the sparse voxel map into a dense grid and generates one texture per material present.
         *
         * @return the grid, or {@code null} when there is nothing to draw
         */
        static Grid of(SceneSpec spec, Map<Long, SceneGeometry.CellInfo> voxels) {
            if (voxels == null || voxels.isEmpty()) return null;

            int minX = Integer.MAX_VALUE;
            int minY = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxY = Integer.MIN_VALUE;
            int maxZ = Integer.MIN_VALUE;
            for (long key : voxels.keySet()) {
                int x = VoxelModel.kx(key);
                int y = VoxelModel.ky(key);
                int z = VoxelModel.kz(key);
                if (x < minX) minX = x;
                if (y < minY) minY = y;
                if (z < minZ) minZ = z;
                if (x > maxX) maxX = x;
                if (y > maxY) maxY = y;
                if (z > maxZ) maxZ = z;
            }
            int sx = maxX - minX + 1;
            int sy = maxY - minY + 1;
            int sz = maxZ - minZ + 1;
            long volume = (long) sx * sy * sz;
            if (volume > MAX_GRID_CELLS) {
                throw new IllegalArgumentException("voxel grid too large to render: " + sx + "x" + sy + "x" + sz
                        + " = " + volume + " cells (limit " + MAX_GRID_CELLS + ")");
            }

            int[] data = new int[(int) volume];
            int[] cellMat = new int[voxels.size()];
            boolean[] cellGlass = new boolean[voxels.size()];
            Map<String, Integer> materialIndex = new LinkedHashMap<>();
            List<String> materials = new ArrayList<>();
            int cell = 0;

            for (Map.Entry<Long, SceneGeometry.CellInfo> e : voxels.entrySet()) {
                long key = e.getKey();
                int x = VoxelModel.kx(key) - minX;
                int y = VoxelModel.ky(key) - minY;
                int z = VoxelModel.kz(key) - minZ;
                SceneGeometry.CellInfo info = e.getValue();
                String material = info == null ? "" : info.material();
                if (material == null) material = "";
                Integer mi = materialIndex.get(material);
                if (mi == null) {
                    mi = materials.size();
                    materialIndex.put(material, mi);
                    materials.add(material);
                }
                cellMat[cell] = mi;
                cellGlass[cell] = isGlass(material);
                data[x + sx * (z + sz * y)] = cell + 1;
                cell++;
            }

            byte[][] textures = new byte[materials.size()][];
            long seed = spec == null ? 1L : spec.seed();
            for (int i = 0; i < materials.size(); i++) {
                String m = materials.get(i);
                textures[i] = makeTexture(m, baseColour(spec, m), (int) (seed + (m.hashCode() * 31L) % 997));
            }
            return new Grid(minX, minY, minZ, sx, sy, sz, data, cellMat, cellGlass, textures);
        }
    }

    /** Sparse lookup used by ambient occlusion and the entry checks; 0 = empty. */
    private static int gridAt(Grid g, int x, int y, int z) {
        int ix = x - g.minX;
        int iy = y - g.minY;
        int iz = z - g.minZ;
        if (ix < 0 || iy < 0 || iz < 0 || ix >= g.sx || iy >= g.sy || iz >= g.sz) return 0;
        return g.data[ix + g.sx * (iz + g.sz * iy)];
    }

    /** Mutable traversal result, allocated once per {@link #render} call. */
    private static final class Hit {
        boolean hit;
        double t;
        int nx;
        int ny;
        int nz;
        int index;
        double u;
        double v;
        double px;
        double py;
        double pz;
        int hx;
        int hy;
        int hz;
    }

    // ------------------------------------------------------------------ textures

    /**
     * Builds the procedural 16&times;16 texture of a material.
     *
     * <p>The palette colour is the base; the family (from the material name) decides the pattern, so stone,
     * planks, roof tiles, bricks, glass, leaves and metal are distinguishable at a glance even in a small render.</p>
     *
     * @param material the material key or block name (only used for classification)
     * @param base     the base colour, RGB
     * @param seed     texture seed, derived from the spec seed and the material name
     * @return {@code TEX*TEX*3} bytes, RGB
     */
    private static byte[] makeTexture(String material, int[] base, int seed) {
        byte[] out = new byte[TEX * TEX * 3];
        String kind = classify(material);
        for (int ty = 0; ty < TEX; ty++) {
            for (int tx = 0; tx < TEX; tx++) {
                double[] c = {base[0], base[1], base[2]};
                double n = noise2(tx / 3.2, ty / 3.2, seed);
                double grain = (n - 0.5) * 22;
                switch (kind) {
                    case "stone" -> {
                        double blotch = (noise2(tx / 1.7, ty / 1.7, seed + 11) - 0.5) * 26;
                        add(c, grain + blotch);
                        if (Math.floorMod(tx * 7 + ty * 13 + seed, 23) == 0) add(c, -26);
                    }
                    case "planks" -> {
                        int plankRow = ty / 4;
                        double seam = ty % 4 == 0 ? -30 : 0;
                        double along = Math.floorMod(tx + plankRow * 5 + seed, 16) == 0 ? -24 : 0;
                        add(c, seam + along + grain * 0.8);
                    }
                    case "roof" -> {
                        int row = ty / 4;
                        int ty2 = ty % 4;
                        int off = (row % 2) * 4;
                        double joint = Math.floorMod(tx + off, 8) == 0 ? -28 : 0;
                        double band = ty2 == 3 ? -34 : ty2 == 0 ? 12 : 0;
                        add(c, joint + band + grain * 0.7);
                    }
                    case "brick" -> {
                        int row = ty / 4;
                        int off = (row % 2) * 4;
                        boolean mortar = ty % 4 == 3 || Math.floorMod(tx + off, 8) == 7;
                        if (mortar) {
                            c[0] = 196;
                            c[1] = 190;
                            c[2] = 180;
                        } else {
                            add(c, grain);
                        }
                    }
                    case "leaves" -> {
                        double a = noise2(tx / 1.3, ty / 1.3, seed + 3);
                        for (int i = 0; i < 3; i++) c[i] = c[i] * (0.72 + a * 0.5) + (i == 1 ? 18 : 0);
                    }
                    case "glass" -> {
                        boolean edge = tx < 1 || ty < 1 || tx > TEX - 2 || ty > TEX - 2;
                        c[0] = edge ? 200 : 148;
                        c[1] = edge ? 214 : 186;
                        c[2] = edge ? 222 : 205;
                    }
                    case "metal" -> {
                        double streak = Math.abs(Math.sin((tx + ty * 0.5) * 0.7 + seed)) * 18;
                        add(c, streak - 6);
                    }
                    default -> add(c, grain);
                }
                int o = (ty * TEX + tx) * 3;
                out[o] = (byte) clamp255(c[0]);
                out[o + 1] = (byte) clamp255(c[1]);
                out[o + 2] = (byte) clamp255(c[2]);
            }
        }
        return out;
    }

    /** Adds a delta to all three channels. */
    private static void add(double[] c, double d) {
        c[0] += d;
        c[1] += d;
        c[2] += d;
    }

    /**
     * Groups a material into one of the eight texture families.
     *
     * <p>The order matters: it mirrors the JavaScript renderer, so {@code stone_bricks} gets the brick pattern
     * (which is what the block looks like) rather than plain stone.</p>
     */
    private static String classify(String material) {
        String m = material == null ? "" : material.toLowerCase(Locale.ROOT);
        if (m.contains("glass") || m.contains("pane")) return "glass";
        if (m.contains("planks") || m.contains("wood") || m.contains("oak") || m.contains("spruce")
                || m.contains("birch") || m.contains("jungle") || m.contains("acacia") || m.contains("log")) {
            return "planks";
        }
        if (m.contains("roof") || m.contains("tile") || m.contains("shingle") || m.contains("slate")) return "roof";
        if (m.contains("brick")) return "brick";
        if (m.contains("leaf") || m.contains("leaves") || m.contains("moss") || m.contains("grass")) return "leaves";
        if (m.contains("metal") || m.contains("iron") || m.contains("gold") || m.contains("copper")) return "metal";
        if (m.contains("stone") || m.contains("cobble") || m.contains("deepslate") || m.contains("andesite")
                || m.contains("granite") || m.contains("tuff") || m.contains("rock")) {
            return "stone";
        }
        return "plain";
    }

    /** @return {@code true} for glass and panes, the only translucent family */
    private static boolean isGlass(String material) {
        String m = material == null ? "" : material.toLowerCase(Locale.ROOT);
        return m.contains("glass") || m.contains("pane");
    }

    // ------------------------------------------------------------------ colour

    /**
     * Resolves the base colour of a material: a palette key, a {@code #rrggbb} literal, or a Minecraft block name.
     */
    private static int[] baseColour(SceneSpec spec, String material) {
        if (material == null || material.isBlank()) return new int[]{160, 160, 160};
        String m = material.trim();
        if (m.startsWith("#")) {
            int[] c = parseHex(m);
            if (c != null) return c;
        }
        if (spec != null && spec.palette().containsKey(m)) {
            int[] c = parseHex(spec.palette().get(m));
            if (c != null) return c;
        }
        return colourForBlock(m.toLowerCase(Locale.ROOT));
    }

    /**
     * Fallback colour for a bare Minecraft block name, used when the cell does not carry a palette key.
     *
     * <p>Only a compact table of the materials the scene format actually emits: the renderer's job is shape and
     * light, not a full block colour catalogue.</p>
     */
    private static int[] colourForBlock(String block) {
        if (block.contains("dark_oak")) return new int[]{74, 48, 24};
        if (block.contains("oak") || block.contains("spruce")) return new int[]{138, 98, 66};
        if (block.contains("birch")) return new int[]{196, 178, 123};
        if (block.contains("terracotta")) return new int[]{217, 207, 186};
        if (block.contains("deepslate")) return new int[]{74, 74, 78};
        if (block.contains("stone") || block.contains("cobble") || block.contains("andesite")) {
            return new int[]{138, 138, 138};
        }
        if (block.contains("brick")) return new int[]{154, 90, 68};
        if (block.contains("glass") || block.contains("pane")) return new int[]{169, 201, 217};
        if (block.contains("iron") || block.contains("metal")) return new int[]{176, 176, 184};
        if (block.contains("gold")) return new int[]{224, 192, 80};
        if (block.contains("copper")) return new int[]{192, 122, 74};
        if (block.contains("leaf") || block.contains("leaves")) return new int[]{78, 122, 52};
        if (block.contains("water")) return new int[]{70, 110, 190};
        return new int[]{160, 160, 160};
    }

    /**
     * Parses {@code #rgb} or {@code #rrggbb}.
     *
     * @return the RGB triple, or {@code null} when the string is not a hex colour
     */
    private static int[] parseHex(String hex) {
        if (hex == null) return null;
        String s = hex.trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() == 3) {
            s = "" + s.charAt(0) + s.charAt(0) + s.charAt(1) + s.charAt(1) + s.charAt(2) + s.charAt(2);
        }
        if (s.length() != 6) return null;
        for (int i = 0; i < 6; i++) {
            if (Character.digit(s.charAt(i), 16) < 0) return null;
        }
        return new int[]{
                Integer.parseInt(s.substring(0, 2), 16),
                Integer.parseInt(s.substring(2, 4), 16),
                Integer.parseInt(s.substring(4, 6), 16)};
    }

    // ------------------------------------------------------------------ noise

    /** Deterministic integer hash mapped to {@code [0,1)} — the texture engine, no {@link Math#random()}. */
    private static double hash3(int x, int y, int z, int salt) {
        int h = x * 374761393 + y * 668265263 + z * 2147483647 + salt * 1442695040;
        h = (h ^ (h >>> 13)) * 1274126177;
        h = h ^ (h >>> 16);
        return (h & 0xFFFFFFFFL) / 4294967296.0;
    }

    /** Smooth value noise used for texture grain. */
    private static double noise2(double x, double y, int seed) {
        int xi = (int) Math.floor(x);
        int yi = (int) Math.floor(y);
        double xf = x - xi;
        double yf = y - yi;
        double u = xf * xf * (3 - 2 * xf);
        double v = yf * yf * (3 - 2 * yf);
        double a = hash3(xi, yi, seed, 7) * (1 - u) + hash3(xi + 1, yi, seed, 7) * u;
        double b = hash3(xi, yi + 1, seed, 7) * (1 - u) + hash3(xi + 1, yi + 1, seed, 7) * u;
        return a * (1 - v) + b * v;
    }

    // ------------------------------------------------------------------ math helpers

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    private static int clamp255(double v) {
        return v < 0 ? 0 : v > 255 ? 255 : (int) v;
    }

    private static double[] normalise(double x, double y, double z) {
        double[] v = new double[3];
        normaliseInto(v, x, y, z);
        return v;
    }

    private static void normaliseInto(double[] out, double x, double y, double z) {
        double len = Math.sqrt(x * x + y * y + z * z);
        if (len < 1e-12) {
            out[0] = 0;
            out[1] = 0;
            out[2] = 0;
            return;
        }
        out[0] = x / len;
        out[1] = y / len;
        out[2] = z / len;
    }
}
