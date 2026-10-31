/**
 * Roofs: gable / hip / shed / pyramid, with overhang and closed gable ends.
 *
 * A roof is modelled once, as the lower envelope of a few affine planes
 *   H(x, z) = min_i ( a_i + bx_i * x + bz_i * z )
 * over the (overhang-expanded) footprint rectangle. Every style is just a different plane set:
 *
 *   gable    two planes, steepest along the cross axis; the ridge is their intersection line
 *   shed     one plane (mono-pitch)
 *   hip      four planes, one per side; the ridge is the intersection of the two long ones
 *   pyramid  four planes whose slopes are scaled so that they all meet in one apex
 *
 * From that single model we derive BOTH representations, so they can never disagree:
 *
 *   1. `cells` descriptor (role 'voxel') — columns filled from the wall top up to ceil(H).
 *      This is what Minecraft receives: a watertight stepped mass with closed gable ends.
 *   2. `faces` descriptor (role 'render') — the exact polyhedron: every top plane clipped to the
 *      region where it is the lowest one, one wall polygon per footprint side (following the
 *      H profile, so a gable end is a pentagon), and a flat underside. This is what the software
 *      rasteriser draws, so roofs look like real slopes instead of stairs.
 *
 * The two are linked: cells fill up to ceil(H), so the stepped mass always contains the smooth
 * surface and never pokes through it.
 */

import { addCell, key, makeDesc } from './helpers.mjs';
import { materialOf } from './blocks.mjs';

/** Safety cap: a roof never gets taller than this (keeps pathological specs cheap). */
const MAX_ROOF_HEIGHT = 96;

/** Default eave thickness in world units (roof slab thickness at the low edge). */
const EAVE_THICKNESS = 1;

/**
 * Describes a roof: footprint, base height and the plane set.
 * @param {object} obj a prism/roof object
 * @returns {{rect:[number,number,number,number], baseY:number, planes:Array<{a:number,bx:number,bz:number}>, style:string, ridge:string, pitch:number, overhang:number}}
 */
export function roofPlanes(obj) {
  const from = obj.from || [0, 0, 0];
  const to = obj.to || from;
  const style = ['gable', 'shed', 'hip', 'pyramid'].includes(obj.style) ? obj.style : 'gable';
  const overhang = Math.max(0, Math.round(Number(obj.overhang ?? 0)));
  const pitch = Math.max(0.25, Number(obj.pitch ?? 1));
  const x0 = Math.min(from[0], to[0]) - overhang;
  const x1 = Math.max(from[0], to[0]) + 1 + overhang;
  const z0 = Math.min(from[2], to[2]) - overhang;
  const z1 = Math.max(from[2], to[2]) + 1 + overhang;
  const baseY = from[1];
  const W = x1 - x0;
  const D = z1 - z0;
  const ridge = obj.ridge === 'x' || obj.ridge === 'z' ? obj.ridge : W >= D ? 'x' : 'z';
  const back = obj.direction === 'back';

  /** Height of the roof surface right above the eave, one `pitch` above the wall top. */
  const eave = baseY + pitch * EAVE_THICKNESS;
  /** @type {Array<{a:number,bx:number,bz:number}>} */
  let planes;
  if (style === 'shed') {
    // Mono-pitch: high on one side, low on the opposite one. `direction:'back'` flips the slope
    // to the other axis, which is what a lean-to against a taller wall needs.
    const highAtZ0 = !back;
    const zPlane = highAtZ0
      ? { a: eave + pitch * (D - EAVE_THICKNESS), bx: 0, bz: -pitch }
      : { a: eave - pitch * (D - EAVE_THICKNESS), bx: 0, bz: pitch };
    const xPlane = highAtZ0
      ? { a: eave + pitch * (W - EAVE_THICKNESS), bx: -pitch, bz: 0 }
      : { a: eave - pitch * (W - EAVE_THICKNESS), bx: pitch, bz: 0 };
    planes = [ridge === 'z' ? xPlane : zPlane];
  } else if (style === 'pyramid') {
    const apex = eave + (pitch * Math.min(W, D)) / 2;
    const sx = (apex - eave) / (W / 2);
    const sz = (apex - eave) / (D / 2);
    planes = [
      { a: eave + sx * W, bx: -sx, bz: 0 },
      { a: eave - sx * x0, bx: sx, bz: 0 },
      { a: eave + sz * D, bx: 0, bz: -sz },
      { a: eave - sz * z0, bx: 0, bz: sz },
    ];
  } else if (style === 'hip') {
    planes = [
      { a: eave - pitch * x0, bx: pitch, bz: 0 },
      { a: eave + pitch * x1, bx: -pitch, bz: 0 },
      { a: eave - pitch * z0, bx: 0, bz: pitch },
      { a: eave + pitch * z1, bx: 0, bz: -pitch },
    ];
  } else {
    // gable: two planes sloping towards the ridge line
    if (ridge === 'x') {
      planes = [
        { a: eave - pitch * z0, bx: 0, bz: pitch },
        { a: eave + pitch * z1, bx: 0, bz: -pitch },
      ];
    } else {
      planes = [
        { a: eave - pitch * x0, bx: pitch, bz: 0 },
        { a: eave + pitch * x1, bx: -pitch, bz: 0 },
      ];
    }
  }
  return { rect: [x0, x1, z0, z1], baseY, planes, style, ridge, pitch, overhang };
}

