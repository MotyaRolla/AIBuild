/** Renders a Minecraft plugin build (.json.gz) with the same voxel renderer, for A/B judging. */
import { createReadStream } from 'node:fs';
import { createWriteStream } from 'node:fs';
import { mkdir } from 'node:fs/promises';
import path from 'node:path';
import { gunzipSync } from 'node:zlib';
import { readFileSync } from 'node:fs';
import { renderVoxelViews } from './packages/core/src/voxel-render.mjs';

const COLORS = {
  stone: '#8a8a8a', stone_bricks: '#7d7d7d', cobblestone: '#6f6f6f', mossy_cobblestone: '#5d6b4e',
  deepslate: '#4a4a52', deepslate_bricks: '#3f3f47', andesite: '#86878a', polished_andesite: '#9a9b9e',
  plaster: '#d9cfba', white_terracotta: '#d8cdb8', calcite: '#dfdcd3', smooth_sandstone: '#dbd0b0',
  dark_oak: '#3a2a1c', spruce: '#6b4a2f', oak: '#8a6242', birch: '#c8b98a',
  glass: '#a9c9d9', iron: '#4a4a52', gold: '#d4af37', brick: '#8b4a3a', bricks: '#8b4a3a',
  leaves: '#4e7a34', grass: '#5d8a3a', dirt: '#7a5c3a', gravel: '#8a8580', water: '#3a6ea8',
  lantern: '#e8c46a', torch: '#e8a54a', hay: '#c8a03a', copper: '#b06a3a', amethyst: '#8a5aa8',
};
function colorFor(name) {
  const n = String(name).toLowerCase();
  for (const [k, v] of Object.entries(COLORS)) if (n.includes(k)) return v;
  if (/planks|log|wood|fence|trapdoor|door|sign|shelf/.test(n)) return COLORS.oak;
  if (/stairs|slab|wall/.test(n)) return COLORS.stone;
  if (/terracotta|concrete/.test(n)) return '#b8a888';
  if (/pane|glass/.test(n)) return COLORS.glass;
  return '#949494';
}
export function pluginBuildToVoxels(file, version = null) {
  const raw = JSON.parse(gunzipSync(readFileSync(file)).toString('utf8'));
  const v = version === null ? raw.currentVersion ?? raw.versions.length - 1 : version;
  const model = raw.versions[v].model;
  const palette = model.palette;
  const cells = model.cells;
  const voxels = new Map();
  for (let i = 0; i + 3 < cells.length; i += 4) {
    const [x, y, z, p] = [cells[i], cells[i + 1], cells[i + 2], cells[i + 3]];
    const state = palette[p];
    const name = state.split('[')[0];
    if (name === 'air' || name === 'cave_air') continue;
    voxels.set(`${x}|${y}|${z}`, { material: name, _color: colorFor(name) });
  }
  return { voxels, name: raw.name, version: raw.versions[v].number, score: raw.versions[v].score };
}
export function paletteFromVoxels(voxels) {
  const p = {};
  for (const c of voxels.values()) p[c.material] = c._color || colorFor(c.material);
  return p;
}
if (process.argv[1]?.endsWith('tools-compare.mjs')) {
  const [file, outDir, ...views] = process.argv.slice(2);
  const b = pluginBuildToVoxels(file);
  const palette = paletteFromVoxels(b.voxels);
  await mkdir(outDir, { recursive: true });
  const out = await renderVoxelViews({ voxels: b.voxels, palette }, { width: 512, height: 384, views: views.length ? views : ['se', 'sw', 'ne', 'front'], supersample: 2 });
  const fs = await import('node:fs/promises');
  for (const v of out.views) await fs.writeFile(path.join(outDir, `plugin-${v.name}.png`), v.png);
  console.log(JSON.stringify({ name: b.name, version: b.version, score: b.score, voxels: b.voxels.size, views: out.views.length }));
}
