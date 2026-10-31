/**
 * AI critic: the "eyes" of the loop.
 *
 * A rendered iteration is described to a vision model together with the objective analysis, and the
 * model answers with issues, per-category scores, a verdict and a patch (add / replace / remove
 * objects). This module owns the prompt, the tolerant parsing of the model's answer and the
 * normalisation of that answer into a shape the loop can trust; it never renders and never applies a
 * patch (loop.mjs does that, so a bad patch is rejected in one place).
 *
 * Contract:
 *   critiqueSpec({ spec, views, analysis, llm }) -> { issues, scores, verdict, patch }
 *
 * The `llm` function is injected: `async ({system, user, json, images}) => ({content, usage})`.
 * Any failure of the model is turned into a text-only fallback critique (issues taken from the
 * objective analysis) instead of an exception, because one bad answer must not end a long build loop.
 *
 * A positional call `critiqueSpec(spec, views, analysis, {llm})` is also accepted for compatibility
 * with the short form in AGENTS.md; the object form is preferred.
 */
import { normalizeSpec, specDigest, specBounds, OBJECT_TYPES } from './spec.mjs';

/** System prompt of the critic (stable, so provider prompt caching can kick in). */
export const CRITIC_SYSTEM = `You are AIBuild's architectural critic. You are shown renders of a Minecraft-scale
building designed as a declarative 3D scene (StructureSpec), plus an objective analysis produced by code.
You must do what the code cannot: judge the design as an architect looking at the pictures.

# WHAT TO JUDGE
- silhouette and proportions (massing, roof shape, height against footprint);
- roof: is it complete, does it overhang, does the ridge close, is the pitch believable;
- facade depth and rhythm: frames, sills, cornices, plinths, timber framing, recesses;
- openings: is the entrance obvious, are windows aligned and evenly spaced, are there blank walls;
- style fidelity: does the result read as the requested building, palette and period;
- broken or empty things: floating parts, gaps, intersecting geometry, a flat "just a box" look,
  details that are present as objects but invisible in the renders.
The objective analysis lists facts found in the spec (missing door, missing roof, size mismatch, ...).
Treat those as true. Do not repeat an analyzer issue in your own list unless you can add a visual
observation to it.

# ANSWER FORMAT
Answer with ONE JSON object, no prose, no markdown:
{
  "issues": [{"severity": "critical|major|minor", "what": "...", "where": "..."}],
  "scores": {"shape": 0-10, "detail": 0-10, "style": 0-10, "structure": 0-10, "fidelity": 0-10},
  "verdict": "one sentence",
  "patch": {"add": [OBJ, ...], "replace": [OBJ, ...], "remove": ["id-or-index", ...]}
}
Rules:
- severity: "critical" = the build is broken or does not answer the request; "major" = clearly visible
  architectural defect; "minor" = polish.
- "what" and "where" must be written in RUSSIAN (the report is shown to a Russian-speaking user);
  "where" names a facade/view/object id ("южный фасад", "крыша", "#roof").
- scores are integers 0..10; be honest, most first drafts land at 3..6.
- patch: use object ids from the spec for remove/replace; an integer index from 0 is also accepted.
  If you list ANY critical or major issue you MUST include the patch that fixes it in the same answer —
  an issue without a fix is wasted work. Empty patch is allowed ONLY when everything is already good.
- Prefer several small, surgical ops (move a window, add a sill, enlarge the roof overhang, add a
  bargeboard, swap a material) over rebuilding objects; the loop shows you the same object ids again.
- Every added/replaced object must be a valid object (see the type list in the message) with integer
  coordinates that sit on or against the existing geometry.
  Replaced objects keep their id. Added objects get a short unique id and follow the spec format.
  Only include a patch entry when it fixes an issue you listed. An empty patch is valid when the
  design is already good.
- Keep what already works: never rewrite the whole building in one patch. At most 12 operations.
- Coordinates are integers, y=0 is the ground, +x east, +z south, one unit = one block.`;

