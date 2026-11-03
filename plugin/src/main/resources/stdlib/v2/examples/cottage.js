// Traits: half-timbered two-storey house with a jettied upper floor, a lower side wing, steep layered roofs with a
// chimney, a garden with a path, fence, lamp, tree and bushes. Main house 15x11, wing 7x7; ridge ~y 16.
// Palette: medieval (cobble plinth, white plaster infill, spruce frame, spruce/dark-oak roof). Parts: house, wing, garden.
meta({title: 'Cottage', summary: 'A half-timbered cottage with a garden', type: 'house', style: 'medieval',
      expect_interior: true, rooms: [{name: 'kitchen and hall', level: 0, area: '-5-5, -3-3', furnishing: 'counter, table, fireplace'},
      {name: 'bedrooms', level: 1, area: '-6-6, -4-4', furnishing: 'beds, wardrobe, desk'}]});
const P = palette('medieval');
let main;
part('house', () => {
  main = volume(-7, -5, 7, 5, 1, {palette: P, floors: 2, jetty: true, chimney: 'west', roof: {pitch: 2, alt: P.roofAlt},
                 rooms: [['kitchen', 'living'], ['bedroom', 'study']]});
  dormer(0, main.roofY + 2, 6, 'south', {roof: P.roof, trim: P.trim});
});
part('wing', () => volume(8, -3, 14, 3, 1, {palette: P, floors: 1, floorH: 5, door: {face: 'east'}, roof: {ridge: 'x'}, rooms: ['workshop']}));
part('garden', () => {
  groundCover(-20, -16, 24, 20);
  path([[0, 0, 7], [1, 0, 12], [4, 0, 19]], {w: 3, m: P.path});
  fenceLine([[-12, 10], [-3, 10]], {m: P.fence});
  fenceLine([[4, 10], [12, 10]], {m: P.fence});
  lampPost(4, 1, 8, {arm: true, dir: 'west'});
  tree(-15, 1, 9, {species: 'oak', h: 9});
  tree(19, 1, -10, {species: 'birch'});
  [[-9, 7], [10, 7], [-11, -7]].forEach(([x, z]) => bush(x, 1, z, 1.6));
  rock(16, 1, 12, 1.5);
});
