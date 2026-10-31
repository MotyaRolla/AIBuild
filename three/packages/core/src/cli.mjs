#!/usr/bin/env node
/**
 * aibuild3d — command line for the three.js pipeline.
 *
 *   node packages/core/src/cli.mjs spec <prompt>            design a spec from a prompt (1 AI call)
 *   node packages/core/src/cli.mjs build <prompt> [--iters N]  full loop: design -> render -> critique -> patch
 *   node packages/core/src/cli.mjs render <spec.json>       render views of an existing spec
 *   node packages/core/src/cli.mjs transfer <spec.json>     voxelise + write .litematic + commands
 *   node packages/core/src/cli.mjs info <spec.json>         validate + digest a spec
 *
 * Everything is written under three/out/<buildId>/ (spec versions, renders, report, litematic).
 * The AI key comes from COMMANDCODE_API_KEY or /root/ai-build/.cc_key; models/effort via flags.
 */
import { mkdir, writeFile, readFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { normalizeSpec, validateSpec, specDigest, specBounds } from './spec.mjs';
import { createLlm, askJson, parseJsonLoose } from './llm.mjs';
import { SPEC_SYSTEM, initialPrompt, critiquePrompt, applyPatch, scoreFromCritique, severityCounts } from './generate.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '../../..');
const OUT = path.join(ROOT, 'out');

export function newBuildId() {
  const t = new Date();
  const stamp = `${t.getFullYear()}${String(t.getMonth() + 1).padStart(2, '0')}${String(t.getDate()).padStart(2, '0')}-${String(t.getHours()).padStart(2, '0')}${String(t.getMinutes()).padStart(2, '0')}${String(t.getSeconds()).padStart(2, '0')}`;
  return `${stamp}-${Math.random().toString(16).slice(2, 6)}`;
}

export async function buildDir(id) {
  const d = path.join(OUT, id);
  await mkdir(d, { recursive: true });
  return d;
}

/** Loads an optional module; returns null when another agent has not written it yet. */
async function tryImport(rel) {
  try {
    return await import(rel);
  } catch (e) {
    if (e.code === 'ERR_MODULE_NOT_FOUND') return null;
    throw e;
  }
}

function parseArgs(argv) {
  const args = { _: [] };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a.startsWith('--')) {
      const key = a.slice(2);
      const next = argv[i + 1];
      if (next && !next.startsWith('--')) {
        args[key] = next;
        i++;
      } else args[key] = true;
    } else args._.push(a);
  }
  return args;
}

/**
 * Renders views of a spec from the VOXEL grid (pixels of what Minecraft will get), falling back to the
 * smooth polygon renderer only when the voxel pipeline is unavailable.
 *
 * @returns {Promise<{views: {name: string, png: Buffer}[], note?: string, voxels?: number}>}
 */
export async function renderSpec(spec, { width = 480, height = 360, views, draft = false, smooth = false } = {}) {
  const geom = await tryImport('./geometry.mjs');
  const voxelRender = await tryImport('./voxel-render.mjs');
  if (geom && voxelRender && !smooth) {
    const vox = geom.voxelizeSpec(spec);
    const out = await voxelRender.renderVoxelViews(
      { voxels: vox, palette: spec.palette },
      { width, height, views: views || ['se', 'sw', 'ne', 'front'], supersample: draft ? 1 : 2, seed: spec.seed ?? 1 },
    );
    return { views: out.views, voxels: vox.size };
  }
  const render = await tryImport('./render.mjs');
  if (!geom || !render) return { views: [], note: 'geometry/render modules not available yet' };
  const scene = geom.buildScene(spec);
  const out = await render.renderViews(scene, { width, height, draft, views });
  return { views: out.views, scene };
}

