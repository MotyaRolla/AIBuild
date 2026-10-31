/**
 * Builders for the blocky object types: box, floor, stairs, beam, column, chimney, balustrade,
 * detail. They all emit integer cells; buildScene() turns the cells into polygons.
 *
 * Every builder has the shape `(obj, ctx) -> Desc[]`, where `ctx` carries the normalised spec,
 * the seeded RNG and scene bounds. A descriptor is described in helpers.mjs.
 */

import { addCell, makeDesc, fillBox, shellBox, key, validBox, range, makeRng, mid, round } from './helpers.mjs';
import { WALL_INFO, apertureCells, resolveOpening, wallDAxis, wallUAxis } from './openings.mjs';

/** Default material when an object omits one. */
export const DEFAULT_MATERIAL = 'plain';

/** The material of an object, with a fallback so the renderer always has a palette key. */
export function materialOf(obj, fallback = DEFAULT_MATERIAL) {
  return typeof obj.material === 'string' && obj.material ? obj.material : fallback;
}

/** Secondary material for trims/accents; falls back to a sensible palette key. */
export function accentOf(obj, primary) {
  if (typeof obj.accent === 'string' && obj.accent) return obj.accent;
  if (typeof obj.frame === 'string' && obj.frame) return obj.frame;
  if (primary === 'roof') return 'roof_accent';
  if (primary === 'wall') return 'frame';
  return 'stone_dark';
}

/**
 * Removes aperture cells from a box-ish descriptor.
 *
 * @param {object} desc
 * @param {Array<{wall:string, u0:number, u1:number, y0:number, y1:number, kind:string, arch:boolean, cells:number[][]|null}>} rects
 * @param {{uAxis:number, dAxis:number, dMin:number, dMax:number, outerSign:number, maxDepth?:(r:object)=>number}} geom
 * @returns {{cells:number[][], depth:number, rect:object}[]} holes that were actually opened
 */
export function cutRects(desc, rects, geom) {
  const opened = [];
  for (const rect of rects) {
    const cells = apertureCells(rect);
    const startD = geom.outerSign > 0 ? geom.dMax : geom.dMin;
    const dir = -geom.outerSign;
    const maxDepth = geom.maxDepth ? geom.maxDepth(rect) : Infinity;
    for (const [u, y] of cells) {
      let removed = 0;
      const p = [0, 0, 0];
      p[geom.uAxis] = u;
      p[1] = y;
      let d = startD;
      while (removed < maxDepth && d >= geom.dMin && d <= geom.dMax) {
        p[geom.dAxis] = d;
        const k = key(p[0], p[1], p[2]);
        if (!desc.cells.has(k)) break;
        desc.cells.delete(k);
        if (desc.materials) desc.materials.delete(k);
        removed++;
        d += dir;
      }
      if (removed > 0) opened.push({ u, y, depth: d - dir, removed });
    }
    rect.depth = startD;
  }
  return opened;
}

/**
 * Adds a decorative frame around a rect, on the outermost wall layer.
 * @param {object} frameDesc
 * @param {object} rect
 * @param {{uAxis:number, dAxis:number, d:number}} geom
 * @param {{sill?:boolean, shutters?:boolean, canopy?:boolean, outward?:number}} opt
 */
