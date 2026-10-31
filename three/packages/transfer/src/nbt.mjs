/**
 * Minimal, dependency-free NBT (Named Binary Tag) writer **and** reader.
 *
 * NBT is the binary format Minecraft uses for level.dat, chunk data and structure/schematic
 * files. The transfer module needs it for `.litematic` export, and the reader is what proves the
 * export is well formed (round-trip tests) and lets the studio inspect a saved schematic again.
 *
 * Design notes
 * ------------
 * * Values are plain JavaScript: object -> compound, array -> list, string -> string,
 *   boolean -> byte, integer number -> int (or double when fractional), bigint -> long.
 *   Numeric arrays are typed: `Int8Array` -> byte array, `Int32Array` -> int array,
 *   `BigInt64Array` -> long array (that last one is how `.litematic` stores `BlockStates`).
 * * A few Minecraft tags are ambiguous in JavaScript (`1` could be a byte, short or int; an empty
 *   list has no element type). Those use explicit wrappers: `Byte()`, `Short()`, `Long()`,
 *   `Float()`, `Double()` and `nbtList(type, items)`.
 * * The reader returns the same plain structures, so `readNbt(writeNbt(x))` is a round-trip.
 *   All integer types decode to `number`, longs to `bigint` (a 64-bit value does not survive a
 *   JavaScript number). Empty lists remember their element type through a non-enumerable symbol,
 *   so re-writing a decoded tree keeps the exact tag type without polluting deep equality.
 * * Strings are written as UTF-8 with a 2-byte length prefix. Java's "modified UTF-8" only
 *   differs for NUL and characters outside the basic multilingual plane, neither of which appears
 *   in block ids, property values or metadata names.
 *
 * The file is pure data manipulation: no I/O and no zlib. `.litematic` gzip wrapping lives in
 * `litematic.mjs` so this module stays reusable for uncompressed NBT too.
 */

/** NBT tag ids, exactly as defined by the format. */
export const TAG = Object.freeze({
  END: 0,
  BYTE: 1,
  SHORT: 2,
  INT: 3,
  LONG: 4,
  FLOAT: 5,
  DOUBLE: 6,
  BYTE_ARRAY: 7,
  STRING: 8,
  LIST: 9,
  COMPOUND: 10,
  INT_ARRAY: 11,
  LONG_ARRAY: 12,
});

/** Human-readable names, used in error messages. */
export const TAG_NAMES = Object.freeze({
  0: 'end',
  1: 'byte',
  2: 'short',
  3: 'int',
  4: 'long',
  5: 'float',
  6: 'double',
  7: 'byte_array',
  8: 'string',
  9: 'list',
  10: 'compound',
  11: 'int_array',
  12: 'long_array',
});

/**
 * Non-enumerable marker on decoded lists that remembers the declared element tag.
 * It is what makes `readNbt` -> `writeNbt` byte-stable for empty lists, without making two
 * decoded values compare unequal in `assert.deepStrictEqual`.
 */
export const LIST_ELEMENT_TYPE = Symbol.for('@aibuild/transfer/nbt.listElementType');

/** Internal node marker (never leaks to callers). */
const NODE = Symbol('aibuild.nbt.node');

const INT32_MIN = -2_147_483_648;
const INT32_MAX = 2_147_483_647;
const INT64_MIN = -(2n ** 63n);
const INT64_MAX = 2n ** 63n - 1n;

/**
 * @typedef {{type:number, value:any}} NbtNode
 * @typedef {{type:number, name:string, value:any, bytesRead:number, length:number}} NbtReadResult
 */

/**
 * Creates an explicit tag node.
 * @param {number} type one of TAG
 * @param {any} value payload
 * @returns {NbtNode}
 */
function makeNode(type, value) {
  const node = { type, value };
  Object.defineProperty(node, NODE, { value: true });
  return node;
}

/** True when a value came from this module's typed constructors. @param {unknown} value */
export function isNbtNode(value) {
  return typeof value === 'object' && value !== null && /** @type {any} */ (value)[NODE] === true;
}

