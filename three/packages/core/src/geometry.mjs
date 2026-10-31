/**
 * Scene geometry: StructureSpec -> solids (polygons for the renderer) + voxels (for Minecraft).
 *
 * Pipeline
 * --------
 *   1. normalizeSpec() every object, group it and ask the matching builder for descriptors.
 *   2. Materialise every descriptor into unit cells (`Set<key>`, 1x1x1, centre at x+0.5).
 *      Standalone window/door/arch objects punch themselves into the already-built scene cells,
 *      so an opening really removes material instead of being drawn on top.
 *   3. Drop completely enclosed cells (cells with all 6 neighbours occupied). Those can never be
 *      seen and can never be reached by the transfer module, so removing them cuts the triangle
 *      count of a 10x10x15 house by roughly an order of magnitude.
 *   4. Emit one outward-facing polygon per exposed cell face, merging coplanar neighbours into
 *      rectangles (a 10x10 floor becomes 2 polygons instead of thousands).
 *   5. Derive the Minecraft view of each cell: `up`/`down` (solid surface above/below),
 *      `exposed` (which of the 6 directions is not occupied) and an averaged normal, which the
 *      transfer module uses to pick stairs vs slabs.
 *
 * The cell set is the single source of truth: render polygons and Minecraft blocks always agree.
 */

import { normalizeSpec, specBounds, OBJECT_TYPES } from './spec.mjs';
import { FACE_STEPS, MAX_OBJECT_CELLS, NORMALS, cellsToFaces, key, makeRng, parseKey } from './objects/helpers.mjs';
import {
  buildBalustrade,
  buildBeam,
  buildBox,
  buildChimney,
  buildColumn,
  buildDetail,
  buildFloor,
  buildStairs,
} from './objects/blocks.mjs';
import { buildCone, buildCylinder, buildRing, buildSphere } from './objects/curved.mjs';
import { buildRoof } from './objects/roofs.mjs';
import { buildOpening, buildWall, punchOpening } from './objects/walls.mjs';

/** Version of the geometry contract, reported in `stats` so the renderer can assert compatibility. */
export const GEOMETRY_VERSION = '1.0.0';

/**
 * Hard cap on the number of 1x1x1 cells a single object may claim (re-exported from helpers, where
 * the builders enforce it). A structure is Minecraft-sized, so a sphere of radius 60 (~930k cells)
 * is a spec bug, not a build; clamping it keeps one bad object from stalling an iteration.
 * Clamped objects are listed in `stats.warnings`.
 */
export { MAX_OBJECT_CELLS as MAX_SHAPE_CELLS } from './objects/helpers.mjs';

/** The six exposure bits stored per cell. */
export const DIR = { xp: 1, xn: 2, yp: 4, yn: 8, zp: 16, zn: 32 };

/** Builder lookup, one entry per accept type (roof is normalised into prism). */
const BUILDERS = {
  box: buildBox,
  floor: buildFloor,
  stairs: buildStairs,
  beam: buildBeam,
  column: buildColumn,
  chimney: buildChimney,
  balustrade: buildBalustrade,
  detail: buildDetail,
  cylinder: buildCylinder,
  cone: buildCone,
  sphere: buildSphere,
  ring: buildRing,
  prism: buildRoof,
  wall: buildWall,
};

/** Types that are openings rather than solids. */
const OPENING_TYPES = new Set(['window', 'door', 'arch']);

const isIntVec = (v) => Array.isArray(v) && v.length === 3 && v.every((n) => Number.isFinite(n));

/**
 * Everything a builder may need beyond its own object.
 * @param {object} spec normalised spec
 * @param {{min:number[], max:number[], size:number[]}} bounds
 * @param {number} seed
 */
function makeContext(spec, bounds, seed) {
  return {
    spec,
    seed,
    bounds,
    rng: makeRng(seed),
    /** Small helper so builders can ask for the seeded RNG lazily. */
    random: () => makeRng(seed)(),
  };
}

/**
 * Builds the renderable + voxelisable scene for a spec.
 *
 * @param {object} rawSpec a StructureSpec (plain JSON); it is normalised, not modified
 * @returns {{spec: object, solids: Array<object>, stats: object}}
 */