export function addFrame(frameDesc, rect, geom, opt = {}) {
  const put = (u, y, mat) => {
    const p = [0, 0, 0];
    p[geom.uAxis] = u;
    p[1] = y;
    p[geom.dAxis] = geom.d;
    addCell(frameDesc, p[0], p[1], p[2]);
    if (frameDesc.materials) frameDesc.materials.set(key(p[0], p[1], p[2]), mat);
  };
  const mat = frameDesc.material;
  for (let u = rect.u0 - 1; u <= rect.u1 + 1; u++) {
    put(u, rect.y0 - 1, mat);
    put(u, rect.y1 + 1, mat);
  }
  for (let y = rect.y0; y <= rect.y1; y++) {
    put(rect.u0 - 1, y, mat);
    put(rect.u1 + 1, y, mat);
  }
  if (opt.sill) {
    for (let u = rect.u0 - 1; u <= rect.u1 + 1; u++) put(u, rect.y0 - 1, mat);
    // A sill sticks out one cell further than the frame.
    for (let u = rect.u0; u <= rect.u1; u++) {
      const p = [0, 0, 0];
      p[geom.uAxis] = u;
      p[1] = rect.y0 - 1;
      p[geom.dAxis] = geom.d + (opt.outward ?? 1);
      addCell(frameDesc, p[0], p[1], p[2]);
      if (frameDesc.materials) frameDesc.materials.set(key(p[0], p[1], p[2]), mat);
    }
  }
  if (opt.shutters) {
    for (let y = rect.y0; y <= rect.y1; y++) {
      for (let u = rect.u0 - 2; u <= rect.u1 + 2; u += rect.u1 + 3 - rect.u0) {
        const p = [0, 0, 0];
        p[geom.uAxis] = u;
        p[1] = y;
        p[geom.dAxis] = geom.d + (opt.outward ?? 1);
        addCell(frameDesc, p[0], p[1], p[2]);
        if (frameDesc.materials) frameDesc.materials.set(key(p[0], p[1], p[2]), mat);
      }
    }
  }
  if (opt.canopy) {
    for (let u = rect.u0 - 1; u <= rect.u1 + 1; u++) {
      for (let o = 1; o <= 2; o++) {
        const p = [0, 0, 0];
        p[geom.uAxis] = u;
        p[1] = rect.y1 + 2;
        p[geom.dAxis] = geom.d + (opt.outward ?? 1) * o;
        addCell(frameDesc, p[0], p[1], p[2]);
        if (frameDesc.materials) frameDesc.materials.set(key(p[0], p[1], p[2]), mat);
      }
    }
  }
  return frameDesc;
}

/** Fills the aperture with glass cells on the given wall layer. */
export function addGlass(glassDesc, rect, geom) {
  for (const [u, y] of apertureCells(rect)) {
    const p = [0, 0, 0];
    p[geom.uAxis] = u;
    p[1] = y;
    p[geom.dAxis] = geom.d;
    addCell(glassDesc, p[0], p[1], p[2]);
  }
  return glassDesc;
}

/** Resolves all `openings` of an object to rects, clamped to the object footprint. */
export function openingRects(obj, bounds) {
  const list = Array.isArray(obj.openings) ? obj.openings : [];
  return list.map((op) => resolveOpening(op, bounds));
}

/**
 * box — axis-aligned box, optionally hollow, with optional open sides and openings.
 * @param {object} obj
 * @param {object} ctx
 * @returns {object[]}
 */
export function buildBox(obj, ctx) {
  const mat = materialOf(obj, 'wall');
  const from = obj.from || [0, 0, 0];
  const to = obj.to || from;
  if (!validBox(from, to)) return [];
  const body = makeDesc('cells', mat);
  const open = Array.isArray(obj.open) ? obj.open : [];
  if (obj.hollow) shellBox(body, from, to, open);
  else fillBox(body, from, to);

  const bounds = {
    uMin: -Infinity,
    uMax: Infinity,
    yMin: from[1],
    yMax: to[1],
  };
  const out = [body];
  const rects = openingRects(obj, bounds);
  if (rects.length) {
    // Wall-plane extents per wall axis: `u` runs along the wall, `d` through it.
    const punched = [];
    for (const rect of rects) {
      const uAxis = wallUAxis(rect.wall);
      const dAxis = wallDAxis(rect.wall);
      const uMin = uAxis === 0 ? from[0] : from[2];
      const uMax = uAxis === 0 ? to[0] : to[2];
      rect.u0 = Math.max(uMin, Math.min(uMax, rect.u0));
      rect.u1 = Math.max(uMin, Math.min(uMax, rect.u1));
      if (rect.cells) rect.cells = rect.cells.filter((c) => c[0] >= uMin && c[0] <= uMax);
      const dMin = dAxis === 0 ? from[0] : from[2];
      const dMax = dAxis === 0 ? to[0] : to[2];
      const outerSign = WALL_INFO[rect.wall].sign;
      const through = rect.kind === 'door' || rect.kind === 'arch' || rect.through === true;
      const geom = {
        uAxis,
        dAxis,
        dMin,
        dMax,
        outerSign,
        d: outerSign > 0 ? dMax : dMin,
        maxDepth: through ? () => dMax - dMin + 1 : (r) => Math.min(dMax - dMin + 1, r.depth ?? 2),
      };
      const holes = cutRects(body, [rect], geom);
      if (holes.length) punched.push({ rect, geom, holes });
    }
    // Frames / glass / sills are separate descriptors with their own face kind and material.
    for (const { rect, geom, holes } of punched) {
      if (rect.frame) {
        const f = makeDesc('cells', rect.frame);
        addFrame(f, rect, { uAxis: geom.uAxis, dAxis: geom.dAxis, d: geom.d }, {
          sill: rect.sill,
          shutters: rect.shutters,
          canopy: rect.canopy || (rect.kind === 'door' && obj.canopy),
          outward: geom.outerSign,
        });
        if (f.cells.size) out.push(f);
      }
      if (rect.glass) {
        const g = makeDesc('cells', rect.glass, 'glass');
        addGlass(g, rect, { uAxis: geom.uAxis, dAxis: geom.dAxis, d: geom.d });
        if (g.cells.size) out.push(g);
      }
    }
  }
  return out;
}

