/**
 * Tests for the iteration orchestrator (loop.mjs).
 *
 * The whole point of the loop is that it can be exercised without a provider and without a GPU:
 * `llm` is a deterministic stub and `render` is a stub returning tiny PNG-ish buffers. The real
 * pipeline is covered by a separate, slower test at the bottom that uses tasks A and B when they are
 * present (it skips itself when they are not).
 */
import test from 'node:test';
import assert from 'node:assert/strict';

import {
  runBuildLoop,
  applySpecPatch,
  pickBest,
  renderReport,
  heuristicScore,
  evaluateSpec,
  checkObjectShape,
  DEFAULT_THRESHOLDS,
} from '../src/loop.mjs';
import { normalizeSpec, validateSpec } from '../src/spec.mjs';
import { analyzeSpec } from '../src/analysis.mjs';
import { severityCounts } from '../src/critic.mjs';

/* ========================================================================== */
/* fixtures                                                                   */
/* ========================================================================== */

/** A deliberately plain start: four walls, no door, no window, no roof. */
function bareSpec() {
  return {
    name: 'стартовый домик',
    seed: 5,
    palette: { wall: '#d9cfba', roof: '#6b452f', frame: '#3a2a1c', glass: '#a9c9d9', stone: '#8a8a8a', wood: '#8a6242' },
    objects: [
      { id: 'walls', type: 'box', from: [0, 0, 0], to: [7, 4, 7], material: 'wall', hollow: true, open: ['up', 'down'] },
    ],
  };
}

/** The five objects the improving stub adds, in order. */
const IMPROVEMENTS = [
  { id: 'roof', type: 'prism', from: [0, 5, 0], to: [7, 5, 7], style: 'gable', ridge: 'x', material: 'roof', overhang: 1 },
  { id: 'door', type: 'door', wall: 'south', at: [3, 0], w: 2, h: 3, frame: 'frame' },
  { id: 'win-s', type: 'window', wall: 'south', at: [1, 1], w: 1, h: 2, frame: 'frame', glass: 'glass' },
  { id: 'floor1', type: 'floor', from: [0, 2, 0], to: [7, 2, 7], material: 'wood' },
  { id: 'chimney', type: 'chimney', at: [1, 5, 1], w: 1, d: 1, height: 3, material: 'stone' },
];

/** A tiny but real PNG-ish payload; the loop only forwards it, never decodes it. */
const FAKE_PNG = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 13]);

/**
 * Stub renderer: returns named views, counts calls, and can be told to fail.
 * @param {{fail?: boolean, empty?: boolean}} [opts]
 */
function stubRender(opts = {}) {
  const calls = [];
  const fn = async (scene, options) => {
    calls.push({ scene, options });
    if (opts.fail) throw new Error('renderer exploded');
    if (opts.empty) return { views: [] };
    return {
      views: (options.views || ['se']).map((name) => ({ name, png: FAKE_PNG, camera: null })),
      ms: 1,
    };
  };
  fn.calls = calls;
  return fn;
}

/**
 * The "improving model" stub used by most tests.
 *
 * On every call it looks at the spec text it was given, finds the first IMPROVEMENTS object that is
 * not there yet and adds it. Its scores rise only while it still has something to add and then stay
 * flat — exactly the shape of progress the loop's stop rule is designed to detect. Once nothing is
 * left to add it reports a clean verdict (unless `alwaysIssues`).
 *
 * @param {object} [opts]
 * @param {number} [opts.baseScore=4] score with nothing added; +1 per improvement present
 * @param {boolean} [opts.badPatch] send an invalid patch on the first call
 * @param {boolean} [opts.alwaysIssues] never report a clean verdict
 */
