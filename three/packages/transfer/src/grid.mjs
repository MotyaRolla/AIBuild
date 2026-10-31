/**
 * Integer voxel grid helpers shared by the voxeliser, the block selector and the exporter.
 *
 * A cell (x, y, z) covers [x, x+1] x [y, y+1] x [z, z+1] and is addressed by the integer key
 * `"x|y|z"` — the same key convention `packages/core` uses, so voxel maps can be passed between
 * the two modules without conversion.
 *
 * Coordinates: +x = east, +y = up, +z = south (Minecraft's convention).
 */

/** Builds the string key of a cell. @param {number} x @param {number} y @param {number} z */
export function key(x, y, z) {
  return `${x}|${y}|${z}`;
}

/** Parses a cell key. @param {string} k @returns {[number, number, number]} */
export function parseKey(k) {
  const parts = k.split('|');
  return [Number(parts[0]), Number(parts[1]), Number(parts[2])];
}

/** True when a value looks like a cell key. @param {unknown} k */
export function isKey(k) {
  return typeof k === 'string' && /^-?\d+\|-?\d+\|-?\d+$/.test(k);
}

/** The six face directions, matching the order used by `packages/core` (xp, xn, yp, yn, zp, zn). */
export const FACE_STEPS = /** @type {const} */ ([
  [1, 0, 0],
  [-1, 0, 0],
  [0, 1, 0],
  [0, -1, 0],
  [0, 0, 1],
  [0, 0, -1],
]);

/** Local-space normal of each face, same order as FACE_STEPS. */
export const FACE_NORMALS = /** @type {const} */ ([
  [1, 0, 0],
  [-1, 0, 0],
  [0, 1, 0],
  [0, -1, 0],
  [0, 0, 1],
  [0, 0, -1],
]);

/** The four horizontal directions, in Minecraft clockwise order starting at east. */
export const HORIZONTAL = /** @type {const} */ ([
  ['east', 1, 0],
  ['south', 0, 1],
  ['west', -1, 0],
  ['north', 0, -1],
]);

/** `facing` -> horizontal step. */
export const FACING_STEP = Object.freeze({
  east: [1, 0],
  south: [0, 1],
  west: [-1, 0],
  north: [0, -1],
});

/** The opposite horizontal facing. */
export const OPPOSITE_FACING = Object.freeze({ east: 'west', west: 'east', north: 'south', south: 'north' });

/**
 * Neighbour key of a cell.
 * @param {string} k
 * @param {number} dx
 * @param {number} dy
 * @param {number} dz
 */
export function neighborKey(k, dx, dy, dz) {
  const [x, y, z] = parseKey(k);
  return key(x + dx, y + dy, z + dz);
}

/**
 * Minimal bounds enclosing a set of cell keys.
 * @param {Iterable<string>} keys
 * @returns {{min:[number,number,number], max:[number,number,number], size:[number,number,number]}}
 */
export function keyBounds(keys) {
  const min = [Infinity, Infinity, Infinity];
  const max = [-Infinity, -Infinity, -Infinity];
  let any = false;
  for (const k of keys) {
    const [x, y, z] = parseKey(k);
    any = true;
    if (x < min[0]) min[0] = x;
    if (y < min[1]) min[1] = y;
    if (z < min[2]) min[2] = z;
    if (x > max[0]) max[0] = x;
    if (y > max[1]) max[1] = y;
    if (z > max[2]) max[2] = z;
  }
  if (!any) return { min: [0, 0, 0], max: [0, 0, 0], size: [0, 0, 0] };
  return { min, max, size: [max[0] - min[0] + 1, max[1] - min[1] + 1, max[2] - min[2] + 1] };
}

/**
 * Deterministic iteration order for a block map: bottom-up, then north-south, then west-east.
 * Exporters and tests rely on this so two runs always produce identical files/commands.
 * @param {Iterable<string>} keys
 * @returns {string[]}
 */
export function sortedKeys(keys) {
  return [...keys].sort((a, b) => {
    const pa = parseKey(a);
    const pb = parseKey(b);
    return pa[1] - pb[1] || pa[2] - pb[2] || pa[0] - pb[0];
  });
}
