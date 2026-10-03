package io.github.lazytive.alosearth.compat;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGeneratorReturnType;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBiomeWrapper;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelLoadEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelUnloadEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.objects.data.DhApiChunk;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import com.seibel.distanthorizons.api.objects.data.IDhApiFullDataSource;
import io.github.lazytive.alosearth.AlosEarth;
import io.github.lazytive.alosearth.EarthChunkGenerator;
import io.github.lazytive.alosearth.LodTrees;
import io.github.lazytive.alosearth.core.Palette;
import io.github.lazytive.alosearth.core.Terrain;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

/**
 * With Distant Horizons installed, its far-away terrain (LODs) for ALOS Earth levels comes straight
 * from the terrain model, column by column: rock, soil, surface block, water, and a tree canopy
 * over forests. That's much faster than having Distant Horizons generate full Minecraft chunks
 * (with features, structures and lighting) hundreds of kilometres away. Only loaded when Distant
 * Horizons is present.
 */
public final class DistantHorizonsEarth {
    /** The LOD generators handed to Distant Horizons, by level (for the self-test). */
    private static final Map<ResourceKey<Level>, Generator> GENERATORS = new ConcurrentHashMap<>();
    static final int PRIORITY = 1_000_000;

    private DistantHorizonsEarth() {
    }

    public static void register() {
        DhApi.events.bind(DhApiLevelLoadEvent.class, new DhApiLevelLoadEvent() {
            @Override
            public void onLevelLoad(DhApiEventParam<EventParam> input) {
                try {
                    IDhApiLevelWrapper lw = input.value.levelWrapper;
                    if (lw.getWrappedMcObject() instanceof ServerLevel level
                        && level.getChunkSource().getGenerator() instanceof EarthChunkGenerator gen && gen.layer == 0) {
                        Generator g = new Generator(level, gen, lw);
                        var result = DhApi.worldGenOverrides.registerWorldGeneratorOverride(lw, g);
                        if (result.success) GENERATORS.put(level.dimension(), g);
                        AlosEarth.LOG.info("Distant Horizons: terrain for {} comes straight from ALOS Earth ({})",
                            level.dimension().location(), result.success ? "ok" : result.message);
                    }
                } catch (Throwable e) { // a Distant Horizons version with a different API: keep its normal generation
                    AlosEarth.LOG.warn("Distant Horizons integration unavailable: {}", e.toString());
                }
            }
        });
        DhApi.events.bind(DhApiLevelUnloadEvent.class, new DhApiLevelUnloadEvent() {
            @Override
            public void onLevelUnload(DhApiEventParam<EventParam> input) {
                GENERATORS.values().removeIf(g -> g.lw == input.value.levelWrapper);
            }
        });
    }