function improvingLlm(opts = {}) {
  const { baseScore = 4, badPatch = false, alwaysIssues = false } = opts;
  const calls = [];
  const fn = async (req) => {
    const index = calls.length;
    calls.push(req);
    const present = IMPROVEMENTS.filter((o) => req.user.includes(`"id":"${o.id}"`) || req.user.includes(`"id": "${o.id}"`));
    const missing = IMPROVEMENTS.filter((o) => !present.includes(o));
    const add = missing.length ? [missing[0]] : [];
    const clean = !alwaysIssues && missing.length === 0;
    const score = Math.min(10, baseScore + present.length);
    const issues = clean
      ? [{ severity: 'minor', what: 'можно добавить ставни', where: 'фасад' }]
      : [{ severity: 'major', what: `не хватает деталей (шаг ${index + 1})`, where: 'фасад' }];
    const patch = badPatch && index === 0
      ? { add: [{ id: 'broken', type: 'box', from: [0, 0], to: [3, 3, 3] }, { id: 'also-bad', type: 'nonsense' }], remove: ['no-such-id'], replace: [] }
      : { add, remove: [], replace: [] };
    return {
      content: JSON.stringify({
        issues,
        scores: { shape: score, detail: score, style: score, structure: score, fidelity: score },
        verdict: clean ? 'выглядит достойно' : `итерация ${index + 1}: ещё есть что улучшить`,
        patch,
      }),
      usage: { prompt_tokens: 100, completion_tokens: 50 },
    };
  };
  fn.calls = calls;
  return fn;
}

/* ========================================================================== */
/* applySpecPatch                                                             */
/* ========================================================================== */

test('applySpecPatch: add, replace and remove by id', () => {
  const spec = normalizeSpec(bareSpec());
  const patched = applySpecPatch(spec, {
    add: [{ id: 'roof', type: 'prism', from: [0, 5, 0], to: [7, 5, 7], style: 'gable', material: 'roof' }],
    replace: [{ id: 'walls', type: 'box', from: [0, 0, 0], to: [7, 5, 7], material: 'wall', hollow: true, open: ['up', 'down'] }],
    remove: [],
  });
  assert.equal(patched.applied, true);
  assert.equal(patched.errors.length, 0);
  assert.equal(patched.spec.objects.length, 2);
  assert.equal(patched.spec.objects.find((o) => o.id === 'roof').type, 'prism');
  assert.equal(patched.spec.objects.find((o) => o.id === 'walls').to[1], 5);
  assert.equal(patched.changes.length, 2);
  // the input is untouched
  assert.equal(spec.objects.length, 1);
});

test('applySpecPatch: remove by index and by #id', () => {
  const spec = normalizeSpec({ name: 'x', objects: [{ id: 'a', type: 'detail', cells: [[0, 0, 0]] }, { id: 'b', type: 'detail', cells: [[1, 0, 0]] }] });
  const byIndex = applySpecPatch(spec, { remove: [0] });
  assert.equal(byIndex.spec.objects.length, 1);
  assert.equal(byIndex.spec.objects[0].id, 'b');

  const byHash = applySpecPatch(spec, { remove: ['#a'] });
  assert.equal(byHash.spec.objects.length, 1);
  assert.equal(byHash.spec.objects[0].id, 'b');
});

test('applySpecPatch: invalid operations are rejected, valid ones still land', () => {
  const spec = normalizeSpec(bareSpec());
  const patched = applySpecPatch(spec, {
    add: [
      { id: 'good', type: 'chimney', at: [1, 5, 1], w: 1, d: 1, height: 2, material: 'stone' },
      { id: 'no-type' },
      { id: 'unknown-type', type: 'spaceship' },
      { id: 'bad-box', type: 'box', from: [0, 0, 0], to: 'nope' },
      { id: 'reversed', type: 'box', from: [5, 5, 5], to: [0, 0, 0] },
      { id: 'walls', type: 'detail', cells: [[0, 0, 0]] }, // duplicate id
    ],
    replace: [{ id: 'ghost', type: 'box', from: [0, 0, 0], to: [1, 1, 1] }],
    remove: ['ghost-id', 99],
  });
  assert.equal(patched.applied, true);
  assert.ok(patched.spec.objects.some((o) => o.id === 'good'));
  assert.equal(patched.spec.objects.filter((o) => o.id === 'walls').length, 1);
  assert.ok(patched.errors.length >= 5, `expected several errors, got ${patched.errors.length}: ${patched.errors}`);
  // the result always validates
  assert.equal(validateSpec(patched.spec).ok, true);
});

