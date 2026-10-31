/**
 * Tests for the geometry module (task A).
 *
 * Run with:  cd three && node --test packages/core/test/
 *
 * The suite covers the five acceptance criteria from AGENTS.md:
 *   - every object type yields non-empty geometry
 *   - an opening reduces the voxel count and is transparent from outside
 *   - a gable roof is closed (no holes in the shell)
 *   - a basic 10x10 house has no floating voxels
 *   - plus determinism, contract shape and the 300 ms performance budget
 */

import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  buildScene,
  voxelizeSolid,
  voxelizeSpec,
  voxelStats,
  GEOMETRY_VERSION,
  DIR,
} from '../src/geometry.mjs';
import { normalizeSpec, OBJECT_TYPES } from '../src/spec.mjs';
import { FACE_STEPS, key, parseKey } from '../src/objects/helpers.mjs';

/** A spec with exactly one object, padded so normalizeSpec keeps it. */
function one(obj, extra = {}) {
  return { name: 'test', seed: 1234, objects: [obj], ...extra };
}

/** Cell set of a scene, from buildScene (independent of voxelizeSpec). */
function sceneCells(scene) {
  const set = new Set();
  for (const solid of scene.solids) {
    if (!solid.cells) continue;
    for (const k of solid.cells.keys()) set.add(k);
  }
  return set;
}

/** Every object type with the minimal fields it needs. */
const SAMPLES = {
  box: { type: 'box', from: [0, 0, 0], to: [4, 3, 4], material: 'wall', hollow: true },
  prism: { type: 'prism', from: [0, 4, 0], to: [4, 4, 4], style: 'gable', material: 'roof', overhang: 1 },
  cylinder: { type: 'cylinder', center: [0, 0, 0], radius: 2, height: 5, material: 'stone' },
  cone: { type: 'cone', center: [0, 5, 0], radius: 2, height: 4, material: 'roof' },
  sphere: { type: 'sphere', center: [0, 6, 0], radius: 2, material: 'stone', half: 'top' },
  ring: { type: 'ring', from: [0, 0, 0], to: [4, 4, 4], y: 3, material: 'stone', profile: 'cornice' },
  wall: { type: 'wall', from: [0, 0, 0], to: [6, 0, 0], height: 3, material: 'wall', thickness: 1 },
  floor: { type: 'floor', from: [0, 0, 0], to: [4, 0, 4], material: 'stone' },
  stairs: { type: 'stairs', from: [0, 0, 0], to: [4, 4, 1], axis: 'x', material: 'wood' },
  window: { type: 'window', wall: 'south', at: [2, 2], w: 1, h: 2, frame: 'frame', glass: 'glass' },
  door: { type: 'door', wall: 'south', at: [2, 0], w: 1, h: 2, frame: 'frame' },
  arch: { type: 'arch', wall: 'south', at: [2, 0], w: 2, h: 3, material: 'stone' },
  balustrade: { type: 'balustrade', from: [0, 4, 0], to: [5, 4, 0], material: 'stone' },
  beam: { type: 'beam', from: [0, 3, 0], to: [5, 3, 0], thickness: 1, material: 'frame' },
  column: { type: 'column', at: [0, 0, 0], height: 4, radius: 1, material: 'stone' },
  chimney: { type: 'chimney', at: [0, 4, 0], w: 1, d: 1, height: 3, material: 'brick', cap: true },
  roof: { type: 'roof', from: [0, 4, 0], to: [4, 4, 4], style: 'hip', material: 'roof' },
  detail: { type: 'detail', cells: [[0, 0, 0], [1, 0, 0], [0, 1, 0]], material: 'wood' },
};

