/**
 * Objective analysis of a StructureSpec.
 *
 * The critic (critic.mjs) is an LLM looking at pictures; this module is the part that is *checkable*.
 * It never renders anything and never touches blocks: it reasons about the declarative spec, so it is
 * cheap (a few milliseconds for a house) and can run on every iteration, or a hundred times in a test.
 *
 * Two kinds of output are produced:
 *
 *   metrics — numbers the loop stores in history and shows in the report;
 *   issues  — [{severity, what, where, code}] where `code` is a stable machine-readable id and
 *             `severity` uses the same scale as the critic ('critical' | 'major' | 'minor').
 *
 * The checks are deliberately conservative heuristics. A gothic cathedral without a door object is not
 * really an error, so most findings are 'major' or 'minor'; only a spec that cannot be built at all
 * (no objects, invalid boxes, a missing roof over an enclosed body) is 'critical'.
 *
 * Requested size is inferred from the prompt when one is given (`analyzeSpec(spec, {request})`):
 * the generator is told to respect "10 на 10" / "10x10" literally, and this is where we verify it.
 */
import { normalizeSpec, validateSpec, specBounds, OBJECT_TYPES } from './spec.mjs';

/** Severity ordering, highest first. */
export const SEVERITY_ORDER = /** @type {const} */ (['critical', 'major', 'minor']);

/** Object types that count as "the enclosure" of a building. */
const ENCLOSING_TYPES = new Set(['box', 'wall', 'cylinder']);

/** Object types that create a walkable floor plane. */
const FLOOR_TYPES = new Set(['floor', 'box']);

/** Object types that are openings (they punch holes rather than add mass). */
const OPENING_TYPES = new Set(['window', 'door', 'arch']);

/** Object types that carry the roof. */
const ROOF_TYPES = new Set(['prism', 'cone', 'sphere']);

/** Types for which `from`/`to` are mandatory. */
const BOX_TYPES = new Set(['box', 'prism', 'roof', 'wall', 'floor', 'stairs', 'ring', 'beam', 'balustrade']);

/** Issue severities as a stable list, so callers can filter without string comparisons everywhere. */
export const isSevere = (issue) => issue?.severity === 'critical' || issue?.severity === 'major';

/**
 * Parses a requested size out of a natural-language prompt.
 *
 * Recognised forms: "10 на 10", "10x10", "10×10", "10 by 10", "12 на 8 на 6", and heights such as
 * "высотой 12" / "12 blocks tall". Returns integers in scene units (metres == blocks), or null when
 * the prompt does not ask for an explicit size.
 *
 * @param {string} [request]
 * @returns {{width:number, depth:number, height:number|null, source:string}|null}
 */
export function parseRequestedSize(request) {
  if (typeof request !== 'string' || !request.trim()) return null;
  const text = request.toLowerCase().replace(/ё/g, 'е');
  const sizes = [];

  // "10 на 10", "10x10", "10 × 10", "10 by 10", "10*10", "10 на 10 на 12"
  // Lookarounds keep a 4-digit year ("2024 на 2025") from being read as a 24x202 footprint.
  const dimRe = /(?<!\d)(\d{1,3})\s*(?:на|by|x|×|\*)\s*(\d{1,3})(?!\d)(?:\s*(?:на|by|x|×|\*)\s*(\d{1,3})(?!\d))?/g;
  let m;
  while ((m = dimRe.exec(text))) {
    const a = Number(m[1]);
    const b = Number(m[2]);
    const c = m[3] ? Number(m[3]) : null;
    // Ignore anything that cannot be a building footprint in blocks (0 or absurd sizes).
    if (a < 2 || a > 400 || b < 2 || b > 400) continue;
    sizes.push({ width: a, depth: b, height: c, source: m[0] });
  }
  if (!sizes.length) {
    // A lone "10x10x10 room" is already covered above; fall back to no constraint.
    return null;
  }
  // The first size mentioned is treated as the footprint of the main body.
  const s = sizes[0];
  return { ...s, height: sizes[0].height ?? parseRequestedHeight(text) };
}

/** "высотой 12", "12 blocks tall" -> 12 */
function parseRequestedHeight(text) {
  const m = /(?:высот\w*|height|tall|вышка|h)\D{0,12}?(\d{1,3})/.exec(text);
  if (!m) return null;
  const h = Number(m[1]);
  return h >= 2 && h <= 400 ? h : null;
}

/**
 * True when every number of a coordinate triple is an integer and the box is non-degenerate.
 * @param {any} from @param {any} to
 */
