# HANDBOOK: terrain — the build stands somewhere
- Never a flat single-block lawn: groundCover() blends grass, coarse dirt, podzol, moss and paths in patches;
  terrain() gives gentle relief; the plot edge fades (lower, thinner) instead of ending in a hard square.
- Layers: grass top, dirt 2-4 below, stone deeper; exposed stone on slopes and cliffs, gravel and sand in hollows.
- Paths: dirt_path / gravel / coarse dirt mixed in patches (mix() with scale 2-3, never block by block), 2-3 wide, slightly winding, worn at junctions; edged with stones.
- Water: a shore band of sand/gravel/clay, mud or moss in the shallows, depth 1 at the edge then 2-4; lily pads,
  reeds (sugar cane), rocks half in the water. Water level 1 below the bank.
- Rocks: in groups of 1 big + 2-3 small, half buried, mossy on top.
- Plants: clusters, not speckle; tall grass and ferns at bases of walls and rocks; flowers in drifts of one kind.
- Frame, don't hide: the setting reaches 5-10 blocks beyond the subject; nothing tall stands between the subject
  and the front view.
```js
part('ground', () => {
  box(-12, 0, -12, 12, 0, 12, 'grass_block');
  groundCover(-12, -12, 12, 12, {patches: true});
  pond(4, 4, 5, 4, {depth: 2});
  rock(-6, 1, -5, 2, {m: 'stone'});
  path([[-12, 0, 0], [-4, 0, 1], [0, 0, -2]], {w: 2});
});
```
