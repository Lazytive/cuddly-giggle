package io.github.lazytive.alosearth.compat;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
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
        private volatile IDhApiBlockStateWrapper air, oakLeaves, spruceLeaves, jungleLeaves, acaciaLeaves, cherryLeaves;

        Generator(ServerLevel level, EarthChunkGenerator gen, IDhApiLevelWrapper lw) {
            this.level = level;
            this.gen = gen;
            this.lw = lw;
        }

        @Override
        public EDhApiWorldGeneratorReturnType getReturnType() {
            return EDhApiWorldGeneratorReturnType.API_CHUNKS;
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
                    int x = (cx << 4) + lx, z = (cz << 4) + lz, i = Terrain.index(x, z);
                    int top = Math.max(minY, Math.min(maxY - 2, tile.top[i]));
                    int water = Math.min(maxY - 1, tile.water[i]);
                    IDhApiBiomeWrapper biome = biome(tile.biome[i]);
                    List<DhApiTerrainDataPoint> col = new ArrayList<>(6);
                    int y = minY;
                    int soil = Math.max(minY, top - 3);
                    if (soil > y) { col.add(point(y, soil, 0, block(t.block(tile, i, x, soil - 1, z)), biome)); y = soil; }
                    if (top > y) { col.add(point(y, top, 0, block(t.block(tile, i, x, top - 1, z)), biome)); y = top; }
                    col.add(point(top, top + 1, water > top ? 0 : 15, block(t.block(tile, i, x, top, z)), biome));
                    y = top + 1;
                    if (water > top) {
                        col.add(point(y, water + 1, 15, block(Palette.WATER), biome));
                        y = water + 1;
                    } else {
                        IDhApiBlockStateWrapper leaves = canopy(Palette.BIOMES[tile.biome[i]], x, z);
                        if (leaves != null && y + 7 < maxY) { // tree tops over forests, so they look green from afar
                            col.add(point(y, y + 4, 15, air(), biome));
                            col.add(point(y + 4, y + 7, 15, leaves, biome));
                            y += 7;
                        }
                    }
                    if (y < maxY) col.add(point(y, maxY, 15, air(), biome));
                    c.setDataPoints(lx, lz, col);
                }
            }
            return c;
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

        private IDhApiBiomeWrapper biome(int idx) {
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
