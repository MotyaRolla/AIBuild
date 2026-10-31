/**
 * Voxel pixel renderer — the "pixels first" renderer.
 *
 * The scene is NOT rasterised as smooth triangles. The single source of truth is the voxel grid
 * (`voxelizeSpec` from geometry.mjs); every screen pixel shoots a ray into that grid (DDA voxel
 * traversal), so what the model sees is exactly what Minecraft will contain: one block = one
 * cube, every block face textured with a procedural 16x16 pixel texture, block edges visible.
 *
 * Features: per-pixel DDA, per-face pixel textures, neighbour-based ambient occlusion, sun shadow
 * rays, glass transparency, block outlines, deterministic (no Math.random unless seeded).
 *
 * Coordinates: +x east, +y up, +z south (same as the spec). Cell (x,y,z) covers
 * [x,x+1] x [y,y+1] x [z,z+1].
 */
import { deflateSync } from 'node:zlib';
import { PNG_SIGNATURE, crc32, pngChunk } from './png.mjs';
import { parseHex, DEFAULT_BLOCKS } from './materials.mjs';

const TEX = 16; // texture resolution per block face, like Minecraft

/** Small deterministic hash -> [0,1). */
function hash3(x, y, z, salt = 0) {
  let h = (x * 374761393 + y * 668265263 + z * 2147483647 + salt * 1442695040) | 0;
  h = (h ^ (h >>> 13)) * 1274126177;
  h = (h ^ (h >>> 16)) >>> 0;
  return h / 4294967296;
}

/** value noise for texture grain */
function noise2(x, y, seed) {
  const xi = Math.floor(x), yi = Math.floor(y);
  const xf = x - xi, yf = y - yi;
  const s = (a, b) => hash3(a, b, seed, 7);
  const u = xf * xf * (3 - 2 * xf);
  const v = yf * yf * (3 - 2 * yf);
  const a = s(xi, yi) * (1 - u) + s(xi + 1, yi) * u;
  const b = s(xi, yi + 1) * (1 - u) + s(xi + 1, yi + 1) * u;
  return a * (1 - v) + b * v;
}

/**
 * Procedural 16x16 texture for a material, cached.
 * The palette colour is the base; the pattern depends on the material family so that stone,
 * planks, roof tiles, glass and leaves look different at a glance.
 * @param {string} material palette key
 * @param {string} hex base colour
 * @param {number} seed
 * @returns {Uint8Array} TEX*TEX*3 bytes
 */
function makeTexture(material, hex, seed) {
  const base = parseHex(hex) || [160, 160, 160];
  const out = new Uint8Array(TEX * TEX * 3);
  const kind = classify(material);
  for (let ty = 0; ty < TEX; ty++) {
    for (let tx = 0; tx < TEX; tx++) {
      let c = base.slice();
      const n = noise2(tx / 3.2, ty / 3.2, seed);
      const grain = (n - 0.5) * 22;
      switch (kind) {
        case 'stone': {
          const blotch = (noise2(tx / 1.7, ty / 1.7, seed + 11) - 0.5) * 26;
          c = c.map((v) => v + grain + blotch);
          if (((tx * 7 + ty * 13 + seed) % 23) === 0) c = c.map((v) => v - 26);
          break;
        }
        case 'planks': {
          const plankRow = Math.floor(ty / 4);
          const seam = ty % 4 === 0 ? -30 : 0;
          const along = ((tx + plankRow * 5 + seed) % 16 === 0) ? -24 : 0;
          c = c.map((v) => v + seam + along + grain * 0.8);
          break;
        }
        case 'roof': {
          // overlapping tiles: a dark row every 4 px, staggered vertical joints
          const row = Math.floor(ty / 4);
          const ty2 = ty % 4;
          const off = (row % 2) * 4;
          const joint = ((tx + off) % 8 === 0) ? -28 : 0;
          const shade = ty2 === 3 ? -34 : ty2 === 0 ? 12 : 0;
          c = c.map((v) => v + joint + shade + grain * 0.7);
          break;
        }
        case 'brick': {
          const row = Math.floor(ty / 4);
          const off = (row % 2) * 4;
          const mortar = ty % 4 === 3 || (tx + off) % 8 === 7;
          c = mortar ? [196, 190, 180] : c.map((v) => v + grain);
          break;
        }
        case 'leaves': {
          const a = noise2(tx / 1.3, ty / 1.3, seed + 3);
          c = c.map((v, i) => v * (0.72 + a * 0.5) + (i === 1 ? 18 : 0));
          break;
        }
        case 'glass': {
          const edge = tx < 1 || ty < 1 || tx > TEX - 2 || ty > TEX - 2;
          c = edge ? [200, 214, 222] : [148, 186, 205];
          break;
        }
        case 'metal': {
          const streak = Math.abs(Math.sin((tx + ty * 0.5) * 0.7 + seed)) * 18;
          c = c.map((v) => v + streak - 6);
          break;
        }
        case 'plain':
        default: {
          c = c.map((v) => v + grain);
          break;
        }
      }
      const o = (ty * TEX + tx) * 3;
      out[o] = clamp255(c[0]);
      out[o + 1] = clamp255(c[1]);
      out[o + 2] = clamp255(c[2]);
    }
  }
  return out;
}