test('applySpecPatch: a patch that is complete junk is rejected and returns the input spec', () => {
  const spec = normalizeSpec(bareSpec());
  const patched = applySpecPatch(spec, { add: [], replace: [], remove: ['nope'] });
  assert.equal(patched.applied, false);
  assert.equal(patched.changes.length, 0);
  assert.deepEqual(patched.spec.objects, spec.objects);
  assert.ok(patched.errors.length >= 1);

  // not even an object
  const garbage = applySpecPatch(spec, 'not a patch');
  assert.equal(garbage.applied, false);
  assert.equal(garbage.spec.objects.length, 1);
});

test('applySpecPatch: a patch may not mutate the spec that was passed in', () => {
  const spec = normalizeSpec(bareSpec());
  const snapshot = JSON.stringify(spec);
  const patched = applySpecPatch(spec, {
    replace: [{ id: 'walls', type: 'box', from: [0, 0, 0], to: [7, 9, 7], material: 'wall', hollow: true }],
    add: [{ id: 'roof', type: 'prism', from: [0, 10, 0], to: [7, 10, 7], style: 'hip', material: 'roof' }],
  });
  assert.equal(JSON.stringify(spec), snapshot);
  assert.notEqual(JSON.stringify(patched.spec), snapshot);
});

test('applySpecPatch: enforces the object limit', () => {
  const spec = normalizeSpec(bareSpec());
  const many = Array.from({ length: 5 }, (_, i) => ({ id: `d${i}`, type: 'detail', cells: [[i, 0, 0]] }));
  const patched = applySpecPatch(spec, { add: many }, { maxObjects: 3 });
  assert.equal(patched.applied, true);
  assert.equal(patched.spec.objects.length, 3);
  assert.ok(patched.errors.some((e) => e.includes('limit')));
});

test('checkObjectShape: catches the field mistakes models make', () => {
  assert.equal(checkObjectShape({ type: 'box', from: [0, 0, 0], to: [1, 1, 1] }), '');
  assert.match(checkObjectShape({ type: 'box', from: [0, 0, 0] }), /from\[3\] and to\[3\]/);
  assert.match(checkObjectShape({ type: 'box', from: [2, 0, 0], to: [0, 0, 0] }), /from\[0\] > to\[0\]/);
  assert.match(checkObjectShape({ type: 'door', wall: 'up', at: [1, 1], w: 1, h: 2 }), /wall/);
  assert.match(checkObjectShape({ type: 'door', wall: 'south', at: [1, 1], w: 0, h: 2 }), /positive w and h/);
  assert.match(checkObjectShape({ type: 'window', wall: 'south', at: [1, 2, 3], w: 1, h: 2 }), /at:\[u,y\]/);
  assert.match(checkObjectShape({ type: 'chimney', at: [1, 2] }), /at:\[x,y,z\]/);
  assert.match(checkObjectShape({ type: 'detail', cells: [] }), /cells/);
  assert.match(checkObjectShape({ type: 'cylinder', center: [0, 0, 0], radius: 0, height: 4 }), /radius/);
  assert.match(checkObjectShape({ type: 'sphere', center: [0, 0, 0] }), /radius/);
  assert.match(checkObjectShape({ type: 'nope' }), /unknown type/);
  assert.match(checkObjectShape(null), /JSON object/);
});

/* ========================================================================== */
/* runBuildLoop — the happy path                                              */
/* ========================================================================== */