/** Severity values the rest of the pipeline understands. */
export const SEVERITIES = /** @type {const} */ (['critical', 'major', 'minor']);

/** Score categories asked from the model. */
export const SCORE_KEYS = /** @type {const} */ (['shape', 'detail', 'style', 'structure', 'fidelity']);

/** Coerces any severity spelling an LLM may produce to critical|major|minor. */
export function normalizeSeverity(value) {
  const s = String(value ?? '').toLowerCase().trim();
  if (s.startsWith('crit') || s === 'blocker' || s === 'high' || s === 'fatal' || s === 'error') return 'critical';
  if (s.startsWith('maj') || s === 'medium' || s === 'serious' || s === 'moderate' || s === 'warn' || s === 'warning') return 'major';
  return 'minor';
}

/** Counts issues by severity. */
export function severityCounts(issues = []) {
  const out = { critical: 0, major: 0, minor: 0 };
  for (const issue of issues || []) {
    const s = normalizeSeverity(issue?.severity);
    out[s]++;
  }
  out.total = out.critical + out.major + out.minor;
  return out;
}

/** True when there is nothing critical or major left. */
export function isClean(issues = []) {
  const c = severityCounts(issues);
  return c.critical === 0 && c.major === 0;
}

/**
 * Average of the per-category scores on the 0..10 scale, or null when the model gave none.
 * Unknown categories and non-finite values are ignored.
 */
export function averageScores(scores) {
  if (!scores || typeof scores !== 'object') return null;
  const values = SCORE_KEYS.map((k) => Number(scores[k])).filter((n) => Number.isFinite(n) && n >= 0 && n <= 10);
  if (!values.length) return null;
  return Math.round((values.reduce((a, b) => a + b, 0) / values.length) * 10) / 10;
}

/** Converts per-category 0..10 scores into the loop's 0..100 quality score. */
export function scoreFromCritique(critique) {
  const avg = averageScores(critique?.scores);
  return avg === null ? null : Math.round(avg * 10);
}

/** Clamps every known score into 0..10, dropping the rest. Returns null for an empty result. */
export function normalizeScores(raw) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null;
  /** @type {Record<string, number>} */
  const out = {};
  for (const key of SCORE_KEYS) {
    const n = Number(raw[key]);
    if (Number.isFinite(n)) out[key] = Math.max(0, Math.min(10, Math.round(n * 10) / 10));
  }
  // Keep extra numeric buckets the model may invent, but only when they look like a 0..10 score.
  for (const [key, value] of Object.entries(raw)) {
    if (SCORE_KEYS.includes(key)) continue;
    const n = Number(value);
    if (Number.isFinite(n) && n >= 0 && n <= 10 && /^[a-z][a-z0-9_-]{0,23}$/i.test(key)) {
      out[key] = Math.round(n * 10) / 10;
    }
  }
  return Object.keys(out).length ? out : null;
}

/** Normalises one issue; returns null when it carries no usable description. */
export function normalizeIssue(raw, index = 0) {
  if (!raw || typeof raw !== 'object') {
    if (typeof raw === 'string' && raw.trim()) return { severity: 'minor', what: raw.trim(), where: 'spec' };
    return null;
  }
  const what = cleanText(raw.what ?? raw.text ?? raw.issue ?? raw.message ?? raw.description, 600);
  if (!what) return null;
  const where = cleanText(raw.where ?? raw.location ?? raw.object ?? raw.element ?? raw.id, 200) || 'spec';
  return {
    severity: normalizeSeverity(raw.severity ?? raw.level ?? raw.priority),
    what,
    where,
    ...(raw.code ? { code: cleanText(raw.code, 60) } : {}),
    ...(raw.view ? { view: cleanText(raw.view, 60) } : {}),
    index,
  };
}

/** Single-line, length-limited string (report tables cannot survive newlines or pipes). */
function cleanText(value, max) {
  if (typeof value !== 'string' && typeof value !== 'number') return '';
  return String(value).replace(/\s+/g, ' ').replace(/\|/g, '/').trim().slice(0, max);
}

