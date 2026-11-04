# HANDBOOK: interiors — rooms with a purpose
- Every room has one purpose and a focal piece: hearth in a living room, bed in a bedroom, long table in a hall,
  counter in a shop, altar in a chapel. Name it before furnishing.
- Leave walking space: a 2-block path from each door to the far wall and to the stairs; nothing in doorways.
- Furniture from blocks: tables (fence or wall + pressure plate or carpet top, or a slab on a post), chairs
  (stairs with trapdoor arms), beds with nightstands, shelves of bookshelves/barrels, kitchens of furnaces,
  smokers, cauldrons, counters of stripped logs or slabs.
- Layers again: floor rug or carpet, walls with beams/panelling, ceiling with joists and a hanging light.
- Light every room (lanterns, candles, chandeliers) so nothing is dark; light level is checked.
- furnish(room, type) does a full room from the walls in; rooms() lists the rooms that exist. Hand-add personal
  touches: potted plants, books, a cat bed, crates.
```js
part('cottage', () => {
  const h = volume(0, 0, 10, 8, 1, {palette: 'medieval', floors: 1, roof: {type: 'gable', ridge: 'x'}, rooms: false});
  rooms().forEach(r => furnish(r, 'living'));
});
```