/** One AI-prompted design step with retries on invalid JSON. */
export async function designSpec(llm, prompt, { log = console.log } = {}) {
  let lastErr = null;
  for (let attempt = 0; attempt < 3; attempt++) {
    const hint = attempt === 0 ? '' : `\n\nYour previous answer was rejected: ${lastErr}\nReturn a corrected StructureSpec.`;
    try {
      const res = await askJson(llm, { system: SPEC_SYSTEM, user: initialPrompt(prompt) + hint });
      const spec = normalizeSpec(res.value);
      const v = validateSpec(spec);
      if (!v.ok) {
        lastErr = `invalid spec: ${v.errors.slice(0, 6).join('; ')}`;
        log(`  attempt ${attempt + 1}: ${lastErr}`);
        continue;
      }
      return spec;
    } catch (e) {
      lastErr = e.message;
      log(`  attempt ${attempt + 1}: ${lastErr}`);
    }
  }
  throw new Error(`could not design a valid spec: ${lastErr}`);
}

/** The core loop: render -> critique -> patch, N times. */
export async function runLoop({ prompt, llm, iterations = 6, width = 480, height = 360, log = console.log, id = newBuildId() }) {
  const dir = await buildDir(id);
  const history = [];
  let spec = await designSpec(llm, prompt, { log });
  await writeFile(path.join(dir, 'spec-v0.json'), JSON.stringify(spec, null, 2));
  log(`build ${id}: initial spec, ${spec.objects.length} objects, ${specBounds(spec).size.join('x')}`);

  for (let it = 1; it <= iterations; it++) {
    const { views } = await renderSpec(spec, { width, height });
    let score = null;
    let issues = [];
    let verdict = '';
    let patched = null;
    if (views.length) {
      for (const v of views) await writeFile(path.join(dir, `v${it}-${v.name}.png`), v.png);
    }
    const images = views.slice(0, 6).map((v) => v.png);
    try {
      const res = await askJson(llm, {
        system: SPEC_SYSTEM,
        user: critiquePrompt({
          request: prompt,
          spec,
          analysis: { bounds: specBounds(spec), objects: spec.objects.length, voxels: views.voxels, views: views.map((v) => v.name) },
          viewNames: views.map((v) => v.name),
          iteration: it,
          history: history.slice(-3).map((h) => `iteration ${h.iteration}: ${h.verdict}`).join('\n'),
        }),
        images,
      });
      issues = res.value.issues || [];
      verdict = res.value.verdict || '';
      score = scoreFromCritique(res.value);
      patched = applyPatch(spec, res.value.patch || {});
    } catch (e) {
      verdict = `critic failed: ${e.message}`;
      log(`iteration ${it}: ${verdict}`);
      break;
    }
    const sev = severityCounts(issues);
    history.push({ iteration: it, score, verdict, issues, sev, applied: patched.applied, errors: patched.errors });
    log(`iteration ${it}: score ${score ?? '?'}, issues ${sev.critical}c/${sev.major}m/${sev.minor}min, patch ${patched.applied ? 'applied' : 'rejected'}${patched.errors.length ? ' (' + patched.errors.slice(0, 2).join('; ') + ')' : ''}`);
    if (patched.applied) {
      spec = patched.spec;
      await writeFile(path.join(dir, `spec-v${it}.json`), JSON.stringify(spec, null, 2));
    }
    if (sev.critical === 0 && sev.major === 0 && !patched.applied) {
      log(`iteration ${it}: critic is satisfied, stopping`);
      break;
    }
  }
  await writeFile(path.join(dir, 'spec.json'), JSON.stringify(spec, null, 2));
  await writeFile(path.join(dir, 'history.json'), JSON.stringify(history, null, 2));
  await writeFile(path.join(dir, 'report.md'), renderReport({ id, prompt, spec, history }));
  return { id, dir, spec, history };
}

