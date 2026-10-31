#!/usr/bin/env node
/**
 * aibuild3d — the full pipeline: prompt -> iterative 3D design -> Minecraft structure.
 *
 * Every iteration renders the build FROM ITS VOXELS (pixel-per-block-face), so the AI critiques
 * exactly what will end up in Minecraft. The final version is transferred to blocks and written
 * as a .litematic plus a list of /setblock commands.
 *
 *   node packages/core/src/pipeline.mjs "средневековый дом 10 на 10" --iters 8
 *   node packages/core/src/pipeline.mjs --spec samples/house-10x10.json --transfer   (no AI)
 *
 * Options: --iters N, --effort off|low|medium|high, --width, --height, --views se,sw,ne,front,
 *          --out DIR, --no-transfer, --max-tokens, --model
 */
import { mkdir, writeFile, readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { normalizeSpec, specBounds } from './spec.mjs';
import { voxelizeSpec } from './geometry.mjs';
import { renderVoxelViews, VOXEL_VIEWS } from './voxel-render.mjs';
import { createLlm } from './llm.mjs';
import { runBuildLoop } from './loop.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(HERE, '../../..');
const OUT = path.join(ROOT, 'out');

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
 * The renderer handed to the build loop: voxel pixels, not triangles.
 *
 * The loop calls `render(scene, {spec, ...})`, so both a prebuilt Scene and a bare spec are
 * accepted — scene.spec is preferred, and the scene is reused when it is already there.
 *
 * @param {object|{spec:object, solids:Array}} sceneOrSpec a Scene from geometry.buildScene, or a StructureSpec
 * @param {{width?:number,height?:number,views?:string[],supersample?:number,spec?:object}} opts
 */
export async function renderVoxelSpec(sceneOrSpec, opts = {}) {
  const spec = opts.spec || (sceneOrSpec && Array.isArray(sceneOrSpec.solids) ? sceneOrSpec.spec : sceneOrSpec);
  if (!spec || !Array.isArray(spec.objects)) {
    throw new Error('renderVoxelSpec: no spec (expected a StructureSpec or a Scene with .spec)');
  }
  const scene = sceneOrSpec && Array.isArray(sceneOrSpec.solids) ? sceneOrSpec : undefined;
  const voxels = voxelizeSpec(spec, scene ? { scene } : undefined);
  const views = opts.views || ['se', 'sw', 'ne', 'front'];
  const out = await renderVoxelViews(
    { voxels, palette: spec.palette },
    {
      width: opts.width || 512,
      height: opts.height || 384,
      views,
      supersample: opts.supersample ?? 1,
      seed: spec.seed ?? 1,
    },
  );
  return { views: out.views, voxels: voxels.size };
}

/** Transfers a spec to Minecraft and writes .litematic + commands. */
export async function transferSpec(spec, dir) {
  const transfer = await import('../../transfer/src/index.mjs');
  const res = transfer.transferToMinecraft(spec, { origin: [0, 0, 0] });
  const litematic = transfer.toLitematic(res.blocks, {
    name: spec.name,
    author: 'AIBuild 3D',
    description: spec.notes || '',
  });
  await mkdir(dir, { recursive: true });
  await writeFile(path.join(dir, 'structure.litematic'), litematic);
  await writeFile(path.join(dir, 'commands.txt'), transfer.toCommands(res.blocks).join('\n'));
  const analyze = transfer.analyzeResult(res.blocks);
  const report = {
    blocks: res.blocks.size,
    stats: res.stats,
    litematicBytes: litematic.length,
    errors: analyze.errors,
    warnings: analyze.warnings,
  };
  await writeFile(path.join(dir, 'transfer.json'), JSON.stringify(report, null, 2));
  return report;
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const prompt = args._.join(' ').trim();
  if (!prompt && !args.spec) {
    console.log('usage: node packages/core/src/pipeline.mjs "<prompt>" [--iters 8] [--effort low]');
    console.log('       node packages/core/src/pipeline.mjs --spec samples/house-10x10.json --transfer');
    return;
  }
  const id = args.id || new Date().toISOString().replace(/[-:T.]/g, '').slice(0, 14);
  const dir = path.join(OUT, id);
  await mkdir(dir, { recursive: true });
  const views = String(args.views || 'se,sw,ne,front').split(',').map((s) => s.trim()).filter(Boolean);
  for (const v of views) if (!VOXEL_VIEWS[v]) throw new Error(`unknown view ${v}; known: ${Object.keys(VOXEL_VIEWS).join(', ')}`);

  // no-AI path: transfer an existing spec straight away
  if (args.spec) {
    const spec = normalizeSpec(JSON.parse(await readFile(args.spec, 'utf8')));
    await writeFile(path.join(dir, 'spec.json'), JSON.stringify(spec, null, 2));
    const rendered = await renderVoxelSpec(spec, { views, width: Number(args.width || 800), height: Number(args.height || 600), supersample: 2 });
    for (const v of rendered.views) await writeFile(path.join(dir, `${v.name}.png`), v.png);
    console.log(`rendered ${rendered.views.length} views, ${rendered.voxels} voxels`);
    if (args.transfer) {
      const t = await transferSpec(spec, dir);
      console.log(`transfer: ${t.blocks} blocks, litematic ${t.litematicBytes} bytes, errors ${t.errors.length}`);
    }
    console.log(`out: ${dir}`);
    return;
  }

  // AI loop
  const llm = await createLlm({
    effort: args.effort || 'low',
    maxTokens: Number(args['max-tokens'] || 32000),
    model: args.model,
    log: (m) => console.log('  llm:', m),
  });
  const t0 = Date.now();
  // keep every iteration's renders on disk: the history is the point of the "many attempts" idea
  const iterationViews = [];
  const result = await runBuildLoop({
    prompt,
    iterations: Number(args.iters || 8),
    llm,
    buildId: id,
    render: async (spec, opts) => {
      const rendered = await renderVoxelSpec(spec, { ...opts, views, width: Number(args.width || 512), height: Number(args.height || 384) });
      iterationViews.push({ views: rendered.views, voxels: rendered.voxels });
      return rendered;
    },
    log: (m) => console.log(' ', m),
  });

  const spec = result.best?.spec || result.spec;
  await writeFile(path.join(dir, 'spec.json'), JSON.stringify(spec, null, 2));
  await writeFile(path.join(dir, 'history.json'), JSON.stringify(result.history, null, 2));
  await writeFile(path.join(dir, 'report.md'), result.report);

  // final renders of the chosen version (higher quality than the loop's)
  const finalViews = await renderVoxelSpec(spec, { views, width: Number(args.width || 1024), height: Number(args.height || 768), supersample: 2 });
  for (const v of finalViews.views) await writeFile(path.join(dir, `final-${v.name}.png`), v.png);
  for (let i = 0; i < result.history.length && i < iterationViews.length; i++) {
    const it = result.history[i].iteration;
    for (const v of iterationViews[i].views) {
      await writeFile(path.join(dir, `it${it}-${v.name}.png`), v.png);
    }
  }
  await writeFile(path.join(dir, 'iterations.json'), JSON.stringify(
    result.history.map((h, i) => ({
      iteration: h.iteration,
      score: h.score,
      sev: h.sev,
      voxels: iterationViews[i]?.voxels,
      objects: h.spec?.objects?.length,
      changed: h.patch?.changes ?? null,
      verdict: h.verdict,
      issues: (h.issues || []).map((x) => `${x.severity}: ${x.what}`),
    })), null, 2));

  let transfer = null;
  if (!args['no-transfer']) transfer = await transferSpec(spec, dir);

  const secs = Math.round((Date.now() - t0) / 1000);
  console.log('');
  console.log(`build ${id}: ${result.stopCode} after ${result.history.length} iterations in ${secs}s`);
  console.log(`  objects: ${spec.objects.length}, size: ${specBounds(spec).size.join('x')}`);
  console.log(`  llm: ${llm.stats.calls} calls, ${llm.stats.promptTokens} in / ${llm.stats.completionTokens} out (${llm.stats.reasoningTokens} reasoning)`);
  if (transfer) {
    console.log(`  minecraft: ${transfer.blocks} blocks, ${transfer.stats.stairs} stairs, ${transfer.stats.slabs} slabs, ${transfer.stats.floating} floating`);
    console.log(`  litematic: ${path.join(dir, 'structure.litematic')} (${transfer.litematicBytes} bytes)`);
    if (transfer.errors.length) console.log(`  transfer errors: ${transfer.errors.slice(0, 3).join('; ')}`);
  }
  console.log(`  out: ${dir}`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url))) {
  main().catch((e) => {
    console.error('ERROR:', e.message);
    if (process.env.DEBUG) console.error(e.stack);
    process.exit(1);
  });
}
