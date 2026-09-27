package io.github.lazytive.alosearth;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.lazytive.alosearth.core.EarthSettings;
import io.github.lazytive.alosearth.core.Palette;
import io.github.lazytive.alosearth.core.Terrain;
import java.util.ArrayList;
import java.util.List;
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

    public EarthBiomeSource(HolderGetter<Biome> biomes, EarthSettings settings) {
        this.settings = settings;
        for (String name : Palette.BIOMES) {
            holders.add(biomes.getOrThrow(ResourceKey.create(Registries.BIOME, ResourceLocation.withDefaultNamespace(name))));
        }
    }

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return holders.stream();
    }

    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler sampler) {
        int bx = QuartPos.toBlock(x) + 2, bz = QuartPos.toBlock(z) + 2;
        Terrain t = AlosEarth.terrain(settings);
        return holders.get(t.tileAt(bx, bz).biome[Terrain.index(bx, bz)]);
    }
}
