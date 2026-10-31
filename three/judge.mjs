/** Blind A/B comparison of two builds rendered with the same voxel renderer. */
import { readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import { createLlm, askJson } from './packages/core/src/llm.mjs';
import { pluginBuildToVoxels, paletteFromVoxels } from './tools-compare.mjs';

const ORDER = process.argv[2] === 'reverse';
const pluginDir = 'out/_cmp065a18';
const threeDir = 'out/run2';
const prompt = 'детализированный средневековый дом 10 на 10 блоков: фахверк, двускатная крыша с коньком, труба, ставни, цветочные ящики, максимальная детализация';

function load(dir, prefix) {
  return ['se', 'sw', 'ne', 'front'].map((v) => readFileSync(path.join(dir, `${prefix}-${v}.png`)));
}
const A = ORDER ? { name: 'threejs', pngs: load(threeDir, 'final') } : { name: 'plugin', pngs: load(pluginDir, 'plugin') };
const B = ORDER ? { name: 'plugin', pngs: load(pluginDir, 'plugin') } : { name: 'threejs', pngs: load(threeDir, 'final') };

const sys = `You are a strict architectural judge for Minecraft builds. You see renders of two builds made for the same request.
Judge each independently 0-10 on: silhouette, roof, facade detail, openings, style fidelity, overall.
Be harsh and concrete. Answer with ONE JSON object.`;
const user = `Request: "${prompt}"

BUILD A: ${A.pngs.length} images attached first (order: se, sw, ne, front).
BUILD B: the next ${B.pngs.length} images (same order).

Return JSON:
{"A":{"silhouette":0,"roof":0,"facade":0,"openings":0,"style":0,"overall":0,"total":0,"notes":"..."},
 "B":{"silhouette":0,"roof":0,"facade":0,"openings":0,"style":0,"overall":0,"total":0,"notes":"..."},
 "winner":"A|B|tie","reason":"..."}
total = 0-100.`;

const llm = await createLlm({ effort: 'medium', maxTokens: 4000 });
const res = await askJson(llm, { system: sys, user, images: [...A.pngs, ...B.pngs] });
const r = res.value;
const map = { A: A.name, B: B.name };
console.log('A =', A.name, 'B =', B.name, '(order', ORDER ? 'reversed' : 'normal', ')');
console.log('A total:', r.A.total, '| B total:', r.B.total, '| winner:', r.winner, '->', map[r.winner] || 'tie');
console.log('reason:', r.reason);
console.log('A notes:', r.A.notes);
console.log('B notes:', r.B.notes);
