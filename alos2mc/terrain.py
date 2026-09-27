"""Turns elevation, water and climate samples into block columns."""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np

from . import blocks as bk
from .config import WorldConfig
from .raster import CLS_LAKE, CLS_LAND, CLS_SEA, Aw3d30, RasterSet

# Approximate permanent snow line (metres) by absolute latitude.
_SNOW_LAT = [0, 20, 30, 45, 60, 70, 80, 90]
_SNOW_M = [4800, 5300, 5000, 3000, 1500, 600, 100, 0]


class Sources:
    """The input datasets named in a :class:`WorldConfig`."""

    def __init__(self, cfg: WorldConfig):
        self.dem = Aw3d30(cfg.dem) if cfg.dem else None
        self.bathy = RasterSet(cfg.bathymetry) if cfg.bathymetry else None
        self.climate = RasterSet(cfg.climate) if cfg.climate else None


@dataclass
class Columns:
    top: np.ndarray      # int32 y of the top solid block
    surf: np.ndarray     # uint8 surface material (blocks.SURFACES)
    water: np.ndarray    # int32 y of the highest water block (< top: none)
    biome: np.ndarray    # uint8 index into blocks.BIOMES
    default: np.ndarray  # bool: identical to the flat generator's open ocean


def _shift_max(a: np.ndarray, r: int) -> np.ndarray:
    """Maximum over a (2r+1)^2 neighbourhood (edges clamp)."""
    out = a.copy()
    for axis in (0, 1):
        src = out.copy()
        for d in range(1, r + 1):
            fwd = np.roll(src, d, axis=axis)
            bwd = np.roll(src, -d, axis=axis)
            if axis == 0:
                fwd[:d] = src[:d]
                bwd[-d:] = src[-d:]
            else:
                fwd[:, :d] = src[:, :d]
                bwd[:, -d:] = src[:, -d:]
            out = np.maximum(out, np.maximum(fwd, bwd))
    return out


def _max_slope(h: np.ndarray) -> np.ndarray:
    s = np.zeros_like(h)
    s[1:, :] = np.maximum(s[1:, :], np.abs(h[1:, :] - h[:-1, :]))
    s[:-1, :] = np.maximum(s[:-1, :], np.abs(h[:-1, :] - h[1:, :]))
    s[:, 1:] = np.maximum(s[:, 1:], np.abs(h[:, 1:] - h[:, :-1]))
    s[:, :-1] = np.maximum(s[:, :-1], np.abs(h[:, :-1] - h[:, 1:]))
    return s


def ocean_biome_by_latitude(alat: np.ndarray) -> np.ndarray:
    return np.select(
        [alat < 20, alat < 35, alat < 50, alat < 62],
        [bk.B["warm_ocean"], bk.B["lukewarm_ocean"], bk.B["ocean"], bk.B["cold_ocean"]],
        bk.B["frozen_ocean"],
    ).astype(np.uint8)


