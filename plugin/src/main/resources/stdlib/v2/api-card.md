# BUILD SCRIPT API (JavaScript; all coordinates are integer blocks, ranges inclusive; m = block or material)
Small data mistakes do not stop the script: an unknown property is dropped, a shape a material lacks falls back to
the nearest (polished_andesite_wall -> andesite_wall) or the full block, a write with a NaN coordinate is skipped.
Each such fix is listed in the warnings; check them, because the build then differs from what you wrote.
Dialect: Rhino ES2016-ish. let/const/arrow functions/template strings/destructuring are fine. NOT supported:
`class`, spread arguments in calls f(...xs) (use f.apply(null, xs)), `Date`. Math.random()/rng() are seeded.

## Structure
meta({title, summary, type, style, expect_interior, exact_size:{x,y,z}, rooms:[{name, level, area, furnishing}]})
part('name', () => { ... })      group everything placed inside; nest for sub-parts ('tower/roof')
withT({at:[x,y,z], rotY:deg, rotX, rotZ, scale, mirror:'x'|'z'}, fn)   local frame; rotY>0 turns clockwise from above
at(x,y,z, fn)                    same as withT({at:[x,y,z]}, fn)
sym('x'|'z', center, (mirrored) => {...})   draw once and mirrored across the plane axis=center (stairs/doors flip correctly)
around(n, cx, cz, (i) => {...}, startDeg)    n copies rotated around the vertical axis through (cx, cz); draw relative to it
rng() rand(a,b) randInt(a,b) pick(arr) chance(p) noise(x,y,z,scale) lerp clamp range(a,b,step) log(...)
option('roof', ['gable','hipped','mansard'])   a design choice left open: returns the decided choice, else the first.
  The studio builds every choice of a new option and shows them side by side; decide({roof:'hipped'}) fixes one.

## Blocks
block(x,y,z,m)   box(x1,y1,z1,x2,y2,z2,m,{hollow|walls|outline})   clear(x1,y1,z1,x2,y2,z2 | x,y,z)
line([x,y,z],[x,y,z],m,r)   (r>0 = round beam; logs auto-orient)   get(x,y,z) -> 'block[state]' | null
op({op:'roof'|'staircase'|'trim'|..., ...})   legacy JSON operation (same fields as the classic op list)

## Queries: read what is built instead of guessing coordinates (world coordinates, what is placed so far)
rooms() -> [{x1,z1,x2,z2, y, area, furniture, light}]   enclosed rooms a door/stair/ladder reaches, largest first;
    y = the layer you stand in, x1..z2 = free floor inside the walls. Call it after the shells are built.
partBox(name) -> [x1,y1,z1,x2,y2,z2] | null   isAir(x,y,z)   isSolid(x,y,z)   surface(x,z) -> top solid y
note('text')   one line of intent for the current part: its signature details and the space it keeps clear
mark('name', x,y,z, note?) | mark('name', [x1,y1,z1,x2,y2,z2], note?)   names a key point or box of the part
anchor('name') -> {x,y,z,note} | {x1,y1,z1,x2,y2,z2,note} | null   reads a mark back, or a kit's own anchor:
    'house.body' / 'house.door' (volume), 'keep/tower.tower', 'walls/wall.wall', 'gate.door' (door()); the BUILD
    SHEET lists them. Place details relative to anchors instead of retyping numbers.
confine('part', rule)   set by the pipeline at the top of a script; do not write or remove it.
onWall(x,y,z, face, m)   puts a wall-mounted block (banner, torch, sign) on the real wall face near the cell, facing
    face; never replaces a block; returns the cell or null.   hangFrom(x,y,z, m, chain)   hangs a light under the
    real ceiling above.

## Shapes (signed-distance solids; place them with place())
sphere(c,r)  ellipsoid(c,[rx,ry,rz])  cuboid(a,b)  rbox(a,b,round)  cyl(a,b,r)  cone(a,b,r0,r1)
capsule(a,b,r0,r1)  torus(c,R,r,axis)  revolve(base,[[radius,height],...])  extrude([[x,z],...],y0,y1)
tube(points, r | [r per point] | t=>r, {curve:'smooth'|'line'|'bezier'})  loft([{c:[x,y,z], rx, rz} | {c, r}, ...])
sdf((x,y,z)=>distance, [x0,y0,z0,x1,y1,z1])   slow custom solid, keep small
union(a,b,...)  sub(a, cut1, cut2...)  inter(a,b,...)  blend(k, a,b,...)  (smooth union, k 2..6)
shape.move(x,y,z) .rot('x'|'y'|'z', deg, pivot?) .rotY(deg) .scale(s | sx,sy,sz) .mirror('x', at) .grow(by)
     .warp(amp, scale) (organic noise displacement: rocks, terrain, foliage) .and(b) .minus(b) .union(b)
     .bounds() -> [x0,y0,z0,x1,y1,z1]  .center()
