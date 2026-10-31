/**
 * Software renderer for AIBuild 3D.
 *
 * No WebGL, no GPU and no three.js: geometry is read straight from the scene contract
 * (`scene.solids[].faces`, each face a triangle or a quad in world units) and rasterized
 * here into an RGB buffer with a z-buffer.  The result is encoded by the hand-written PNG
 * encoder in `./png.mjs`.
 *
 * Per view:
 *   - perspective pinhole camera orbiting the model.  The distance is fitted exactly to the
 *     scene bounding box (all eight corners are projected and the smallest distance that keeps
 *     them inside the frustum is used), so any model size frames correctly;
 *   - one directional sun + cool fill + sky/ground ambient, contact darkening near the ground;
 *   - a ray-cast ground plane receiving a soft projected shadow (the whole silhouette is
 *     projected along the sun direction into a blurred grid and sampled per ground pixel);
 *   - z-buffered opaque pass, back-face culling for closed solids, then a back-to-front
 *     alpha pass for glass and detail faces;
 *   - a depth-discontinuity outline, optional supersampling and a mild vignette.
 *
 * The renderer is deterministic (no Math.random) and synchronous under the returned promises.
 *
 * ```js
 * import { renderViews, renderPng, cameraForView } from './render.mjs';
 * const { views } = await renderViews(scene, { width: 800, height: 600, draft: true });
 * const png = await renderPng(scene, 'se', { width: 400, height: 300 });
 * ```
 */

import { encodePng } from './png.mjs';
import { parseHex } from './materials.mjs';
import { DEFAULT_PALETTE } from './spec.mjs';

const DEG = Math.PI / 180;
const EPS = 1e-9;

/** Default set of camera views, in the order the studio expects them. */
export const DEFAULT_VIEWS = /** @type {const} */ (['se', 'sw', 'ne', 'nw', 'front', 'top', 'back']);

/**
 * Orbit angles per named view.  Azimuth is measured from +z (south) towards +x (east), so
 * `se` looks from the south-east corner, `front` is the south facade and `back` the north one.
 * `top` is a straight-down plan view with north up.
 */
export const VIEW_ANGLES = /** @type {Record<string, {azimuth: number, elevation: number}>} */ ({
  se: { azimuth: 45, elevation: 22 },
  sw: { azimuth: -45, elevation: 22 },
  ne: { azimuth: 135, elevation: 22 },
  nw: { azimuth: -135, elevation: 22 },
  front: { azimuth: 0, elevation: 10 },
  back: { azimuth: 180, elevation: 10 },
  top: { azimuth: 0, elevation: 90 },
});

/** Direction towards the sun (key light), south-west and high. */
const SUN = normalize3([-0.38, 0.84, 0.39]);
/** Direction towards the fill light: cool light from the north-east, keeps shadow sides readable. */
const FILL = normalize3([0.62, 0.26, -0.74]);

/** Defaults accepted by `renderViews` / `renderPng`. */
export const RENDER_DEFAULTS = /** @type {const} */ ({
  width: 800,
  height: 600,
  views: DEFAULT_VIEWS,
  background: '#eef3f8',
  ground: null,
  draft: false,
  fov: 42,
  margin: 1.06,
  supersample: null,
  shadows: null,
  shadowRes: 160,
  outline: true,
  outlineStrength: 0.34,
  vignette: 0.05,
  glassAlpha: 0.45,
  detailAlpha: 0.88,
  glassBias: 2e-4,
  detailBias: 4e-4,
  coplanarBias: 2e-7,
  pngLevel: 9,
});

/* ------------------------------------------------------------------------------------ */
/* small numeric helpers                                                                 */
/* ------------------------------------------------------------------------------------ */

function normalize3(v) {
  const len = Math.hypot(v[0], v[1], v[2]);
  if (!(len > EPS)) return [0, 0, 0];
  return [v[0] / len, v[1] / len, v[2] / len];
}

function dot3(a, b) {
  return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
}

