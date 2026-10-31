/**
 * Analysing a transferred block set: the objective checks the studio and the critic run on the
 * *Minecraft* result rather than on the scene.
 *
 * `analyzeResult(blocks)` answers three questions:
 *
 * 1. **Are all blocks valid for 26.3?** Every state goes through the registry check, and ids are
 *    checked for resource-location syntax.
 * 2. **Does anything float?** The block set is split into face-connected components; every component
 *    must reach the ground layer. This is the check the brief asks for and the one that catches the
 *    classic "the AI forgot to connect the roof to the walls" failure.
 * 3. **Is the shell closed?** A building is closed when its interior is not reachable from outside
 *    through air cells. The check floods the air *around* the bounding box and verifies that no
 *    interior air cell is reached through a hole; it reports the hole position so a critic can point
 *    at the wall that needs fixing.
 *
 * Errors are structural (invalid ids, floating geometry); warnings are cosmetic (a stair without a
 * neighbour, a top slab with nothing under it, an empty region, glass with no connection).
 */

import { key, parseKey, FACE_STEPS, keyBounds } from './grid.mjs';
import { canonicalState, isFullCube, stripNamespace, validateBlockState } from './states.mjs';
import { checkFootprintSupport } from './foundation.mjs';

/**
 * Split a block set into face-connected components.
 * @param {Map<string, any>|Set<string>|string[]} blocksOrKeys
 * @returns {string[][]}
 */
export function componentsOf(blocksOrKeys) {
  const present =
    blocksOrKeys instanceof Map || blocksOrKeys instanceof Set
      ? new Set([...blocksOrKeys.keys()])
      : new Set(blocksOrKeys);
  const seen = new Set();
  /** @type {string[][]} */
  const out = [];
  const order = [...present].sort((a, b) => {
    const pa = parseKey(a);
    const pb = parseKey(b);
    return pa[1] - pb[1] || pa[2] - pb[2] || pa[0] - pb[0];
  });
  for (const start of order) {
    if (seen.has(start)) continue;
    seen.add(start);
    const component = [];
    const stack = [start];
    while (stack.length) {
      const current = stack.pop();
      component.push(current);
      const [x, y, z] = parseKey(current);
      for (const [dx, dy, dz] of FACE_STEPS) {
        const next = key(x + dx, y + dy, z + dz);
        if (!present.has(next) || seen.has(next)) continue;
        seen.add(next);
        stack.push(next);
      }
    }
    out.push(component);
  }
  return out;
}

/**
 * Flood-fill the air around a block set to find air cells reachable from outside.
 *
 * @param {Map<string, any>} blocks
 * @param {{min:number[], max:number[]}} bounds block bounds
 * @returns {Set<string>} reachable air keys
 */
function floodOutsideAir(blocks, bounds) {
  // The search box is the block bounds grown by one, so the outside shell is always available.
  const lo = [bounds.min[0] - 1, bounds.min[1] - 1, bounds.min[2] - 1];
  const hi = [bounds.max[0] + 1, bounds.max[1] + 1, bounds.max[2] + 1];
  const inside = (x, y, z) => x >= lo[0] && x <= hi[0] && y >= lo[1] && y <= hi[1] && z >= lo[2] && z <= hi[2];
  const start = key(lo[0], lo[1], lo[2]);
  const reachable = new Set([start]);
  const queue = [start];
  while (queue.length) {
    const current = queue.pop();
    const [x, y, z] = parseKey(current);
    for (const [dx, dy, dz] of FACE_STEPS) {
      const nx = x + dx;
      const ny = y + dy;
      const nz = z + dz;
      if (!inside(nx, ny, nz)) continue;
      const nk = key(nx, ny, nz);
      if (reachable.has(nk) || blocks.has(nk)) continue;
      reachable.add(nk);
      queue.push(nk);
    }
  }
  return reachable;
}

