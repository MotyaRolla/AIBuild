/**
 * `wall` objects plus the standalone `window` / `door` / `arch` objects.
 *
 * A wall is a straight run between two points with a `thickness` through the wall and a `height`.
 * Its openings are cut right through the thickness (the run of removed cells stops at the first
 * empty cell, so a 1-cell wall loses exactly one layer and thicker walls are opened all the way).
 *
 * Standalone openings (their own object, not nested in a box) are resolved by buildScene against
 * whatever geometry already exists: they find the outermost surface in their column, punch the
 * contiguous run of cells behind it and drop a frame + glass into the hole. This is why a spec like
 *   { type: 'box', ... }, { type: 'window', wall: 'south', at: [2,3], w: 1, h: 2 }
 * produces a real window instead of a decal.
 */

import { addCell, key, makeDesc } from './helpers.mjs';
import { WALL_INFO, apertureCells, resolveOpening, wallDAxis, wallUAxis } from './openings.mjs';
import { materialOf, accentOf } from './blocks.mjs';

/**
 * wall — straight wall with openings.
 * @param {object} obj
 * @param {object} ctx
 * @returns {object[]}
 */
export function buildWall(obj, ctx) {
  const from = obj.from || [0, 0, 0];
  const to = obj.to || from;
  const mat = materialOf(obj, 'wall');
  const axis = Math.abs(to[0] - from[0]) >= Math.abs(to[2] - from[2]) ? 0 : 2;
  const c = axis === 0 ? 2 : 0;
  const a0 = Math.min(from[axis], to[axis]);
  const a1 = Math.max(from[axis], to[axis]);
  const height = Math.max(1, Math.round(obj.height ?? Math.abs(to[1] - from[1]) + 1));
  const thickness = Math.max(1, Math.round(Number(obj.thickness ?? 1)));
  const y0 = from[1];
  const y1 = y0 + height - 1;
  const c0 = from[c] - Math.floor((thickness - 1) / 2);
  const c1 = c0 + thickness - 1;

  const body = makeDesc('cells', mat);
  for (let a = a0; a <= a1; a++) {
    for (let cc = c0; cc <= c1; cc++) {
      for (let y = y0; y <= y1; y++) {
        const p = [0, 0, 0];
        p[axis] = a;
        p[c] = cc;
        p[1] = y;
        addCell(body, p[0], p[1], p[2]);
      }
    }
  }

  // A coping row in the accent material makes the wall read as built rather than extruded.
  const coping = makeDesc('cells', accentOf(obj, mat), 'detail');
  if (obj.coping !== false && thickness > 0) {
    for (let a = a0; a <= a1; a++) {
      for (let cc = c0 - 1; cc <= c1 + 1; cc++) {
        const p = [0, 0, 0];
        p[axis] = a;
        p[c] = cc;
        p[1] = y1 + 1;
        addCell(coping, p[0], p[1], p[2]);
      }
    }
  }

  const out = [body];
  if (coping.cells.size) out.push(coping);

  for (const raw of Array.isArray(obj.openings) ? obj.openings : []) {
    const rect = resolveOpening(raw, { uMin: a0, uMax: a1, yMin: y0, yMax: y1 });
    // For a wall the aperture always runs through the whole thickness, whichever wall was named:
    // a wall is thin by definition, so every opening is a through-hole.
    const cells = apertureCells(rect);
    for (const [u, y] of cells) {
      if (u < a0 || u > a1 || y < y0 || y > y1) continue;
      for (let cc = c0; cc <= c1; cc++) {
        const p = [0, 0, 0];
        p[axis] = u;
        p[c] = cc;
        p[1] = y;
        body.cells.delete(key(p[0], p[1], p[2]));
      }
      if (rect.frame) {
        const frame = makeDesc('cells', rect.frame);
        const faces = [
          [u - 1, y],
          [u + 1, y],
          [u, y - 1],
          [u, y + 1],
        ];
        for (const [fu, fy] of faces) {
          if (fu < a0 || fu > a1 || fy < y0 || fy > y1) continue;
          const p = [0, 0, 0];
          p[axis] = fu;
          p[c] = c0;
          p[1] = fy;
          addCell(frame, p[0], p[1], p[2]);
        }
        if (frame.cells.size) out.push(frame);
      }
      if (rect.glass && (rect.kind === 'window' || rect.glass)) {
        const glass = makeDesc('cells', rect.glass, 'glass');
        const p = [0, 0, 0];
        p[axis] = u;
        p[c] = c0;
        p[1] = y;
        addCell(glass, p[0], p[1], p[2]);
        out.push(glass);
      }
    }
  }

  return out.filter((d) => !d.cells || d.cells.size > 0);
}

/**
 * Resolves a standalone opening object into a punch request. The actual cutting happens in
 * buildScene, once every solid exists, so a window can open a box it does not own.
 *
 * @param {object} obj
 * @param {{bounds:{min:number[],max:number[]}, scene?:object}} ctx
 * @returns {{kind:string, rect:object, request:true, depth:number|null}}
 */
