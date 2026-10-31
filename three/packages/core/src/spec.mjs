/**
 * StructureSpec — the declarative scene description that everything else works on.
 *
 * A spec is plain JSON (see IDEA.md). Objects are primitives in a right-handed space where
 *   +x = east, +y = up, +z = south   (same convention as the Minecraft plugin)
 * and one scene unit == one Minecraft block. Model space starts at y=0 = ground surface.
 *
 * This module is pure data + validation. It does not import three.js, so it can run anywhere
 * (node, browser, tests) and is cheap to serialize into prompts.
 */

/** Known object types. Each type documents its own fields in TYPES below. */
export const OBJECT_TYPES = /** @type {const} */ ([
  'box', 'prism', 'cylinder', 'cone', 'sphere', 'ring', 'wall', 'floor', 'stairs',
  'window', 'door', 'arch', 'balustrade', 'beam', 'column', 'chimney', 'roof', 'detail',
]);

/** Human/LLM-facing description of every object type: required and optional fields. */
export const TYPES = {
  box: {
    doc: 'Axis-aligned box. The workhorse for walls, floors, plinths.',
    fields: {
      from: 'int[3] minimal corner (inclusive)',
      to: 'int[3] maximal corner (inclusive)',
      material: 'palette key',
      hollow: 'bool (default false) — keep only the shell',
      open: 'string[] (default []) — faces to omit: north|south|east|west|up|down',
      openings: 'Opening[] — door/window holes cut into this box',
    },
  },
  prism: {
    doc: 'Roof-like prism over a footprint. style=gable (two slopes + gable ends) | shed (one slope) | hip | pyramid.',
    fields: {
      from: 'int[3] footprint of the wall tops (y = first roof layer)',
      to: 'int[3]',
      style: 'gable|shed|hip|pyramid',
      ridge: 'x|z (default: longer side)',
      material: 'palette key',
      overhang: 'int (default 0)',
      pitch: 'number (default 1.0) — rise per one block of run',
    },
  },
  cylinder: {
    doc: 'Vertical cylinder / tower shell.',
    fields: { center: 'int[3] (x,y,z of the axis at the bottom)', radius: 'number', height: 'int', material: 'palette key', hollow: 'bool', crenellated: 'bool' },
  },
  cone: {
    doc: 'Cone / spire (tower roofs).',
    fields: { center: 'int[3]', radius: 'number', height: 'int', material: 'palette key', hollow: 'bool' },
  },
  sphere: {
    doc: 'Sphere or dome.',
    fields: { center: 'int[3]', radius: 'number', material: 'palette key', hollow: 'bool', half: 'top|bottom|full' },
  },
  ring: {
    doc: 'Horizontal ring/band around a footprint: cornices, string courses, plinths.',
    fields: { from: 'int[3]', to: 'int[3]', y: 'int', material: 'palette key', profile: 'flat|cornice|eave|base', thickness: 'int' },
  },
  wall: {
    doc: 'Straight wall between two points with optional openings.',
    fields: { from: 'int[3]', to: 'int[3]', height: 'int', thickness: 'int', material: 'palette key', openings: 'Opening[]' },
  },
  floor: {
    doc: 'Floor slab with an optional pattern.',
    fields: { from: 'int[3]', to: 'int[3]', material: 'palette key', thickness: 'int', pattern: 'solid|checker|border|planks' },
  },
  stairs: {
    doc: 'Stair flight climbing along +axis.',
    fields: { from: 'int[3]', to: 'int[3]', axis: 'x|z', direction: 'forward|back', material: 'palette key', railing: 'bool' },
  },
  window: {
    doc: 'Window opening with frame, glass, sill and optional shutters.',
    fields: { wall: 'north|south|east|west', at: 'int[2] (u,y)', w: 'int', h: 'int', frame: 'palette key', glass: 'palette key', sill: 'bool', shutters: 'bool', arch: 'bool' },
  },
  door: {
    doc: 'Door opening with frame and optional canopy.',
    fields: { wall: 'north|south|east|west', at: 'int[2]', w: 'int (default 1)', h: 'int (default 2)', frame: 'palette key', canopy: 'bool' },
  },
  arch: {
    doc: 'Arched opening (gate, arcade).',
    fields: { wall: 'north|south|east|west', at: 'int[2]', w: 'int', h: 'int', material: 'palette key' },
  },
  balustrade: {
    doc: 'Railing run with posts and balusters.',
    fields: { from: 'int[3]', to: 'int[3]', material: 'palette key', spacing: 'int', style: 'fence|baluster|solid' },
  },
  beam: {
    doc: 'Structural beam (timber framing).',
    fields: { from: 'int[3]', to: 'int[3]', thickness: 'int', material: 'palette key' },
  },
  column: {
    doc: 'Column with base and capital.',
    fields: { at: 'int[3]', height: 'int', radius: 'number', material: 'palette key', style: 'round|square' },
  },
  chimney: {
    doc: 'Chimney stack with cap.',
    fields: { at: 'int[3]', w: 'int', d: 'int', height: 'int', material: 'palette key', cap: 'bool' },
  },
  roof: {
    doc: 'Alias of prism, kept for readability of older specs.',
    fields: { from: 'int[3]', to: 'int[3]', style: 'gable|hip|shed|pyramid', ridge: 'x|z', material: 'palette key', overhang: 'int' },
  },
  detail: {
    doc: 'Free-form decorative voxel cluster: [[x,y,z], ...] + material(s).',
    fields: { cells: 'int[3][]', material: 'palette key | palette key[]', pattern: 'solid|hash' },
  },
};

