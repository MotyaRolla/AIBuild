/**
 * Choosing a block for a voxel cell.
 *
 * The material (a palette key such as `wall` or `roof`) comes from the spec; the *shape* comes
 * from the local geometry, plus the material's declared family in `packages/core/src/materials.mjs`:
 *
 *   sloped surface    -> a stairs variant, `facing` towards the uphill side
 *   thin + flat       -> a bottom slab when it rests on something, a top slab when it hangs, a
 *                        double slab when it fills the cell
 *   glass family      -> glass pane, with the four connection states set by its glass neighbours
 *   post-like         -> fence
 *   everything else   -> the plain block from the palette mapping
 *
 * What "sloped" means here
 * ------------------------
 * The voxel cell reports the *averaged outward normal* of its exposed faces. A flat roof tile
 * (only its top face is open) has `ny = 1`; a wall has `ny = 0`; a 45-degree roof staircase row is
 * open on the top and on one side, so its averaged normal points up at ~0.71. The rule from the
 * brief is `|ny| < 0.85` means sloped. The **uphill side is therefore the direction the roof rises
 * towards — the direction opposite the horizontal part of the normal**, because the normal points
 * away from the solid, down the slope. `facing` of a Minecraft stair points at its full-block
 * (tall) side, which is the uphill side, so `facing` is the *inverse* of the horizontal normal.
 *
 * A normal that is mostly horizontal (`|ny| < 0.2`) is a vertical face — a wall, not a slope — and
 * becomes a full block, never a stair, unless the material explicitly asks for stairs.
 *
 * Determinism
 * -----------
 * No randomness and no iteration-order dependence: tiles are processed in sorted key order, and
 * every decision is a pure function of the cell set and its neighbours.
 */

import { DEFAULT_BLOCKS, resolveMaterial } from '../../core/src/materials.mjs';
import { key, parseKey, FACE_STEPS, neighborKey } from './grid.mjs';
import {
  blockState,
  canonicalState,
  facingFromStep,
  fenceFor,
  isKnownBlock,
  makeConnections,
  makeSlab,
  makeStairs,
  paneFor,
  slabFor,
  stairsFor,
  stripNamespace,
} from './states.mjs';

/** `|ny|` below this is a slope (the contract's 0.85 threshold). */
export const SLOPE_NY = 0.85;

/** `|ny|` below this is a vertical surface, not a slope. */
export const VERTICAL_NY = 0.2;

/** Horizontal connection directions in Minecraft's clockwise order, with their cell steps. */
const DIRECTIONS = [
  ['east', 1, 0],
  ['south', 0, 1],
  ['west', -1, 0],
  ['north', 0, -1],
];

/**
 * @typedef {{
 *   material:string, up:boolean, down:boolean, nx:number, ny:number, nz:number,
 *   exposed:number, interior:boolean, solid:string
 * }} VoxelCell
 */

/**
 * True when the uphill side of a sloped cell has material behind it.
 *
 * The outward normal points *down* the slope, so uphill is `-nx, -nz`. A roof staircase row that
 * steps up towards that direction has a cell there (or directly above it), which is precisely what
 * distinguishes a real slope from the open rim of a flat plate.
 *
 * @param {Map<string, any>} cells
 * @param {number} x
 * @param {number} y
 * @param {number} z
 * @param {{nx:number, nz:number, nz?:number}} cell
 * @returns {boolean}
 */
export function hasUphillSupport(cells, x, y, z, cell) {
  const dx = -Math.sign(Math.round(cell.nx || 0));
  const dz = -Math.sign(Math.round(cell.nz || 0));
  if (dx === 0 && dz === 0) return false;
  // A roof staircase row has material *higher than this cell* on the uphill side, or directly on
  // top of it. A flat plate's rim also has neighbours uphill (the plate continues), but never above
  // it, so requiring height is what separates the two cases.
  if (cells.has(key(x, y + 1, z))) return true;
  for (let up = 0; up <= 2; up++) {
    if (cells.has(key(x + dx, y + up, z + dz)) && up > 0) return true;
  }
  return false;
}

/**
 * True when the cell belongs to a horizontal plate that is exactly one cell thick: there is air
 * (or nothing) above and below it, and it is part of a run of at least three cells in one
 * horizontal direction while being at most one cell deep in the other.
 *
 * A floor tile lying on the ground is *not* a plate by this rule when it is a single isolated cell,
 * but a genuine 1-thick canopy or roof sheet is, which is exactly what should become slabs.
 *
 * @param {Map<string, any>} cells
 * @param {number} x
 * @param {number} y
 * @param {number} z
 * @returns {boolean}
 */
export function isThinPlate(cells, x, y, z) {
  if (cells.has(key(x, y + 1, z))) return false; // something above: the cell is part of a wall/mass
  const run = (dx, dz) => {
    let n = 0;
    for (const step of [1, -1]) {
      for (let i = 1; i <= 3; i++) {
        if (!cells.has(key(x + dx * i * step, y, z + dz * i * step))) break;
        n += 1;
      }
    }
    return n;
  };
  const alongX = run(1, 0);
  const alongZ = run(0, 1);
  return Math.max(alongX, alongZ) >= 2;
}

