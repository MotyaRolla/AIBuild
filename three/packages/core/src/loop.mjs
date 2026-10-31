/**
 * The iteration loop: design -> render -> critique -> patch -> repeat.
 *
 * One call of `runBuildLoop` is one "many attempts" build. Each iteration:
 *
 *   1. objectively analyses the current spec (`analyzeSpec`, no rendering needed);
 *   2. renders a few views (injected `render(scene, opts)`, or the built-in software renderer);
 *   3. asks the AI critic for issues, scores and a patch (`critiqueSpec`);
 *   4. applies the patch, rejecting anything that would make the spec invalid;
 *   5. records everything in `history` and decides whether to stop.
 *
 * Stopping: the critic reports no critical/major issues, the analyzer is ok, and the quality score
 * did not gain `thresholds.scoreGain` points for `thresholds.stallIterations` iterations in a row —
 * or the iteration cap is reached. `pickBest(history)` always returns the version to ship, which is
 * not necessarily the last one (a late iteration can make things worse).
 *
 * Rendering (task A/B) and the language model are both injected or optional: if the renderer is not
 * available the loop continues with analysis only, and a failing model degrades to the objective
 * checks instead of aborting the build. The loop never writes files; it returns a Russian markdown
 * report that the CLI/studio persists next to the spec.
 */
import { normalizeSpec, validateSpec, specDigest, specBounds, OBJECT_TYPES, WALLS } from './spec.mjs';
import { analyzeSpec, dedupeIssues, sortIssues, isSevere, parseRequestedSize } from './analysis.mjs';
import { critiqueSpec, severityCounts, isClean, averageScores } from './critic.mjs';

/** Default stopping/behaviour thresholds. Scores are on a 0..100 scale. */
export const DEFAULT_THRESHOLDS = /** @type {const} */ ({
  /** A gain smaller than this is "no real progress". */
  scoreGain: 2,
  /** How many consecutive stalled iterations are needed to stop (together with a clean critic). */
  stallIterations: 2,
  /** Do not stop before the score reaches this. */
  minScore: 0,
  /** Guardrail: a patch may not push the object count above this. */
  maxObjects: 300,
  /** How many rendered PNGs to send to the critic. */
  maxImages: 6,
  /** Maximum operations accepted from one patch. */
  patchOps: 40,
  /** Renderer defaults (draft resolution keeps 8 iterations fast enough to watch). */
  render: { width: 400, height: 300, draft: true, views: ['se', 'sw', 'front', 'top'] },
});

/** Types whose `from`/`to` must form a real box; checked before a patch may add one. */
const BOX_TYPES = new Set(['box', 'prism', 'roof', 'wall', 'floor', 'stairs', 'ring', 'beam', 'balustrade']);
/** Types that need `at:[u,y]` and a wall. */
const OPENING_TYPES = new Set(['window', 'door', 'arch']);
/** Types that need `at:[x,y,z]`. */
const AT_TYPES = new Set(['column', 'chimney']);

/** Optional modules are imported lazily so this file loads while A and B are still in progress. */
const moduleCache = new Map();

/**
 * Imports another module, returning null (and remembering why) instead of throwing.
 * @param {string} rel relative specifier, e.g. './render.mjs'
 * @returns {Promise<object|null>}
 */
async function optionalImport(rel) {
  if (moduleCache.has(rel)) return moduleCache.get(rel);
  let mod = null;
  try {
    mod = await import(rel);
  } catch (e) {
    if (e?.code === 'ERR_MODULE_NOT_FOUND' || e?.code === 'ERR_UNSUPPORTED_DIR_IMPORT') mod = null;
    else mod = null; // a broken sibling module must not take the loop down
  }
  moduleCache.set(rel, mod);
  return mod;
}

/** Resets the lazy-import cache (tests use this to simulate a missing renderer). */
export function resetModuleCache() {
  moduleCache.clear();
}