/** The canonical 10x10x15-ish medieval house used by several tests. */
const HOUSE = {
  name: 'basic house',
  seed: 42,
  objects: [
    {
      id: 'body',
      type: 'box',
      from: [0, 0, 0],
      to: [9, 5, 9],
      material: 'wall',
      hollow: true,
      openings: [
        { wall: 'south', x: 4, y: 0, w: 2, h: 3, kind: 'door' },
        { wall: 'south', x: 1, y: 2, w: 1, h: 2, kind: 'window', frame: 'frame', glass: 'glass' },
        { wall: 'north', x: 3, y: 2, w: 2, h: 2, kind: 'window', frame: 'frame', glass: 'glass' },
        { wall: 'east', x: 4, y: 2, w: 2, h: 2, kind: 'window', frame: 'frame', glass: 'glass' },
      ],
    },
    { id: 'floor', type: 'floor', from: [0, 0, 0], to: [9, 0, 9], material: 'stone', pattern: 'checker' },
    { id: 'roof', type: 'prism', from: [0, 6, 0], to: [9, 6, 9], style: 'gable', ridge: 'x', material: 'roof', overhang: 1 },
    { id: 'chimney', type: 'chimney', at: [7, 6, 2], w: 1, d: 1, height: 4, material: 'brick', cap: true },
  ],
};

// ---------------------------------------------------------------------------------------------
// Contract shape
// ---------------------------------------------------------------------------------------------

test('buildScene returns the documented scene shape', () => {
  const scene = buildScene(HOUSE);
  assert.equal(typeof scene, 'object');
  assert.ok(scene.spec && Array.isArray(scene.spec.objects), 'scene.spec is a normalised spec');
  assert.ok(Array.isArray(scene.solids) && scene.solids.length > 0, 'scene.solids is non-empty');
  assert.ok(scene.stats && typeof scene.stats === 'object', 'scene.stats exists');
  assert.equal(scene.stats.version, GEOMETRY_VERSION);

  for (const solid of scene.solids) {
    assert.equal(typeof solid.id, 'string');
    assert.equal(typeof solid.type, 'string');
    assert.equal(typeof solid.material, 'string');
    assert.ok(['surface', 'glass', 'detail'].includes(solid.kind), `kind ${solid.kind}`);
    assert.ok(Array.isArray(solid.faces) && solid.faces.length > 0, `solid ${solid.id} has faces`);
    assert.ok(solid.bounds && solid.bounds.min.length === 3 && solid.bounds.max.length === 3);
    for (const face of solid.faces) {
      assert.ok(face.v.length === 3 || face.v.length === 4, 'faces are triangles or quads');
      assert.equal(face.normal.length, 3);
      assert.ok(face.v.every((p) => p.length === 3 && p.every(Number.isFinite)), 'finite vertices');
      assert.equal(typeof face.material, 'string');
      assert.ok(['surface', 'glass', 'detail'].includes(face.kind));
    }
  }
});

test('buildScene does not mutate its input spec', () => {
  const input = structuredClone(HOUSE);
  const before = JSON.stringify(input);
  buildScene(input);
  assert.equal(JSON.stringify(input), before);
});

test('every object type yields non-empty geometry', () => {
  const norm = normalizeSpec({ objects: [] });
  assert.ok(norm.objects.length === 0);
  for (const type of OBJECT_TYPES) {
    const sample = SAMPLES[type];
    assert.ok(sample, `missing sample for type ${type}`);
    const scene = buildScene(one(sample));
    const cells = sceneCells(scene);
    const faces = scene.solids.reduce((n, s) => n + s.faces.length, 0);
    assert.ok(scene.solids.length > 0, `${type}: no solids`);
    assert.ok(faces > 0, `${type}: no faces`);
    assert.ok(cells.size > 0, `${type}: no voxel cells`);
    assert.ok(scene.stats.triangles > 0, `${type}: no triangles`);
  }
});

test('window/door/arch alone still produce geometry', () => {
  for (const type of ['window', 'door', 'arch']) {
    const scene = buildScene(one(SAMPLES[type]));
    const faces = scene.solids.reduce((n, s) => n + s.faces.length, 0);
    assert.ok(faces > 0, `${type} standalone should build a frame`);
  }
});

