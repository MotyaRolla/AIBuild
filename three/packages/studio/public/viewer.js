/**
 * StructureSpec -> THREE.Group, in the browser.
 *
 * The studio prefers the real geometry engine: `buildScene()` from
 * `packages/core/src/geometry.mjs`, served by the studio at `/core/geometry.mjs`. It is the same
 * cell engine the offline PNG renderer and the Minecraft voxelizer use, so the live preview, the
 * critic's renders and the transferred blocks all describe the same building.
 *
 * When `/core` is unavailable (the studio pointed at a root without a core package, or a core
 * module that fails to load), `buildFallbackGroup()` draws the primitive volumes of each object
 * instead: boxes, roofs, cylinders, cones, spheres and detail cells. It is visibly coarser — it has
 * no openings — but it keeps the viewer useful on partial checkouts.
 *
 * Both paths colour by palette key, accept raw `#rrggbb` materials, and render glass transparent.
 */
import * as THREE from 'three';

/** Mirrors core's DEFAULT_PALETTE. Duplicated (not imported) so the viewer boots without /core. */
const FALLBACK_PALETTE = {
  stone: '#8a8a8a',
  stone_dark: '#6b6b6b',
  wall: '#d9cfba',
  frame: '#3a2a1c',
  wood: '#8a6242',
  roof: '#6b452f',
  roof_accent: '#4d3222',
  glass: '#a9c9d9',
  metal: '#4a4a52',
  leaf: '#4e7a34',
  plain: '#c8c8c8',
};

/**
 * @typedef {object} PreviewResult
 * @property {THREE.Group} group
 * @property {THREE.Box3} box
 * @property {{source: 'core'|'fallback', objects: number, triangles: number, materials: number}} stats
 */

/**
 * Builds the preview for a spec, preferring the shared core geometry engine.
 *
 * @param {any} spec StructureSpec (normalized or raw)
 * @param {{wireframe?: boolean}} [options]
 * @returns {Promise<PreviewResult>}
 */
export async function buildPreviewGroup(spec, options = {}) {
  const core = await loadCore();
  if (core) {
    try {
      const scene = core.buildScene(spec);
      const built = groupFromCoreScene(scene, options);
      if (built.stats.triangles > 0) return built;
    } catch (error) {
      console.warn('[viewer] core geometry failed, using primitive preview:', error);
    }
  }
  return buildFallbackGroup(spec, options);
}

/**
 * Lazily imports the core geometry module; resolves `null` when it cannot be loaded.
 * @returns {Promise<{buildScene: Function}|null>}
 */
let corePromise = null;
function loadCore() {
  if (!corePromise) {
    corePromise = import('/core/geometry.mjs')
      .then((mod) => (typeof mod.buildScene === 'function' ? mod : null))
      .catch(() => null);
  }
  return corePromise;
}

/* ───────────────────────── core geometry path ───────────────────────── */

/**
 * Converts a core `Scene{spec, solids, stats}` into one mesh per material. Each face is a polygon
 * (triangle or quad); it is fanned into triangles and the face normal is reused for every vertex,
 * which is what gives the model its crisp faceted look instead of a smoothed blob.
 *
 * @param {any} scene
 * @param {{wireframe?: boolean}} options
 * @returns {PreviewResult}
 */
function groupFromCoreScene(scene, options = {}) {
  const spec = scene.spec || {};
  const palette = { ...FALLBACK_PALETTE, ...(spec.palette || {}) };
  /** @type {Map<string, {positions: number[], normals: number[], glass: boolean}>} */
  const buckets = new Map();
  let triangles = 0;

  for (const solid of scene.solids || []) {
    for (const face of solid.faces || []) {
      const points = face?.v;
      if (!Array.isArray(points) || points.length < 3) continue;
      const material = face.material || solid.material || 'plain';
      const glass = face.kind === 'glass' || String(material).startsWith('glass');
      const key = `${material}|${glass ? 1 : 0}`;
      let bucket = buckets.get(key);
      if (!bucket) {
        bucket = { positions: [], normals: [], glass };
        buckets.set(key, bucket);
      }
      const normal = Array.isArray(face.normal) ? face.normal : polygonNormal(points);
      for (let i = 1; i < points.length - 1; i++) {
        pushVertex(bucket, points[0], normal);
        pushVertex(bucket, points[i], normal);
        pushVertex(bucket, points[i + 1], normal);
        triangles++;
      }
    }
  }

  const group = new THREE.Group();
  group.name = `spec:${spec.name || 'structure'}`;
  for (const [key, bucket] of buckets) {
    if (!bucket.positions.length) continue;
    const materialKey = key.slice(0, key.indexOf('|'));
    const geometry = new THREE.BufferGeometry();
    geometry.setAttribute('position', new THREE.Float32BufferAttribute(bucket.positions, 3));
    geometry.setAttribute('normal', new THREE.Float32BufferAttribute(bucket.normals, 3));
    geometry.computeBoundingSphere();
    const mesh = new THREE.Mesh(geometry, materialFor(materialKey, palette, bucket.glass, options));
    mesh.name = materialKey;
    mesh.castShadow = true;
    mesh.receiveShadow = true;
    group.add(mesh);
  }

  return {
    group,
    box: new THREE.Box3().setFromObject(group),
    stats: {
      source: 'core',
      objects: Array.isArray(spec.objects) ? spec.objects.length : 0,
      triangles,
      materials: buckets.size,
    },
  };
}

