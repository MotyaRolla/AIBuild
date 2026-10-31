/**
 * Tests for the `.litematic` exporter/importer and the `/setblock` command exporter.
 *
 * The brief requires three things of this layer, and each has its own section below:
 *
 *   1. `toLitematic` produces a real Litematica file: gzip magic, the documented NBT tree, a
 *      palette that starts with air, and a `BlockStates` long array of exactly the right length.
 *   2. Reading the file back reproduces **the same blocks** and **the same region size**.
 *   3. `toCommands` emits lines that parse as `/setblock x y z minecraft:id[state]`.
 */

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { gunzipSync, gzipSync } from 'node:zlib';

import {
  transferToMinecraft,
  toLitematic,
  readLitematic,
  validateLitematic,
  buildLitematic,
  litematicSummary,
  toCommands,
  batchCommands,
  parseSetblock,
  setblockCommand,
  toMcFunction,
  parseKey,
} from '../src/index.mjs';
import { blockState, makeStairs, makeSlab, makeConnections, stateToString, DATA_VERSION_26_3 } from '../src/states.mjs';
import { readNbt, writeNbt } from '../src/nbt.mjs';
import { bitsForPalette, packBits } from '../src/litematic.mjs';

const house = JSON.parse(readFileSync(new URL('../../../samples/house-10x10.json', import.meta.url), 'utf8'));

/** A small, hand-built block map so expectations are exact and independent of the voxeliser. */
function fixtureBlocks() {
  return new Map([
    ['0|0|0', blockState('stone')],
    ['1|0|0', blockState('stone_bricks')],
    ['0|1|0', makeStairs('spruce_stairs', { facing: 'east' })],
    ['0|0|1', makeSlab('dark_oak_slab', { type: 'top' })],
    ['1|1|1', makeConnections('glass_pane', ['north', 'east'])],
    ['2|2|2', blockState('iron_block')],
  ]);
}

test('litematic: the file is gzip-compressed NBT with the documented root tags', () => {
  const file = toLitematic(fixtureBlocks(), { name: 'Fixture', author: 'tests' });
  assert.equal(file.subarray(0, 2).toString('hex'), '1f8b', 'gzip magic');
  const root = readNbt(gunzipSync(file)).value;
  assert.equal(root.MinecraftDataVersion, DATA_VERSION_26_3);
  assert.equal(root.Version, 6);
  assert.equal(typeof root.SubVersion, 'number');
  assert.equal(root.Metadata.Name, 'Fixture');
  assert.equal(root.Metadata.Author, 'tests');
  assert.equal(typeof root.Metadata.Description, 'string');
  assert.deepEqual(root.Metadata.EnclosingSize, { x: 3, y: 3, z: 3 });
  assert.equal(root.Metadata.TotalBlocks, 6);
  assert.equal(root.Metadata.TotalVolume, 27);
  assert.equal(root.Metadata.RegionCount, 1);
  assert.equal(typeof root.Metadata.TimeCreated, 'bigint');
  assert.equal(typeof root.Metadata.TimeModified, 'bigint');
});

test('litematic: the region has a palette starting with air and a correctly sized BlockStates array', () => {
  const blocks = fixtureBlocks();
  const file = toLitematic(blocks);
  const root = readNbt(gunzipSync(file)).value;
  const regionNames = Object.keys(root.Regions);
  assert.equal(regionNames.length, 1);
  const region = root.Regions[regionNames[0]];
  assert.deepEqual(region.Position, { x: 0, y: 0, z: 0 });
  assert.deepEqual(region.Size, { x: 3, y: 3, z: 3 });
  assert.equal(region.BlockStatePalette[0].Name, 'minecraft:air');
  // 6 distinct non-air states + air = 7 entries -> 3 bits
  assert.equal(region.BlockStatePalette.length, 7);
  const bits = bitsForPalette(region.BlockStatePalette.length);
  assert.equal(bits, 3);
  assert.equal(region.BlockStates.length, Math.ceil((27 * bits) / 64));
  assert.equal(region.BlockStates.length, 2);
  for (const list of ['Entities', 'TileEntities', 'PendingBlockTicks', 'PendingFluidTicks']) {
    assert.ok(Array.isArray(region[list]), `${list} must be an empty list`);
    assert.equal(region[list].length, 0);
  }
});

