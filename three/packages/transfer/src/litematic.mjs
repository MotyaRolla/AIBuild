/**
 * `.litematic` export and import (Litematica schematic format).
 *
 * The format is gzip-compressed NBT with a compound root:
 *
 *   {
 *     MinecraftDataVersion: <int>          // 5023 for 26.3
 *     Version:             <int>           // Litematica schematic version (6 for 1.20.4-era data)
 *     SubVersion:          <int>           // written for completeness, ignored on read
 *     Metadata: {
 *       Name, Author, Description: string
 *       EnclosingSize:  {x, y, z}
 *       TotalBlocks, TotalVolume, RegionCount: int
 *       TimeCreated, TimeModified: long (unix ms)
 *     }
 *     Regions: {
 *       <name>: {
 *         Position: {x, y, z}             // primary corner, world coordinates
 *         Size:     {x, y, z}             // signed vector to the opposite corner
 *         BlockStatePalette: [ {Name, Properties} ... ]
 *         BlockStates: <long[]>           // 4096-bit maximum, packed continuously
 *         Entities: [], TileEntities: [], PendingBlockTicks: [], PendingFluidTicks: []
 *       }
 *     }
 *   }
 *
 * `BlockStates` is *not* "one long per 64/entriesPerLong" style (which is what modern chunk data
 * uses); Litematica packs entries continuously across the whole long array, with an entry allowed
 * to straddle a long boundary. `packBits`/`unpackBits` implement exactly that, and the round-trip
 * test compares against the unpacked values rather than against bytes, so a future change to the
 * packing scheme would be caught.
 *
 * Palette rules that Litematica relies on:
 *   * index 0 is always `minecraft:air`, even when the region has no air;
 *   * the bit width starts at 2 and grows only when the palette outgrows the current width;
 *   * block indices use Y-major order: `index = (y * sizeZ + z) * sizeX + x`.
 */

import { gzipSync, gunzipSync } from 'node:zlib';
import { readNbt, writeNbt } from './nbt.mjs';
import { key, parseKey } from './grid.mjs';
import { blockState, canonicalState, validateBlockState } from './states.mjs';
import { DATA_VERSION_26_3 } from './states.mjs';

/** Litematica's schematic version written into files. 6 is the widely supported 1.20.4-era layout. */
export const LITEMATIC_VERSION = 6;

/** Litematica's `SubVersion`, written for completeness. */
export const LITEMATIC_SUBVERSION = 1;

/** The air entry every palette starts with. */
export const AIR = Object.freeze({ Name: 'minecraft:air' });

/**
 * @typedef {{
 *   name?:string, author?:string, description?:string,
 *   timeCreated?:number, timeModified?:number, regionName?:string,
 *   dataVersion?:number, version?:number
 * }} LitematicMeta
 */

/**
 * Bit width needed to store `count` palette indices (Litematica starts at 2).
 * @param {number} count number of palette entries
 * @returns {number}
 */
export function bitsForPalette(count) {
  let bits = 2;
  while (1 << bits < count) bits += 1;
  return Math.min(32, bits);
}

/**
 * Packs integer values into a `BigInt64Array` of 64-bit words, exactly like Litematica's
 * `LitematicaBitArray`: entry `i` starts at bit `i * bitsPerEntry` and may straddle two longs.
 *
 * @param {ArrayLike<number>} values
 * @param {number} bitsPerEntry 1..32
 * @returns {BigInt64Array}
 */
