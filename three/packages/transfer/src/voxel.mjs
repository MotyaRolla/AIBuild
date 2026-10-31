/**
 * Getting from a `StructureSpec` to `Map<key, VoxelCell>`.
 *
 * The contract (AGENTS.md, joint A -> C) says task A owns `packages/core/src/geometry.mjs` and its
 * `voxelizeSpec(spec)`. That module is written in parallel, so this file must never hard-depend on
 * it: transfer has to keep working (and its tests keep passing) while geometry is in flux. Two
 * layers solve that:
 *
 * 1. `loadCoreVoxelizer()` — a guarded load of core's `voxelizeSpec`, used when present and
 *    well-formed. Any failure (missing file, syntax error from an in-progress edit, wrong output
 *    shape) falls through to layer 2 instead of throwing.
 * 2. `fallbackVoxelize(spec)` — a small but complete voxeliser for the axis-aligned primitives
 *    (boxes with hollow/open/openings, floors, beams, columns, chimneys, rings, stair flights,
 *    straight walls, balustrades, detail clusters, gable/shed/hip/pyramid roofs, and the curved
 *    cylinder/cone/sphere). It is deliberately blocky: its job is to unblock transfer, not to be
 *    beautiful.
 *
 * Every returned cell has the contract shape:
 *
 *   { material, up, down, nx, ny, nz, exposed, interior, solid }
 *
 * `up`/`down` mean "a solid cell sits directly above/below", `nx/ny/nz` is the averaged outward
 * normal of the exposed faces (the signal that chooses stairs vs slab vs full block) and `exposed`
 * is a bitmask over the six directions, matching core's `DIR`.
 */

import { createRequire } from 'node:module';
import { normalizeSpec } from '../../core/src/spec.mjs';
import { key, parseKey, FACE_STEPS, FACE_NORMALS } from './grid.mjs';

/** Exposure bits, matching core's `DIR` so both modules agree. */
export const DIR = Object.freeze({ xp: 1, xn: 2, yp: 4, yn: 8, zp: 16, zn: 32 });

/**
 * A cell with no exposed face is interior: it is dropped from the block map but still counts as
 * support for its neighbours, so `up`/`down` are computed against the *full* cell set.
 * @typedef {{
 *   material:string, up:boolean, down:boolean, nx:number, ny:number, nz:number,
 *   exposed:number, interior:boolean, solid:string, source?:string
 * }} VoxelCell
 */

/* ------------------------------------------------------------------------------------------ */
/* Draft cell map used by the fallback voxeliser                                               */
/* ------------------------------------------------------------------------------------------ */

/** Rounds a normal to a stable precision so two runs compare exactly. */
function round6(v) {
  const r = Math.round(v * 1e6) / 1e6;
  return Object.is(r, -0) ? 0 : r;
}

/** Normalises `[a, b]` so a <= b on every axis. */
function orderBox(a, b) {
  return [
    [Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2])],
    [Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2])],
  ];
}

/**
 * A mutable cell map used while building. Later writes overwrite earlier ones, which is how a
 * window frame painted after a wall wins without a z-order pass.
 */
class CellDraft {
  constructor() {
    /** @type {Map<string, {material:string, solid:string}>} */
    this.cells = new Map();
  }

  /**
   * @param {number} x @param {number} y @param {number} z
   * @param {string} material palette key
   * @param {string} solid owner id
   */
  put(x, y, z, material, solid) {
    this.cells.set(key(x, y, z), { material, solid });
  }

  /** @param {number} x @param {number} y @param {number} z */
  has(x, y, z) {
    return this.cells.has(key(x, y, z));
  }

  /** @param {number} x @param {number} y @param {number} z */
  delete(x, y, z) {
    return this.cells.delete(key(x, y, z));
  }
}

/**
 * Cuts a `{wall, at:[u,y], w, h, kind}` opening through a box, all the way through its thickness,
 * so the hole is really open rather than drawn on top.
 * @param {CellDraft} draft
 * @param {number[]} lo
 * @param {number[]} hi
 * @param {object} opening
 * @returns {number} removed cells
 */