/** A signed 8-bit tag. @param {number} value */
export function Byte(value) {
  return makeNode(TAG.BYTE, value);
}
/** A signed 16-bit tag. @param {number} value */
export function Short(value) {
  return makeNode(TAG.SHORT, value);
}
/** A signed 32-bit tag. @param {number} value */
export function Int(value) {
  return makeNode(TAG.INT, value);
}
/** A signed 64-bit tag. Accepts a bigint or an integer number. @param {bigint|number} value */
export function Long(value) {
  return makeNode(TAG.LONG, typeof value === 'bigint' ? value : BigInt(Math.trunc(Number(value))));
}
/** A 32-bit float tag. @param {number} value */
export function Float(value) {
  return makeNode(TAG.FLOAT, value);
}
/** A 64-bit float tag. @param {number} value */
export function Double(value) {
  return makeNode(TAG.DOUBLE, value);
}
/** A string tag. @param {string} value */
export function StringTag(value) {
  return makeNode(TAG.STRING, String(value));
}
/** A byte array tag. @param {Int8Array|number[]} values */
export function ByteArrayTag(values) {
  return makeNode(TAG.BYTE_ARRAY, toInt8Array(values));
}
/** An int array tag. @param {Int32Array|number[]} values */
export function IntArrayTag(values) {
  return makeNode(TAG.INT_ARRAY, toInt32Array(values));
}
/** A long array tag. @param {BigInt64Array|bigint[]|number[]} values */
export function LongArrayTag(values) {
  return makeNode(TAG.LONG_ARRAY, toBigInt64Array(values));
}
/**
 * An explicitly typed list tag. Needed for empty lists (which otherwise decode as `end`) and for
 * lists whose element type cannot be inferred.
 * @param {number} elementType one of TAG (not END for non-empty lists)
 * @param {any[]} items
 */
export function nbtList(elementType, items) {
  return makeNode(TAG.LIST, { elementType, items: items.map((v) => toNodeWithHint(v, elementType)) });
}
/** An explicit compound tag (rarely needed; plain objects infer automatically). @param {object} value */
export function CompoundTag(value) {
  return makeNode(TAG.COMPOUND, value || {});
}

function toInt8Array(values) {
  if (values instanceof Int8Array) return values;
  const out = new Int8Array(values.length);
  for (let i = 0; i < values.length; i++) out[i] = Number(values[i]) | 0;
  return out;
}

function toInt32Array(values) {
  if (values instanceof Int32Array) return values;
  const out = new Int32Array(values.length);
  for (let i = 0; i < values.length; i++) out[i] = Number(values[i]) | 0;
  return out;
}

function toBigInt64Array(values) {
  if (values instanceof BigInt64Array) return values;
  const out = new BigInt64Array(values.length);
  for (let i = 0; i < values.length; i++) out[i] = BigInt.asIntN(64, BigInt(values[i]));
  return out;
}

/**
 * Infers the NBT tag type of a plain JavaScript value.
 * @param {any} value
 * @returns {number} one of TAG
 */
export function inferType(value) {
  if (isNbtNode(value)) return /** @type {NbtNode} */ (value).type;
  if (typeof value === 'boolean') return TAG.BYTE;
  if (typeof value === 'bigint') return TAG.LONG;
  if (typeof value === 'string') return TAG.STRING;
  if (typeof value === 'number') {
    if (!Number.isFinite(value)) throw new TypeError('NBT: cannot encode a non-finite number');
    return Number.isInteger(value) && value >= INT32_MIN && value <= INT32_MAX ? TAG.INT : TAG.DOUBLE;
  }
  if (Array.isArray(value)) return TAG.LIST;
  if (ArrayBuffer.isView(value)) {
    if (value instanceof Int8Array || value instanceof Uint8Array) return TAG.BYTE_ARRAY;
    if (value instanceof Int32Array || value instanceof Uint32Array) return TAG.INT_ARRAY;
    if (value instanceof BigInt64Array || value instanceof BigUint64Array) return TAG.LONG_ARRAY;
    throw new TypeError(`NBT: unsupported typed array ${value.constructor.name}`);
  }
  if (value !== null && typeof value === 'object') return TAG.COMPOUND;
  throw new TypeError(`NBT: cannot encode value of type ${value === null ? 'null' : typeof value}`);
}

