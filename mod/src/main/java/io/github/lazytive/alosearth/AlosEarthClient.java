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
        // On someone else's server the client can't see the world's settings; the dimension type
        // tells which ALOS Earth world it is, which is enough for where rain turns to snow.
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (client.hasSingleplayerServer() || client.level == null) return; // the built-in server already set it
            String type = client.level.dimensionTypeRegistration().unwrapKey()
                .map(k -> k.location().getNamespace().equals(AlosEarth.MOD_ID) ? k.location().getPath() : "").orElse("");
            EarthClimate.active = switch (type) {
                case "earth" -> io.github.lazytive.alosearth.core.EarthSettings.MINECRAFT_LIKE;
                case "earth_true" -> io.github.lazytive.alosearth.core.EarthSettings.TRUE_SCALE;
                case "earth_1to10" -> io.github.lazytive.alosearth.core.EarthSettings.ONE_TO_TEN;
                case "earth_1to5" -> io.github.lazytive.alosearth.core.EarthSettings.ONE_TO_FIVE;
                case "earth_max" -> io.github.lazytive.alosearth.core.EarthSettings.MAX;
                case "earth_1to1", "earth_deep" -> io.github.lazytive.alosearth.core.EarthSettings.ONE_TO_ONE;
                default -> null;
            };
        });
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (!client.hasSingleplayerServer()) EarthClimate.active = null;
        });
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