export function packBits(values, bitsPerEntry) {
  if (!Number.isInteger(bitsPerEntry) || bitsPerEntry < 1 || bitsPerEntry > 32) {
    throw new RangeError(`bitsPerEntry must be 1..32, got ${bitsPerEntry}`);
  }
  const count = values.length;
  const wordCount = Math.ceil((count * bitsPerEntry) / 64);
  const words = new BigInt64Array(wordCount);
  const mask = (1n << BigInt(bitsPerEntry)) - 1n;
  for (let i = 0; i < count; i++) {
    const value = BigInt(Math.trunc(Number(values[i])) || 0) & mask;
    const startBit = i * bitsPerEntry;
    const wordIndex = Math.floor(startBit / 64);
    const bitOffset = startBit % 64;
    words[wordIndex] |= value << BigInt(bitOffset);
    const spill = bitOffset + bitsPerEntry - 64;
    if (spill > 0) {
      // The high `spill` bits continue in the next word, at its low end.
      words[wordIndex + 1] |= value >> BigInt(bitsPerEntry - spill);
    }
  }
  return words;
}

/**
 * Unpacks integer values from a `BigInt64Array` written by `packBits` / Litematica.
 *
 * @param {BigInt64Array|bigint[]} words
 * @param {number} bitsPerEntry
 * @param {number} count number of entries to read
 * @returns {number[]}
 */
export function unpackBits(words, bitsPerEntry, count) {
  if (!Number.isInteger(bitsPerEntry) || bitsPerEntry < 1 || bitsPerEntry > 32) {
    throw new RangeError(`bitsPerEntry must be 1..32, got ${bitsPerEntry}`);
  }
  const out = new Array(count);
  const mask = (1n << BigInt(bitsPerEntry)) - 1n;
  // BigInt shifts are arithmetic, so a word whose sign bit is set (normal as soon as one 64-bit
  // word carries more than one entry) would sign-extend and corrupt the value. Reinterpreting each
  // word as *unsigned* before shifting is what makes the unpack bit-exact.
  const asBig = (i) => (i < words.length ? BigInt.asUintN(64, BigInt(words[i])) : 0n);
  for (let i = 0; i < count; i++) {
    const startBit = i * bitsPerEntry;
    const wordIndex = Math.floor(startBit / 64);
    const bitOffset = startBit % 64;
    const endOffset = 64 - bitOffset;
    let value;
    if (endOffset >= bitsPerEntry) {
      value = (asBig(wordIndex) >> BigInt(bitOffset)) & mask;
    } else {
      value =
        ((asBig(wordIndex) >> BigInt(bitOffset)) | (asBig(wordIndex + 1) << BigInt(endOffset))) & mask;
    }
    out[i] = Number(value);
  }
  return out;
}

/**
 * Builds the enclosing box of a block map.
 * @param {Map<string, any>} blocks
 * @returns {{min:[number,number,number], max:[number,number,number], size:[number,number,number]}}
 */
export function blocksBounds(blocks) {
  const min = [Infinity, Infinity, Infinity];
  const max = [-Infinity, -Infinity, -Infinity];
  for (const k of blocks.keys()) {
    const [x, y, z] = parseKey(k);
    if (x < min[0]) min[0] = x;
    if (y < min[1]) min[1] = y;
    if (z < min[2]) min[2] = z;
    if (x > max[0]) max[0] = x;
    if (y > max[1]) max[1] = y;
    if (z > max[2]) max[2] = z;
  }
  if (!Number.isFinite(min[0])) return { min: [0, 0, 0], max: [0, 0, 0], size: [0, 0, 0] };
  return { min, max, size: [max[0] - min[0] + 1, max[1] - min[1] + 1, max[2] - min[2] + 1] };
}

/**
 * Builds the export payload (uncompressed NBT object) for a block map.
 * Exposed separately from `toLitematic` so tests and the studio can inspect the structure.
 *
 * @param {Map<string, {Name:string, Properties?:Record<string,string>}>} blocks
 * @param {LitematicMeta} [meta]
 * @returns {{root: object, region: {name:string, position:number[], size:number[], palette:object[], volume:number, used:number}}}
 */