/** Height of the roof surface at a world point. */
export function surfaceHeight(info, x, z) {
  let h = Infinity;
  for (const p of info.planes) {
    const v = p.a + p.bx * x + p.bz * z;
    if (v < h) h = v;
  }
  return h;
}

/** Index of the plane that owns the surface at (x, z). */
function surfacePlane(info, x, z) {
  let best = 0;
  let h = Infinity;
  for (let i = 0; i < info.planes.length; i++) {
    const p = info.planes[i];
    const v = p.a + p.bx * x + p.bz * z;
    if (v < h - 1e-12) {
      h = v;
      best = i;
    }
  }
  return best;
}

/**
 * prism / roof — builds both representations.
 * @param {object} obj
 * @param {object} ctx
 * @returns {object[]} [voxel cells descriptor, render faces descriptor]
 */
export function buildRoof(obj, ctx) {
  const info = roofPlanes(obj);
  const mat = materialOf(obj, 'roof');
  const [x0, x1, z0, z1] = info.rect;
  const baseY = info.baseY;
  const cells = makeDesc('cells', mat);
  cells.role = 'voxel';
  // Per-cell normal override: the top cell of each column reports the *slope* normal of the surface
  // above it, so the Minecraft transfer can turn it into a stair block facing the right way.
  // Without this a stepped roof would only ever report (0,1,0) and come out as flat planks.
  cells.cellNormals = new Map();
  const maxTop = Math.min(baseY + MAX_ROOF_HEIGHT, Math.ceil(eaveMax(info)) + 1);
  for (let x = x0; x < x1; x++) {
    for (let z = z0; z < z1; z++) {
      const h = surfaceHeight(info, x + 0.5, z + 0.5);
      const top = Math.min(maxTop, Math.ceil(h) - 1);
      for (let y = baseY; y <= top; y++) addCell(cells, x, y, z);
      const plane = info.planes[surfacePlane(info, x + 0.5, z + 0.5)];
      const n = planeNormal(plane);
      cells.cellNormals.set(key(x, top, z), n);
    }
  }

  const faces = makeDesc('faces', mat);
  faces.role = 'render';
  faces.faces = roofMeshFaces(info, mat);
  return [cells, faces];
}

/**
 * Unit upward normal of a roof plane `y = a + bx*x + bz*z`.
 * Computed from the unrounded slopes so the components stay as close to exact as possible, then
 * re-normalised, which keeps hypot(n) == 1 to floating point precision (important because the
 * transfer module compares normals and a test asserts they are unit length).
 */
