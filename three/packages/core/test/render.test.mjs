/**
 * Tests for the software renderer and the hand-written PNG encoder.
 *
 * The scenes here are hand-built (cube, slab, gable roof, glazed wall) so the suite does not
 * depend on the geometry module; a final test tries the real `buildScene` via dynamic import
 * and stays skipped while task A has not landed yet.
 */

import test from 'node:test';
import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

import { encodePng, makePng, decodePng, crc32, pngChunk, PNG_SIGNATURE } from '../src/png.mjs';
import { renderViews, renderPng, cameraForView, DEFAULT_VIEWS, VIEW_ANGLES } from '../src/render.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));

/** Task A's geometry module: integration tests are skipped while it has not landed yet. */
const geometryPath = join(HERE, '..', 'src', 'geometry.mjs');
const hasGeometry = existsSync(geometryPath);

/* ------------------------------------------------------------------------------------ */
/* scene builders                                                                        */
/* ------------------------------------------------------------------------------------ */

/** One quad face with an outward normal. */
const quad = (a, b, c, d, material, normal, kind = 'surface') => ({ v: [a, b, c, d], normal, material, kind });

/**
 * Closed axis-aligned box as a list of quad faces with outward normals.
 * @param {number[]} from
 * @param {number[]} to
 */
function boxFaces(from, to, material, kind = 'surface') {
  const [x0, y0, z0] = from;
  const [x1, y1, z1] = to;
  return [
    quad([x0, y0, z1], [x1, y0, z1], [x1, y1, z1], [x0, y1, z1], material, [0, 0, 1], kind),
    quad([x1, y0, z0], [x0, y0, z0], [x0, y1, z0], [x1, y1, z0], material, [0, 0, -1], kind),
    quad([x1, y0, z1], [x1, y0, z0], [x1, y1, z0], [x1, y1, z1], material, [1, 0, 0], kind),
    quad([x0, y0, z0], [x0, y0, z1], [x0, y1, z1], [x0, y1, z0], material, [-1, 0, 0], kind),
    quad([x0, y1, z0], [x0, y1, z1], [x1, y1, z1], [x1, y1, z0], material, [0, 1, 0], kind),
    quad([x0, y0, z0], [x1, y0, z0], [x1, y0, z1], [x0, y0, z1], material, [0, -1, 0], kind),
  ];
}

/** A single solid wrapping the given faces. */
const solid = (id, faces, material = 'wall') => ({ id, type: 'box', material, faces });

/** 1x1x1 cube at the origin. */
const cubeScene = () => ({
  spec: { name: 'unit cube', palette: {} },
  solids: [solid('cube', boxFaces([0, 0, 0], [1, 1, 1], 'wall'))],
});

/**
 * A small house-like scene: shell, floor slab, stepped gable roof, chimney, glass windows,
 * a timber beam, plus an open (non-closed) detail cluster — enough to exercise every pass.
 */
function houseScene() {
  const solids = [];
  const shell = [];
  // four walls 10x10, 6 high
  shell.push(...boxFaces([0, 0, 0], [10, 0.4, 10], 'stone'));
  shell.push(...boxFaces([0, 0.4, 10], [10, 6, 10.3], 'wall'));
  shell.push(...boxFaces([0, 0.4, -0.3], [10, 6, 0], 'wall'));
  shell.push(...boxFaces([10, 0.4, 0], [10.3, 6, 10], 'wall'));
  shell.push(...boxFaces([-0.3, 0.4, 0], [0, 6, 10], 'wall'));
  solids.push(solid('walls', shell, 'wall'));

  // stepped gable roof, ridge along x
  const roof = [];
  for (let k = 0; k < 5; k++) {
    roof.push(...boxFaces([-0.6, 6 + k, 2.2 + k * 1.1], [10.6, 6.7 + k, 7.8 - k * 1.1], k % 2 ? 'roof' : 'roof_accent'));
  }
  solids.push({ id: 'roof', type: 'prism', material: 'roof', faces: roof });

  solids.push(solid('chimney', boxFaces([7, 6, 1.4], [8.2, 11, 2.6], 'stone_dark'), 'stone_dark'));
  solids.push({ id: 'win-s', type: 'window', material: 'glass', faces: boxFaces([6.2, 2, 10.25], [8.2, 4, 10.32], 'glass', 'glass') });
  solids.push({ id: 'win-w', type: 'window', material: 'glass', faces: boxFaces([1.8, 2, 10.25], [3.8, 4, 10.32], 'glass', 'glass') });
  solids.push(solid('door', boxFaces([4, 0.4, 10.1], [6, 3.4, 10.25], 'wood'), 'wood'));
  solids.push({ id: 'beam', type: 'beam', material: 'frame', faces: boxFaces([0, 6, 10.15], [10, 6.35, 10.4], 'frame') });
  return { spec: { name: 'house', palette: {} }, solids };
}

