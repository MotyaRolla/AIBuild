/**
 * Block states: building, normalising, serialising and validating them.
 *
 * `transferToMinecraft` returns a `Map<key, BlockState>` where a BlockState is the JSON form
 * Minecraft itself uses for block states (the same shape as a recipe result or a structure file):
 *
 *   { Name: 'minecraft:oak_stairs', Properties: { facing: 'east', half: 'bottom', shape: 'straight' } }
 *
 * Everything here targets **Minecraft Java 26.3** (data version 5023). That matters in three ways:
 *
 * 1. Only Java Edition block ids. Bedrock-only names (`minecraft:connection_east`, `minecraft:corner`)
 *    are never produced, and unknown states are rejected by `validateBlockState`.
 * 2. Every block is checked against a registry of the block ids this project actually uses, with
 *    the exact allowed properties/values for each block *family*. A typo cannot reach a `.litematic`.
 * 3. Stairs/slabs/panes/fences are only used through the helpers below, so their required
 *    properties (`facing`/`half`/`shape` for stairs, `type` for slabs, four boolean connections for
 *    panes and fences, `axis` for logs/pillars) are always complete.
 *
 * A BlockState is immutable by convention: the helpers return new objects, never mutate input.
 */

/** Namespace every id is normalised to. */
export const NAMESPACE = 'minecraft';

/** Minecraft Java data version of 26.3. Written to `.litematic` as `MinecraftDataVersion`. */
export const DATA_VERSION_26_3 = 5023;

/** Target game version string, kept beside the data version so the two cannot drift apart. */
export const TARGET_VERSION = '26.3';

/** The four horizontal facings Minecraft uses for stairs/doors/chests/oriented blocks. */
export const FACINGS = Object.freeze(['north', 'east', 'south', 'west']);

/** Maps a horizontal step to the `facing` value of a block. */
const STEP_TO_FACING = Object.freeze({
  '1,0': 'east',
  '-1,0': 'west',
  '0,1': 'south',
  '0,-1': 'north',
});

/**
 * Stem -> block names for the shape families this project uses.
 * Building a variant from a base block is a plain string map here (not a regex) so that odd names
 * such as `dark_oak_planks` -> `dark_oak_stairs` or `stone_bricks` -> `stone_brick_stairs` are
 * explicit and testable.
 */
