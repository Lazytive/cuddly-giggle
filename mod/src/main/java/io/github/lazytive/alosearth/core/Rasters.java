package io.github.lazytive.alosearth.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Sampling of the input rasters (AW3D30 tiles, GEBCO, climate). Thread-safe. */
public final class Rasters {
    private Rasters() {
    }

    public static final int CLS_LAND = 0, CLS_SEA = 1, CLS_LAKE = 2, CLS_UNKNOWN = 255;

    /** Least-recently-used cache of decoded segments, bounded by bytes. */
    public static final class SegmentCache {
        private record Key(GeoTiff tiff, int idx) {
        }

        private final long maxBytes;
        private long bytes;
        private final LinkedHashMap<Key, GeoTiff.Segment> map = new LinkedHashMap<>(256, 0.75f, true);

        public SegmentCache(long maxBytes) {
            this.maxBytes = maxBytes;
        }

        private final Map<Key, java.util.concurrent.CompletableFuture<GeoTiff.Segment>> loading =
            new java.util.concurrent.ConcurrentHashMap<>();

        public GeoTiff.Segment get(GeoTiff t, int idx) {
            Key k = new Key(t, idx);
            synchronized (this) {
                GeoTiff.Segment s = map.get(k);
                if (s != null) return s;
            }
            // one thread decodes (and maybe downloads) a segment; others wait for it
            var mine = new java.util.concurrent.CompletableFuture<GeoTiff.Segment>();
            var running = loading.putIfAbsent(k, mine);
            if (running != null) {
                try {
                    return running.join();
                } catch (java.util.concurrent.CompletionException e) {
                    throw e.getCause() instanceof RuntimeException re ? re : new UncheckedIOException(
                        e.getCause() instanceof IOException io ? io : new IOException(e.getCause()));
                }
            }
            GeoTiff.Segment s;
            try {
                s = t.decode(idx);
                mine.complete(s);
            } catch (IOException e) {
                mine.completeExceptionally(e);
                throw new UncheckedIOException(e);
            } catch (RuntimeException e) {
                mine.completeExceptionally(e);
                throw e;
            } finally {
                loading.remove(k, mine);
            }
            synchronized (this) {
                if (map.put(k, s) == null) bytes += s.bytes();
                var it = map.entrySet().iterator();
                while (bytes > maxBytes && map.size() > 1 && it.hasNext()) {
                    var e = it.next();
                    bytes -= e.getValue().bytes();
                    it.remove();
                }
            }
            return s;
        }

