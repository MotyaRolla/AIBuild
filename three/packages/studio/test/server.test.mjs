/**
 * Studio server tests.
 *
 * Every test starts the real server on port 0 (a free port) with its own temporary `out/` directory,
 * so the suite never reads or writes the repository's real build output and the tests can run in
 * parallel with a live studio or the pipeline.
 */
import { test, describe, afterEach, before } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, rm, readFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';

import { start } from '../src/server.mjs';
import { resolveRoots, DEFAULT_ROOT } from '../src/paths.mjs';
import { rewriteModule, resolveVendorPath } from '../src/vendor.mjs';
import { isBuildId, isFileName, parseViewName, severityOf } from '../src/store.mjs';
import { resolveInside, parseRange, mimeFor } from '../src/respond.mjs';

const THREE_BUILD = path.join(DEFAULT_ROOT, 'node_modules', 'three', 'build', 'three.module.js');
const HAS_THREE = existsSync(THREE_BUILD);

/** @type {{app: Awaited<ReturnType<typeof start>>, dir: string, outDir: string}[]} */
const running = [];

/**
 * Creates an isolated root: an `out/` under a fresh temp dir, while the public assets and three.js
 * still come from the real repository (they are read-only).
 * @returns {Promise<{root: string, outDir: string}>}
 */
async function makeRoot() {
  const root = await mkdtemp(path.join(tmpdir(), 'aibuild-studio-'));
  const outDir = path.join(root, 'out');
  await mkdir(outDir, { recursive: true });
  return { root, outDir };
}

/**
 * Starts a server against an isolated out/ and registers it for cleanup.
 * @param {{root?: string, outDir?: string}} [overrides]
 */
async function boot(overrides = {}) {
  const made = await makeRoot();
  const app = await start({
    port: 0,
    host: '127.0.0.1',
    root: made.root,
    outDir: overrides.outDir || made.outDir,
    quiet: true,
  });
  running.push({ app, dir: made.root });
  return { app, root: made.root, outDir: made.outDir };
}

/** Writes a small but valid build into outDir/<id>/. */
async function writeBuild(outDir, id, { status = 'ready', prompt = 'a test tower', spec = null, views = [], history = null } = {}) {
  const dir = path.join(outDir, id);
  await mkdir(dir, { recursive: true });
  const meta = { id, prompt, name: `Test ${id}`, status, createdAt: '2026-01-02T03:04:05.000Z', updatedAt: '2026-01-02T03:04:05.000Z' };
  await writeFile(path.join(dir, 'meta.json'), JSON.stringify(meta, null, 2));
  if (spec) {
    await writeFile(path.join(dir, 'spec.json'), JSON.stringify(spec, null, 2));
    await writeFile(path.join(dir, 'spec-v0.json'), JSON.stringify(spec, null, 2));
  }
  for (const view of views) await writeFile(path.join(dir, view.file), Buffer.from([0x89, 0x50, 0x4e, 0x47]));
  if (history) await writeFile(path.join(dir, 'history.json'), JSON.stringify(history, null, 2));
  return dir;
}

const TINY_SPEC = { name: 'tower', palette: { stone: '#888888' }, objects: [{ id: 'body', type: 'box', from: [0, 0, 0], to: [3, 6, 3], material: 'stone' }] };

afterEach(async () => {
  while (running.length) {
    const { app, dir } = running.pop();
    await app.close();
    await rm(dir, { recursive: true, force: true });
  }
});

/** @param {string} url @param {RequestInit} [init] */
async function get(url, init) {
  return fetch(url, init);
}