export const SHAPE_FAMILY = Object.freeze({
  // planks-like
  oak_planks: { stairs: 'oak_stairs', slab: 'oak_slab', fence: 'oak_fence' },
  spruce_planks: { stairs: 'spruce_stairs', slab: 'spruce_slab', fence: 'spruce_fence' },
  birch_planks: { stairs: 'birch_stairs', slab: 'birch_slab', fence: 'birch_fence' },
  jungle_planks: { stairs: 'jungle_stairs', slab: 'jungle_slab', fence: 'jungle_fence' },
  acacia_planks: { stairs: 'acacia_stairs', slab: 'acacia_slab', fence: 'acacia_fence' },
  dark_oak_planks: { stairs: 'dark_oak_stairs', slab: 'dark_oak_slab', fence: 'dark_oak_fence' },
  mangrove_planks: { stairs: 'mangrove_stairs', slab: 'mangrove_slab', fence: 'mangrove_fence' },
  cherry_planks: { stairs: 'cherry_stairs', slab: 'cherry_slab', fence: 'cherry_fence' },
  bamboo_planks: { stairs: 'bamboo_stairs', slab: 'bamboo_slab', fence: 'bamboo_fence' },
  bamboo_mosaic: { stairs: 'bamboo_mosaic_stairs', slab: 'bamboo_mosaic_slab', fence: 'bamboo_fence' },
  crimson_planks: { stairs: 'crimson_stairs', slab: 'crimson_slab', fence: 'crimson_fence' },
  warped_planks: { stairs: 'warped_stairs', slab: 'warped_slab', fence: 'warped_fence' },
  pale_oak_planks: { stairs: 'pale_oak_stairs', slab: 'pale_oak_slab', fence: 'pale_oak_fence' },
  poplar_planks: { stairs: 'poplar_stairs', slab: 'poplar_slab', fence: 'poplar_fence' },

  // stone-like
  stone: { stairs: 'stone_stairs', slab: 'stone_slab', wall: 'stone_wall' },
  smooth_stone: { slab: 'smooth_stone_slab' },
  cobblestone: { stairs: 'cobblestone_stairs', slab: 'cobblestone_slab', wall: 'cobblestone_wall' },
  mossy_cobblestone: { stairs: 'mossy_cobblestone_stairs', slab: 'mossy_cobblestone_slab', wall: 'mossy_cobblestone_wall' },
  stone_bricks: { stairs: 'stone_brick_stairs', slab: 'stone_brick_slab', wall: 'stone_brick_wall' },
  mossy_stone_bricks: { stairs: 'mossy_stone_brick_stairs', slab: 'mossy_stone_brick_slab', wall: 'mossy_stone_brick_wall' },
  cracked_stone_bricks: { stairs: 'stone_brick_stairs', slab: 'stone_brick_slab', wall: 'stone_brick_wall' },
  bricks: { stairs: 'brick_stairs', slab: 'brick_slab', wall: 'brick_wall' },
  mud_bricks: { stairs: 'mud_brick_stairs', slab: 'mud_brick_slab', wall: 'mud_brick_wall' },
  sandstone: { stairs: 'sandstone_stairs', slab: 'sandstone_slab', wall: 'sandstone_wall' },
  smooth_sandstone: { stairs: 'smooth_sandstone_stairs', slab: 'smooth_sandstone_slab', wall: 'sandstone_wall' },
  red_sandstone: { stairs: 'red_sandstone_stairs', slab: 'red_sandstone_slab', wall: 'red_sandstone_wall' },
  deepslate: { stairs: 'cobbled_deepslate_stairs', slab: 'cobbled_deepslate_slab', wall: 'cobbled_deepslate_wall' },
  polished_deepslate: { stairs: 'polished_deepslate_stairs', slab: 'polished_deepslate_slab', wall: 'polished_deepslate_wall' },
  deepslate_bricks: { stairs: 'deepslate_brick_stairs', slab: 'deepslate_brick_slab', wall: 'deepslate_brick_wall' },
  deepslate_tiles: { stairs: 'deepslate_tile_stairs', slab: 'deepslate_tile_slab', wall: 'deepslate_tile_wall' },
  tuff: { stairs: 'tuff_stairs', slab: 'tuff_slab', wall: 'tuff_wall' },
  polished_tuff: { stairs: 'polished_tuff_stairs', slab: 'polished_tuff_slab', wall: 'polished_tuff_wall' },
  tuff_bricks: { stairs: 'tuff_brick_stairs', slab: 'tuff_brick_slab', wall: 'tuff_brick_wall' },
  blackstone: { stairs: 'blackstone_stairs', slab: 'blackstone_slab', wall: 'blackstone_wall' },
  polished_blackstone: { stairs: 'polished_blackstone_stairs', slab: 'polished_blackstone_slab', wall: 'polished_blackstone_wall' },
  polished_blackstone_bricks: {
    stairs: 'polished_blackstone_brick_stairs',
    slab: 'polished_blackstone_brick_slab',
    wall: 'polished_blackstone_brick_wall',
  },
  quartz_block: { stairs: 'quartz_stairs', slab: 'quartz_slab' },
  smooth_quartz: { stairs: 'smooth_quartz_stairs', slab: 'smooth_quartz_slab' },
  purpur_block: { stairs: 'purpur_stairs', slab: 'purpur_slab' },
  end_stone_bricks: { stairs: 'end_stone_brick_stairs', slab: 'end_stone_brick_slab', wall: 'end_stone_brick_wall' },
  nether_bricks: { stairs: 'nether_brick_stairs', slab: 'nether_brick_slab', wall: 'nether_brick_wall' },
  red_nether_bricks: { stairs: 'red_nether_brick_stairs', slab: 'red_nether_brick_slab', wall: 'red_nether_brick_wall' },
  prismarine: { stairs: 'prismarine_stairs', slab: 'prismarine_slab', wall: 'prismarine_wall' },
  prismarine_bricks: { stairs: 'prismarine_brick_stairs', slab: 'prismarine_brick_slab' },
  dark_prismarine: { stairs: 'dark_prismarine_stairs', slab: 'dark_prismarine_slab' },
  andesite: { stairs: 'andesite_stairs', slab: 'andesite_slab', wall: 'andesite_wall' },
  polished_andesite: { stairs: 'polished_andesite_stairs', slab: 'polished_andesite_slab' },
  diorite: { stairs: 'diorite_stairs', slab: 'diorite_slab', wall: 'diorite_wall' },
  granite: { stairs: 'granite_stairs', slab: 'granite_slab', wall: 'granite_wall' },

  // terracotta: 26.3 has terracotta slabs but no terracotta stairs
  white_terracotta: { slab: 'white_terracotta_slab' },
  light_gray_terracotta: { slab: 'light_gray_terracotta_slab' },
  gray_terracotta: { slab: 'gray_terracotta_slab' },

  // copper
  copper_block: { stairs: 'cut_copper_stairs', slab: 'cut_copper_slab' },
  waxed_copper_block: { stairs: 'waxed_cut_copper_stairs', slab: 'waxed_cut_copper_slab' },

  // concrete / wool (stairs + slabs added in 26.2/26.3)
  white_concrete: { stairs: 'white_concrete_stairs', slab: 'white_concrete_slab' },
  light_gray_concrete: { stairs: 'light_gray_concrete_stairs', slab: 'light_gray_concrete_slab' },
  gray_concrete: { stairs: 'gray_concrete_stairs', slab: 'gray_concrete_slab' },
  black_concrete: { stairs: 'black_concrete_stairs', slab: 'black_concrete_slab' },
  white_wool: { stairs: 'white_wool_stairs', slab: 'white_wool_slab' },
  gray_wool: { stairs: 'gray_wool_stairs', slab: 'gray_wool_slab' },
  black_wool: { stairs: 'black_wool_stairs', slab: 'black_wool_slab' },
});

/**
 * Blocks that share the stairs property set (facing/half/shape/waterlogged).
 * Everything in SHAPE_FAMILY's `stairs` values is covered, plus the plain list below.
 */
