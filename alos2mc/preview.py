"""Top-down PNG renders, either of written region files or straight from
the source data (to check an area before generating it)."""

from __future__ import annotations

import os
import struct
import zlib

import numpy as np

from .anvil import decode_section_blocks, read_region
from .blocks import BLOCK_COLORS, BLOCK_NAMES, SURF_TOP, WATER

_NAME_TO_ID = {n: i for i, n in enumerate(BLOCK_NAMES)}


def write_png(path: str, rgb: np.ndarray) -> None:
    h, w, _ = rgb.shape
    raw = b"".join(b"\x00" + rgb[y].astype(np.uint8).tobytes() for y in range(h))

    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)

    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n")
        f.write(chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)))
        f.write(chunk(b"IDAT", zlib.compress(raw, 6)))
        f.write(chunk(b"IEND", b""))


def shade(block: np.ndarray, height: np.ndarray, water_depth: np.ndarray | None = None) -> np.ndarray:
    rgb = BLOCK_COLORS[block].astype(np.float32)
    dz = np.zeros(height.shape, dtype=np.float32)
    dz[1:, 1:] = (height[1:, 1:] - height[:-1, :-1]).astype(np.float32)
    rgb *= np.clip(1.0 + dz[..., None] * 0.08, 0.55, 1.35)
    if water_depth is not None:
        f = np.clip(water_depth / 40.0, 0, 0.7)[..., None]
        rgb = rgb * (1 - f) + np.array([20, 40, 110], dtype=np.float32) * f
    return np.clip(rgb, 0, 255).astype(np.uint8)


def chunk_top(chunk: dict):
    """-> (top block id [16,16], top y, water depth) for a chunk NBT."""
    secs = sorted(chunk["sections"], key=lambda s: -s["Y"])
    blk = np.full((16, 16), -1, dtype=np.int64)
    ys = np.zeros((16, 16), dtype=np.int64)
    wtop = np.full((16, 16), -10_000, dtype=np.int64)
    for s in secs:
        names, idx = decode_section_blocks(s)
        ids = np.array([_NAME_TO_ID.get(n, 3) for n in names])[idx].reshape(16, 16, 16)
        for yy in range(15, -1, -1):
            layer = ids[yy]
            y = s["Y"] * 16 + yy
            wnew = (layer == WATER) & (wtop == -10_000) & (blk < 0)
            wtop[wnew] = y
            solid = (layer != 0) & (layer != WATER) & (blk < 0)
            blk[solid] = layer[solid]
            ys[solid] = y
        if (blk >= 0).all():
            break
    depth = np.where(wtop > ys, wtop - ys, 0)
    return np.maximum(blk, 0), ys, depth


def render_world(world_dir: str, x0: int, z0: int, x1: int, z1: int, step: int = 1) -> np.ndarray:
    w, h = -(-(x1 - x0) // step), -(-(z1 - z0) // step)
    blk = np.zeros((h, w), dtype=np.int64)
    hy = np.zeros((h, w), dtype=np.int64)
    dep = np.zeros((h, w), dtype=np.int64)
    for rz in range(z0 // 512, (z1 - 1) // 512 + 1):
        for rx in range(x0 // 512, (x1 - 1) // 512 + 1):
            p = os.path.join(world_dir, "region", f"r.{rx}.{rz}.mca")
            if not os.path.exists(p):
                continue
            for (lx, lz), ch in read_region(p).items():
                cx, cz = rx * 512 + lx * 16, rz * 512 + lz * 16
                xs = np.arange(cx, cx + 16)
                zs = np.arange(cz, cz + 16)
                mx = (xs >= x0) & (xs < x1) & ((xs - x0) % step == 0)
                mz = (zs >= z0) & (zs < z1) & ((zs - z0) % step == 0)
                if not mx.any() or not mz.any():
                    continue
                b, y, d = chunk_top(ch)
                ii = (zs[mz] - z0) // step
                jj = (xs[mx] - x0) // step
                sub = np.ix_(mz, mx)
                blk[np.ix_(ii, jj)] = b[sub]
                hy[np.ix_(ii, jj)] = y[sub]
                dep[np.ix_(ii, jj)] = d[sub]
    return shade(blk, hy, dep)


def render_sources(proj, src, cfg, x0, z0, x1, z1, step: int = 1) -> np.ndarray:
    from .terrain import compute_columns
    xs = x0 + (np.arange(-(-(x1 - x0) // step) + 2) - 1) * step + 0.5
    zs = z0 + (np.arange(-(-(z1 - z0) // step) + 2) - 1) * step + 0.5
    X, Z = np.meshgrid(xs, zs)
    lon, lat, kind = proj.inverse(X, Z)
    cols = compute_columns(lon, lat, kind > 0, src, cfg, 1)
    top_block = SURF_TOP[cols.surf]
    depth = np.maximum(cols.water - cols.top, 0)
    img = shade(top_block, cols.top, depth)
    img[kind[1:-1, 1:-1] == 0] = (0, 0, 0)
    return img