/** Scene with a flat single-face roof: must stay visible (never culled as "closed"). */
function openPlaneScene() {
  return {
    spec: { name: 'plane', palette: {} },
    solids: [
      solid('box', boxFaces([0, 0, 0], [4, 2, 4], 'wall')),
      { id: 'canopy', type: 'detail', material: 'roof', faces: [quad([-1, 3, -1], [5, 3, -1], [5, 3, 5], [-1, 3, 5], 'roof', [0, 1, 0])] },
    ],
  };
}

/** Counts distinct RGB colours in a decoded image. */
function distinctColors(rgb) {
  const set = new Set();
  for (let i = 0; i < rgb.length; i += 3) set.add((rgb[i] << 16) | (rgb[i + 1] << 8) | rgb[i + 2]);
  return set.size;
}

const rgbOf = (png) => decodePng(png).rgb;

/* ------------------------------------------------------------------------------------ */
/* PNG encoder                                                                           */
/* ------------------------------------------------------------------------------------ */

test('png: signature is correct', () => {
  const png = encodePng(4, 3, new Uint8Array(4 * 3 * 3).fill(128));
  assert.ok(png.length > 8);
  assert.deepEqual([...png.subarray(0, 8)], [...PNG_SIGNATURE]);
  assert.equal(png.subarray(0, 8).toString('hex'), '89504e470d0a1a0a');
});

test('png: IHDR width and height are correct for several sizes', () => {
  for (const [w, h] of [[1, 1], [7, 3], [800, 600], [64, 97], [3, 2]]) {
    const png = encodePng(w, h, new Uint8Array(w * h * 3).fill(200));
    // IHDR data starts at byte 16 (8 signature + 4 length + 4 type)
    assert.equal(png.readUInt32BE(16), w, `width for ${w}x${h}`);
    assert.equal(png.readUInt32BE(20), h, `height for ${w}x${h}`);
    // bit depth 8, colour type 2 (RGB), no interlace
    assert.equal(png[24], 8);
    assert.equal(png[25], 2);
    assert.equal(png[28], 0);
    // chunk order and CRC validity
    const decoded = decodePng(png);
    assert.equal(decoded.width, w);
    assert.equal(decoded.height, h);
    assert.deepEqual(decoded.chunks, ['IHDR', 'IDAT', 'IEND']);
  }
});

test('png: crc32 matches the reference check value and chunk CRCs are valid', () => {
  assert.equal(crc32(Buffer.from('123456789')), 0xcbf43926);
  const data = Buffer.from('IEND');
  const chunk = pngChunk('tEXt', data);
  const len = chunk.readUInt32BE(0);
  assert.equal(len, 4);
  const expected = crc32(chunk.subarray(4, 8 + len));
  assert.equal(chunk.readUInt32BE(8 + len), expected);
});