/** Structural check of one object before it is allowed into a spec. Returns an error string or ''. */
export function checkObjectShape(obj) {
  if (!obj || typeof obj !== 'object' || Array.isArray(obj)) return 'object is not a JSON object';
  if (typeof obj.type !== 'string' || !obj.type.trim()) return 'object has no type';
  if (!OBJECT_TYPES.includes(obj.type)) return `unknown type '${obj.type}'`;
  if (obj.id !== undefined && (typeof obj.id !== 'string' || !obj.id.trim())) return 'id must be a non-empty string';
  const vec = (v, n) => Array.isArray(v) && v.length === n && v.every(Number.isInteger);
  if (BOX_TYPES.has(obj.type)) {
    if (!vec(obj.from, 3) || !vec(obj.to, 3)) return `${obj.type} needs integer from[3] and to[3]`;
    for (let k = 0; k < 3; k++) if (obj.from[k] > obj.to[k]) return `${obj.type}: from[${k}] > to[${k}]`;
  }
  if (OPENING_TYPES.has(obj.type)) {
    if (!WALLS.includes(obj.wall)) return `${obj.type} needs wall (${WALLS.join('|')})`;
    if (!vec(obj.at, 2)) return `${obj.type} needs at:[u,y]`;
    if (!(Number(obj.w) >= 1) || !(Number(obj.h) >= 1)) return `${obj.type} needs positive w and h`;
  }
  if (AT_TYPES.has(obj.type) && !vec(obj.at, 3)) return `${obj.type} needs at:[x,y,z]`;
  if (obj.type === 'detail' && (!Array.isArray(obj.cells) || !obj.cells.length)) return 'detail needs cells[]';
  if (obj.type === 'cylinder' || obj.type === 'cone') {
    if (!(Number(obj.radius) > 0)) return `${obj.type} needs a positive radius`;
    if (!(Number(obj.height) >= 1)) return `${obj.type} needs height`;
  }
  if (obj.type === 'sphere' && !(Number(obj.radius) > 0)) return 'sphere needs a positive radius';
  return '';
}

/** Shallow clone of an object, so a patch can never mutate the history version. */
function cloneObject(o) {
  return JSON.parse(JSON.stringify(o));
}

/** Deep-clones a spec (specs are small; JSON round-trip also drops undefined/functions). */
function cloneSpec(spec) {
  try {
    return JSON.parse(JSON.stringify(spec));
  } catch {
    return normalizeSpec(spec || {});
  }
}

/** Finds the index of a patch reference: an object id, or an integer index. */
function resolveRef(objects, ref) {
  if (typeof ref === 'string') {
    const id = ref.startsWith('#') ? ref.slice(1) : ref;
    return objects.findIndex((o) => o && o.id === id);
  }
  if (Number.isInteger(ref)) return ref >= 0 && ref < objects.length ? ref : -1;
  return -1;
}

/** Human description of the change one patch operation made. */
function describeChange(change) {
  const what = change.type ? ` (${change.type})` : '';
  const id = change.id ? `\`${change.id}\`` : `#${(change.index ?? 0) + 1}`;
  if (change.op === 'add') return `добавлен объект ${id}${what}`;
  if (change.op === 'replace') return `заменён объект ${id}${what}`;
  return `удалён объект ${id}${what}`;
}

/**
 * Applies an AI patch to a spec. Every operation is checked on its own: unknown ids, unknown types,
 * malformed boxes and objects that would break `validateSpec` are dropped with an error message
 * instead of corrupting the build. A patch with no surviving operation is reported as rejected and
 * the original spec is returned unchanged (so the loop can never crash or lose a version on a bad
 * model answer).
 *
 * Accepted patch shape:
 *   { add: [OBJ], replace: [OBJ with id or index], remove: [id | index] }
 *
 * @param {object} spec current StructureSpec
 * @param {{add?: object[], replace?: object[], remove?: Array<string|number>}} patch
 * @param {{maxObjects?: number}} [options]
 * `empty` is true when the model simply asked for no change at all (an "all good" answer); the
 * caller uses it to word the report differently from a rejected patch.
 *
 * @returns {{spec: object, applied: boolean, empty: boolean, errors: string[], changes: Array<{op:string,id?:string,type?:string,index?:number}>}}
 */