const EXTRA_STAIRS = [
  'stone_stairs', 'cobblestone_stairs', 'mossy_cobblestone_stairs',
  'stone_brick_stairs', 'mossy_stone_brick_stairs', 'brick_stairs', 'mud_brick_stairs',
  'sandstone_stairs', 'smooth_sandstone_stairs', 'smooth_red_sandstone_stairs', 'red_sandstone_stairs',
  'cobbled_deepslate_stairs',
  'polished_deepslate_stairs', 'deepslate_brick_stairs', 'deepslate_tile_stairs',
  'tuff_stairs', 'polished_tuff_stairs', 'tuff_brick_stairs', 'blackstone_stairs',
  'polished_blackstone_stairs', 'polished_blackstone_brick_stairs', 'quartz_stairs',
  'smooth_quartz_stairs', 'purpur_stairs', 'end_stone_brick_stairs', 'nether_brick_stairs',
  'red_nether_brick_stairs', 'prismarine_stairs', 'prismarine_brick_stairs', 'dark_prismarine_stairs',
  'andesite_stairs', 'polished_andesite_stairs', 'diorite_stairs', 'polished_diorite_stairs',
  'granite_stairs', 'polished_granite_stairs',
  'cinnabar_stairs', 'polished_cinnabar_stairs', 'cinnabar_brick_stairs',
  'sulfur_stairs', 'polished_sulfur_stairs', 'sulfur_brick_stairs',
  'cut_copper_stairs', 'exposed_cut_copper_stairs', 'weathered_cut_copper_stairs',
  'oxidized_cut_copper_stairs', 'waxed_cut_copper_stairs', 'waxed_exposed_cut_copper_stairs',
  'waxed_weathered_cut_copper_stairs', 'waxed_oxidized_cut_copper_stairs',
  'oak_stairs', 'spruce_stairs', 'birch_stairs', 'jungle_stairs', 'acacia_stairs',
  'dark_oak_stairs', 'mangrove_stairs', 'cherry_stairs', 'bamboo_stairs', 'bamboo_mosaic_stairs',
  'crimson_stairs', 'warped_stairs', 'pale_oak_stairs', 'poplar_stairs',
  'resin_brick_stairs',
  'white_concrete_stairs', 'light_gray_concrete_stairs', 'gray_concrete_stairs',
  'black_concrete_stairs', 'brown_concrete_stairs', 'red_concrete_stairs',
  'orange_concrete_stairs', 'yellow_concrete_stairs', 'lime_concrete_stairs',
  'green_concrete_stairs', 'cyan_concrete_stairs', 'light_blue_concrete_stairs',
  'blue_concrete_stairs', 'purple_concrete_stairs', 'magenta_concrete_stairs',
  'pink_concrete_stairs',
  'white_wool_stairs', 'light_gray_wool_stairs', 'gray_wool_stairs', 'black_wool_stairs',
  'brown_wool_stairs', 'red_wool_stairs', 'orange_wool_stairs', 'yellow_wool_stairs',
  'lime_wool_stairs', 'green_wool_stairs', 'cyan_wool_stairs', 'light_blue_wool_stairs',
  'blue_wool_stairs', 'purple_wool_stairs', 'magenta_wool_stairs', 'pink_wool_stairs',
];

/** Slabs that share the slab property set (type/waterlogged). */
const EXTRA_SLABS = [
  'stone_slab', 'smooth_stone_slab', 'cobblestone_slab', 'mossy_cobblestone_slab',
  'stone_brick_slab', 'mossy_stone_brick_slab', 'brick_slab', 'mud_brick_slab',
  'sandstone_slab', 'smooth_sandstone_slab', 'smooth_red_sandstone_slab', 'red_sandstone_slab',
  'cut_sandstone_slab', 'cut_red_sandstone_slab', 'cobbled_deepslate_slab',
  'polished_deepslate_slab', 'deepslate_brick_slab', 'deepslate_tile_slab', 'tuff_slab',
  'polished_tuff_slab', 'tuff_brick_slab', 'blackstone_slab', 'polished_blackstone_slab',
  'polished_blackstone_brick_slab', 'quartz_slab', 'smooth_quartz_slab', 'purpur_slab',
  'end_stone_brick_slab', 'nether_brick_slab', 'red_nether_brick_slab', 'prismarine_slab',
  'prismarine_brick_slab', 'dark_prismarine_slab', 'andesite_slab', 'polished_andesite_slab',
  'diorite_slab', 'polished_diorite_slab', 'granite_slab', 'polished_granite_slab',
  'cinnabar_slab', 'polished_cinnabar_slab', 'cinnabar_brick_slab',
  'sulfur_slab', 'polished_sulfur_slab', 'sulfur_brick_slab',
  'cut_copper_slab', 'exposed_cut_copper_slab', 'weathered_cut_copper_slab',
  'oxidized_cut_copper_slab', 'waxed_cut_copper_slab', 'waxed_exposed_cut_copper_slab',
  'waxed_weathered_cut_copper_slab', 'waxed_oxidized_cut_copper_slab',
  'oak_slab', 'spruce_slab', 'birch_slab', 'jungle_slab', 'acacia_slab', 'dark_oak_slab',
  'mangrove_slab', 'cherry_slab', 'bamboo_slab', 'bamboo_mosaic_slab', 'crimson_slab',
  'warped_slab', 'pale_oak_slab', 'poplar_slab', 'resin_brick_slab',
  'white_concrete_slab', 'light_gray_concrete_slab', 'gray_concrete_slab',
  'black_concrete_slab', 'brown_concrete_slab', 'red_concrete_slab', 'orange_concrete_slab',
  'yellow_concrete_slab', 'lime_concrete_slab', 'green_concrete_slab', 'cyan_concrete_slab',
  'light_blue_concrete_slab', 'blue_concrete_slab', 'purple_concrete_slab',
  'magenta_concrete_slab', 'pink_concrete_slab',
  'white_wool_slab', 'light_gray_wool_slab', 'gray_wool_slab', 'black_wool_slab',
  'brown_wool_slab', 'red_wool_slab', 'orange_wool_slab', 'yellow_wool_slab', 'lime_wool_slab',
  'green_wool_slab', 'cyan_wool_slab', 'light_blue_wool_slab', 'blue_wool_slab',
  'purple_wool_slab', 'magenta_wool_slab', 'pink_wool_slab',
];

