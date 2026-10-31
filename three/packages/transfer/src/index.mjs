/**
 * @aibuild/transfer — voxelise a StructureSpec and move it into Minecraft.
 *
 * Pipeline
 * --------
 *   spec ──voxelise──> cells ──select blocks──> block states ──post-process──> blocks
 *                                                                                 │
 *                          ┌──────────────────────────────┬───────────────────────┤
 *                          ▼                              ▼                       ▼
 *                    toLitematic()                 toCommands()             analyzeResult()
 *
 * The package owns one user-facing function per artefact and one for the checks:
 *
 *   transferToMinecraft(spec, opts)  spec  -> {blocks, stats, warnings}
 *   toLitematic(blocks, meta)        blocks -> gzipped NBT Buffer (.litematic)
 *   toCommands(blocks)               blocks -> string[] of /setblock lines
 *   analyzeResult(blocks)            blocks -> {errors, warnings}
 *
 * Nothing here imports three.js, and no step needs a network or a browser: the studio, the
 * orchestrator and the plugin all drive the same pure functions.
 *
 * Coordinate systems
 * ------------------
 * Scene coordinates and Minecraft coordinates are identical (+x east, +y up, +z south), one scene
 * unit = one block, and `y = 0` is the ground surface. `origin` shifts the *exported* coordinates
 * without moving the design, which is how a build is placed at a specific spot in a world.
 */

import { normalizeSpec, specBounds, validateSpec } from '../../core/src/spec.mjs';
import { DEFAULT_BLOCKS } from '../../core/src/materials.mjs';
import { keyBounds, parseKey, sortedKeys } from './grid.mjs';
import { voxelizeStructure, voxelizerSource, fallbackVoxelize, DIR } from './voxel.mjs';
import { selectBlocks, mappingFor, refreshConnections } from './select.mjs';
import { addFoundation } from './foundation.mjs';
import { postProcess, attachFloating, connectedComponents, groundLevel } from './postprocess.mjs';
import { analyzeResult } from './analyze.mjs';
import { toLitematic, readLitematic, validateLitematic, buildLitematic, litematicSummary } from './litematic.mjs';
import { toCommands, batchCommands, setblockCommand, toMcFunction, parseSetblock } from './commands.mjs';

/**
 * @typedef {import('./voxel.mjs').VoxelCell} VoxelCell
 * @typedef {{Name:string, Properties?:Record<string,string>}} BlockState
 */

/**
 * @typedef {{
 *   origin?: number[],
 *   palette?: object,
 *   blocks?: object,
 *   options?: {
 *     foundation?: boolean|string,
 *     foundationLayers?: number,
 *     maxSupportRun?: number,
 *     includeHidden?: boolean,
 *     voxelizer?: Function,
 *     core?: boolean,
 *     groundY?: number|null,
 *     hollowInterior?: boolean
 *   },
 *   foundation?: boolean|string,
 *   maxSupportRun?: number,
 *   includeHidden?: boolean,
 *   voxelizer?: Function,
 *   core?: boolean,
 *   validate?: boolean
 * }} TransferOptions
 */

/**
 * The full pipeline, with every intermediate result kept. This is what the studio shows when a
 * user clicks "transfer to Minecraft" and wants to see why a block was chosen.
 *
 * @param {object} rawSpec a StructureSpec (plain JSON)
 * @param {TransferOptions} [opts]
 * @returns {{
 *   spec: object,
 *   cells: Map<string, VoxelCell>,
 *   blocks: Map<string, BlockState>,
 *   kinds: Map<string, string>,
 *   stats: object,
 *   warnings: string[],
 *   errors: string[],
 *   voxelizer: string,
 *   analysis: {errors:string[], warnings:string[], stats:object}
 * }}
 */
