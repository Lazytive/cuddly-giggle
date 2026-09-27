"""Command line interface: ``python -m alos2mc <command> ...``"""

from __future__ import annotations

import argparse
import json
import math
import os
import sys

import numpy as np

from .config import WorldConfig
from .generate import REGION, BBox, plan_regions, run
from .raster import Aw3d30, tile_name
from .terrain import Sources, compute_columns
from .world import write_datapack, write_level_dat


def _world(path: str) -> WorldConfig:
    if not os.path.exists(os.path.join(path, "alos2mc.json")):
        sys.exit(f"{path} is not an alos2mc world (run `create` first)")
    return WorldConfig.load(path)


def _surface_y(cfg, proj, src, x: float, z: float) -> int:
    xs = np.floor(x) + np.arange(-2, 3) + 0.5
    zs = np.floor(z) + np.arange(-2, 3) + 0.5
    X, Z = np.meshgrid(xs, zs)
    lon, lat, kind = proj.inverse(X, Z)
    cols = compute_columns(lon, lat, kind > 0, src, cfg, 2)
    return int(max(cols.top[0, 0], cols.water[0, 0])) + 1


def cmd_create(a):
    os.makedirs(a.world, exist_ok=True)
    if os.path.exists(os.path.join(a.world, "alos2mc.json")) and not a.force:
        sys.exit(f"{a.world} already has alos2mc.json; use --force to rewrite level.dat/datapack")
    center = [float(v) for v in a.center.split(",")] if a.center else [0.0, 24.0]
    cfg = WorldConfig(
        level_name=a.name, projection=a.projection, meters_per_block=a.scale,
        vertical_meters_per_block=a.vertical_scale or a.scale, lat0=center[0], lon0=center[1], margin=a.margin,
        height_mode=a.height_mode, sea_level=a.sea_level, data_version=a.data_version,
        game_mode=0 if a.survival else 1, spawn=[float(v) for v in a.spawn.split(",")] if a.spawn else None,
        dem=[os.path.abspath(p) for p in a.dem], bathymetry=[os.path.abspath(p) for p in a.bathymetry],
        climate=[os.path.abspath(p) for p in a.climate], skip_default_ocean=not a.write_all_ocean,
    )
    cfg.save(a.world)
    proj = cfg.make_projection()
    lat, lon = cfg.spawn if cfg.spawn else (cfg.lat0, cfg.lon0)
    fx, fz = proj.forward(np.array([lon]), np.array([lat]))
    sx, sz = int(math.floor(fx[0])), int(math.floor(fz[0]))
    try:
        sy = _surface_y(cfg, proj, Sources(cfg), sx, sz)
    except Exception as e:  # missing data should not block creating the world
        print(f"warning: could not sample spawn height: {e}", file=sys.stderr)
        sy = cfg.sea_level + 1
    write_level_dat(a.world, cfg, (sx, sy, sz))
    pack = write_datapack(a.world, cfg, proj)
    xmin, xmax, zmin, zmax = proj.bounds()
    print(f"created {a.world}")
    print(f"  projection {proj.kind}, 1 block = {proj.meters_per_block:.3f} m, "
          f"vertical 1 block = {cfg.vertical_meters_per_block:g} m")
    print(f"  world x {xmin}..{xmax}, z {zmin}..{zmax}; y {cfg.min_y}..{cfg.max_y}, sea level y={cfg.sea_level}")
    print(f"  spawn {sx} {sy} {sz} (lat {lat}, lon {lon})")
    print(f"  datapack {pack} ({len(proj.links)} seam links)")


def _regions(a, proj):
    bbox = BBox.parse(a.bbox) if a.bbox else None
    return plan_regions(proj, bbox)


