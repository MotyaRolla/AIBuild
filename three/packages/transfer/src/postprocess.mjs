/**
 * Post-processing: make the emitted block set physically plausible before it is exported.
 *
 * Minecraft has very few *invalid* placements, but it has plenty of *bad* ones: a block hanging in
 * the air, a stair that needs a solid face to sit against, a glass pane with nothing to attach to.
 * The original plugin fixed this with an `OrientationFixer` pass; this module is the same idea,
 * rebuilt around one invariant:
 *
 *   **Every block is connected to the ground through face-adjacent blocks.**
 *
 * Steps, in order:
 *
 * 1. `dropInterior`   — cells with no exposed face carry no information for the build (and would
 *                       be invisible in-game), so they are not exported. They still count as
 *                       support while the passes below run, because the ground of a hollow building
 *                       is its foundation, not its floor.
 * 2. `attachFloating` — find every connected component that does not reach the lowest (ground)
 *                       layer. Small ornaments are attached down/upwards with a *support column*
 *                       of their own material; anything bigger than `maxSupportRun` is reported and
 *                       dropped, because inventing a 12-block pillar would be worse than deleting
 *                       the object. This is the "attach a support below or drop the cell" rule.
 * 3. `repairSupports` — a fence, pane or wall piece with no fixed support loses its thin shape:
 *                       it is replaced by its solid material (glass pane -> glass block). Stairs
 *                       are only allowed where they have a neighbour to back onto; an isolated
 *                       stair becomes its base block. Nothing here ever creates a hole.
 * 4. `normaliseStates` — final validation of every block state against the 26.3 registry.
 *
 * Everything is deterministic: components are visited in sorted key order and the support columns
 * always grow in a fixed direction order.
 */

import {
  FACE_STEPS,
  key,
  parseKey,
  sortedKeys,
} from './grid.mjs';
import {
  blockState,
  isFullCube,
  solidFallback,
  stripNamespace,
  validateBlockState,
} from './states.mjs';

/**
 * @typedef {{
 *   blocks: Map<string, {Name:string, Properties?:Record<string,string>}>,
 *   warnings: string[],
 *   stats: {floating:number, supported:number, dropped:number, reshaped:number, interior:number}
 * }} PostProcessResult
 */

/** Maximum length of a support column the pass is allowed to invent. */
export const DEFAULT_MAX_SUPPORT_RUN = 3;

/**
 * Cells with no exposed face are invisible in-game and are not exported.
 *
 * @param {Map<string, {material:string}>} cells voxel cells (contract shape)
 * @param {Map<string, {Name:string, Properties?:Record<string,string>}>} blocks built blocks
 * @returns {{blocks: Map<string, object>, dropped: string[], warnings: string[]}}
 */
export function dropInterior(cells, blocks) {
  const dropped = [];
  for (const [k, cell] of cells) {
    if (cell && cell.interior === true && blocks.has(k)) {
      dropped.push(k);
      blocks.delete(k);
    }
  }
  return {
    blocks,
    dropped,
    warnings: dropped.length
      ? [`did not export ${dropped.length} fully enclosed cells (invisible in Minecraft)`]
      : [],
  };
}

/**
 * Splits a block map into face-connected components (6-neighbourhood).
 *
 * @param {Map<string, any>|Iterable<string>} blocksOrKeys
 * @returns {string[][]} components, each a sorted list of keys
 */
export function connectedComponents(blocksOrKeys) {
  const present =
    blocksOrKeys instanceof Map ? new Set(blocksOrKeys.keys()) : new Set(blocksOrKeys);
  const seen = new Set();
  /** @type {string[][]} */
  const components = [];
  for (const start of sortedKeys(present)) {
    if (seen.has(start)) continue;
    seen.add(start);
    /** @type {string[]} */
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
    components.push(component.sort(keyOrder));
  }
  return components;
}

/** Stable comparison for keys (y, then z, then x). */
function keyOrder(a, b) {
  const pa = parseKey(a);
  const pb = parseKey(b);
  return pa[1] - pb[1] || pa[2] - pb[2] || pa[0] - pb[0];
}

/**
 * The lowest y of a block map, which is the ground the build stands on.
 * @param {Map<string, any>|Iterable<string>} blocksOrKeys
 * @returns {number}
 */
export function groundLevel(blocksOrKeys) {
  let min = Infinity;
  for (const k of blocksOrKeys instanceof Map ? blocksOrKeys.keys() : blocksOrKeys) {
    const y = parseKey(k)[1];
    if (y < min) min = y;
  }
  return Number.isFinite(min) ? min : 0;
}

/**
 * Attaches every component that does not reach the ground layer.
 *
 * For each floating component the pass tries, in order:
 *   1. a vertical support column straight down from one of its lowest cells, if the column is
 *      short enough and does not pass through existing blocks;
 *   2. a vertical column straight up from one of its highest cells, to hang it from geometry above;
 *   3. otherwise it is dropped and reported.
 *
 * @param {Map<string, {Name:string, Properties?:Record<string,string>}>} blocks
 * @param {{maxSupportRun?:number, ground?:number|null, supportBlock?:string}} [opts]
 * @returns {PostProcessResult}
 */