function cutOpening(draft, lo, hi, opening) {
  const wall = opening.wall;
  const at = Array.isArray(opening.at) ? opening.at : [0, 0];
  const u0 = Math.round(at[0]);
  const y0 = Math.round(at[1]);
  const w = Math.max(1, Math.round(opening.w ?? 1));
  const h = Math.max(1, Math.round(opening.h ?? 2));
  const kinds = new Set(['window', 'door', 'arch']);
  if (opening.kind && !kinds.has(opening.kind)) return 0;
  if (wall !== 'north' && wall !== 'south' && wall !== 'east' && wall !== 'west') return 0;
  let removed = 0;
  for (let u = u0; u < u0 + w; u++) {
    for (let y = y0; y < y0 + h; y++) {
      // A crude arch: drop the two top corners so the lintel reads as curved.
      if (opening.kind === 'arch' && y === y0 + h - 1 && w >= 3 && (u === u0 || u === u0 + w - 1)) continue;
      if (wall === 'north' || wall === 'south') {
        if (u < lo[0] || u > hi[0] || y < lo[1] || y > hi[1]) continue;
        for (let zz = lo[2]; zz <= hi[2]; zz++) if (draft.delete(u, y, zz)) removed++;
      } else {
        if (u < lo[2] || u > hi[2] || y < lo[1] || y > hi[1]) continue;
        for (let xx = lo[0]; xx <= hi[0]; xx++) if (draft.delete(xx, y, u)) removed++;
      }
    }
  }
  return removed;
}

/**
 * Fills a box, optionally as a shell, optionally omitting whole faces.
 * @param {CellDraft} draft
 * @param {number[]} lo
 * @param {number[]} hi
 * @param {{material:string, solid:string, hollow?:boolean, open?:Set<string>, openings?:object[]}} opts
 */
function fillBox(draft, lo, hi, opts) {
  const { material, solid } = opts;
  const hollow = opts.hollow === true;
  const open = opts.open || new Set();
  const [x0, y0, z0] = lo;
  const [x1, y1, z1] = hi;
  // A face is omitted only when the cell is on *that* face of the box, not whenever its coordinate
  // happens to match. `open: ['up']` on a hollow box removes the roof ring (y == y1) but must leave
  // the side walls standing up to it, otherwise the box is cut in half at that height.
  for (let x = x0; x <= x1; x++) {
    for (let y = y0; y <= y1; y++) {
      for (let z = z0; z <= z1; z++) {
        const onBottom = y === y0;
        const onTop = y === y1;
        const onNorth = z === z0;
        const onSouth = z === z1;
        const onWest = x === x0;
        const onEast = x === x1;
        const onShell = onBottom || onTop || onNorth || onSouth || onWest || onEast;
        if (hollow && !onShell) continue;
        // `open` omits a whole face, but only where that face is all there is. A cell on the bottom
        // *and* on the south side is a wall corner: it must stay when only `down` is open,
        // otherwise "hollow box open top and bottom" would lose its walls and float.
        const faces = [];
        if (onBottom) faces.push('down');
        if (onTop) faces.push('up');
        if (onNorth) faces.push('north');
        if (onSouth) faces.push('south');
        if (onWest) faces.push('west');
        if (onEast) faces.push('east');
        if (!hollow) {
          // A solid box has no interior: every cell is on at least one face, so "open" can only
          // mean "omit the cells of that face". A 3x3x1 slab with `open: ['up']` keeps its floor.
          const onlyOnOpenedFaces = faces.every((face) => open.has(face));
          if (onlyOnOpenedFaces && faces.length) continue;
        } else if (faces.every((face) => open.has(face))) {
          continue;
        }
        draft.put(x, y, z, material, solid);
      }
    }
  }
  for (const opening of opts.openings || []) cutOpening(draft, lo, hi, opening);
}

/* ------------------------------------------------------------------------------------------ */
/* Roofs                                                                                       */
/* ------------------------------------------------------------------------------------------ */

/**
 * Height of a roof surface above `baseY` at a footprint position.
 * @param {'gable'|'shed'|'hip'|'pyramid'} style
 * @param {number} rise maximum roof height in blocks
 * @param {number} cross distance (in blocks) from the ridge line / high edge
 * @param {number} along distance (in blocks) from the nearest gable end, Infinity when not hipped
 * @param {number} pitch rise per block of run
 * @returns {number} number of blocks to fill above the base layer
 */
