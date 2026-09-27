package io.github.lazytive.alosearth.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps Minecraft block coordinates to latitude/longitude on an equi-angular
 * cube ("cubed sphere") unfolded into a cross:
 *
 * <pre>
 *             +-------+
 *             | north |
 *     +-------+-------+-------+-------+
 *     | west  | front | east  | back  |
 *     +-------+-------+-------+-------+
 *             | south |
 *             +-------+
 * </pre>
 *
 * {@code front} is centred on (lat0, lon0) with north towards -z. Five cube
 * edges touch in the cross; the other seven are {@link Link seams}: rigid
 * block-grid transforms (shift + multiple of 90 degrees) used both to
 * teleport entities across and to fill a margin strip beyond each seam with
 * a copy of the terrain on the other side.
 *
 * This is a line-by-line port of {@code alos2mc/projection.py}; the tests
 * compare the two.
 */
public final class CubeProjection {
    public static final double EARTH_MEAN_RADIUS_M = 6_371_008.8;
    public static final double EARTH_CIRCUMFERENCE_M = 2 * Math.PI * EARTH_MEAN_RADIUS_M;

    public static final int OUTSIDE = 0, ON_FACE = 1, SEAM_MARGIN = 2, CORNER_MARGIN = 3;

    public final int size;
    public final double metersPerBlock;
    public final double lat0, lon0;
    public final int margin;
    public final Face[] faces;
    public final Link[] links;

    public static final class Face {
        public final String name;
        public final int x0, z0, size;
        final double[] n, xa, za;

        Face(String name, int x0, int z0, int size, double[] n, double[] xa, double[] za) {
            this.name = name;
            this.x0 = x0;
            this.z0 = z0;
            this.size = size;
            this.n = n;
            this.xa = xa;
            this.za = za;
        }

        public int x1() {
            return x0 + size;
        }

        public int z1() {
            return z0 + size;
        }

        public boolean contains(double x, double z) {
            return x >= x0 && x < x0 + size && z >= z0 && z < z0 + size;
        }
    }

    /** One-way seam: points in the strip beyond {@code face.edge} map onto {@code dest}. */
    public static final class Link {
        public final Face face, dest;
        public final String edge;
        public String destEdge;
        public final int sx0, sx1, sz0, sz1;
        public final int m00, m01, m10, m11;
        public final int t0, t1;
        public final int yaw;

        Link(Face face, String edge, Face dest, int[] strip, int[][] m, int t0, int t1) {
            this.face = face;
            this.edge = edge;
            this.dest = dest;
            this.sx0 = strip[0];
            this.sx1 = strip[1];
            this.sz0 = strip[2];
            this.sz1 = strip[3];
            this.m00 = m[0][0];
            this.m01 = m[0][1];
            this.m10 = m[1][0];
            this.m11 = m[1][1];
            this.t0 = t0;
            this.t1 = t1;
            this.yaw = yawDelta(m01, m11);
        }

        public boolean contains(double x, double z) {
            return x >= sx0 && x < sx1 && z >= sz0 && z < sz1;
        }

        /** How far beyond the linked edge a point in the strip is. */
        public double depth(double x, double z) {
            switch (edge) {
                case "top": return sz1 - z;
                case "bottom": return z - sz0;
                case "left": return sx1 - x;
                default: return x - sx0;
            }
        }

        public double applyX(double x, double z) {
            return m00 * x + m01 * z + t0;
        }

        public double applyZ(double x, double z) {
            return m10 * x + m11 * z + t1;
        }

        /** Rotate a horizontal vector (velocity) by the link's rotation. */
        public double rotX(double vx, double vz) {
            return m00 * vx + m01 * vz;
        }

        public double rotZ(double vx, double vz) {
            return m10 * vx + m11 * vz;
        }

        @Override
        public String toString() {
            return face.name + "." + edge + " -> " + dest.name + "." + destEdge;
        }
    }

    private static int yawDelta(int dx, int dz) {
        // image of (0, 1), the yaw-0 direction (Minecraft yaw 0 faces +z, 90 faces -x)
        if (dx == 0 && dz == 1) return 0;
        if (dx == -1 && dz == 0) return 90;
        if (dx == 0 && dz == -1) return 180;
        if (dx == 1 && dz == 0) return -90;
        throw new IllegalStateException("not a rotation");
    }

