package io.github.lazytive.alosearth.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Elevation downloaded on demand: the Copernicus GLO-30 DEM (30 m, free,
 * openly hosted on AWS as cloud-optimised GeoTIFFs). Only the ~2 MB blocks
 * of each 1x1 degree tile that an area needs are fetched, the first time it
 * is generated, and kept in a local folder. Used wherever no
 * AW3D30 or fill-DEM tile is installed, so a new world has land straight
 * away. Tiles over open ocean do not exist and are remembered as such.
 */
public final class AutoDem {
    public static final String BASE = "https://copernicus-dem-30m.s3.amazonaws.com/";
    private static final long RETRY_MS = 10 * 60 * 1000;

    private final Path dir;
    private final Rasters.SegmentCache cache;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "ALOS Earth download");
        t.setDaemon(true);
        return t;
    });
    private final Map<Integer, CompletableFuture<Rasters.Raster>> tiles = new ConcurrentHashMap<>();
    private final Map<Integer, Long> failedAt = new ConcurrentHashMap<>();
    public final AtomicInteger downloaded = new AtomicInteger(), missing = new AtomicInteger(), failures = new AtomicInteger();
    public volatile String lastError = "";

    public AutoDem(Path dir, Rasters.SegmentCache cache) {
        this.dir = dir;
        this.cache = cache;
    }

    public static String tileName(int lat, int lon) {
        return String.format("Copernicus_DSM_COG_10_%s%02d_00_%s%03d_00_DEM", lat >= 0 ? "N" : "S", Math.abs(lat),
            lon >= 0 ? "E" : "W", Math.abs(lon));
    }

    /** Bilinear elevation in metres, NaN over open ocean or if the tile can't be had. Blocks while downloading. */
    public double bilinear(double lon, double lat) {
        return sample(lon, lat, false);
    }

    /** Elevation; {@code smooth} uses bicubic interpolation (for fine scales). */
    public double sample(double lon, double lat, boolean smooth) {
        lon = Rasters.normLon(lon);
        int la = (int) Math.floor(Math.max(-90, Math.min(89.999999, lat)));
        int lo = (int) Math.floor(Math.max(-180, Math.min(179.999999, lon)));
        Rasters.Raster r = tile(la, lo);
        if (r == null) return Double.NaN;
        return smooth ? r.cubic(lon, lat, r.tiff.nodata) : r.bilinear(lon, lat, r.tiff.nodata);
    }

    /** {@link Rasters.Raster#demClass} of the downloaded tile (sea where there is no tile). */
    public int demClass(double lon, double lat, double[] level) {
        lon = Rasters.normLon(lon);
        int la = (int) Math.floor(Math.max(-90, Math.min(89.999999, lat)));
        int lo = (int) Math.floor(Math.max(-180, Math.min(179.999999, lon)));
        Rasters.Raster r = tile(la, lo);
        return r == null ? Rasters.CLS_SEA : r.demClass(lon, lat, level);
    }

    private Rasters.Raster tile(int la, int lo) {
        int key = Rasters.Aw3d30.key(la, lo);
        Long failed = failedAt.get(key);
        if (failed != null) {
            if (System.currentTimeMillis() - failed < RETRY_MS) return null;
            failedAt.remove(key);
        }
        CompletableFuture<Rasters.Raster> f = tiles.computeIfAbsent(key,
            k -> CompletableFuture.supplyAsync(() -> load(la, lo), pool));
        try {
            return f.join();
        } catch (CompletionException e) {
            // network trouble: try again later
            tiles.remove(key, f);
            failedAt.put(key, System.currentTimeMillis());
            failures.incrementAndGet();
            lastError = String.valueOf(e.getCause());
            return null;
        }
    }

    private Rasters.Raster load(int la, int lo) {
        String name = tileName(la, lo);
        Path file = dir.resolve(name + ".tif");
        Path none = dir.resolve(name + ".none");
        try {
            if (Files.exists(none)) return null;
            if (Files.exists(file)) { // a whole tile downloaded earlier (or put there by hand)
                return new Rasters.Raster(new GeoTiff(new GeoTiff.Source(file, null)), cache);
            }
            URI url = URI.create(BASE + name + "/" + name + ".tif");
            try {
                GeoTiff t = new GeoTiff(new GeoTiff.Source(dir.resolve(name), null, url, http));
                downloaded.incrementAndGet();
                return new Rasters.Raster(t, cache);
            } catch (RemoteBytes.Missing e) { // no tile: open ocean
                Files.createDirectories(dir);
                Files.writeString(none, "no Copernicus tile\n");
                missing.incrementAndGet();
                return null;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Bytes fetched from the network by all instances (for status and tests). */
    public static final java.util.concurrent.atomic.AtomicLong BYTES_FETCHED = new java.util.concurrent.atomic.AtomicLong();

    /** Where progress messages go (the mod points this at its logger). */
    public static volatile java.util.function.Consumer<String> logger = s -> { };

    static void log(String message) {
        logger.accept(message);
    }

    public String describe() {
        return String.format("auto-download on (%d tiles in use, %d ocean tiles, %.1f MB fetched this session)",
            downloaded.get(), missing.get(), BYTES_FETCHED.get() / 1e6)
            + (failures.get() > 0 ? ", " + failures.get() + " failed (" + lastError + ")" : "");
    }
}
