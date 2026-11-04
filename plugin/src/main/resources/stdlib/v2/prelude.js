// AIBuild build-script library (prelude). Runs before every build script.
// Coordinates: x -> east, y -> up, z -> south; y = 0 is the ground surface, things stand on it from y = 1.
// Everything here is plain JavaScript on top of the host functions __*; read it to see how kits work.

// ---------------------------------------------------------------- basics

function meta(o) { __meta(o); }
function log() { __log(Array.prototype.slice.call(arguments).map(function (a) {
    return typeof a === 'object' ? JSON.stringify(a) : String(a); }).join(' ')); }
function rng() { return __rng(); }
function rand(a, b) { return a + (b - a) * __rng(); }
function randInt(a, b) { return Math.floor(a + (b - a + 1) * __rng()); }
function pick(arr) { return arr[Math.floor(__rng() * arr.length)]; }
function chance(p) { return __rng() < p; }
function noise(x, y, z, scale) { return __noise(x, y, z, scale === undefined ? 8 : scale); }
function lerp(a, b, t) { return a + (b - a) * t; }
function clamp(v, a, b) { return v < a ? a : v > b ? b : v; }
function range(a, b, step) {
    if (b === undefined) { b = a; a = 0; }
    step = step || 1;
    var out = [];
    for (var i = a; step > 0 ? i < b : i > b; i += step) out.push(i);
    return out;
}
var DIRS = { north: [0, -1], south: [0, 1], west: [-1, 0], east: [1, 0] };
var OPPOSITE = { north: 'south', south: 'north', west: 'east', east: 'west' };
var LEFT_OF = { north: 'west', south: 'east', west: 'south', east: 'north' };
function _v(a) { return Array.isArray(a) ? a : [a.x, a.y, a.z]; }
function _args(a, from) { var l = Array.prototype.slice.call(a, from || 0); return l.length === 1 && Array.isArray(l[0]) && l[0].length && l[0][0] && l[0][0].__s !== undefined ? l[0] : l; }

// ---------------------------------------------------------------- parts and transforms

/** Groups everything fn() places under a name; reviews, renders and repairs address parts by name. */
function part(name, fn) {
    __part(name);
    try { return fn(); } finally { __endpart(); }
}
/**
 * Set by the pipeline at the top of a script, never needed in your own code: the named part may not break other
 * parts' blocks ('others') or writes only inside the rooms standing when it starts ('rooms'). Out-of-scope writes
 * are skipped.
 */
function confine(name, rule) { __confine(name, rule); }

// ---- the build sheet: what this part is for and where its key points are, for every later stage to read
/**
 * One line of intent for the current part, at most 200 characters: its signature details and the space it keeps
 * clear ("spiral stair in the SE corner; keep 3x3 clear"). Reviewers and later passes read it in the build sheet.
 */
function note(text) { __note(String(text)); }
/**
 * Names a point mark(name, x, y, z, note?) or a box mark(name, [x1, y1, z1, x2, y2, z2], note?) of the current
 * part (local coordinates, stored in world coordinates). Another part reads it back with anchor(name).
 */
function mark(name, a, b, c, d) {
    if (Array.isArray(a)) return __mark(String(name), a, b === undefined ? '' : String(b), false);
    // a point or box object, as anchor(), partBox() and the kits return them
    if (a !== null && typeof a === 'object') {
        var note = b === undefined ? '' : String(b);
        if (a.x1 !== undefined) return __mark(String(name), [a.x1, a.y1, a.z1, a.x2, a.y2, a.z2], note, false);
        if (a.x !== undefined) return __mark(String(name), [a.x, a.y, a.z], note, false);
    }
    return __mark(String(name), [a, b, c], d === undefined ? '' : String(d), false);
}
// ---- design options: try alternatives side by side, then keep one
var __picks = {};
/** Fixes choices of option() by name: decide({roof: 'hipped', bays: 5}). The studio writes this line for PICK. */
function decide(o) { for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) __picks[k] = o[k]; }
/**
 * A design choice left open: option('roof', ['gable', 'hipped', 'mansard']) returns the picked choice, or the
 * first one. In the studio every choice of a new option is built and shown side by side from the same camera, so
 * you compare instead of guess; PICK roof=hipped keeps one. Choices are strings, numbers or booleans.
 */
function option(name, choices) {
    if (typeof name !== 'string' || !Array.isArray(choices) || choices.length === 0) {
        throw new Error("option(name, [choice, ...]): give a name and a non-empty list of choices");
    }
    var keys = choices.map(function (c) { return JSON.stringify(c); });
    var i = 0, fixed = false;
    if (Object.prototype.hasOwnProperty.call(__picks, name)) {
        fixed = true;
        var j = keys.indexOf(JSON.stringify(__picks[name]));
        if (j >= 0) i = j;
        else __warn("decide: option '" + name + "' has no choice " + JSON.stringify(__picks[name]) + "; the first one was used");
    }
    __option(name, JSON.stringify(keys), i, fixed);
    return choices[i];
}
/**
 * A named anchor of the build so far, in world coordinates: a mark(), or one a kit set ('keep.body', 'keep.door',
 * 'keep/tower.tower', 'walls/wall.wall'; the build sheet lists them). A point is {x, y, z}; a box is
 * {x1, y1, z1, x2, y2, z2}; both carry note. Null when there is no such anchor.
 */
function anchor(name) { return __anchor(String(name)); }

function _frameOps(t) {
    var ops = [];
    if (t.mirror) ops.push(['m', t.mirror]);
    if (t.scale !== undefined) {
        var s = Array.isArray(t.scale) ? t.scale : [t.scale, t.scale, t.scale];
        ops.push(['s', s[0], s[1], s[2]]);
    }
    if (t.rotX) ops.push(['rx', t.rotX]);
    if (t.rotZ) ops.push(['rz', t.rotZ]);
    if (t.rotY) ops.push(['ry', t.rotY]);
    if (t.at) { var a = _v(t.at); ops.push(['t', a[0], a[1], a[2]]); }
    return ops;
}

/** Runs fn in a local frame: {at:[x,y,z], rotY:deg (clockwise from above), rotX, rotZ, scale, mirror:'x'|'z'}. */
function withT(t, fn) {
    __push(_frameOps(t));
    try { return fn(); } finally { __pop(); }
}
function at(x, y, z, fn) { return withT({ at: [x, y, z] }, fn); }

/** Builds fn() and its mirror image across the plane axis = center ('x' mirrors east/west). Use for symmetry. */
function sym(axis, center, fn) {
    fn(false);
    var off = axis === 'x' ? [2 * center, 0, 0] : [0, 0, 2 * center];
    withT({ mirror: axis, at: off }, function () { fn(true); });
}

/** Repeats fn(i) n times rotated around the vertical axis through (cx, cz); fn draws relative to that axis. */
function around(n, cx, cz, fn, startDeg) {
    for (var i = 0; i < n; i++) {
        (function (k) {
            withT({ rotY: (startDeg || 0) + 360 * k / n, at: [cx, 0, cz] }, function () { fn(k); });
        })(i);
    }
}

// ---------------------------------------------------------------- blocks

function block(x, y, z, m) { __block(x, y, z, m); }
/** Inclusive box; opts {hollow:true} (shell, inside cleared) | {walls:true} (4 sides) | {outline:true} (12 edges). */
function box(x1, y1, z1, x2, y2, z2, m, opts) {
    var mode = !opts ? 'fill' : opts.hollow ? 'hollow' : opts.walls ? 'walls' : opts.outline ? 'outline' : 'fill';
    return __box(x1, y1, z1, x2, y2, z2, m, mode);
}
function clear(x1, y1, z1, x2, y2, z2) {
    if (x2 === undefined) { x2 = x1; y2 = y1; z2 = z1; } // clear(x, y, z): one cell
    return __box(x1, y1, z1, x2, y2, z2, 'air', 'fill');
}
/** Line of blocks; r > 0 gives a round beam of that radius. Logs auto-orient along the line. */
function line(a, b, m, r) { return __line(_v(a), _v(b), m, r || 0); }
/** Block at a local position, e.g. 'oak_planks' or 'oak_stairs[facing=north,half=bottom,shape=straight]', or null. */
function get(x, y, z) { return __get(x, y, z); }
/** Legacy JSON operation, e.g. op({op:'roof', from:[0,6,0], to:[9,6,7], style:'gable', stairs:'spruce_stairs'}). */
function op(o) { return __op(o); }

// ---- queries: read what is already built instead of guessing its coordinates
/**
 * Rooms built so far (enclosed floors of 12+ cells that a door, stair or ladder reaches), largest first:
 * {x1, z1, x2, z2, y, area, furniture, light}; y is the layer you stand in, x1..z2 the free floor inside the walls.
 * World coordinates. Call it after the shells are built; furnish(room, type) takes one directly.
 */
function rooms() { return __rooms(); }
/** World bounds [x1, y1, z1, x2, y2, z2] of a part (with its nested parts) as placed so far, or null. */
function partBox(name) { return __partBox(name); }
function isAir(x, y, z) { return get(x, y, z) === null; }
/** A full opaque block (wall, floor, ceiling material), not glass, plants, furniture shapes or air. */
function isSolid(x, y, z) { return __solid(x, y, z); }
/** Highest solid y in column (x, z) at or below y0 (default 200), or null. */
function surface(x, z, y0) {
    for (var y = y0 === undefined ? 200 : y0; y >= -32; y--) if (isSolid(x, y, z)) return y;
    return null;
}
/**
 * Places a wall-mounted block (wall banner, wall torch, sign, item frame...) facing face on the real wall near
 * (x, y, z): out of the wall if (x, y, z) is inside it, toward it if it stands in the open. Never replaces a block.
 * Returns [x, y, z] or null when no wall is within 3 cells.
 */
function onWall(x, y, z, face, m) {
    face = DIRS[face] ? face : 'south';
    var p = _wallCell(x, y, z, face);
    if (!p) {
        // the wall is on the other side: it faces the other way
        var back = OPPOSITE[face];
        p = _wallCell(x, y, z, back);
        if (!p) return null;
        m = String(m).replace('facing=' + face, 'facing=' + back);
    }
    block(p[0], y, p[1], m);
    return [p[0], y, p[1]];
}
function _wallCell(x, y, z, face) {
    var d = DIRS[face], i;
    for (i = 0; i < 4 && get(x, y, z) !== null; i++) { x += d[0]; z += d[1]; }
    for (i = 0; i < 4 && get(x, y, z) === null && !isSolid(x - d[0], y, z - d[1]); i++) { x -= d[0]; z -= d[1]; }
    return get(x, y, z) === null && isSolid(x - d[0], y, z - d[1]) ? [x, z] : null;
}
/** The air cell right under the ceiling above (x, y, z) (searching 8 up), or null when nothing is above. */
function _underCeiling(x, y, z) {
    var t = y;
    while (t < y + 8 && get(x, t + 1, z) === null) t++;
    return get(x, t + 1, z) === null ? null : t;
}
/** Hangs a light (default lantern) under the real ceiling above (x, y, z), on chain links if given. Returns its cell or null. */
function hangFrom(x, y, z, m, chain) {
    var t = get(x, y, z) === null ? _underCeiling(x, y, z) : null;
    if (t === null) return null;
    var i = 0;
    for (; i < (chain || 0) && t - i > y; i++) block(x, t - i, z, 'iron_chain');
    block(x, t - i, z, _base(m || 'lantern') + '[hanging=true]');
    return [x, t - i, z];
}

// ---------------------------------------------------------------- shapes (signed-distance solids)

var ShapeProto = {
    move: function (x, y, z) { var v = Array.isArray(x) ? x : [x, y, z]; return _xf(this, [['t', v[0], v[1], v[2]]]); },
    rot: function (axis, deg, pivot) {
        var p = pivot ? _v(pivot) : this.center();
        return _xf(this, [['t', -p[0], -p[1], -p[2]], ['r' + axis, deg], ['t', p[0], p[1], p[2]]]);
    },
    rotY: function (deg, pivot) { return this.rot('y', deg, pivot); },
    scale: function (sx, sy, sz, pivot) {
        if (sy === undefined) { sy = sx; sz = sx; }
        var p = pivot ? _v(pivot) : this.center();
        return _xf(this, [['t', -p[0], -p[1], -p[2]], ['s', sx, sy, sz], ['t', p[0], p[1], p[2]]]);
    },
    mirror: function (axis, at) {
        var c = at === undefined ? 0 : at;
        var t = axis === 'x' ? ['t', 2 * c, 0, 0] : axis === 'z' ? ['t', 0, 0, 2 * c] : ['t', 0, 2 * c, 0];
        return _xf(this, [['m', axis], t]);
    },
    bounds: function () { return __bounds(this); },
    center: function () { var b = __bounds(this); return [(b[0] + b[3]) / 2, (b[1] + b[4]) / 2, (b[2] + b[5]) / 2]; },
    union: function () { return union([this].concat(_args(arguments))); },
    minus: function () { return sub.apply(null, [this].concat(_args(arguments))); },
    and: function (o) { return inter(this, o); },
    grow: function (by) { return _S({ t: 'offset', of: this, by: by }); },
    warp: function (amp, scale) { return _S({ t: 'warp', of: this, amp: amp, scale: scale || 6 }); },
    place: function (m, opts) { return place(this, m, opts); },
    carve: function () { return carve(this); }
};
function _S(spec) { var o = Object.create(ShapeProto); o.__s = __shape(spec); return o; }
function _xf(s, ops) { return _S({ t: 'xf', of: s, ops: ops }); }

function sphere(c, r) { return _S({ t: 'sphere', c: _v(c), r: r }); }
function ellipsoid(c, r) { return _S({ t: 'ellipsoid', c: _v(c), r: r }); }
/** Solid box shape over cells a..b (use box() to place plain boxes directly). */
function cuboid(a, b) { return _S({ t: 'box', a: _v(a), b: _v(b) }); }
function rbox(a, b, round) { return _S({ t: 'rbox', a: _v(a), b: _v(b), round: round }); }
/** Cylinder whose end caps are centred on cells a and b (both included), radius r. */
function cyl(a, b, r) { return _S({ t: 'cyl', a: _v(a), b: _v(b), r: r }); }
/** Cone/frustum from radius r0 at a to r1 at b (one may be 0). */
function cone(a, b, r0, r1) { return _S({ t: 'cone', a: _v(a), b: _v(b), r0: r0, r1: r1 || 0 }); }
/** Rounded beam from a to b, radius r0 tapering to r1. */
function capsule(a, b, r0, r1) { return _S({ t: 'capsule', a: _v(a), b: _v(b), r0: r0, r1: r1 === undefined ? r0 : r1 }); }
function torus(c, R, r, axis) { return _S({ t: 'torus', c: _v(c), R: R, r: r, axis: axis || 'y' }); }
/** Lathe: profile [[radius, height], ...] revolved around the vertical axis through base. Vases, towers, domes, onions. */
function revolve(base, profile) { return _S({ t: 'revolve', base: _v(base), profile: profile }); }
/** Vertical prism: polygon [[x, z], ...] filled over layers y0..y1. Irregular footprints, ship hulls in plan, walls. */
function extrude(poly, y0, y1) { return _S({ t: 'extrude', poly: poly, y0: y0, y1: y1 }); }
/**
 * Organic tube through points (Catmull-Rom curve). r: a number, one radius per point, or a function t (0..1) -> radius.
 * opts.curve: 'smooth' (default) | 'line' | 'bezier'. Necks, tails, branches, horns, arches, rivers, cables.
 */
function tube(points, r, opts) {
    var n = points.length, radii = [];
    for (var i = 0; i < n; i++) {
        radii.push(typeof r === 'function' ? r(n === 1 ? 0 : i / (n - 1)) : Array.isArray(r) ? r[i] : r);
    }
    return _S({ t: 'tube', pts: points.map(_v), radii: radii, curve: (opts && opts.curve) || 'smooth' });
}
/** Loft through horizontal sections [{c:[x,y,z], rx, rz} | {c, r}], bottom to top. Bodies, hulls, bottles. */
function loft(sections) {
    return _S({ t: 'loft', centers: sections.map(function (s) { return _v(s.c); }),
        radii: sections.map(function (s) { return [s.rx || s.r, s.rz || s.r]; }) });
}
/** Custom solid from a distance function f(x,y,z) (< 0 inside) inside bounds [x0,y0,z0,x1,y1,z1]. Slow: keep it small. */
function sdf(f, bounds) { return _S({ t: 'fn', f: f, bounds: bounds }); }

function union() { return _S({ t: 'union', of: _args(arguments) }); }
function sub(a) { return _S({ t: 'sub', a: a, of: _args(arguments, 1) }); }
function inter() { return _S({ t: 'inter', of: _args(arguments) }); }
/** Smooth union with blend radius k (2..6 typical): organic joints between body parts or a trunk and its roots. */
function blend(k) { return _S({ t: 'blend', k: k, of: _args(arguments, 1) }); }

/**
 * Places a shape. opts: smooth:true (stairs/slabs on sloped tops and undersides: domes, roofs, hulls, creatures),
 * shell:t (hollow with walls t thick), onlyAir:true (don't overwrite), onlySolid:true (repaint existing blocks only).
 */
function place(shape, m, opts) { return __place(shape, m, opts || {}); }
function carve(shape) { return __place(shape, 'air', { carve: true }); }
function paint(shape, m) { return __place(shape, m, { onlySolid: true }); }

// ---------------------------------------------------------------- materials

