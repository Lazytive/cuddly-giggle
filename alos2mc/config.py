"""World configuration, stored as ``alos2mc.json`` inside the world folder so
that later ``generate`` runs (resuming, adding areas) stay consistent."""

from __future__ import annotations

import json
import math
import os
from dataclasses import asdict, dataclass, field

import numpy as np

from .anvil import DEFAULT_DATA_VERSION
from .projection import Projection, make_projection

EVEREST_M = 8849.0
DEEPEST_M = 11000.0
CONFIG_NAME = "alos2mc.json"


def _floor16(v: float) -> int:
    return int(math.floor(v / 16)) * 16


def _ceil16(v: float) -> int:
    return int(math.ceil(v / 16)) * 16


@dataclass
class WorldConfig:
    level_name: str = "ALOS Earth"
    projection: str = "cube"
    meters_per_block: float = 30.0
    vertical_meters_per_block: float = 30.0
    lat0: float = 0.0                 # centre of the map (cube: centre of the front face)
    lon0: float = 24.0
    margin: int = 512
    height_mode: str = "vanilla"      # vanilla (-64..320) or extended (datapack dimension_type)
    min_y: int | None = None
    max_y: int | None = None
    sea_level: int | None = None
    ocean_default_depth: int = 10     # blocks of water where no bathymetry is available
    ocean_knee_m: float = 1200.0      # depths below this are compressed if they do not fit
    lake_depth: int = 2
    steep_threshold: int = 2          # blocks of rise per block that exposes bare rock
    ocean_biomes: str = "auto"        # auto | uniform | latitude
    skip_default_ocean: bool = True   # leave open-ocean chunks to the flat generator
    data_version: int = DEFAULT_DATA_VERSION
    game_mode: int = 1                # 0 survival, 1 creative
    spawn: list[float] | None = None  # [lat, lon]
    dem: list[str] = field(default_factory=list)
    bathymetry: list[str] = field(default_factory=list)
    climate: list[str] = field(default_factory=list)

    def __post_init__(self):
        v = self.vertical_meters_per_block
        land = math.ceil(EVEREST_M / v)
        deep = math.ceil(DEEPEST_M / v)
        if self.height_mode == "vanilla":
            self.min_y = -64 if self.min_y is None else self.min_y
            self.max_y = 320 if self.max_y is None else self.max_y
            if self.sea_level is None:
                self.sea_level = min(63, self.max_y - 1 - land - 4)
        elif self.height_mode == "extended":
            if self.sea_level is None:
                self.sea_level = 63
            if self.max_y is None:
                self.max_y = _ceil16(self.sea_level + land + 16)
            if self.min_y is None:
                self.min_y = _floor16(self.sea_level - deep - 4)
        else:
            raise ValueError("height_mode must be 'vanilla' or 'extended'")
        if self.min_y % 16 or self.max_y % 16:
            raise ValueError("min_y and max_y must be multiples of 16")
        if not (-2032 <= self.min_y and self.max_y <= 2032 and self.max_y - self.min_y <= 4064):
            raise ValueError("world height out of Minecraft's supported range")
        if not (self.min_y + 2 < self.sea_level < self.max_y):
            raise ValueError("sea level must be inside the world height")
        if self.ocean_biomes == "auto":
            self.ocean_biomes = "latitude" if self.bathymetry else "uniform"

    # ---------------------------------------------------------------- io
    def save(self, world_dir: str) -> None:
        with open(os.path.join(world_dir, CONFIG_NAME), "w") as f:
            json.dump(asdict(self), f, indent=2)

    @classmethod
    def load(cls, world_dir: str) -> "WorldConfig":
        with open(os.path.join(world_dir, CONFIG_NAME)) as f:
            return cls(**json.load(f))

    def make_projection(self) -> Projection:
        return make_projection(self.projection, self.meters_per_block, self.lon0, self.margin, self.lat0)

    # -------------------------------------------------------- vertical map
    def land_y(self, elev_m):
        return self.sea_level + np.rint(np.asarray(elev_m) / self.vertical_meters_per_block).astype(np.int32)

    def sea_floor_y(self, depth_m):
        """Top block of the sea floor for a (positive) water depth."""
        v = self.vertical_meters_per_block
        d = np.maximum(np.asarray(depth_m, dtype=np.float64) / v, 1.0)
        avail = self.sea_level - (self.min_y + 2)
        full = DEEPEST_M / v
        if full > avail:
            knee = min(self.ocean_knee_m / v, avail * 0.6)
            k = (avail - knee) / (full - knee)
            d = np.where(d <= knee, d, knee + (d - knee) * k)
        d = np.clip(np.rint(d), 1, avail)
        return (self.sea_level - d).astype(np.int32)

    def default_floor_y(self) -> int:
        return max(self.min_y + 2, self.sea_level - self.ocean_default_depth)