function isBox(from, to) {
  return (
    Array.isArray(from) && Array.isArray(to) && from.length === 3 && to.length === 3 &&
    [...from, ...to].every(Number.isInteger) &&
    from[0] <= to[0] && from[1] <= to[1] && from[2] <= to[2]
  );
}

/** Volume of a box in cells (inclusive corners), 0 for a degenerate/absent box. */
function boxVolume(from, to) {
  if (!isBox(from, to)) return 0;
  return (to[0] - from[0] + 1) * (to[1] - from[1] + 1) * (to[2] - from[2] + 1);
}

/** The axis-aligned extents an object occupies, or null when they cannot be known cheaply. */
function objectExtent(o) {
  if (isBox(o.from, o.to)) return { min: o.from, max: o.to };
  if (o.type === 'chimney' && Array.isArray(o.at)) {
    const w = Math.max(1, Math.round(o.w ?? 1));
    const d = Math.max(1, Math.round(o.d ?? 1));
    const h = Math.max(1, Math.round(o.height ?? 1));
    return { min: [o.at[0], o.at[1], o.at[2]], max: [o.at[0] + w - 1, o.at[1] + h - 1, o.at[2] + d - 1] };
  }
  if (o.type === 'column' && Array.isArray(o.at)) {
    const r = Math.max(1, Math.round(o.radius ?? 1));
    const h = Math.max(1, Math.round(o.height ?? 1));
    return { min: [o.at[0] - r + 1, o.at[1], o.at[2] - r + 1], max: [o.at[0] + r, o.at[1] + h - 1, o.at[2] + r] };
  }
  if (typeof o.type === 'string' && ['cylinder', 'cone', 'sphere', 'ring'].includes(o.type)) {
    const c = o.center || (isBox(o.from, o.to) ? [Math.floor((o.from[0] + o.to[0]) / 2), o.from[1], Math.floor((o.from[2] + o.to[2]) / 2)] : null);
    if (!c) return null;
    const r = Math.max(1, Math.round(o.radius ?? (isBox(o.from, o.to) ? Math.max(o.to[0] - o.from[0], o.to[2] - o.from[2]) / 2 + 1 : 1)));
    const y0 = o.type === 'sphere' ? c[1] - r : c[1];
    const y1 = o.type === 'sphere' ? c[1] + r : c[1] + Math.max(1, Math.round(o.height ?? 1)) - 1;
    return { min: [c[0] - r + 1, y0, c[2] - r + 1], max: [c[0] + r, y1, c[2] + r] };
  }
  return null;
}

/** True when two extents share at least one cell on all three axes. */
function overlaps(a, b) {
  if (!a || !b) return false;
  for (let k = 0; k < 3; k++) if (a.max[k] < b.min[k] || b.max[k] < a.min[k]) return false;
  return true;
}

/** Cells shared by two boxes (inclusive), 0 when they do not overlap. */
function overlapCells(a, b) {
  if (!overlaps(a, b)) return 0;
  let cells = 1;
  for (let k = 0; k < 3; k++) cells *= Math.min(a.max[k], b.max[k]) - Math.max(a.min[k], b.min[k]) + 1;
  return cells;
}

const sameBox = (a, b) =>
  !!a && !!b && [0, 1, 2].every((k) => a.min[k] === b.min[k] && a.max[k] === b.max[k]);

/** Short human label for an object, used in `issues[].where`. */
function label(o, i) {
  const id = o && typeof o.id === 'string' && o.id ? `#${o.id}` : `#${i + 1}`;
  return `${id}${o && o.type ? ` (${o.type})` : ''}`;
}

/**
 * Counts the objects by type.
 * @param {object[]} objects
 * @returns {Record<string, number>}
 */
function countByType(objects) {
  /** @type {Record<string, number>} */
  const byType = {};
  for (const o of objects) {
    const t = o?.type === 'roof' ? 'prism' : String(o?.type || 'unknown');
    byType[t] = (byType[t] || 0) + 1;
  }
  return byType;
}

/**
 * Counts the openings a spec declares, both as standalone objects and as `openings` arrays.
 * Understands the two shapes the geometry module accepts (`kind` and no `kind` = window).
 * @param {object[]} objects
 */