test('runBuildLoop: runs to the cap and records a complete history', async () => {
  const spec0 = normalizeSpec(bareSpec());
  const llm = improvingLlm({ alwaysIssues: true });
  const render = stubRender();
  const result = await runBuildLoop({
    prompt: 'дом 8 на 8 блоков',
    spec0,
    iterations: 3,
    llm,
    render,
    thresholds: { stallIterations: 2 },
  });

  assert.equal(result.history.length, 3, 'one entry per iteration');
  assert.deepEqual(result.history.map((h) => h.iteration), [1, 2, 3]);
  for (const entry of result.history) {
    assert.ok(entry.spec && Array.isArray(entry.spec.objects), 'history keeps the spec version');
    assert.ok(entry.metrics && typeof entry.metrics === 'object', 'history keeps the metrics');
    assert.ok(Array.isArray(entry.issues), 'history keeps the issues');
    assert.ok(entry.patch && typeof entry.patch.applied === 'boolean', 'history keeps the patch result');
    assert.ok(Number.isFinite(entry.score), 'history keeps the score');
    assert.equal(typeof entry.why, 'string');
    assert.ok(entry.why.length > 0);
    assert.ok(entry.metrics.counts, 'metrics carry severity counters');
    assert.ok(Array.isArray(entry.views));
  }
  // every iteration saw at least one rendered view
  assert.ok(result.history.every((h) => h.views.length > 0));
  assert.equal(result.rendering, true);
  assert.equal(result.stopCode, 'iteration-cap');
  assert.ok(result.stopReason.includes('лимит'));
  // the stub adds one object per call: 1 -> 2 -> 3 -> 4
  assert.equal(result.history[0].spec.objects.length, 1);
  assert.equal(result.history[2].metrics.objects, 3);
});

test('runBuildLoop: the improving stub makes the score rise', async () => {
  const result = await runBuildLoop({
    prompt: 'дом 8 на 8',
    spec0: bareSpec(),
    iterations: 4,
    llm: improvingLlm(),
    render: stubRender(),
    thresholds: { stallIterations: 2 },
  });
  const scores = result.history.map((h) => h.score);
  assert.deepEqual(scores, [40, 50, 60, 70]);
  assert.equal(result.history[0].scoreGain, 40, 'the first gain is measured against zero');
  assert.equal(result.history[1].scoreGain, 10);
});

test('runBuildLoop: stops early when the critic is clean twice in a row', async () => {
  // The stub can add 5 objects. Scores rise while it adds them (iterations 1..6); after that there
  // is nothing left to add, so two clean but stalled iterations follow and the loop stops at 8,
  // well before the 10-iteration cap.
  const llm = improvingLlm();
  const result = await runBuildLoop({
    prompt: 'дом 8 на 8',
    spec0: bareSpec(),
    iterations: 10,
    llm,
    render: stubRender(),
    thresholds: { stallIterations: 2, scoreGain: 2 },
  });
  assert.equal(result.history.length, 8, `expected an early stop after 2 clean stalled iterations, history: ${result.history.length}`);
  assert.equal(result.stopCode, 'converged');
  assert.ok(result.stopReason.includes('критик не нашёл'));
  assert.equal(llm.calls.length, 8, 'it stopped before the 10-iteration cap');
  const sev = severityCounts(result.history.at(-1).issues);
  assert.equal(sev.critical + sev.major, 0, 'the loop only stops when the critic is clean');
  // the analysis of the final version must also be clean for the stop to be valid
  const analysis = analyzeSpec(result.history.at(-1).spec, { request: 'дом 8 на 8' });
  assert.equal(analysis.ok, true);
  // the last two gains really are below the threshold
  const tail = result.history.slice(-2);
  assert.ok(tail.every((h) => h.scoreGain < 2), `gains: ${tail.map((h) => h.scoreGain)}`);
  // all five improvements landed
  assert.equal(result.spec.objects.length, 1 + IMPROVEMENTS.length);
});

test('runBuildLoop: does not stop early while the critic reports major issues', async () => {
  const result = await runBuildLoop({
    prompt: 'дом 8 на 8',
    spec0: bareSpec(),
    iterations: 3,
    llm: improvingLlm({ alwaysIssues: true }),
    render: stubRender(),
    thresholds: { stallIterations: 1 },
  });
  assert.equal(result.history.length, 3, 'a persistent critic keeps the loop going to the cap');
  assert.equal(result.stopCode, 'iteration-cap');
  assert.ok(result.stopReason.includes('лимит'));
});