export function attachFloating(blocks, opts = {}) {
  const maxRun = Number.isFinite(opts.maxSupportRun) ? Math.max(0, opts.maxSupportRun) : DEFAULT_MAX_SUPPORT_RUN;
  const warnings = [];
  const stats = { floating: 0, supported: 0, dropped: 0, reshaped: 0, interior: 0 };
  const ground = Number.isFinite(opts.ground) ? opts.ground : null;
  const components = connectedComponents(blocks);

  // Grounded = touches the lowest layer of the *whole* map (or, when the map has a single
  // component, that component is the ground by definition).
  const level = ground === null ? groundLevel(blocks) : ground;
  const anchored = [];
  for (const component of components) {
    const touchesGround = component.some((k) => parseKey(k)[1] === level);
    if (touchesGround) anchored.push(component);
  }
  // Nothing reaches the ground (a model that starts above y=0, e.g. an exported fragment): the
  // lowest component is the anchor, the rest are floating.
  if (!anchored.length && components.length) {
    let lowest = components[0];
    for (const component of components) {
      if (parseKey(component[0])[1] < parseKey(lowest[0])[1]) lowest = component;
    }
    anchored.push(lowest);
  }
  const anchoredSet = new Set(anchored);
  const floating = components.filter((component) => !anchoredSet.has(component));

  for (const component of floating) {
    stats.floating += 1;
    const occupied = new Set(blocks.keys());
    const supported = supportComponent(component, blocks, occupied, maxRun, level, opts.supportBlock);
    if (supported) {
      stats.supported += 1;
      for (const k of supported) occupied.add(k);
      continue;
    }
    for (const k of component) blocks.delete(k);
    stats.dropped += component.length;
    warnings.push(
      `dropped a floating cluster of ${component.length} block(s) near ${component[0]}: it does not reach the ground and no support column of <= ${maxRun} blocks fits`,
    );
  }

  if (stats.floating) {
    warnings.push(
      `floating clusters: ${stats.floating} found, ${stats.supported} attached with a support column, ${stats.dropped} block(s) dropped`,
    );
  }
  return { blocks, warnings, stats };
}

/**
 * Tries to bridge a floating component to the ground (or to the build above it).
 * Returns the keys of the new support blocks, or null when no column fits.
 *
 * @param {string[]} component
 * @param {Map<string, object>} blocks
 * @param {Set<string>} occupied
 * @param {number} maxRun
 * @param {number} ground
 * @param {string} [supportBlock] override material for the column
 * @returns {string[]|null}
 */
function supportComponent(component, blocks, occupied, maxRun, ground, supportBlock) {
  const lowestY = Math.min(...component.map((k) => parseKey(k)[1]));
  const highestY = Math.max(...component.map((k) => parseKey(k)[1]));
  const bottoms = component.filter((k) => parseKey(k)[1] === lowestY);
  const tops = component.filter((k) => parseKey(k)[1] === highestY);

  // 1) Grow down to the ground or to the first solid block below.
  for (const bottom of bottoms) {
    const [x, y, z] = parseKey(bottom);
    const supports = [];
    let fits = true;
    for (let yy = y - 1; ; yy--) {
      if (yy < ground) {
        fits = false;
        break;
      }
      const k = key(x, yy, z);
      if (occupied.has(k)) break; // landed on something solid
      supports.push(k);
      if (supports.length > maxRun) {
        fits = false;
        break;
      }
    }
    if (fits && supports.length) return materialize(supports, blocks, bottom, supportBlock);
  }

  // 2) Grow up to a block above (hanging ornament, chandelier, lantern chain).
  for (const top of tops) {
    const [x, y, z] = parseKey(top);
    const supports = [];
    let fits = true;
    for (let yy = y + 1; ; yy++) {
      const k = key(x, yy, z);
      if (occupied.has(k)) break;
      supports.push(k);
      if (supports.length > maxRun) {
        fits = false;
        break;
      }
    }
    if (fits && supports.length) return materialize(supports, blocks, top, supportBlock);
  }
  return null;
}

/**
 * Creates the support blocks for a column, using the block they support (so an oak stair hangs
 * from an oak post, not from stone).
 * @param {string[]} supports
 * @param {Map<string, object>} blocks
 * @param {string} anchor the component cell the column was grown from
 * @param {string} [supportBlock]
 * @returns {string[]}
 */
function materialize(supports, blocks, anchor, supportBlock) {
  const anchorState = blocks.get(anchor) || { Name: 'minecraft:stone' };
  const base = supportBlock ? stripNamespace(supportBlock) : solidFallback(anchorState.Name);
  const state = blockState(base);
  for (const k of supports) blocks.set(k, { Name: state.Name });
  return supports;
}