/** Patch object with the three arrays the loop understands. */
function emptyPatch() {
  return { add: [], replace: [], remove: [] };
}

/** True for a plain object that could be a spec object. */
function isPlainObject(value) {
  return !!value && typeof value === 'object' && !Array.isArray(value);
}

/** Removes functions/symbols and normalises ids; used on every model-provided object. */
function cleanObject(obj) {
  if (!isPlainObject(obj)) return null;
  let json;
  try {
    json = JSON.parse(JSON.stringify(obj, (key, value) => (typeof value === 'function' || typeof value === 'symbol' ? undefined : value)));
  } catch {
    return null;
  }
  if (json.id !== undefined) {
    const id = typeof json.id === 'number' ? String(json.id) : json.id;
    if (typeof id === 'string' && id.trim()) json.id = id.trim().slice(0, 64);
    else delete json.id;
  }
  return json;
}

/**
 * Turns whatever the model returned into a patch with at most `maxOps` operations.
 * Only the shape is sanitised here; whether an operation is legal for the current spec (does the id
 * exist, is the box valid, does the result validate) is decided by `applySpecPatch` in loop.mjs.
 *
 * @param {any} raw
 * @param {{maxOps?: number}} [options]
 * @returns {{patch: {add: object[], replace: object[], remove: Array<string|number>}, dropped: string[]}}
 */
export function normalizePatch(raw, { maxOps = 40 } = {}) {
  const patch = emptyPatch();
  const dropped = [];
  if (!isPlainObject(raw)) {
    if (raw !== undefined && raw !== null) dropped.push('patch is not an object');
    return { patch, dropped };
  }
  const budget = { left: Math.max(0, maxOps) };

  const take = (list, kind) => {
    if (list === undefined || list === null) return;
    if (!Array.isArray(list)) {
      dropped.push(`${kind}: not an array`);
      return;
    }
    for (const item of list) {
      if (budget.left <= 0) {
        dropped.push(`${kind}: over the ${maxOps}-operation limit`);
        continue;
      }
      if (kind === 'remove') {
        const ref = isPlainObject(item) ? item.id ?? item.index : item;
        if (typeof ref === 'string' && ref.trim()) {
          patch.remove.push(ref.trim().slice(0, 64));
          budget.left--;
        } else if (Number.isInteger(ref) && ref >= 0) {
          patch.remove.push(ref);
          budget.left--;
        } else {
          dropped.push('remove: not an id or index');
        }
        continue;
      }
      const obj = cleanObject(item);
      if (!obj) {
        dropped.push(`${kind}: not an object`);
        continue;
      }
      if (typeof obj.type !== 'string' || !obj.type.trim()) {
        dropped.push(`${kind}: object without type`);
        continue;
      }
      if (!OBJECT_TYPES.includes(obj.type)) {
        dropped.push(`${kind}: unknown type '${String(obj.type).slice(0, 40)}'`);
        continue;
      }
      if (kind === 'replace' && obj.id === undefined && !Number.isInteger(obj.index)) {
        dropped.push('replace: object without id or index');
        continue;
      }
      if (Number.isInteger(obj.index) && obj.id === undefined) {
        // keep the index, drop it from the object body (it is not a spec field)
        delete obj.index;
      }
      patch[kind].push(obj);
      budget.left--;
    }
  };

  take(raw.add, 'add');
  take(raw.replace, 'replace');
  take(raw.remove, 'remove');
  return { patch, dropped };
}

/**
 * Tolerant JSON extraction for model answers: fences, prose before/after, trailing commas and a
 * truncated final object. Mirrors the behaviour of llm.mjs's parser, duplicated here on purpose so
 * the critic has no dependency on the provider layer.
 * @param {string} text
 * @returns {any}
 */