    /**
     * For the self-test: generates LOD chunks over the test data's mountain, lake and sea the way
     * Distant Horizons asks for them, checks every column by Distant Horizons' rules, and runs them
     * through its own converter. Returns the problem, or null.
     */
    public static String check(ServerLevel level, EarthChunkGenerator gen, List<String> notes) {
        try {
            Generator g = GENERATORS.get(level.dimension());
            if (g == null) return "no LOD generator registered for " + level.dimension().location();
            Object active = activeGenerator(g.lw);
            if (active != null && active != g) {
                return "another far-terrain generator is in use instead of ALOS Earth's: " + active.getClass().getName();
            }
            notes.add("Distant Horizons: the far-terrain generator in use is ALOS Earth's"
                + (active == null ? " (couldn't ask Distant Horizons)" : ""));
            int minY = level.getMinBuildHeight(), maxY = level.getMaxBuildHeight();
            List<DhApiChunk> chunks = Collections.synchronizedList(new ArrayList<>());
            double[][] places = {{35.5, 139.5}, {35.75, 139.25}, {35.1, 139.95}};
            double[] p = new double[2];
            for (double[] ll : places) {
                gen.terrain().projection.forward(ll[1], ll[0], p);
                int cx = ((int) Math.floor(p[0]) >> 4) - 1, cz = ((int) Math.floor(p[1]) >> 4) - 1;
                g.generateApiChunks(cx, cz, 2, (byte) 0, EDhApiDistantGeneratorMode.FEATURES, ForkJoinPool.commonPool(), chunks::add)
                    .get(60, TimeUnit.SECONDS);
            }
            if (chunks.size() != places.length * 4) return "asked for " + places.length * 4 + " chunks, got " + chunks.size();

            Method convert = null; // Distant Horizons' own API-chunk converter, with its validation on
            try {
                convert = Class.forName("com.seibel.distanthorizons.core.dataObjects.transformers.LodDataBuilder")
                    .getMethod("createFromApiChunkData", DhApiChunk.class, boolean.class);
            } catch (ReflectiveOperationException e) {
                notes.add("Distant Horizons: its chunk converter wasn't found (" + e + "), checking the columns only");
            }
            IDhApiBlockStateWrapper water = g.block(Palette.WATER);
            int columns = 0, wet = 0, canopy = 0;
            for (DhApiChunk c : chunks) {
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        List<DhApiTerrainDataPoint> col = c.getDataPoints(lx, lz);
                        String bad = checkColumn(col, minY, maxY);
                        if (bad != null) return "chunk " + c.chunkPosX + "," + c.chunkPosZ + " column " + lx + "," + lz + ": " + bad;
                        columns++;
                        int airs = 0;
                        for (DhApiTerrainDataPoint d : col) {
                            if (d.blockStateWrapper == water) wet++;
                            if (d.blockStateWrapper.isAir()) airs++;
                        }
                        if (airs >= 2) canopy++; // something stands between the ground and the open sky: trees
                    }
                }
                if (convert != null) {
                    try {
                        Object source = convert.invoke(null, c, true);
                        if (source instanceof AutoCloseable a) a.close();
                    } catch (InvocationTargetException e) {
                        return "Distant Horizons rejected chunk " + c.chunkPosX + "," + c.chunkPosZ + ": " + e.getCause();
                    }
                }
            }
            if (wet == 0) return "no water in the LODs over the test lake and sea";
            String version = io.github.lazytive.alosearth.Platform.get().modVersion("distanthorizons");
            notes.add("Distant Horizons " + version + ": " + chunks.size() + " LOD chunks (" + columns + " columns, " + wet
                + " with water, " + canopy + " with trees) pass its checks" + (convert != null ? " and converter" : ""));
            if (!g.dataSources) {
                notes.add("Distant Horizons: API " + DhApi.getApiMajorVersion() + "." + DhApi.getApiMinorVersion()
                    + ", so far terrain comes block by block");
                return null;
            }
            return checkDataSources(level, gen, g, notes);
        } catch (Exception e) {
            return "crashed: " + e;
        }
    }

    /**
     * The far-terrain path: LOD squares at full detail (from the terrain tiles) and at 1 column per
     * 16 x 16 blocks (from the far sampler), filled into Distant Horizons' own data source with its
     * validation on, read back, and compared with the terrain model column by column.
     */
    private static String checkDataSources(ServerLevel level, EarthChunkGenerator gen, Generator g, List<String> notes) {
        try {
            Class<?> sectionPos = Class.forName("com.seibel.distanthorizons.core.pos.DhSectionPos");
            Class<?> fullData = Class.forName("com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2");
            Method encode = sectionPos.getMethod("encode", byte.class, int.class, int.class);
            Method create = fullData.getMethod("createEmpty", long.class);
            Method validate = fullData.getMethod("setRunApiSetterValidation", boolean.class);
            Terrain t = gen.terrain();
            int minY = level.getMinBuildHeight(), maxY = level.getMaxBuildHeight();
            double[] p = new double[2];
            t.projection.forward(139.5, 35.5, p); // the test data's mountain
            StringBuilder timing = new StringBuilder();
            for (int detail : new int[] {0, 4}) {
                int width = 64 << detail, sx = Math.floorDiv((int) p[0], width), sz = Math.floorDiv((int) p[1], width);
                Object source = create.invoke(null, (long) encode.invoke(null, (byte) (6 + detail), sx, sz));
                validate.invoke(source, true);
                IDhApiFullDataSource api = (IDhApiFullDataSource) source;
                long t0 = System.nanoTime();
                g.generateLod((sx * width) >> 4, (sz * width) >> 4, sx, sz, (byte) detail, api, EDhApiDistantGeneratorMode.FEATURES,
                    ForkJoinPool.commonPool(), r -> { }).get(120, TimeUnit.SECONDS);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                int w = api.getWidthInDataColumns(), step = 1 << detail;
                Terrain.Tile far = detail == 0 ? null : t.far(sx * width, sz * width, step, w);
                int off = 0;
                for (int z = 0; z < w; z++) {
                    for (int x = 0; x < w; x++) {
                        List<DhApiTerrainDataPoint> col = api.getApiDataPointColumn(x, z);
                        // (Distant Horizons labels what it hands back with the section's own detail level)
                        String bad = checkColumn(col, 0, maxY - minY, false);
                        if (bad != null) return "detail " + detail + " column " + x + "," + z + ": " + bad;
                        int bx = sx * width + x * step + step / 2, bz = sz * width + z * step + step / 2;
                        Terrain.Tile tile = far != null ? far : t.tileAt(bx, bz);
                        int i = far != null ? z * w + x : Terrain.index(bx, bz);
                        int want = Math.max(tile.top[i], tile.water[i]) + 1 - minY, got = Integer.MAX_VALUE;
                        java.util.Collection<IDhApiBlockStateWrapper> treeBlocks = g.wrappers.values();
                        for (DhApiTerrainDataPoint d : col) { // the first thing above the ground: air, or a tree
                            if (d.blockStateWrapper.isAir() || treeBlocks.contains(d.blockStateWrapper)) {
                                got = Math.min(got, d.bottomYBlockPos);
                            }
                        }
                        if (got != want) off++;
                    }
                }
                if (off > 0) return "detail " + detail + ": " + off + " columns don't match the terrain's surface";
                if (source instanceof AutoCloseable a) a.close();
                timing.append(timing.isEmpty() ? "" : ", ").append(width).append(" blocks across at 1 column per ")
                    .append(step == 1 ? "block" : step + " blocks").append(" in ").append(ms).append(" ms");
            }
            notes.add("Distant Horizons: far terrain at any detail (" + timing + "), matching the terrain model");
            return null;
        } catch (Exception e) {
            return "crashed: " + e;
        }
    }

    /**
     * Version of the far terrain ALOS Earth gives Distant Horizons: raise it whenever that changes
     * (1: ground and a flat leaf canopy; 2: the real trees and plants), so far terrain that Distant
     * Horizons saved with an older version is rebuilt instead of kept forever.
     */
    static final int FAR_TERRAIN_VERSION = 2;

    /**
     * Before the levels load (so before Distant Horizons opens its database): if this ALOS Earth
     * world's saved far terrain was made by an older version (or isn't marked at all), it is
     * deleted, and Distant Horizons builds it again as the player looks around.
     */
    public static void resetOutdated(net.minecraft.server.MinecraftServer server) {
        var stem = server.registryAccess().registryOrThrow(Registries.LEVEL_STEM)
            .get(net.minecraft.world.level.dimension.LevelStem.OVERWORLD);
        if (stem == null || !(stem.generator() instanceof EarthChunkGenerator)) return;
        java.nio.file.Path data = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data");
        java.nio.file.Path mark = data.resolve("alosearth-far-terrain.txt");
        String want = String.valueOf(FAR_TERRAIN_VERSION);
        try {
            String have = java.nio.file.Files.exists(mark) ? java.nio.file.Files.readString(mark).trim() : "";
            if (want.equals(have)) return;
            int deleted = 0;
            if (java.nio.file.Files.isDirectory(data)) {
                try (var files = java.nio.file.Files.list(data)) {
                    for (java.nio.file.Path f : (Iterable<java.nio.file.Path>) files::iterator) {
                        if (f.getFileName().toString().startsWith("DistantHorizons.sqlite")) {
                            java.nio.file.Files.delete(f);
                            deleted++;
                        }
                    }
                }
            }
            java.nio.file.Files.createDirectories(data);
            java.nio.file.Files.writeString(mark, want);
            if (deleted > 0) {
                AlosEarth.LOG.info("Distant Horizons: this ALOS Earth version draws far terrain differently, so the far terrain"
                    + " saved for the overworld was cleared; Distant Horizons is building it again");
            }
        } catch (java.io.IOException | RuntimeException e) {
            AlosEarth.LOG.warn("Distant Horizons: couldn't clear the outdated far terrain ({}); it stays until Distant Horizons"
                + " rebuilds it", e.toString());
        }
    }

    /**
     * Once the server has started (every mod has had its say): which far-terrain generator each ALOS
     * Earth level ended up with, in the log, so a clash with another Distant Horizons add-on shows.
     */
    public static void report() {
        GENERATORS.forEach((dim, g) -> {
            Object active = activeGenerator(g.lw);
            if (active == null || active == g) {
                AlosEarth.LOG.info("Distant Horizons: far terrain for {} comes from ALOS Earth ({})", dim.location(),
                    g.dataSources ? "any detail" : "block by block, Distant Horizons older than 3");
            } else {
                AlosEarth.LOG.warn("Distant Horizons: far terrain for {} is made by {} instead of ALOS Earth, so it won't match"
                    + " the world", dim.location(), active.getClass().getName());
            }
        });
    }

    /** The world generator Distant Horizons will use for a level (internal API), or null if it can't be asked. */
    private static Object activeGenerator(IDhApiLevelWrapper lw) {
        try {
            Class<?> injector = Class.forName("com.seibel.distanthorizons.coreapi.DependencyInjection.WorldGeneratorInjector");
            Object instance = injector.getField("INSTANCE").get(null);
            return injector.getMethod("get", IDhApiLevelWrapper.class).invoke(instance, lw);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** Distant Horizons' rules for a column: whole blocks, no gaps or overlaps, from the bottom of the world to the top. */
    private static String checkColumn(List<DhApiTerrainDataPoint> col, int minY, int maxY) {
        return checkColumn(col, minY, maxY, true);
    }

    private static String checkColumn(List<DhApiTerrainDataPoint> col, int minY, int maxY, boolean blockSized) {
        if (col == null || col.isEmpty()) return "empty";
        List<DhApiTerrainDataPoint> up = new ArrayList<>(col);
        for (DhApiTerrainDataPoint d : up) if (d == null) return "null data point";
        up.sort(Comparator.comparingInt(d -> d.bottomYBlockPos));
        int y = minY;
        for (DhApiTerrainDataPoint d : up) {
            if (blockSized && d.detailLevel != 0) return "detail level " + d.detailLevel;
            if (d.blockStateWrapper == null || d.biomeWrapper == null) return "missing block or biome";
            if (d.bottomYBlockPos != y) return "gap or overlap at y " + y + " (next point starts at " + d.bottomYBlockPos + ")";
            if (d.topYBlockPos <= d.bottomYBlockPos || d.topYBlockPos - d.bottomYBlockPos >= 4096) {
                return "bad height " + d.bottomYBlockPos + ".." + d.topYBlockPos;
            }
            if (d.skyLightLevel < 0 || d.skyLightLevel > 15 || d.blockLightLevel < 0 || d.blockLightLevel > 15) return "bad light";
            y = d.topYBlockPos;
        }
        return y == maxY ? null : "ends at y " + y + ", not the top of the world (" + maxY + ")";
    }

    static final class Generator implements IDhApiWorldGenerator {
        private final ServerLevel level;
        private final EarthChunkGenerator gen;
        private final IDhApiLevelWrapper lw;
        private final IDhApiBlockStateWrapper[] blocks = new IDhApiBlockStateWrapper[Palette.BLOCKS.length];
        private final IDhApiBiomeWrapper[] biomes = new IDhApiBiomeWrapper[Palette.BIOMES.length];
        private final Map<Holder<Biome>, IDhApiBiomeWrapper> replaced = new ConcurrentHashMap<>();
        private volatile IDhApiBlockStateWrapper air;
        private final Map<BlockState, IDhApiBlockStateWrapper> wrappers = new ConcurrentHashMap<>();
        /** The trees Minecraft will grow here, for near terrain, and each biome's canopy, for far. */
        final io.github.lazytive.alosearth.LodTrees trees;
        /** Up to this detail level (1 column per 2^n blocks) far terrain shows the real trees. */
        static final int EXACT_TREES = 2;

        /** Distant Horizons 3 and later (API 7.1): far-away terrain comes at whatever detail it needs. */
        final boolean dataSources = dataSourcesSupported();

        Generator(ServerLevel level, EarthChunkGenerator gen, IDhApiLevelWrapper lw) {
            this.level = level;
            this.gen = gen;
            this.lw = lw;
            this.trees = new io.github.lazytive.alosearth.LodTrees(level, gen);
        }

        private static boolean dataSourcesSupported() {
            try {
                int major = DhApi.getApiMajorVersion(), minor = DhApi.getApiMinorVersion();
                return major > 7 || major == 7 && minor >= 1;
            } catch (Throwable e) {
                return false;
            }
        }

        @Override
        public EDhApiWorldGeneratorReturnType getReturnType() {
            return dataSources ? EDhApiWorldGeneratorReturnType.API_DATA_SOURCES : EDhApiWorldGeneratorReturnType.API_CHUNKS;
        }

        /** Up to one column per 4096 x 4096 blocks, like Distant Horizons' own far-terrain estimate. */
        @Override
        public byte getLargestDataDetailLevel() {
            return dataSources ? (byte) 12 : (byte) 0;
        }

        /**
         * One square of LOD columns: at full detail straight from the terrain tiles (the same as the
         * world), further out sampled once per column from the terrain model, without working out
         * every block in between (see {@link Terrain#far}).
         */
        @Override
        public CompletableFuture<Void> generateLod(int chunkPosMinX, int chunkPosMinZ, int lodPosX, int lodPosZ, byte detail,
                                                   IDhApiFullDataSource source, EDhApiDistantGeneratorMode mode,
                                                   ExecutorService pool, Consumer<IDhApiFullDataSource> result) {
            return CompletableFuture.runAsync(() -> {
                fill(chunkPosMinX << 4, chunkPosMinZ << 4, detail, source);
                result.accept(source);
            }, pool);
        }

        void fill(int x0, int z0, int detail, IDhApiFullDataSource source) {
            Terrain t = gen.terrain();
            int w = source.getWidthInDataColumns(), step = 1 << detail;
            int minY = level.getMinBuildHeight(), maxY = level.getMaxBuildHeight();
            Terrain.Tile far = detail == 0 ? null : t.far(x0, z0, step, w);
            // near: the very trees the chunks will get; further out: each biome's own canopy
            LodTrees.Area area = null;
            if (trees.hasTrees() && detail <= EXACT_TREES) {
                area = trees.area(x0, z0, w * step);
                trees.observe(area, x0, z0, w * step);
            }
            List<DhApiTerrainDataPoint> col = new ArrayList<>(16);
            for (int z = 0; z < w; z++) {
                for (int x = 0; x < w; x++) {
                    col.clear();
                    if (far == null) {
                        int bx = x0 + x, bz = z0 + z;
                        column(t, t.tileAt(bx, bz), Terrain.index(bx, bz), bx, bz, minY, maxY, -minY, col, area, bx, bz, null);
                    } else {
                        int bx = x0 + x * step + step / 2, bz = z0 + z * step + step / 2, i = z * w + x;
                        int tx = bx, tz = bz;
                        LodTrees.Canopy canopy = null;
                        if (area != null) { // the tallest tree column in this square stands for it
                            int best = Integer.MIN_VALUE;
                            for (int dz = 0; dz < step; dz++) {
                                for (int dx = 0; dx < step; dx++) {
                                    int[] ys = LodTrees.heights(area, x0 + x * step + dx, z0 + z * step + dz);
                                    if (ys != null && ys[ys.length - 1] > best) {
                                        best = ys[ys.length - 1];
                                        tx = x0 + x * step + dx;
                                        tz = z0 + z * step + dz;
                                    }
                                }
                            }
                        } else if (trees.hasTrees() && far.water[i] <= far.top[i]) {
                            canopy = trees.canopy(earthBiomes().biomeAt(far.biome[i], bx, bz), bx, bz);
                        }
                        column(t, far, i, bx, bz, minY, maxY, -minY, col, area, tx, tz, canopy);
                    }
                    source.setApiDataPointColumn(x, z, EDhApiWorldGenerationStep.LIGHT, col);
                }
            }
        }

        @Override
        public CompletableFuture<Void> generateApiChunks(int chunkPosMinX, int chunkPosMinZ, int width, byte detail,
                                                         EDhApiDistantGeneratorMode mode, ExecutorService pool,
                                                         Consumer<DhApiChunk> result) {
            return CompletableFuture.runAsync(() -> {
                for (int dz = 0; dz < width; dz++) {
                    for (int dx = 0; dx < width; dx++) result.accept(chunk(chunkPosMinX + dx, chunkPosMinZ + dz));
                }
            }, pool);
        }

        private DhApiChunk chunk(int cx, int cz) {
            Terrain t = gen.terrain();
            int minY = level.getMinBuildHeight(), maxY = level.getMaxBuildHeight();
            DhApiChunk c = DhApiChunk.create(cx, cz, minY, maxY);
            Terrain.Tile tile = t.tileAt(cx << 4, cz << 4);
            LodTrees.Area area = trees.hasTrees() ? trees.area(cx << 4, cz << 4, 16) : null;
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    int x = (cx << 4) + lx, z = (cz << 4) + lz;
                    List<DhApiTerrainDataPoint> col = new ArrayList<>(16);
                    column(t, tile, Terrain.index(x, z), x, z, minY, maxY, 0, col, area, x, z, null);
                    c.setDataPoints(lx, lz, col);
                }
            }
            return c;
        }

        /**
         * One column, bottom to top: rock, soil, the surface block, then water (lit less the deeper
         * it is, like the real thing) or, over forests, a canopy of leaves; air up to the top of the
         * world. Heights are shifted by {@code yOff} (data sources count from the bottom of the world).
         */
        private void column(Terrain t, Terrain.Tile tile, int i, int x, int z, int minY, int maxY, int yOff,
                            List<DhApiTerrainDataPoint> col, LodTrees.Area area, int treeX, int treeZ, LodTrees.Canopy canopy) {
            int top = Math.max(minY, Math.min(maxY - 2, tile.top[i]));
            int water = Math.min(maxY - 1, tile.water[i]);
            IDhApiBiomeWrapper biome = biome(tile.biome[i], x, z);
            int y = minY;
            int soil = Math.max(minY, top - 3);
            if (soil > y) {
                col.add(point(y + yOff, soil + yOff, 0, block(solid(t.block(tile, i, x, soil - 1, z))), biome));
                y = soil;
            }
            if (top > y) {
                col.add(point(y + yOff, top + yOff, 0, block(solid(t.block(tile, i, x, top - 1, z))), biome));
                y = top;
            }
            int surfaceSky = water > top ? Math.max(0, 15 - (water - top)) : 15;
            col.add(point(top + yOff, top + 1 + yOff, surfaceSky, block(solid(t.block(tile, i, x, top, z))), biome));
            y = top + 1;

            // above the ground: water, and the trees (as Minecraft will grow them, or the biome's canopy)
            int[] ys = area == null ? null : LodTrees.heights(area, treeX, treeZ);
            BlockState[] st = area == null ? null : LodTrees.states(area, treeX, treeZ);
            int crownLow = Integer.MAX_VALUE, crownTop = Integer.MIN_VALUE;
            IDhApiBlockStateWrapper crown = null;
            if (canopy != null) {
                long h = (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
                h ^= h >>> 29;
                h *= 0xBF58476D1CE4E5B9L;
                double u1 = ((h >>> 11) & 0xFFFFF) / (double) 0x100000, u2 = ((h >>> 33) & 0xFFFFF) / (double) 0x100000;
                if (u1 < canopy.cover()) {
                    crownTop = top + Math.max(1, canopy.top(u2));
                    crownLow = Math.min(crownTop, top + canopy.crownBottom());
                    crown = state(canopy.leaves());
                }
            }
            int last = Math.max(water, Math.max(crownTop, ys == null ? Integer.MIN_VALUE : ys[ys.length - 1]));
            last = Math.min(last, maxY - 1);
            int k = 0;
            while (ys != null && k < ys.length && ys[k] < y) k++;
            IDhApiBlockStateWrapper run = null;
            int runStart = y;
            for (; y <= last; y++) {
                IDhApiBlockStateWrapper b;
                if (ys != null && k < ys.length && ys[k] == y) {
                    b = state(st[k]);
                    k++;
                } else if (y >= crownLow && y <= crownTop) {
                    b = crown;
                } else {
                    b = y <= water ? block(Palette.WATER) : air();
                }
                if (b != run) {
                    if (run != null) col.add(point(runStart + yOff, y + yOff, 15, run, biome));
                    run = b;
                    runStart = y;
                }
            }
            if (run != null) col.add(point(runStart + yOff, y + yOff, 15, run, biome));
            if (y < maxY) col.add(point(y + yOff, maxY + yOff, 15, air(), biome));
        }

        /** Cave mouths and overhangs are too small to see from afar: rock. */
        private static int solid(int id) {
            return id == Palette.AIR ? Palette.STONE : id;
        }

        private static DhApiTerrainDataPoint point(int bottom, int top, int sky, IDhApiBlockStateWrapper b, IDhApiBiomeWrapper biome) {
            return DhApiTerrainDataPoint.create((byte) 0, 0, sky, bottom, top, b, biome);
        }

        private IDhApiBlockStateWrapper block(int id) {
            IDhApiBlockStateWrapper w = blocks[id];
            if (w == null) {
                BlockState s = gen.states()[id];
                w = blocks[id] = DhApi.Delayed.wrapperFactory.getBlockStateWrapper(new Object[] {s}, lw);
            }
            return w;
        }

        private IDhApiBlockStateWrapper state(BlockState s) {
            return wrappers.computeIfAbsent(s, k -> DhApi.Delayed.wrapperFactory.getBlockStateWrapper(new Object[] {k}, lw));
        }

        private io.github.lazytive.alosearth.EarthBiomeSource earthBiomes() {
            return (io.github.lazytive.alosearth.EarthBiomeSource) gen.getBiomeSource();
        }

        private IDhApiBlockStateWrapper air() {
            if (air == null) air = DhApi.Delayed.wrapperFactory.getAirBlockStateWrapper();
            return air;
        }

        private IDhApiBiomeWrapper biome(int idx, int x, int z) {
            // biomes from other mods (config "biomes"): the same ones the world places there
            if (level.getChunkSource().getGenerator().getBiomeSource() instanceof io.github.lazytive.alosearth.EarthBiomeSource src
                && src.replacesBiomes()) {
                Holder<Biome> h = src.biomeAt(idx, x, z);
                return replaced.computeIfAbsent(h, k -> DhApi.Delayed.wrapperFactory.getBiomeWrapper(new Object[] {k}, lw));
            }
            IDhApiBiomeWrapper w = biomes[idx];
            if (w == null) {
                Holder<Biome> h = level.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(
                    ResourceKey.create(Registries.BIOME, ResourceLocation.withDefaultNamespace(Palette.BIOMES[idx])));
                w = biomes[idx] = DhApi.Delayed.wrapperFactory.getBiomeWrapper(new Object[] {h}, lw);
            }
            return w;
        }

        /**
         * Above other far-terrain generators (DH SeedGen and the like), which recreate vanilla terrain
         * from the seed and know nothing of the Earth: in ALOS Earth levels only this one is right.
         * (Distant Horizons uses the highest priority; on a tie the last one registered wins.)
         */
        @Override
        public int getPriority() {
            return PRIORITY;
        }

        @Override
        public void preGeneratorTaskStart() {
        }

        @Override
        public void close() {
        }
    }
}