/** @param {{positions: number[], normals: number[]}} bucket @param {number[]} p @param {number[]} n */
function pushVertex(bucket, p, n) {
  bucket.positions.push(p[0], p[1], p[2]);
  bucket.normals.push(n[0] || 0, n[1] || 0, n[2] || 0);
}

/**
 * Newell's method — robust for the non-planar quads a voxel shell can produce.
 * @param {number[][]} points
 * @returns {number[]}
 */
export function polygonNormal(points) {
  let nx = 0;
  let ny = 0;
  let nz = 0;
  for (let i = 0; i < points.length; i++) {
    const a = points[i];
    const b = points[(i + 1) % points.length];
    nx += (a[1] - b[1]) * (a[2] + b[2]);
    ny += (a[2] - b[2]) * (a[0] + b[0]);
    nz += (a[0] - b[0]) * (a[1] + b[1]);
  }
  const length = Math.hypot(nx, ny, nz) || 1;
  return [nx / length, ny / length, nz / length];
}

/* ───────────────────────── fallback path ───────────────────────── */

/**
 * Draws each spec object as its primitive volume. No openings, no merging — a diagnostic preview.
 * @param {any} spec
 * @param {{wireframe?: boolean}} options
 * @returns {PreviewResult}
 */
export function buildFallbackGroup(spec, options = {}) {
  const palette = { ...FALLBACK_PALETTE, ...(spec?.palette || {}) };
  const group = new THREE.Group();
  group.name = `spec:${spec?.name || 'structure'}`;
  const materials = new Set();
  let triangles = 0;

  /** @param {THREE.BufferGeometry} geometry @param {string} key @param {(m: THREE.Mesh) => void} [place] */
  const add = (geometry, key, place) => {
    const glass = String(key).startsWith('glass');
    const mesh = new THREE.Mesh(geometry, materialFor(key, palette, glass, options));
    mesh.name = key;
    mesh.castShadow = true;
    mesh.receiveShadow = true;
    if (place) place(mesh);
    group.add(mesh);
    materials.add(key);
    const index = geometry.getIndex();
    triangles += index ? index.count / 3 : (geometry.getAttribute('position')?.count || 0) / 3;
  };

  const unitBox = new THREE.BoxGeometry(1, 1, 1);
  const placeBox = (lo, hi) => (mesh) => {
    mesh.scale.set(hi[0] - lo[0] + 1, hi[1] - lo[1] + 1, hi[2] - lo[2] + 1);
    mesh.position.set(lo[0] + (hi[0] - lo[0] + 1) / 2, lo[1] + (hi[1] - lo[1] + 1) / 2, lo[2] + (hi[2] - lo[2] + 1) / 2);
  };

  for (const obj of spec?.objects || []) {
    const key = Array.isArray(obj?.material) ? obj.material[0] : obj?.material || 'plain';
    const lo = obj?.from ? [Math.min(obj.from[0], obj.to?.[0] ?? obj.from[0]), Math.min(obj.from[1], obj.to?.[1] ?? obj.from[1]), Math.min(obj.from[2], obj.to?.[2] ?? obj.from[2])] : null;
    const hi = obj?.to ? [Math.max(obj.from[0], obj.to[0]), Math.max(obj.from[1], obj.to[1]), Math.max(obj.from[2], obj.to[2])] : null;
    try {
      switch (obj?.type) {
        case 'prism':
        case 'roof':
          if (lo && hi) add(roofGeometry(lo, hi, obj), key === 'plain' ? 'roof' : key);
          break;
        case 'cone':
        case 'sphere':
        case 'cylinder':
          add(curvedGeometry(obj), key);
          break;
        case 'detail':
          for (const cell of obj.cells || []) {
            if (!Array.isArray(cell) || cell.length < 3) continue;
            add(unitBox, key, (mesh) => mesh.position.set(cell[0] + 0.5, cell[1] + 0.5, cell[2] + 0.5));
          }
          break;
        case 'window':
        case 'door':
        case 'arch':
        case 'ring':
        case 'balustrade':
        case 'beam':
          // These are openings and trim: they only exist relative to a wall, draw nothing.
          break;
        default:
          if (lo && hi) add(unitBox, key, placeBox(lo, hi));
      }
    } catch (error) {
      console.warn('[viewer] skipped object', obj?.id || obj?.type, error);
    }
  }

  return {
    group,
    box: new THREE.Box3().setFromObject(group),
    stats: {
      source: 'fallback',
      objects: Array.isArray(spec?.objects) ? spec.objects.length : 0,
      triangles: Math.round(triangles),
      materials: materials.size,
    },
  };
}

