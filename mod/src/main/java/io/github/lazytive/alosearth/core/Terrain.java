package io.github.lazytive.alosearth.core;

import java.util.LinkedHashMap;
import java.util.Map;

import static io.github.lazytive.alosearth.core.Palette.*;

/**
 * The world's terrain, computed in 64x64-block tiles (with a 16-block border
 * of context so slopes, coasts and river banks match across tiles) and
 * cached. Everything is a pure function of the settings and input data, so
 * the same place always generates the same way.
 */
public final class Terrain {
    public static final int TILE = 64, BORDER = 16, N = TILE + 2 * BORDER;
    private static final long SEED = 0x414C4F5345415254L;
    private static final int MAX_TILES = 2048;

    public final EarthSettings settings;
    public final CubeProjection projection;
    public final DataSources data;
    private final Noise detailNoise = new Noise(SEED), ridgeNoise = new Noise(SEED + 1),
        patchNoise = new Noise(SEED + 2), styleNoise = new Noise(SEED + 3),
        caveA = new Noise(SEED + 10), caveB = new Noise(SEED + 11), shapeNoise = new Noise(SEED + 12);
    /** Caves stay within this many blocks of the surface. */
    public static final int CAVE_DEPTH = 64;
    /** Per-thread cache of the last column's unit vector (caves are 3-D, sampled in globe space). */
    private final ThreadLocal<double[]> column = ThreadLocal.withInitial(() -> new double[] {Double.NaN, Double.NaN, 0, 0, 0});

    /** Remote ocean islands that become mushroom fields: lat, lon. */
    private static final double[][] MUSHROOM_ISLANDS = {
        {-25.066, -130.100}, // Pitcairn
        {-37.114, -12.283},  // Tristan da Cunha
        {-15.965, -5.708},   // Saint Helena
        {-7.946, -14.356},   // Ascension
        {10.302, -109.217},  // Clipperton
        {-27.113, -109.350}, // Easter Island
    };
    private final double radius;
    /** Size of real-world features relative to the 1:30 design scale (1 at 30 m per block, 30 at 1:1). */
    private final double scale;
    private final Map<Long, Tile> cache = new LinkedHashMap<>(256, 0.75f, true);
    /** Streams and rivers from the elevation data (worlds with the Minecraft feel), else null. */
    final Rivers rivers;

    /** One 64x64 tile of columns, indexed [z * 64 + x] relative to the tile corner. */
    public static final class Tile {
        public final int tx, tz;
        public final int[] top = new int[TILE * TILE];
        public final int[] water = new int[TILE * TILE];
        public final byte[] biome = new byte[TILE * TILE];
        public final byte[] style = new byte[TILE * TILE];
        /** Bit 0: caves may run below; bit 1: they may open at the surface; bits 4-6: overhang depth. */
        public final byte[] feature = new byte[TILE * TILE];
        /** Biome of the caves under a column (-1: the surface biome). */
        public final byte[] caveBiome = new byte[TILE * TILE];

        Tile(int tx, int tz) {
            this.tx = tx;
            this.tz = tz;
            java.util.Arrays.fill(caveBiome, (byte) -1);
        }
    }

    enum Zone { TROPICAL, SAVANNA, DESERT, STEPPE, MEDITERRANEAN, TEMPERATE, BOREAL, TUNDRA, ICE }

    private record Pick(String biome, int weight) {
    }

    private static final Map<Zone, Pick[]> ZONE_BIOMES = Map.of(
        Zone.TROPICAL, new Pick[] {new Pick("jungle", 45), new Pick("sparse_jungle", 25), new Pick("bamboo_jungle", 15),
            new Pick("savanna", 15)},
        Zone.SAVANNA, new Pick[] {new Pick("savanna", 55), new Pick("plains", 25), new Pick("sparse_jungle", 10),
            new Pick("desert", 10)},
        Zone.DESERT, new Pick[] {new Pick("desert", 80), new Pick("badlands", 12), new Pick("savanna", 8)},
        Zone.STEPPE, new Pick[] {new Pick("savanna", 35), new Pick("desert", 20), new Pick("badlands", 20),
            new Pick("plains", 15), new Pick("wooded_badlands", 10)},
        Zone.MEDITERRANEAN, new Pick[] {new Pick("savanna", 35), new Pick("plains", 30), new Pick("forest", 15),
            new Pick("sunflower_plains", 10), new Pick("flower_forest", 5), new Pick("birch_forest", 5)},
        Zone.TEMPERATE, new Pick[] {new Pick("forest", 35), new Pick("plains", 20), new Pick("birch_forest", 15),
            new Pick("dark_forest", 12), new Pick("flower_forest", 8), new Pick("sunflower_plains", 5), new Pick("meadow", 5)},
        Zone.BOREAL, new Pick[] {new Pick("taiga", 50), new Pick("old_growth_spruce_taiga", 15),
            new Pick("old_growth_pine_taiga", 10), new Pick("snowy_taiga", 15), new Pick("plains", 10)},
        Zone.TUNDRA, new Pick[] {new Pick("snowy_plains", 70), new Pick("snowy_taiga", 20), new Pick("ice_spikes", 10)},
        Zone.ICE, new Pick[] {new Pick("snowy_plains", 75), new Pick("ice_spikes", 25)});

    // approximate permanent snow line (metres) by absolute latitude
    private static final double[] SNOW_LAT = {0, 20, 30, 45, 60, 70, 80, 90};
    private static final double[] SNOW_M = {4600, 4800, 4200, 2600, 1300, 500, 100, 0};
    /** Version 1 snow line: closer to the real permanent snow line (Alps ~2900 m, Norway ~1500 m). */
    private static final double[] SNOW_M_V1 = {4800, 5000, 4400, 2900, 1500, 700, 200, 0};

    private double snowLine(double alat) {
        return interp(alat, SNOW_LAT, settings.minecraftFeel() ? SNOW_M_V1 : SNOW_M);
    }

