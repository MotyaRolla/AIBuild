/**
 * Tests for the objective analyzer (analysis.mjs) and the AI critic wrapper (critic.mjs).
 *
 * Everything runs offline: the critic is exercised with deterministic stubs that stand in for the
 * language model, and with deliberately broken answers (invalid JSON, unknown types, junk patches)
 * to prove that a bad model answer never throws and never corrupts a spec.
 */
import test from 'node:test';
import assert from 'node:assert/strict';

import {
  analyzeSpec,
  parseRequestedSize,
  roomHeuristic,
  countOpenings,
  dedupeIssues,
  sortIssues,
  isSevere,
} from '../src/analysis.mjs';
import {
  critiqueSpec,
  CRITIC_SYSTEM,
  SCORE_KEYS,
  normalizeSeverity,
  severityCounts,
  isClean,
  averageScores,
  scoreFromCritique,
  normalizeScores,
  normalizeIssue,
  normalizePatch,
  parseCritiqueJson,
  normalizeCritique,
  buildCritiquePrompt,
} from '../src/critic.mjs';
import { normalizeSpec } from '../src/spec.mjs';

/** A small valid house used as the baseline in the analyzer tests. */
const HOUSE = {
  name: 'test house',
  seed: 3,
  palette: { wall: '#d9cfba', frame: '#3a2a1c', roof: '#6b452f', glass: '#a9c9d9', wood: '#8a6242', stone: '#8a8a8a' },
  objects: [
    { id: 'base', type: 'box', from: [-1, -1, -1], to: [8, 0, 8], material: 'stone' },
    { id: 'walls', type: 'box', from: [0, 1, 0], to: [7, 6, 7], material: 'wall', hollow: true, open: ['up'],
      openings: [{ wall: 'south', at: [3, 1], w: 2, h: 3, kind: 'door' }, { wall: 'south', at: [1, 2], w: 1, h: 2, kind: 'window' }] },
    { id: 'floor1', type: 'floor', from: [0, 3, 0], to: [7, 3, 7], material: 'wood' },
    { id: 'roof', type: 'prism', from: [0, 7, 0], to: [7, 7, 7], style: 'gable', ridge: 'x', material: 'roof', overhang: 1 },
    { id: 'door', type: 'door', wall: 'south', at: [3, 1], w: 2, h: 3, frame: 'frame' },
    { id: 'win', type: 'window', wall: 'east', at: [4, 2], w: 1, h: 2, frame: 'frame', glass: 'glass' },
    { id: 'chimney', type: 'chimney', at: [1, 7, 1], w: 1, d: 1, height: 3, material: 'stone' },
  ],
};

const clone = (v) => JSON.parse(JSON.stringify(v));
const without = (pred) => ({ ...clone(HOUSE), objects: clone(HOUSE).objects.filter(pred) });
const codes = (result) => result.issues.map((i) => i.code);

/* ========================================================================== */
/* analysis.mjs                                                               */
/* ========================================================================== */

test('analyzeSpec: a complete house passes with no issues', () => {
  const result = analyzeSpec(HOUSE);
  assert.equal(result.ok, true, `unexpected issues: ${JSON.stringify(result.issues)}`);
  assert.deepEqual(result.issues, []);
  assert.equal(result.metrics.objects, HOUSE.objects.length);
  assert.equal(result.metrics.hasDoor, true);
  assert.equal(result.metrics.hasWindow, true);
  assert.equal(result.metrics.hasRoof, true);
  assert.equal(result.metrics.enclosed, true);
  assert.ok(result.metrics.variety >= 3);
  assert.ok(result.metrics.voxelEstimate > 0);
});

test('analyzeSpec: an empty spec is critical, not a crash', () => {
  const result = analyzeSpec({ name: 'nothing', objects: [] });
  assert.equal(result.ok, false);
  assert.ok(codes(result).includes('empty-spec'));
  assert.equal(result.issues.find((i) => i.code === 'empty-spec').severity, 'critical');
  // must not throw on garbage input either
  assert.equal(analyzeSpec(null).ok, false);
  assert.equal(analyzeSpec(undefined).ok, false);
  assert.equal(analyzeSpec({ objects: 'nope' }).ok, false);
});

