// Traits: pointed ears, slim snout, long bushy tail with a white tip, white chest, slender legs, alert pose
// (head turned slightly, tail curled forward). ~30 long, 20 high on a mossy boulder in a small meadow.
// Palette: coat shaded on a 5-step orange ramp (light on top, dark underneath and in creases), white chest and tip,
// black nose/eyes/paws. Parts: base, body, head, tail. Organic: tubes + capsules blended, smoothed with stairs/slabs.
meta({title: 'Fox', summary: 'An alert fox on a mossy rock', type: 'sculpture', style: 'stylized naturalistic',
      expect_interior: false});
const coat = shade(['brown_terracotta', 'orange_terracotta', 'orange_concrete', 'orange_wool']);
const white = shade(['white_terracotta', 'white_concrete', 'snow_block']);
part('base', () => { rock(0, 1, 0, 6); rock(-7, 1, 3, 3); groundCover(-16, -12, 18, 12, {forest: true}); });
part('body', () => {
  const torso = tube([[-6, 9, 0], [0, 9.5, 0], [6, 10.5, 0]], t => 3 + Math.sin(t * Math.PI) * 0.8);
  const legs = [[-4, -1.6], [-4, 1.6], [4, -1.6], [4, 1.6]].map(([x, z]) => capsule([x, 4, z * 1.3], [x, 9, z], 0.8, 1.4));
  place(blend(2.5, torso, union(legs)), coat, {smooth: true});
  paint(ellipsoid([6, 8.5, 0], [2.2, 2.6, 2.1]), white);
  [[-4, -2], [-4, 2], [4, -2], [4, 2]].forEach(([x, z]) => paint(sphere([x, 4, z * 1.1], 1.2), 'black_concrete'));
});
part('head', () => {
  withT({rotY: -15, at: [0, 0, 0]}, () => {
    const skull = sphere([9, 13.5, 0], 2.8), snout = cone([10.5, 12.8, 0], [14, 12.2, 0], 1.6, 0.5);
    place(blend(1.5, skull, snout), coat, {smooth: true});
    [-1.4, 1.4].forEach(z => place(cone([8.6, 15.5, z], [8.2, 19, z * 1.5], 1.1, 0.2), coat));
    paint(cone([12.6, 12.4, 0], [14, 12.2, 0], 1.3, 0.6).grow(0.5), white);
    block(14, 12, 0, 'black_concrete');
    [-2, 2].forEach(z => block(11, 14, z, 'black_concrete'));
  });
});
part('tail', () => {
  const pts = [[-6, 9, 0], [-10, 7, 2], [-13, 9, 4], [-12, 13, 6]];
  place(tube(pts, t => 1.2 + 1.8 * Math.sin(Math.PI * Math.min(1, t * 1.2))), coat, {smooth: true});
  paint(sphere([-12, 13, 6], 2.2), white);
});
