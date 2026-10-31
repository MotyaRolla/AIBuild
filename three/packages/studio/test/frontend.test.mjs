/**
 * Frontend asset tests.
 *
 * The browser app cannot be executed here (no headless browser, no GPU), so these tests check the
 * things that break silently instead: that the shipped ES modules are syntactically valid, that the
 * HTML wires up the module graph the server actually serves, and that the pure helpers the viewer
 * depends on behave as documented.
 *
 * Anything requiring `three` is imported with a stub resolver, so the test suite stays runnable on a
 * checkout without node_modules.
 */
import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { existsSync } from 'node:fs';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';

import { STUDIO_DIR } from '../src/paths.mjs';

const PUBLIC = path.join(STUDIO_DIR, 'public');
const THREE_BUILD = path.join(STUDIO_DIR, '..', '..', 'node_modules', 'three', 'build', 'three.module.js');
const HAS_THREE = existsSync(THREE_BUILD);

/** Reads a public asset. @param {string} name */
const asset = (name) => readFile(path.join(PUBLIC, name), 'utf8');

describe('studio frontend assets', () => {
  test('index.html references the served module graph', async () => {
    const html = await asset('index.html');
    assert.match(html, /<!doctype html>/i);
    assert.match(html, /<script type="module" src="\/app\.js">/);
    assert.match(html, /<link rel="stylesheet" href="\/style\.css"/);
    // The import map is what lets `three/addons/…` resolve inside OrbitControls.
    assert.match(html, /"three":\s*"\/vendor\/three\.module\.js"/);
    assert.match(html, /"three\/addons\/":\s*"\/vendor\/addons\/"/);
    // Panels the app expects to find by id.
    for (const id of ['build-list', 'prompt', 'create-btn', 'view-a', 'view-b', 'iterations-list', 'renders-grid', 'spec-json']) {
      assert.match(html, new RegExp(`id="${id}"`), `missing #${id}`);
    }
  });

  test('style.css defines the layout hooks the app toggles', async () => {
    const css = await asset('style.css');
    assert.match(css, /\.app\s*\{/);
    assert.match(css, /\.build-card/);
    assert.match(css, /\.iter-card/);
    assert.match(css, /\.renders-grid/);
    // Compare mode is driven by data-mode on .app.
    assert.match(css, /data-mode="compare"/);
  });

  test('app.js and viewer.js are valid ES modules', async () => {
    const run = promisify(execFile);
    for (const file of ['app.js', 'viewer.js']) {
      // `node --check` parses without executing: a syntax error fails the test, a browser-only API
      // (document, WebGL) does not. packages/studio/package.json sets "type": "module", so these
      // plain .js files are parsed as ESM.
      await assert.doesNotReject(run(process.execPath, ['--check', path.join(PUBLIC, file)]), `${file} has a syntax error`);
    }
  });

  test('app.js imports three, OrbitControls and the viewer', async () => {
    const source = await asset('app.js');
    assert.match(source, /import \* as THREE from 'three'/);
    assert.match(source, /from 'three\/addons\/controls\/OrbitControls\.js'/);
    assert.match(source, /from '\/viewer\.js'/);
    // Both API families the studio relies on.
    assert.match(source, /\/api\/builds/);
    assert.match(source, /\/api\/build['"`]/);
    assert.match(source, /\/api\/build\/\$\{/);
  });

  test('viewer.js prefers core geometry and keeps a fallback', async () => {
    const source = await asset('viewer.js');
    assert.match(source, /import\('\/core\/geometry\.mjs'\)/);
    assert.match(source, /export async function buildPreviewGroup/);
    assert.match(source, /export function buildFallbackGroup/);
    assert.match(source, /export function materialFor/);
    assert.match(source, /export function addEdgeOverlay/);
    assert.match(source, /export function roofGeometry/);
    // Glass must be transparent, otherwise windows read as holes.
    assert.match(source, /transparent\s*=\s*true/);
  });

  test('every element id the app looks up exists in index.html', async () => {
    const [html, app] = await Promise.all([asset('index.html'), asset('app.js')]);
    const htmlIds = new Set([...html.matchAll(/id="([^"]+)"/g)].map((m) => m[1]));
    const referenced = new Set([
      ...[...app.matchAll(/\$\('([A-Za-z0-9_-]+)'\)/g)].map((m) => m[1]),
      ...[...app.matchAll(/getElementById\('([^']+)'\)/g)].map((m) => m[1]),
    ]);
    const missing = [...referenced].filter((id) => !htmlIds.has(id));
    assert.deepEqual(missing, [], `app.js references ids missing from index.html: ${missing.join(', ')}`);
  });

  test('viewer.js does not contain syntax that only works after bundling', async () => {
    const source = await asset('viewer.js');
    // Bare imports of anything except 'three' would break in the browser.
    const bare = [...source.matchAll(/from\s+'([^']+)'/g)].map((m) => m[1]).filter((s) => !s.startsWith('.') && !s.startsWith('/'));
    for (const spec of bare) assert.equal(spec, 'three', `unexpected bare import '${spec}'`);
  });
});

describe('viewer geometry helpers', { skip: HAS_THREE ? false : 'three.js is not installed' }, () => {
  /**
   * Imports viewer.js with `three` resolved from the installed package. The module uses browser
   * globals only inside functions, so importing it here is safe.
   */
  async function loadViewer() {
    return import(path.join(PUBLIC, 'viewer.js'));
  }

  test('polygonNormal points out of a known quad', async () => {
    const { polygonNormal } = await loadViewer();
    // Quad in the xz plane, wound counter-clockwise when seen from +y.
    const n = polygonNormal([[0, 0, 0], [0, 0, 1], [1, 0, 1], [1, 0, 0]]);
    assert.ok(Math.abs(Math.abs(n[1]) - 1) < 1e-9, `expected ±1 on y, got ${n}`);
    assert.ok(Math.abs(n[0]) < 1e-9 && Math.abs(n[2]) < 1e-9);
  });

  test('roofGeometry produces a closed gable volume above the wall line', async () => {
    const { roofGeometry } = await loadViewer();
    const geometry = roofGeometry([0, 10, 0], [9, 10, 9], { style: 'gable', ridge: 'x' });
    const position = geometry.getAttribute('position');
    assert.ok(position.count > 0);
    assert.equal(position.count % 3, 0, 'triangle soup must be a whole number of triangles');
    let maxY = -Infinity;
    let minY = Infinity;
    for (let i = 0; i < position.count; i++) {
      const y = position.getY(i);
      maxY = Math.max(maxY, y);
      minY = Math.min(minY, y);
    }
    assert.ok(minY >= 10, `roof must start at the wall top, got ${minY}`);
    assert.ok(maxY > 11, `ridge must rise above the eaves, got ${maxY}`);
  });

  test('roofGeometry supports all four styles', async () => {
    const { roofGeometry } = await loadViewer();
    for (const style of ['gable', 'hip', 'shed', 'pyramid']) {
      const geometry = roofGeometry([0, 4, 0], [5, 4, 7], { style });
      const position = geometry.getAttribute('position');
      assert.ok(position.count >= 6, `${style} should emit at least two triangles`);
      assert.equal(position.count % 3, 0);
    }
  });

  test('materialFor maps palette keys and raw hex, and marks glass transparent', async () => {
    const { materialFor } = await loadViewer();
    const solid = materialFor('wall', { wall: '#d9cfba' }, false);
    assert.equal(`#${solid.color.getHexString()}`, '#d9cfba');
    assert.equal(solid.transparent, false);

    const glass = materialFor('glass', { glass: '#a9c9d9' }, true);
    assert.equal(glass.transparent, true);
    assert.ok(glass.opacity < 1);
    assert.equal(glass.depthWrite, false);

    const hex = materialFor('#ff0000', {}, false);
    assert.equal(`#${hex.color.getHexString()}`, '#ff0000');

    // An unknown key falls back to a visible neutral instead of throwing.
    const unknown = materialFor('not-a-colour', {}, false);
    assert.ok(unknown.color);
  });

  test('buildFallbackGroup draws boxes for a simple spec', async () => {
    const { buildFallbackGroup } = await loadViewer();
    const built = buildFallbackGroup({
      name: 'two boxes',
      palette: { wall: '#d9cfba', roof: '#6b452f' },
      objects: [
        { id: 'body', type: 'box', from: [0, 0, 0], to: [4, 3, 4], material: 'wall' },
        { id: 'roof', type: 'prism', from: [0, 3, 0], to: [4, 3, 4], style: 'gable', material: 'roof' },
      ],
    });
    assert.ok(built.group.children.length >= 2);
    assert.equal(built.stats.source, 'fallback');
    assert.equal(built.stats.objects, 2);
    assert.ok(built.stats.triangles > 0);
    assert.ok(built.box.max.y > built.box.min.y);
  });
});