function _M(spec) { return { __m: __mat(spec) }; }
/** Patchy mix: mix({stone_bricks: 6, mossy_stone_bricks: 2, cracked_stone_bricks: 1}, {scale: 3}); scale 0 = per-block noise. */
function mix(weights, opts) {
    var of = [], w = [];
    if (Array.isArray(weights)) weights.forEach(function (p) { of.push(p[0]); w.push(p[1]); });
    else Object.keys(weights).forEach(function (k) { of.push(k); w.push(weights[k]); });
    return _M({ t: 'mix', of: of, w: w, scale: opts && opts.scale !== undefined ? opts.scale : 3 });
}
/** Dithered bands: grad('y', [[0, 'cobblestone'], [0.3, 'stone_bricks'], [1, 'polished_andesite']], {from: 1, to: 12}). */
function grad(axis, stops, opts) {
    return _M({ t: 'grad', axis: axis, stops: stops.map(function (s) { return s[0]; }),
        of: stops.map(function (s) { return s[1]; }), from: opts && opts.from, to: opts && opts.to });
}
/** Different blocks for surfaces facing up / sideways / down: byNormal({top: 'grass_block', side: 'dirt', bottom: 'stone'}). */
function byNormal(o) { return _M({ t: 'normal', top: o.top, side: o.side, bottom: o.bottom }); }
/** Turns a share (0..1) of blocks into mossy/cracked/exposed variants, in patches. */
function weather(m, amount) { return _M({ t: 'weather', of: m, amount: amount === undefined ? 0.25 : amount }); }
/** Uses edge on the creases and corners of a shape, m elsewhere. */
function trimmed(m, edge) { return _M({ t: 'trim', of: m, edge: edge }); }

function stairsOf(m) { return __family(m, 'stairs'); }
function slabOf(m) { return __family(m, 'slab'); }
function wallOf(m) { return __family(m, 'wall'); }
function fenceOf(m) { return __family(m, 'fence'); }
function _base(m) { return typeof m === 'string' ? m.split('[')[0] : m; }
/** Stair state from a block or stairs name: stair('stone_bricks', 'north', 'top') -> 'stone_brick_stairs[...]'. */
function stair(m, facing, half) {
    var b = _base(m);
    var name = typeof b === 'string' && /_stairs$/.test(b) ? b : stairsOf(m);
    if (!name) {
        // a block without stairs (hay_block, glass...): the full block keeps the shape instead of stopping the script
        __warn('auto-corrected: ' + m + ' has no stairs, the full block was used');
        return m;
    }
    return name + '[facing=' + facing + ',half=' + (half || 'bottom') + ']';
}
/** Stair state when the block has stairs, else the block itself (furniture trims in any material). */
function _stairOr(m, facing, half) {
    if (typeof _base(m) !== 'string') return m; // a mix()/grad() material has no single stair family
    return stairsOf(m) || /_stairs$/.test(_base(m)) ? stair(m, facing, half) : m;
}
/** Slab state: slab('oak_planks', 'top'). */
function slab(m, type) {
    var b = _base(m);
    var name = typeof b === 'string' && /_slab$/.test(b) ? b : slabOf(m);
    if (!name) {
        __warn('auto-corrected: ' + m + ' has no slab, the full block was used');
        return m;
    }
    return name + '[type=' + (type || 'bottom') + ']';
}

/** n blocks of one hue, darkest first, picked from real block colours: ramp('orange_terracotta', 5). */
function ramp(base, n, spread) { return __ramp(base, n || 5, spread === undefined ? 0.5 : spread); }
/**
 * Sculptural shading like builders do on statues: each surface cell takes a step of a dark-to-light ramp from how it
 * faces the light, how high it is in the shape and how enclosed it is (creases darker, ridges lighter).
 * shade(['black_concrete','gray_concrete','light_gray_concrete']) or shade('brown_terracotta', {n: 5}).
 * opts: light [x,y,z] (default upper south-west), height (0..0.6, darker toward the ground), cavity (0..2).
 */
function shade(r, opts) {
    opts = opts || {};
    var list = typeof r === 'string' ? ramp(r, opts.n || 5, opts.spread) : r;
    var spec = { t: 'shade', of: list };
    if (opts.light) spec.light = opts.light;
    if (opts.height !== undefined) spec.height = opts.height;
    if (opts.cavity !== undefined) spec.cavity = opts.cavity;
    return _M(spec);
}

/**
 * Curated palettes with clear roles, so every material of a build belongs together:
 * base (foundation, plinth), wall (infill), frame (posts), beam, roof, trim (roof edges, sills), gable, floor,
 * accent, door, shutter, glass, path, fence, lamp.
 */
var PALETTES = {
    medieval: { base: mix({ cobblestone: 4, stone: 3, andesite: 2, mossy_cobblestone: 1 }, { scale: 2 }),
        wall: mix({ calcite: 3, white_terracotta: 2, diorite: 1 }, { scale: 2 }), frame: 'spruce_log', beam: 'stripped_spruce_log',
        roof: 'spruce_planks', roofAlt: 'dark_oak_planks', trim: 'dark_oak_planks', gable: 'spruce_planks', floor: 'spruce_planks',
        accent: 'stone_bricks', door: 'spruce_door', shutter: 'spruce_trapdoor', glass: 'glass_pane',
        path: mix({ dirt_path: 5, coarse_dirt: 2, gravel: 1 }, { scale: 2 }), fence: 'spruce_fence', lamp: 'lantern' },
    tudor: { base: mix({ stone_bricks: 4, cobblestone: 2, mossy_stone_bricks: 1 }, { scale: 2 }),
        wall: mix({ white_terracotta: 3, calcite: 2 }, { scale: 3 }), frame: 'dark_oak_log', beam: 'stripped_dark_oak_log',
        roof: 'deepslate_tiles', roofAlt: 'cobbled_deepslate', trim: 'dark_oak_planks', gable: 'dark_oak_planks', floor: 'dark_oak_planks',
        accent: 'bricks', door: 'dark_oak_door', shutter: 'dark_oak_trapdoor', glass: 'glass_pane',
        path: mix({ cobblestone: 3, stone: 2, gravel: 1, andesite: 1 }, { scale: 1 }), fence: 'dark_oak_fence', lamp: 'lantern' },
    nordic: { base: mix({ cobblestone: 3, stone: 3, mossy_cobblestone: 2, tuff: 1 }, { scale: 2 }),
        wall: 'spruce_planks', frame: 'stripped_spruce_log', beam: 'spruce_log', roof: 'dark_oak_planks', roofAlt: 'spruce_planks',
        trim: 'spruce_planks', gable: 'spruce_planks', floor: 'spruce_planks', accent: 'stone_bricks', door: 'spruce_door',
        shutter: 'spruce_trapdoor', glass: 'glass_pane', path: mix({ gravel: 3, coarse_dirt: 2, cobblestone: 1 }), fence: 'spruce_fence',
        lamp: 'lantern' },
    fantasy: { base: mix({ stone_bricks: 3, mossy_stone_bricks: 2, cracked_stone_bricks: 1 }, { scale: 2 }),
        wall: mix({ white_terracotta: 2, calcite: 2, smooth_quartz: 1 }, { scale: 3 }), frame: 'dark_oak_log', beam: 'stripped_dark_oak_log',
        roof: 'purpur_block', roofAlt: 'deepslate_tiles', trim: 'dark_oak_planks', gable: 'dark_oak_planks', floor: 'oak_planks',
        accent: 'amethyst_block', door: 'dark_oak_door', shutter: 'dark_oak_trapdoor', glass: 'purple_stained_glass_pane',
        path: mix({ moss_block: 2, cobblestone: 2, mossy_cobblestone: 2, dirt_path: 2 }, { scale: 1 }), fence: 'dark_oak_fence',
        lamp: 'soul_lantern' },
    japanese: { base: mix({ stone: 3, andesite: 2, cobblestone: 1 }, { scale: 2 }), wall: mix({ white_wool: 3, calcite: 1 }),
        frame: 'dark_oak_log', beam: 'stripped_dark_oak_log', roof: 'deepslate_tiles', roofAlt: 'polished_deepslate',
        trim: 'dark_oak_planks', gable: 'dark_oak_planks', floor: 'bamboo_planks', accent: 'red_terracotta', door: 'bamboo_door',
        shutter: 'bamboo_trapdoor', glass: 'white_stained_glass_pane', path: mix({ gravel: 4, andesite: 2, stone: 1 }),
        fence: 'dark_oak_fence', lamp: 'lantern' },
    desert: { base: 'smooth_sandstone', wall: mix({ sandstone: 3, smooth_sandstone: 2, cut_sandstone: 1 }), frame: 'stripped_acacia_log',
        beam: 'stripped_jungle_log', roof: 'smooth_sandstone', roofAlt: 'cut_sandstone', trim: 'cut_red_sandstone', gable: 'cut_sandstone',
        floor: 'smooth_sandstone', accent: 'orange_terracotta', door: 'acacia_door', shutter: 'acacia_trapdoor', glass: 'glass_pane',
        path: mix({ sand: 3, smooth_sandstone: 2, gravel: 1 }), fence: 'acacia_fence', lamp: 'lantern' },
    castle: { base: mix({ cobblestone: 3, mossy_cobblestone: 2, andesite: 2, stone: 2 }, { scale: 2 }),
        wall: mix({ stone_bricks: 6, cracked_stone_bricks: 1, mossy_stone_bricks: 2, andesite: 1 }, { scale: 2 }),
        frame: 'polished_andesite', beam: 'stripped_spruce_log', roof: 'deepslate_tiles', roofAlt: 'cobbled_deepslate',
        trim: 'stone_bricks', gable: 'stone_bricks', floor: 'spruce_planks', accent: 'polished_andesite', door: 'spruce_door',
        shutter: 'spruce_trapdoor', glass: 'glass_pane', path: mix({ cobblestone: 3, gravel: 2, andesite: 1 }), fence: 'cobblestone_wall',
        lamp: 'lantern' },
    gothic: { base: mix({ cobbled_deepslate: 3, deepslate_bricks: 2, tuff: 1 }), wall: mix({ deepslate_bricks: 4, cracked_deepslate_bricks: 1, deepslate_tiles: 1 }),
        frame: 'polished_blackstone', beam: 'polished_deepslate', roof: 'blackstone', roofAlt: 'deepslate_tiles', trim: 'polished_blackstone',
        gable: 'deepslate_tiles', floor: 'dark_oak_planks', accent: 'gilded_blackstone', door: 'dark_oak_door', shutter: 'iron_trapdoor',
        glass: 'gray_stained_glass_pane', path: mix({ cobbled_deepslate: 3, gravel: 1, tuff: 1 }), fence: 'blackstone_wall',
        lamp: 'soul_lantern' },
    elven: { base: mix({ mossy_stone_bricks: 3, stone_bricks: 2, moss_block: 1 }), wall: mix({ birch_planks: 3, stripped_birch_log: 1 }),
        frame: 'birch_log', beam: 'stripped_birch_log', roof: 'cherry_planks', roofAlt: 'mangrove_planks', trim: 'birch_planks',
        gable: 'birch_planks', floor: 'birch_planks', accent: 'quartz_block', door: 'birch_door', shutter: 'birch_trapdoor',
        glass: 'glass_pane', path: mix({ moss_block: 3, dirt_path: 2, mossy_cobblestone: 1 }), fence: 'birch_fence', lamp: 'lantern' },
    steampunk: { base: mix({ bricks: 3, stone_bricks: 2, polished_andesite: 1 }), wall: mix({ bricks: 3, mud_bricks: 1 }),
        frame: 'stripped_dark_oak_log', beam: 'exposed_copper', roof: 'weathered_cut_copper', roofAlt: 'oxidized_cut_copper',
        trim: 'dark_oak_planks', gable: 'spruce_planks', floor: 'spruce_planks', accent: 'copper_block', door: 'iron_door',
        shutter: 'copper_trapdoor', glass: 'glass_pane', path: mix({ polished_andesite: 2, cobblestone: 2, gravel: 1 }),
        fence: 'iron_bars', lamp: 'lantern' },
    coastal: { base: mix({ stone_bricks: 3, cobblestone: 2, mossy_cobblestone: 1 }), wall: mix({ birch_planks: 3, white_terracotta: 1 }),
        frame: 'stripped_oak_log', beam: 'stripped_spruce_log', roof: 'light_blue_terracotta', roofAlt: 'cyan_terracotta',
        trim: 'white_terracotta', gable: 'birch_planks', floor: 'oak_planks', accent: 'prismarine_bricks', door: 'birch_door',
        shutter: 'oak_trapdoor', glass: 'glass_pane', path: mix({ sand: 2, gravel: 2, dirt_path: 1 }), fence: 'birch_fence', lamp: 'lantern' }
};
/** A palette by name (medieval, tudor, nordic, fantasy, japanese, desert, castle, gothic, elven, steampunk, coastal), or a custom one merged over medieval. */
function palette(p) {
    var base = PALETTES.medieval, out = {}, k;
    var src = typeof p === 'string' ? (PALETTES[p] || base) : (p || base);
    for (k in base) out[k] = base[k];
    for (k in src) out[k] = src[k];
    return out;
}
/** Deterministic variation: true for a share of cells, in small patches (roof and wall texture). */
function _patchy(x, y, z, share) { return share > 0 && (noise(x, y, z, 2.2) + 1) / 2 < share; }

// ---------------------------------------------------------------- architecture kit

/**
 * Roof over the wall-top rectangle x1..x2 / z1..z2 starting at layer y (the layer above the wall top), built the way
 * good builders do: overhanging eaves with an upside-down-stair lip, optional steep pitch, rake trim along the gable
 * edges, a ridge cap, filled gable ends, and texture (a second roof block mixed in patches).
 * opts.type: gable | hip | pyramid | mansard | shed | flat (stair roofs); dome | cone | spire | onion (smooth, for round
 * or square plans). opts: m (roof block; stairs/slabs derived), alt (mixed in), altShare (0..1, default 0.18),
 * tones [blocks bottom row -> top row] (bands), trim (block for the rake edges and the eave lip), ridgeM (ridge cap),
 * fill (gable block), overhang (default 1), gableOverhang, pitch (1 = 45 deg, 2 = steep), ridge 'x'|'z',
 * eaves:false (no lip), height (round roofs), finial.
 */
function roof(x1, z1, x2, z2, y, opts) {
    opts = opts || {};
    var type = opts.type || 'gable', m = opts.m || 'spruce_planks';
    var xa = Math.min(x1, x2), xb = Math.max(x1, x2), za = Math.min(z1, z2), zb = Math.max(z1, z2);
    var cx = (xa + xb) / 2, cz = (za + zb) / 2, w = xb - xa + 1, d = zb - za + 1;
    var ov = opts.overhang === undefined ? 1 : opts.overhang;
    if (type === 'dome' || type === 'cone' || type === 'spire' || type === 'onion') {
        var r = Math.max(w, d) / 2 + ov - 0.5, h = opts.height || (type === 'dome' ? r : type === 'onion' ? r * 1.8 : r * (type === 'spire' ? 3 : 1.6));
        var s;
        if (type === 'dome') s = ellipsoid([cx, y - 1, cz], [r, h, r]).and(cuboid([cx - r - 1, y, cz - r - 1], [cx + r + 1, y + h + 1, cz + r + 1]));
        else if (type === 'onion') s = revolve([cx, y, cz], [[r * 0.75, 0], [r * 1.05, h * 0.28], [r * 0.9, h * 0.5], [r * 0.45, h * 0.75], [0.6, h * 0.92], [0.3, h]]);
        else s = cone([cx, y, cz], [cx, y + h, cz], r, 0.2);
        place(s, opts.tones ? grad('y', opts.tones.map(function (t, i2) { return [i2 / Math.max(1, opts.tones.length - 1), t]; })) : m,
            { smooth: true, shell: opts.shell || 2 });
        if (type !== 'dome' && ov > 0) {
            // flared eave ring of upside-down stairs where the cone meets the wall
            var rr = r + 0.3;
            for (var a = 0; a < 360; a += 360 / Math.max(12, Math.round(rr * 6.3))) {
                var px = Math.round(cx + Math.cos(a * Math.PI / 180) * rr), pz = Math.round(cz + Math.sin(a * Math.PI / 180) * rr);
                var f = Math.abs(px - cx) > Math.abs(pz - cz) ? (px > cx ? 'west' : 'east') : (pz > cz ? 'north' : 'south');
                if (get(px, y - 1, pz) === null) block(px, y - 1, pz, stair(opts.trim || m, f, 'top'));
            }
        }
        if (opts.finial !== false) block(Math.round(cx), Math.round(y + h + 1), Math.round(cz), opts.finial || 'lightning_rod');
        return s;
    }
    var o = {
        m: m, alt: opts.alt, altShare: opts.altShare === undefined ? (opts.alt ? 0.18 : 0) : opts.altShare, tones: opts.tones,
        trim: opts.trim, ridgeM: opts.ridgeM, fill: opts.fill === undefined ? m : opts.fill, ov: ov,
        ovG: opts.gableOverhang === undefined ? ov : opts.gableOverhang, eaves: opts.eaves !== false && ov > 0
    };
    if (type === 'gable' || type === 'shed') {
        var alongX = opts.ridge ? opts.ridge === 'x' : w >= d;
        return _gableRoof(xa, za, xb, zb, y, o, alongX, opts.pitch || 1, type === 'shed', opts.facing);
    }
    if (type === 'flat') return _flatRoof(xa, za, xb, zb, y, o);
    var pitch = opts.pitch || 1;
    var pitches = type === 'mansard' ? function (k) { return k < (opts.steepRows || 2) + ov ? 2 : 1; } : function () { return pitch; };
    return _hipRoof(xa, za, xb, zb, y, o, pitches);
}

function _tone(o, k, rows, x, yy, z) {
    var t = o.tones ? o.tones[Math.min(o.tones.length - 1, Math.floor(k * o.tones.length / Math.max(1, rows)))] : o.m;
    return o.alt && _patchy(x, yy, z, o.altShare) ? o.alt : t;
}