test('analyzeSpec: missing door, windows and roof are reported with the right severity', () => {
  const noDoor = analyzeSpec(without((o) => o.type !== 'door' && !(o.openings || []).some((op) => op.kind === 'door')));
  assert.ok(codes(noDoor).includes('no-door'));
  assert.equal(noDoor.issues.find((i) => i.code === 'no-door').severity, 'major');

  const noWindow = analyzeSpec(without((o) => o.type !== 'window' && !(o.openings || []).length));
  assert.ok(codes(noWindow).includes('no-window'));

  const noRoof = analyzeSpec(without((o) => o.type !== 'prism'));
  assert.ok(codes(noRoof).includes('no-roof'));
  assert.equal(noRoof.issues.find((i) => i.code === 'no-roof').severity, 'critical');
  assert.equal(noRoof.ok, false);
});

test('analyzeSpec: an explicitly requested size is verified', () => {
  const ok = analyzeSpec(HOUSE, { request: 'средневековый дом 8 на 8 блоков' });
  assert.equal(ok.metrics.sizeMatch.ok, true);
  assert.deepEqual(ok.metrics.sizeMatch.requested, { width: 8, depth: 8, height: null });
  assert.ok(!codes(ok).includes('size-mismatch'));

  const wrong = analyzeSpec(HOUSE, { request: 'дом 20 на 20 блоков' });
  assert.equal(wrong.metrics.sizeMatch.ok, false);
  assert.ok(codes(wrong).includes('size-mismatch'));
  assert.equal(wrong.ok, false);
});

test('parseRequestedSize: understands the forms the generator is told to respect', () => {
  assert.deepEqual(
    { ...parseRequestedSize('дом 10 на 10') },
    { width: 10, depth: 10, height: null, source: '10 на 10' },
  );
  assert.equal(parseRequestedSize('tower 12x12x30').height, 30);
  assert.equal(parseRequestedSize('house 10 by 14').depth, 14);
  assert.equal(parseRequestedSize('квадратный дом 9 × 9').width, 9);
  assert.equal(parseRequestedSize('замок с башнями и рвом'), null);
  assert.equal(parseRequestedSize(''), null);
  assert.equal(parseRequestedSize(undefined), null);
  // sizes that cannot be a footprint are ignored
  assert.equal(parseRequestedSize('год 2024 на 2025'), null);
});

test('analyzeSpec: degenerate and duplicated objects are flagged', () => {
  const degenerate = analyzeSpec({
    name: 'broken',
    objects: [
      { id: 'bad', type: 'box', from: [0, 0], to: [3, 3, 3] },
      { id: 'nobox', type: 'floor', material: 'wood' },
      { id: 'ok', type: 'detail', cells: [[0, 0, 0]], material: 'stone' },
    ],
  });
  assert.equal(degenerate.ok, false);
  assert.ok(codes(degenerate).some((c) => c === 'bad-box' || c === 'invalid-spec'));
  assert.ok(degenerate.metrics.degenerate >= 2);

  const duplicate = analyzeSpec({
    name: 'copy',
    objects: [
      { id: 'a', type: 'box', from: [0, 0, 0], to: [2, 2, 2], material: 'stone' },
      { id: 'b', type: 'box', from: [0, 0, 0], to: [2, 2, 2], material: 'stone' },
    ],
  });
  assert.ok(codes(duplicate).includes('duplicate-box'));
  assert.equal(duplicate.metrics.duplicates, 1);
});

test('analyzeSpec: a flat pile of planes with no roof is not a building', () => {
  const result = analyzeSpec({
    name: 'slab',
    objects: [{ id: 'pad', type: 'box', from: [0, 0, 0], to: [5, 0, 5], material: 'stone' }],
  });
  assert.equal(result.ok, false);
  assert.ok(codes(result).includes('no-roof'));
});

test('roomHeuristic: counts storeys from floor slabs inside a hollow shell', () => {
  const one = roomHeuristic(clone(HOUSE).objects);
  assert.equal(one.enclosed, true);
  assert.equal(one.enclosures, 1);
  assert.equal(one.storeys, 2, 'ground floor + one inserted slab = two storeys');

  const noSlab = roomHeuristic(clone(HOUSE).objects.filter((o) => o.type !== 'floor'));
  assert.equal(noSlab.storeys, 1);

  const solid = roomHeuristic([{ id: 'block', type: 'box', from: [0, 0, 0], to: [3, 3, 3], material: 'stone' }]);
  assert.equal(solid.enclosed, false);
});

test('countOpenings: counts both standalone openings and openings arrays', () => {
  const counts = countOpenings(HOUSE.objects);
  assert.deepEqual(counts, { door: 2, window: 2, arch: 0, total: 4 });
});