    public Terrain(EarthSettings settings, DataSources data) {
        this.settings = settings;
        this.data = data;
        this.projection = new CubeProjection(settings.metersPerBlock(), settings.centerLat(), settings.centerLon(),
            settings.margin());
        this.radius = projection.size * 2 / Math.PI;
        this.scale = 30.0 / settings.metersPerBlock();
        double mpb = settings.metersPerBlock();
        double minArea = mpb <= 2 ? 0.6 : mpb <= 6 ? 1.5 : mpb <= 12 ? 3 : 10;
        this.rivers = settings.minecraftFeel() ? new Rivers(this::hydroElevation, minArea) : null;
    }

    /** Elevation for the flow model (metres, NaN at sea), from the same data as the terrain. */
    double hydroElevation(double lon, double lat) {
        double[] smp = new double[2], lvl = new double[1];
        try {
            data.aw3d30.sample(lon, lat, smp, false);
            if (!Double.isNaN(smp[0])) return smp[1] == Rasters.CLS_SEA ? Double.NaN : smp[0];
            if (!data.fillDem.isEmpty()) {
                double e = data.fillDem.sample(lon, lat, false);
                if (!Double.isNaN(e)) {
                    int c = data.fillDem.demClass(lon, lat, lvl);
                    return c == Rasters.CLS_SEA ? Double.NaN : c == Rasters.CLS_LAKE ? lvl[0] : e;
                }
            }
            if (data.autoDem != null) {
                double e = data.autoDem.sample(lon, lat, false);
                if (!Double.isNaN(e)) {
                    int c = data.autoDem.demClass(lon, lat, lvl);
                    return c == Rasters.CLS_SEA ? Double.NaN : c == Rasters.CLS_LAKE ? lvl[0] : e;
                }
            }
            double b = !data.bathymetry.isEmpty() ? data.bathymetry.sample(lon, lat, false)
                : settings.seaFloor() && data.seaFloor != null ? data.seaFloor.sample(lon, lat, false) : Double.NaN;
            return b > 0 ? b : Double.NaN;
        } catch (RuntimeException ex) {
            dataError(ex);
            return Double.NaN;
        }
    }

    // ------------------------------------------------------------ queries

    public Tile tile(int tx, int tz) {
        long key = ((long) tx << 32) ^ (tz & 0xffffffffL);
        synchronized (cache) {
            Tile t = cache.get(key);
            if (t != null) return t;
        }
        Tile t = compute(tx, tz);
        synchronized (cache) {
            cache.put(key, t);
            if (cache.size() > MAX_TILES) {
                var it = cache.entrySet().iterator();
                it.next();
                it.remove();
            }
        }
        return t;
    }

    /** The tile if it has already been computed, else null (never computes or downloads). */
    public Tile cachedTileAt(int x, int z) {
        long key = ((long) Math.floorDiv(x, TILE) << 32) ^ (Math.floorDiv(z, TILE) & 0xffffffffL);
        synchronized (cache) {
            return cache.get(key);
        }
    }

    /**
     * A cheap biome estimate from climate alone (no elevation, no downloads):
     * what Minecraft's wide-area searches get (stronghold placement,
     * structure checks, /locate). Chunks themselves use the exact biome.
     */
    public int approximateBiome(int x, int z) {
        Tile cached = cachedTileAt(x, z);
        if (cached != null) return cached.biome[index(x, z)];
        double[] ll = new double[2];
        if (projection.inverse(x + 0.5, z + 0.5, ll) == CubeProjection.OUTSIDE) return Palette.biome("ocean");
        double lon = ll[0], lat = ll[1];
        double[] v = CubeProjection.lonLatToVec(lon, lat);
        double px = v[0] * radius, py = v[1] * radius, pz = v[2] * radius;
        int k = climateClass(lon, lat, px, py, pz);
        if (k == 0 && data.climate.isEmpty() && BuiltinClimate.get() != null) {
            double alat = Math.abs(lat);
            return Palette.biome(alat < 20 ? "warm_ocean" : alat < 35 ? "lukewarm_ocean" : alat < 50 ? "ocean"
                : alat < 62 ? "cold_ocean" : "frozen_ocean");
        }
        Zone zone = k > 0 ? zoneForKoppen(k) : null;
        if (zone == null) zone = zoneForLatitude(Math.abs(lat));
        return Palette.biome(pickBiome(zone, px, py, pz));
    }

    public Tile tileAt(int x, int z) {
        return tile(Math.floorDiv(x, TILE), Math.floorDiv(z, TILE));
    }

    public static int index(int x, int z) {
        return Math.floorMod(z, TILE) * TILE + Math.floorMod(x, TILE);
    }

    /** y of the top solid block. */
    public int top(int x, int z) {
        return tileAt(x, z).top[index(x, z)];
    }

    /** y of the highest non-air block (water surface or ground). */
    public int surface(int x, int z) {
        Tile t = tileAt(x, z);
        int i = index(x, z);
        return Math.max(t.top[i], t.water[i]);
    }

    public String biome(int x, int z) {
        return BIOMES[tileAt(x, z).biome[index(x, z)]];
    }

    /** Block id ({@link Palette#BLOCKS}) at a position. */
    public int block(Tile t, int i, int x, int y, int z) {
        int top = t.top[i];
        if (y > top) return y <= t.water[i] ? WATER : AIR;
        int f = t.feature[i];
        if (f != 0) {
            int u = (f >> 4) & 7; // an overhang: air under a 2-block lip at a cliff edge
            if (u > 0 && y <= top - 2 && y >= top - 1 - u) return AIR;
            if ((f & 1) != 0 && cave(top - y, (f & 2) != 0, x, y, z)) return AIR;
        }
        int[] st = STYLES[t.style[i]];
        int d = top - y;
        if (d == 0) return resolve(st[0], y);
        if (d <= st[2]) return resolve(st[1], y);
        if (d <= st[2] + st[4]) return resolve(st[3], y);
        return deep(x, y, z);
    }

    public int block(int x, int y, int z) {
        Tile t = tileAt(x, z);
        return block(t, index(x, z), x, y, z);
    }

    private static int resolve(int b, int y) {
        return b == BAND ? BANDS[Math.floorMod(y, BANDS.length)] : b;
    }

    /** Virtual y below which a column is plain rock (stone/deepslate/bedrock only, no caves). */
    public static int deepStart(Tile t, int i) {
        int[] st = STYLES[t.style[i]];
        int d = t.top[i] - st[2] - st[4];
        int f = t.feature[i];
        if ((f & 1) != 0) d = Math.min(d, t.top[i] - CAVE_DEPTH - 1);
        if (((f >> 4) & 7) != 0) d = Math.min(d, t.top[i] - 1 - ((f >> 4) & 7) - 1);
        return d;
    }

