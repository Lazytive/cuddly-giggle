"""Region planning and the parallel region writer."""

from __future__ import annotations

import math
import multiprocessing as mp
import os
import sys
import time
from dataclasses import dataclass

import numpy as np

from .anvil import ChunkBuilder, write_region
from .config import WorldConfig
from .projection import Projection
from .terrain import Sources, compute_columns

REGION = 512
BORDER = 4          # extra columns sampled around a region for slopes/beaches
GROUP = 4           # regions per task side (keeps DEM tiles hot in each worker)
PROGRESS_NAME = "alos2mc_progress.txt"


@dataclass
class BBox:
    south: float
    west: float
    north: float
    east: float

    @classmethod
    def parse(cls, text: str) -> "BBox":
        """'south,west,north,east' in degrees; west > east crosses 180."""
        s, w, n, e = (float(v) for v in text.split(","))
        if not (-90 <= s < n <= 90):
            raise ValueError("bbox needs south < north within [-90, 90]")
        return cls(s, w, n, e)

    def contains(self, lon, lat):
        lon = (np.asarray(lon) + 180.0) % 360.0 - 180.0
        w = (self.west + 180.0) % 360.0 - 180.0
        e = (self.east + 180.0) % 360.0 - 180.0
        in_lat = (lat >= self.south) & (lat <= self.north)
        if self.east - self.west >= 360:
            in_lon = np.ones_like(in_lat)
        elif w <= e:
            in_lon = (lon >= w) & (lon <= e)
        else:
            in_lon = (lon >= w) | (lon <= e)
        return in_lat & in_lon


def _covered_rects(proj: Projection):
    rects = [(f.x0, f.x1, f.z0, f.z1) for f in proj.faces]
    rects += [(l.sx0, l.sx1, l.sz0, l.sz1) for l in proj.links]
    if proj.kind == "cube" and proj.margin:
        m = proj.margin
        rects += [(f.x0 - m, f.x1 + m, f.z0 - m, f.z1 + m) for f in proj.faces]
    return rects


class RegionGrid:
    """Which regions contain anything to generate (``keep``) and which lie
    entirely inside a face (``inside``), as 2-D grids over the world."""

    def __init__(self, proj: Projection):
        xmin, xmax, zmin, zmax = proj.bounds()
        self.rx0, self.rz0 = math.floor(xmin / REGION), math.floor(zmin / REGION)
        nx = math.ceil(xmax / REGION) - self.rx0
        nz = math.ceil(zmax / REGION) - self.rz0
        self.keep = np.zeros((nz, nx), dtype=bool)
        self.inside = np.zeros((nz, nx), dtype=bool)
        for x0, x1, z0, z1 in _covered_rects(proj):
            self.keep[self._span(z0, z1, self.rz0, True), self._span(x0, x1, self.rx0, True)] = True
        for f in proj.faces:
            self.inside[self._span(f.z0, f.z1, self.rz0, False), self._span(f.x0, f.x1, self.rx0, False)] = True
        self.inside &= self.keep

    @staticmethod
    def _span(a, b, origin, touching):
        if touching:  # regions overlapping [a, b)
            return slice(math.floor(a / REGION) - origin, math.ceil(b / REGION) - origin)
        return slice(math.ceil(a / REGION) - origin, math.floor(b / REGION) - origin)

    def regions(self, mask=None):
        rz, rx = np.nonzero(self.keep if mask is None else mask)
        return rx + self.rx0, rz + self.rz0

    def contains(self, rx, rz):
        rx = np.asarray(rx) - self.rx0
        rz = np.asarray(rz) - self.rz0
        ok = (rx >= 0) & (rz >= 0) & (rx < self.keep.shape[1]) & (rz < self.keep.shape[0])
        out = np.zeros(rx.shape, dtype=bool)
        out[ok] = self.keep[rz[ok], rx[ok]]
        return out


def _regions_touching(proj, RX, RZ, bbox, samples):
    """Which of the given regions have a sample point inside the bbox."""
    sel = np.zeros(RX.shape, dtype=bool)
    offs = (np.arange(samples) + 0.5) / samples * REGION
    ox, oz = np.meshgrid(offs, offs)
    ox, oz = ox.ravel(), oz.ravel()
    step = 200_000
    for i in range(0, RX.size, step):
        bx = RX[i:i + step, None] * REGION + ox[None, :]
        bz = RZ[i:i + step, None] * REGION + oz[None, :]
        lon, lat, kind = proj.inverse(bx, bz)
        hit = (kind > 0) & bbox.contains(np.nan_to_num(lon), np.nan_to_num(lat, nan=-999))
        sel[i:i + step] = hit.any(axis=1)
    return sel