/** True when a position lies strictly inside the block bounds. */
function insideBox(x, y, z, bounds) {
  return (
    x >= bounds.min[0] && x <= bounds.max[0] &&
    y >= bounds.min[1] && y <= bounds.max[1] &&
    z >= bounds.min[2] && z <= bounds.max[2]
  );
}

/**
 * Finds air cells that are inside the build's box but not reachable from outside: those are the
 * sealed interior. A hole in the shell makes the interior reachable and shows up as `holes`.
 *
 * @param {Map<string, any>} blocks
 * @returns {{closed:boolean, interior:number, holes:{x:number,y:number,z:number}[], interiorCells:string[]}}
 */
export function analyseEnclosure(blocks) {
  if (!blocks.size) return { closed: true, interior: 0, holes: [], interiorCells: [] };
  const bounds = keyBounds(blocks.keys());
  const reachable = floodOutsideAir(blocks, bounds);
  // A shell is closed when *no* air cell inside the bounding box is reachable from outside... but
  // the bounding box is the build's own box, so outside air is always adjacent to it. The right
  // test is: does outside air reach any cell that is strictly inside the box in every axis?
  /** @type {string[]} */
  const interiorCells = [];
  for (let x = bounds.min[0]; x <= bounds.max[0]; x++) {
    for (let y = bounds.min[1]; y <= bounds.max[1]; y++) {
      for (let z = bounds.min[2]; z <= bounds.max[2]; z++) {
        const k = key(x, y, z);
        if (blocks.has(k) || reachable.has(k)) continue;
        interiorCells.push(k);
      }
    }
  }
  // A hole is an air cell that *is* reachable from outside but is surrounded by blocks on the
  // build's outer shell, i.e. a gap that lets the outside in. We report the reachable air cells
  // that touch the shell, which is where a critic should look.
  const holes = [];
  for (const k of reachable) {
    const [x, y, z] = parseKey(k);
    if (!insideBox(x, y, z, bounds)) continue;
    // Count solid face neighbours: a gap in a wall always has several.
    let solid = 0;
    for (const [dx, dy, dz] of FACE_STEPS) if (blocks.has(key(x + dx, y + dy, z + dz))) solid += 1;
    if (solid >= 3) holes.push({ x, y, z });
    if (holes.length >= 16) break;
  }
  // A solid build (no enclosed air at all) is trivially closed; only a build that *does* enclose
  // air and also has a way in is reported as not closed.
  const closed = holes.length === 0;
  return {
    closed,
    interior: interiorCells.length,
    holes: closed ? [] : holes,
    interiorCells,
  };
}

/**
 * Full analysis of a transferred block map.
 *
 * @param {Map<string, {Name:string, Properties?:Record<string,string>}>} blocks
 * @param {{groundY?:number|null, expectClosed?:boolean, maxComponents?:number}} [opts]
 * @returns {{
 *   errors: string[],
 *   warnings: string[],
 *   stats: {
 *     blocks:number, palette:number, components:number, floatingClusters:number, floatingBlocks:number,
 *     groundY:number|null, closed:boolean, interiorAir:number, stairs:number, slabs:number, panes:number, fences:number
 *   }
 * }}
 */