/** Gable (or shed) roof; alongX = ridge runs east-west. */
function _gableRoof(xa, za, xb, zb, y, o, alongX, p, shed, facing) {
    var ov = o.ov, ovG = o.ovG;
    var a0 = (alongX ? xa : za) - ovG, a1 = (alongX ? xb : zb) + ovG;
    var wc0 = alongX ? za : xa, wc1 = alongX ? zb : xb;
    var c0 = wc0 - ov, c1 = wc1 + ov;
    var lowF = alongX ? 'south' : 'east', highF = alongX ? 'north' : 'west';
    function P(a, c) { return alongX ? [a, c] : [c, a]; }
    function put(a, yy, c, m) { var q = P(a, c); block(q[0], yy, q[1], m); }
    function layer(k) { return y + k * p; }
    var span = c1 - c0 + 1;
    var rows = shed ? span : Math.floor(span / 2), odd = !shed && span % 2 === 1;
    var shedHigh = shed && facing ? (facing === highF ? true : false) : false; // shed: which side is high
    for (var k = 0; k < rows; k++) {
        var base = layer(k), top = base + p - 1;
        for (var a = a0; a <= a1; a++) {
            var edge = a === a0 || a === a1;
            var sides = shed ? [shedHigh ? c1 - k : c0 + k] : [c0 + k, c1 - k];
            for (var si = 0; si < sides.length; si++) {
                var c = sides[si], f = shed ? (shedHigh ? highF : lowF) : (si === 0 ? lowF : highF);
                var q = P(a, c);
                var tone = edge && o.trim ? o.trim : _tone(o, k, rows, q[0], top, q[1]);
                for (var yy = base; yy < top; yy++) put(a, yy, c, edge && o.trim ? o.trim : _tone(o, k, rows, q[0], yy, q[1]));
                put(a, top, c, stair(tone, f, 'bottom'));
                if (k === 0 && o.eaves) put(a, base - 1, c, stair(o.trim || o.m, f, 'top'));
            }
        }
    }
    var ridgeTop = layer(rows);
    if (!shed) {
        for (var a2 = a0; a2 <= a1; a2++) {
            var cap = o.ridgeM || o.trim || o.m;
            if (odd) {
                var cm = c0 + rows;
                for (var yy2 = layer(rows) - p + 1; yy2 <= ridgeTop - 1; yy2++) put(a2, yy2, cm, _tone(o, rows, rows, a2, yy2, cm));
                put(a2, ridgeTop, cm, slab(cap, 'bottom'));
            } else {
                put(a2, ridgeTop, c0 + rows - 1, slab(cap, 'bottom'));
                put(a2, ridgeTop, c0 + rows, slab(cap, 'bottom'));
            }
        }
    }
    // gable ends: fill under the roof surface on the two wall planes
    if (o.fill) {
        var planes = shed ? [a0 + ovG, a1 - ovG] : [a0 + ovG, a1 - ovG];
        for (var pi = 0; pi < planes.length; pi++) {
            for (var c3 = wc0; c3 <= wc1; c3++) {
                var kk = shed ? (shedHigh ? c1 - c3 : c3 - c0) : Math.min(c3 - c0, c1 - c3);
                var surface = kk >= rows ? ridgeTop - 1 : layer(kk) + p - 2;
                for (var yy3 = y; yy3 <= surface; yy3++) put(planes[pi], yy3, c3, o.fill);
            }
        }
    }
    return [P(a0, c0)[0], layer(0), P(a0, c0)[1], P(a1, c1)[0], ridgeTop, P(a1, c1)[1]];
}

/** Hip / pyramid / mansard roof: rings shrinking from all four sides; pitches(k) = layers of ring k. */
function _hipRoof(xa, za, xb, zb, y, o, pitches) {
    var ov = o.ov;
    var yy = y, k = 0, rows = Math.ceil(Math.min(xb - xa, zb - za) / 2) + ov + 1;
    while (true) {
        var x0 = xa - ov + k, x1 = xb + ov - k, z0 = za - ov + k, z1 = zb + ov - k;
        if (x0 > x1 || z0 > z1) break;
        var p = pitches(k), top = yy + p - 1;
        if (x0 === x1 || z0 === z1) {
            // ridge line
            for (var x = x0; x <= x1; x++) for (var z = z0; z <= z1; z++) {
                for (var l = yy; l <= top; l++) block(x, l, z, _tone(o, k, rows, x, l, z));
                block(x, top + 1, z, slab(o.ridgeM || o.trim || o.m, 'bottom'));
            }
            return;
        }
        for (var x2 = x0; x2 <= x1; x2++) for (var z2 = z0; z2 <= z1; z2++) {
            var onX = x2 === x0 || x2 === x1, onZ = z2 === z0 || z2 === z1;
            if (!onX && !onZ) continue;
            var f = z2 === z0 ? 'south' : z2 === z1 ? 'north' : x2 === x0 ? 'east' : 'west';
            var t = _tone(o, k, rows, x2, top, z2);
            for (var l2 = yy; l2 < top; l2++) block(x2, l2, z2, _tone(o, k, rows, x2, l2, z2));
            block(x2, top, z2, stair(t, f, 'bottom'));
            if (k === 0 && o.eaves) block(x2, yy - 1, z2, stair(o.trim || o.m, f, 'top'));
        }
        // close the top of a ring that will not be covered (flat top of a mansard / pyramid tip)
        yy += p;
        k++;
        if (x0 + 1 > x1 - 1 || z0 + 1 > z1 - 1) {
            for (var x3 = x0 + 1; x3 <= x1 - 1; x3++) for (var z3 = z0 + 1; z3 <= z1 - 1; z3++) block(x3, yy - 1, z3, slab(o.ridgeM || o.m, 'top'));
            return;
        }
    }
}

/** Flat roof with a raised parapet of the trim block and slab cap. */
function _flatRoof(xa, za, xb, zb, y, o) {
    box(xa, y - 1, za, xb, y - 1, zb, o.m);
    for (var x = xa; x <= xb; x++) for (var z = za; z <= zb; z++) {
        if (x === xa || x === xb || z === za || z === zb) {
            block(x, y, z, o.trim || o.m);
            block(x, y + 1, z, slab(o.trim || o.m, 'bottom'));
        }
    }
}

/**
 * Dormer: a small gabled window box sticking out of a roof slope. (x, y, z) = bottom-centre cell on the wall line,
 * face = the side it looks out of. opts: w (3), h (3), depth (into the roof, 3), m (walls), roof (block), trim, glass.
 */
function dormer(x, y, z, face, opts) {
    opts = opts || {};
    var w = opts.w || 3, h = opts.h || 3, dep = opts.depth || 3, m = opts.m || 'spruce_planks';
    var dir = DIRS[face], side = DIRS[LEFT_OF[face]], half = Math.floor(w / 2);
    function cell(along, out) { return [x + side[0] * along + dir[0] * out, z + side[1] * along + dir[1] * out]; }
    // clear only the space inside the dormer's walls: the main roof around it must stay closed
    for (var a = -half + 1; a <= half - 1; a++) for (var o = -dep + 1; o < 0; o++) for (var u = 0; u < h; u++) {
        var c = cell(a, o);
        block(c[0], y + u, c[1], 'air');
    }
    for (var a2 = -half; a2 <= half; a2++) for (var o2 = -dep; o2 <= 0; o2++) for (var u2 = 0; u2 < h; u2++) {
        var edge = a2 === -half || a2 === half || o2 === -dep;
        var c2 = cell(a2, o2);
        if (edge || o2 === 0) block(c2[0], y + u2, c2[1], o2 === 0 && !edge && u2 > 0 ? (opts.glass || 'glass_pane') : m);
        if (o2 === 0 && !edge && u2 === 0) block(c2[0], y + u2, c2[1], m);
    }
    var p0 = cell(-half, -dep), p1 = cell(half, 0);
    roof(p0[0], p0[1], p1[0], p1[1], y + h, { m: opts.roof || 'dark_oak_planks', trim: opts.trim, ridge: face === 'north' || face === 'south' ? 'z' : 'x', overhang: 0, gableOverhang: 1, fill: m });
}

/**
 * Window in an existing wall. (x, y, z) = bottom-centre cell of the opening in the wall plane; face = the side the
 * window looks out of ('south' for a front wall at the largest z). opts: w (1..5, default 1), h (default 2),
 * glass, frame (block around the opening, 1 cell proud of the wall), sill (true / block), shutters (true / trapdoor id),
 * arch (round top), recess (glass set back 1 cell into a thick wall).
 */
function windowAt(x, y, z, face, opts) {
    opts = opts || {};
    var w = opts.w || 1, h = opts.h || 2, dir = DIRS[face], side = DIRS[LEFT_OF[face]];
    var glass = opts.glass || 'glass_pane', half = Math.floor((w - 1) / 2);
    function cell(along, up, out) {
        return [x + side[0] * along + dir[0] * out, y + up, z + side[1] * along + dir[1] * out];
    }
    var back = opts.recess ? -1 : 0;
    for (var a = -half; a <= w - 1 - half; a++) {
        for (var u = 0; u < h; u++) {
            var p = cell(a, u, 0), q = cell(a, u, back);
            block(p[0], p[1], p[2], 'air');
            block(q[0], q[1], q[2], glass);
        }
    }
    if (opts.frame) {
        for (var a2 = -half - 1; a2 <= w - half; a2++) {
            var top = cell(a2, h, 1), bot = cell(a2, -1, 1);
            block(top[0], top[1], top[2], opts.arch ? stair(opts.frame, OPPOSITE[face], 'top') : opts.frame);
            if (!opts.sill) block(bot[0], bot[1], bot[2], opts.frame);
        }
        for (var u2 = 0; u2 < h; u2++) {
            var l = cell(-half - 1, u2, 1), r = cell(w - half, u2, 1);
            block(l[0], l[1], l[2], opts.frame);
            block(r[0], r[1], r[2], opts.frame);
        }
    }
    if (opts.sill) {
        var sillBlock = opts.sill === true ? (opts.frame || 'stone_bricks') : opts.sill;
        for (var a3 = -half; a3 <= w - 1 - half; a3++) {
            var s = cell(a3, -1, 1);
            block(s[0], s[1], s[2], stair(sillBlock, OPPOSITE[face], 'top'));
        }
    }
    if (opts.shutters) {
        // shutters: true takes the palette's trapdoor
        var shutter = opts.shutters === true ? palette(opts.palette).shutter : opts.shutters;
        var gap = opts.frame ? 2 : 1, ls = cell(-half - gap, 0, 1), rs = cell(w - 1 - half + gap, 0, 1);
        for (var u3 = 0; u3 < h; u3++) {
            block(ls[0], ls[1] + u3, ls[2], shutter + '[facing=' + face + ',open=true]');
            block(rs[0], rs[1] + u3, rs[2], shutter + '[facing=' + face + ',open=true]');
        }
    }
}

/**
 * Door in an existing wall at the bottom cell (x, y, z); face = outward side of the wall. opts: m (door id),
 * frame (block around), step (stairs outside, true or block), lamp (lantern beside the door), w: 2 for double doors.
 */
function door(x, y, z, face, opts) {
    opts = opts || {};
    var m = opts.m || 'oak_door', dir = DIRS[face], side = DIRS[LEFT_OF[face]];
    // a wood name ('dark_oak', 'spruce_planks') means that wood's door
    if (!/_door$/.test(m)) m = m.replace(/_(planks|log|wood)$/, '') + '_door';
    var facing = OPPOSITE[face], n = opts.w === 2 ? 2 : 1;
    for (var i = 0; i < n; i++) {
        var px = x + side[0] * i, pz = z + side[1] * i;
        block(px, y + 1, pz, 'air');
        var hinge = n === 2 ? (i === 0 ? 'left' : 'right') : 'left';
        var state = m + '[facing=' + facing + ',hinge=' + hinge + ']';
        block(px, y, pz, state);
        __door(px, y, pz, state);
    }
    if (opts.frame) {
        for (var u = 0; u <= 2; u++) {
            block(x - side[0] + dir[0], y + u, z - side[1] + dir[1], opts.frame);
            block(x + side[0] * n + dir[0], y + u, z + side[1] * n + dir[1], opts.frame);
        }
        for (var j = -1; j <= n; j++) block(x + side[0] * j + dir[0], y + 2, z + side[1] * j + dir[1], opts.frame);
    }
    if (opts.step) {
        var sb = opts.step === true ? 'stone_bricks' : opts.step;
        for (var k = 0; k < n; k++) block(x + side[0] * k + dir[0], y - 1, z + side[1] * k + dir[1], stair(sb, facing, 'bottom'));
    }
    if (opts.lamp) block(x - side[0] + dir[0] * (opts.frame ? 2 : 1), y + 2, z - side[1] + dir[1] * (opts.frame ? 2 : 1),
        (opts.lamp === true ? 'lantern' : opts.lamp));
    __mark('door', [x, y, z], (n === 2 ? 'double door' : 'door') + ' facing ' + face, true);
}

/** Column from (x, y, z) up h blocks. opts: r (0 = square 1x1, >= 1 round), m, base and capital (block; stairs ring). */
function column(x, y, z, h, opts) {
    opts = opts || {};
    var m = opts.m || 'stone_bricks', r = opts.r || 0;
    if (r >= 1) place(cyl([x, y, z], [x, y + h - 1, z], r + 0.5), m);
    else box(x, y, z, x, y + h - 1, z, m);
    var ring = function (yy, half, b) {
        ['north', 'south', 'west', 'east'].forEach(function (f) {
            var d = DIRS[f];
            block(x + d[0] * (r + 1), yy, z + d[1] * (r + 1), stair(b, OPPOSITE[f], half));
        });
    };
    if (opts.base) ring(y, 'bottom', opts.base);
    if (opts.capital) ring(y + h - 1, 'top', opts.capital);
}

/**
 * Arched opening through a wall, optionally framed by a ring of voussoirs. The opening spans the cells from
 * (x1, z1) to (x2, z2) along the wall (same z: wall runs east-west; same x: north-south), its lowest cell is at y.
 * opts: h (total opening height, default about 1.5 x span), style 'round' | 'gothic' | 'flat', rise (height of the
 * curved part), depth (wall cells to cut, counted toward +z or +x from the given wall cell; negative toward -z/-x),
 * ring (block for the arch ring), thick (ring thickness, default 1), fill (fills the opening instead of air, e.g.
 * glass for a window or a gate block), smooth (round the ring with stairs). Returns the opening shape.
 */
function arch(x1, z1, x2, z2, y, opts) {
    opts = opts || {};
    var alongX = x1 !== x2 || z1 === z2;
    var span = (alongX ? Math.abs(x2 - x1) : Math.abs(z2 - z1)) + 1, hw = span / 2;
    var style = opts.style || 'round';
    var rise = opts.rise !== undefined ? opts.rise : style === 'gothic' ? hw * 1.5 : style === 'flat' ? 0 : hw;
    var h = opts.h || Math.max(Math.round(rise) + 2, Math.round(span * 1.5));
    var spring = y - 0.5 + h - rise;
    var depth = opts.depth || 1, w = alongX ? z1 : x1;
    var w0 = depth > 0 ? w : w + depth + 1, w1 = depth > 0 ? w + depth - 1 : w;
    var c = alongX ? (x1 + x2) / 2 : (z1 + z2) / 2;
    function shape(grow) {
        return _S({ t: 'arch', along: alongX ? 'x' : 'z', c: c, w0: w0, w1: w1, hw: hw + grow,
            rise: style === 'flat' ? rise + grow : rise + grow, bottom: y, spring: spring, style: style });
    }
    var hole = shape(0);
    if (opts.ring) place(sub(shape(opts.thick || 1), hole), opts.ring, { smooth: !!opts.smooth });
    if (opts.fill) place(hole, opts.fill);
    else carve(hole);
    return hole;
}

/** Crenellations along the rectangle x1..x2 / z1..z2 at height y (merlons every other block). */
function battlements(x1, y, z1, x2, z2, m, opts) {
    opts = opts || {};
    var every = opts.every || 2;
    for (var x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
        for (var z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
            var edge = x === x1 || x === x2 || z === z1 || z === z2;
            if (!edge) continue;
            var k = (x - x1) + (z - z1);
            if (k % every === 0) block(x, y, z, m);
            else if (opts.cap) block(x, y, z, slab(opts.cap, 'bottom'));
        }
    }
}

/** Spiral stair of slabs around a central post: centre (cx, y, cz), height h, radius r (2..4). */
function spiralStair(cx, y, cz, h, r, opts) {
    opts = opts || {};
    var m = opts.m || 'stone_bricks', post = opts.post || m, per = opts.steps || 12;
    box(cx, y, cz, cx, y + h, cz, post);
    for (var i = 0; i < h * 2; i++) {
        var ang = 2 * Math.PI * i / per, yy = y + Math.floor(i / 2), type = i % 2 === 0 ? 'bottom' : 'top';
        for (var rr = 1; rr <= r; rr++) {
            var px = Math.round(cx + Math.cos(ang) * rr), pz = Math.round(cz + Math.sin(ang) * rr);
            if (px === cx && pz === cz) continue;
            block(px, yy, pz, slab(m, type));
            for (var hh = 1; hh <= 3; hh++) if (yy + hh <= y + h) block(px, yy + hh, pz, 'air');
        }
    }
}

/** Straight walkable flight climbing toward dir from the lowest step; uses the legacy staircase op. */
function stairs(x, y, z, dir, steps, opts) {
    opts = opts || {};
    var m = opts.m || 'oak_planks';
    return op({ op: 'staircase', from: [x, y, z], direction: dir, steps: steps, width: opts.w || 1,
        block: stairsOf(m) || m, support: opts.support || m, headroom: opts.headroom || 3 });
}