def plan_regions(proj: Projection, bbox: BBox | None = None, samples: int = 4) -> list[tuple[int, int]]:
    """Regions (rx, rz) that contain anything to generate, optionally only
    those touching a lat/lon box (including seam margins that show it).
    Sorted north to south, west to east."""
    grid = RegionGrid(proj)
    if bbox is None:
        rx, rz = grid.regions()
        return list(zip(rx.tolist(), rz.tolist()))

    # Regions under a grid of points covering the box, finer than a region.
    step = max(0.005, REGION * proj.meters_per_block / 111_320 / 3)
    east = bbox.east if bbox.east >= bbox.west else bbox.east + 360
    if east - bbox.west >= 360:
        east = bbox.west + 360
    lats = np.append(np.arange(bbox.south, bbox.north, step), bbox.north)
    lons = np.append(np.arange(bbox.west, east, step), east)
    direct = []
    for i in range(0, lats.size, 256):
        LO, LA = np.meshgrid(lons, lats[i:i + 256])
        fx, fz = proj.forward(LO.ravel(), LA.ravel())
        d = np.unique(np.stack([np.floor(fx / REGION), np.floor(fz / REGION)], axis=1).astype(np.int64), axis=0)
        direct.append(d)
    direct = np.unique(np.concatenate(direct), axis=0)
    # their neighbours and every seam-margin region are refined by sampling
    ring = (direct[:, None, :] + np.array([(dx, dz) for dx in (-1, 0, 1) for dz in (-1, 0, 1)])[None]).reshape(-1, 2)
    mx, mz = grid.regions(grid.keep & ~grid.inside)
    cand = np.unique(np.concatenate([ring, np.stack([mx, mz], axis=1)]), axis=0)
    cand = cand[grid.contains(cand[:, 0], cand[:, 1])]
    hit = _regions_touching(proj, cand[:, 0], cand[:, 1], bbox, samples)
    direct = direct[grid.contains(direct[:, 0], direct[:, 1])]
    out = {tuple(r) for r in cand[hit].tolist()} | {tuple(r) for r in direct.tolist()}
    return sorted(out, key=lambda r: (r[1], r[0]))