test('issue helpers: dedupe and sort', () => {
  const issues = [
    { severity: 'minor', what: 'a', where: 'x' },
    { severity: 'critical', what: 'b', where: 'y' },
    { severity: 'minor', what: 'a', where: 'x' },
    { severity: 'major', what: 'c', where: 'z' },
  ];
  const unique = dedupeIssues(issues);
  assert.equal(unique.length, 3);
  assert.deepEqual(sortIssues(unique).map((i) => i.severity), ['critical', 'major', 'minor']);
  assert.equal(isSevere({ severity: 'critical' }), true);
  assert.equal(isSevere({ severity: 'minor' }), false);
});

/* ========================================================================== */
/* critic.mjs — pure helpers                                                  */
/* ========================================================================== */

test('normalizeSeverity: tolerant of the spellings models produce', () => {
  assert.equal(normalizeSeverity('critical'), 'critical');
  assert.equal(normalizeSeverity('CRITICAL'), 'critical');
  assert.equal(normalizeSeverity('blocker'), 'critical');
  assert.equal(normalizeSeverity('major'), 'major');
  assert.equal(normalizeSeverity('high'), 'critical');
  assert.equal(normalizeSeverity('medium'), 'major');
  assert.equal(normalizeSeverity('minor'), 'minor');
  assert.equal(normalizeSeverity('nitpick'), 'minor');
  assert.equal(normalizeSeverity(undefined), 'minor');
});

test('severityCounts / isClean', () => {
  const counts = severityCounts([{ severity: 'critical' }, { severity: 'major' }, { severity: 'minor' }, {}]);
  assert.deepEqual(counts, { critical: 1, major: 1, minor: 2, total: 4 });
  assert.equal(isClean([{ severity: 'minor' }]), true);
  assert.equal(isClean([{ severity: 'major' }]), false);
  assert.equal(isClean([]), true);
});

test('scores: normalized, averaged and converted to 0..100', () => {
  const scores = normalizeScores({ shape: 9, detail: 7.5, style: 12, structure: -3, fidelity: 'x', mood: 6 });
  assert.equal(scores.shape, 9);
  assert.equal(scores.detail, 7.5);
  assert.equal(scores.style, 10, 'above 10 is clamped');
  assert.equal(scores.structure, 0, 'below 0 is clamped');
  assert.equal(scores.fidelity, undefined);
  assert.equal(scores.mood, 6);
  assert.equal(normalizeScores({}), null);
  assert.equal(averageScores(null), null);

  const avg = averageScores({ shape: 8, detail: 6, style: 7, structure: 7, fidelity: 7 });
  assert.equal(avg, 7);
  assert.equal(scoreFromCritique({ scores: { shape: 8, detail: 6, style: 7, structure: 7, fidelity: 7 } }), 70);
  assert.equal(scoreFromCritique(null), null);
  assert.deepEqual([...SCORE_KEYS].sort(), ['detail', 'fidelity', 'shape', 'structure', 'style']);
});

test('normalizeIssue: keeps the contract fields and defaults where', () => {
  const issue = normalizeIssue({ severity: 'MAJOR', what: '  roof   is flat ', where: 'крыша', view: 'se', code: 'roof-flat' }, 2);
  assert.deepEqual(
    { severity: issue.severity, what: issue.what, where: issue.where },
    { severity: 'major', what: 'roof is flat', where: 'крыша' },
  );
  assert.equal(issue.code, 'roof-flat');
  assert.equal(issue.view, 'se');
  assert.equal(normalizeIssue({ what: '' }), null);
  assert.equal(normalizeIssue(null), null);
  assert.deepEqual(normalizeIssue('no windows'), { severity: 'minor', what: 'no windows', where: 'spec' });
  // newlines and pipes never leak into the report table
  assert.equal(normalizeIssue({ what: 'a | b\nc' }).what, 'a / b c');
});

test('normalizePatch: keeps only legal operations and reports the rest', () => {
  const { patch, dropped } = normalizePatch({
    add: [{ type: 'box', from: [0, 0, 0], to: [1, 1, 1] }, { from: [0, 0, 0] }, 'nope'],
    replace: [{ id: 'roof', type: 'prism', from: [0, 6, 0], to: [5, 6, 5] }, { type: 'box' }],
    remove: ['old', 2, null],
  });
  assert.equal(patch.add.length, 1);
  assert.equal(patch.replace.length, 1);
  assert.deepEqual(patch.remove, ['old', 2]);
  assert.ok(dropped.length >= 3);
  assert.ok(typeof dropped[0] === 'string');
});