/**
 * Cylinder/cone/sphere primitive for the fallback path.
 * @param {any} obj
 * @returns {THREE.BufferGeometry}
 */
function curvedGeometry(obj) {
  const center = Array.isArray(obj.center) ? obj.center : [0, 0, 0];
  const radius = Number(obj.radius) || 2;
  const height = Number(obj.height) || 4;
  if (obj.type === 'cone') {
    const geometry = new THREE.ConeGeometry(radius, height, 24, 1, Boolean(obj.hollow));
    geometry.translate(center[0], center[1] + height / 2, center[2]);
    return geometry;
  }
  if (obj.type === 'sphere') {
    const half = obj.half === 'top' || obj.half === 'bottom';
    const geometry = new THREE.SphereGeometry(radius, 24, 16, 0, Math.PI * 2, 0, half ? Math.PI / 2 : Math.PI);
    geometry.translate(center[0], center[1] + (obj.half === 'bottom' ? radius : 0), center[2]);
    return geometry;
  }
  const geometry = new THREE.CylinderGeometry(radius, radius, height, 24, 1, Boolean(obj.hollow));
  geometry.translate(center[0], center[1] + height / 2, center[2]);
  return geometry;
}

/**
 * A gable / hip / shed / pyramid roof over an axis-aligned footprint.
 * @param {number[]} lo
 * @param {number[]} hi
 * @param {any} obj
 * @returns {THREE.BufferGeometry}
 */
export function roofGeometry(lo, hi, obj) {
  const x0 = lo[0];
  const x1 = hi[0] + 1;
  const z0 = lo[2];
  const z1 = hi[2] + 1;
  const y0 = lo[1];
  const style = obj.style || 'gable';
  const ridge = obj.ridge || (x1 - x0 >= z1 - z0 ? 'x' : 'z');
  const pitch = Number(obj.pitch) || 1;
  const span = ridge === 'x' ? (z1 - z0) / 2 : (x1 - x0) / 2;
  const rise = Math.max(1, span * pitch);
  const y1 = y0 + rise;
  const mx = (x0 + x1) / 2;
  const mz = (z0 + z1) / 2;

  /** @type {number[]} */
  const positions = [];
  const tri = (a, b, c) => positions.push(...a, ...b, ...c);

  if (style === 'shed') {
    tri([x0, y0, z1], [x1, y0, z1], [x1, y1, z1]);
    tri([x0, y0, z1], [x1, y1, z1], [x0, y1, z1]);
    tri([x0, y0, z0], [x1, y0, z0], [x1, y1, z1]);
    tri([x0, y0, z0], [x1, y1, z1], [x0, y1, z1]);
    tri([x0, y0, z0], [x0, y0, z1], [x0, y1, z1]);
    tri([x1, y0, z0], [x1, y1, z1], [x1, y0, z1]);
  } else if (style === 'pyramid') {
    const apex = [mx, y1, mz];
    tri([x0, y0, z1], [x1, y0, z1], apex);
    tri([x0, y0, z0], [x0, y0, z1], apex);
    tri([x1, y0, z0], [x0, y0, z0], apex);
    tri([x1, y0, z1], [x1, y0, z0], apex);
  } else if (ridge === 'x') {
    const a = [x0, y1, mz];
    const b = [x1, y1, mz];
    tri([x0, y0, z1], [x1, y0, z1], b);
    tri([x0, y0, z1], b, a);
    tri([x1, y0, z0], [x0, y0, z0], a);
    tri([x1, y0, z0], a, b);
    tri([x0, y0, z0], [x0, y0, z1], a);
    tri([x1, y0, z1], [x1, y0, z0], b);
  } else {
    const a = [mx, y1, z0];
    const b = [mx, y1, z1];
    tri([x0, y0, z0], [x1, y0, z0], a);
    tri([x1, y0, z1], [x0, y0, z1], b);
    tri([x0, y0, z0], a, b);
    tri([x0, y0, z0], b, [x0, y0, z1]);
    tri([x1, y0, z0], [x1, y0, z1], b);
    tri([x1, y0, z0], b, a);
  }

  const geometry = new THREE.BufferGeometry();
  geometry.setAttribute('position', new THREE.Float32BufferAttribute(positions, 3));
  geometry.computeVertexNormals();
  return geometry;
}

