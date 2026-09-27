"""Synthetic AW3D30-style tiles for tests (real tiles cannot be bundled)."""

import os

import numpy as np
import tifffile

from alos2mc.raster import tile_name


def geotags(west, north, dx, dy, nodata=None):
    tags = [
        (33550, "d", 3, (dx, dy, 0.0), True),
        (33922, "d", 6, (0.0, 0.0, 0.0, west, north, 0.0), True),
        (34735, "H", 16, (1, 1, 0, 3, 1024, 0, 1, 2, 1025, 0, 1, 1, 2048, 0, 1, 4326), True),
    ]
    if nodata is not None:
        tags.append((42113, "s", 0, str(nodata), True))
    return tags


def terrain(lon, lat):
    """A 3000 m mountain at (139.5E, 35.5N) with sea to the south-east and
    a lake at 500 m on its north-west flank."""
    r2 = (lon - 139.5) ** 2 + (lat - 35.5) ** 2
    e = 3000 * np.exp(-r2 / 0.02) + 400 * np.exp(-((lon - 139.2) ** 2 + (lat - 35.8) ** 2) / 0.05) + 60
    e -= 600 * np.clip((lon - 139.5) + (35.5 - lat) - 0.3, 0, None)
    lake = (lon - 139.25) ** 2 + (lat - 35.75) ** 2 < 0.004
    sea = e <= 0
    dsm = np.where(sea, 0, np.where(lake, 500, e))
    msk = np.where(sea, 3, np.where(lake, 2, 0)).astype(np.uint8)
    return dsm, msk


def write_tiles(root, tiles, n=600, zipped=False):
    """Write AW3D30-named DSM/MSK tiles for (lat, lon) SW corners."""
    os.makedirs(root, exist_ok=True)
    written = []
    for lat, lon in tiles:
        d = 1.0 / n
        cols = lon + (np.arange(n) + 0.5) * d
        rows = lat + 1 - (np.arange(n) + 0.5) * d
        LO, LA = np.meshgrid(cols, rows)
        dsm, msk = terrain(LO, LA)
        name = tile_name(lat, lon)
        sub = os.path.join(root, name)
        os.makedirs(sub, exist_ok=True)
        p1 = os.path.join(sub, f"ALPSMLC30_{name}_DSM.tif")
        p2 = os.path.join(sub, f"ALPSMLC30_{name}_MSK.tif")
        tifffile.imwrite(p1, np.rint(dsm).astype(np.int16), extratags=geotags(lon, lat + 1, d, d, -9999),
                         compression="deflate")
        tifffile.imwrite(p2, msk, extratags=geotags(lon, lat + 1, d, d))
        written += [p1, p2]
    if zipped:
        import zipfile
        zp = os.path.join(root, "bundle.zip")
        with zipfile.ZipFile(zp, "w") as z:
            for p in written:
                z.write(p, os.path.relpath(p, root))
                os.remove(p)
    return root


def write_global_bathy(path, n_per_deg=4):
    """A coarse global 'GEBCO' grid: -4000 m ocean, land elsewhere from
    terrain(), stored as one GeoTIFF."""
    w, h = 360 * n_per_deg, 180 * n_per_deg
    d = 1.0 / n_per_deg
    lon = -180 + (np.arange(w) + 0.5) * d
    lat = 90 - (np.arange(h) + 0.5) * d
    LO, LA = np.meshgrid(lon, lat)
    z = -4000 + 0 * LO
    z = np.where(np.abs(LA) > 80, 1500, z)  # polar plateaus
    tifffile.imwrite(path, z.astype(np.int16), extratags=geotags(-180, 90, d, d))
    return path