export function buildLitematic(blocks, meta = {}) {
  if (!(blocks instanceof Map)) throw new TypeError('toLitematic: blocks must be a Map<key, BlockState>');
  if (!blocks.size) throw new Error('toLitematic: refusing to export an empty block map');

  const regionName = sanitizeRegionName(meta.regionName || meta.name || 'AIBuild');
  const bounds = blocksBounds(blocks);
  const [sizeX, sizeY, sizeZ] = bounds.size;
  const volume = sizeX * sizeY * sizeZ;
  if (volume <= 0) throw new Error('toLitematic: the block map has an empty bounding box');
  if (sizeX > 256 || sizeY > 256 || sizeZ > 256) {
    // Litematica itself handles larger regions, but a >256 cube is almost always a coordinate bug
    // (an object accidentally placed at 0 instead of near the build) and would blow up the file.
    throw new Error(
      `toLitematic: region ${sizeX}x${sizeY}x${sizeZ} is larger than 256 in a dimension; check the spec coordinates`,
    );
  }

  // ---- palette: air first, then every used state in canonical (sorted) order ----
  const states = new Map();
  for (const [, state] of blocks) {
    const canonical = canonicalState(state);
    if (!states.has(canonical)) states.set(canonical, state);
  }
  const palette = [AIR];
  const indexByCanonical = new Map([[canonicalState(AIR), 0]]);
  for (const canonical of [...states.keys()].sort()) {
    const state = states.get(canonical);
    indexByCanonical.set(canonical, palette.length);
    palette.push(state.Properties && Object.keys(state.Properties).length
      ? { Name: state.Name, Properties: { ...state.Properties } }
      : { Name: state.Name });
  }

  const bitsPerEntry = bitsForPalette(palette.length);
  const indices = new Array(volume).fill(0);
  for (const [k, state] of blocks) {
    const [x, y, z] = parseKey(k);
    const lx = x - bounds.min[0];
    const ly = y - bounds.min[1];
    const lz = z - bounds.min[2];
    indices[(ly * sizeZ + lz) * sizeX + lx] = indexByCanonical.get(canonicalState(state)) ?? 0;
  }

  const now = Date.now();
  const root = {
    MinecraftDataVersion: meta.dataVersion ?? DATA_VERSION_26_3,
    Version: meta.version ?? LITEMATIC_VERSION,
    SubVersion: LITEMATIC_SUBVERSION,
    Metadata: {
      Name: String(meta.name ?? 'AIBuild structure'),
      Author: String(meta.author ?? 'AIBuild 3D'),
      Description: String(meta.description ?? 'Generated by @aibuild/transfer'),
      EnclosingSize: { x: sizeX, y: sizeY, z: sizeZ },
      TotalBlocks: blocks.size,
      TotalVolume: volume,
      RegionCount: 1,
      TimeCreated: toLongMs(meta.timeCreated, now),
      TimeModified: toLongMs(meta.timeModified, now),
    },
    Regions: {
      [regionName]: {
        Position: { x: bounds.min[0], y: bounds.min[1], z: bounds.min[2] },
        Size: { x: sizeX, y: sizeY, z: sizeZ },
        BlockStatePalette: palette,
        BlockStates: packBits(indices, bitsPerEntry),
        Entities: [],
        TileEntities: [],
        PendingBlockTicks: [],
        PendingFluidTicks: [],
      },
    },
  };

  return {
    root,
    region: {
      name: regionName,
      position: bounds.min,
      size: bounds.size,
      palette,
      volume,
      used: blocks.size,
      bitsPerEntry,
    },
  };
}

/**
 * Coerces a timestamp (Date, bigint, number or undefined) into a bigint long tag.
 * @param {unknown} value
 * @param {number} fallback unix milliseconds
 * @returns {bigint}
 */
function toLongMs(value, fallback) {
  if (typeof value === 'bigint') return value;
  if (value instanceof Date) return BigInt(value.getTime());
  if (typeof value === 'number' && Number.isFinite(value)) return BigInt(Math.trunc(value));
  return BigInt(Math.trunc(fallback));
}

