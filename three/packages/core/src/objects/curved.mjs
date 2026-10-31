/**
 * Curved objects: cylinder, cone, sphere, plus the horizontal `ring` band.
 *
 * These use analytic "is the cell centre inside the shape?" tests. The cell set is the single
 * source of truth: a 1-cell-thick cylindrical tower wall therefore yields exactly one cell of
 * thickness (cells whose centre is inside the annulus), and the same set drives rendering.
 */

import { addCell, makeDesc } from './helpers.mjs';
import { materialOf } from './blocks.mjs';

/** Distance between the shape axis / centre and the centre of cell (x,z). */
function radial(x, z, cx, cz) {
  return Math.hypot(x + 0.5 - cx, z + 0.5 - cz);
}

/**
 * cylinder — vertical tube / drum. Uses `hollow` for shells and `crenellated` for battlements.
 * @param {object} obj
 * @param {object} ctx
 * @returns {object[]}
 */
export function buildCylinder(obj, ctx) {
  const mat = materialOf(obj, 'stone');
  const center = obj.center || [0, 0, 0];
  const radius = Math.max(0.5, Number(obj.radius ?? 2));
  const height = Math.max(1, Math.round(obj.height ?? 4));
  const cx = center[0] + 0.5;
  const cz = center[2] + 0.5;
  const inner = Math.max(0, radius - Math.max(0.5, Number(obj.thickness ?? 1)));
  const hollow = obj.hollow === true;
  const desc = makeDesc('cells', mat);
  const xLo = Math.floor(cx - radius - 1);
  const xHi = Math.ceil(cx + radius + 1);
  const zLo = Math.floor(cz - radius - 1);
  const zHi = Math.ceil(cz + radius + 1);
  for (let y = center[1]; y < center[1] + height; y++) {
    for (let x = xLo; x <= xHi; x++) {
      for (let z = zLo; z <= zHi; z++) {
        const r = radial(x, z, cx, cz);
        if (r > radius + 0.5) continue;
        if (hollow && r < inner) continue;
        if (obj.crenellated && y === center[1] + height - 1) {
          const ang = Math.atan2(z + 0.5 - cz, x + 0.5 - cx);
          const teeth = Math.max(8, Math.round(radius * 8));
          const idx = Math.floor(((ang + Math.PI) / (Math.PI * 2)) * teeth);
          if (idx % 2 !== 0) continue;
        }
        addCell(desc, x, y, z);
      }
    }
  }
  return desc.cells.size ? [desc] : [];
}

/**
 * cone — tapering spire, useful for tower roofs.
 * @param {object} obj
 * @param {object} ctx
 * @returns {object[]}
 */
export function buildCone(obj, ctx) {
  const mat = materialOf(obj, 'roof');
  const center = obj.center || [0, 0, 0];
  const radius = Math.max(0.5, Number(obj.radius ?? 2));
  const height = Math.max(1, Math.round(obj.height ?? 4));
  const cx = center[0] + 0.5;
  const cz = center[2] + 0.5;
  const shell = Math.max(0.5, Number(obj.thickness ?? 1));
  const desc = makeDesc('cells', mat);
  const xLo = Math.floor(cx - radius - 1);
  const xHi = Math.ceil(cx + radius + 1);
  const zLo = Math.floor(cz - radius - 1);
  const zHi = Math.ceil(cz + radius + 1);
  for (let i = 0; i < height; i++) {
    const t = height === 1 ? 0 : i / (height - 1);
    const r = radius * (1 - t);
    const y = center[1] + i;
    for (let x = xLo; x <= xHi; x++) {
      for (let z = zLo; z <= zHi; z++) {
        const rr = radial(x, z, cx, cz);
        if (rr > r + 0.5) continue;
        if (obj.hollow && rr < Math.max(0, r - shell)) continue;
        addCell(desc, x, y, z);
      }
    }
  }
  return desc.cells.size ? [desc] : [];
}

/**
 * sphere — ball or dome. `half` may be 'top' (dome, the default for domes) or 'bottom'.
 * @param {object} obj
 * @param {object} ctx
 * @returns {object[]}
 */
