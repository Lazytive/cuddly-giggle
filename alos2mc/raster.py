"""GeoTIFF readers for the elevation / bathymetry / climate inputs.

* :class:`Aw3d30` indexes JAXA ALOS World 3D-30m (AW3D30) 1x1 degree tiles
  (``..._N035E139_DSM.tif`` + ``..._MSK.tif``), either extracted or still
  inside the 5x5 degree zip files JAXA distributes.
* :class:`RasterSet` samples any set of lon/lat GeoTIFFs (GEBCO grids,
  a Koppen-Geiger climate map, other DEM tiles...).  Large files are read
  window-by-window so worker processes stay small.

Only geographic (EPSG:4326-style lon/lat) rasters are supported.
"""

from __future__ import annotations

import io
import math
import os
import re
import threading
import zipfile
from collections import OrderedDict
from dataclasses import dataclass

import numpy as np
import tifffile

# AW3D30 MSK: the low two bits classify the pixel (0 valid, 1 cloud/snow
# (filled from another DEM), 2 land water, 3 sea); higher bits record which
# DEM was used to fill voids.
MSK_CLASS_BITS = 0b11
MSK_LAND_WATER = 2
MSK_SEA = 3

CLS_LAND, CLS_SEA, CLS_LAKE, CLS_UNKNOWN = 0, 1, 2, 255

_TILE_RE = re.compile(r"([NS])(\d{2,3})([EW])(\d{3})(?:_AVE)?_(DSM|MSK)\.tiff?$", re.IGNORECASE)


@dataclass
class GeoRef:
    width: int
    height: int
    x0: float   # longitude of the centre of pixel column 0
    y0: float   # latitude of the centre of pixel row 0
    dx: float   # degrees per column (> 0)
    dy: float   # degrees per row (> 0, rows go south)
    nodata: float | None

    @property
    def bounds(self):
        """(west, south, east, north) of the pixel *areas*."""
        return (self.x0 - self.dx / 2, self.y0 - (self.height - 0.5) * self.dy,
                self.x0 + (self.width - 0.5) * self.dx, self.y0 + self.dy / 2)


def read_georef(tif: tifffile.TiffFile) -> GeoRef:
    page = tif.pages[0]
    tags = page.tags
    h, w = page.shape[:2]
    raster_type = 1  # PixelIsArea
    gk = tags.get("GeoKeyDirectoryTag")
    if gk is not None:
        v = list(gk.value)
        for i in range(4, len(v) - 3, 4):
            if v[i] == 1025 and v[i + 1] == 0:
                raster_type = v[i + 3]
    if "ModelTransformationTag" in tags and "ModelPixelScaleTag" not in tags:
        m = tags["ModelTransformationTag"].value
        sx, sy, X, Y = m[0], -m[5], m[3], m[7]
        if m[1] or m[4]:
            raise ValueError("rotated rasters are not supported")
    else:
        sx, sy = tags["ModelPixelScaleTag"].value[:2]
        tp = tags["ModelTiepointTag"].value
        i, j, X, Y = tp[0], tp[1], tp[3], tp[4]
        X -= i * sx
        Y += j * sy
    if raster_type == 2:  # PixelIsPoint: tie point is the pixel centre
        x0, y0 = X, Y
    else:
        x0, y0 = X + sx / 2, Y - sy / 2
    nodata = None
    nd = tags.get("GDAL_NODATA")
    if nd is not None:
        try:
            nodata = float(str(nd.value).strip("\x00 "))
        except ValueError:
            pass
    return GeoRef(w, h, float(x0), float(y0), float(sx), float(sy), nodata)


class Source:
    """A GeoTIFF on disk or inside a zip file."""

    def __init__(self, path: str, member: str | None = None):
        self.path = path
        self.member = member

    def __repr__(self):
        return f"{self.path}!{self.member}" if self.member else self.path

    def open(self) -> tifffile.TiffFile:
        if self.member is None:
            return tifffile.TiffFile(self.path)
        with zipfile.ZipFile(self.path) as z:
            return tifffile.TiffFile(io.BytesIO(z.read(self.member)))

    def read(self) -> tuple[np.ndarray, GeoRef]:
        with self.open() as tif:
            return tif.pages[0].asarray(), read_georef(tif)


def _bilinear(arr, fr, fc, nodata):
    h, w = arr.shape[:2]
    fr = np.clip(fr, 0, h - 1)
    fc = np.clip(fc, 0, w - 1)
    r0 = np.minimum(np.floor(fr).astype(np.int64), h - 2 if h > 1 else 0)
    c0 = np.minimum(np.floor(fc).astype(np.int64), w - 2 if w > 1 else 0)
    r1 = np.minimum(r0 + 1, h - 1)
    c1 = np.minimum(c0 + 1, w - 1)
    tr = (fr - r0).astype(np.float32)
    tc = (fc - c0).astype(np.float32)
    v00 = arr[r0, c0].astype(np.float32)
    v01 = arr[r0, c1].astype(np.float32)
    v10 = arr[r1, c0].astype(np.float32)
    v11 = arr[r1, c1].astype(np.float32)
    out = (v00 * (1 - tc) + v01 * tc) * (1 - tr) + (v10 * (1 - tc) + v11 * tc) * tr
    if nodata is not None:
        bad = (v00 == nodata) | (v01 == nodata) | (v10 == nodata) | (v11 == nodata)
        if bad.any():
            # fall back to nearest neighbour next to holes
            near = arr[np.rint(fr).astype(np.int64), np.rint(fc).astype(np.int64)].astype(np.float32)
            out = np.where(bad, near, out)
            out[out == nodata] = np.nan
    return out