    private static final int[][][] ROT = {
        {{1, 0}, {0, 1}},
        {{0, -1}, {1, 0}},
        {{-1, 0}, {0, -1}},
        {{0, 1}, {-1, 0}},
    };

    public static int faceSizeForScale(double metersPerBlock) {
        return (int) Math.max(1024, Math.round(EARTH_CIRCUMFERENCE_M / 4 / metersPerBlock / 1024) * 1024);
    }

    public CubeProjection(double metersPerBlock, double lat0, double lon0, int margin) {
        int S = faceSizeForScale(metersPerBlock);
        int h = S / 2;
        this.size = S;
        this.metersPerBlock = (EARTH_CIRCUMFERENCE_M / 4) / S;
        this.lat0 = lat0;
        this.lon0 = lon0;
        this.margin = margin;
        double la = Math.toRadians(lat0), lo = Math.toRadians(lon0);
        double[] n0 = {Math.cos(la) * Math.cos(lo), Math.cos(la) * Math.sin(lo), Math.sin(la)};
        double[] e0 = {-Math.sin(lo), Math.cos(lo), 0};
        double[] u0 = cross(n0, e0);
        double[] mu0 = scale(u0, -1);
        faces = new Face[] {
            belt("west", -1, -3 * h, S, n0, e0, mu0),
            belt("front", 0, -h, S, n0, e0, mu0),
            belt("east", 1, h, S, n0, e0, mu0),
            belt("back", 2, 3 * h, S, n0, e0, mu0),
            new Face("north", -h, -3 * h, S, u0, e0, n0),
            new Face("south", -h, h, S, mu0, e0, scale(n0, -1)),
        };
        links = deriveLinks();
    }

    private static Face belt(String name, int k, int x0, int S, double[] n0, double[] e0, double[] za) {
        double c = Math.cos(k * Math.PI / 2), s = Math.sin(k * Math.PI / 2);
        double[] n = add(scale(n0, c), scale(e0, s));
        double[] e = add(scale(n0, -s), scale(e0, c));
        return new Face(name, x0, -S / 2, S, n, e, za);
    }

    // ------------------------------------------------------------ geometry