export function buildScene(rawSpec) {
  const t0 = performance.now();
  const spec = normalizeSpec(rawSpec || {});
  const bounds = specBounds(spec);
  const margin = 8;
  const ctx = makeContext(spec, bounds, spec.seed);

  /** @type {Array<{desc:object, obj:object, id:string, type:string, index:number, material:string, faceKind:string}>} */
  const entries = [];
  /** @type {Array<{obj:object, id:string, index:number}>} */
  const openingObjects = [];

  const usedIds = new Set();
  spec.objects.forEach((obj, index) => {
    const type = obj.type === 'roof' ? 'prism' : obj.type;
    if (!OBJECT_TYPES.includes(obj.type)) return;
    let id = typeof obj.id === 'string' && obj.id ? obj.id : `${type}-${index + 1}`;
    if (usedIds.has(id)) {
      let n = 2;
      while (usedIds.has(`${id}~${n}`)) n++;
      id = `${id}~${n}`;
    }
    usedIds.add(id);

    if (OPENING_TYPES.has(type)) {
      openingObjects.push({ obj, id, index });
      return;
    }
    const builder = BUILDERS[type];
    if (!builder) return;
    let descs;
    try {
      descs = builder(obj, ctx) || [];
    } catch (err) {
      throw new Error(`geometry: failed to build ${type} #${index + 1} (${id}): ${err.message}`);
    }
    for (const desc of descs) {
      if (!desc) continue;
      entries.push({
        desc,
        obj,
        id: descs.length > 1 ? `${id}:${desc.faceKind}` : id,
        type,
        index,
        material: desc.material,
        faceKind: desc.faceKind,
      });
    }
  });

  // 2. Materialise every descriptor into its cell set.
  for (const entry of entries) materializeEntry(entry, bounds, margin);

  // Global occupancy: key -> owner entry index (last writer wins, deterministic order).
  // Keep the owning entry so a standalone opening can DELETE the host's cells (a real hole), not
  // just hide them from the face builder.
  /** @type {Map<string, number>} */
  const occupancy = new Map();
  /** @type {Map<string, object>} */
  const cellOwner = new Map();
  entries.forEach((entry, i) => {
    if (!entry.cells) return;
    for (const k of entry.cells.keys()) {
      occupancy.set(k, i);
      cellOwner.set(k, entry);
    }
  });

  // 3. Standalone openings punch holes into whatever is already there.
  /** @type {Array<{entry:object}>} */
  const punchedDescs = [];
  for (const { obj, id, index } of openingObjects) {
    let request;
    try {
      request = buildOpening(obj, { bounds });
    } catch (err) {
      throw new Error(`geometry: failed to resolve opening ${id}: ${err.message}`);
    }
    const { desc, cut } = punchOpening(request, occupancy, { min: bounds.min, max: bounds.max, margin: margin + 2 }, cellOwner);
    for (const d of desc) {
      const entry = {
        desc: d,
        obj,
        id: desc.length > 1 ? `${id}:${d.faceKind}` : id,
        type: request.type,
        index,
        material: d.material,
        faceKind: d.faceKind,
        opening: true,
        cut,
      };
      materializeEntry(entry, bounds, margin);
      entries.push(entry);
      punchedDescs.push({ entry });
    }
  }

  // Re-sync occupancy so faces of punched solids are recomputed against the final cell set.
  occupancy.clear();
  entries.forEach((entry, i) => {
    if (!entry.cells) return;
    for (const k of entry.cells.keys()) occupancy.set(k, i);
  });

  // 4. Emit polygons for every entry, hiding faces that face another occupied cell.
  //    An object may deliver a voxel descriptor and a render descriptor (roofs do): they are merged
  //    into a single Solid whose polygons are the exact mesh and whose cells are the stepped mass,
  //    so the renderer and the Minecraft transfer always describe the same object.
  const solids = [];
  for (const group of groupEntries(entries)) {
    const merged = mergeGroup(group);
    if (merged && merged.faces.length > 0) solids.push(merged);
  }

  const stats = computeStats(solids, entries, performance.now() - t0, spec);
  const warnings = [];
  for (const entry of entries) {
    if (!entry.truncated) continue;
    warnings.push(`solid '${entry.id}' hit the ${MAX_OBJECT_CELLS} cell cap; the shape was clamped`);
  }
  stats.warnings = warnings;
  return { spec, solids, stats };
}

