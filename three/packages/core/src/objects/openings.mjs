/**
 * Openings: door/window/arch apertures that really cut walls (they are not decals).
 *
 * Coordinates
 * -----------
 * `south` is +z, `north` is -z, `east` is +x, `west` is -x (same as the rest of the project).
 * For an opening the horizontal coordinate `u` is the world coordinate *along the wall*:
 *   south / north -> u is world x
 *   east  / west  -> u is world z
 * and `y` is world y. `w`/`h` are sizes in cells.
 *
 * A rect is expressed as {wall, u0, u1, y0, y1}; converting it to cells happens per host, because
 * the wall plane (the depth coordinate) depends on the host object.
 */

/** Outward normal axis and sign for each wall name. `axis`: 0=x, 2=z. */
export const WALL_INFO = {
  south: { axis: 2, sign: 1 },
  north: { axis: 2, sign: -1 },
  east: { axis: 0, sign: 1 },
  west: { axis: 0, sign: -1 },
};

/** Axis index of the horizontal coordinate `u` for a wall. */
export const wallUAxis = (wall) => (wall === 'east' || wall === 'west' ? 2 : 0);

/** Depth axis (normal to the wall). */
export const wallDAxis = (wall) => (wall === 'east' || wall === 'west' ? 0 : 2);

const num = (v, d) => (Number.isFinite(v) ? v : d);

/**
 * Normalises the many accepted opening spellings into one rect.
 * Accepts `{wall, x, y, w, h}`, `{wall, at: [u, y], w, h}`, and `{wall, u, y, w, h}`.
 *
 * @param {object} op
 * @param {{uMin?:number,uMax?:number,yMin?:number,yMax?:number}} [clampBox] optional host extent
 * @returns {{wall:string, u0:number, u1:number, y0:number, y1:number, kind:string, arch:boolean, glass:string|null, frame:string|null, sill:boolean, shutters:boolean, canopy:boolean, depth:number, cells:null|number[][], filter:((u:number,y:number)=>boolean)|null}}
 */
export function resolveOpening(op, clampBox) {
  const wall = WALL_INFO[op.wall] ? op.wall : 'south';
  const kind = op.kind || 'window';
  const at = Array.isArray(op.at) ? op.at : null;
  let u0 = num(at ? at[0] : num(op.u, op.x), 0);
  let y0 = num(at ? at[1] : op.y, 0);
  const w = Math.max(1, Math.round(num(op.w, 1)));
  const h = Math.max(1, Math.round(num(op.h, kind === 'door' ? 2 : 2)));
  let u1 = u0 + w - 1;
  let y1 = y0 + h - 1;

  const archiveExtra = op.arch && kind !== 'door' ? Math.ceil(w / 2) : 0;
  if (archiveExtra > 0) y1 += archiveExtra;

  if (clampBox) {
    const cu0 = Math.min(num(clampBox.uMin, u0), num(clampBox.uMax, u1));
    const cu1 = Math.max(num(clampBox.uMin, u1), num(clampBox.uMax, u0));
    if (u1 < cu0 || u0 > cu1) {
      // Rect is completely outside the host: clamp it onto the wall so the opening stays usable.
      u0 = Math.min(Math.max(u0, cu0), cu1);
      u1 = Math.min(u0 + w - 1, cu1);
    }
    if (Number.isFinite(clampBox.yMin)) y1 = Math.min(y1, clampBox.yMax);
    if (Number.isFinite(clampBox.yMin)) y0 = Math.max(y0, clampBox.yMin);
  }

  return {
    wall,
    u0,
    u1,
    y0,
    y1,
    kind,
    arch: !!op.arch,
    glass: op.glass || null,
    frame: op.frame || null,
    sill: !!op.sill,
    shutters: !!op.shutters,
    canopy: !!op.canopy,
    depth: Math.max(1, Math.round(num(op.depth, 3))),
    cells: Array.isArray(op.cells) ? op.cells : null,
    filter: null,
  };
}

/**
 * The arch curve: for the rows above the spring line the aperture narrows like a semicircle.
 * Returns true when cell (u, y) is inside the arch.
 * @param {number} u cell column
 * @param {number} y cell row
 * @param {{u0:number,u1:number,y0:number,y1:number,springY:number}} r
 */
export function insideArch(u, y, r) {
  if (y < r.springY) return true;
  const w = r.u1 - r.u0 + 1;
  const ry = r.y1 - r.springY + 1;
  const cx = (r.u0 + r.u1 + 1) / 2;
  const cy = r.springY;
  const nx = (u + 0.5 - cx) / (w / 2);
  const ny = (y + 0.5 - cy) / ry;
  return nx * nx + ny * ny <= 1.0001;
}

/**
 * Enumerates the cells of an aperture, honouring the arched top when requested and an explicit
 * `cells` list when the opening was described cell-by-cell.
 * @param {{u0:number,u1:number,y0:number,y1:number,arch:boolean,cells:number[][]|null}} r
 * @returns {number[][]} [u, y] pairs
 */
export function apertureCells(r) {
  const springY = r.y1 - (r.arch ? Math.max(1, Math.ceil((r.u1 - r.u0 + 1) / 2)) : 0) + 1;
  const archRect = { ...r, springY };
  const out = [];
  for (let u = r.u0; u <= r.u1; u++) {
    for (let y = r.y0; y <= r.y1; y++) {
      if (r.arch && !insideArch(u, y, archRect)) continue;
      if (r.cells) {
        // An explicit cell list is in wall-plane coordinates [u, y].
        const match = r.cells.some((c) => c[0] === u && c[1] === y);
        if (!match) continue;
      }
      out.push([u, y]);
    }
  }
  return out;
}