    /** Spaghetti caves: thin winding tunnels where two 3-D noise fields are both near zero. */
    private boolean cave(int depth, boolean opens, int x, int y, int z) {
        if (depth > CAVE_DEPTH || depth < (opens ? 0 : 5) || y < settings.bottomY() + 10) return false;
        double[] c = column.get();
        if (c[0] != x || c[1] != z) {
            double[] ll = new double[2];
            projection.inverse(x + 0.5, z + 0.5, ll);
            double[] v = CubeProjection.lonLatToVec(ll[0], ll[1]);
            c[0] = x;
            c[1] = z;
            c[2] = v[0];
            c[3] = v[1];
            c[4] = v[2];
        }
        double r = (radius + 1.6 * y) / 26; // a little flatter than wide
        double a = caveA.noise(c[2] * r, c[3] * r, c[4] * r), b = caveB.noise(c[2] * r, c[3] * r, c[4] * r);
        return a * a + b * b < 0.004;
    }

    private int deep(int x, int y, int z) {
        int minY = settings.bottomY();
        if (y <= minY) return BEDROCK;
        int k = y - minY;
        if (k < 5 && Math.floorMod(hash(x, y, z), 5) < 5 - k) return BEDROCK;
        if (y < 0) return DEEPSLATE;
        if (y < 8 && Math.floorMod(hash(x, y, z) >> 8, 8) < 8 - y) return DEEPSLATE;
        return STONE;
    }

    private static int hash(int x, int y, int z) {
        long h = x * 0x9E3779B97F4A7C15L ^ y * 0xC2B2AE3D27D4EB4FL ^ z * 0x165667B19E3779F9L;
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        return (int) h;
    }

    // ------------------------------------------------------------ generation

    private static double smoothstep(double a, double b, double v) {
        double t = Math.max(0, Math.min(1, (v - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }

    /**
     * Cellular patches: the globe is split into irregular blobs about
     * {@code cell} blocks across (nearest jittered point of a 3-D lattice,
     * with warped borders); returns a uniform 0..1 value per blob.
     */
    private double patch(double x, double y, double z, double cell) {
        // strong domain warp so patch borders wander instead of being straight
        double warp = cell * 1.1;
        double wx = x + patchNoise.fbm(x, y, z, cell * 0.45, 3) * warp;
        double wy = y + patchNoise.fbm(x + 5000, y, z, cell * 0.45, 3) * warp;
        double wz = z + patchNoise.fbm(x, y + 5000, z, cell * 0.45, 3) * warp;
        int cx = (int) Math.floor(wx / cell), cy = (int) Math.floor(wy / cell), cz = (int) Math.floor(wz / cell);
        double best = Double.MAX_VALUE;
        int bestHash = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int h = hash(cx + dx, cy + dy, cz + dz);
                    double jx = (cx + dx + ((h & 1023) / 1024.0)) * cell - wx;
                    double jy = (cy + dy + (((h >>> 10) & 1023) / 1024.0)) * cell - wy;
                    double jz = (cz + dz + (((h >>> 20) & 1023) / 1024.0)) * cell - wz;
                    double d = jx * jx + jy * jy + jz * jz;
                    if (d < best) {
                        best = d;
                        bestHash = h;
                    }
                }
            }
        }
        return (hash(bestHash, 7, 13) >>> 8) / (double) (1 << 24);
    }

    private static Zone zoneForKoppen(int k) {
        return switch (k) {
            case 1, 2 -> Zone.TROPICAL;
            case 3, 6 -> Zone.SAVANNA;
            case 4, 5 -> Zone.DESERT;
            case 7 -> Zone.STEPPE;
            case 8, 9, 10 -> Zone.MEDITERRANEAN;
            case 16, 19, 20, 23, 24, 27, 28 -> Zone.BOREAL;
            case 29 -> Zone.TUNDRA;
            case 30 -> Zone.ICE;
            case 11, 12, 13, 14, 15, 17, 18, 21, 22, 25, 26 -> Zone.TEMPERATE;
            default -> null;
        };
    }

    private static Zone zoneForLatitude(double alat) {
        if (alat < 15) return Zone.TROPICAL;
        if (alat < 25) return Zone.SAVANNA;
        if (alat < 48) return Zone.TEMPERATE;
        if (alat < 62) return Zone.BOREAL;
        if (alat < 72) return Zone.TUNDRA;
        return Zone.ICE;
    }

    private static boolean cold(Zone z) {
        return z == Zone.BOREAL || z == Zone.TUNDRA || z == Zone.ICE;
    }

    /** Approximate octile distance to the nearest source cell, capped at BORDER. */
    private static float[] distance(boolean[] src) {
        int n = N;
        float[] d = new float[n * n];
        float inf = 1e9f, diag = 1.4142135f;
        for (int i = 0; i < d.length; i++) d[i] = src[i] ? 0 : inf;
        for (int z = 0; z < n; z++) {
            for (int x = 0; x < n; x++) {
                int i = z * n + x;
                float v = d[i];
                if (x > 0) v = Math.min(v, d[i - 1] + 1);
                if (z > 0) {
                    v = Math.min(v, d[i - n] + 1);
                    if (x > 0) v = Math.min(v, d[i - n - 1] + diag);
                    if (x < n - 1) v = Math.min(v, d[i - n + 1] + diag);
                }
                d[i] = v;
            }
        }
        for (int z = n - 1; z >= 0; z--) {
            for (int x = n - 1; x >= 0; x--) {
                int i = z * n + x;
                float v = d[i];
                if (x < n - 1) v = Math.min(v, d[i + 1] + 1);
                if (z < n - 1) {
                    v = Math.min(v, d[i + n] + 1);
                    if (x < n - 1) v = Math.min(v, d[i + n + 1] + diag);
                    if (x > 0) v = Math.min(v, d[i + n - 1] + diag);
                }
                d[i] = Math.min(v, BORDER);
            }
        }
        return d;
    }