export function buildSphere(obj, ctx) {
  const mat = materialOf(obj, 'stone');
  const center = obj.center || [0, 0, 0];
  const radius = Math.max(0.5, Number(obj.radius ?? 2));
  const half = obj.half === 'bottom' ? 'bottom' : obj.half === 'full' ? 'full' : 'top';
  const cx = center[0] + 0.5;
  const cy = center[1] + 0.5;
  const cz = center[2] + 0.5;
  const thickness = Math.max(0.5, Number(obj.thickness ?? 1));
  const desc = makeDesc('cells', mat);
  const xLo = Math.floor(cx - radius - 1);
  const xHi = Math.ceil(cx + radius + 1);
  const zLo = Math.floor(cz - radius - 1);
  const zHi = Math.ceil(cz + radius + 1);
  const yLo = half === 'top' ? Math.floor(cy) : Math.floor(cy - radius - 1);
  const yHi = half === 'bottom' ? Math.ceil(cy) : Math.ceil(cy + radius + 1);
  for (let y = yLo; y <= yHi; y++) {
    for (let x = xLo; x <= xHi; x++) {
      for (let z = zLo; z <= zHi; z++) {
        const d = Math.hypot(x + 0.5 - cx, y + 0.5 - cy, z + 0.5 - cz);
        if (d > radius + 0.5) continue;
        if (obj.hollow && d < radius - thickness) continue;
        addCell(desc, x, y, z);
      }
    }
  }
  return desc.cells.size ? [desc] : [];
}

/**
 * ring — horizontal band around a rectangular footprint (cornice, string course, plinth).
 * The band only occupies the perimeter ring of cells, one to `thickness` deep.
 * @param {object} obj
 * @param {object} ctx
 * @returns {object[]}
 */
export function buildRing(obj, ctx) {
  const mat = materialOf(obj, 'trim', 'stone');
  const from = obj.from || (ctx.bounds ? ctx.bounds.min : [0, 0, 0]);
  const to = obj.to || (ctx.bounds ? ctx.bounds.max : from);
  const thickness = Math.max(1, Math.round(obj.thickness ?? 1));
  const profile = obj.profile || 'flat';
  const y0 = Math.round(obj.y ?? from[1]);
  const layers = profile === 'cornice' ? Math.max(2, thickness) : thickness;
  const desc = makeDesc('cells', mat);
  const accent = makeDesc('cells', 'roof_accent', 'detail');
  const x0 = Math.min(from[0], to[0]);
  const x1 = Math.max(from[0], to[0]);
  const z0 = Math.min(from[2], to[2]);
  const z1 = Math.max(from[2], to[2]);

  const inRing = (x, z, grow) =>
    x >= x0 - grow && x <= x1 + grow && z >= z0 - grow && z <= z1 + grow &&
    !(x >= x0 + grow + 1 && x <= x1 - grow - 1 && z >= z0 + grow + 1 && z <= z1 - grow - 1);

  for (let layer = 0; layer < layers; layer++) {
    // cornice flares outwards as it rises; base flares outwards at the bottom
    let grow = 0;
    if (profile === 'cornice') grow = layer;
    else if (profile === 'base') grow = Math.max(0, layers - 1 - layer);
    else if (profile === 'eave') grow = layer === 0 ? 1 : 0;
    for (let x = x0 - grow; x <= x1 + grow; x++) {
      for (let z = z0 - grow; z <= z1 + grow; z++) {
        if (!inRing(x, z, grow)) continue;
        // keep the band hollow inside the footprint: only the perimeter ring cells count
        const onOuter = x === x0 - grow || x === x1 + grow || z === z0 - grow || z === z1 + grow;
        const onInner = x === x0 - grow + thickness || x === x1 + grow - thickness || z === z0 - grow + thickness || z === z1 + grow - thickness;
        if (!onOuter && !onInner) {
          // fill between the outer and inner faces for a proper band
          const depthX = Math.min(x - (x0 - grow), x1 + grow - x);
          const depthZ = Math.min(z - (z0 - grow), z1 + grow - z);
          if (Math.min(depthX, depthZ) > thickness) continue;
        }
        addCell(desc, x, y0 + layer, z);
      }
    }
  }
  if (profile === 'cornice' || profile === 'eave') {
    const topY = y0 + layers - 1;
    for (let x = x0 - layers; x <= x1 + layers; x++) {
      for (let z = z0 - layers; z <= z1 + layers; z++) {
        if (Math.abs(x - x0) <= layers && Math.abs(z - z0) <= layers) continue;
        addCell(accent, x, topY, z);
      }
    }
  }
  const out = [desc];
  if (accent.cells.size) out.push(accent);
  return out;
}