test('litematic: round-trip reproduces the same blocks and the same region size', () => {
  const blocks = fixtureBlocks();
  const file = toLitematic(blocks, { name: 'Round trip' });
  const back = readLitematic(file);
  assert.equal(back.blocks.size, blocks.size, 'block count must survive the round-trip');
  assert.deepEqual([...back.blocks.keys()].sort(), [...blocks.keys()].sort());
  for (const [k, state] of blocks) {
    assert.deepEqual(back.blocks.get(k), state, `state changed for ${k}`);
  }
  const expected = { x: 3, y: 3, z: 3 };
  assert.deepEqual(back.regions[0].size, [3, 3, 3]);
  const meta = back.metadata.EnclosingSize;
  assert.deepEqual({ x: meta.x, y: meta.y, z: meta.z }, expected);
  assert.equal(back.format.minecraftDataVersion, DATA_VERSION_26_3);
});

test('litematic: the whole 10x10 house survives an export/import cycle', () => {
  const { blocks } = transferToMinecraft(house);
  const file = toLitematic(blocks, { name: 'Средневековый дом', author: 'AIBuild' });
  const back = readLitematic(file);
  assert.equal(back.blocks.size, blocks.size);
  for (const [k, state] of blocks) {
    assert.deepEqual(back.blocks.get(k), state, `state changed for ${k}`);
  }
  // Region size equals the block map's bounding box.
  const xs = [...blocks.keys()].map((k) => parseKey(k)[0]);
  const ys = [...blocks.keys()].map((k) => parseKey(k)[1]);
  const zs = [...blocks.keys()].map((k) => parseKey(k)[2]);
  assert.deepEqual(back.regions[0].size, [
    Math.max(...xs) - Math.min(...xs) + 1,
    Math.max(...ys) - Math.min(...ys) + 1,
    Math.max(...zs) - Math.min(...zs) + 1,
  ]);
  assert.deepEqual(back.regions[0].position, [Math.min(...xs), Math.min(...ys), Math.min(...zs)]);
  assert.equal(back.metadata.Name, 'Средневековый дом', 'UTF-8 metadata must survive');
});

test('litematic: validateLitematic accepts what toLitematic writes', () => {
  const file = toLitematic(fixtureBlocks(), { name: 'valid' });
  const check = validateLitematic(file);
  assert.deepEqual(check.errors, []);
  assert.equal(check.ok, true);
  assert.equal(check.info.minecraftDataVersion, DATA_VERSION_26_3);
  assert.equal(check.info.regions.length, 1);
  assert.equal(check.info.regions[0].paletteSize, 7);
});

test('litematic: validateLitematic rejects a corrupt or foreign buffer', () => {
  const notGzip = validateLitematic(Buffer.from('definitely not gzip'));
  assert.equal(notGzip.ok, false);
  assert.match(notGzip.errors[0], /gzip/);

  // Valid gzip, valid NBT, but not a schematic: no Version/Regions tags.
  const foreign = gzipSync(writeNbt({ hello: 'world' }));
  const check = validateLitematic(foreign);
  assert.equal(check.ok, false);
  assert.ok(check.errors.some((e) => /Version/.test(e)));
  assert.ok(check.errors.some((e) => /Regions/.test(e)));

  // A truncated file is reported, not thrown.
  const good = toLitematic(fixtureBlocks());
  const truncated = validateLitematic(good.subarray(0, good.length - 10));
  assert.equal(truncated.ok, false);
});

