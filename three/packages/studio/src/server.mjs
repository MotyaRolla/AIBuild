/**
 * AIBuild 3D web studio — a dependency-free node:http server.
 *
 * Routes
 * ------
 *   GET  /                          studio UI (public/index.html)
 *   GET  /app.js, /style.css, ...   other studio assets from public/
 *   GET  /vendor/three.module.js    three.js build (from node_modules/three/build)
 *   GET  /vendor/three.core.js      three.js core (three 0.187 splits the build)
 *   GET  /vendor/OrbitControls.js   examples/jsm/controls/OrbitControls.js, import rewritten
 *   GET  /vendor/addons/*           any other examples/jsm file, imports rewritten
 *   GET  /api/builds                gallery: [{ id, name, prompt, status, thumbnail, ... }]
 *   GET  /api/samples               prepared specs in three/samples/
 *   POST /api/build {prompt|sample} creates out/<id>/meta.json (status "pending") -> 202
 *   GET  /api/build/:id[?version=N] meta + spec + versions + history + views
 *   GET  /api/build/:id/views       list of render URLs (optionally ?iteration=N)
 *   GET  /api/build/:id/spec?v=N    one spec version (or the final spec)
 *   GET  /out/:id/:file             a render (PNG) from out/<id>/
 *   GET  /api/health                liveness plus resolved directories
 *
 * The server renders nothing: images under `out/` are produced by the software rasteriser in
 * packages/core (task B), and the browser draws the live 3D preview from the spec JSON.
 */
import http from 'node:http';
import path from 'node:path';
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

import { resolveRoots, DEFAULT_ROOT, STUDIO_DIR } from './paths.mjs';
import {
  isBuildId,
  isFileName,
  listBuilds,
  listSamples,
  listViews,
  readBuild,
  readBuildSummary,
  readSpec,
  createBuild,
} from './store.mjs';
import { loadVendorModule, resolveVendorPath, clearVendorCache } from './vendor.mjs';
import {
  resolveInside,
  sendFile,
  sendJson,
  sendText,
  readJsonBody,
} from './respond.mjs';

/** Short alias used by the CLI: `node packages/studio/src/server.mjs`. */
export { DEFAULT_ROOT, STUDIO_DIR };

/** Default port for the studio (see AGENTS.md). Port 0 picks a free port, which tests use. */
export const DEFAULT_PORT = 8137;

/**
 * Core modules the browser may import. Kept to the pure data/geometry layer: no `node:` imports,
 * no filesystem, no credentials. `geometry.mjs` pulls in `spec.mjs`, `objects/*` and `materials.mjs`
 * itself, so the viewer only needs this one entry point.
 */
export const CORE_BROWSER_MODULES = new Set([
  'spec.mjs',
  'materials.mjs',
  'geometry.mjs',
  'objects/helpers.mjs',
  'objects/blocks.mjs',
  'objects/curved.mjs',
  'objects/roofs.mjs',
  'objects/walls.mjs',
  'objects/openings.mjs',
]);

/**
 * Starts the studio server.
 *
 * @param {{port?: number, host?: string, root?: string, publicDir?: string, outDir?: string,
 *          vendorDir?: string, quiet?: boolean}} [options]
 *   `port` 0 asks the OS for a free port; `root` is the `three/` project root whose `out/` is served.
 * @returns {Promise<{url: string, port: number, host: string, close: () => Promise<void>,
 *           server: import('node:http').Server, roots: object}>}
 */
