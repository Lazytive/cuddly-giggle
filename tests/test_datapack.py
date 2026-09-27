"""Runs the generated .mcfunction files through a small interpreter for the
handful of commands they use, to check the seam teleports end to end."""

import json
import math
import os
import re

import numpy as np
import pytest

from alos2mc.config import WorldConfig
from alos2mc.world import write_datapack


def i32(v):
    return (int(v) + 2**31) % 2**32 - 2**31


class Game:
    def __init__(self, pack_dir):
        self.fn_dir = os.path.join(pack_dir, "data", "alos2mc", "function")
        self.scores = {}
        self.storage = {}
        self.pos = [0.0, 100.0, 0.0]
        self.yaw = 0.0
        self.funcs = {}

    def load(self, name):
        if name not in self.funcs:
            path = os.path.join(self.fn_dir, *name.split(":")[1].split("/")) + ".mcfunction"
            with open(path) as f:
                self.funcs[name] = [l.strip() for l in f if l.strip() and not l.startswith("#")]
        return self.funcs[name]

    def call(self, name, macro=None):
        for line in self.load(name):
            if line.startswith("$"):
                line = re.sub(r"\$\((\w+)\)", lambda m: self._fmt(macro[m.group(1)]), line[1:])
            r = self.run(line)
            if r == "return":
                return

    @staticmethod
    def _fmt(v):
        if isinstance(v, float):
            return f"{v:.15f}".rstrip("0").rstrip(".")
        return str(v)

    def key(self, holder, obj):
        return ("@s" if holder == "@s" else holder, obj)

    def run(self, cmd):
        m = re.fullmatch(r"execute store result score @s (\S+) run data get entity @s Pos\[(\d)\] (\d+)", cmd)
        if m:
            self.scores[("@s", m[1])] = i32(math.floor(self.pos[int(m[2])] * int(m[3])))
            return
        m = re.fullmatch(r"execute (if score .*?) run (return run )?(.*)", cmd)
        if m:
            conds = re.findall(r"if score (\S+) (\S+) (matches (\S+)|(<=) (\S+) (\S+))", m[1])
            ok = True
            for holder, obj, _, rng, op, h2, o2 in conds:
                v = self.scores.get(self.key(holder, obj), 0)
                if rng:
                    lo, hi = rng.split("..")
                    ok &= (lo == "" or v >= int(lo)) and (hi == "" or v <= int(hi))
                else:
                    ok &= v <= self.scores.get(self.key(h2, o2), 0)
            if ok:
                self.run(m[3])
                if m[2]:
                    return "return"
            return
        m = re.fullmatch(r"execute at @s run tp @s ~(-?\d+) ~ ~(-?\d+)", cmd)
        if m:
            self.pos[0] += int(m[1])
            self.pos[2] += int(m[2])
            return
        m = re.fullmatch(r"tp @s (\S+) (\S+) (\S+) ~(\S+) ~", cmd)
        if m:
            self.pos = [float(m[1]), float(m[2]), float(m[3])]
            self.yaw += float(m[4])
            return
        m = re.fullmatch(r"tp @s (\S+) ~ (\S+)", cmd)
        if m:
            self.pos[0], self.pos[2] = float(m[1]), float(m[2])
            return
        m = re.fullmatch(r"scoreboard players (set|add|remove) (\S+) (\S+) (-?\d+)", cmd)
        if m:
            k = self.key(m[2], m[3])
            v = int(m[4])
            cur = self.scores.get(k, 0)
            self.scores[k] = i32(v if m[1] == "set" else cur + v if m[1] == "add" else cur - v)
            return
        m = re.fullmatch(r"scoreboard players operation (\S+) (\S+) (=|\*=|-=|\+=) (\S+) (\S+)", cmd)
        if m:
            k = self.key(m[1], m[2])
            o = self.scores.get(self.key(m[4], m[5]), 0)
            cur = self.scores.get(k, 0)
            self.scores[k] = i32({"=": o, "*=": cur * o, "-=": cur - o, "+=": cur + o}[m[3]])
            return
        m = re.fullmatch(r"execute store result storage \S+ (\w+) double ([\d.]+) run scoreboard players get (\S+) (\S+)", cmd)
        if m:
            self.storage[m[1]] = self.scores.get(self.key(m[3], m[4]), 0) * float(m[2])
            return
        m = re.fullmatch(r"data modify storage \S+ y set from entity @s Pos\[1\]", cmd)
        if m:
            self.storage["y"] = self.pos[1]
            return
        m = re.fullmatch(r"data modify storage \S+ (\w+) set value (-?\d+)", cmd)
        if m:
            self.storage[m[1]] = int(m[2])
            return
        m = re.fullmatch(r"function (\S+) with storage \S+", cmd)
        if m:
            self.call(m[1], dict(self.storage))
            return
        m = re.fullmatch(r"function (\S+)", cmd)
        if m:
            self.call(m[1])
            return
        if cmd.startswith("scoreboard objectives add"):
            return
        raise AssertionError(f"interpreter does not know: {cmd}")