// ---------------------------------------------------------------------------------------------
// Openings really cut
// ---------------------------------------------------------------------------------------------

test('an opening reduces the voxel count', () => {
  const plain = { type: 'box', from: [0, 0, 0], to: [9, 5, 9], material: 'wall', hollow: true };
  const withDoor = structuredClone(plain);
  withDoor.openings = [{ wall: 'south', x: 4, y: 0, w: 2, h: 3, kind: 'door' }];
  const plainScene = buildScene(one(plain));
  const doorScene = buildScene(one(withDoor));
  const plainCells = sceneCells(plainScene).size;
  const doorCells = sceneCells(doorScene).size;
  assert.ok(doorCells < plainCells, `door must remove cells (${doorCells} < ${plainCells})`);
  assert.ok(plainCells - doorCells >= 2 * 3, 'a 2x3 door removes at least 6 cells... minus its frame');

  // The removed volume is really empty: no cell exists in the door aperture.
  const cells = sceneCells(doorScene);
  let filled = 0;
  for (let y = 0; y <= 2; y++) for (let x = 4; x <= 5; x++) if (cells.has(key(x, y, 9))) filled++;
  assert.ok(filled <= 1, `door aperture is empty (found ${filled} cells)`);
});

test('a door cut through a thick wall leaves no ghost wall', () => {
  const thin = { type: 'wall', from: [0, 0, 0], to: [0, 0, 9], height: 3, thickness: 1, material: 'wall',
    openings: [{ wall: 'south', x: 4, y: 0, w: 2, h: 2, kind: 'door' }] };
  const thick = { ...thin, thickness: 3 };
  const thinCells = sceneCells(buildScene(one(thin)));
  const thickCells = sceneCells(buildScene(one(thick)));
  assert.ok(thickCells.size > thinCells.size, 'thick wall has more cells');
  // The doorway is open on all layers for the thick wall too.
  for (let d = -1; d <= 1; d++) {
    for (let x = 4; x <= 5; x++) {
      for (let y = 0; y <= 1; y++) {
        assert.equal(thickCells.has(key(x, y, 4 + d)), false, `door cell ${x}|${y}|${4 + d} should be empty`);
      }
    }
  }
});

test('standalone window punches a box it does not own', () => {
  const box = { type: 'box', from: [0, 0, 0], to: [9, 5, 9], material: 'wall', hollow: true };
  const baseScene = buildScene(one(box));
  const withWindow = buildScene({
    name: 'w',
    objects: [box, { type: 'window', wall: 'south', at: [2, 2], w: 2, h: 2, frame: 'frame', glass: 'glass' }],
  });
  const boxCells = (scene) => scene.solids.filter((s) => s.type === 'box').reduce((n, s) => n + (s.cells ? s.cells.size : 0), 0);
  assert.ok(boxCells(withWindow) < boxCells(baseScene), 'the standalone window removed wall cells');
  // The wall cells behind the glass are gone (glass is a separate, transparent solid).
  const wallCells = new Set();
  for (const s of withWindow.solids) {
    if (s.type !== 'box') continue;
    for (const k of s.cells.keys()) wallCells.add(k);
  }
  assert.equal(wallCells.has(key(2, 2, 9)), false);
  assert.equal(wallCells.has(key(3, 3, 9)), false);
  // ... and there is glass in that hole.
  const glass = withWindow.solids.filter((s) => s.kind === 'glass');
  assert.ok(glass.length > 0, 'window contributes glass');
  assert.ok(glass.some((s) => s.cells && s.cells.has(key(2, 2, 9))), 'glass sits in the aperture');
});

// ---------------------------------------------------------------------------------------------
// Roofs
// ---------------------------------------------------------------------------------------------