function planeNormal(plane) {
  const nx = -plane.bx;
  const ny = 1;
  const nz = -plane.bz;
  const len = Math.hypot(nx, ny, nz) || 1;
  // Keep full precision: the transfer module compares these normals, and rounding here would make
  // hypot(n) drift away from 1. Depth-sorting does not care about 17 digits of a normal.
  return [nx / len, ny / len, nz / len];

}

/** Highest surface point of a roof (used to bound the voxel fill). */
function eaveMax(info) {
  const [x0, x1, z0, z1] = info.rect;
  let h = -Infinity;
  for (const [x, z] of [
    [x0, z0],
    [x1, z0],
    [x0, z1],
    [x1, z1],
    [(x0 + x1) / 2, (z0 + z1) / 2],
  ]) {
    h = Math.max(h, surfaceHeight(info, x, z));
  }
  return h;
}

/**
 * The exact roof polyhedron: top pieces + side walls + underside.
 * @param {ReturnType<typeof roofPlanes>} info
 * @param {string} material
 * @returns {{v:number[][], normal:number[], kind:string, material:string}[]}
 */
export function roofMeshFaces(info, material) {
  const [x0, x1, z0, z1] = info.rect;
  const baseY = info.baseY;
  const out = [];

  // ---- 1. Top pieces: clip the rectangle to the region where each plane is the lowest. ----
  for (let i = 0; i < info.planes.length; i++) {
    let poly = [
      [x0, z0],
      [x1, z0],
      [x1, z1],
      [x0, z1],
    ];
    for (let j = 0; j < info.planes.length; j++) {
      if (i === j) continue;
      const pi = info.planes[i];
      const pj = info.planes[j];
      // plane_i <= plane_j  <=>  da + dbx*x + dbz*z <= 0
      const da = pi.a - pj.a;
      const dbx = pi.bx - pj.bx;
      const dbz = pi.bz - pj.bz;
      poly = clipPoly2(poly, da, dbx, dbz);
      if (poly.length < 3) break;
    }
    if (poly.length < 3) continue;
    const v = poly.map(([x, z]) => [x, surfaceHeight(info, x, z), z]);
    out.push(makeFace(v, [0, 1, 0], 'surface', material));
  }

  // ---- 2. Side walls: bottom edge -> the H profile along that side. ----
  const sides = [
    { from: [x0, z0], to: [x1, z0], outward: [0, 0, -1] },
    { from: [x1, z0], to: [x1, z1], outward: [1, 0, 0] },
    { from: [x1, z1], to: [x0, z1], outward: [0, 0, 1] },
    { from: [x0, z1], to: [x0, z0], outward: [-1, 0, 0] },
  ];
  for (const side of sides) {
    const ts = wallBreakpoints(info, side.from, side.to);
    for (let k = 0; k + 1 < ts.length; k++) {
      const t0 = ts[k];
      const t1 = ts[k + 1];
      if (t1 - t0 < 1e-9) continue;
      const p0 = lerp2(side.from, side.to, t0);
      const p1 = lerp2(side.from, side.to, t1);
      const h0 = surfaceHeight(info, p0[0], p0[1]);
      const h1 = surfaceHeight(info, p1[0], p1[1]);
      if (h0 <= baseY + 1e-9 && h1 <= baseY + 1e-9) continue;
      const v = [
        [p0[0], baseY, p0[1]],
        [p1[0], baseY, p1[1]],
        [p1[0], h1, p1[1]],
        [p0[0], h0, p0[1]],
      ];
      out.push(makeFace(v, side.outward, 'surface', material));
    }
  }

  // ---- 3. Underside. ----
  const bottom = [
    [x0, baseY, z0],
    [x0, baseY, z1],
    [x1, baseY, z1],
    [x1, baseY, z0],
  ];
  out.push(makeFace(bottom, [0, -1, 0], 'surface', material));
  return out;
}

