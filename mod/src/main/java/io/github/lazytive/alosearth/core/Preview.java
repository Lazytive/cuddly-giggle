package io.github.lazytive.alosearth.core;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

import static io.github.lazytive.alosearth.core.Palette.*;

/**
 * Renders a top-down PNG of the terrain without Minecraft, for checking data
 * setup and tuning. Usage:
 *
 * <pre>
 * java -cp alos-earth.jar io.github.lazytive.alosearth.core.Preview \
 *     --aw3d30 DIR --fill DIR --bathymetry DIR --climate FILE \
 *     --bbox SOUTH,WEST,NORTH,EAST [--step BLOCKS] [--center LAT,LON] [--true-scale | --one-to-ten | --one-to-five | --max | --one-to-one] [--auto DIR] -o out.png
 * </pre>
 */
public final class Preview {
    private static final int[] COLORS = new int[BLOCKS.length];

    static {
        COLORS[STONE] = 0x7d7d7d;
        COLORS[DIRT] = 0x866043;
        COLORS[GRASS] = 0x7ab356;
        COLORS[SAND] = 0xdbcfa3;
        COLORS[SANDSTONE] = 0xd8cb9b;
        COLORS[RED_SAND] = 0xbe6621;
        COLORS[TERRACOTTA] = 0x985e43;
        COLORS[ORANGE_TC] = 0xa15325;
        COLORS[YELLOW_TC] = 0xba8523;
        COLORS[WHITE_TC] = 0xd1b2a1;
        COLORS[RED_TC] = 0x8f3d2e;
        COLORS[BROWN_TC] = 0x4d3323;
        COLORS[LIGHT_GRAY_TC] = 0x876b62;
        COLORS[GRAVEL] = 0x887e7e;
        COLORS[SNOW_BLOCK] = 0xf0fbfb;
        COLORS[PACKED_ICE] = 0x8db4fa;
        COLORS[WATER] = 0x3f76e4;
        COLORS[COARSE_DIRT] = 0x77553b;
        COLORS[PODZOL] = 0x5b3f18;
        COLORS[CLAY] = 0xa0a6b3;
        COLORS[MUD] = 0x3c393d;
        COLORS[DEEPSLATE] = 0x505050;
        COLORS[BEDROCK] = 0x333333;
    }

    /** Grass tint by biome, roughly like the game's colour map. */
    static int grassTint(String b) {
        return switch (b) {
            case "desert", "savanna", "savanna_plateau", "badlands", "wooded_badlands" -> 0xbfb755;
            case "jungle", "sparse_jungle", "bamboo_jungle" -> 0x59c93c;
            case "swamp", "mangrove_swamp" -> 0x6a7039;
            case "dark_forest" -> 0x507a32;
            case "taiga", "old_growth_pine_taiga", "old_growth_spruce_taiga", "grove" -> 0x86b87f;
            case "snowy_taiga", "snowy_plains", "snowy_slopes", "ice_spikes", "frozen_river", "snowy_beach" -> 0xe8f0f0;
            case "meadow" -> 0x83bb6d;
            case "windswept_hills", "windswept_forest", "windswept_gravelly_hills", "stony_shore" -> 0x8ab689;
            default -> 0x79c05a;
        };
    }