test('png: round-trips pixels exactly, including the paeth/bad-filter paths', () => {
  const w = 37;
  const h = 23;
  const rgb = new Uint8Array(w * h * 3);
  // noisy gradient so every filter type can win on some row
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const o = (y * w + x) * 3;
      rgb[o] = (x * 7 + y * 13) & 0xff;
      rgb[o + 1] = (x * x + y) & 0xff;
      rgb[o + 2] = (y * y + x * 31) & 0xff;
    }
  }
  for (const opts of [{}, { adaptiveFilter: false }, { filter: 4 }, { filter: 1 }]) {
    const decoded = decodePng(encodePng(w, h, rgb, opts));
    assert.deepEqual([...decoded.rgb], [...rgb], `pixels for ${JSON.stringify(opts)}`);
  }
});

test('png: makePng accepts RGBA and drops the alpha channel', () => {
  const w = 5;
  const h = 4;
  const rgba = new Uint8Array(w * h * 4);
  for (let i = 0; i < w * h; i++) {
    rgba[i * 4] = i * 3;
    rgba[i * 4 + 1] = 255 - i * 3;
    rgba[i * 4 + 2] = (i * 7) & 0xff;
    rgba[i * 4 + 3] = 40; // ignored
  }
  const decoded = decodePng(makePng(w, h, rgba));
  assert.equal(decoded.width, w);
  assert.equal(decoded.height, h);
  for (let i = 0; i < w * h; i++) {
    assert.equal(decoded.rgb[i * 3], rgba[i * 4], `red #${i}`);
    assert.equal(decoded.rgb[i * 3 + 1], rgba[i * 4 + 1], `green #${i}`);
    assert.equal(decoded.rgb[i * 3 + 2], rgba[i * 4 + 2], `blue #${i}`);
  }
});

test('png: rejects invalid sizes and short buffers', () => {
  assert.throws(() => encodePng(0, 10, new Uint8Array(0)), /positive integers/);
  assert.throws(() => encodePng(10, 10, new Uint8Array(4)), /expected 300 RGB bytes/);
  assert.throws(() => decodePng(Buffer.from('not a png')), /bad signature/);
});

/* ------------------------------------------------------------------------------------ */
/* renderer basics                                                                       */
/* ------------------------------------------------------------------------------------ */

test('render: default view list covers the orbit and the plan', () => {
  assert.deepEqual([...DEFAULT_VIEWS], ['se', 'sw', 'ne', 'nw', 'front', 'top', 'back']);
  for (const name of DEFAULT_VIEWS) assert.ok(VIEW_ANGLES[name], `angles for ${name}`);
});

test('render: renders every default view with a valid PNG of the requested size', async () => {
  const { views, width, height } = await renderViews(cubeScene(), { width: 240, height: 180 });
  assert.equal(width, 240);
  assert.equal(height, 180);
  assert.equal(views.length, DEFAULT_VIEWS.length);
  for (const view of views) {
    assert.equal(typeof view.name, 'string');
    assert.ok(Buffer.isBuffer(view.png));
    assert.deepEqual([...view.png.subarray(0, 8)], [...PNG_SIGNATURE], `${view.name} signature`);
    const decoded = decodePng(view.png);
    assert.equal(decoded.width, 240);
    assert.equal(decoded.height, 180);
    assert.ok(Array.isArray(view.camera.position) && view.camera.position.length === 3);
    assert.ok(Array.isArray(view.camera.target) && view.camera.target.length === 3);
    assert.equal(typeof view.camera.fov, 'number');
    assert.ok(view.camera.distance > 0);
  }
});

test('render: the image is not monochrome (well over 50 distinct colours)', async () => {
  const { views } = await renderViews(houseScene(), { width: 400, height: 300, views: ['se'] });
  const colors = distinctColors(rgbOf(views[0].png));
  assert.ok(colors > 50, `expected > 50 distinct colours, got ${colors}`);
  assert.ok(colors > 500, `graded shading should give many colours, got ${colors}`);
});

