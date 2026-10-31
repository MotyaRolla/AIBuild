/**
 * Minimal, dependency-free PNG encoder.
 *
 * Only the standard library is used: `node:zlib` does the deflate step and CRC32 is
 * implemented here by hand (PNG requires the reflected IEEE 802.3 polynomial
 * 0xEDB88320, which is not exposed by node's zlib module).
 *
 * The encoder always writes a truecolour, 8 bits per channel, non-interlaced PNG
 * (colour type 2).  That keeps the file small and is what every viewer supports.
 * Pixels are supplied as raw RGB bytes; the encoder adds the per-row filter byte
 * and optionally picks the cheapest filter per scanline (PNG "adaptive filtering"),
 * which typically buys ~20-40% on renders with large flat areas.
 *
 * ```js
 * import { encodePng, makePng, crc32, decodePng } from './png.mjs';
 * const png = encodePng(width, height, rgb);          // Buffer
 * ```
 */

import { deflateSync, inflateSync } from 'node:zlib';

/** PNG magic: \x89 P N G \r \n \x1a \n */
export const PNG_SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

/** PNG filter methods and colour types we support. */
export const PNG_COLOR_TYPE_RGB = 2;
export const PNG_BIT_DEPTH = 8;

/** Precomputed CRC32 table (lazy, built once). */
let crcTable = null;

function getCrcTable() {
  if (crcTable) return crcTable;
  const table = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    table[n] = c;
  }
  crcTable = table;
  return table;
}

/**
 * CRC32 as specified by PNG (reflected polynomial 0xEDB88320, init/xor 0xFFFFFFFF).
 *
 * @param {Uint8Array} bytes
 * @param {number} [seed=0] CRC state from a previous call, to checksum several buffers as one
 * @returns {number} unsigned 32-bit checksum
 */