/** Region names must be non-empty and must not contain characters that break NBT clients. */
function sanitizeRegionName(name) {
  const cleaned = String(name).replace(/[^\w .-]+/g, '_').trim();
  return cleaned || 'AIBuild';
}

/**
 * Serialises a block map as a `.litematic` file: gzip-compressed NBT.
 *
 * @param {Map<string, {Name:string, Properties?:Record<string,string>}>} blocks
 * @param {LitematicMeta} [meta]
 * @returns {Buffer}
 */
export function toLitematic(blocks, meta = {}) {
  const { root } = buildLitematic(blocks, meta);
  const raw = writeNbt(root);
  // Level 6 is Litematica's own default (NbtIo.writeCompressed uses Java's default deflate);
  // the exact level does not matter for compatibility, only that the stream is plain gzip.
  return gzipSync(raw, { level: 6 });
}

/**
 * Reads a `.litematic` buffer back into blocks.
 *
 * Region `Position`/`Size` are honoured (including negative sizes), so files written by Litematica
 * itself load with the same world coordinates they describe.
 *
 * @param {Buffer|Uint8Array} buffer gzip-compressed `.litematic`
 * @returns {{
 *   blocks: Map<string, {Name:string, Properties?:Record<string,string>}>,
 *   metadata: object, regions: {name:string, position:number[], size:number[], bitsPerEntry:number,
 *   paletteSize:number, blockCount:number}[], format: {version:number, minecraftDataVersion:number}
 * }}
 */
export function readLitematic(buffer) {
  const raw = gunzipSync(buffer);
  const { value } = readNbt(raw);
  if (!value || typeof value !== 'object') throw new Error('readLitematic: root tag is not a compound');
  const version = Number(value.Version ?? 0);
  if (version < 1 || version > 32) throw new Error(`readLitematic: unsupported schematic Version ${version}`);
  const regionsTag = value.Regions;
  if (!regionsTag || typeof regionsTag !== 'object') throw new Error('readLitematic: missing Regions');

  /** @type {Map<string, any>} */
  const blocks = new Map();
  const regions = [];
  for (const [name, region] of Object.entries(regionsTag)) {
    if (!region || typeof region !== 'object') continue;
    const palette = Array.isArray(region.BlockStatePalette) ? region.BlockStatePalette : [];
    if (!palette.length) continue;
    const words = region.BlockStates;
    if (!words || typeof words.length !== 'number') continue;
    const position = readVec(region.Position);
    const size = readVec(region.Size);
    const dims = size.map((n) => {
      const v = Math.trunc(n);
      return v === 0 ? 1 : Math.abs(v);
    });
    // Litematica stores `Size` as an inclusive span vector: +n means n cells towards +axis, -n
    // means n cells towards -axis, and there is no -1 (a single cell is 1 on either side).
    // The container itself always covers |Size| cells starting from the minimum corner.
    const origin = [0, 1, 2].map((i) => minCorner(position[i], Math.trunc(size[i])));
    const volume = dims[0] * dims[1] * dims[2];
    if (!volume) continue;
    // The bit width is not stored explicitly; it is implied by the palette size, exactly as
    // Litematica derives it when creating the container from a file.
    const bitsPerEntry = bitsForPalette(palette.length);
    const expectedWords = Math.ceil((volume * bitsPerEntry) / 64);
    if (words.length < expectedWords) {
      throw new Error(
        `readLitematic: region "${name}" has ${words.length} long(s) but ${volume} blocks at ${bitsPerEntry} bits need ${expectedWords}`,
      );
    }
    const indices = unpackBits(words, bitsPerEntry, volume);
    for (let i = 0; i < volume; i++) {
      const paletteIndex = indices[i];
      const entry = palette[paletteIndex];
      if (!entry || !entry.Name) continue;
      if (entry.Name === 'minecraft:air' || entry.Name === 'air') continue;
      const lx = i % dims[0];
      const rest = (i - lx) / dims[0];
      const lz = rest % dims[2];
      const ly = (rest - lz) / dims[2];
      blocks.set(key(origin[0] + lx, origin[1] + ly, origin[2] + lz), normalizePaletteEntry(entry));
    }
    regions.push({
      name,
      position: origin,
      size: dims,
      bitsPerEntry,
      paletteSize: palette.length,
      blockCount: blocks.size,
    });
  }

  const metadata = value.Metadata && typeof value.Metadata === 'object' ? { ...value.Metadata } : {};
  return {
    blocks,
    metadata,
    regions,
    format: { version, minecraftDataVersion: Number(value.MinecraftDataVersion ?? 0) },
  };
}

