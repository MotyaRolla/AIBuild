/**
 * Low-level helpers shared by every object builder.
 *
 * A builder never produces triangles directly. It fills a small "descriptor" object:
 *
 *   kind:  'cells'  — blocky volumes given cell by cell (exact, used for walls/roofs/stairs/...)
 *          'solid'  — a volume described by an analytic `inside(x, y, z)` test (curved shapes)
 *          'faces'  — explicit polygons (custom details)
 *
 * buildScene() then turns every descriptor into `faces` (for the software renderer) and into a
 * `Set` of unit cells (for the Minecraft voxelizer). Doing it this way guarantees that the render
 * geometry and the Minecraft result always agree: both come from the same cell set.
 *
 * Cell convention (shared with the transfer module):
 *   cell (x,y,z) covers [x,x+1] x [y,y+1] x [z,z+1]; its centre is (x+0.5, y+0.5, z+0.5).
 *   key = "x|y|z".
 */

/** Unit normals of the six axis-aligned faces, indexed like FACE_CORNERS. */
export const NORMALS = /** @type {const} */ ([
  [1, 0, 0], // +x
  [-1, 0, 0], // -x
  [0, 1, 0], // +y
  [0, -1, 0], // -y
  [0, 0, 1], // +z
  [0, 0, -1], // -z
]);

export const FACE_XP = 0;
export const FACE_XN = 1;
export const FACE_YP = 2;
export const FACE_YN = 3;
export const FACE_ZP = 4;
export const FACE_ZN = 5;

/**
 * Corner offsets (cell-local units) of each face, in counter-clockwise order seen from outside.
 */
export const FACE_CORNERS = /** @type {const} */ ([
  [[1, 0, 0], [1, 1, 0], [1, 1, 1], [1, 0, 1]], // +x
  [[0, 0, 1], [0, 1, 1], [0, 1, 0], [0, 0, 0]], // -x
  [[0, 1, 0], [0, 1, 1], [1, 1, 1], [1, 1, 0]], // +y
  [[0, 0, 0], [1, 0, 0], [1, 0, 1], [0, 0, 1]], // -y
  [[1, 0, 1], [1, 1, 1], [0, 1, 1], [0, 0, 1]], // +z
  [[0, 0, 0], [0, 1, 0], [1, 1, 0], [1, 0, 0]], // -z
]);

/** Neighbour offsets matching the face order above. */
export const FACE_STEPS = /** @type {const} */ ([
  [1, 0, 0],
  [-1, 0, 0],
  [0, 1, 0],
  [0, -1, 0],
  [0, 0, 1],
  [0, 0, -1],
]);

/** Axis perpendicular to each face (0=x, 1=y, 2=z), and the two in-plane axes. */
const FACE_AXIS = [0, 0, 1, 1, 2, 2];
const FACE_INPLANE = [[1, 2], [1, 2], [0, 2], [0, 2], [0, 1], [0, 1]];

/** Builds the integer-string key of a voxel cell. */
export function key(x, y, z) {
  return `${x}|${y}|${z}`;
}

/** Parses a cell key produced by key(). */
export function parseKey(k) {
  const parts = k.split('|');
  return [Number(parts[0]), Number(parts[1]), Number(parts[2])];
}

/**
 * Clips a convex polygon to the half-space { p : dot(n,p) <= d }.
 * Polygons lying exactly on a cell boundary survive unchanged (conservative for sloped shells).
 * @param {number[][]} poly
 * @param {number[]} n
 * @param {number} d
 * @returns {number[][]}
 */
export function clipPolygon(poly, n, d) {
  const EPS = 1e-9;
  const dist = poly.map((p) => n[0] * p[0] + n[1] * p[1] + n[2] * p[2] - d);
  let anyOut = false;
  let anyIn = false;
  for (const s of dist) {
    if (s > EPS) anyOut = true;
    else anyIn = true;
  }
  if (!anyOut) return poly;
  if (!anyIn) return [];

  /** @type {number[][]} */
  const out = [];
  for (let i = 0; i < poly.length; i++) {
    const j = (i + 1) % poly.length;
    const si = dist[i];
    const sj = dist[j];
    const pi = poly[i];
    const pj = poly[j];
    if (si <= EPS) out.push(pi);
    if ((si > EPS && sj < -EPS) || (si < -EPS && sj > EPS)) {
      const t = si / (si - sj);
      out.push([pi[0] + (pj[0] - pi[0]) * t, pi[1] + (pj[1] - pi[1]) * t, pi[2] + (pj[2] - pi[2]) * t]);
    }
  }
  return out;
}