def group_regions(regions):
    groups: dict[tuple[int, int], list] = {}
    for rx, rz in regions:
        groups.setdefault((rx // GROUP, rz // GROUP), []).append((rx, rz))
    return [(k, sorted(v, key=lambda r: (r[1], r[0]))) for k, v in sorted(groups.items(), key=lambda kv: (kv[0][1], kv[0][0]))]


# ------------------------------------------------------------------ worker

_W: dict = {}


def _init_worker(world_dir: str):
    cfg = WorldConfig.load(world_dir)
    _W["cfg"] = cfg
    _W["proj"] = cfg.make_projection()
    _W["src"] = Sources(cfg)
    _W["builder"] = ChunkBuilder(cfg.min_y, cfg.max_y, cfg.data_version)
    _W["dir"] = os.path.join(world_dir, "region")


def region_columns(rx: int, rz: int, proj: Projection, src: Sources, cfg: WorldConfig):
    n = REGION + 2 * BORDER
    xs = rx * REGION - BORDER + np.arange(n) + 0.5
    zs = rz * REGION - BORDER + np.arange(n) + 0.5
    X, Z = np.meshgrid(xs, zs)
    lon, lat, kind = proj.inverse(X, Z)
    valid = kind > 0
    inner = valid[BORDER:-BORDER, BORDER:-BORDER]
    if not inner.any():
        return None, inner
    return compute_columns(lon, lat, valid, src, cfg, BORDER), inner


def _by_chunk(a: np.ndarray) -> np.ndarray:
    """(512, 512) region array -> (32, 32, 16, 16) indexed [lz][lx][z][x]."""
    return a.reshape(32, 16, 32, 16).transpose(0, 2, 1, 3)


def generate_region(rx: int, rz: int, overwrite: bool = False) -> tuple[int, int]:
    """Returns (chunks written, bytes written)."""
    cfg, proj, src, builder = _W["cfg"], _W["proj"], _W["src"], _W["builder"]
    path = os.path.join(_W["dir"], f"r.{rx}.{rz}.mca")
    if not overwrite and os.path.exists(path):
        return 0, 0
    cols, inner = region_columns(rx, rz, proj, src, cfg)
    if cols is None:
        return 0, 0
    skip = cols.default | ~inner
    blocks = _by_chunk
    want = blocks(inner).any(axis=(2, 3))
    if cfg.skip_default_ocean:
        want &= ~blocks(skip).all(axis=(2, 3))
    lz, lx = np.nonzero(want)
    if lz.size == 0:
        return 0, 0
    coords = list(zip((rx * 32 + lx).tolist(), (rz * 32 + lz).tolist()))
    raws = builder.build_many(coords, blocks(cols.top)[lz, lx], blocks(cols.surf)[lz, lx],
                              blocks(cols.water)[lz, lx], blocks(cols.biome)[lz, lx])
    chunks = dict(zip(zip(lx.tolist(), lz.tolist()), raws))
    return len(chunks), write_region(path, chunks)


def _run_group(args):
    key, regions, overwrite = args
    t = time.time()
    nch = nb = 0
    empty = []
    for rx, rz in regions:
        c, b = generate_region(rx, rz, overwrite)
        nch += c
        nb += b
        if c == 0:
            empty.append((rx, rz))
    return key, len(regions), nch, nb, empty, time.time() - t


def run(world_dir: str, regions, workers: int = 0, overwrite: bool = False, log=sys.stderr) -> dict:
    """Generate the given regions.  Regions whose file exists, or that were
    found to be empty before (open ocean; recorded in the progress file),
    are skipped unless ``overwrite``."""
    region_dir = os.path.join(world_dir, "region")
    os.makedirs(region_dir, exist_ok=True)
    prog_path = os.path.join(world_dir, PROGRESS_NAME)
    if not overwrite:
        empty_before = set()
        if os.path.exists(prog_path):
            with open(prog_path) as f:
                empty_before = {tuple(map(int, line.split(","))) for line in f if line.strip()}
        existing = set()
        for name in os.listdir(region_dir):
            parts = name.split(".")
            if len(parts) == 4 and parts[0] == "r" and parts[3] == "mca":
                existing.add((int(parts[1]), int(parts[2])))
        regions = [r for r in regions if r not in empty_before and r not in existing]
    groups = group_regions(regions)
    total_regions = len(regions)
    workers = workers or os.cpu_count() or 1
    stats = {"regions": 0, "chunks": 0, "bytes": 0, "groups": len(groups)}
    t0 = time.time()
    print(f"{total_regions} regions in {len(groups)} groups, {workers} workers", file=log)
    tasks = [(k, rs, overwrite) for k, rs in groups]
    if workers == 1 or len(tasks) <= 1:
        _init_worker(world_dir)
        results = map(_run_group, tasks)
        pool = None
    else:
        ctx = mp.get_context("spawn" if sys.platform == "win32" else "fork")
        pool = ctx.Pool(min(workers, len(tasks)), initializer=_init_worker, initargs=(world_dir,))
        results = pool.imap_unordered(_run_group, tasks)
    try:
        with open(prog_path, "a") as prog:
            for key, nreg, nch, nb, empty, dt in results:
                stats["regions"] += nreg
                stats["chunks"] += nch
                stats["bytes"] += nb
                prog.writelines(f"{rx},{rz}\n" for rx, rz in empty)
                prog.flush()
                el = time.time() - t0
                rate = stats["regions"] / el if el else 0
                eta = (total_regions - stats["regions"]) / rate if rate else 0
                print(f"\r{stats['regions']}/{total_regions} regions  {stats['chunks']} chunks  "
                      f"{stats['bytes'] / 2**30:.2f} GiB  {rate:.2f} regions/s  ETA {_fmt(eta)}   ",
                      end="", file=log, flush=True)
    finally:
        if pool is not None:
            pool.close()
            pool.join()
    print(file=log)
    stats["seconds"] = time.time() - t0
    return stats


def _fmt(s: float) -> str:
    s = int(s)
    d, s = divmod(s, 86400)
    h, s = divmod(s, 3600)
    m, s = divmod(s, 60)
    return (f"{d}d " if d else "") + f"{h:02d}:{m:02d}:{s:02d}"