export function countOpenings(objects) {
  const out = { door: 0, window: 0, arch: 0, total: 0 };
  for (const o of objects) {
    if (!o) continue;
    if (OPENING_TYPES.has(o.type)) {
      const kind = o.type === 'door' ? 'door' : o.type === 'arch' ? 'arch' : 'window';
      out[kind]++;
      out.total++;
    }
    if (Array.isArray(o.openings)) {
      for (const op of o.openings) {
        const kind = op?.kind === 'door' ? 'door' : op?.kind === 'arch' ? 'arch' : 'window';
        out[kind]++;
        out.total++;
      }
    }
  }
  return out;
}

/**
 * Finds "rooms": a horizontal slab (floor) strictly inside a larger vertical enclosure.
 *
 * The heuristic is intentionally simple and type-based (we do not build geometry here): an enclosing
 * object (`box` hollow, `wall`) whose interior extent is bigger than the slab gives an interior, and a
 * floor/box slab at a height above the ground inside it splits that interior into storeys.
 *
 * @param {object[]} objects
 * @returns {{rooms:number, storeys:number, enclosed:boolean, enclosedVolume:number, floors:number}}
 */
export function roomHeuristic(objects) {
  const enclosures = [];
  for (const o of objects) {
    if (!o || !ENCLOSING_TYPES.has(o.type)) continue;
    const ext = objectExtent(o);
    if (!ext) continue;
    const size = [0, 1, 2].map((k) => ext.max[k] - ext.min[k] + 1);
    // Only shell-like things count as an enclosure: at least 2 blocks in every horizontal direction
    // and at least 2 blocks tall. A fence or a plinth is not a room.
    if (size[0] < 3 || size[2] < 3 || size[1] < 2) continue;
    const hollow = o.hollow === true || o.type === 'wall' || o.open === true || Array.isArray(o.open);
    if (!hollow) continue;
    enclosures.push({ o, ext, size, inner: [size[0] - 2, size[1] - 2, size[2] - 2] });
  }
  let floors = 0;
  let interiorVolume = 0;
  for (const e of enclosures) {
    interiorVolume += Math.max(0, e.inner[0] * e.inner[1] * e.inner[2]);
    for (const o of objects) {
      if (!o || !FLOOR_TYPES.has(o.type) || o === e.o) continue;
      const ext = objectExtent(o);
      if (!ext) continue;
      const thickness = ext.max[1] - ext.min[1] + 1;
      if (thickness > 2) continue; // a storey slab, not a block of masonry
      // A real storey slab sits inside the enclosure footprint (touching the walls is normal — a
      // 10x10 floor is laid 0..9 while the hollow shell's interior is 1..8) and above its bottom.
      const inside =
        ext.min[0] >= e.ext.min[0] && ext.max[0] <= e.ext.max[0] &&
        ext.min[2] >= e.ext.min[2] && ext.max[2] <= e.ext.max[2] &&
        ext.min[1] > e.ext.min[1] && ext.max[1] <= e.ext.max[1];
      if (inside) floors++;
    }
  }
  const storeys = enclosures.length ? 1 + floors : 0;
  return {
    // One room per storey: we cannot see interior walls in the spec, but every storey is a space.
    rooms: storeys,
    storeys,
    floors,
    interiorFloors: floors,
    enclosed: enclosures.length > 0,
    enclosedVolume: interiorVolume,
    enclosures: enclosures.length,
  };
}

/** Does the spec have an object that can be read as a roof over the body? */
function roofInfo(objects) {
  const roofs = objects.filter((o) => o && (ROOF_TYPES.has(o.type) || o.type === 'roof'));
  let coverage = 0;
  for (const o of roofs) {
    const ext = objectExtent(o);
    if (!ext) continue;
    const area = (ext.max[0] - ext.min[0] + 1) * (ext.max[2] - ext.min[2] + 1);
    coverage = Math.max(coverage, area);
  }
  const overhang = roofs.some((o) => Number(o.overhang) > 0);
  return { count: roofs.length, coverage, overhang, styles: [...new Set(roofs.map((o) => o.style).filter(Boolean))] };
}

/**
 * The main objective check.
 *
 * @param {object} spec a StructureSpec (normalised here; it is cheap)
 * @param {object} [options]
 * @param {string} [options.request] the original user prompt, used to verify an explicitly requested size
 * @param {number} [options.maxObjects=400] an honest upper bound for a hand-designed scene
 * @param {number} [options.minVariety=3] how many distinct object types a "designed" building should use
 * @returns {{
 *   ok: boolean,
 *   metrics: object,
 *   issues: Array<{severity:'critical'|'major'|'minor', code:string, what:string, where:string}>,
 *   valid: {ok:boolean, errors:string[], warnings:string[]},
 *   requested: object|null
 * }}
 */