/** Walls that share the wall property set (up/east/north/south/west/waterlogged). */
const EXTRA_WALLS = [
  'cobblestone_wall', 'mossy_cobblestone_wall', 'stone_brick_wall', 'mossy_stone_brick_wall',
  'brick_wall', 'mud_brick_wall', 'sandstone_wall', 'red_sandstone_wall', 'cobbled_deepslate_wall',
  'polished_deepslate_wall', 'deepslate_brick_wall', 'deepslate_tile_wall', 'tuff_wall',
  'polished_tuff_wall', 'tuff_brick_wall', 'blackstone_wall', 'polished_blackstone_wall',
  'polished_blackstone_brick_wall', 'end_stone_brick_wall', 'nether_brick_wall',
  'red_nether_brick_wall', 'prismarine_wall', 'andesite_wall', 'diorite_wall', 'granite_wall',
];

/** Fences (four boolean connections + waterlogged). */
const EXTRA_FENCES = [
  'oak_fence', 'spruce_fence', 'birch_fence', 'jungle_fence', 'acacia_fence', 'dark_oak_fence',
  'mangrove_fence', 'cherry_fence', 'bamboo_fence', 'crimson_fence', 'warped_fence',
  'pale_oak_fence', 'poplar_fence', 'nether_brick_fence',
];

/** Logs / pillars: only `axis` (+ wood variants are not used here). */
const LOG_BLOCKS = [
  'oak_log', 'spruce_log', 'birch_log', 'jungle_log', 'acacia_log', 'dark_oak_log',
  'mangrove_log', 'cherry_log', 'pale_oak_log', 'poplar_log', 'crimson_stem', 'warped_stem',
  'stripped_dark_oak_log', 'stripped_oak_log', 'stripped_spruce_log',
  'deepslate_pillar', 'quartz_pillar', 'purpur_pillar', 'basalt', 'bone_block',
  'polished_basalt', 'hay_block',
];

const PROPERTIES = {
  stairs: {
    facing: [...FACINGS],
    half: ['bottom', 'top'],
    shape: ['straight', 'inner_left', 'inner_right', 'outer_left', 'outer_right'],
    waterlogged: ['false', 'true'],
  },
  slab: { type: ['top', 'bottom', 'double'], waterlogged: ['false', 'true'] },
  wall: {
    up: ['false', 'true'],
    east: ['false', 'true', 'low', 'tall'],
    north: ['false', 'true', 'low', 'tall'],
    south: ['false', 'true', 'low', 'tall'],
    west: ['false', 'true', 'low', 'tall'],
    waterlogged: ['false', 'true'],
  },
  fence: {
    east: ['false', 'true'],
    north: ['false', 'true'],
    south: ['false', 'true'],
    west: ['false', 'true'],
    waterlogged: ['false', 'true'],
  },
  pane: {
    east: ['false', 'true'],
    north: ['false', 'true'],
    south: ['false', 'true'],
    west: ['false', 'true'],
    waterlogged: ['false', 'true'],
  },
  axis: { axis: ['x', 'y', 'z'] },
  axisXZ: { axis: ['x', 'z'] },
  none: {},
};

/** Every block id this module may ever emit, mapped to its property schema. */
const REGISTRY = new Map();

function register(names, schema) {
  for (const name of names) REGISTRY.set(name, schema);
}

function collectFamily(key) {
  return Object.values(SHAPE_FAMILY).map((family) => family[key]).filter(Boolean);
}