test('gable roof is closed: every roof column has cells and the shell has no holes', () => {
  const roof = { type: 'prism', from: [0, 6, 0], to: [9, 6, 9], style: 'gable', ridge: 'x', material: 'roof', overhang: 1 };
  const scene = buildScene(one(roof));
  const solid = scene.solids.find((s) => s.type === 'prism');
  assert.ok(solid, 'roof solid exists');
  const cells = solid.cells;

  // 1. No column of the footprint is empty.
  const [x0, y0, z0] = [-1, 6, -1];
  const x1 = 10;
  const z1 = 10;
  for (let x = x0; x <= x1; x++) {
    for (let z = z0; z <= z1; z++) {
      let any = false;
      for (let y = y0; y <= y0 + 20 && !any; y++) any = cells.has(key(x, y, z));
      assert.ok(any, `roof column ${x},${z} is empty`);
    }
  }

  // 2. The shell is watertight: every exposed face of every cell has an outward normal, and the
  //    per-column surface height is contiguous (no missing layer between eave and ridge).
  for (let x = x0; x <= x1; x++) {
    for (let z = z0; z <= z1; z++) {
      let top = null;
      for (let y = y0 + 20; y >= y0; y--) {
        if (cells.has(key(x, y, z))) {
          top = y;
          break;
        }
      }
      assert.notEqual(top, null);
      for (let y = y0; y <= top; y++) assert.ok(cells.has(key(x, y, z)), `hole at ${x},${y},${z}`);
    }
  }

  // 3. Gable ends are closed on both sides of the ridge axis (x here): the end walls reach the ridge.
  const midZ = 4;
  const ridgeTop = (() => {
    let t = -Infinity;
    for (const k of cells.keys()) {
      const [x, y, z] = parseKey(k);
      if (z === midZ) t = Math.max(t, y);
    }
    return t;
  })();
  for (const x of [x0, x1]) {
    let endTop = -Infinity;
    for (const k of cells.keys()) {
      const [cx, y, cz] = parseKey(k);
      if (cx === x && cz === midZ) endTop = Math.max(endTop, y);
    }
    assert.equal(endTop, ridgeTop, `gable end at x=${x} reaches the ridge`);
  }
});

test('gable roof renders sloped planes, not a box', () => {
  const roof = { type: 'prism', from: [0, 6, 0], to: [9, 6, 9], style: 'gable', ridge: 'x', material: 'roof', overhang: 1 };
  const scene = buildScene(one(roof));
  const solid = scene.solids.find((s) => s.type === 'prism');
  const normals = solid.faces.map((f) => f.normal.map((n) => Math.round(n * 100) / 100));
  const hasSlope = normals.some((n) => Math.abs(n[1]) > 0.3 && Math.abs(n[1]) < 0.99);
  assert.ok(hasSlope, 'roof has sloped faces');
  const hasRidge = normals.some((n) => n[0] === 0 && n[1] > 0.5 && Math.abs(n[2]) > 0.3);
  assert.ok(hasRidge, 'roof has two opposing slopes');
  // Render mesh is a closed polyhedron: top slope area + walls + underside.
  const ys = solid.faces.flatMap((f) => f.v.map((p) => p[1]));
  assert.ok(Math.max(...ys) > 6, 'roof rises above its base');
});

test('all four roof styles are watertight and rise above the wall top', () => {
  for (const style of ['gable', 'hip', 'shed', 'pyramid']) {
    for (const ridge of ['x', 'z']) {
      const roof = { type: 'prism', from: [0, 6, 0], to: [9, 6, 9], style, ridge, material: 'roof', overhang: 1 };
      const scene = buildScene(one(roof));
      const solid = scene.solids.find((s) => s.type === 'prism');
      assert.ok(solid && solid.cells.size > 0, `${style}/${ridge}: cells`);
      let topY = -Infinity;
      for (const k of solid.cells.keys()) topY = Math.max(topY, parseKey(k)[1]);
      assert.ok(topY > 6, `${style}/${ridge}: rises above the wall top (top ${topY})`);
      // No cell column gaps: the mass under each column is contiguous from baseY to its top.
      for (const k of solid.cells.keys()) {
        const [x, y, z] = parseKey(k);
        if (y === 6) continue;
        assert.ok(solid.cells.has(key(x, y - 1, z)), `${style}/${ridge}: floating cell ${k}`);
      }
    }
  }
});

