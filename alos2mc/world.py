"""level.dat and the world datapack (seam teleports, optional taller world)."""

from __future__ import annotations

import json
import os
import time

from . import nbt
from .config import WorldConfig
from .projection import Projection
from .terrain import flat_layers

PACK_NAME = "alos2mc"
NS = "alos2mc"
VERSION_NAMES = {3465: "1.20.1", 3700: "1.20.4", 3837: "1.20.6", 3953: "1.21", 3955: "1.21.1"}
# data pack format of the targeted version (1.21.1); newer versions accept a range
PACK_FORMAT = 48
MAX_PACK_FORMAT = 999


def write_level_dat(world_dir: str, cfg: WorldConfig, spawn_xyz: tuple[int, int, int]) -> None:
    layers = nbt.List(nbt.COMPOUND, [{"block": b, "height": h} for b, h in flat_layers(cfg)])
    dims = {
        "minecraft:overworld": {
            "type": "minecraft:overworld",
            "generator": {
                "type": "minecraft:flat",
                "settings": {
                    "layers": layers,
                    "biome": "minecraft:ocean",
                    "lakes": nbt.Byte(0),
                    "features": nbt.Byte(0),
                    "structure_overrides": nbt.List(nbt.STRING),
                },
            },
        },
        "minecraft:the_nether": {
            "type": "minecraft:the_nether",
            "generator": {"type": "minecraft:noise", "settings": "minecraft:nether",
                          "biome_source": {"type": "minecraft:multi_noise", "preset": "minecraft:nether"}},
        },
        "minecraft:the_end": {
            "type": "minecraft:the_end",
            "generator": {"type": "minecraft:noise", "settings": "minecraft:end",
                          "biome_source": {"type": "minecraft:the_end"}},
        },
    }
    sx, sy, sz = spawn_xyz
    data = {
        "DataVersion": cfg.data_version,
        "version": 19133,
        "Version": {"Id": cfg.data_version, "Name": VERSION_NAMES.get(cfg.data_version, "1.21.1"),
                    "Series": "main", "Snapshot": nbt.Byte(0)},
        "LevelName": cfg.level_name,
        "GameType": cfg.game_mode,
        "Difficulty": nbt.Byte(1),
        "hardcore": nbt.Byte(0),
        "allowCommands": nbt.Byte(1),
        "initialized": nbt.Byte(1),
        "SpawnX": sx, "SpawnY": sy, "SpawnZ": sz, "SpawnAngle": nbt.Float(0.0),
        "Time": nbt.Long(0), "DayTime": nbt.Long(6000),
        "LastPlayed": nbt.Long(int(time.time() * 1000)),
        "raining": nbt.Byte(0), "rainTime": 0, "thundering": nbt.Byte(0), "thunderTime": 0,
        "clearWeatherTime": 0,
        "WasModded": nbt.Byte(0),
        "DataPacks": {"Enabled": nbt.List(nbt.STRING, ["vanilla", f"file/{PACK_NAME}"]),
                      "Disabled": nbt.List(nbt.STRING)},
        "WorldGenSettings": {"seed": nbt.Long(0), "generate_features": nbt.Byte(0),
                             "bonus_chest": nbt.Byte(0), "dimensions": dims},
        "GameRules": {"doMobSpawning": "true", "spawnRadius": "0"},
    }
    nbt.write_gzip_file(os.path.join(world_dir, "level.dat"), {"Data": data})


# ---------------------------------------------------------------- datapack


def _write(path: str, text: str) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", newline="\n") as f:
        f.write(text)


def _range(lo: int, hi: int) -> str:
    return f"{lo}..{hi}"


def link_trigger_ranges(link):
    """Score ranges (thousandths of a block) that trigger a link.  Strips on
    the positive side of an edge start one step past the edge so a player
    standing exactly on it is never bounced back and forth."""
    x_lo, x_hi = link.sx0 * 1000, link.sx1 * 1000 - 1
    z_lo, z_hi = link.sz0 * 1000, link.sz1 * 1000 - 1
    if link.edge == "right":
        x_lo += 1
    if link.edge == "bottom":
        z_lo += 1
    return (x_lo, x_hi), (z_lo, z_hi)