place(shape, m, {smooth:true, shell:t, onlyAir:true, onlySolid:true})   returns blocks written
   smooth: terraces sloped tops/undersides with matching stairs+slabs (domes, roofs, hulls, bodies, hills)
   shell: hollow solid with walls t thick (big masses, domes, hulls, towers)
carve(shape)      paint(shape, m) (repaint only existing blocks)

## Materials (any block argument accepts these)
'stone_bricks'   'oak_stairs[facing=north,half=top]'   '60%cobblestone,40%andesite' (per-block random)
mix({stone_bricks:5, mossy_stone_bricks:2, cracked_stone_bricks:1}, {scale:3})   coherent patches (scale 0 = speckle)
grad('y', [[0,'cobblestone'],[0.3,'stone_bricks'],[1,'polished_andesite']], {from, to})   dithered bands (default: shape bounds)
byNormal({top:'grass_block', side:'dirt', bottom:'stone'})   weather(m, 0..1) mossy/cracked patches   trimmed(m, edgeM)
stairsOf(m) slabOf(m) wallOf(m) fenceOf(m) -> variant names   stair(m, facing, half) slab(m, 'top'|'bottom') -> states
axisOf('spruce_log','x') -> log lying along x
ramp('orange_terracotta', 5) -> 5 blocks of that hue, dark to light (from real block colours)
shade(ramp | [dark..light blocks], {light, height, cavity})   SCULPTURE SHADING: lit tops/sides lighter, undersides
   and creases darker, dithered — use it on every creature, statue and organic mass instead of one flat block
palette('medieval'|'tudor'|'nordic'|'fantasy'|'japanese'|'desert'|'castle'|'gothic'|'elven'|'steampunk'|'coastal')
   -> {base, wall, frame, beam, roof, roofAlt, trim, gable, floor, accent, door, shutter, glass, path, fence, lamp}

## Architecture kit (face/dir = 'north'|'south'|'east'|'west'; face = side the opening looks out of)
volume(x1,z1,x2,z2, y, {palette, floors, floorH, every, plinth, jetty, door:{face, at}|false, windows:{boxes, shutters, w}|false,
    roof:{...roof opts}, chimney:'east'|..., rooms, wood, hearth, ladder, beams, interior, wall, frame, beam, base, trim,
    gable, floor, roofM, doorM})
    ONE BUILDING MASS done like a master builder: stone plinth + stair skirt, corner posts and posts every 4, beams at
    floor lines, infill set 1 back (3 depth layers), windows in bays with sills/flower boxes, framed door with step and
    lantern, layered roof. INSIDE: a real staircase (ladder when too small), ceiling joists, and every floor furnished:
    rooms = one entry per floor, a room type or a list of types split by partition walls with doorways, e.g.
    rooms:[['kitchen','dining'], ['bedroom','study']] (default ['living','bedroom',...]; false = bare floors).
    Returns {x0,z0,x1,z1, top, roofY, floors, door, rooms:[{type, x1,z1,x2,z2, y, pieces}]}.
    COMPOSE 2-4 volumes of different size/height/roof (main + wing + tower + porch); never one plain box.
tower(cx,y,cz, r, h, {palette, square, roof:{...}|false, windows, m})   castle tower: flared plinth, graded weathered
    masonry, slits and windows, corbelled parapet, merlons (open top) or cone/pyramid roof
curtainWall([[x,z],...], y, h, {palette, t, outside:'right', walk: true|block, m})   wall with plinth skirt, buttresses, walkway, merlons
roof(x1,z1,x2,z2, y, {type:'gable'|'hip'|'pyramid'|'mansard'|'shed'|'flat'|'dome'|'cone'|'spire'|'onion', m, alt, altShare,
    tones:[bottom..top], trim, ridgeM, fill, overhang, gableOverhang, pitch:1|2, ridge:'x'|'z', eaves, height})
    x1..z2 = wall-top rectangle, y = layer above the wall top. Overhang, eave lip, rake trim, ridge cap and gable
    fill are automatic; alt mixes a second roof block in patches; pitch 2 = steep. Round types smooth and hollow.