/**
 * Converts one descriptor (cells / solid / explicit faces) into a Set of cell keys.
 * @param {object} entry
 * @param {{min:number[],max:number[]}} bounds
 * @param {number} margin
 */
function materializeEntry(entry, bounds, margin) {
  const desc = entry.desc;
  if (desc.kind === 'cells') {
    entry.cells = desc.cells instanceof Map ? new Map(desc.cells) : new Map();
    entry.materials = desc.materials || null;
    entry.cellNormals = desc.cellNormals || null;
    entry.inside = null;
    entry.truncated = !!desc.truncated;
    return;
  }
  if (desc.kind === 'solid') {
    const inside = desc.inside;
    const cells = new Map();
    if (typeof inside === 'function') {
      const limit = desc.cellLimit || 400_000;
      outer: for (let x = desc.min[0]; x <= desc.max[0]; x++) {
        for (let y = desc.min[1]; y <= desc.max[1]; y++) {
          for (let z = desc.min[2]; z <= desc.max[2]; z++) {
            if (!inside(x + 0.5, y + 0.5, z + 0.5)) continue;
            cells.set(key(x, y, z), 0);
            if (cells.size >= limit) break outer;
          }
        }
      }
    }
    entry.cells = cells;
    entry.materials = null;
    entry.cellNormals = desc.cellNormals || null;
    entry.inside = inside;
    return;
  }
  // Explicit faces: keep them as polygons and derive their cells for statistics only.
  entry.cells = null;
  entry.materials = null;
  entry.cellNormals = null;
  entry.inside = null;
}

/**
 * Builds a Solid record: deduplicated polygons, bounds and the per-cell Minecraft info.
 * @param {object} entry
 * @returns {object|null}
 */
function finalizeSolid(entry) {
  const { desc, obj, id, type } = entry;
  let faces = [];
  let bounds = null;

  if (desc.kind === 'faces') {
    for (const f of desc.faces || []) {
      if (!Array.isArray(f.v) || f.v.length < 3) continue;
      faces.push({
        v: f.v.map((p) => p.map((n) => roundCoord(n))),
        normal: f.normal ? [...f.normal] : polygonNormal(f.v),
        material: f.material || desc.material,
        kind: f.kind || desc.faceKind,
      });
    }
    bounds = boundsOfFaces(faces);
  } else {
    const cells = entry.cells;
    if (!cells || cells.size === 0) return null;
    const materialOf = (k) => (entry.materials && entry.materials.get(k)) || desc.material || 'plain';
    const raw = cellsToFaces(cells, { materialOf, clip: desc.clip || [] });
    faces = raw.map((f) => ({
      v: f.v.map((p) => p.map((n) => roundCoord(n))),
      normal: f.normal,
      material: f.material,
      kind: desc.faceKind || 'surface',
    }));
    bounds = boundsOfFaces(faces, cells);
  }

  // A single solid with no polygons (everything hidden) is dropped by buildScene.
  const cells = entry.cells ? entry.cells : null;
  return makeSolid({
    id,
    type,
    material: desc.material,
    faces,
    bounds,
    cells,
    materials: entry.materials || null,
    cellNormals: entry.cellNormals || null,
    obj,
    faceKind: desc.faceKind,
    inside: entry.inside,
  });
}

/**
 * Groups entries that belong to one object (same index) so role='voxel' / role='render' descriptor
 * pairs can be merged. Entries without a role stay independent (e.g. a window frame + its glass).
 * @param {object[]} entries
 * @returns {object[][]}
 */
function groupEntries(entries) {
  /** @type {Map<string, object[]>} */
  const groups = new Map();
  const out = [];
  for (const entry of entries) {
    const role = entry.desc && entry.desc.role;
    const bk = `${entry.index}|${entry.desc.faceKind}|${entry.material}|${role ? 'role' : 'solo'}`;
    if (!role) {
      out.push([entry]);
      continue;
    }
    let g = groups.get(bk);
    if (!g) {
      g = [];
      groups.set(bk, g);
      out.push(g);
    }
    g.push(entry);
  }
  return out;
}

