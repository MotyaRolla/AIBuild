# HANDBOOK: palette — roles, gradients, texture
- 60/30/10: one dominant block family (walls, ground), a secondary one (frame, roof, trim), a 10% accent (doors,
  banners, flowers, lanterns, coloured glass). 4-7 real blocks in total; more turns to noise.
- Every surface is a gradient of 3-5 close blocks, not one block: masonry cobblestone -> stone_bricks ->
  polished_andesite going up; wood dark_oak -> spruce -> oak from heavy to light members.
- Gradients run dark to light from bottom to top, and from worn (mossy, cracked) at the ground to clean above.
- Texture matters as much as colour: smooth next to rough (polished next to cobbled, planks next to logs).
- Warm woods against cool stones give natural contrast; a complementary accent at 10% (orange terracotta with cyan).
- Use ramp('base', n) for real colour steps of one hue, and shade() for organic bodies (statues, trees, rocks).
Style palettes that work (palette() has these as presets):
- german fachwerk: stripped dark_oak frame, white_terracotta/calcite infill, cobblestone + stone_bricks plinth,
  deepslate_tiles or dark_oak roof with spruce patches, red/brown accents.
- gothic: deepslate bricks/tiles, polished blackstone, stone_bricks, dark glass, iron bars, copper or deepslate roofs.
- japanese: dark_oak / spruce frame, white walls (white_terracotta, birch), dark roofs (deepslate_tiles,
  blackstone), cherry accents, stone lanterns, moss and gravel gardens.
- nordic: spruce logs and planks, cobblestone base, dark roofs, mossy patches, snow or grass.
- desert: sandstone family (cut, smooth, chiseled), terracotta accents, palm and dead bush.
- fantasy/elven: light stones (calcite, diorite, quartz), birch and oak, cyan/green copper roofs, flowers.
```js
part('gradient', () => {
  box(0, 1, 0, 6, 9, 0, grad('y', [[0, 'cobblestone'], [0.35, 'stone_bricks'], [0.75, 'andesite'], [1, 'polished_andesite']]));
  box(0, 1, 2, 6, 9, 2, weather(mix({stone_bricks: 5, mossy_stone_bricks: 2, cracked_stone_bricks: 1}, {scale: 3}), 0.2));
});
```
