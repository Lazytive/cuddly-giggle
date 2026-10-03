import os

import nbtlib
import numpy as np
import pytest
import tifffile

from alos2mc.anvil import decode_section_blocks, read_region
from alos2mc.cli import main
from alos2mc.config import WorldConfig
from alos2mc.generate import BBox, plan_regions, run
from alos2mc.raster import Aw3d30, RasterSet
from alos2mc.terrain import flat_layers

from synth import geotags, terrain, write_tiles


def write_wavy_bathy(path, n_per_deg=4):
    """Global 'GEBCO' grid with land and sea everywhere, so any seam has
    real structure to compare."""
    w, h = 360 * n_per_deg, 180 * n_per_deg
    d = 1.0 / n_per_deg
    lon = -180 + (np.arange(w) + 0.5) * d
    lat = 90 - (np.arange(h) + 0.5) * d
    LO, LA = np.meshgrid(lon, lat)
    z = 1500 * np.sin(np.radians(LO) * 7) * np.cos(np.radians(LA) * 5) + 800 * np.sin(np.radians(LA) * 11) - 300
    tifffile.imwrite(path, z.astype(np.int16), extratags=geotags(-180, 90, d, d))
    return path


@pytest.fixture(scope="module")
def world(tmp_path_factory):
    base = tmp_path_factory.mktemp("gen")
    dem = write_tiles(str(base / "dem"), [(35, 139)], n=600)
    bathy = write_wavy_bathy(str(base / "gebco.tif"))
    w = str(base / "world")
    main(["create", w, "--dem", dem, "--bathymetry", bathy, "--spawn", "35.5,139.5"])
    return w


def column(chunk, x, z):
    out = {}
    for s in chunk["sections"]:
        names, idx = decode_section_blocks(s)
        idx = idx.reshape(16, 16, 16)
        for yy in range(16):
            out[s["Y"] * 16 + yy] = names[idx[yy, z, x]]
    return out


def block_at(world, x, z, cache=None):
    rx, rz = x // 512, z // 512
    path = os.path.join(world, "region", f"r.{rx}.{rz}.mca")
    if cache is None:
        cache = {}
    if path not in cache:
        cache[path] = read_region(path)
    return column(cache[path][((x % 512) // 16, (z % 512) // 16)], x % 16, z % 16)


def surface(col):
    solid = [y for y, n in col.items() if n not in ("minecraft:air", "minecraft:water")]
    water = [y for y, n in col.items() if n == "minecraft:water"]
    return max(solid), (max(water) if water else None)


def test_level_dat_and_pack(world):
    lvl = nbtlib.load(os.path.join(world, "level.dat"))
    data = lvl["Data"]
    assert int(data["initialized"]) == 1
    gen = data["WorldGenSettings"]["dimensions"]["minecraft:overworld"]["generator"]
    assert str(gen["type"]) == "minecraft:flat"
    cfg = WorldConfig.load(world)
    layers = [(str(l["block"]), int(l["height"])) for l in gen["settings"]["layers"]]
    assert layers == flat_layers(cfg)
    assert sum(h for _, h in layers) == cfg.sea_level - cfg.min_y + 1
    assert os.path.exists(os.path.join(world, "datapacks", "alos2mc", "pack.mcmeta"))


def test_mountain_lake_and_sea(world):
    cfg = WorldConfig.load(world)
    proj = cfg.make_projection()
    regions = plan_regions(proj, BBox(35.45, 139.45, 35.55, 139.55))
    stats = run(world, regions, workers=1)
    assert stats["chunks"] > 0
    for lat, lon, what in [(35.5, 139.5, "peak"), (35.75, 139.25, "lake"), (35.1, 139.95, "sea")]:
        x, z = (int(np.floor(v[0])) for v in proj.forward(np.array([lon]), np.array([lat])))
        if (x // 512, z // 512) not in regions:
            run(world, [(x // 512, z // 512)], workers=1)
        top, water = surface(block_at(world, x, z))
        dsm, msk = terrain(np.array(lon), np.array(lat))
        if what == "peak":
            assert abs(top - cfg.land_y(dsm)) <= 1 and water is None
            assert top > cfg.sea_level + 100
        elif what == "lake":
            assert water == cfg.land_y(500) and top == water - cfg.lake_depth
        else:
            assert water == cfg.sea_level and top < cfg.sea_level


def test_resume_skips_existing(world):
    cfg = WorldConfig.load(world)
    regions = plan_regions(cfg.make_projection(), BBox(35.45, 139.45, 35.55, 139.55))
    stats = run(world, regions, workers=1)
    assert stats["regions"] == 0  # all groups recorded as done


def test_seam_margin_is_an_exact_copy(world):
    """Terrain beyond a seam equals, block for block, the terrain on the
    other side after the link transform."""
    cfg = WorldConfig.load(world)
    proj = cfg.make_projection()
    link = next(l for l in proj.links if l.face == "west" and l.edge == "top")
    # a margin region in the middle of the edge, and the one it copies
    x0 = -250_000 // 512 * 512
    src_region = (x0 // 512, link.sz0 // 512)
    tx, tz = link.apply(x0 + 0.5, link.sz0 + 0.5)
    tx2, tz2 = link.apply(x0 + 511.5, link.sz1 - 0.5)
    dst_regions = {(int(np.floor(a / 512)), int(np.floor(b / 512))) for a in (tx, tx2) for b in (tz, tz2)}
    run(world, [src_region, *dst_regions], workers=1, overwrite=True)
    rng = np.random.default_rng(0)
    checked = 0
    cache = {}
    for _ in range(200):
        x = x0 + int(rng.integers(0, 512))
        z = link.sz0 + int(rng.integers(4, 508))  # stay away from the margin's outer edge
        ax, az = link.apply(x + 0.5, z + 0.5)
        a = block_at(world, x, z, cache)
        b = block_at(world, int(np.floor(ax)), int(np.floor(az)), cache)
        assert a == b, (x, z)
        checked += 1
    assert checked == 200


def test_zip_and_folder_give_same_samples(tmp_path):
    d1 = write_tiles(str(tmp_path / "a"), [(35, 139)], n=300)
    d2 = write_tiles(str(tmp_path / "b"), [(35, 139)], n=300, zipped=True)
    a, b = Aw3d30(d1), Aw3d30(d2)
    assert a.tiles() == b.tiles() == [(35, 139)]
    lon = 139 + np.random.default_rng(0).random(1000)
    lat = 35 + np.random.default_rng(1).random(1000)
    ea, ca = a.sample(lon, lat)
    eb, cb = b.sample(lon, lat)
    assert np.array_equal(ea, eb) and np.array_equal(ca, cb)
    # and they match the analytic terrain to within interpolation error
    dsm, msk = terrain(lon, lat)
    assert np.abs(ea - dsm)[msk == 0].max() < 60
    assert ((ca == 1) == (msk == 3)).mean() > 0.98


def test_raster_windowed_read(tmp_path):
    """Big rasters are read by segments; results match a full read."""
    p = str(tmp_path / "big.tif")
    arr = (np.arange(4000 * 1000, dtype=np.int32).reshape(1000, 4000) % 30000).astype(np.int16)
    tifffile.imwrite(p, arr, extratags=geotags(-180, 90, 0.09, 0.18), compression="deflate", rowsperstrip=7)
    rs = RasterSet(p)
    rf = rs.files[0]
    rf.npixels = 10**9  # force the windowed path
    win = rf.window(123, 456, 1000, 3999, rs._cache)
    assert np.array_equal(win, arr[123:456, 1000:3999])