function roofHeight(style, rise, cross, along, pitch) {
  const drop = Math.round(cross / pitch);
  if (style === 'shed') return Math.max(0, rise - drop);
  if (style === 'pyramid') {
    const d = Math.max(cross, Number.isFinite(along) ? along : 0);
    return Math.max(0, rise - Math.round(d / pitch));
  }
  if (style === 'hip') {
    // A hip roof is a gable whose ends are also cut back, so both distances taper it.
    const d = Number.isFinite(along) ? Math.min(cross, along) : cross;
    return Math.max(0, rise - Math.round(d / pitch));
  }
  return Math.max(0, rise - drop); // gable
}

/**
 * Adds a roof prism over a footprint. The volume is filled solid from the wall top to the roof
 * surface, which keeps the shell sealed and the gable ends closed.
 *
 * @param {CellDraft} draft
 * @param {object} obj normalised `prism`/`roof` object
 * @param {string} material
 * @param {string} solid
 */
function addRoof(draft, obj, material, solid) {
  const box = orderBox(obj.from.map(Math.round), obj.to.map(Math.round));
  const [lo, hi] = box;
  const overhang = Math.max(0, Math.round(obj.overhang ?? 0));
  const x0 = lo[0] - overhang;
  const x1 = hi[0] + overhang;
  const z0 = lo[2] - overhang;
  const z1 = hi[2] + overhang;
  const baseY = hi[1];
  const spanX = x1 - x0;
  const spanZ = z1 - z0;
  const ridge = obj.ridge || (spanX >= spanZ ? 'x' : 'z');
  const style = ['gable', 'shed', 'hip', 'pyramid'].includes(obj.style) ? obj.style : 'gable';
  const pitch = Number.isFinite(obj.pitch) && obj.pitch > 0 ? Number(obj.pitch) : 1;
  // A ridge axis of x means slopes fall towards +/-z, so the *cross* axis is z.
  const crossLo = ridge === 'x' ? z0 : x0;
  const crossHi = ridge === 'x' ? z1 : x1;
  const ridgeCenter = (crossLo + crossHi) / 2;
  const alongLo = ridge === 'x' ? x0 : z0;
  const alongHi = ridge === 'x' ? x1 : z1;
  const halfSpan = Math.max(1, (crossHi - crossLo + 1) / 2);
  const rise = Math.max(1, Math.ceil(halfSpan / pitch));

  for (let c = crossLo; c <= crossHi; c++) {
    const cross = style === 'shed' ? c - crossLo : Math.abs(c - ridgeCenter);
    for (let a = alongLo; a <= alongHi; a++) {
      const along =
        style === 'hip' || style === 'pyramid'
          ? Math.min(a - alongLo, alongHi - a)
          : Infinity;
      const top = baseY + roofHeight(style, rise, cross, along, pitch);
      for (let y = baseY; y <= top; y++) {
        if (ridge === 'x') draft.put(a, y, c, material, solid);
        else draft.put(c, y, a, material, solid);
      }
    }
  }
}

/* ------------------------------------------------------------------------------------------ */
/* Fallback voxeliser                                                                          */
/* ------------------------------------------------------------------------------------------ */

/**
 * Voxelises one normalised spec object into `draft`.
 * @param {CellDraft} draft
 * @param {object} obj
 * @param {number} index
 * @returns {string|null} owner id, or null when the object produced nothing
 */
