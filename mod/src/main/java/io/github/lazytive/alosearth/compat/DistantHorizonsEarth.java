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
                        for (DhApiTerrainDataPoint d : col) {
                            if (d.blockStateWrapper == water) wet++;
                            if (d.blockStateWrapper != water && !d.blockStateWrapper.isAir() && d.bottomYBlockPos > minY
                                && d.skyLightLevel == 15 && d.topYBlockPos - d.bottomYBlockPos == 3) canopy++;
                        }
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
            String version = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("distanthorizons")
                .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
            notes.add("Distant Horizons " + version + ": " + chunks.size() + " LOD chunks (" + columns + " columns, " + wet
                + " with water, " + canopy + " with tree tops) pass its checks" + (convert != null ? " and converter" : ""));
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
                        String bad = checkColumn(col, 0, maxY - minY);
                        if (bad != null) return "detail " + detail + " column " + x + "," + z + ": " + bad;
                        int bx = sx * width + x * step + step / 2, bz = sz * width + z * step + step / 2;
                        Terrain.Tile tile = far != null ? far : t.tileAt(bx, bz);
                        int i = far != null ? z * w + x : Terrain.index(bx, bz);
                        int want = Math.max(tile.top[i], tile.water[i]) + 1 - minY, got = Integer.MAX_VALUE;
                        for (DhApiTerrainDataPoint d : col) {
                            if (d.blockStateWrapper.isAir()) got = Math.min(got, d.bottomYBlockPos);
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

    /** Distant Horizons' rules for a column: whole blocks, no gaps or overlaps, from the bottom of the world to the top. */
    private static String checkColumn(List<DhApiTerrainDataPoint> col, int minY, int maxY) {
        if (col == null || col.isEmpty()) return "empty";
        List<DhApiTerrainDataPoint> up = new ArrayList<>(col);
        for (DhApiTerrainDataPoint d : up) if (d == null) return "null data point";
        up.sort(Comparator.comparingInt(d -> d.bottomYBlockPos));
        int y = minY;
        for (DhApiTerrainDataPoint d : up) {
            if (d.detailLevel != 0) return "detail level " + d.detailLevel;
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
        private volatile IDhApiBlockStateWrapper air, oakLeaves, spruceLeaves, jungleLeaves, acaciaLeaves, cherryLeaves;

        /** Distant Horizons 3 and later (API 7.1): far-away terrain comes at whatever detail it needs. */
        final boolean dataSources = dataSourcesSupported();

        Generator(ServerLevel level, EarthChunkGenerator gen, IDhApiLevelWrapper lw) {
            this.level = level;
            this.gen = gen;
            this.lw = lw;
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
            List<DhApiTerrainDataPoint> col = new ArrayList<>(8);
            for (int z = 0; z < w; z++) {
                for (int x = 0; x < w; x++) {
                    col.clear();
                    if (far == null) {
                        int bx = x0 + x, bz = z0 + z;
                        column(t, t.tileAt(bx, bz), Terrain.index(bx, bz), bx, bz, minY, maxY, -minY, col);
                    } else {
                        column(t, far, z * w + x, x0 + x * step + step / 2, z0 + z * step + step / 2, minY, maxY, -minY, col);
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
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    int x = (cx << 4) + lx, z = (cz << 4) + lz;
                    List<DhApiTerrainDataPoint> col = new ArrayList<>(8);
                    column(t, tile, Terrain.index(x, z), x, z, minY, maxY, 0, col);
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
                            List<DhApiTerrainDataPoint> col) {
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
            if (water > top) {
                col.add(point(y + yOff, water + 1 + yOff, 15, block(Palette.WATER), biome));
                y = water + 1;
            } else {
                IDhApiBlockStateWrapper leaves = canopy(Palette.BIOMES[tile.biome[i]], x, z);
                if (leaves != null && y + 7 < maxY) { // tree tops over forests, so they look green from afar
                    col.add(point(y + yOff, y + 4 + yOff, 15, air(), biome));
                    col.add(point(y + 4 + yOff, y + 7 + yOff, 15, leaves, biome));
                    y += 7;
                }
            }
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

        private IDhApiBlockStateWrapper air() {
            if (air == null) air = DhApi.Delayed.wrapperFactory.getAirBlockStateWrapper();
            return air;
        }

        private IDhApiBlockStateWrapper named(String id) {
            BlockState s = BuiltInRegistries.BLOCK.get(ResourceLocation.withDefaultNamespace(id)).defaultBlockState();
            return DhApi.Delayed.wrapperFactory.getBlockStateWrapper(new Object[] {s}, lw);
        }

        /** A leaf canopy for wooded biomes (about 70% cover), or null. */
        private IDhApiBlockStateWrapper canopy(String biome, int x, int z) {
            if (((x * 734287 + z * 912931) >>> 7 & 7) < 3) return null; // gaps between the trees
            if (biome.contains("jungle") || biome.equals("mangrove_swamp")) {
                if (jungleLeaves == null) jungleLeaves = named("jungle_leaves");
                return jungleLeaves;
            }
            if (biome.contains("taiga") || biome.equals("grove") || biome.equals("windswept_forest")) {
                if (spruceLeaves == null) spruceLeaves = named("spruce_leaves");
                return spruceLeaves;
            }
            if (biome.equals("cherry_grove")) {
                if (cherryLeaves == null) cherryLeaves = named("cherry_leaves");
                return cherryLeaves;
            }
            if (biome.contains("savanna")) {
                if (((x * 31 + z * 17) & 3) != 0) return null; // sparse
                if (acaciaLeaves == null) acaciaLeaves = named("acacia_leaves");
                return acaciaLeaves;
            }
            if (biome.contains("forest") || biome.equals("swamp")) {
                if (oakLeaves == null) oakLeaves = named("oak_leaves");
                return oakLeaves;
            }
            return null;
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

        @Override
        public void preGeneratorTaskStart() {
        }

        @Override
        public void close() {
        }
    }
}