/* ───────────────────────── materials, edges, helpers ───────────────────────── */

/**
 * A MeshStandardMaterial for a palette key or raw hex colour. Glass is transparent and slightly
 * emissive so it reads as glazing rather than a hole in the wall.
 *
 * @param {string} key palette key or `#rrggbb`
 * @param {Record<string, string>} palette
 * @param {boolean} glass
 * @param {{wireframe?: boolean}} [options]
 * @returns {THREE.MeshStandardMaterial}
 */
export function materialFor(key, palette, glass, options = {}) {
  const isHex = /^#([0-9a-f]{3}|[0-9a-f]{6})$/i.test(String(key));
  const hex = isHex ? key : palette[key] || palette.plain || '#c8c8c8';
  const material = new THREE.MeshStandardMaterial({
    color: new THREE.Color(hex),
    roughness: glass ? 0.18 : 0.85,
    metalness: glass ? 0.05 : 0.02,
    side: glass ? THREE.DoubleSide : THREE.FrontSide,
    wireframe: Boolean(options.wireframe),
  });
  if (glass) {
    material.transparent = true;
    material.opacity = 0.44;
    material.depthWrite = false;
    material.emissive = new THREE.Color(hex).multiplyScalar(0.1);
  }
  material.userData.paletteKey = key;
  return material;
}

/**
 * Adds a thin dark edge overlay so blocks read as separate cubes. Built once per mesh and stored
 * under `userData.edgeOverlay` so toggling edges never rebuilds geometry.
 * @param {THREE.Group} group
 * @param {{thresholdAngle?: number, opacity?: number}} [options]
 * @returns {THREE.LineSegments[]}
 */
export function addEdgeOverlay(group, options = {}) {
  const material = new THREE.LineBasicMaterial({ color: 0x0b0f13, transparent: true, opacity: options.opacity ?? 0.3 });
  const out = [];
  group.traverse((child) => {
    if (!child.isMesh || child.userData.edgeOverlay) return;
    const edges = new THREE.EdgesGeometry(child.geometry, options.thresholdAngle ?? 25);
    const lines = new THREE.LineSegments(edges, material);
    lines.name = `edges:${child.name || 'mesh'}`;
    lines.userData.edgeOverlay = true;
    lines.visible = false;
    child.add(lines);
    out.push(lines);
  });
  return out;
}

/**
 * Numeric bounds of a THREE.Box3 plus the scene centre, for camera framing and the status bar.
 * @param {THREE.Box3} box
 * @returns {{min: number[], max: number[], size: number[], center: number[]}}
 */
export function boundsOfBox(box) {
  const isEmpty = !box || box.isEmpty();
  const min = isEmpty ? [0, 0, 0] : [box.min.x, box.min.y, box.min.z];
  const max = isEmpty ? [1, 1, 1] : [box.max.x, box.max.y, box.max.z];
  return {
    min,
    max,
    size: max.map((v, i) => v - min[i]),
    center: [(min[0] + max[0]) / 2, (min[1] + max[1]) / 2, (min[2] + max[2]) / 2],
  };
}

/**
 * Disposes a group's geometries and materials (the viewer swaps specs on every selection).
 * @param {THREE.Object3D} root
 */
export function disposeGroup(root) {
  root.traverse((child) => {
    if (child.geometry) child.geometry.dispose();
    if (child.material) {
      const materials = Array.isArray(child.material) ? child.material : [child.material];
      for (const material of materials) material.dispose();
    }
  });
}
