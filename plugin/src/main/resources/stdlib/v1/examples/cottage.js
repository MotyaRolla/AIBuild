// Traits: steep dark roof with overhang, timber-framed walls on a stone plinth, shuttered windows, cottage garden.
// Body 13 x 10 (x 0..12, z 0..9), walls y 1..5, ridge ~y 13. Palette: cobble plinth, white infill + dark oak
// frame, deepslate roof, spruce trim, brick chimney. Parts: body, openings, roof, interior, garden.
const W = 12, D = 9, WALL = 5;
meta({title: 'Cottage', summary: 'Timber-framed cottage with a garden', type: 'house', style: 'medieval cottage',
      expect_interior: true, rooms: [{name: 'hall', level: 'ground floor', area: '1-11, 1-8', furnishing: 'fireplace, table, bed'}]});
part('body', () => {
  box(0, 0, 0, W, 0, D, 'cobblestone');
  box(0, 1, 0, W, 1, D, weather(mix({cobblestone: 3, stone_bricks: 2}), 0.3), {walls: true});
  box(0, 2, 0, W, WALL, D, 'white_terracotta', {walls: true});
  timber(0, 2, D, W, WALL, D, {m: 'dark_oak_log', every: 3, braces: true});
  timber(0, 2, 0, W, WALL, 0, {m: 'dark_oak_log', every: 3});
  box(0, WALL + 1, 0, W, WALL + 1, D, 'spruce_planks');
});
part('openings', () => {
  door(6, 1, D, 'south', {m: 'spruce_door', frame: 'stripped_dark_oak_log', step: 'cobblestone', lamp: true});
  [2, 10].forEach(x => windowAt(x, 2, D, 'south', {h: 2, sill: 'spruce_planks', shutters: 'spruce_trapdoor'}));
  [3, 9].forEach(x => windowAt(x, 2, 0, 'north', {w: 2, h: 2, sill: 'spruce_planks'}));
});
part('roof', () => {
  roof(0, 0, W, D, WALL + 1, {type: 'gable', m: 'deepslate_tiles', fill: 'white_terracotta', ridge: 'x'});
  chimney(W - 2, 2, WALL + 1, WALL + 9, {m: 'bricks'});
});
part('interior', () => {
  fireplace(W - 2, 1, 1, 'south', {m: 'bricks'});
  table(4, 1, 3, {w: 2, d: 2}); chair(3, 1, 3, 'west'); chair(6, 1, 4, 'east');
  bed(1, 1, 3, 'north', 'red'); lamp(6, WALL, 5, {hanging: true}); rug(3, 6, 7, 8, 1, 'brown', 'orange');
});
part('garden', () => {
  path([[6, 0, D + 1], [6, 0, D + 5], [3, 0, D + 10]], {w: 3});
  flowers(-3, D + 1, W + 3, D + 8, {density: 0.3});
  tree(-5, 1, 3, {species: 'oak'}); bush(W + 2, 1, D - 1, 1.5); rock(W + 4, 1, 2, 1.6);
});