export async function start(options = {}) {
  const { port = DEFAULT_PORT, host = '127.0.0.1', quiet = false } = options;
  const roots = resolveRoots(options);
  const state = { roots, startedAt: Date.now() };

  const server = http.createServer((req, res) => {
    handle(req, res, state).catch((err) => {
      const status = Number(err?.statusCode) || 500;
      if (!res.headersSent) {
        sendJson(res, status, { error: err?.message || 'internal error' });
      } else {
        res.destroy();
      }
      if (status >= 500 && !quiet) console.error('[studio]', err);
    });
  });

  server.on('clientError', (err, socket) => {
    if (socket.writable) socket.end('HTTP/1.1 400 Bad Request\r\n\r\n');
  });

  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, host, () => {
      server.removeListener('error', reject);
      resolve();
    });
  });

  const address = server.address();
  const actualPort = typeof address === 'object' && address ? address.port : port;
  const url = `http://${host === '0.0.0.0' ? '127.0.0.1' : host}:${actualPort}`;

  /** @type {Set<import('node:net').Socket>} */
  const sockets = new Set();
  server.on('connection', (socket) => {
    sockets.add(socket);
    socket.on('close', () => sockets.delete(socket));
  });

  return {
    url,
    port: actualPort,
    host,
    roots,
    server,
    /**
     * Stops accepting connections and resolves once the port is free.
     * @returns {Promise<void>}
     */
    close: () =>
      new Promise((resolve, reject) => {
        if (!server.listening) return resolve();
        server.close((err) => (err ? reject(err) : resolve()));
        for (const socket of sockets) socket.destroy();
        sockets.clear();
      }),
  };
}

/**
 * Route dispatcher shared by every request.
 * @param {import('node:http').IncomingMessage} req
 * @param {import('node:http').ServerResponse} res
 * @param {{roots: ReturnType<typeof resolveRoots>, startedAt: number}} state
 */
