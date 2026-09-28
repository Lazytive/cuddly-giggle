package io.github.lazytive.alosearth.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Rivers and streams worked out from the elevation data: for each small
 * region (0.05 degrees, with the same again around it for context) the
 * elevation is sampled every arc-second (~30 m), depressions are filled
 * (priority-flood), every cell drains to the neighbour that reached it, and
 * the area draining through each cell is added up. Cells with enough area
 * upstream form a channel; the channel is kept as short line segments
 * (through the midpoints of each step, which rounds the 45-degree corners)
 * with the drained area and the water level (the filled elevation) at each.
 * The terrain then asks for the nearest channel to each column.
 */
public final class Rivers {
    /** Elevation in metres for the flow model; NaN is sea (water leaves there). */
    public interface Elevation {
        double at(double lon, double lat);
    }

    static final double CORE = 0.05, PAD = 0.05, RES = 1.0 / 3600;
    private static final int MAX_REGIONS = 96, BUCKET = 8;

    private final Elevation dem;
    /** Smallest drained area (km^2) drawn as a stream. */
    public final double minAreaKm2;
    private final Map<Long, CompletableFuture<Region>> regions = new LinkedHashMap<>(64, 0.75f, true);

    public Rivers(Elevation dem, double minAreaKm2) {
        this.dem = dem;
        this.minAreaKm2 = minAreaKm2;
    }

    /** The nearest channel to a point. */
    public record Hit(double distanceM, double areaKm2, double waterM) {
    }

    /** Nearest channel within {@code radiusM} metres, or null. */
    public Hit nearest(double lon, double lat, double radiusM) {
        lon = Rasters.normLon(lon);
        int ry = (int) Math.floor(lat / CORE), rx = (int) Math.floor(lon / CORE);
        Hit best = null;
        double dLat = radiusM / 111320.0, dLon = dLat / Math.max(0.05, Math.cos(Math.toRadians(lat)));
        int ry0 = (int) Math.floor((lat - dLat) / CORE), ry1 = (int) Math.floor((lat + dLat) / CORE);
        int rx0 = (int) Math.floor((lon - dLon) / CORE), rx1 = (int) Math.floor((lon + dLon) / CORE);
        if (ry0 < ry - 1 || ry1 > ry + 1 || rx0 < rx - 1 || rx1 > rx + 1) return null; // radius too big for this scheme
        for (int y = ry0; y <= ry1; y++) {
            for (int x = rx0; x <= rx1; x++) {
                Region r = region(x, y);
                if (r == null) continue;
                Hit h = r.nearest(lon, lat, radiusM);
                if (h != null && (best == null || h.distanceM < best.distanceM)) best = h;
            }
        }
        return best;
    }

    private Region region(int rx, int ry) {
        if (ry * CORE >= 84 || ry * CORE < -84) return null; // no rivers near the poles (ice)
        long key = ((long) rx << 32) ^ (ry & 0xffffffffL);
        CompletableFuture<Region> f, mine = null;
        synchronized (regions) {
            f = regions.get(key);
            if (f == null) {
                f = mine = new CompletableFuture<>();
                regions.put(key, f);
                if (regions.size() > MAX_REGIONS) {
                    var it = regions.entrySet().iterator();
                    it.next();
                    it.remove();
                }
            }
        }
        if (mine != null) {
            try {
                mine.complete(build(rx, ry));
            } catch (RuntimeException e) {
                mine.completeExceptionally(e);
                synchronized (regions) {
                    regions.remove(key, mine);
                }
            }
        }
        try {
            return f.join();
        } catch (CompletionException e) {
            return null;
        }
    }

    // ------------------------------------------------------------ one region

    private static final int[] DX = {1, 1, 0, -1, -1, -1, 0, 1}, DY = {0, 1, 1, 1, 0, -1, -1, -1};

