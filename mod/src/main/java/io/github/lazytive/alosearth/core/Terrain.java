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
        patchNoise = new Noise(SEED + 2), styleNoise = new Noise(SEED + 3);
    private final double radius;
    private final Map<Long, Tile> cache = new LinkedHashMap<>(256, 0.75f, true);

    /** One 64x64 tile of columns, indexed [z * 64 + x] relative to the tile corner. */
    public static final class Tile {
        public final int tx, tz;
        public final int[] top = new int[TILE * TILE];
        public final int[] water = new int[TILE * TILE];
        public final byte[] biome = new byte[TILE * TILE];
        public final byte[] style = new byte[TILE * TILE];

        Tile(int tx, int tz) {
            this.tx = tx;
            this.tz = tz;
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
        Zone.STEPPE, new Pick[] {new Pick("plains", 55), new Pick("sunflower_plains", 10), new Pick("savanna", 15),
            new Pick("desert", 10), new Pick("taiga", 10)},
        Zone.MEDITERRANEAN, new Pick[] {new Pick("plains", 35), new Pick("savanna", 15), new Pick("forest", 25),
            new Pick("sunflower_plains", 10), new Pick("flower_forest", 15)},
        Zone.TEMPERATE, new Pick[] {new Pick("forest", 35), new Pick("plains", 20), new Pick("birch_forest", 15),
            new Pick("dark_forest", 12), new Pick("flower_forest", 8), new Pick("sunflower_plains", 5), new Pick("meadow", 5)},
        Zone.BOREAL, new Pick[] {new Pick("taiga", 50), new Pick("old_growth_spruce_taiga", 15),
            new Pick("old_growth_pine_taiga", 10), new Pick("snowy_taiga", 15), new Pick("plains", 10)},
        Zone.TUNDRA, new Pick[] {new Pick("snowy_plains", 70), new Pick("snowy_taiga", 20), new Pick("ice_spikes", 10)},
        Zone.ICE, new Pick[] {new Pick("snowy_plains", 75), new Pick("ice_spikes", 25)});

    // approximate permanent snow line (metres) by absolute latitude
    private static final double[] SNOW_LAT = {0, 20, 30, 45, 60, 70, 80, 90};
    private static final double[] SNOW_M = {4600, 4800, 4200, 2600, 1300, 500, 100, 0};

    public Terrain(EarthSettings settings, DataSources data) {
        this.settings = settings;
        this.data = data;
        this.projection = new CubeProjection(settings.metersPerBlock(), settings.centerLat(), settings.centerLon(),
            settings.margin());
        this.radius = projection.size * 2 / Math.PI;
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

    private int deep(int x, int y, int z) {
        int minY = settings.minY();
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
        double warp = cell * 0.35;
        double wx = x + patchNoise.fbm(x, y, z, cell * 0.7, 2) * warp;
        double wy = y + patchNoise.fbm(x + 5000, y, z, cell * 0.7, 2) * warp;
        double wz = z + patchNoise.fbm(x, y + 5000, z, cell * 0.7, 2) * warp;
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

        double[] lat = new double[nn], elev = new double[nn], depth = new double[nn];
        double[] px = new double[nn], py = new double[nn], pz = new double[nn];
        byte[] cls = new byte[nn];
        boolean[] valid = new boolean[nn];
        Zone[] zone = new Zone[nn];
        double[] ll = new double[2], smp = new double[2];

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
            double[] v = CubeProjection.lonLatToVec(lon, la);
            px[i] = v[0] * radius;
            py[i] = v[1] * radius;
            pz[i] = v[2] * radius;

            data.aw3d30.sample(lon, la, smp);
            double e = smp[0];
            int c = (int) smp[1];
            if (Double.isNaN(e) && !data.fillDem.isEmpty()) {
                e = data.fillDem.bilinear(lon, la);
                c = e <= 0 ? Rasters.CLS_SEA : Rasters.CLS_LAND;
            }
            double b = Double.NaN;
            if ((Double.isNaN(e) || c == Rasters.CLS_SEA) && !data.bathymetry.isEmpty()) {
                b = data.bathymetry.bilinear(lon, la);
            }
            if (Double.isNaN(e) && !Double.isNaN(b)) {
                e = b;
                c = b < 0 ? Rasters.CLS_SEA : Rasters.CLS_LAND;
            }
            if (Double.isNaN(e)) c = Rasters.CLS_SEA;
            if (c == Rasters.CLS_SEA && !Double.isNaN(b)) depth[i] = Math.max(0, -b);
            elev[i] = e;
            cls[i] = (byte) c;

            Zone zn = null;
            if (!data.climate.isEmpty()) {
                double k = data.climate.nearest(lon, la);
                if (!Double.isNaN(k)) zn = zoneForKoppen((int) k);
            }
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
            double mountain = smoothstep(90, 320, base[i]);
            double ridged = 1 - Math.abs(ridgeNoise.fbm(px[i], py[i], pz[i], 150, 2)) * 2.2;
            h[i] = base[i] + s.detail() * (bumps * coastFade * (1 + 1.5 * mountain) + ridged * 14 * mountain);
        }

        float[] distLand = distance(or(isLand, isLake));
        float[] distSea = distance(isSea);

        int[] top = new int[nn], water = new int[nn];
        int minTop = s.minY() + 1, maxTop = s.maxY() - 1;
        for (int i = 0; i < nn; i++) {
            water[i] = Integer.MIN_VALUE;
            if (isSea[i]) {
                double d;
                double dl = distLand[i] / (double) BORDER;
                if (!Double.isNaN(depth[i])) d = s.oceanBlocks(depth[i]);
                else d = 3 + 27 * smoothstep(0, 1, dl);
                d = Math.min(d, 2 + dl * dl * 200);
                d = Math.max(2, d);
                top[i] = sea - (int) Math.round(d);
                water[i] = sea;
            } else if (isLake[i]) {
                water[i] = sea + (int) Math.round(base[i]);
                top[i] = water[i] - 3;
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

        // --- slope of the visible surface
        int[] surf = new int[nn];
        for (int i = 0; i < nn; i++) {
            top[i] = Math.max(minTop, Math.min(maxTop, top[i]));
            surf[i] = Math.max(top[i], water[i]);
        }

        Tile t = new Tile(tx, tz);
        for (int zz = 0; zz < TILE; zz++) {
            for (int xx = 0; xx < TILE; xx++) {
                int i = (zz + BORDER) * n + (xx + BORDER);
                int o = zz * TILE + xx;
                int slope = 0;
                for (int j : new int[] {i - 1, i + 1, i - n, i + n}) slope = Math.max(slope, Math.abs(surf[i] - surf[j]));
                t.top[o] = top[i];
                t.water[o] = water[i];
                classify(t, o, i, valid[i], isSea[i], lake2[i], zone[i], lat[i], base[i], h[i], slope,
                    distSea[i], sea - top[i], px[i], py[i], pz[i]);
            }
        }
        return t;
    }

    private static boolean[] or(boolean[] a, boolean[] b) {
        boolean[] o = new boolean[a.length];
        for (int i = 0; i < a.length; i++) o[i] = a[i] || b[i];
        return o;
    }

    private void classify(Tile t, int o, int i, boolean valid, boolean sea, boolean lake, Zone zone, double lat,
                          double base, double h, int slope, float distSea, int waterDepth,
                          double x, double y, double z) {
        double alat = Math.abs(lat);
        double v = styleNoise.fbm(x, y, z, 24, 2);
        if (!valid) {
            set(t, o, "ocean", S_SAND);
            return;
        }
        if (sea) {
            boolean deep = waterDepth >= 40;
            String b;
            if (alat < 20) b = "warm_ocean";
            else if (alat < 35) b = deep ? "deep_lukewarm_ocean" : "lukewarm_ocean";
            else if (alat < 50) b = deep ? "deep_ocean" : "ocean";
            else if (alat < 62) b = deep ? "deep_cold_ocean" : "cold_ocean";
            else b = deep ? "deep_frozen_ocean" : "frozen_ocean";
            int st = waterDepth <= 12 ? (v > 0.3 ? S_CLAY : S_SAND) : (alat < 20 ? S_SAND : S_GRAVEL);
            set(t, o, b, st);
            return;
        }
        if (lake) {
            set(t, o, cold(zone) && alat > 55 ? "frozen_river" : "river", v > 0.25 ? S_CLAY : v < -0.2 ? S_GRAVEL : S_SAND);
            return;
        }

        // base biome from the climate zone, varied in patches
        Pick[] picks = ZONE_BIOMES.get(zone);
        double u = patch(x, y, z, 650);
        int total = 0;
        for (Pick p : picks) total += p.weight;
        double acc = 0;
        String biome = picks[picks.length - 1].biome;
        for (Pick p : picks) {
            acc += p.weight / (double) total;
            if (u < acc) {
                biome = p.biome;
                break;
            }
        }
        int style = switch (biome) {
            case "desert" -> S_DESERT;
            case "badlands", "wooded_badlands" -> S_BADLANDS;
            case "old_growth_spruce_taiga", "old_growth_pine_taiga" -> v > 0.2 ? S_DRY : S_PODZOL;
            case "savanna" -> v > 0.3 ? S_DRY : S_GRASS;
            case "ice_spikes" -> S_SNOW;
            default -> S_GRASS;
        };
        if (zone == Zone.ICE && biome.equals("snowy_plains")) style = S_ICE;
        if (biome.equals("savanna") && h > 60) biome = "savanna_plateau";
        if (biome.equals("badlands") && v > 0.1) biome = "wooded_badlands";

        // wetlands
        if (h <= 4 && distSea <= 6) {
            double w = patchNoise.fbm(x + 999, y, z, 90, 2);
            if ((zone == Zone.TROPICAL || zone == Zone.SAVANNA) && w > 0.05) {
                biome = "mangrove_swamp";
                style = S_MUD;
            } else if (zone == Zone.TEMPERATE && w > 0.15) {
                biome = "swamp";
                style = S_GRASS;
            }
        }

        // mountains
        double snowH = settings.landBlocks(interp(alat, SNOW_LAT, SNOW_M));
        if (zone != Zone.ICE && h >= snowH) {
            if (slope >= 4) biome = h >= snowH + 60 ? "jagged_peaks" : "frozen_peaks";
            else biome = "snowy_slopes";
            style = slope >= 4 || (slope == 3 && v > 0.15) ? S_ROCK : S_SNOW;
        } else if (zone != Zone.ICE && h >= snowH - 70 && h > 40) {
            switch (zone) {
                case BOREAL, TUNDRA, STEPPE -> biome = "grove";
                case TEMPERATE, MEDITERRANEAN -> biome = slope >= 3 ? "windswept_hills" : "meadow";
                default -> {
                    if (slope >= 3) biome = "stony_peaks";
                }
            }
            if (slope >= 3) style = biome.equals("stony_peaks") || slope >= 5 ? S_ROCK : (v > 0.1 ? S_SCREE : S_ROCK);
        } else if (slope >= 4) {
            if (style == S_BADLANDS || style == S_DESERT) style = style == S_BADLANDS ? S_BAND_CLIFF : S_ROCK;
            else style = v > 0.25 ? S_SCREE : S_ROCK;
            if (zone == Zone.TEMPERATE && h > 100 && biome.contains("forest")) biome = "windswept_forest";
            else if (zone == Zone.TEMPERATE && h > 100) biome = "windswept_hills";
        }

        // coasts
        if (distSea <= 4 && h <= 3 && slope < 3 && !biome.contains("swamp")) {
            biome = cold(zone) ? "snowy_beach" : "beach";
            style = S_SAND;
        } else if (distSea <= 3 && slope >= 3 && h <= 20) {
            biome = "stony_shore";
            style = S_ROCK;
        }
        set(t, o, biome, style);
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
