# HANDBOOK: micro detail — the 1-block vocabulary
Small blocks are a professional builder's brushes. Each turns a flat cube edge into a shape:
- stairs: sills, cornices, eave lips, pilasters, chair backs, roof edges, rounded corners (half=top for overhangs).
- slabs: half-steps in terrain and roofs, thin floors, shelves, table tops, window heads.
- walls / fences: posts, rails, balusters, chimney caps, thin columns, tree branches, lamp arms.
- trapdoors: shutters, flower boxes, panelling, window frames, table sides (open trapdoors stand vertical).
- buttons / levers / item frames: rivets, door handles, ornaments; chains + lanterns: hanging lights.
- banners, flower pots, candles, decorated pots, barrels, books: the life of a place.
What reads at which distance: from 30+ blocks only silhouette and big colour areas; from 10-20 the frames, sills,
roof patches; up close the trims, pots, lanterns. Put micro detail where a player walks: entrances, at eye level,
paths, the focal point; keep it out of places nobody sees.
Avoid: single-block lines across a big wall, the same detail every 2 blocks, details that float without a holder.
```js
part('details', () => {
  box(0, 1, 0, 6, 4, 0, 'stone_bricks');
  for (let x = 0; x <= 6; x++) block(x, 5, -1, stair('stone_bricks', 'south', 'top'));   // cornice overhang
  block(1, 3, -1, 'spruce_trapdoor[facing=north,open=true]');                            // shutter
  block(3, 2, -1, stair('spruce_planks', 'south', 'top'));                                // sill
  block(5, 4, -1, 'lantern');                                                             // on the cornice
});
```