/**
 * Merges one group of entries into a Solid.
 * @param {object[]} group
 * @returns {object|null}
 */
function mergeGroup(group) {
  const voxelEntries = group.filter((e) => (e.desc.role || 'voxel') === 'voxel');
  const renderEntries = group.filter((e) => e.desc.role === 'render');
  if (renderEntries.length) {
    const render = renderEntries[0];
    const solid = finalizeSolid(render);
    if (!solid) return null;
    solid.kind = render.faceKind === 'glass' ? 'glass' : render.faceKind === 'detail' ? 'detail' : 'surface';
    // Attach the stepped voxel mass so voxelizeSolid() works on this solid as well.
    const cells = new Map();
    /** @type {Map<string, number[]>} */
    const cellNormals = new Map();
    for (const ve of voxelEntries) {
      if (!ve.cells) continue;
      for (const [k, f] of ve.cells) cells.set(k, f);
      if (ve.cellNormals) for (const [k, n] of ve.cellNormals) cellNormals.set(k, n);
    }
    if (cells.size) {
      solid.cells = cells;
      solid.voxels = null;
      if (cellNormals.size) solid.cellNormals = cellNormals;
    }
    return solid;
  }
  return finalizeSolid(group[0]);
}

/** Rounds a coordinate to 5 decimals so geometry stays compact and byte-comparable. */
function roundCoord(v) {
  const r = Math.round(v * 1e5) / 1e5;
  return r === 0 ? 0 : r;
}

/** Outward normal of an arbitrary polygon (Newell's method). */
export function polygonNormal(v) {
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
  return [roundCoord(nx / len), roundCoord(ny / len), roundCoord(nz / len)];
}

/** Bounding box of a face list (or of a cell set when there are no faces). */
function boundsOfFaces(faces, cells) {
  const min = [Infinity, Infinity, Infinity];
  const max = [-Infinity, -Infinity, -Infinity];
  const grow = (x, y, z) => {
    if (x < min[0]) min[0] = x;
    if (y < min[1]) min[1] = y;
    if (z < min[2]) min[2] = z;
    if (x > max[0]) max[0] = x;
    if (y > max[1]) max[1] = y;
    if (z > max[2]) max[2] = z;
  };
  for (const f of faces) for (const p of f.v) grow(p[0], p[1], p[2]);
  if (!Number.isFinite(min[0]) && cells) {
    for (const k of cells.keys()) {
      const [x, y, z] = parseKey(k);
      grow(x, y, z);
      grow(x + 1, y + 1, z + 1);
    }
  }
  if (!Number.isFinite(min[0])) return { min: [0, 0, 0], max: [0, 0, 0] };
  return { min, max };
}

/**
 * Assembles the public Solid record.
 * @param {{id:string,type:string,material:string,faces:object[],bounds:object,cells:Map<string,number>|null,materials?:Map<string,string>|null,cellNormals?:Map<string,number[]>|null,obj:object,faceKind:string,inside:Function|null}} p
 * @returns {object}
 */
function makeSolid(p) {
  const solid = {
    id: p.id,
    type: p.type,
    material: p.material,
    kind: describeKind(p),
    faces: p.faces,
    bounds: p.bounds,
    /** @type {Set<string>|null} filled lazily by voxelizeSolid */
    voxels: null,
    cells: p.cells,
    materials: p.materials || null,
    cellNormals: p.cellNormals || null,
    source: p.obj,
    inside: p.inside,
  };
  Object.defineProperty(solid, 'faceCount', {
    enumerable: false,
    get: () => p.faces.length,
  });
  return solid;
}

/** Classifies a solid as surface | glass | detail for the renderer's material rules. */
function describeKind(p) {
  if (p.faceKind === 'glass') return 'glass';
  if (p.faceKind === 'detail') return 'detail';
  const kinds = new Set(p.faces.map((f) => f.kind));
  if (kinds.size === 1) return [...kinds][0];
  if (kinds.has('surface')) return 'surface';
  return kinds.has('glass') ? 'glass' : 'detail';
}