function classify(material) {
  const m = String(material || '');
  if (/glass|pane/.test(m)) return 'glass';
  if (/planks|wood|oak|spruce|birch|jungle|acacia/.test(m)) return 'planks';
  if (/roof|tile|shingle|slate/.test(m)) return 'roof';
  if (/brick/.test(m)) return 'brick';
  if (/leaf|leaves|moss|grass/.test(m)) return 'leaves';
  if (/metal|iron|gold|copper/.test(m)) return 'metal';
  if (/stone|cobble|deepslate|andesite|granite|tuff|rock/.test(m)) return 'stone';
  return 'plain';
}

const clamp255 = (v) => (v < 0 ? 0 : v > 255 ? 255 : v | 0);

/**
 * Flat voxel grid for fast DDA. Stores an index into `cells` (0 = empty).
 */
function buildGrid(voxels) {
  let minX = Infinity, minY = Infinity, minZ = Infinity;
  let maxX = -Infinity, maxY = -Infinity, maxZ = -Infinity;
  for (const k of voxels.keys()) {
    const [x, y, z] = k.split('|').map(Number);
    if (x < minX) minX = x;
    if (y < minY) minY = y;
    if (z < minZ) minZ = z;
    if (x > maxX) maxX = x;
    if (y > maxY) maxY = y;
    if (z > maxZ) maxZ = z;
  }
  if (!Number.isFinite(minX)) return null;
  const sx = maxX - minX + 1, sy = maxY - minY + 1, sz = maxZ - minZ + 1;
  const data = new Int32Array(sx * sy * sz); // 0 = empty, else index+1
  const cells = [];
  for (const [k, cell] of voxels) {
    const [x, y, z] = k.split('|').map(Number);
    const i = (x - minX) + sx * ((z - minZ) + sz * (y - minY));
    data[i] = cells.length + 1;
    cells.push({ x, y, z, cell });
  }
  return { minX, minY, minZ, sx, sy, sz, data, cells, maxY };
}

/** Linear index; returns -1 outside the grid. */
function gridAt(g, x, y, z) {
  const ix = x - g.minX, iy = y - g.minY, iz = z - g.minZ;
  if (ix < 0 || iy < 0 || iz < 0 || ix >= g.sx || iy >= g.sy || iz >= g.sz) return -1;
  return g.data[ix + g.sx * (iz + g.sz * iy)];
}

/**
 * Amanatides & Woo voxel traversal.
 * @returns {{hit:boolean, t:number, nx:number, ny:number, nz:number, index:number, u:number, v:number}}
 */