export function parseCritiqueJson(text) {
  if (typeof text !== 'string' || !text.trim()) throw new Error('empty critique answer');
  let s = text.trim();
  const fence = s.indexOf('```');
  if (fence >= 0) {
    const nl = s.indexOf('\n', fence);
    const end = s.indexOf('```', nl < 0 ? fence + 3 : nl);
    if (nl >= 0) s = end > nl ? s.slice(nl + 1, end) : s.slice(nl + 1);
  }
  const start = Math.min(...[s.indexOf('{'), s.indexOf('[')].filter((i) => i >= 0));
  if (!Number.isFinite(start)) throw new Error('no JSON in the critique answer');
  s = s.slice(start);
  try {
    return parseLeadingJson(s);
  } catch {
    /* try repairs below */
  }
  const noTrailing = s.replace(/,\s*([}\]])/g, '$1');
  try {
    return JSON.parse(noTrailing);
  } catch {
    /* try to close a truncated answer */
  }
  const closed = closeTruncatedJson(noTrailing);
  if (closed) {
    try {
      return JSON.parse(closed);
    } catch {
      /* give up */
    }
  }
  throw new Error('invalid JSON in the critique answer');
}

/** Parses one JSON value starting at index 0, ignoring prose after it. */
function parseLeadingJson(s) {
  let depth = 0;
  let inStr = false;
  let esc = false;
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    if (inStr) {
      if (esc) esc = false;
      else if (c === '\\') esc = true;
      else if (c === '"') inStr = false;
      continue;
    }
    if (c === '"') inStr = true;
    else if (c === '{' || c === '[') depth++;
    else if (c === '}' || c === ']') {
      depth--;
      if (depth === 0) return JSON.parse(s.slice(0, i + 1));
    }
  }
  throw new Error('unbalanced JSON');
}

/** Closes JSON cut off mid-stream at the last complete element. */
function closeTruncatedJson(s) {
  const stack = [];
  let inStr = false;
  let esc = false;
  let lastSafe = -1;
  let stackAtSafe = null;
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    if (inStr) {
      if (esc) esc = false;
      else if (c === '\\') esc = true;
      else if (c === '"') inStr = false;
      continue;
    }
    if (c === '"') inStr = true;
    else if (c === '{' || c === '[') stack.push(c);
    else if (c === '}' || c === ']') {
      stack.pop();
      if (stack.length === 0) return s.slice(0, i + 1);
      lastSafe = i + 1;
      stackAtSafe = [...stack];
    } else if (c === ',') {
      lastSafe = i;
      stackAtSafe = [...stack];
    }
  }
  if (lastSafe < 0 || !stackAtSafe) return null;
  let out = s.slice(0, lastSafe).trim();
  while (out.endsWith(':') || out.endsWith(',')) out = out.slice(0, -1);
  for (const open of stackAtSafe.reverse()) out += open === '{' ? '}' : ']';
  return out;
}

/**
 * Builds the user message for one critique: request, iteration, digest, analysis and a census of
 * the pictures. Kept as a separate export so the studio or a test can show a user exactly what the
 * model was asked.
 *
 * @param {object} params
 * @param {string} [params.prompt] original user request
 * @param {object} params.spec
 * @param {object} [params.analysis]
 * @param {string[]} [params.viewNames]
 * @param {number} [params.iteration]
 * @param {string} [params.history] short description of earlier iterations
 * @param {number} [params.maxSpecJsonChars]
 * @returns {string}
 */
