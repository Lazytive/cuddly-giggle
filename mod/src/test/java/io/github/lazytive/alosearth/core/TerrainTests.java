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

        run("1:5 option: the largest fully to-scale world", () -> {
            EarthSettings s = EarthSettings.ONE_TO_FIVE;
            check(Math.abs(s.landBlocks(8849) - 1769.8) < 1e-9 && s.seaLevel() + s.landBlocks(8849) + 60 < s.maxY(), "Everest fits");
            check(s.seaLevel() - s.oceanBlocks(10935) > s.minY() + 5, "Challenger Deep fits");
            check(s.fine() && s.deepLayers() == 0, "one dimension");
            int[] b = new CubeProjection(s.metersPerBlock(), s.centerLat(), s.centerLon(), s.margin()).bounds();
            for (int v : b) check(Math.abs(v) < 29_999_000, "map inside the world border");
            Terrain t = new Terrain(s, sources(data));
            double[] p = new double[2];
            t.projection.forward(139.5, 35.5, p);
            int top = t.top((int) Math.floor(p[0]), (int) Math.floor(p[1]));
            check(Math.abs(top - (s.seaLevel() + 614.2)) < 15, "1:5 peak at " + top);
            // anything deeper than the world allows stops just above the bedrock band
            check(t.block((int) Math.floor(p[0]), s.minY(), (int) Math.floor(p[1])) == Palette.DEEPSLATE, "no bedrock: the floor can be dug through");
        });

        run("max option: Everest at the top, the Challenger Deep at the bottom", () -> {
            EarthSettings s = EarthSettings.MAX;
            double everest = s.seaLevel() + s.landBlocks(8849), deep = s.seaLevel() - s.oceanBlocks(10935);
            check(everest > s.maxY() - 30 && everest < s.maxY() - 12, "Everest at " + everest);
            check(deep < s.minY() + 12 && deep > s.minY() + 5, "Challenger Deep at " + deep);
            int[] b = new CubeProjection(s.metersPerBlock(), s.centerLat(), s.centerLon(), s.margin()).bounds();
            for (int v : b) check(Math.abs(v) < 29_999_000, "map inside the world border");
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
            check(t.block(x, s.bottomY(), z) == Palette.DEEPSLATE && t.block(x, s.minY(), z) != Palette.BEDROCK,
                "no bedrock (new worlds): the bottom of the last layer can be dug through");
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

        run("Minecraft-feel features match across seams and exist", () -> {
            EarthSettings s = EarthSettings.MINECRAFT_LIKE;
            Terrain t = new Terrain(s, sources(data));
            CubeProjection pr = t.projection;
            Random r = new Random(5);
            int checked = 0;
            for (CubeProjection.Link l : pr.links) {
                for (int k = 0; k < 30; k++) {
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
                        && a.style[ia] == b.style[ib] && a.feature[ia] == b.feature[ib] && a.caveBiome[ia] == b.caveBiome[ib],
                        "margin differs at " + x + "," + z + " (" + l + ")");
                    for (int y = a.top[ia] - 70; y <= a.top[ia]; y += 3) {
                        if (y < 8 && y >= 0) continue; // the stone/deepslate dithering is per block position, deep in the rock
                        check(t.block(a, ia, x, y, z) == t.block(b, ib, ox, y, oz), "block differs at " + x + "," + y + "," + z);
                    }
                    checked++;
                }
            }
            check(checked > 300, "only " + checked + " checked");
            // features show up around the synthetic mountain
            double[] p = new double[2];
            t.projection.forward(139.5, 35.5, p);
            int cx = (int) Math.floor(p[0]), cz = (int) Math.floor(p[1]);
            int caveAir = 0, solid = 0, boulders = 0, overhangs = 0, caveCols = 0, patches = 0;
            for (int dz = -600; dz < 600; dz += 3) {
                for (int dx = -600; dx < 600; dx += 3) {
                    int x = cx + dx, z = cz + dz;
                    Terrain.Tile tile = t.tileAt(x, z);
                    int i = Terrain.index(x, z);
                    int st = tile.style[i];
                    if (st == Palette.S_BOULDER || st == Palette.S_MOSSY_BOULDER) boulders++;
                    if (st == Palette.S_MOSS || st == Palette.S_GRAVEL_PATCH || st == Palette.S_ANDESITE || st == Palette.S_DRY) patches++;
                    if (((tile.feature[i] >> 4) & 7) != 0) overhangs++;
                    if ((tile.feature[i] & 1) != 0) {
                        caveCols++;
                        for (int y = tile.top[i] - 60; y < tile.top[i] - 5; y += 2) {
                            if (t.block(tile, i, x, y, z) == Palette.AIR) caveAir++;
                            else solid++;
                        }
                    }
                }
            }
            double frac = caveAir / (double) Math.max(1, caveAir + solid);
            System.out.printf("     boulders %d, overhang columns %d, patches %d, cave columns %d, cave air %.2f%%%n",
                boulders, overhangs, patches, caveCols, frac * 100);
            check(boulders > 0 && patches > 0 && caveCols > 0, "features missing"); // overhangs need real cliffs: see the Alps test
            check(frac > 0.002 && frac < 0.06, "caves should be rare but present: " + frac);
            // streams and rivers: present, and the water never hangs above neighbouring dry land
            int riverCols = 0, spills = 0;
            for (int dz = -900; dz < 900; dz++) {
                for (int dx = -900; dx < 900; dx++) {
                    int x = cx + dx, z = cz + dz;
                    Terrain.Tile tile = t.tileAt(x, z);
                    int i = Terrain.index(x, z);
                    if (!Palette.BIOMES[tile.biome[i]].contains("river") || tile.water[i] <= tile.top[i]) continue;
                    riverCols++;
                    for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                        Terrain.Tile nt = t.tileAt(x + d[0], z + d[1]);
                        int j = Terrain.index(x + d[0], z + d[1]);
                        if (nt.water[j] <= nt.top[j] && nt.top[j] < tile.water[i]) spills++;
                    }
                }
            }
            System.out.printf("     river columns %d, spilling edges %d%n", riverCols, spills);
            check(riverCols > 500, "no rivers on the synthetic mountain: " + riverCols);
            check(spills < riverCols / 50, "too much river water next to lower dry land: " + spills);
            // the old terrain is untouched for worlds made before (version 0)
            Terrain old = new Terrain(EarthSettings.DEFAULT.withSeaFloor(true), sources(data));
            Terrain.Tile ot = old.tileAt(cx, cz);
            for (int k = 0; k < ot.feature.length; k++) check(ot.feature[k] == 0 && ot.caveBiome[k] == -1, "old worlds must not change");
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

        if ("1".equals(System.getenv("ALOSEARTH_NET"))) {
            run("land below sea level stays dry, flat water is a lake", () -> {
                Path dir = java.nio.file.Files.createTempDirectory("alosearth-low");
                AutoDem auto = new AutoDem(dir, new Rasters.SegmentCache(128L << 20));
                double[] lvl = new double[1];
                check(auto.demClass(4.65, 52.30, lvl) == Rasters.CLS_LAND && lvl[0] < -3, "Dutch polder: " + lvl[0]);
                check(auto.demClass(-116.85, 36.25, lvl) == Rasters.CLS_LAND, "Death Valley");
                check(auto.demClass(35.50, 31.50, lvl) == Rasters.CLS_LAKE && Math.abs(lvl[0] + 427) < 2, "Dead Sea: " + lvl[0]);
                check(auto.demClass(4.20, 52.50, lvl) == Rasters.CLS_SEA, "North Sea");
                DataSources ds = new DataSources(List.of(), List.of(), List.of(), List.of(), dir, 256L << 20);
                EarthSettings s = EarthSettings.ONE_TO_FIVE;
                Terrain t = new Terrain(s, ds);
                double[] p = new double[2];
                t.projection.forward(4.65, 52.30, p);
                int x = (int) Math.floor(p[0]), z = (int) Math.floor(p[1]);
                check(t.top(x, z) < s.seaLevel() && t.surface(x, z) == t.top(x, z), "polder should be dry land below sea level: top "
                    + t.top(x, z) + " surface " + t.surface(x, z) + " " + t.biome(x, z));
                t.projection.forward(35.50, 31.50, p);
                x = (int) Math.floor(p[0]);
                z = (int) Math.floor(p[1]);
                int want = s.seaLevel() + (int) Math.round(-427 / 5.0);
                check(Math.abs(t.surface(x, z) - want) <= 1 && t.top(x, z) < t.surface(x, z), "Dead Sea water at " + t.surface(x, z)
                    + " (want " + want + "), bed " + t.top(x, z));
            });
        }

        if ("1".equals(System.getenv("ALOSEARTH_NET"))) {
            run("real mountains get ledges, cliffs, overhangs and boulders", () -> {
                Path dir = java.nio.file.Files.createTempDirectory("alosearth-alps");
                DataSources ds = new DataSources(List.of(), List.of(), List.of(), List.of(), dir, 256L << 20);
                Terrain t = new Terrain(EarthSettings.MINECRAFT_LIKE, ds);
                double[] p = new double[2];
                t.projection.forward(7.66, 45.98, p); // the Matterhorn
                int cx = (int) p[0], cz = (int) p[1], cliffs = 0, overhangs = 0, boulders = 0, cols = 0;
                for (int dz = -300; dz < 300; dz += 2) {
                    for (int dx = -300; dx < 300; dx += 2) {
                        int x = cx + dx, z = cz + dz, top = t.top(x, z);
                        int low = Math.min(Math.min(t.top(x - 1, z), t.top(x + 1, z)), Math.min(t.top(x, z - 1), t.top(x, z + 1)));
                        Terrain.Tile tile = t.tileAt(x, z);
                        int i = Terrain.index(x, z);
                        cols++;
                        if (top - low >= 4) cliffs++;
                        if (((tile.feature[i] >> 4) & 7) != 0) overhangs++;
                        if (tile.style[i] == Palette.S_BOULDER || tile.style[i] == Palette.S_MOSSY_BOULDER) boulders++;
                    }
                }
                System.out.printf("     Matterhorn area: cliffs %.1f%%, overhangs %.2f%%, boulders %.2f%%%n",
                    100.0 * cliffs / cols, 100.0 * overhangs / cols, 100.0 * boulders / cols);
                check(cliffs > cols / 100 && overhangs > 0 && boulders > 0, "mountain features missing");
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
