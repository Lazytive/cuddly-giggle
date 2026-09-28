package io.github.lazytive.alosearth.compat;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGeneratorReturnType;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBiomeWrapper;
import com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiLevelLoadEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.api.objects.data.DhApiChunk;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import io.github.lazytive.alosearth.AlosEarth;
import io.github.lazytive.alosearth.EarthChunkGenerator;
import io.github.lazytive.alosearth.core.Palette;
import io.github.lazytive.alosearth.core.Terrain;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
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
                        var result = DhApi.worldGenOverrides.registerWorldGeneratorOverride(lw, new Generator(level, gen, lw));
                        AlosEarth.LOG.info("Distant Horizons: terrain for {} comes straight from ALOS Earth ({})",
                            level.dimension().location(), result.success ? "ok" : result.message);
                    }
                } catch (Throwable e) { // a Distant Horizons version with a different API: keep its normal generation
                    AlosEarth.LOG.warn("Distant Horizons integration unavailable: {}", e.toString());
                }
            }
        });
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