test('litematic: negative region sizes are understood (files written by Litematica itself)', async () => {
  const { writeNbt } = await import('../src/nbt.mjs');
  // Litematica stores Position as the primary corner and Size as a signed vector; a negative
  // component means the other corner lies towards the negative axis.
  const region = {
    Position: { x: 5, y: 4, z: 3 },
    Size: { x: -2, y: 1, z: -3 },
    BlockStatePalette: [
      { Name: 'minecraft:air' },
      { Name: 'minecraft:stone' },
      { Name: 'minecraft:oak_stairs', Properties: { facing: 'east', half: 'bottom', shape: 'straight' } },
    ],
    BlockStates: packBits([1, 2, 0, 1, 0, 2], 2),
    Entities: [],
    TileEntities: [],
    PendingBlockTicks: [],
    PendingFluidTicks: [],
  };
  const root = {
    MinecraftDataVersion: DATA_VERSION_26_3,
    Version: 6,
    SubVersion: 1,
    Metadata: {
      Name: 'negative size',
      Author: 'litematica',
      Description: '',
      EnclosingSize: { x: 2, y: 1, z: 3 },
      TotalBlocks: 4,
      TotalVolume: 6,
      RegionCount: 1,
      TimeCreated: 1n,
      TimeModified: 1n,
    },
    Regions: { neg: region },
  };
  const file = gzipSync(writeNbt(root));
  const back = readLitematic(file);
  // Size {x:-2, y:1, z:-3} from (5,4,3) spans x 4..5, z 1..3, so the minimum corner is (4,4,1).
  assert.deepEqual(back.regions[0].position, [4, 4, 1]);
  assert.deepEqual(back.regions[0].size, [2, 1, 3]);
  assert.equal(back.blocks.size, 4);
  // Y-major indexing: index 0 = (0,0,0) local = (4,4,1); index 1 = (1,0,0) = (5,4,1).
  assert.equal(back.blocks.get('4|4|1').Name, 'minecraft:stone');
  assert.equal(back.blocks.get('5|4|1').Name, 'minecraft:oak_stairs');
});

test('litematic: an oversized region is refused with a clear message', () => {
  const blocks = new Map([
    ['0|0|0', blockState('stone')],
    ['300|0|0', blockState('stone')],
  ]);
  assert.throws(() => toLitematic(blocks), /larger than 256/);
});

test('litematic: an empty block map is refused rather than written', () => {
  assert.throws(() => toLitematic(new Map()), /empty/);
  assert.throws(() => toLitematic(null), TypeError);
});

test('litematic: buildLitematic exposes the palette and volume before serialisation', () => {
  const { root, region } = buildLitematic(fixtureBlocks(), { name: 'x' });
  assert.equal(region.volume, 27);
  assert.equal(region.used, 6);
  assert.equal(region.palette[0].Name, 'minecraft:air');
  assert.equal(region.bitsPerEntry, 3);
  assert.equal(root.Regions[region.name].BlockStates.length, 2);
});

test('litematic: litematicSummary reports palette, regions and metadata', () => {
  const summary = litematicSummary(toLitematic(fixtureBlocks(), { name: 'summary' }));
  assert.equal(summary.blocks, 6);
  assert.equal(summary.metadata.Name, 'summary');
  assert.equal(summary.regions.length, 1);
  assert.ok(summary.palette.includes('stone'));
  assert.ok(summary.palette.includes('spruce_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]'));
});

/* ------------------------------------------------------------------------------------------ */
/* Commands                                                                                    */
/* ------------------------------------------------------------------------------------------ */