/**
 * Creates an empty descriptor. Every builder returns one; `material` is copied into every face that
 * is emitted from it.
 * @param {'cells'|'solid'|'faces'} kind
 * @param {string} material
 * @param {string} [faceKind] 'surface'|'glass'|'detail'
 */
export function makeDesc(kind, material, faceKind = 'surface') {
  const desc = {
    kind,
    material,
    faceKind,
    /** @type {Map<string, number>|null} cell key -> extra shape flags */
    cells: kind === 'cells' ? new Map() : null,
    /** @type {Map<string, string>|null} optional per-cell material override */
    materials: null,
    /** @type {Map<string, number[]>|null} optional per-cell normal override (curved/sloped shells) */
    cellNormals: null,
    /** 'voxel' | 'render' | undefined — lets a builder emit one descriptor per representation */
    role: undefined,
    /** @type {((x:number,y:number,z:number)=>boolean)|null} */
    inside: null,
    /** @type {{v:number[][], normal:number[], kind?:string}[]} */
    faces: [],
    min: [Infinity, Infinity, Infinity],
    max: [-Infinity, -Infinity, -Infinity],
  };
  return desc;
}

/**
 * Hard cap on the number of cells one object may claim. A Minecraft structure is block-sized; a
 * sphere of radius 60 (~930k cells) is a spec bug, and without a cap it would stall an iteration
 * for seconds. Objects that hit the cap are flagged and reported in `stats.warnings`.
 */
export const MAX_OBJECT_CELLS = 400_000;

/** Marks a cell in a 'cells' descriptor. `flags` is an extra bitmask used for special shapes. */
export function addCell(desc, x, y, z, flags = 0) {
  const k = key(x, y, z);
  const prev = desc.cells.get(k);
  if (prev === undefined) {
    if (desc.cells.size >= (desc.cellLimit || MAX_OBJECT_CELLS)) {
      desc.truncated = true;
      return;
    }
    desc.cells.set(k, flags);
    if (x < desc.min[0]) desc.min[0] = x;
    if (y < desc.min[1]) desc.min[1] = y;
    if (z < desc.min[2]) desc.min[2] = z;
    if (x > desc.max[0]) desc.max[0] = x;
    if (y > desc.max[1]) desc.max[1] = y;
    if (z > desc.max[2]) desc.max[2] = z;
  } else if (flags) {
    desc.cells.set(k, prev | flags);
  }
}

/** Inclusive integer range helper: `range(2, 4)` -> [2, 3, 4]; empty when hi < lo. */
export function range(lo, hi) {
  const out = [];
  for (let v = lo; v <= hi; v++) out.push(v);
  return out;
}

/**
 * Adds every cell of the inclusive integer box.
 * @param {ReturnType<typeof makeDesc>} desc
 * @param {number[]} from
 * @param {number[]} to
 * @param {Set<string>} [skip]
 * @param {((x:number,y:number,z:number)=>number)} [flagsFn]
 */
export function fillBox(desc, from, to, skip, flagsFn) {
  for (let x = from[0]; x <= to[0]; x++) {
    for (let y = from[1]; y <= to[1]; y++) {
      for (let z = from[2]; z <= to[2]; z++) {
        if (skip && skip.has(key(x, y, z))) continue;
        addCell(desc, x, y, z, flagsFn ? flagsFn(x, y, z) : 0);
      }
    }
  }
}

/**
 * Adds only the 6 face layers of the inclusive integer box (a shell one cell thick).
 * @param {ReturnType<typeof makeDesc>} desc
 * @param {number[]} from
 * @param {number[]} to
 * @param {string[]} [openFaces] north|south|east|west|up|down
 * @param {Set<string>} [skip]
 */
