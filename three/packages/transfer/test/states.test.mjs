/**
 * Tests for block states and the post-processing passes.
 *
 * Block states are checked against the 26.3 registry: a state is only accepted if the block id
 * exists and every property/value pair is legal for that block family. This is what stops an
 * invalid id from ever reaching a `.litematic` or a `/setblock` line.
 */

import test from 'node:test';
import assert from 'node:assert/strict';

import {
  blockState,
  canonicalState,
  parseState,
  stateToString,
  validateBlockState,
  isKnownBlock,
  isValidId,
  sameState,
  makeStairs,
  makeSlab,
  makeConnections,
  makeAxis,
  stairsFor,
  slabFor,
  fenceFor,
  paneFor,
  solidFallback,
  isFullCube,
  isFullHeight,
  facingFromStep,
  stepFromFacing,
  withNamespace,
  stripNamespace,
  KNOWN_BLOCKS,
  DATA_VERSION_26_3,
  TARGET_VERSION,
  FACINGS,
} from '../src/states.mjs';
import {
  attachFloating,
  repairSupports,
  normaliseStates,
  postProcess,
  connectedComponents,
  groundLevel,
  DEFAULT_MAX_SUPPORT_RUN,
} from '../src/postprocess.mjs';
import { analyzeResult, analyseEnclosure, componentsOf } from '../src/analyze.mjs';
import { addFoundation, checkFootprintSupport, footprint } from '../src/foundation.mjs';
import { refreshConnections } from '../src/select.mjs';

/* ------------------------------------------------------------------------------------------ */
/* States                                                                                      */
/* ------------------------------------------------------------------------------------------ */

test('states: the target is Minecraft 26.3 (data version 5023)', () => {
  assert.equal(TARGET_VERSION, '26.3');
  assert.equal(DATA_VERSION_26_3, 5023);
});

test('states: blockState normalises the namespace and property values', () => {
  const state = blockState('stone');
  assert.deepEqual(state, { Name: 'minecraft:stone' });
  assert.deepEqual(blockState('minecraft:oak_planks'), { Name: 'minecraft:oak_planks' });
  const stairs = blockState('oak_stairs', { facing: 'east', half: 'top', shape: 'straight' });
  assert.equal(stairs.Name, 'minecraft:oak_stairs');
  assert.equal(stairs.Properties.facing, 'east');
  // Unknown properties are dropped rather than emitted (Minecraft would reject them).
  const withJunk = blockState('oak_stairs', { facing: 'east', half: 'bottom', shape: 'straight', nonsense: 'x' });
  assert.equal('nonsense' in withJunk.Properties, false);
  // Invalid values for known properties fall back to omission.
  const badFacing = blockState('oak_stairs', { facing: 'up', half: 'bottom', shape: 'straight' });
  assert.equal('facing' in (badFacing.Properties || {}), false);
});

test('states: a block without properties has no Properties key at all', () => {
  const state = blockState('stone_bricks', {});
  assert.equal(Object.prototype.hasOwnProperty.call(state, 'Properties'), false);
});

test('states: ids are validated for syntax', () => {
  assert.equal(isValidId('minecraft:oak_stairs'), true);
  assert.equal(isValidId('oak_stairs'), true);
  assert.equal(isValidId('Minecraft:Stone'), false);
  assert.equal(isValidId('minecraft:oak stairs'), false);
  assert.equal(isValidId(''), false);
  assert.equal(isValidId(null), false);
});

test('states: canonical text form is stable and round-trips', () => {
  const stairs = makeStairs('oak_stairs', { facing: 'west', half: 'top' });
  const text = canonicalState(stairs);
  // Properties are sorted, so the text does not depend on object key order.
  assert.equal(text, 'oak_stairs[facing=west,half=top,shape=straight,waterlogged=false]');
  assert.ok(sameState(parseState(text), stairs));
  assert.equal(stateToString(stairs), `minecraft:${text}`);
});

