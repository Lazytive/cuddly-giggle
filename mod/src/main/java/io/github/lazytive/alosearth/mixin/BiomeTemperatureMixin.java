package io.github.lazytive.alosearth.mixin;

import io.github.lazytive.alosearth.EarthClimate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.synth.PerlinSimplexNoise;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Snow by real altitude in ALOS Earth worlds (see {@link EarthClimate}). */
@Mixin(Biome.class)
public abstract class BiomeTemperatureMixin {
    @Shadow
    @Final
    private static PerlinSimplexNoise TEMPERATURE_NOISE;

    @Shadow
    @Final
    private Biome.ClimateSettings climateSettings;

    @Inject(method = "getHeightAdjustedTemperature", at = @At("HEAD"), cancellable = true)
    private void alosearth$realAltitude(BlockPos pos, CallbackInfoReturnable<Float> cir) {
        if (EarthClimate.active == null) return;
        float base = climateSettings.temperatureModifier().modifyTemperature(pos, climateSettings.temperature());
        double noise = TEMPERATURE_NOISE.getValue(pos.getX() / 8.0, pos.getZ() / 8.0, false);
        cir.setReturnValue((float) (base - EarthClimate.drop(pos.getY(), noise)));
    }
}