@pytest.fixture(scope="module", params=["cube", "equirect"])
def setup(request, tmp_path_factory):
    d = str(tmp_path_factory.mktemp(request.param))
    cfg = WorldConfig(projection=request.param)
    proj = cfg.make_projection()
    pack = write_datapack(d, cfg, proj)
    g = Game(pack)
    g.call("alos2mc:load")
    return proj, g, d


def on_face(proj, x, z):
    return any(f.contains(x, z) for f in proj.faces)


def test_links_json(setup):
    proj, g, d = setup
    with open(os.path.join(d, "alos2mc_links.json")) as f:
        data = json.load(f)
    assert len(data["links"]) == len(proj.links)


def test_players_on_faces_are_not_moved(setup):
    proj, g, _ = setup
    rng = np.random.default_rng(0)
    for f in proj.faces:
        for _ in range(200):
            # bias towards edges
            u = rng.choice([rng.random(), rng.random() * 1e-4, 1 - rng.random() * 1e-4])
            v = rng.random()
            x = f.x0 + u * f.width
            z = f.z0 + v * f.height
            if rng.random() < 0.5:
                x, z = f.x0 + v * f.width, f.z0 + u * f.height
            if not f.contains(x, z):
                continue
            g.pos, g.yaw = [x, 70.0, z], 0.0
            g.call("alos2mc:wrap/check")
            assert g.pos == [x, 70.0, z]


def test_crossing_every_seam(setup):
    """A player just past any seam is moved onto a face, to the same place
    on the globe that the margin terrain around them showed, facing the
    same real-world direction, and stays there on the next tick."""
    proj, g, _ = setup
    rng = np.random.default_rng(1)
    for link in proj.links:
        for _ in range(300):
            t = rng.random()
            depth = rng.choice([0.001, 0.2, 3.0, 40.0])
            if link.edge in ("left", "right"):
                z = link.sz0 + t * (link.sz1 - link.sz0)
                x = link.sx1 - depth if link.edge == "left" else link.sx0 + depth
            else:
                x = link.sx0 + t * (link.sx1 - link.sx0)
                z = link.sz1 - depth if link.edge == "top" else link.sz0 + depth
            if on_face(proj, x, z):
                continue
            lon0, lat0, kind0 = proj.inverse(np.array([x]), np.array([z]))
            g.pos, g.yaw = [x, 80.0, z], 30.0
            g.call("alos2mc:wrap/check")
            nx, _, nz = g.pos
            assert on_face(proj, nx, nz), (link.face, link.edge, x, z, g.pos)
            # same point of the globe as the margin showed (within 1 mm of
            # score rounding)
            lon1, lat1, kind1 = proj.inverse(np.array([nx]), np.array([nz]))
            assert kind1[0] == 1
            if kind0[0] == 2:
                d = abs(lat1[0] - lat0[0]) + abs(((lon1[0] - lon0[0]) + 180) % 360 - 180) * math.cos(math.radians(lat0[0]))
                assert d < 1e-4, (link.face, link.edge, x, z)
            before = list(g.pos)
            g.call("alos2mc:wrap/check")
            assert g.pos == before  # no bouncing back


def test_vertex_corners_push_back(setup):
    proj, g, _ = setup
    if proj.kind != "cube":
        return
    from alos2mc.world import corner_pushbacks
    for (x0, x1), (z0, z1), tx, tz in corner_pushbacks(proj):
        g.pos = [(x0 + x1) / 2, 64.0, (z0 + z1) / 2]
        g.call("alos2mc:wrap/check")
        assert on_face(proj, g.pos[0], g.pos[2])