function trace(g, ox, oy, oz, dx, dy, dz, maxDist = 512) {
  // Fast path: advance the origin to the entry point of the grid (ray/AABB slab test).
  // Without this most pixels would walk hundreds of empty cells before reaching the model.
  const bx0 = g.minX, by0 = g.minY, bz0 = g.minZ;
  const bx1 = g.minX + g.sx, by1 = g.minY + g.sy, bz1 = g.minZ + g.sz;
  let tEnter = 0;
  let tExit = maxDist;
  const slab = (o, d, lo, hi) => {
    if (Math.abs(d) < 1e-9) return o >= lo && o <= hi ? [0, Infinity] : null;
    const t1 = (lo - o) / d;
    const t2 = (hi - o) / d;
    return t1 < t2 ? [t1, t2] : [t2, t1];
  };
  for (const [o, d, lo, hi] of [[ox, dx, bx0, bx1], [oy, dy, by0, by1], [oz, dz, bz0, bz1]]) {
    const r = slab(o, d, lo, hi);
    if (!r) return { hit: false };
    tEnter = Math.max(tEnter, r[0]);
    tExit = Math.min(tExit, r[1]);
  }
  if (tEnter > tExit || tExit < 0) return { hit: false };
  // Entering face normal: whichever slab produced the entry parameter (deterministic order).
  let entryNormal = [0, 0, 0];
  {
    let best = -Infinity;
    for (const [o, d, lo, hi, axis] of [[ox, dx, bx0, bx1, 0], [oy, dy, by0, by1, 1], [oz, dz, bz0, bz1, 2]]) {
      if (Math.abs(d) < 1e-9) continue;
      const t = Math.min((lo - o) / d, (hi - o) / d);
      if (t > best) {
        best = t;
        entryNormal = [0, 0, 0];
        entryNormal[axis] = d > 0 ? -1 : 1;
      }
    }
  }
  if (tEnter > 0) {
    ox += dx * (tEnter + 1e-6);
    oy += dy * (tEnter + 1e-6);
    oz += dz * (tEnter + 1e-6);
  }
  let x = Math.floor(ox), y = Math.floor(oy), z = Math.floor(oz);
  const stepX = dx > 0 ? 1 : -1, stepY = dy > 0 ? 1 : -1, stepZ = dz > 0 ? 1 : -1;
  const tDeltaX = dx === 0 ? Infinity : Math.abs(1 / dx);
  const tDeltaY = dy === 0 ? Infinity : Math.abs(1 / dy);
  const tDeltaZ = dz === 0 ? Infinity : Math.abs(1 / dz);
  const nextBoundary = (o, i, step) => (step > 0 ? i + 1 - o : o - i);
  let tMaxX = dx === 0 ? Infinity : nextBoundary(ox, x, stepX) * tDeltaX;
  let tMaxY = dy === 0 ? Infinity : nextBoundary(oy, y, stepY) * tDeltaY;
  let tMaxZ = dz === 0 ? Infinity : nextBoundary(oz, z, stepZ) * tDeltaZ;
  let nx = entryNormal[0], ny = entryNormal[1], nz = entryNormal[2];
  let t = tEnter;
  for (let guard = 0; guard < 4096; guard++) {
    const idx = gridAt(g, x, y, z);
    if (idx > 0) {
      // hit: compute the local u,v on the hit face
      const hx = ox + dx * t, hy = oy + dy * t, hz = oz + dz * t;
      let u, v;
      if (nx !== 0) {
        u = hz - Math.floor(hz);
        v = hy - Math.floor(hy);
      } else if (ny !== 0) {
        u = hx - Math.floor(hx);
        v = hz - Math.floor(hz);
      } else {
        u = hx - Math.floor(hx);
        v = hy - Math.floor(hy);
      }
      return { hit: true, t, nx, ny, nz, index: idx, u, v, px: hx, py: hy, pz: hz };
    }
    if (tMaxX < tMaxY && tMaxX < tMaxZ) {
      x += stepX; t = tMaxX; tMaxX += tDeltaX; nx = -stepX; ny = 0; nz = 0;
    } else if (tMaxY < tMaxZ) {
      y += stepY; t = tMaxY; tMaxY += tDeltaY; nx = 0; ny = -stepY; nz = 0;
    } else {
      z += stepZ; t = tMaxZ; tMaxZ += tDeltaZ; nx = 0; ny = 0; nz = -stepZ;
    }
    if (x < g.minX - 1 || y < g.minY - 1 || z < g.minZ - 1 ||
        x > g.minX + g.sx + 1 || y > g.minY + g.sy + 1 || z > g.minZ + g.sz + 1) break;
    if (t > maxDist) break;
  }
  return { hit: false };
}

