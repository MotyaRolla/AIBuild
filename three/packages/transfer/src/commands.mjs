/**
 * `/setblock` command generation.
 *
 * This is the fast path for the Paper plugin: instead of parsing a schematic, the server just runs
 * one command per block. Two rules matter for correctness:
 *
 * 1. Every line must be a syntactically valid 26.3 command:
 *
 *        /setblock <x> <y> <z> minecraft:<id>[<prop>=<value>,...] replace
 *
 *    Coordinates are absolute block coordinates (no `~`), the id is namespaced, properties are
 *    sorted so the output is byte-stable, and `replace` is explicit (the default mode, but being
 *    explicit keeps the line valid under `/execute ... run` too).
 * 2. The order must not matter: a support column may be written before or after the block it
 *    supports, so the list is sorted bottom-up (then north-to-south, then west-to-east) and the
 *    plugin is expected to run the lines without physics updates (or set the gamerule). Commands
 *    are also chunked into batches, because a chat/command packet has a length limit.
 */

import { parseKey, sortedKeys } from './grid.mjs';
import { stateToString, validateBlockState } from './states.mjs';

/** Default commands per batch; the plugin sends one packet per batch. */
export const DEFAULT_BATCH_SIZE = 64;

/** Minecraft's per-command length limit is generous; this keeps batches safely under it. */
export const MAX_COMMAND_LENGTH = 32_000;

/**
 * Formats one `/setblock` line for a block state.
 *
 * @param {{Name:string, Properties?:Record<string,string>}} state
 * @param {number} x
 * @param {number} y
 * @param {number} z
 * @returns {string}
 */
export function setblockCommand(state, x, y, z) {
  return `/setblock ${Math.trunc(x)} ${Math.trunc(y)} ${Math.trunc(z)} ${stateToString(state)} replace`;
}

/**
 * Builds one `/setblock` command per block, in a deterministic order.
 *
 * @param {Map<string, {Name:string, Properties?:Record<string,string>}>} blocks
 * @param {{verify?:boolean, origin?:number[]}} [opts] `verify` rejects invalid states instead of emitting them
 * @returns {string[]}
 */
export function toCommands(blocks, opts = {}) {
  if (!(blocks instanceof Map)) throw new TypeError('toCommands: blocks must be a Map<key, BlockState>');
  const verify = opts.verify !== false;
  const origin = Array.isArray(opts.origin) ? opts.origin : [0, 0, 0];
  const commands = [];
  for (const k of sortedKeys(blocks.keys())) {
    const state = blocks.get(k);
    if (verify) {
      const check = validateBlockState(state);
      if (!check.ok) {
        throw new Error(`toCommands: invalid block state at ${k}: ${check.errors.join('; ')}`);
      }
    }
    const [x, y, z] = parseKey(k);
    commands.push(setblockCommand(state, x + origin[0], y + origin[1], z + origin[2]));
  }
  return commands;
}

/**
 * Splits a command list into batches of at most `batchSize` lines (and at most `maxLength` chars).
 * @param {string[]} commands
 * @param {{batchSize?:number, maxLength?:number}} [opts]
 * @returns {string[][]}
 */
export function batchCommands(commands, opts = {}) {
  const batchSize = Number.isFinite(opts.batchSize) ? Math.max(1, opts.batchSize) : DEFAULT_BATCH_SIZE;
  const maxLength = Number.isFinite(opts.maxLength) ? opts.maxLength : MAX_COMMAND_LENGTH;
  /** @type {string[][]} */
  const batches = [];
  let current = [];
  let length = 0;
  for (const command of commands) {
    if (current.length >= batchSize || (current.length && length + command.length + 1 > maxLength)) {
      batches.push(current);
      current = [];
      length = 0;
    }
    current.push(command);
    length += command.length + 1;
  }
  if (current.length) batches.push(current);
  return batches;
}

/**
 * Validates a `/setblock` line against the 26.3 grammar. Used by the tests, and by the plugin when
 * it receives a batch from the studio.
 *
 * @param {string} line
 * @returns {{ok:boolean, error?:string, parsed?:{x:number, y:number, z:number, state:string, mode:string}}}
 */
export function parseSetblock(line) {
  const match = /^\/setblock (-?\d+) (-?\d+) (-?\d+) ([a-z0-9_]+:[a-z0-9_/]+)(?:\[([a-z0-9_]+=[a-z0-9_]+,?)*\])?(?: (replace|keep|destroy))?$/.exec(
    String(line).trim(),
  );
  if (!match) return { ok: false, error: `not a /setblock line: ${JSON.stringify(line)}` };
  const [, x, y, z, id, , mode] = match;
  return {
    ok: true,
    parsed: { x: Number(x), y: Number(y), z: Number(z), state: id, mode: mode || 'replace' },
  };
}

/**
 * A `.mcfunction` file body (one command per line, no leading slash — functions are bare commands).
 * @param {Map<string, object>} blocks
 * @returns {string}
 */
export function toMcFunction(blocks) {
  return `${toCommands(blocks)
    .map((line) => line.replace(/^\//, ''))
    .join('\n')}\n`;
}