function voxelizeObject(draft, obj, index) {
  const idBase = typeof obj.id === 'string' && obj.id ? obj.id : `${obj.type}-${index + 1}`;
  const material = typeof obj.material === 'string' && obj.material ? obj.material : 'plain';
  const solid = `${idBase}:${material}`;
  const open = new Set(Array.isArray(obj.open) ? obj.open : []);
  const box = () =>
    Array.isArray(obj.from) && Array.isArray(obj.to)
      ? orderBox(obj.from.map(Math.round), obj.to.map(Math.round))
      : null;

  switch (obj.type) {
    case 'box': {
      const b = box();
      if (!b) return null;
      fillBox(draft, b[0], b[1], {
        material,
        solid,
        hollow: obj.hollow === true,
        open,
        openings: obj.openings || [],
      });
      return idBase;
    }

    case 'floor': {
      const b = box();
      if (!b) return null;
      const thickness = Math.max(1, Math.round(obj.thickness ?? 1));
      const [lo, hi] = b;
      fillBox(draft, lo, [hi[0], Math.min(hi[1], lo[1] + thickness - 1), hi[2]], { material, solid });
      return idBase;
    }

    case 'beam': {
      const b = box();
      if (!b) return null;
      const thickness = Math.max(1, Math.round(obj.thickness ?? 1));
      const [lo, hi] = b;
      // A beam is thin on its two short axes: shrink the longer horizontal axis to `thickness`.
      const axis = Math.abs(hi[0] - lo[0]) >= Math.abs(hi[2] - lo[2]) ? 0 : 2;
      const mid = Math.round((lo[axis] + hi[axis]) / 2);
      const half = Math.floor(thickness / 2);
      const shrunkLo = [...lo];
      const shrunkHi = [...hi];
      shrunkLo[axis] = mid - half;
      shrunkHi[axis] = mid - half + thickness - 1;
      fillBox(draft, shrunkLo, shrunkHi, { material, solid });
      return idBase;
    }

    case 'wall': {
      const b = box();
      if (!b) return null;
      const height = Math.max(1, Math.round(obj.height ?? 3));
      const [lo, hi] = b;
      fillBox(draft, lo, [hi[0], Math.min(hi[1], lo[1] + height - 1), hi[2]], {
        material,
        solid,
        openings: obj.openings || [],
      });
      return idBase;
    }

    case 'column': {
      if (!Array.isArray(obj.at)) return null;
      const at = obj.at.map(Math.round);
      const height = Math.max(1, Math.round(obj.height ?? 4));
      const radius = Number.isFinite(obj.radius) ? Math.max(0, Math.round(obj.radius)) : 0;
      for (let y = at[1]; y < at[1] + height; y++) {
        for (let dx = -radius; dx <= radius; dx++) {
          for (let dz = -radius; dz <= radius; dz++) {
            if (dx * dx + dz * dz > radius * radius) continue;
            draft.put(at[0] + dx, y, at[2] + dz, material, solid);
          }
        }
      }
      return idBase;
    }

    case 'chimney': {
      if (!Array.isArray(obj.at)) return null;
      const at = obj.at.map(Math.round);
      const w = Math.max(1, Math.round(obj.w ?? 1));
      const d = Math.max(1, Math.round(obj.d ?? 1));
      const height = Math.max(1, Math.round(obj.height ?? 4));
      fillBox(draft, [at[0], at[1], at[2]], [at[0] + w - 1, at[1] + height - 1, at[2] + d - 1], {
        material,
        solid,
      });
      if (obj.cap) {
        fillBox(
          draft,
          [at[0] - 1, at[1] + height, at[2] - 1],
          [at[0] + w, at[1] + height, at[2] + d],
          { material, solid: `${idBase}:cap` },
        );
      }
      return idBase;
    }

    case 'ring': {
      const b = box();
      if (!b) return null;
      const [lo, hi] = b;
      const y = Number.isFinite(obj.y) ? Math.round(obj.y) : lo[1];
      const thickness = Math.max(1, Math.round(obj.thickness ?? 1));
      for (let layer = 0; layer < thickness; layer++) {
        const yy = y + layer;
        for (let x = lo[0]; x <= hi[0]; x++) {
          for (let z = lo[2]; z <= hi[2]; z++) {
            if (x === lo[0] || x === hi[0] || z === lo[2] || z === hi[2]) {
              draft.put(x, yy, z, material, solid);
            }
          }
        }
      }
      return idBase;
    }

    case 'detail': {
      const cells = Array.isArray(obj.cells) ? obj.cells : [];
      const mats = Array.isArray(obj.material) ? obj.material : null;
      cells.forEach((cell, i) => {
        if (!Array.isArray(cell) || cell.length < 3) return;
        const mat = mats ? mats[i % mats.length] : material;
        draft.put(Math.round(cell[0]), Math.round(cell[1]), Math.round(cell[2]), mat, `${idBase}:${mat}`);
      });
      return idBase;
    }

    case 'stairs': {
      const b = box();
      if (!b) return null;
      const [lo, hi] = b;
      const axis = obj.axis === 'x' ? 0 : 2;
      const back = obj.direction === 'back';
      const runLo = axis === 0 ? lo[0] : lo[2];
      const runHi = axis === 0 ? hi[0] : hi[2];
      const length = runHi - runLo + 1;
      const totalRise = hi[1] - lo[1] + 1;
      const step = Math.max(1, Math.ceil(totalRise / Math.max(1, length)));
      const crossLo = axis === 0 ? lo[2] : lo[0];
      const crossHi = axis === 0 ? hi[2] : hi[0];
      for (let u = runLo; u <= runHi; u++) {
        const stepIndex = back ? runHi - u : u - runLo;
        const top = Math.min(hi[1], lo[1] + (stepIndex + 1) * step - 1);
        for (let y = lo[1]; y <= Math.max(lo[1], top); y++) {
          for (let c = crossLo; c <= crossHi; c++) {
            if (axis === 0) draft.put(u, y, c, material, solid);
            else draft.put(c, y, u, material, solid);
          }
        }
      }
      return idBase;
    }

    case 'prism': {
      if (!Array.isArray(obj.from) || !Array.isArray(obj.to)) return null;
      addRoof(draft, obj, material, solid);
      return idBase;
    }

    case 'cylinder': {
      if (!Array.isArray(obj.center)) return null;
      const center = obj.center.map(Math.round);
      const radius = Number(obj.radius);
      if (!(radius > 0)) return null;
      const height = Math.max(1, Math.round(obj.height ?? 1));
      const hollow = obj.hollow === true;
      for (let y = center[1]; y < center[1] + height; y++) {
        for (let x = Math.floor(center[0] - radius); x <= Math.ceil(center[0] + radius); x++) {
          for (let z = Math.floor(center[2] - radius); z <= Math.ceil(center[2] + radius); z++) {
            const d = Math.hypot(x - center[0], z - center[2]);
            if (d > radius) continue;
            if (hollow && d < radius - 1) continue;
            draft.put(x, y, z, material, solid);
          }
        }
      }
      // Crenellations: alternate merlons around the rim.
      if (obj.crenellated) {
        const top = center[1] + height;
        for (let x = Math.floor(center[0] - radius); x <= Math.ceil(center[0] + radius); x++) {
          for (let z = Math.floor(center[2] - radius); z <= Math.ceil(center[2] + radius); z++) {
            const d = Math.hypot(x - center[0], z - center[2]);
            if (d > radius || d < radius - 1) continue;
            if (((x + z) & 1) === 0) draft.put(x, top, z, material, solid);
          }
        }
      }
      return idBase;
    }

    case 'cone': {
      if (!Array.isArray(obj.center)) return null;
      const center = obj.center.map(Math.round);
      const radius = Number(obj.radius);
      if (!(radius > 0)) return null;
      const height = Math.max(1, Math.round(obj.height ?? 1));
      for (let y = 0; y < height; y++) {
        const r = radius * (1 - y / height);
        for (let x = Math.floor(center[0] - r); x <= Math.ceil(center[0] + r); x++) {
          for (let z = Math.floor(center[2] - r); z <= Math.ceil(center[2] + r); z++) {
            if (Math.hypot(x - center[0], z - center[2]) > r) continue;
            draft.put(x, center[1] + y, z, material, solid);
          }
        }
      }
      return idBase;
    }

    case 'sphere': {
      if (!Array.isArray(obj.center)) return null;
      const center = obj.center.map(Math.round);
      const radius = Number(obj.radius);
      if (!(radius > 0)) return null;
      const hollow = obj.hollow === true;
      const half = obj.half || 'full';
      for (let x = Math.floor(center[0] - radius); x <= Math.ceil(center[0] + radius); x++) {
        for (let y = Math.floor(center[1] - radius); y <= Math.ceil(center[1] + radius); y++) {
          if (half === 'top' && y < center[1]) continue;
          if (half === 'bottom' && y > center[1]) continue;
          for (let z = Math.floor(center[2] - radius); z <= Math.ceil(center[2] + radius); z++) {
            const d = Math.hypot(x - center[0], y - center[1], z - center[2]);
            if (d > radius) continue;
            if (hollow && d < radius - 1) continue;
            draft.put(x, y, z, material, solid);
          }
        }
      }
      return idBase;
    }

    case 'balustrade': {
      const b = box();
      if (!b) return null;
      const [lo, hi] = b;
      const axis = Math.abs(hi[0] - lo[0]) >= Math.abs(hi[2] - lo[2]) ? 0 : 2;
      const other = axis === 0 ? 2 : 0;
      const cross = Math.round((lo[other] + hi[other]) / 2);
      const spacing = Math.max(1, Math.round(obj.spacing ?? 3));
      const baseY = lo[1];
      const topY = Math.max(lo[1] + 1, hi[1]);
      const style = obj.style || 'baluster';
      const put = (u, y, mat = material) => {
        if (axis === 0) draft.put(u, y, cross, mat, solid);
        else draft.put(cross, y, u, mat, solid);
      };
      for (let u = lo[axis]; u <= hi[axis]; u++) {
        put(u, baseY);
        put(u, topY);
        const isPost = (u - lo[axis]) % spacing === 0 || u === hi[axis];
        if (isPost || style === 'solid') {
          for (let y = baseY; y <= topY; y++) put(u, y);
        } else if (style === 'baluster') {
          if ((u - lo[axis]) % 2 === 1) put(u, baseY + 1);
          if (topY - baseY > 2) put(u, topY - 1);
        }
      }
      return idBase;
    }

    default:
      return null;
  }
}