test('render: different views produce different bytes and different cameras', async () => {
  const scene = houseScene();
  const { views } = await renderViews(scene, { width: 300, height: 220, views: ['se', 'nw', 'top', 'front'] });
  const seen = new Map();
  for (const view of views) {
    const hex = view.png.toString('hex');
    const other = seen.get(hex);
    assert.equal(other, undefined, `view '${view.name}' is byte-identical to '${other}'`);
    seen.set(hex, view.name);
  }
  assert.notDeepEqual(views[0].camera.position, views[1].camera.position);
  const topCam = views.find((v) => v.name === 'top').camera;
  assert.ok(topCam.position[1] > 0 && Math.abs(topCam.position[2] - topCam.target[2]) < 1e-6);
});

test('render: draft mode is faster than full mode and halves the output', async () => {
  const scene = houseScene();
  const opts = { width: 400, height: 300, views: ['se'] };
  // warm the JIT first so the comparison measures the render, not the first-call compile
  await renderViews(scene, { ...opts, draft: true });
  await renderViews(scene, opts);
  const bestDraft = Math.min(...(await Promise.all([1, 2].map(async () => (await renderViews(scene, { ...opts, draft: true })).ms))));
  const bestFull = Math.min(...(await Promise.all([1, 2].map(async () => (await renderViews(scene, opts)).ms))));

  const draft = await renderViews(scene, { ...opts, draft: true });
  const full = await renderViews(scene, opts);
  assert.deepEqual([draft.width, draft.height], [200, 150]);
  assert.deepEqual([full.width, full.height], [400, 300]);
  const draftDecoded = decodePng(draft.views[0].png);
  assert.equal(draftDecoded.width, 200);
  assert.equal(draftDecoded.height, 150);
  assert.ok(
    bestDraft < bestFull,
    `draft (${bestDraft} ms) should be faster than full (${bestFull} ms)`,
  );
  // draft is a real render, not a blank frame
  assert.ok(distinctColors(draftDecoded.rgb) > 20);
});

test('render: renderViews reports the camera it actually used', async () => {
  const scene = houseScene();
  const opts = { width: 320, height: 240 };
  const { views } = await renderViews(scene, { ...opts, views: ['se', 'nw'] });
  for (const view of views) {
    const expected = cameraForView(scene, view.name, opts);
    assert.deepEqual(view.camera.position, expected.position, `${view.name} position`);
    assert.deepEqual(view.camera.target, expected.target, `${view.name} target`);
    assert.equal(view.camera.fov, expected.fov);
  }
  // an empty view list falls back to the default orbit
  const fallback = await renderViews(scene, { ...opts, views: [] });
  assert.equal(fallback.views.length, DEFAULT_VIEWS.length);
});

test('render: a house-like scene renders without throwing and fills a good part of the frame', async () => {
  const scene = houseScene();
  const { views } = await renderViews(scene, { width: 320, height: 240, views: ['se'] });
  const rgb = rgbOf(views[0].png);
  // count pixels that are clearly not the background gradient / ground
  const min = Math.min(views[0].camera.position[1], views[0].camera.target[1]);
  assert.ok(Number.isFinite(min));
  const colors = distinctColors(rgb);
  assert.ok(colors > 200, `house should be shaded, got ${colors} colours`);
  // the model must occupy a reasonable share of the frame: look for dark/detail pixels
  let modelish = 0;
  for (let i = 0; i < rgb.length; i += 3) {
    if (rgb[i] < 190 || rgb[i + 1] < 190 || rgb[i + 2] < 190) modelish++;
  }
  const share = modelish / (rgb.length / 3);
  assert.ok(share > 0.1, `model should cover >10% of the frame, got ${(share * 100).toFixed(1)}%`);
});

