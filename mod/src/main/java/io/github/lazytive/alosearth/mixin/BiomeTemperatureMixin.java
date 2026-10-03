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

/**
 * Snow by real altitude in ALOS Earth worlds (see {@link EarthClimate}): vanilla's result has its
 * own "colder above y 80" part taken back out, and the real-altitude cooling put in instead.
 */
@Mixin(Biome.class)
public abstract class BiomeTemperatureMixin {
    @Shadow
    @Final
    private static PerlinSimplexNoise TEMPERATURE_NOISE;

    // optional (require = 0): if another mod has replaced this method, the game still starts and
    // snow just follows vanilla's rule
    @Inject(method = "getHeightAdjustedTemperature", at = @At("RETURN"), cancellable = true, require = 0)
    private void alosearth$realAltitude(BlockPos pos, CallbackInfoReturnable<Float> cir) {
        if (!EarthClimate.changes(pos.getY())) return; // most calls: low ground, nothing to change
        double noise = TEMPERATURE_NOISE.getValue(pos.getX() / 8.0, pos.getZ() / 8.0, false);
        float vanillaDrop = pos.getY() > 80 ? ((float) (noise * 8.0) + pos.getY() - 80.0F) * 0.05F / 40.0F : 0;
        cir.setReturnValue((float) (cir.getReturnValue() + vanillaDrop - EarthClimate.drop(pos.getY(), noise)));
    }
}