test('runBuildLoop: invalid patches are rejected without crashing the loop', async () => {
  const spec0 = bareSpec();
  const result = await runBuildLoop({
    prompt: 'дом 8 на 8',
    spec0,
    iterations: 3,
    llm: improvingLlm({ badPatch: true }),
    render: stubRender(),
  });

  assert.equal(result.history.length, 3);
  // iteration 1 sent a broken patch: the valid operations of a broken patch are dropped one by one
  const first = result.history[0];
  assert.equal(first.patch.applied, false, 'the bad patch must not be applied');
  assert.ok(first.patch.errors.length >= 2);
  assert.ok(first.patch.errors.some((e) => e.includes('no-such-id')));
  // the spec of iteration 1 is still the untouched, valid start
  assert.deepEqual(first.spec.objects.map((o) => o.id), ['walls']);
  assert.equal(validateSpec(first.spec).ok, true);
  // later iterations continue normally and add good objects
  assert.ok(result.history[2].spec.objects.length > 1);
  assert.equal(validateSpec(result.spec).ok, true);
  // the loop keeps every iteration, including the rejected one
  assert.equal(result.history.filter((h) => !h.patch.applied).length, 1);
});

test('runBuildLoop: a model that always sends garbage still finishes with a valid spec', async () => {
  const brokenLlm = async () => ({ content: 'I cannot answer in JSON, sorry!' });
  const result = await runBuildLoop({
    prompt: 'дом 8 на 8',
    spec0: bareSpec(),
    iterations: 2,
    llm: brokenLlm,
    render: stubRender(),
  });
  assert.equal(result.history.length, 2);
  assert.ok(result.history.every((h) => h.criticFailed === true));
  assert.ok(result.history.every((h) => h.patch.applied === false));
  assert.equal(validateSpec(result.spec).ok, true);
  assert.equal(result.spec.objects.length, 1);
  // it still produced a report and a best entry
  assert.ok(result.best);
  assert.ok(result.report.includes('Итерация 1'));
});

/* ========================================================================== */
/* degrading gracefully                                                       */
/* ========================================================================== */

test('runBuildLoop: continues with analysis only when rendering is unavailable', async () => {
  const llm = improvingLlm({ alwaysIssues: true });
  const result = await runBuildLoop({
    prompt: 'дом 8 на 8',
    spec0: bareSpec(),
    iterations: 3,
    llm,
    render: null,
  });
  assert.equal(result.rendering, false);
  assert.equal(result.history.length, 3, 'the loop must not stop just because renders are missing');
  assert.ok(result.history.every((h) => h.views.length === 0));
  assert.ok(result.history.every((h) => typeof h.renderError === 'string'));
  // the critic was still called, with an empty image list
  assert.ok(llm.calls.length >= 3);
  assert.ok(llm.calls.every((c) => Array.isArray(c.images) && c.images.length === 0));
  assert.ok(result.report.includes('недоступны'));
});

test('runBuildLoop: a throwing renderer degrades instead of aborting', async () => {
  const result = await runBuildLoop({
    prompt: 'дом 8 на 8',
    spec0: bareSpec(),
    iterations: 2,
    llm: improvingLlm(),
    render: stubRender({ fail: true }),
  });
  assert.equal(result.history.length, 2);
  assert.ok(result.history.every((h) => h.renderError.includes('exploded')));
  assert.equal(result.rendering, false);
});

test('runBuildLoop: an empty view list is treated as no rendering', async () => {
  const result = await runBuildLoop({
    prompt: 'дом 8 на 8',
    spec0: bareSpec(),
    iterations: 2,
    llm: improvingLlm(),
    render: stubRender({ empty: true }),
  });
  assert.equal(result.rendering, false);
  assert.equal(result.history.length, 2);
});

test('runBuildLoop: the default renderer is used when render is omitted (and is optional)', async () => {
  // This test does not assume tasks A/B are finished: either the real renderer works, or the loop
  // degrades to analysis only. Both outcomes must produce a complete history.
  const result = await runBuildLoop({
    prompt: 'дом 8 на 8',
    spec0: bareSpec(),
    iterations: 1,
    llm: improvingLlm(),
  });
  assert.equal(result.history.length, 1);
  assert.equal(typeof result.rendering, 'boolean');
  if (result.rendering) {
    assert.ok(result.history[0].views.length > 0);
    assert.ok(result.history[0].views.every((n) => typeof n === 'string'));
  }
});