/**
 * Converts a plain value into a node, coercing to `hint` when a tag type is required
 * (list elements). Numbers may be coerced to byte/short/int/long/float/double; anything else
 * must already match.
 * @param {any} value
 * @param {number} [hint]
 * @returns {NbtNode}
 */
function toNodeWithHint(value, hint) {
  if (hint === undefined || hint === TAG.END) return toNode(value);
  if (!isNbtNode(value)) {
    if (typeof value === 'number' && hint !== TAG.STRING && hint !== TAG.COMPOUND && hint !== TAG.LIST) {
      if (hint === TAG.LONG) return makeNode(TAG.LONG, BigInt(Math.trunc(value)));
      if (hint === TAG.FLOAT || hint === TAG.DOUBLE) return makeNode(hint, value);
      return makeNode(hint, value | 0);
    }
    if (typeof value === 'bigint' && hint === TAG.LONG) return makeNode(TAG.LONG, value);
    if (typeof value === 'string' && hint === TAG.STRING) return makeNode(TAG.STRING, value);
    if (typeof value === 'boolean' && hint === TAG.BYTE) return makeNode(TAG.BYTE, value ? 1 : 0);
    if (Array.isArray(value) && hint === TAG.LIST) return toNode(value);
    if (value !== null && typeof value === 'object' && hint === TAG.COMPOUND) {
      return makeNode(TAG.COMPOUND, value);
    }
  }
  const node = toNode(value);
  if (node.type !== hint) {
    throw new TypeError(
      `NBT: list element has tag ${TAG_NAMES[node.type]} but the list declares ${TAG_NAMES[hint]}`,
    );
  }
  return node;
}

/**
 * Converts any supported JavaScript value into an NBT node.
 * @param {any} value
 * @returns {NbtNode}
 */
export function toNode(value) {
  if (isNbtNode(value)) return /** @type {NbtNode} */ (value);
  const type = inferType(value);
  switch (type) {
    case TAG.BYTE:
      return makeNode(TAG.BYTE, value ? 1 : 0);
    case TAG.STRING:
      return makeNode(TAG.STRING, String(value));
    case TAG.INT:
      return makeNode(TAG.INT, value | 0);
    case TAG.DOUBLE:
      return makeNode(TAG.DOUBLE, Number(value));
    case TAG.LONG:
      return makeNode(TAG.LONG, BigInt.asIntN(64, /** @type {bigint} */ (value)));
    case TAG.LIST: {
      const items = /** @type {any[]} */ (value);
      /** @type {number} */
      let elementType = items.length
        ? inferType(items[0])
        : (/** @type {any} */ (items)[LIST_ELEMENT_TYPE] ?? TAG.END);
      if (elementType === TAG.END && items.length) elementType = TAG.COMPOUND;
      const nodes = items.map((item) => toNodeWithHint(item, elementType));
      return makeNode(TAG.LIST, { elementType, items: nodes });
    }
    case TAG.COMPOUND:
      return makeNode(TAG.COMPOUND, value);
    case TAG.BYTE_ARRAY:
      return makeNode(TAG.BYTE_ARRAY, toInt8Array(/** @type {any} */ (value)));
    case TAG.INT_ARRAY:
      return makeNode(TAG.INT_ARRAY, toInt32Array(/** @type {any} */ (value)));
    case TAG.LONG_ARRAY:
      return makeNode(TAG.LONG_ARRAY, toBigInt64Array(/** @type {any} */ (value)));
    default:
      throw new TypeError(`NBT: cannot encode tag type ${TAG_NAMES[type] || type}`);
  }
}

