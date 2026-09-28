package io.github.lazytive.alosearth.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.lazytive.alosearth.core.Palette.*;

/**
 * Buildings from OpenStreetMap, via the OSM Buildings tile service
 * (osmbuildings.org): footprints with heights, colours and materials, in
 * zoom-15 GeoJSON tiles fetched when an area first generates and cached on
 * disk. Each building becomes a simple block building standing on the
 * terrain at its real footprint and height (1 block = 1 m): walls with
 * windows, a floor every 4 blocks, and a flat roof.
 */
public final class Buildings {
    public static final String DEFAULT_URL = "https://a.data.osmbuildings.org/0.2/59fcc2e8/tile/15/{x}/{y}.json";
    static final int ZOOM = 15;
    private static final long RETRY_MS = 10 * 60 * 1000;
    private static final int STOREY = 4;

    /** A footprint (outer ring then holes, each as lon, lat pairs) with its looks. */
    public record Building(String id, List<double[]> rings, double height, double minHeight, int wall, int roof,
                           boolean glassy, double west, double south, double east, double north) {
        /** Even-odd test over all rings, so courtyards (holes) stay open. */
        public boolean contains(double lon, double lat) {
            if (lon < west || lon > east || lat < south || lat > north) return false;
            boolean in = false;
            for (double[] r : rings) {
                int n = r.length / 2;
                for (int i = 0, j = n - 1; i < n; j = i++) {
                    double xi = r[2 * i], yi = r[2 * i + 1], xj = r[2 * j], yj = r[2 * j + 1];
                    if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) in = !in;
                }
            }
            return in;
        }
    }

    private final Path dir;
    private final String url;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "ALOS Earth buildings download");
        t.setDaemon(true);
        return t;
    });
    private final Map<Long, CompletableFuture<List<Building>>> tiles = new ConcurrentHashMap<>();
    private final Map<Long, Long> failedAt = new ConcurrentHashMap<>();
    public final AtomicInteger downloaded = new AtomicInteger(), failures = new AtomicInteger();
    public volatile String lastError = "";

    /** @param url tile URL template with {x} and {y} (zoom 15), or a local folder of {x}/{y}.json files */
    public Buildings(Path dir, String url) {
        this.dir = dir;
        this.url = url == null || url.isBlank() ? DEFAULT_URL : url;
    }

    // ------------------------------------------------------------ data

    /** Buildings whose outline may touch the given lon/lat box (each once). */
    public List<Building> around(double west, double south, double east, double north) {
        int n = 1 << ZOOM;
        int x0 = tileX(west, n), x1 = tileX(east, n), y0 = tileY(north, n), y1 = tileY(south, n);
        List<Building> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (Building b : tile(x, y)) {
                    if (b.east < west || b.west > east || b.north < south || b.south > north) continue;
                    if (seen.add(b.id)) out.add(b);
                }
            }
        }
        return out;
    }

    static int tileX(double lon, int n) {
        return Math.max(0, Math.min(n - 1, (int) Math.floor((lon + 180) / 360 * n)));
    }

    static int tileY(double lat, int n) {
        double phi = Math.toRadians(Math.max(-85.05, Math.min(85.05, lat)));
        double y = (1 - Math.log(Math.tan(phi) + 1 / Math.cos(phi)) / Math.PI) / 2 * n;
        return Math.max(0, Math.min(n - 1, (int) Math.floor(y)));
    }

    List<Building> tile(int x, int y) {
        long key = ((long) x << 32) | y;
        Long failed = failedAt.get(key);
        if (failed != null) {
            if (System.currentTimeMillis() - failed < RETRY_MS) return List.of();
            failedAt.remove(key);
        }
        CompletableFuture<List<Building>> f = tiles.get(key);
        if (f == null) f = tiles.computeIfAbsent(key, k -> CompletableFuture.supplyAsync(() -> load(x, y), pool));
        try {
            return f.join();
        } catch (CompletionException e) {
            tiles.remove(key, f);
            failedAt.put(key, System.currentTimeMillis());
            if (failures.incrementAndGet() <= 5) AutoDem.log("buildings tile " + x + "/" + y + " failed: " + e.getCause());
            lastError = String.valueOf(e.getCause());
            return List.of();
        }
    }

    private List<Building> load(int x, int y) {
        Path file = dir.resolve(ZOOM + "/" + x + "/" + y + ".json");
        try {
            if (!url.contains("{x}")) { // a local folder of tiles
                Path local = Path.of(url).resolve(x + "/" + y + ".json");
                return Files.exists(local) ? parse(Files.readString(local, StandardCharsets.UTF_8)) : List.of();
            }
            if (!Files.exists(file)) {
                long t0 = System.nanoTime();
                URI u = URI.create(url.replace("{x}", Integer.toString(x)).replace("{y}", Integer.toString(y))
                    .replace("{z}", Integer.toString(ZOOM)));
                HttpRequest req = HttpRequest.newBuilder(u).timeout(Duration.ofMinutes(2))
                    .header("User-Agent", "alos-earth-minecraft-mod").GET().build();
                HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
                byte[] body = resp.body();
                if (resp.statusCode() == 404 || resp.statusCode() == 204) body = "{\"features\":[]}".getBytes();
                else if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode() + " for " + u);
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
                AutoDem.log(String.format("fetched buildings tile %d/%d (%.2f MB) in %.1f s", x, y, body.length / 1e6,
                    (System.nanoTime() - t0) / 1e9));
            }
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedIOException(new IOException("interrupted", e));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Reads an OSM Buildings (GeoJSON) tile. */
    @SuppressWarnings("unchecked")
    public static List<Building> parse(String json) {
        Object root = Json.parse(json);
        List<Building> out = new ArrayList<>();
        if (!(root instanceof Map<?, ?> m) || !(m.get("features") instanceof List<?> features)) return out;
        int k = 0;
        for (Object fo : features) {
            k++;
            if (!(fo instanceof Map<?, ?> f) || !(f.get("geometry") instanceof Map<?, ?> g)) continue;
            Map<String, Object> p = f.get("properties") instanceof Map<?, ?> pm ? (Map<String, Object>) pm : Map.of();
            Object idv = f.get("id") != null ? f.get("id") : p.get("id");
            String id = idv != null ? String.valueOf(idv) : "feature" + k + ":" + g.hashCode();
            List<List<Object>> polygons = new ArrayList<>();
            Object coords = g.get("coordinates");
            if ("Polygon".equals(g.get("type")) && coords instanceof List<?> c) polygons.add((List<Object>) c);
            else if ("MultiPolygon".equals(g.get("type")) && coords instanceof List<?> c) {
                for (Object poly : c) if (poly instanceof List<?> pl) polygons.add((List<Object>) pl);
            } else continue;
            double height = num(p.get("height"), Double.NaN), levels = num(p.get("levels"), Double.NaN);
            if (Double.isNaN(height)) height = !Double.isNaN(levels) ? levels * 3.2 : 8;
            double minHeight = num(p.get("minHeight"), 0);
            if (height - minHeight < 1) continue;
            int wall = wallBlock(p, height, id);
            int roof = roofBlock(p, height, id);
            boolean glassy = wall == BLUE_GLASS || wall == GLASS || "glass".equals(p.get("material"));
            if (glassy) wall = BLUE_GLASS;
            int part = 0;
            for (List<Object> poly : polygons) {
                List<double[]> rings = new ArrayList<>();
                double w = 180, s = 90, e = -180, n = -90;
                for (Object ro : poly) {
                    if (!(ro instanceof List<?> ring) || ring.size() < 3) continue;
                    double[] r = new double[ring.size() * 2];
                    int i = 0;
                    for (Object pt : ring) {
                        List<?> xy = (List<?>) pt;
                        double lon = ((Number) xy.get(0)).doubleValue(), lat = ((Number) xy.get(1)).doubleValue();
                        r[i++] = lon;
                        r[i++] = lat;
                        w = Math.min(w, lon);
                        e = Math.max(e, lon);
                        s = Math.min(s, lat);
                        n = Math.max(n, lat);
                    }
                    rings.add(r);
                }
                if (rings.isEmpty() || e - w > 1 || n - s > 1) continue;
                out.add(new Building(polygons.size() > 1 ? id + "#" + part : id, rings, height, minHeight, wall, roof,
                    glassy, w, s, e, n));
                part++;
            }
        }
        return out;
    }

    private static double num(Object v, double fallback) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.replaceAll("[^0-9.+-]", ""));
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    // ------------------------------------------------------------ looks

    private static final int[] COLOR_BLOCKS = {
        WHITE_CONCRETE, LIGHT_GRAY_CONCRETE, GRAY_CONCRETE, BLACK_CONCRETE, BRICKS, STONE_BRICKS, SMOOTH_STONE,
        QUARTZ, SMOOTH_SANDSTONE, SPRUCE_PLANKS, POLISHED_ANDESITE, DEEPSLATE_TILES, BROWN_CONCRETE, RED_TC, CYAN_TC,
        BLUE_CONCRETE, GREEN_TC, YELLOW_CONCRETE, POLISHED_GRANITE, WHITE_TC, LIGHT_GRAY_TC, TERRACOTTA,
    };
    private static final int[] COLOR_RGB = {
        0xcfd5d6, 0x7d7d73, 0x36393d, 0x080a0f, 0x966153, 0x7a797a, 0x9e9e9e,
        0xebe5de, 0xdfd6aa, 0x725430, 0x848685, 0x363637, 0x603c20, 0x8f3d2e, 0x565b5b,
        0x2c2e8f, 0x4c532a, 0xf0af15, 0x9a6a59, 0xd1b2a1, 0x876b62, 0x985e43,
    };

    static int nearestColor(String hex) {
        int rgb;
        try {
            String h = hex.trim().replace("#", "");
            if (h.length() == 3) h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
            rgb = Integer.parseInt(h, 16);
        } catch (RuntimeException e) {
            return -1;
        }
        int best = -1;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < COLOR_RGB.length; i++) {
            int c = COLOR_RGB[i];
            double dr = ((rgb >> 16) & 255) - ((c >> 16) & 255), dg = ((rgb >> 8) & 255) - ((c >> 8) & 255),
                db = (rgb & 255) - (c & 255);
            double d = 2 * dr * dr + 4 * dg * dg + 3 * db * db;
            if (d < bd) {
                bd = d;
                best = COLOR_BLOCKS[i];
            }
        }
        return best;
    }

    private static int material(Object m) {
        if (!(m instanceof String s)) return -1;
        return switch (s) {
            case "brick", "bricks" -> BRICKS;
            case "glass", "mirror" -> BLUE_GLASS;
            case "stone", "granite", "marble" -> STONE_BRICKS;
            case "sandstone" -> SMOOTH_SANDSTONE;
            case "wood", "timber_framing" -> SPRUCE_PLANKS;
            case "metal", "steel", "aluminium" -> LIGHT_GRAY_CONCRETE;
            case "concrete", "cement_block" -> LIGHT_GRAY_CONCRETE;
            case "plaster", "render" -> WHITE_TC;
            default -> -1;
        };
    }

    private static int wallBlock(Map<String, Object> p, double height, String id) {
        int b = material(p.get("material"));
        if (b < 0 && p.get("color") instanceof String c) b = nearestColor(c);
        if (b >= 0) return b;
        int h = id.hashCode() & 0x7fffffff;
        if (height > 45) return h % 3 == 0 ? LIGHT_GRAY_CONCRETE : BLUE_GLASS;
        int[] pick = {WHITE_CONCRETE, WHITE_TC, SMOOTH_SANDSTONE, QUARTZ, LIGHT_GRAY_CONCRETE, BRICKS, POLISHED_ANDESITE};
        return pick[h % pick.length];
    }

    private static int roofBlock(Map<String, Object> p, double height, String id) {
        int b = material(p.get("roofMaterial"));
        if ((b < 0 || b == BLUE_GLASS) && p.get("roofColor") instanceof String c) b = nearestColor(c);
        if (b >= 0 && b != BLUE_GLASS) return b;
        int h = (id.hashCode() >>> 8) & 0x7fffffff;
        if (height < 14) return new int[] {RED_TC, DEEPSLATE_TILES, TERRACOTTA, GRAY_CONCRETE}[h % 4];
        return new int[] {GRAY_CONCRETE, LIGHT_GRAY_CONCRETE, SMOOTH_STONE}[h % 3];
    }

    // ------------------------------------------------------------ placing

    /** Where one building stands, in block y (virtual). */
    public record Placed(Building b, int base, int bottom, int top) {
    }

    /** The buildings of one chunk: per column (z * 16 + x) the building standing there and whether it is a wall. */
    public static final class Plan {
        public final List<Placed> placed = new ArrayList<>();
        public final int[] building = new int[256];
        public final boolean[] wall = new boolean[256];

        public boolean isEmpty() {
            return placed.isEmpty();
        }

        /**
         * Block for column {@code c} at y, or -1 to keep the terrain's block.
         * {@code ground} is the terrain's top solid block in that column.
         */
        public int block(int c, int x, int y, int z, int ground) {
            int k = building[c];
            if (k < 0) return -1;
            Placed p = placed.get(k);
            if (y < p.base) return y > ground ? STONE_BRICKS : -1; // foundation where the ground dips
            if (y == p.base) return p.bottom == p.base ? SMOOTH_STONE : -1;
            if (y > p.top) return -1;
            if (y < p.bottom) return AIR; // raised parts (overhangs, canopies)
            if (y == p.top) return wall[c] ? p.b.wall : p.b.roof;
            int rel = y - p.base;
            if (!wall[c]) return rel % STOREY == 0 ? SMOOTH_STONE : AIR;
            if (p.b.glassy) return rel % STOREY == 0 ? LIGHT_GRAY_CONCRETE : BLUE_GLASS;
            int along = Math.floorMod(x + z, 3);
            return (rel % STOREY == 2 || rel % STOREY == 3) && along != 0 ? GLASS : p.b.wall;
        }

        /** Highest y this chunk's buildings reach (Integer.MIN_VALUE if none). */
        public int maxTop() {
            int m = Integer.MIN_VALUE;
            for (Placed p : placed) m = Math.max(m, p.top);
            return m;
        }

        public int minBase() {
            int m = Integer.MAX_VALUE;
            for (Placed p : placed) m = Math.min(m, p.base);
            return m;
        }
    }

    /** Works out which buildings stand in the chunk at block (x0, z0). */
    public Plan plan(Terrain t, int x0, int z0) {
        Plan plan = new Plan();
        java.util.Arrays.fill(plan.building, -1);
        final int n = 18;
        double[] lon = new double[n * n], lat = new double[n * n];
        boolean[] ok = new boolean[n * n];
        double[] ll = new double[2];
        double w = 999, s = 999, e = -999, no = -999;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int o = j * n + i;
                if (t.projection.inverse(x0 - 1 + i + 0.5, z0 - 1 + j + 0.5, ll) == CubeProjection.OUTSIDE) continue;
                ok[o] = true;
                lon[o] = ll[0];
                lat[o] = ll[1];
                w = Math.min(w, ll[0]);
                e = Math.max(e, ll[0]);
                s = Math.min(s, ll[1]);
                no = Math.max(no, ll[1]);
            }
        }
        if (w > e || e - w > 1 || Math.abs(s) > 85) return plan; // outside, across the date line, or polar
        List<Building> near = around(w, s, e, no);
        if (near.isEmpty()) return plan;
        // which building covers each column (the tallest, where parts overlap)
        int[] cover = new int[n * n];
        java.util.Arrays.fill(cover, -1);
        List<Building> used = new ArrayList<>();
        int[] slot = new int[near.size()];
        java.util.Arrays.fill(slot, -2);
        for (int o = 0; o < n * n; o++) {
            if (!ok[o]) continue;
            int best = -1;
            for (int k = 0; k < near.size(); k++) {
                Building b = near.get(k);
                if (!b.contains(lon[o], lat[o])) continue;
                if (slot[k] == -2) slot[k] = place(t, b, plan);
                if (slot[k] < 0) continue;
                if (best < 0 || b.height > plan.placed.get(best).b.height) best = slot[k];
            }
            cover[o] = best;
        }
        for (int j = 1; j < n - 1; j++) {
            for (int i = 1; i < n - 1; i++) {
                int o = j * n + i, c = (j - 1) * 16 + (i - 1);
                int k = cover[o];
                plan.building[c] = k;
                if (k >= 0) {
                    plan.wall[c] = cover[o - 1] != k || cover[o + 1] != k || cover[o - n] != k || cover[o + n] != k;
                }
            }
        }
        return plan;
    }

    /** Adds a building to the plan standing on the ground at its middle; -1 if it can't stand (in water). */
    private static int place(Terrain t, Building b, Plan plan) {
        double[] r = b.rings.get(0);
        double cx = 0, cy = 0;
        int m = r.length / 2;
        for (int i = 0; i < m; i++) {
            cx += r[2 * i];
            cy += r[2 * i + 1];
        }
        double[] p = new double[2];
        t.projection.forward(cx / m, cy / m, p);
        int x = (int) Math.floor(p[0]), z = (int) Math.floor(p[1]);
        Terrain.Tile tile = t.tileAt(x, z);
        int i = Terrain.index(x, z);
        if (tile.water[i] > tile.top[i]) return -1;
        int base = tile.top[i];
        int top = Math.min(t.settings.maxY() - 2, base + Math.max(2, (int) Math.round(b.height)));
        int bottom = Math.min(top - 1, base + (int) Math.round(b.minHeight));
        plan.placed.add(new Placed(b, base, bottom, top));
        return plan.placed.size() - 1;
    }

    public String describe() {
        return "buildings on (" + tiles.size() + " tiles in use"
            + (failures.get() > 0 ? ", " + failures.get() + " failed (" + lastError + ")" : "") + ")";
    }
}