/** Occlusion factor for a face vertex: how many of the three neighbouring cells are solid. */
function vertexAO(g, x, y, z, nx, ny, nz, du, dv) {
  // two tangent axes of the face
  let tx, ty, tz, ux, uy, uz;
  if (nx !== 0) { tx = 0; ty = 1; tz = 0; ux = 0; uy = 0; uz = 1; }
  else if (ny !== 0) { tx = 1; ty = 0; tz = 0; ux = 0; uy = 0; uz = 1; }
  else { tx = 1; ty = 0; tz = 0; ux = 0; uy = 1; uz = 0; }
  const s1 = gridAt(g, x + nx + tx * du, y + ny + ty * du, z + nz + tz * du) > 0 ? 1 : 0;
  const s2 = gridAt(g, x + nx + ux * dv, y + ny + uy * dv, z + nz + uz * dv) > 0 ? 1 : 0;
  const c = gridAt(g, x + nx + tx * du + ux * dv, y + ny + ty * du + uy * dv, z + nz + tz * du + uz * dv) > 0 ? 1 : 0;
  if (s1 && s2) return 0.55;
  return 1 - 0.18 * (s1 + s2 + c);
}

/**
 * Renders one view from the voxel grid.
 *
 * @param {{voxels: Map<string, object>, palette: Record<string,string>, palette2?: Record<string,string>}} input
 * @param {object} opts
 * @param {number} [opts.width]
 * @param {number} [opts.height]
 * @param {[number,number,number]} [opts.eye] explicit camera position
 * @param {[number,number,number]} [opts.target]
 * @param {number} [opts.azimuth] degrees, 0 = looking from south (+z), 90 = from east (+x)
 * @param {number} [opts.elevation] degrees above the horizon
 * @param {boolean} [opts.outlines=true]
 * @param {boolean} [opts.shadows=true]
 * @param {boolean} [opts.fog=true]
 * @param {string} [opts.background='#eef3f8']
 * @param {number} [opts.supersample=1]
 * @param {number} [opts.seed=1]
 * @returns {Buffer} PNG
 */
