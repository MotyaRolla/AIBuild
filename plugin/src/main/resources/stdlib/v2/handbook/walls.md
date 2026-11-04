# HANDBOOK: walls — depth makes a wall
A flat wall of one block is the most common amateur mistake. A professional wall has at least three depth layers:
1. Frame (front): corner posts, pillars every 3-5 blocks, horizontal beams at floor lines, a plinth at the bottom
   and a cornice/eave line at the top. Logs, stripped logs or a darker stone.
2. Infill (1 back): plaster, planks, bricks or masonry between the frame members.
3. Openings (1-2 back again): windows set into the infill, with a sill (stair or slab sticking out) under and a
   lintel or small roof (slab, stairs) over; shutters (trapdoors) beside them.
- Base: a plinth 1-2 high of coarser stone, sticking out 1 block, often with a stair "skirt" on top.
- Mix within the wall: weather() patches (mossy/cracked), mix() of 2-3 close blocks, a grad() from darker at the
  bottom to lighter at the top. Dark at the base, light at the top, never the other way.
- Break long walls with buttresses, pilasters (stairs/walls stacked on a column), bay windows, balconies.
- Half-timber (fachwerk): timber() frames with diagonal braces on white or light plaster; jetty each upper storey
  out by 1 block; the frame uses stripped dark wood, the infill white_terracotta / calcite / mud_bricks.
```js
part('wall-depth', () => {
  box(0, 1, 0, 10, 1, 0, 'cobblestone');                                   // plinth
  box(0, 2, 1, 10, 5, 1, mix({white_terracotta: 4, calcite: 1}, {scale: 2}));  // infill, 1 back
  [0, 5, 10].forEach(x => box(x, 2, 0, x, 5, 0, 'stripped_spruce_log'));    // posts in front
  box(0, 6, 0, 10, 6, 0, axisOf('stripped_spruce_log', 'x'));               // beam
  windowAt(2, 3, 1, 'north', {w: 2, h: 2, shutters: true, sill: true});
  windowAt(7, 3, 1, 'north', {w: 2, h: 2, shutters: true, sill: true});
  note('frame / infill / openings: three depth layers');
});
```