test('render: empty scene returns a plain background PNG instead of throwing', async () => {
  for (const scene of [{ solids: [] }, {}, { solids: [{ id: 'empty', faces: [] }] }, null]) {
    const { views } = await renderViews(scene, { width: 120, height: 90, views: ['se', 'top'] });
    assert.equal(views.length, 2);
    for (const view of views) {
      const decoded = decodePng(view.png);
      assert.equal(decoded.width, 120);
      assert.equal(decoded.height, 90);
      // flat background: a single colour everywhere, with no exception pixels
      const colors = new Set();
      for (let i = 0; i < decoded.rgb.length; i += 3) {
        colors.add((decoded.rgb[i] << 16) | (decoded.rgb[i + 1] << 8) | decoded.rgb[i + 2]);
      }
      assert.equal(colors.size, 1, 'empty scene should be a flat background');
    }
  }
});

test('render: custom background colour is honoured', async () => {
  const png = await renderPng({ solids: [] }, 'se', { width: 40, height: 30, background: '#102030' });
  const { rgb } = decodePng(png);
  assert.deepEqual([rgb[0], rgb[1], rgb[2]].map(Math.round), [16, 32, 48]);
});

/* ------------------------------------------------------------------------------------ */
/* camera, culling, transparency                                                          */
/* ------------------------------------------------------------------------------------ */

test('render: camera auto-fits the bounding box for tiny and huge models', async () => {
  const tiny = { solids: [solid('t', boxFaces([0, 0, 0], [1, 1, 1], 'wall'))] };
  const huge = { solids: [solid('h', boxFaces([0, 0, 0], [400, 90, 400], 'wall'))] };
  const camTiny = cameraForView(tiny, 'se', { width: 400, height: 300 });
  const camHuge = cameraForView(huge, 'se', { width: 400, height: 300 });
  assert.ok(camHuge.distance > camTiny.distance * 50, 'distance should scale with the model');
  // the target is the bbox centre in both cases
  assert.deepEqual(camTiny.target.map((v) => Math.round(v)), [1, 1, 1]);
  assert.deepEqual(camHuge.target.map((v) => Math.round(v)), [200, 45, 200]);
});

test('render: inverted winding is detected and still shaded correctly', async () => {
  const normal = cubeScene();
  const flipped = {
    spec: normal.spec,
    solids: normal.solids.map((s) => ({
      ...s,
      // reverse the vertex order of every face, keep the stated normal
      faces: s.faces.map((f) => ({ ...f, v: [...f.v].reverse() })),
    })),
  };
  const a = rgbOf(await renderPng(normal, 'se', { width: 160, height: 120 }));
  const b = rgbOf(await renderPng(flipped, 'se', { width: 160, height: 120 }));
  let maxDiff = 0;
  for (let i = 0; i < a.length; i++) maxDiff = Math.max(maxDiff, Math.abs(a[i] - b[i]));
  assert.ok(maxDiff < 8, `winding should not change the image (max channel diff ${maxDiff})`);
});

test('render: glass faces are translucent, opaque materials are not', async () => {
  const openBox = () => solid('shell', [
    ...boxFaces([0, 0, 0], [10, 6, 0.4], 'wall'), // south wall band
    ...boxFaces([0, 0, 0], [0.4, 6, 10], 'wall'),
  ]);
  const glass = { solids: [openBox(), { id: 'window', type: 'window', material: 'glass', faces: boxFaces([2, 1, 1], [8, 5, 4], 'glass', 'glass') }] };
  const clear = await renderPng(glass, 'se', { width: 200, height: 150, glassAlpha: 0.3 });
  const opaque = await renderPng(glass, 'se', { width: 200, height: 150, glassAlpha: 1 });
  assert.notEqual(clear.toString('hex'), opaque.toString('hex'));
  // hidden geometry behind the glass shows through when translucent
  const transparentScene = {
    solids: [
      ...glass.solids,
      solid('hidden', boxFaces([6, 1, 6], [9, 4, 9], 'roof'), 'roof'),
    ],
  };
  const translucent = rgbOf(await renderPng(transparentScene, 'se', { width: 200, height: 150, glassAlpha: 0.3 }));
  const solidPanes = rgbOf(await renderPng(transparentScene, 'se', { width: 200, height: 150, glassAlpha: 1 }));
  let diff = 0;
  for (let i = 0; i < translucent.length; i += 3) {
    if (Math.abs(translucent[i] - solidPanes[i]) > 12) diff++;
  }
  assert.ok(diff > 50, `translucency should change pixels behind glass (${diff} px differ)`);
});