register([...new Set([...collectFamily('stairs'), ...EXTRA_STAIRS])], PROPERTIES.stairs);
register([...new Set([...collectFamily('slab'), ...EXTRA_SLABS])], PROPERTIES.slab);
register([...new Set([...collectFamily('wall'), ...EXTRA_WALLS])], PROPERTIES.wall);
register([...new Set([...collectFamily('fence'), ...EXTRA_FENCES])], PROPERTIES.fence);
register(['glass_pane', 'iron_bars'], PROPERTIES.pane);
register(LOG_BLOCKS, PROPERTIES.axis);
register(
  [
    // simple full cubes used by the default palette and by foundations
    'stone', 'smooth_stone', 'cobblestone', 'mossy_cobblestone', 'stone_bricks',
    'cracked_stone_bricks', 'mossy_stone_bricks', 'chiseled_stone_bricks', 'bricks', 'mud_bricks',
    'deepslate', 'cobbled_deepslate', 'polished_deepslate', 'deepslate_bricks',
    'cracked_deepslate_bricks', 'deepslate_tiles', 'tuff', 'polished_tuff', 'tuff_bricks',
    'calcite', 'andesite', 'polished_andesite', 'diorite', 'polished_diorite', 'granite',
    'polished_granite', 'sandstone', 'smooth_sandstone', 'red_sandstone', 'smooth_red_sandstone',
    'nether_bricks', 'red_nether_bricks', 'quartz_block', 'smooth_quartz', 'purpur_block',
    'prismarine', 'prismarine_bricks', 'dark_prismarine', 'blackstone',
    'polished_blackstone', 'polished_blackstone_bricks', 'end_stone_bricks', 'obsidian',
    'bedrock', 'dirt', 'coarse_dirt', 'grass_block', 'mud', 'clay', 'gravel', 'snow_block',
    'iron_block', 'gold_block', 'copper_block', 'waxed_copper_block', 'exposed_copper',
    'weathered_copper', 'oxidized_copper', 'amethyst_block', 'coal_block', 'emerald_block',
    'diamond_block', 'lapis_block', 'redstone_block', 'bone_block', 'hay_block',
    'white_terracotta', 'light_gray_terracotta', 'gray_terracotta', 'black_terracotta',
    'brown_terracotta', 'red_terracotta', 'orange_terracotta', 'yellow_terracotta',
    'lime_terracotta', 'green_terracotta', 'cyan_terracotta', 'light_blue_terracotta',
    'blue_terracotta', 'purple_terracotta', 'magenta_terracotta', 'pink_terracotta',
    'terracotta', 'brown_concrete', 'white_concrete', 'light_gray_concrete', 'gray_concrete',
    'black_concrete', 'red_concrete', 'orange_concrete', 'yellow_concrete', 'lime_concrete',
    'green_concrete', 'cyan_concrete', 'light_blue_concrete', 'blue_concrete',
    'purple_concrete', 'magenta_concrete', 'pink_concrete',
    'oak_planks', 'spruce_planks', 'birch_planks', 'jungle_planks', 'acacia_planks',
    'dark_oak_planks', 'mangrove_planks', 'cherry_planks', 'bamboo_planks', 'bamboo_mosaic',
    'crimson_planks', 'warped_planks', 'pale_oak_planks', 'poplar_planks',
    'oak_leaves', 'spruce_leaves', 'birch_leaves', 'jungle_leaves', 'acacia_leaves',
    'dark_oak_leaves', 'mangrove_leaves', 'cherry_leaves', 'azalea_leaves',
    'flowering_azalea_leaves', 'glass', 'tinted_glass', 'glowstone', 'sea_lantern',
    'air', 'cave_air', 'void_air', 'water', 'lava', 'moss_block', 'moss_carpet', 'sand', 'red_sand', 'soul_sand',
    'white_wool', 'gray_wool', 'black_wool', 'brown_wool', 'red_wool', 'orange_wool',
    'yellow_wool', 'lime_wool', 'green_wool', 'cyan_wool', 'light_blue_wool', 'blue_wool',
    'purple_wool', 'magenta_wool', 'pink_wool',
  ],
  PROPERTIES.none,
);

// `stone` and friends appear twice above: once as an axis-capable pillar candidate and once as a
// plain cube. `none` is the later registration on purpose — a plain stone block is the common
// case, and `axis` is only meaningful for the dedicated pillar variants.
for (const name of ['stone', 'sandstone', 'quartz_block', 'purpur_block']) REGISTRY.set(name, PROPERTIES.none);

// Logs and pillars must keep their axis schema: the bulk "plain cube" list above is registered
// later and would otherwise overwrite them with `none`.
register(LOG_BLOCKS, PROPERTIES.axis);
register(['deepslate'], PROPERTIES.axisXZ);

/**
 * Blocks with no properties: an empty/absent `Properties` object is normalised away.
 * Anything in the registry with schema `none` is included, and unknown-but-plain ids are handled
 * by the lenient path in `normalizeId` (they simply carry whatever properties the caller set).
 */
const STATELESS = new Set();
for (const [name, schema] of REGISTRY) if (schema === PROPERTIES.none) STATELESS.add(name);

/** All block ids the registry knows (useful for tests and for the studio's palette view). */
export const KNOWN_BLOCKS = Object.freeze([...REGISTRY.keys()].sort());

/** True when the id is in this module's Minecraft 26.3 registry. @param {string} id */
export function isKnownBlock(id) {
  return REGISTRY.has(stripNamespace(id));
}