test('states: validateBlockState accepts every state the helpers can build', () => {
  const samples = [
    makeStairs('spruce_stairs', { facing: 'east' }),
    makeSlab('dark_oak_slab', { type: 'top' }),
    makeSlab('stone_slab', { type: 'double' }),
    makeConnections('glass_pane', ['north', 'east', 'south', 'west']),
    makeConnections('oak_fence', ['north']),
    makeAxis('oak_log', 'y'),
    blockState('stone_bricks'),
    blockState('white_terracotta'),
    blockState('glass'),
  ];
  for (const state of samples) {
    const check = validateBlockState(state);
    assert.deepEqual(check.errors, [], `${stateToString(state)}: ${check.errors.join('; ')}`);
    assert.ok(isKnownBlock(state.Name), `${state.Name} should be registered`);
  }
});

test('states: validateBlockState rejects states that would crash or error in game', () => {
  const bad = [
    { Name: 'minecraft:not_a_block' },
    { Name: 'minecraft:oak_log', Properties: { axis: 'w' } },
    { Name: 'minecraft:oak_stairs', Properties: { facing: 'east' } }, // missing half/shape
    { Name: 'minecraft:oak_stairs', Properties: { facing: 'up', half: 'bottom', shape: 'straight' } },
    { Name: 'minecraft:oak_slab', Properties: {} }, // missing type
    { Name: 'minecraft:oak_slab', Properties: { type: 'left' } },
    { Name: 'minecraft:stone', Properties: { axis: 'y' } }, // stone takes no properties
    { Name: 'oak stairs' },
  ];
  for (const state of bad) {
    const check = validateBlockState(state);
    assert.equal(check.ok, false, `should reject ${JSON.stringify(state)}`);
    assert.ok(check.errors.length > 0);
  }
  assert.equal(validateBlockState(null).ok, false);
});

test('states: unknown ids are errors by default and warnings when explicitly allowed', () => {
  // This package only ever emits ids it knows, so an unknown id is a bug (usually a typo).
  const strict = validateBlockState({ Name: 'minecraft:some_future_block' });
  assert.equal(strict.ok, false);
  assert.ok(strict.errors.some((e) => /not a known Minecraft 26\.3 block id/.test(e)));
  // Schematics from elsewhere (third-party .litematic files) opt out of that check.
  const lenient = validateBlockState({ Name: 'minecraft:some_future_block' }, { allowUnknown: true });
  assert.equal(lenient.ok, true);
  assert.ok(lenient.warnings.some((w) => /not in the known 26.3 registry/.test(w)));
});

test('states: shape families resolve to real 26.3 variants', () => {
  assert.equal(stairsFor('spruce_planks'), 'spruce_stairs');
  assert.equal(slabFor('spruce_planks'), 'spruce_slab');
  assert.equal(fenceFor('spruce_planks'), 'spruce_fence');
  assert.equal(stairsFor('stone_bricks'), 'stone_brick_stairs');
  assert.equal(slabFor('stone_bricks'), 'stone_brick_slab');
  assert.equal(stairsFor('dark_oak_planks'), 'dark_oak_stairs');
  // White terracotta has slabs but no stairs in 26.3, so the stairs lookup must return null
  // rather than inventing an id.
  assert.equal(stairsFor('white_terracotta'), null);
  assert.equal(slabFor('white_terracotta'), 'white_terracotta_slab');
  assert.equal(paneFor('glass'), 'glass_pane');
  assert.equal(paneFor('glass_pane'), 'glass_pane');
  assert.equal(paneFor('iron_block'), 'iron_bars');
  // A block with no stairs form has no variant.
  assert.equal(stairsFor('glass'), null);
  for (const id of [
    stairsFor('spruce_planks'),
    slabFor('spruce_planks'),
    fenceFor('spruce_planks'),
    stairsFor('stone_bricks'),
    paneFor('glass'),
  ]) {
    assert.ok(isKnownBlock(id), `${id} should be a known block`);
  }
});

test('states: solid fallbacks keep the material when a shape is impossible', () => {
  assert.equal(solidFallback('spruce_stairs'), 'spruce_planks');
  assert.equal(solidFallback('dark_oak_slab'), 'dark_oak_planks');
  assert.equal(solidFallback('glass_pane'), 'glass');
  assert.equal(solidFallback('oak_fence'), 'oak_planks');
  assert.equal(solidFallback('stone_bricks'), 'stone_bricks');
});

