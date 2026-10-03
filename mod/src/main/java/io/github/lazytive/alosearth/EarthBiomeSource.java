package io.github.lazytive.alosearth;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.lazytive.alosearth.core.EarthSettings;
import io.github.lazytive.alosearth.core.Palette;
import io.github.lazytive.alosearth.core.Terrain;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

/** Biomes come from the terrain core: climate zone, altitude, coasts, rivers. */
public final class EarthBiomeSource extends BiomeSource {
    public static final MapCodec<EarthBiomeSource> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
        RegistryOps.retrieveGetter(Registries.BIOME),
        EarthChunkGenerator.SETTINGS_CODEC.optionalFieldOf("settings", EarthSettings.DEFAULT).forGetter(b -> b.settings)
    ).apply(i, i.stable(EarthBiomeSource::new)));

    private final EarthSettings settings;
    private final List<Holder<Biome>> holders = new ArrayList<>();
    /** Per palette biome, the biomes from {@code "biomes"} in the config that replace it (null: none). */
    private final Holder<Biome>[][] replacements;
    /** Per thread, the last column's variety (biomes are asked for many heights of one column in a row). */
    private final ThreadLocal<double[]> lastVariety = ThreadLocal.withInitial(() -> new double[] {Double.NaN, Double.NaN, 0});

    public EarthBiomeSource(HolderGetter<Biome> biomes, EarthSettings settings) {
        this(biomes, settings, EarthConfig.load().biomes);
    }

    @SuppressWarnings("unchecked")
    public EarthBiomeSource(HolderGetter<Biome> biomes, EarthSettings settings, Map<String, List<String>> replace) {
        this.settings = settings;
        this.replacements = new Holder[Palette.BIOMES.length][];
        for (int k = 0; k < Palette.BIOMES.length; k++) {
            String name = Palette.BIOMES[k];
            holders.add(biomes.getOrThrow(ResourceKey.create(Registries.BIOME, ResourceLocation.withDefaultNamespace(name))));
            List<String> ids = replace == null ? null : replace.containsKey(name) ? replace.get(name) : replace.get("minecraft:" + name);
            if (ids == null) continue;
            List<Holder<Biome>> found = new ArrayList<>();
            for (String id : ids) {
                ResourceLocation loc = id == null ? null : ResourceLocation.tryParse(id);
                var holder = loc == null ? java.util.Optional.<Holder.Reference<Biome>>empty()
                    : biomes.get(ResourceKey.create(Registries.BIOME, loc));
                if (holder.isPresent()) found.add(holder.get());
                else AlosEarth.LOG.warn("config \"biomes\": no biome {} (is the mod that adds it installed?), skipped", id);
            }
            if (!found.isEmpty()) replacements[k] = found.toArray(new Holder[0]);
        }
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        Stream<Holder<Biome>> extra = java.util.Arrays.stream(replacements).filter(java.util.Objects::nonNull).flatMap(java.util.Arrays::stream);
        return Stream.concat(holders.stream(), extra).distinct();
    }

    /**
     * The biome placed for a palette biome at a block column: the vanilla one, or (with
     * {@code "biomes"} in the config) its replacement; several replacements share the land in
     * patches.
     */
    public Holder<Biome> biomeAt(int palette, int x, int z) {
        Holder<Biome>[] r = replacements[palette];
        if (r == null) return holders.get(palette);
        if (r.length == 1) return r[0];
        double[] last = lastVariety.get();
        if (last[0] != x || last[1] != z) {
            last[0] = x;
            last[1] = z;
            last[2] = AlosEarth.terrain(settings).variety(x, z);
        }
        return r[Math.min(r.length - 1, (int) (last[2] * r.length))];
    }

    /** Whether any palette biome is replaced through the config. */
    public boolean replacesBiomes() {
        for (Holder<Biome>[] r : replacements) if (r != null) return true;
        return false;
    }

    /**
     * Used by Minecraft's wide-area searches (stronghold placement, structure
     * checks, /locate): a climate-only estimate unless the terrain there is
     * already computed, so they never compute terrain or download elevation
     * far from players. Chunks get exact biomes via {@link #exactBiome}.
     */
    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) {
        int bx = QuartPos.toBlock(x) + 2, bz = QuartPos.toBlock(z) + 2;
        return biomeAt(AlosEarth.terrain(settings).approximateBiome(bx, bz), bx, bz);
    }

    /** The biome of the actual terrain (used when a chunk is generated). */
    public Holder<Biome> exactBiome(int x, int y, int z) {
        int bx = QuartPos.toBlock(x) + 2, bz = QuartPos.toBlock(z) + 2;
        Terrain t = AlosEarth.terrain(settings);
        Terrain.Tile tile = t.tileAt(bx, bz);
        int i = Terrain.index(bx, bz);
        int cave = tile.caveBiome[i];
        if (cave >= 0) { // lush or dripstone caves underground, where caves can run
            int by = QuartPos.toBlock(y) + 2, top = tile.top[i];
            if (by < top - 12 && by > top - Terrain.CAVE_DEPTH) return biomeAt(cave, bx, bz);
        }
        return biomeAt(tile.biome[i], bx, bz);
    }
}