describe('studio server: basics', () => {
  test('starts on a free port and reports its url', async () => {
    const { app } = await boot();
    assert.ok(app.port > 0);
    assert.equal(app.url, `http://127.0.0.1:${app.port}`);
    assert.equal(typeof app.close, 'function');
    const health = await get(`${app.url}/api/health`);
    assert.equal(health.status, 200);
    const body = await health.json();
    assert.equal(body.ok, true);
    assert.equal(body.service, '@aibuild/studio');
  });

  test('GET / returns 200 text/html and references the app module', async () => {
    const { app } = await boot();
    const response = await get(`${app.url}/`);
    assert.equal(response.status, 200);
    assert.match(response.headers.get('content-type'), /^text\/html/);
    const html = await response.text();
    assert.match(html, /<html/i);
    assert.match(html, /\/app\.js/);
    // The import map is what lets the browser resolve bare 'three' inside addons.
    assert.match(html, /"three":\s*"\/vendor\/three\.module\.js"/);
  });

  test('GET /app.js and /style.css are served from public/', async () => {
    const { app } = await boot();
    const appJs = await get(`${app.url}/app.js`);
    assert.equal(appJs.status, 200);
    assert.match(appJs.headers.get('content-type'), /javascript/);
    assert.match(await appJs.text(), /import \* as THREE from 'three'/);

    const css = await get(`${app.url}/style.css`);
    assert.equal(css.status, 200);
    assert.match(css.headers.get('content-type'), /text\/css/);
  });

  test('unknown paths return 404', async () => {
    const { app } = await boot();
    const response = await get(`${app.url}/does-not-exist.html`);
    assert.equal(response.status, 404);
  });

  test('refuses path traversal out of the public directory', async () => {
    const { app } = await boot();
    // Node's fetch normalises '..' before sending, so use a raw http request instead.
    const http = await import('node:http');
    const status = await new Promise((resolve, reject) => {
      const req = http.request({ host: '127.0.0.1', port: app.port, path: '/../../../../etc/passwd' }, (res) => {
        res.resume();
        resolve(res.statusCode);
      });
      req.on('error', reject);
      req.end();
    });
    assert.ok(status === 400 || status === 404, `expected 400/404, got ${status}`);
  });
});

