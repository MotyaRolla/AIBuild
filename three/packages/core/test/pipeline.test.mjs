/**
 * End-to-end tests for the three.js pipeline: spec -> voxel scene -> pixel render -> Minecraft.
 *
 * These tests are the contract of the whole idea: whatever the AI designs must survive the trip
 * into blocks, and the picture it looks at must be made of exactly those blocks.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { normalizeSpec, validateSpec, specBounds } from '../src/spec.mjs';
import { buildScene, voxelizeSpec } from '../src/geometry.mjs';
import { renderVoxelPng } from '../src/voxel-render.mjs';
import { decodePng } from '../src/png.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SAMPLES = path.resolve(HERE, '../../../samples');

async function loadSample(name) {
  return normalizeSpec(JSON.parse(await readFile(path.join(SAMPLES, name), 'utf8')));
}

/** Cheap floating-voxel check: every voxel is either on the ground or touches a supported voxel. */
function floatingVoxels(vox) {
  const seen = new Set();
  const floating = [];
  const key = (x, y, z) => `${x}|${y}|${z}`;
  for (const k of vox.keys()) {
    if (seen.has(k)) continue;
    // flood fill this connected component
    const comp = [];
    const stack = [k];
    seen.add(k);
    let touchesGround = false;
    while (stack.length) {
      const cur = stack.pop();
      comp.push(cur);
      const [x, y, z] = cur.split('|').map(Number);
      if (y <= 0) touchesGround = true;
      for (const [dx, dy, dz] of [[1, 0, 0], [-1, 0, 0], [0, 1, 0], [0, -1, 0], [0, 0, 1], [0, 0, -1]]) {
        const nk = key(x + dx, y + dy, z + dz);
        if (vox.has(nk) && !seen.has(nk)) {
          seen.add(nk);
          stack.push(nk);
        }
      }
    }
    if (!touchesGround) floating.push(comp.length);
  }
  return floating;
}

test('pipeline: the sample house validates and builds a scene', async () => {
  const spec = await loadSample('house-10x10.json');
  const v = validateSpec(spec);
  assert.equal(v.ok, true, v.errors.join('; '));
  const scene = buildScene(spec);
  assert.ok(scene.solids.length > 10);
  assert.ok(scene.solids.every((s) => s.faces.length > 0));
});

test('pipeline: the sample house voxelises without floating voxels', async () => {
  const spec = await loadSample('house-10x10.json');
  const vox = voxelizeSpec(spec);
  assert.ok(vox.size > 500, `expected a real house, got ${vox.size} voxels`);
  const floating = floatingVoxels(vox);
  assert.deepEqual(floating, [], `floating components: ${floating.join(', ')}`);
});

test('pipeline: pixels come from voxels (background only when there is nothing to hit)', async () => {
  const spec = await loadSample('house-10x10.json');
  const palette = spec.palette;
  const full = decodePng(renderVoxelPng({ voxels: voxelizeSpec(spec), palette }, { width: 200, height: 150, supersample: 1 }));
  const empty = decodePng(renderVoxelPng({ voxels: new Map(), palette }, { width: 200, height: 150, supersample: 1 }));

  // 1. an empty voxel set draws only background
  const emptyColours = new Set();
  for (let i = 0; i < empty.width * empty.height; i++) emptyColours.add(empty.rgb[i * 3] << 16 | empty.rgb[i * 3 + 1] << 8 | empty.rgb[i * 3 + 2]);
  assert.ok(emptyColours.size <= 3, `empty scene should be flat background, got ${emptyColours.size} colours`);

  // 2. a single voxel becomes visible pixels of its own material colour
  const one = new Map([['0|0|0', { material: 'roof' }]]);
  const single = decodePng(renderVoxelPng({ voxels: one, palette }, { width: 200, height: 150, supersample: 1 }));
  let modelPixels = 0;
  for (let i = 0; i < single.width * single.height; i++) {
    const r = single.rgb[i * 3], g = single.rgb[i * 3 + 1], b = single.rgb[i * 3 + 2];
    const same = r === empty.rgb[i * 3] && g === empty.rgb[i * 3 + 1] && b === empty.rgb[i * 3 + 2];
    if (!same) modelPixels++;
  }
  assert.ok(modelPixels > 200, `one voxel should cover some pixels, got ${modelPixels}`);

  // 3. deleting every roof voxel must visibly change the picture of the house
  const vox = voxelizeSpec(spec);
  const roofCount = [...vox.values()].filter((c) => c.material === 'roof').length;
  assert.ok(roofCount > 50);
  for (const [k, c] of [...vox]) if (c.material === 'roof') vox.delete(k);
  const noRoof = decodePng(renderVoxelPng({ voxels: vox, palette }, { width: 200, height: 150, supersample: 1 }));
  let changed = 0;
  for (let i = 0; i < full.rgb.length; i++) if (full.rgb[i] !== noRoof.rgb[i]) changed++;
  assert.ok(changed > full.rgb.length * 0.05, `removing all ${roofCount} roof voxels changed only ${changed} bytes`);
});

test('pipeline: spec bounds match the voxel bounds', async () => {
  const spec = await loadSample('house-10x10.json');
  const vox = voxelizeSpec(spec);
  let minX = Infinity, minY = Infinity, minZ = Infinity, maxX = -Infinity, maxY = -Infinity, maxZ = -Infinity;
  for (const k of vox.keys()) {
    const [x, y, z] = k.split('|').map(Number);
    minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
    maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
  }
  const b = specBounds(spec);
  // voxels are cell indices; the spec bounds are inclusive corners, so they must agree within 1 block
  assert.ok(Math.abs(minX - b.min[0]) <= 1, `minX ${minX} vs ${b.min[0]}`);
  assert.ok(Math.abs(maxX - b.max[0]) <= 1, `maxX ${maxX} vs ${b.max[0]}`);
  assert.ok(maxY >= b.max[1] - 2, `maxY ${maxY} vs ${b.max[1]}`);
});

test('pipeline: every voxel carries the data the Minecraft transfer needs', async () => {
  const spec = await loadSample('house-10x10.json');
  const vox = voxelizeSpec(spec);
  let checked = 0;
  for (const [k, c] of vox) {
    assert.match(k, /^-?\d+\|-?\d+\|-?\d+$/, 'key format x|y|z');
    assert.equal(typeof c.material, 'string');
    assert.equal(typeof c.up, 'boolean');
    assert.equal(typeof c.down, 'boolean');
    assert.ok(Number.isFinite(c.nx) && Number.isFinite(c.ny) && Number.isFinite(c.nz));
    checked++;
    if (checked > 300) break;
  }
  assert.ok(checked > 300);
});

test('pipeline: roof voxels are mostly top faces (that is what makes stairs in Minecraft)', async () => {
  const spec = await loadSample('house-10x10.json');
  const vox = voxelizeSpec(spec);
  const roof = [...vox.values()].filter((c) => c.material === 'roof');
  assert.ok(roof.length > 50);
  const withUp = roof.filter((c) => c.up).length;
  assert.ok(withUp > roof.length * 0.3, `only ${withUp}/${roof.length} roof cells expose a top surface`);
});