export function buildCritiquePrompt({
  prompt = '',
  spec,
  analysis = null,
  viewNames = [],
  iteration = 1,
  history = '',
  maxSpecJsonChars = 24000,
}) {
  const normalized = spec && Array.isArray(spec.objects) ? spec : normalizeSpec(spec || {});
  const bounds = specBounds(normalized);
  const views = viewNames.length
    ? `Images attached in this order: ${viewNames.join(', ')}. They are renders of exactly this spec.`
    : 'No renders are available for this iteration (the renderer is unavailable). Critique the spec and the analysis only.';
  let specJson = '';
  try {
    specJson = JSON.stringify(normalized);
  } catch {
    specJson = '';
  }
  const specBlock = specJson && specJson.length <= maxSpecJsonChars
    ? `CURRENT SPEC (JSON):\n\`\`\`json\n${specJson}\n\`\`\``
    : 'CURRENT SPEC (too large to include in full; use the digest below):\n' + specDigest(normalized, 80);
  const analysisIssues = Array.isArray(analysis?.issues) ? analysis.issues : [];
  const analysisText = analysis
    ? JSON.stringify(
        {
          ok: analysis.ok,
          metrics: analysis.metrics,
          issues: analysisIssues.slice(0, 25),
        },
        null,
        1,
      )
    : 'not available';
  return `Review iteration ${iteration} of this build.
Original request: "${prompt || '(not given)'}"
Current size: ${bounds.size.join(' x ')} (x,y,z) blocks; objects: ${normalized.objects.length}.

${views}

${specBlock}

SPEC DIGEST:
${specDigest(normalized, 60)}

OBJECTIVE ANALYSIS (facts from code, not opinions):
${analysisText}
${history ? `\nWHAT CHANGED IN PREVIOUS ITERATIONS:\n${history}\n` : ''}
Look at the images as an architect. Return the JSON object described in the system message.`;
}

/**
 * Turns a parsed model answer into the critic result. Pure, exported for tests.
 *
 * @param {any} parsed decoded JSON answer
 * @param {{maxOps?: number, usage?: object}} [options]
 * @returns {{issues: object[], scores: object|null, verdict: string, patch: object, dropped: string[], score: number|null, sev: object, usage: object|null}}
 */
export function normalizeCritique(parsed, { maxOps = 40, usage = null } = {}) {
  const answer = isPlainObject(parsed) ? parsed : {};
  const rawIssues = Array.isArray(answer.issues) ? answer.issues : [];
  const issues = rawIssues.map((i, n) => normalizeIssue(i, n)).filter(Boolean);
  const scores = normalizeScores(answer.scores);
  const verdict = cleanText(answer.verdict ?? answer.summary ?? answer.note, 400);
  const { patch, dropped } = normalizePatch(answer.patch, { maxOps });
  const result = {
    issues,
    scores,
    verdict,
    patch,
    dropped,
    score: null,
    sev: severityCounts(issues),
    usage: usage || null,
  };
  result.score = scoreFromCritique(result);
  // A model that lists issues but forgets the verdict still gets a readable line in the report.
  if (!result.verdict) {
    result.verdict = issues.length
      ? `Критик нашёл ${issues.length} ${pluralRu(issues.length, 'замечание', 'замечания', 'замечаний')}; главное: ${issues[0].what}`
      : 'Критик не нашёл замечаний.';
  }
  // Findings from `analysis` are merged with the critic's own by the loop, not here: this function
  // stays a pure decoder of one model answer.
  return result;
}

/** Russian plural form: 1 замечание / 2 замечания / 5 замечаний. */
function pluralRu(n, one, few, many) {
  const a = Math.abs(n) % 100;
  const b = a % 10;
  if (a > 10 && a < 20) return many;
  if (b === 1) return one;
  if (b >= 2 && b <= 4) return few;
  return many;
}

/**
 * Calls the critic model for one iteration.
 *
 * @param {object} arg
 * @param {object} arg.spec current StructureSpec
 * @param {Array<{name: string, png?: Buffer}>} [arg.views] rendered views (PNGs are sent as images)
 * @param {object} [arg.analysis] result of `analyzeSpec`
 * @param {(req: {system: string, user: string, json: boolean, images: Buffer[]}) => Promise<{content: string, usage?: object}>} arg.llm
 * @param {string} [arg.prompt] original user request
 * @param {number} [arg.iteration=1]
 * @param {Array<{iteration:number, verdict?:string}>} [arg.history] earlier iterations (a short tail is shown)
 * @param {number} [arg.maxImages=6]
 * @param {number} [arg.maxOps=40]
 * @param {number} [arg.maxSpecJsonChars=24000]
 * @param {string} [arg.model]
 * @returns {Promise<object>} critique; on model failure a fallback with `failed: true` and `error`
 */