/** Chimney column x,z from y0 to y1 with a cap and smoke (campfire). */
function chimney(x, z, y0, y1, opts) {
    opts = opts || {};
    var m = opts.m || 'bricks';
    box(x, y0, z, x, y1, z, m);
    block(x, y1 + 1, z, opts.smoke === false ? slab(m, 'bottom') : 'campfire[lit=true,signal_fire=false]');
}

/** Balcony slab platform w wide, d deep out of a wall at floor y, centred on (x, z), with a railing. */
function balcony(x, y, z, face, opts) {
    opts = opts || {};
    var w = opts.w || 3, d = opts.d || 2, m = opts.m || 'spruce_planks', rail = opts.rail || fenceOf(m) || 'oak_fence';
    var dir = DIRS[face], side = DIRS[LEFT_OF[face]], half = Math.floor(w / 2);
    for (var a = -half; a <= w - 1 - half; a++) {
        for (var o = 1; o <= d; o++) {
            var px = x + side[0] * a + dir[0] * o, pz = z + side[1] * a + dir[1] * o;
            block(px, y, pz, m);
            if (o === d || a === -half || a === w - 1 - half) block(px, y + 1, pz, rail);
        }
        block(x + side[0] * a + dir[0], y - 1, z + side[1] * a + dir[1], stair(m, OPPOSITE[face], 'top'));
    }
}

/**
 * Half-timbering on a flat wall plane between (x1, y1, z1) and (x2, y2, z2) (same x or same z): posts every
 * opts.every (default 3), a mid-rail and optional diagonal braces between posts. Draw it 1 cell outside a wall
 * or replace the wall surface itself.
 */
function timber(x1, y1, z1, x2, y2, z2, opts) {
    opts = opts || {};
    var m = opts.m || 'dark_oak_log', every = opts.every || 3, alongX = z1 === z2;
    var a0 = alongX ? Math.min(x1, x2) : Math.min(z1, z2), a1 = alongX ? Math.max(x1, x2) : Math.max(z1, z2);
    function p(a, y) { return alongX ? [a, y, z1] : [x1, y, a]; }
    line(p(a0, y1), p(a1, y1), m);
    line(p(a0, y2), p(a1, y2), m);
    if (opts.rail !== false && y2 - y1 >= 4) { var mid = Math.round((y1 + y2) / 2); line(p(a0, mid), p(a1, mid), m); }
    for (var a = a0; a <= a1; a += every) line(p(a, y1), p(a, y2), m);
    line(p(a1, y1), p(a1, y2), m);
    if (opts.braces) {
        for (var b = a0; b + every <= a1; b += every * 2) line(p(b, y1), p(b + every, y2), opts.braces === true ? m : opts.braces);
    }
}

/** Log/wood block turned along an axis ('x', 'y', 'z'); other blocks are returned unchanged. */
function axisOf(m, axis) {
    return typeof m === 'string' && /(_log|_wood|_stem|_hyphae|basalt|bone_block|hay_block|pillar|chain)$/.test(m) ? m + '[axis=' + axis + ']' : m;
}

function _posts(a0, a1, every) {
    var out = [a0];
    for (var a = a0 + every; a < a1 - 1; a += every) out.push(a);
    out.push(a1);
    return out;
}

/**
 * One building mass, built like an experienced builder would: a stone plinth, a frame of corner posts, posts every
 * few blocks and beams at every floor line on the outer face, wall infill set one block back (three depth layers),
 * windows centred in the bays with sills, shutters or flower boxes, a door with a step, lintel and lantern, floors
 * with ladders and lights inside, an optional jetty (upper floors overhang), chimney, and a layered roof.
 * Compose several volumes of different sizes, heights and roofs (main house + wing + tower + porch) for a real build.
 *
 * x1..x2, z1..z2 = outer footprint of the ground floor; y = ground floor layer (1 on flat ground).
 * opts: palette (name or object, see palette()), floors (2), floorH (4 = floor + 3 air), every (post spacing, 4),
 * plinth (stone rows, 1), jetty (true: upper floors overhang 1), door ({face, at: along-wall coordinate} or false),
 * windows ({boxes: true, shutters: true, w} or false), roof (options for roof(); {type: 'none'} for no roof),
 * chimney ('east'|'west'|'north'|'south'), interior (false = no floors/ladders/lights), wall/frame/beam/base/trim/
 * gable/floor/shutter/glass/roofM/doorM (override palette roles).
 * Returns {x0, z0, x1, z1, top, roofY, floors: [y...], door: [x, y, z]}.
 */
function volume(x1, z1, x2, z2, y, opts) {
    // a building stands against walls and towers already built instead of cutting into them
    __fit('volume x ' + x1 + '..' + x2 + ', z ' + z1 + '..' + z2);
    try { return _volume(x1, z1, x2, z2, y, opts); } finally { __endfit(); }
}

function _volume(x1, z1, x2, z2, y, opts) {
    opts = opts || {};
    var P = palette(opts.palette || opts.style);
    ['base', 'wall', 'frame', 'beam', 'roofAlt', 'trim', 'gable', 'floor', 'shutter', 'glass'].forEach(function (k) {
        if (opts[k] !== undefined) P[k] = opts[k];
    });
    if (opts.roofM) P.roof = opts.roofM;
    if (opts.doorM) P.door = opts.doorM;
    var floors = opts.floors || 2, fh = opts.floorH || 4, every = opts.every || 4, jet = opts.jetty ? 1 : 0;
    var plinth = opts.plinth === undefined ? 1 : opts.plinth;
    var xa = Math.min(x1, x2), xb = Math.max(x1, x2), za = Math.min(z1, z2), zb = Math.max(z1, z2);
    var win = opts.windows === false ? null : (opts.windows || {});
    var doorOpt = opts.door === false ? null : (opts.door || {});
    var doorFace = doorOpt ? (doorOpt.face || 'south') : null, doorCell = null;
    var floorYs = [], inner = [], X0, X1, Z0, Z1;
    var sides = ['north', 'south', 'west', 'east'];
    for (var f = 0; f < floors; f++) {
        var j = f > 0 ? jet : 0;
        X0 = xa - j; X1 = xb + j; Z0 = za - j; Z1 = zb + j;
        var y0 = y + f * fh, y1 = y0 + fh - 1, top = f === floors - 1;
        floorYs.push(y0);
        if (opts.interior !== false || f === 0) box(X0 + 1, y0, Z0 + 1, X1 - 1, y0, Z1 - 1, P.floor);
        box(X0 + 1, y0 + 1, Z0 + 1, X1 - 1, y1, Z1 - 1, P.wall, { walls: true });
        if (f > 0 && jet) box(xa + 1, y0, za + 1, xb - 1, y0, zb - 1, P.floor);
        if (f > 0 && opts.interior !== false && opts.beams !== false) {
            // floor joists at the post lines, seen as beams in the ceiling below
            var jx = X1 - X0 >= Z1 - Z0, jp = _posts(jx ? X0 : Z0, jx ? X1 : Z1, every);
            for (var ji = 1; ji + 1 < jp.length; ji++) {
                if (jx) box(jp[ji], y0, Z0 + 1, jp[ji], y0, Z1 - 1, axisOf(P.beam, 'z'));
                else box(X0 + 1, y0, jp[ji], X1 - 1, y0, jp[ji], axisOf(P.beam, 'x'));
            }
        }
        inner.push([X0 + 2, Z0 + 2, X1 - 2, Z1 - 2, y0, y1]);
        sides.forEach(function (side) {
            var alongX = side === 'north' || side === 'south';
            var plane = side === 'north' ? Z0 : side === 'south' ? Z1 : side === 'west' ? X0 : X1;
            var inward = OPPOSITE[side];
            var a0 = alongX ? X0 : Z0, a1 = alongX ? X1 : Z1;
            var posts = _posts(a0, a1, every);
            function P3(a, yy, out) {
                var d = DIRS[side];
                return alongX ? [a, yy, plane + d[1] * out] : [plane + d[0] * out, yy, a];
            }
            function put(a, yy, out, m) { var q = P3(a, yy, out); block(q[0], q[1], q[2], m); }
            for (var a = a0; a <= a1; a++) {
                var isPost = posts.indexOf(a) >= 0;
                for (var yy = y0; yy <= y1; yy++) {
                    if (f === 0 && yy < y0 + plinth) put(a, yy, 0, P.base);
                    else if (isPost) put(a, yy, 0, axisOf(P.frame, 'y'));
                    else if ((yy === y0 && f > 0) || (yy === y1 && top)) put(a, yy, 0, axisOf(P.beam, alongX ? 'x' : 'z'));
                }
                if (f > 0 && jet && isPost) put(a, y0 - 1, 0, stair(P.trim, inward, 'top'));
            }
            if (f === 0 && plinth > 0) {
                for (var a2 = a0 - 1; a2 <= a1 + 1; a2++) put(a2, y0, 1, stair(P.base, inward, 'bottom'));
            }
            // bays: windows (and the door on the ground floor)
            var bays = [];
            for (var i = 0; i + 1 < posts.length; i++) if (posts[i + 1] - posts[i] >= 2) bays.push([posts[i] + 1, posts[i + 1] - 1]);
            var doorBay = -1;
            if (f === 0 && doorFace === side && bays.length) {
                var want = doorOpt.at !== undefined ? doorOpt.at : (a0 + a1) / 2, best = 0;
                for (var b2 = 0; b2 < bays.length; b2++) {
                    if (Math.abs((bays[b2][0] + bays[b2][1]) / 2 - want) < Math.abs((bays[best][0] + bays[best][1]) / 2 - want)) best = b2;
                }
                doorBay = best;
            }
            for (var bi = 0; bi < bays.length; bi++) {
                var bw = bays[bi][1] - bays[bi][0] + 1, mid = Math.floor((bays[bi][0] + bays[bi][1]) / 2);
                if (bi === doorBay) {
                    var dq = P3(mid, y0 + 1, -1);
                    door(dq[0], dq[1], dq[2], side, { m: P.door });
                    put(mid, y0, 0, stair(P.base, inward, 'bottom'));
                    put(mid, y0, 1, 'air');
                    if (bw >= 3) {
                        put(mid - 1, y0 + 1, -1, P.frame); put(mid + 1, y0 + 1, -1, P.frame);
                        put(mid - 1, y0 + 2, -1, P.frame); put(mid + 1, y0 + 2, -1, P.frame);
                    }
                    put(mid, y0 + 3 <= y1 ? y0 + 3 : y1, 0, axisOf(P.beam, alongX ? 'x' : 'z'));
                    if (bw >= 3 && (floors > 1 || fh >= 5)) put(mid + 1, y0 + 3, 0, (P.lamp || 'lantern') + '[hanging=true]');
                    doorCell = P3(mid, y0 + 1, -1);
                    continue;
                }
                if (!win || bw < 1 || fh < 4) continue;
                var ww = win.w || (bw >= 4 ? 2 : 1), wh = Math.min(win.h || 2, fh - 2);
                var w0 = Math.floor((bays[bi][0] + bays[bi][1] + 1 - ww) / 2);
                for (var wa = w0; wa < w0 + ww; wa++) {
                    for (var wy = y0 + 2; wy < y0 + 2 + wh; wy++) put(wa, wy, -1, P.glass);
                    var box2 = (win.boxes === true || (win.boxes !== false && f === 0)) && f === 0;
                    put(wa, y0 + 1, 0, box2 ? 'flowering_azalea_leaves' : stair(P.trim, inward, 'top'));
                }
                if (win.shutters === true ? bw - ww >= 2 : win.shutters !== false && bw - ww >= 4) {
                    for (var sy = y0 + 2; sy < y0 + 2 + wh; sy++) {
                        put(w0 - 1, sy, 0, P.shutter + '[facing=' + side + ',open=true]');
                        put(w0 + ww, sy, 0, P.shutter + '[facing=' + side + ',open=true]');
                    }
                }
            }
        });
    }
    var wallTop = y + floors * fh - 1, roofOpts = { m: P.roof, alt: P.roofAlt, trim: P.trim, fill: P.gable, ridgeM: P.trim }, k;
    var ro = opts.roof || {};
    for (k in ro) roofOpts[k] = ro[k];
    if (roofOpts.type !== 'none') roof(X0, Z0, X1, Z1, wallTop + 1, roofOpts);
    if (opts.chimney) {
        var cside = opts.chimney, cxm = Math.round((X0 + X1) / 2), czm = Math.round((Z0 + Z1) / 2);
        var chx = cside === 'east' ? X1 + 1 : cside === 'west' ? X0 - 1 : cxm, chz = cside === 'south' ? Z1 + 1 : cside === 'north' ? Z0 - 1 : czm;
        var chTop = wallTop + Math.ceil(Math.min(X1 - X0, Z1 - Z0) / 2) + 3;
        box(chx, y, chz, chx, chTop, chz, P.base);
        block(chx, chTop + 1, chz, 'campfire[lit=true,signal_fire=false]');
    }
    var rooms = [];
    if (opts.interior !== false) {
        for (var fl = 0; fl + 1 < floors; fl++) _climb(inner[fl], fh, P, opts);
        var plan = opts.rooms === undefined ? ['living', 'bedroom', 'study', 'bedroom'] : opts.rooms;
        for (var f2 = 0; f2 < floors; f2++) {
            var want = plan === false ? null : plan[Math.min(f2, plan.length - 1)];
            if (want === false || want === null || want === undefined) {
                var q = inner[f2];
                lamp(Math.round((q[0] + q[2]) / 2), q[5], Math.round((q[1] + q[3]) / 2), { hanging: true });
                continue;
            }
            _split(inner[f2], typeof want === 'string' ? [want] : want, P).forEach(function (rm) {
                var res = furnish(rm.x1, rm.z1, rm.x2, rm.z2, rm.y, rm.type, { wood: opts.wood || _woodOf(P.floor), stone: opts.hearth === true ? undefined : opts.hearth });
                rooms.push({ type: rm.type, floor: rm.floor, x1: rm.x1, z1: rm.z1, x2: rm.x2, z2: rm.z2, y: rm.y, pieces: res.pieces });
            });
        }
    }
    __mark('body', [X0, y, Z0, X1, wallTop, Z1], 'floors y=' + floorYs.join(',') + '; roof from y=' + (wallTop + 1), true);
    if (doorCell) __mark('door', doorCell, 'main door', true);
    return { x0: X0, z0: Z0, x1: X1, z1: Z1, top: wallTop, roofY: wallTop + 1, floors: floorYs, door: doorCell, rooms: rooms };
}

function _woodOf(m) {
    var w = typeof m === 'string' && /^([a-z_]+?)_planks$/.exec(m);
    return w ? w[1] : 'spruce';
}

/**
 * Way up from floor area a = [x1,z1,x2,z2,floorY,ceilY] to the floor above: a straight stair along the inner
 * north (else west) wall with a railed opening in the upper floor when the room is long enough, else a ladder.
 */
function _climb(a, fh, P, opts) {
    var lenX = a[2] - a[0] + 1, lenZ = a[3] - a[1] + 1, y0 = a[4], W = _wood(opts.wood || _woodOf(P.floor));
    var alongX = lenX >= fh + 3 ? true : lenZ >= fh + 3 ? false : null;
    if (alongX === null || opts.ladder) {
        for (var ly = y0 + 1; ly <= y0 + fh; ly++) block(a[0], ly, a[1], 'ladder[facing=south]');
        return;
    }
    var u = alongX ? [1, 0] : [0, 1], face = alongX ? 'east' : 'south', side = alongX ? [0, 1] : [1, 0];
    var sx = a[0] + u[0], sz = a[1] + u[1];
    for (var k = 0; k < fh; k++) {
        var x = sx + u[0] * k, z = sz + u[1] * k;
        for (var yy = y0 + 1; yy < y0 + 1 + k; yy++) block(x, yy, z, W.planks);
        block(x, y0 + 1 + k, z, stair(W.planks, face, 'bottom'));
        // headroom: open the upper floor above the climbing steps, with a railing on the upper floor
        if (k >= Math.max(0, fh - 4) && k <= fh - 2) {
            block(x, y0 + fh, z, 'air');
            block(x + side[0], y0 + fh + 1, z + side[1], W.fence);
        }
    }
    var kb = Math.max(0, fh - 4);
    block(sx + u[0] * (kb - 1), y0 + fh + 1, sz + u[1] * (kb - 1), W.fence);
}

/** Whether the outer wall at position p (along the split axis) has a door or opening at either end of a partition. */
function _doorAt(a, alongX, p) {
    var y = a[4] + 1;
    var ends = alongX ? [[p, a[1] - 1], [p, a[3] + 1]] : [[a[0] - 1, p], [a[2] + 1, p]];
    for (var i = 0; i < 2; i++) {
        var b1 = get(ends[i][0], y, ends[i][1]), b2 = get(ends[i][0], y + 1, ends[i][1]);
        if ((b1 !== null && /_door/.test(b1)) || (b1 === null && b2 === null)) return true;
    }
    return false;
}