export function shellBox(desc, from, to, openFaces, skip) {
  const open = new Set(openFaces || []);
  const [x0, y0, z0] = from;
  const [x1, y1, z1] = to;
  for (let x = x0; x <= x1; x++) {
    for (let y = y0; y <= y1; y++) {
      for (let z = z0; z <= z1; z++) {
        const onX = x === x0 || x === x1;
        const onY = y === y0 || y === y1;
        const onZ = z === z0 || z === z1;
        if (!onX && !onY && !onZ) continue;
        const hit = [];
        if (onX) hit.push(x === x1 ? 'east' : 'west');
        if (onY) hit.push(y === y1 ? 'up' : 'down');
        if (onZ) hit.push(z === z1 ? 'south' : 'north');
        if (hit.length && hit.every((f) => open.has(f))) continue;
        if (skip && skip.has(key(x, y, z))) continue;
        addCell(desc, x, y, z);
      }
    }
  }
}

/** Clamps a value to the inclusive [lo, hi] range. */
export function clamp(v, lo, hi) {
  return v < lo ? lo : v > hi ? hi : v;
}

/** True when the inclusive integer box is valid and non-empty. */
export function validBox(from, to) {
  return (
    Array.isArray(from) &&
    Array.isArray(to) &&
    from.length === 3 &&
    to.length === 3 &&
    Number.isFinite(from[0]) &&
    Number.isFinite(to[0]) &&
    to[0] >= from[0] &&
    to[1] >= from[1] &&
    to[2] >= from[2]
  );
}

/** Mid-point of an inclusive integer span, e.g. mid(0, 9) = 4.5. */
export function mid(lo, hi) {
  return (lo + hi) / 2;
}

/** Rounds a number to `digits` decimals (keeps geometry small and comparison stable). */
export function round(v, digits = 4) {
  const f = 10 ** digits;
  return Math.round(v * f) / f;
}