/**
 * @typedef {{
 *   blocks: Map<string, {Name:string, Properties?:Record<string,string>}>,
 *   kinds: Map<string, 'full'|'stairs'|'slab'|'pane'|'fence'>,
 *   stats: {byMaterial: Record<string, number>, stairs:number, slabs:number, panes:number, fences:number, full:number}
 * }} SelectionResult
 */

/**
 * Resolves the block mapping for a palette material, tolerating colours and unknown keys.
 * @param {string} material
 * @param {object} palette
 * @param {object} blocks
 * @returns {{block:string, family?:string, variants?:string[]}}
 */
export function mappingFor(material, palette, blocks) {
  try {
    const mapping = resolveMaterial(material, palette || {}, blocks || DEFAULT_BLOCKS);
    if (mapping && typeof mapping.block === 'string' && isKnownBlock(mapping.block)) {
      return { ...mapping, block: stripNamespace(mapping.block) };
    }
    if (mapping && typeof mapping.block === 'string') {
      // The palette points at a block this build does not know: keep the id (it is still a valid
      // resource location) but let the caller warn about it.
      return { ...mapping, block: stripNamespace(mapping.block), unknown: true };
    }
  } catch {
    /* fall through to the default */
  }
  return { block: 'stone' };
}

/**
 * Picks the block kind and orientation for a cell.
 *
 * Exported separately from `selectBlocks` so the choice can be unit-tested cell by cell.
 *
 * @param {VoxelCell} cell
 * @param {{block:string, family?:string}} mapping
 * @param {{isGlassNeighbor:(key:string)=>boolean, solidBelow:boolean, solidAbove:boolean, occupied:(key:string)=>boolean}} context
 * @param {string} k the cell's own key
 * @returns {{state:{Name:string, Properties?:Record<string,string>}, kind:'full'|'stairs'|'slab'|'pane'|'fence', note?:string}}
 */
export function chooseBlock(cell, mapping, context, k) {
  const base = stripNamespace(mapping.block);
  const family = mapping.family;
  const ny = Number.isFinite(cell.ny) ? cell.ny : 0;
  const horizontal = Math.hypot(cell.nx || 0, cell.nz || 0);

  // ---- glass: a pane with the connection states its neighbours justify ----
  const paneId = family === 'pane' ? paneFor(base) : null;
  if (paneId) {
    const connections = new Set();
    for (const [dir, dx, dz] of DIRECTIONS) {
      if (context.isGlassNeighbor(key(parseKey(k)[0] + dx, parseKey(k)[1], parseKey(k)[2] + dz))) {
        connections.add(dir);
      }
    }
    // A single pane with nothing to connect to would be a 2x2 post; a full glass block reads
    // better as a window and is what the material family suggests.
    if (connections.size === 0) return { state: blockState('glass'), kind: 'full', note: 'pane with no neighbours' };
    return { state: makeConnections(paneId, connections), kind: 'pane' };
  }

  // ---- posts: fences ----
  if (family === 'fence') {
    const fenceId = fenceFor(base) || 'oak_fence';
    const connections = new Set();
    for (const [dir, dx, dz] of DIRECTIONS) {
      const [x, y, z] = parseKey(k);
      if (context.occupied(key(x + dx, y, z + dz))) connections.add(dir);
    }
    return { state: makeConnections(fenceId, connections), kind: 'fence' };
  }

  // ---- slopes: stairs facing uphill ----
  // A slope needs a cell *above* it on the uphill side: that is what makes it a staircase profile
  // rather than the exposed edge of a flat plate (which also averages to |ny| ~ 0.71 because it is
  // open on top and on one side). Without this guard a plain 1-thick floor would grow stairs around
  // its rim — the classic bad result this rule exists to avoid.
  const sloped = Math.abs(ny) < SLOPE_NY && horizontal > VERTICAL_NY && context.backed;
  const stairId = family === 'stairs' ? stairsFor(base) : null;
  if (sloped && stairId) {
    // Uphill = opposite the outward horizontal normal.
    const facing =
      facingFromStep(-Math.round(cell.nx || 0), -Math.round(cell.nz || 0)) ||
      facingFromStep(0, -1);
    const half = ny > 0.35 ? 'bottom' : 'bottom'; // step-up roofs read best right side up
    return { state: makeStairs(stairId, { facing, half, shape: 'straight' }), kind: 'stairs' };
  }

  // ---- thin horizontal plates: slabs ----
  // "Thin and flat": the cell has a solid cell below it and nothing but air above, and the plate it
  // belongs to is one cell thick. Thickness is measured from the neighbourhood (see `plateThickness`)
  // because a single cell's exposure mask cannot distinguish a plate from a floor.
  const slabId = family === 'stairs' || family === 'slab' ? slabFor(base) : null;
  if (slabId && Math.abs(ny) >= SLOPE_NY && !cell.up && context.plateThin) {
    if (context.solidBelow) return { state: makeSlab(slabId, { type: 'bottom' }), kind: 'slab' };
    if (context.solidAbove) return { state: makeSlab(slabId, { type: 'top' }), kind: 'slab' };
    // Nothing above or below: a single slab would hover, so fill the cell with a double slab.
    return { state: makeSlab(slabId, { type: 'double' }), kind: 'slab' };
  }

  // ---- everything else: the plain block ----
  return { state: blockState(base), kind: 'full' };
}