export function applySpecPatch(spec, patch, { maxObjects = DEFAULT_THRESHOLDS.maxObjects } = {}) {
  const base = normalizeSpec(cloneSpec(spec || {}));
  const errors = [];
  const changes = [];
  const working = cloneSpec(base);

  const p = patch && typeof patch === 'object' ? patch : {};
  const adds = Array.isArray(p.add) ? p.add : [];
  const replaces = Array.isArray(p.replace) ? p.replace : [];
  const removes = Array.isArray(p.remove) ? p.remove : [];

  // Each operation is checked on its own and the spec is re-validated when the operation lands.
  // A model that sends one bad object therefore loses that object, not the whole iteration.
  const commit = (nextObjects, change) => {
    const candidate = normalizeSpec({ ...working, objects: nextObjects });
    const validation = validateSpec(candidate);
    if (!validation.ok) {
      errors.push(...validation.errors.slice(0, 3).map((e) => `${change.op}: ${e} (operation dropped)`));
      return false;
    }
    working.objects = candidate.objects;
    changes.push(change);
    return true;
  };

  // ---- removes first, so a replace+remove of the same id behaves predictably ----
  for (const ref of removes) {
    const idx = resolveRef(working.objects, ref);
    if (idx < 0) {
      errors.push(`remove: unknown object ${JSON.stringify(ref)}`);
      continue;
    }
    const removed = working.objects[idx];
    const next = working.objects.slice(0, idx).concat(working.objects.slice(idx + 1));
    commit(next, { op: 'remove', id: removed?.id, type: removed?.type, index: idx });
  }

  // ---- replaces ----
  for (const raw of replaces) {
    if (!raw || typeof raw !== 'object') {
      errors.push('replace: not an object');
      continue;
    }
    const ref = raw.id !== undefined ? raw.id : raw.index;
    const idx = resolveRef(working.objects, ref);
    if (idx < 0) {
      errors.push(`replace: unknown object ${JSON.stringify(ref)}`);
      continue;
    }
    const target = working.objects[idx];
    const next = cloneObject(raw);
    delete next.index;
    // A replacement keeps the id of what it replaces: that keeps the history readable and id
    // references in later patches (and the studio) stable.
    if (target?.id) next.id = target.id;
    const shapeError = checkObjectShape(next);
    if (shapeError) {
      errors.push(`replace ${JSON.stringify(ref)}: ${shapeError}`);
      continue;
    }
    const objects = working.objects.slice();
    objects[idx] = next;
    commit(objects, { op: 'replace', id: next.id, type: next.type, index: idx });
  }

  // ---- adds ----
  for (const raw of adds) {
    if (!raw || typeof raw !== 'object') {
      errors.push('add: not an object');
      continue;
    }
    const next = cloneObject(raw);
    if (next.id === undefined) next.id = `ai-${working.objects.length + 1}`;
    const shapeError = checkObjectShape(next);
    if (shapeError) {
      errors.push(`add ${next.id ? JSON.stringify(next.id) : ''}: ${shapeError}`);
      continue;
    }
    if (working.objects.some((o) => o && o.id === next.id)) {
      errors.push(`add: duplicate id ${JSON.stringify(next.id)}`);
      continue;
    }
    if (working.objects.length + 1 > maxObjects) {
      errors.push(`add: would exceed the ${maxObjects}-object limit`);
      continue;
    }
    commit(working.objects.concat([next]), { op: 'add', id: next.id, type: next.type, index: working.objects.length });
  }

  if (!changes.length) {
    // Nothing was requested at all: the critic is happy with the current version. That is not an
    // error, and the report says so ("правок не предложено"), unlike a patch that was rejected.
    const requested = adds.length + replaces.length + removes.length;
    const empty = requested === 0;
    return {
      spec: base,
      applied: false,
      empty,
      errors: errors.length ? errors : empty ? [] : ['patch contains no valid operations'],
      changes,
    };
  }
  return { spec: normalizeSpec(working), applied: true, empty: false, errors, changes };
}

/**
 * Full objective + critic picture of one version, without touching the network.
 * Exported because the studio and the CLI show it; the loop uses it internally.
 *
 * @param {object} spec
 * @param {object} [options] forwarded to `analyzeSpec`
 * @returns {{analysis: object, score: number, sev: object}}
 */
export function evaluateSpec(spec, options = {}) {
  const analysis = analyzeSpec(spec, options);
  return {
    analysis,
    score: heuristicScore(analysis),
    sev: analysis.metrics.counts,
  };
}

/**
 * Fallback quality score (0..100) used when the critic gives no usable scores. It is intentionally
 * simple and deterministic: completeness rewards, defect penalties. The critic's own score always
 * wins when it is available.
 *
 * @param {object} analysis result of `analyzeSpec`
 * @returns {number}
 */
export function heuristicScore(analysis) {
  const m = analysis?.metrics || {};
  let score = 30;
  score += Math.min(24, (m.variety || 0) * 3);
  score += Math.min(18, (m.objects || 0) * 1.2);
  score += m.hasDoor ? 4 : 0;
  score += m.hasWindow ? 4 : 0;
  score += m.hasRoof ? 6 : 0;
  score += m.roofOverhang ? 2 : 0;
  score += Math.min(6, (m.rooms || 0) * 2);
  score -= (m.counts?.critical || 0) * 22;
  score -= (m.counts?.major || 0) * 7;
  score -= (m.counts?.minor || 0) * 1.5;
  return Math.max(0, Math.min(100, Math.round(score)));
}

/** Stable numeric score for one history entry (critic score when known, fallback otherwise). */
function entryScore(entry) {
  return Number.isFinite(entry?.score) ? entry.score : 0;
}

