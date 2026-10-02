package io.github.lazytive.alosearth;

import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.resources.ResourceLocation;

/**
 * Client side, whatever the loader: overworld skies with clouds at a realistic height for the
 * big-scale world types (vanilla fixes them at y 192, which would be underwater or grazing the
 * coast there). Players without the mod see ordinary overworld skies.
 */
public final class AlosEarthClient {
    private AlosEarthClient() {
    }

    /** Sky effects for the dimension types that name them, for the loader to register. */
    public static Map<ResourceLocation, DimensionSpecialEffects> skies() {
        return Map.of(
            AlosEarth.id("high_clouds"), clouds(480),   // 1:5 and max worlds: about 1.3-2 km above sea level
            AlosEarth.id("clouds_1to1"), clouds(1300)); // 1:1: about 1.2 km above sea level
    }

    private static DimensionSpecialEffects clouds(float height) {
        return new DimensionSpecialEffects.OverworldEffects() {
            @Override
            public float getCloudHeight() {
                return height;
            }
        };
    }

    /**
     * Joined a server. On someone else's server the client can't see the world's settings; the
     * dimension type tells which ALOS Earth world it is, which is enough for where rain turns to snow.
     */
    public static void joined(Minecraft client) {
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
    }

    public static void left(Minecraft client) {
        if (!client.hasSingleplayerServer()) EarthClimate.active = null;
    }
}