/**
 * floor — a slab with an optional pattern.
 * @param {object} obj
 * @param {object} ctx
 * @returns {object[]}
 */
export function buildFloor(obj, ctx) {
  const mat = materialOf(obj, 'stone');
  const from = obj.from || [0, 0, 0];
  const to = obj.to || from;
  if (!validBox(from, to)) return [];
  const thickness = Math.max(1, Math.round(obj.thickness ?? 1));
  const top = to[1];
  const bottom = Math.max(from[1], top - thickness + 1);
  const desc = makeDesc('cells', mat);
  const accent = accentOf(obj, mat);
  const pattern = obj.pattern || 'solid';
  desc.materials = new Map();
  for (let x = from[0]; x <= to[0]; x++) {
    for (let z = from[2]; z <= to[2]; z++) {
      let useAccent = false;
      if (pattern === 'checker') useAccent = (x + z) % 2 === 0;
      else if (pattern === 'border') {
        useAccent = x === from[0] || x === to[0] || z === from[2] || z === to[2];
      } else if (pattern === 'planks') useAccent = Math.floor((x - from[0]) / 3) % 2 === 1;
      for (let y = bottom; y <= top; y++) {
        addCell(desc, x, y, z);
        if (useAccent) desc.materials.set(key(x, y, z), accent);
      }
    }
  }
  return [desc];
}

/**
 * stairs — a solid stair flight climbing along `axis`.
 * @param {object} obj
 * @param {object} ctx
 * @returns {object[]}
 */
export function buildStairs(obj, ctx) {
  const mat = materialOf(obj, 'wood');
  const from = obj.from || [0, 0, 0];
  const to = obj.to || from;
  if (!validBox(from, to)) return [];
  const axis = obj.axis === 'x' || obj.axis === 'z' ? obj.axis : 'z';
  const a = axis === 'x' ? 0 : 2;
  const c = axis === 'x' ? 2 : 0;
  const climbLo = Math.min(from[a], to[a]);
  const climbHi = Math.max(from[a], to[a]);
  const crossLo = Math.min(from[c], to[c]);
  const crossHi = Math.max(from[c], to[c]);
  const levels = to[1] - from[1] + 1;
  if (levels < 1) return [];
  const length = climbHi - climbLo + 1;
  const back = obj.direction === 'back';
  const desc = makeDesc('cells', mat);
  const rail = makeDesc('cells', accentOf(obj, mat), 'detail');
  for (let i = 0; i < length; i++) {
    const pos = back ? climbHi - i : climbLo + i;
    // Step index: how many levels of the flight have been climbed at this position.
    const step = Math.min(levels - 1, Math.floor((i * levels) / length));
    for (let y = from[1]; y <= from[1] + step; y++) {
      for (let cc = crossLo; cc <= crossHi; cc++) {
        const p = [0, 0, 0];
        p[a] = pos;
        p[c] = cc;
        p[1] = y;
        addCell(desc, p[0], p[1], p[2]);
      }
    }
    if (obj.railing && i < length - 1) {
      const topY = from[1] + step + 1;
      for (const cc of [crossLo, crossHi]) {
        const p = [0, 0, 0];
        p[a] = pos;
        p[c] = cc;
        p[1] = topY;
        addCell(rail, p[0], p[1], p[2]);
      }
    }
  }
  return rail.cells.size ? [desc, rail] : [desc];
}

/**
 * beam — a structural member between two points. Dominant axis first, then a square cross-section.
 * @param {object} obj
 * @param {object} ctx
 * @returns {object[]}
 */
