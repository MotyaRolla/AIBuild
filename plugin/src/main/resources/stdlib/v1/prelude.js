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
function clear(x1, y1, z1, x2, y2, z2) { return __box(x1, y1, z1, x2, y2, z2, 'air', 'fill'); }
/** Line of blocks; r > 0 gives a round beam of that radius. Logs auto-orient along the line. */
function line(a, b, m, r) { return __line(_v(a), _v(b), m, r || 0); }
/** Block at a local position, e.g. 'oak_planks' or 'oak_stairs[facing=north,half=bottom,shape=straight]', or null. */
function get(x, y, z) { return __get(x, y, z); }
/** Legacy JSON operation, e.g. op({op:'roof', from:[0,6,0], to:[9,6,7], style:'gable', stairs:'spruce_stairs'}). */
function op(o) { return __op(o); }

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
    if (!name) throw new Error('no stairs exist for ' + m);
    return name + '[facing=' + facing + ',half=' + (half || 'bottom') + ']';
}
/** Slab state: slab('oak_planks', 'top'). */
function slab(m, type) {
    var b = _base(m);
    var name = typeof b === 'string' && /_slab$/.test(b) ? b : slabOf(m);
    if (!name) throw new Error('no slab exists for ' + m);
    return name + '[type=' + (type || 'bottom') + ']';
}

// ---------------------------------------------------------------- architecture kit

/**
 * Roof over the wall-top rectangle x1..x2 / z1..z2 starting at layer y.
 * opts.type: gable | hip | pyramid | shed | flat (stair roofs), dome | cone | spire | onion (smooth round roofs on
 * square or round plans), mansard. opts.m: roof block (stairs and slabs are derived), opts.fill (gable ends),
 * opts.overhang (default 1), opts.ridge 'x'|'z', opts.height (round roofs), opts.trim (eave stairs, default m).
 */
function roof(x1, z1, x2, z2, y, opts) {
    opts = opts || {};
    var type = opts.type || 'gable', m = opts.m || 'spruce_planks';
    var cx = (x1 + x2) / 2, cz = (z1 + z2) / 2, w = Math.abs(x2 - x1) + 1, d = Math.abs(z2 - z1) + 1;
    var ov = opts.overhang === undefined ? 1 : opts.overhang;
    if (type === 'dome' || type === 'cone' || type === 'spire' || type === 'onion') {
        var r = Math.max(w, d) / 2 + ov - 0.5, h = opts.height || (type === 'dome' ? r : type === 'onion' ? r * 1.8 : r * (type === 'spire' ? 3 : 1.6));
        var s;
        if (type === 'dome') s = ellipsoid([cx, y - 1, cz], [r, h, r]).and(cuboid([cx - r - 1, y, cz - r - 1], [cx + r + 1, y + h + 1, cz + r + 1]));
        else if (type === 'onion') s = revolve([cx, y, cz], [[r * 0.75, 0], [r * 1.05, h * 0.28], [r * 0.9, h * 0.5], [r * 0.45, h * 0.75], [0.6, h * 0.92], [0.3, h]]);
        else s = cone([cx, y, cz], [cx, y + h, cz], r, 0.2);
        place(s, m, { smooth: true, shell: opts.shell || 2 });
        if (opts.finial !== false) block(Math.round(cx), Math.round(y + h + 1), Math.round(cz), opts.finial || 'lightning_rod');
        return s;
    }
    if (type === 'mansard') {
        var steep = stairsOf(m);
        op({ op: 'roof', from: [x1, y, z1], to: [x2, y, z2], style: 'hip', stairs: steep, slab: slabOf(m), fill: opts.fill || m, overhang: ov });
        return;
    }
    var st = stairsOf(m) || m, sl = slabOf(m);
    var o = { op: 'roof', from: [x1, y, z1], to: [x2, y, z2], style: type, stairs: st, overhang: ov, fill: opts.fill || m };
    if (sl) o.slab = sl;
    if (opts.ridge) o.ridge = opts.ridge;
    if (opts.facing) o.facing = opts.facing;
    op(o);
}