/**
 * Picks the version worth shipping: the highest score; ties go to the cleaner version (fewer
 * critical+major issues) and then to the earlier iteration, because extra patches that fix nothing
 * are not progress.
 *
 * @param {Array<object>} history result of `runBuildLoop`
 * @returns {object|null} the winning history entry (with `.spec`), or null for an empty history
 */
export function pickBest(history) {
  const entries = Array.isArray(history) ? history.filter((h) => h && Number.isFinite(h.score)) : [];
  if (!entries.length) return null;
  let best = entries[0];
  const severe = (h) => (h.issues || []).filter(isSevere).length;
  for (const entry of entries.slice(1)) {
    if (entry.score > best.score) best = entry;
    else if (entry.score === best.score) {
      if (severe(entry) < severe(best)) best = entry;
      // otherwise keep the earlier entry (stable: no replacement on a tie)
    }
  }
  return best;
}

/** Russian plural helper for "итерация/итерации/итераций". */
function plural(n, one, few, many) {
  const a = Math.abs(n) % 100;
  const b = a % 10;
  if (a > 10 && a < 20) return many;
  if (b === 1) return one;
  if (b >= 2 && b <= 4) return few;
  return many;
}

/** Russian text for one issue, safe inside a markdown table cell. */
function issueLine(issue) {
  const sev = issue?.severity || 'minor';
  const where = issue?.where && issue.where !== 'spec' ? ` (${issue.where})` : '';
  return `${sev}: ${issue?.what || '—'}${where}`;
}

/** One-line Russian summary of what happened in an iteration. */
function summarizeChanges(entry) {
  const patch = entry.patch || {};
  const changes = Array.isArray(patch.changes) ? patch.changes : [];
  if (patch.applied && changes.length) {
    return changes.map(describeChange).join('; ');
  }
  if (patch.applied === false && (patch.errors?.length || 0) > 0) {
    return `правки отклонены: ${patch.errors[0]}`;
  }
  // An empty patch is the critic saying "this is good": say that, not "rejected".
  return 'критик не предложил правок';
}

/**
 * Markdown report in Russian, meant for the user (and for the blog post about the experiment).
 * Every iteration is present twice: as a table row and as its own section.
 *
 * @param {object} params
 * @param {string} params.prompt
 * @param {object} params.spec final spec
 * @param {Array<object>} params.history
 * @param {object} [params.best] entry from `pickBest`; computed from history when omitted
 * @param {string} [params.stopReason]
 * @param {boolean} [params.rendering] whether renders were available at all
 * @param {object} [params.thresholds]
 * @param {string} [params.buildId]
 * @param {object} [params.usage]
 * @returns {string}
 */