/** Builds a face, orienting the winding so that its Newell normal matches `want`. */
function makeFace(v, want, kind, material) {
  const n = newell(v);
  const dot = n[0] * want[0] + n[1] * want[1] + n[2] * want[2];
  const vv = dot < 0 ? [...v].reverse() : v;
  const nn = dot < 0 ? newell(vv) : n;
  return { v: vv, normal: nn, kind, material };
}

/** Newell normal of a polygon (not normalised). */
function newell(v) {
  let nx = 0;
  let ny = 0;
  let nz = 0;
  for (let i = 0; i < v.length; i++) {
    const a = v[i];
    const b = v[(i + 1) % v.length];
    nx += (a[1] - b[1]) * (a[2] + b[2]);
    ny += (a[2] - b[2]) * (a[0] + b[0]);
    nz += (a[0] - b[0]) * (a[1] + b[1]);
  }
  const len = Math.hypot(nx, ny, nz) || 1;
  return [Math.round((nx / len) * 1e5) / 1e5, Math.round((ny / len) * 1e5) / 1e5, Math.round((nz / len) * 1e5) / 1e5];
}

/**
 * Parameter values along a side where the surface changes plane (planar breakpoints), plus the
 * two endpoints. Used to build exact wall polygons.
 * @param {ReturnType<typeof roofPlanes>} info
 * @param {number[]} a
 * @param {number[]} b
 * @returns {number[]}
 */
function wallBreakpoints(info, a, b) {
  const ts = [0, 1];
  // p(t) = a + (b - a) t ;  plane_i(p(t)) = plane_j(p(t))  ->  linear in t
  const dx = b[0] - a[0];
  const dz = b[1] - a[1];
  for (let i = 0; i < info.planes.length; i++) {
    for (let j = i + 1; j < info.planes.length; j++) {
      const pi = info.planes[i];
      const pj = info.planes[j];
      const da = (pi.a - pj.a) + (pi.bx - pj.bx) * a[0] + (pi.bz - pj.bz) * a[1];
      const db = (pi.bx - pj.bx) * dx + (pi.bz - pj.bz) * dz;
      if (Math.abs(db) < 1e-12) continue;
      const t = -da / db;
      if (t > 1e-9 && t < 1 - 1e-9) ts.push(t);
    }
  }
  ts.sort((p, q) => p - q);
  const uniq = [];
  for (const t of ts) {
    if (!uniq.length || Math.abs(t - uniq[uniq.length - 1]) > 1e-9) uniq.push(t);
  }
  return uniq;
}

function lerp2(a, b, t) {
  return [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t];
}

/**
 * Clips a 2D convex polygon to the half-plane  da + dbx*x + dbz*z <= 0.
 * @param {number[][]} poly
 * @param {number} da
 * @param {number} dbx
 * @param {number} dbz
 * @returns {number[][]}
 */
function clipPoly2(poly, da, dbx, dbz) {
  const EPS = 1e-9;
  const s = poly.map(([x, z]) => da + dbx * x + dbz * z);
  let anyOut = false;
  let anyIn = false;
  for (const v of s) {
    if (v > EPS) anyOut = true;
    else anyIn = true;
  }
  if (!anyOut) return poly;
  if (!anyIn) return [];
  const out = [];
  for (let i = 0; i < poly.length; i++) {
    const j = (i + 1) % poly.length;
    const si = s[i];
    const sj = s[j];
    if (si <= EPS) out.push(poly[i]);
    if ((si > EPS && sj < -EPS) || (si < -EPS && sj > EPS)) {
      const t = si / (si - sj);
      out.push([poly[i][0] + (poly[j][0] - poly[i][0]) * t, poly[i][1] + (poly[j][1] - poly[i][1]) * t]);
    }
  }
  return out;
}