test('render: a flat single-face canopy is never culled away', async () => {
  const withCanopy = openPlaneScene();
  const withoutCanopy = { solids: [withCanopy.solids[0]] };
  const a = rgbOf(await renderPng(withCanopy, 'se', { width: 240, height: 180 }));
  const b = rgbOf(await renderPng(withoutCanopy, 'se', { width: 240, height: 180 }));
  let diff = 0;
  for (let i = 0; i < a.length; i += 3) if (a[i] !== b[i] || a[i + 1] !== b[i + 1] || a[i + 2] !== b[i + 2]) diff++;
  assert.ok(diff > 100, `open canopy should be visible (${diff} px differ)`);
});

/* ------------------------------------------------------------------------------------ */
/* robustness and options                                                                 */
/* ------------------------------------------------------------------------------------ */

test('render: detail faces coplanar with the wall do not z-fight into stripes', {
  skip: hasGeometry ? false : 'packages/core/src/geometry.mjs not available yet',
}, async () => {
  // Real geometry puts window frames exactly on the wall plane (both at z = 10). Without a
  // depth bias the two coplanar surfaces interleave per pixel and the frame renders as a set
  // of horizontal stripes. The artifact is resolution dependent, so check at 600 px.
  const { buildScene } = await import('../src/geometry.mjs');
  const scene = buildScene({
    name: 'coplanar window', palette: {},
    objects: [
      { id: 'body', type: 'box', from: [0, 0, 0], to: [9, 6, 9], material: 'wall', hollow: true },
      { id: 'win1', type: 'window', wall: 'south', at: [1, 2], w: 2, h: 2, frame: 'frame', glass: 'glass', sill: true },
    ],
  });
  const { rgb } = decodePng(await renderPng(scene, 'front', {
    width: 600, height: 600, outline: false, vignette: 0, supersample: 1, shadows: false,
  }));
  // Count alternating wall/frame transitions inside the frame region. A clean flat-shaded
  // frame changes colour a handful of times per scanline; z-fighting pushes it to 20+.
  let worst = 0;
  for (let y = 150; y < 520; y++) {
    let alternations = 0;
    let prev = null;
    for (let x = 20; x < 300; x++) {
      const o = (y * 600 + x) * 3;
      const c = (rgb[o] << 16) | (rgb[o + 1] << 8) | rgb[o + 2];
      if (c !== prev) alternations++;
      prev = c;
    }
    if (alternations > worst) worst = alternations;
  }
  assert.ok(worst < 15, `worst scanline had ${worst} colour transitions (z-fighting stripes)`);
});

test('render: malformed faces are skipped, not fatal', async () => {
  const scene = {
    spec: { name: 'broken', palette: {} },
    solids: [
      { id: 'a', faces: [null, { v: [] }, { v: [[0, 0, 0]] }, { v: [[0, 0, 0], [1, 0, 0]] }, { v: [[0, 0, 0], [0, 1, 0], [1, 0, 0]] }] },
      { id: 'b', material: 'wall', faces: boxFaces([0, 0, 0], [2, 2, 2], 'wall') },
    ],
  };
  const png = await renderPng(scene, 'se', { width: 100, height: 80 });
  assert.equal(decodePng(png).width, 100);
});