def link_commands(link) -> list[str]:
    """Commands of the per-link teleport function (executed as the player,
    whose position in thousandths is in the x/z scores)."""
    (a, b), (c, d) = link.M
    t0, t1 = link.t
    if (a, b, c, d) == (1, 0, 0, 1):
        return [f"execute at @s run tp @s ~{t0} ~ ~{t1}"]
    cmds = []
    for out, (cx, cz), t in (("#nx", (a, b), t0), ("#nz", (c, d), t1)):
        srcobj = f"{NS}.x" if cx else f"{NS}.z"
        coef = cx or cz
        cmds.append(f"scoreboard players operation {out} {NS}.t = @s {srcobj}")
        if coef == -1:
            cmds.append(f"scoreboard players operation {out} {NS}.t *= #-1 {NS}.t")
        if t:
            op = "add" if t > 0 else "remove"
            cmds.append(f"scoreboard players {op} {out} {NS}.t {abs(t) * 1000}")
    cmds += [
        f"execute store result storage {NS}:tp x double 0.001 run scoreboard players get #nx {NS}.t",
        f"execute store result storage {NS}:tp z double 0.001 run scoreboard players get #nz {NS}.t",
        f"data modify storage {NS}:tp y set from entity @s Pos[1]",
        f"data modify storage {NS}:tp yaw set value {link.yaw}",
        f"function {NS}:wrap/tp with storage {NS}:tp",
    ]
    return cmds


def _depth_commands(link, out: str) -> list[str]:
    """Score ``out`` = how far (thousandths) the player is beyond the edge."""
    obj = f"{NS}.t"
    if link.edge == "top":
        return [f"scoreboard players set {out} {obj} {link.sz1 * 1000}",
                f"scoreboard players operation {out} {obj} -= @s {NS}.z"]
    if link.edge == "left":
        return [f"scoreboard players set {out} {obj} {link.sx1 * 1000}",
                f"scoreboard players operation {out} {obj} -= @s {NS}.x"]
    src, edge = (f"{NS}.z", link.sz0) if link.edge == "bottom" else (f"{NS}.x", link.sx0)
    return [f"scoreboard players set {out} {obj} {edge * 1000}",
            f"scoreboard players operation {out} {obj} *= #-1 {obj}",
            f"scoreboard players operation {out} {obj} += @s {src}"]


def strip_overlaps(proj: Projection):
    """Pairs of links whose margin strips overlap (next to cube vertices):
    (i, j, x range, z range)."""
    out = []
    L = proj.links
    for i in range(len(L)):
        for j in range(i + 1, len(L)):
            x0, x1 = max(L[i].sx0, L[j].sx0), min(L[i].sx1, L[j].sx1)
            z0, z1 = max(L[i].sz0, L[j].sz0), min(L[i].sz1, L[j].sz1)
            if x0 < x1 and z0 < z1:
                out.append((i, j, (x0, x1), (z0, z1)))
    return out


def corner_pushbacks(proj: Projection):
    """Margin squares diagonally beyond a face corner that no face or link
    covers (next to cube vertices): (x range, z range, target x, target z)."""
    m = proj.margin
    out = []
    if proj.kind != "cube" or not m:
        return out
    for f in proj.faces:
        for (cx, cz, sx, sz) in ((f.x0, f.z0, -1, -1), (f.x1, f.z0, 1, -1), (f.x0, f.z1, -1, 1), (f.x1, f.z1, 1, 1)):
            x0, x1 = (cx - m, cx) if sx < 0 else (cx, cx + m)
            z0, z1 = (cz - m, cz) if sz < 0 else (cz, cz + m)
            px, pz = (x0 + x1) / 2, (z0 + z1) / 2
            if any(g.contains(px, pz) for g in proj.faces) or any(l.contains(px, pz) for l in proj.links):
                continue
            tx = cx - 0.5 if sx > 0 else cx + 0.5
            tz = cz - 0.5 if sz > 0 else cz + 0.5
            out.append(((x0, x1), (z0, z1), tx, tz))
    return out