describe('studio server: three.js vendor files', { skip: HAS_THREE ? false : 'three.js is not installed' }, () => {
  test('GET /vendor/three.module.js returns 200 javascript', async () => {
    const { app } = await boot();
    const response = await get(`${app.url}/vendor/three.module.js`);
    assert.equal(response.status, 200);
    assert.match(response.headers.get('content-type'), /javascript/);
    const text = await response.text();
    assert.ok(text.length > 100_000, 'three.module.js should be a large module');
    assert.match(text, /REVISION/);
    // three 0.187 imports './three.core.js'. build/ and examples/jsm/ are mounted at /vendor/ and
    // /vendor/addons/, so a sibling import inside the same mount may stay relative; either way it
    // must resolve to the served file, and no bare specifier may survive.
    assert.match(text, /from ['"](?:\.\/|\/vendor\/)three\.core\.js['"]/);
    assert.doesNotMatch(text, /from ['"]three['"]/);
  });

  test('GET /vendor/three.core.js is served (split build)', async () => {
    const { app } = await boot();
    const response = await get(`${app.url}/vendor/three.core.js`);
    assert.equal(response.status, 200);
    assert.match(response.headers.get('content-type'), /javascript/);
    assert.ok((await response.text()).length > 100_000);
  });

  test('GET /vendor/OrbitControls.js rewrites the bare "three" import', async () => {
    const { app } = await boot();
    const response = await get(`${app.url}/vendor/OrbitControls.js`);
    assert.equal(response.status, 200);
    assert.match(response.headers.get('content-type'), /javascript/);
    const text = await response.text();
    assert.match(text, /from ['"]\/vendor\/three\.module\.js['"]/);
    assert.doesNotMatch(text, /from ['"]three['"]/);
    assert.match(text, /class OrbitControls/);
  });

  test('addons resolve transitively and traversal is refused', async () => {
    const { app } = await boot();
    const response = await get(`${app.url}/vendor/addons/controls/OrbitControls.js`);
    assert.equal(response.status, 200);
    const missing = await get(`${app.url}/vendor/addons/nope/Nope.js`);
    assert.equal(missing.status, 404);
    const traversal = await get(`${app.url}/vendor/..%2f..%2fpackage.json`);
    assert.ok([400, 404].includes(traversal.status), `expected 400/404, got ${traversal.status}`);
  });

  test('vendor responses are cached but revalidated', async () => {
    const { app } = await boot();
    const first = await get(`${app.url}/vendor/OrbitControls.js`);
    await first.text();
    const second = await get(`${app.url}/vendor/OrbitControls.js`);
    assert.equal(second.status, 200);
    assert.equal(second.headers.get('cache-control'), 'no-cache');
  });
});

describe('studio server: builds API', () => {
  test('GET /api/builds returns a JSON array', async () => {
    const { app } = await boot();
    const response = await get(`${app.url}/api/builds`);
    assert.equal(response.status, 200);
    assert.match(response.headers.get('content-type'), /application\/json/);
    assert.ok(Array.isArray(await response.json()));
  });

  test('GET /api/builds lists builds from out/<id>/meta.json', async () => {
    const { app, outDir } = await boot();
    await writeBuild(outDir, '20260101-000001-aaaa', { spec: TINY_SPEC, views: [{ file: 'se.png' }] });
    await writeBuild(outDir, '20260102-000002-bbbb', { status: 'pending' });
    // A directory without meta.json must not appear.
    await mkdir(path.join(outDir, 'stray'), { recursive: true });

    const builds = await (await get(`${app.url}/api/builds`)).json();
    assert.equal(builds.length, 2);
    const ids = builds.map((b) => b.id).sort();
    assert.deepEqual(ids, ['20260101-000001-aaaa', '20260102-000002-bbbb']);
    const first = builds.find((b) => b.id === '20260101-000001-aaaa');
    assert.equal(first.status, 'ready');
    assert.equal(first.thumbnail, '/out/20260101-000001-aaaa/se.png');
    assert.equal(first.viewCount, 1);
    assert.equal(builds.find((b) => b.id === '20260102-000002-bbbb').thumbnail, null);
  });

  test('POST /api/build stores a pending build and GET /api/build/:id returns the prompt', async () => {
    const { app, outDir } = await boot();
    const prompt = 'a small stone lighthouse with a red roof';
    const created = await get(`${app.url}/api/build`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ prompt }),
    });
    assert.equal(created.status, 202);
    const body = await created.json();
    assert.match(body.id, /^[A-Za-z0-9][A-Za-z0-9._-]*$/);
    assert.equal(body.status, 'pending');

    const fetched = await get(`${app.url}/api/build/${body.id}`);
    assert.equal(fetched.status, 200);
    const build = await fetched.json();
    assert.equal(build.id, body.id);
    assert.equal(build.prompt, prompt);
    assert.equal(build.status, 'pending');
    assert.equal(build.meta.prompt, prompt);
    assert.equal(build.spec, null);
    assert.deepEqual(build.versions, []);
    assert.deepEqual(build.views, []);

    // It is persisted on disk where the generator will pick it up.
    const onDisk = JSON.parse(await readFile(path.join(outDir, body.id, 'meta.json'), 'utf8'));
    assert.equal(onDisk.prompt, prompt);
    assert.equal(onDisk.status, 'pending');

    // And it shows up in the list.
    const list = await (await get(`${app.url}/api/builds`)).json();
    assert.ok(list.some((b) => b.id === body.id));
  });

  test('POST /api/build reuses the prompt verbatim, including unicode and newlines', async () => {
    const { app } = await boot();
    const prompt = 'Средневековый дом 10×10\nс фахверком и трубой — "quoted"';
    const created = await (await get(`${app.url}/api/build`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ prompt }),
    })).json();
    const build = await (await get(`${app.url}/api/build/${created.id}`)).json();
    assert.equal(build.prompt, prompt);
  });

  test('POST /api/build rejects a missing prompt and malformed JSON', async () => {
    const { app } = await boot();
    const empty = await get(`${app.url}/api/build`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({}),
    });
    assert.equal(empty.status, 400);

    const broken = await get(`${app.url}/api/build`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: '{not json',
    });
    assert.equal(broken.status, 400);
  });

  test('POST /api/build accepts a prepared sample', async () => {
    const { app } = await boot();
    const samples = await (await get(`${app.url}/api/samples`)).json();
    if (!samples.length) return; // no samples/ in this checkout
    const created = await get(`${app.url}/api/build`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ sample: samples[0].name, prompt: 'from a sample' }),
    });
    assert.equal(created.status, 202);
    const body = await created.json();
    assert.equal(body.status, 'ready');
    const build = await (await get(`${app.url}/api/build/${body.id}`)).json();
    assert.ok(build.spec && Array.isArray(build.spec.objects));
    assert.ok(build.versions.length >= 1);
  });

  test('GET /api/build/:id returns meta, spec, versions, history and views', async () => {
    const { app, outDir } = await boot();
    const id = '20260103-101010-cccc';
    const history = [
      { iteration: 1, score: 6.5, verdict: 'roof too flat', issues: [{ severity: 'major', text: 'flat roof' }], sev: { critical: 0, major: 1, minor: 0 }, applied: true },
      { iteration: 2, score: 8.5, verdict: 'looks good', issues: [], sev: { critical: 0, major: 0, minor: 0 }, applied: false },
    ];
    await writeBuild(outDir, id, {
      spec: TINY_SPEC,
      history,
      views: [{ file: 'v1-se.png' }, { file: 'v2-se.png' }, { file: 'v2-front.png' }],
    });

    const response = await get(`${app.url}/api/build/${id}`);
    assert.equal(response.status, 200);
    const build = await response.json();
    assert.equal(build.id, id);
    assert.equal(build.spec.name, 'tower');
    assert.equal(build.specFile, 'spec.json');
    assert.equal(build.versions.length, 2); // spec-v0.json + spec.json
    assert.equal(build.history.length, 2);
    assert.equal(build.history[1].score, 8.5);
    assert.equal(build.views.length, 3);
    assert.equal(build.views[0].url, `/out/${id}/v1-se.png`);
    assert.equal(build.views[0].iteration, 1);
    assert.equal(build.views[1].iteration, 2);
    assert.equal(build.score, 8.5);
  });

  test('derives the object count from the spec when meta.json omits it', async () => {
    const { app, outDir } = await boot();
    const id = '20260107-141414-aaaa';
    await writeBuild(outDir, id, { spec: TINY_SPEC });
    const summary = await (await get(`${app.url}/api/build/${id}`)).json();
    assert.equal(summary.objects, TINY_SPEC.objects.length);
    const list = await (await get(`${app.url}/api/builds`)).json();
    assert.equal(list.find((b) => b.id === id).objects, TINY_SPEC.objects.length);
  });

  test('GET /api/build/:id/views returns render URLs, filterable by iteration', async () => {
    const { app, outDir } = await boot();
    const id = '20260104-111111-dddd';
    await writeBuild(outDir, id, { spec: TINY_SPEC, views: [{ file: 'v0-se.png' }, { file: 'v0-front.png' }, { file: 'v1-se.png' }] });

    const all = await (await get(`${app.url}/api/build/${id}/views`)).json();
    assert.ok(Array.isArray(all));
    assert.equal(all.length, 3);
    for (const view of all) {
      assert.match(view.url, new RegExp(`^/out/${id}/v\\d+-[a-z]+\\.png$`));
      assert.ok(view.view);
      assert.ok(Number.isInteger(view.iteration));
    }

    const v1 = await (await get(`${app.url}/api/build/${id}/views?iteration=1`)).json();
    assert.equal(v1.length, 1);
    assert.equal(v1[0].view, 'se');
  });

  test('GET /api/build/:id?version=N returns that spec version', async () => {
    const { app, outDir } = await boot();
    const id = '20260105-121212-eeee';
    const dir = await writeBuild(outDir, id, { spec: TINY_SPEC });
    await writeFile(path.join(dir, 'spec-v1.json'), JSON.stringify({ ...TINY_SPEC, name: 'tower v1' }));

    const latest = await (await get(`${app.url}/api/build/${id}?version=1`)).json();
    assert.equal(latest.version, 1);
    assert.equal(latest.spec.name, 'tower v1');

    const spec = await (await get(`${app.url}/api/build/${id}/spec?version=0`)).json();
    assert.equal(spec.version, 0);
    assert.equal(spec.spec.name, 'tower');
  });

  test('unknown build ids 404, malformed ids 400', async () => {
    const { app } = await boot();
    assert.equal((await get(`${app.url}/api/build/20990101-000000-ffff`)).status, 404);
    assert.equal((await get(`${app.url}/api/build/not%20a%20build`)).status, 400);
  });

  test('out/:id/:file serves the PNG bytes', async () => {
    const { app, outDir } = await boot();
    const id = '20260106-131313-ffff';
    await writeBuild(outDir, id, { spec: TINY_SPEC, views: [{ file: 'se.png' }] });
    const response = await get(`${app.url}/out/${id}/se.png`);
    assert.equal(response.status, 200);
    assert.equal(response.headers.get('content-type'), 'image/png');
    const bytes = new Uint8Array(await response.arrayBuffer());
    assert.deepEqual([...bytes], [0x89, 0x50, 0x4e, 0x47]);

    assert.equal((await get(`${app.url}/out/${id}/missing.png`)).status, 404);
    assert.equal((await get(`${app.url}/out/${id}/..%2f..%2fmeta.json`)).status, 400);
  });
});

