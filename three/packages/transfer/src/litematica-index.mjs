/**
 * Litematica's block-container indexing, isolated because it is the easiest part of the format to
 * get subtly wrong.
 *
 * Two different coordinate orders are in play in a `.litematic` file:
 *
 * * `BlockStates` is packed in the container's *local* order, which is Y-major:
 *
 *       index = (y * sizeZ + z) * sizeX + x
 *
 *   (see `LitematicaBlockStateContainer#getIndex`).
 *
 * * The `Position` and `Size` tags are *world/reference* coordinates: `Position` is the primary
 *   (red) corner of the area selection and `Size` is the signed vector from that corner to the
 *   opposite one. A negative component simply means the second corner lies towards the negative
 *   direction, and the container is still laid out over the resulting positive box.
 *
 * For a single region it is simplest and most robust to write `Position` = minimum corner and
 * `Size` = positive size, which this module does. `litematicToBlocks` still understands negative
 * sizes, so files produced by Litematica itself can be read back.
 */

import { key, parseKey } from './grid.mjs';

/**
 * Converts world position -> local container index.
 * @param {number} lx local x (0..sizeX-1)
 * @param {number} ly local y
 * @param {number} lz local z
 * @param {{sizeX:number, sizeY:number, sizeZ:number}} size
 * @returns {number}
 */
export function localIndex(lx, ly, lz, size) {
  return (ly * size.sizeZ + lz) * size.sizeX + lx;
}

/**
 * Converts a local container index -> local position.
 * @param {number} index
 * @param {{sizeX:number, sizeY:number, sizeZ:number}} size
 * @returns {[number, number, number]}
 */
export function indexToLocal(index, size) {
  const x = index % size.sizeX;
  const rest = (index - x) / size.sizeX;
  const z = rest % size.sizeZ;
  const y = (rest - z) / size.sizeZ;
  return [x, y, z];
}

/**
 * Signed `Size` -> positive extent, as Litematica does when it overlays the container on the box.
 * `Size` components are never 0 (a 0 would be indistinguishable from 1), so 0 is treated as 1.
 * @param {number[]} size
 * @returns {[number, number, number]}
 */
export function positiveSize(size) {
  return size.map((n) => {
    const v = Math.trunc(n);
    if (v === 0) return 1;
    return v < 0 ? -v : v;
  });
}

/**
 * The minimum world corner of a region, given its primary corner and signed size.
 * @param {number[]} position
 * @param {number[]} size signed
 * @returns {[number, number, number]}
 */
export function regionMinCorner(position, size) {
  const out = [0, 0, 0];
  for (let i = 0; i < 3; i++) {
    const signed = Math.trunc(size[i]);
    // Litematica's Size is an inclusive span: `-n` covers n cells towards the negative axis, so
    // the minimum corner moves by `size + 1` when the span is negative.
    out[i] = signed > 0 ? position[i] : position[i] + signed + 1;
  }
  return /** @type {[number, number, number]} */ (out);
}

/**
 * Maps a block key (world coordinates) into a region's container index.
 *
 * @param {string} blockKey
 * @param {{position:number[], size:number[]}} region as written in the file
 * @returns {{index:number, local:[number,number,number]}|null} null when the block is outside
 */
export function blockToIndex(blockKey, region) {
  const dims = positiveSize(region.size);
  const origin = regionMinCorner(region.position, region.size);
  const [x, y, z] = parseKey(blockKey);
  const local = [x - origin[0], y - origin[1], z - origin[2]];
  if (local[0] < 0 || local[1] < 0 || local[2] < 0) return null;
  if (local[0] >= dims[0] || local[1] >= dims[1] || local[2] >= dims[2]) return null;
  return {
    index: localIndex(local[0], local[1], local[2], { sizeX: dims[0], sizeY: dims[1], sizeZ: dims[2] }),
    local: /** @type {[number,number,number]} */ (local),
  };
}

/**
 * Maps a container index back to a world block key.
 * @param {number} index
 * @param {{position:number[], size:number[]}} region
 * @returns {string}
 */
export function indexToBlockKey(index, region) {
  const dims = positiveSize(region.size);
  const origin = regionMinCorner(region.position, region.size);
  const local = indexToLocal(index, { sizeX: dims[0], sizeY: dims[1], sizeZ: dims[2] });
  return key(origin[0] + local[0], origin[1] + local[1], origin[2] + local[2]);
}

/**
 * Tries to read a region's `Position`/`Size` into `{position, size, dimensions}`.
 * @param {{position:any, size:any}} region
 * @returns {{position:[number,number,number], size:[number,number,number], dimensions:[number,number,number]}}
 */
export function regionBox(region) {
  const read = (value) => {
    if (Array.isArray(value)) return value.map((n) => Math.trunc(Number(n)));
    if (value && typeof value === 'object') {
      const x = Number(value.x ?? value.X ?? 0);
      const y = Number(value.y ?? value.Y ?? 0);
      const z = Number(value.z ?? value.Z ?? 0);
      return [Math.trunc(x), Math.trunc(y), Math.trunc(z)];
    }
    return [0, 0, 0];
  };
  const position = /** @type {[number,number,number]} */ (read(region.position));
  const size = /** @type {[number,number,number]} */ (read(region.size));
  return { position, size, dimensions: /** @type {[number,number,number]} */ (positiveSize(size)) };
}