test('states: full-cube and full-height classification drives support checks', () => {
  assert.equal(isFullCube(blockState('stone')), true);
  assert.equal(isFullCube(makeSlab('oak_slab', { type: 'double' })), true);
  assert.equal(isFullCube(makeSlab('oak_slab', { type: 'bottom' })), false);
  assert.equal(isFullCube(makeStairs('oak_stairs', {})), false);
  assert.equal(isFullCube(makeConnections('glass_pane', [])), false);
  assert.equal(isFullHeight(makeConnections('oak_fence', [])), false);
  assert.equal(isFullHeight(blockState('stone')), true);
});

test('states: facing and step conversions agree with Minecraft', () => {
  assert.equal(facingFromStep(1, 0), 'east');
  assert.equal(facingFromStep(-1, 0), 'west');
  assert.equal(facingFromStep(0, 1), 'south');
  assert.equal(facingFromStep(0, -1), 'north');
  assert.deepEqual(stepFromFacing('east'), [1, 0]);
  assert.deepEqual(stepFromFacing('north'), [0, -1]);
  for (const facing of FACINGS) {
    const [dx, dz] = stepFromFacing(facing);
    assert.equal(facingFromStep(dx, dz), facing);
  }
});

test('states: the registry is non-trivial and namespace helpers are consistent', () => {
  assert.ok(KNOWN_BLOCKS.length > 300, `registry has ${KNOWN_BLOCKS.length} ids`);
  assert.equal(withNamespace('stone'), 'minecraft:stone');
  assert.equal(withNamespace('minecraft:stone'), 'minecraft:stone');
  assert.equal(stripNamespace('minecraft:stone'), 'stone');
});

/* ------------------------------------------------------------------------------------------ */
/* Post-processing                                                                             */
/* ------------------------------------------------------------------------------------------ */

/** Builds a solid cube of blocks, used as a grounded anchor in the tests below. */
function cube(size) {
  const blocks = new Map();
  for (let x = 0; x < size; x++) {
    for (let y = 0; y < size; y++) {
      for (let z = 0; z < size; z++) {
        blocks.set(`${x}|${y}|${z}`, blockState('stone'));
      }
    }
  }
  return blocks;
}

test('postprocess: connectedComponents splits a face-touching map correctly', () => {
  const blocks = new Map([
    ['0|0|0', blockState('stone')],
    ['1|0|0', blockState('stone')],
    ['5|0|0', blockState('stone')],
    ['5|0|1', blockState('stone')],
  ]);
  const components = connectedComponents(blocks);
  assert.equal(components.length, 2);
  assert.deepEqual(components.map((c) => c.length).sort(), [2, 2]);
  // Diagonal neighbours do not connect.
  const diagonal = new Map([
    ['0|0|0', blockState('stone')],
    ['1|1|1', blockState('stone')],
  ]);
  assert.equal(connectedComponents(diagonal).length, 2);
});

test('postprocess: a floating ornament is attached with a support column, not deleted', () => {
  const blocks = cube(3);
  blocks.set('1|5|1', makeSlab('oak_slab', { type: 'bottom' }));
  const result = attachFloating(blocks, { maxSupportRun: DEFAULT_MAX_SUPPORT_RUN });
  assert.equal(result.stats.floating, 1);
  assert.equal(result.stats.supported, 1);
  assert.equal(result.stats.dropped, 0);
  // The slab survives and a column now connects it to the cube below.
  assert.ok(result.blocks.has('1|5|1'));
  assert.equal(connectedComponents(result.blocks).length, 1);
  assert.ok(result.blocks.has('1|4|1'));
  assert.ok(result.blocks.has('1|3|1'));
});

test('postprocess: a floating cluster too far from anything is dropped and reported', () => {
  const blocks = cube(3);
  blocks.set('1|40|1', blockState('stone'));
  const result = attachFloating(blocks, { maxSupportRun: 2 });
  assert.equal(result.stats.floating, 1);
  assert.equal(result.stats.supported, 0);
  assert.equal(result.stats.dropped, 1);
  assert.equal(result.blocks.has('1|40|1'), false);
  assert.ok(result.warnings.some((w) => /dropped a floating cluster/.test(w)));
});