export function buildOpening(obj, ctx) {
  const type = obj.type === 'door' ? 'door' : obj.type === 'arch' ? 'arch' : 'window';
  const rect = resolveOpening({ ...obj, kind: obj.kind || type }, undefined);
  return {
    request: true,
    type,
    rect,
    depth: obj.depth ?? (type === 'door' || type === 'arch' ? null : Math.max(1, Math.round(Number(obj.wallThickness ?? 2)))),
    obj,
  };
}

/**
 * Cuts a standalone opening into the scene cell map and returns the decoration descriptors
 * (frame / glass / sill / canopy) that belong to it.
 *
 * @param {import('./openings.mjs').resolveOpening extends Function ? any : any} unused
 * @param {{rect:object, type:string, depth:number|null, obj:object}} request
 * @param {Map<string, {material:string, rank:number, owner:number, kind:string}>} occupancy global cells
 * @param {{min:number[], max:number[], margin:number}} bounds scene bounds
 * @param {Map<string, any>} [cellOwner] key -> owning entry (so the hole is removed from the owner)
 * @returns {{desc:object[], cut:number, dOuter:number|null}}
 */
export function punchOpening(request, occupancy, bounds, cellOwner) {
  const { rect, type } = request;
  const uAxis = wallUAxis(rect.wall);
  const dAxis = wallDAxis(rect.wall);
  const sign = WALL_INFO[rect.wall].sign;
  const lo = bounds.min[dAxis] - bounds.margin;
  const hi = bounds.max[dAxis] + bounds.margin;
  const out = [];
  let cut = 0;
  const outerDepth = new Map();

  for (const [u, y] of apertureCells(rect)) {
    // Walk from outside inwards to find the first occupied cell.
    let d = sign > 0 ? hi : lo;
    const step = sign > 0 ? -1 : 1;
    let first = null;
    while (sign > 0 ? d >= lo : d <= hi) {
      const p = [0, 0, 0];
      p[uAxis] = u;
      p[1] = y;
      p[dAxis] = d;
      if (occupancy.has(key(p[0], p[1], p[2]))) {
        first = d;
        break;
      }
      d += step;
    }
    if (first === null) continue;
    outerDepth.set(`${u}|${y}`, first);
    const maxDepth = request.depth ?? 64;
    let removed = 0;
    let dd = first;
    while (removed < maxDepth) {
      const p = [0, 0, 0];
      p[uAxis] = u;
      p[1] = y;
      p[dAxis] = dd;
      const k = key(p[0], p[1], p[2]);
      if (!occupancy.has(k)) break;
      occupancy.delete(k);
      const ownerEntry = cellOwner && cellOwner.get(k);
      if (ownerEntry && ownerEntry.cells) ownerEntry.cells.delete(k);
      removed++;
      cut++;
      dd += step;
    }
  }

  // When the opening has no host wall at all (a lone `window` in a spec), anchor it on the first
  // free depth plane in front of the scene so the frame and glass still exist as geometry.
  const dOuter = outerDepth.size
    ? Math.min(...outerDepth.values())
    : (sign > 0 ? Math.min(hi, bounds.min[dAxis] - 1) : Math.max(lo, bounds.max[dAxis] + 1));
  const frameMat = rect.frame || (type === 'door' ? 'frame' : 'wood');
  if (dOuter !== null) {
    const frame = makeDesc('cells', frameMat, 'detail');
    const put = (u, y) => {
      const p = [0, 0, 0];
      p[uAxis] = u;
      p[1] = y;
      p[dAxis] = dOuter;
      addCell(frame, p[0], p[1], p[2]);
    };
    for (let u = rect.u0 - 1; u <= rect.u1 + 1; u++) {
      put(u, rect.y0 - 1);
      put(u, rect.y1 + 1);
    }
    for (let y = rect.y0; y <= rect.y1; y++) {
      put(rect.u0 - 1, y);
      put(rect.u1 + 1, y);
    }
    if (rect.sill || type === 'window') {
      for (let u = rect.u0 - 1; u <= rect.u1 + 1; u++) put(u, rect.y0 - 2);
    }
    if (rect.shutters) {
      for (let y = rect.y0; y <= rect.y1; y++) {
        put(rect.u0 - 2, y);
        put(rect.u1 + 2, y);
      }
    }
    if (rect.canopy || (type === 'door' && request.obj.canopy)) {
      for (let u = rect.u0 - 1; u <= rect.u1 + 1; u++) {
        for (let o = 1; o <= 2; o++) {
          const p = [0, 0, 0];
          p[uAxis] = u;
          p[1] = rect.y1 + 2;
          p[dAxis] = dOuter + sign * o;
          addCell(frame, p[0], p[1], p[2]);
        }
      }
    }
    if (frame.cells.size) out.push(frame);

    if (type === 'window' || rect.glass) {
      const glassMat = rect.glass || request.obj.glass || 'glass';
      const glass = makeDesc('cells', glassMat, 'glass');
      for (const [u, y] of apertureCells(rect)) {
        if (!outerDepth.has(`${u}|${y}`)) continue;
        const p = [0, 0, 0];
        p[uAxis] = u;
        p[1] = y;
        p[dAxis] = outerDepth.get(`${u}|${y}`);
        addCell(glass, p[0], p[1], p[2]);
      }
      if (glass.cells.size) out.push(glass);
    }
  }
  return { desc: out, cut, dOuter };
}