/**
 * Fallback voxeliser. Produces the same cell contract as core's `voxelizeSpec` for the object types
 * above. Deterministic: no randomness, no clock.
 *
 * @param {object} rawSpec
 * @param {{includeHidden?:boolean}} [opts]
 * @returns {Map<string, VoxelCell>}
 */
export function fallbackVoxelize(rawSpec, opts = {}) {
  const spec = normalizeSpec(rawSpec || {});
  const draft = new CellDraft();
  spec.objects.forEach((obj, index) => {
    voxelizeObject(draft, obj, index);
  });
  return decorateCells(draft.cells, opts);
}

/**
 * Turns a raw `key -> {material, solid}` map into the full cell contract: exposure bits, averaged
 * normal (with the thin-shape rule), interior flag and vertical support flags.
 *
 * @param {Map<string, {material:string, solid?:string}>} cells
 * @param {{includeHidden?:boolean}} [opts] `includeHidden: false` drops fully enclosed cells
 * @returns {Map<string, VoxelCell>}
 */
export function decorateCells(cells, opts = {}) {
  const includeHidden = opts.includeHidden !== false;
  /** @type {Map<string, VoxelCell>} */
  const out = new Map();

  for (const [k, info] of cells) {
    const [x, y, z] = parseKey(k);
    let exposed = 0;
    let nx = 0;
    let ny = 0;
    let nz = 0;
    for (let f = 0; f < 6; f++) {
      const step = FACE_STEPS[f];
      if (cells.has(key(x + step[0], y + step[1], z + step[2]))) continue;
      exposed |= 1 << f;
      const n = FACE_NORMALS[f];
      nx += n[0];
      ny += n[1];
      nz += n[2];
    }
    if (exposed === 0 && !includeHidden) continue;

    let normal;
    const len = Math.hypot(nx, ny, nz);
    if (len > 1e-6) {
      normal = [nx / len, ny / len, nz / len];
    } else {
      // Exposed on two opposite sides (a 1-thick wall, a floor tile, a pane): the sum cancels.
      // Report the thin axis with a positive sign, exactly like core's thinNormal().
      normal = thinNormal(exposed);
    }

    out.set(k, {
      material: info.material || 'plain',
      up: false,
      down: false,
      nx: round6(normal[0]),
      ny: round6(normal[1]),
      nz: round6(normal[2]),
      exposed,
      interior: exposed === 0,
      // `x`/`z` when the cell is a one-cell-thick plate along that axis, `null` otherwise. This is
      // the "thin and flat" test that selects slabs; it cannot be derived from the averaged normal
      // alone, because a plate's normal is straight up like any floor tile's.
      thinAxis: thinAxisOf(exposed),
      solid: info.solid || 'fallback',
    });
  }

  for (const [k, cell] of out) {
    const [x, y, z] = parseKey(k);
    cell.up = cells.has(key(x, y + 1, z));
    cell.down = cells.has(key(x, y - 1, z));
  }
  return out;
}