export async function critiqueSpec(arg, viewsArg, analysisArg, optionsArg) {
  const args = normalizeCriticArgs(arg, viewsArg, analysisArg, optionsArg);
  const {
    spec,
    views = [],
    analysis = null,
    llm,
    prompt = '',
    iteration = 1,
    history = [],
    maxImages = 6,
    maxOps = 40,
    maxSpecJsonChars = 24000,
    model,
  } = args;

  const normalizedSpec = normalizeSpec(spec && typeof spec === 'object' ? spec : {});
  const viewNames = (views || []).map((v) => (typeof v === 'string' ? v : v?.name)).filter((n) => typeof n === 'string' && n);
  const images = (views || [])
    .filter((v) => v && typeof v === 'object' && Buffer.isBuffer(v.png))
    .slice(0, Math.max(0, maxImages))
    .map((v) => v.png);
  const historyText = (history || [])
    .slice(-3)
    .map((h) => `- итерация ${h?.iteration}: ${cleanText(h?.why ?? h?.verdict, 240)}`)
    .join('\n');
  const user = buildCritiquePrompt({
    prompt,
    spec: normalizedSpec,
    analysis,
    viewNames,
    iteration,
    history: historyText,
    maxSpecJsonChars,
  });

  if (typeof llm !== 'function') {
    return fallbackCritique(analysis, {
      error: 'critic: no llm function was provided',
      verdict: 'Критик недоступен: не передана функция llm.',
      model,
    });
  }

  let response;
  try {
    response = await llm({ system: CRITIC_SYSTEM, user, json: true, images });
  } catch (e) {
    return fallbackCritique(analysis, {
      error: `critic request failed: ${e?.message || e}`,
      verdict: `Критик недоступен: ${e?.message || e}`,
      model,
    });
  }

  const usage = response?.usage || null;
  let parsed;
  try {
    const content = response?.content;
    if (content && typeof content === 'object') parsed = content;
    else parsed = parseCritiqueJson(content);
  } catch (e) {
    return fallbackCritique(analysis, {
      error: `critic answer could not be parsed: ${e?.message || e}`,
      verdict: `Критик вернул ответ не в формате JSON: ${e?.message || e}`,
      usage,
      model,
    });
  }

  const result = normalizeCritique(parsed, { maxOps, usage });
  // `scores` may be missing in a valid answer (rare); the loop then treats the score as unknown.
  return {
    ...result,
    failed: false,
    error: null,
    model: model || null,
    viewNames,
    imageCount: images.length,
    iteration,
    system: CRITIC_SYSTEM,
    user,
  };
}

/** Fallback critique built from the objective analysis; never throws. */
function fallbackCritique(analysis, { error, verdict, usage = null, model = null } = {}) {
  const objective = Array.isArray(analysis?.issues) ? analysis.issues : [];
  const issues = objective
    .filter((i) => i?.severity === 'critical' || i?.severity === 'major')
    .map((i, n) =>
      normalizeIssue(
        { severity: i.severity, what: i.what, where: i.where || 'analysis', code: i.code, source: 'analysis' },
        n,
      ),
    )
    .filter(Boolean);
  return {
    issues,
    scores: null,
    verdict: verdict || 'Критик недоступен; учтены только объективные проверки.',
    patch: emptyPatch(),
    dropped: [],
    score: null,
    sev: severityCounts(issues),
    usage,
    failed: true,
    error: error || 'critic failed',
    model,
    viewNames: [],
    imageCount: 0,
    fromAnalysis: true,
  };
}

/**
 * Accepts both call shapes:
 *   critiqueSpec({ spec, views, analysis, llm, ... })
 *   critiqueSpec(spec, views, analysis, { llm, ... })
 */
function normalizeCriticArgs(a, views, analysis, options) {
  if (a && typeof a === 'object' && !Array.isArray(a) && (a.llm !== undefined || a.spec !== undefined)) {
    return { ...a, views: a.views || [], analysis: a.analysis ?? null };
  }
  return { ...(options || {}), spec: a, views: views || [], analysis: analysis ?? null };
}
