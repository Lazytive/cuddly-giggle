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
            double want = s.seaLevel() + s.landBlocks(3460);
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