export function renderVoxelPng(input, opts = {}) {
  const W = Math.max(1, Math.round(opts.width || 640));
  const H = Math.max(1, Math.round(opts.height || 480));
  const ss = Math.max(1, Math.min(3, Math.round(opts.supersample || 1)));
  const RW = W * ss, RH = H * ss;
  const voxels = input.voxels;
  const palette = input.palette || {};
  const seed = opts.seed ?? 1;

  const g = buildGrid(voxels);
  const rgb = new Uint8Array(W * H * 3);
  const bg = parseHex(opts.background || '#eef3f8') || [238, 243, 248];
  for (let i = 0; i < W * H; i++) {
    rgb[i * 3] = bg[0];
    rgb[i * 3 + 1] = bg[1];
    rgb[i * 3 + 2] = bg[2];
  }
  if (!g) return encodeRgb(W, H, rgb);

  // ---------- camera ----------
  const cx = (g.minX + g.sx / 2);
  const cz = (g.minZ + g.sz / 2);
  const cy = (g.minY + g.sy * 0.5);
  const radius = Math.hypot(g.sx, g.sy, g.sz) * 0.5;
  const az = ((opts.azimuth ?? 45) * Math.PI) / 180;
  const el = ((opts.elevation ?? 28) * Math.PI) / 180;
  const dist = opts.eye ? 0 : radius * (2.6 / Math.tan((opts.fov ?? 45) * Math.PI / 360)) * 0.42 + radius;
  let eye, target;
  if (opts.eye) {
    eye = opts.eye;
    target = opts.target || [cx, cy, cz];
  } else {
    target = opts.target || [cx, cy * 0.92, cz];
    eye = [
      target[0] + Math.sin(az) * Math.cos(el) * dist,
      target[1] + Math.sin(el) * dist,
      target[2] + Math.cos(az) * Math.cos(el) * dist,
    ];
  }
  const fwd = norm(sub(target, eye));
  const right = norm(cross(fwd, [0, 1, 0]));
  const up = cross(right, fwd);
  const fov = ((opts.fov ?? 45) * Math.PI) / 180;
  const tanF = Math.tan(fov / 2);
  const aspect = RW / RH;

  // ---------- sun ----------
  const sun = norm(opts.sunDir || [0.45, 0.82, 0.35]);
  const texCache = new Map();
  const texFor = (material) => {
    let t = texCache.get(material);
    if (!t) {
      t = makeTexture(material, palette[material] || '#a0a0a0', (seed + hash3(material.length, material.charCodeAt(0) || 1, 3, 5) * 997) | 0);
      texCache.set(material, t);
    }
    return t;
  };

  const cols = new Float32Array(RW * RH * 3);
  const depthBuf = new Float32Array(RW * RH);
  const lit = new Uint8Array(RW * RH);

  // ---------- per-pixel raycast ----------
  for (let py = 0; py < RH; py++) {
    const sy = (1 - (2 * (py + 0.5)) / RH) * tanF;
    for (let px = 0; px < RW; px++) {
      const sx = ((2 * (px + 0.5)) / RW - 1) * tanF * aspect;
      const dir = norm([
        fwd[0] + right[0] * sx + up[0] * sy,
        fwd[1] + right[1] * sx + up[1] * sy,
        fwd[2] + right[2] * sx + up[2] * sy,
      ]);
      const hit = trace(g, eye[0], eye[1], eye[2], dir[0], dir[1], dir[2]);
      const o = (py * RW + px);
      if (!hit.hit) {
        // sky gradient
        const t = py / RH;
        cols[o * 3] = bg[0] * (0.92 + 0.08 * t);
        cols[o * 3 + 1] = bg[1] * (0.94 + 0.06 * t);
        cols[o * 3 + 2] = bg[2] * (0.96 + 0.04 * t);
        continue;
      }
      const cell = g.cells[hit.index - 1];
      const material = cell.cell.material;
      const tex = texFor(material);
      const tu = Math.min(TEX - 1, Math.max(0, Math.floor(hit.u * TEX)));
      const tv = Math.min(TEX - 1, Math.max(0, Math.floor((1 - hit.v) * TEX)));
      const ti = (tv * TEX + tu) * 3;
      let tr = tex[ti], tg = tex[ti + 1], tb = tex[ti + 2];

      // lighting: sun + sky + face orientation
      const ndl = Math.max(0, hit.nx * sun[0] + hit.ny * sun[1] + hit.nz * sun[2]);
      const faceTint = 0.72 + 0.16 * (hit.ny > 0 ? 1 : hit.ny < 0 ? -1 : 0);
      let shade = 0.42 + 0.68 * ndl;
      // ambient occlusion from the neighbour cells around the hit face
      const du = hit.u < 0.5 ? -1 : 1;
      const dv = hit.v < 0.5 ? -1 : 1;
      shade *= vertexAO(g, cell.x, cell.y, cell.z, hit.nx, hit.ny, hit.nz, du, dv);
      shade *= faceTint;
      // shadow ray
      if (opts.shadows !== false && ndl > 0) {
        const sx0 = hit.px + hit.nx * 1e-3, sy0 = hit.py + hit.ny * 1e-3, sz0 = hit.pz + hit.nz * 1e-3;
        const sh = trace(g, sx0, sy0, sz0, sun[0], sun[1], sun[2], 256);
        if (sh.hit) shade *= 0.55;
      }
      // distance fog
      if (opts.fog !== false) {
        const fog = Math.min(0.35, hit.t / (radius * 9));
        shade = shade * (1 - fog) + 1.02 * fog;
      }
      const isGlass = /glass|pane/.test(String(material));
      cols[o * 3] = tr * shade;
      cols[o * 3 + 1] = tg * shade;
      cols[o * 3 + 2] = tb * shade;
      lit[o] = 1;
      depthBuf[o] = hit.t;
      if (isGlass) {
        // transparent: mix what is behind along the same ray
        const behind = trace(g, hit.px + dir[0] * 1.01, hit.py + dir[1] * 1.01, hit.pz + dir[2] * 1.01, dir[0], dir[1], dir[2]);
        if (behind.hit) {
          const bcell = g.cells[behind.index - 1];
          const btex = texFor(bcell.cell.material);
          const btu = Math.min(TEX - 1, Math.max(0, Math.floor(behind.u * TEX)));
          const btv = Math.min(TEX - 1, Math.max(0, Math.floor((1 - behind.v) * TEX)));
          const bi = (btv * TEX + btu) * 3;
          const bndl = Math.max(0.35, behind.nx * sun[0] + behind.ny * sun[1] + behind.nz * sun[2]);
          cols[o * 3] = cols[o * 3] * 0.45 + btex[bi] * 0.55 * bndl;
          cols[o * 3 + 1] = cols[o * 3 + 1] * 0.45 + btex[bi + 1] * 0.55 * bndl;
          cols[o * 3 + 2] = cols[o * 3 + 2] * 0.45 + btex[bi + 2] * 0.55 * bndl;
        }
      }
    }
  }

  // ---------- block outlines (visible cube edges) ----------
  if (opts.outlines !== false) {
    for (let py = 0; py < RH; py++) {
      for (let px = 0; px < RW; px++) {
        const o = py * RW + px;
        if (!lit[o]) continue;
        const right2 = px + 1 < RW ? o + 1 : -1;
        const down = py + 1 < RH ? o + RW : -1;
        let edge = false;
        if (right2 >= 0 && lit[right2] && Math.abs(depthBuf[o] - depthBuf[right2]) > 0.35) edge = true;
        if (down >= 0 && lit[down] && Math.abs(depthBuf[o] - depthBuf[down]) > 0.35) edge = true;
        if (right2 >= 0 && !lit[right2]) edge = true;
        if (down >= 0 && !lit[down]) edge = true;
        if (edge) {
          cols[o * 3] *= 0.62;
          cols[o * 3 + 1] *= 0.62;
          cols[o * 3 + 2] *= 0.62;
        }
      }
    }
  }

  // ---------- downsample (supersampling) ----------
  for (let y = 0; y < H; y++) {
    for (let x = 0; x < W; x++) {
      let r = 0, gg = 0, b = 0;
      for (let dy = 0; dy < ss; dy++) {
        for (let dx = 0; dx < ss; dx++) {
          const o = ((y * ss + dy) * RW + (x * ss + dx)) * 3;
          r += cols[o]; gg += cols[o + 1]; b += cols[o + 2];
        }
      }
      const n = ss * ss;
      rgb[(y * W + x) * 3] = clamp255(r / n);
      rgb[(y * W + x) * 3 + 1] = clamp255(gg / n);
      rgb[(y * W + x) * 3 + 2] = clamp255(b / n);
    }
  }
  return encodeRgb(W, H, rgb);
}

