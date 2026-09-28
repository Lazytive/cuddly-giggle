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
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
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
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.block.Block;
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
        Codec.DOUBLE.optionalFieldOf("detail", D.detail()).forGetter(EarthSettings::detail),
        Codec.BOOL.optionalFieldOf("true_scale", false).forGetter(EarthSettings::trueScale),
        // worlds made before these existed decode with the old behaviour
        Codec.BOOL.optionalFieldOf("sea_floor", false).forGetter(EarthSettings::seaFloor),
        Codec.BOOL.optionalFieldOf("true_ocean", false).forGetter(EarthSettings::trueOcean),
        Codec.intRange(0, 8).optionalFieldOf("deep_layers", 0).forGetter(EarthSettings::deepLayers)
    ).apply(i, EarthSettings::new));

    public static final MapCodec<EarthChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
        BiomeSource.CODEC.fieldOf("biome_source").forGetter(ChunkGenerator::getBiomeSource),
        SETTINGS_CODEC.optionalFieldOf("settings", D).forGetter(g -> g.settings),
        Codec.intRange(0, 8).optionalFieldOf("layer", 0).forGetter(g -> g.layer)
    ).apply(i, i.stable(EarthChunkGenerator::new)));

    public final EarthSettings settings;
    /** 0 for the main world; k for the k-th deep layer below it. */
    public final int layer;
    /** Virtual y = local y - offset. */
    public final int offset;
    private volatile BlockState[] states;

    public EarthChunkGenerator(BiomeSource biomeSource, EarthSettings settings, int layer) {
        super(biomeSource);
        this.settings = settings;
        this.layer = layer;
        this.offset = layer * settings.layerShift();
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
    public CompletableFuture<ChunkAccess> createBiomes(RandomState randomState, Blender blender,
                                                       StructureManager structureManager, ChunkAccess chunk) {
        if (biomeSource instanceof EarthBiomeSource earth) {
            chunk.fillBiomesFromNoise((x, y, z, sampler) -> earth.exactBiome(x, y, z), randomState.sampler());
            return CompletableFuture.completedFuture(chunk);
        }
        return super.createBiomes(randomState, blender, structureManager, chunk);
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

        // per-column tops (in this layer's y) and what the whole chunk has in common
        int[] idx = new int[256], surf = new int[256];
        int maxSurf = Integer.MIN_VALUE, minDeep = Integer.MAX_VALUE, maxTop = Integer.MIN_VALUE, minWater = Integer.MAX_VALUE;
        for (int c = 0; c < 256; c++) {
            int i = Terrain.index(x0 + (c & 15), z0 + (c >> 4));
            idx[c] = i;
            surf[c] = Math.max(tile.top[i], tile.water[i]) + offset;
            maxSurf = Math.max(maxSurf, surf[c]);
            minDeep = Math.min(minDeep, Terrain.deepStart(tile, i) + offset);
            maxTop = Math.max(maxTop, tile.top[i] + offset);
            minWater = Math.min(minWater, tile.water[i] + offset);
        }
        int bedrockTop = t.settings.bottomY() + offset + 5;
        LevelChunkSection[] sections = chunk.getSections();
        BlockState lastUniform = null;
        int lastUniformTop = 0;
        for (int si = 0; si < sections.length; si++) {
            int y0 = chunk.getSectionYFromSectionIndex(si) << 4, y1 = y0 + 15;
            if (y0 > maxSurf || y0 > maxY) break;
            // whole sections of one block (deep rock, open water) are made in one go
            BlockState uniform = null;
            if (y1 < minDeep && y0 > bedrockTop) {
                int v0 = y0 - offset;
                if (v0 >= 8) uniform = st[Palette.STONE];
                else if (v0 + 15 < 0) uniform = st[Palette.DEEPSLATE];
            } else if (y0 > maxTop && y1 <= minWater) {
                uniform = st[Palette.WATER];
            }
            if (uniform != null) {
                sections[si] = new LevelChunkSection(
                    new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, uniform, PalettedContainer.Strategy.SECTION_STATES),
                    sections[si].getBiomes());
                lastUniform = uniform;
                lastUniformTop = y1;
                continue;
            }
            LevelChunkSection section = sections[si];
            for (int c = 0; c < 256; c++) {
                int lx = c & 15, lz = c >> 4, x = x0 + lx, z = z0 + lz, i = idx[c];
                int hi = Math.min(y1, Math.min(maxY, surf[c]));
                for (int y = y0; y <= hi; y++) {
                    int id = t.block(tile, i, x, y - offset, z);
                    if (id == Palette.AIR) continue;
                    BlockState state = st[id];
                    section.setBlockState(lx, y & 15, lz, state, false);
                    oceanFloor.update(lx, y, lz, state);
                    worldSurface.update(lx, y, lz, state);
                }
            }
        }
        if (lastUniform != null) { // heightmaps only ever rise, so the highest uniform section is enough
            for (int c = 0; c < 256; c++) {
                oceanFloor.update(c & 15, lastUniformTop, c >> 4, lastUniform);
                worldSurface.update(c & 15, lastUniformTop, c >> 4, lastUniform);
            }
        }
    }

    @Override
    public void createStructures(RegistryAccess registryAccess, ChunkGeneratorStructureState structureState,
                                 StructureManager structureManager, ChunkAccess chunk,
                                 StructureTemplateManager templateManager) {
        // structures belong to the surface world, not the deep layers under it
        if (layer == 0) super.createStructures(registryAccess, structureState, structureManager, chunk, templateManager);
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
        int y = (floor ? t.top(x, z) : t.surface(x, z)) + 1 + offset;
        return Math.max(level.getMinBuildHeight(), Math.min(level.getMaxBuildHeight(), y));
    }

    /** Virtual y (continuous through the deep layers) for a y in this layer. */
    public int virtualY(double y) {
        return (int) Math.floor(y) - offset;
    }

    @Override
    public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState random) {
        Terrain t = terrain();
        BlockState[] st = states();
        int minY = level.getMinBuildHeight();
        BlockState[] column = new BlockState[level.getHeight()];
        Terrain.Tile tile = t.tileAt(x, z);
        int i = Terrain.index(x, z);
        for (int k = 0; k < column.length; k++) column[k] = st[t.block(tile, i, x, minY + k - offset, z)];
        return new NoiseColumn(minY, column);
    }

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState random, BlockPos pos) {
        info.add("ALOS Earth: " + terrain().describe(pos.getX(), pos.getZ()));
        if (settings.deepLayers() > 0) {
            info.add("ALOS Earth: " + EarthCommands.elevation(this, pos.getY()) + (layer > 0 ? " (deep layer " + layer + ")" : ""));
        }
    }
}