export function buildBeam(obj, ctx) {
  const mat = materialOf(obj, 'frame');
  const from = obj.from || [0, 0, 0];
  const to = obj.to || from;
  const t = Math.max(1, Math.round(obj.thickness ?? 1));
  const delta = [to[0] - from[0], to[1] - from[1], to[2] - from[2]];
  const len = [Math.abs(delta[0]) + 1, Math.abs(delta[1]) + 1, Math.abs(delta[2]) + 1];
  const axis = len[0] >= len[1] && len[0] >= len[2] ? 0 : len[1] >= len[2] ? 1 : 2;
  const desc = makeDesc('cells', mat);
  const run = len[axis];
  const half = Math.floor((t - 1) / 2);
  for (let step = 0; step < run; step++) {
    const f = run === 1 ? 0 : step / (run - 1);
    const center = [0, 0, 0];
    for (let k = 0; k < 3; k++) center[k] = from[k] + delta[k] * f;
    const base = center.map((v) => Math.round(v));
    for (let ox = -half; ox <= t - 1 - half; ox++) {
      for (let oy = -half; oy <= t - 1 - half; oy++) {
        for (let oz = -half; oz <= t - 1 - half; oz++) {
          addCell(desc, base[0] + ox, base[1] + oy, base[2] + oz);
        }
      }
    }
  }
  return [desc];
}

/**
 * column — base, shaft and capital. `style` may be round or square.
 * @param {object} obj
 * @param {object} ctx
 * @returns {object[]}
 */
export function buildColumn(obj, ctx) {
  const mat = materialOf(obj, 'stone');
  const at = obj.at || [0, 0, 0];
  const height = Math.max(1, Math.round(obj.height ?? 4));
  const radius = Math.max(0.5, Number(obj.radius ?? 0.5));
  const style = obj.style === 'square' ? 'square' : 'round';
  const desc = makeDesc('cells', mat);
  const accent = makeDesc('cells', accentOf(obj, mat), 'detail');
  const size = Math.max(1, Math.round(radius * 2));
  const halfU = (size - 1) / 2;
  const cx = at[0] + halfU;
  const cz = at[2] + halfU;
  const inCross = (x, z, grow) => {
    if (style === 'square') {
      return Math.abs(x + 0.5 - (cx + 0.5)) <= halfU + grow + 0.5 && Math.abs(z + 0.5 - (cz + 0.5)) <= halfU + grow + 0.5;
    }
    const dx = x + 0.5 - (cx + 0.5);
    const dz = z + 0.5 - (cz + 0.5);
    return Math.hypot(dx, dz) <= radius + grow;
  };
  const xLo = Math.floor(cx - radius - 2);
  const xHi = Math.ceil(cx + radius + 2);
  const zLo = Math.floor(cz - radius - 2);
  const zHi = Math.ceil(cz + radius + 2);
  for (let y = at[1]; y < at[1] + height; y++) {
    const grow = y === at[1] || y === at[1] + height - 1 ? 0.5 : 0;
    for (let x = xLo; x <= xHi; x++) {
      for (let z = zLo; z <= zHi; z++) {
        if (!inCross(x, z, grow)) continue;
        addCell(desc, x, y, z);
      }
    }
  }
  // Base plinth (wider, two layers) and abacus on top, kept in the accent material.
  for (let y = at[1]; y <= at[1] + 1; y++) {
    for (let x = xLo - 1; x <= xHi + 1; x++) {
      for (let z = zLo - 1; z <= zHi + 1; z++) {
        if (!inCross(x, z, 1)) continue;
        addCell(accent, x, y, z);
      }
    }
  }
  for (let y = at[1] + height - 2; y <= at[1] + height - 1; y++) {
    if (y < at[1]) continue;
    for (let x = xLo - 1; x <= xHi + 1; x++) {
      for (let z = zLo - 1; z <= zHi + 1; z++) {
        if (!inCross(x, z, 1)) continue;
        addCell(accent, x, y, z);
      }
    }
  }
  return [desc, accent];
}

/**
 * chimney — a stack with an optional cap.
 * @param {object} obj
 * @param {object} ctx
 * @returns {object[]}
 */