export function analyzeSpec(spec, options = {}) {
  const maxObjects = Number.isFinite(options.maxObjects) ? options.maxObjects : 400;
  const minVariety = Number.isFinite(options.minVariety) ? options.minVariety : 3;
  const raw = normalizeSpec(spec && typeof spec === 'object' ? spec : {});
  const objects = Array.isArray(raw.objects) ? raw.objects : [];
  const valid = validateSpec(raw);
  const bounds = specBounds(raw);
  const issues = [];

  const add = (severity, code, what, where = 'spec') => {
    issues.push({ severity, code, what, where });
  };

  // ---- 1. Validity -------------------------------------------------------------------------
  if (!objects.length) add('critical', 'empty-spec', 'В спецификации нет ни одного объекта — строить нечего.', 'objects');
  if (!valid.ok) {
    for (const err of valid.errors.slice(0, 8)) add('critical', 'invalid-spec', err, 'objects');
  }
  // Warnings from spec.mjs are minor findings: an unknown palette key still renders, just in grey.
  for (const warn of valid.warnings.slice(0, 8)) add('minor', 'spec-warning', warn, 'objects');

  // ---- 2. Bounds: are the coordinates sane? ------------------------------------------------
  const size = bounds.size;
  if (objects.length) {
    if (bounds.min[1] < -1) {
      add('major', 'below-ground', `Модель уходит под землю до y=${bounds.min[1]} (земля — y=0).`, 'bounds');
    }
    if (bounds.min[1] > 1) {
      add('major', 'floating-model', `Модель висит над землёй: нижняя точка y=${bounds.min[1]}.`, 'bounds');
    }
    if (size[1] > 160) add('major', 'too-tall', `Высота ${size[1]} блоков — выше разумного для постройки.`, 'bounds');
    if (Math.max(size[0], size[2]) > 256) {
      add('major', 'too-large', `Основание ${size[0]}×${size[2]} блоков — слишком большое, детализация пропадёт.`, 'bounds');
    }
  }

  // ---- 3. Requested size -------------------------------------------------------------------
  const requested = parseRequestedSize(options.request);
  let sizeMatch = null;
  if (requested && objects.length) {
    // Compare with the footprint of the largest enclosing object (the main body), or with the whole
    // bounds when there is no obvious body. Overhangs/plinths add 1-2 blocks, so a small tolerance
    // is applied; anything beyond it is a real mismatch the model must fix.
    const body = biggestBody(objects);
    const actual = body
      ? [body.ext.max[0] - body.ext.min[0] + 1, body.ext.max[1] - body.ext.min[1] + 1, body.ext.max[2] - body.ext.min[2] + 1]
      : size;
    const want = [requested.width, requested.height ?? actual[1], requested.depth];
    const deltas = [actual[0] - want[0], actual[2] - want[2]];
    if (requested.height) deltas.push(actual[1] - want[1]);
    const tolerance = (n) => Math.max(1, Math.ceil(n * 0.15));
    sizeMatch = {
      requested: { width: requested.width, depth: requested.depth, height: requested.height },
      actual: { width: actual[0], depth: actual[2], height: actual[1] },
      deltas,
      ok: deltas.every((d, i) => Math.abs(d) <= tolerance(want[i])),
      source: requested.source,
    };
    if (!sizeMatch.ok) {
      add(
        'major',
        'size-mismatch',
        `Запрошен размер ${requested.width}×${requested.depth}${requested.height ? `, высота ${requested.height}` : ''} блоков, ` +
          `а тело постройки — ${actual[0]}×${actual[2]}${requested.height ? `, высота ${actual[1]}` : ''}.`,
        'bounds',
      );
    }
  }

  // ---- 4. Degenerate / duplicated objects --------------------------------------------------
  let degenerate = 0;
  let duplicateBoxes = 0;
  const extents = [];
  objects.forEach((o, i) => {
    if (!o) return;
    const ext = objectExtent(o);
    if (BOX_TYPES.has(o.type)) {
      if (!isBox(o.from, o.to)) {
        degenerate++;
        add('critical', 'bad-box', `${label(o, i)}: некорректные from/to — объект не построить.`, label(o, i));
      } else {
        const v = boxVolume(o.from, o.to);
        if (v <= 0) {
          degenerate++;
          add('major', 'empty-box', `${label(o, i)}: нулевой объём.`, label(o, i));
        }
      }
    }
    if (o.type === 'column' && !(Array.isArray(o.at) && o.at.length === 3)) {
      degenerate++;
      add('major', 'missing-at', `${label(o, i)}: нет координаты at.`, label(o, i));
    }
    if ((o.type === 'chimney') && !(Array.isArray(o.at) && o.at.length === 3)) {
      degenerate++;
      add('major', 'missing-at', `${label(o, i)}: нет координаты at.`, label(o, i));
    }
    if (OPENING_TYPES.has(o.type)) {
      if (!(Array.isArray(o.at) && o.at.length === 2)) {
        degenerate++;
        add('major', 'missing-at', `${label(o, i)}: проём без at:[u,y].`, label(o, i));
      }
      if (!(Number(o.w) >= 1 && Number(o.h) >= 1)) {
        degenerate++;
        add('minor', 'zero-opening', `${label(o, i)}: нулевой проём (w/h).`, label(o, i));
      }
    }
    if (o.type === 'detail' && (!Array.isArray(o.cells) || o.cells.length === 0)) {
      degenerate++;
      add('minor', 'empty-detail', `${label(o, i)}: detail без ячеек.`, label(o, i));
    }
    if (ext) extents.push({ i, o, ext });
  });

  // Identical boxes stacked on top of each other are almost always a copy-paste bug: they add no
  // geometry, only draw calls and confusion in the patch history.
  for (let a = 0; a < extents.length; a++) {
    for (let b = a + 1; b < extents.length; b++) {
      if (sameBox(extents[a].ext, extents[b].ext) && extents[a].o.type === extents[b].o.type) {
        duplicateBoxes++;
        add(
          'minor',
          'duplicate-box',
          `${label(extents[a].o, extents[a].i)} и ${label(extents[b].o, extents[b].i)} занимают один и тот же объём.`,
          label(extents[b].o, extents[b].i),
        );
      }
    }
  }

  // ---- 5. Composition: door, windows, roof ------------------------------------------------
  const openings = countOpenings(objects);
  if (objects.length) {
    if (!openings.door) {
      add('major', 'no-door', 'Нет ни одного дверного проёма — в постройку нельзя войти.', 'openings');
    }
    if (!openings.window) {
      add('major', 'no-window', 'Нет ни одного окна — фасад получается слепым.', 'openings');
    }
  }
  const roof = roofInfo(objects);
  const rooms = roomHeuristic(objects);
  if (objects.length && rooms.enclosed && !roof.count) {
    add('critical', 'no-roof', 'У постройки есть стены и пол, но нет крыши — сверху она открыта.', 'roof');
  } else if (objects.length && !rooms.enclosed && !roof.count) {
    add('major', 'no-roof', 'Нет крыши и нет замкнутых стен — это не постройка, а набор плоскостей.', 'roof');
  }
  if (roof.count && rooms.enclosed && roof.coverage > 0) {
    const body = biggestBody(objects);
    if (body) {
      const bodyArea = (body.ext.max[0] - body.ext.min[0] + 1) * (body.ext.max[2] - body.ext.min[2] + 1);
      if (roof.coverage < bodyArea * 0.8) {
        add('major', 'roof-too-small', `Крыша покрывает ${roof.coverage} кл., а основание — ${bodyArea} кл.: будут дыры.`, 'roof');
      }
    }
  }
  if (roof.count && !roof.overhang && objects.length > 8) {
    add('minor', 'no-overhang', 'У крыши нет свеса (overhang) — фасад выглядит срезанным сверху.', 'roof');
  }

  // ---- 6. Count and variety ---------------------------------------------------------------
  if (objects.length > maxObjects) {
    add('major', 'too-many-objects', `${objects.length} объектов при разумном пределе ${maxObjects}.`, 'objects');
  }
  const byType = countByType(objects);
  const variety = Object.keys(byType).length;
  if (objects.length > 2 && variety < minVariety) {
    add(
      'minor',
      'low-variety',
      `Всего ${variety} тип(ов) объектов (${Object.keys(byType).join(', ')}) — постройка будет из однообразных кубов.`,
      'objects',
    );
  }

  // ---- 7. Metrics -------------------------------------------------------------------------
  const materials = new Set(objects.map((o) => o?.material).filter((m) => typeof m === 'string'));
  const voxelEstimate = estimateVoxels(objects);
  const metrics = {
    objects: objects.length,
    byType,
    variety,
    materials: materials.size,
    size: { width: size[0], height: size[1], depth: size[2] },
    bounds,
    footprint: size[0] * size[2],
    buildings: rooms.enclosures,
    storeys: rooms.storeys,
    rooms: rooms.rooms,
    enclosed: rooms.enclosed,
    enclosedVolume: rooms.enclosedVolume,
    openings,
    hasDoor: openings.door > 0,
    hasWindow: openings.window > 0,
    hasRoof: roof.count > 0,
    roofCoverage: roof.coverage,
    roofOverhang: roof.overhang,
    roofStyles: roof.styles,
    degenerate,
    duplicates: duplicateBoxes,
    voxelEstimate,
    counts: {
      critical: issues.filter((i) => i.severity === 'critical').length,
      major: issues.filter((i) => i.severity === 'major').length,
      minor: issues.filter((i) => i.severity === 'minor').length,
    },
    sizeMatch,
    requested,
  };
  metrics.counts.total = issues.length;

  // "ok" is about buildability, not beauty: a spec is ok when nothing is critical/major.
  const ok = metrics.counts.critical === 0 && metrics.counts.major === 0;
  return { ok, metrics, issues, valid, requested, spec: raw };
}