test('render: unknown material keys still get a colour', async () => {
  const scene = { solids: [solid('x', boxFaces([0, 0, 0], [2, 2, 2], 'not-a-palette-key'))] };
  const { rgb } = decodePng(await renderPng(scene, 'se', { width: 120, height: 90 }));
  // the cube differs from the empty background in the centre
  const centre = (45 * 120 + 60) * 3;
  assert.ok(rgb[centre] !== rgb[0] || rgb[centre + 1] !== rgb[1] || rgb[centre + 2] !== rgb[2]);
});

test('render: unknown view name throws a helpful error', async () => {
  await assert.rejects(() => renderPng(cubeScene(), 'nope'), /Unknown view 'nope'/);
});

test('render: custom view descriptors and options are accepted', async () => {
  const png = await renderPng(cubeScene(), { name: 'low', azimuth: 200, elevation: 5, distance: 12 }, {
    width: 160,
    height: 120,
    fov: 50,
    background: '#ffffff',
    shadows: false,
    outline: false,
    supersample: 1,
    vignette: 0,
    pngLevel: 1,
  });
  assert.deepEqual([...png.subarray(0, 8)], [...PNG_SIGNATURE]);
  const cam = cameraForView(cubeScene(), { name: 'low', azimuth: 200, elevation: 5, distance: 12 }, { width: 160, height: 120 });
  assert.ok(Math.abs(cam.distance - 12) < 1e-6, 'explicit distance should be respected');
  // azimuth is measured from +z (south) towards +x (east); 200 deg is the north-west quadrant
  assert.ok(cam.position[0] < cam.target[0], 'camera should be west of the target');
  assert.ok(cam.position[2] < cam.target[2], 'camera should be north of the target');
  assert.ok(cam.position[1] > cam.target[1], 'positive elevation => camera above the target');
});

test('render: png output is deterministic for identical input', async () => {
  const scene = houseScene();
  const a = await renderPng(scene, 'se', { width: 200, height: 150 });
  const b = await renderPng(scene, 'se', { width: 200, height: 150 });
  assert.equal(a.toString('hex'), b.toString('hex'));
});

/* ------------------------------------------------------------------------------------ */
/* real geometry (task A), skipped while geometry.mjs does not exist                      */
/* ------------------------------------------------------------------------------------ */

test('render: real buildScene output renders (integration, skips gracefully without geometry.mjs)', {
  skip: hasGeometry ? false : 'packages/core/src/geometry.mjs not available yet',
}, async () => {
  const { buildScene } = await import('../src/geometry.mjs');
  const spec = {
    name: 'integration house', seed: 3,
    palette: { wall: '#d9cfba', frame: '#3a2a1c', roof: '#6b452f', glass: '#a9c9d9', stone: '#8a8a8a' },
    objects: [
      { id: 'body', type: 'box', from: [0, 0, 0], to: [9, 6, 9], material: 'wall', hollow: true },
      { id: 'roof', type: 'prism', from: [0, 6, 0], to: [9, 6, 9], style: 'gable', ridge: 'x', material: 'roof' },
      { id: 'door', type: 'door', wall: 'south', at: [4, 0], w: 2, h: 3, frame: 'frame' },
      { id: 'win', type: 'window', wall: 'south', at: [1, 3], w: 1, h: 2, frame: 'frame', glass: 'glass' },
    ],
  };
  const scene = buildScene(spec);
  assert.ok(scene && Array.isArray(scene.solids), 'buildScene should return a scene with solids');
  const { views } = await renderViews(scene, { width: 400, height: 300, views: ['se', 'front'], draft: true });
  for (const view of views) {
    const decoded = decodePng(view.png);
    assert.equal(decoded.width, 200);
    assert.equal(decoded.height, 150);
    assert.ok(distinctColors(decoded.rgb) > 50, `real scene should be shaded (${view.name})`);
  }
});

