/**
 * Foundations: guaranteeing that a transferred build stands on something.
 *
 * A scene is designed with `y = 0` as the ground surface, so most builds already reach the ground.
 * Two cases still need help:
 *
 * * an object that starts above ground (a balcony, an arch, a hanging sign) — the builder either
 *   adds a support column or drops the cell, see `postprocess.mjs`;
 * * a build whose lowest layer is a slab/stair shell with holes under it — the ground itself.
 *
 * `addFoundation` lays a plinth under the build's footprint (one or more layers, only where there is
 * nothing already), which is what makes a hollow-shell house sit on solid ground rather than on the
 * void. It is deliberately conservative: it never overwrites an existing block and never widens the
 * footprint beyond the columns that exist.
 */

import { key, parseKey } from './grid.mjs';
import { blockState, validateBlockState } from './states.mjs';

/**
 * The per-column footprint of a block map: for every (x, z) pair, its lowest block.
 *
 * @param {Map<string, any>|Iterable<string>} blocksOrKeys
 * @returns {Map<string, {x:number, z:number, y:number}>} keyed `"x|z"`
 */
export function footprint(blocksOrKeys) {
  const keys = blocksOrKeys instanceof Map ? blocksOrKeys.keys() : blocksOrKeys;
  /** @type {Map<string, {x:number, z:number, y:number}>} */
  const columns = new Map();
  for (const k of keys) {
    const [x, y, z] = parseKey(k);
    const id = `${x}|${z}`;
    const current = columns.get(id);
    if (!current || y < current.y) columns.set(id, { x, z, y });
  }
  return columns;
}

/**
 * Lays a foundation under every column that does not already reach the ground.
 *
 * Two modes, and the difference matters for repeated calls:
 *
 * * `groundY` given — the plinth fills everything between each column and the ground plane. Running
 *   it twice adds nothing, because after the first pass every column already reaches `groundY`.
 * * `groundY` omitted — a fixed `layers`-deep slab under each column. This is a one-shot shaping
 *   pass, not a level: calling it again would treat the new plinth as part of the build and dig one
 *   level deeper, which is why callers in this package always pass `groundY`.
 *
 * @param {Map<string, {Name:string, Properties?:Record<string,string>}>} blocks
 * @param {{
 *   material?:string,     // block id for the plinth (default stone_bricks)
 *   layers?:number,       // how many layers below the lowest block of each column (default 1)
 *   groundY?:number|null, // absolute y the foundation must reach (default: one layer below the build)
 *   thickness?:number     // alias for `layers`
 * }} [opts]
 * @returns {{blocks: Map<string, object>, added:number, warnings:string[]}}
 */
export function addFoundation(blocks, opts = {}) {
  const material = opts.material || 'stone_bricks';
  const layers = Math.max(
    1,
    Math.round(opts.layers ?? opts.thickness ?? 1),
  );
  const check = validateBlockState(blockState(material));
  if (!check.ok) {
    return { blocks, added: 0, warnings: [`foundation material "${material}" is not valid: ${check.errors.join('; ')}`] };
  }
  // Snapshot the columns *before* adding anything: `footprint` reports each column's lowest block,
  // and after the first plinth that would be the plinth itself — which is why the snapshot is what
  // makes repeated calls idempotent.
  const columns = footprint(blocks);
  const warnings = [];
  let added = 0;
  const plinth = blockState(material);
  const groundY = Number.isFinite(opts.groundY) ? Math.trunc(Number(opts.groundY)) : null;
  // With an explicit `groundY`, the plinth is "everything between the build and the ground", which
  // is idempotent by construction: after one call every column already reaches the ground.
  // Without a ground plane, a plinth is a fixed `layers`-deep slab under each column.
  for (const { x, z, y } of columns.values()) {
    const bottom = groundY === null ? y - layers : groundY;
    for (let yy = bottom; yy <= y - 1; yy++) {
      const k = key(x, yy, z);
      if (blocks.has(k)) continue;
      blocks.set(k, { ...plinth, Properties: plinth.Properties ? { ...plinth.Properties } : undefined });
      added += 1;
    }
  }
  if (added) warnings.push(`added a ${material} foundation of ${added} block(s) under the build`);
  return { blocks, added, warnings };
}

/**
 * Reports whether every column of the build reaches the ground, so the build stands on something
 * rather than hovering. Used by `analyzeResult` and by the "no floating blocks" tests.
 *
 * * With an explicit `groundY`, a column is supported when its lowest block sits at `groundY`.
 * * Without one, the build's own lowest layer is treated as the ground, and a column is supported
 *   when it reaches that layer.
 *
 * @param {Map<string, any>} blocks
 * @param {{groundY?:number|null}} [opts]
 * @returns {{ok:boolean, holes:{x:number, z:number, y:number}[], groundY:number|null}}
 */
export function checkFootprintSupport(blocks, opts = {}) {
  const columns = footprint(blocks);
  if (!columns.size) return { ok: true, holes: [], groundY: null };
  let ground =
    Number.isFinite(opts.groundY) ? Math.trunc(Number(opts.groundY)) : Infinity;
  if (!Number.isFinite(ground)) {
    for (const { y } of columns.values()) if (y < ground) ground = y;
  }
  const holes = [];
  for (const { x, z, y } of columns.values()) {
    if (y > ground) holes.push({ x, z, y });
  }
  return { ok: holes.length === 0, holes, groundY: Number.isFinite(ground) ? ground : null };
}

/** Sorted list of the y levels a build occupies, useful for slicing exports. */
export function occupiedLevels(blocks) {
  const levels = new Set();
  for (const k of blocks instanceof Map ? blocks.keys() : blocks) levels.add(parseKey(k)[1]);
  return [...levels].sort((a, b) => a - b);
}