test('normalizePatch: enforces the operation budget', () => {
  const add = Array.from({ length: 10 }, (_, i) => ({ id: `o${i}`, type: 'detail', cells: [[i, 0, 0]] }));
  const { patch, dropped } = normalizePatch({ add }, { maxOps: 3 });
  assert.equal(patch.add.length, 3);
  assert.ok(dropped.some((d) => d.includes('limit')));
});

test('parseCritiqueJson: fences, prose and truncated answers', () => {
  assert.deepEqual(parseCritiqueJson('{"verdict":"ok"}'), { verdict: 'ok' });
  assert.deepEqual(parseCritiqueJson('```json\n{"verdict":"ok"}\n```'), { verdict: 'ok' });
  assert.deepEqual(parseCritiqueJson('Here is my answer:\n{"verdict":"ok"} — hope it helps'), { verdict: 'ok' });
  assert.deepEqual(parseCritiqueJson('{"issues":[{"what":"a"},],}'), { issues: [{ what: 'a' }] });
  const truncated = parseCritiqueJson('{"issues":[{"what":"a"}],"scores":{"shape":5');
  assert.equal(truncated.issues.length, 1);
  assert.throws(() => parseCritiqueJson('no json here'), /no JSON/);
  assert.throws(() => parseCritiqueJson(''), /empty/);
});

test('normalizeCritique: an answer without a verdict still produces a readable one', () => {
  const result = normalizeCritique({
    issues: [{ severity: 'major', what: 'фасад плоский', where: 'юг' }],
    scores: { shape: 5, detail: 4, style: 5, structure: 6, fidelity: 4 },
  });
  assert.equal(result.issues.length, 1);
  assert.equal(result.score, 48);
  assert.ok(result.verdict.includes('фасад плоский'));
  assert.deepEqual(result.patch, { add: [], replace: [], remove: [] });
  assert.equal(result.sev.major, 1);
});

test('buildCritiquePrompt: contains the request, digest, analysis and view names', () => {
  const prompt = buildCritiquePrompt({
    prompt: 'средневековый дом 8 на 8',
    spec: HOUSE,
    analysis: analyzeSpec(HOUSE),
    viewNames: ['se', 'front'],
    iteration: 3,
  });
  assert.ok(prompt.includes('средневековый дом 8 на 8'));
  assert.ok(prompt.includes('se, front'));
  assert.ok(prompt.includes('идёт ли речь') || prompt.includes('OBJECTIVE ANALYSIS'));
  assert.ok(prompt.includes('"objects"'));
  assert.ok(prompt.includes('iteration 3'));
});

/* ========================================================================== */
/* critic.mjs — critiqueSpec with stubs                                       */
/* ========================================================================== */

/** Builds a stub llm that answers once with `content` and records the request. */
function stubLlm(content, usage = { prompt_tokens: 12, completion_tokens: 34 }) {
  const calls = [];
  const fn = async (req) => {
    calls.push(req);
    return { content: typeof content === 'function' ? content(req) : content, usage };
  };
  fn.calls = calls;
  return fn;
}

test('critiqueSpec: parses a normal answer and passes images + digest to the model', async () => {
  const png = Buffer.from([0x89, 0x50, 0x4e, 0x47]);
  const answer = JSON.stringify({
    issues: [{ severity: 'major', what: 'крыша слишком плоская', where: 'крыша' }],
    scores: { shape: 6, detail: 5, style: 6, structure: 7, fidelity: 5 },
    verdict: 'силуэт слабый',
    patch: { add: [{ id: 'ridge', type: 'prism', from: [0, 7, 0], to: [7, 7, 7], style: 'gable', material: 'roof' }] },
  });
  const llm = stubLlm(answer);
  const analysis = analyzeSpec(HOUSE);
  const critique = await critiqueSpec({
    spec: HOUSE,
    views: [{ name: 'se', png }, { name: 'front', png }],
    analysis,
    llm,
    prompt: 'дом 8 на 8',
    iteration: 2,
  });

  assert.equal(critique.failed, false);
  assert.equal(critique.issues.length, 1);
  assert.equal(critique.issues[0].severity, 'major');
  assert.equal(critique.scores.shape, 6);
  assert.equal(critique.score, 58);
  assert.equal(critique.verdict, 'силуэт слабый');
  assert.equal(critique.patch.add.length, 1);
  assert.equal(critique.patch.add[0].id, 'ridge');
  assert.equal(critique.imageCount, 2);
  assert.deepEqual(critique.viewNames, ['se', 'front']);
  assert.equal(critique.usage.prompt_tokens, 12);

  const request = llm.calls[0];
  assert.equal(request.system, CRITIC_SYSTEM);
  assert.equal(request.json, true);
  assert.equal(request.images.length, 2);
  assert.ok(request.user.includes('дом 8 на 8'));
  assert.ok(request.user.includes('se, front'));
});