export const WALLS = ['north', 'south', 'east', 'west'];

/** Default palette used when a spec does not define one. */
export const DEFAULT_PALETTE = {
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

const isInt = (v) => Number.isInteger(v);
const isVec3 = (v) => Array.isArray(v) && v.length === 3 && v.every((n) => Number.isFinite(n));

/** Errors are collected, never thrown, so an LLM can be shown everything at once. */
export function validateSpec(spec) {
  const errors = [];
  const warnings = [];
  if (!spec || typeof spec !== 'object') return { ok: false, errors: ['spec must be an object'], warnings };
  if (typeof spec.name !== 'string' || !spec.name.trim()) warnings.push('spec.name is empty');
  if (!Array.isArray(spec.objects)) errors.push('spec.objects must be an array');
  const palette = spec.palette || {};
  const seenIds = new Set();
  (spec.objects || []).forEach((o, i) => {
    const at = `objects[${i}]${o && o.id ? ` (${o.id})` : ''}`;
    if (!o || typeof o !== 'object') {
      errors.push(`${at}: not an object`);
      return;
    }
    const type = o.type === 'roof' ? 'prism' : o.type;
    if (!OBJECT_TYPES.includes(o.type)) errors.push(`${at}: unknown type '${o.type}' (valid: ${OBJECT_TYPES.join(', ')})`);
    if (o.id) {
      if (seenIds.has(o.id)) errors.push(`${at}: duplicate id`);
      seenIds.add(o.id);
    }
    const openType0 = o.type === 'window' || o.type === 'door' || o.type === 'arch';
    for (const key of ['from', 'to', 'center']) {
      if (o[key] !== undefined && !isVec3(o[key])) errors.push(`${at}: ${key} must be [x,y,z]`);
    }
    if (o.at !== undefined && !openType0 && !isVec3(o.at)) errors.push(`${at}: at must be [x,y,z]`);
    if (openType0 && o.at !== undefined && !(Array.isArray(o.at) && o.at.length === 2 && o.at.every(isInt))) {
      errors.push(`${at}: at must be [u,y] for ${o.type}`);
    }
    if (o.from && o.to) {
      for (let k = 0; k < 3; k++) {
        if (o.from[k] > o.to[k]) errors.push(`${at}: from[${k}] > to[${k}]`);
      }
    }
    if (o.material && typeof o.material === 'string' && palette[o.material] === undefined && !isColor(o.material)) {
      warnings.push(`${at}: material '${o.material}' is not in the palette`);
    }
    if (type === 'window' || type === 'door' || type === 'arch') {
      if (!WALLS.includes(o.wall)) errors.push(`${at}: wall must be one of ${WALLS.join('|')}`);
    }
    if (type === 'prism' && o.style && !['gable', 'hip', 'shed', 'pyramid'].includes(o.style)) {
      errors.push(`${at}: style must be gable|hip|shed|pyramid`);
    }
    if (type === 'detail' && !Array.isArray(o.cells)) errors.push(`${at}: detail needs cells`);
  });
  return { ok: errors.length === 0, errors, warnings };
}

function isColor(s) {
  return typeof s === 'string' && /^#([0-9a-f]{3}|[0-9a-f]{6})$/i.test(s);
}

/** Fills in defaults so consumers never have to. Returns a new spec (the input is not modified). */
export function normalizeSpec(spec) {
  const out = {
    name: spec?.name || 'structure',
    seed: Number.isFinite(spec?.seed) ? spec.seed : 1,
    palette: { ...DEFAULT_PALETTE, ...(spec?.palette || {}) },
    objects: [],
    notes: spec?.notes || '',
  };
  for (const raw of spec?.objects || []) {
    const o = { ...raw, type: raw.type === 'roof' ? 'prism' : raw.type };
    if (o.from && o.to) {
      o.from = o.from.map(Math.round);
      o.to = o.to.map(Math.round);
      for (let k = 0; k < 3; k++) if (o.from[k] > o.to[k]) [o.from[k], o.to[k]] = [o.to[k], o.from[k]];
    }
    if (o.center) o.center = o.center.map(Math.round);
    if (o.at) o.at = o.at.map(Math.round);
    if (o.openings) o.openings = o.openings.map((op) => ({ ...op }));
    out.objects.push(o);
  }
  return out;
}

/** Bounding box of the whole spec, in scene units (approximate: object primitives only). */
export function specBounds(spec) {
  let min = [Infinity, Infinity, Infinity];
  let max = [-Infinity, -Infinity, -Infinity];
  const grow = (v) => {
    for (let k = 0; k < 3; k++) {
      min[k] = Math.min(min[k], v[k]);
      max[k] = Math.max(max[k], v[k]);
    }
  };
  for (const o of spec?.objects || []) {
    if (o.from) grow(o.from);
    if (o.to) grow(o.to);
    if (o.center) grow(o.center);
    if (o.at) grow([o.at[0], o.at[1], 0]);
    if (Array.isArray(o.cells)) for (const c of o.cells) grow(c);
  }
  if (!Number.isFinite(min[0])) return { min: [0, 0, 0], max: [0, 0, 0], size: [0, 0, 0] };
  return { min, max, size: [max[0] - min[0] + 1, max[1] - min[1] + 1, max[2] - min[2] + 1] };
}

/** Compact textual digest of the spec for prompts (no geometry rebuilding, cheap). */
export function specDigest(spec, max = 60) {
  const b = specBounds(spec);
  const lines = [
    `name: ${spec?.name || '?'}  size: ${b.size.join('x')}  objects: ${spec?.objects?.length || 0}`,
    `palette: ${Object.entries(spec?.palette || {}).map(([k, v]) => `${k}=${v}`).join(' ')}`,
  ];
  (spec?.objects || []).slice(0, max).forEach((o, i) => {
    const id = o.id ? `#${o.id} ` : '';
    lines.push(`${i + 1}. ${id}${o.type} ${JSON.stringify(o, (k, v) => (k === 'type' || k === 'id' ? undefined : v)).slice(0, 220)}`);
  });
  if ((spec?.objects?.length || 0) > max) lines.push(`... ${spec.objects.length - max} more objects`);
  return lines.join('\n');
}
