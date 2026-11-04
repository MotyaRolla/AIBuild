# HANDBOOK: roofs — a third of the silhouette
- Pitch by style: german/nordic/gothic steep (pitch 2); mediterranean and japanese shallow with deep overhangs.
- Overhang 1-2 blocks at the eaves and the gables; an eave lip of upside-down stairs; a rake trim along the gable.
- Never one flat colour: alt mixes 15-30% of a second roof block in patches, tones go darker at the eaves.
- Layer the ridge: a ridge cap of slabs or walls, finials or a weathervane on the ends.
- Break big roofs: dormers, chimneys (2-3 blocks above the ridge, with a cap), a cross gable, a small turret.
- Japanese/asian: curved roofs, tips turned up at the corners, double eaves, dark roof blocks with light ridges.
- Towers: cones and spires 1.5-3x the tower's diameter high, with a flared base and a small overhang.
```js
part('roofs', () => {
  box(0, 1, 0, 8, 4, 6, 'spruce_planks', {walls: true});
  roof(0, 0, 8, 6, 5, {type: 'gable', ridge: 'x', pitch: 2, m: 'dark_oak_stairs', alt: 'spruce_stairs', altShare: 0.25,
                      overhang: 1, gableOverhang: 1});
  dormer(4, 7, 0, 'north', {w: 3});
  chimney(7, 5, 1, 12, {m: 'bricks'});
});
```
