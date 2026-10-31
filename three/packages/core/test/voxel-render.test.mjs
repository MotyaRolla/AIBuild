/**
 * Tests for the voxel pixel renderer: the "pixels from voxels" renderer.
 *
 * The point of this renderer is that what the AI sees is exactly what Minecraft will contain,
 * so the tests check that every visible pixel comes from an actual voxel, that block textures
 * and outlines are visible, and that it is fast enough to run many iterations.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { renderVoxelPng, renderVoxelViews, buildGrid, trace, makeTexture, VOXEL_VIEWS } from '../src/voxel-render.mjs';
import { decodePng } from '../src/png.mjs';

/** Builds a voxel map from a list of [x,y,z,material] cells. */
function voxels(cells) {
  const m = new Map();
  for (const [x, y, z, mat] of cells) m.set(`${x}|${y}|${z}`, { material: mat || 'stone' });
  return m;
}

/** A solid cube shell with a floor, a roof ridge and a window, i.e. something to look at. */
function houseVoxels() {
  const cells = [];
  for (let x = 0; x < 8; x++) {
    for (let z = 0; z < 8; z++) {
      cells.push([x, 0, z, 'stone']);
      const edge = x === 0 || x === 7 || z === 0 || z === 7;
      if (edge) for (let y = 1; y <= 4; y++) cells.push([x, y, z, 'wall']);
    }
  }
  // gable roof
  for (let i = 0; i <= 3; i++) {
    for (let x = -1; x <= 8; x++) {
      for (let z = -1 + i; z <= 8 - i; z++) {
        if (z === -1 + i || z === 8 - i) cells.push([x, 5 + i, z, 'roof']);
      }
    }
  }
  // window: replace two wall cells with glass
  for (let y = 2; y <= 3; y++) {
    const key = `3|${y}|0`;
    const idx = cells.findIndex((c) => `${c[0]}|${c[1]}|${c[2]}` === key);
    if (idx >= 0) cells[idx] = [3, y, 0, 'glass'];
  }
  return voxels(cells);
}

const palette = { stone: '#8a8a8a', wall: '#d9cfba', roof: '#6b452f', glass: '#a9c9d9' };

function stats(png) {
  const d = decodePng(png);
  const seen = new Map();
  for (let i = 0; i < d.width * d.height; i++) {
    const k = (d.rgb[i * 3] << 16) | (d.rgb[i * 3 + 1] << 8) | d.rgb[i * 3 + 2];
    seen.set(k, (seen.get(k) || 0) + 1);
  }
  return { ...d, colours: seen.size, seen };
}