export function renderReport({
  prompt = '',
  spec,
  history = [],
  best = null,
  stopReason = '',
  rendering = true,
  thresholds = DEFAULT_THRESHOLDS,
  buildId = '',
  usage = null,
}) {
  const normalized = normalizeSpec(spec || {});
  const bounds = specBounds(normalized);
  const winner = best || pickBest(history);
  const finalScore = Number.isFinite(winner?.score) ? winner.score : null;
  const lines = [];

  lines.push(`# Отчёт о постройке${normalized.name ? `: ${normalized.name}` : ''}`);
  lines.push('');
  if (buildId) lines.push(`**Сборка:** \`${buildId}\`  `);
  lines.push(`**Запрос:** ${prompt || '—'}  `);
  lines.push(
    `**Итераций:** ${history.length} (лимит ${thresholds?.maxIterations ?? '—'}) — ` +
      `${stopReason || 'остановка по лимиту'}.  `,
  );
  lines.push(
    `**Итоговая модель:** ${bounds.size[0]} × ${bounds.size[1]} × ${bounds.size[2]} блоков (Ш×В×Г), ` +
      `${normalized.objects.length} объектов, ${Object.keys(normalized.palette || {}).length} цветов палитры.  `,
  );
  if (winner) {
    lines.push(`**Лучшая версия:** итерация ${winner.iteration} — ${winner.score}/100.  `);
  }
  lines.push(`**Рендеры:** ${rendering ? 'использовались' : 'недоступны — работали только объективные проверки'}.  `);
  if (usage && Number.isFinite(usage.calls) && usage.calls > 0) {
    lines.push(`**Запросов к модели:** ${usage.calls}, токенов: ${usage.promptTokens || 0} + ${usage.completionTokens || 0}.  `);
  }
  lines.push('');

  // ---- table of iterations ---------------------------------------------------------------
  lines.push('## Итерации');
  lines.push('');
  if (!history.length) {
    lines.push('_Итераций не было: цикл завершился до первой оценки._');
    lines.push('');
  } else {
    lines.push('| Итерация | Оценка | Прирост | Критично | Серьёзно | Мелочи | Правки | Что изменилось |');
    lines.push('|---:|---:|---:|---:|---:|---:|---|---|');
    for (const h of history) {
      const sev = h.sev || severityCounts(h.issues || []);
      const gain = Number.isFinite(h.scoreGain) ? `${h.scoreGain > 0 ? '+' : ''}${h.scoreGain}` : '—';
      const applied = h.patch?.applied ? 'применены' : h.patch?.errors?.length ? 'отклонены' : 'не нужны';
      lines.push(
        `| ${h.iteration} | ${Number.isFinite(h.score) ? h.score : '—'} | ${gain} | ${sev.critical ?? 0} | ` +
          `${sev.major ?? 0} | ${sev.minor ?? 0} | ${applied} | ${escapeCell(summarizeChanges(h))} |`,
      );
    }
    lines.push('');
  }

  // ---- per-iteration detail --------------------------------------------------------------
  if (history.length) {
    lines.push('## Что происходило по итерациям');
    lines.push('');
    for (const h of history) {
      const score = Number.isFinite(h.score) ? `${h.score}/100` : 'оценки нет';
      lines.push(`### Итерация ${h.iteration} — ${score}`);
      lines.push('');
      const m = h.metrics || {};
      const size = m.size ? `${m.size.width} × ${m.size.height} × ${m.size.depth}` : '—';
      lines.push(`- Размер: ${size} блоков; объектов: ${m.objects ?? '—'}; типов: ${m.variety ?? '—'}; этажей: ${m.storeys ?? '—'}.`);
      if (m.openings) lines.push(`- Проёмы: двери — ${m.openings.door}, окна — ${m.openings.window}, арки — ${m.openings.arch}.`);
      if (m.hasRoof) lines.push(`- Крыша: есть, покрытие ${m.roofCoverage} кл.${m.roofOverhang ? ', со свесом' : ''}.`);
      lines.push(`- Объективные проверки: ${h.analysisOk ? 'чисто' : 'есть замечания'}.`);
      const issues = h.issues || [];
      if (issues.length) {
        lines.push('- Замечания:');
        for (const issue of issues.slice(0, 8)) lines.push(`  - ${issueLine(issue)}`);
        if (issues.length > 8) lines.push(`  - … ещё ${issues.length - 8}`);
      } else {
        lines.push('- Замечаний нет.');
      }
      const changes = h.patch?.changes || [];
      if (h.patch?.applied && changes.length) {
        lines.push(`- Изменения: ${changes.map(describeChange).join('; ')}.`);
      } else if (h.patch?.errors?.length) {
        lines.push(`- Патч отклонён: ${h.patch.errors.slice(0, 4).join('; ')}.`);
      } else {
        lines.push('- Изменений не вносилось: критик не предложил правок.');
      }
      if (h.verdict) lines.push(`- Вердикт критика: ${escapeCell(h.verdict)}`);
      lines.push(`- Почему: ${escapeCell(h.why || '—')}`);
      lines.push('');
    }
  }

  // ---- winner + final state --------------------------------------------------------------
  if (winner && winner.spec) {
    const wb = specBounds(winner.spec);
    lines.push('## Версия для переноса в Minecraft');
    lines.push('');
    lines.push(
      `Выбрана итерация **${winner.iteration}** (оценка ${winner.score}/100, ` +
        `${winner.spec.objects?.length ?? 0} объектов, ${wb.size.join(' × ')} блоков).`,
    );
    lines.push('');
    if (history.length && winner.iteration !== history[history.length - 1].iteration) {
      lines.push('Последняя итерация оказалась не лучшей — цикл откатывается к лучшей версии при переносе.');
      lines.push('');
    }
  }

  lines.push('## Объекты финальной модели');
  lines.push('');
  if (!normalized.objects.length) lines.push('_Список пуст._');
  for (const o of normalized.objects.slice(0, 200)) {
    const id = o.id ? `\`${o.id}\`` : '—';
    const where = o.from && o.to ? ` ${o.from.join(',')} → ${o.to.join(',')}` : o.at ? ` at ${o.at.join(',')}` : '';
    lines.push(`- ${id} — ${o.type}${o.material ? ` (${o.material})` : ''}${where}`);
  }
  if (normalized.objects.length > 200) lines.push(`- … ещё ${normalized.objects.length - 200}`);
  lines.push('');

  const remaining = history.length ? history[history.length - 1].issues || [] : [];
  const serious = remaining.filter(isSevere);
  lines.push('## Что осталось');
  lines.push('');
  if (!serious.length) lines.push('- Серьёзных замечаний не осталось.');
  else for (const issue of serious.slice(0, 10)) lines.push(`- ${issueLine(issue)}`);
  if (!rendering) lines.push('- Рендеры были недоступны: критик не видел картинок, качество оценено только по спецификации.');
  lines.push(
    '- Это экспериментальная сборка: геометрия, рендер и перенос в Minecraft проверяются отдельными модулями.',
  );
  lines.push('');
  return lines.join('\n');
}

