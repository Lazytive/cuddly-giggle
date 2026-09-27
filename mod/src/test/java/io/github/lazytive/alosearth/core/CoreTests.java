package io.github.lazytive.alosearth.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests for the terrain core. Plain main() so they run with just a JDK:
 * {@code java CoreTests <testdata dir>} (see scripts/core-test.sh). The test
 * data comes from scripts/make_testdata.py, which uses the Python
 * implementation as the reference.
 */
public final class CoreTests {
    static int passed, failed;
    static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path data = Path.of(args.length > 0 ? args[0] : "build/testdata");
        run("projection matches Python (polar)", () -> projectionMatchesPython(data.resolve("projection_polar.txt")));
        run("projection matches Python (tilted)", () -> projectionMatchesPython(data.resolve("projection_tilted.txt")));
        run("seams land on the same place", CoreTests::seamsAreConsistent);
        run("rasters match Python samples", () -> rastersMatchPython(data));
        run("LZW round trip", CoreTests::lzwRoundTrip);
        TerrainTests.register(data);
        for (String f : failures) System.out.println("FAIL " + f);
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    interface Body {
        void run() throws Exception;
    }

    static void run(String name, Body body) {
        long t = System.nanoTime();
        try {
            body.run();
            passed++;
            System.out.printf("ok   %-50s %6.0f ms%n", name, (System.nanoTime() - t) / 1e6);
        } catch (Throwable e) {
            failed++;
            failures.add(name + ": " + e);
            System.out.printf("FAIL %-50s %s%n", name, e);
            e.printStackTrace(System.out);
        }
    }

    static void check(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
    }

    static double angleDiff(double a, double b) {
        double d = Math.abs(a - b) % 360;
        return d > 180 ? 360 - d : d;
    }

    static void projectionMatchesPython(Path fixture) throws IOException {
        List<String> lines = Files.readAllLines(fixture);
        String[] cfg = lines.get(0).split(" ");
        CubeProjection p = new CubeProjection(30.0, Double.parseDouble(cfg[1]), Double.parseDouble(cfg[2]), 512);
        check(p.size == Integer.parseInt(cfg[3]), "face size " + p.size);
        int li = 0, ninv = 0, nfwd = 0;
        double[] out = new double[2];
        for (String line : lines) {
            String[] s = line.split(" ");
            switch (s[0]) {
                case "link": {
                    CubeProjection.Link l = p.links[li++];
                    String got = l.face.name + " " + l.edge + " " + l.dest.name + " " + l.destEdge + " " + l.sx0 + " "
                        + l.sx1 + " " + l.sz0 + " " + l.sz1 + " " + l.m00 + " " + l.m01 + " " + l.m10 + " " + l.m11
                        + " " + l.t0 + " " + l.t1 + " " + l.yaw;
                    check(line.equals("link " + got), "link mismatch:\n  py   " + line + "\n  java link " + got);
                    break;
                }
                case "inv": {
                    double x = Double.parseDouble(s[1]), z = Double.parseDouble(s[2]);
                    int kind = p.inverse(x, z, out);
                    check(kind == Integer.parseInt(s[3]), "kind at " + x + "," + z + ": " + kind + " vs " + s[3]);
                    if (kind != 0) {
                        double lo = Double.parseDouble(s[4]), la = Double.parseDouble(s[5]);
                        check(Math.abs(out[1] - la) < 1e-9 && angleDiff(out[0], lo) * Math.cos(Math.toRadians(la)) < 1e-9,
                              "inverse at " + x + "," + z + ": " + out[0] + "," + out[1] + " vs " + lo + "," + la);
                    }
                    ninv++;
                    break;
                }
                case "fwd": {
                    p.forward(Double.parseDouble(s[1]), Double.parseDouble(s[2]), out);
                    check(Math.abs(out[0] - Double.parseDouble(s[3])) < 1e-6 && Math.abs(out[1] - Double.parseDouble(s[4])) < 1e-6,
                          "forward " + line);
                    nfwd++;
                    break;
                }
                default:
            }
        }
        check(li == p.links.length && li == 14, "links " + li);
        check(ninv > 3000 && nfwd == 2000, "fixture too small");
    }