/** A small deterministic PRNG (mulberry32): seeded objects never touch Math.random. */
export function makeRng(seed) {
  let a = (Number.isFinite(seed) ? seed : 1) >>> 0;
  return function next() {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/**
 * Turns a set of cells into outward-facing polygons.
 *
 * Faces between two cells of the same set are dropped (interior faces are invisible), so a slab
 * becomes 2 polygons instead of 2*n*n. Neighbouring coplanar faces are merged with an exact greedy
 * rectangle union, then each merged rectangle is clipped by the optional half-spaces (used for
 * sloped roofs, where a stepped rim is cut back to the exact slope plane).
 *
 * @param {Map<string, number>|Set<string>} cells
 * @param {{skipFace?: (x:number,y:number,z:number,face:number)=>boolean, clip?: Array<{n:number[], d:number}>, materialOf?: (k:string)=>string}} [opts]
 * @returns {{v:number[][], normal:number[], face:number, material:string}[]}
 */
export function cellsToFaces(cells, opts = {}) {
  const skipFace = opts.skipFace;
  const clip = opts.clip || [];
  const materialOf = opts.materialOf || (() => 'plain');
  const out = [];

  /** @type {Map<string, {axis:number, slab:number, sign:number, material:string, faces:number[][]}>} */
  const buckets = new Map();
  for (const k of cells.keys()) {
    const p = parseKey(k);
    const mat = materialOf(k);
    for (let f = 0; f < 6; f++) {
      const s = FACE_STEPS[f];
      if (cells.has(key(p[0] + s[0], p[1] + s[1], p[2] + s[2]))) continue;
      if (skipFace && skipFace(p[0], p[1], p[2], f)) continue;
      const axis = FACE_AXIS[f];
      const sign = s[axis];
      const slab = p[axis] + (sign > 0 ? 1 : 0);
      const bk = `${axis}|${slab}|${sign}|${mat}`;
      let b = buckets.get(bk);
      if (!b) {
        b = { axis, slab, sign, material: mat, faces: [] };
        buckets.set(bk, b);
      }
      b.faces.push(p);
    }
  }

  for (const b of buckets.values()) {
    const [ua, va] = FACE_INPLANE[faceFor(b.axis, b.sign)];
    // Bucket cells are already sorted by (u, v) because buildScene materialises descriptors in
    // that order; re-sort defensively so the merge is order-independent.
    const sorted = b.faces.slice().sort((p, q) => p[ua] - q[ua] || p[va] - q[va]);
    const occupied = new Set(sorted.map((p) => `${p[ua]}|${p[va]}`));
    for (const p of sorted) {
      const uk = p[ua];
      const vk = p[va];
      if (!occupied.has(`${uk}|${vk}`)) continue;
      // Greedy rectangle: grow along v while the row is full, then along u while the whole
      // column band is full. Deleted cells disappear from `occupied`, so each cell is visited once.
      let v2 = vk;
      while (occupied.has(`${uk}|${v2 + 1}`)) v2++;
      let u2 = uk;
      grow: while (true) {
        const nextU = u2 + 1;
        for (let vv = vk; vv <= v2; vv++) if (!occupied.has(`${nextU}|${vv}`)) break grow;
        u2 = nextU;
      }
      for (let uu = uk; uu <= u2; uu++) for (let vv = vk; vv <= v2; vv++) occupied.delete(`${uu}|${vv}`);
      const face = faceFor(b.axis, b.sign);
      emitRect(out, b.axis, b.slab, ua, va, uk, u2 + 1, vk, v2 + 1, face, clip, b.material);
    }
  }
  return out;
}

/** Maps (slab axis, sign) back to a face index. */
function faceFor(axis, sign) {
  if (axis === 0) return sign > 0 ? FACE_XP : FACE_XN;
  if (axis === 1) return sign > 0 ? FACE_YP : FACE_YN;
  return sign > 0 ? FACE_ZP : FACE_ZN;
}

/** Emits one rectangle (possibly clipped into a triangle fan) with a correct outward winding. */
function emitRect(out, axis, slab, ua, va, u0, u1, v0, v1, face, clip, material) {
  /** @type {number[][]} */
  let poly = [
    [0, 0, 0],
    [0, 0, 0],
    [0, 0, 0],
    [0, 0, 0],
  ];
  poly[0][axis] = slab;
  poly[1][axis] = slab;
  poly[2][axis] = slab;
  poly[3][axis] = slab;
  poly[0][ua] = u0;
  poly[1][ua] = u1;
  poly[2][ua] = u1;
  poly[3][ua] = u0;
  poly[0][va] = v0;
  poly[1][va] = v0;
  poly[2][va] = v1;
  poly[3][va] = v1;

  // Outward winding: flip if the geometric normal opposes the face normal.
  const n = NORMALS[face];
  const e1 = [poly[1][0] - poly[0][0], poly[1][1] - poly[0][1], poly[1][2] - poly[0][2]];
  const e2 = [poly[2][0] - poly[1][0], poly[2][1] - poly[1][1], poly[2][2] - poly[1][2]];
  const cx = e1[1] * e2[2] - e1[2] * e2[1];
  const cy = e1[2] * e2[0] - e1[0] * e2[2];
  const cz = e1[0] * e2[1] - e1[1] * e2[0];
  if (cx * n[0] + cy * n[1] + cz * n[2] < 0) poly.reverse();

  if (clip.length) {
    for (const plane of clip) {
      poly = clipPolygon(poly, plane.n, plane.d);
      if (poly.length < 3) return;
    }
    if (areaOf(poly) < 1e-7) return;
  }
  if (poly.length === 4) {
    out.push({ v: poly, normal: n, face, material });
  } else if (poly.length === 3) {
    out.push({ v: poly, normal: n, face, material });
  } else {
    for (let i = 1; i + 1 < poly.length; i++) out.push({ v: [poly[0], poly[i], poly[i + 1]], normal: n, face, material });
  }
}

/** Total (absolute) area of a planar polygon. */
export function areaOf(poly) {
  let area = 0;
  for (let i = 1; i + 1 < poly.length; i++) {
    const a = poly[0];
    const b = poly[i];
    const c = poly[i + 1];
    const ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
    const vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
    const cx = uy * vz - uz * vy;
    const cy = uz * vx - ux * vz;
    const cz = ux * vy - uy * vx;
    area += Math.hypot(cx, cy, cz) * 0.5;
  }
  return area;
}

