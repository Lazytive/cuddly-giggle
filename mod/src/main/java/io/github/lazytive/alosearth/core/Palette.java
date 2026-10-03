package io.github.lazytive.alosearth.core;

import java.util.HashMap;
import java.util.Map;

/** Block and biome names used by the terrain core (vanilla ids without "minecraft:"). */
public final class Palette {
    private Palette() {
    }

    public static final String[] BLOCKS = {
        "air", "bedrock", "deepslate", "stone", "dirt", "grass_block", "sand", "sandstone", "red_sand",
        "terracotta", "orange_terracotta", "yellow_terracotta", "white_terracotta", "red_terracotta",
        "brown_terracotta", "light_gray_terracotta", "gravel", "snow_block", "packed_ice", "water",
        "coarse_dirt", "podzol", "clay", "mud",
        "moss_block", "mossy_cobblestone", "andesite", "mycelium", "cobblestone",
    };
    public static final int AIR = 0, BEDROCK = 1, DEEPSLATE = 2, STONE = 3, DIRT = 4, GRASS = 5, SAND = 6,
        SANDSTONE = 7, RED_SAND = 8, TERRACOTTA = 9, ORANGE_TC = 10, YELLOW_TC = 11, WHITE_TC = 12, RED_TC = 13,
        BROWN_TC = 14, LIGHT_GRAY_TC = 15, GRAVEL = 16, SNOW_BLOCK = 17, PACKED_ICE = 18, WATER = 19,
        COARSE_DIRT = 20, PODZOL = 21, CLAY = 22, MUD = 23, MOSS = 24, MOSSY_COBBLE = 25, ANDESITE = 26,
        MYCELIUM = 27, COBBLE = 28;

    /** Badlands terracotta bands, repeating with height. */
    static final int[] BANDS = {
        TERRACOTTA, ORANGE_TC, TERRACOTTA, YELLOW_TC, BROWN_TC, TERRACOTTA, WHITE_TC, ORANGE_TC,
        RED_TC, TERRACOTTA, LIGHT_GRAY_TC, ORANGE_TC, TERRACOTTA, YELLOW_TC, RED_TC, TERRACOTTA,
    };
    static final int BAND = -1;

    /** Surface styles: top block, sub-surface block and depth, second layer and depth. */
    static final int[][] STYLES = {
        {GRASS, DIRT, 3, DIRT, 0},              // 0 grass
        {SAND, SAND, 3, SANDSTONE, 4},          // 1 desert
        {RED_SAND, BAND, 14, STONE, 0},         // 2 badlands
        {SNOW_BLOCK, SNOW_BLOCK, 2, STONE, 0},  // 3 snowy peaks
        {SNOW_BLOCK, PACKED_ICE, 24, STONE, 0}, // 4 ice sheet
        {STONE, STONE, 0, STONE, 0},            // 5 bare rock
        {GRAVEL, GRAVEL, 2, STONE, 0},          // 6 scree
        {COARSE_DIRT, DIRT, 3, DIRT, 0},        // 7 dry ground
        {PODZOL, DIRT, 3, DIRT, 0},             // 8 forest floor
        {MUD, MUD, 3, DIRT, 2},                 // 9 mangrove mud
        {SAND, SAND, 3, SANDSTONE, 3},          // 10 beach / sandy bed
        {GRAVEL, GRAVEL, 3, STONE, 0},          // 11 gravel bed
        {CLAY, CLAY, 1, SAND, 2},               // 12 clay bed
        {BAND, BAND, 14, STONE, 0},             // 13 badlands cliff
        {MOSS, DIRT, 3, DIRT, 0},               // 14 mossy forest floor
        {GRAVEL, DIRT, 2, DIRT, 1},             // 15 gravel patch
        {ANDESITE, ANDESITE, 2, STONE, 0},      // 16 andesite outcrop
        {MOSSY_COBBLE, COBBLE, 1, STONE, 0},    // 17 mossy boulder
        {STONE, COBBLE, 1, STONE, 0},           // 18 stone boulder
        {MYCELIUM, DIRT, 3, DIRT, 0},           // 19 mushroom island
    };
    static final int S_GRASS = 0, S_DESERT = 1, S_BADLANDS = 2, S_SNOW = 3, S_ICE = 4, S_ROCK = 5, S_SCREE = 6,
        S_DRY = 7, S_PODZOL = 8, S_MUD = 9, S_SAND = 10, S_GRAVEL = 11, S_CLAY = 12, S_BAND_CLIFF = 13,
        S_MOSS = 14, S_GRAVEL_PATCH = 15, S_ANDESITE = 16, S_MOSSY_BOULDER = 17, S_BOULDER = 18, S_MYCELIUM = 19;

    public static final String[] BIOMES = {
        "ocean", "deep_ocean", "warm_ocean", "lukewarm_ocean", "deep_lukewarm_ocean", "cold_ocean",
        "deep_cold_ocean", "frozen_ocean", "deep_frozen_ocean", "river", "frozen_river", "beach", "snowy_beach",
        "stony_shore", "plains", "sunflower_plains", "forest", "flower_forest", "birch_forest", "dark_forest",
        "taiga", "old_growth_pine_taiga", "old_growth_spruce_taiga", "snowy_taiga", "snowy_plains", "ice_spikes",
        "desert", "badlands", "wooded_badlands", "savanna", "savanna_plateau", "jungle", "sparse_jungle",
        "bamboo_jungle", "swamp", "mangrove_swamp", "meadow", "grove", "snowy_slopes", "frozen_peaks",
        "jagged_peaks", "stony_peaks", "windswept_hills", "windswept_forest", "windswept_gravelly_hills",
        "cherry_grove", "mushroom_fields", "eroded_badlands", "windswept_savanna", "old_growth_birch_forest",
        "lush_caves", "dripstone_caves",
    };
    private static final Map<String, Integer> BIOME_INDEX = new HashMap<>();

    static {
        for (int i = 0; i < BIOMES.length; i++) BIOME_INDEX.put(BIOMES[i], i);
    }

    public static int biome(String name) {
        Integer i = BIOME_INDEX.get(name);
        if (i == null) throw new IllegalArgumentException("unknown biome " + name);
        return i;
    }
}