/** Strips the `minecraft:` prefix (block ids are stored bare inside this package). @param {string} id */
export function stripNamespace(id) {
  return String(id).startsWith(`${NAMESPACE}:`) ? String(id).slice(NAMESPACE.length + 1) : String(id);
}

/** Adds the `minecraft:` prefix when it is missing. @param {string} id */
export function withNamespace(id) {
  const bare = stripNamespace(id);
  return `${NAMESPACE}:${bare}`;
}

/**
 * Validates an id's *shape*: namespace + a plain resource location.
 * @param {string} id
 * @returns {boolean}
 */
export function isValidId(id) {
  return typeof id === 'string' && /^(minecraft:)?[a-z0-9_]+(:[a-z0-9_/.-]+)?$/.test(id);
}

/**
 * Builds a BlockState.
 * @param {string} id block id, with or without the `minecraft:` prefix
 * @param {Record<string, string|number|boolean>} [properties]
 * @returns {{Name:string, Properties?:Record<string,string>}}
 */
export function blockState(id, properties) {
  if (!isValidId(id)) throw new TypeError(`Invalid block id: ${JSON.stringify(id)}`);
  const name = withNamespace(id);
  const props = normalizeProperties(stripNamespace(id), properties);
  return props ? { Name: name, Properties: props } : { Name: name };
}

/**
 * Coerces property values to the strings Minecraft stores, drops unknown keys for known blocks
 * and drops the object entirely when it is empty.
 * @param {string} bareId
 * @param {Record<string, any>|null|undefined} properties
 * @returns {Record<string,string>|null}
 */
export function normalizeProperties(bareId, properties) {
  if (!properties) return null;
  const schema = REGISTRY.get(bareId);
  /** @type {Record<string,string>} */
  const out = {};
  for (const [rawKey, rawValue] of Object.entries(properties)) {
    const propKey = String(rawKey).toLowerCase();
    if (rawValue === undefined || rawValue === null) continue;
    if (schema && schema !== PROPERTIES.none && !(propKey in schema)) continue;
    const value = typeof rawValue === 'boolean' ? String(rawValue) : String(rawValue);
    if (schema && propKey in schema && !schema[propKey].includes(value)) {
      // Value outside the allowed set for a known block: fall back to Minecraft's default rather
      // than emitting an invalid state.
      continue;
    }
    out[propKey] = value;
  }
  return Object.keys(out).length ? out : null;
}

/**
 * Deep equality of two block states (property order does not matter).
 * @param {{Name:string, Properties?:Record<string,string>}|null|undefined} a
 * @param {{Name:string, Properties?:Record<string,string>}|null|undefined} b
 */
export function sameState(a, b) {
  if (!a || !b) return a === b;
  if (a.Name !== b.Name) return false;
  return canonicalState(a) === canonicalState(b);
}

/**
 * Canonical one-line text form, used for deduplication and for stable palette order.
 * @param {{Name:string, Properties?:Record<string,string>}} state
 * @returns {string}
 */
export function canonicalState(state) {
  const bare = stripNamespace(state.Name);
  const props = state.Properties || {};
  const keys = Object.keys(props).sort();
  if (!keys.length) return bare;
  return `${bare}[${keys.map((k) => `${k}=${props[k]}`).join(',')}]`;
}

/**
 * Parses the canonical text form (also the bracketed form `/setblock` accepts).
 * @param {string} text e.g. `minecraft:oak_stairs[facing=east,half=bottom,shape=straight]`
 * @returns {{Name:string, Properties?:Record<string,string>}}
 */
export function parseState(text) {
  const trimmed = String(text).trim();
  const match = /^([a-z0-9_:/.]+)(?:\[(.*)\])?$/.exec(trimmed);
  if (!match) throw new Error(`Invalid block state text: ${JSON.stringify(text)}`);
  const [, id, props] = match;
  if (!props || !props.trim()) return blockState(id);
  /** @type {Record<string,string>} */
  const parsed = {};
  for (const pair of props.split(',')) {
    const [k, v] = pair.split('=');
    if (!k || v === undefined) throw new Error(`Invalid block state property: ${JSON.stringify(pair)}`);
    parsed[k.trim()] = v.trim();
  }
  return blockState(id, parsed);
}

/**
 * The command-file / litematic form: `minecraft:oak_stairs[facing=east,half=bottom,shape=straight]`.
 * @param {{Name:string, Properties?:Record<string,string>}} state
 * @returns {string}
 */
export function stateToString(state) {
  const props = state.Properties || {};
  const keys = Object.keys(props).sort();
  if (!keys.length) return withNamespace(state.Name);
  return `${withNamespace(state.Name)}[${keys.map((k) => `${k}=${props[k]}`).join(',')}]`;
}

/**
 * Full validation against the 26.3 registry.
 *
 * @param {{Name?:string, Properties?:Record<string,string>}} state
 * @param {{allowUnknown?:boolean}} [opts] `allowUnknown: true` downgrades an unregistered id to a warning
 * @returns {{ok:boolean, errors:string[], warnings:string[]}} problems are English, user-facing
 */
