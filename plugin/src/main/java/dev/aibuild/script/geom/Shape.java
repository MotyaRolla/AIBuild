package dev.aibuild.script.geom;

import java.util.ArrayList;
import java.util.List;

/**
 * A bounded signed-distance solid: negative inside, positive outside, in block units.
 *
 * <p>Block {@code (x, y, z)} is the unit cube centred on the integer point, so a box from 0 to 9 spans
 * {@code [-0.5, 9.5]} and a vertical cylinder from y 0 to y 9 contains ten layers. Distances only need to be
 * exact near the surface; far away they may be any lower bound, which keeps unions of many parts cheap.</p>
 *
 * <p>Formulas for the capped cone, rounded box, torus and polygon distances follow the public-domain
 * catalogue by Inigo Quilez. Transform and sweep handling is adapted from the 1.7.x component geometry.</p>
 */
public interface Shape {
    double MAX_EXTENT = 1024;

    double d(double x, double y, double z);

    /** Conservative continuous bounds {minX,minY,minZ,maxX,maxY,maxZ}. */
    double[] bounds();

    /** Rough evaluation cost per sample (number of leaf primitives), used for work budgets. */
    int cost();

    // ------------------------------------------------------------------ primitives

    static Shape sphere(double[] c, double r) {
        positive(r, "radius");
        return of(new double[]{c[0] - r, c[1] - r, c[2] - r, c[0] + r, c[1] + r, c[2] + r}, 1,
                (x, y, z) -> Math.sqrt(sq(x - c[0]) + sq(y - c[1]) + sq(z - c[2])) - r);
    }

    static Shape ellipsoid(double[] c, double[] r) {
        for (double v : r) positive(v, "radius");
        double min = Math.min(r[0], Math.min(r[1], r[2]));
        return of(new double[]{c[0] - r[0], c[1] - r[1], c[2] - r[2], c[0] + r[0], c[1] + r[1], c[2] + r[2]}, 1,
                (x, y, z) -> (Math.sqrt(sq((x - c[0]) / r[0]) + sq((y - c[1]) / r[1]) + sq((z - c[2]) / r[2])) - 1) * min);
    }

    /** Box covering the cells {@code a..b} inclusive (corners in any order). */
    static Shape box(double[] a, double[] b) {
        return rbox(a, b, 0);
    }

