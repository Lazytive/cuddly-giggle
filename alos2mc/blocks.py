"""Block states, biomes and surface materials used by the generator."""

from __future__ import annotations

import numpy as np

# Global block ids (indices into BLOCKS).  Order matters only internally.
AIR, BEDROCK, DEEPSLATE, STONE, DIRT, GRASS, SAND, SANDSTONE, GRAVEL, SNOW_BLOCK, PACKED_ICE, WATER, \
    COARSE_DIRT, PODZOL, CLAY, MUD = range(16)

BLOCKS: list[dict] = [
    {"Name": "minecraft:air"},
    {"Name": "minecraft:bedrock"},
    {"Name": "minecraft:deepslate", "Properties": {"axis": "y"}},
    {"Name": "minecraft:stone"},
    {"Name": "minecraft:dirt"},
    {"Name": "minecraft:grass_block", "Properties": {"snowy": "false"}},
    {"Name": "minecraft:sand"},
    {"Name": "minecraft:sandstone"},
    {"Name": "minecraft:gravel"},
    {"Name": "minecraft:snow_block"},
    {"Name": "minecraft:packed_ice"},
    {"Name": "minecraft:water", "Properties": {"level": "0"}},
    {"Name": "minecraft:coarse_dirt"},
    {"Name": "minecraft:podzol", "Properties": {"snowy": "false"}},
    {"Name": "minecraft:clay"},
    {"Name": "minecraft:mud"},
]
BLOCK_NAMES = [b["Name"] for b in BLOCKS]

# Map colours (RGB) for the preview renderer.
BLOCK_COLORS = np.array([
    (0, 0, 0), (40, 40, 40), (70, 70, 80), (125, 125, 125), (134, 96, 67), (95, 159, 53), (219, 207, 163),
    (216, 203, 155), (136, 126, 126), (240, 251, 251), (141, 180, 250), (52, 88, 200), (119, 85, 59),
    (91, 63, 24), (160, 166, 179), (60, 57, 61),
], dtype=np.uint8)

# Biomes (indices into BIOMES)
BIOMES = [
    "minecraft:ocean", "minecraft:warm_ocean", "minecraft:lukewarm_ocean", "minecraft:cold_ocean",
    "minecraft:frozen_ocean", "minecraft:river", "minecraft:frozen_river", "minecraft:beach",
    "minecraft:snowy_beach", "minecraft:plains", "minecraft:forest", "minecraft:birch_forest",
    "minecraft:taiga", "minecraft:snowy_taiga", "minecraft:snowy_plains", "minecraft:desert",
    "minecraft:savanna", "minecraft:jungle", "minecraft:sparse_jungle", "minecraft:swamp",
    "minecraft:stony_peaks", "minecraft:frozen_peaks", "minecraft:ice_spikes", "minecraft:meadow",
]
B = {name.split(":")[1]: i for i, name in enumerate(BIOMES)}

# Surface material types: (top block, sub-surface block, sub-surface depth)
SURFACES = [
    (GRASS, DIRT, 3),          # 0 grass
    (SAND, SAND, 4),           # 1 sand (deserts, beaches)
    (SNOW_BLOCK, STONE, 1),    # 2 snow on rock (mountains)
    (STONE, STONE, 0),         # 3 bare rock (steep slopes)
    (COARSE_DIRT, DIRT, 3),    # 4 dry grassland
    (PODZOL, DIRT, 3),         # 5 boreal forest floor
    (SNOW_BLOCK, PACKED_ICE, 12),  # 6 ice sheet
    (SAND, SAND, 3),           # 7 shallow sea floor
    (GRAVEL, STONE, 2),        # 8 deep sea floor
    (CLAY, DIRT, 2),           # 9 lake bed
    (MUD, DIRT, 3),            # 10 wetland
]
S_GRASS, S_SAND, S_SNOW, S_ROCK, S_DRY, S_PODZOL, S_ICE, S_SEABED, S_DEEPBED, S_LAKEBED, S_MUD = range(len(SURFACES))
SURF_TOP = np.array([s[0] for s in SURFACES], dtype=np.uint8)
SURF_SUB = np.array([s[1] for s in SURFACES], dtype=np.uint8)
SURF_DEPTH = np.array([s[2] for s in SURFACES], dtype=np.int32)

# Koppen-Geiger classes as numbered in Beck et al. (2018/2023) 1 km maps:
# class -> (biome, surface)
_KOPPEN = {
    1: ("jungle", S_GRASS),        # Af
    2: ("jungle", S_GRASS),        # Am
    3: ("savanna", S_GRASS),       # Aw
    4: ("desert", S_SAND),         # BWh
    5: ("desert", S_SAND),         # BWk
    6: ("savanna", S_DRY),         # BSh
    7: ("plains", S_DRY),          # BSk
    8: ("plains", S_GRASS),        # Csa
    9: ("plains", S_GRASS),        # Csb
    10: ("meadow", S_GRASS),       # Csc
    11: ("forest", S_GRASS),       # Cwa
    12: ("forest", S_GRASS),       # Cwb
    13: ("meadow", S_GRASS),       # Cwc
    14: ("forest", S_GRASS),       # Cfa
    15: ("forest", S_GRASS),       # Cfb
    16: ("taiga", S_GRASS),        # Cfc
    17: ("plains", S_GRASS),       # Dsa
    18: ("forest", S_GRASS),       # Dsb
    19: ("taiga", S_PODZOL),       # Dsc
    20: ("snowy_taiga", S_PODZOL),  # Dsd
    21: ("forest", S_GRASS),       # Dwa
    22: ("birch_forest", S_GRASS),  # Dwb
    23: ("taiga", S_PODZOL),       # Dwc
    24: ("snowy_taiga", S_PODZOL),  # Dwd
    25: ("forest", S_GRASS),       # Dfa
    26: ("birch_forest", S_GRASS),  # Dfb
    27: ("taiga", S_PODZOL),       # Dfc
    28: ("snowy_taiga", S_PODZOL),  # Dfd
    29: ("snowy_plains", S_GRASS),  # ET tundra
    30: ("snowy_plains", S_ICE),   # EF ice cap
}
KOPPEN_BIOME = np.full(256, 255, dtype=np.uint8)
KOPPEN_SURFACE = np.full(256, 255, dtype=np.uint8)
for _k, (_b, _s) in _KOPPEN.items():
    KOPPEN_BIOME[_k] = B[_b]
    KOPPEN_SURFACE[_k] = _s
# Biomes that count as "cold" for beaches/rivers
COLD_BIOMES = np.zeros(len(BIOMES), dtype=bool)
for _n in ("snowy_taiga", "snowy_plains", "frozen_peaks", "ice_spikes", "frozen_ocean", "frozen_river", "snowy_beach"):
    COLD_BIOMES[B[_n]] = True