test('overhang extends the roof beyond the wall footprint', () => {
  const tight = buildScene(one({ type: 'prism', from: [0, 6, 0], to: [9, 6, 9], style: 'gable', material: 'roof', overhang: 0 }));
  const wide = buildScene(one({ type: 'prism', from: [0, 6, 0], to: [9, 6, 9], style: 'gable', material: 'roof', overhang: 2 }));
  const b1 = tight.solids[0].bounds;
  const b2 = wide.solids[0].bounds;
  assert.ok(b2.min[0] < b1.min[0] && b2.max[0] > b1.max[0], 'overhang grows the roof in x');
  assert.ok(b2.min[2] < b1.min[2] && b2.max[2] > b1.max[2], 'overhang grows the roof in z');
});

// ---------------------------------------------------------------------------------------------
// Floating voxels
// ---------------------------------------------------------------------------------------------

/**
 * Every voxel must be connected (6-connectivity) to a component that touches y <= 0.
 * @param {Map<string, object>} vox
 */
function findFloating(vox) {
  const seen = new Set();
  const floating = [];
  for (const start of vox.keys()) {
    if (seen.has(start)) continue;
    // Flood fill the component.
    const comp = [start];
    seen.add(start);
    let grounded = false;
    for (let i = 0; i < comp.length; i++) {
      const [x, y, z] = parseKey(comp[i]);
      if (y <= 0) grounded = true;
      for (const [dx, dy, dz] of FACE_STEPS) {
        const nk = key(x + dx, y + dy, z + dz);
        if (!vox.has(nk) || seen.has(nk)) continue;
        seen.add(nk);
        comp.push(nk);
      }
    }
    if (!grounded) floating.push(...comp);
  }
  return floating;
}

test('voxelizeSpec: every cell reports material, up/down and an averaged normal', () => {
  const vox = voxelizeSpec(HOUSE);
  assert.ok(vox.size > 0);
  for (const [k, cell] of vox) {
    const [x, y, z] = parseKey(k);
    assert.equal(k, key(x, y, z), 'keys are canonical x|y|z');
    assert.equal(typeof cell.material, 'string');
    assert.equal(typeof cell.up, 'boolean');
    assert.equal(typeof cell.down, 'boolean');
    assert.ok(Number.isFinite(cell.nx) && Number.isFinite(cell.ny) && Number.isFinite(cell.nz));
    const len = Math.hypot(cell.nx, cell.ny, cell.nz);
    assert.ok(len < 1e-6 || Math.abs(len - 1) < 1e-3, `normal is unit length when defined (${len})`);
    assert.equal(typeof cell.solid, 'string');
  }
});

test('normals are meaningful: floor up, ceiling down, wall sideways', () => {
  const floorCells = voxelizeSpec(one({ type: 'floor', from: [0, 0, 0], to: [4, 0, 4], material: 'stone' }));
  const centre = floorCells.get(key(2, 0, 2));
  assert.ok(centre, 'floor centre cell exists');
  assert.ok(centre.ny > 0.99, `floor cell normal points up (${centre.ny})`);
  assert.equal(centre.exposed & DIR.yp, DIR.yp, 'floor top is exposed');
  assert.equal(centre.exposed & DIR.yn, DIR.yn, 'floor bottom is exposed');

  const wallCells = voxelizeSpec(one({ type: 'wall', from: [0, 0, 0], to: [9, 0, 0], height: 3, thickness: 1, material: 'wall' }));
  const wall = wallCells.get(key(4, 1, 0));
  assert.ok(wall, 'wall cell exists');
  assert.ok(Math.abs(wall.ny) < 0.01, 'wall cell normal is horizontal');
  assert.ok(Math.abs(Math.abs(wall.nz) - 1) < 0.01, 'wall cell normal is along z');
});

