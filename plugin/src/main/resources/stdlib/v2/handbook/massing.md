# HANDBOOK: massing — silhouette first
A professional blocks out the masses before any detail, and judges the silhouette from far away and at eye level.
- Primary, secondary, tertiary: one dominant mass, 1-3 secondary masses clearly smaller (60-80% of its height or
  width), then tertiary forms (porches, chimneys, dormers, bay windows, buttresses). Never several equal boxes.
- Break the box: offsets in plan (L, T, U shapes), different heights, roofs at different levels and directions,
  overhangs (jetties) on upper storeys.
- Odd numbers and rhythm: 3 or 5 bays, windows grouped; repeat an element with variation, never a perfect grid.
- Asymmetry with balance: a tall tower on one side is answered by a long low wing on the other.
- The silhouette must change at every viewing angle: spires, chimneys, finials and trees break the skyline.
- Focal point: one area (gate, main facade, the crown of a tree) gets most of the detail; the eye goes there first.
- Ground it: a building sits on a plinth and steps or terrain; a tree has a root flare; nothing floats or stands on
  a flat green slab.
```js
part('massing', () => {
  const main = volume(0, 0, 12, 8, 1, {palette: 'medieval', floors: 2, roof: {type: 'gable', ridge: 'x'}, rooms: false});
  const wing = volume(12, 2, 18, 8, 1, {palette: 'medieval', floors: 1, roof: {type: 'gable', ridge: 'z'}, rooms: false});
  note('main house 2 storeys + lower cross wing on the east: L-plan, two ridge directions');
});
```