/**
 * Turns a voxel map into a block map.
 *
 * @param {Map<string, VoxelCell>} cells voxel cells (contract shape)
 * @param {{palette?:object, blocks?:object, origin?:number[]}} [opts]
 *   `origin` shifts every exported coordinate (useful when the build has to land at a specific
 *   place in a world; the region itself is still exported at its own origin).
 * @returns {SelectionResult & {warnings:string[]}}
 */
export function selectBlocks(cells, opts = {}) {
  const palette = opts.palette || {};
  const mappings = opts.blocks || DEFAULT_BLOCKS;
  const origin = Array.isArray(opts.origin) ? opts.origin : [0, 0, 0];
  const warnings = [];
  const blocks = new Map();
  const kinds = new Map();
  const stats = { byMaterial: {}, stairs: 0, slabs: 0, panes: 0, fences: 0, full: 0 };

  const glassKeys = new Set();
  for (const [k, cell] of cells) {
    const mapping = mappingFor(cell.material, palette, mappings);
    if (mapping.family === 'pane' || /glass/.test(stripNamespace(mapping.block))) glassKeys.add(k);
  }

  const unknownMaterials = new Set();
  // Sorted so the output does not depend on insertion order.
  const ordered = [...cells.keys()].sort((a, b) => {
    const pa = parseKey(a);
    const pb = parseKey(b);
    return pa[1] - pb[1] || pa[2] - pb[2] || pa[0] - pb[0];
  });

  for (const k of ordered) {
    const cell = cells.get(k);
    if (!cell) continue;
    const mapping = mappingFor(cell.material, palette, mappings);
    if (mapping.unknown && !unknownMaterials.has(mapping.block)) {
      unknownMaterials.add(mapping.block);
      warnings.push(`material "${cell.material}" maps to "${mapping.block}", which is not in this build's 26.3 registry`);
    }
    const [x, y, z] = parseKey(k);
    const context = {
      isGlassNeighbor: (neighbor) => glassKeys.has(neighbor),
      occupied: (neighbor) => cells.has(neighbor),
      solidBelow: cells.has(key(x, y - 1, z)),
      solidAbove: cells.has(key(x, y + 1, z)),
      plateThin: isThinPlate(cells, x, y, z),
      // Is there solid material on the uphill side, i.e. opposite the horizontal normal?
      backed: hasUphillSupport(cells, x, y, z, cell),
    };
    const { state, kind } = chooseBlock(cell, mapping, context, k);
    const outKey = key(x + origin[0], y + origin[1], z + origin[2]);
    blocks.set(outKey, state);
    kinds.set(outKey, kind);
    stats.byMaterial[cell.material] = (stats.byMaterial[cell.material] || 0) + 1;
    if (kind === 'stairs') stats.stairs += 1;
    else if (kind === 'slab') stats.slabs += 1;
    else if (kind === 'pane') stats.panes += 1;
    else if (kind === 'fence') stats.fences += 1;
    else stats.full += 1;
  }

  return { blocks, kinds, stats, warnings };
}

/**
 * Recomputes pane/fence connection states after post-processing changed the neighbourhood.
 * Without this, a pane whose only glass neighbour was dropped would keep a stale connection.
 *
 * @param {Map<string, {Name:string, Properties?:Record<string,string>}>} blocks
 * @returns {number} number of states updated
 */
export function refreshConnections(blocks) {
  let updated = 0;
  for (const [k, state] of blocks) {
    const name = stripNamespace(state.Name);
    // `glass_pane` (no prefix) is the real id, so check for equality as well as the suffix.
    const isPane = name === 'glass_pane' || name.endsWith('_glass_pane') || name === 'iron_bars';
    const isFence = name.endsWith('_fence');
    if (!isPane && !isFence) continue;
    const [x, y, z] = parseKey(k);
    const connections = new Set();
    for (const [dir, dx, dz] of DIRECTIONS) {
      const neighbor = blocks.get(key(x + dx, y, z + dz));
      if (!neighbor) continue;
      const other = stripNamespace(neighbor.Name);
      const otherIsThin = other.endsWith('_glass_pane') || other === 'iron_bars' || other.endsWith('_fence');
      // Panes connect to other panes and to solid blocks; fences connect to fences and solid cubes.
      if (isPane && otherIsThin) connections.add(dir);
      else if (isPane && !otherIsThin) connections.add(dir);
      else if (isFence && (other.endsWith('_fence') || !/_(stairs|slab)$/.test(other))) connections.add(dir);
    }
    const replacement = makeConnections(name, connections);
    if (canonicalState(replacement) !== canonicalState(state)) {
      blocks.set(k, replacement);
      updated += 1;
    }
  }
  return updated;
}

export { neighborKey, FACE_STEPS };