dormer(x,y,z, face, {w, h, depth, m, roof, trim, glass})   gabled window box out of a roof slope
windowAt(x,y,z, face, {w, h, glass, frame, sill, shutters, arch, recess})   (x,y,z) = bottom-centre cell in the wall
door(x,y,z, face, {m, w:2, frame, step, lamp})     arch(x1,z1,x2,z2, y, {style:'round'|'gothic'|'flat', h, rise, depth, ring, thick, fill, smooth})
column(x,y,z,h, {r, m, base, capital})   battlements(x1,y,z1,x2,z2, m, {every, cap})   chimney(x,z,y0,y1,{m})
stairs(x,y,z, dir, steps, {w, m})   spiralStair(cx,y,cz,h,r,{m})   balcony(x,y,z, face, {w,d,m,rail})
timber(x1,y1,z1,x2,y2,z2, {m, every, braces})   half-timber frame on a wall plane (same x or same z)

## Interior kit (furnish rooms like a builder, never leave an empty shell)
furnish(x1,z1,x2,z2, y, type, {wood, stone, rug, border, banner, extra:[pieces], skip:[pieces], replace, lights})
furnish(room, type, opts)   the same for a room from rooms():  rooms().forEach(r => furnish(r, 'bedroom'))
    ONE CALL FURNISHES A ROOM: x1..z2 = the air cells inside its walls, y = its floor layer (furniture stands at y+1).
    Furniture only goes into air: walls, stairs and other parts' blocks are never replaced.
    type: bedroom | kitchen | dining | living | hall | tavern | library | study | workshop | smithy | storage | shop |
    chapel | throne. Reads the walls first: doorways, stairs and ladders stay clear, tall pieces avoid windows. Wall
    pieces (bed+nightstand, double bed, wardrobe, bookcase, counter, fireplace with mantel, desk+chair, armchair, sideboard,
    kegs, workbench, altar, throne, pillar, banner, barrels, chest, plant) go along the walls, far wall first; a
    centrepiece (table with chairs and rug, long dining table, tavern tables, pews and aisle, stalls, crates) in the
    middle; chandeliers hang from the ceiling. replace:true first removes what an earlier furnish() put there and is
    still there (e.g. the default rooms of volume()); a stair built over it stays. Returns {pieces}.
chandelier(x, top, z, {drop, r, m})   banner(x,y,z, face, color) (snaps to the wall, like onWall)   bed(x,y,z, facing, color)  table(x,y,z,{w,d,m})  chair(x,y,z, facing, m)
shelves(x1,y,z1,x2,z2,h)  fireplace(x,y,z, face, {m})  lamp(x,y,z, {hanging, chain})  rug(x1,z1,x2,z2, y, color, border)
kitchen(x,y,z, dir, n, face)   personal touches by hand: flower_pot / potted_*, candle[candles=3,lit=true],
    decorated_pot, lectern, brewing_stand, cauldron, anvil, chiseled_bookshelf, barrel, hay_block, *_wall_banner

## Nature and scene kit
tree(x,y,z, {species:'oak'|'birch'|'spruce'|'pine'|'dark_oak'|'cherry'|'jungle'|'acacia'|'willow'|'azalea'|'pale_oak'|'palm'|'dead',
    h (5..40, crown scales), crown, lean, leaves, log})   custom builder tree: root flare, wood branches, mixed-leaf
    clusters, hanging leaves; conifers in layered skirts. Never build trees as a sphere on a stick.
groundCover(x1,z1,x2,z2, {patches, density, forest, dry, kinds})   repaint the ground layer y=0 in blended patches
    (grass, coarse dirt, podzol, moss...) + clustered grass/flowers; use it under EVERY build's surroundings
terrain(x1,z1,x2,z2, {height, scale, cliffs, island, top}) -> h(x,z)   rolling terrain with stone cliffs
pond(cx,cz, rx,rz, {depth})   rock(x,y,z, r, {m, top})   bush(x,y,z, r, leaves)   hill(x,z, rx,rz, h, {top, side, rough})
path([[x,0,z],...], {w, m, rough})   flowers(x1,z1,x2,z2, {density, kinds})   water(shape | [x1,y1,z1,x2,y2,z2])
lampPost(x,y,z, {h, m, arm, dir})   fenceLine([[x,z],...], {m, post, gate, caps})