    /** Box covering the cells {@code a..b} with edges rounded by {@code round} blocks. */
    static Shape rbox(double[] a, double[] b, double round) {
        double[] lo = new double[3], hi = new double[3];
        for (int i = 0; i < 3; i++) {
            lo[i] = Math.min(a[i], b[i]) - 0.5;
            hi[i] = Math.max(a[i], b[i]) + 0.5;
        }
        double cx = (lo[0] + hi[0]) / 2, cy = (lo[1] + hi[1]) / 2, cz = (lo[2] + hi[2]) / 2;
        double hx = (hi[0] - lo[0]) / 2, hy = (hi[1] - lo[1]) / 2, hz = (hi[2] - lo[2]) / 2;
        double rr = Math.clamp(round, 0, Math.min(hx, Math.min(hy, hz)));
        return of(new double[]{lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]}, 1, (x, y, z) -> {
            double qx = Math.abs(x - cx) - hx + rr, qy = Math.abs(y - cy) - hy + rr, qz = Math.abs(z - cz) - hz + rr;
            double outside = Math.sqrt(sq(Math.max(qx, 0)) + sq(Math.max(qy, 0)) + sq(Math.max(qz, 0)));
            return outside + Math.min(Math.max(qx, Math.max(qy, qz)), 0) - rr;
        });
    }

    /** Capped cylinder whose end-cap centres are the cells {@code a} and {@code b} (both layers included). */
    static Shape cyl(double[] a, double[] b, double r) {
        return cone(a, b, r, r);
    }

    /** Capped cone from radius {@code ra} at cell {@code a} to {@code rb} at cell {@code b}; one radius may be 0. */
    static Shape cone(double[] a0, double[] b0, double ra, double rb) {
        if (ra < 0 || rb < 0 || Math.max(ra, rb) < 0.1 || Math.max(ra, rb) > MAX_EXTENT) {
            throw new IllegalArgumentException("cone radii must be 0..1024 and not both 0");
        }
        double[] dir = sub(b0, a0);
        double len = Math.sqrt(dot(dir, dir));
        double[] a, b;
        if (len < 1e-9) {
            a = new double[]{a0[0], a0[1] - 0.5, a0[2]};
            b = new double[]{a0[0], a0[1] + 0.5, a0[2]};
        } else {
            double[] u = {dir[0] / len * 0.5, dir[1] / len * 0.5, dir[2] / len * 0.5};
            a = sub(a0, u);
            b = add(b0, u);
        }
        double[] ba = sub(b, a);
        double baba = dot(ba, ba), rmax = Math.max(ra, rb);
        double[] bounds = new double[6];
        for (int k = 0; k < 3; k++) {
            bounds[k] = Math.min(a[k], b[k]) - rmax;
            bounds[k + 3] = Math.max(a[k], b[k]) + rmax;
        }
        return of(bounds, 1, (x, y, z) -> {
            double px = x - a[0], py = y - a[1], pz = z - a[2];
            double paba = (px * ba[0] + py * ba[1] + pz * ba[2]) / baba;
            double papa = px * px + py * py + pz * pz;
            double xr = Math.sqrt(Math.max(0, papa - paba * paba * baba));
            double cax = Math.max(0, xr - (paba < 0.5 ? ra : rb));
            double cay = Math.abs(paba - 0.5) - 0.5;
            double k = sq(rb - ra) + baba;
            double f = Math.clamp(((rb - ra) * (xr - ra) + paba * baba) / k, 0, 1);
            double cbx = xr - ra - f * (rb - ra);
            double cby = paba - f;
            double s = (cbx < 0 && cay < 0) ? -1 : 1;
            return s * Math.sqrt(Math.min(cax * cax + cay * cay * baba, cbx * cbx + cby * cby * baba));
        });
    }

    /** Rounded cone (capsule) between points {@code a} and {@code b} with linearly varying radius. */
    static Shape capsule(double[] a, double[] b, double ra, double rb) {
        positive(Math.max(ra, rb), "radius");
        if (ra < 0 || rb < 0) throw new IllegalArgumentException("capsule radii must be >= 0");
        return segment(a, b, ra, rb);
    }

    /** Torus with major radius {@code big} and tube radius {@code small} around {@code axis}. */
    static Shape torus(double[] c, double big, double small, char axis) {
        positive(big, "major radius");
        positive(small, "tube radius");
        double e = big + small;
        double[] bounds = switch (axis) {
            case 'x' -> new double[]{c[0] - small, c[1] - e, c[2] - e, c[0] + small, c[1] + e, c[2] + e};
            case 'z' -> new double[]{c[0] - e, c[1] - e, c[2] - small, c[0] + e, c[1] + e, c[2] + small};
            default -> new double[]{c[0] - e, c[1] - small, c[2] - e, c[0] + e, c[1] + small, c[2] + e};
        };
        return of(bounds, 1, (x, y, z) -> {
            double px = x - c[0], py = y - c[1], pz = z - c[2];
            double ring, along;
            switch (axis) {
                case 'x' -> {
                    ring = Math.sqrt(py * py + pz * pz);
                    along = px;
                }
                case 'z' -> {
                    ring = Math.sqrt(px * px + py * py);
                    along = pz;
                }
                default -> {
                    ring = Math.sqrt(px * px + pz * pz);
                    along = py;
                }
            }
            return Math.sqrt(sq(ring - big) + along * along) - small;
        });
    }

    /**
     * Solid of revolution around the vertical line through {@code base}: {@code profile} lists
     * {@code [radius, height]} pairs (height relative to base, increasing or not), closed along the axis.
     */
    static Shape revolve(double[] base, double[][] profile) {
        if (profile.length < 2 || profile.length > 256) throw new IllegalArgumentException("revolve needs 2..256 profile points");
        List<double[]> poly = new ArrayList<>();
        poly.add(new double[]{0, profile[0][1]});
        double rmax = 0, ymin = Double.POSITIVE_INFINITY, ymax = Double.NEGATIVE_INFINITY;
        for (double[] p : profile) {
            if (p[0] < 0 || p[0] > MAX_EXTENT) throw new IllegalArgumentException("revolve radius must be 0..1024");
            poly.add(new double[]{p[0], p[1]});
            rmax = Math.max(rmax, p[0]);
            ymin = Math.min(ymin, p[1]);
            ymax = Math.max(ymax, p[1]);
        }
        poly.add(new double[]{0, profile[profile.length - 1][1]});
        positive(rmax, "revolve radius");
        double[][] pts = poly.toArray(new double[0][]);
        double[] bounds = {base[0] - rmax, base[1] + ymin, base[2] - rmax, base[0] + rmax, base[1] + ymax, base[2] + rmax};
        return of(bounds, 2, (x, y, z) -> {
            double r = Math.sqrt(sq(x - base[0]) + sq(z - base[2]));
            return polygon(pts, r, y - base[1]);
        });
    }

    /** Vertical prism: polygon {@code [[x, z], ...]} (cell centres) extruded over layers {@code y0..y1}. */
    static Shape extrude(double[][] poly, double y0, double y1) {
        if (poly.length < 3 || poly.length > 256) throw new IllegalArgumentException("extrude needs 3..256 polygon points");
        double lo = Math.min(y0, y1) - 0.5, hi = Math.max(y0, y1) + 0.5;
        double[] bounds = {Double.POSITIVE_INFINITY, lo, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, hi, Double.NEGATIVE_INFINITY};
        for (double[] p : poly) {
            bounds[0] = Math.min(bounds[0], p[0] - 0.5);
            bounds[3] = Math.max(bounds[3], p[0] + 0.5);
            bounds[2] = Math.min(bounds[2], p[1] - 0.5);
            bounds[5] = Math.max(bounds[5], p[1] + 0.5);
        }
        return of(bounds, 2, (x, y, z) -> {
            // +0.5 so that cells whose centre lies on the polygon edge are inside, like a hand-drawn outline
            double side = polygon(poly, x, z) - 0.5;
            double cap = Math.max(lo - y, y - hi);
            return Math.max(side, cap);
        });
    }

    /**
     * Tube through {@code pts} (polyline when {@code curve} is "line", otherwise a Catmull-Rom spline through the
     * points or, for "bezier", a single Bézier curve using them as control points). {@code radii} holds one radius
     * per point; radii are interpolated along the curve.
     */
    static Shape tube(double[][] pts, double[] radii, String curve) {
        if (pts.length < 2 || pts.length > 128) throw new IllegalArgumentException("tube needs 2..128 points");
        if (radii.length != pts.length) throw new IllegalArgumentException("tube needs one radius per point");
        for (double r : radii) {
            if (r < 0 || r > MAX_EXTENT) throw new IllegalArgumentException("tube radius must be 0..1024");
        }
        List<double[]> samples = new ArrayList<>();
        List<Double> sr = new ArrayList<>();
        int n = pts.length;
        double total = 0;
        for (int i = 0; i < n - 1; i++) total += Math.sqrt(dot(sub(pts[i + 1], pts[i]), sub(pts[i + 1], pts[i])));
        int steps = Math.clamp((int) Math.ceil(total * 2), n, 4096);
        for (int s = 0; s <= steps; s++) {
            double t = (double) s / steps;
            double[] p;
            if ("bezier".equals(curve)) {
                p = bezier(pts, t);
            } else {
                double f = t * (n - 1);
                int i = Math.min(n - 2, (int) Math.floor(f));
                double u = f - i;
                p = new double[3];
                for (int k = 0; k < 3; k++) {
                    p[k] = "line".equals(curve) ? pts[i][k] + (pts[i + 1][k] - pts[i][k]) * u
                            : catmull(pts[Math.max(0, i - 1)][k], pts[i][k], pts[i + 1][k], pts[Math.min(n - 1, i + 2)][k], u);
                }
            }
            double f = t * (n - 1);
            int i = Math.min(n - 2, (int) Math.floor(f));
            samples.add(p);
            sr.add(radii[i] + (radii[i + 1] - radii[i]) * (f - i));
        }
        List<Shape> segments = new ArrayList<>();
        for (int i = 0; i + 1 < samples.size(); i++) {
            double r0 = sr.get(i), r1 = sr.get(i + 1);
            if (Math.max(r0, r1) < 0.05) continue;
            segments.add(segment(samples.get(i), samples.get(i + 1), r0, r1));
        }
        if (segments.isEmpty()) throw new IllegalArgumentException("tube radii are all zero");
        return union(segments);
    }

    /** Loft through horizontal elliptical sections {center, rx, rz}, sorted by centre height. */
    static Shape loft(double[][] centers, double[][] radii) {
        int n = centers.length;
        if (n < 2 || n > 64 || radii.length != n) throw new IllegalArgumentException("loft needs 2..64 sections");
        for (int i = 1; i < n; i++) {
            if (centers[i][1] <= centers[i - 1][1]) throw new IllegalArgumentException("loft sections need increasing y");
        }
        double[] b = {Double.POSITIVE_INFINITY, centers[0][1] - 0.5, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, centers[n - 1][1] + 0.5, Double.NEGATIVE_INFINITY};
        for (int i = 0; i < n; i++) {
            positive(radii[i][0], "loft rx");
            positive(radii[i][1], "loft rz");
            b[0] = Math.min(b[0], centers[i][0] - radii[i][0]);
            b[3] = Math.max(b[3], centers[i][0] + radii[i][0]);
            b[2] = Math.min(b[2], centers[i][2] - radii[i][1]);
            b[5] = Math.max(b[5], centers[i][2] + radii[i][1]);
        }
        return of(b, 2, (x, y, z) -> {
            int i = 0;
            while (i < n - 2 && y > centers[i + 1][1]) i++;
            double t = Math.clamp((y - centers[i][1]) / (centers[i + 1][1] - centers[i][1]), 0, 1);
            double cx = lerp(centers[i][0], centers[i + 1][0], t), cz = lerp(centers[i][2], centers[i + 1][2], t);
            double rx = lerp(radii[i][0], radii[i + 1][0], t), rz = lerp(radii[i][1], radii[i + 1][1], t);
            double side = (Math.sqrt(sq((x - cx) / rx) + sq((z - cz) / rz)) - 1) * Math.min(rx, rz);
            return Math.max(side, Math.max(b[1] - y, y - b[4]));
        });
    }

    /**
     * Arched opening in a wall that runs along {@code along} ('x' or 'z'). {@code c} is the centre of the span on
     * that axis, {@code w0..w1} the wall cells it cuts through on the other horizontal axis, {@code hw} half the
     * span in blocks, {@code bottom} the lowest cell of the opening and {@code spring} the continuous height where
     * the curve starts. Styles: round (elliptical, rise {@code rise}), gothic (two arcs meeting in a point at
     * {@code spring + rise}), flat (rectangle up to {@code spring + rise}).
     */
    static Shape arch(char along, double c, double w0, double w1, double hw, double rise, double bottom, double spring, String style) {
        positive(hw, "arch half-span");
        if (rise < 0 || rise > MAX_EXTENT) throw new IllegalArgumentException("arch rise must be 0..1024");
        double wc = (Math.min(w0, w1) + Math.max(w0, w1)) / 2, wh = (Math.abs(w1 - w0) + 1) / 2;
        double lo = bottom - 0.5, top = spring + Math.max(rise, 0.01);
        boolean gothic = "gothic".equals(style), flat = "flat".equals(style) || rise < 0.5;
        double off = gothic ? Math.max(0, (rise * rise - hw * hw) / (2 * hw)) : 0, radius = hw + off;
        double[] b = along == 'x'
                ? new double[]{c - hw, lo, wc - wh, c + hw, top, wc + wh}
                : new double[]{wc - wh, lo, c - hw, wc + wh, top, c + hw};
        return of(b, 1, (x, y, z) -> {
            double u = (along == 'x' ? x : z) - c, w = (along == 'x' ? z : x) - wc;
            double rect = Math.max(Math.abs(u) - hw, Math.max(lo - y, y - (flat ? top : spring)));
            double curve;
            if (flat) curve = rect;
            else if (gothic) {
                double left = Math.sqrt(sq(u - off) + sq(y - spring)) - radius;
                double right = Math.sqrt(sq(u + off) + sq(y - spring)) - radius;
                curve = Math.max(Math.max(left, right), spring - y);
            } else {
                curve = Math.max((Math.sqrt(sq(u / hw) + sq((y - spring) / rise)) - 1) * Math.min(hw, rise), spring - y);
            }
            return Math.max(Math.min(rect, curve), Math.abs(w) - wh);
        });
    }

    // ------------------------------------------------------------------ combinators

    static Shape union(List<Shape> parts) {
        if (parts.isEmpty()) throw new IllegalArgumentException("union needs at least one shape");
        if (parts.size() == 1) return parts.getFirst();
        Shape[] p = parts.toArray(new Shape[0]);
        double[][] pb = new double[p.length][];
        double[] b = p[0].bounds().clone();
        int cost = 0;
        for (int i = 0; i < p.length; i++) {
            pb[i] = p[i].bounds();
            cost += p[i].cost();
            for (int k = 0; k < 3; k++) {
                b[k] = Math.min(b[k], pb[i][k]);
                b[k + 3] = Math.max(b[k + 3], pb[i][k + 3]);
            }
        }
        return of(b, cost, (x, y, z) -> {
            double best = Double.POSITIVE_INFINITY;
            for (int i = 0; i < p.length; i++) {
                double lb = boxDistance(pb[i], x, y, z);
                if (lb > 1.5) {
                    best = Math.min(best, lb);
                    continue;
                }
                best = Math.min(best, p[i].d(x, y, z));
            }
            return best;
        });
    }

    /** {@code a} minus every shape in {@code cut}. */
    static Shape subtract(Shape a, List<Shape> cut) {
        Shape c = union(cut);
        double[] cb = c.bounds();
        return of(a.bounds(), a.cost() + c.cost(), (x, y, z) -> {
            double d = a.d(x, y, z);
            if (d > 1.5 || boxDistance(cb, x, y, z) > 1.5) return d;
            return Math.max(d, -c.d(x, y, z));
        });
    }

    static Shape intersect(List<Shape> parts) {
        if (parts.isEmpty()) throw new IllegalArgumentException("intersect needs at least one shape");
        double[] b = parts.getFirst().bounds().clone();
        int cost = 0;
        for (Shape s : parts) {
            double[] q = s.bounds();
            cost += s.cost();
            for (int k = 0; k < 3; k++) {
                b[k] = Math.max(b[k], q[k]);
                b[k + 3] = Math.min(b[k + 3], q[k + 3]);
            }
        }
        for (int k = 0; k < 3; k++) if (b[k] > b[k + 3]) b[k + 3] = b[k];
        Shape[] p = parts.toArray(new Shape[0]);
        return of(b, cost, (x, y, z) -> {
            double d = Double.NEGATIVE_INFINITY;
            for (Shape s : p) d = Math.max(d, s.d(x, y, z));
            return d;
        });
    }

    /** Smooth union with blend radius {@code k} (organic joints: neck into body, branch into trunk). */
    static Shape blend(double k, List<Shape> parts) {
        if (!(k > 0 && k <= 16)) throw new IllegalArgumentException("blend radius must be >0 and <=16");
        if (parts.isEmpty()) throw new IllegalArgumentException("blend needs at least one shape");
        Shape[] p = parts.toArray(new Shape[0]);
        double[] b = p[0].bounds().clone();
        int cost = 0;
        for (Shape s : p) {
            double[] q = s.bounds();
            cost += s.cost();
            for (int i = 0; i < 3; i++) {
                b[i] = Math.min(b[i], q[i]);
                b[i + 3] = Math.max(b[i + 3], q[i + 3]);
            }
        }
        for (int i = 0; i < 3; i++) {
            b[i] -= k / 4;
            b[i + 3] += k / 4;
        }
        return of(b, cost, (x, y, z) -> {
            double d = p[0].d(x, y, z);
            for (int i = 1; i < p.length; i++) {
                double e = p[i].d(x, y, z);
                double h = Math.max(k - Math.abs(d - e), 0) / k;
                d = Math.min(d, e) - h * h * k * 0.25;
            }
            return d;
        });
    }

    /** Grows ({@code by > 0}) or shrinks the surface by {@code by} blocks. */
    static Shape offset(Shape s, double by) {
        if (!Double.isFinite(by) || Math.abs(by) > 64) throw new IllegalArgumentException("offset must be -64..64");
        double[] b = s.bounds().clone();
        double grow = Math.max(0, by);
        for (int i = 0; i < 3; i++) {
            b[i] -= grow;
            b[i + 3] += grow;
        }
        return of(b, s.cost(), (x, y, z) -> s.d(x, y, z) - by);
    }

    static Shape transform(Shape s, Frame f) {
        if (f.isIdentity()) return s;
        double stretch = Math.max(1e-3, f.minStretch());
        return of(f.bounds(s.bounds()), s.cost(), (x, y, z) -> {
            double[] p = f.inverse(x, y, z);
            return s.d(p[0], p[1], p[2]) * stretch;
        });
    }

    /** A shape from an arbitrary distance function; the caller supplies bounds. */
    static Shape of(double[] bounds, int cost, Field field) {
        for (double v : bounds) {
            if (!Double.isFinite(v) || Math.abs(v) > MAX_EXTENT) throw new IllegalArgumentException("shape exceeds the coordinate limit " + (int) MAX_EXTENT);
        }
        double[] b = bounds.clone();
        return new Shape() {
            @Override
            public double d(double x, double y, double z) {
                return field.at(x, y, z);
            }

            @Override
            public double[] bounds() {
                return b.clone();
            }

            @Override
            public int cost() {
                return Math.max(1, cost);
            }
        };
    }

    @FunctionalInterface
    interface Field {
        double at(double x, double y, double z);
    }

    // ------------------------------------------------------------------ helpers

    private static Shape segment(double[] a, double[] b, double r0, double r1) {
        double[] v = sub(b, a);
        double len = dot(v, v), r = Math.max(r0, r1);
        double[] bounds = new double[6];
        for (int k = 0; k < 3; k++) {
            bounds[k] = Math.min(a[k], b[k]) - r;
            bounds[k + 3] = Math.max(a[k], b[k]) + r;
        }
        return of(bounds, 1, (x, y, z) -> {
            double t = len < 1e-12 ? 0 : Math.clamp(((x - a[0]) * v[0] + (y - a[1]) * v[1] + (z - a[2]) * v[2]) / len, 0, 1);
            return Math.sqrt(sq(x - a[0] - t * v[0]) + sq(y - a[1] - t * v[1]) + sq(z - a[2] - t * v[2])) - (r0 + (r1 - r0) * t);
        });
    }

    /** Signed distance from {@code (px, py)} to a closed polygon (negative inside). */
    static double polygon(double[][] v, double px, double py) {
        double d = sq(px - v[0][0]) + sq(py - v[0][1]);
        double s = 1;
        for (int i = 0, j = v.length - 1; i < v.length; j = i, i++) {
            double ex = v[j][0] - v[i][0], ey = v[j][1] - v[i][1];
            double wx = px - v[i][0], wy = py - v[i][1];
            double ee = ex * ex + ey * ey;
            double t = ee < 1e-12 ? 0 : Math.clamp((wx * ex + wy * ey) / ee, 0, 1);
            d = Math.min(d, sq(wx - ex * t) + sq(wy - ey * t));
            boolean c1 = py >= v[i][1], c2 = py < v[j][1], c3 = ex * wy > ey * wx;
            if ((c1 && c2 && c3) || (!c1 && !c2 && !c3)) s = -s;
        }
        return s * Math.sqrt(d);
    }

    /** Distance from a point to an axis-aligned box (0 inside): a lower bound for any shape inside it. */
    static double boxDistance(double[] b, double x, double y, double z) {
        double dx = Math.max(Math.max(b[0] - x, x - b[3]), 0);
        double dy = Math.max(Math.max(b[1] - y, y - b[4]), 0);
        double dz = Math.max(Math.max(b[2] - z, z - b[5]), 0);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double[] bezier(double[][] p, double t) {
        double[][] w = new double[p.length][];
        for (int i = 0; i < p.length; i++) w[i] = p[i].clone();
        for (int n = p.length - 1; n > 0; n--) {
            for (int i = 0; i < n; i++) {
                for (int k = 0; k < 3; k++) w[i][k] = w[i][k] + (w[i + 1][k] - w[i][k]) * t;
            }
        }
        return w[0];
    }

    private static double catmull(double a, double b, double c, double d, double t) {
        return 0.5 * ((2 * b) + (-a + c) * t + (2 * a - 5 * b + 4 * c - d) * t * t + (-a + 3 * b - 3 * c + d) * t * t * t);
    }

    private static void positive(double v, String name) {
        if (!(v >= 0.1 && v <= MAX_EXTENT)) throw new IllegalArgumentException(name + " must be 0.1..1024");
    }

    private static double sq(double v) {
        return v * v;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double[] sub(double[] a, double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static double[] add(double[] a, double[] b) {
        return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }
}