export function buildChimney(obj, ctx) {
  const mat = materialOf(obj, 'brick');
  const at = obj.at || [0, 0, 0];
  const w = Math.max(1, Math.round(obj.w ?? 1));
  const d = Math.max(1, Math.round(obj.d ?? w));
  const height = Math.max(1, Math.round(obj.height ?? 6));
  const body = makeDesc('cells', mat);
  fillBox(body, at, [at[0] + w - 1, at[1] + height - 1, at[2] + d - 1]);
  const cap = makeDesc('cells', accentOf(obj, mat), 'detail');
  if (obj.cap !== false) {
    fillBox(cap, [at[0] - 1, at[1] + height, at[2] - 1], [at[0] + w, at[1] + height, at[2] + d]);
    fillBox(cap, [at[0], at[1] + height + 1, at[2]], [at[0] + w - 1, at[1] + height + 1, at[2] + d - 1], new Set([key(at[0], at[1] + height + 1, at[2])]));
  }
  return cap.cells.size ? [body, cap] : [body];
}

/**
 * balustrade — a railing run with posts, rails and (optionally) balusters.
 * @param {object} obj
 * @param {object} ctx
 * @returns {object[]}
 */
export function buildBalustrade(obj, ctx) {
  const mat = materialOf(obj, 'stone');
  const from = obj.from || [0, 0, 0];
  const to = obj.to || from;
  const a = Math.abs(to[0] - from[0]) >= Math.abs(to[2] - from[2]) ? 0 : 2;
  const c = a === 0 ? 2 : 0;
  const lo = Math.min(from[a], to[a]);
  const hi = Math.max(from[a], to[a]);
  const baseY = Math.min(from[1], to[1]);
  const topY = Math.max(from[1], to[1]) > baseY ? Math.max(from[1], to[1]) : baseY + 1;
  const cross = from[c];
  const spacing = Math.max(1, Math.round(obj.spacing ?? 3));
  const style = obj.style || 'baluster';
  const desc = makeDesc('cells', mat);
  const put = (u, y) => {
    const p = [0, 0, 0];
    p[a] = u;
    p[1] = y;
    p[c] = cross;
    addCell(desc, p[0], p[1], p[2]);
  };
  for (let u = lo; u <= hi; u++) {
    put(u, baseY);
    put(u, topY);
    const isPost = (u - lo) % spacing === 0 || u === hi;
    if (isPost) {
      for (let y = baseY; y <= topY; y++) put(u, y);
    } else if (style === 'solid') {
      for (let y = baseY; y <= topY; y++) put(u, y);
    } else if (style === 'baluster') {
      if ((u - lo) % 2 === 1) put(u, baseY + 1);
      if (topY - baseY > 2) put(u, topY - 1);
    }
  }
  // Ball caps on the posts.
  if (style !== 'fence') {
    for (let u = lo; u <= hi; u += spacing) put(u, topY + 1);
    put(hi, topY + 1);
  }
  return [desc];
}

/**
 * detail — a free-form voxel cluster.
 * @param {object} obj
 * @param {object} ctx
 * @returns {object[]}
 */
export function buildDetail(obj, ctx) {
  const cells = Array.isArray(obj.cells) ? obj.cells : [];
  if (!cells.length) return [];
  const mats = Array.isArray(obj.material) ? obj.material : [obj.material].filter(Boolean);
  const material = mats[0] || 'plain';
  const desc = makeDesc('cells', material, 'detail');
  desc.materials = new Map();
  const pattern = obj.pattern || 'solid';
  const rng = makeRng((ctx?.seed ?? 1) * 7919 + cells.length);
  let i = 0;
  for (const raw of cells) {
    if (!Array.isArray(raw) || raw.length < 3) continue;
    const p = raw.map(Math.round);
    let keep = true;
    if (pattern === 'hash') keep = (p[0] + p[1] + p[2]) % 2 === 0;
    else if (pattern === 'sparse') keep = rng() > 0.35;
    if (!keep) continue;
    const mat = mats.length > 1 ? mats[Math.abs(p[0] * 31 + p[1] * 17 + p[2] * 7 + i) % mats.length] : material;
    addCell(desc, p[0], p[1], p[2]);
    desc.materials.set(key(p[0], p[1], p[2]), mat);
    i++;
  }
  return desc.cells.size ? [desc] : [];
}

/** Small helpers re-exported for the other object modules. */
export { range, mid, round, fillBox };