describe('studio server: core modules for the browser viewer', () => {
  // These need the real project root (a temp root has no packages/core), but still an isolated out/.
  async function bootReal() {
    const { outDir } = await makeRoot();
    const app = await start({ port: 0, host: '127.0.0.1', root: DEFAULT_ROOT, outDir, quiet: true });
    running.push({ app, dir: path.dirname(outDir) });
    return app;
  }

  test('serves the geometry modules the viewer imports', async () => {
    const app = await bootReal();
    for (const rel of ['geometry.mjs', 'spec.mjs', 'materials.mjs', 'objects/helpers.mjs']) {
      const response = await get(`${app.url}/core/${rel}`);
      assert.equal(response.status, 200, `/core/${rel} should be served`);
      assert.match(response.headers.get('content-type'), /javascript/);
    }
  });

  test('does not expose node-only core modules', async () => {
    const app = await bootReal();
    for (const rel of ['llm.mjs', 'cli.mjs', 'render.mjs', 'generate.mjs']) {
      const response = await get(`${app.url}/core/${rel}`);
      assert.equal(response.status, 404, `/core/${rel} must not be reachable from a browser`);
    }
    assert.equal((await get(`${app.url}/core/..%2f..%2fpackage.json`)).status, 404);
  });
});

describe('studio server: pure helpers', () => {
  test('isBuildId / isFileName reject traversal', () => {
    assert.equal(isBuildId('20260101-000001-abcd'), true);
    assert.equal(isBuildId('..'), false);
    assert.equal(isBuildId('a/b'), false);
    assert.equal(isBuildId(''), false);
    assert.equal(isBuildId(42), false);
    assert.equal(isFileName('se.png'), true);
    assert.equal(isFileName('../se.png'), false);
    assert.equal(isFileName('a/b.png'), false);
  });

  test('parseViewName understands v<N>-<view> and bare views', () => {
    assert.deepEqual(parseViewName('v3-se.png'), { view: 'se', iteration: 3 });
    assert.deepEqual(parseViewName('front.png'), { view: 'front', iteration: null });
  });

  test('severityOf counts issues by severity', () => {
    const sev = severityOf([{ severity: 'critical' }, { severity: 'major' }, { severity: 'minor' }, { severity: 'major' }, {}]);
    assert.deepEqual(sev, { critical: 1, major: 2, minor: 2 });
  });

  test('resolveInside keeps requests inside the directory', () => {
    const base = path.join(tmpdir(), 'inside-test');
    assert.equal(resolveInside(base, 'a/b.txt'), path.join(base, 'a', 'b.txt'));
    assert.equal(resolveInside(base, '../x'), null);
    assert.equal(resolveInside(base, '/etc/passwd'), path.join(base, 'etc', 'passwd'));
    assert.equal(resolveInside(base, 'a%2f..%2f..%2fx'), null);
  });

  test('parseRange handles closed, open and suffix ranges', () => {
    assert.deepEqual(parseRange('bytes=0-9', 100), { start: 0, end: 9 });
    assert.deepEqual(parseRange('bytes=10-', 100), { start: 10, end: 99 });
    assert.deepEqual(parseRange('bytes=-20', 100), { start: 80, end: 99 });
    assert.equal(parseRange('bytes=100-200', 100), 'invalid');
    assert.equal(parseRange(undefined, 100), null);
  });

  test('mimeFor maps the types the studio serves', () => {
    assert.equal(mimeFor('x.png'), 'image/png');
    assert.equal(mimeFor('x.js'), 'text/javascript; charset=utf-8');
    assert.equal(mimeFor('x.json'), 'application/json; charset=utf-8');
  });

  test('rewriteModule only touches import specifiers', () => {
    const source = [
      "import { A } from 'three';",
      "import './libs/local.js';",
      "export * from 'three/addons/controls/OrbitControls.js';",
      "import 'three';",
      "const p = import('three');",
      "// import { B } from 'three';",
      "/* import 'three' */",
      "const shader = \"import x from 'three'\";",
    ].join('\n');
    const out = rewriteModule(source, '/three/examples/jsm/controls', '/three/examples/jsm', '/three/build');
    // Bare specifiers become absolute vendor URLs (static, side-effect and dynamic imports).
    assert.match(out, /from '\/vendor\/three\.module\.js'/);
    assert.match(out, /^import '\/vendor\/three\.module\.js';$/m);
    assert.match(out, /import\('\/vendor\/three\.module\.js'\)/);
    assert.match(out, /'\/vendor\/addons\/controls\/OrbitControls\.js'/);
    // A relative sibling inside the addon mount stays relative: the URL layout mirrors the disk.
    assert.match(out, /^import '\.\/libs\/local\.js';$/m);
    // Comments and ordinary string data are never touched.
    assert.match(out, /\/\/ import \{ B \} from 'three';/);
    assert.match(out, /\/\* import 'three' \*\//);
    assert.match(out, /const shader = "import x from 'three'"/);

    // A relative import that stays inside the same mount is left alone (the URL layout mirrors disk)…
    const sameMount = rewriteModule("import '../build/x.js';", '/three/examples/jsm/a', '/three/examples/jsm', '/three/build');
    assert.equal(sameMount, "import '../build/x.js';");
    // …but one that crosses from build/ into the addon tree becomes an absolute /vendor URL.
    const crossing = rewriteModule("import '../examples/jsm/controls/OrbitControls.js';", '/three/build', '/three/examples/jsm', '/three/build');
    assert.equal(crossing, "import '/vendor/addons/controls/OrbitControls.js';");
  });

  test('resolveVendorPath maps the friendly URLs and refuses traversal', () => {
    const roots = resolveRoots();
    assert.ok(resolveVendorPath('three.module.js', roots)?.file.endsWith(path.join('build', 'three.module.js')));
    assert.ok(resolveVendorPath('OrbitControls.js', roots)?.file.endsWith(path.join('controls', 'OrbitControls.js')));
    assert.ok(resolveVendorPath('addons/controls/OrbitControls.js', roots)?.file.endsWith(path.join('controls', 'OrbitControls.js')));
    assert.equal(resolveVendorPath('../../package.json', roots), null);
  });

  test('resolveRoots derives out/ and public/ from a root', () => {
    const roots = resolveRoots({ root: '/tmp/example-root' });
    assert.equal(roots.projectRoot, '/tmp/example-root');
    assert.equal(roots.outDir, path.join('/tmp/example-root', 'out'));
    // public and three fall back to the shipped copies when the root lacks them.
    assert.ok(existsSync(path.join(roots.publicDir, 'index.html')));
    if (HAS_THREE) assert.ok(existsSync(path.join(roots.threeDir, 'build', 'three.module.js')));
  });
});

describe('studio server: lifecycle', () => {
  test('close() frees the port and is idempotent', async () => {
    const app = await start({ port: 0, host: '127.0.0.1', root: DEFAULT_ROOT, quiet: true });
    const port = app.port;
    assert.ok(port > 0);
    await app.close();
    await app.close();

    // A second server can take the same port afterwards.
    const again = await start({ port, host: '127.0.0.1', root: DEFAULT_ROOT, quiet: true });
    running.push({ app: again, dir: await mkdtemp(path.join(tmpdir(), 'aibuild-studio-close-')) });
    assert.equal(again.port, port);
  });
});