/** Splits floor area a along its longer side into rooms of the given types, with partition walls and doorways. */
function _split(a, types, P) {
    var alongX = a[2] - a[0] >= a[3] - a[1], lo = alongX ? a[0] : a[1], hi = alongX ? a[2] : a[3], n = types.length;
    var out = [], from = lo;
    for (var i = 0; i < n; i++) {
        var to = i === n - 1 ? hi : Math.round(lo + (hi - lo + 1) * (i + 1) / n) - 1;
        if (alongX) out.push({ type: types[i], x1: from, z1: a[1], x2: to, z2: a[3], y: a[4] });
        else out.push({ type: types[i], x1: a[0], z1: from, x2: a[2], z2: to, y: a[4] });
        if (i < n - 1) {
            // never wall off a door: move the partition along until both ends meet plain wall
            for (var shift = 0; shift < 4 && (_doorAt(a, alongX, to + 1) || _doorAt(a, alongX, to + 1 + 1) || _doorAt(a, alongX, to)); shift++) {
                var alt = shift % 2 === 0 ? to + 2 + shift : to - 2 - shift;
                if (alt > from + 1 && alt < hi - 1 && !_doorAt(a, alongX, alt + 1) && !_doorAt(a, alongX, alt + 2) && !_doorAt(a, alongX, alt)) { to = alt; break; }
            }
            if (alongX) out[out.length - 1].x2 = to; else out[out.length - 1].z2 = to;
            var wpos = to + 1, mid = alongX ? Math.round((a[1] + a[3]) / 2) + 1 : Math.round((a[0] + a[2]) / 2) + 1;
            // columns the stair already uses, and the cells beside them, stay open
            var open = {};
            for (var s0 = alongX ? a[1] : a[0]; s0 <= (alongX ? a[3] : a[2]); s0++) {
                for (var y2 = a[4] + 1; y2 <= a[5]; y2++) {
                    if (get(alongX ? wpos : s0, y2, alongX ? s0 : wpos) !== null) { open[s0] = open[s0 + 1] = true; break; }
                }
            }
            for (var s = alongX ? a[1] : a[0]; s <= (alongX ? a[3] : a[2]); s++) for (var yy = a[4] + 1; yy <= a[5]; yy++) {
                if (open[s]) continue;
                var px = alongX ? wpos : s, pz = alongX ? s : wpos;
                var post = s === mid - 1 || s === mid + 1;
                block(px, yy, pz, s === mid && yy <= a[4] + 2 ? 'air' : post || yy === a[5] ? axisOf(P.frame, 'y') : P.wall);
            }
            from = wpos + 1;
        }
    }
    out.forEach(function (r) { r.floor = a[4]; });
    return out;
}

/**
 * Castle tower: flared plinth, weathered masonry graded from rough stone at the base to dressed stone above,
 * arrow slits and windows climbing around, a corbelled ring under a crenellated parapet, and a conical roof
 * (roof: false for an open fighting top). (cx, y, cz) = centre of the base, r = radius (round) or half-width
 * (square: true), h = shaft height. opts: palette, m, square, roof (roof() options or false), windows (every n layers).
 */
function tower(cx, y, cz, r, h, opts) {
    opts = opts || {};
    var P = palette(opts.palette || 'castle');
    var m = opts.m || weather(grad('y', [[0, P.base], [0.3, P.wall], [1, P.wall]], { from: y, to: y + h }), 0.12);
    var sq = !!opts.square, top = y + h;
    part('tower', function () {
        if (sq) {
            box(cx - r, y, cz - r, cx + r, top - 1, cz + r, m, { hollow: true });
            box(cx - r - 1, y, cz - r - 1, cx + r + 1, y, cz + r + 1, P.base, { walls: true });
            for (var c = 0; c < 4; c++) {
                var f = ['north', 'south', 'west', 'east'][c], d = DIRS[f];
                for (var a = -r - 1; a <= r + 1; a++) {
                    var px = d[0] === 0 ? cx + a : cx + d[0] * (r + 2), pz = d[1] === 0 ? cz + a : cz + d[1] * (r + 2);
                    block(px, y, pz, stair(P.base, OPPOSITE[f], 'bottom'));
                }
            }
        } else {
            place(cyl([cx, y, cz], [cx, top - 1, cz], r + 0.5), m, { shell: 2 });
            place(cone([cx, y, cz], [cx, y + 2, cz], r + 1.7, r + 0.5), P.base, { smooth: true, onlyAir: true });
        }
        var roofed = opts.roof !== false;
        if (sq) box(cx - r + 1, y, cz - r + 1, cx + r - 1, y, cz + r - 1, P.floor);
        else place(cyl([cx, y, cz], [cx, y, cz], r - 0.5), P.floor);
        // openings climbing around the shaft
        var every = opts.windows || 5, n = 0;
        for (var wy = y + 3; wy < top - 3; wy += every, n++) {
            var face = ['south', 'east', 'north', 'west'][n % 4], d2 = DIRS[face];
            var big = n % 2 === 0 && r >= 3;
            for (var dep = r - 1; dep <= r + 1; dep++) {
                for (var u = 0; u < (big ? 3 : 2); u++) {
                    block(cx + d2[0] * dep, wy + u, cz + d2[1] * dep, dep === r - 1 && big ? P.glass : 'air');
                }
            }
            if (big) block(cx + d2[0] * (r + 1), wy - 1, cz + d2[1] * (r + 1), stair(P.trim, OPPOSITE[face], 'top'));
        }
        // corbelled parapet ring: upside-down stairs, wall, merlons (only on an open top)
        var R = sq ? r + 1 : r + 1;
        for (var x = cx - R - 1; x <= cx + R + 1; x++) for (var z = cz - R - 1; z <= cz + R + 1; z++) {
            var ring;
            if (sq) ring = Math.max(Math.abs(x - cx), Math.abs(z - cz)) === R;
            else { var dd = Math.sqrt((x - cx) * (x - cx) + (z - cz) * (z - cz)); ring = dd >= r + 0.5 && dd < r + 1.5; }
            if (!ring) continue;
            var dx = cx - x, dz = cz - z;
            var fc = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? 'east' : 'west') : (dz > 0 ? 'south' : 'north');
            block(x, top - 1, z, stair(P.trim, fc, 'top'));
            block(x, top, z, P.wall);
            if (!roofed) {
                var sector = sq ? (x + z) : Math.floor((Math.atan2(z - cz, x - cx) + Math.PI) / (2 * Math.PI) * Math.max(8, Math.round((r + 1) * 3)));
                block(x, top + 1, z, sector % 2 === 0 ? P.wall : slab(P.trim, 'bottom'));
            }
        }
        if (sq) box(cx - r, top - 1, cz - r, cx + r, top - 1, cz + r, P.floor);
        else place(cyl([cx, top - 1, cz], [cx, top - 1, cz], r + 0.5), P.floor);
        if (roofed) {
            var ro = { type: sq ? 'pyramid' : 'cone', m: P.roof, alt: P.roofAlt, trim: P.trim, overhang: 1 }, k;
            var given = opts.roof || {};
            for (k in given) ro[k] = given[k];
            if (!sq && !ro.height) ro.height = (r + 1.5) * 2.4;
            roof(cx - r - 1, cz - r - 1, cx + r + 1, cz + r + 1, top + 1, ro);
        }
        lamp(cx, top - 2, cz, { hanging: true });
        __mark('tower', [cx - r, y, cz - r, cx + r, top, cz + r], 'centre ' + cx + ',' + cz + ', r=' + r + ', top floor y=' + top, true);
    });
}

/**
 * Curtain wall between points [[x, z], ...] (axis-aligned or diagonal runs), base at y, h high, t thick (3):
 * rough plinth with a stair skirt, weathered masonry, buttresses every 7 on the outside (left of the walking
 * direction unless outside: 'right'), a walkway on top, crenellated parapet with slab caps and a low inner rail.
 */
function curtainWall(points, y, h, opts) {
    opts = opts || {};
    var P = palette(opts.palette || 'castle'), t = opts.t || 3, half = Math.floor(t / 2);
    var m = opts.m || weather(grad('y', [[0, P.base], [0.25, P.wall], [1, P.wall]], { from: y, to: y + h }), 0.14);
    var outSign = opts.outside === 'right' ? -1 : 1, n = 0;
    // walk: true (or omitted) paves the walkway with the palette floor; a block or material paves it with that
    var walkM = opts.walk && opts.walk !== true ? opts.walk : P.floor;
    part('wall', function () {
        for (var i = 0; i + 1 < points.length; i++) {
            var a = points[i], b = points[i + 1];
            var dx = b[0] - a[0], dz = b[1] - a[1], len = Math.max(Math.abs(dx), Math.abs(dz));
            var ux = dx / len, uz = dz / len, nx = -uz * outSign, nz = ux * outSign; // outward normal (left of direction)
            for (var s2 = 0; s2 <= len; s2++, n++) {
                var px = a[0] + ux * s2, pz = a[1] + uz * s2;
                for (var o = -half; o <= half; o++) {
                    var qx = Math.round(px + nx * o), qz = Math.round(pz + nz * o);
                    box(qx, y, qz, qx, y + h - 1, qz, m);
                }
                var ox = Math.round(px + nx * (half + 1)), oz = Math.round(pz + nz * (half + 1));
                var ix = Math.round(px - nx * (half + 1)), iz = Math.round(pz - nz * (half + 1));
                var fOut = Math.abs(nx) > Math.abs(nz) ? (nx > 0 ? 'west' : 'east') : (nz > 0 ? 'north' : 'south');
                block(ox, y + h, oz, P.wall);
                if (n % 2 === 0) block(ox, y + h + 1, oz, P.wall);
                else block(ox, y + h + 1, oz, slab(P.trim, 'bottom'));
                block(ox, y + h - 1, oz, stair(P.trim, fOut, 'top'));
                block(ix, y + h, iz, slab(P.trim, 'bottom'));
                block(ox, y, oz, stair(P.base, fOut, 'bottom'));
                if (n % 7 === 3) {
                    // buttress: stepped pier on the outside
                    for (var by = y; by < y + h - 2; by++) {
                        var reach = by < y + (h - 2) / 2 ? 2 : 1;
                        for (var rr = 1; rr <= reach; rr++) block(Math.round(px + nx * (half + rr)), by, Math.round(pz + nz * (half + rr)), m);
                    }
                    block(Math.round(px + nx * (half + 2)), y + Math.floor((h - 2) / 2), Math.round(pz + nz * (half + 2)), stair(P.base, fOut, 'bottom'));
                }
                for (var o2 = -half; o2 <= half; o2++) block(Math.round(px + nx * o2), y + h - 1, Math.round(pz + nz * o2), walkM);
            }
        }
        var xs = points.map(function (q) { return q[0]; }), zs = points.map(function (q) { return q[1]; });
        __mark('wall', [Math.min.apply(null, xs) - half, y, Math.min.apply(null, zs) - half, Math.max.apply(null, xs) + half, y + h, Math.max.apply(null, zs) + half],
            'thickness ' + t + ', walkway y=' + (y + h) + ', path ' + points.map(function (q) { return q[0] + ',' + q[1]; }).join(' > '), true);
    });
}

// ---------------------------------------------------------------- interior kit

/** Furniture wood set from a wood name (oak, spruce, dark_oak, birch, cherry, ...). */
function _wood(w) {
    // 'spruce', 'spruce_planks', 'stripped_spruce_log' all mean spruce
    w = typeof w === 'string' ? w.replace(/^stripped_/, '').replace(/_(planks|log|wood|stem|hyphae|stairs|slab|fence|door|trapdoor)$/, '') : 'spruce';
    var stem = w === 'crimson' || w === 'warped';
    return { planks: w + '_planks', slab: w + '_slab', stairs: w + '_stairs', fence: w + '_fence', trapdoor: w + '_trapdoor',
        log: 'stripped_' + w + (stem ? '_stem' : '_log'), door: w + '_door', name: w };
}
/** Bed: foot at (x, y, z), head 1 block toward facing (the head usually touches a wall). */
function bed(x, y, z, facing, color) { block(x, y, z, (color || 'red') + '_bed[facing=' + facing + ']'); }
/** Table w x d at floor layer y: top slabs at y with fence legs at the corners (1x1: fence + pressure plate). */
function table(x, y, z, opts) {
    opts = opts || {};
    var m = opts.m || 'spruce_planks', w = opts.w || 1, d = opts.d || 1, leg = fenceOf(m) || 'oak_fence';
    if (w === 1 && d === 1) {
        block(x, y, z, leg);
        block(x, y + 1, z, (opts.plate || m.replace(/_planks$/, '')) + '_pressure_plate');
        return;
    }
    for (var i = 0; i < w; i++) for (var j = 0; j < d; j++) {
        var corner = (i === 0 || i === w - 1) && (j === 0 || j === d - 1);
        block(x + i, y, z + j, corner ? leg : slab(m, 'top'));
    }
}
/** Chair: stairs with the back away from the table, i.e. facing = direction from table to chair. */
function chair(x, y, z, facing, m) { block(x, y, z, stair(m || 'oak_planks', facing, 'bottom')); }
/** Bookshelves along a wall from (x1, y, z1) to (x2, y, z2), h high. */
function shelves(x1, y, z1, x2, z2, h, m) { box(x1, y, z1, x2, y + (h || 2) - 1, z2, m || 'bookshelf'); }
/** Fireplace in a wall: (x, y, z) = hearth cell inside the room, face = toward the room. */
function fireplace(x, y, z, face, opts) {
    opts = opts || {};
    var m = opts.m || 'bricks', dir = DIRS[face], side = DIRS[LEFT_OF[face]];
    for (var a = -1; a <= 1; a++) for (var u = 0; u <= 2; u++) block(x + side[0] * a - dir[0], y + u, z + side[1] * a - dir[1], m);
    block(x, y, z, 'campfire[lit=true,facing=' + face + ']');
    block(x - side[0], y, z - side[1], m);
    block(x + side[0], y, z + side[1], m);
    block(x, y + 1, z, 'air');
    block(x, y + 2, z, m);
    block(x + dir[0], y + 2, z + dir[1], stair(m, OPPOSITE[face], 'top'));
}
/** Light: standing lantern or hanging one (hanging: true, under a ceiling with an optional chain length). */
function lamp(x, y, z, opts) {
    opts = opts || {};
    if (opts.hanging && get(x, y + 1, z) === null) {
        var t = _underCeiling(x, y, z);
        if (t !== null) y = t;
    } else if (!opts.hanging) {
        // stand it on the floor below, not in the air
        for (var k = 0; k < 8 && get(x, y - 1, z) === null; k++) y--;
    }
    if (opts.hanging) {
        var chain = opts.chain || 0;
        for (var i = 0; i < chain; i++) block(x, y - i, z, 'iron_chain');
        block(x, y - chain, z, (opts.m || 'lantern') + '[hanging=true]');
    } else block(x, y, z, opts.m || 'lantern');
}
/** Carpet rug over x1..x2 / z1..z2 at y, with an optional border colour. */
/** Wall banner at (x, y, z) hanging on the wall behind it, looking out to face. */
function banner(x, y, z, face, color) {
    var c = (color || 'red').replace(/_(wall_)?banner$/, '');
    if (!onWall(x, y, z, face || 'south', c + '_wall_banner[facing=' + (face || 'south') + ']')) __warn('banner at ' + x + ',' + y + ',' + z + ': no wall behind it within 3 cells, skipped');
}
function rug(x1, z1, x2, z2, y, color, border) {
    for (var x = x1; x <= x2; x++) for (var z = z1; z <= z2; z++) {
        var edge = border && (x === x1 || x === x2 || z === z1 || z === z2);
        block(x, y, z, (edge ? border : color) + '_carpet');
    }
}
/** Kitchen counter of length n along dir from (x, y, z): barrels, smoker, cauldron, with slab tops for the wall. */
function kitchen(x, y, z, dir, n, face) {
    var d = DIRS[dir], items = ['barrel[facing=up]', 'smoker[facing=' + face + ']', 'cauldron', 'crafting_table', 'barrel[facing=up]', 'furnace[facing=' + face + ']'];
    for (var i = 0; i < n; i++) block(x + d[0] * i, y, z + d[1] * i, items[i % items.length]);
}
/**
 * Chandelier hanging from the ceiling cell (x, top, z) (the highest air cell): a chain down to a lantern, and with
 * r >= 1 a ring of four lanterns on chains around it.
 */
function chandelier(x, top, z, opts) {
    opts = opts || {};
    var drop = opts.drop === undefined ? 1 : opts.drop, m = opts.m || 'lantern', r = opts.r || 0;
    // m is the light (lantern, soul_lantern); any other block (a fence, a wall) is the hanger above a lantern
    var hanger = /lantern$/.test(m) ? 'iron_chain' : m;
    if (hanger !== 'iron_chain') m = 'lantern';
    // hang from the real ceiling, not from the air under it
    if (get(x, top, z) === null && get(x, top + 1, z) === null) {
        var t = _underCeiling(x, top, z);
        if (t !== null) top = t;
    }
    for (var i = 0; i < drop; i++) block(x, top - i, z, hanger);
    block(x, top - drop, z, m + '[hanging=true]');
    if (r >= 1) [[r, 0], [-r, 0], [0, r], [0, -r]].forEach(function (o) {
        // each outer light hangs from its own ceiling: find it, never overwrite a roof or beam
        var cx = x + o[0], cz = z + o[1], ceil = top;
        while (ceil > top - 3 && get(cx, ceil, cz) !== null) ceil--;
        if (get(cx, ceil, cz) !== null || get(cx, ceil + 1, cz) === null) return;
        var d = Math.max(0, Math.min(drop - 1, ceil - (top - drop)));
        for (var k = 0; k < d; k++) block(cx, ceil - k, cz, 'iron_chain');
        block(cx, ceil - d, cz, m + '[hanging=true]');
    });
}