/**
 * Window in an existing wall. (x, y, z) = bottom-centre cell of the opening in the wall plane; face = the side the
 * window looks out of ('south' for a front wall at the largest z). opts: w (1..5, default 1), h (default 2),
 * glass, frame (block around the opening, 1 cell proud of the wall), sill (true / block), shutters (trapdoor id),
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
        var gap = opts.frame ? 2 : 1, ls = cell(-half - gap, 0, 1), rs = cell(w - 1 - half + gap, 0, 1);
        for (var u3 = 0; u3 < h; u3++) {
            block(ls[0], ls[1] + u3, ls[2], opts.shutters + '[facing=' + face + ',open=true]');
            block(rs[0], rs[1] + u3, rs[2], opts.shutters + '[facing=' + face + ',open=true]');
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

// ---------------------------------------------------------------- interior kit

/** Bed: foot at (x, y, z), head 1 block toward facing. */
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
    if (opts.hanging) {
        var chain = opts.chain || 0;
        for (var i = 0; i < chain; i++) block(x, y - i, z, 'iron_chain');
        block(x, y - chain, z, (opts.m || 'lantern') + '[hanging=true]');
    } else block(x, y, z, opts.m || 'lantern');
}
/** Carpet rug over x1..x2 / z1..z2 at y, with an optional border colour. */
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

// ---------------------------------------------------------------- nature and scene kit