export function validateBlockState(state, opts = {}) {
  const allowUnknown = opts.allowUnknown === true;
  const errors = [];
  const warnings = [];
  if (!state || typeof state !== 'object') return { ok: false, errors: ['block state is not an object'], warnings };
  if (typeof state.Name !== 'string') return { ok: false, errors: ['block state has no Name'], warnings };
  if (!isValidId(state.Name)) errors.push(`invalid block id "${state.Name}"`);
  const bare = stripNamespace(state.Name);
  const schema = REGISTRY.get(bare);
  if (!schema) {
    // The registry covers every id this project can emit. An id outside it is almost always a typo
    // (`not_a_block`) rather than a modded block, so it is an error by default; callers that know
    // they are handling third-party schematics can pass `allowUnknown`.
    if (allowUnknown) {
      warnings.push(`block "${bare}" is not in the known 26.3 registry; states were not checked`);
      return { ok: errors.length === 0, errors, warnings };
    }
    errors.push(`block "${bare}" is not a known Minecraft 26.3 block id`);
    return { ok: false, errors, warnings };
  }
  const props = state.Properties || {};
  if (schema === PROPERTIES.none) {
    if (Object.keys(props).length) errors.push(`block "${bare}" does not take properties`);
    return { ok: errors.length === 0, errors, warnings };
  }
  for (const [key, value] of Object.entries(props)) {
    const allowed = schema[key];
    if (!allowed) {
      errors.push(`block "${bare}" has no property "${key}"`);
      continue;
    }
    if (!allowed.includes(String(value))) {
      errors.push(`block "${bare}" property "${key}" cannot be "${value}" (allowed: ${allowed.join('|')})`);
    }
  }
  // Required properties: stairs need facing/half/shape, slabs need type, connections default to false.
  if (schema === PROPERTIES.stairs) {
    for (const required of ['facing', 'half', 'shape']) {
      if (!(required in props)) errors.push(`stairs "${bare}" is missing required property "${required}"`);
    }
  }
  if (schema === PROPERTIES.slab && !('type' in props)) {
    errors.push(`slab "${bare}" is missing required property "type"`);
  }
  return { ok: errors.length === 0, errors, warnings };
}

/* ------------------------------------------------------------------------------------------ */
/* Shape helpers                                                                               */
/* ------------------------------------------------------------------------------------------ */

/**
 * The stairs / slab / wall / fence / pane family entry for a block id, or null.
 * @param {string} id
 * @returns {{stairs?:string, slab?:string, wall?:string, fence?:string}|null}
 */
export function familyOf(id) {
  return SHAPE_FAMILY[stripNamespace(id)] || null;
}

/**
 * Stairs variant of a block, or null when the block has no stairs form.
 * @param {string} id
 * @returns {string|null}
 */
export function stairsFor(id) {
  const family = familyOf(id);
  if (family && family.stairs) return family.stairs;
  const bare = stripNamespace(id);
  return REGISTRY.get(bare) === PROPERTIES.stairs ? bare : null;
}

/**
 * Slab variant of a block, or null when the block has no slab form.
 * @param {string} id
 * @returns {string|null}
 */
export function slabFor(id) {
  const family = familyOf(id);
  if (family && family.slab) return family.slab;
  const bare = stripNamespace(id);
  return REGISTRY.get(bare) === PROPERTIES.slab ? bare : null;
}

/**
 * Fence variant of a block, or null.
 * @param {string} id
 * @returns {string|null}
 */
export function fenceFor(id) {
  const family = familyOf(id);
  if (family && family.fence) return family.fence;
  const bare = stripNamespace(id);
  if (REGISTRY.get(bare) === PROPERTIES.fence) return bare;
  // Planks without an explicit fence mapping still have one in vanilla; derive it safely.
  if (/_planks$/.test(bare) && REGISTRY.has(bare.replace(/_planks$/, '_fence'))) {
    return bare.replace(/_planks$/, '_fence');
  }
  return null;
}

/**
 * Pane variant of a block, or null. Glass -> glass_pane, iron_block -> iron_bars,
 * anything already pane-shaped stays.
 * @param {string} id
 */
export function paneFor(id) {
  const bare = stripNamespace(id);
  if (REGISTRY.get(bare) === PROPERTIES.pane) return bare;
  if (bare === 'glass' || /_glass$/.test(bare)) {
    const pane = bare.replace(/_glass$/, '_glass_pane');
    if (REGISTRY.get(pane) === PROPERTIES.pane) return pane;
    return 'glass_pane';
  }
  if (bare === 'iron_block') return 'iron_bars';
  return null;
}

/**
 * A solid, property-free stand-in for a shaped block: what to use when the shape is not possible
 * here (e.g. a stairs block that would float). Stairs -> their base material, slabs -> base
 * material, panes -> glass, fences -> their plank block.
 * @param {string} id
 * @returns {string}
 */