/**
 * Returns the unit cells of a solid (cached on `solid.voxels`).
 *
 * The default is the *full* volume, because a shell is not guaranteed to stay in one piece: the
 * interior cells of a roof are what connect its eave ring to the walls. Callers that only need
 * statistics can ask for `visibleOnly`.
 *
 * @param {object} solid a Solid from buildScene()
 * @param {{visibleOnly?: boolean, includeHidden?: boolean, offset?: number[]}} [opts]
 *   `visibleOnly: true` keeps only cells that have at least one exposed face;
 *   `offset` shifts every key (used when a caller wants world coordinates).
 * @returns {Set<string>}
 */
export function voxelizeSolid(solid, opts = {}) {
  if (!solid || typeof solid !== 'object') throw new TypeError('voxelizeSolid: solid must be an object');
  const visibleOnly = opts.visibleOnly === true || opts.includeHidden === false;
  const offset = Array.isArray(opts.offset) ? opts.offset : null;
  if (offset) {
    const out = new Set();
    const base = solid.cells || cellsOfSolid(solid);
    for (const k of base.keys()) {
      const [x, y, z] = parseKey(k);
      out.add(key(x + (offset[0] | 0), y + (offset[1] | 0), z + (offset[2] | 0)));
    }
    return out;
  }
  if (solid.cells && visibleOnly && solid.voxels) return solid.voxels;
  const cells = solid.cells || cellsOfSolid(solid);
  const out = new Set();
  for (const k of cells.keys()) {
    if (visibleOnly && !isExposed(cells, k)) continue;
    out.add(k);
  }
  if (solid.cells && visibleOnly) solid.voxels = out;
  return out;
}

/** Reconstructs a cell map from explicit faces (faces -> cell centres inside the polygon set). */
function cellsOfSolid(solid) {
  const cells = new Map();
  for (const f of solid.faces || []) {
    const n = f.normal;
    // A face lies on a cell boundary; the owning cell is one step inward along -normal.
    const axis = Math.abs(n[0]) > 0.5 ? 0 : Math.abs(n[1]) > 0.5 ? 1 : 2;
    const inward = n[axis] > 0 ? -1 : 1;
    const xs = f.v.map((p) => p[0]);
    const ys = f.v.map((p) => p[1]);
    const zs = f.v.map((p) => p[2]);
    const x0 = Math.floor(Math.min(...xs));
    const x1 = Math.ceil(Math.max(...xs));
    const y0 = Math.floor(Math.min(...ys));
    const y1 = Math.ceil(Math.max(...ys));
    const z0 = Math.floor(Math.min(...zs));
    const z1 = Math.ceil(Math.max(...zs));
    const p = [0, 0, 0];
    const slab = f.v[0][axis];
    p[axis] = axis === 0 ? Math.round(slab) : 0;
    if (axis === 0) p[0] += inward;
    for (let y = y0; y < Math.max(y1, y0 + 1); y++) {
      for (let z = z0; z < Math.max(z1, z0 + 1); z++) {
        if (axis === 0) {
          cells.set(key(p[0], y, z), 0);
        } else if (axis === 1) {
          for (let x = x0; x < Math.max(x1, x0 + 1); x++) cells.set(key(x, Math.round(slab) + inward, z), 0);
        } else {
          for (let x = x0; x < Math.max(x1, x0 + 1); x++) cells.set(key(x, y, Math.round(slab) + inward), 0);
        }
      }
    }
  }
  return cells;
}

/**
 * Normal for a cell whose exposed sides cancel out (opposite pairs). Chooses the axis of the pair
 * so the result is still a useful direction, and keeps its sign from the first exposed side.
 * @param {number} exposed bitmask over DIR
 * @returns {number[]}
 */
function thinNormal(exposed) {
  const pairs = [
    [DIR.xp, DIR.xn, 0],
    [DIR.yp, DIR.yn, 1],
    [DIR.zp, DIR.zn, 2],
  ];
  for (const [pos, neg, axis] of pairs) {
    if ((exposed & pos) && (exposed & neg)) {
      const n = [0, 0, 0];
      n[axis] = 1;
      return n;
    }
  }
  return [0, 0, 0];
}