    private Region build(int rx, int ry) {
        double latS = ry * CORE - PAD, latN = (ry + 1) * CORE + PAD;
        double coreLon = rx * CORE, latMid = (ry + 0.5) * CORE;
        double cos = Math.max(0.1, Math.cos(Math.toRadians(latMid)));
        double dLat = RES, dLon = RES / cos; // square-ish cells
        int ny = (int) Math.round((latN - latS) / dLat);
        int coreCols = (int) Math.max(8, Math.round(CORE / dLon)), padCols = (int) Math.round(PAD / dLon);
        dLon = CORE / coreCols;
        int nx = coreCols + 2 * padCols;
        double lonW = coreLon - padCols * dLon;
        int nn = nx * ny;
        float[] e = new float[nn];
        boolean anyLand = false;
        for (int y = 0; y < ny; y++) {
            double lat = latN - (y + 0.5) * dLat;
            for (int x = 0; x < nx; x++) {
                double v = dem.at(lonW + (x + 0.5) * dLon, lat);
                e[y * nx + x] = (float) v;
                if (!Double.isNaN(v)) anyLand = true;
            }
        }
        Region r = new Region(lonW, latN, dLon, dLat, nx, ny, padCols, (int) Math.round(PAD / dLat), cos);
        if (!anyLand) return r;

        // priority-flood: fill depressions; each cell drains to the cell it was reached from
        int[] recv = new int[nn];
        float[] filled = new float[nn];
        boolean[] done = new boolean[nn];
        int[] order = new int[nn];
        int count = 0;
        Heap heap = new Heap(nn);
        for (int i = 0; i < nn; i++) {
            recv[i] = -1;
            int x = i % nx, y = i / nx;
            if (Float.isNaN(e[i])) { // sea: an outlet
                done[i] = true;
                filled[i] = 0;
                continue;
            }
            if (x == 0 || y == 0 || x == nx - 1 || y == ny - 1 || nextToSea(e, nx, ny, x, y)) {
                done[i] = true;
                filled[i] = e[i];
                heap.push(i, e[i]);
            }
        }
        while (heap.size > 0) {
            int c = heap.pop();
            order[count++] = c;
            int cx = c % nx, cy = c / nx;
            for (int k = 0; k < 8; k++) {
                int x = cx + DX[k], y = cy + DY[k];
                if (x < 0 || y < 0 || x >= nx || y >= ny) continue;
                int j = y * nx + x;
                if (done[j]) continue;
                done[j] = true;
                filled[j] = Math.max(e[j], Math.nextUp(filled[c]));
                recv[j] = c;
                heap.push(j, filled[j]);
            }
        }
        // drained area, adding each cell to its receiver, highest cells first
        float[] area = new float[nn];
        for (int y = 0; y < ny; y++) {
            double lat = latN - (y + 0.5) * dLat;
            float a = (float) (dLat * 111.32 * dLon * 111.32 * Math.cos(Math.toRadians(lat))); // km^2
            for (int x = 0; x < nx; x++) area[y * nx + x] = a;
        }
        for (int k = count - 1; k >= 0; k--) {
            int c = order[k];
            if (recv[c] >= 0) area[recv[c]] += area[c];
        }
        // channel segments for the core cells
        boolean[] hasUp = new boolean[nn];
        for (int c = 0; c < nn; c++) if (recv[c] >= 0 && area[c] >= minAreaKm2) hasUp[recv[c]] = true;
        for (int y = r.padRows; y < ny - r.padRows; y++) {
            for (int x = padCols; x < nx - padCols; x++) {
                int c = y * nx + x;
                if (area[c] < minAreaKm2 || Float.isNaN(e[c])) continue;
                int d = recv[c];
                if (d < 0) continue;
                float mx = (x + d % nx) / 2f, my = (y + d / nx) / 2f;
                float wc = filled[c], wd = filled[d];
                if (!hasUp[c]) r.add(x, y, mx, my, area[c], wc, (wc + wd) / 2); // a stream's source
                int dd = recv[d];
                if (dd >= 0 && !Float.isNaN(e[d])) {
                    float nx2 = (d % nx + dd % nx) / 2f, ny2 = (d / nx + dd / nx) / 2f;
                    r.add(mx, my, nx2, ny2, area[d], (wc + wd) / 2, (wd + filled[dd]) / 2);
                } else {
                    r.add(mx, my, d % nx, d / nx, area[d], (wc + wd) / 2, wd);
                }
            }
        }
        return r;
    }

    private static boolean nextToSea(float[] e, int nx, int ny, int x, int y) {
        for (int k = 0; k < 8; k++) {
            int xx = x + DX[k], yy = y + DY[k];
            if (xx >= 0 && yy >= 0 && xx < nx && yy < ny && Float.isNaN(e[yy * nx + xx])) return true;
        }
        return false;
    }

