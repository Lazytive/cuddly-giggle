package io.github.lazytive.alosearth.core;

/**
 * Shape of the world. {@link #DEFAULT} is the "Minecraft-like" style: about
 * 1 block = 30 m across, lowland relief exaggerated about 3x and mountains
 * compressed so Everest peaks near y 600. {@link #TRUE_SCALE} keeps the
 * data's real proportions: 1 block = 30 m vertically too (Everest ~y 358,
 * the Mariana Trench ~y -304) with only light detail added.
 * {@link #ONE_TO_TEN} and {@link #ONE_TO_FIVE} are 1 block = 10 m and 5 m, fully to scale. {@link #ONE_TO_ONE} is 1 block = 1 m: oceans at their real depth, carried
 * on below the world's floor by {@code deepLayers} stacked dimensions, and
 * land 1:1 near sea level, easing off so Everest fits under the build limit.
 *
 * <p>All heights inside the terrain are "virtual" y: the main world's y,
 * continued downwards through the deep layers. Layer {@code k} shows virtual
 * y {@code localY - k * layerShift()}.
 */
public record EarthSettings(double centerLat, double centerLon, double metersPerBlock, int margin,
                            int minY, int height, int seaLevel,
                            double landScale, double landKnee, double oceanScale, double oceanKnee,
                            double detail, boolean trueScale, boolean seaFloor, boolean trueOcean, int deepLayers,
                            int version) {
    /**
     * Terrain generation version. 0: worlds made before beta.21 (kept exactly as they were, so new
     * chunks match old ones). 1: the "Minecraft feel" features (rivers and streams, cliffs and
     * ledges, outcrops and boulders, overhangs, dunes, surface patches, more biomes, caves).
     */
    public static final int CURRENT_VERSION = 1;

    /** Blocks shared by two stacked layers (so the view across the join matches). */
    public static final int LAYER_OVERLAP = 96;

    public static final EarthSettings DEFAULT = new EarthSettings(0.0, 24.0, 30.0, 512,
        -128, 768, 63, 575.0, 5750.0, 60.0, 600.0, 1.0, false, false, false, 0, 0);
    public static final EarthSettings TRUE_SCALE = new EarthSettings(0.0, 24.0, 30.0, 512,
        -320, 704, 63, 575.0, 5750.0, 60.0, 600.0, 0.25, true, true, true, 0, CURRENT_VERSION);
    public static final EarthSettings ONE_TO_ONE = new EarthSettings(0.0, 24.0, 1.0, 512,
        -2032, 4064, 63, 760.0, 760.0, 60.0, 600.0, 1.0, false, true, true, 3, CURRENT_VERSION);

    /**
     * 1 block = 10 m with real proportions (Everest ~y 948, the Mariana Trench ~y -1036): the
     * whole Earth fits in one dimension without squashing mountains or stacking layers.
     */
    public static final EarthSettings ONE_TO_TEN = new EarthSettings(0.0, 24.0, 10.0, 512,
        -1152, 2240, 63, 575.0, 5750.0, 60.0, 600.0, 0.35, true, true, true, 0, CURRENT_VERSION);

    /**
     * 1 block = 5 m with real proportions in one Minecraft dimension (y -2032..2031): sea level
     * y 161, so the Challenger Deep (10.9 km) ends just above bedrock and Everest peaks near y 1931.
     * (1:5 worlds made before sea level moved keep y 63, where only trench bottoms are trimmed.)
     */
    public static final EarthSettings ONE_TO_FIVE = new EarthSettings(0.0, 24.0, 5.0, 512,
        -2032, 4064, 161, 575.0, 5750.0, 60.0, 600.0, 0.3, true, true, true, 0, CURRENT_VERSION);

    /**
     * The largest Earth with real proportions that Minecraft can hold: 1 block = 4.9 m, the
     * Challenger Deep at the bottom of the world (~y -2026) and Everest at the top (~y 2012).
     */
    public static final EarthSettings MAX = new EarthSettings(0.0, 24.0, 4.9, 512,
        -2032, 4064, 206, 575.0, 5750.0, 60.0, 600.0, 0.3, true, true, true, 0, CURRENT_VERSION);

    /** DEFAULT as new worlds get it (with the downloaded sea floor). */
    public static final EarthSettings MINECRAFT_LIKE = DEFAULT.withSeaFloor(true).withVersion(CURRENT_VERSION);

    public EarthSettings withSeaFloor(boolean on) {
        return new EarthSettings(centerLat, centerLon, metersPerBlock, margin, minY, height, seaLevel, landScale,
            landKnee, oceanScale, oceanKnee, detail, trueScale, on, trueOcean, deepLayers, version);
    }

    public EarthSettings withCenter(double lat, double lon) {
        return new EarthSettings(lat, lon, metersPerBlock, margin, minY, height, seaLevel, landScale,
            landKnee, oceanScale, oceanKnee, detail, trueScale, seaFloor, trueOcean, deepLayers, version);
    }

    public EarthSettings withVersion(int v) {
        return new EarthSettings(centerLat, centerLon, metersPerBlock, margin, minY, height, seaLevel, landScale,
            landKnee, oceanScale, oceanKnee, detail, trueScale, seaFloor, trueOcean, deepLayers, v);
    }

    /** True for worlds with the "Minecraft feel" features. */
    public boolean minecraftFeel() {
        return version >= 1;
    }

    public int maxY() {
        return minY + height;
    }

    /** How far down each deep layer is shifted from the one above it. */
    public int layerShift() {
        return height - LAYER_OVERLAP;
    }

    /** The lowest virtual y (bottom of the deepest layer); bedrock is here. */
    public int bottomY() {
        return minY - deepLayers * layerShift();
    }

    /** True when one data pixel spans many blocks (smooth interpolation, finer detail). */
    public boolean fine() {
        return metersPerBlock <= 10;
    }

    /** Blocks above sea level for an elevation in metres (negative below). */
    public double landBlocks(double meters) {
        if (trueScale) return meters / metersPerBlock;
        double b = landScale * Math.log1p(Math.abs(meters) / landKnee);
        return meters < 0 ? -b : b;
    }

    /** Real elevation in metres for a virtual y (inverse of the height curves; negative is under the sea). */
    public double metersAt(double y) {
        double b = y - seaLevel;
        if (b >= 0) return trueScale ? b * metersPerBlock : landKnee * Math.expm1(b / landScale);
        if (trueScale || trueOcean) return b * metersPerBlock;
        return -oceanKnee * Math.expm1(-b / oceanScale);
    }

    /** Blocks of water for a depth in metres. */
    public double oceanBlocks(double depthMeters) {
        if (trueScale || trueOcean) return Math.max(0, depthMeters) / metersPerBlock;
        return oceanScale * Math.log1p(Math.max(0, depthMeters) / oceanKnee);
    }
}