test('critiqueSpec: caps the number of images sent', async () => {
  const png = Buffer.from([1, 2, 3]);
  const llm = stubLlm('{"issues":[],"scores":{"shape":8},"verdict":"ok"}');
  const views = Array.from({ length: 9 }, (_, i) => ({ name: `v${i}`, png }));
  const critique = await critiqueSpec({ spec: HOUSE, views, analysis: analyzeSpec(HOUSE), llm, maxImages: 3 });
  assert.equal(critique.imageCount, 3);
  assert.equal(llm.calls[0].images.length, 3);
});

test('critiqueSpec: a JSON failure falls back to the objective analysis (no throw)', async () => {
  const analysis = analyzeSpec(without((o) => o.type !== 'door' && !(o.openings || []).some((op) => op.kind === 'door')));
  const critique = await critiqueSpec({ spec: HOUSE, views: [], analysis, llm: stubLlm('I think it looks fine!') });
  assert.equal(critique.failed, true);
  assert.ok(critique.error.includes('JSON'));
  assert.ok(critique.issues.every((i) => i.severity === 'critical' || i.severity === 'major'));
  assert.ok(critique.issues.some((i) => String(i.what).includes('двер')));
  assert.equal(critique.patch.add.length, 0);
});

test('critiqueSpec: a throwing model is contained', async () => {
  const analysis = analyzeSpec(HOUSE);
  const boom = async () => {
    throw new Error('provider down (429)');
  };
  const critique = await critiqueSpec({ spec: HOUSE, views: [], analysis, llm: boom });
  assert.equal(critique.failed, true);
  assert.ok(critique.error.includes('429'));
  assert.deepEqual(critique.issues, []);
  assert.equal(critique.verdict.includes('недоступен'), true);
});

test('critiqueSpec: no llm at all degrades instead of throwing', async () => {
  const critique = await critiqueSpec({ spec: HOUSE, views: [], analysis: analyzeSpec(HOUSE) });
  assert.equal(critique.failed, true);
  assert.ok(critique.error.includes('no llm'));
});

test('critiqueSpec: model objects with junk fields are sanitised', async () => {
  const answer = JSON.stringify({
    issues: [{ severity: 'high', what: 'нет окон на востоке' }],
    scores: { shape: 4, detail: 3, style: 4, structure: 5, fidelity: 3 },
    verdict: 'фасад слепой',
    patch: {
      add: [{ id: 'win2', type: 'window', wall: 'east', at: [2, 2], w: 1, h: 2 }, { type: 'no-such-type' }, null],
      replace: [{ id: 'missing', type: 'box', from: [0, 0, 0], to: [1, 1, 1] }],
      remove: ['no-such-id'],
    },
  });
  const critique = await critiqueSpec({ spec: HOUSE, views: [], analysis: analyzeSpec(HOUSE), llm: stubLlm(answer) });
  // shape-level sanitation happens here; existence checks happen in applySpecPatch
  assert.equal(critique.patch.add.length, 1);
  assert.equal(critique.issues[0].severity, 'critical', 'high is normalised to critical');
});

test('critiqueSpec: accepts the positional short form', async () => {
  const llm = stubLlm('{"issues":[],"scores":{"shape":9},"verdict":"хорошо"}');
  const critique = await critiqueSpec(HOUSE, [{ name: 'se', png: Buffer.from([1]) }], analyzeSpec(HOUSE), { llm });
  assert.equal(critique.failed, false);
  assert.equal(critique.score, 90);
  assert.equal(critique.imageCount, 1);
});

test('critiqueSpec: a valid spec is never mutated by the critic', async () => {
  const before = JSON.stringify(HOUSE);
  const llm = stubLlm('{"issues":[],"patch":{"add":[{"id":"x","type":"detail","cells":[[0,0,0]]}]}}');
  const spec = normalizeSpec(HOUSE);
  const snapshot = JSON.stringify(spec);
  await critiqueSpec({ spec, views: [], analysis: analyzeSpec(spec), llm });
  assert.equal(JSON.stringify(spec), snapshot);
  assert.equal(JSON.stringify(HOUSE), before);
});