/** Escapes a string for a markdown table cell / inline text. */
function escapeCell(text) {
  return String(text ?? '')
    .replace(/\|/g, '/')
    .replace(/\r?\n/g, ' ')
    .trim();
}

/** @deprecated alias kept for symmetry with older imports; use `renderReport`. */
export const buildReport = renderReport;

/** Normalises the options object accepted by `runBuildLoop`. */
function normalizeLoopOptions(options) {
  const opts = options && typeof options === 'object' ? options : {};
  const rawThresholds = opts.thresholds && typeof opts.thresholds === 'object' ? opts.thresholds : {};
  const thresholds = { ...DEFAULT_THRESHOLDS, ...rawThresholds };
  thresholds.render = { ...DEFAULT_THRESHOLDS.render, ...(rawThresholds.render || {}) };
  const iterations = Number.isFinite(Number(opts.iterations)) ? Math.floor(Number(opts.iterations)) : 8;
  return {
    ...opts,
    thresholds,
    // One evaluation of the initial spec always happens: iteration 1 is the "as designed" version.
    iterations: Math.max(1, Math.min(50, iterations)),
  };
}

/** Normalises whatever a renderer returned into `{views, error}`. */
function normalizeViews(result) {
  const raw = Array.isArray(result) ? result : result?.views;
  const views = (Array.isArray(raw) ? raw : [])
    .filter((v) => v && typeof v === 'object')
    .map((v) => ({
      name: typeof v.name === 'string' && v.name ? v.name : 'view',
      png: Buffer.isBuffer(v.png) ? v.png : null,
      camera: v.camera ?? null,
    }));
  return views;
}

/** Renders one spec, degrading to an empty view list on any failure. */
async function renderSpecVersion(spec, { render, sceneBuilder, renderOptions, maxImages }) {
  const started = Date.now();
  const fail = (error) => ({ views: [], ms: Date.now() - started, error, scene: null });

  if (render === null || render === false) return fail('rendering disabled');
  let scene = null;
  if (typeof sceneBuilder === 'function') {
    try {
      scene = sceneBuilder(spec);
    } catch (e) {
      return fail(`scene build failed: ${e?.message || e}`);
    }
  } else {
    const geometry = await optionalImport('./geometry.mjs');
    if (!geometry || typeof geometry.buildScene !== 'function') return fail('geometry module is not available');
    try {
      scene = geometry.buildScene(spec);
    } catch (e) {
      return fail(`scene build failed: ${e?.message || e}`);
    }
  }

  const renderer = typeof render === 'function' ? render : await defaultRenderFn();
  if (typeof renderer !== 'function') return fail('render module is not available');
  try {
    const result = await renderer(scene, { ...renderOptions, spec, maxImages });
    const views = normalizeViews(result);
    if (!views.length) return fail(result?.note || result?.error || 'renderer returned no views');
    return { views, ms: Date.now() - started, error: null, scene };
  } catch (e) {
    return fail(`render failed: ${e?.message || e}`);
  }
}

/** The built-in renderer (task B), resolved lazily. */
async function defaultRenderFn() {
  const render = await optionalImport('./render.mjs');
  return render && typeof render.renderViews === 'function' ? render.renderViews : null;
}

/** Tries to design the first spec when the caller passes no `spec0` (uses generate.mjs when present). */
async function designInitialSpec({ prompt, llm }) {
  if (typeof llm !== 'function') return null;
  const generate = await optionalImport('./generate.mjs');
  if (!generate || typeof generate.initialPrompt !== 'function' || typeof generate.SPEC_SYSTEM !== 'string') {
    return null;
  }
  try {
    const res = await llm({ system: generate.SPEC_SYSTEM, user: generate.initialPrompt(prompt), json: true, images: [] });
    const text = res?.content;
    if (!text) return null;
    let parsed = text;
    if (typeof text === 'string') {
      const criticMod = await optionalImport('./critic.mjs');
      parsed = criticMod?.parseCritiqueJson ? criticMod.parseCritiqueJson(text) : JSON.parse(text);
    }
    const spec = normalizeSpec(parsed?.spec && Array.isArray(parsed.spec.objects) ? parsed.spec : parsed);
    if (!Array.isArray(spec.objects)) return null;
    if (spec.objects.length === 0) return null;
    return spec;
  } catch {
    return null;
  }
}

