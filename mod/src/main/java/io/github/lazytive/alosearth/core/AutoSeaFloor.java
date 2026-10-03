package io.github.lazytive.alosearth.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sea-floor depth downloaded on demand, so oceans have real depths without
 * installing GEBCO: the free global elevation tiles on AWS (Mapzen/Tilezen
 * "terrain tiles", whose oceans come from ETOPO1 and GEBCO), zoom 6: 512x512
 * web-Mercator GeoTIFFs of about 250 KB with ~1 km pixels. Each tile is
 * fetched the first time an area needs it and kept in a local folder.
 */
public final class AutoSeaFloor {
    public static final String BASE = "https://s3.amazonaws.com/elevation-tiles-prod/geotiff/";
    static final int ZOOM = 6, SIZE = 512, TILES = 1 << ZOOM, WIDTH = TILES * SIZE;
    private static final long RETRY_MS = 10 * 60 * 1000;
    private static final double MAX_LAT = 85.0511287798;

    private final Path dir;
    private final Rasters.SegmentCache cache;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "ALOS Earth sea floor download");
        t.setDaemon(true);
        return t;
    });
    private final Map<Integer, CompletableFuture<Rasters.Raster>> tiles = new ConcurrentHashMap<>();
    private final Map<Integer, Long> failedAt = new ConcurrentHashMap<>();
    public final AtomicInteger downloaded = new AtomicInteger(), failures = new AtomicInteger();
    public volatile String lastError = "";

    public AutoSeaFloor(Path dir, Rasters.SegmentCache cache) {
        this.dir = dir;
        this.cache = cache;
    }

    /** Elevation in metres (negative under the sea), NaN if the tile can't be had. Blocks while downloading. */
    public double sample(double lon, double lat, boolean smooth) {
        double gx = (Rasters.normLon(lon) + 180) / 360 * WIDTH - 0.5;
        double phi = Math.toRadians(Math.max(-MAX_LAT, Math.min(MAX_LAT, lat)));
        double gy = (1 - Math.log(Math.tan(phi) + 1 / Math.cos(phi)) / Math.PI) / 2 * WIDTH - 0.5;
        int x1 = (int) Math.floor(gx), y1 = (int) Math.floor(gy);
        double tx = gx - x1, ty = gy - y1;
        if (!smooth) {
            double v00 = pixel(x1, y1), v01 = pixel(x1 + 1, y1), v10 = pixel(x1, y1 + 1), v11 = pixel(x1 + 1, y1 + 1);
            return (v00 * (1 - tx) + v01 * tx) * (1 - ty) + (v10 * (1 - tx) + v11 * tx) * ty;
        }
        double[] rows = new double[4], v = new double[4];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) v[j] = pixel(x1 - 1 + j, y1 - 1 + i);
            rows[i] = catmullRom(v, tx);
        }
        return catmullRom(rows, ty);
    }

    private static double catmullRom(double[] p, double t) {
        return 0.5 * (2 * p[1] + (-p[0] + p[2]) * t + (2 * p[0] - 5 * p[1] + 4 * p[2] - p[3]) * t * t
            + (-p[0] + 3 * p[1] - 3 * p[2] + p[3]) * t * t * t);
    }

    /** A pixel of the whole-world mosaic (wrapping east-west, clamped at the poles). */
    private double pixel(int gx, int gy) {
        gx = Math.floorMod(gx, WIDTH);
        gy = Math.max(0, Math.min(WIDTH - 1, gy));
        Rasters.Raster r = tile(gx / SIZE, gy / SIZE);
        if (r == null) return Double.NaN;
        return r.pixel(gy % SIZE, gx % SIZE);
    }

    private Rasters.Raster tile(int x, int y) {
        int key = x * TILES + y;
        Long failed = failedAt.get(key);
        if (failed != null) {
            if (System.currentTimeMillis() - failed < RETRY_MS) return null;
            failedAt.remove(key);
        }
        CompletableFuture<Rasters.Raster> f = tiles.get(key);
        if (f == null) f = tiles.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> load(x, y), pool));
        try {
            return f.join();
        } catch (CompletionException e) {
            tiles.remove(key, f);
            failedAt.put(key, System.currentTimeMillis());
            failures.incrementAndGet();
            lastError = String.valueOf(e.getCause());
            return null;
        }
    }

    private Rasters.Raster load(int x, int y) {
        Path file = dir.resolve(ZOOM + "/" + x + "/" + y + ".tif");
        try {
            if (!Files.exists(file)) {
                long t0 = System.nanoTime();
                URI url = URI.create(BASE + ZOOM + "/" + x + "/" + y + ".tif");
                HttpRequest req = HttpRequest.newBuilder(url).timeout(Duration.ofMinutes(2)).GET().build();
                HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode() + " for " + url);
                byte[] body = resp.body();
                Files.createDirectories(file.getParent());
                Path tmp = file.resolveSibling(y + "." + Thread.currentThread().getId() + "." + System.nanoTime() + ".part");
                Files.write(tmp, body);
                try {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException e) {
                    Files.deleteIfExists(tmp);
                    if (!Files.exists(file)) throw e;
                }
                downloaded.incrementAndGet();
                AutoDem.BYTES_FETCHED.addAndGet(body.length);
                AutoDem.log(String.format("fetched sea floor tile %d/%d/%d (%.2f MB) in %.1f s", ZOOM, x, y,
                    body.length / 1e6, (System.nanoTime() - t0) / 1e9));
            }
            GeoTiff t = new GeoTiff(new GeoTiff.Source(file, null));
            if (t.width != SIZE || t.height != SIZE) throw new IOException("unexpected tile size in " + file);
            return new Rasters.Raster(t, cache);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedIOException(new IOException("interrupted", e));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String describe() {
        return "sea floor auto-download on (" + tiles.size() + " tiles in use)"
            + (failures.get() > 0 ? ", " + failures.get() + " failed (" + lastError + ")" : "");
    }
}
