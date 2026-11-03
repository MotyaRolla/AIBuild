# BUILD SCRIPT API (JavaScript; all coordinates are integer blocks, ranges inclusive; m = block or material)
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

## Blocks
block(x,y,z,m)   box(x1,y1,z1,x2,y2,z2,m,{hollow|walls|outline})   clear(x1,y1,z1,x2,y2,z2)
line([x,y,z],[x,y,z],m,r)   (r>0 = round beam; logs auto-orient)   get(x,y,z) -> 'block[state]' | null
op({op:'roof'|'staircase'|'trim'|..., ...})   legacy JSON operation (same fields as the classic op list)

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

## Architecture kit (face/dir = 'north'|'south'|'east'|'west'; face = side the opening looks out of)
roof(x1,z1,x2,z2, y, {type:'gable'|'hip'|'pyramid'|'shed'|'flat'|'dome'|'cone'|'spire'|'onion', m, fill, overhang, ridge:'x'|'z', height})
    x1..z2 = wall-top rectangle, y = first roof layer; round types are smooth and hollow, for round or square plans
windowAt(x,y,z, face, {w, h, glass, frame, sill, shutters, arch, recess})   (x,y,z) = bottom-centre cell in the wall
door(x,y,z, face, {m, w:2, frame, step, lamp})     arch(x1,z1,x2,z2, y, {style:'round'|'gothic'|'flat', h, rise, depth, ring, thick, fill, smooth})
column(x,y,z,h, {r, m, base, capital})   battlements(x1,y,z1,x2,z2, m, {every, cap})   chimney(x,z,y0,y1,{m})
stairs(x,y,z, dir, steps, {w, m})   spiralStair(cx,y,cz,h,r,{m})   balcony(x,y,z, face, {w,d,m,rail})
timber(x1,y1,z1,x2,y2,z2, {m, every, braces})   half-timber frame on a wall plane (same x or same z)

## Interior kit
bed(x,y,z, facing, color)  table(x,y,z,{w,d,m})  chair(x,y,z, facing, m)  shelves(x1,y,z1,x2,z2,h)
fireplace(x,y,z, face, {m})  lamp(x,y,z, {hanging, chain})  rug(x1,z1,x2,z2, y, color, border)  kitchen(x,y,z, dir, n, face)

## Nature and scene kit
tree(x,y,z, {species:'oak'|'birch'|'spruce'|'pine'|'dark_oak'|'cherry'|'jungle'|'acacia'|'willow'|'azalea'|'dead', h, crown, lean})
rock(x,y,z, r, {m})  bush(x,y,z, r, leaves)  hill(x,z, rx,rz, h, {top, side, rough})  path([[x,0,z],...], {w, m})
flowers(x1,z1,x2,z2, {density, kinds})   water(shape | [x1,y1,z1,x2,y2,z2])   (keep water enclosed)