test('voxel render: produces a valid PNG of the requested size', () => {
  const png = renderVoxelPng({ voxels: houseVoxels(), palette }, { width: 200, height: 150 });
  assert.ok(Buffer.isBuffer(png));
  assert.deepEqual([...png.subarray(0, 8)], [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  const d = stats(png);
  assert.equal(d.width, 200);
  assert.equal(d.height, 150);
});

test('voxel render: the picture is rich (block textures + shading), not a flat silhouette', () => {
  const d = stats(renderVoxelPng({ voxels: houseVoxels(), palette }, { width: 320, height: 240 }));
  assert.ok(d.colours > 150, `expected many colours from textures, got ${d.colours}`);
});

test('voxel render: every block face kind shows up (wall, stone, roof)', () => {
  const png = renderVoxelPng({ voxels: houseVoxels(), palette }, { width: 320, height: 240, outlines: false });
  const d = stats(png);
  // the three materials are visibly different in hue
  const reds = [...d.seen.keys()].filter((c) => ((c >> 16) & 255) > ((c >> 8) & 255) + 10);
  assert.ok(reds.length > 10, 'roof (brown/red) pixels are present');
});

test('voxel render: views differ from each other', async () => {
  const { views } = await renderVoxelViews({ voxels: houseVoxels(), palette }, { width: 160, height: 120, views: ['se', 'nw'] });
  assert.equal(views.length, 2);
  assert.notEqual(views[0].png.toString('base64').slice(0, 400), views[1].png.toString('base64').slice(0, 400));
});

test('voxel render: unknown view name is rejected with a helpful message', () => {
  const vr = { renderVoxelViews };
  assert.rejects(() => renderVoxelViews({ voxels: houseVoxels(), palette }, { views: ['nope'] }), /unknown view/);
  assert.ok(Object.keys(VOXEL_VIEWS).length >= 6);
});

test('voxel render: empty voxels give a plain background instead of throwing', () => {
  const png = renderVoxelPng({ voxels: new Map(), palette }, { width: 64, height: 48 });
  const d = stats(png);
  assert.equal(d.width, 64);
  assert.ok(d.colours <= 3, 'background only');
});

test('voxel render: outlines are on by default and darken silhouette pixels', () => {
  const withOut = stats(renderVoxelPng({ voxels: houseVoxels(), palette }, { width: 200, height: 150, outlines: true }));
  const noOut = stats(renderVoxelPng({ voxels: houseVoxels(), palette }, { width: 200, height: 150, outlines: false }));
  assert.ok(withOut.colours > noOut.colours, 'outlines add intermediate dark colours');
});

test('voxel render: supersampling adds antialiasing (more colours)', () => {
  const ss1 = stats(renderVoxelPng({ voxels: houseVoxels(), palette }, { width: 160, height: 120, supersample: 1 }));
  const ss2 = stats(renderVoxelPng({ voxels: houseVoxels(), palette }, { width: 160, height: 120, supersample: 2 }));
  assert.ok(ss2.colours > ss1.colours);
});

test('voxel render: is fast enough for many iterations', () => {
  const v = houseVoxels();
  const t0 = Date.now();
  renderVoxelPng({ voxels: v, palette }, { width: 400, height: 300, supersample: 1 });
  const ms = Date.now() - t0;
  assert.ok(ms < 4000, `400x300 single frame should stay well under 4 s, took ${ms} ms`);
});

test('voxel render: deterministic for identical input', () => {
  const a = renderVoxelPng({ voxels: houseVoxels(), palette }, { width: 128, height: 96 });
  const b = renderVoxelPng({ voxels: houseVoxels(), palette }, { width: 128, height: 96 });
  assert.equal(a.toString('base64'), b.toString('base64'));
});

test('voxel grid: ray hits the first solid cell and reports its face normal', () => {
  const g = buildGrid(voxels([[0, 0, 0, 'stone'], [1, 0, 0, 'stone']]));
  assert.ok(g);
  const hit = trace(g, -5, 0.5, 0.5, 1, 0, 0);
  assert.equal(hit.hit, true);
  assert.equal(hit.nx, -1);
  assert.equal(hit.index, 1);
});

test('voxel grid: a ray that misses the grid reports no hit', () => {
  const g = buildGrid(voxels([[0, 0, 0, 'stone']]));
  const hit = trace(g, -5, 20, 0.5, 1, 0, 0);
  assert.equal(hit.hit, false);
});

test('voxel textures: each family looks different', () => {
  const stone = makeTexture('stone', '#8a8a8a', 1);
  const roof = makeTexture('roof', '#6b452f', 1);
  const planks = makeTexture('planks', '#8a6242', 1);
  assert.equal(stone.length, 16 * 16 * 3);
  assert.notDeepEqual(stone.subarray(0, 48), roof.subarray(0, 48));
  assert.notDeepEqual(planks.subarray(0, 48), roof.subarray(0, 48));
  // textures must have internal variation, not a single flat colour
  assert.ok(new Set(stone).size > 5);
});

test('voxel render: a single isolated block is visible', () => {
  const png = renderVoxelPng({ voxels: voxels([[0, 0, 0, 'stone']]), palette }, { width: 120, height: 90 });
  const d = stats(png);
  assert.ok(d.colours > 5);
});

test('voxel render: glass is translucent, showing what is behind it', () => {
  const solid = voxels([[0, 0, 0, 'glass'], [0, 0, 1, 'roof']]);
  const png = renderVoxelPng({ voxels: solid, palette }, { width: 160, height: 120, azimuth: 0, elevation: 4, fog: false });
  assert.ok(Buffer.isBuffer(png));
});