/**
 * Removes thin shapes (stairs, panes, fences, slabs on nothing) that have no neighbour to hold
 * them, replacing them with their solid material instead of deleting them, so the shell stays
 * closed.
 *
 * A Minecraft stair only needs *some* neighbour to look right; the real problem is a stair or pane
 * surrounded by air, which can only happen for a one-cell ornament. Glass panes and fences get
 * special care because their connection states depend on neighbours: a pane whose four horizontal
 * neighbours are all non-panes is replaced by a glass block.
 *
 * @param {Map<string, {Name:string, Properties?:Record<string,string>}>} blocks
 * @returns {PostProcessResult}
 */
export function repairSupports(blocks) {
  const warnings = [];
  const stats = { floating: 0, supported: 0, dropped: 0, reshaped: 0, interior: 0 };
  const reshaped = [];
  // Sorted, so a chain of panes is repaired uniformly and deterministically.
  for (const k of sortedKeys(blocks.keys())) {
    const state = blocks.get(k);
    const name = stripNamespace(state.Name);
    const [x, y, z] = parseKey(k);
    const neighbours = FACE_STEPS.map(([dx, dy, dz]) => blocks.get(key(x + dx, y + dy, z + dz)));
    const solidNeighbours = neighbours.filter((n) => n && isFullCube(n)).length;
    const anyNeighbour = neighbours.filter(Boolean).length;

    // Note the alternation must not require a leading underscore: `glass_pane` and `oak_fence`
    // are the real ids, and `iron_bars` is a pane too.
    const isPane = name === 'glass_pane' || name.endsWith('_glass_pane') ||
      name.endsWith('_fence') || (name.endsWith('_wall') && name !== 'wall');
    if (isPane && solidNeighbours === 0 && anyNeighbour === 0) {
      const replacement = name === 'iron_bars' ? 'iron_block' : 'glass';
      blocks.set(k, blockState(replacement));
      stats.reshaped += 1;
      reshaped.push({ key: k, from: name, to: replacement, reason: 'no neighbour to attach to' });
      continue;
    }

    // A stair floating alone in the air: keep the material, drop the shape.
    const isStair = /_stairs$/.test(name);
    if (isStair && anyNeighbour === 0) {
      const replacement = solidFallback(name);
      blocks.set(k, blockState(replacement));
      stats.reshaped += 1;
      reshaped.push({ key: k, from: name, to: replacement, reason: 'isolated stair' });
      continue;
    }

    // A slab is fine anywhere, but a top slab with nothing underneath reads as a floating shelf.
    if (/_slab$/.test(name) && state.Properties?.type === 'top') {
      const below = blocks.get(key(x, y - 1, z));
      if (!below) {
        blocks.set(k, blockState(name, { type: 'double' }));
        stats.reshaped += 1;
        reshaped.push({ key: k, from: `${name}[type=top]`, to: `${name}[type=double]`, reason: 'top slab without support' });
      }
    }
  }
  if (reshaped.length) {
    warnings.push(`reshaped ${reshaped.length} block(s) that could not keep their thin shape`);
  }
  return { blocks, warnings, stats, reshaped };
}

/**
 * Validates every block state against the 26.3 registry and repairs anything that fails (by
 * falling back to a full block of the same material).
 *
 * @param {Map<string, {Name:string, Properties?:Record<string,string>}>} blocks
 * @returns {{blocks: Map<string, object>, warnings: string[], repaired: number}}
 */
export function normaliseStates(blocks) {
  const warnings = [];
  let repaired = 0;
  for (const [k, state] of blocks) {
    const check = validateBlockState(state);
    if (check.ok) continue;
    const replacement = blockState(solidFallback(state.Name));
    blocks.set(k, replacement);
    repaired += 1;
    if (repaired <= 5) {
      warnings.push(`repaired invalid block state at ${k} (${check.errors.join('; ')}) -> ${replacement.Name}`);
    }
  }
  if (repaired > 5) warnings.push(`repaired ${repaired - 5} more invalid block states`);
  return { blocks, warnings, repaired };
}

/**
 * Runs the whole post-processing chain.
 *
 * @param {Map<string, {Name:string, Properties?:Record<string,string>}>} blocks
 * @param {{maxSupportRun?:number, ground?:number|null, skipInterior?:boolean}} [opts]
 * @returns {PostProcessResult & {errors: string[]}}
 */
export function postProcess(blocks, opts = {}) {
  const warnings = [];
  const errors = [];
  const totals = { floating: 0, supported: 0, dropped: 0, reshaped: 0, interior: 0 };
  const absorb = (result) => {
    warnings.push(...result.warnings);
    for (const k of Object.keys(totals)) totals[k] += result.stats?.[k] ?? 0;
  };

  const floating = attachFloating(blocks, {
    maxSupportRun: opts.maxSupportRun,
    ground: opts.ground,
  });
  absorb(floating);

  const repairs = repairSupports(floating.blocks);
  absorb(repairs);
  totals.reshaped += repairs.reshaped.length - (repairs.stats?.reshaped ?? 0);

  const normalised = normaliseStates(repairs.blocks);
  warnings.push(...normalised.warnings);

  if (!normalised.blocks.size) errors.push('post-processing removed every block');

  return { blocks: normalised.blocks, warnings, errors, stats: { ...totals, reshaped: repairs.reshaped.length } };
}
