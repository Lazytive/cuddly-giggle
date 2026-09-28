package io.github.lazytive.alosearth.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static io.github.lazytive.alosearth.core.CoreTests.check;
import static io.github.lazytive.alosearth.core.CoreTests.run;

final class TerrainTests {
    private TerrainTests() {
    }

    static DataSources sources(Path data) {
        Path d = data.resolve("data");
        return new DataSources(List.of(d.resolve("aw3d30")), List.of(), List.of(d.resolve("gebco.tif")),
            List.of(d.resolve("climate")), 256L << 20);
    }

    /** A GeoJSON ring (closed) of the box lon0+w..lon0+e, lat0+s..lat0+n. */
    static String square(double lon0, double lat0, double w, double e, double s, double n) {
        double[][] pts = {{w, s}, {e, s}, {e, n}, {w, n}, {w, s}};
        StringBuilder b = new StringBuilder("[");
        for (double[] q : pts) b.append(b.length() > 1 ? "," : "").append("[").append(lon0 + q[0]).append(",").append(lat0 + q[1]).append("]");
        return b.append("]").toString();
    }

    static void register(Path data) {
        run("height curve fits the world", () -> {
            EarthSettings s = EarthSettings.DEFAULT;
            double everest = s.seaLevel() + s.landBlocks(8849);
            double trench = s.seaLevel() - s.oceanBlocks(11000);
            check(everest > 580 && everest < s.maxY() - 25, "everest at " + everest);
            check(trench > s.minY() + 5, "trench at " + trench);
            check(Math.abs(s.landBlocks(100) - 10) < 0.5, "lowlands ~3x: " + s.landBlocks(100));
        });

        run("true-scale option keeps real proportions", () -> {
            EarthSettings s = EarthSettings.TRUE_SCALE;
            check(Math.abs(s.landBlocks(8849) - 295) < 1 && Math.abs(s.landBlocks(300) - 10) < 1e-9, "land 1:30");
            check(Math.abs(s.oceanBlocks(3000) - 100) < 1e-9, "sea 1:30");
            check(s.seaLevel() + s.landBlocks(8849) + 20 < s.maxY(), "Everest fits");
            check(s.seaLevel() - s.oceanBlocks(11000) > s.minY() + 5, "Mariana Trench fits");
            Terrain t = new Terrain(s, sources(data));
            double[] p = new double[2];
            t.projection.forward(139.5, 35.5, p);
            int top = t.top((int) Math.floor(p[0]), (int) Math.floor(p[1]));
            double want = s.seaLevel() + 3071 / 30.0; // synthetic summit: 3000 + 11 + 60 m
            check(Math.abs(top - want) < 8, "true-scale peak at " + top + ", expected about " + want);
        });

        run("1:10 option: everything to scale in one dimension", () -> {
            EarthSettings s = EarthSettings.ONE_TO_TEN;
            check(Math.abs(s.landBlocks(8849) - 884.9) < 1e-9 && Math.abs(s.oceanBlocks(10994) - 1099.4) < 1e-9, "1:10");
            check(s.seaLevel() + s.landBlocks(8849) + 60 < s.maxY(), "Everest fits");
            check(s.seaLevel() - s.oceanBlocks(10994) > s.minY() + 50, "Mariana Trench fits");
            check(s.fine() && s.bottomY() == s.minY(), "smooth sampling, no deep layers");
            int[] b = new CubeProjection(s.metersPerBlock(), s.centerLat(), s.centerLon(), s.margin()).bounds();
            for (int v : b) check(Math.abs(v) < 29_999_000, "map inside the world border");
            Terrain t = new Terrain(s, sources(data));
            double[] p = new double[2];
            t.projection.forward(139.5, 35.5, p);
            int top = t.top((int) Math.floor(p[0]), (int) Math.floor(p[1]));
            double want = s.seaLevel() + 307.1; // synthetic summit 3071 m
            check(Math.abs(top - want) < 12, "1:10 peak at " + top + ", expected about " + want);
            t.projection.forward(-12.857, -4, p); // ~2.5 km deep synthetic ocean
            int floor = t.top((int) Math.floor(p[0]), (int) Math.floor(p[1]));
            double depth = -t.data.bathymetry.bilinear(-12.857, -4);
            check(Math.abs(floor - (s.seaLevel() - depth / 10)) < 8, "1:10 sea floor " + floor + " for depth " + depth);
        });

        run("1:1 option: real ocean depths through the deep layers, land fits", () -> {
            EarthSettings s = EarthSettings.ONE_TO_ONE;
            double everest = s.seaLevel() + s.landBlocks(8849);
            check(everest > 1900 && everest < s.maxY() - 30, "everest at " + everest);
            check(Math.abs(s.landBlocks(100) - 94) < 2, "hills near 1:1: " + s.landBlocks(100));
            check(s.oceanBlocks(10994) == 10994, "sea 1:1");
            check(s.seaLevel() - 10994 > s.bottomY() + 50, "Mariana Trench fits: bottom " + s.bottomY());
            check(s.layerShift() == s.height() - EarthSettings.LAYER_OVERLAP, "shift");
            for (EarthSettings e : new EarthSettings[] {s, EarthSettings.DEFAULT, EarthSettings.TRUE_SCALE}) {
                for (double m : new double[] {-9000, -300, 0, 250, 3000, 8849}) {
                    double back = e.metersAt(e.seaLevel() + (m < 0 ? -e.oceanBlocks(-m) : e.landBlocks(m)));
                    check(Math.abs(back - m) < 1e-6 * Math.max(1, Math.abs(m)) + 1e-6, "metersAt(" + m + ") = " + back);
                }
            }
            int[] b = new CubeProjection(s.metersPerBlock(), s.centerLat(), s.centerLon(), s.margin()).bounds();
            for (int v : b) check(Math.abs(v) < 29_999_000, "map inside the world border: " + java.util.Arrays.toString(b));

            Terrain t = new Terrain(s, sources(data));
            double[] p = new double[2];
            t.projection.forward(139.5, 35.5, p);
            int top = t.top((int) Math.floor(p[0]), (int) Math.floor(p[1]));
            double want = s.seaLevel() + s.landBlocks(3071);
            check(Math.abs(top - want) < 40, "1:1 peak at " + top + ", expected about " + want);
            // deep synthetic ocean: the floor sits at the real depth, below the main world
            t.projection.forward(-12.857, -4, p);
            int x = (int) Math.floor(p[0]), z = (int) Math.floor(p[1]);
            double depth = -t.data.bathymetry.bilinear(-12.857, -4);
            int floor = t.top(x, z);
            check(depth > 2200, "synthetic depth " + depth);
            check(Math.abs(floor - (s.seaLevel() - depth)) < 20 && floor < s.minY(), "sea floor " + floor + " for depth " + depth);
            check(t.surface(x, z) == s.seaLevel() && t.block(x, floor + 1, z) == Palette.WATER
                && t.block(x, floor - 50, z) == Palette.DEEPSLATE, "water column");
            check(t.block(x, s.bottomY(), z) == Palette.BEDROCK && t.block(x, s.minY(), z) != Palette.BEDROCK,
                "bedrock only at the bottom of the last layer");
        });

        run("OSM buildings stand on the 1:1 terrain", () -> {
            EarthSettings s = EarthSettings.ONE_TO_ONE;
            Terrain t = new Terrain(s, sources(data));
            double lon0 = 139.30, lat0 = 35.40;
            double mLat = 1 / 111320.0, mLon = 1 / (111320.0 * Math.cos(Math.toRadians(lat0)));
            String ring = square(lon0, lat0, -20 * mLon, 20 * mLon, -15 * mLat, 15 * mLat);
            String hole = square(lon0, lat0, 8 * mLon, 16 * mLon, -4 * mLat, 4 * mLat);
            String json = "{\"type\":\"FeatureCollection\",\"features\":[{\"id\":\"w1\",\"type\":\"Feature\","
                + "\"properties\":{\"height\":21,\"color\":\"#b0463a\",\"roofColor\":\"#444\",\"name\":\"Caf\\u00e9 \\\"A\\\"\"},"
                + "\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[" + ring + "," + hole + "]}}]}";
            java.util.List<Buildings.Building> parsed = Buildings.parse(json);
            check(parsed.size() == 1 && parsed.get(0).height() == 21, "parsed " + parsed);
            check(parsed.get(0).wall() == Palette.BRICKS || parsed.get(0).wall() == Palette.RED_TC, "wall colour " + parsed.get(0).wall());
            int n = 1 << Buildings.ZOOM;
            Path dir = java.nio.file.Files.createTempDirectory("alosearth-bld");
            Path tile = dir.resolve(Buildings.tileX(lon0, n) + "/" + Buildings.tileY(lat0, n) + ".json");
            java.nio.file.Files.createDirectories(tile.getParent());
            java.nio.file.Files.writeString(tile, json);
            Buildings b = new Buildings(dir.resolve("cache"), dir.toString());
            double[] p = new double[2];
            t.projection.forward(lon0 - 10 * mLon, lat0, p);
            int x = (int) Math.floor(p[0]), z = (int) Math.floor(p[1]);
            Buildings.Plan plan = b.plan(t, Math.floorDiv(x, 16) * 16, Math.floorDiv(z, 16) * 16);
            int c = Math.floorMod(z, 16) * 16 + Math.floorMod(x, 16);
            check(plan.building[c] >= 0 && !plan.wall[c], "inside column: " + plan.building[c] + " wall " + plan.wall[c]);
            Buildings.Placed pl = plan.placed.get(plan.building[c]);
            check(pl.top() - pl.base() == 21, "height " + (pl.top() - pl.base()));
            int ground = t.top(x, z);
            check(plan.block(c, x, pl.base() + 1, z, ground) == Palette.AIR
                && plan.block(c, x, pl.base() + 4, z, ground) == Palette.SMOOTH_STONE
                && plan.block(c, x, pl.top(), z, ground) == parsed.get(0).roof()
                && plan.block(c, x, pl.top() + 1, z, ground) == -1, "interior blocks");
            t.projection.forward(lon0 - 20 * mLon, lat0, p); // the west wall
            Buildings.Plan edge = b.plan(t, Math.floorDiv((int) Math.floor(p[0]), 16) * 16, Math.floorDiv((int) Math.floor(p[1]), 16) * 16);
            int walls = 0, inside = 0;
            for (int k = 0; k < 256; k++) {
                if (edge.building[k] < 0) continue;
                inside++;
                if (edge.wall[k]) walls++;
            }
            check(walls > 0 && inside > walls, "walls " + walls + " of " + inside);
            // the courtyard stays open
            t.projection.forward(lon0 + 12 * mLon, lat0, p);
            x = (int) Math.floor(p[0]);
            z = (int) Math.floor(p[1]);
            Buildings.Plan plan2 = b.plan(t, Math.floorDiv(x, 16) * 16, Math.floorDiv(z, 16) * 16);
            check(plan2.building[Math.floorMod(z, 16) * 16 + Math.floorMod(x, 16)] < 0, "courtyard is built over");
        });

        run("same input gives the same terrain", () -> {
            Terrain a = new Terrain(EarthSettings.DEFAULT, sources(data));
            Terrain b = new Terrain(EarthSettings.DEFAULT, sources(data));
            double[] p = new double[2];
            a.projection.forward(139.5, 35.5, p);
            int tx = Math.floorDiv((int) p[0], Terrain.TILE), tz = Math.floorDiv((int) p[1], Terrain.TILE);
            Terrain.Tile t1 = a.tile(tx, tz), t2 = b.compute(tx, tz);
            check(java.util.Arrays.equals(t1.top, t2.top) && java.util.Arrays.equals(t1.water, t2.water)
                && java.util.Arrays.equals(t1.biome, t2.biome) && java.util.Arrays.equals(t1.style, t2.style), "tiles differ");
        });

        run("synthetic mountain, lake and sea", () -> {
            Terrain t = new Terrain(EarthSettings.DEFAULT, sources(data));
            EarthSettings s = t.settings;
            double[] p = new double[2];
            t.projection.forward(139.5, 35.5, p);
            int top = t.top((int) Math.floor(p[0]), (int) Math.floor(p[1]));
            double want = s.seaLevel() + s.landBlocks(3071); // synthetic summit: 3000 + 11 + 60 m
            check(Math.abs(top - want) < 30, "peak at " + top + ", expected about " + want);
            t.projection.forward(139.25, 35.75, p); // lake at 500 m
            int x = (int) Math.floor(p[0]), z = (int) Math.floor(p[1]);
            int water = t.surface(x, z);
            check(water == s.seaLevel() + (int) Math.round(s.landBlocks(500)) && t.top(x, z) < water,
                "lake surface " + water + " top " + t.top(x, z) + " biome " + t.biome(x, z));
            check(t.biome(x, z).equals("river"), "lake biome " + t.biome(x, z));
            t.projection.forward(139.95, 35.1, p); // sea
            x = (int) Math.floor(p[0]);
            z = (int) Math.floor(p[1]);
            check(t.surface(x, z) == s.seaLevel() && t.top(x, z) < s.seaLevel() - 1, "sea at " + t.top(x, z));
            check(t.biome(x, z).contains("ocean"), "sea biome " + t.biome(x, z));
            // blocks: bedrock floor, deepslate below 0, stone, then the surface
            check(t.block(x, s.minY(), z) == Palette.BEDROCK, "bedrock");
            check(t.block(x, -20, z) == Palette.DEEPSLATE, "deepslate");
            check(t.block(x, s.seaLevel(), z) == Palette.WATER && t.block(x, s.seaLevel() + 1, z) == Palette.AIR, "water");
        });

        run("seam margins copy the other side exactly", () -> {
            Terrain t = new Terrain(EarthSettings.DEFAULT, sources(data));
            CubeProjection pr = t.projection;
            Random r = new Random(9);
            int checked = 0;
            for (CubeProjection.Link l : pr.links) {
                for (int k = 0; k < 40; k++) {
                    boolean horizontal = l.edge.equals("top") || l.edge.equals("bottom");
                    int along = 40 + r.nextInt((horizontal ? l.sx1 - l.sx0 : l.sz1 - l.sz0) - 80);
                    int depth = 1 + r.nextInt(400);
                    int x, z;
                    switch (l.edge) {
                        case "top" -> { x = l.sx0 + along; z = l.sz1 - depth; }
                        case "bottom" -> { x = l.sx0 + along; z = l.sz0 + depth - 1; }
                        case "left" -> { x = l.sx1 - depth; z = l.sz0 + along; }
                        default -> { x = l.sx0 + depth - 1; z = l.sz0 + along; }
                    }
                    if (pr.linkAt(x + 0.5, z + 0.5) != l) continue;
                    int ox = (int) Math.floor(l.applyX(x + 0.5, z + 0.5)), oz = (int) Math.floor(l.applyZ(x + 0.5, z + 0.5));
                    Terrain.Tile a = t.tileAt(x, z), b = t.tileAt(ox, oz);
                    int ia = Terrain.index(x, z), ib = Terrain.index(ox, oz);
                    check(a.top[ia] == b.top[ib] && a.water[ia] == b.water[ib] && a.biome[ia] == b.biome[ib]
                        && a.style[ia] == b.style[ib], "margin differs at " + x + "," + z + " (" + l + "): top "
                        + a.top[ia] + " vs " + b.top[ib] + ", biome " + Palette.BIOMES[a.biome[ia]] + " vs "
                        + Palette.BIOMES[b.biome[ib]]);
                    checked++;
                }
            }
            check(checked > 400, "only " + checked + " checked");
        });

        if ("1".equals(System.getenv("ALOSEARTH_NET"))) {
            run("auto-download fetches real elevation", () -> {
                Path dir = java.nio.file.Files.createTempDirectory("alosearth-auto");
                long before = AutoDem.BYTES_FETCHED.get();
                AutoDem auto = new AutoDem(dir, new Rasters.SegmentCache(128L << 20));
                double fuji = auto.bilinear(138.7274, 35.3606);
                check(fuji > 3500 && fuji < 3900, "Fuji summit " + fuji);
                check(Double.isNaN(auto.bilinear(-140.2, 0.3)), "open Pacific should have no tile");
                long fetched = AutoDem.BYTES_FETCHED.get() - before;
                check(auto.downloaded.get() == 1 && auto.missing.get() == 1, auto.describe());
                check(fetched > 0 && fetched < 6_000_000, "fetched " + fetched + " bytes (should be a block, not the whole tile)");
                // a second instance reuses the cached files without downloading
                AutoDem again = new AutoDem(dir, new Rasters.SegmentCache(128L << 20));
                long mark = AutoDem.BYTES_FETCHED.get();
                check(Math.abs(again.bilinear(138.7274, 35.3606) - fuji) < 1e-6, "cached value differs");
                check(AutoDem.BYTES_FETCHED.get() == mark, "second run should not download");
            });
        }

        run("tile speed", () -> {
            Terrain t = new Terrain(EarthSettings.DEFAULT, sources(data));
            double[] p = new double[2];
            t.projection.forward(139.2, 35.3, p);
            int tx = Math.floorDiv((int) p[0], Terrain.TILE), tz = Math.floorDiv((int) p[1], Terrain.TILE);
            t.compute(tx, tz);
            long t0 = System.nanoTime();
            for (int i = 1; i <= 20; i++) t.compute(tx + i, tz);
            double ms = (System.nanoTime() - t0) / 1e6 / 20;
            System.out.printf("     %.1f ms per 64x64 tile (%.2f ms per chunk)%n", ms, ms / 16);
            check(ms < 200, "too slow: " + ms + " ms per tile");
        });
    }
}