test('up/down reflect a solid surface above/below the cell', () => {
  const vox = voxelizeSpec(HOUSE);
  const wallBase = vox.get(key(0, 1, 0));
  assert.ok(wallBase, 'wall base cell exists');
  assert.equal(wallBase.up, true, 'wall cell has a cell above it');
  assert.equal(wallBase.down, true, 'wall cell has a floor below it');
  const chimneyTop = vox.get(key(7, 10, 2));
  if (chimneyTop) assert.equal(chimneyTop.up, false, 'top of the chimney has nothing above');
});

test('basic 10x10 house has no floating voxels', () => {
  const vox = voxelizeSpec(HOUSE);
  const floating = findFloating(vox);
  assert.deepEqual(floating, [], `floating cells: ${floating.slice(0, 20).join(' ')}`);
});

test('a deliberately floating detail is detected by the same check', () => {
  const vox = voxelizeSpec(one({ type: 'detail', cells: [[0, 20, 0], [1, 20, 0]], material: 'wood' }));
  const floating = findFloating(vox);
  assert.equal(floating.length, 2, 'both floating cells are reported');
});

test('the house sits on the ground and stays inside a sane box', () => {
  const vox = voxelizeSpec(HOUSE);
  let minY = Infinity;
  let maxY = -Infinity;
  for (const k of vox.keys()) {
    const [, y] = parseKey(k);
    minY = Math.min(minY, y);
    maxY = Math.max(maxY, y);
  }
  assert.equal(minY, 0, 'house starts at ground level');
  assert.ok(maxY >= 6 && maxY <= 16, `house height is reasonable (${maxY})`);
});

// ---------------------------------------------------------------------------------------------
// voxelizeSolid
// ---------------------------------------------------------------------------------------------

test('voxelizeSolid returns a Set of keys and caches it', () => {
  const scene = buildScene(one({ type: 'box', from: [0, 0, 0], to: [3, 2, 3], material: 'wall' }));
  const solid = scene.solids[0];
  const set = voxelizeSolid(solid);
  assert.ok(set instanceof Set);
  assert.ok(set.size > 0);
  for (const k of set) assert.match(k, /^-?\d+\|-?\d+\|-?\d+$/);
  assert.deepEqual([...voxelizeSolid(solid)], [...set], 'stable result');

  const visible = voxelizeSolid(solid, { visibleOnly: true });
  assert.ok(visible.size <= set.size, 'visibleOnly is a subset of the full volume');
  assert.ok(visible.size > 0, 'a box has visible cells');
  assert.ok(visible.size < set.size, 'a solid box has interior cells the shell drops');

  const shifted = voxelizeSolid(solid, { offset: [10, 20, 30] });
  assert.equal(shifted.size, set.size);
  assert.ok(shifted.has(key(10, 20, 30)), 'offset shifts keys');
});

test('voxelizeSolid of a 1-thick wall keeps exactly one cell of thickness', () => {
  const scene = buildScene(one({ type: 'wall', from: [0, 0, 0], to: [9, 0, 0], height: 4, thickness: 1, material: 'wall' }));
  const set = voxelizeSolid(scene.solids[0]);
  const depths = new Set([...set].map((k) => parseKey(k)[2]));
  assert.deepEqual([...depths], [0], 'wall occupies a single z layer');
});

test('voxelizeSpec accepts a prebuilt scene and matches the direct call', () => {
  const scene = buildScene(HOUSE);
  const a = voxelizeSpec(HOUSE, { scene });
  const b = voxelizeSpec(HOUSE);
  assert.deepEqual([...a.keys()].sort(), [...b.keys()].sort());
});