function cross3(a, b) {
  return [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
}

const clamp = (v, lo, hi) => (v < lo ? lo : v > hi ? hi : v);
const clamp255 = (v) => (v < 0 ? 0 : v > 255 ? 255 : v);

function smoothstep(e0, e1, x) {
  const t = clamp((x - e0) / (e1 - e0 || EPS), 0, 1);
  return t * t * (3 - 2 * t);
}

function mixColor(a, b, t) {
  return [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t];
}

function hexToRgb(hex, fallback = [200, 200, 200]) {
  const parsed = parseHex(hex);
  return parsed ? [parsed[0], parsed[1], parsed[2]] : fallback;
}

/** Stable pleasant colour for a palette key the spec does not define (never throws). */
function hashColor(key) {
  let h = 2166136261;
  for (let i = 0; i < key.length; i++) h = Math.imul(h ^ key.charCodeAt(i), 16777619);
  const hue = ((h >>> 0) % 360) / 360;
  const sat = 0.14 + (((h >>> 9) & 0xff) / 255) * 0.18;
  const light = 0.55 + (((h >>> 17) & 0xff) / 255) * 0.18;
  return hslToRgb(hue, sat, light);
}

function hslToRgb(h, s, l) {
  if (s === 0) return [l * 255, l * 255, l * 255];
  const q = l < 0.5 ? l * (1 + s) : l + s - l * s;
  const p = 2 * l - q;
  const channel = (t) => {
    let x = t;
    if (x < 0) x += 1;
    if (x > 1) x -= 1;
    if (x < 1 / 6) return p + (q - p) * 6 * x;
    if (x < 1 / 2) return q;
    if (x < 2 / 3) return p + (q - p) * (2 / 3 - x) * 6;
    return p;
  };
  return [channel(h + 1 / 3) * 255, channel(h) * 255, channel(h - 1 / 3) * 255];
}

/* ------------------------------------------------------------------------------------ */
/* scene preparation: faces -> shaded triangles, bounds, orientation, shadow grid        */
/* ------------------------------------------------------------------------------------ */

/** Reads a face polygon as an array of [x,y,z]; returns null when the face is unusable. */
function readPolygon(face) {
  const raw = face && face.v;
  if (!Array.isArray(raw) || raw.length < 3) return null;
  const out = [];
  for (let i = 0; i < raw.length; i++) {
    const p = raw[i];
    if (!p) return null;
    const x = Array.isArray(p) ? p[0] : p.x;
    const y = Array.isArray(p) ? p[1] : p.y;
    const z = Array.isArray(p) ? p[2] : p.z;
    if (!Number.isFinite(x) || !Number.isFinite(y) || !Number.isFinite(z)) return null;
    out.push([x, y, z]);
  }
  return out;
}

/** Geometric normal from the first non-degenerate vertex triple of a polygon. */
function polygonNormal(pts) {
  for (let i = 1; i + 1 < pts.length; i++) {
    const n = cross3(
      [pts[i][0] - pts[0][0], pts[i][1] - pts[0][1], pts[i][2] - pts[0][2]],
      [pts[i + 1][0] - pts[0][0], pts[i + 1][1] - pts[0][1], pts[i + 1][2] - pts[0][2]],
    );
    if (Math.hypot(n[0], n[1], n[2]) > 1e-7) return normalize3(n);
  }
  return null;
}

function readNormal(face) {
  const raw = face && face.normal;
  if (!raw) return null;
  const x = Array.isArray(raw) ? raw[0] : raw.x;
  const y = Array.isArray(raw) ? raw[1] : raw.y;
  const z = Array.isArray(raw) ? raw[2] : raw.z;
  if (!Number.isFinite(x) || !Number.isFinite(y) || !Number.isFinite(z)) return null;
  const n = normalize3([x, y, z]);
  return Math.hypot(n[0], n[1], n[2]) > 0.5 ? n : null;
}

const isGlassMaterial = (material) => typeof material === 'string' && /glass/i.test(material);

/** Total polygon area, summed over a triangle fan from the first vertex. */
function polygonArea(pts) {
  let total = 0;
  for (let i = 1; i + 1 < pts.length; i++) {
    const ux = pts[i][0] - pts[0][0];
    const uy = pts[i][1] - pts[0][1];
    const uz = pts[i][2] - pts[0][2];
    const vx = pts[i + 1][0] - pts[0][0];
    const vy = pts[i + 1][1] - pts[0][1];
    const vz = pts[i + 1][2] - pts[0][2];
    total += 0.5 * Math.hypot(uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx);
  }
  return total;
}

/** Material -> base RGB, cached. Accepts palette keys, raw hex and unknown keys. */
function resolveColor(material, palette, cache, materials) {
  const key = String(material);
  const cached = cache.get(key);
  if (cached) return cached;
  let rgb = null;
  if (key[0] === '#') rgb = parseHex(key);
  else if (palette[key] !== undefined) rgb = parseHex(palette[key]);
  else if (materials && materials[key] !== undefined) rgb = parseHex(materials[key]);
  if (!rgb) rgb = key === 'plain' ? [200, 200, 200] : hashColor(key);
  cache.set(key, rgb);
  return rgb;
}

/**
 * One flat-shaded face colour.  Sun + fill + sky ambient, contact darkening near the ground
 * and a tiny deterministic per-face brightness jitter so large flat walls do not look dead.
 */
function shadeFace(base, n, pts, opts) {
  const ny = n[1];
  const ambient = 0.34 + 0.10 * (ny * 0.5 + 0.5);
  const sun = 0.62 * Math.max(0, dot3(n, opts.sunDir));
  const fill = 0.20 * Math.max(0, dot3(n, opts.fillDir));
  const bounce = 0.06 * Math.max(0, -ny);
  let y = 0;
  for (let i = 0; i < pts.length; i++) y += pts[i][1];
  y /= pts.length;
  const contact = 0.87 + 0.13 * clamp((y - opts.groundY) / opts.aoHeight, 0, 1);
  const jitter = 0.975 + hashUnit(pts[0][0], pts[0][1], pts[0][2], base) * 0.05;
  const light = Math.min(1.12, (ambient + sun + fill + bounce) * contact * jitter);
  return [clamp255(base[0] * light), clamp255(base[1] * light), clamp255(base[2] * light)];
}

/** Deterministic pseudo-random value in [0,1) derived from a position and a colour. */
function hashUnit(x, y, z, base) {
  const v = Math.sin(x * 12.9898 + y * 78.233 + z * 37.719 + base[0] * 0.017 + base[1] * 0.031) * 43758.5453;
  return v - Math.floor(v);
}

/**
 * Converts the scene contract into flat triangles with baked colours.
 *
 * Orientation handling: solids are closed shells in practice, so the signed volume tells us
 * whether the provided normals/winding point outwards.  If they point inwards we flip them;
 * if a solid is not closed (a single plane, a decal) we do not cull its faces at all and just
 * trust the provided normal.  This makes culling safe for hand-made scenes and for geometry
 * from task A regardless of its winding convention.
 *
 * @param {object} scene
 * @param {object} opts normalized options (sunDir, colors, alphas, shadow settings)
 * @returns {object} prepared scene consumed by renderToBuffer
 */
function prepareScene(scene, opts) {
  const spec = scene && typeof scene === 'object' ? scene.spec : null;
  const palette = { ...DEFAULT_PALETTE, ...(spec && spec.palette ? spec.palette : {}) };
  const colorCache = new Map();
  const solids = scene && Array.isArray(scene.solids) ? scene.solids : [];

  const min = [Infinity, Infinity, Infinity];
  const max = [-Infinity, -Infinity, -Infinity];

  // ---- pass 1: parse polygons and gather per-solid geometry -------------------------
  const records = []; // { solid, pts, geo, given, material, kind, area, centroid }
  for (let si = 0; si < solids.length; si++) {
    const solid = solids[si];
    if (!solid || !Array.isArray(solid.faces)) continue;
    for (let fi = 0; fi < solid.faces.length; fi++) {
      const face = solid.faces[fi];
      const pts = readPolygon(face);
      if (!pts) continue;
      const geo = polygonNormal(pts);
      if (!geo) continue;
      const area = polygonArea(pts);
      if (!(area > 1e-9)) continue;
      const cx = pts.reduce((s, p) => s + p[0], 0) / pts.length;
      const cy = pts.reduce((s, p) => s + p[1], 0) / pts.length;
      const cz = pts.reduce((s, p) => s + p[2], 0) / pts.length;
      for (let p = 0; p < pts.length; p++) {
        const v = pts[p];
        if (v[0] < min[0]) min[0] = v[0];
        if (v[1] < min[1]) min[1] = v[1];
        if (v[2] < min[2]) min[2] = v[2];
        if (v[0] > max[0]) max[0] = v[0];
        if (v[1] > max[1]) max[1] = v[1];
        if (v[2] > max[2]) max[2] = v[2];
      }
      records.push({
        solid,
        order: si,
        pts,
        geo,
        given: readNormal(face),
        material: face.material ?? solid.material ?? 'plain',
        kind: face.kind || 'surface',
        area,
        centroid: [cx, cy, cz],
      });
    }
  }

  const empty = records.length === 0;
  const bounds = empty
    ? null
    : {
        min,
        max,
        size: [max[0] - min[0], max[1] - min[1], max[2] - min[2]],
        center: [(min[0] + max[0]) / 2, (min[1] + max[1]) / 2, (min[2] + max[2]) / 2],
      };
  if (bounds) {
    bounds.radius = 0.5 * Math.hypot(bounds.size[0], bounds.size[1], bounds.size[2]) || 0.5;
    bounds.groundY = bounds.min[1] - 0.02;
    bounds.aoHeight = clamp(bounds.size[1] * 0.28, 1.2, 9);
    bounds.horizontal = Math.max(bounds.size[0], bounds.size[2], 1);
  }
  const context = {
    sunDir: opts.sunDir,
    fillDir: opts.fillDir,
    groundY: bounds ? bounds.groundY : 0,
    aoHeight: bounds ? bounds.aoHeight : 3,
  };

  // ---- pass 2: per-solid orientation, then triangles ---------------------------------
  const infoBySolid = new Map();
  for (const rec of records) {
    let info = infoBySolid.get(rec.solid);
    if (!info) {
      info = { volume: 0, bboxVol: 1, verts: [] };
      for (const p of rec.pts) info.verts.push(p);
      infoBySolid.set(rec.solid, info);
    } else {
      for (const p of rec.pts) info.verts.push(p);
    }
    const n = rec.given || rec.geo;
    info.volume += dot3(n, rec.centroid) * rec.area / 3;
  }
  for (const info of infoBySolid.values()) {
    let lo = [Infinity, Infinity, Infinity];
    let hi = [-Infinity, -Infinity, -Infinity];
    for (const p of info.verts) {
      for (let k = 0; k < 3; k++) {
        if (p[k] < lo[k]) lo[k] = p[k];
        if (p[k] > hi[k]) hi[k] = p[k];
      }
    }
    const ext = [hi[0] - lo[0], hi[1] - lo[1], hi[2] - lo[2]];
    const bboxVol = ext[0] * ext[1] * ext[2];
    // A solid only counts as closed when it has real thickness on every axis and the
    // divergence-theorem volume is a sane fraction of its bounding box.  A flat plane has
    // zero thickness -> open -> never culled, so a single roof face can never disappear.
    const slab = Math.min(ext[0], ext[1], ext[2]) > Math.max(1e-6, Math.max(...ext) * 1e-4);
    info.closed = slab && bboxVol > EPS && Math.abs(info.volume) > bboxVol * 0.2;
    info.flip = info.volume < 0;
    info.verts = null; // free
  }

  const opaque = [];
  const transparent = [];
  let faceCount = 0;
  for (let r = 0; r < records.length; r++) {
    const rec = records[r];
    const info = infoBySolid.get(rec.solid);
    let n = rec.given || rec.geo;
    if (info.closed && info.flip) n = [-n[0], -n[1], -n[2]];
    const glass = rec.kind === 'glass' || isGlassMaterial(rec.material);
    const alpha = glass ? opts.glassAlpha : rec.kind === 'detail' ? opts.detailAlpha : 1;
    const noCull = glass || !info.closed || rec.kind === 'detail';
    const base = resolveColor(rec.material, palette, colorCache, opts.materials);
    const color = shadeFace(base, n, rec.pts, context);
    const backColor = [
      color[0] * 0.60 + base[0] * 0.12,
      color[1] * 0.60 + base[1] * 0.12,
      color[2] * 0.60 + base[2] * 0.12,
    ];
    const specular = glass ? 0.5 : /metal|gold|iron/i.test(String(rec.material)) ? 0.28 : 0.05;
    faceCount++;
    const fid = faceCount;
    const twoSidedLight = glass || (rec.given && dot3(rec.given, rec.geo) < 0) || !info.closed;
    for (let i = 1; i + 1 < rec.pts.length; i++) {
      const tri = {
        a: rec.pts[0],
        b: rec.pts[i],
        c: rec.pts[i + 1],
        // face centroid: identical for every triangle of a face, so flat shading and the
        // specular highlight never show a seam along the quad diagonal
        cx: rec.centroid[0],
        cy: rec.centroid[1],
        cz: rec.centroid[2],
        n,
        r: color[0],
        g: color[1],
        bl: color[2],
        br: backColor[0],
        bg: backColor[1],
        bb: backColor[2],
        alpha,
        spec: specular,
        fid,
        noCull,
        twoSidedLight,
        // Polygon offset: detail/glass decorations frequently sit exactly coplanar with the
        // wall they decorate (same depth, different material). Nudge them towards the camera
        // so they win the depth tie instead of z-fighting into stripes.
        bias:
          (rec.kind === 'detail' ? opts.detailBias : glass ? opts.glassBias : 0) +
          Math.min(rec.order, 2000) * opts.coplanarBias,
      };
      if (alpha >= 0.999) opaque.push(tri);
      else transparent.push(tri);
    }
  }

  const shadow = bounds && opts.shadows ? buildShadowGrid(opaque, bounds, opts) : null;
  return { opaque, transparent, bounds, shadow, faceCount };
}

/* ------------------------------------------------------------------------------------ */
/* soft shadow: project all opaque faces onto the ground along the sun                   */
/* ------------------------------------------------------------------------------------ */

function buildShadowGrid(tris, bounds, opts) {
  const sun = opts.sunDir;
  if (!(sun[1] > 1e-3)) return null;
  const groundY = bounds.groundY;
  const height = Math.max(0.001, bounds.max[1] - groundY);
  const offX = (-sun[0] / sun[1]) * height;
  const offZ = (-sun[2] / sun[1]) * height;
  const minX = bounds.min[0] + Math.min(0, offX) - 2.5;
  const maxX = bounds.max[0] + Math.max(0, offX) + 2.5;
  const minZ = bounds.min[2] + Math.min(0, offZ) - 2.5;
  const maxZ = bounds.max[2] + Math.max(0, offZ) + 2.5;
  const w = Math.max(1, maxX - minX);
  const d = Math.max(1, maxZ - minZ);
  const res = clamp(Math.round(opts.shadowRes) || 160, 32, 512);
  const cell = Math.max(w, d) / res;
  const gw = Math.max(2, Math.ceil(w / cell) + 1);
  const gh = Math.max(2, Math.ceil(d / cell) + 1);
  const grid = new Uint8Array(gw * gh);
  const invSunY = 1 / sun[1];
  for (let t = 0; t < tris.length; t++) {
    const tri = tris[t];
    const t0 = (tri.a[1] - groundY) * invSunY;
    const t1 = (tri.b[1] - groundY) * invSunY;
    const t2 = (tri.c[1] - groundY) * invSunY;
    fillTri2D(
      grid, gw, gh,
      (tri.a[0] - sun[0] * t0 - minX) / cell, (tri.a[2] - sun[2] * t0 - minZ) / cell,
      (tri.b[0] - sun[0] * t1 - minX) / cell, (tri.b[2] - sun[2] * t1 - minZ) / cell,
      (tri.c[0] - sun[0] * t2 - minX) / cell, (tri.c[2] - sun[2] * t2 - minZ) / cell,
    );
  }
  boxBlur(grid, gw, gh, 2);
  boxBlur(grid, gw, gh, 1);
  return { grid, gw, gh, minX, minZ, cell };
}

/** Fills a 2D triangle (grid coordinates, y = z axis) with 255s. */
function fillTri2D(buf, W, H, x0, y0, x1, y1, x2, y2) {
  let area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
  if (!(Math.abs(area) > 1e-6)) return;
  if (area < 0) {
    [x1, x2] = [x2, x1];
    [y1, y2] = [y2, y1];
    area = -area;
  }
  const minX = Math.max(0, Math.floor(Math.min(x0, x1, x2)));
  const maxX = Math.min(W - 1, Math.ceil(Math.max(x0, x1, x2)));
  const minY = Math.max(0, Math.floor(Math.min(y0, y1, y2)));
  const maxY = Math.min(H - 1, Math.ceil(Math.max(y0, y1, y2)));
  if (minX > maxX || minY > maxY) return;
  const e0dx = -(y2 - y1);
  const e1dx = -(y0 - y2);
  const e2dx = -(y1 - y0);
  for (let y = minY; y <= maxY; y++) {
    const py = y + 0.5;
    let w0 = (x2 - x1) * (py - y1) - (y2 - y1) * (minX + 0.5 - x1);
    let w1 = (x0 - x2) * (py - y2) - (y0 - y2) * (minX + 0.5 - x2);
    let w2 = (x1 - x0) * (py - y0) - (y1 - y0) * (minX + 0.5 - x0);
    let idx = y * W + minX;
    for (let x = minX; x <= maxX; x++, idx++, w0 += e0dx, w1 += e1dx, w2 += e2dx) {
      if (w0 >= 0 && w1 >= 0 && w2 >= 0) buf[idx] = 255;
    }
  }
}

/** Separable box blur, run twice for a cheap gaussian-ish falloff. */
function boxBlur(buf, W, H, radius) {
  if (radius < 1) return;
  const tmp = new Uint8Array(W * H);
  const span = 2 * radius + 1;
  for (let y = 0; y < H; y++) {
    const row = y * W;
    let sum = 0;
    for (let k = -radius; k <= radius; k++) sum += buf[row + clamp(k, 0, W - 1)];
    for (let x = 0; x < W; x++) {
      tmp[row + x] = sum / span;
      sum += buf[row + clamp(x + radius + 1, 0, W - 1)] - buf[row + clamp(x - radius, 0, W - 1)];
    }
  }
  for (let x = 0; x < W; x++) {
    let sum = 0;
    for (let k = -radius; k <= radius; k++) sum += tmp[clamp(k, 0, H - 1) * W + x];
    for (let y = 0; y < H; y++) {
      buf[y * W + x] = sum / span;
      sum += tmp[clamp(y + radius + 1, 0, H - 1) * W + x] - tmp[clamp(y - radius, 0, H - 1) * W + x];
    }
  }
}

/** Bilinear shadow density (0 = lit, 1 = fully shadowed). */
function sampleShadow(sg, x, z) {
  const fx = (x - sg.minX) / sg.cell - 0.5;
  const fz = (z - sg.minZ) / sg.cell - 0.5;
  if (fx <= 0 || fz <= 0 || fx >= sg.gw - 1 || fz >= sg.gh - 1) return 0;
  const ix = fx | 0;
  const iz = fz | 0;
  const tx = fx - ix;
  const tz = fz - iz;
  const i = iz * sg.gw + ix;
  const a = sg.grid[i] * (1 - tx) + sg.grid[i + 1] * tx;
  const b = sg.grid[i + sg.gw] * (1 - tx) + sg.grid[i + sg.gw + 1] * tx;
  return (a * (1 - tz) + b * tz) / 255;
}

/* ------------------------------------------------------------------------------------ */
/* camera                                                                               */
/* ------------------------------------------------------------------------------------ */

/** Resolves a view name or descriptor into a full camera definition. */
function resolveView(view) {
  if (typeof view === 'string') {
    const key = view.trim().toLowerCase();
    const def = VIEW_ANGLES[key];
    if (!def) throw new Error(`Unknown view '${view}'. Known views: ${Object.keys(VIEW_ANGLES).join(', ')}`);
    return { name: key, azimuth: def.azimuth, elevation: def.elevation, distance: null, fov: null, target: null };
  }
  if (view && typeof view === 'object') {
    const azimuth = Number.isFinite(view.azimuth) ? view.azimuth : 45;
    const elevation = Number.isFinite(view.elevation) ? view.elevation : 22;
    return {
      name: view.name || `az${Math.round(azimuth)}el${Math.round(elevation)}`,
      azimuth,
      elevation,
      distance: Number.isFinite(view.distance) ? view.distance : null,
      fov: Number.isFinite(view.fov) ? view.fov : null,
      target: Array.isArray(view.target) && view.target.length === 3 ? view.target : null,
    };
  }
  throw new Error(`Invalid view: ${String(view)}`);
}

const EMPTY_BOUNDS = {
  min: [-1, -1, -1],
  max: [1, 1, 1],
  size: [2, 2, 2],
  center: [0, 0, 0],
  radius: 1.7,
  groundY: -1.02,
  aoHeight: 1.2,
  horizontal: 2,
};

/**
 * Builds the orbit camera for one view.  The distance is fitted to the bounding box: every
 * corner must satisfy |u| <= (d + w) tanH and |v| <= (d + w) tanV, which gives the minimal
 * camera distance directly instead of guessing from the bounding sphere.
 */
function makeCamera(bounds, view, W, H, opts) {
  const def = resolveView(view);
  const fovDeg = def.fov ?? opts.fov;
  const fov = clamp(fovDeg, 8, 120) * DEG;
  const aspect = W / H;
  const tanV = Math.tan(fov / 2);
  const tanH = tanV * aspect;
  const center = def.target || bounds.center;
  const az = def.azimuth * DEG;
  const el = clamp(def.elevation, -89.9, 90) * DEG;
  const dir = [Math.sin(az) * Math.cos(el), Math.sin(el), Math.cos(az) * Math.cos(el)];
  const upWorld = Math.abs(dir[1]) > 0.9995 ? [0, 0, -1] : [0, 1, 0];
  const forward = [-dir[0], -dir[1], -dir[2]];
  const right = normalize3(cross3(forward, upWorld));
  const up = cross3(right, forward);

  let dist = 0;
  for (let i = 0; i < 8; i++) {
    const p = [
      i & 1 ? bounds.max[0] : bounds.min[0],
      i & 2 ? bounds.max[1] : bounds.min[1],
      i & 4 ? bounds.max[2] : bounds.min[2],
    ];
    const q = [p[0] - center[0], p[1] - center[1], p[2] - center[2]];
    const u = dot3(q, right);
    const v = dot3(q, up);
    const w = dot3(q, forward);
    dist = Math.max(dist, Math.abs(u) / tanH - w, Math.abs(v) / tanV - w);
  }
  if (def.distance) dist = def.distance;
  dist = Math.max(dist * (def.distance ? 1 : opts.margin), bounds.radius * 0.4, 0.5);
  const eye = [center[0] + dir[0] * dist, center[1] + dir[1] * dist, center[2] + dir[2] * dist];
  return {
    name: def.name,
    position: eye,
    target: [center[0], center[1], center[2]],
    fov: fovDeg,
    up,
    right,
    forward,
    azimuth: def.azimuth,
    elevation: def.elevation,
    distance: dist,
    tanH,
    tanV,
    near: Math.max(0.05, dist * 0.01),
    width: W,
    height: H,
  };
}

function publicCamera(cam) {
  return {
    position: cam.position,
    target: cam.target,
    fov: cam.fov,
    up: cam.up,
    near: cam.near,
    azimuth: cam.azimuth,
    elevation: cam.elevation,
    distance: cam.distance,
  };
}

/* ------------------------------------------------------------------------------------ */
/* rasterization                                                                        */
/* ------------------------------------------------------------------------------------ */

/** Renders one prepared view into an RGB buffer plus depth/id buffers. */
function renderToBuffer(prepared, cam, W, H, opts) {
  const nPixels = W * H;
  const color = new Uint8Array(nPixels * 3);
  const zbuf = new Float32Array(nPixels); // inverse view depth; 0 = untouched
  const fid = new Int32Array(nPixels); // face id, 0 = sky/ground
  drawSky(color, W, H, opts, prepared.bounds ? 'gradient' : 'flat');
  if (prepared.bounds) {
    drawGround(color, zbuf, W, H, cam, prepared, opts);
    drawMesh(prepared.opaque, cam, W, H, color, zbuf, fid, false, opts);
    drawMesh(prepared.transparent, cam, W, H, color, zbuf, fid, true, opts);
    if (opts.outline) drawOutline(color, zbuf, fid, W, H, opts);
  }
  return { color, zbuf, fid };
}

/**
 * Background fill.  With geometry it is a soft vertical sky gradient; for an empty scene a
 * flat fill of the requested background colour is returned (a "blank sheet", not a gradient).
 */
function drawSky(color, W, H, opts, mode = 'gradient') {
  const top = mode === 'flat' ? opts.flatColor : opts.skyTop;
  const bottom = mode === 'flat' ? opts.flatColor : opts.skyBottom;
  const stride = W * 3;
  const denom = Math.max(1, H - 1);
  for (let y = 0; y < H; y++) {
    const t = y / denom;
    const s = t * t * (3 - 2 * t);
    const r = top[0] + (bottom[0] - top[0]) * s;
    const g = top[1] + (bottom[1] - top[1]) * s;
    const b = top[2] + (bottom[2] - top[2]) * s;
    const row = y * stride;
    for (let x = 0; x < W; x++) {
      const o = row + x * 3;
      color[o] = r;
      color[o + 1] = g;
      color[o + 2] = b;
    }
  }
}

/**
 * Ray-casts a ground plane at `bounds.groundY` for every pixel below the horizon.  Depth is
 * stored as inverse view depth (scaled slightly back so mesh faces win ties), so the mesh pass
 * z-tests against the ground.  The ground fades into the background with distance and receives
 * the projected soft shadow.
 */
function drawGround(color, zbuf, W, H, cam, prepared, opts) {
  const b = prepared.bounds;
  const groundY = b.groundY;
  const eye = cam.position;
  const fwd = cam.forward;
  const right = cam.right;
  const up = cam.up;
  const halfW = W / 2;
  const halfH = H / 2;
  const sxStep = cam.tanH / halfW;
  const syStep = cam.tanV / halfH;
  const cx = b.center[0];
  const cz = b.center[2];
  const R = Math.max(3, b.horizontal * 0.85);
  const fogNear = R * 1.5;
  const fogFar = R * 4.2 + 14;
  const fog = opts.fogColor;
  const near = opts.groundColor;
  const far = opts.groundFar;
  const shadow = prepared.shadow;
  const shadowStrength = opts.shadowStrength;
  const colR = new Float32Array(W);
  const colG = new Float32Array(W);
  const colB = new Float32Array(W);
  const colShade = new Float32Array(W);

  for (let y = 0; y < H; y++) {
    const vStep = (halfH - (y + 0.5)) * syStep;
    const dy = fwd[1] + up[1] * vStep;
    if (dy > -1e-4) continue; // this row looks at the sky
    const t = (groundY - eye[1]) / dy;
    if (!(t > 0) || t > 1e7) continue;
    const rowBaseX = eye[0] + (fwd[0] + up[0] * vStep + right[0] * (0.5 - halfW) * sxStep) * t;
    const rowBaseZ = eye[2] + (fwd[2] + up[2] * vStep + right[2] * (0.5 - halfW) * sxStep) * t;
    const stepX = right[0] * sxStep * t;
    const stepZ = right[2] * sxStep * t;

    // first fill the per-row colour / shadow arrays, then write pixels
    for (let x = 0; x < W; x++) {
      const px = rowBaseX + stepX * x;
      const pz = rowBaseZ + stepZ * x;
      const ddx = px - cx;
      const ddz = pz - cz;
      const dist = Math.sqrt(ddx * ddx + ddz * ddz);
      let shade = 1;
      if (shadow) {
        const density = sampleShadow(shadow, px, pz);
        if (density > 0) shade -= shadowStrength * density;
      }
      const radial = clamp(dist / (R * 3.2), 0, 1);
      // fade to the background completely before the ground ray reaches the horizon,
      // otherwise a faint band marks where the ground plane stops
      const f = smoothstep(fogNear, fogFar, dist);
      colR[x] = (near[0] + (far[0] - near[0]) * radial) * (1 - f) + fog[0] * f;
      colG[x] = (near[1] + (far[1] - near[1]) * radial) * (1 - f) + fog[1] * f;
      colB[x] = (near[2] + (far[2] - near[2]) * radial) * (1 - f) + fog[2] * f;
      colShade[x] = shade;
    }
    const invT = 1 / t;
    const row = y * W;
    for (let x = 0; x < W; x++) {
      const idx = row + x;
      const s = colShade[x];
      color[idx * 3] = colR[x] * s;
      color[idx * 3 + 1] = colG[x] * s;
      color[idx * 3 + 2] = colB[x] * s;
      zbuf[idx] = invT * 0.985;
    }
  }
}

/**
 * Transforms one world point into camera space, writing into `out` as [right, up, forward]
 * to avoid allocating a fresh array per vertex (this is the hottest loop in the renderer).
 */
function toView(p, cam, out) {
  const dx = p[0] - cam.position[0];
  const dy = p[1] - cam.position[1];
  const dz = p[2] - cam.position[2];
  const R = cam.right;
  const U = cam.up;
  const F = cam.forward;
  out[0] = dx * R[0] + dy * R[1] + dz * R[2];
  out[1] = dx * U[0] + dy * U[1] + dz * U[2];
  out[2] = dx * F[0] + dy * F[1] + dz * F[2];
  return out;
}

/** Blinn-Phong specular term (0..1) for a normal, a unit view vector and the sun. */
function specular(n, vx, vy, vz, sun, power) {
  let hx = sun[0] + vx;
  let hy = sun[1] + vy;
  let hz = sun[2] + vz;
  const len = Math.hypot(hx, hy, hz);
  if (!(len > EPS)) return 0;
  hx /= len;
  hy /= len;
  hz /= len;
  const d = n[0] * hx + n[1] * hy + n[2] * hz;
  return d > 0 ? Math.pow(d, power) : 0;
}

/** Sutherland-Hodgman clip of a convex polygon against the near plane z = near. */
function clipNear(poly, near) {
  const out = [];
  for (let i = 0; i < poly.length; i++) {
    const cur = poly[i];
    const prev = poly[(i + poly.length - 1) % poly.length];
    const curIn = cur[2] >= near;
    const prevIn = prev[2] >= near;
    if (curIn !== prevIn) {
      const t = (near - prev[2]) / (cur[2] - prev[2]);
      out.push([prev[0] + (cur[0] - prev[0]) * t, prev[1] + (cur[1] - prev[1]) * t, near]);
    }
    if (curIn) out.push(cur);
  }
  return out;
}

function project(v, cam, fH, fV, W, H, out = [0, 0, 0]) {
  const invZ = 1 / v[2];
  out[0] = W / 2 + v[0] * invZ * fH;
  out[1] = H / 2 - v[1] * invZ * fV;
  out[2] = invZ;
  return out;
}

/**
 * Rasterizes mesh triangles.  The opaque pass writes depth; the transparent pass blends
 * back-to-front without writing depth.
 */
function drawMesh(list, cam, W, H, color, zbuf, fid, transparent, opts) {
  if (!list.length) return;
  let tris = list;
  if (transparent) {
    const dir = cam.forward;
    const eye = cam.position;
    tris = tris
      .map((tri) => ({
        tri,
        d: (tri.cx - eye[0]) * dir[0] + (tri.cy - eye[1]) * dir[1] + (tri.cz - eye[2]) * dir[2],
      }))
      .sort((p, q) => q.d - p.d)
      .map((p) => p.tri);
  }
  const eye = cam.position;
  const fH = W / 2 / cam.tanH;
  const fV = H / 2 / cam.tanV;
  const near = cam.near;
  const cull = !transparent;
  const va = [0, 0, 0];
  const vb = [0, 0, 0];
  const vc = [0, 0, 0];

  for (let i = 0; i < tris.length; i++) {
    const tri = tris[i];
    if (cull && !tri.noCull) {
      const vx = eye[0] - tri.a[0];
      const vy = eye[1] - tri.a[1];
      const vz = eye[2] - tri.a[2];
      if (tri.n[0] * vx + tri.n[1] * vy + tri.n[2] * vz <= 0) continue; // back face
    }
    const a = toView(tri.a, cam, va);
    const b = toView(tri.b, cam, vb);
    const c = toView(tri.c, cam, vc);
    let poly;
    if (a[2] >= near && b[2] >= near && c[2] >= near) {
      poly = [a, b, c];
    } else if (a[2] < near && b[2] < near && c[2] < near) {
      continue;
    } else {
      poly = clipNear([a, b, c], near);
      if (poly.length < 3) continue;
    }

    let r = tri.r;
    let g = tri.g;
    let bl = tri.bl;
    const vx = eye[0] - tri.cx;
    const vy = eye[1] - tri.cy;
    const vz = eye[2] - tri.cz;
    if (transparent) {
      if (tri.n[0] * vx + tri.n[1] * vy + tri.n[2] * vz < 0) {
        r = tri.br;
        g = tri.bg;
        bl = tri.bb;
      }
      if (tri.spec > 0) {
        const len = Math.hypot(vx, vy, vz) || 1;
        const s = specular(tri.n, vx / len, vy / len, vz / len, opts.sunDir, 24) * tri.spec * 255;
        if (s > 0.5) {
          r = clamp255(r + s);
          g = clamp255(g + s);
          bl = clamp255(bl + s);
        }
      }
    } else if (tri.noCull && tri.n[0] * vx + tri.n[1] * vy + tri.n[2] * vz < 0) {
      // Open surface (a plane, a decal, an unclosed detail cluster) seen from behind: shade it
      // with the ambient-dominant back colour instead of leaving the front-side lighting.
      r = tri.br;
      g = tri.bg;
      bl = tri.bb;
    } else if (tri.spec > 0.05 && tri.twoSidedLight) {
      const len = Math.hypot(vx, vy, vz) || 1;
      const s = specular(tri.n, vx / len, vy / len, vz / len, opts.sunDir, 32) * tri.spec * 255;
      if (s > 0.5) {
        r = clamp255(r + s);
        g = clamp255(g + s);
        bl = clamp255(bl + s);
      }
    }

    const p0 = project(poly[0], cam, fH, fV, W, H);
    for (let k = 1; k + 1 < poly.length; k++) {
      const p1 = project(poly[k], cam, fH, fV, W, H);
      const p2 = project(poly[k + 1], cam, fH, fV, W, H);
      rasterTri(p0, p1, p2, r, g, bl, tri.alpha, tri.fid, transparent, W, H, color, zbuf, fid, tri.bias);
    }
  }
}

/**
 * Screen-space triangle fill with depth test (and alpha blending in the transparent pass).
 * Barycentrics are computed from edge functions; inverse view depth is interpolated linearly,
 * which is exactly perspective-correct.
 */
function rasterTri(p0, p1, p2, r, g, b, alpha, fidVal, blend, W, H, color, zbuf, fid, bias = 0) {
  let x0 = p0[0];
  let y0 = p0[1];
  const i0 = p0[2];
  let x1 = p1[0];
  let y1 = p1[1];
  let i1 = p1[2];
  let x2 = p2[0];
  let y2 = p2[1];
  let i2 = p2[2];
  let area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
  if (!(Math.abs(area) > 1e-9)) return;
  if (area < 0) {
    // keep the winding positive so every edge function is >= 0 inside
    [x1, x2] = [x2, x1];
    [y1, y2] = [y2, y1];
    [i1, i2] = [i2, i1];
    area = -area;
  }
  const minX = Math.max(0, Math.floor(Math.min(x0, x1, x2)));
  const maxX = Math.min(W - 1, Math.ceil(Math.max(x0, x1, x2)));
  const minY = Math.max(0, Math.floor(Math.min(y0, y1, y2)));
  const maxY = Math.min(H - 1, Math.ceil(Math.max(y0, y1, y2)));
  if (minX > maxX || minY > maxY) return;
  const invArea = 1 / area;
  const e0dx = -(y2 - y1);
  const e1dx = -(y0 - y2);
  const e2dx = -(y1 - y0);
  // Edge w0 is opposite vertex 0 (it is zero on the edge v1->v2), so the barycentric weights
  // are w0 -> v0, w1 -> v1, w2 -> v2.
  const iz0 = i0 * invArea;
  const iz1 = i1 * invArea;
  const iz2 = i2 * invArea;
  for (let y = minY; y <= maxY; y++) {
    const py = y + 0.5;
    let w0 = (x2 - x1) * (py - y1) - (y2 - y1) * (minX + 0.5 - x1);
    let w1 = (x0 - x2) * (py - y2) - (y0 - y2) * (minX + 0.5 - x2);
    let w2 = (x1 - x0) * (py - y0) - (y1 - y0) * (minX + 0.5 - x0);
    let idx = y * W + minX;
    for (let x = minX; x <= maxX; x++, idx++, w0 += e0dx, w1 += e1dx, w2 += e2dx) {
      if (w0 < 0 || w1 < 0 || w2 < 0) continue;
      let iz = w0 * iz0 + w1 * iz1 + w2 * iz2;
      if (bias > 0) iz *= 1 + bias;
      if (blend) {
        // Blend over the background as well: only skip when an opaque surface is in front of
        // this translucent face (zbuf === 0 means "nothing but sky / ground-to-be").
        const existing = zbuf[idx];
        if (existing > 0 && iz < existing) continue;
        const o = idx * 3;
        const inv = 1 - alpha;
        color[o] = color[o] * inv + r * alpha;
        color[o + 1] = color[o + 1] * inv + g * alpha;
        color[o + 2] = color[o + 2] * inv + b * alpha;
      } else if (iz > zbuf[idx]) {
        zbuf[idx] = iz;
        const o = idx * 3;
        color[o] = r;
        color[o + 1] = g;
        color[o + 2] = b;
        fid[idx] = fidVal;
      }
    }
  }
}

/**
 * Depth-discontinuity outline.  Only model pixels are considered, so the ground horizon is
 * never outlined, while silhouettes against the sky and against the ground are.
 */
function drawOutline(color, zbuf, fid, W, H, opts) {
  const strength = opts.outlineStrength;
  if (strength <= 0) return;
  const edge = new Uint8Array(W * H);
  for (let y = 1; y < H - 1; y++) {
    for (let x = 1; x < W - 1; x++) {
      const idx = y * W + x;
      if (fid[idx] === 0) continue;
      const z = zbuf[idx];
      const zl = zbuf[idx - 1];
      const zu = zbuf[idx - W];
      const zr = zbuf[idx + 1];
      const zd = zbuf[idx + W];
      let m;
      if (zl === 0 || zu === 0 || zr === 0 || zd === 0) m = 1;
      else {
        m = Math.max(
          Math.abs(z - zl) / Math.max(z, zl),
          Math.abs(z - zr) / Math.max(z, zr),
          Math.abs(z - zu) / Math.max(z, zu),
          Math.abs(z - zd) / Math.max(z, zd),
        );
      }
      const f = smoothstep(0.035, 0.16, m);
      if (f > 0) edge[idx] = (f * strength * 255) | 0;
    }
  }
  for (let i = 0; i < edge.length; i++) {
    const e = edge[i];
    if (!e) continue;
    const k = (255 - e) / 255;
    color[i * 3] *= k;
    color[i * 3 + 1] *= k;
    color[i * 3 + 2] *= k;
  }
}

/**
 * Box-downsamples the supersampled buffer and applies a mild vignette.  The vignette is
 * skipped for an empty scene so a "no geometry" frame is exactly the requested background.
 */
function resolveBuffer(buffer, W, H, outW, outH, opts, applyVignette = true) {
  const src = buffer.color;
  const out = new Uint8Array(outW * outH * 3);
  const ss = W / outW;
  if (ss === 1) {
    out.set(src.subarray(0, W * H * 3));
  } else {
    const inv = 1 / (ss * ss);
    for (let y = 0; y < outH; y++) {
      for (let x = 0; x < outW; x++) {
        let r = 0;
        let g = 0;
        let b = 0;
        for (let sy = 0; sy < ss; sy++) {
          const row = (y * ss + sy) * W;
          for (let sx = 0; sx < ss; sx++) {
            const o = (row + x * ss + sx) * 3;
            r += src[o];
            g += src[o + 1];
            b += src[o + 2];
          }
        }
        const o = (y * outW + x) * 3;
        out[o] = r * inv;
        out[o + 1] = g * inv;
        out[o + 2] = b * inv;
      }
    }
  }
  const vig = applyVignette ? opts.vignette : 0;
  if (vig > 0) {
    const cx = outW / 2;
    const cy = outH / 2;
    const maxR2 = cx * cx + cy * cy || 1;
    for (let y = 0; y < outH; y++) {
      const dy = y + 0.5 - cy;
      for (let x = 0; x < outW; x++) {
        const dx = x + 0.5 - cx;
        const f = 1 - vig * ((dx * dx + dy * dy) / maxR2);
        const o = (y * outW + x) * 3;
        out[o] *= f;
        out[o + 1] *= f;
        out[o + 2] *= f;
      }
    }
  }
  return out;
}

/* ------------------------------------------------------------------------------------ */
/* option handling and public API                                                        */
/* ------------------------------------------------------------------------------------ */

/**
 * Normalizes user options into the internal shape used across the renderer.  Draft mode halves
 * the output resolution and disables shadows and supersampling.
 */
function normalizeOptions(options = {}) {
  const src = options || {};
  const draft = !!src.draft;
  const fullW = clamp(Math.round(src.width ?? RENDER_DEFAULTS.width) || 800, 1, 4096);
  const fullH = clamp(Math.round(src.height ?? RENDER_DEFAULTS.height) || 600, 1, 4096);
  const width = draft ? Math.max(1, Math.round(fullW / 2)) : fullW;
  const height = draft ? Math.max(1, Math.round(fullH / 2)) : fullH;
  const ss = clamp(Math.round(src.supersample ?? (draft ? 1 : 2)), 1, 4);
  const background = typeof src.background === 'string' ? src.background : RENDER_DEFAULTS.background;
  const bg = hexToRgb(background, [238, 243, 248]);
  const shadows = src.shadows === undefined || src.shadows === null ? !draft : !!src.shadows;
  const sunDir = Array.isArray(src.sun) && src.sun.length === 3 ? normalize3(src.sun) : SUN;
  const fillDir = Array.isArray(src.fill) && src.fill.length === 3 ? normalize3(src.fill) : FILL;
  return {
    width,
    height,
    draft,
    ss,
    background,
    flatColor: bg,
    skyTop: mixColor(bg, [255, 255, 255], 0.45),
    skyBottom: mixColor(bg, [255, 255, 255], 0.04),
    fogColor: bg,
    groundColor: src.ground ? hexToRgb(src.ground, [224, 230, 226]) : mixColor(bg, [212, 220, 214], 0.55),
    groundFar: mixColor(bg, [204, 212, 216], 0.45),
    fov: clamp(Number(src.fov) || RENDER_DEFAULTS.fov, 8, 120),
    margin: clamp(Number(src.margin) || RENDER_DEFAULTS.margin, 1, 3),
    supersample: ss,
    shadows,
    shadowRes: clamp(Math.round(src.shadowRes ?? RENDER_DEFAULTS.shadowRes), 32, 512),
    shadowStrength: clamp(Number(src.shadowStrength ?? 0.55), 0, 0.95),
    outline: src.outline === undefined ? RENDER_DEFAULTS.outline : !!src.outline,
    outlineStrength: clamp(Number(src.outlineStrength ?? RENDER_DEFAULTS.outlineStrength), 0, 1),
    vignette: clamp(Number(src.vignette ?? RENDER_DEFAULTS.vignette), 0, 0.5),
    pngLevel: clamp(Math.round(src.pngLevel ?? (draft ? 6 : RENDER_DEFAULTS.pngLevel)), 1, 9),
    glassAlpha: clamp(Number(src.glassAlpha ?? RENDER_DEFAULTS.glassAlpha), 0.02, 1),
    detailAlpha: clamp(Number(src.detailAlpha ?? RENDER_DEFAULTS.detailAlpha), 0.02, 1),
    glassBias: clamp(Number(src.glassBias ?? RENDER_DEFAULTS.glassBias), 0, 0.01),
    detailBias: clamp(Number(src.detailBias ?? RENDER_DEFAULTS.detailBias), 0, 0.01),
    coplanarBias: clamp(Number(src.coplanarBias ?? RENDER_DEFAULTS.coplanarBias), 0, 1e-4),
    sunDir,
    fillDir,
    materials: src.materials && typeof src.materials === 'object' ? src.materials : null,
  };
}

/** Renders one prepared view and returns PNG bytes plus the public camera. */
function renderViewToPng(prepared, view, opts) {
  const W = opts.width * opts.ss;
  const H = opts.height * opts.ss;
  const cam = makeCamera(prepared.bounds || EMPTY_BOUNDS, view, W, H, opts);
  const buffers = renderToBuffer(prepared, cam, W, H, opts);
  const rgb = resolveBuffer(buffers, W, H, opts.width, opts.height, opts, !!prepared.bounds);
  return {
    png: encodePng(opts.width, opts.height, rgb, { level: opts.pngLevel }),
    camera: publicCamera(cam),
  };
}

/**
 * Renders several named views of a scene.
 *
 * @param {{spec?: object, solids?: Array}} scene scene produced by `buildScene`; may be empty
 * @param {object} [options]
 * @param {number} [options.width=800] output width (draft mode halves it)
 * @param {number} [options.height=600] output height (draft mode halves it)
 * @param {Array<string|object>} [options.views=DEFAULT_VIEWS] view names or camera descriptors
 * @param {string} [options.background='#eef3f8'] background colour
 * @param {boolean} [options.draft=false] half resolution, no shadows, no supersampling
 * @param {number} [options.fov=42] vertical field of view in degrees
 * @param {number} [options.supersample] 1..4 (default 2, 1 in draft)
 * @param {boolean} [options.shadows] projected soft shadow (default true, off in draft)
 * @param {boolean} [options.outline=true] depth outline pass
 * @param {string} [options.ground] ground tint
 * @returns {Promise<{views: Array<{name: string, png: Buffer, camera: object}>, width: number, height: number, draft: boolean, ms: number}>}
 *   `width`/`height` are the actual output dimensions (halved in draft mode).
 */
export async function renderViews(scene, options = {}) {
  const started = Date.now();
  const opts = normalizeOptions(options);
  const prepared = prepareScene(scene, opts);
  const viewList = Array.isArray(options && options.views) && options.views.length
    ? options.views
    : DEFAULT_VIEWS;
  const views = [];
  for (const view of viewList) {
    const { png, camera } = renderViewToPng(prepared, view, opts);
    const name = typeof view === 'string' ? view : view && view.name ? view.name : String(view);
    views.push({ name, png, camera });
  }
  return { views, width: opts.width, height: opts.height, draft: opts.draft, ms: Date.now() - started };
}

/**
 * Renders a single view and resolves to the PNG bytes.
 *
 * @param {{spec?: object, solids?: Array}} scene
 * @param {string|object} view view name or `{azimuth, elevation, distance, fov, target, name}`
 * @param {object} [options] same options as `renderViews`
 * @returns {Promise<Buffer>}
 */
export async function renderPng(scene, view, options = {}) {
  const opts = normalizeOptions(options);
  const prepared = prepareScene(scene, opts);
  return renderViewToPng(prepared, view, opts).png;
}

/**
 * Computes the camera the renderer would use for a view, without rendering.  Useful for the
 * studio to align its interactive three.js camera with a rendered picture.
 *
 * @param {{spec?: object, solids?: Array}} scene
 * @param {string|object} view view name or descriptor
 * @param {object} [options] same options as `renderViews`
 * @returns {{position: number[], target: number[], fov: number, up: number[], near: number, azimuth: number, elevation: number, distance: number}}
 */
export function cameraForView(scene, view, options = {}) {
  const opts = normalizeOptions(options);
  const prepared = prepareScene(scene, opts);
  return publicCamera(makeCamera(prepared.bounds || EMPTY_BOUNDS, view, opts.width * opts.ss, opts.height * opts.ss, opts));
}