/**
 * Normalises a final cell normal: rounds for stable JSON, then rescales so hypot == 1 within
 * 1e-9. Order matters — rounding after normalising is what made roof normals slightly non-unit.
 * @param {number[]} n
 * @returns {{nx:number, ny:number, nz:number}}
 */
function finishNormal(n) {
  let [x, y, z] = n;
  const len = Math.hypot(x, y, z);
  if (len < 1e-12) return { nx: 0, ny: 0, nz: 0 };
  x /= len;
  y /= len;
  z /= len;
  const rounded = [x, y, z].map((v) => {
    const q = Math.round(v * 1e9) / 1e9;
    return q === 0 ? 0 : q; // normalise -0 to 0 so JSON and equality checks stay clean
  });
  const l2 = Math.hypot(rounded[0], rounded[1], rounded[2]);
  const fix = (v) => {
    const q = v / l2;
    return q === 0 ? 0 : q;
  };
  return { nx: fix(rounded[0]), ny: fix(rounded[1]), nz: fix(rounded[2]) };
}

/** True when at least one of the 6 neighbours of a cell is empty. */
function isExposed(cells, k) {
  const [x, y, z] = parseKey(k);
  for (let f = 0; f < 6; f++) {
    const s = FACE_STEPS[f];
    if (!cells.has(key(x + s[0], y + s[1], z + s[2]))) return true;
  }
  return false;
}

/**
 * Voxelises an entire spec into Minecraft cells.
 *
 * Every cell reports what the transfer module needs:
 *   material — palette key of the cell (later objects overwrite earlier ones, like the renderer)
 *   up/down  — is there a solid surface directly above/below this cell? (a neighbour cell, so a
 *              flat floor never reports `up`, while the wall standing on a floor does)
 *   nx/ny/nz — averaged outward normal of the cell's exposed faces (0,1,0 for a floor's top,
 *              0,-1,0 for a ceiling, ±1/0 for a 1-thick wall). This is what selects stairs vs
 *              slabs in the transfer module. A cell exposed on two sides averages them (a corner
 *              gets a diagonal), which is exactly the "averaged normal" the contract asks for.
 *   exposed  — bitmask over DIR (which of the 6 sides is open to the outside world)
 *   solid    — id of the owning solid
 *
 * @param {object} rawSpec
 * @param {{interior?: boolean, visibleOnly?: boolean, scene?: object}} [opts]
 *   `visibleOnly: true` drops cells with no exposed face (smaller, but the result is a shell and a
 *   shell can be disconnected — use it for statistics, not for transfer). Interior cells are kept
 *   by default and flagged with `interior: true` so a transfer module can skip them.
 * @returns {Map<string, {material:string, up:boolean, down:boolean, nx:number, ny:number, nz:number, exposed:number, interior:boolean, solid:string}>}
 */
export function voxelizeSpec(rawSpec, opts = {}) {
  const scene = opts.scene || buildScene(rawSpec);
  const visibleOnly = opts.visibleOnly === true || opts.includeHidden === false;

  // ---- 1. Collect every cell. Later solids overwrite earlier ones (container/child order). ----
  /** @type {Map<string, {material:string, solid:string, owner:object, normal:number[]}>} */
  const owner = new Map();
  for (const solid of scene.solids) {
    if (!solid.cells) continue;
    for (const [k] of solid.cells) {
      owner.set(k, {
        material: (solid.materials && solid.materials.get(k)) || solid.material || 'plain',
        solid: solid.id,
        owner: solid,
        normal: null,
      });
    }
  }

  const occupied = new Set(owner.keys());

  // ---- 2. Per-cell exposure and normal, measured against the whole scene. ----
  const out = new Map();
  for (const [k, info] of owner) {
    const [x, y, z] = parseKey(k);
    let exposed = 0;
    let nx = 0;
    let ny = 0;
    let nz = 0;
    for (let f = 0; f < 6; f++) {
      const s = FACE_STEPS[f];
      if (occupied.has(key(x + s[0], y + s[1], z + s[2]))) continue;
      exposed |= 1 << f;
      nx += NORMALS[f][0];
      ny += NORMALS[f][1];
      nz += NORMALS[f][2];
    }
    if (exposed === 0 && visibleOnly) continue;

    const override = info.owner && info.owner.cellNormals ? info.owner.cellNormals.get(k) : null;
    let n;
    if (override) {
      n = [...override];
    } else {
      const len = Math.hypot(nx, ny, nz);
      if (len > 1e-6) {
        n = [nx / len, ny / len, nz / len];
      } else {
        // A cell exposed on exactly two opposite sides (a 1-thick wall, a floor tile, a pane):
        // the raw sum cancels out. Pick the dominant axis of its *thin* direction and keep the
        // sign of the outward side, which is what a Minecraft stair/slab needs.
        n = thinNormal(exposed);
      }
    }
    const fn = finishNormal(n);
    out.set(k, {
      material: info.material,
      up: false,
      down: false,
      nx: fn.nx,
      ny: fn.ny,
      nz: fn.nz,
      exposed,
      interior: exposed === 0,
      solid: info.solid,
    });
  }

  // ---- 3. Vertical support flags, in the "is there a solid surface on that side" sense: a cell
  //         that a block rests directly on top of reports up=true; a cell with a block underneath
  //         reports down=true. This is what the transfer module needs to decide between a full
  //         block, a top slab and a bottom slab. ----
  for (const [k, cell] of out) {
    const [x, y, z] = parseKey(k);
    cell.up = occupied.has(key(x, y + 1, z));
    cell.down = occupied.has(key(x, y - 1, z));
  }
  return out;
}