// Wall furniture: each piece fills n cells along a wall run, starting at ring-0 cell (x, z) on floor layer y and
// going along direction a; f points into the room. Pieces only place blocks in their cells, the cells in front of
// them (ring 1, listed in 'front') and the air above.
function _cellAt(x, z, a, f, i, ring) {
    var da = DIRS[a], df = DIRS[f];
    return [x + da[0] * i + df[0] * ring, z + da[1] * i + df[1] * ring];
}
var _PIECES = {
    bed: { n: 2, depth: 2, low: true, put: function (c, o) {
        var head = c(0, 0), foot = c(0, 1), ns = c(1, 0);
        bed(foot[0], o.y + 1, foot[1], o.out, o.bed || 'red');
        block(ns[0], o.y + 1, ns[1], 'barrel[facing=up]');
        block(ns[0], o.y + 2, ns[1], o.candle);
    } },
    doubleBed: { n: 3, depth: 2, low: true, put: function (c, o) {
        var a = c(0, 1), b = c(1, 1), ns = c(2, 0);
        bed(a[0], o.y + 1, a[1], o.out, o.bed || 'red');
        bed(b[0], o.y + 1, b[1], o.out, o.bed || 'red');
        block(ns[0], o.y + 1, ns[1], o.W.log + '[axis=y]');
        block(ns[0], o.y + 2, ns[1], 'potted_' + pick(['red_tulip', 'blue_orchid', 'fern', 'azure_bluet']));
    } },
    wardrobe: { n: 2, depth: 2, tall: 2, put: function (c, o) {
        for (var i = 0; i < 2; i++) {
            var p = c(i, 0), q = c(i, 1);
            block(p[0], o.y + 1, p[1], o.W.planks);
            block(p[0], o.y + 2, p[1], o.W.planks);
            if (o.H >= 4) block(p[0], o.y + 3, p[1], slab(o.W.planks, 'bottom'));
            block(q[0], o.y + 1, q[1], o.W.trapdoor + '[facing=' + o.f + ',open=true,half=bottom]');
            block(q[0], o.y + 2, q[1], o.W.trapdoor + '[facing=' + o.f + ',open=true,half=top]');
        }
    } },
    bookcase: { n: 3, grow: 6, depth: 1, tall: 3, put: function (c, o, n) {
        var h = Math.min(3, o.H - 1);
        for (var i = 0; i < n; i++) {
            var p = c(i, 0), end = i === 0 || i === n - 1;
            for (var u = 1; u <= h; u++) {
                block(p[0], o.y + u, p[1], end && n >= 3 ? o.W.log + '[axis=y]'
                    : (u === 2 && i % 3 === 1 ? 'chiseled_bookshelf[facing=' + o.f + ']' : 'bookshelf'));
            }
            if (h + 1 < o.H) block(p[0], o.y + h + 1, p[1], end ? o.candle : i % 2 ? 'potted_fern' : slab(o.W.planks, 'bottom'));
        }
    } },
    counter: { n: 3, grow: 6, depth: 1, put: function (c, o, n) {
        var items = ['barrel[facing=' + o.f + ']', 'smoker[facing=' + o.f + ',lit=true]', 'water_cauldron[level=3]',
            o.W.planks, 'furnace[facing=' + o.f + ']', 'barrel[facing=' + o.f + ']'];
        var tops = ['flower_pot', null, null, 'decorated_pot', null, o.candle];
        for (var i = 0; i < n; i++) {
            var p = c(i, 0);
            block(p[0], o.y + 1, p[1], items[i % items.length]);
            if (tops[i % tops.length]) block(p[0], o.y + 2, p[1], tops[i % tops.length]);
            if (o.H >= 4 && i % 2 === 0) block(p[0], o.y + 3, p[1], o.W.trapdoor + '[facing=' + o.f + ',half=top]');
        }
    } },
    fireplace: { n: 3, depth: 1, tall: 9, put: function (c, o) {
        var m = o.stone;
        for (var i = 0; i < 3; i++) {
            var p = c(i, 0), q = c(i, 1);
            for (var u = 1; u <= o.H; u++) block(p[0], o.y + u, p[1], i === 1 && u === 1 ? 'campfire[lit=true,facing=' + o.f + ']'
                : i === 1 && u === 2 ? 'air' : m);
            if (o.H >= 4) block(q[0], o.y + 3, q[1], _stairOr(m, o.out, 'top'));
            block(q[0], o.y, q[1], i === 1 ? 'smooth_stone' : m);
        }
    } },
    desk: { n: 2, depth: 2, low: true, put: function (c, o) {
        var a = c(0, 0), b = c(1, 0), ch = c(0, 1);
        block(a[0], o.y + 1, a[1], slab(o.W.planks, 'top'));
        block(a[0], o.y + 2, a[1], o.candle);
        block(b[0], o.y + 1, b[1], 'chiseled_bookshelf[facing=' + o.f + ']');
        block(b[0], o.y + 2, b[1], pick(['lectern[facing=' + o.f + ']', 'potted_red_tulip', 'decorated_pot']));
        chair(ch[0], o.y + 1, ch[1], o.f, o.W.planks);
    } },
    chest: { n: 1, depth: 1, low: true, put: function (c, o) {
        var p = c(0, 0);
        block(p[0], o.y + 1, p[1], 'chest[facing=' + o.f + ']');
    } },
    barrels: { n: 2, depth: 1, tall: 2, put: function (c, o) {
        var a = c(0, 0), b = c(1, 0);
        block(a[0], o.y + 1, a[1], 'barrel[facing=up]');
        block(a[0], o.y + 2, a[1], 'barrel[facing=' + o.f + ']');
        block(b[0], o.y + 1, b[1], pick(['barrel[facing=up]', 'hay_block', 'composter']));
        block(b[0], o.y + 2, b[1], 'flower_pot');
    } },
    plant: { n: 1, depth: 1, low: true, put: function (c, o) {
        var p = c(0, 0);
        block(p[0], o.y + 1, p[1], pick(['potted_flowering_azalea_bush', 'potted_azalea_bush', 'potted_fern', 'potted_spruce_sapling']));
    } },
    armchair: { n: 3, depth: 1, low: true, put: function (c, o) {
        var l = c(0, 0), s = c(1, 0), r = c(2, 0);
        block(s[0], o.y + 1, s[1], stair(o.W.planks, o.out, 'bottom'));
        // an open trapdoor's panel sits on the side opposite its facing: both arms lean against the seat
        block(l[0], o.y + 1, l[1], o.W.trapdoor + '[facing=' + OPPOSITE[o.a] + ',open=true]');
        block(r[0], o.y + 1, r[1], o.W.trapdoor + '[facing=' + o.a + ',open=true]');
    } },
    sideboard: { n: 3, depth: 1, put: function (c, o) {
        for (var i = 0; i < 3; i++) {
            var p = c(i, 0);
            block(p[0], o.y + 1, p[1], i === 1 ? 'barrel[facing=' + o.f + ']' : stair(o.W.planks, o.out, 'top'));
            block(p[0], o.y + 2, p[1], i === 1 ? 'decorated_pot' : i === 0 ? o.candle : 'potted_red_tulip');
        }
    } },
    workbench: { n: 4, depth: 1, put: function (c, o) {
        var items = ['smithing_table', 'anvil[facing=' + LEFT_OF[o.f] + ']', 'grindstone[face=floor,facing=' + o.f + ']',
            'blast_furnace[facing=' + o.f + ']', 'stonecutter[facing=' + o.f + ']', 'fletching_table'];
        for (var i = 0; i < 4; i++) {
            var p = c(i, 0);
            block(p[0], o.y + 1, p[1], items[(i + (o.k || 0)) % items.length]);
        }
    } },
    altar: { n: 3, depth: 1, put: function (c, o) {
        for (var i = 0; i < 3; i++) {
            var p = c(i, 0);
            block(p[0], o.y + 1, p[1], i === 1 ? 'chiseled_quartz_block' : 'quartz_stairs[facing=' + o.out + ',half=top]');
            block(p[0], o.y + 2, p[1], i === 1 ? 'lectern[facing=' + o.f + ']' : 'white_candle[candles=3,lit=true]');
        }
    } },
    throne: { n: 3, depth: 2, put: function (c, o) {
        var l = c(0, 0), s = c(1, 0), r = c(2, 0);
        block(s[0], o.y + 1, s[1], o.W.stairs + '[facing=' + o.out + ']');
        block(s[0], o.y + 2, s[1], 'gold_block');
        block(l[0], o.y + 1, l[1], 'gold_block');
        block(r[0], o.y + 1, r[1], 'gold_block');
        block(l[0], o.y + 2, l[1], 'white_candle[candles=2,lit=true]');
        block(r[0], o.y + 2, r[1], 'white_candle[candles=2,lit=true]');
    } },
    pillar: { n: 1, depth: 1, tall: 9, put: function (c, o) {
        var p = c(0, 0), m = o.stone === 'bricks' ? 'stone_bricks' : o.stone;
        block(p[0], o.y + 1, p[1], _stairOr(m, o.out, 'bottom'));
        for (var u = 2; u <= o.H; u++) block(p[0], o.y + u, p[1], u === o.H ? _stairOr(m, o.out, 'top') : m);
        var q = c(0, 1);
        if (o.H >= 4) block(q[0], o.y + 3, q[1], 'wall_torch[facing=' + o.f + ']');
        if (o.H >= 4) block(p[0], o.y + 3, p[1], m);
    } },
    banner: { n: 1, depth: 1, low: true, put: function (c, o) {
        var p = c(0, 0);
        if (o.H >= 4) block(p[0], o.y + 3, p[1], (o.banner || 'red') + '_wall_banner[facing=' + o.f + ']');
        else block(p[0], o.y + 1, p[1], 'potted_fern');
    } },
    kegs: { n: 3, grow: 8, depth: 1, tall: 2, put: function (c, o, n) {
        for (var i = 0; i < n; i++) {
            var p = c(i, 0);
            block(p[0], o.y + 1, p[1], 'barrel[facing=' + o.f + ']');
            if (o.H >= 3) block(p[0], o.y + 2, p[1], i % 3 === 1 ? 'barrel[facing=' + o.f + ']' : 'barrel[facing=up]');
        }
    } }
};

/** Wall pieces per room type, in placement order (the first fit along the walls, far wall first). */
var _ROOMS = {
    bedroom: { wall: ['bed', 'wardrobe', 'chest', 'desk', 'bookcase', 'plant'], center: 'rug', bed2: 'doubleBed' },
    kitchen: { wall: ['counter', 'barrels', 'chest', 'plant'], center: 'table' },
    dining: { wall: ['sideboard', 'fireplace', 'barrels', 'plant'], center: 'longtable' },
    living: { wall: ['fireplace', 'bookcase', 'armchair', 'chest', 'plant', 'barrels'], center: 'table' },
    hall: { wall: ['fireplace', 'banner', 'kegs', 'banner', 'counter', 'barrels', 'sideboard', 'banner'], center: 'tables' },
    tavern: { wall: ['kegs', 'fireplace', 'counter', 'barrels', 'sideboard'], center: 'tables' },
    library: { wall: ['bookcase', 'bookcase', 'bookcase', 'desk', 'bookcase', 'plant'], center: 'table' },
    study: { wall: ['desk', 'bookcase', 'bookcase', 'chest', 'plant'], center: 'rug' },
    workshop: { wall: ['workbench', 'barrels', 'chest', 'counter'], center: 'table' },
    smithy: { wall: ['workbench', 'fireplace', 'barrels', 'chest'], center: 'none' },
    storage: { wall: ['barrels', 'barrels', 'chest', 'barrels', 'chest', 'barrels'], center: 'crates' },
    shop: { wall: ['bookcase', 'barrels', 'chest', 'barrels'], center: 'stall' },
    chapel: { wall: ['altar', 'pillar', 'pillar', 'banner', 'banner', 'pillar', 'pillar', 'plant'], center: 'pews', banner: 'white' },
    throne: { wall: ['throne', 'banner', 'banner', 'pillar', 'pillar', 'pillar', 'pillar', 'banner', 'banner'], center: 'runner' }
};

/**
 * Furnishes one room the way builders do, from its inner floor area x1..x2 / z1..z2 (air cells inside the walls)
 * and its floor layer y (furniture stands at y + 1). type: bedroom | kitchen | dining | living | hall | tavern |
 * library | study | workshop | smithy | storage | shop | chapel | throne. It reads the walls first: doorways,
 * stairs and ladders stay clear, tall pieces avoid windows, and pieces only go on a solid floor. Wall pieces go
 * along the walls (far wall from the door first), a centrepiece (table set, rug, pews, stalls...) in the middle,
 * and lights hang from the ceiling. opts: wood (furniture wood, default spruce), stone (fireplace), bed, rug
 * (carpet colour), border, banner (colour), candle, extra (more wall piece names), skip (piece names), lights:false,
 * replace:true (first remove what an earlier furnish() placed in this area, e.g. the rooms volume() furnished).
 * Returns {pieces: [...placed names]}.
 */
function furnish(x1, z1, x2, z2, y, type, opts) {
    if (x1 && typeof x1 === 'object' && !Array.isArray(x1)) {
        // furnish(room, type, opts): a room from rooms()
        return furnish(x1.x1, x1.z1, x1.x2, x1.z2, x1.y - 1, z1, x2);
    }
    opts = opts || {};
    if (opts.replace) {
        // take back only what furnish() placed and what is still there: stairs, walls or anything another part
        // built over those cells since then stay
        for (var k in _FURNISHED) {
            var c = _FURNISHED[k];
            if (c[3] === y && c[0] >= Math.min(x1, x2) && c[0] <= Math.max(x1, x2) && c[2] >= Math.min(z1, z2) && c[2] <= Math.max(z1, z2)) {
                if (get(c[0], c[1], c[2]) === c[4]) block(c[0], c[1], c[2], 'air');
                delete _FURNISHED[k];
            }
        }
    }
    // furniture goes only into air (or over furniture this room placed), never into walls, stairs or floors, and
    // every block is recorded so a later furnish(..., {replace: true}) can take the room back
    var outer = block, skipped = 0;
    block = function (bx, by, bz, m) {
        var key = bx + ',' + by + ',' + bz, cur = get(bx, by, bz), own = _FURNISHED[key];
        // the floor layer itself may be re-tiled (a hearth stone, a rug's border inset)
        var floorTile = by <= y && isSolid(bx, by, bz) && m !== 'air';
        if (cur !== null && !(own && own[4] === cur) && !floorTile) {
            if (by > y) skipped++;
            return;
        }
        outer(bx, by, bz, m);
        var now = get(bx, by, bz);
        if (now === null || by <= y) delete _FURNISHED[key];
        else _FURNISHED[key] = [bx, by, bz, y, now];
    };
    try {
        return _furnish(x1, z1, x2, z2, y, type, opts);
    } finally {
        block = outer;
        if (skipped) __warn('furnish ' + type + ' at y=' + y + ': ' + skipped + ' furniture cells were already occupied (walls, stairs, other parts) and kept');
    }
}
var _FURNISHED = {};

