# HANDBOOK: organic — statues, creatures, rocks
- Start from an armature: a line of the spine/pose (tube through key points), then masses for head, chest, hips,
  limbs with capsule()/ellipsoid(), joined with blend(k, ...).
- Proportions from the total height (human 7.5 heads; animals: measure body length to height from a reference).
- Pose: weight on one side, head turned, limbs at different angles; symmetric stiff poses look like toys.
- Shade, don't paint flat: shade(ramp(base, 5), {light, cavity}) darkens creases and undersides, lights tops.
- Detail by scale: at 10-20 blocks high, features are 1-2 blocks; carve eyes and mouths with carve(), add
  stairs/slabs at the silhouette for smoother curves (place(..., {smooth: true})).
- Features sit in the surface, one layer, level and mirrored: give the face (or any front that carries marks) a
  flat front plane, then paint(shape, m, {from: 'south'}) so a mark lands on the first block seen from that side;
  a plain paint() box on a round surface wraps into the blocks behind and the mark breaks up into steps. Mirror
  left/right features from one definition, and write any part that crosses them (ears, a brim, hair) before them
  or with onlyAir. Judge them in the detail close-up, not the whole view, where they are a few pixels.
- Rocks and cliffs: overlapping warped ellipsoids, mixed stone/andesite/tuff with moss on top surfaces.
```js
part('statue', () => {
  const body = blend(3, ellipsoid([0, 8, 0], [3, 4, 2]), sphere([0, 14, 0], 2.5), capsule([0, 4, 0], [0, 1, 0], 2, 1.5));
  place(body, shade(ramp('stone', 5), {light: [0.4, 1, 0.3]}), {smooth: true});
});
```