test('runBuildLoop: no llm at all still analyses and reports', async () => {
  const result = await runBuildLoop({
    prompt: 'дом 8 на 8',
    spec0: bareSpec(),
    iterations: 2,
    render: stubRender(),
  });
  assert.equal(result.history.length, 2);
  assert.ok(result.history.every((h) => h.criticFailed));
  assert.ok(result.history.every((h) => Number.isFinite(h.score)), 'the heuristic score fills in');
  assert.equal(result.history[0].score, heuristicScore(analyzeSpec(bareSpec())));
});

test('runBuildLoop: rejects a missing prompt with a clear error', async () => {
  await assert.rejects(() => runBuildLoop({ llm: improvingLlm(), render: stubRender() }), TypeError);
  await assert.rejects(() => runBuildLoop({ prompt: '   ', llm: improvingLlm(), render: stubRender() }), /prompt/);
});

/* ========================================================================== */
/* pickBest / report                                                          */
/* ========================================================================== */

test('pickBest: highest score wins, not the latest version', () => {
  const history = [
    { iteration: 1, score: 40, issues: [], spec: { objects: [] } },
    { iteration: 2, score: 70, issues: [], spec: { objects: [] } },
    { iteration: 3, score: 55, issues: [{ severity: 'major' }], spec: { objects: [] } },
  ];
  assert.equal(pickBest(history).iteration, 2);
  assert.equal(pickBest([]), null);
  assert.equal(pickBest(null), null);
  assert.equal(pickBest([{ iteration: 1, score: null }]), null);
});

test('pickBest: ties go to the cleaner version, then to the earlier one', () => {
  const clean = { iteration: 1, score: 60, issues: [] };
  const dirty = { iteration: 2, score: 60, issues: [{ severity: 'critical' }] };
  assert.equal(pickBest([dirty, clean]).iteration, 1);
  const same = { iteration: 3, score: 60, issues: [] };
  assert.equal(pickBest([clean, same]).iteration, 1, 'stable on a full tie');
});

test('runBuildLoop: best is not necessarily the last iteration', async () => {
  // The stub gives 40, 50, 60 but the last iteration's spec is made worse by a "clean" critic
  // whose score drops: pickBest must still return the peak.
  let call = 0;
  const llm = async () => {
    call += 1;
    const scores = call === 3 ? { shape: 1, detail: 1, style: 1, structure: 1, fidelity: 1 } : { shape: 8, detail: 8, style: 8, structure: 8, fidelity: 8 };
    return {
      content: JSON.stringify({
        issues: [{ severity: 'minor', what: 'мелочь' }],
        scores,
        verdict: `шаг ${call}`,
        patch: call === 1 ? { add: [{ id: 'a', type: 'detail', cells: [[0, 0, 0]] }] } : { add: [] },
      }),
    };
  };
  const result = await runBuildLoop({ prompt: 'дом 8 на 8', spec0: bareSpec(), iterations: 3, llm, render: stubRender() });
  assert.deepEqual(result.history.map((h) => h.score), [80, 80, 10]);
  assert.equal(result.best.iteration, 1);
  assert.equal(result.spec.objects.length, 2, 'the returned spec is the last version');
  assert.equal(result.best.spec.objects.length, 1, 'the best entry keeps its own version');
  assert.ok(result.report.includes('Лучшая версия'));
});