/** Growable big-endian byte writer. */
class Writer {
  /** @param {number} [capacity] */
  constructor(capacity = 4096) {
    this.buf = Buffer.allocUnsafe(capacity);
    this.pos = 0;
  }

  /** @param {number} n */
  _need(n) {
    if (this.pos + n <= this.buf.length) return;
    let capacity = this.buf.length || 64;
    while (capacity < this.pos + n) capacity *= 2;
    const next = Buffer.allocUnsafe(capacity);
    this.buf.copy(next, 0, 0, this.pos);
    this.buf = next;
  }

  /** @param {number} v */
  u8(v) {
    this._need(1);
    this.buf[this.pos++] = v & 0xff;
  }

  /** @param {number} v */
  i8(v) {
    this._need(1);
    this.buf.writeInt8(Math.max(-128, Math.min(127, Math.trunc(v))), this.pos);
    this.pos += 1;
  }

  /** @param {number} v */
  u16(v) {
    this._need(2);
    this.buf.writeUInt16BE(v & 0xffff, this.pos);
    this.pos += 2;
  }

  /** @param {number} v */
  i16(v) {
    this._need(2);
    this.buf.writeInt16BE(Math.max(-32768, Math.min(32767, Math.trunc(v))), this.pos);
    this.pos += 2;
  }

  /** @param {number} v */
  i32(v) {
    this._need(4);
    this.buf.writeInt32BE(Math.max(INT32_MIN, Math.min(INT32_MAX, Math.trunc(v))) | 0, this.pos);
    this.pos += 4;
  }

  /** @param {bigint|number} v */
  i64(v) {
    this._need(8);
    const value = typeof v === 'bigint' ? v : BigInt(Math.trunc(Number(v)));
    this.buf.writeBigInt64BE(BigInt.asIntN(64, value), this.pos);
    this.pos += 8;
  }

  /** @param {number} v */
  f32(v) {
    this._need(4);
    this.buf.writeFloatBE(v, this.pos);
    this.pos += 4;
  }

  /** @param {number} v */
  f64(v) {
    this._need(8);
    this.buf.writeDoubleBE(v, this.pos);
    this.pos += 8;
  }

  /** @param {Buffer} bytes */
  bytes(bytes) {
    this._need(bytes.length);
    bytes.copy(this.buf, this.pos);
    this.pos += bytes.length;
  }

  /** @param {string} s */
  string(s) {
    const raw = Buffer.from(String(s), 'utf8');
    if (raw.length > 0xffff) throw new RangeError(`NBT: string is too long (${raw.length} bytes)`);
    this.u16(raw.length);
    this.bytes(raw);
  }

  /** @returns {Buffer} an independent copy of the written bytes */
  finish() {
    return Buffer.from(this.buf.subarray(0, this.pos));
  }
}

/** Bounds-checked big-endian byte reader. */
class Reader {
  /**
   * @param {Buffer|Uint8Array} buf
   * @param {number} [offset]
   */
  constructor(buf, offset = 0) {
    this.buf = buf;
    this.pos = offset;
  }

  /** @param {number} n */
  _need(n) {
    if (this.pos + n > this.buf.length) {
      throw new Error(`NBT: unexpected end of data at byte ${this.pos} (need ${n} more)`);
    }
  }

  u8() {
    this._need(1);
    return this.buf[this.pos++];
  }

  i8() {
    this._need(1);
    const v = this.buf.readInt8(this.pos);
    this.pos += 1;
    return v;
  }

  u16() {
    this._need(2);
    const v = this.buf.readUInt16BE(this.pos);
    this.pos += 2;
    return v;
  }

  i16() {
    this._need(2);
    const v = this.buf.readInt16BE(this.pos);
    this.pos += 2;
    return v;
  }

  i32() {
    this._need(4);
    const v = this.buf.readInt32BE(this.pos);
    this.pos += 4;
    return v;
  }

  i64() {
    this._need(8);
    const v = this.buf.readBigInt64BE(this.pos);
    this.pos += 8;
    return v;
  }