function _furnish(x1, z1, x2, z2, y, type, opts) {
    var R = _ROOMS[type] || _ROOMS.living;
    var xa = Math.min(x1, x2), xb = Math.max(x1, x2), za = Math.min(z1, z2), zb = Math.max(z1, z2);
    var W = _wood(opts.wood), stone = opts.stone || 'bricks', candle = opts.candle || 'candle[candles=2,lit=true]';
    var cx = Math.round((xa + xb) / 2), cz = Math.round((za + zb) / 2);
    var H = 0;
    [[0, 0], [1, 0], [-1, 0], [0, 1], [0, -1]].forEach(function (o) {
        var h = 0;
        while (h < 12 && get(cx + o[0], y + 1 + h, cz + o[1]) === null) h++;
        H = Math.max(H, h);
    });
    if (H < 2) return { pieces: [] };
    var roofed = H < 12;
    if (!roofed) H = 4;
    var occ = {}, clear = {};
    function key(x, z) { return x + ',' + z; }
    function inside(x, z) { return x >= xa && x <= xb && z >= za && z <= zb; }
    function free(x, z) {
        return inside(x, z) && !occ[key(x, z)] && !clear[key(x, z)] && get(x, y + 1, z) === null && get(x, y + 2, z) === null
            && get(x, y, z) !== null;
    }
    function isPass(b) { return b !== null && /(_door|ladder|_stairs|scaffolding)/.test(b) && !/trapdoor/.test(b); }
    function isGlass(b) { return b !== null && /(glass|_bars)/.test(b); }
    // cells already used (ladders, stairs, pillars) and the cells next to passages stay clear
    for (var x = xa; x <= xb; x++) for (var z = za; z <= zb; z++) {
        var b1 = get(x, y + 1, z), b2 = get(x, y + 2, z);
        if (b1 !== null || b2 !== null) occ[key(x, z)] = true;
        if (isPass(b1) || isPass(b2) || isPass(get(x, y, z))) {
            for (var dx = -1; dx <= 1; dx++) for (var dz = -1; dz <= 1; dz++) clear[key(x + dx, z + dz)] = true;
        }
    }
    // the four wall runs: ring-0 cells, their wall, the direction along them and into the room
    var runs = [
        { side: 'north', f: 'south', a: 'east', cells: [] }, { side: 'south', f: 'north', a: 'east', cells: [] },
        { side: 'west', f: 'east', a: 'south', cells: [] }, { side: 'east', f: 'west', a: 'south', cells: [] }];
    var doors = [];
    runs.forEach(function (r) {
        var d = DIRS[r.side];
        var n = r.a === 'east' ? xb - xa + 1 : zb - za + 1;
        for (var i = 0; i < n; i++) {
            var px = r.a === 'east' ? xa + i : (r.side === 'west' ? xa : xb);
            var pz = r.a === 'east' ? (r.side === 'north' ? za : zb) : za + i;
            var wx = px + d[0], wz = pz + d[1];
            var w1 = get(wx, y + 1, wz), w2 = get(wx, y + 2, wz), w3 = get(wx, y + 3, wz);
            var opening = isPass(w1) || isPass(w2) || (w1 === null && w2 === null);
            if (opening) {
                doors.push({ x: px, z: pz, side: r.side });
                var df = DIRS[r.f], da = DIRS[r.a];
                for (var k = -1; k <= 1; k++) for (var ring = 0; ring <= 1; ring++) {
                    clear[key(px + da[0] * k + df[0] * ring, pz + da[1] * k + df[1] * ring)] = true;
                }
            }
            r.cells.push({ x: px, z: pz, window: isGlass(w1) || isGlass(w2) || isGlass(w3) });
        }
    });
    // far wall first: opposite the first door, then the two side walls, then the door wall
    var doorSide = doors.length ? doors[0].side : 'south';
    runs.sort(function (p, q) {
        function rank(r) { return r.side === OPPOSITE[doorSide] ? 0 : r.side === doorSide ? 2 : 1; }
        return rank(p) - rank(q) || q.cells.length - p.cells.length;
    });
    var placed = [];
    function tryPiece(name) {
        var P = _PIECES[name];
        if (!P) return false;
        for (var ri = 0; ri < runs.length; ri++) {
            var r = runs[ri], cells = r.cells;
            // prefer the run's middle for single big pieces (fireplace, altar, throne), else the first fit
            var order = [];
            for (var s = 0; s + P.n <= cells.length; s++) order.push(s);
            if (name === 'fireplace' || name === 'altar' || name === 'throne' || name === 'doubleBed') {
                var mid = (cells.length - P.n) / 2;
                order.sort(function (p, q) { return Math.abs(p - mid) - Math.abs(q - mid); });
            }
            for (var oi = 0; oi < order.length; oi++) {
                var st = order[oi], n = P.n;
                if (!fits(r, st, n, P)) continue;
                if (P.grow) while (n < P.grow && st + n < cells.length && fits(r, st, n + 1, P)) n++;
                var c0 = cells[st];
                var cf = function (i, ring) { return _cellAt(c0.x, c0.z, r.a, r.f, i, ring); };
                P.put(cf, { y: y, f: r.f, a: r.a, out: r.side, H: H, W: W, stone: stone, candle: candle, bed: opts.bed, k: placed.length,
                    banner: opts.banner || R.banner }, n);
                for (var i = 0; i < n; i++) for (var ring = 0; ring < Math.max(1, P.depth); ring++) {
                    var q = _cellAt(c0.x, c0.z, r.a, r.f, i, ring);
                    occ[key(q[0], q[1])] = true;
                }
                // keep a walking gap after each piece
                var g = _cellAt(c0.x, c0.z, r.a, r.f, n, 0);
                if (!P.low) clear[key(g[0], g[1])] = true;
                placed.push(name);
                return true;
            }
        }
        return false;
    }
    function fits(r, st, n, P) {
        if (st + n > r.cells.length) return false;
        for (var i = 0; i < n; i++) {
            var c = r.cells[st + i];
            if (!free(c.x, c.z) || (P.tall && c.window)) return false;
            for (var ring = 1; ring < P.depth; ring++) {
                var q = _cellAt(c.x, c.z, r.a, r.f, 0, ring);
                if (!free(q[0], q[1])) return false;
            }
        }
        return true;
    }
    var area = (xb - xa + 1) * (zb - za + 1);
    var list = R.wall.slice();
    if (type === 'bedroom' && area >= 30) list[0] = R.bed2;
    if (type === 'bedroom' && area >= 45) list.splice(1, 0, 'bed');
    if (opts.extra) list = list.concat(opts.extra);
    if (opts.skip) list = list.filter(function (n) { return opts.skip.indexOf(n) < 0; });
    // small rooms get fewer pieces so a path stays
    var budget = Math.max(2, Math.floor(area / 7));
    for (var li = 0; li < list.length && placed.length < budget; li++) tryPiece(list[li]);
    _centre(R.center, xa, za, xb, zb, y, H, W, opts, free, occ, key, doors, placed, type);
    if (opts.lights !== false && !roofed) {
        [[xa, za], [xb, zb], [xa, zb], [xb, za]].forEach(function (p) { if (free(p[0], p[1])) block(p[0], y + 1, p[1], 'lantern'); });
    } else if (opts.lights !== false) {
        var top = y + H;
        // lights hang 3-4 above the floor even under a high roof, or they light nothing
        var drop = Math.max(0, H - (H >= 6 ? 4 : 3));
        if (get(cx, top, cz) === null) chandelier(cx, top, cz, { drop: drop, r: area >= 36 && H >= 4 ? 1 : 0 });
        if (area >= 60) [[xa + 2, za + 2], [xb - 2, za + 2], [xa + 2, zb - 2], [xb - 2, zb - 2]].forEach(function (p) {
            var t = top;
            while (t > y + 2 && get(p[0], t, p[1]) !== null) t--;
            if (t > y + 2 && get(p[0], t + 1, p[1]) !== null && get(p[0], t - 1, p[1]) === null) lamp(p[0], t, p[1], { hanging: true, chain: Math.min(drop, t - y - 3) });
        });
        if (H <= 2) placed.push('low-ceiling');
    }
    return { pieces: placed };
}

/** Centrepieces of a room inside the area two cells from the walls. */
function _centre(kind, xa, za, xb, zb, y, H, W, opts, free, occ, key, doors, placed, type) {
    var ia = xa + 2, ib = xb - 2, ja = za + 2, jb = zb - 2;
    if (ia > ib || ja > jb || kind === 'none') return;
    var colour = opts.rug || { bedroom: 'light_gray', study: 'green', library: 'green', kitchen: 'brown', living: 'red' }[type] || 'red';
    var border = opts.border || (colour === 'brown' ? 'black' : 'brown');
    function rugOver(x1, z1, x2, z2) {
        for (var x = x1; x <= x2; x++) for (var z = z1; z <= z2; z++) {
            if (free(x, z)) block(x, y + 1, z, ((x === x1 || x === x2 || z === z1 || z === z2) ? border : colour) + '_carpet');
        }
    }
    function chairsAround(tx1, tz1, tx2, tz2) {
        for (var x = tx1; x <= tx2; x++) {
            if (free(x, tz1 - 1)) chair(x, y + 1, tz1 - 1, 'north', W.planks);
            if (free(x, tz2 + 1)) chair(x, y + 1, tz2 + 1, 'south', W.planks);
        }
        if (tx2 - tx1 < 3) for (var z = tz1; z <= tz2; z++) {
            if (free(tx1 - 1, z)) chair(tx1 - 1, y + 1, z, 'west', W.planks);
            if (free(tx2 + 1, z)) chair(tx2 + 1, y + 1, z, 'east', W.planks);
        }
    }
    function tableAt(tx1, tz1, tx2, tz2, withChairs, noRug) {
        for (var x = tx1; x <= tx2; x++) for (var z = tz1; z <= tz2; z++) if (!free(x, z)) return false;
        table(tx1, y + 1, tz1, { w: tx2 - tx1 + 1, d: tz2 - tz1 + 1, m: W.planks });
        for (var x2 = tx1; x2 <= tx2; x2++) for (var z2 = tz1; z2 <= tz2; z2++) {
            occ[key(x2, z2)] = true;
            var corner = (x2 === tx1 || x2 === tx2) && (z2 === tz1 || z2 === tz2);
            if (!corner || (tx2 - tx1 + 1) * (tz2 - tz1 + 1) <= 2) {
                if ((x2 + z2) % 3 === 0) block(x2, y + 2, z2, 'candle[candles=3,lit=true]');
                else if ((x2 + z2) % 3 === 1) block(x2, y + 2, z2, pick(['flower_pot', 'decorated_pot', 'potted_red_tulip']));
            }
        }
        if (withChairs) chairsAround(tx1, tz1, tx2, tz2);
        if (!noRug) rugOver(tx1 - 1, tz1 - 1, tx2 + 1, tz2 + 1);
        return true;
    }
    var w = ib - ia + 1, d = jb - ja + 1, mx = Math.floor((ia + ib) / 2), mz = Math.floor((ja + jb) / 2);
    if (kind === 'rug') {
        // a rug of about half the free floor, never wall to wall
        var rw = Math.max(1, Math.min(5, Math.ceil(w * 0.6))), rd = Math.max(1, Math.min(4, Math.ceil(d * 0.6)));
        var rx = mx - Math.floor((rw - 1) / 2), rz = mz - Math.floor((rd - 1) / 2);
        rugOver(rx, rz, rx + rw - 1, rz + rd - 1);
        placed.push('rug');
    } else if (kind === 'table') {
        var tw = w >= 4 ? 2 : 1, td = d >= 4 ? 2 : 1;
        if (tableAt(mx - Math.floor((tw - 1) / 2), mz - Math.floor((td - 1) / 2), mx - Math.floor((tw - 1) / 2) + tw - 1,
            mz - Math.floor((td - 1) / 2) + td - 1, true)) placed.push('table');
    } else if (kind === 'longtable') {
        var alongX = w >= d, len = Math.max(2, (alongX ? w : d) - 2);
        var t1 = alongX ? [ia + 1, mz, ia + len, mz] : [mx, ja + 1, mx, ja + len];
        if (tableAt(t1[0], t1[1], Math.min(t1[2], ib), Math.min(t1[3], jb), true)) placed.push('longtable');
    } else if (kind === 'tables' || kind === 'crates' || kind === 'stall') {
        for (var x = ia; x + 1 <= ib; x += 4) for (var z = ja; z + 1 <= jb; z += 4) {
            if (kind === 'tables') { if (tableAt(x + 1, z + 1, x + 1, z + 1, true, true)) placed.push('table'); }
            else if (kind === 'crates') {
                if (free(x + 1, z + 1) && free(x + 2, z + 1)) {
                    block(x + 1, y + 1, z + 1, 'barrel[facing=up]'); block(x + 2, y + 1, z + 1, 'hay_block');
                    block(x + 1, y + 2, z + 1, 'barrel[facing=north]');
                    occ[key(x + 1, z + 1)] = occ[key(x + 2, z + 1)] = true;
                    placed.push('crates');
                }
            } else if (free(x + 1, z + 1) && free(x + 2, z + 1)) {
                block(x + 1, y + 1, z + 1, W.planks); block(x + 2, y + 1, z + 1, 'barrel[facing=up]');
                block(x + 1, y + 2, z + 1, pick(['melon', 'pumpkin', 'hay_block', 'decorated_pot']));
                block(x + 2, y + 2, z + 1, 'flower_pot');
                occ[key(x + 1, z + 1)] = occ[key(x + 2, z + 1)] = true;
                placed.push('stall');
            }
        }
    } else if (kind === 'pews' || kind === 'runner') {
        // rows face the far wall from the door, with a carpet aisle from the door
        var ds = doors.length ? doors[0].side : 'south', look = OPPOSITE[ds];
        var alongZ = ds === 'south' || ds === 'north';
        var ax = doors.length ? (alongZ ? doors[0].x : doors[0].z) : (alongZ ? mx : mz);
        for (var i = alongZ ? za : xa; i <= (alongZ ? zb : xb); i++) {
            var px = alongZ ? ax : i, pz = alongZ ? i : ax;
            if (free(px, pz)) block(px, y + 1, pz, 'red_carpet');
        }
        if (kind === 'pews') {
            var r0 = (alongZ ? za : xa) + 1, r1 = (alongZ ? zb : xb) - 1;
            // the altar stands at the far wall: leave two rows in front of it
            if (look === 'north' || look === 'west') r0 += 2; else r1 -= 2;
            for (var row = r0; row <= r1; row += 2) {
                for (var s = (alongZ ? xa : za) + 1; s <= (alongZ ? xb : zb) - 1; s++) {
                    if (Math.abs(s - ax) <= 1) continue;
                    var qx = alongZ ? s : row, qz = alongZ ? row : s;
                    if (free(qx, qz)) block(qx, y + 1, qz, stair(W.planks, OPPOSITE[look], 'bottom'));
                }
            }
        }
        placed.push(kind);
    }
}

// ---------------------------------------------------------------- nature and scene kit

/** Leaf mixes per species: two or three leaf types in patches read as a real canopy, not a green blob. */
var TREES = {
    oak: { log: 'oak_log', wood: 'oak_wood', leaves: mix({ oak_leaves: 6, azalea_leaves: 1, dark_oak_leaves: 1 }, { scale: 2 }), h: 9, crown: 4.5, branches: 5 },
    birch: { log: 'birch_log', wood: 'birch_wood', leaves: mix({ birch_leaves: 6, oak_leaves: 1 }, { scale: 2 }), h: 11, crown: 3.2, branches: 4, slim: true },
    spruce: { log: 'spruce_log', wood: 'spruce_wood', leaves: 'spruce_leaves', h: 14, crown: 4.5, conifer: true },
    pine: { log: 'spruce_log', wood: 'spruce_wood', leaves: mix({ spruce_leaves: 5, dark_oak_leaves: 1 }), h: 16, crown: 3.5, conifer: true, bare: 0.45 },
    dark_oak: { log: 'dark_oak_log', wood: 'dark_oak_wood', leaves: mix({ dark_oak_leaves: 5, oak_leaves: 2 }, { scale: 2 }), h: 8, crown: 5.5, branches: 6, thick: true },
    cherry: { log: 'cherry_log', wood: 'cherry_wood', leaves: 'cherry_leaves', h: 7, crown: 5, branches: 5, wide: true, petals: true },
    jungle: { log: 'jungle_log', wood: 'jungle_wood', leaves: mix({ jungle_leaves: 5, oak_leaves: 1 }), h: 16, crown: 5, branches: 5, vines: true },
    acacia: { log: 'acacia_log', wood: 'acacia_wood', leaves: 'acacia_leaves', h: 8, crown: 5, branches: 4, flat: true },
    willow: { log: 'mangrove_log', wood: 'mangrove_wood', leaves: mix({ mangrove_leaves: 4, oak_leaves: 2 }), h: 8, crown: 5, branches: 6, droop: true },
    azalea: { log: 'oak_log', wood: 'oak_wood', leaves: mix({ azalea_leaves: 3, flowering_azalea_leaves: 2 }), h: 5, crown: 3.2, branches: 3 },
    pale_oak: { log: 'pale_oak_log', wood: 'pale_oak_wood', leaves: 'pale_oak_leaves', h: 9, crown: 5, branches: 6, thick: true },
    palm: { log: 'jungle_log', wood: 'jungle_wood', leaves: 'jungle_leaves', h: 11, crown: 5, palm: true },
    dead: { log: 'spruce_log', wood: 'spruce_wood', leaves: null, h: 7, crown: 4, branches: 5 }
};

/**
 * Custom tree the way builders make them: tapered trunk with root flare, real branches of wood blocks, clustered
 * canopies of mixed leaves with gaps and hanging leaves; conifers in alternating layered skirts; palms with fronds.
 * opts: species (see TREES), h (trunk height, 5..40; the crown scales with it), crown (radius), lean, leaves (block or
 * material), log, seedOffset. Returns the canopy bounds.
 */
function tree(x, y, z, opts) {
    opts = opts || {};
    var base = TREES[opts.species || 'oak'] || TREES.oak, sp = {}, k;
    for (k in base) sp[k] = base[k];
    if (opts.leaves) sp.leaves = opts.leaves;
    if (opts.log) { sp.log = opts.log; sp.wood = opts.log; }
    var h = Math.max(3, Math.round(opts.h || sp.h)), cr = opts.crown || sp.crown * h / sp.h;
    var out;
    part('tree', function () {
        if (sp.conifer) out = _conifer(x, y, z, h, cr, sp, opts);
        else if (sp.palm) out = _palm(x, y, z, h, cr, sp, opts);
        else out = _broadleaf(x, y, z, h, cr, sp, opts);
    });
    return out;
}

function _roots(x, y, z, r, sp) {
    var n = r > 1.2 ? 5 : 4, a0 = rand(0, 6.28);
    for (var i = 0; i < n; i++) {
        var a = a0 + 6.28 * i / n + rand(-0.3, 0.3), reach = r * rand(2.2, 3.4) + 1;
        place(tube([[x, y + r * 1.4 + 0.5, z], [x + Math.cos(a) * reach * 0.55, y + 0.6, z + Math.sin(a) * reach * 0.55],
            [x + Math.cos(a) * reach, y - 0.2, z + Math.sin(a) * reach]], [r * 0.7, r * 0.45, 0.35]).and(cuboid([x - 20, y, z - 20], [x + 20, y + 20, z + 20])),
            sp.wood, { onlyAir: true });
    }
}

function _broadleaf(x, y, z, h, cr, sp, opts) {
    var lean = opts.lean === undefined ? rand(-0.18, 0.18) * h : opts.lean, la = rand(0, 6.28);
    var lx = Math.cos(la) * lean, lz = Math.sin(la) * lean;
    var r = opts.trunk || Math.max(0.55, h / (sp.slim ? 16 : sp.thick ? 7.5 : 10.5));
    var top = [x + lx, y + h, z + lz];
    function trunkAt(t) { return [x + lx * t * t, y + h * t, z + lz * t * t]; }
    place(tube([trunkAt(0), trunkAt(0.35), trunkAt(0.7), top], function (t) { return r * (1.15 - 0.5 * t) + (t < 0.12 ? r * (0.12 - t) * 4 : 0); }), sp.log);
    if (r >= 0.9) _roots(x, y, z, r, sp);
    var tips = [], nb = opts.branches || sp.branches;
    for (var i = 0; i < nb; i++) {
        var a = la + 6.28 * i / nb + rand(-0.4, 0.4), t0 = sp.flat ? rand(0.75, 0.9) : 0.45 + 0.45 * i / nb;
        var start = trunkAt(t0), len = cr * rand(0.8, 1.15) * (sp.wide ? 1.25 : 1);
        var rise = sp.flat ? rand(0.1, 0.3) : sp.droop ? rand(0.2, 0.45) : rand(0.35, 0.75);
        var end = [start[0] + Math.cos(a) * len, start[1] + len * rise, start[2] + Math.sin(a) * len];
        var mid = [lerp(start[0], end[0], 0.5), lerp(start[1], end[1], 0.4) + 0.4, lerp(start[2], end[2], 0.5)];
        place(tube([start, mid, end], [r * 0.65, r * 0.4, 0.4]), sp.wood);
        tips.push(end);
        if (len > 3.5) {
            var a2 = a + rand(-0.9, 0.9), l2 = len * rand(0.35, 0.55);
            var sub = [mid[0] + Math.cos(a2) * l2, mid[1] + l2 * rand(0.4, 0.9), mid[2] + Math.sin(a2) * l2];
            place(tube([mid, sub], [0.45, 0.35]), sp.wood);
            tips.push(sub);
        }
    }
    tips.push([top[0], top[1] + cr * 0.25, top[2]]);
    if (!sp.leaves) return null;
    var blobs = [];
    for (var j = 0; j < tips.length; j++) {
        var t = tips[j], s = cr * rand(0.45, 0.62) * (sp.wide ? 1.1 : 1);
        blobs.push(ellipsoid([t[0], t[1] + (sp.flat ? 0.3 : 0.6), t[2]], [s, s * (sp.flat ? 0.38 : 0.7), s]));
    }
    var canopy = blend(1.6, blobs).warp(Math.min(1.8, cr * 0.22 + 0.5), 2.4);
    place(canopy, sp.leaves, { onlyAir: true });
    var b = canopy.bounds();
    _hang(b, sp, sp.droop ? [2, 5] : sp.vines ? [2, 6] : [1, 2], sp.droop ? 24 : 18);
    if (sp.petals) _scatterGround(b[0], b[2], b[3], b[5], y, ['pink_petals'], 0.18);
    return b;
}

