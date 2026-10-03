"""Builds test fixtures for the Java core and the in-game self-test.

Uses the Python implementation in the repository root (alos2mc/) as the
reference.  Output (default mod/build/testdata):

  projection_<name>.txt   links, inverse and forward samples
  data/aw3d30/...         synthetic AW3D30 tiles (one folder, one zip)
  data/gebco.tif          tiled + LZW int16 global grid
  data/koppen.tif         striped + deflate/predictor uint8 global grid
  data/float.tif          float32 tile with floating-point predictor
  rasters.txt             expected samples from those files
"""

import os
import sys
import zipfile

import numpy as np
import tifffile

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
sys.path.insert(0, ROOT)
sys.path.insert(0, os.path.join(ROOT, "tests"))

from alos2mc.projection import CubeProjection  # noqa: E402
from alos2mc.raster import Aw3d30, RasterSet  # noqa: E402
from synth import geotags, write_tiles  # noqa: E402


def projection_fixture(out, name, lat0, lon0):
    p = CubeProjection(30.0, lon0, 512, lat0)
    rng = np.random.default_rng(7)
    xmin, xmax, zmin, zmax = p.bounds()
    with open(os.path.join(out, f"projection_{name}.txt"), "w") as f:
        f.write(f"config {lat0} {lon0} {p.size}\n")
        for l in p.links:
            (a, b), (c, d) = l.M
            f.write(f"link {l.face} {l.edge} {l.dest} {l.dest_edge} {l.sx0} {l.sx1} {l.sz0} {l.sz1} "
                    f"{a} {b} {c} {d} {l.t[0]} {l.t[1]} {l.yaw}\n")
        # random points everywhere plus points hugging every seam and vertex
        xs = list(rng.uniform(xmin - 100, xmax + 100, 3000))
        zs = list(rng.uniform(zmin - 100, zmax + 100, 3000))
        for l in p.links:
            for _ in range(60):
                xs.append(rng.uniform(l.sx0 - 3, l.sx1 + 3))
                zs.append(rng.uniform(l.sz0 - 3, l.sz1 + 3))
        for fc in p.faces:
            for cx in (fc.x0, fc.x1):
                for cz in (fc.z0, fc.z1):
                    for _ in range(20):
                        xs.append(cx + rng.uniform(-600, 600))
                        zs.append(cz + rng.uniform(-600, 600))
        xs, zs = np.array(xs), np.array(zs)
        lon, lat, kind = p.inverse(xs, zs)
        for x, z, lo, la, k in zip(xs, zs, lon, lat, kind):
            f.write(f"inv {float(x)!r} {float(z)!r} {int(k)} {float(lo)!r} {float(la)!r}\n")
        v = rng.normal(size=(2000, 3))
        v /= np.linalg.norm(v, axis=1, keepdims=True)
        lo = np.degrees(np.arctan2(v[:, 1], v[:, 0]))
        la = np.degrees(np.arcsin(v[:, 2]))
        fx, fz = p.forward(lo, la)
        for a, b, x, z in zip(lo, la, fx, fz):
            f.write(f"fwd {float(a)!r} {float(b)!r} {float(x)!r} {float(z)!r}\n")


def raster_fixtures(out):
    data = os.path.join(out, "data")
    dem = os.path.join(data, "aw3d30")
    write_tiles(os.path.join(dem, "plain"), [(35, 139)], n=600)
    write_tiles(os.path.join(dem, "zipped"), [(35, 138)], n=600, zipped=True)

    # a global "GEBCO" grid: tiled, LZW, int16, with land around the test tiles
    n_per_deg = 8
    w, h = 360 * n_per_deg, 180 * n_per_deg
    d = 1.0 / n_per_deg
    lon = -180 + (np.arange(w) + 0.5) * d
    lat = 90 - (np.arange(h) + 0.5) * d
    LO, LA = np.meshgrid(lon, lat)
    z = (1500 * np.sin(np.radians(LO) * 7) * np.cos(np.radians(LA) * 5)
         + 800 * np.sin(np.radians(LA) * 11) - 500).astype(np.int16)
    tifffile.imwrite(os.path.join(data, "gebco.tif"), z, tile=(256, 256), compression="lzw",
                     extratags=geotags(-180, 90, d, d))
    # a Koppen-like class grid: striped, deflate + horizontal predictor
    k = ((np.floor((LA + 90) / 6) + np.floor((LO + 180) / 12)) % 30 + 1).astype(np.uint8)
    os.makedirs(os.path.join(data, "climate"), exist_ok=True)
    tifffile.imwrite(os.path.join(data, "climate", "koppen.tif"), k, rowsperstrip=37, compression="deflate",
                     predictor=True, extratags=geotags(-180, 90, d, d))
    # float32 with the floating point predictor (as in Copernicus DEM COGs)
    fl = (np.sin(LO[:360, :720] / 7.0) * 100 + LA[:360, :720]).astype(np.float32)
    tifffile.imwrite(os.path.join(data, "float.tif"), fl, tile=(128, 128), compression="deflate",
                     predictor=3, extratags=geotags(-180, 90, d, d))

    rng = np.random.default_rng(3)
    a3 = Aw3d30(dem)
    g = RasterSet(os.path.join(data, "gebco.tif"))
    kc = RasterSet(os.path.join(data, "climate"))
    fr = RasterSet(os.path.join(data, "float.tif"))
    with open(os.path.join(out, "rasters.txt"), "w") as f:
        lo = rng.uniform(138.0, 140.0, 3000)
        la = rng.uniform(35.0, 36.0, 3000)
        e, c = a3.sample(lo, la)
        for x, y, ee, cc in zip(lo, la, e, c):
            f.write(f"aw3d30 {float(x)!r} {float(y)!r} {float(ee)!r} {int(cc)}\n")
        lo = rng.uniform(-180, 180, 3000)
        la = rng.uniform(-89.9, 89.9, 3000)
        for x, y, v in zip(lo, la, g.sample(lo, la)):
            f.write(f"gebco {float(x)!r} {float(y)!r} {float(v)!r}\n")
        for x, y, v in zip(lo, la, kc.sample(lo, la, method="nearest")):
            f.write(f"koppen {float(x)!r} {float(y)!r} {float(v)!r}\n")
        lo2 = rng.uniform(-179.9, -90.1, 2000)
        la2 = rng.uniform(45.1, 89.9, 2000)
        for x, y, v in zip(lo2, la2, fr.sample(lo2, la2, method="nearest")):
            f.write(f"float {float(x)!r} {float(y)!r} {float(v)!r}\n")


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "mod", "build", "testdata")
    os.makedirs(out, exist_ok=True)
    projection_fixture(out, "polar", 0.0, 24.0)
    projection_fixture(out, "tilted", 36.0, 138.0)
    raster_fixtures(out)
    print("test data written to", out)


if __name__ == "__main__":
    main()
