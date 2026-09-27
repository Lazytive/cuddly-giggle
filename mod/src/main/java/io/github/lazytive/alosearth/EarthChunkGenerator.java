package io.github.lazytive.alosearth;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.lazytive.alosearth.core.EarthSettings;
import io.github.lazytive.alosearth.core.Palette;
import io.github.lazytive.alosearth.core.Terrain;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.RandomSupport;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.blending.Blender;

/**
 * Builds chunks from the terrain core: solid rock (no caves or carvers),
 * surface blocks, water. Everything else - ores, trees, plants, structures,
 * mobs, lighting - is vanilla, driven by the biomes.
 */
public final class EarthChunkGenerator extends ChunkGenerator {
    private static final EarthSettings D = EarthSettings.DEFAULT;

    public static final Codec<EarthSettings> SETTINGS_CODEC = RecordCodecBuilder.create(i -> i.group(
        Codec.DOUBLE.optionalFieldOf("center_lat", D.centerLat()).forGetter(EarthSettings::centerLat),
        Codec.DOUBLE.optionalFieldOf("center_lon", D.centerLon()).forGetter(EarthSettings::centerLon),
        Codec.DOUBLE.optionalFieldOf("meters_per_block", D.metersPerBlock()).forGetter(EarthSettings::metersPerBlock),
        Codec.INT.optionalFieldOf("margin", D.margin()).forGetter(EarthSettings::margin),
        Codec.INT.optionalFieldOf("min_y", D.minY()).forGetter(EarthSettings::minY),
        Codec.INT.optionalFieldOf("height", D.height()).forGetter(EarthSettings::height),
        Codec.INT.optionalFieldOf("sea_level", D.seaLevel()).forGetter(EarthSettings::seaLevel),
        Codec.DOUBLE.optionalFieldOf("land_scale", D.landScale()).forGetter(EarthSettings::landScale),
        Codec.DOUBLE.optionalFieldOf("land_knee", D.landKnee()).forGetter(EarthSettings::landKnee),
        Codec.DOUBLE.optionalFieldOf("ocean_scale", D.oceanScale()).forGetter(EarthSettings::oceanScale),
        Codec.DOUBLE.optionalFieldOf("ocean_knee", D.oceanKnee()).forGetter(EarthSettings::oceanKnee),
        Codec.DOUBLE.optionalFieldOf("detail", D.detail()).forGetter(EarthSettings::detail)
    ).apply(i, EarthSettings::new));

    public static final MapCodec<EarthChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
        BiomeSource.CODEC.fieldOf("biome_source").forGetter(ChunkGenerator::getBiomeSource),
        SETTINGS_CODEC.optionalFieldOf("settings", D).forGetter(g -> g.settings)
    ).apply(i, i.stable(EarthChunkGenerator::new)));

    public final EarthSettings settings;
    private volatile BlockState[] states;

    public EarthChunkGenerator(BiomeSource biomeSource, EarthSettings settings) {
        super(biomeSource);
        this.settings = settings;
    }

    public Terrain terrain() {
        return AlosEarth.terrain(settings);
    }

    /** Block states for {@link Palette#BLOCKS}, resolved once registries are ready. */
    public BlockState[] states() {
        BlockState[] s = states;
        if (s == null) {
            s = new BlockState[Palette.BLOCKS.length];
            for (int i = 0; i < s.length; i++) {
                s[i] = BuiltInRegistries.BLOCK.get(ResourceLocation.withDefaultNamespace(Palette.BLOCKS[i])).defaultBlockState();
            }
            states = s;
        }
        return s;
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(Blender blender, RandomState randomState,
                                                        StructureManager structureManager, ChunkAccess chunk) {
        fill(chunk);
        return CompletableFuture.completedFuture(chunk);
    }

    private void fill(ChunkAccess chunk) {
        Terrain t = terrain();
        BlockState[] st = states();
        ChunkPos cp = chunk.getPos();
        int x0 = cp.getMinBlockX(), z0 = cp.getMinBlockZ();
        int minY = chunk.getMinBuildHeight(), maxY = chunk.getMaxBuildHeight() - 1;
        Terrain.Tile tile = t.tileAt(x0, z0);
        Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap worldSurface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int x = x0 + lx, z = z0 + lz;
                int i = Terrain.index(x, z);
                int top = Math.min(maxY, Math.max(tile.top[i], tile.water[i]));
                LevelChunkSection section = null;
                int sectionIndex = -1;
                for (int y = minY; y <= top; y++) {
                    int id = t.block(tile, i, x, y, z);
                    if (id == Palette.AIR) continue;
                    int si = chunk.getSectionIndex(y);
                    if (si != sectionIndex) {
                        sectionIndex = si;
                        section = chunk.getSection(si);
                    }
                    BlockState state = st[id];
                    section.setBlockState(lx, y & 15, lz, state, false);
                    oceanFloor.update(lx, y, lz, state);
                    worldSurface.update(lx, y, lz, state);
                }
            }
        }
    }

    @Override
    public void applyCarvers(WorldGenRegion level, long seed, RandomState random, BiomeManager biomeManager,
                             StructureManager structureManager, ChunkAccess chunk, GenerationStep.Carving step) {
        // No caves: the underground stays solid (ores still come from the biomes' features).
    }

    @Override
    public void buildSurface(WorldGenRegion level, StructureManager structureManager, RandomState random, ChunkAccess chunk) {
        // Surface blocks are placed in fillFromNoise.
    }

    @Override
    public void spawnOriginalMobs(WorldGenRegion level) {
        ChunkPos pos = level.getCenter();
        Holder<Biome> biome = level.getBiome(pos.getWorldPosition().atY(level.getMaxBuildHeight() - 1));
        WorldgenRandom random = new WorldgenRandom(new LegacyRandomSource(RandomSupport.generateUniqueSeed()));
        random.setDecorationSeed(level.getSeed(), pos.getMinBlockX(), pos.getMinBlockZ());
        NaturalSpawner.spawnMobsForChunkGeneration(level, biome, pos, random);
    }

    @Override
    public int getGenDepth() {
        return settings.height();
    }

    @Override
    public int getSeaLevel() {
        return settings.seaLevel();
    }

    @Override
    public int getMinY() {
        return settings.minY();
    }

    @Override
    public int getBaseHeight(int x, int z, Heightmap.Types type, LevelHeightAccessor level, RandomState random) {
        Terrain t = terrain();
        boolean floor = type == Heightmap.Types.OCEAN_FLOOR_WG || type == Heightmap.Types.OCEAN_FLOOR;
        return (floor ? t.top(x, z) : t.surface(x, z)) + 1;
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState random) {
        Terrain t = terrain();
        BlockState[] st = states();
        int minY = level.getMinBuildHeight();
        BlockState[] column = new BlockState[level.getHeight()];
        Terrain.Tile tile = t.tileAt(x, z);
        int i = Terrain.index(x, z);
        for (int k = 0; k < column.length; k++) column[k] = st[t.block(tile, i, x, minY + k, z)];
        return new NoiseColumn(minY, column);
    }

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState random, BlockPos pos) {
        info.add("ALOS Earth: " + terrain().describe(pos.getX(), pos.getZ()));
    }
}