/** Thin-axis normal for cells exposed on two opposite sides. */
function thinAxisOf(exposed) {
  const xOpen = (exposed & DIR.xp) !== 0 && (exposed & DIR.xn) !== 0;
  const zOpen = (exposed & DIR.zp) !== 0 && (exposed & DIR.zn) !== 0;
  // `exposed` alone cannot tell a 1-cell-thick plate (open on both x sides, one layer tall) from a
  // floor tile that is simply not covered: both report only their vertical faces as open. The
  // caller measures the plate thickness directly from the neighbourhood, and this helper only
  // reports the unambiguous case of a cell open on exactly one horizontal axis.
  if (xOpen && !zOpen) return 'x';
  if (zOpen && !xOpen) return 'z';
  return null;
}

/** Thin-axis normal for cells exposed on two opposite sides. */
function thinNormal(exposed) {
  const pairs = [
    [DIR.xp, DIR.xn, 0],
    [DIR.yp, DIR.yn, 1],
    [DIR.zp, DIR.zn, 2],
  ];
  for (const [pos, neg, axis] of pairs) {
    if ((exposed & pos) !== 0 && (exposed & neg) !== 0) {
      const n = [0, 0, 0];
      n[axis] = 1;
      return n;
    }
  }
  return [0, 0, 0];
}