    Tile compute(int tx, int tz) {
        final int n = N, nn = n * n;
        final EarthSettings s = settings;
        final int sea = s.seaLevel();
        final int x0 = tx * TILE - BORDER, z0 = tz * TILE - BORDER;
        final boolean fine = s.fine();

        final boolean mc = s.minecraftFeel();
        double[] lat = new double[nn], lonA = new double[nn], elev = new double[nn], depth = new double[nn];
        double[] px = new double[nn], py = new double[nn], pz = new double[nn];
        byte[] cls = new byte[nn];
        boolean[] valid = new boolean[nn];
        Zone[] zone = new Zone[nn];
        double[] ll = new double[2], smp = new double[2], flatLevel = new double[1];

        for (int i = 0; i < nn; i++) {
            int x = x0 + i % n, z = z0 + i / n;
            depth[i] = Double.NaN;
            cls[i] = Rasters.CLS_SEA;
            if (projection.inverse(x + 0.5, z + 0.5, ll) == CubeProjection.OUTSIDE) {
                elev[i] = Double.NaN;
                continue;
            }
            valid[i] = true;
            double lon = ll[0], la = ll[1];
            lat[i] = la;
            lonA[i] = lon;
            double[] v = CubeProjection.lonLatToVec(lon, la);
            px[i] = v[0] * radius;
            py[i] = v[1] * radius;
            pz[i] = v[2] * radius;

            double e = Double.NaN, b = Double.NaN;
            int c = Rasters.CLS_SEA;
            // Data problems (a corrupt file, a network error) must never break
            // world generation: that column just falls through to the next source.
            try {
                data.aw3d30.sample(lon, la, smp, fine);
                e = smp[0];
                c = (int) smp[1];
            } catch (RuntimeException ex) {
                dataError(ex);
            }
            if (Double.isNaN(e) && !data.fillDem.isEmpty()) {
                try {
                    e = data.fillDem.sample(lon, la, fine);
                    if (!Double.isNaN(e)) {
                        c = data.fillDem.demClass(lon, la, flatLevel);
                        if (c == Rasters.CLS_LAKE) e = flatLevel[0];
                        else if (c == Rasters.CLS_UNKNOWN) c = e <= 0 ? Rasters.CLS_SEA : Rasters.CLS_LAND;
                    }
                } catch (RuntimeException ex) {
                    dataError(ex);
                }
            }
            if (Double.isNaN(e) && data.autoDem != null) {
                try {
                    e = data.autoDem.sample(lon, la, fine);
                    if (!Double.isNaN(e)) {
                        c = data.autoDem.demClass(lon, la, flatLevel);
                        if (c == Rasters.CLS_LAKE) e = flatLevel[0];
                        else if (c == Rasters.CLS_UNKNOWN) c = e <= 0 ? Rasters.CLS_SEA : Rasters.CLS_LAND;
                    }
                } catch (RuntimeException ex) {
                    dataError(ex);
                }
            }
            // depth data for the sea, and (in worlds with the downloaded sea floor) for lake beds
            boolean wantDepth = Double.isNaN(e) || c == Rasters.CLS_SEA || (c == Rasters.CLS_LAKE && s.seaFloor());
            if (wantDepth && !data.bathymetry.isEmpty()) {
                try {
                    b = data.bathymetry.sample(lon, la, fine);
                } catch (RuntimeException ex) {
                    dataError(ex);
                }
            } else if (wantDepth && s.seaFloor() && data.seaFloor != null) {
                try {
                    b = data.seaFloor.sample(lon, la, fine);
                } catch (RuntimeException ex) {
                    dataError(ex);
                }
            }
            if (Double.isNaN(e) && !Double.isNaN(b)) {
                e = b;
                c = b < 0 ? Rasters.CLS_SEA : Rasters.CLS_LAND;
            }
            if (Double.isNaN(e)) c = Rasters.CLS_SEA;
            if (c == Rasters.CLS_SEA && !Double.isNaN(b)) depth[i] = Math.max(0, -b);
            if (c == Rasters.CLS_LAKE && !Double.isNaN(b) && b < e - 3) depth[i] = b; // lake bed elevation
            elev[i] = e;
            cls[i] = (byte) c;

            int kc = climateClass(lon, la, px[i], py[i], pz[i]);
            Zone zn = kc > 0 ? zoneForKoppen(kc) : null;
            zone[i] = zn != null ? zn : zoneForLatitude(Math.abs(la));
        }

        boolean[] isSea = new boolean[nn], isLake = new boolean[nn], isLand = new boolean[nn];
        for (int i = 0; i < nn; i++) {
            isSea[i] = cls[i] == Rasters.CLS_SEA;
            isLake[i] = cls[i] == Rasters.CLS_LAKE;
            isLand[i] = !isSea[i] && !isLake[i];
        }

        // --- land heights: exaggeration curve + Minecraft-style detail
        double[] base = new double[nn], h = new double[nn];
        for (int i = 0; i < nn; i++) {
            if (!valid[i] || isSea[i]) continue;
            base[i] = s.landBlocks(elev[i]);
            if (isLake[i]) {
                h[i] = base[i];
                continue;
            }
            double bumps = detailNoise.fbm(px[i], py[i], pz[i], 40, 3) * 2.2;
            double coastFade = Math.max(0, Math.min(1, base[i] / 6));
            double mountain = smoothstep(s.landBlocks(1000), s.landBlocks(3500), base[i]);
            double ridged = 1 - Math.abs(ridgeNoise.fbm(px[i], py[i], pz[i], 150, 2)) * 2.2;
            double d = bumps * coastFade * (1 + 1.5 * mountain) + ridged * 14 * mountain;
            // 1:1: a data pixel spans ~30 blocks, so add texture below that size
            if (fine) d += detailNoise.fbm(px[i] + 3000, py[i], pz[i], 9, 2) * coastFade * (0.6 + 2.5 * mountain);
            h[i] = base[i] + s.detail() * d;
        }

        if (mc) shapeLand(valid, isLand, elev, zone, base, h, px, py, pz);

        float[] distLand = distance(or(isLand, isLake));
        float[] distSea = distance(isSea);

        int[] top = new int[nn], water = new int[nn];
        int minTop = s.bottomY() + 6, maxTop = s.maxY() - 1; // floors stay above the bedrock band
        for (int i = 0; i < nn; i++) {
            water[i] = Integer.MIN_VALUE;
            if (isSea[i]) {
                double d;
                double dl = distLand[i] / (double) BORDER;
                if (!Double.isNaN(depth[i])) d = s.oceanBlocks(depth[i]);
                else d = 3 + 27 * smoothstep(0, 1, dl);
                if (dl < 1) d = Math.min(d, 2 + dl * dl * 200); // shelve off right next to the coast
                d = Math.max(2, d);
                if (s.seaFloor() && d > 6) { // a little relief on the (smooth, ~1 km) sea-floor data
                    d += detailNoise.fbm(px[i], py[i] + 7000, pz[i], 48, 3) * Math.min(12, 1 + d * 0.01);
                }
                top[i] = sea - (int) Math.round(d);
                water[i] = sea;
            } else if (isLake[i]) {
                water[i] = sea + (int) Math.round(base[i]);
                top[i] = water[i] - 3;
                if (!Double.isNaN(depth[i])) top[i] = Math.min(top[i], sea + (int) Math.round(s.landBlocks(depth[i])));
            } else {
                top[i] = sea + (int) Math.round(h[i]);
            }
        }

        // --- widen rivers: low land next to river/lake water becomes water
        boolean[] lake2 = isLake.clone();
        for (int z = 2; z < n - 2; z++) {
            for (int x = 2; x < n - 2; x++) {
                int i = z * n + x;
                if (!isLand[i] || !valid[i]) continue;
                int minLevel = Integer.MAX_VALUE;
                for (int dz = -2; dz <= 2; dz++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        int j = i + dz * n + dx;
                        if (isLake[j]) minLevel = Math.min(minLevel, water[j]);
                    }
                }
                if (minLevel != Integer.MAX_VALUE && top[i] <= minLevel + 3) {
                    lake2[i] = true;
                    water[i] = minLevel;
                    top[i] = Math.min(top[i], minLevel - 2);
                }
            }
        }
        // --- no water hanging above lower land next to it
        for (int z = 1; z < n - 1; z++) {
            for (int x = 1; x < n - 1; x++) {
                int i = z * n + x;
                if (!lake2[i]) continue;
                int lvl = water[i];
                for (int j : new int[] {i - 1, i + 1, i - n, i + n}) {
                    if (isLand[j] && !lake2[j]) lvl = Math.min(lvl, top[j]);
                    if (isSea[j]) lvl = Math.min(lvl, Math.max(sea, top[i] + 1));
                }
                water[i] = Math.max(lvl, top[i] + 1);
            }
        }