test('commands: every line is a valid /setblock for a real 26.3 block', () => {
  const { blocks } = transferToMinecraft(house);
  const commands = toCommands(blocks);
  assert.equal(commands.length, blocks.size);
  for (const line of commands) {
    assert.match(line, /^\/setblock -?\d+ -?\d+ -?\d+ minecraft:[a-z0-9_]+(\[[a-z0-9_]+=[a-z0-9_]+,?[a-z0-9_]+=[a-z0-9_]+.*?\])? replace$/);
    const parsed = parseSetblock(line);
    assert.equal(parsed.ok, true, `parse failed: ${line}`);
    assert.ok(parsed.parsed.state.startsWith('minecraft:'));
    assert.equal(parsed.parsed.mode, 'replace');
  }
});

test('commands: coordinates match the block keys and are integers', () => {
  const blocks = fixtureBlocks();
  const commands = toCommands(blocks);
  const seen = new Set();
  for (const line of commands) {
    const parsed = parseSetblock(line);
    const [x, y, z] = [parsed.parsed.x, parsed.parsed.y, parsed.parsed.z];
    assert.ok(Number.isInteger(x) && Number.isInteger(y) && Number.isInteger(z));
    const k = `${x}|${y}|${z}`;
    assert.ok(blocks.has(k), `command for a key that is not in the map: ${k}`);
    assert.ok(!seen.has(k), `duplicate command for ${k}`);
    seen.add(k);
  }
  assert.equal(seen.size, blocks.size);
});

test('commands: properties are sorted and the output is deterministic', () => {
  const blocks = new Map([['0|0|0', makeStairs('oak_stairs', { facing: 'west', half: 'top' })]]);
  const first = toCommands(blocks);
  const second = toCommands(new Map([...blocks].reverse()));
  assert.deepEqual(first, second);
  assert.equal(first[0], '/setblock 0 0 0 minecraft:oak_stairs[facing=west,half=top,shape=straight,waterlogged=false] replace');
});

test('commands: an invalid state is rejected instead of being emitted', () => {
  const broken = new Map([['0|0|0', { Name: 'minecraft:oak_stairs', Properties: { facing: 'up' } }]]);
  assert.throws(() => toCommands(broken), /invalid block state/);
  // With verification off the caller takes responsibility.
  const lines = toCommands(broken, { verify: false });
  assert.equal(lines.length, 1);
});

test('commands: batching keeps every line exactly once', () => {
  const { blocks } = transferToMinecraft(house);
  const commands = toCommands(blocks);
  const batches = batchCommands(commands, { batchSize: 100 });
  assert.equal(batches.length, Math.ceil(commands.length / 100));
  assert.deepEqual(batches.flat(), commands);
  for (const batch of batches) assert.ok(batch.length <= 100);
  // A very small length budget must still make progress rather than loop forever.
  const tight = batchCommands(commands, { batchSize: 1000, maxLength: 200 });
  assert.deepEqual(tight.flat(), commands);
});

test('commands: .mcfunction bodies have no leading slashes', () => {
  const text = toMcFunction(fixtureBlocks());
  const lines = text.trim().split('\n');
  assert.equal(lines.length, 6);
  for (const line of lines) {
    assert.ok(!line.startsWith('/'), 'function files take bare commands');
    assert.match(line, /^setblock /);
  }
});

test('commands: parseSetblock rejects malformed lines', () => {
  for (const bad of [
    'setblock 0 0 0 minecraft:stone',
    '/setblock 0 0 minecraft:stone',
    '/setblock a b c minecraft:stone',
    '/setblock 0 0 0 stone',
    '/setblock 0 0 0 minecraft:stone[broken]',
  ]) {
    assert.equal(parseSetblock(bad).ok, false, `should reject: ${bad}`);
  }
});

test('commands: a round-trip through the command text preserves the state', () => {
  const blocks = fixtureBlocks();
  for (const [k, state] of blocks) {
    const [x, y, z] = parseKey(k);
    const line = setblockCommand(state, x, y, z);
    const parsed = parseSetblock(line);
    assert.equal(parsed.ok, true);
    const text = stateToString(state);
    assert.ok(line.includes(text), `${line} should contain ${text}`);
  }
});

