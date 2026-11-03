// Traits: one elegant round arch, worn stone (mossier near the water), parapets with lanterns, a stream between
// grassy banks with rocks, reeds and trees. Span x 0..30, deck z 0..4, walking surface y 7.
// Palette: cobble -> stone bricks gradient with moss, andesite-flecked deck, spruce lamps.
const L = 30, WD = 5, DECK = 7;
meta({title: 'Old stone bridge', summary: 'Rustic arch bridge over a stream', type: 'bridge', style: 'rustic stone',
      expect_interior: false});
const stone = grad('y', [[0, weather(mix({cobblestone: 3, mossy_cobblestone: 2}), 0.4)],
                          [0.5, weather('stone_bricks', 0.25)], [1, 'stone_bricks']], {from: -2, to: DECK});
part('banks', () => {
  groundCover(-14, -14, L + 14, WD + 14);
  terrain(-14, -14, 5, WD + 14, {height: 4}); terrain(L - 5, -14, L + 14, WD + 14, {height: 4});
  tree(-9, 1, -8, {species: 'willow'}); tree(L + 9, 1, WD + 8, {species: 'oak', h: 8});
});
part('stream', () => {
  carve(cuboid([6, -2, -14], [L - 6, 0, WD + 14]));
  water([6, -2, -14, L - 6, -1, WD + 14]);
  [[8, -8], [L - 8, WD + 7], [9, WD + 9]].forEach(([x, z]) => rock(x, -1, z, 1.5));
  [[7, -3], [L - 7, WD + 3], [7, WD + 11]].forEach(([x, z]) => { block(x, 0, z, 'sugar_cane'); block(x, 1, z, 'sugar_cane'); });
});
part('deck', () => {
  box(0, 1, 0, L, DECK - 1, WD - 1, stone);
  arch(4, 0, L - 4, 0, -2, {style: 'round', h: 8, rise: 6, depth: WD, ring: 'stone_bricks'});
  box(0, DECK, 0, L, DECK, WD - 1, mix({stone_bricks: 3, polished_andesite: 1}));
  [-1, WD].forEach(z => box(0, DECK - 1, z, L, DECK - 1, z, stair('stone_bricks', z < 0 ? 'south' : 'north', 'top')));
  [0, WD - 1].forEach(z => box(0, DECK + 1, z, L, DECK + 1, z, wallOf('stone_bricks') || 'cobblestone_wall'));
  sym('x', L / 2, () => [3, 11].forEach(x => [0, WD - 1].forEach(z => lampPost(x, DECK + 1, z, {h: 2, base: 'stone_brick_wall'}))));
});
