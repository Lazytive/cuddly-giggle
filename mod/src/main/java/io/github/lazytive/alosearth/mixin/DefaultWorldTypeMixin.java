package io.github.lazytive.alosearth.mixin;

import io.github.lazytive.alosearth.AlosEarth;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** "Create New World" starts on ALOS Earth (1:10) instead of vanilla's Default world type. */
@Mixin(WorldCreationUiState.class)
public abstract class DefaultWorldTypeMixin {
    private static final ResourceKey<WorldPreset> ONE_TO_TEN = ResourceKey.create(Registries.WORLD_PRESET, AlosEarth.id("earth_1to10"));

    // require = 0: if a Minecraft update moved this, world creation just keeps vanilla's default
    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void alosearth$defaultToEarth(CallbackInfo ci) {
        WorldCreationUiState self = (WorldCreationUiState) (Object) this;
        var current = self.getWorldType().preset();
        if (current != null && !current.is(WorldPresets.NORMAL)) return; // something else was asked for
        self.getSettings().worldgenLoadContext().registryOrThrow(Registries.WORLD_PRESET).getHolder(ONE_TO_TEN)
            .ifPresent(h -> self.setWorldType(new WorldCreationUiState.WorldTypeEntry(h)));
    }
}