def cmd_plan(a):
    cfg = _world(a.world)
    proj = cfg.make_projection()
    regions = _regions(a, proj)
    print(f"{len(regions)} regions ({len(regions) * 1024:,} chunk slots, "
          f"{len(regions) * REGION * REGION / 1e9:.2f} billion columns)")
    # which 1x1 degree tiles do these regions need?
    need = set()
    offs = (np.arange(4) + 0.5) * (REGION / 4)
    ox, oz = np.meshgrid(offs, offs)
    rr = np.array(regions, dtype=np.int64).reshape(-1, 2)
    for i in range(0, len(rr), 100_000):
        part = rr[i:i + 100_000]
        X = part[:, 0:1] * REGION + ox.ravel()[None, :]
        Z = part[:, 1:2] * REGION + oz.ravel()[None, :]
        lon, lat, kind = proj.inverse(X, Z)
        ok = kind > 0
        la = np.floor(lat[ok]).astype(np.int64)
        lo = np.floor((lon[ok] + 180) % 360 - 180).astype(np.int64)
        need |= set(zip(la.tolist(), lo.tolist()))
    need = {t for t in need if -90 <= t[0] < 90}
    have = set(Aw3d30(cfg.dem).tiles()) if cfg.dem else set()
    missing = sorted(need - have)
    print(f"{len(need)} one-degree cells touched; {len(need & have)} AW3D30 tiles present, {len(missing)} not found")
    print("  (cells over open ocean or beyond 82N/S have no AW3D30 tile; those use bathymetry or flat ocean)")
    if a.list_missing:
        for t in missing:
            print("  missing", tile_name(*t))
    if a.json:
        with open(a.json, "w") as f:
            json.dump({"regions": regions, "missing_tiles": [tile_name(*t) for t in missing]}, f)


def cmd_generate(a):
    cfg = _world(a.world)
    proj = cfg.make_projection()
    if not cfg.dem and not cfg.bathymetry:
        print("warning: no --dem or --bathymetry configured; everything will be flat ocean", file=sys.stderr)
    regions = _regions(a, proj)
    if a.limit:
        regions = regions[: a.limit]
    stats = run(a.world, regions, workers=a.workers, overwrite=a.overwrite)
    print(json.dumps(stats))


def cmd_locate(a):
    cfg = _world(a.world)
    proj = cfg.make_projection()
    x, z = proj.forward(np.array([a.lon]), np.array([a.lat]))
    x, z = int(math.floor(x[0])), int(math.floor(z[0]))
    try:
        y = _surface_y(cfg, proj, Sources(cfg), x, z)
    except Exception:
        y = cfg.sea_level + 1
    print(f"/tp @s {x} {y} {z}")


def cmd_whereis(a):
    cfg = _world(a.world)
    proj = cfg.make_projection()
    lon, lat, kind = proj.inverse(np.array([a.x + 0.5]), np.array([a.z + 0.5]))
    if kind[0] == 0:
        print("outside the globe")
    else:
        what = {1: "", 2: " (seam margin: copy of the other side)", 3: " (margin corner)"}[int(kind[0])]
        print(f"lat {lat[0]:.5f}, lon {lon[0]:.5f}{what}")


def cmd_distortion(a):
    from .projection import local_distortion
    cfg = _world(a.world) if a.world else None
    proj = cfg.make_projection() if cfg else None
    if proj is None:
        lat0, lon0 = (float(v) for v in a.center.split(",")) if a.center else (0.0, 24.0)
        from .projection import make_projection
        proj = make_projection(a.projection, a.scale, lon0, 512, lat0)
    lat, lon = np.array([a.lat]), np.array([a.lon])
    small, large, shear = local_distortion(proj, lon, lat)
    x, z = proj.forward(lon, lat)
    face = next((f.name for f in proj.faces if f.contains(x[0], z[0])), "?")
    print(f"lat {a.lat}, lon {a.lon}: block ({int(np.floor(x[0]))}, {int(np.floor(z[0]))}) on face {face}")
    print(f"  1 block = {small[0]:.1f} .. {large[0]:.1f} m depending on direction; shapes sheared up to {shear[0]:.1f} deg")


def cmd_preview(a):
    from .preview import render_sources, render_world, write_png
    cfg = _world(a.world)
    proj = cfg.make_projection()
    if a.area:
        x0, z0, x1, z1 = (int(v) for v in a.area.split(","))
    else:
        s, w, n, e = (float(v) for v in a.bbox.split(","))
        xs, zs = proj.forward(np.array([w, e, w, e, (w + e) / 2]), np.array([s, s, n, n, (s + n) / 2]))
        x0, x1, z0, z1 = int(xs.min()), int(xs.max()) + 1, int(zs.min()), int(zs.max()) + 1
    step = a.step or max(1, math.ceil(max(x1 - x0, z1 - z0) / 2048))
    if a.from_sources:
        img = render_sources(proj, Sources(cfg), cfg, x0, z0, x1, z1, step)
    else:
        img = render_world(a.world, x0, z0, x1, z1, step)
    write_png(a.output, img)
    print(f"wrote {a.output} ({img.shape[1]}x{img.shape[0]}, x {x0}..{x1}, z {z0}..{z1}, 1 px = {step} blocks)")