/* ------------------------------------------------------------------------------------------ */
/* Optional core adapter                                                                       */
/* ------------------------------------------------------------------------------------------ */

const requireFromHere = createRequire(import.meta.url);

/** Cached probe result: `{ fn: Function|null }`. Never re-probed, so a broken core costs one try. */
let coreProbe = null;

/**
 * Loads `voxelizeSpec` from `packages/core/src/geometry.mjs`, defensively.
 *
 * `createRequire` is used because the public API is synchronous and Node 24 can `require()` an ESM
 * module synchronously; this also avoids the module becoming async. A failure of any kind means
 * "geometry is not ready yet" and returns `null` — never a thrown error, because transfer must keep
 * working while task A is mid-edit.
 *
 * @returns {((spec:object, opts?:object)=>Map<string, any>)|null}
 */
export function loadCoreVoxelizer() {
  if (coreProbe) return coreProbe.fn;
  try {
    const mod = requireFromHere('../../core/src/geometry.mjs');
    coreProbe = { fn: typeof mod?.voxelizeSpec === 'function' ? mod.voxelizeSpec : null };
  } catch {
    coreProbe = { fn: null };
  }
  return coreProbe.fn;
}

/**
 * Overrides the cached core probe (used by tests to exercise the fallback path, and by
 * `index.mjs` when it probes the module explicitly).
 * @param {Function|null} fn
 */
export function primeCoreVoxelizer(fn) {
  coreProbe = { fn: typeof fn === 'function' ? fn : null };
}

/** Which implementation `voxelizeStructure` would use right now: `'core'` or `'fallback'`. */
export function voxelizerSource() {
  return loadCoreVoxelizer() ? 'core' : 'fallback';
}

/**
 * Validates that a cell map conforms to the transfer contract.
 * @param {unknown} cells
 * @returns {{ok:boolean, errors:string[]}}
 */