/**
 * The minimum world coordinate of a Litematica region on one axis.
 *
 * `Size` is an inclusive span: `+n` covers n cells towards the positive axis from `Position`, and
 * `-n` covers n cells towards the negative axis (Litematica has no `-1`, since one cell in either
 * direction is the same box). So a negative size moves the minimum corner by `size + 1`.
 *
 * @param {number} position primary corner on this axis
 * @param {number} size signed span
 * @returns {number}
 */
export function minCorner(position, size) {
  const signed = Math.trunc(size);
  if (signed > 0) return position;
  return position + signed + 1;
}

/** Reads an `{x,y,z}` compound or a `[x,y,z]` array into integers. */
function readVec(value) {
  if (Array.isArray(value)) return value.map((n) => Math.trunc(Number(n)));
  if (value && typeof value === 'object') {
    return [
      Math.trunc(Number(value.x ?? value.X ?? 0)),
      Math.trunc(Number(value.y ?? value.Y ?? 0)),
      Math.trunc(Number(value.z ?? value.Z ?? 0)),
    ];
  }
  return [0, 0, 0];
}

/** Keeps palette entries in the exact shape `blocks` uses. */
function normalizePaletteEntry(entry) {
  const props = entry.Properties && typeof entry.Properties === 'object' ? { ...entry.Properties } : null;
  return props && Object.keys(props).length
    ? blockState(entry.Name, props)
    : blockState(entry.Name);
}

/**
 * Checks the structure of a `.litematic` buffer without building the block map.
 * Used by tests and by the studio to validate a generated file before offering it for download.
 *
 * @param {Buffer|Uint8Array} buffer
 * @returns {{ok:boolean, errors:string[], warnings:string[], info:object|null}}
 */