test('postprocess: repairSupports turns an unattached pane into a glass block', () => {
  const blocks = cube(3);
  blocks.set('1|10|1', makeConnections('glass_pane', []));
  const result = repairSupports(blocks);
  assert.equal(result.blocks.get('1|10|1').Name, 'minecraft:glass');
  assert.equal(result.reshaped.length, 1);
});

test('postprocess: repairSupports turns an isolated stair into its solid material', () => {
  const blocks = new Map([['0|0|0', makeStairs('spruce_stairs', { facing: 'east' })]]);
  const result = repairSupports(blocks);
  assert.equal(result.blocks.get('0|0|0').Name, 'minecraft:spruce_planks');
});

test('postprocess: a top slab without anything below becomes a double slab', () => {
  const blocks = cube(3);
  blocks.set('1|10|1', makeSlab('oak_slab', { type: 'top' }));
  const result = repairSupports(blocks);
  assert.equal(result.blocks.get('1|10|1').Properties.type, 'double');
});

test('postprocess: an invalid state is repaired to a valid one', () => {
  const blocks = new Map([
    ['0|0|0', blockState('stone')],
    ['1|0|0', { Name: 'minecraft:oak_stairs', Properties: { facing: 'up' } }],
  ]);
  const result = normaliseStates(blocks);
  assert.equal(result.repaired, 1);
  assert.ok(validateBlockState(result.blocks.get('1|0|0')).ok);
});

test('postprocess: the full chain leaves every state valid and every block attached', () => {
  const blocks = cube(3);
  blocks.set('1|6|1', blockState('stone'));
  const result = postProcess(blocks);
  assert.deepEqual(result.errors, []);
  for (const state of result.blocks.values()) {
    assert.ok(validateBlockState(state).ok, stateToString(state));
  }
  assert.equal(connectedComponents(result.blocks).length, 1);
});

test('postprocess: groundLevel is the lowest occupied layer', () => {
  assert.equal(groundLevel(new Map([['0|5|0', blockState('stone')], ['0|2|0', blockState('stone')]])), 2);
  assert.equal(groundLevel(new Map()), 0);
});

/* ------------------------------------------------------------------------------------------ */
/* Foundation                                                                                  */
/* ------------------------------------------------------------------------------------------ */

test('foundation: footprint keeps the lowest block of every column', () => {
  const blocks = new Map([
    ['0|5|0', blockState('stone')],
    ['0|1|0', blockState('stone')],
    ['1|3|2', blockState('stone')],
  ]);
  const columns = footprint(blocks);
  assert.equal(columns.size, 2);
  assert.equal(columns.get('0|0').y, 1);
  assert.equal(columns.get('1|2').y, 3);
});

test('foundation: a plinth is added under columns that stop above the ground', () => {
  const blocks = new Map([['0|3|0', blockState('stone')], ['1|3|0', blockState('stone')]]);
  const result = addFoundation(blocks, { groundY: 0, layers: 3, material: 'stone_bricks' });
  assert.equal(result.added, 6); // 2 columns * 3 layers
  for (let y = 0; y <= 2; y++) {
    assert.ok(result.blocks.has(`0|${y}|0`));
    assert.ok(result.blocks.has(`1|${y}|0`));
  }
  assert.equal(result.blocks.get('0|1|0').Name, 'minecraft:stone_bricks');
});

test('foundation: an existing block is never overwritten, and a grounded column needs no plinth', () => {
  // The column already stands on the ground (its lowest block is at groundY), so nothing is added.
  const grounded = new Map([['0|0|0', blockState('gold_block')]]);
  const kept = addFoundation(grounded, { groundY: 0, layers: 1 });
  assert.equal(kept.blocks.get('0|0|0').Name, 'minecraft:gold_block');
  assert.equal(kept.added, 0);

  // A column that stops above the ground is filled all the way down to it.
  const hover = new Map([['0|3|0', blockState('stone')]]);
  const filled = addFoundation(hover, { groundY: 0 });
  assert.equal(filled.added, 3);
  for (const y of [0, 1, 2]) {
    assert.ok(filled.blocks.has(`0|${y}|0`), `expected a plinth block at y=${y}`);
  }
  // A fixed-depth plinth (no ground plane) is exactly `layers` deep.
  const fixed = addFoundation(new Map([['0|5|0', blockState('stone')]]), { layers: 2 });
  assert.equal(fixed.added, 2);
  assert.ok(fixed.blocks.has('0|3|0') && fixed.blocks.has('0|4|0'));

  // Idempotent when a ground plane is given: running it again adds nothing.
  const again = addFoundation(filled.blocks, { groundY: 0, layers: 2 });
  assert.equal(again.added, 0);
});