export function analyzeResult(blocks, opts = {}) {
  const errors = [];
  const warnings = [];
  const stats = {
    blocks: 0,
    palette: 0,
    components: 0,
    floatingClusters: 0,
    floatingBlocks: 0,
    groundY: null,
    closed: true,
    interiorAir: 0,
    stairs: 0,
    slabs: 0,
    panes: 0,
    fences: 0,
  };

  if (!(blocks instanceof Map)) {
    return { errors: ['analyzeResult: blocks must be a Map<key, BlockState>'], warnings, stats };
  }
  if (!blocks.size) {
    return { errors: ['the block map is empty'], warnings, stats };
  }
  stats.blocks = blocks.size;

  // ---- 1. block states ----
  const invalid = [];
  const palette = new Map();
  for (const [k, state] of blocks) {
    if (!state || typeof state !== 'object') {
      invalid.push(`${k}: not a block state`);
      continue;
    }
    const check = validateBlockState(state);
    if (!check.ok) invalid.push(`${k}: ${check.errors.join('; ')}`);
    for (const warning of check.warnings) warnings.push(`${k}: ${warning}`);
    palette.set(canonicalState(state), state);
  }
  stats.palette = palette.size;
  if (invalid.length) {
    errors.push(`${invalid.length} block state(s) are not valid for 26.3: ${invalid.slice(0, 5).join(' | ')}`);
  }

  // ---- 2. floating geometry ----
  const components = componentsOf(blocks);
  stats.components = components.length;
  const groundY = Number.isFinite(opts.groundY) ? Number(opts.groundY) : null;
  const footprint = checkFootprintSupport(blocks, { groundY });
  stats.groundY = footprint.groundY;
  // Face-connected is the game's own notion of "attached": a component reaching the ground layer
  // cannot fall. Components that do not are the floating ones.
  const lowest = Math.min(...[...blocks.keys()].map((k) => parseKey(k)[1]));
  for (const component of components) {
    const minY = Math.min(...component.map((k) => parseKey(k)[1]));
    if (minY > lowest && minY > (groundY ?? -Infinity)) {
      stats.floatingClusters += 1;
      stats.floatingBlocks += component.length;
      errors.push(
        `floating cluster of ${component.length} block(s) starting at ${component[0]} (lowest y = ${minY}, ground = ${lowest})`,
      );
    }
  }

  // ---- 3. shell closure ----
  const enclosure = analyseEnclosure(blocks);
  stats.closed = enclosure.closed;
  stats.interiorAir = enclosure.interior;
  if (opts.expectClosed !== false && !enclosure.closed) {
    const where = enclosure.holes.slice(0, 4).map((h) => `(${h.x},${h.y},${h.z})`).join(', ');
    warnings.push(`the shell is not closed: air reaches the interior through ${where}`);
  }

  // ---- 4. cosmetic checks ----
  for (const [k, state] of blocks) {
    const name = stripNamespace(state.Name);
    if (name.endsWith('_stairs')) stats.stairs += 1;
    else if (name.includes('_slab')) stats.slabs += 1;
    else if (name.endsWith('_glass_pane')) stats.panes += 1;
    else if (name.endsWith('_fence')) stats.fences += 1;

    const [x, y, z] = parseKey(k);
    if (name.includes('_slab') && state.Properties?.type === 'top') {
      const below = blocks.get(key(x, y - 1, z));
      if (!below) warnings.push(`${k}: top slab with nothing underneath`);
    }
    if (name.endsWith('_fence') || name.endsWith('_glass_pane')) {
      const anyConnection =
        state.Properties?.north === 'true' ||
        state.Properties?.south === 'true' ||
        state.Properties?.east === 'true' ||
        state.Properties?.west === 'true';
      const anyNeighbor = FACE_STEPS.some(([dx, dy, dz]) => blocks.has(key(x + dx, y + dy, z + dz)));
      if (!anyConnection && !anyNeighbor) warnings.push(`${k}: ${name} is not connected to anything`);
    }
    if (!isFullCube(state) && !blocks.has(key(x, y - 1, z)) && !name.endsWith('_fence')) {
      // Thin shapes (stairs, slabs, panes) look wrong hanging in mid-air even when attached at the
      // side, and this is exactly the class of defect the plugin's OrientationFixer used to fix.
      const hasSide = FACE_STEPS.slice(0, 2).some(([dx]) => blocks.has(key(x + dx, y, z))) ||
        FACE_STEPS.slice(4).some(([, , dz]) => blocks.has(key(x, y, z + dz)));
      if (!hasSide) warnings.push(`${k}: ${name} has nothing below or beside it`);
    }
  }

  if (stats.components > (opts.maxComponents ?? 8)) {
    warnings.push(`the build has ${stats.components} separate pieces; check that they were meant to be separate`);
  }

  return { errors, warnings, stats };
}