export function validateLitematic(buffer) {
  const errors = [];
  const warnings = [];
  let info = null;
  let root;
  try {
    root = readNbt(gunzipSync(buffer)).value;
  } catch (error) {
    return { ok: false, errors: [`not a gzip-compressed NBT document: ${error.message}`], warnings, info: null };
  }
  if (!root || typeof root !== 'object') {
    return { ok: false, errors: ['root tag is not a compound'], warnings, info: null };
  }
  if (!Number.isInteger(root.MinecraftDataVersion)) errors.push('MinecraftDataVersion must be an int');
  else if (root.MinecraftDataVersion !== DATA_VERSION_26_3) {
    warnings.push(
      `MinecraftDataVersion is ${root.MinecraftDataVersion}, not the 26.3 data version ${DATA_VERSION_26_3}`,
    );
  }
  if (!Number.isInteger(root.Version)) errors.push('Version must be an int');
  if (!root.Metadata || typeof root.Metadata !== 'object') errors.push('Metadata must be a compound');
  else {
    for (const field of ['Name', 'Author', 'Description']) {
      if (typeof root.Metadata[field] !== 'string') errors.push(`Metadata.${field} must be a string`);
    }
    for (const field of ['TotalBlocks', 'TotalVolume', 'RegionCount']) {
      if (!Number.isInteger(root.Metadata[field])) errors.push(`Metadata.${field} must be an int`);
    }
    for (const field of ['TimeCreated', 'TimeModified']) {
      if (!(typeof root.Metadata[field] === 'bigint' || Number.isInteger(root.Metadata[field]))) {
        errors.push(`Metadata.${field} must be a long`);
      }
    }
    const enclosing = root.Metadata.EnclosingSize;
    if (!enclosing || !Number.isFinite(enclosing.x) || !Number.isFinite(enclosing.y) || !Number.isFinite(enclosing.z)) {
      errors.push('Metadata.EnclosingSize must be {x,y,z}');
    }
  }
  const regions = root.Regions;
  if (!regions || typeof regions !== 'object' || !Object.keys(regions).length) {
    errors.push('Regions must be a non-empty compound');
  } else {
    const regionEntries = [];
    for (const [name, region] of Object.entries(regions)) {
      const where = `Regions.${name}`;
      if (!region || typeof region !== 'object') {
        errors.push(`${where} must be a compound`);
        continue;
      }
      for (const field of ['Position', 'Size']) {
        const v = region[field];
        if (!v || !Number.isFinite(v.x) || !Number.isFinite(v.y) || !Number.isFinite(v.z)) {
          errors.push(`${where}.${field} must be {x,y,z}`);
        }
      }
      if (!Array.isArray(region.BlockStatePalette) || !region.BlockStatePalette.length) {
        errors.push(`${where}.BlockStatePalette must be a non-empty list`);
        continue;
      }
      const palette = region.BlockStatePalette;
      if (palette[0]?.Name !== 'minecraft:air') {
        warnings.push(`${where}.BlockStatePalette[0] should be minecraft:air`);
      }
      palette.forEach((entry, i) => {
        if (!entry || typeof entry.Name !== 'string') {
          errors.push(`${where}.BlockStatePalette[${i}] has no Name`);
          return;
        }
        if (entry.Properties !== undefined && (typeof entry.Properties !== 'object' || entry.Properties === null)) {
          errors.push(`${where}.BlockStatePalette[${i}].Properties must be a compound`);
          return;
        }
        const check = validateBlockState({
          Name: entry.Name,
          Properties: entry.Properties || undefined,
        });
        for (const error of check.errors) errors.push(`${where}.BlockStatePalette[${i}]: ${error}`);
      });
      if (!region.BlockStates || typeof region.BlockStates.length !== 'number') {
        errors.push(`${where}.BlockStates must be a long array`);
        continue;
      }
      const dims = readVec(region.Size).map((n) => {
        const v = Math.abs(Math.trunc(n));
        return v === 0 ? 1 : v;
      });
      const volume = dims[0] * dims[1] * dims[2];
      const bits = bitsForPalette(palette.length);
      const expected = Math.ceil((volume * bits) / 64);
      if (region.BlockStates.length !== expected) {
        errors.push(
          `${where}.BlockStates has ${region.BlockStates.length} long(s); ${dims.join('x')} at ${bits} bits needs ${expected}`,
        );
      }
      const localErrors = [];
      for (const field of ['Entities', 'TileEntities', 'PendingBlockTicks', 'PendingFluidTicks']) {
        if (!Array.isArray(region[field])) errors.push(`${where}.${field} must be a list`);
      }
      regionEntries.push({ name, size: dims, paletteSize: palette.length, bitsPerEntry: bits, longCount: region.BlockStates.length, localErrors });
    }
    info = { version: root.Version, minecraftDataVersion: root.MinecraftDataVersion, regions: regionEntries };
  }
  return { ok: errors.length === 0, errors, warnings, info };
}

/**
 * Convenience: the block map of a file plus its metadata, as a plain object (for the studio API).
 * @param {Buffer|Uint8Array} buffer
 */
export function litematicSummary(buffer) {
  const parsed = readLitematic(buffer);
  return {
    format: parsed.format,
    metadata: parsed.metadata,
    regions: parsed.regions,
    blocks: parsed.blocks.size,
    palette: [...new Set([...parsed.blocks.values()].map(canonicalState))].sort(),
  };
}