def _nearest(arr, fr, fc):
    h, w = arr.shape[:2]
    r = np.clip(np.rint(fr).astype(np.int64), 0, h - 1)
    c = np.clip(np.rint(fc).astype(np.int64), 0, w - 1)
    return arr[r, c]


class _LRU:
    def __init__(self, max_bytes: int):
        self.max_bytes = max_bytes
        self.items: OrderedDict = OrderedDict()
        self.size = 0
        self.lock = threading.Lock()

    def get(self, key, loader):
        with self.lock:
            if key in self.items:
                self.items.move_to_end(key)
                return self.items[key]
        value = loader()
        nbytes = sum(getattr(v, "nbytes", 0) for v in (value if isinstance(value, tuple) else (value,)))
        with self.lock:
            self.items[key] = value
            self.size += nbytes
            while self.size > self.max_bytes and len(self.items) > 1:
                _, old = self.items.popitem(last=False)
                self.size -= sum(getattr(v, "nbytes", 0) for v in (old if isinstance(old, tuple) else (old,)))
        return value


def _norm_lon(lon):
    return (lon + 180.0) % 360.0 - 180.0


class Aw3d30:
    """Index of AW3D30 tiles found under the given files/directories."""

    def __init__(self, roots, cache_mb: int = 600):
        self.dsm: dict[tuple[int, int], Source] = {}
        self.msk: dict[tuple[int, int], Source] = {}
        for root in ([roots] if isinstance(roots, (str, os.PathLike)) else roots):
            self._scan(os.fspath(root))
        self._cache = _LRU(cache_mb * 1024 * 1024)

    def _add(self, name: str, src: Source):
        m = _TILE_RE.search(os.path.basename(name))
        if not m:
            return
        lat = int(m.group(2)) * (1 if m.group(1).upper() == "N" else -1)
        lon = int(m.group(4)) * (1 if m.group(3).upper() == "E" else -1)
        (self.dsm if m.group(5).upper() == "DSM" else self.msk)[(lat, lon)] = src

    def _scan(self, root: str):
        paths = []
        if os.path.isdir(root):
            for d, _, files in os.walk(root):
                paths.extend(os.path.join(d, f) for f in files)
        else:
            paths.append(root)
        for p in sorted(paths):
            if p.lower().endswith(".zip"):
                try:
                    with zipfile.ZipFile(p) as z:
                        for n in z.namelist():
                            self._add(n, Source(p, n))
                except zipfile.BadZipFile:
                    continue
            else:
                self._add(p, Source(p))

    def __len__(self):
        return len(self.dsm)

    def tiles(self):
        return sorted(self.dsm)

    def _load(self, key):
        def loader():
            dsm, geo = self.dsm[key].read()
            msk = None
            if key in self.msk:
                msk, mgeo = self.msk[key].read()
                if msk.shape != dsm.shape:
                    msk = None
            return dsm, msk, geo
        return self._cache.get(key, loader)

    def sample(self, lon, lat):
        """-> (elevation metres float32 with NaN where missing, class uint8)."""
        lon = _norm_lon(np.asarray(lon, dtype=np.float64))
        lat = np.asarray(lat, dtype=np.float64)
        elev = np.full(lon.shape, np.nan, dtype=np.float32)
        cls = np.full(lon.shape, CLS_UNKNOWN, dtype=np.uint8)
        ti = np.floor(lat).astype(np.int64)
        tj = np.floor(lon).astype(np.int64)
        keys = (ti + 90) * 360 + (tj + 180)
        for k in np.unique(keys):
            key = (int(k // 360) - 90, int(k % 360) - 180)
            if key not in self.dsm:
                continue
            m = keys == k
            dsm, msk, g = self._load(key)
            fr = (g.y0 - lat[m]) / g.dy
            fc = (lon[m] - g.x0) / g.dx
            nodata = g.nodata if g.nodata is not None else -9999
            e = _bilinear(dsm, fr, fc, nodata)
            e[e < -1000] = np.nan  # voids
            elev[m] = e
            if msk is not None:
                c = _nearest(msk, fr, fc) & MSK_CLASS_BITS
                cls[m] = np.where(c == MSK_SEA, CLS_SEA, np.where(c == MSK_LAND_WATER, CLS_LAKE, CLS_LAND))
            else:
                cls[m] = np.where(e <= 0, CLS_SEA, CLS_LAND)
        return elev, cls


class _RasterFile:
    def __init__(self, src: Source):
        self.src = src
        with src.open() as tif:
            page = tif.pages[0]
            self.geo = read_georef(tif)
            self.dtype = page.dtype
            self.npixels = self.geo.width * self.geo.height
        self._mm = None
        self._tif = None

    @property
    def bounds(self):
        return self.geo.bounds

    def window(self, r0, r1, c0, c1, cache: _LRU) -> np.ndarray:
        """Pixels [r0:r1, c0:c1].  Small rasters are loaded whole (cached);
        big ones are memory-mapped when possible, else read segment by
        segment."""
        if self.npixels <= 64_000_000 or self.src.member is not None:
            arr = cache.get(("full", repr(self.src)), lambda: self.src.read()[0])
            return arr[r0:r1, c0:c1]
        if self._mm is None:
            try:
                self._mm = tifffile.memmap(self.src.path, mode="r")
            except Exception:
                self._mm = False
        if self._mm is not False:
            return np.asarray(self._mm[r0:r1, c0:c1])
        return self._segments(r0, r1, c0, c1, cache)

    def _segments(self, r0, r1, c0, c1, cache):
        if self._tif is None:
            self._tif = tifffile.TiffFile(self.src.path)
        page = self._tif.pages[0]
        if page.is_tiled:
            th, tw = page.tilelength, page.tilewidth
        else:
            th, tw = page.rowsperstrip, page.imagewidth
        nx = math.ceil(page.imagewidth / tw)
        out = np.empty((r1 - r0, c1 - c0), dtype=page.dtype)
        fh = self._tif.filehandle
        for iy in range(r0 // th, (r1 - 1) // th + 1):
            for ix in range(c0 // tw, (c1 - 1) // tw + 1):
                idx = iy * nx + ix

                def load(idx=idx):
                    fh.seek(page.dataoffsets[idx])
                    data = fh.read(page.databytecounts[idx])
                    seg = page.decode(data, idx)[0]
                    return np.asarray(seg).reshape(seg.shape[-3], seg.shape[-2])

                seg = cache.get(("seg", repr(self.src), idx), load)
                sy, sx = iy * th, ix * tw
                ya, yb = max(r0, sy), min(r1, sy + seg.shape[0])
                xa, xb = max(c0, sx), min(c1, sx + seg.shape[1])
                out[ya - r0:yb - r0, xa - c0:xb - c0] = seg[ya - sy:yb - sy, xa - sx:xb - sx]
        return out


class RasterSet:
    """Samples a collection of lon/lat GeoTIFFs (first match wins)."""

    def __init__(self, roots, cache_mb: int = 400):
        self.files: list[_RasterFile] = []
        for root in ([roots] if isinstance(roots, (str, os.PathLike)) else roots):
            root = os.fspath(root)
            paths = []
            if os.path.isdir(root):
                for d, _, fs in os.walk(root):
                    paths.extend(os.path.join(d, f) for f in fs)
            else:
                paths.append(root)
            for p in sorted(paths):
                if p.lower().endswith((".tif", ".tiff")):
                    self.files.append(_RasterFile(Source(p)))
        if not self.files:
            raise FileNotFoundError(f"no GeoTIFF files found in {roots}")
        self._cache = _LRU(cache_mb * 1024 * 1024)

    def sample(self, lon, lat, method: str = "bilinear") -> np.ndarray:
        lon = _norm_lon(np.asarray(lon, dtype=np.float64))
        lat = np.asarray(lat, dtype=np.float64)
        out = np.full(lon.shape, np.nan, dtype=np.float32)
        todo = np.ones(lon.shape, dtype=bool)
        for rf in self.files:
            w, s, e, n = rf.bounds
            m = todo & (lon >= w) & (lon <= e) & (lat >= s) & (lat <= n)
            if not m.any():
                continue
            g = rf.geo
            fr = np.clip((g.y0 - lat[m]) / g.dy, 0, g.height - 1)
            fc = np.clip((lon[m] - g.x0) / g.dx, 0, g.width - 1)
            r0 = int(np.floor(fr.min()))
            r1 = min(g.height, int(np.floor(fr.max())) + 2)
            c0 = int(np.floor(fc.min()))
            c1 = min(g.width, int(np.floor(fc.max())) + 2)
            win = rf.window(r0, r1, c0, c1, self._cache)
            if method == "nearest":
                v = _nearest(win, fr - r0, fc - c0).astype(np.float32)
                if g.nodata is not None:
                    v[v == g.nodata] = np.nan
            else:
                v = _bilinear(win, fr - r0, fc - c0, g.nodata)
            out[m] = v
            todo &= ~m
            if not todo.any():
                break
        return out


def tile_name(lat: int, lon: int) -> str:
    return f"{'N' if lat >= 0 else 'S'}{abs(lat):03d}{'E' if lon >= 0 else 'W'}{abs(lon):03d}"

