# RECIPE: castle / fort / fortress / walls / keep
- Hierarchy of masses: a tall keep (the focal point), towers at every corner/turn taller than the walls, curtain
  walls, a gatehouse with two flanking towers and an arched gate (arch() + iron_bars or a portcullis of fences).
- Sizes: walls 8-14 high and 3 thick, towers r 3-6 and 4-8 higher than the walls, keep 1.5-2x the tower height.
  A castle is 50-140 blocks across; give it a moat, hill (terrain()) or cliff base.
- Masonry craft: graded texture (rough mossy cobble at the base -> stone bricks -> lighter at the top), weathering,
  buttresses, plinth skirts, corbelled parapets with merlons, arrow slits; tower(), curtainWall() do this.
- Variety: mix round and square towers, cone/spire roofs on some, open tops on others, a few timber hoardings or
  houses (volume()) leaning on the inner walls, banners (wool/banners), lanterns on the walkways.
- Inside the walls: a courtyard with paths, a well, stables/smithy as small volumes, trees; never an empty yard.
- Interiors: the keep has a great hall (volume rooms:['throne'] or furnish(..., 'hall')) with a high ceiling
  (floorH 6-8), chandeliers and banners; upper floors bedroom/library; gatehouse and towers get storage or a
  workshop. Every floor a player can reach is furnished and lit.
- Setting: road to the gate, terrain around, water or moat (pond()/water), rocks, trees grouped in clumps.
