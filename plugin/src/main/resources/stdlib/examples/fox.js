// Traits: pointed ears, slim snout, long bushy tail with a white tip, white chest, slender legs. ~30 long, 18 high.
// Palette: orange terracotta coat shaded by surface direction, white belly, black nose/eyes, mossy rock base.
// Parts: base, body, head, tail. Organic forms: tubes + capsules blended, smooth stairs/slabs on every surface.
meta({title: 'Fox', summary: 'A standing fox on a mossy rock', type: 'sculpture', style: 'stylized naturalistic',
      expect_interior: false});
const coat = byNormal({top: 'orange_terracotta', side: mix({orange_terracotta: 4, orange_concrete: 1}, {scale: 2}),
                       bottom: 'white_terracotta'});
part('base', () => { rock(0, 0, 0, 6); rock(-6, 0, 3, 3); });
part('body', () => {
  const torso = tube([[-6, 9, 0], [0, 9.5, 0], [6, 10, 0]], t => 3 + Math.sin(t * Math.PI) * 0.8);
  const legs = [[-4, -1.6], [-4, 1.6], [4, -1.6], [4, 1.6]].map(([x, z]) => capsule([x, 4, z * 1.3], [x, 9, z], 0.8, 1.4));
  place(blend(2.5, torso, union(legs)), coat, {smooth: true});
  paint(ellipsoid([6, 8.5, 0], [2.2, 2.6, 2.1]), 'white_concrete');
});
part('head', () => {
  const skull = sphere([9, 13, 0], 2.8), snout = cone([10.5, 12.4, 0], [14, 11.8, 0], 1.6, 0.5);
  place(blend(1.5, skull, snout), coat, {smooth: true});
  [-1.4, 1.4].forEach(z => place(cone([8.6, 15, z], [8.2, 18.5, z * 1.5], 1.1, 0.2), 'orange_terracotta'));
  paint(cone([12.6, 12.0, 0], [14, 11.8, 0], 1.3, 0.6).grow(0.5), 'white_concrete');
  block(14, 12, 0, 'black_concrete');
  [-2, 2].forEach(z => block(11, 13, z, 'black_concrete'));
});
part('tail', () => {
  const pts = [[-6, 9, 0], [-10, 7, 2], [-13, 9, 4], [-14, 13, 3]];
  place(tube(pts, t => 1.2 + 1.8 * Math.sin(Math.PI * Math.min(1, t * 1.2))), coat, {smooth: true});
  paint(sphere([-14, 13, 3], 2.2), 'white_concrete');
});