export function transferToMinecraftDetailed(rawSpec, opts = {}) {
  const warnings = [];
  const errors = [];
  const spec = normalizeSpec(rawSpec || {});
  const validation = validateSpec(spec);
  warnings.push(...validation.warnings);
  // Spec errors are warnings here: transfer is also used to inspect a half-finished design.
  if (!validation.ok) {
    warnings.push(`spec validation reported ${validation.errors.length} problem(s); transferring anyway`);
    errors.push(...validation.errors);
  }

  const options = { ...(opts.options || {}), ...flattenOptions(opts) };
  const origin = normalizeOrigin(opts.origin);

  // ---- 1. voxels ----
  const { cells, source, warnings: voxelWarnings } = voxelizeStructure(spec, {
    voxelizer: options.voxelizer,
    includeHidden: options.includeHidden !== false,
    core: options.core,
  });
  warnings.push(...voxelWarnings);
  if (!cells.size) {
    return {
      spec,
      cells,
      blocks: new Map(),
      kinds: new Map(),
      stats: emptyStats(),
      warnings,
      errors: [...errors, 'the spec produced no voxels; nothing to transfer'],
      voxelizer: source,
      analysis: { errors: [], warnings: [], stats: null },
    };
  }

  // ---- 2. blocks ----
  // `includeHidden: false` drops enclosed cells, but then the ground a wall stands on disappears
  // and the wall is "floating". Keep the full cell set for transfers, and let the block map drop
  // invisible cells only after the support pass has used them.
  const selection = selectBlocks(cells, {
    palette: opts.palette || spec.palette,
    blocks: opts.blocks || DEFAULT_BLOCKS,
    origin,
  });
  warnings.push(...selection.warnings);
  const blocks = selection.blocks;
  const kinds = selection.kinds;

  // ---- 3. post-process ----
  const processed = postProcess(blocks, {
    maxSupportRun: options.maxSupportRun,
    ground: Number.isFinite(options.groundY) ? Number(options.groundY) + origin[1] : null,
  });
  warnings.push(...processed.warnings);
  if (processed.stats.interior) {
    // Counted by `dropInterior` when it is used; the contract stat stays at 0 otherwise.
  }
  let finalBlocks = processed.blocks;

  // Interior cells are invisible in-game; export them only when the caller insists.
  if (options.hollowInterior === true) {
    let dropped = 0;
    for (const [k, cell] of cells) {
      const outKey = key(
        parseKey(k)[0] + origin[0],
        parseKey(k)[1] + origin[1],
        parseKey(k)[2] + origin[2],
      );
      if (cell.interior === true && finalBlocks.has(outKey)) {
        finalBlocks.delete(outKey);
        dropped += 1;
      }
    }
    if (dropped) warnings.push(`did not export ${dropped} fully enclosed cells`);
  }

  // ---- 4. foundation ----
  const foundation = options.foundation;
  if (foundation) {
    const material = typeof foundation === 'string' ? foundation : 'stone_bricks';
    const result = addFoundation(finalBlocks, {
      material,
      layers: options.foundationLayers ?? 1,
    });
    warnings.push(...result.warnings);
    finalBlocks = result.blocks;
  }

  // Connections were computed before post-processing; refresh them against the final map.
  refreshConnections(finalBlocks);

  // ---- 5. analysis ----
  const analysis = analyzeResult(finalBlocks, {
    groundY: Number.isFinite(options.groundY) ? Number(options.groundY) + origin[1] : null,
  });

  const stats = {
    cells: cells.size,
    blocks: finalBlocks.size,
    byType: { ...selection.stats.byMaterial },
    floating: processed.stats.floating,
    stairs: selection.stats.stairs,
    slabs: selection.stats.slabs,
    panes: selection.stats.panes,
    fences: selection.stats.fences,
    interior: countInterior(cells),
    components: analysis.stats.components,
    sealed: analysis.stats.closed,
    bounds: keyBounds(finalBlocks.keys()),
    voxelizer: source,
  };

  return {
    spec,
    cells,
    blocks: finalBlocks,
    kinds,
    stats,
    warnings,
    errors,
    voxelizer: source,
    analysis,
  };
}

/**
 * Voxelise a spec and transfer it into Minecraft blocks.
 *
 * @param {object} spec a StructureSpec (plain JSON; it is normalised, not modified)
 * @param {TransferOptions} [opts]
 * @returns {{
 *   blocks: Map<string, BlockState>,
 *   stats: {cells:number, blocks:number, byType:Record<string,number>, floating:number, stairs:number,
 *           slabs:number, panes:number, fences:number, interior:number, components:number,
 *           sealed:boolean, bounds:{min:number[],max:number[],size:number[]}, voxelizer:string},
 *   warnings: string[]
 * }}
 */
export function transferToMinecraft(spec, opts = {}) {
  const detailed = transferToMinecraftDetailed(spec, opts);
  return { blocks: detailed.blocks, stats: detailed.stats, warnings: detailed.warnings };
}

/** Flattens the convenience options that may sit at the top level or inside `options`. */
function flattenOptions(opts) {
  /** @type {Record<string, any>} */
  const out = {};
  for (const name of [
    'foundation',
    'foundationLayers',
    'maxSupportRun',
    'includeHidden',
    'voxelizer',
    'core',
    'groundY',
    'hollowInterior',
  ]) {
    if (opts[name] !== undefined) out[name] = opts[name];
  }
  return out;
}

/** Validates and normalises `origin` into an integer triple. */
function normalizeOrigin(origin) {
  if (!Array.isArray(origin) || origin.length < 3) return [0, 0, 0];
  const out = origin.slice(0, 3).map((n) => (Number.isFinite(n) ? Math.trunc(n) : 0));
  while (out.length < 3) out.push(0);
  return out;
}

/** Number of fully enclosed cells in a voxel map. */
function countInterior(cells) {
  let count = 0;
  for (const [, cell] of cells) if (cell.interior === true) count += 1;
  return count;
}

/** The `stats` shape for an empty transfer, so callers never have to null-check. */
function emptyStats() {
  return {
    cells: 0,
    blocks: 0,
    byType: {},
    floating: 0,
    stairs: 0,
    slabs: 0,
    panes: 0,
    fences: 0,
    interior: 0,
    components: 0,
    sealed: false,
    bounds: { min: [0, 0, 0], max: [0, 0, 0], size: [0, 0, 0] },
    voxelizer: voxelizerSource(),
  };
}

export {
  // voxels
  voxelizeStructure,
  fallbackVoxelize,
  DIR,
  // blocks
  selectBlocks,
  mappingFor,
  refreshConnections,
  // post-processing
  postProcess,
  attachFloating,
  connectedComponents,
  groundLevel,
  addFoundation,
  // export
  toLitematic,
  readLitematic,
  validateLitematic,
  buildLitematic,
  litematicSummary,
  toCommands,
  batchCommands,
  setblockCommand,
  parseSetblock,
  toMcFunction,
  // analysis
  analyzeResult,
  // geometry helpers
  keyBounds,
  parseKey,
  sortedKeys,
  // spec helpers, re-exported so callers do not need to reach into core
  normalizeSpec,
  specBounds,
  validateSpec,
  DEFAULT_BLOCKS,
};