    public static BufferedImage render(Terrain t, int x0, int z0, int w, int h, int step) {
        int[] surf = new int[w * h], color = new int[w * h];
        boolean[] wet = new boolean[w * h];
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int x = x0 + i * step, z = z0 + j * step, o = j * w + i;
                Terrain.Tile tile = t.tileAt(x, z);
                int k = Terrain.index(x, z);
                int top = tile.top[k], water = tile.water[k];
                int b = t.block(tile, k, x, top, z);
                int c = b == GRASS ? grassTint(BIOMES[tile.biome[k]]) : COLORS[b];
                if (water > top) {
                    c = mix(c, 0x1d3f9a, 0.35 + Math.min(0.75, (water - top) / 30.0) * 0.6);
                    wet[o] = true;
                }
                surf[o] = Math.max(top, water);
                color[o] = c;
            }
        }
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int o = j * w + i;
                double shade = 1;
                if (i > 0 && j > 0 && !wet[o]) {
                    shade = 1 + (surf[o] - surf[o - w - 1]) * 0.08 / Math.sqrt(step);
                    shade = Math.max(0.55, Math.min(1.4, shade));
                }
                img.setRGB(i, j, scale(color[o], shade));
            }
        }
        return img;
    }

    static int mix(int a, int b, double f) {
        int r = (int) (((a >> 16) & 255) * (1 - f) + ((b >> 16) & 255) * f);
        int g = (int) (((a >> 8) & 255) * (1 - f) + ((b >> 8) & 255) * f);
        int bl = (int) ((a & 255) * (1 - f) + (b & 255) * f);
        return (r << 16) | (g << 8) | bl;
    }

    static int scale(int c, double s) {
        int r = (int) Math.min(255, ((c >> 16) & 255) * s);
        int g = (int) Math.min(255, ((c >> 8) & 255) * s);
        int b = (int) Math.min(255, (c & 255) * s);
        return (r << 16) | (g << 8) | b;
    }

    public static void main(String[] args) throws Exception {
        List<Path> aw = new ArrayList<>(), fill = new ArrayList<>(), bathy = new ArrayList<>(), clim = new ArrayList<>();
        String bbox = null, out = "preview.png";
        Path auto = null;
        boolean trueScale = false, oneToOne = false, oneToTen = false, oneToFive = false, max = false;
        int step = 0;
        double clat = EarthSettings.DEFAULT.centerLat(), clon = EarthSettings.DEFAULT.centerLon();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--aw3d30" -> aw.add(Path.of(args[++i]));
                case "--fill" -> fill.add(Path.of(args[++i]));
                case "--bathymetry" -> bathy.add(Path.of(args[++i]));
                case "--climate" -> clim.add(Path.of(args[++i]));
                case "--auto" -> auto = Path.of(args[++i]);
                case "--true-scale" -> trueScale = true;
                case "--one-to-one" -> oneToOne = true;
                case "--one-to-ten" -> oneToTen = true;
                case "--one-to-five" -> oneToFive = true;
                case "--max" -> max = true;
                case "--bbox" -> bbox = args[++i];
                case "--step" -> step = Integer.parseInt(args[++i]);
                case "--center" -> {
                    String[] c = args[++i].split(",");
                    clat = Double.parseDouble(c[0]);
                    clon = Double.parseDouble(c[1]);
                }
                case "-o" -> out = args[++i];
                default -> throw new IllegalArgumentException("unknown argument " + args[i]);
            }
        }
        if (bbox == null) throw new IllegalArgumentException("--bbox SOUTH,WEST,NORTH,EAST is required");
        EarthSettings d = oneToOne ? EarthSettings.ONE_TO_ONE : oneToTen ? EarthSettings.ONE_TO_TEN : oneToFive ? EarthSettings.ONE_TO_FIVE : max ? EarthSettings.MAX
            : trueScale ? EarthSettings.TRUE_SCALE : EarthSettings.MINECRAFT_LIKE;
        EarthSettings s = d.withCenter(clat, clon);
        Terrain t = new Terrain(s, new DataSources(aw, fill, bathy, clim, auto, 512L << 20));
        String[] b = bbox.split(",");
        double south = Double.parseDouble(b[0]), west = Double.parseDouble(b[1]);
        double north = Double.parseDouble(b[2]), east = Double.parseDouble(b[3]);
        double xmin = Double.MAX_VALUE, xmax = -Double.MAX_VALUE, zmin = Double.MAX_VALUE, zmax = -Double.MAX_VALUE;
        double[] p = new double[2];
        for (int i = 0; i <= 8; i++) {
            for (int j = 0; j <= 8; j++) {
                t.projection.forward(west + (east - west) * i / 8, south + (north - south) * j / 8, p);
                xmin = Math.min(xmin, p[0]);
                xmax = Math.max(xmax, p[0]);
                zmin = Math.min(zmin, p[1]);
                zmax = Math.max(zmax, p[1]);
            }
        }
        if (step <= 0) step = (int) Math.max(1, Math.ceil(Math.max(xmax - xmin, zmax - zmin) / 1600));
        int w = (int) ((xmax - xmin) / step), h = (int) ((zmax - zmin) / step);
        long t0 = System.nanoTime();
        BufferedImage img = render(t, (int) xmin, (int) zmin, w, h, step);
        ImageIO.write(img, "png", new File(out));
        System.out.printf("wrote %s: %dx%d px, 1 px = %d blocks, x %d..%d z %d..%d, %.1f s (%s)%n", out, w, h, step,
            (int) xmin, (int) xmax, (int) zmin, (int) zmax, (System.nanoTime() - t0) / 1e9, t.data.describe());
    }
}