def write_datapack(world_dir: str, cfg: WorldConfig, proj: Projection) -> str:
    root = os.path.join(world_dir, "datapacks", PACK_NAME)
    meta = {
        "pack": {
            "description": f"ALOS AW3D30 Earth ({proj.kind} projection, 1:{cfg.meters_per_block:g})",
            "pack_format": PACK_FORMAT,
            "supported_formats": {"min_inclusive": PACK_FORMAT, "max_inclusive": MAX_PACK_FORMAT},
            "min_format": PACK_FORMAT,
            "max_format": MAX_PACK_FORMAT,
        }
    }
    _write(os.path.join(root, "pack.mcmeta"), json.dumps(meta, indent=2) + "\n")
    data = os.path.join(root, "data")
    fn = os.path.join(data, NS, "function")

    _write(os.path.join(data, "minecraft", "tags", "function", "load.json"),
           json.dumps({"values": [f"{NS}:load"]}, indent=2) + "\n")
    _write(os.path.join(data, "minecraft", "tags", "function", "tick.json"),
           json.dumps({"values": [f"{NS}:tick"]}, indent=2) + "\n")
    _write(os.path.join(fn, "load.mcfunction"), "\n".join([
        f"scoreboard objectives add {NS}.x dummy",
        f"scoreboard objectives add {NS}.z dummy",
        f"scoreboard objectives add {NS}.t dummy",
        f"scoreboard players set #-1 {NS}.t -1",
        "",
    ]))
    _write(os.path.join(fn, "tick.mcfunction"),
           f"execute in minecraft:overworld as @a[distance=0..] run function {NS}:wrap/check\n")
    _write(os.path.join(fn, "wrap", "tp.mcfunction"), "$tp @s $(x) $(y) $(z) ~$(yaw) ~\n")

    check = [
        "# Teleports players who step across a seam of the globe.  Generated by alos2mc.",
        f"execute store result score @s {NS}.x run data get entity @s Pos[0] 1000",
        f"execute store result score @s {NS}.z run data get entity @s Pos[2] 1000",
    ]
    # overlapping strips: the seam the player is nearer to wins
    for n, (i, j, _, _) in enumerate(strip_overlaps(proj)):
        (xa, xb), (za, zb) = link_trigger_ranges(proj.links[i])
        (xc, xd), (zc, zd) = link_trigger_ranges(proj.links[j])
        check.append(f"execute if score @s {NS}.x matches {_range(max(xa, xc), min(xb, xd))} if score @s {NS}.z "
                     f"matches {_range(max(za, zc), min(zb, zd))} run return run function {NS}:wrap/vertex{n}")
        body = _depth_commands(proj.links[i], "#da") + _depth_commands(proj.links[j], "#db") + [
            f"execute if score #da {NS}.t <= #db {NS}.t run return run function {NS}:wrap/link{i}",
            f"function {NS}:wrap/link{j}",
        ]
        _write(os.path.join(fn, "wrap", f"vertex{n}.mcfunction"), "\n".join(body) + "\n")
    for i, link in enumerate(proj.links):
        (xl, xh), (zl, zh) = link_trigger_ranges(link)
        check.append(f"execute if score @s {NS}.x matches {_range(xl, xh)} if score @s {NS}.z matches "
                     f"{_range(zl, zh)} run return run function {NS}:wrap/link{i}")
        _write(os.path.join(fn, "wrap", f"link{i}.mcfunction"),
               f"# {link.face}.{link.edge} -> {link.dest}.{link.dest_edge}\n" + "\n".join(link_commands(link)) + "\n")
    for (x0, x1), (z0, z1), tx, tz in corner_pushbacks(proj):
        check.append(f"execute if score @s {NS}.x matches {_range(x0 * 1000, x1 * 1000 - 1)} if score @s {NS}.z "
                     f"matches {_range(z0 * 1000, z1 * 1000 - 1)} run return run tp @s {tx} ~ {tz}")
    _write(os.path.join(fn, "wrap", "check.mcfunction"), "\n".join(check) + "\n")

    if cfg.height_mode == "extended":
        dim_type = {
            "ultrawarm": False, "natural": True, "coordinate_scale": 1.0, "has_skylight": True,
            "has_ceiling": False, "ambient_light": 0.0, "piglin_safe": False, "bed_works": True,
            "respawn_anchor_works": False, "has_raids": True,
            "monster_spawn_light_level": {"type": "minecraft:uniform", "min_inclusive": 0, "max_inclusive": 7},
            "monster_spawn_block_light_limit": 0,
            "min_y": cfg.min_y, "height": cfg.max_y - cfg.min_y, "logical_height": cfg.max_y - cfg.min_y,
            "infiniburn": "#minecraft:infiniburn_overworld", "effects": "minecraft:overworld",
        }
        _write(os.path.join(data, "minecraft", "dimension_type", "overworld.json"),
               json.dumps(dim_type, indent=2) + "\n")
    with open(os.path.join(world_dir, "alos2mc_links.json"), "w") as f:
        json.dump(proj.to_json(), f, indent=2)
    return root