/** Markdown report of a build loop. */
export function renderReport({ id, prompt, spec, history }) {
  const b = specBounds(spec);
  const lines = [
    `# ${spec.name || 'Постройка'} (${id})`,
    '',
    `**Запрос:** ${prompt}`,
    '',
    `Итог: ${spec.objects.length} объектов, размер ${b.size.join(' × ')} блоков, итераций: ${history.length}.`,
    '',
    '| Итерация | Оценка | Критично | Серьёзно | Мелочи | Правки | Замечание |',
    '|---|---|---|---|---|---|---|',
  ];
  for (const h of history) {
    lines.push(`| ${h.iteration} | ${h.score ?? '—'} | ${h.sev.critical} | ${h.sev.major} | ${h.sev.minor} | ${h.applied ? 'применены' : 'нет'} | ${(h.verdict || '').replace(/\|/g, '/').slice(0, 120)} |`);
  }
  lines.push('', '## Объекты', '');
  for (const o of spec.objects) lines.push(`- \`${o.id || '?'}\` — ${o.type}${o.material ? ` (${o.material})` : ''}`);
  return lines.join('\n');
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const cmd = args._[0];
  if (!cmd || cmd === 'help') {
    console.log('usage: node packages/core/src/cli.mjs <spec|build|render|transfer|info> ...');
    return;
  }
  const llmOpts = {
    effort: args.effort ?? 'low',
    maxTokens: Number(args['max-tokens'] || 32000),
    model: args.model,
  };
  if (cmd === 'info') {
    const spec = normalizeSpec(JSON.parse(await readFile(args._[1], 'utf8')));
    const v = validateSpec(spec);
    console.log(`ok=${v.ok} objects=${spec.objects.length} bounds=${specBounds(spec).size.join('x')}`);
    if (v.errors.length) console.log('errors:', v.errors.slice(0, 10));
    if (v.warnings.length) console.log('warnings:', v.warnings.slice(0, 10));
    console.log(specDigest(spec, 20));
    return;
  }
  if (cmd === 'render') {
    const spec = normalizeSpec(JSON.parse(await readFile(args._[1], 'utf8')));
    const id = args.id || newBuildId();
    const dir = await buildDir(id);
    const { views, note } = await renderSpec(spec, { width: Number(args.width || 640), height: Number(args.height || 480) });
    if (note) console.log(note);
    for (const v of views) {
      await writeFile(path.join(dir, `${v.name}.png`), v.png);
      console.log(`wrote ${path.join(dir, v.name + '.png')} (${v.png.length} bytes)`);
    }
    return;
  }
  if (cmd === 'transfer') {
    const spec = normalizeSpec(JSON.parse(await readFile(args._[1], 'utf8')));
    const transfer = await tryImport('../../../transfer/src/index.mjs');
    if (!transfer) {
      console.log('transfer package not available yet');
      return;
    }
    const id = args.id || newBuildId();
    const dir = await buildDir(id);
    const res = transfer.transferToMinecraft(spec, { origin: [0, 0, 0] });
    console.log(`blocks: ${res.blocks.size}`, JSON.stringify(res.stats));
    if (res.warnings?.length) console.log('warnings:', res.warnings.slice(0, 5));
    const litematic = transfer.toLitematic(res.blocks, { name: spec.name, author: 'AIBuild', description: spec.notes || '' });
    await writeFile(path.join(dir, 'structure.litematic'), litematic);
    await writeFile(path.join(dir, 'commands.txt'), transfer.toCommands(res.blocks).join('\n'));
    await writeFile(path.join(dir, 'blocks.json'), JSON.stringify([...res.blocks], null, 0));
    console.log(`wrote ${path.join(dir, 'structure.litematic')} (${litematic.length} bytes)`);
    return;
  }
  if (cmd === 'spec') {
    const prompt = args._.slice(1).join(' ') || 'детализированный средневековый дом 10 на 10 блоков';
    const llm = await createLlm(llmOpts);
    const spec = await designSpec(llm, prompt);
    const id = args.id || newBuildId();
    const dir = await buildDir(id);
    await writeFile(path.join(dir, 'spec.json'), JSON.stringify(spec, null, 2));
    console.log(`wrote ${path.join(dir, 'spec.json')}: ${spec.objects.length} objects, ${specBounds(spec).size.join('x')}`);
    console.log('llm stats:', JSON.stringify(llm.stats));
    return;
  }
  if (cmd === 'build') {
    const prompt = args._.slice(1).join(' ') || 'детализированный средневековый дом 10 на 10 блоков';
    const llm = await createLlm(llmOpts);
    const res = await runLoop({ prompt, llm, iterations: Number(args.iters || 6), width: Number(args.width || 480), height: Number(args.height || 360) });
    console.log(`done: ${path.join(res.dir, 'report.md')}`);
    console.log('llm stats:', JSON.stringify(llm.stats));
    return;
  }
  console.log(`unknown command: ${cmd}`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url))) {
  main().catch((e) => {
    console.error('ERROR:', e.message);
    process.exit(1);
  });
}