        boolean[] river = new boolean[nn];
        if (rivers != null) carveRivers(valid, isLand, lake2, lonA, lat, top, water, river, px, py, pz);
        if (mc) levees(valid, isSea, top, water);
        for (int i = 0; i < nn; i++) top[i] = Math.max(minTop, Math.min(maxTop, top[i]));
        byte[] feat = new byte[nn];
        boolean[] boulder = new boolean[nn];
        if (mc) rockAndCaves(valid, isLand, lake2, top, water, feat, boulder, px, py, pz, maxTop);

        // --- slope of the visible surface
        int[] surf = new int[nn];
        for (int i = 0; i < nn; i++) surf[i] = Math.max(top[i], water[i]);

        Tile t = new Tile(tx, tz);
        for (int zz = 0; zz < TILE; zz++) {
            for (int xx = 0; xx < TILE; xx++) {
                int i = (zz + BORDER) * n + (xx + BORDER);
                int o = zz * TILE + xx;
                int slope = 0;
                if (fine) { // blocks per block over a few blocks, scaled so 4 is a 45 degree face
                    int m = 0;
                    for (int j : new int[] {i - 3, i + 3, i - 3 * n, i + 3 * n}) m = Math.max(m, Math.abs(surf[i] - surf[j]));
                    slope = (int) Math.round(m * 4 / 3.0);
                } else {
                    for (int j : new int[] {i - 1, i + 1, i - n, i + n}) slope = Math.max(slope, Math.abs(surf[i] - surf[j]));
                }
                t.top[o] = top[i];
                t.water[o] = water[i];
                t.feature[o] = feat[i];
                classify(t, o, i, valid[i], isSea[i], lake2[i] || river[i], zone[i], lat[i], lonA[i], base[i], h[i], slope,
                    distSea[i], sea - top[i], px[i], py[i], pz[i], boulder[i]);
            }
        }
        return t;
    }

    /**
     * Minecraft-style land shapes on top of the real ones: wind-blown dunes in sandy deserts, and
     * on steep ground terraces of flat ledges and short cliffs instead of smooth ramps.
     */
    private void shapeLand(boolean[] valid, boolean[] isLand, double[] elev, Zone[] zone, double[] base, double[] h,
                           double[] px, double[] py, double[] pz) {
        final int n = N, nn = n * n;
        final EarthSettings s = settings;
        double[] slope = new double[nn];
        for (int z = 1; z < n - 1; z++) {
            for (int x = 1; x < n - 1; x++) {
                int i = z * n + x;
                if (!valid[i] || !isLand[i]) continue;
                double m = 0;
                for (int j : new int[] {i - 1, i + 1, i - n, i + n}) if (valid[j] && isLand[j]) m = Math.max(m, Math.abs(h[i] - h[j]));
                slope[i] = m;
            }
        }
        double duneWl = Math.max(24, 700 / s.metersPerBlock());
        for (int i = 0; i < nn; i++) {
            if (!valid[i] || !isLand[i]) continue;
            double coast = Math.max(0, Math.min(1, base[i] / 4));
            if (zone[i] == Zone.DESERT) { // dunes about 20 m high where the sand is deep
                double erg = smoothstep(0.0, 0.35, patchNoise.fbm(px[i] + 9000, py[i], pz[i], duneWl * 12, 2));
                if (erg > 0) {
                    double r = 1 - Math.abs(shapeNoise.fbm(px[i], py[i] + 9000, pz[i] * 0.4, duneWl, 2));
                    double amp = Math.max(0.6, s.landBlocks(elev[i] + 18) - s.landBlocks(elev[i]));
                    h[i] += erg * coast * amp * r * r;
                }
            }
            double w = smoothstep(0.2, 0.8, slope[i]) * smoothstep(-0.2, 0.25, patchNoise.fbm(px[i], py[i] - 9000, pz[i], 900, 2));
            if (w > 0) { // terraces: ledges 4-8 blocks apart joined by short cliffs
                double step = 4 + 4 * (0.5 + 0.5 * shapeNoise.noise(px[i] / 300, py[i] / 300, pz[i] / 300));
                double t = h[i] / step, fl = Math.floor(t);
                double shaped = (fl + smoothstep(0.42, 0.58, t - fl)) * step;
                h[i] += 0.8 * w * (shaped - h[i]);
            }
        }
    }

    /**
     * Streams and rivers: a channel along the nearest drainage line, as wide as the area draining
     * into it warrants (at least a block or two), with the water at the flow model's level and the
     * banks sloping down to it (or raised to it where the ground is lower, so no water hangs).
     */
    private void carveRivers(boolean[] valid, boolean[] isLand, boolean[] lake2, double[] lon, double[] lat, int[] top,
                             int[] water, boolean[] river, double[] px, double[] py, double[] pz) {
        final EarthSettings s = settings;
        final double mpb = s.metersPerBlock();
        final int sea = s.seaLevel();
        final double radius = Math.max(10 * mpb, 120);
        for (int i = 0; i < N * N; i++) {
            if (!valid[i] || !isLand[i] || lake2[i]) continue;
            Rivers.Hit hit;
            try {
                hit = rivers.nearest(lon[i], lat[i], radius);
            } catch (RuntimeException ex) {
                dataError(ex);
                continue;
            }
            if (hit == null || hit.waterM() > snowLine(Math.abs(lat[i]))) continue; // streams start below the ice
            double wM = 1.5 + 2.2 * Math.sqrt(hit.areaKm2());
            double wB = Math.max(1.6 + 0.5 * Math.log(hit.areaKm2() / rivers.minAreaKm2) / Math.log(2), wM / mpb);
            double dB = hit.distanceM() / mpb + shapeNoise.noise(px[i] / 23, py[i] / 23, pz[i] / 23 + 700) * 0.25 * wB;
            double half = wB / 2, bank = 2 + wB / 2;
            if (dB > half + bank) continue;
            int wy = sea + (int) Math.round(s.landBlocks(hit.waterM()));
            if (dB <= half) {
                int depthB = Math.max(1, Math.min(8, 1 + (int) (wB / 5)));
                river[i] = true;
                water[i] = wy;
                top[i] = Math.min(top[i], wy - depthB);
            } else {
                int bankTop = wy + (int) (dB - half);
                top[i] = Math.max(wy, Math.min(top[i], bankTop));
            }
        }
    }

    /** A lip of ground (levee) wherever river or lake water would otherwise touch lower dry land. */
    private static void levees(boolean[] valid, boolean[] isSea, int[] top, int[] water) {
        final int n = N;
        int[] raise = new int[n * n];
        java.util.Arrays.fill(raise, Integer.MIN_VALUE);
        for (int z = 1; z < n - 1; z++) {
            for (int x = 1; x < n - 1; x++) {
                int i = z * n + x;
                if (!valid[i] || isSea[i] || water[i] > top[i]) continue;
                for (int j : new int[] {i - 1, i + 1, i - n, i + n}) {
                    if (!isSea[j] && water[j] > top[j]) raise[i] = Math.max(raise[i], water[j]);
                }
            }
        }
        for (int i = 0; i < n * n; i++) if (raise[i] > top[i]) top[i] = raise[i];
    }

    /**
     * Block-sized details: boulders on rocky ground, overhanging lips at cliff edges, and where
     * caves may run (away from water, in about two thirds of the land).
     */
    private void rockAndCaves(boolean[] valid, boolean[] isLand, boolean[] lake2, int[] top, int[] water, byte[] feat,
                              boolean[] boulder, double[] px, double[] py, double[] pz, int maxTop) {
        final int n = N, nn = n * n;
        boolean[] wet = new boolean[nn];
        for (int i = 0; i < nn; i++) wet[i] = !valid[i] || water[i] > top[i] || lake2[i] || !isLand[i];
        float[] distWet = distance(wet);
        for (int i = 0; i < nn; i++) {
            if (wet[i]) continue;
            double rocky = smoothstep(-0.3, 0.4, patchNoise.fbm(px[i], py[i], pz[i] + 9000, 400, 2));
            double bn = shapeNoise.noise(px[i] / 7 + 500, py[i] / 7, pz[i] / 7);
            if (distWet[i] > 2 && rocky > 0.3 && bn > 0.56) {
                top[i] = Math.min(maxTop, top[i] + 1 + (int) ((bn - 0.56) * 25));
                boulder[i] = true;
            }
        }
        for (int z = 1; z < n - 1; z++) {
            for (int x = 1; x < n - 1; x++) {
                int i = z * n + x;
                if (wet[i]) continue;
                int f = 0;
                double cn = caveA.noise(px[i] / 700 + 77, py[i] / 700, pz[i] / 700);
                if (cn > -0.15 && distWet[i] > 3) {
                    f |= 1;
                    if (shapeNoise.noise(px[i] / 40 - 300, py[i] / 40, pz[i] / 40) > 0.45) f |= 2;
                }
                int low = Math.min(Math.min(top[i - 1], top[i + 1]), Math.min(top[i - n], top[i + n]));
                if (top[i] - low >= 4 && !boulder[i] && shapeNoise.noise(px[i] / 15, py[i] / 15 + 300, pz[i] / 15) > 0.2) {
                    f |= Math.min(4, top[i] - low - 2) << 4;
                }
                feat[i] = (byte) f;
            }
        }
    }

    private static boolean nearMushroomIsland(double lat, double lon) {
        double[] v = CubeProjection.lonLatToVec(lon, lat);
        for (double[] m : MUSHROOM_ISLANDS) {
            double[] w = CubeProjection.lonLatToVec(m[1], m[0]);
            if (v[0] * w[0] + v[1] * w[1] + v[2] * w[2] > Math.cos(25.0 / 6371)) return true;
        }
        return false;
    }

    private final java.util.concurrent.atomic.AtomicInteger dataErrors = new java.util.concurrent.atomic.AtomicInteger();

    private void dataError(RuntimeException ex) {
        if (dataErrors.incrementAndGet() <= 5) AutoDem.log("data read failed, using the next source: " + ex);
    }

    /**
     * Koppen class (Beck numbering) at a place: from the installed climate
     * GeoTIFF, else the bundled map (with wobbly borders); 0 if unknown/ocean.
     */
    private int climateClass(double lon, double la, double px, double py, double pz) {
        if (data.climate.isEmpty()) {
            BuiltinClimate bc = BuiltinClimate.get();
            if (bc == null) return 0;
            // wobble the lookup so the map's grid cells get natural borders
            double jx = patchNoise.fbm(px + 777, py, pz, 500 * scale, 3) * 0.2;
            double jy = patchNoise.fbm(px, py + 777, pz, 500 * scale, 3) * 0.2;
            double wlat = Math.max(-90, Math.min(90, la + jy));
            double wlon = lon + jx / Math.max(0.2, Math.cos(Math.toRadians(la)));
            int k = bc.at(wlon, wlat);
            return k != 0 ? k : nearestLandClass(bc, wlon, wlat);
        }
        try {
            double k = data.climate.nearest(lon, la);
            return Double.isNaN(k) ? 0 : (int) k;
        } catch (RuntimeException ex) {
            dataError(ex);
            return 0;
        }
    }

    /** For coastal cells the map calls ocean: the class of the nearest land cell. */
    private static int nearestLandClass(BuiltinClimate bc, double lon, double lat) {
        double step = 360.0 / bc.width();
        for (int r = 1; r <= 4; r++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != r) continue;
                    int k = bc.at(lon + dx * step, lat + dy * step);
                    if (k != 0) return k;
                }
            }
        }
        return 0;
    }

    private static boolean[] or(boolean[] a, boolean[] b) {
        boolean[] o = new boolean[a.length];
        for (int i = 0; i < a.length; i++) o[i] = a[i] || b[i];
        return o;
    }

    private void classify(Tile t, int o, int i, boolean valid, boolean sea, boolean lake, Zone zone, double lat,
                          double lon, double base, double h, int slope, float distSea, int waterDepth,
                          double x, double y, double z, boolean boulder) {
        double alat = Math.abs(lat);
        double v = styleNoise.fbm(x, y, z, 24, 2);
        if (!valid) {
            set(t, o, "ocean", S_SAND);
            return;
        }
        if (sea) {
            boolean deep = waterDepth >= settings.oceanBlocks(500);
            String b;
            if (alat < 20) b = "warm_ocean";
            else if (alat < 35) b = deep ? "deep_lukewarm_ocean" : "lukewarm_ocean";
            else if (alat < 50) b = deep ? "deep_ocean" : "ocean";
            else if (alat < 62) b = deep ? "deep_cold_ocean" : "cold_ocean";
            else b = deep ? "deep_frozen_ocean" : "frozen_ocean";
            int st = waterDepth <= settings.oceanBlocks(150) ? (v > 0.3 ? S_CLAY : S_SAND) : (alat < 20 ? S_SAND : S_GRAVEL);
            set(t, o, b, st);
            return;
        }
        if (lake) {
            set(t, o, cold(zone) && alat > 55 ? "frozen_river" : "river", v > 0.25 ? S_CLAY : v < -0.2 ? S_GRAVEL : S_SAND);
            return;
        }

        double snowM = snowLine(alat);
        double snowH = settings.landBlocks(snowM);
        double treeH = settings.landBlocks(Math.max(0, snowM - 900));
        // at 1:10 and 1:1 a climate-map pixel (~3 km) of mountain-top tundra would spill far down the slopes
        if (settings.fine() && (zone == Zone.TUNDRA || zone == Zone.ICE) && alat < 55 && h < treeH) {
            zone = alat < 45 ? Zone.TEMPERATE : Zone.BOREAL;
        }

        // base biome from the climate zone, varied in patches
        String biome = pickBiome(zone, x, y, z);
        int style = switch (biome) {
            case "desert" -> S_DESERT;
            case "badlands", "wooded_badlands" -> S_BADLANDS;
            case "old_growth_spruce_taiga", "old_growth_pine_taiga" -> v > 0.2 ? S_DRY : S_PODZOL;
            case "savanna" -> v > 0.3 ? S_DRY : S_GRASS;
            case "ice_spikes" -> S_SNOW;
            default -> S_GRASS;
        };
        if (zone == Zone.ICE && biome.equals("snowy_plains")) style = S_ICE;
        if (zone == Zone.STEPPE && biome.equals("plains")) style = S_DRY;
        if (zone == Zone.MEDITERRANEAN && biome.equals("plains") && v > 0.1) style = S_DRY;
        if (biome.equals("savanna") && h > settings.landBlocks(700)) biome = "savanna_plateau";
        if (biome.equals("badlands") && v > 0.1) biome = "wooded_badlands";

        // wetlands
        if (h <= Math.max(2, settings.landBlocks(40)) && distSea <= 6) {
            double w = patchNoise.fbm(x + 999, y, z, 90, 2);
            if ((zone == Zone.TROPICAL || zone == Zone.SAVANNA) && w > 0.05) {
                biome = "mangrove_swamp";
                style = S_MUD;
            } else if (zone == Zone.TEMPERATE && w > 0.15) {
                biome = "swamp";
                style = S_GRASS;
            }
        }

        // mountains: snow above the snow line; bare scree and rock between the
        // tree line and the snow line; below that, steep ground is rock
        // (banded terracotta cliffs in dry climates)
        boolean dry = zone == Zone.DESERT || zone == Zone.STEPPE;
        if (zone != Zone.ICE && h >= snowH) {
            if (slope >= 4) biome = h >= snowH + 60 ? "jagged_peaks" : "frozen_peaks";
            else biome = "snowy_slopes";
            style = slope >= 4 || (slope == 3 && v > 0.15) ? S_ROCK : S_SNOW;
        } else if (zone != Zone.ICE && h >= treeH && h > settings.landBlocks(450)) {
            if (dry) {
                biome = "badlands";
                style = slope >= 3 ? S_BAND_CLIFF : S_BADLANDS;
            } else {
                biome = zone == Zone.TROPICAL || zone == Zone.SAVANNA ? "stony_peaks" : "windswept_gravelly_hills";
                style = slope >= 4 ? S_ROCK : v > 0.2 ? S_ROCK : v > -0.15 ? S_SCREE : S_DRY;
            }
        } else if ((slope >= 2 && dry) || (slope >= 4 && zone == Zone.MEDITERRANEAN && h > settings.landBlocks(900))) {
            biome = v > 0.3 ? "wooded_badlands" : "badlands";
            style = S_BAND_CLIFF;
        } else if (slope >= 4) {
            style = v > 0.25 ? S_SCREE : S_ROCK;
            if (zone == Zone.TEMPERATE && h > settings.landBlocks(1200) && biome.contains("forest")) biome = "windswept_forest";
            else if (zone == Zone.TEMPERATE && h > settings.landBlocks(1200)) biome = "windswept_hills";
        }

        if (settings.minecraftFeel()) {
            double v2 = styleNoise.fbm(x + 3333, y, z, 12, 2), v3 = styleNoise.fbm(x - 5000, y, z, 9, 2);
            // more of vanilla's biomes, where they fit
            if (zone == Zone.TEMPERATE && lon > 120 && lon < 146 && lat > 22 && lat < 44 && h > settings.landBlocks(80)
                && h < treeH && slope < 4 && patch(x + 1.0e5, y, z, 700) > 0.8) {
                biome = "cherry_grove";
                style = S_GRASS;
            }
            if (biome.equals("birch_forest") && v > 0.3) biome = "old_growth_birch_forest";
            if (biome.equals("savanna") && slope >= 3) biome = "windswept_savanna";
            if (biome.equals("badlands") && style == S_BAND_CLIFF && v > 0.3) biome = "eroded_badlands";
            if ((zone == Zone.TEMPERATE || zone == Zone.BOREAL) && h >= settings.landBlocks(1000) && h < treeH && slope <= 2
                && !biome.contains("peaks") && !biome.equals("cherry_grove") && v2 > 0.2) {
                biome = "meadow";
                style = S_GRASS;
            }
            if (zone == Zone.BOREAL && h >= settings.landBlocks(Math.max(0, snowM - 1400)) && h < treeH && slope < 4) {
                biome = "grove";
                style = S_SNOW;
            }
            if (nearMushroomIsland(lat, lon)) {
                biome = "mushroom_fields";
                style = S_MYCELIUM;
            }
            // patches, so the ground isn't one block for kilometres
            if (style == S_GRASS && !biome.equals("mushroom_fields")) {
                if ((zone == Zone.STEPPE || zone == Zone.SAVANNA || zone == Zone.MEDITERRANEAN) && v2 > 0.3) style = S_DRY;
                else if ((biome.contains("forest") || biome.contains("taiga") || biome.contains("jungle")) && v2 < -0.45) style = S_MOSS;
                else if (v3 > 0.6) style = S_GRAVEL_PATCH;
            }
            if (slope >= 2 && slope < 4 && v3 < -0.5 && style != S_SNOW && style != S_ICE && style != S_MYCELIUM) style = S_ANDESITE;
        }

        // coasts
        if (distSea <= (settings.fine() ? 10 : 4) && h <= 3 && slope < 3 && !biome.contains("swamp")) {
            biome = cold(zone) ? "snowy_beach" : "beach";
            style = S_SAND;
        } else if (distSea <= 3 && slope >= 3 && h <= 20) {
            biome = "stony_shore";
            style = S_ROCK;
        }
        if (boulder) {
            boolean humid = zone == Zone.TROPICAL || zone == Zone.TEMPERATE || biome.contains("taiga") || biome.contains("swamp");
            style = humid && v > -0.1 ? S_MOSSY_BOULDER : S_BOULDER;
        }
        set(t, o, biome, style);
        if ((t.feature[o] & 1) != 0) { // what the caves below look like
            boolean lush = zone == Zone.TROPICAL || biome.contains("jungle") || biome.contains("swamp")
                || biome.equals("dark_forest") || biome.equals("flower_forest") || biome.equals("cherry_grove");
            boolean drip = zone == Zone.DESERT || zone == Zone.STEPPE || zone == Zone.SAVANNA || zone == Zone.MEDITERRANEAN
                || h >= treeH || biome.contains("badlands");
            t.caveBiome[o] = (byte) (lush ? Palette.biome("lush_caves") : drip ? Palette.biome("dripstone_caves") : -1);
        }
    }

    private String pickBiome(Zone zone, double x, double y, double z) {
        Pick[] picks = ZONE_BIOMES.get(zone);
        double u = patch(x, y, z, 650 * Math.min(3, Math.sqrt(scale)));
        int total = 0;
        for (Pick p : picks) total += p.weight;
        double acc = 0;
        for (Pick p : picks) {
            acc += p.weight / (double) total;
            if (u < acc) return p.biome;
        }
        return picks[picks.length - 1].biome;
    }

    private static void set(Tile t, int o, String biome, int style) {
        t.biome[o] = (byte) Palette.biome(biome);
        t.style[o] = (byte) style;
    }

    private static double interp(double v, double[] xs, double[] ys) {
        if (v <= xs[0]) return ys[0];
        for (int i = 1; i < xs.length; i++) {
            if (v <= xs[i]) return ys[i - 1] + (ys[i] - ys[i - 1]) * (v - xs[i - 1]) / (xs[i] - xs[i - 1]);
        }
        return ys[ys.length - 1];
    }

    /** Human-readable description of a block column (for F3 and commands). */
    public String describe(int x, int z) {
        double[] ll = new double[2];
        int kind = projection.inverse(x + 0.5, z + 0.5, ll);
        if (kind == CubeProjection.OUTSIDE) return "outside the globe";
        CubeProjection.Face f = projection.faceAt(x + 0.5, z + 0.5);
        String where = f != null ? f.name + " face" : "seam margin";
        return String.format("%.5f%s %.5f%s (%s)", Math.abs(ll[1]), ll[1] >= 0 ? "N" : "S",
            Math.abs(ll[0]), ll[0] >= 0 ? "E" : "W", where);
    }
}
