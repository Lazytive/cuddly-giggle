package io.github.lazytive.alosearth;

import io.github.lazytive.alosearth.core.EarthSettings;

/**
 * The ALOS Earth world being played, for the biome temperature change (snow by real altitude).
 * Minecraft cools every biome above y 80, whatever the world's scale; ALOS Earth worlds instead
 * cool with real altitude above 1000 m, so snow starts where it would on the real mountain.
 */
public final class EarthClimate {
    private EarthClimate() {
    }

    /** Settings of the loaded ALOS Earth overworld (with the Minecraft-feel version), or null. */
    public static volatile EarthSettings active;

    /** Temperature drop for a block height, or -1 for vanilla behaviour. */
    public static double drop(int y, double noise) {
        EarthSettings s = active;
        if (s == null) return -1;
        double m = s.metersAt(y) + noise * 40;
        return Math.max(0, m - 1000) * 0.000325;
    }
}
