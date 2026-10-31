/**
 * Material contract between the three.js scene and Minecraft.
 *
 * The scene only knows palette keys ('wall', 'roof', ...) and colors. The transfer module maps a
 * palette key (or a bare hex color) to Minecraft blocks, and a subset of those are "family" blocks
 * that the voxelizer may substitute with stairs/slabs/walls/panes to follow the geometry.
 *
 * Keep this file as the single source of truth so the studio preview and the Minecraft result agree.
 */

/** @typedef {{block: string, family?: 'stairs'|'slab'|'wall'|'fence'|'pane'|'carpet', variants?: string[], note?: string}} BlockMapping */

/**
 * Default palette -> Minecraft blocks. `family` marks blocks whose shape variants may be used
 * automatically by the voxelizer (e.g. oak_planks -> oak_stairs / oak_slab).
 */
export const DEFAULT_BLOCKS = /** @type {Record<string, BlockMapping>} */ ({
  stone: { block: 'stone_bricks', variants: ['stone_bricks', 'cracked_stone_bricks', 'mossy_stone_bricks'] },
  stone_dark: { block: 'deepslate_bricks', variants: ['deepslate_bricks', 'cracked_deepslate_bricks', 'polished_deepslate'] },
  wall: { block: 'white_terracotta', variants: ['white_terracotta', 'smooth_sandstone', 'calcite'] },
  frame: { block: 'dark_oak_log', variants: ['dark_oak_log', 'stripped_dark_oak_log'] },
  wood: { block: 'spruce_planks', family: 'stairs', variants: ['spruce_planks', 'spruce_stairs', 'spruce_slab'] },
  roof: { block: 'dark_oak_planks', family: 'stairs', variants: ['dark_oak_planks', 'dark_oak_stairs', 'dark_oak_slab'] },
  roof_accent: { block: 'spruce_planks', family: 'stairs', variants: ['spruce_planks', 'spruce_stairs', 'spruce_slab'] },
  glass: { block: 'glass_pane', family: 'pane', variants: ['glass_pane', 'glass'] },
  metal: { block: 'iron_block', variants: ['iron_block', 'iron_bars', 'chiseled_stone_bricks'] },
  leaf: { block: 'oak_leaves', variants: ['oak_leaves', 'azalea_leaves', 'flowering_azalea_leaves'] },
  plain: { block: 'stone', variants: ['stone', 'andesite', 'smooth_stone'] },
  gold: { block: 'gold_block', variants: ['gold_block', 'waxed_copper_block'] },
  water: { block: 'water', note: 'enclosed basins only' },
  brick: { block: 'bricks', variants: ['bricks', 'mud_bricks'] },
});

/** Families whose variant set the voxelizer may choose from (stairs/slab need orientation states). */
export const FAMILY_BLOCK_PATTERNS = {
  stairs: (base) => base.replace(/_planks$/, '_stairs').replace(/_bricks$/, '_brick_stairs'),
  slab: (base) => base.replace(/_planks$/, '_slab').replace(/_bricks$/, '_brick_slab'),
};

/** Nearest palette key for an arbitrary hex color (used when the spec embeds raw colors). */
export function nearestPaletteKey(hex, palette) {
  const rgb = parseHex(hex);
  if (!rgb) return 'plain';
  let best = 'plain';
  let bestD = Infinity;
  for (const [key, value] of Object.entries(palette || {})) {
    const c = parseHex(value);
    if (!c) continue;
    const d = (c[0] - rgb[0]) ** 2 + (c[1] - rgb[1]) ** 2 + (c[2] - rgb[2]) ** 2;
    if (d < bestD) {
      bestD = d;
      best = key;
    }
  }
  return best;
}

export function parseHex(hex) {
  if (typeof hex !== 'string') return null;
  let s = hex.replace('#', '').trim();
  if (s.length === 3) s = s.split('').map((c) => c + c).join('');
  if (!/^[0-9a-f]{6}$/i.test(s)) return null;
  return [parseInt(s.slice(0, 2), 16), parseInt(s.slice(2, 4), 16), parseInt(s.slice(4, 6), 16)];
}

/** Resolves a spec material (palette key or hex) to its block mapping. */
export function resolveMaterial(material, palette, blocks = DEFAULT_BLOCKS) {
  if (blocks[material]) return blocks[material];
  const key = nearestPaletteKey(material, palette);
  return blocks[key] || { block: 'stone' };
}