    /** Binary min-heap of cell indices by float key. */
    private static final class Heap {
        final int[] idx;
        final float[] key;
        int size;

        Heap(int cap) {
            idx = new int[cap];
            key = new float[cap];
        }

        void push(int i, float k) {
            int p = size++;
            while (p > 0) {
                int q = (p - 1) >> 1;
                if (key[q] <= k) break;
                idx[p] = idx[q];
                key[p] = key[q];
                p = q;
            }
            idx[p] = i;
            key[p] = k;
        }

        int pop() {
            int top = idx[0];
            int li = idx[--size];
            float lk = key[size];
            int p = 0;
            while (true) {
                int c = 2 * p + 1;
                if (c >= size) break;
                if (c + 1 < size && key[c + 1] < key[c]) c++;
                if (key[c] >= lk) break;
                idx[p] = idx[c];
                key[p] = key[c];
                p = c;
            }
            idx[p] = li;
            key[p] = lk;
            return top;
        }
    }

    /** One region's channel segments, in cell coordinates, bucketed for lookups. */
    static final class Region {
        final double lonW, latN, dLon, dLat, cos;
        final int nx, ny, padCols, padRows, bx, by;
        final List<float[]> segs = new ArrayList<>();
        final List<List<Integer>> buckets = new ArrayList<>();

        Region(double lonW, double latN, double dLon, double dLat, int nx, int ny, int padCols, int padRows, double cos) {
            this.lonW = lonW;
            this.latN = latN;
            this.dLon = dLon;
            this.dLat = dLat;
            this.nx = nx;
            this.ny = ny;
            this.padCols = padCols;
            this.padRows = padRows;
            this.cos = cos;
            bx = nx / BUCKET + 1;
            by = ny / BUCKET + 1;
            for (int i = 0; i < bx * by; i++) buckets.add(null);
        }

        void add(float x0, float y0, float x1, float y1, float areaKm2, float w0, float w1) {
            int id = segs.size();
            segs.add(new float[] {x0, y0, x1, y1, areaKm2, w0, w1});
            int bx0 = (int) (Math.min(x0, x1) / BUCKET), bx1 = (int) (Math.max(x0, x1) / BUCKET);
            int by0 = (int) (Math.min(y0, y1) / BUCKET), by1 = (int) (Math.max(y0, y1) / BUCKET);
            for (int y = by0; y <= by1; y++) {
                for (int x = bx0; x <= bx1; x++) {
                    int b = y * bx + x;
                    if (buckets.get(b) == null) buckets.set(b, new ArrayList<>(4));
                    buckets.get(b).add(id);
                }
            }
        }

        Hit nearest(double lon, double lat, double radiusM) {
            if (segs.isEmpty()) return null;
            double cx = (lon - lonW) / dLon - 0.5, cy = (latN - lat) / dLat - 0.5;
            double mx = dLon * 111320 * cos, my = dLat * 111320; // metres per cell
            int rb = (int) Math.ceil(radiusM / Math.min(mx, my) / BUCKET) + 1;
            int bcx = (int) Math.floor(cx / BUCKET), bcy = (int) Math.floor(cy / BUCKET);
            double best = radiusM * radiusM;
            float[] hit = null;
            double hitT = 0;
            for (int y = bcy - rb; y <= bcy + rb; y++) {
                if (y < 0 || y >= by) continue;
                for (int x = bcx - rb; x <= bcx + rb; x++) {
                    if (x < 0 || x >= bx) continue;
                    List<Integer> l = buckets.get(y * bx + x);
                    if (l == null) continue;
                    for (int id : l) {
                        float[] s = segs.get(id);
                        double ax = s[0] * mx, ay = s[1] * my, ex = (s[2] - s[0]) * mx, ey = (s[3] - s[1]) * my;
                        double px = cx * mx - ax, py = cy * my - ay;
                        double len2 = ex * ex + ey * ey;
                        double t = len2 > 0 ? Math.max(0, Math.min(1, (px * ex + py * ey) / len2)) : 0;
                        double qx = px - t * ex, qy = py - t * ey, d2 = qx * qx + qy * qy;
                        if (d2 < best) {
                            best = d2;
                            hit = s;
                            hitT = t;
                        }
                    }
                }
            }
            if (hit == null) return null;
            return new Hit(Math.sqrt(best), hit[4], hit[5] + (hit[6] - hit[5]) * hitT);
        }
    }
}