  f32() {
    this._need(4);
    const v = this.buf.readFloatBE(this.pos);
    this.pos += 4;
    return v;
  }

  f64() {
    this._need(8);
    const v = this.buf.readDoubleBE(this.pos);
    this.pos += 8;
    return v;
  }

  bytes(n) {
    this._need(n);
    const out = Buffer.from(this.buf.subarray(this.pos, this.pos + n));
    this.pos += n;
    return out;
  }

  string() {
    const len = this.u16();
    this._need(len);
    const value = Buffer.from(this.buf.subarray(this.pos, this.pos + len)).toString('utf8');
    this.pos += len;
    return value;
  }
}

/**
 * Writes an NBT payload of the given tag type.
 * @param {NbtNode} node
 * @param {Writer} w
 */
function writePayload(node, w) {
  switch (node.type) {
    case TAG.BYTE:
      w.i8(Number(node.value));
      return;
    case TAG.SHORT:
      w.i16(Number(node.value));
      return;
    case TAG.INT:
      w.i32(Number(node.value));
      return;
    case TAG.LONG:
      w.i64(node.value);
      return;
    case TAG.FLOAT:
      w.f32(Number(node.value));
      return;
    case TAG.DOUBLE:
      w.f64(Number(node.value));
      return;
    case TAG.BYTE_ARRAY: {
      const arr = toInt8Array(node.value);
      w.i32(arr.length);
      for (let i = 0; i < arr.length; i++) w.i8(arr[i]);
      return;
    }
    case TAG.STRING:
      w.string(String(node.value));
      return;
    case TAG.LIST: {
      const { elementType, items } = node.value;
      const declared = elementType === undefined || elementType === TAG.END
        ? (items.length ? items[0].type : TAG.END)
        : elementType;
      if (!items.length) {
        // NBT permits any element type for an empty list; writing the declared one keeps the tag
        // type stable across read/write cycles (and `TAG_END` is only the "no information" case).
        w.u8(declared);
        w.i32(0);
        return;
      }
      w.u8(declared);
      w.i32(items.length);
      for (const item of items) {
        if (item.type !== declared) {
          throw new TypeError(
            `NBT: list declares ${TAG_NAMES[declared]} but contains ${TAG_NAMES[item.type]}`,
          );
        }
        writePayload(item, w);
      }
      return;
    }
    case TAG.COMPOUND: {
      const entries = node.value || {};
      for (const [name, raw] of Object.entries(entries)) {
        const child = toNode(raw);
        if (child.type === TAG.END) throw new TypeError(`NBT: tag '${name}' has no type`);
        w.u8(child.type);
        w.string(name);
        writePayload(child, w);
      }
      w.u8(TAG.END);
      return;
    }
    case TAG.INT_ARRAY: {
      const arr = toInt32Array(node.value);
      w.i32(arr.length);
      for (let i = 0; i < arr.length; i++) w.i32(arr[i]);
      return;
    }
    case TAG.LONG_ARRAY: {
      const arr = toBigInt64Array(node.value);
      w.i32(arr.length);
      for (let i = 0; i < arr.length; i++) w.i64(arr[i]);
      return;
    }
    default:
      throw new TypeError(`NBT: cannot write tag type ${TAG_NAMES[node.type] || node.type}`);
  }
}

/**
 * Serialises a value as a complete NBT document (root tag type + root name + payload).
 *
 * @param {any} root plain object / typed node to use as the root compound
 * @param {{name?: string}} [opts] root tag name (usually empty for .litematic)
 * @returns {Buffer}
 */
export function writeNbt(root, opts = {}) {
  const node = toNode(root);
  if (node.type === TAG.END) throw new TypeError('NBT: cannot write an empty root tag');
  const w = new Writer(8192);
  w.u8(node.type);
  w.string(opts.name ?? '');
  writePayload(node, w);
  return w.finish();
}

/**
 * Reads one payload of the given type.
 * @param {number} type
 * @param {Reader} r
 * @returns {any}
 */