export function validateCellMap(cells) {
  if (!(cells instanceof Map)) return { ok: false, errors: ['voxel map is not a Map'] };
  if (cells.size === 0) return { ok: false, errors: ['voxel map is empty'] };
  const errors = [];
  let checked = 0;
  for (const [k, cell] of cells) {
    if (typeof k !== 'string') {
      errors.push('voxel map has a non-string key');
      break;
    }
    if (checked++ > 32) break;
    if (!cell || typeof cell !== 'object') {
      errors.push(`cell ${k} is not an object`);
      continue;
    }
    if (typeof cell.material !== 'string' || !cell.material) errors.push(`cell ${k} has no material`);
    for (const axis of ['nx', 'ny', 'nz']) {
      if (!Number.isFinite(cell[axis])) errors.push(`cell ${k} has a non-finite ${axis}`);
    }
    if (typeof cell.up !== 'boolean' || typeof cell.down !== 'boolean') {
      errors.push(`cell ${k} is missing up/down support flags`);
    }
  }
  return { ok: errors.length === 0, errors };
}

/**
 * Normalises core's cells to the transfer contract. Core is the authoritative voxeliser, so its
 * per-solid `cellNormals`, exposure bits and support flags are preserved; only missing fields are
 * filled in, and a structural pass guarantees `up`/`down` are derived from the cell set.
 *
 * @param {Map<string, any>} cells
 * @returns {Map<string, VoxelCell>}
 */
export function adaptCoreCells(cells) {
  const decorated = decorateCells(
    new Map(
      [...cells].map(([k, cell]) => [
        k,
        {
          material: (cell && typeof cell.material === 'string' && cell.material) || 'plain',
          solid: (cell && typeof cell.solid === 'string' && cell.solid) || 'core',
        },
      ]),
    ),
    { includeHidden: true },
  );
  for (const [k, cell] of cells) {
    const target = decorated.get(k);
    if (!target || !cell) continue;
    if (Number.isFinite(cell.nx)) target.nx = round6(cell.nx);
    if (Number.isFinite(cell.ny)) target.ny = round6(cell.ny);
    if (Number.isFinite(cell.nz)) target.nz = round6(cell.nz);
    if (Number.isInteger(cell.exposed)) target.exposed = cell.exposed;
    if (typeof cell.thinAxis === 'string' || cell.thinAxis === null) target.thinAxis = cell.thinAxis;
    if (typeof cell.interior === 'boolean') target.interior = cell.interior;
    // Core's flags are derived from the same occupancy test, but only trust them when complete.
    if (typeof cell.up === 'boolean') target.up = cell.up;
    if (typeof cell.down === 'boolean') target.down = cell.down;
  }
  return decorated;
}

/**
 * The voxeliser `transferToMinecraft` uses: core's when available, the local fallback otherwise.
 *
 * @param {object} spec
 * @param {{voxelizer?:Function, includeHidden?:boolean, core?:boolean}} [opts]
 *   `voxelizer` overrides both; `core: false` forces the fallback (tests use it)
 * @returns {{cells: Map<string, VoxelCell>, source: 'core'|'fallback'|'custom', warnings: string[]}}
 */
export function voxelizeStructure(spec, opts = {}) {
  const warnings = [];
  if (typeof opts.voxelizer === 'function') {
    const cells = opts.voxelizer(spec);
    const check = validateCellMap(cells);
    if (!check.ok) throw new Error(`Custom voxeliser returned an invalid map: ${check.errors.join('; ')}`);
    return { cells, source: 'custom', warnings };
  }

  const coreFn = opts.core === false ? null : loadCoreVoxelizer();
  if (coreFn) {
    try {
      const cells = coreFn(spec, { includeHidden: opts.includeHidden !== false });
      const check = validateCellMap(cells);
      if (check.ok) return { cells: adaptCoreCells(cells), source: 'core', warnings };
      warnings.push(
        `core voxelizeSpec returned an unusable map (${check.errors.slice(0, 3).join('; ')}); using the built-in voxeliser`,
      );
    } catch (error) {
      warnings.push(`core voxelizeSpec failed (${errorMessage(error)}); using the built-in voxeliser`);
    }
  }
  return {
    cells: fallbackVoxelize(spec, { includeHidden: opts.includeHidden !== false }),
    source: 'fallback',
    warnings,
  };
}

/** Compact error text for warnings. @param {unknown} error */
export function errorMessage(error) {
  if (error instanceof Error) return `${error.name}: ${error.message.split('\n')[0]}`;
  return String(error);
}
