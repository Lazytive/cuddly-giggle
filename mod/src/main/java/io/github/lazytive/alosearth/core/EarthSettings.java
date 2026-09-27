package io.github.lazytive.alosearth.core;

/**
 * Shape of the world. The defaults are the "Minecraft-like" style: about
 * 1 block = 30 m across, lowland relief exaggerated about 3x and mountains
 * compressed so Everest peaks near y 600.
 */
public record EarthSettings(double centerLat, double centerLon, double metersPerBlock, int margin,
                            int minY, int height, int seaLevel,
                            double landScale, double landKnee, double oceanScale, double oceanKnee,
                            double detail) {
    public static final EarthSettings DEFAULT = new EarthSettings(0.0, 24.0, 30.0, 512,
        -128, 768, 63, 575.0, 5750.0, 60.0, 600.0, 1.0);

    public int maxY() {
        return minY + height;
    }

    /** Blocks above sea level for an elevation in metres (negative below). */
    public double landBlocks(double meters) {
        double b = landScale * Math.log1p(Math.abs(meters) / landKnee);
        return meters < 0 ? -b : b;
    }

    /** Blocks of water for a depth in metres. */
    public double oceanBlocks(double depthMeters) {
        return oceanScale * Math.log1p(Math.max(0, depthMeters) / oceanKnee);
    }
}