/** Adds LLM usage counters. */
function addUsage(total, usage) {
  if (!usage || typeof usage !== 'object') return total;
  total.calls += 1;
  total.promptTokens += Number(usage.prompt_tokens ?? usage.promptTokens) || 0;
  total.completionTokens += Number(usage.completion_tokens ?? usage.completionTokens) || 0;
  return total;
}

/**
 * Runs the iterative build loop.
 *
 * @param {object} options
 * @param {string} options.prompt the user request ("средневековый дом 10 на 10")
 * @param {object} [options.spec0] starting StructureSpec; designed with the llm when omitted
 * @param {number} [options.iterations=8] maximum number of evaluated versions
 * @param {(req: {system:string,user:string,json:boolean,images:Buffer[]}) => Promise<{content:string,usage?:object}>} options.llm
 * @param {((scene: object, opts: object) => Promise<{views: Array<{name:string,png:Buffer}>} | Array<{name:string,png:Buffer}>>|null)} [options.render]
 *        renderer; omit for the built-in software renderer, pass `null` to disable rendering
 * @param {(spec: object) => object} [options.sceneBuilder] scene builder override (default: geometry.buildScene)
 * @param {object} [options.thresholds] see DEFAULT_THRESHOLDS
 * @param {string} [options.buildId] optional id, only echoed in the report
 * @param {(msg: string) => void} [options.log] progress logger
 * @returns {Promise<{spec: object, history: object[], best: object|null, report: string, stopReason: string, stopCode: 'iteration-cap'|'converged', thresholds: object, usage: object, rendering: boolean}>}
 *   `spec` is the final version the loop ended on (history[last].spec), `best` the version to ship.
 *
 * @example
 * const llm = createLlm();
 * const { best, report } = await runBuildLoop({ prompt: 'дом 10 на 10', llm, iterations: 8 });
 */