test('foundation: checkFootprintSupport spots a column that stops short', () => {
  const grounded = new Map([['0|0|0', blockState('stone')], ['1|0|0', blockState('stone')]]);
  assert.equal(checkFootprintSupport(grounded).ok, true);
  const hovering = new Map([['0|0|0', blockState('stone')], ['1|4|0', blockState('stone')]]);
  const check = checkFootprintSupport(hovering, { groundY: 0 });
  assert.equal(check.ok, false);
  assert.equal(check.holes.length, 1);
  assert.deepEqual(check.holes[0], { x: 1, z: 0, y: 4 });
});

/* ------------------------------------------------------------------------------------------ */
/* Analysis                                                                                    */
/* ------------------------------------------------------------------------------------------ */

test('analyze: a solid cube is a single closed component', () => {
  const result = analyzeResult(cube(3));
  assert.deepEqual(result.errors, []);
  assert.equal(result.stats.components, 1);
  assert.equal(result.stats.closed, true);
});

test('analyze: a floating component is an error', () => {
  const blocks = cube(3);
  blocks.set('10|10|10', blockState('stone'));
  const result = analyzeResult(blocks);
  assert.ok(result.errors.some((e) => /floating cluster/.test(e)));
  assert.equal(result.stats.floatingClusters, 1);
  assert.equal(result.stats.floatingBlocks, 1);
});

test('analyze: an invalid state is an error naming the problem', () => {
  const blocks = new Map([['0|0|0', { Name: 'minecraft:oak_stairs', Properties: { facing: 'up' } }]]);
  const result = analyzeResult(blocks);
  assert.ok(result.errors.some((e) => /not valid for 26\.3/.test(e)));
});

test('analyze: an empty map is reported rather than silently passing', () => {
  const result = analyzeResult(new Map());
  assert.ok(result.errors.length > 0);
  assert.equal(analyzeResult(null).errors.length > 0, true);
});

test('analyze: a hollow box has a sealed interior, and a hole opens it', () => {
  const shell = new Map();
  for (let x = 0; x < 5; x++) {
    for (let y = 0; y < 5; y++) {
      for (let z = 0; z < 5; z++) {
        const onShell = x === 0 || x === 4 || y === 0 || y === 4 || z === 0 || z === 4;
        if (onShell) shell.set(`${x}|${y}|${z}`, blockState('stone'));
      }
    }
  }
  const closed = analyseEnclosure(shell);
  assert.equal(closed.closed, true);
  assert.equal(closed.interior, 27);

  shell.delete('2|4|2'); // punch a hole in the roof
  const opened = analyseEnclosure(shell);
  assert.equal(opened.closed, false);
  assert.equal(opened.interior, 0, 'the interior is now reachable from outside');
  assert.ok(
    opened.holes.some((h) => h.x === 2 && h.y === 4 && h.z === 2),
    'the punched cell must be reported as the hole',
  );
});

test('analyze: componentsOf agrees with connectedComponents', () => {
  const blocks = new Map([
    ['0|0|0', blockState('stone')],
    ['0|1|0', blockState('stone')],
    ['9|9|9', blockState('stone')],
  ]);
  assert.equal(componentsOf(blocks).length, 2);
  assert.equal(connectedComponents(blocks).length, 2);
});

test('analyze: pane connections are refreshed after the neighbourhood changes', () => {
  const blocks = new Map([
    ['0|0|0', makeConnections('glass_pane', ['east'])],
    ['1|0|0', makeConnections('glass_pane', ['west'])],
  ]);
  // Both panes touch each other, so nothing changes yet.
  assert.equal(refreshConnections(blocks), 0);
  assert.equal(blocks.get('0|0|0').Properties.east, 'true');
  // Remove the eastern pane: the western one must lose its east connection.
  blocks.delete('1|0|0');
  assert.equal(refreshConnections(blocks), 1);
  assert.equal(blocks.get('0|0|0').Properties.east, 'false');
});