test('render: every buildScene object type is renderable (integration)', {
  skip: hasGeometry ? false : 'packages/core/src/geometry.mjs not available yet',
}, async () => {
  const { buildScene } = await import('../src/geometry.mjs');
  const objects = [
    { id: 'box', type: 'box', from: [0, 0, 0], to: [3, 3, 3], material: 'wall' },
    { id: 'prism', type: 'prism', from: [0, 3, 0], to: [3, 3, 3], style: 'gable', ridge: 'x', material: 'roof' },
    { id: 'cyl', type: 'cylinder', center: [8, 0, 0], radius: 1.2, height: 4, material: 'stone' },
    { id: 'cone', type: 'cone', center: [8, 4, 0], radius: 1.2, height: 3, material: 'roof' },
    { id: 'sphere', type: 'sphere', center: [0, 2, 8], radius: 1.5, material: 'metal' },
    { id: 'ring', type: 'ring', from: [0, 0, 0], to: [3, 0, 3], y: 3, material: 'trim' },
    { id: 'floor', type: 'floor', from: [0, 0, 0], to: [3, 0, 3], material: 'stone', thickness: 1 },
    { id: 'stairs', type: 'stairs', from: [5, 0, 5], to: [7, 2, 8], axis: 'z', material: 'wood' },
    { id: 'bal', type: 'balustrade', from: [0, 4, 0], to: [3, 4, 0], material: 'frame' },
    { id: 'beam', type: 'beam', from: [0, 0, 0], to: [3, 4, 0], material: 'frame' },
    { id: 'col', type: 'column', at: [5, 0, 0], height: 4, radius: 0.4, material: 'stone' },
    { id: 'chim', type: 'chimney', at: [2, 3, 2], w: 1, d: 1, height: 3, material: 'stone' },
    { id: 'detail', type: 'detail', cells: [[0, 0, 0], [1, 1, 1]], material: 'leaf' },
  ];
  const scene = buildScene({ name: 'all types', objects, palette: {} });
  const { views } = await renderViews(scene, { width: 320, height: 240, views: ['se', 'nw', 'top'], draft: true });
  for (const view of views) {
    const decoded = decodePng(view.png);
    assert.equal(decoded.width, 160);
    assert.equal(decoded.height, 120);
    assert.ok(distinctColors(decoded.rgb) > 20, `${view.name} should be shaded`);
  }
});

test('render: a 10x10 house with ~5k faces renders in a couple of seconds (perf target)', async () => {
  // A brick-built house: 30x30 tiled wall blocks, each 6 quads -> 5400 quads / 10800 triangles.
  const solids = [];
  for (let i = 0; i < 30; i++) {
    for (let j = 0; j < 30; j++) {
      const mat = i % 4 ? 'wall' : 'roof';
      solids.push(solid(`t${i}_${j}`, boxFaces([i, (i + j) % 3, j], [i + 1, ((i + j) % 3) + 1, j + 1], mat), mat));
    }
  }
  const scene = { spec: { name: 'tiled', palette: {} }, solids };
  const faces = solids.reduce((n, s) => n + s.faces.length, 0);
  assert.ok(faces >= 5000, `expected a >=5000 face scene, built ${faces}`);
  // take the best of three runs: the target is about throughput, not one cold-start sample
  let best = Infinity;
  let png;
  for (let i = 0; i < 3; i++) {
    const started = Date.now();
    png = await renderPng(scene, 'se', { width: 800, height: 600, supersample: 1 });
    best = Math.min(best, Date.now() - started);
  }
  assert.equal(decodePng(png).width, 800);
  assert.ok(best < 3000, `best 800x600 render took ${best} ms (target: a couple of seconds)`);
});

test('render: draft 400x300 is under the 1.5 s budget', async () => {
  const scene = houseScene();
  await renderPng(scene, 'se', { width: 400, height: 300, draft: true }); // warm up
  let best = Infinity;
  for (let i = 0; i < 3; i++) {
    const started = Date.now();
    await renderPng(scene, 'se', { width: 400, height: 300, draft: true });
    best = Math.min(best, Date.now() - started);
  }
  assert.ok(best < 1500, `best draft 400x300 frame took ${best} ms (budget 1500 ms)`);
});