    static double[] cross(double[] a, double[] b) {
        return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    static double[] scale(double[] a, double s) {
        return new double[] {a[0] * s, a[1] * s, a[2] * s};
    }

    static double[] add(double[] a, double[] b) {
        return new double[] {a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    /** Unit vector for a longitude/latitude in degrees. */
    public static double[] lonLatToVec(double lon, double lat) {
        double lo = Math.toRadians(lon), la = Math.toRadians(lat);
        double c = Math.cos(la);
        return new double[] {c * Math.cos(lo), c * Math.sin(lo), Math.sin(la)};
    }

    /** Face-local equi-angular inverse: net (x, z) -> out {lon, lat}. */
    public void faceInverse(Face f, double x, double z, double[] out) {
        double a = 2.0 * (x - f.x0) / f.size - 1.0;
        double b = 2.0 * (z - f.z0) / f.size - 1.0;
        double tu = Math.tan(a * (Math.PI / 4));
        double tw = Math.tan(b * (Math.PI / 4));
        double vx = f.n[0] + tu * f.xa[0] + tw * f.za[0];
        double vy = f.n[1] + tu * f.xa[1] + tw * f.za[1];
        double vz = f.n[2] + tu * f.xa[2] + tw * f.za[2];
        double len = Math.sqrt(vx * vx + vy * vy + vz * vz);
        out[1] = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, vz / len))));
        out[0] = Math.toDegrees(Math.atan2(vy, vx));
    }

    private void faceForward(Face f, double[] v, double[] out) {
        double d = dot(v, f.n);
        double tu = dot(v, f.xa) / d;
        double tw = dot(v, f.za) / d;
        double a = Math.atan(tu) * (4 / Math.PI);
        double b = Math.atan(tw) * (4 / Math.PI);
        out[0] = f.x0 + (a + 1) / 2 * f.size;
        out[1] = f.z0 + (b + 1) / 2 * f.size;
    }

    /** lon/lat (degrees) -> out {x, z} in net (block) coordinates; returns the face. */
    public Face forward(double lon, double lat, double[] out) {
        double[] v = lonLatToVec(lon, lat);
        Face best = faces[0];
        double bd = -2;
        for (Face f : faces) {
            double d = dot(v, f.n);
            if (d > bd) {
                bd = d;
                best = f;
            }
        }
        faceForward(best, v, out);
        return best;
    }

    public Face faceAt(double x, double z) {
        for (Face f : faces) {
            if (f.contains(x, z)) return f;
        }
        return null;
    }

    /** The link whose strip contains the point, nearest seam first; null if none. */
    public Link linkAt(double x, double z) {
        Link best = null;
        double bd = Double.POSITIVE_INFINITY;
        for (Link l : links) {
            if (l.contains(x, z)) {
                double d = l.depth(x, z);
                if (d < bd) {
                    bd = d;
                    best = l;
                }
            }
        }
        return best;
    }

    /**
     * Net coordinates (use block centres) -> out {lon, lat}; returns
     * {@link #OUTSIDE}, {@link #ON_FACE}, {@link #SEAM_MARGIN} (a copy of the
     * other side of a seam) or {@link #CORNER_MARGIN} (continued projection
     * next to a cube vertex).
     */
    public int inverse(double x, double z, double[] out) {
        Face f = faceAt(x, z);
        if (f != null) {
            faceInverse(f, x, z, out);
            return ON_FACE;
        }
        Link l = linkAt(x, z);
        if (l != null) {
            Face d = l.dest;
            double tx = clamp(l.applyX(x, z), d.x0, d.x1() - 1e-6);
            double tz = clamp(l.applyZ(x, z), d.z0, d.z1() - 1e-6);
            faceInverse(d, tx, tz, out);
            return SEAM_MARGIN;
        }
        if (margin > 0) {
            for (Face g : faces) {
                if (x >= g.x0 - margin && x < g.x1() + margin && z >= g.z0 - margin && z < g.z1() + margin) {
                    faceInverse(g, x, z, out);
                    return CORNER_MARGIN;
                }
            }
        }
        out[0] = Double.NaN;
        out[1] = Double.NaN;
        return OUTSIDE;
    }

    static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** Bounds of everything generated, incl. margins: {xmin, xmax, zmin, zmax}. */
    public int[] bounds() {
        int xmin = Integer.MAX_VALUE, xmax = Integer.MIN_VALUE, zmin = Integer.MAX_VALUE, zmax = Integer.MIN_VALUE;
        for (Face f : faces) {
            xmin = Math.min(xmin, f.x0);
            xmax = Math.max(xmax, f.x1());
            zmin = Math.min(zmin, f.z0);
            zmax = Math.max(zmax, f.z1());
        }
        for (Link l : links) {
            xmin = Math.min(xmin, l.sx0);
            xmax = Math.max(xmax, l.sx1);
            zmin = Math.min(zmin, l.sz0);
            zmax = Math.max(zmax, l.sz1);
        }
        return new int[] {xmin, xmax, zmin, zmax};
    }

    /** Shortest distance (blocks) from a point on a face to any of its linked edges. */
    public double distanceToSeam(double x, double z) {
        double best = Double.POSITIVE_INFINITY;
        for (Link l : links) {
            Face f = l.face;
            double along, across;
            switch (l.edge) {
                case "top": across = Math.abs(z - f.z0); along = Math.max(0, Math.max(f.x0 - x, x - f.x1())); break;
                case "bottom": across = Math.abs(z - f.z1()); along = Math.max(0, Math.max(f.x0 - x, x - f.x1())); break;
                case "left": across = Math.abs(x - f.x0); along = Math.max(0, Math.max(f.z0 - z, z - f.z1())); break;
                default: across = Math.abs(x - f.x1()); along = Math.max(0, Math.max(f.z0 - z, z - f.z1())); break;
            }
            best = Math.min(best, Math.hypot(across, along));
        }
        return best;
    }

    // ------------------------------------------------------------ seams

    private int[][] edge(Face f, String e) {
        int m = margin;
        switch (e) {
            case "top": return new int[][] {{f.x0, f.z0}, {f.x1(), f.z0}, {f.x0, f.x1(), f.z0 - m, f.z0}};
            case "bottom": return new int[][] {{f.x0, f.z1()}, {f.x1(), f.z1()}, {f.x0, f.x1(), f.z1(), f.z1() + m}};
            case "left": return new int[][] {{f.x0, f.z0}, {f.x0, f.z1()}, {f.x0 - m, f.x0, f.z0, f.z1()}};
            default: return new int[][] {{f.x1(), f.z0}, {f.x1(), f.z1()}, {f.x1(), f.x1() + m, f.z0, f.z1()}};
        }
    }

    private Link[] deriveLinks() {
        List<Link> out = new ArrayList<>();
        double[] ll = new double[2];
        double[] q = new double[2];
        for (Face f : faces) {
            for (String e : new String[] {"top", "bottom", "left", "right"}) {
                int[][] ed = edge(f, e);
                int[] p1 = ed[0], p2 = ed[1], strip = ed[2];
                double mx = (p1[0] + p2[0]) / 2.0, mz = (p1[1] + p2[1]) / 2.0;
                int ox = e.equals("left") ? -1 : e.equals("right") ? 1 : 0;
                int oz = e.equals("top") ? -1 : e.equals("bottom") ? 1 : 0;
                double px = mx + ox * 0.5, pz = mz + oz * 0.5;
                faceInverse(f, px, pz, ll);
                double[] pv = lonLatToVec(ll[0], ll[1]);
                Face g = null;
                double gd = -2;
                for (Face o : faces) {
                    if (o == f) continue;
                    double d = dot(pv, o.n);
                    if (d > gd) {
                        gd = d;
                        g = o;
                    }
                }
                if (g.contains(px, pz)) continue; // touches in the net
                int[][] qs = new int[2][];
                int i = 0;
                for (int[] p : new int[][] {p1, p2}) {
                    faceInverse(f, p[0], p[1], ll);
                    faceForward(g, lonLatToVec(ll[0], ll[1]), q);
                    qs[i++] = new int[] {(int) Math.round(q[0]), (int) Math.round(q[1])};
                }
                int dx = p2[0] - p1[0], dz = p2[1] - p1[1];
                int qx = qs[1][0] - qs[0][0], qz = qs[1][1] - qs[0][1];
                int[][] M = null;
                for (int[][] r : ROT) {
                    if (r[0][0] * dx + r[0][1] * dz == qx && r[1][0] * dx + r[1][1] * dz == qz) {
                        M = r;
                        break;
                    }
                }
                if (M == null) throw new IllegalStateException("no rotation for " + f.name + "." + e);
                int t0 = qs[0][0] - (M[0][0] * p1[0] + M[0][1] * p1[1]);
                int t1 = qs[0][1] - (M[1][0] * p1[0] + M[1][1] * p1[1]);
                Link link = new Link(f, e, g, strip, M, t0, t1);
                double qmx = link.applyX(mx, mz), qmz = link.applyZ(mx, mz);
                link.destEdge = qmx == g.x0 ? "left" : qmx == g.x1() ? "right" : qmz == g.z0 ? "top" : "bottom";
                if (!g.contains(link.applyX(px, pz), link.applyZ(px, pz))) {
                    throw new IllegalStateException("seam probe missed " + g.name);
                }
                out.add(link);
            }
        }
        return out.toArray(new Link[0]);
    }

    /**
     * Local distortion at a place: {metres per block along the most stretched
     * direction, along the least stretched direction, max shear in degrees}.
     */
    public double[] distortion(double lon, double lat) {
        double h = 1e-4;
        double[] p0 = new double[2], pe = new double[2], pn = new double[2];
        forward(lon, lat, p0);
        forward(lon + h / Math.cos(Math.toRadians(lat)), lat, pe);
        forward(lon, lat + h, pn);
        double m = Math.toRadians(h) * EARTH_MEAN_RADIUS_M;
        double a = (pe[0] - p0[0]) / m, b = (pn[0] - p0[0]) / m, c = (pe[1] - p0[1]) / m, d = (pn[1] - p0[1]) / m;
        // singular values of [[a, b], [c, d]]
        double s1 = a * a + b * b + c * c + d * d;
        double det = Math.abs(a * d - b * c);
        double disc = Math.sqrt(Math.max(0, s1 * s1 / 4 - det * det));
        double big = Math.sqrt(s1 / 2 + disc), small = Math.sqrt(Math.max(0, s1 / 2 - disc));
        double shear = Math.toDegrees(2 * Math.asin((big - small) / (big + small)));
        return new double[] {1 / big, 1 / small, shear};
    }
}
