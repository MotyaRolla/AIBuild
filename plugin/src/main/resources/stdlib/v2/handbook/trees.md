# HANDBOOK: trees — built like a sculpture
A tree is a trunk, 3-5 main branches, smaller branches, then leaf clusters around the branch ends. Never a ball on
a stick, never one leaf block in one colour.
1. Trunk: wider at the bottom (root flare) and at the crown split, thinner in the middle; curve or lean it a little.
2. Roots: 3-6 roots running out and down into the ground, partly buried, the ends thin.
3. Branches: 3-5 main branches leaving at different heights and angles, each thinner than the trunk, splitting
   again; bark runs along the branch (logs oriented with the branch, axisOf / line() orients them).
4. Leaves: flattened clusters ("leaf disks") around each branch end at different heights, thicker in the middle,
   ragged edges; mix 2-3 leaf blocks; darker leaves inside and under the crown, light on top; gaps where branches
   show through.
5. Details: hanging leaves or vines under clusters, fallen petals or leaves on the ground under the crown, moss on
   the north side of the trunk, a few dead stubs.
Sakura (cherry): dark cherry_log trunk with a strong S-curve, wide flat clouds of cherry_leaves (pink, a little
flowering_azalea_leaves), pink_petals on the ground; the crown wider than tall. Willow: drooping strands to the
ground. Conifer: layered skirts getting smaller to the top.
tree() builds all of this; hand-build only a hero tree, with tube() branches and ellipsoid().warp() clusters.
```js
part('hero-tree', () => {
  const trunk = tube([[0, 1, 0], [1, 6, 1], [0, 12, 2]], t => lerp(2.5, 1.2, t));
  place(trunk, 'cherry_log');
  [[[0, 11, 2], [7, 15, 4]], [[0, 11, 2], [-6, 14, -3]], [[0, 12, 2], [2, 17, -5]]].forEach(b => {
    place(tube(b, t => lerp(1.0, 0.5, t), {curve: 'line'}), 'cherry_log');
    place(ellipsoid(b[1], [4, 2, 4]).warp(1.2, 3), mix({cherry_leaves: 4, flowering_azalea_leaves: 1}, {scale: 2}), {onlyAir: true});
  });
  note('S-curved trunk, three limbs, flattened blossom clouds at the limb ends');
});
```