def compute_columns(lon: np.ndarray, lat: np.ndarray, valid: np.ndarray, src: Sources,
                    cfg: WorldConfig, border: int) -> Columns:
    """Build columns for a 2-D grid of sample points.  ``border`` rows and
    columns on each side are only used as neighbourhood context and are
    cropped from the result."""
    shape = lon.shape
    sea_y = cfg.sea_level
    vl = valid
    lo, la = lon[vl], lat[vl]

    elev = np.full(shape, np.nan, dtype=np.float32)
    cls = np.full(shape, CLS_SEA, dtype=np.uint8)
    if src.dem is not None and lo.size:
        e, c = src.dem.sample(lo, la)
        elev[vl] = e
        cls[vl] = c
    bathy = np.full(shape, np.nan, dtype=np.float32)
    if src.bathy is not None and lo.size:
        bathy[vl] = src.bathy.sample(lo, la)

    # Where the DEM has no data, fall back to the bathymetry grid (GEBCO also
    # covers land), otherwise to open ocean.
    missing = np.isnan(elev)
    fb = missing & ~np.isnan(bathy)
    elev[fb] = bathy[fb]
    cls[fb] = np.where(bathy[fb] < 0, CLS_SEA, CLS_LAND)
    cls[missing & ~fb] = CLS_SEA
    cls[~vl] = CLS_SEA

    is_sea = cls == CLS_SEA
    is_lake = cls == CLS_LAKE
    is_land = ~is_sea & ~is_lake

    land_y = cfg.land_y(np.nan_to_num(elev, nan=0.0))
    top = land_y.copy()
    water = np.full(shape, cfg.min_y, dtype=np.int32)

    # --- sea
    has_depth = is_sea & ~np.isnan(bathy)
    if has_depth.any():
        top[has_depth] = cfg.sea_floor_y(-bathy[has_depth])
    no_depth = is_sea & ~has_depth
    top[no_depth] = cfg.default_floor_y()
    water[is_sea] = sea_y

    # --- lakes and rivers
    water[is_lake] = land_y[is_lake]
    top[is_lake] = land_y[is_lake] - cfg.lake_depth

    # --- climate
    alat = np.abs(lat)
    alat = np.where(np.isnan(alat), 0, alat)
    clim_b = np.full(shape, 255, dtype=np.uint8)
    clim_s = np.full(shape, 255, dtype=np.uint8)
    if src.climate is not None and lo.size:
        k = src.climate.sample(lo, la, method="nearest")
        k = np.where(np.isnan(k), 0, k).astype(np.int64).clip(0, 255)
        kk = np.zeros(shape, dtype=np.int64)
        kk[vl] = k
        clim_b = bk.KOPPEN_BIOME[kk]
        clim_s = bk.KOPPEN_SURFACE[kk]
    # latitude fallback where there is no climate class
    fb_b = np.select([alat >= 66, alat >= 58, alat >= 45],
                     [bk.B["snowy_plains"], bk.B["taiga"], bk.B["forest"]], bk.B["plains"]).astype(np.uint8)
    fb_s = np.select([alat >= 58], [bk.S_PODZOL], bk.S_GRASS).astype(np.uint8)
    nocl = clim_b == 255
    biome = np.where(nocl, fb_b, clim_b).astype(np.uint8)
    surf = np.where(nocl, fb_s, clim_s).astype(np.uint8)

    # --- relief overrides on land
    snow_m = np.interp(alat, _SNOW_LAT, _SNOW_M)
    e0 = np.nan_to_num(elev, nan=0.0)
    surface_h = np.where(is_land, top, np.maximum(top, water))
    slope = _max_slope(surface_h)
    steep = is_land & (slope >= cfg.steep_threshold)
    snowy = is_land & (e0 > snow_m) & (surf != bk.S_ICE)
    surf = np.where(steep & (slope >= cfg.steep_threshold + 2), bk.S_ROCK,
                    np.where(snowy, bk.S_SNOW, np.where(steep, bk.S_ROCK, surf))).astype(np.uint8)
    biome = np.where(snowy, bk.B["frozen_peaks"],
                     np.where(steep & (e0 > 2000), bk.B["stony_peaks"], biome)).astype(np.uint8)

    near_sea = _shift_max(is_sea.astype(np.uint8), 3).astype(bool)
    beach = is_land & near_sea & (top <= sea_y + 1) & ~steep & (surf != bk.S_ICE)
    cold = bk.COLD_BIOMES[biome] | (alat >= 60)
    surf[beach] = bk.S_SAND
    biome[beach] = np.where(cold[beach], bk.B["snowy_beach"], bk.B["beach"])

    # --- water biomes and beds
    if cfg.ocean_biomes == "latitude":
        biome[is_sea] = ocean_biome_by_latitude(alat[is_sea])
    else:
        biome[is_sea] = bk.B["ocean"]
    surf[is_sea] = np.where(sea_y - top[is_sea] <= 5, bk.S_SEABED, bk.S_DEEPBED)
    surf[no_depth] = bk.S_SEABED
    lake_cold = is_lake & cold
    biome[is_lake] = bk.B["river"]
    biome[lake_cold] = bk.B["frozen_river"]
    surf[is_lake] = bk.S_LAKEBED

    biome[~vl] = bk.B["ocean"]
    default = no_depth & (biome == bk.B["ocean"])

    b = border
    crop = (slice(b, shape[0] - b), slice(b, shape[1] - b))
    top = np.clip(top, cfg.min_y + 1, cfg.max_y - 1)
    return Columns(top[crop], surf[crop], water[crop], biome[crop], default[crop])


def default_ocean_columns(cfg: WorldConfig, shape) -> Columns:
    return Columns(
        np.full(shape, cfg.default_floor_y(), dtype=np.int32),
        np.full(shape, bk.S_SEABED, dtype=np.uint8),
        np.full(shape, cfg.sea_level, dtype=np.int32),
        np.full(shape, bk.B["ocean"], dtype=np.uint8),
        np.ones(shape, dtype=bool),
    )


def flat_layers(cfg: WorldConfig) -> list[tuple[str, int]]:
    """Flat-generator layers (bottom up) that reproduce the default ocean
    column exactly, used for every chunk the generator leaves empty."""
    floor = cfg.default_floor_y()
    top_blk, sub_blk, sub_depth = bk.SURFACES[bk.S_SEABED]
    sub_top = floor - sub_depth
    col = []
    for y in range(cfg.min_y, cfg.sea_level + 1):
        if y == cfg.min_y:
            b = bk.BEDROCK
        elif y < sub_top:
            b = bk.DEEPSLATE if y < 0 else bk.STONE
        elif y < floor:
            b = sub_blk
        elif y == floor:
            b = top_blk
        else:
            b = bk.WATER
        col.append(bk.BLOCK_NAMES[b])
    layers: list[tuple[str, int]] = []
    for name in col:
        if layers and layers[-1][0] == name:
            layers[-1] = (name, layers[-1][1] + 1)
        else:
            layers.append((name, 1))
    return layers


__all__ = ["Sources", "Columns", "compute_columns", "default_ocean_columns", "flat_layers"]