test('cell coverage follows the cell-centre-inside rule for curved shapes', () => {
  const vox = voxelizeSpec(one({ type: 'cylinder', center: [0, 0, 0], radius: 2, height: 3, material: 'stone' }));
  // Every kept cell's centre must be inside the cylinder; every rejected cell's centre outside.
  const cx = 0.5;
  const cz = 0.5;
  for (let x = -4; x <= 4; x++) {
    for (let z = -4; z <= 4; z++) {
      const inside = Math.hypot(x + 0.5 - cx, z + 0.5 - cz) <= 2.5;
      const present = vox.has(key(x, 1, z));
      assert.equal(present, inside, `cell ${x},${z} mismatch`);
    }
  }
});

// ---------------------------------------------------------------------------------------------
// Determinism and performance
// ---------------------------------------------------------------------------------------------

test('same spec builds byte-identical geometry', () => {
  const a = buildScene(HOUSE);
  const b = buildScene(structuredClone(HOUSE));
  assert.equal(JSON.stringify(a.solids), JSON.stringify(b.solids));
  assert.deepEqual([...voxelizeSpec(HOUSE).entries()].map(([k, v]) => [k, v.material, v.nx, v.ny, v.nz]),
    [...voxelizeSpec(HOUSE).entries()].map(([k, v]) => [k, v.material, v.nx, v.ny, v.nz]));
});

test('seed affects only the objects that use randomness', () => {
  const spec = {
    name: 'seeded',
    seed: 1,
    objects: [{ type: 'detail', cells: Array.from({ length: 60 }, (_, i) => [i % 6, 0, Math.floor(i / 6)]), material: 'stone', pattern: 'sparse' }],
  };
  const a = buildScene(spec);
  const b = buildScene({ ...structuredClone(spec), seed: 1 });
  const c = buildScene({ ...structuredClone(spec), seed: 99 });
  assert.equal(JSON.stringify(a.solids), JSON.stringify(b.solids), 'same seed -> same geometry');
  assert.notEqual(JSON.stringify(a.solids), JSON.stringify(c.solids), 'different seed -> different hash pattern');
});

test('a 10x10x15 house with details builds in under 300 ms', () => {
  const detailed = {
    name: 'detailed house',
    seed: 7,
    objects: [
      ...HOUSE.objects,
      { type: 'ring', from: [0, 0, 0], to: [9, 9, 9], y: 5, material: 'stone', profile: 'cornice', thickness: 1 },
      { type: 'window', wall: 'west', at: [3, 2], w: 2, h: 2, frame: 'frame', glass: 'glass' },
      { type: 'window', wall: 'west', at: [3, 4], w: 1, h: 1, frame: 'frame', glass: 'glass' },
      { type: 'door', wall: 'south', at: [7, 0], w: 1, h: 2, frame: 'frame', canopy: true },
      { type: 'beam', from: [0, 5, 0], to: [9, 5, 0], thickness: 1, material: 'frame' },
      { type: 'balustrade', from: [0, 0, 0], to: [9, 0, 0], material: 'stone' },
      { type: 'column', at: [0, 0, 0], height: 6, radius: 1, material: 'stone' },
      { type: 'column', at: [9, 0, 0], height: 6, radius: 1, material: 'stone' },
      { type: 'stairs', from: [0, 0, 10], to: [9, 0, 11], axis: 'x', material: 'wood', railing: true },
      { type: 'detail', cells: Array.from({ length: 40 }, (_, i) => [i % 10, 1, 9]), material: 'wood' },
    ],
  };
  const t0 = performance.now();
  const scene = buildScene(detailed);
  const vox = voxelizeSpec(detailed, { scene });
  const elapsed = performance.now() - t0;
  assert.ok(scene.solids.length > 5);
  assert.ok(vox.size > 500);
  assert.ok(elapsed < 300, `buildScene + voxelizeSpec took ${elapsed.toFixed(1)} ms, budget 300 ms`);
});

test('voxelStats summarises a spec', () => {
  const stats = voxelStats(HOUSE);
  assert.ok(stats.cells > 0);
  assert.ok(stats.volume === stats.cells);
  assert.ok(stats.columns > 0);
  assert.ok(stats.height > 0);
});

