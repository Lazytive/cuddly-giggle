package io.github.lazytive.alosearth;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.DimensionRenderingRegistry;
import net.minecraft.client.renderer.DimensionSpecialEffects;

/**
 * Client side: overworld skies with clouds at a realistic height for the big-scale world types
 * (vanilla fixes them at y 192, which would be underwater or grazing the coast there). Players
 * without the mod see ordinary overworld skies.
 */
public final class AlosEarthClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        clouds("high_clouds", 480);     // 1:5 and max worlds: about 1.3-2 km above sea level
        clouds("clouds_1to1", 1300);    // 1:1: about 1.2 km above sea level
    }

    private static void clouds(String name, float height) {
        DimensionRenderingRegistry.registerDimensionEffects(AlosEarth.id(name), new DimensionSpecialEffects.OverworldEffects() {
            @Override
            public float getCloudHeight() {
                return height;
            }
        });
    }
}
