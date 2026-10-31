/**
 * Tests for the NBT writer/reader and the `.litematic` bit packing.
 *
 * The NBT layer is hand-written (no dependencies), so it is tested the only way a binary format
 * can be trusted: write, read back, compare — plus golden byte-level assertions on the header and
 * on the exact tag types, and explicit handling of the cases that JavaScript cannot express
 * naturally (empty lists without an element type, longs beyond 2^53).
 */

import test from 'node:test';
import assert from 'node:assert/strict';

import {
  TAG,
  TAG_NAMES,
  writeNbt,
  readNbt,
  nbtToPlain,
  Byte,
  Short,
  Int,
  Long,
  Float,
  Double,
  StringTag,
  nbtList,
  inferType,
  LIST_ELEMENT_TYPE,
} from '../src/nbt.mjs';
import {
  packBits,
  unpackBits,
  bitsForPalette,
  LITEMATIC_VERSION,
} from '../src/litematic.mjs';

test('nbt: header carries the root tag type and an empty root name', () => {
  const buffer = writeNbt({ a: 1 });
  assert.equal(buffer[0], TAG.COMPOUND);
  assert.equal(buffer[1], 0); // root name length high byte
  assert.equal(buffer[2], 0); // root name length low byte
  // first child: TAG_Int, name "a"
  assert.equal(buffer[3], TAG.INT);
  assert.equal(buffer[4], 0);
  assert.equal(buffer[5], 1);
  assert.equal(buffer[6], 'a'.charCodeAt(0));
});

test('nbt: named root and nested compounds round-trip', () => {
  const doc = {
    name: 'AIBuild',
    count: 7,
    nested: { flag: true, ratio: 0.5, text: 'hello \u00e9\u00e8' },
    list: ['a', 'b', 'c'],
  };
  const buffer = writeNbt(doc, { name: 'root' });
  const back = readNbt(buffer);
  assert.equal(back.name, 'root');
  assert.equal(back.type, TAG.COMPOUND);
  // NBT has no boolean type: `true` is written as a byte and comes back as 1.
  assert.deepEqual(nbtToPlain(back.value), {
    name: 'AIBuild',
    count: 7,
    nested: { flag: 1, ratio: 0.5, text: 'hello éè' },
    list: ['a', 'b', 'c'],
  });
  assert.equal(back.bytesRead, buffer.length);
});

test('nbt: every scalar tag type keeps its exact type on round-trip', () => {
  const doc = {
    b: Byte(1),
    s: Short(300),
    i: Int(-70_000),
    l: Long(9_000_000_000n),
    f: Float(1.5),
    d: Double(-2.25),
    str: StringTag('x'),
  };
  const back = readNbt(writeNbt(doc)).value;
  assert.equal(inferType(Byte(1)), TAG.BYTE);
  assert.equal(typeof back.l, 'bigint');
  assert.equal(back.l, 9_000_000_000n);
  assert.equal(back.f, 1.5);
  assert.equal(back.d, -2.25);
  assert.equal(back.s, 300);
  assert.equal(back.i, -70_000);
  // A plain integer round-trips as int (not double) and a fractional one as double.
  assert.equal(inferType(5), TAG.INT);
  assert.equal(inferType(5.5), TAG.DOUBLE);
});

test('nbt: longs do not lose precision (millisecond timestamps)', () => {
  const timestamp = 1_764_000_000_123n;
  const back = readNbt(writeNbt({ TimeCreated: Long(timestamp) })).value;
  assert.equal(back.TimeCreated, timestamp);
  // 1.764e12 is still below 2^53, so also prove the tag survives a value a double cannot hold:
  // 2^53 + 1 is not representable, and reading it back must still yield the exact integer.
  const huge = 9_007_199_254_740_993n;
  const round = readNbt(writeNbt({ t: Long(huge) })).value;
  assert.equal(round.t, huge);
  assert.equal(BigInt(Number(huge)), 9_007_199_254_740_992n, 'a double loses the low bit here');
  assert.notEqual(BigInt(Number(huge)), huge);
});

test('nbt: arrays are typed and round-trip', () => {
  const bytes = Int8Array.from([-128, 0, 127]);
  const ints = Int32Array.from([-2_147_483_648, 0, 2_147_483_647]);
  const longs = BigInt64Array.from([-(2n ** 63n), 0n, 2n ** 63n - 1n]);
  const back = readNbt(writeNbt({ bytes, ints, longs })).value;
  assert.deepEqual([...back.bytes], [...bytes]);
  assert.deepEqual([...back.ints], [...ints]);
  assert.deepEqual([...back.longs], [...longs]);
});