export async function runBuildLoop(options = {}) {
  const opts = normalizeLoopOptions(options);
  const {
    prompt = '',
    iterations,
    llm,
    render,
    sceneBuilder,
    thresholds,
    buildId = '',
    log = () => {},
  } = opts;

  if (typeof prompt !== 'string' || !prompt.trim()) {
    throw new TypeError('runBuildLoop: prompt must be a non-empty string');
  }

  const history = [];
  const usage = { calls: 0, promptTokens: 0, completionTokens: 0 };
  const requestedSize = parseRequestedSize(prompt);

  // ---- initial version --------------------------------------------------------------------
  let spec;
  if (opts.spec0 && typeof opts.spec0 === 'object') {
    spec = normalizeSpec(cloneSpec(opts.spec0));
  } else if (opts.spec && typeof opts.spec === 'object') {
    spec = normalizeSpec(cloneSpec(opts.spec));
  } else {
    const designed = await designInitialSpec({ prompt, llm });
    spec = designed || normalizeSpec({ name: prompt.slice(0, 60) || 'structure', objects: [], notes: prompt });
    if (designed) log(`runBuildLoop: designed the initial spec (${spec.objects.length} objects)`);
  }

  const thresholdsWithCap = { ...thresholds, maxIterations: iterations };
  let stopReason = 'достигнут лимит итераций';
  let stopCode = 'iteration-cap';
  let renderingAvailable = false;
  let stalled = 0;
  let previousScore = null;
  const stalledIterations = Math.max(1, Number(thresholds.stallIterations) || DEFAULT_THRESHOLDS.stallIterations);
  const requiredGain = Number.isFinite(Number(thresholds.scoreGain)) ? Number(thresholds.scoreGain) : DEFAULT_THRESHOLDS.scoreGain;
  const minScore = Number.isFinite(Number(thresholds.minScore)) ? Number(thresholds.minScore) : 0;

  for (let iteration = 1; iteration <= iterations; iteration++) {
    const evaluated = normalizeSpec(cloneSpec(spec));

    // 1. objective analysis of the spec (no rendering, always available)
    const analysis = analyzeSpec(evaluated, { request: prompt });

    // 2. renders
    const rendered = await renderSpecVersion(evaluated, {
      render,
      sceneBuilder,
      renderOptions: { ...thresholds.render },
      maxImages: thresholds.maxImages,
    });
    if (rendered.views.length) renderingAvailable = true;

    // 3. AI critique (never throws; a failure degrades to the objective findings)
    const critique = await critiqueSpec({
      spec: evaluated,
      views: rendered.views,
      analysis,
      llm,
      prompt,
      iteration,
      history,
      maxImages: thresholds.maxImages,
      maxOps: thresholds.patchOps,
    });
    addUsage(usage, critique.usage);

    // 4. merge analyzer + critic findings (analyzer facts first, duplicates removed)
    const objectiveIssues = (analysis.issues || []).map((i) => ({ ...i, source: 'analysis' }));
    const criticIssues = (critique.issues || []).map((i) => ({ ...i, source: i.source || 'critic' }));
    const issues = sortIssues(dedupeIssues([...objectiveIssues, ...criticIssues]));

    // 5. patch
    const patchInput = critique.patch || { add: [], replace: [], remove: [] };
    const applied = applySpecPatch(evaluated, patchInput, { maxObjects: thresholds.maxObjects });
    const patch = {
      add: patchInput.add || [],
      replace: patchInput.replace || [],
      remove: patchInput.remove || [],
      applied: applied.applied,
      errors: applied.errors || [],
      changes: applied.changes || [],
    };

    // 6. score: the critic's average is authoritative; otherwise a deterministic heuristic
    const criticScore = Number.isFinite(critique.score) ? critique.score : null;
    const score = criticScore !== null ? criticScore : heuristicScore(analysis);
    const scoreGain = previousScore === null ? score : Math.round((score - previousScore) * 10) / 10;
    previousScore = score;

    // 7. what happened and why, in Russian (used by the report and by the studio)
    const sev = severityCounts(issues);
    const clean = isClean(issues);
    const hasSerious = !clean;
    const whyParts = [];
    if (applied.applied && applied.changes.length) {
      whyParts.push(`применён патч: ${applied.changes.map(describeChange).join(', ')}`);
    } else if (applied.errors.length) {
      whyParts.push(`патч отклонён (${applied.errors[0]})`);
    } else {
      whyParts.push('критик не предложил правок');
    }
    whyParts.push(
      hasSerious
        ? `у критика замечания: критичных — ${sev.critical}, серьёзных — ${sev.major}, мелких — ${sev.minor}`
        : 'критик не нашёл критичных и серьёзных замечаний',
    );
    whyParts.push(analysis.ok ? 'объективные проверки чисты' : `объективных замечаний: ${analysis.issues.length}`);
    if (criticScore === null) whyParts.push('оценка по эвристике (критик не дал баллов)');
    if (rendered.error && !rendered.views.length) whyParts.push(`без рендера: ${rendered.error}`);
    const why = whyParts.join('; ');

    history.push({
      iteration,
      spec: evaluated,
      metrics: analysis.metrics,
      issues,
      patch,
      score,
      why,
      scoreGain,
      verdict: critique.verdict || '',
      sev,
      analysisOk: analysis.ok,
      criticFailed: Boolean(critique.failed),
      criticError: critique.error || null,
      views: rendered.views.map((v) => v.name),
      renderMs: rendered.ms,
      renderError: rendered.error,
      usage: critique.usage || null,
      scores: critique.scores || null,
    });

    log(
      `iteration ${iteration}/${iterations}: score ${score}` +
        `${criticScore === null ? ' (heuristic)' : ''}, issues ${sev.critical}c/${sev.major}m/${sev.minor}min, ` +
        `patch ${applied.applied ? `${applied.changes.length} applied` : 'rejected'}, views ${rendered.views.length}`,
    );

    // 8. stopping rule
    const gainIsLow = scoreGain < requiredGain;
    if (gainIsLow) stalled += 1;
    else stalled = 0;
    const canStop = clean && analysis.ok && score >= minScore;
    if (canStop && stalled >= stalledIterations) {
      stopCode = 'converged';
      stopReason =
        `критик не нашёл критичных и серьёзных замечаний, а ${stalledIterations} ` +
        `${plural(stalledIterations, 'последняя итерация', 'последние итерации', 'последних итераций')} ` +
        `подряд дали прирост меньше ${requiredGain} баллов`;
      break;
    }

    // 9. next version (a rejected patch leaves the current spec in place)
    if (applied.applied) spec = applied.spec;
  }

  const best = pickBest(history);
  const finalSpec = history.length ? history[history.length - 1].spec : spec;
  const report = renderReport({
    prompt,
    spec: finalSpec,
    history,
    best,
    stopReason,
    rendering: renderingAvailable,
    thresholds: thresholdsWithCap,
    buildId,
    usage,
  });

  return {
    spec: finalSpec,
    history,
    best,
    report,
    // extras (safe to ignore): diagnostics for the CLI/studio
    stopReason,
    stopCode,
    thresholds: thresholdsWithCap,
    usage,
    rendering: renderingAvailable,
    requestedSize,
  };
}

export { analyzeSpec, critiqueSpec, severityCounts, isClean, averageScores, specDigest, specBounds };