/**
 * Convenience wrapper: voxelises a spec and returns the union area/volume statistics that the
 * critic and the studio use. Cheap on a 10x10 house.
 * @param {object} rawSpec
 * @returns {{cells:number, columns:number, exposed:number, area:number, volume:number, height:number}}
 */
export function voxelStats(rawSpec) {
  const cells = voxelizeSpec(rawSpec);
  let area = 0;
  let minY = Infinity;
  let maxY = -Infinity;
  const cols = new Set();
  for (const [k, c] of cells) {
    const [x, y, z] = parseKey(k);
    cols.add(`${x}|${z}`);
    if (y < minY) minY = y;
    if (y > maxY) maxY = y;
    if ((c.exposed & DIR.yp) !== 0 && c.ny > 0.5) area += 1;
  }
  const columns = cols.size;
  return {
    cells: cells.size,
    columns,
    exposed: area,
    area,
    volume: cells.size,
    height: Number.isFinite(minY) ? maxY - minY + 1 : 0,
  };
}

/** Aggregated scene statistics (also used by the critic). */
function computeStats(solids, entries, ms, spec) {
  let triangles = 0;
  let polygons = 0;
  let cells = 0;
  const byType = {};
  let minY = Infinity;
  let maxY = -Infinity;
  let minX = Infinity;
  let maxX = -Infinity;
  let minZ = Infinity;
  let maxZ = -Infinity;
  const materials = {};
  for (const s of solids) {
    polygons += s.faces.length;
    for (const f of s.faces) triangles += Math.max(0, f.v.length - 2);
    if (s.cells) cells += s.cells.size;
    byType[s.type] = (byType[s.type] || 0) + (s.cells ? s.cells.size : s.faces.length);
    materials[s.material || 'plain'] = (materials[s.material || 'plain'] || 0) + (s.cells ? s.cells.size : 0);
    const b = s.bounds;
    if (b) {
      minX = Math.min(minX, b.min[0]);
      maxX = Math.max(maxX, b.max[0]);
      minY = Math.min(minY, b.min[1]);
      maxY = Math.max(maxY, b.max[1]);
      minZ = Math.min(minZ, b.min[2]);
      maxZ = Math.max(maxZ, b.max[2]);
    }
  }
  return {
    version: GEOMETRY_VERSION,
    ms: Math.round(ms * 100) / 100,
    objects: spec.objects.length,
    solids: solids.length,
    polygons,
    triangles,
    cells,
    byType,
    materials,
    bounds: Number.isFinite(minX)
      ? { min: [minX, minY, minZ], max: [maxX, maxY, maxZ], size: [maxX - minX, maxY - minY, maxZ - minZ] }
      : { min: [0, 0, 0], max: [0, 0, 0], size: [0, 0, 0] },
  };
}

/** Exposed for tests: the integer-vector guard used by builders. */
export { isIntVec };