async function handle(req, res, state) {
  const { roots } = state;
  const method = (req.method || 'GET').toUpperCase();
  const rawUrl = req.url || '/';
  const queryAt = rawUrl.indexOf('?');
  const rawPath = queryAt === -1 ? rawUrl : rawUrl.slice(0, queryAt);
  const query = new URLSearchParams(queryAt === -1 ? '' : rawUrl.slice(queryAt + 1));

  // Normalise: collapse duplicate slashes and drop a trailing slash (but keep '/'), then percent-decode
  // once so route matching sees real names.
  let pathname;
  try {
    pathname = decodeURIComponent(rawPath);
  } catch {
    return sendJson(res, 400, { error: 'malformed URL' });
  }
  pathname = pathname.replace(/\/{2,}/g, '/');
  if (pathname.length > 1 && pathname.endsWith('/')) pathname = pathname.slice(0, -1);

  if (method === 'OPTIONS') {
    res.writeHead(204, {
      allow: 'GET, HEAD, POST, OPTIONS',
      'access-control-allow-origin': '*',
      'access-control-allow-methods': 'GET, HEAD, POST, OPTIONS',
      'access-control-allow-headers': 'content-type',
    });
    return res.end();
  }
  if (method !== 'GET' && method !== 'HEAD' && method !== 'POST') {
    return sendJson(res, 405, { error: `method ${method} is not allowed` }, { allow: 'GET, HEAD, POST, OPTIONS' });
  }

  // ---- API ---------------------------------------------------------------------------------
  if (pathname === '/api/health') {
    return sendJson(res, 200, {
      ok: true,
      service: '@aibuild/studio',
      root: roots.projectRoot,
      outDir: roots.outDir,
      uptimeMs: Date.now() - state.startedAt,
      three: existsSync(path.join(roots.threeDir, 'build', 'three.module.js')),
    });
  }

  if (pathname === '/api/builds') {
    if (method !== 'GET' && method !== 'HEAD') return sendJson(res, 405, { error: 'use GET /api/builds' });
    const builds = await listBuilds(roots);
    const status = query.get('status');
    const filtered = status ? builds.filter((b) => b.status === status) : builds;
    return sendJson(res, 200, filtered);
  }

  if (pathname === '/api/samples') {
    if (method !== 'GET' && method !== 'HEAD') return sendJson(res, 405, { error: 'use GET /api/samples' });
    return sendJson(res, 200, await listSamples(roots));
  }

  if (pathname === '/api/build') {
    if (method !== 'POST') return sendJson(res, 405, { error: 'use POST /api/build with {"prompt": "..."}' }, { allow: 'POST' });
    const body = await readJsonBody(req, 64 * 1024);
    const created = await createBuild(roots, {
      prompt: body.prompt,
      sample: body.sample,
      name: body.name,
      source: body.source || 'api',
    });
    return sendJson(res, 202, {
      id: created.id,
      status: created.meta.status,
      prompt: created.meta.prompt,
      url: `/api/build/${created.id}`,
      build: await readBuildSummary(roots, created.id),
      note: created.meta.status === 'pending'
        ? 'Build is queued. The generator (packages/core loop) fills spec/renders; refresh to see progress.'
        : 'Created from a prepared sample spec.',
    }, { location: `/api/build/${created.id}` });
  }

  const apiMatch = /^\/api\/build\/([^/]+)(?:\/([^/]+))?$/.exec(pathname);
  if (apiMatch) {
    const id = apiMatch[1];
    const sub = apiMatch[2] || '';
    if (!isBuildId(id)) return sendJson(res, 400, { error: 'invalid build id' });

    if (sub === 'views') {
      if (method !== 'GET' && method !== 'HEAD') return sendJson(res, 405, { error: 'use GET' });
      const summary = await readBuildSummary(roots, id);
      if (!summary) return sendJson(res, 404, { error: `build '${id}' not found` });
      let views = await listViews(roots, id);
      const iteration = query.get('iteration');
      if (iteration !== null && iteration !== '' && iteration !== 'all') {
        const n = Number(iteration);
        if (!Number.isFinite(n)) return sendJson(res, 400, { error: 'iteration must be a number' });
        views = views.filter((v) => v.iteration === n);
      }
      return sendJson(res, 200, views);
    }

    if (sub === 'spec') {
      if (method !== 'GET' && method !== 'HEAD') return sendJson(res, 405, { error: 'use GET' });
      const version = query.get('version') ?? query.get('v') ?? 'final';
      const found = await readSpec(roots, id, version);
      if (!found) return sendJson(res, 404, { error: `spec for build '${id}' (version ${version}) not found` });
      return sendJson(res, 200, { id, version: found.version, file: found.file, spec: found.spec });
    }

    if (sub === 'history') {
      if (method !== 'GET' && method !== 'HEAD') return sendJson(res, 405, { error: 'use GET' });
      const build = await readBuild(roots, id);
      if (!build) return sendJson(res, 404, { error: `build '${id}' not found` });
      return sendJson(res, 200, build.history);
    }

    if (sub) return sendJson(res, 404, { error: `unknown build resource '${sub}'` });
    if (method !== 'GET' && method !== 'HEAD') return sendJson(res, 405, { error: 'use GET' });

    const version = query.get('version') ?? query.get('v') ?? undefined;
    const build = await readBuild(roots, id, { version });
    if (!build) return sendJson(res, 404, { error: `build '${id}' not found` });
    return sendJson(res, 200, build);
  }

  // ---- vendor (three.js) --------------------------------------------------------------------
  if (pathname === '/vendor' || pathname.startsWith('/vendor/')) {
    const rest = pathname.slice('/vendor/'.length);
    const resolved = resolveVendorPath(rest, roots);
    if (!resolved) return sendJson(res, 404, { error: `unknown vendor module '${rest}'` });
    const body = await loadVendorModule(resolved.file, roots);
    if (body === null) {
      return sendJson(res, 404, {
        error: `three.js vendor file not found: ${resolved.file}`,
        hint: 'three must be installed in node_modules/three (it ships with this repo)',
      });
    }
    const payload = Buffer.from(body, 'utf8');
    res.writeHead(200, {
      'content-type': resolved.js ? 'text/javascript; charset=utf-8' : 'application/octet-stream',
      'content-length': String(payload.length),
      'cache-control': 'no-cache',
      'access-control-allow-origin': '*',
    });
    if (method === 'HEAD') return res.end();
    return res.end(payload);
  }

  // ---- core modules (browser preview reuses the same geometry as the renders) ----------------
  if (pathname === '/core' || pathname.startsWith('/core/')) {
    if (method !== 'GET' && method !== 'HEAD') return sendJson(res, 405, { error: 'use GET' });
    const rel = pathname.slice('/core/'.length);
    // Explicit allowlist: only pure data/geometry modules. `llm.mjs` and `cli.mjs` touch env vars,
    // key files and the filesystem, and must never be reachable from the browser.
    if (!CORE_BROWSER_MODULES.has(rel)) {
      return sendJson(res, 404, { error: `core module '${rel}' is not exposed to the browser` });
    }
    const coreDir = path.join(roots.projectRoot, 'packages', 'core', 'src');
    const file = resolveInside(coreDir, rel);
    if (!file || !existsSync(file)) return sendJson(res, 404, { error: `core module '${rel}' not found` });
    const ok = await sendFile(req, res, file, {
      contentType: 'text/javascript; charset=utf-8',
      cacheControl: 'no-cache',
    });
    if (!ok) return sendJson(res, 404, { error: `core module '${rel}' not found` });
    return;
  }

  // ---- renders and other build artefacts ----------------------------------------------------
  if (pathname.startsWith('/out/')) {
    const rest = pathname.slice('/out/'.length);
    const slash = rest.indexOf('/');
    if (slash === -1) return sendJson(res, 404, { error: 'expected /out/<id>/<file>' });
    const id = rest.slice(0, slash);
    const file = rest.slice(slash + 1);
    if (!isBuildId(id)) return sendJson(res, 400, { error: 'invalid build id' });
    if (!isFileName(file)) return sendJson(res, 400, { error: 'invalid file name' });
    const target = resolveInside(path.join(roots.outDir, id), file);
    if (!target) return sendJson(res, 400, { error: 'path escapes the build directory' });
    const ok = await sendFile(req, res, target, { cacheControl: 'no-cache' });
    if (!ok) return sendJson(res, 404, { error: `file '${file}' not found in build '${id}'` });
    return;
  }

  // ---- static studio assets -----------------------------------------------------------------
  if (method === 'POST') return sendJson(res, 404, { error: `no route for POST ${pathname}` });

  const rel = pathname === '/' ? 'index.html' : pathname.slice(1);
  const target = resolveInside(roots.publicDir, rel);
  if (target) {
    const ok = await sendFile(req, res, target, {
      cacheControl: rel === 'index.html' ? 'no-cache' : 'public, max-age=60',
    });
    if (ok) return;
  }
  return sendText(res, 404, 'Not found. The studio UI is at /.\n');
}