export function solidFallback(id) {
  const bare = stripNamespace(id);
  const schema = REGISTRY.get(bare);
  if (schema === PROPERTIES.stairs || schema === PROPERTIES.slab) {
    const entry = Object.entries(SHAPE_FAMILY).find(
      ([, family]) => family.stairs === bare || family.slab === bare,
    );
    if (entry) return entry[0];
    if (/_stairs$/.test(bare)) return bare.replace(/_stairs$/, '_planks');
    if (/_slab$/.test(bare)) return bare.replace(/_slab$/, '_planks');
    return 'stone';
  }
  if (schema === PROPERTIES.pane) return bare === 'iron_bars' ? 'glass' : 'glass';
  if (schema === PROPERTIES.fence) {
    const planks = bare.replace(/_fence$/, '_planks');
    if (REGISTRY.has(planks)) return planks;
    return 'oak_planks';
  }
  return bare;
}

/**
 * True when the state is a full, property-free cube (used to decide whether a cell can carry
 * another block on top of it).
 * @param {{Name:string, Properties?:Record<string,string>}} state
 */
export function isFullCube(state) {
  const bare = stripNamespace(state.Name);
  const schema = REGISTRY.get(bare);
  if (!schema) return true;
  if (schema === PROPERTIES.none || schema === PROPERTIES.axis || schema === PROPERTIES.axisXZ) return true;
  if (schema === PROPERTIES.slab) return state.Properties?.type === 'double';
  return false; // stairs, panes, fences, walls
}

/**
 * True when a block occupies the full height of its cell (fences/panes/walls do not).
 * @param {{Name:string, Properties?:Record<string,string>}} state
 */
export function isFullHeight(state) {
  const schema = REGISTRY.get(stripNamespace(state.Name));
  if (schema === PROPERTIES.pane || schema === PROPERTIES.fence) return false;
  if (schema === PROPERTIES.slab) return state.Properties?.type === 'double';
  if (schema === PROPERTIES.stairs) return false;
  return true;
}

/* ------------------------------------------------------------------------------------------ */
/* Specific shape constructors                                                                 */
/* ------------------------------------------------------------------------------------------ */

/**
 * Builds a stairs state. The `facing` of a Minecraft stair points at the *full-block* (uphill)
 * side, which is exactly the direction the sloped surface rises towards.
 *
 * @param {string} stairsId
 * @param {{facing?:string, half?:'top'|'bottom', shape?:string, waterlogged?:boolean}} [opts]
 * @returns {{Name:string, Properties:Record<string,string>}}
 */
export function makeStairs(stairsId, opts = {}) {
  const facing = FACINGS.includes(opts.facing) ? opts.facing : 'north';
  return blockState(stairsId, {
    facing,
    half: opts.half === 'top' ? 'top' : 'bottom',
    shape: opts.shape && PROPERTIES.stairs.shape.includes(opts.shape) ? opts.shape : 'straight',
    waterlogged: opts.waterlogged ? 'true' : 'false',
  });
}

/**
 * Builds a slab state.
 * @param {string} slabId
 * @param {{type?:'top'|'bottom'|'double', waterlogged?:boolean}} [opts]
 */
export function makeSlab(slabId, opts = {}) {
  const type = opts.type === 'top' || opts.type === 'double' ? opts.type : 'bottom';
  return blockState(slabId, { type, waterlogged: opts.waterlogged ? 'true' : 'false' });
}

/**
 * Builds a pane/fence connection state from a set of directions.
 * @param {string} id
 * @param {Set<string>|string[]} directions any of north/south/east/west
 * @returns {{Name:string, Properties:Record<string,string>}}
 */
export function makeConnections(id, directions) {
  const set = directions instanceof Set ? directions : new Set(directions || []);
  return blockState(id, {
    north: set.has('north') ? 'true' : 'false',
    east: set.has('east') ? 'true' : 'false',
    south: set.has('south') ? 'true' : 'false',
    west: set.has('west') ? 'true' : 'false',
    waterlogged: 'false',
  });
}

/**
 * Builds a wall state (walls are only used by the optional `stone_wall` post-style; fences are the
 * project default for posts).
 * @param {string} id
 * @param {{directions?:Set<string>|string[], up?:boolean}} [opts]
 */
export function makeWall(id, opts = {}) {
  const set = opts.directions instanceof Set ? opts.directions : new Set(opts.directions || []);
  const side = (dir) => (set.has(dir) ? 'tall' : 'false');
  return blockState(id, {
    up: opts.up ? 'true' : 'false',
    north: side('north'),
    east: side('east'),
    south: side('south'),
    west: side('west'),
    waterlogged: 'false',
  });
}

/**
 * Builds an axis state (logs, pillars).
 * @param {string} id
 * @param {'x'|'y'|'z'} axis
 */
export function makeAxis(id, axis) {
  return blockState(id, { axis: axis === 'x' || axis === 'z' ? axis : 'y' });
}

/**
 * The facing value matching a horizontal step (for stairs).
 * @param {number} dx
 * @param {number} dz
 * @returns {'north'|'east'|'south'|'west'|null}
 */
export function facingFromStep(dx, dz) {
  return STEP_TO_FACING[`${Math.sign(dx)},${Math.sign(dz)}`] || null;
}

/** Step for a facing. @param {string} facing @returns {[number, number]} */
export function stepFromFacing(facing) {
  switch (facing) {
    case 'east':
      return [1, 0];
    case 'west':
      return [-1, 0];
    case 'south':
      return [0, 1];
    case 'north':
      return [0, -1];
    default:
      return [0, 0];
  }
}