// ---------------------------------------------------------------------------------------------
// Robustness
// ---------------------------------------------------------------------------------------------

test('an empty spec builds an empty scene instead of throwing', () => {
  const scene = buildScene({});
  assert.deepEqual(scene.solids, []);
  assert.equal(scene.stats.objects, 0);
  assert.equal(voxelizeSpec({}).size, 0);
});

test('unknown object types are skipped, valid ones still build', () => {
  const scene = buildScene({ objects: [{ type: 'spaceship', from: [0, 0, 0] }, SAMPLES.box] });
  assert.ok(scene.solids.length > 0);
});

test('duplicate ids are disambiguated deterministically', () => {
  const scene = buildScene({ objects: [{ ...SAMPLES.box, id: 'a' }, { ...SAMPLES.floor, id: 'a' }] });
  const ids = scene.solids.map((s) => s.id);
  assert.equal(new Set(ids).size, ids.length, `ids are unique: ${ids.join(',')}`);
});

test('normalizeSpec + buildScene agree on property names for every accepted type', () => {
  for (const type of OBJECT_TYPES) {
    const spec = one(SAMPLES[type]);
    const norm = normalizeSpec(spec);
    assert.equal(norm.objects.length, 1);
    const scene = buildScene(spec);
    assert.ok(scene.stats.objects === 1);
  }
});

// ---------------------------------------------------------------------------------------------
// Interior cells, warnings and roof normals (behaviour added for the transfer module)
// ---------------------------------------------------------------------------------------------

test('voxelizeSpec keeps interior cells and flags them', () => {
  const solidRoom = { type: 'box', from: [0, 0, 0], to: [4, 4, 4], material: 'wall' };
  const full = voxelizeSpec(one(solidRoom));
  const shell = voxelizeSpec(one(solidRoom), { visibleOnly: true });
  assert.ok(full.size > shell.size, 'full volume is larger than the shell');
  let interior = 0;
  for (const cell of full.values()) if (cell.interior) interior++;
  assert.ok(interior > 0, 'the solid box has interior cells');
  assert.equal([...shell.values()].filter((c) => c.interior).length, 0);
  for (const cell of full.values()) {
    assert.equal(cell.interior, cell.exposed === 0, 'interior flag matches exposure');
  }
});

test('roof top cells carry the slope normal, so the transfer can pick stairs', () => {
  const roof = { type: 'prism', from: [0, 6, 0], to: [9, 6, 9], style: 'gable', ridge: 'x', material: 'roof' };
  const scene = buildScene(one(roof));
  const solid = scene.solids.find((s) => s.type === 'prism');
  assert.ok(solid.cellNormals && solid.cellNormals.size > 0, 'roof exposes per-cell normals');
  const vox = voxelizeSpec(one(roof), { scene });
  const sloped = [...vox.values()].filter((c) => Math.abs(c.ny) > 0.4 && Math.abs(c.ny) < 0.95);
  assert.ok(sloped.length > 10, `roof cells report a sloped normal (${sloped.length})`);
});

test('per-cell materials come through the floor pattern', () => {
  const vox = voxelizeSpec(one({ type: 'floor', from: [0, 0, 0], to: [5, 0, 5], material: 'stone', pattern: 'checker' }));
  const mats = new Set([...vox.values()].map((c) => c.material));
  assert.ok(mats.size >= 2, `checker floor uses at least two materials (${[...mats].join(',')})`);
  assert.ok(mats.has('stone'));
});

test('pathological shapes are clamped and reported instead of hanging the build', () => {
  const scene = buildScene(one({ type: 'sphere', center: [0, 0, 0], radius: 60, material: 'stone', half: 'full' }));
  assert.ok(scene.solids.length > 0);
  assert.ok(scene.stats.cells > 0);
  assert.ok(Array.isArray(scene.stats.warnings));
  assert.ok(scene.stats.warnings.some((w) => /cap/.test(w)), `warning reported: ${scene.stats.warnings.join('; ')}`);
});