/** Standard orbit views in the same order as render.mjs. */
export const VOXEL_VIEWS = {
  se: { azimuth: 45, elevation: 26 },
  sw: { azimuth: -45, elevation: 26 },
  ne: { azimuth: 135, elevation: 26 },
  nw: { azimuth: -135, elevation: 26 },
  front: { azimuth: 0, elevation: 12 },
  back: { azimuth: 180, elevation: 12 },
  top: { azimuth: 45, elevation: 78 },
  side: { azimuth: 90, elevation: 16 },
};

/**
 * Renders several views of a voxel set.
 * @param {{voxels: Map<string,object>, palette: Record<string,string>}} input
 * @param {object} [opts]
 * @returns {Promise<{views: {name: string, png: Buffer}[]}>}
 */
export async function renderVoxelViews(input, opts = {}) {
  const names = opts.views || ['se', 'sw', 'ne', 'front'];
  const views = [];
  for (const name of names) {
    const v = VOXEL_VIEWS[name];
    if (!v) throw new Error(`unknown view '${name}' (known: ${Object.keys(VOXEL_VIEWS).join(', ')})`);
    const png = renderVoxelPng(input, { ...opts, ...v });
    views.push({ name, png });
  }
  return { views };
}

// ---------------------------------------------------------------- helpers

function sub(a, b) { return [a[0] - b[0], a[1] - b[1], a[2] - b[2]]; }
function cross(a, b) { return [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]; }
function norm(a) {
  const l = Math.hypot(a[0], a[1], a[2]) || 1;
  return [a[0] / l, a[1] / l, a[2] / l];
}

/** Minimal PNG writer for an RGB byte buffer (no filtering, level 9). */
function encodeRgb(width, height, rgb) {
  const stride = width * 3;
  const raw = Buffer.alloc((stride + 1) * height);
  for (let y = 0; y < height; y++) {
    raw[y * (stride + 1)] = 0;
    Buffer.from(rgb.buffer, rgb.byteOffset + y * stride, stride).copy(raw, y * (stride + 1) + 1);
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;
  ihdr[9] = 2;
  ihdr[10] = 0;
  ihdr[11] = 0;
  ihdr[12] = 0;
  return Buffer.concat([
    PNG_SIGNATURE,
    pngChunk('IHDR', ihdr),
    pngChunk('IDAT', deflateSync(raw, { level: 9 })),
    pngChunk('IEND', Buffer.alloc(0)),
  ]);
}

export { buildGrid, trace, makeTexture, encodeRgb };