test('renderReport: mentions every iteration in both the table and the details', () => {
  const history = [
    {
      iteration: 1,
      spec: bareSpec(),
      metrics: analyzeSpec(bareSpec()).metrics,
      issues: [{ severity: 'major', what: 'нет крыши', where: 'крыша' }],
      patch: { applied: true, errors: [], changes: [{ op: 'add', id: 'roof', type: 'prism' }] },
      score: 40,
      scoreGain: 40,
      sev: { critical: 0, major: 1, minor: 0 },
      analysisOk: false,
      verdict: 'нужна крыша',
      why: 'применён патч: добавлен объект `roof` (prism)',
    },
    {
      iteration: 2,
      spec: bareSpec(),
      metrics: analyzeSpec(bareSpec()).metrics,
      issues: [],
      patch: { applied: false, errors: [], changes: [] },
      score: 55,
      scoreGain: 15,
      sev: { critical: 0, major: 0, minor: 0 },
      analysisOk: true,
      verdict: 'всё хорошо',
      why: 'правки не требовались',
    },
  ];
  const report = renderReport({
    prompt: 'дом 8 на 8',
    spec: bareSpec(),
    history,
    stopReason: 'критик чист',
    thresholds: { ...DEFAULT_THRESHOLDS, maxIterations: 8 },
    usage: { calls: 2, promptTokens: 200, completionTokens: 100 },
  });
  assert.ok(report.startsWith('# Отчёт'));
  assert.ok(report.includes('дом 8 на 8'));
  assert.ok(report.includes('| Итерация |'));
  assert.ok(report.includes('| 1 |') && report.includes('| 2 |'));
  assert.ok(report.includes('### Итерация 1') && report.includes('### Итерация 2'));
  assert.ok(report.includes('нет крыши'));
  assert.ok(report.includes('добавлен объект `roof`'));
  assert.ok(report.includes('55'));
  assert.ok(report.includes('критик чист'));
  assert.ok(report.includes('Запросов к модели'));
  // no markdown-breaking pipes leaked from the issue text
  assert.ok(!report.includes('| нет крыши |'), 'issue text must stay inside its own cell');
});

test('renderReport: survives an empty history', () => {
  const report = renderReport({ prompt: 'x', spec: { name: 'x', objects: [] }, history: [] });
  assert.ok(report.includes('Итераций не было'));
  assert.ok(report.includes('Список пуст'));
});

test('runBuildLoop: the report mentions every iteration of a real run', async () => {
  const result = await runBuildLoop({
    prompt: 'дом 8 на 8',
    spec0: bareSpec(),
    iterations: 4,
    llm: improvingLlm({ alwaysIssues: true }),
    render: stubRender(),
  });
  for (const h of result.history) {
    assert.ok(result.report.includes(`| ${h.iteration} |`), `table row for iteration ${h.iteration}`);
    assert.ok(result.report.includes(`### Итерация ${h.iteration}`), `detail section for iteration ${h.iteration}`);
  }
  assert.ok(result.report.includes('дом 8 на 8'));
  // the report is valid-ish markdown: balanced code fences
  assert.equal((result.report.match(/```/g) || []).length % 2, 0);
});

test('evaluateSpec: returns analysis, a heuristic score and severity counts', () => {
  const evaluated = evaluateSpec(normalizeSpec(bareSpec()));
  assert.ok(evaluated.analysis);
  assert.ok(evaluated.score >= 0 && evaluated.score <= 100);
  assert.equal(evaluated.sev.critical, evaluated.analysis.metrics.counts.critical);
});

/* ========================================================================== */
/* integration with tasks A and B (skipped while they are in progress)        */
/* ========================================================================== */

test('runBuildLoop: renders real views through geometry.mjs + render.mjs when available', async (t) => {
  let geometry;
  let render;
  try {
    geometry = await import('../src/geometry.mjs');
    render = await import('../src/render.mjs');
  } catch {
    t.skip('geometry.mjs / render.mjs are not available yet');
    return;
  }
  if (typeof geometry.buildScene !== 'function' || typeof render.renderViews !== 'function') {
    t.skip('geometry/render exports are not ready');
    return;
  }
  const result = await runBuildLoop({
    prompt: 'дом 8 на 8',
    spec0: bareSpec(),
    iterations: 1,
    llm: improvingLlm({ alwaysIssues: true }),
    thresholds: { render: { width: 96, height: 72, draft: true, views: ['se'] } },
  });
  assert.equal(result.rendering, true, 'the real renderer should produce views');
  assert.deepEqual(result.history[0].views, ['se']);
  assert.ok(result.history[0].renderMs >= 0);
});