/** The largest shell/box object — the main body of the building, used for size comparisons. */
function biggestBody(objects) {
  let best = null;
  for (let i = 0; i < objects.length; i++) {
    const o = objects[i];
    if (!o || !ENCLOSING_TYPES.has(o.type)) continue;
    const ext = objectExtent(o);
    if (!ext) continue;
    const cells = (ext.max[0] - ext.min[0] + 1) * (ext.max[1] - ext.min[1] + 1) * (ext.max[2] - ext.min[2] + 1);
    if (!best || cells > best.cells) best = { o, ext, i, cells };
  }
  return best;
}

/**
 * Cheap voxel count estimate from the spec alone (no geometry). Boxes contribute their volume
 * (halved when hollow is... not assumed — a hollow box is still drawn as a shell, but we cannot know
 * the wall thickness here, so a hollow box counts as its surface). Prisms count as half their
 * footprint times the implied height. Only used for the report, never for decisions.
 */
function estimateVoxels(objects) {
  let total = 0;
  for (const o of objects) {
    if (!o) continue;
    if (isBox(o.from, o.to)) {
      const w = o.to[0] - o.from[0] + 1;
      const h = o.to[1] - o.from[1] + 1;
      const d = o.to[2] - o.from[2] + 1;
      if (o.type === 'prism' || o.type === 'roof') {
        const ridge = o.ridge || (w >= d ? 'x' : 'z');
        const span = ridge === 'x' ? d : w;
        const rise = Math.max(1, Math.ceil(span / 2));
        total += Math.ceil((w * d * rise) / 2);
      } else if (o.hollow === true) {
        // shell of thickness 1
        total += w * h * d - Math.max(0, w - 2) * Math.max(0, h - 2) * Math.max(0, d - 2);
      } else {
        total += w * h * d;
      }
    } else if (o.type === 'cylinder' || o.type === 'cone') {
      const r = Math.max(1, Number(o.radius) || 1);
      const h = Math.max(1, Number(o.height) || 1);
      total += Math.round(Math.PI * r * r * h * (o.type === 'cone' ? 1 / 3 : 1) * (o.hollow ? 0.4 : 1));
    } else if (o.type === 'detail' && Array.isArray(o.cells)) {
      total += o.cells.length;
    } else if (o.type === 'chimney') {
      total += Math.max(1, Number(o.w) || 1) * Math.max(1, Number(o.d) || 1) * Math.max(1, Number(o.height) || 1);
    }
  }
  return total;
}

/**
 * Removes the findings that a critic should not be shown twice: identical `what` strings at the
 * same `where`. Used by the loop when it merges analyzer and critic issues.
 * @param {Array<object>} issues
 * @returns {Array<object>}
 */
export function dedupeIssues(issues) {
  const seen = new Set();
  const out = [];
  for (const i of issues || []) {
    if (!i) continue;
    const key = `${i.severity}|${String(i.code || '')}|${String(i.where || '')}|${String(i.what || '')}`.slice(0, 400);
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(i);
  }
  return out;
}

/**
 * Sorts issues by severity (critical first) and keeps a stable order inside a severity.
 * @param {Array<object>} issues
 * @returns {Array<object>}
 */
export function sortIssues(issues) {
  const rank = { critical: 0, major: 1, minor: 2 };
  return [...(issues || [])].sort((a, b) => (rank[a?.severity] ?? 3) - (rank[b?.severity] ?? 3));
}

/** Exported for tests / other modules that need the same notion of "known object type". */
export { OBJECT_TYPES, overlaps, overlapCells, objectExtent };