/** Re-exported so a long-running studio can drop the vendor cache after a three.js upgrade. */
export { clearVendorCache };

/**
 * Parses the flags this server accepts. Kept here so `server.mjs` is runnable on its own
 * (`npm run studio` in the root package.json points at this file).
 * @param {string[]} argv
 * @returns {{port: number, host: string, root?: string, help?: boolean}}
 */
function parseArgs(argv) {
  const out = { port: DEFAULT_PORT, host: '127.0.0.1', root: undefined };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '--port' || arg === '-p') out.port = Number(argv[++i]);
    else if (arg === '--host') out.host = argv[++i];
    else if (arg === '--root') out.root = argv[++i];
    else if (arg === '--help' || arg === '-h') out.help = true;
  }
  return out;
}

/** Runs the studio in the foreground when the file is executed directly. */
async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) {
    console.log('usage: node packages/studio/src/server.mjs [--port N] [--host H] [--root DIR]');
    return;
  }
  const app = await start({ port: args.port, host: args.host, root: args.root });
  console.log(`AIBuild studio: ${app.url}`);
  console.log(`  builds: ${app.roots.outDir}`);
  console.log(`  public: ${app.roots.publicDir}`);
  console.log(`  three:  ${app.roots.threeDir}`);
  let closing = false;
  for (const signal of ['SIGINT', 'SIGTERM']) {
    process.on(signal, async () => {
      if (closing) process.exit(1);
      closing = true;
      await app.close();
      process.exit(0);
    });
  }
}

// Only start when executed (`node src/server.mjs`), never when imported by the tests.
if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url))) {
  main().catch((error) => {
    console.error('studio failed to start:', error.message);
    process.exit(1);
  });
}