def main(argv=None):
    p = argparse.ArgumentParser(prog="alos2mc", description="Build a 1:30 Minecraft Earth from JAXA AW3D30 data.")
    sub = p.add_subparsers(dest="cmd", required=True)

    c = sub.add_parser("create", help="create a world folder (config, level.dat, datapack)")
    c.add_argument("world")
    c.add_argument("--name", default="ALOS Earth")
    c.add_argument("--projection", choices=["cube", "equirect"], default="cube")
    c.add_argument("--scale", type=float, default=30.0, help="metres per block horizontally (default 30)")
    c.add_argument("--vertical-scale", type=float, default=None, help="metres per block vertically (default = --scale)")
    c.add_argument("--height-mode", choices=["vanilla", "extended"], default="vanilla")
    c.add_argument("--sea-level", type=int, default=None)
    c.add_argument("--center", help="LAT,LON at the centre of the map, where distortion is lowest "
                   "(default 0,24: minimises shear over the world's largest cities)")
    c.add_argument("--margin", type=int, default=512, help="blocks of neighbouring terrain copied past each seam")
    c.add_argument("--dem", action="append", default=[], help="AW3D30 folder or zip (repeatable)")
    c.add_argument("--bathymetry", action="append", default=[], help="GEBCO GeoTIFF folder/file (repeatable)")
    c.add_argument("--climate", action="append", default=[], help="Koppen-Geiger GeoTIFF (Beck et al. 1 km)")
    c.add_argument("--spawn", help="LAT,LON")
    c.add_argument("--survival", action="store_true")
    c.add_argument("--write-all-ocean", action="store_true", help="also write open-ocean chunks")
    c.add_argument("--data-version", type=int, default=WorldConfig.data_version)
    c.add_argument("--force", action="store_true")
    c.set_defaults(func=cmd_create)

    for name, fn, hlp in (("plan", cmd_plan, "count regions and check which DEM tiles are present"),
                          ("generate", cmd_generate, "write region files")):
        g = sub.add_parser(name, help=hlp)
        g.add_argument("world")
        g.add_argument("--bbox", help="SOUTH,WEST,NORTH,EAST in degrees (default: whole globe)")
        if name == "plan":
            g.add_argument("--list-missing", action="store_true")
            g.add_argument("--json")
        else:
            g.add_argument("--workers", type=int, default=0)
            g.add_argument("--overwrite", action="store_true")
            g.add_argument("--limit", type=int, default=0, help="only the first N regions (testing)")
        g.set_defaults(func=fn)

    lo = sub.add_parser("locate", help="print a /tp command for a latitude/longitude")
    lo.add_argument("world")
    lo.add_argument("lat", type=float)
    lo.add_argument("lon", type=float)
    lo.set_defaults(func=cmd_locate)

    wh = sub.add_parser("whereis", help="latitude/longitude of block coordinates")
    wh.add_argument("world")
    wh.add_argument("x", type=float)
    wh.add_argument("z", type=float)
    wh.set_defaults(func=cmd_whereis)

    ds = sub.add_parser("distortion", help="scale and shape distortion of the projection at a place")
    ds.add_argument("lat", type=float)
    ds.add_argument("lon", type=float)
    ds.add_argument("--world", help="use this world's projection")
    ds.add_argument("--projection", choices=["cube", "equirect"], default="cube")
    ds.add_argument("--center", help="LAT,LON (as for create)")
    ds.add_argument("--scale", type=float, default=30.0)
    ds.set_defaults(func=cmd_distortion)

    pv = sub.add_parser("preview", help="render a PNG map")
    pv.add_argument("world")
    grp = pv.add_mutually_exclusive_group(required=True)
    grp.add_argument("--bbox", help="SOUTH,WEST,NORTH,EAST")
    grp.add_argument("--area", help="X0,Z0,X1,Z1 block coordinates")
    pv.add_argument("--step", type=int, default=0, help="blocks per pixel")
    pv.add_argument("--from-sources", action="store_true", help="render from the input data, not region files")
    pv.add_argument("-o", "--output", default="preview.png")
    pv.set_defaults(func=cmd_preview)

    a = p.parse_args(argv)
    a.func(a)


if __name__ == "__main__":
    main()
