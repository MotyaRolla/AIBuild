/**
 * HTTP plumbing: content types, safe path resolution, ranged file streaming and JSON replies.
 *
 * Plain node:http — no framework. Everything here is deliberately small and dependency-free, and
 * every path that reaches the filesystem has passed `resolveInside()` first.
 */
import { createReadStream } from 'node:fs';
import { stat } from 'node:fs/promises';
import path from 'node:path';

/** Content types used by the studio (kept explicit: no external mime database). */
export const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.htm': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.map': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.webp': 'image/webp',
  '.gif': 'image/gif',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.txt': 'text/plain; charset=utf-8',
  '.md': 'text/plain; charset=utf-8',
  '.litematic': 'application/octet-stream',
  '.woff2': 'font/woff2',
  '.wasm': 'application/wasm',
};

/**
 * Content type for a file name.
 * @param {string} file
 * @returns {string}
 */
export function mimeFor(file) {
  return MIME[path.extname(file).toLowerCase()] || 'application/octet-stream';
}

/**
 * Joins `name` onto `dir` and refuses anything that would escape it.
 * Returns `null` for traversal, absolute paths and embedded NUL bytes.
 * @param {string} dir absolute directory
 * @param {string} name relative request path
 * @returns {string|null} absolute path inside `dir`
 */
export function resolveInside(dir, name) {
  if (typeof name !== 'string' || !name || name.includes('\0')) return null;
  const decoded = (() => {
    try {
      return decodeURIComponent(name);
    } catch {
      return null;
    }
  })();
  if (decoded === null || decoded.includes('\0')) return null;
  const base = path.resolve(dir);
  const target = path.resolve(base, `.${path.sep}${decoded.replace(/^[/\\]+/, '')}`);
  if (target !== base && !target.startsWith(base + path.sep)) return null;
  return target;
}

/**
 * Writes a JSON body with the right headers.
 * @param {import('node:http').ServerResponse} res
 * @param {number} status
 * @param {any} body
 * @param {Record<string, string>} [headers]
 */
export function sendJson(res, status, body, headers = {}) {
  const payload = Buffer.from(`${JSON.stringify(body)}\n`, 'utf8');
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': String(payload.length),
    'cache-control': 'no-store',
    ...headers,
  });
  res.end(payload);
}

/**
 * Writes a plain-text body (used for errors and the health endpoint).
 * @param {import('node:http').ServerResponse} res
 * @param {number} status
 * @param {string} text
 * @param {Record<string, string>} [headers]
 */
export function sendText(res, status, text, headers = {}) {
  const payload = Buffer.from(text, 'utf8');
  res.writeHead(status, {
    'content-type': 'text/plain; charset=utf-8',
    'content-length': String(payload.length),
    'cache-control': 'no-store',
    ...headers,
  });
  res.end(payload);
}

/**
 * Serves a file with ETag revalidation and single-range support (so the browser can seek or
 * partially load large PNGs and `.litematic` downloads).
 *
 * @param {import('node:http').IncomingMessage} req
 * @param {import('node:http').ServerResponse} res
 * @param {string} file absolute path
 * @param {{cacheControl?: string, contentType?: string, downloadName?: string}} [options]
 * @returns {Promise<boolean>} false when the file is missing or not a regular file
 */
export async function sendFile(req, res, file, options = {}) {
  let info;
  try {
    info = await stat(file);
  } catch {
    return false;
  }
  if (!info.isFile()) return false;
  const etag = `W/"${info.size.toString(16)}-${Math.floor(info.mtimeMs).toString(16)}"`;
  const headers = {
    'content-type': options.contentType || mimeFor(file),
    'cache-control': options.cacheControl || 'no-cache',
    etag,
    'last-modified': info.mtime.toUTCString(),
    'accept-ranges': 'bytes',
  };
  if (options.downloadName) headers['content-disposition'] = `attachment; filename="${options.downloadName.replace(/["\\]/g, '_')}"`;

  if (req.headers['if-none-match'] === etag) {
    res.writeHead(304, headers);
    res.end();
    return true;
  }

  const range = parseRange(req.headers.range, info.size);
  if (range === 'invalid') {
    res.writeHead(416, { ...headers, 'content-range': `bytes */${info.size}` });
    res.end();
    return true;
  }
  if (range) {
    headers['content-range'] = `bytes ${range.start}-${range.end}/${info.size}`;
    headers['content-length'] = String(range.end - range.start + 1);
    res.writeHead(206, headers);
    if (req.method === 'HEAD') {
      res.end();
      return true;
    }
    await pipe(createReadStream(file, { start: range.start, end: range.end }), res);
    return true;
  }

  headers['content-length'] = String(info.size);
  res.writeHead(200, headers);
  if (req.method === 'HEAD') {
    res.end();
    return true;
  }
  await pipe(createReadStream(file), res);
  return true;
}

/**
 * Parses a single `Range: bytes=a-b` header.
 * @param {string|undefined} header
 * @param {number} size
 * @returns {{start: number, end: number}|null|'invalid'} null = serve whole file
 */
export function parseRange(header, size) {
  if (!header || typeof header !== 'string') return null;
  const m = /^bytes=(\d*)-(\d*)$/.exec(header.trim());
  if (!m) return null; // multi-range and unknown units: fall back to the full body
  let start = m[1] === '' ? null : Number(m[1]);
  let end = m[2] === '' ? null : Number(m[2]);
  if (start === null && end === null) return null;
  if (start === null) {
    // suffix range: last N bytes
    if (end <= 0) return 'invalid';
    start = Math.max(0, size - end);
    end = size - 1;
  } else if (end === null || end >= size) {
    end = size - 1;
  }
  if (!Number.isFinite(start) || !Number.isFinite(end) || start > end || start >= size) return 'invalid';
  return { start, end };
}

/**
 * Pipes a read stream into the response, destroying both sides when the client goes away.
 * @param {import('node:fs').ReadStream} stream
 * @param {import('node:http').ServerResponse} res
 * @returns {Promise<void>}
 */
function pipe(stream, res) {
  return new Promise((resolve) => {
    let done = false;
    const finish = () => {
      if (done) return;
      done = true;
      resolve();
    };
    stream.on('error', () => {
      if (!res.headersSent) res.writeHead(500, { 'content-type': 'text/plain; charset=utf-8' });
      res.end('read error');
      finish();
    });
    res.on('close', () => {
      stream.destroy();
      finish();
    });
    stream.on('end', finish);
    stream.pipe(res);
  });
}

/**
 * Reads a request body with a hard size cap.
 * @param {import('node:http').IncomingMessage} req
 * @param {number} [limit] bytes
 * @returns {Promise<Buffer>}
 */
export function readBody(req, limit = 1024 * 1024) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > limit) {
        reject(Object.assign(new Error(`request body exceeds ${limit} bytes`), { statusCode: 413 }));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => resolve(Buffer.concat(chunks)));
    req.on('error', reject);
  });
}

/**
 * Reads and parses a JSON request body (`{}` for an empty body).
 * @param {import('node:http').IncomingMessage} req
 * @param {number} [limit]
 * @returns {Promise<any>}
 */
export async function readJsonBody(req, limit = 1024 * 1024) {
  const buf = await readBody(req, limit);
  if (!buf.length) return {};
  try {
    return JSON.parse(buf.toString('utf8'));
  } catch {
    throw Object.assign(new Error('request body is not valid JSON'), { statusCode: 400 });
  }
}

/**
 * Small HTML escape helper for the few places that build HTML strings on the server.
 * @param {unknown} value
 * @returns {string}
 */
export function escapeHtml(value) {
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}