export function crc32(bytes, seed = 0) {
  const table = getCrcTable();
  let c = (seed ^ 0xffffffff) >>> 0;
  for (let i = 0; i < bytes.length; i++) c = table[(c ^ bytes[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

/**
 * Builds one PNG chunk: length + type + data + CRC(type|data).
 *
 * @param {string} type four ASCII characters, e.g. 'IHDR'
 * @param {Uint8Array} data
 * @returns {Buffer}
 */
export function pngChunk(type, data) {
  const out = Buffer.allocUnsafe(12 + data.length);
  out.writeUInt32BE(data.length, 0);
  out.write(type, 4, 4, 'ascii');
  Buffer.from(data.buffer, data.byteOffset, data.length).copy(out, 8);
  const crc = crc32(out.subarray(4, 8 + data.length));
  out.writeUInt32BE(crc, 8 + data.length);
  return out;
}

/**
 * Encodes raw RGB pixel data as a PNG.
 *
 * @param {number} width image width in pixels (>= 1)
 * @param {number} height image height in pixels (>= 1)
 * @param {Uint8Array|Uint8ClampedArray|Buffer} rgb packed RGB bytes, `width * height * 3`
 * @param {{ adaptiveFilter?: boolean, filter?: number, level?: number }} [options]
 *   `adaptiveFilter` (default true) picks the best filter per scanline, `filter` forces one
 *   (0..4), `level` is the zlib deflate level (default 9).
 * @returns {Buffer} complete PNG file bytes
 */
export function encodePng(width, height, rgb, options = {}) {
  const { adaptiveFilter = true, filter = 0, level = 9 } = options;
  if (!Number.isInteger(width) || !Number.isInteger(height) || width < 1 || height < 1) {
    throw new Error(`encodePng: width and height must be positive integers, got ${width}x${height}`);
  }
  const stride = width * 3;
  if (!rgb || rgb.length < stride * height) {
    throw new Error(`encodePng: expected ${stride * height} RGB bytes, got ${rgb ? rgb.length : 0}`);
  }
  const raw = filterScanlines(width, height, rgb, stride, adaptiveFilter, filter);
  const compressed = deflateSync(raw, { level });
  const ihdr = Buffer.allocUnsafe(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = PNG_BIT_DEPTH;
  ihdr[9] = PNG_COLOR_TYPE_RGB;
  ihdr[10] = 0; // compression method: deflate
  ihdr[11] = 0; // filter method: adaptive (5 types)
  ihdr[12] = 0; // interlace: none
  return Buffer.concat([
    PNG_SIGNATURE,
    pngChunk('IHDR', ihdr),
    pngChunk('IDAT', compressed),
    pngChunk('IEND', new Uint8Array(0)),
  ]);
}

/**
 * Convenience wrapper that takes one flat RGBA/RGB buffer.  When pixel components are 4
 * (`stride === 4`) the alpha channel is dropped: the renderer composites transparency
 * itself, so output PNGs are opaque.
 *
 * @param {number} width
 * @param {number} height
 * @param {Uint8Array} pixels
 * @param {{ adaptiveFilter?: boolean, level?: number }} [options]
 * @returns {Buffer}
 */
export function makePng(width, height, pixels, options = {}) {
  const channels = pixels.length === width * height * 4 ? 4 : 3;
  if (channels === 3) return encodePng(width, height, pixels, options);
  const rgb = new Uint8Array(width * height * 3);
  for (let i = 0, j = 0; j < width * height; i += 4, j++) {
    rgb[j * 3] = pixels[i];
    rgb[j * 3 + 1] = pixels[i + 1];
    rgb[j * 3 + 2] = pixels[i + 2];
  }
  return encodePng(width, height, rgb, options);
}

/** PNG filter offsets from the PNG specification (bytes-per-pixel distance). */
const FILTER_PROBES = 8;

/**
 * Packs scanlines with the preceding filter-type byte.  With `adaptiveFilter` each row is
 * encoded with every filter and the one with the smallest sum of absolute signed values
 * is kept (the heuristic recommended by the spec).  Filter 0 is always a candidate, so
 * the result can never be worse than unfiltered by much.
 *
 * @param {number} width
 * @param {number} height
 * @param {Uint8Array} rgb
 * @param {number} stride
 * @param {boolean} adaptive
 * @param {number} forced
 * @returns {Uint8Array}
 */
function filterScanlines(width, height, rgb, stride, adaptive, forced) {
  const out = new Uint8Array((stride + 1) * height);
  let prev = new Uint8Array(stride); // zero row above the first scanline
  const row = new Uint8Array(stride);
  const cand = new Uint8Array(stride);
  let bestCand = new Uint8Array(stride);
  for (let y = 0; y < height; y++) {
    // copy the current row once; filtering reads neighbours from it
    row.set(rgb.subarray(y * stride, y * stride + stride));
    const dst = (stride + 1) * y;
    if (!adaptive) {
      out[dst] = forced;
      out.set(filterRow(forced, row, prev, stride, cand).subarray(0, stride), dst + 1);
    } else {
      let bestType = 0;
      let bestScore = Infinity;
      for (let type = 0; type <= 4; type++) {
        const filtered = filterRow(type, row, prev, stride, cand);
        // Sum of absolute values, treating bytes as signed (spec heuristic).
        let score = 0;
        for (let i = 0; i < stride; i += FILTER_PROBES) {
          const v = filtered[i];
          score += v < 128 ? v : 256 - v;
        }
        if (score < bestScore) {
          bestScore = score;
          bestType = type;
          if (filtered !== bestCand) bestCand.set(filtered);
        }
      }
      out[dst] = bestType;
      out.set(bestCand.subarray(0, stride), dst + 1);
    }
    prev = row.slice();
  }
  return out;
}

/**
 * Applies one PNG filter type to a scanline.
 *
 * @param {number} type 0 none | 1 sub | 2 up | 3 average | 4 paeth
 * @param {Uint8Array} row current scanline
 * @param {Uint8Array} prev scanline above (zeros for y=0)
 * @param {number} stride row length in bytes
 * @param {Uint8Array} out scratch buffer of length >= stride, returned
 * @returns {Uint8Array}
 */
function filterRow(type, row, prev, stride, out) {
  const bpp = 3; // 8-bit RGB
  switch (type) {
    case 0:
      out.set(row);
      return out;
    case 1:
      for (let i = 0; i < stride; i++) out[i] = (row[i] - (i >= bpp ? row[i - bpp] : 0)) & 0xff;
      return out;
    case 2:
      for (let i = 0; i < stride; i++) out[i] = (row[i] - prev[i]) & 0xff;
      return out;
    case 3:
      for (let i = 0; i < stride; i++) {
        const left = i >= bpp ? row[i - bpp] : 0;
        out[i] = (row[i] - ((left + prev[i]) >> 1)) & 0xff;
      }
      return out;
    case 4:
      for (let i = 0; i < stride; i++) {
        const a = i >= bpp ? row[i - bpp] : 0;
        const b = prev[i];
        const c = i >= bpp ? prev[i - bpp] : 0;
        const p = a + b - c;
        const pa = Math.abs(p - a);
        const pb = Math.abs(p - b);
        const pc = Math.abs(p - c);
        const pred = pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
        out[i] = (row[i] - pred) & 0xff;
      }
      return out;
    default:
      throw new Error(`encodePng: unsupported filter type ${type}`);
  }
}

/**
 * Minimal PNG reader used by tests and by tooling that wants to inspect what the renderer
 * produced (dimensions, pixels).  Supports only what `encodePng` writes: 8-bit RGB,
 * non-interlaced, filters 0-4.  Throws on anything else.
 *
 * @param {Uint8Array} bytes
 * @returns {{width: number, height: number, rgb: Uint8Array, chunks: string[]}}
 */
export function decodePng(bytes) {
  const buf = Buffer.from(bytes.buffer, bytes.byteOffset, bytes.length);
  if (buf.length < 8 || !buf.subarray(0, 8).equals(PNG_SIGNATURE)) {
    throw new Error('decodePng: not a PNG (bad signature)');
  }
  let pos = 8;
  let width = 0;
  let height = 0;
  let colorType = -1;
  let interlace = 0;
  const idat = [];
  const chunks = [];
  while (pos + 8 <= buf.length) {
    const len = buf.readUInt32BE(pos);
    const type = buf.toString('ascii', pos + 4, pos + 8);
    const data = buf.subarray(pos + 8, pos + 8 + len);
    if (data.length !== len) throw new Error(`decodePng: truncated chunk ${type}`);
    chunks.push(type);
    if (type === 'IHDR') {
      width = data.readUInt32BE(0);
      height = data.readUInt32BE(4);
      if (data[8] !== 8) throw new Error(`decodePng: only 8-bit depth is supported, got ${data[8]}`);
      colorType = data[9];
      interlace = data[12];
      if (colorType !== PNG_COLOR_TYPE_RGB) throw new Error(`decodePng: only RGB (type 2) is supported, got ${colorType}`);
      if (interlace !== 0) throw new Error('decodePng: interlaced images are not supported');
    } else if (type === 'IDAT') {
      idat.push(Buffer.from(data));
    } else if (type === 'IEND') {
      break;
    }
    pos += 12 + len;
  }
  if (!width || !height) throw new Error('decodePng: missing IHDR');
  const raw = inflateSync(Buffer.concat(idat));
  const stride = width * 3;
  if (raw.length < (stride + 1) * height) throw new Error('decodePng: not enough image data');
  const rgb = new Uint8Array(stride * height);
  let prev = new Uint8Array(stride);
  for (let y = 0; y < height; y++) {
    const base = y * (stride + 1);
    const filter = raw[base];
    const src = raw.subarray(base + 1, base + 1 + stride);
    const dst = rgb.subarray(y * stride, y * stride + stride);
    const bpp = 3;
    for (let i = 0; i < stride; i++) {
      const a = i >= bpp ? dst[i - bpp] : 0;
      const b = prev[i];
      const c = i >= bpp ? prev[i - bpp] : 0;
      let pred = 0;
      if (filter === 0) pred = 0;
      else if (filter === 1) pred = a;
      else if (filter === 2) pred = b;
      else if (filter === 3) pred = (a + b) >> 1;
      else if (filter === 4) {
        const p = a + b - c;
        const pa = Math.abs(p - a);
        const pb = Math.abs(p - b);
        const pc = Math.abs(p - c);
        pred = pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
      } else throw new Error(`decodePng: unsupported filter type ${filter}`);
      dst[i] = (src[i] + pred) & 0xff;
    }
    prev = dst.slice();
  }
  return { width, height, rgb, chunks };
}