function readPayload(type, r) {
  switch (type) {
    case TAG.BYTE:
      return r.i8();
    case TAG.SHORT:
      return r.i16();
    case TAG.INT:
      return r.i32();
    case TAG.LONG:
      return r.i64();
    case TAG.FLOAT:
      return r.f32();
    case TAG.DOUBLE:
      return r.f64();
    case TAG.BYTE_ARRAY: {
      const len = r.i32();
      if (len < 0) throw new Error('NBT: negative byte array length');
      const raw = r.bytes(len);
      const out = new Int8Array(len);
      for (let i = 0; i < len; i++) out[i] = raw.readInt8(i);
      return out;
    }
    case TAG.STRING:
      return r.string();
    case TAG.LIST: {
      const elementType = r.u8();
      const len = r.i32();
      if (len < 0) throw new Error('NBT: negative list length');
      if (len > 0 && elementType === TAG.END) throw new Error('NBT: non-empty list of TAG_End');
      const items = new Array(len);
      for (let i = 0; i < len; i++) items[i] = readPayload(elementType, r);
      Object.defineProperty(items, LIST_ELEMENT_TYPE, {
        value: elementType,
        enumerable: false,
        configurable: true,
      });
      return items;
    }
    case TAG.COMPOUND: {
      /** @type {Record<string, any>} */
      const out = {};
      for (;;) {
        const childType = r.u8();
        if (childType === TAG.END) break;
        const name = r.string();
        const value = readPayload(childType, r);
        // defineProperty keeps keys such as "__proto__" from touching the prototype chain.
        Object.defineProperty(out, name, { value, enumerable: true, writable: true, configurable: true });
      }
      return out;
    }
    case TAG.INT_ARRAY: {
      const len = r.i32();
      if (len < 0) throw new Error('NBT: negative int array length');
      const out = new Int32Array(len);
      for (let i = 0; i < len; i++) out[i] = r.i32();
      return out;
    }
    case TAG.LONG_ARRAY: {
      const len = r.i32();
      if (len < 0) throw new Error('NBT: negative long array length');
      const out = new BigInt64Array(len);
      for (let i = 0; i < len; i++) out[i] = r.i64();
      return out;
    }
    default:
      throw new Error(`NBT: unknown tag type ${type}`);
  }
}

/**
 * Parses one NBT document starting at `offset`.
 *
 * @param {Buffer|Uint8Array} buffer
 * @param {{offset?: number, maxBytes?: number}} [opts]
 * @returns {NbtReadResult} the root tag. `value` is a plain object for compound roots.
 */
export function readNbt(buffer, opts = {}) {
  const r = new Reader(buffer, opts.offset || 0);
  const type = r.u8();
  if (type === TAG.END) return { type, name: '', value: null, bytesRead: 1, length: 1 };
  const name = r.string();
  const value = readPayload(type, r);
  if (opts.maxBytes !== undefined && r.pos > opts.maxBytes) {
    throw new Error(`NBT: document is larger than the ${opts.maxBytes} byte budget`);
  }
  return { type, name, value, bytesRead: r.pos - (opts.offset || 0), length: r.pos };
}

/**
 * Deep-clones a decoded NBT tree into plain JavaScript (drops the hidden list-type marker).
 * Useful for logging and for the studio's JSON API.
 * @param {any} value
 * @returns {any}
 */
export function nbtToPlain(value) {
  if (Array.isArray(value)) return value.map(nbtToPlain);
  if (ArrayBuffer.isView(value)) return Array.from(value, (v) => (typeof v === 'bigint' ? v.toString() : v));
  if (value !== null && typeof value === 'object') {
    /** @type {Record<string, any>} */
    const out = {};
    for (const [k, v] of Object.entries(value)) out[k] = nbtToPlain(v);
    return out;
  }
  return typeof value === 'bigint' ? value.toString() : value;
}

export { INT32_MIN, INT32_MAX, INT64_MIN, INT64_MAX };