var TREES = {
    oak: { log: 'oak_log', leaves: 'oak_leaves', h: 7, crown: 3.5 },
    birch: { log: 'birch_log', leaves: 'birch_leaves', h: 8, crown: 2.8 },
    spruce: { log: 'spruce_log', leaves: 'spruce_leaves', h: 11, crown: 3.5, conifer: true },
    pine: { log: 'spruce_log', leaves: 'spruce_leaves', h: 13, crown: 3, conifer: true },
    dark_oak: { log: 'dark_oak_log', leaves: 'dark_oak_leaves', h: 7, crown: 4.5 },
    cherry: { log: 'cherry_log', leaves: 'cherry_leaves', h: 6, crown: 4 },
    jungle: { log: 'jungle_log', leaves: 'jungle_leaves', h: 12, crown: 4 },
    acacia: { log: 'acacia_log', leaves: 'acacia_leaves', h: 6, crown: 3.5, flat: true },
    willow: { log: 'mangrove_log', leaves: 'mangrove_leaves', h: 7, crown: 4.5, droop: true },
    azalea: { log: 'oak_log', leaves: 'flowering_azalea_leaves', h: 5, crown: 3 },
    dead: { log: 'spruce_log', leaves: null, h: 6, crown: 0 }
};
/** Organic tree with a curved trunk, branches and a lumpy crown. opts: species (see TREES), h, crown, lean. */
function tree(x, y, z, opts) {
    opts = opts || {};
    var sp = TREES[opts.species || 'oak'] || TREES.oak, h = opts.h || sp.h, cr = opts.crown || sp.crown;
    var lean = opts.lean === undefined ? rand(-1, 1) : opts.lean, lean2 = rand(-1, 1);
    var trunkR = opts.trunk || Math.max(0.7, h / 12);
    var top = [x + lean, y + h, z + lean2];
    part('tree', function () {
        place(tube([[x, y, z], [x + lean * 0.3, y + h * 0.5, z + lean2 * 0.3], top], function (t) { return trunkR * (1.3 - 0.6 * t); }), sp.log);
        if (!sp.leaves) {
            for (var b = 0; b < 4; b++) {
                var a = rand(0, 6.28), hy = y + h * rand(0.5, 0.9);
                place(tube([[x, hy, z], [x + Math.cos(a) * 3, hy + 2, z + Math.sin(a) * 3]], [0.6, 0.3]), sp.log);
            }
            return;
        }
        if (sp.conifer) {
            for (var k = 0; k < 4; k++) {
                var yy = y + h * (0.3 + k * 0.18);
                place(cone([top[0], yy, top[2]], [top[0], yy + h * 0.3, top[2]], cr * (1 - k * 0.2), 0.3), sp.leaves);
            }
            block(Math.round(top[0]), Math.round(y + h + 1), Math.round(top[2]), sp.leaves);
            return;
        }
        var blobs = [];
        var n = opts.blobs || 5;
        for (var i = 0; i < n; i++) {
            var ang = 2 * Math.PI * i / n + rand(0, 1), dist = cr * rand(0.35, 0.7);
            var c = [top[0] + Math.cos(ang) * dist, top[1] + rand(-1, 1.5) - (sp.flat ? 1 : 0), top[2] + Math.sin(ang) * dist];
            place(tube([[top[0], top[1] - 2, top[2]], c], [trunkR * 0.6, 0.35]), sp.log);
            blobs.push(sp.flat ? ellipsoid(c, [cr * 0.8, 1.4, cr * 0.8]) : sphere(c, cr * rand(0.6, 0.85)));
        }
        blobs.push(sp.flat ? ellipsoid(top, [cr, 1.6, cr]) : sphere([top[0], top[1] + 1, top[2]], cr));
        place(blend(2, blobs).warp(1.0, 3), sp.leaves, { onlyAir: true });
        if (sp.droop) {
            for (var j = 0; j < 14; j++) {
                var aa = rand(0, 6.28), rr = cr * rand(0.8, 1.1), px = Math.round(top[0] + Math.cos(aa) * rr), pz = Math.round(top[2] + Math.sin(aa) * rr);
                var len = randInt(2, 4);
                for (var q = 0; q < len; q++) block(px, Math.round(top[1]) - q, pz, sp.leaves);
            }
        }
    });
}
/** Natural boulder of radius r (lumpy, mossy), centre (x, y, z). */
function rock(x, y, z, r, opts) {
    opts = opts || {};
    var m = opts.m || mix({ stone: 5, andesite: 3, cobblestone: 2, mossy_cobblestone: 1 }, { scale: 2 });
    return place(ellipsoid([x, y, z], [r * 1.2, r * 0.8, r]).warp(r * 0.35, Math.max(2, r)), m, { smooth: opts.smooth !== false });
}
/** Ground path along points (y of each point = the ground layer it replaces), width w. */
function path(points, opts) {
    opts = opts || {};
    var w = opts.w || 3, m = opts.m || mix({ dirt_path: 5, coarse_dirt: 2, gravel: 1 }, { scale: 2 });
    var pts = points.map(_v), y = pts[0][1];
    place(tube(pts, w / 2, { curve: opts.curve || 'smooth' }).and(cuboid([-1000, y, -1000], [1000, y, 1000])), m);
}
/** Scatter flowers and grass over x1..x2 / z1..z2 standing at layer y (default 1). */
function flowers(x1, z1, x2, z2, opts) {
    opts = opts || {};
    var y = opts.y || 1, density = opts.density || 0.25;
    var kinds = opts.kinds || ['short_grass', 'short_grass', 'poppy', 'dandelion', 'cornflower', 'oxeye_daisy', 'azure_bluet', 'fern'];
    for (var x = x1; x <= x2; x++) for (var z = z1; z <= z2; z++) {
        if (get(x, y, z) !== null || !chance(density)) continue;
        var below = get(x, y - 1, z);
        if (below !== null && !/grass_block|dirt|moss|podzol/.test(below)) continue;
        block(x, y, z, pick(kinds));
    }
}
/** Hedge / bush blob at (x, y, z) radius r. */
function bush(x, y, z, r, leaves) { place(sphere([x, y, z], r || 1.5).warp(0.6, 2), leaves || 'oak_leaves', { onlyAir: true }); }
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