    static void rastersMatchPython(Path data) throws IOException {
        Rasters.SegmentCache cache = new Rasters.SegmentCache(64L << 20);
        Path d = data.resolve("data");
        Rasters.Aw3d30 aw = new Rasters.Aw3d30(List.of(d.resolve("aw3d30")), cache);
        check(aw.tileCount() == 2 && aw.hasTile(35, 139) && aw.hasTile(35, 138), "aw3d30 tiles " + aw.tileCount());
        Rasters.RasterSet gebco = new Rasters.RasterSet(List.of(d.resolve("gebco.tif")), cache);
        Rasters.RasterSet koppen = new Rasters.RasterSet(List.of(d.resolve("climate")), cache);
        Rasters.RasterSet flt = new Rasters.RasterSet(List.of(d.resolve("float.tif")), cache);
        double[] o = new double[2];
        int n = 0;
        for (String line : Files.readAllLines(data.resolve("rasters.txt"))) {
            String[] s = line.split(" ");
            double lon = Double.parseDouble(s[1]), lat = Double.parseDouble(s[2]), want = Double.parseDouble(s[3]);
            double got;
            switch (s[0]) {
                case "aw3d30" -> {
                    aw.sample(lon, lat, o);
                    got = o[0];
                    check((int) o[1] == Integer.parseInt(s[4]), "aw3d30 class at " + lon + "," + lat + ": " + o[1] + " vs " + s[4]);
                }
                case "gebco" -> got = gebco.bilinear(lon, lat);
                case "koppen" -> got = koppen.nearest(lon, lat);
                default -> got = flt.nearest(lon, lat);
            }
            boolean same = Double.isNaN(want) ? Double.isNaN(got) : Math.abs(got - want) <= 1e-3 * Math.max(1, Math.abs(want));
            check(same, s[0] + " at " + lon + "," + lat + ": " + got + " vs " + want);
            n++;
        }
        check(n == 11000, "samples " + n);
    }

    static void lzwRoundTrip() {
        // encode with a straightforward TIFF LZW encoder, decode with ours
        java.util.Random r = new java.util.Random(5);
        for (int trial = 0; trial < 20; trial++) {
            byte[] src = new byte[1 + r.nextInt(20000)];
            int alphabet = 1 + r.nextInt(trial % 2 == 0 ? 4 : 256);
            for (int i = 0; i < src.length; i++) src[i] = (byte) (r.nextInt(alphabet) * (256 / alphabet));
            byte[] enc = LzwEncoder.encode(src);
            byte[] dec = GeoTiff.lzw(java.nio.ByteBuffer.wrap(enc), src.length);
            check(java.util.Arrays.equals(src, dec), "LZW mismatch, trial " + trial);
        }
    }

    static void seamsAreConsistent() {
        CubeProjection p = new CubeProjection(30.0, 0, 24, 512);
        java.util.Random r = new java.util.Random(1);
        double[] a = new double[2], b = new double[2];
        for (CubeProjection.Link l : p.links) {
            for (int i = 0; i < 500; i++) {
                double x = l.sx0 + r.nextDouble() * (l.sx1 - l.sx0);
                double z = l.sz0 + r.nextDouble() * (l.sz1 - l.sz0);
                if (p.linkAt(x, z) != l) continue; // vertex overlap: another seam is nearer
                int k = p.inverse(x, z, a);
                check(k == CubeProjection.SEAM_MARGIN, "not margin");
                double tx = l.applyX(x, z), tz = l.applyZ(x, z);
                check(l.dest.contains(tx, tz), "lands off face " + l);
                check(p.inverse(tx, tz, b) == CubeProjection.ON_FACE, "dest kind");
                check(a[0] == b[0] && a[1] == b[1], "margin differs from destination at " + l);
            }
        }
    }
}