/** Hangs leaves (or vines) under the canopy: lowest leaf in random columns gets a short strand. */
function _hang(b, sp, len, n) {
    var strand = sp.vines ? 'vine' : sp.leaves;
    for (var i = 0; i < n; i++) {
        var x = randInt(Math.ceil(b[0]), Math.floor(b[3])), z = randInt(Math.ceil(b[2]), Math.floor(b[5]));
        for (var yy = Math.floor(b[4]); yy >= Math.ceil(b[1]); yy--) {
            var g = get(x, yy, z);
            if (g && /_leaves/.test(g) && get(x, yy - 1, z) === null) {
                var l = randInt(len[0], len[1]);
                for (var q = 1; q <= l; q++) if (get(x, yy - q, z) === null && yy - q >= 1) block(x, yy - q, z, sp.vines ? 'vine[north=true]' : strand);
                break;
            }
        }
    }
}

function _conifer(x, y, z, h, cr, sp, opts) {
    var r = opts.trunk || Math.max(0.5, h / 18);
    // the trunk ends inside the crown: the thin top layers cannot hide a log
    place(tube([[x, y, z], [x, y + h - 2, z]], [r * 1.25, r * 0.6]), sp.log);
    if (r >= 0.9) _roots(x, y, z, r, sp);
    var start = y + Math.round(h * (sp.bare || 0.22)), layers = y + h + 1 - start;
    for (var i = 0; i < layers; i++) {
        var yy = start + i, t = i / Math.max(1, layers - 1);
        var big = i % 3 !== 1, rad = cr * Math.pow(1 - t, 0.95) * (big ? 1 : 0.62) + 0.7;
        place(cyl([x, yy, z], [x, yy, z], rad).warp(0.55, 1.7), sp.leaves, { onlyAir: true });
        if (big && rad > 2.2) {
            // drooping tips under the big skirts
            for (var a = 0; a < 6.28; a += 6.28 / Math.round(rad * 2.2)) {
                var px = Math.round(x + Math.cos(a) * (rad - 0.6)), pz = Math.round(z + Math.sin(a) * (rad - 0.6));
                if (chance(0.55) && get(px, yy - 1, pz) === null) block(px, yy - 1, pz, sp.leaves);
            }
        }
    }
    for (var ty = y + h - 1; ty <= y + h + 3; ty++) block(x, ty, z, sp.leaves);
    return [x - cr - 1, start, z - cr - 1, x + cr + 1, y + h + 3, z + cr + 1];
}

function _palm(x, y, z, h, cr, sp, opts) {
    var la = rand(0, 6.28), bend = h * 0.28;
    var top = [x + Math.cos(la) * bend, y + h, z + Math.sin(la) * bend];
    place(tube([[x, y, z], [x + Math.cos(la) * bend * 0.2, y + h * 0.5, z + Math.sin(la) * bend * 0.2], top], [0.9, 0.7, 0.6]), sp.log);
    var n = 8;
    for (var i = 0; i < n; i++) {
        var a = 6.28 * i / n + rand(-0.2, 0.2), len = cr * rand(0.9, 1.2);
        var mid = [top[0] + Math.cos(a) * len * 0.55, top[1] + 1.2, top[2] + Math.sin(a) * len * 0.55];
        var end = [top[0] + Math.cos(a) * len, top[1] - len * 0.45, top[2] + Math.sin(a) * len];
        place(tube([[top[0], top[1] + 0.5, top[2]], mid, end], [0.9, 0.7, 0.4]), sp.leaves, { onlyAir: true });
    }
    block(Math.round(top[0]), Math.round(top[1]) + 1, Math.round(top[2]), sp.leaves);
    return [top[0] - cr, y, top[2] - cr, top[0] + cr, top[1] + 2, top[2] + cr];
}

/** Natural boulder of radius r: lumpy, stratified mixed stone with moss on top. opts: m (side material), top, smooth. */
function rock(x, y, z, r, opts) {
    opts = opts || {};
    var side = opts.m || mix({ stone: 4, andesite: 3, tuff: 2, cobblestone: 1 }, { scale: 1.5 });
    var top = opts.top || mix({ moss_block: 3, mossy_cobblestone: 2, stone: 2 }, { scale: 1.5 });
    var s = ellipsoid([x, y, z], [r * 1.25, r * 0.8, r]).warp(r * 0.32 + 0.3, Math.max(2, r * 0.9)).rotY(rand(0, 180));
    return place(s, byNormal({ top: top, side: side, bottom: 'cobblestone' }), { smooth: opts.smooth !== false });
}

/** Ground path along points (y of each point = the ground layer it replaces), width w, with natural ragged edges. */
function path(points, opts) {
    opts = opts || {};
    var w = opts.w || 3, m = opts.m || mix({ dirt_path: 5, coarse_dirt: 2, gravel: 1 }, { scale: 2 });
    var pts = points.map(_v), y = pts[0][1];
    place(tube(pts, w / 2, { curve: opts.curve || 'smooth' }).warp(opts.rough === undefined ? 0.6 : opts.rough, 2.5)
        .and(cuboid([-1000, y, -1000], [1000, y, 1000])), m);
}

function _scatterGround(x1, z1, x2, z2, y, kinds, density) {
    for (var x = Math.ceil(x1); x <= Math.floor(x2); x++) for (var z = Math.ceil(z1); z <= Math.floor(z2); z++) {
        if (!chance(density)) continue;
        var yy = y;
        if (get(x, yy, z) !== null) continue;
        var below = get(x, yy - 1, z);
        if (below !== null && !/grass_block|dirt|moss|podzol/.test(below)) continue;
        block(x, yy, z, pick(kinds));
    }
}

/** Scatter flowers and grass over x1..x2 / z1..z2 standing at layer y (default 1), in natural clusters. */
function flowers(x1, z1, x2, z2, opts) {
    opts = opts || {};
    var y = opts.y || 1, density = opts.density || 0.25;
    var kinds = opts.kinds || ['poppy', 'dandelion', 'cornflower', 'oxeye_daisy', 'azure_bluet', 'allium', 'red_tulip'];
    var grass = opts.grass || ['short_grass', 'short_grass', 'short_grass', 'fern', 'tall_grass[half=lower]'];
    for (var x = x1; x <= x2; x++) for (var z = z1; z <= z2; z++) {
        if (get(x, y, z) !== null) continue;
        var below = get(x, y - 1, z);
        if (below !== null && !/grass_block|dirt|moss|podzol/.test(below)) continue;
        var cluster = (noise(x, 0, z, 4) + 1) / 2;
        if (!chance(density * Math.max(0, cluster * 2.6 - 0.9))) continue;
        if (cluster > 0.55 && chance(0.55)) {
            var kind = kinds[Math.floor(((noise(x + 99, 0, z - 41, 9) + 1) / 2) * kinds.length * 0.999)];
            block(x, y, z, kind);
        } else {
            var g = pick(grass);
            block(x, y, z, g);
            if (/tall_grass/.test(g)) block(x, y + 1, z, 'tall_grass[half=upper]');
        }
    }
}

/**
 * Natural ground over x1..x2 / z1..z2: repaints the ground layer (y = 0) in blended patches the way landscapers do
 * (grass with coarse dirt, podzol, moss, rooted dirt and gravel; fading out toward the edge so it meets the world
 * naturally) and scatters clustered grass and flowers. opts: patches (0..1 share of non-grass, default 0.3),
 * kinds, density (plants, default 0.22), forest (more podzol/moss), dry (more coarse dirt/gravel).
 */
function groundCover(x1, z1, x2, z2, opts) {
    opts = opts || {};
    var share = opts.patches === undefined ? 0.3 : opts.patches;
    var cx = (x1 + x2) / 2, cz = (z1 + z2) / 2, hx = Math.max(1, (x2 - x1) / 2), hz = Math.max(1, (z2 - z1) / 2);
    var alt = opts.forest ? ['podzol', 'moss_block', 'coarse_dirt', 'rooted_dirt'] : opts.dry ? ['coarse_dirt', 'gravel', 'dirt', 'packed_mud']
        : ['coarse_dirt', 'podzol', 'rooted_dirt', 'moss_block'];
    for (var x = x1; x <= x2; x++) for (var z = z1; z <= z2; z++) {
        var e = Math.max(Math.abs(x - cx) / hx, Math.abs(z - cz) / hz);
        if (e > 0.75 && chance((e - 0.75) * 4)) continue;
        if (get(x, 0, z) !== null && !/grass_block/.test(get(x, 0, z))) continue;
        var n = (noise(x, 0, z, 6) + 1) / 2, n2 = (noise(x - 31, 0, z + 17, 2.5) + 1) / 2;
        var m = 'grass_block';
        if (n < share * 0.9 && n2 < 0.75) m = alt[Math.floor(n2 * alt.length * 0.999)];
        block(x, 0, z, m);
    }
    if (opts.density !== 0) flowers(x1, z1, x2, z2, { density: opts.density || 0.1, kinds: opts.kinds });
}

/**
 * Rolling terrain over x1..x2 / z1..z2 raised up to opts.height above the ground: grass tops, dirt below, mixed
 * stone cliffs where it is steep, fading to the natural ground at the edges. opts: height (default 4), scale (feature
 * size, default 10), cliffs (0..1, default 0.5), top, island (true: an oval mound instead of a rectangle).
 * Returns a function h(x, z) giving the terrain top layer (0 = natural ground), to stand things on it.
 */
function terrain(x1, z1, x2, z2, opts) {
    opts = opts || {};
    var H = opts.height === undefined ? 4 : opts.height, sc = opts.scale || 10, cliff = opts.cliffs === undefined ? 0.5 : opts.cliffs;
    var cx = (x1 + x2) / 2, cz = (z1 + z2) / 2, hx = Math.max(1, (x2 - x1) / 2), hz = Math.max(1, (z2 - z1) / 2);
    var stone = mix({ stone: 4, andesite: 3, tuff: 2, cobblestone: 1, mossy_cobblestone: 1 }, { scale: 1.5 });
    function height(x, z) {
        var e = opts.island ? Math.sqrt(Math.pow((x - cx) / hx, 2) + Math.pow((z - cz) / hz, 2)) : Math.max(Math.abs(x - cx) / hx, Math.abs(z - cz) / hz);
        var fall = e >= 1 ? 0 : 1 - Math.pow(e, 3);
        var n = (noise(x, 0, z, sc) + 1) / 2 * 0.75 + (noise(x + 77, 0, z - 13, sc / 3) + 1) / 2 * 0.25;
        return Math.max(0, Math.round(H * fall * (0.35 + 0.85 * n)));
    }
    for (var x = x1; x <= x2; x++) for (var z = z1; z <= z2; z++) {
        var t = height(x, z);
        if (t <= 0) continue;
        var slope = Math.max(Math.abs(t - height(x + 1, z)), Math.abs(t - height(x - 1, z)), Math.abs(t - height(x, z + 1)), Math.abs(t - height(x, z - 1)));
        var rocky = slope >= 2 && chance(cliff + 0.3);
        for (var yy = 1; yy <= t; yy++) {
            var m = yy < t - 2 || rocky ? stone : yy < t ? 'dirt' : (opts.top || (slope >= 2 && chance(0.3) ? 'coarse_dirt' : 'grass_block'));
            block(x, yy, z, m);
        }
    }
    return height;
}

/** Pond: oval basin centred (cx, cz) radii rx, rz, depth (default 2), with a gravel/clay bed, sandy shore, reeds and lily pads. */
function pond(cx, cz, rx, rz, opts) {
    opts = opts || {};
    var dep = opts.depth || 2;
    var basin = ellipsoid([cx, 0.5, cz], [rx, dep + 0.5, rz]).warp(0.8, 3);
    var b = basin.bounds();
    for (var x = Math.floor(b[0]) - 2; x <= Math.ceil(b[3]) + 2; x++) for (var z = Math.floor(b[2]) - 2; z <= Math.ceil(b[5]) + 2; z++) {
        var e = Math.pow((x - cx) / (rx + 0.5), 2) + Math.pow((z - cz) / (rz + 0.5), 2);
        if (e < 1) {
            var d = Math.max(1, Math.round(dep * (1 - e) + 0.4));
            for (var yy = 0; yy > -d; yy--) block(x, yy, z, 'water');
            block(x, -d, z, pick(['gravel', 'clay', 'sand', 'mud']));
            if (e < 0.7 && chance(0.05)) block(x, 1, z, 'lily_pad');
            if (e > 0.75 && chance(0.25)) block(x, -d + 1, z, 'seagrass');
        } else if (e < 1.6) {
            block(x, 0, z, pick(['sand', 'gravel', 'coarse_dirt', 'grass_block', 'mud']));
            if (e < 1.35 && chance(0.18)) { block(x, 1, z, 'sugar_cane'); if (chance(0.5)) block(x, 2, z, 'sugar_cane'); }
        }
    }
}

/** Hedge / bush blob at (x, y, z) radius r, leaves of mixed tones. */
function bush(x, y, z, r, leaves) {
    place(ellipsoid([x, y, z], [(r || 1.5) * 1.2, (r || 1.5) * 0.85, (r || 1.5)]).warp(0.6, 1.8),
        leaves || mix({ oak_leaves: 3, azalea_leaves: 2, flowering_azalea_leaves: 1 }, { scale: 1.5 }), { onlyAir: true });
}
/** Terrain mound / hill: smooth half-ellipsoid with grass on top, dirt and stone inside. */
function hill(x, z, rx, rz, h, opts) {
    opts = opts || {};
    var top = opts.top || 'grass_block';
    var s = ellipsoid([x, 0, z], [rx, h, rz]).warp(opts.rough || 1.2, 5).and(cuboid([x - rx - 3, 0, z - rz - 3], [x + rx + 3, h + 3, z + rz + 3]));
    place(s, byNormal({ top: top, side: opts.side || mix({ dirt: 3, coarse_dirt: 1, stone: 1 }), bottom: 'stone' }), { smooth: opts.smooth === true });
    return s;
}
/** Water inside a basin shape or box [x1,y1,z1,x2,y2,z2] (keep it enclosed so it cannot flow). */
function water(s) {
    if (Array.isArray(s)) return box(s[0], s[1], s[2], s[3], s[4], s[5], 'water');
    return place(s, 'water');
}

/** Street lamp: post of fence/wall h tall with a lantern on top, or (arm: true) a lantern hanging on a chain from an arm toward dir. */
function lampPost(x, y, z, opts) {
    opts = opts || {};
    var h = opts.h || 3, post = opts.m || 'spruce_fence', lamp = opts.lamp || 'lantern';
    block(x, y, z, opts.base || 'stone_brick_wall');
    for (var i = 1; i < h; i++) block(x, y + i, z, post);
    if (opts.arm) {
        var d = DIRS[opts.dir || 'east'];
        block(x, y + h, z, post);
        block(x + d[0], y + h, z + d[1], post);
        block(x + d[0], y + h - 1, z + d[1], 'iron_chain');
        block(x + d[0], y + h - 2, z + d[1], lamp + '[hanging=true]');
    } else block(x, y + h, z, lamp);
}

/** Fence along points with log posts every 4 blocks and a gate where opts.gate = index of the gap cell. */
function fenceLine(points, opts) {
    opts = opts || {};
    var m = opts.m || 'spruce_fence', post = opts.post || 'stripped_spruce_log', y = opts.y || 1, n = 0;
    for (var i = 0; i + 1 < points.length; i++) {
        var a = _v(points[i]), b = _v(points[i + 1]);
        var steps = Math.max(Math.abs(b[0] - a[0]), Math.abs(b[1] - a[1] || 0), Math.abs((b[2] !== undefined ? b[2] : b[1]) - (a[2] !== undefined ? a[2] : a[1])));
        for (var s2 = 0; s2 <= steps; s2++, n++) {
            var px = Math.round(lerp(a[0], b[0], s2 / Math.max(1, steps))), pz = Math.round(lerp(a.length > 2 ? a[2] : a[1], b.length > 2 ? b[2] : b[1], s2 / Math.max(1, steps)));
            if (opts.gate !== undefined && n === opts.gate) { block(px, y, pz, (opts.gateM || m.replace('_fence', '_fence_gate'))); continue; }
            block(px, y, pz, n % 4 === 0 ? post : m);
            if (n % 4 === 0 && opts.caps) block(px, y + 1, pz, opts.cap || 'lantern');
        }
    }
}