test('nbt: empty lists keep their element type through a round-trip', () => {
  const doc = { empty: nbtList(TAG.COMPOUND, []) };
  const buffer = writeNbt(doc);
  const back = readNbt(buffer);
  assert.deepEqual(back.value.empty, []);
  assert.equal(back.value.empty[LIST_ELEMENT_TYPE], TAG.COMPOUND);
  // Re-writing the decoded tree must produce the same bytes (that is what stable files need).
  assert.deepEqual([...writeNbt(back.value)], [...buffer]);
  // The marker is not enumerable, so deep equality still works.
  assert.deepEqual(Object.keys(back.value.empty), []);
});

test('nbt: list of compounds round-trips', () => {
  const doc = { palette: [{ Name: 'minecraft:air' }, { Name: 'minecraft:stone', Properties: { axis: 'y' } }] };
  const back = readNbt(writeNbt(doc)).value;
  assert.deepEqual(nbtToPlain(back), doc);
});

test('nbt: unsupported values are rejected with a clear English message', () => {
  assert.throws(() => writeNbt({ bad: Number.NaN }), /non-finite/);
  assert.throws(() => writeNbt({ bad: undefined }), /cannot encode/);
  assert.throws(() => writeNbt({ bad: Symbol('x') }), /cannot encode/);
});

test('nbt: a truncated document is reported, not silently accepted', () => {
  const buffer = writeNbt({ a: 1, b: 'text' });
  assert.throws(() => readNbt(buffer.subarray(0, buffer.length - 3)), /unexpected end of data/);
  assert.throws(() => readNbt(Buffer.from([99, 0, 0])), /unknown tag type/);
});

test('nbt: tag names cover every type used by the writer', () => {
  for (const id of Object.values(TAG)) {
    assert.equal(typeof TAG_NAMES[id], 'string', `missing name for tag ${id}`);
  }
});

/* ------------------------------------------------------------------------------------------ */
/* Litematica bit packing                                                                      */
/* ------------------------------------------------------------------------------------------ */

test('packing: entries straddle long boundaries without corruption', () => {
  // 21 entries at 3 bits = 63 bits, so the 22nd entry starts in the second long.
  const values = Array.from({ length: 500 }, (_, i) => i % 8);
  const words = packBits(values, 3);
  assert.equal(words.length, Math.ceil((values.length * 3) / 64));
  assert.deepEqual(unpackBits(words, 3, values.length), values);
});

test('packing: works for every bit width from 1 to 32', () => {
  for (let bits = 1; bits <= 32; bits++) {
    const max = bits >= 31 ? 1000 : (1 << bits) - 1;
    const values = Array.from({ length: 257 }, (_, i) => (i * 37) % (max + 1));
    const words = packBits(values, bits);
    assert.deepEqual(unpackBits(words, bits, values.length), values, `bits=${bits}`);
  }
});

test('packing: negative words (high bit set) unpack correctly', () => {
  // 32 values of 3 bits fill more than one long, and the top bit of the first long is set.
  const values = Array.from({ length: 64 }, (_, i) => i % 8);
  const words = packBits(values, 3);
  assert.ok(words.some((w) => w < 0n), 'expected at least one sign-bit-set word');
  assert.deepEqual(unpackBits(words, 3, values.length), values);
});

test('packing: bit width follows Litematica rules (2 bits minimum, grows with the palette)', () => {
  assert.equal(bitsForPalette(1), 2);
  assert.equal(bitsForPalette(4), 2);
  assert.equal(bitsForPalette(5), 3);
  assert.equal(bitsForPalette(16), 4);
  assert.equal(bitsForPalette(17), 5);
  assert.equal(bitsForPalette(1024), 10);
  assert.equal(bitsForPalette(1025), 11);
  assert.equal(LITEMATIC_VERSION, 6);
});

test('packing: invalid bit widths are rejected', () => {
  assert.throws(() => packBits([1], 0), RangeError);
  assert.throws(() => packBits([1], 33), RangeError);
  assert.throws(() => unpackBits([1n], 0, 1), RangeError);
});