        public synchronized void forget(GeoTiff t) {
            var it = map.entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                if (e.getKey().tiff == t) {
                    bytes -= e.getValue().bytes();
                    it.remove();
                }
            }
        }
    }

    /** One georeferenced raster with pixel access through the cache. */
    public static final class Raster {
        public final GeoTiff tiff;
        private final SegmentCache cache;

        Raster(GeoTiff tiff, SegmentCache cache) {
            this.tiff = tiff;
            this.cache = cache;
        }

        public double pixel(int r, int c) {
            GeoTiff t = tiff;
            int idx = t.segmentIndex(r, c);
            GeoTiff.Segment s = cache.get(t, idx);
            return s.get(r % t.segHeight, c % t.segWidth);
        }

        public double row(double lat) {
            return (tiff.y0 - lat) / tiff.dy;
        }

        public double col(double lon) {
            return (lon - tiff.x0) / tiff.dx;
        }

        public boolean covers(double lon, double lat) {
            double[] b = tiff.bounds();
            return lon >= b[0] && lon <= b[2] && lat >= b[1] && lat <= b[3];
        }

        /** Nearest pixel (NaN on nodata). */
        public double nearest(double lon, double lat) {
            int r = (int) clamp(Math.rint(row(lat)), 0, tiff.height - 1);
            int c = (int) clamp(Math.rint(col(lon)), 0, tiff.width - 1);
            double v = pixel(r, c);
            return v == tiff.nodata ? Double.NaN : v;
        }

        public int nearestRaw(double lon, double lat) {
            int r = (int) clamp(Math.rint(row(lat)), 0, tiff.height - 1);
            int c = (int) clamp(Math.rint(col(lon)), 0, tiff.width - 1);
            return (int) pixel(r, c);
        }

        /**
         * Catmull-Rom bicubic: smooth when one data pixel spans many blocks
         * (1:1 worlds). Falls back to bilinear next to nodata.
         */
        public double cubic(double lon, double lat, double nodata) {
            int h = tiff.height, w = tiff.width;
            double fr = clamp(row(lat), 0, h - 1), fc = clamp(col(lon), 0, w - 1);
            int r1 = (int) Math.floor(fr), c1 = (int) Math.floor(fc);
            double tr = fr - r1, tc = fc - c1;
            double[] rows = new double[4];
            for (int i = 0; i < 4; i++) {
                int r = Math.max(0, Math.min(h - 1, r1 - 1 + i));
                double[] v = new double[4];
                for (int j = 0; j < 4; j++) {
                    int c = Math.max(0, Math.min(w - 1, c1 - 1 + j));
                    v[j] = pixel(r, c);
                    if (v[j] == nodata) return bilinear(lon, lat, nodata);
                }
                rows[i] = catmullRom(v, tc);
            }
            return catmullRom(rows, tr);
        }

        private static double catmullRom(double[] p, double t) {
            return 0.5 * (2 * p[1] + (-p[0] + p[2]) * t + (2 * p[0] - 5 * p[1] + 4 * p[2] - p[3]) * t * t
                + (-p[0] + 3 * p[1] - 3 * p[2] + p[3]) * t * t * t);
        }

        /**
         * AW3D30 mask class with smooth borders: each class's share of the four
         * surrounding pixels, weighted bilinearly; the largest share wins.
         */
        int smoothClass(double lon, double lat) {
            int h = tiff.height, w = tiff.width;
            double fr = clamp(row(lat), 0, h - 1), fc = clamp(col(lon), 0, w - 1);
            int r0 = (int) Math.floor(fr), c0 = (int) Math.floor(fc);
            int r1 = Math.min(r0 + 1, h - 1), c1 = Math.min(c0 + 1, w - 1);
            double tr = fr - r0, tc = fc - c0;
            double[] share = new double[4];
            share[(int) pixel(r0, c0) & 3] += (1 - tr) * (1 - tc);
            share[(int) pixel(r0, c1) & 3] += (1 - tr) * tc;
            share[(int) pixel(r1, c0) & 3] += tr * (1 - tc);
            share[(int) pixel(r1, c1) & 3] += tr * tc;
            double land = share[0] + share[1];
            if (share[3] >= land && share[3] >= share[2]) return 3;
            if (share[2] >= land) return 2;
            return 0;
        }

        /** Bilinear with nearest-neighbour fallback next to nodata (NaN if that is nodata too). */
        public double bilinear(double lon, double lat, double nodata) {
            int h = tiff.height, w = tiff.width;
            double fr = clamp(row(lat), 0, h - 1), fc = clamp(col(lon), 0, w - 1);
            int r0 = Math.min((int) Math.floor(fr), h > 1 ? h - 2 : 0);
            int c0 = Math.min((int) Math.floor(fc), w > 1 ? w - 2 : 0);
            int r1 = Math.min(r0 + 1, h - 1), c1 = Math.min(c0 + 1, w - 1);
            float tr = (float) (fr - r0), tc = (float) (fc - c0);
            float v00 = (float) pixel(r0, c0), v01 = (float) pixel(r0, c1);
            float v10 = (float) pixel(r1, c0), v11 = (float) pixel(r1, c1);
            if (v00 == nodata || v01 == nodata || v10 == nodata || v11 == nodata) {
                double near = pixel((int) Math.rint(fr), (int) Math.rint(fc));
                return near == nodata ? Double.NaN : near;
            }
            return (v00 * (1 - tc) + v01 * tc) * (1 - tr) + (v10 * (1 - tc) + v11 * tc) * tr;
        }
    }

    static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    static double normLon(double lon) {
        double l = (lon + 180.0) % 360.0;
        if (l < 0) l += 360.0;
        return l - 180.0;
    }

    private static List<Path> listFiles(Path root) {
        if (!Files.exists(root)) return List.of();
        if (!Files.isDirectory(root)) return List.of(root);
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Keeps a bounded number of GeoTiffs open (zip entries are held in memory). */
    static final class OpenFiles {
        private final int max;
        private final SegmentCache cache;
        private final LinkedHashMap<GeoTiff.Source, GeoTiff> open = new LinkedHashMap<>(64, 0.75f, true);

        OpenFiles(int max, SegmentCache cache) {
            this.max = max;
            this.cache = cache;
        }

        synchronized GeoTiff get(GeoTiff.Source src) throws IOException {
            GeoTiff t = open.get(src);
            if (t != null) return t;
            t = new GeoTiff(src);
            open.put(src, t);
            var it = open.entrySet().iterator();
            while (open.size() > max && it.hasNext()) {
                var e = it.next();
                // dropped, not closed: another thread may still be sampling it
                cache.forget(e.getValue());
                it.remove();
            }
            return t;
        }
    }

    /** ALOS AW3D30 1x1 degree DSM/MSK tiles found in folders and zip files. */
    public static final class Aw3d30 {
        private static final Pattern TILE = Pattern.compile("([NS])(\\d{2,3})([EW])(\\d{3})(?:_AVE)?_(DSM|MSK)\\.tiff?$",
            Pattern.CASE_INSENSITIVE);
        final Map<Integer, GeoTiff.Source> dsm = new HashMap<>(), msk = new HashMap<>();
        private final SegmentCache cache;
        private final OpenFiles files;

        public Aw3d30(List<Path> roots, SegmentCache cache) {
            this.cache = cache;
            this.files = new OpenFiles(24, cache);
            for (Path root : roots) {
                for (Path p : listFiles(root)) {
                    String name = p.getFileName().toString();
                    if (name.toLowerCase().endsWith(".zip")) {
                        try (ZipFile z = new ZipFile(p.toFile())) {
                            Enumeration<? extends ZipEntry> en = z.entries();
                            while (en.hasMoreElements()) {
                                String e = en.nextElement().getName();
                                add(e, new GeoTiff.Source(p, e));
                            }
                        } catch (IOException ignored) {
                            // not a readable zip: skip it
                        }
                    } else {
                        add(name, new GeoTiff.Source(p, null));
                    }
                }
            }
        }

        static int key(int lat, int lon) {
            return (lat + 90) * 360 + (lon + 180);
        }

        private void add(String name, GeoTiff.Source src) {
            String base = name.substring(name.lastIndexOf('/') + 1);
            Matcher m = TILE.matcher(base);
            if (!m.find()) return;
            int lat = Integer.parseInt(m.group(2)) * (m.group(1).equalsIgnoreCase("N") ? 1 : -1);
            int lon = Integer.parseInt(m.group(4)) * (m.group(3).equalsIgnoreCase("E") ? 1 : -1);
            (m.group(5).equalsIgnoreCase("DSM") ? dsm : msk).put(key(lat, lon), src);
        }

        public int tileCount() {
            return dsm.size();
        }

        public boolean hasTile(int lat, int lon) {
            return dsm.containsKey(key(lat, lon));
        }

        /** Writes {elevation metres (NaN if none), class} into out. */
        public void sample(double lon, double lat, double[] out) {
            sample(lon, lat, out, false);
        }

        /** As {@link #sample(double, double, double[])}; {@code smooth} for fine scales (bicubic, soft mask). */
        public void sample(double lon, double lat, double[] out, boolean smooth) {
            lon = normLon(lon);
            int k = key((int) Math.floor(lat), (int) Math.floor(lon));
            GeoTiff.Source ds = dsm.get(k);
            out[0] = Double.NaN;
            out[1] = CLS_UNKNOWN;
            if (ds == null) return;
            try {
                Raster d = new Raster(files.get(ds), cache);
                double nodata = Double.isNaN(d.tiff.nodata) ? -9999 : d.tiff.nodata;
                double e = smooth ? d.cubic(lon, lat, nodata) : d.bilinear(lon, lat, nodata);
                if (e < -1000) e = Double.NaN;
                out[0] = e;
                GeoTiff.Source ms = msk.get(k);
                if (ms != null) {
                    Raster m = new Raster(files.get(ms), cache);
                    if (m.tiff.width == d.tiff.width && m.tiff.height == d.tiff.height) {
                        int c = smooth ? m.smoothClass(lon, lat) : m.nearestRaw(lon, lat) & 3;
                        out[1] = c == 3 ? CLS_SEA : c == 2 ? CLS_LAKE : CLS_LAND;
                        return;
                    }
                }
                out[1] = e <= 0 ? CLS_SEA : CLS_LAND;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** A set of lon/lat GeoTIFFs; the first file covering a point wins. Indexed by 1-degree cell. */
    public static final class RasterSet {
        private final List<Raster> rasters = new ArrayList<>();
        private final Map<Integer, List<Raster>> cells = new HashMap<>();

        public RasterSet(List<Path> roots, SegmentCache cache) {
            for (Path root : roots) {
                for (Path p : listFiles(root)) {
                    String n = p.getFileName().toString().toLowerCase();
                    if (n.endsWith(".tif") || n.endsWith(".tiff")) {
                        try {
                            rasters.add(new Raster(new GeoTiff(new GeoTiff.Source(p, null)), cache));
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }
                }
            }
            for (Raster r : rasters) {
                double[] b = r.tiff.bounds();
                int la0 = (int) Math.floor(Math.max(-90, b[1])), la1 = (int) Math.floor(Math.min(89.999999, b[3]));
                int lo0 = (int) Math.floor(Math.max(-180, b[0])), lo1 = (int) Math.floor(Math.min(179.999999, b[2]));
                for (int la = la0; la <= la1; la++) {
                    for (int lo = lo0; lo <= lo1; lo++) {
                        cells.computeIfAbsent(Aw3d30.key(la, lo), k -> new ArrayList<>()).add(r);
                    }
                }
            }
        }

        public boolean isEmpty() {
            return rasters.isEmpty();
        }

        public int size() {
            return rasters.size();
        }

        private List<Raster> candidates(double lon, double lat) {
            int la = (int) Math.floor(Math.max(-90, Math.min(89.999999, lat)));
            int lo = (int) Math.floor(Math.max(-180, Math.min(179.999999, lon)));
            return cells.getOrDefault(Aw3d30.key(la, lo), List.of());
        }

        public double bilinear(double lon, double lat) {
            return sample(lon, lat, false);
        }

        public double sample(double lon, double lat, boolean smooth) {
            lon = normLon(lon);
            for (Raster r : candidates(lon, lat)) {
                if (r.covers(lon, lat)) return smooth ? r.cubic(lon, lat, r.tiff.nodata) : r.bilinear(lon, lat, r.tiff.nodata);
            }
            return Double.NaN;
        }

        public double nearest(double lon, double lat) {
            lon = normLon(lon);
            for (Raster r : candidates(lon, lat)) {
                if (r.covers(lon, lat)) return r.nearest(lon, lat);
            }
            return Double.NaN;
        }
    }
}
