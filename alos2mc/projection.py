"""Mapping between Minecraft block coordinates and latitude/longitude.

Two projections are provided:

``cube`` (default) -- the globe is projected onto the six faces of an
    equi-angular cube (a "cubed sphere").  Every point of the Earth appears
    exactly once and the poles are ordinary places.  Scale stays within
    20-35 m per block and shapes are sheared by up to ~30 degrees near the
    eight cube corners (none at the face centres).  The faces are unfolded
    into a cross::

                      +-------+
                      | north |
              +-------+-------+-------+-------+
              | west  | front | east  | back  |
              +-------+-------+-------+-------+
                      | south |
                      +-------+

    ``front`` is centred on (lat0, lon0).  Five of the twelve cube edges
    touch in this layout and are continuous.  The other seven are joined by
    *links*: rigid block-grid transforms (translation plus a multiple of 90
    degrees rotation) that the generated datapack uses to teleport players
    across, and that the generator uses to copy the neighbouring face's
    terrain into a margin beyond each linked edge so the view across a seam
    matches what is on the other side.

``equirect`` -- a plain longitude/latitude grid.  East and west wrap
    around, but the poles are stretched into lines and cannot be crossed.

In both, +x is east and +z is south at the centre of the map, and block
(0, 0) is at latitude ``lat0``, longitude ``lon0``.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

import numpy as np

EARTH_MEAN_RADIUS_M = 6_371_008.8
EARTH_CIRCUMFERENCE_M = 2 * math.pi * EARTH_MEAN_RADIUS_M


def round_to(value: float, multiple: int) -> int:
    return int(round(value / multiple)) * multiple


def face_size_for_scale(meters_per_block: float) -> int:
    """Cube face edge length (blocks) for a scale, aligned to 1024 so that
    face edges fall on region (512) boundaries.  A face spans 90 degrees of
    arc along its centre lines."""
    return max(1024, round_to(EARTH_CIRCUMFERENCE_M / 4 / meters_per_block, 1024))


@dataclass
class Face:
    name: str
    x0: int          # net coordinate of the face's west (min x) edge
    z0: int          # net coordinate of the face's north (min z) edge
    width: int
    height: int
    normal: np.ndarray | None = None  # cube faces only
    xaxis: np.ndarray | None = None
    zaxis: np.ndarray | None = None

    @property
    def x1(self) -> int:
        return self.x0 + self.width

    @property
    def z1(self) -> int:
        return self.z0 + self.height

    def contains(self, x, z):
        return (x >= self.x0) & (x < self.x1) & (z >= self.z0) & (z < self.z1)


@dataclass
class Link:
    """A one-way seam crossing.

    Points in the strip ``[sx0, sx1) x [sz0, sz1)`` (beyond ``face``'s
    ``edge``) are mapped by ``p' = M @ p + t`` onto the face ``dest``.
    ``yaw`` is the change in player yaw (degrees) needed to keep facing the
    same real-world direction.
    """

    face: str
    edge: str
    dest: str
    dest_edge: str
    sx0: int
    sx1: int
    sz0: int
    sz1: int
    M: tuple[tuple[int, int], tuple[int, int]]
    t: tuple[int, int]
    yaw: int

    def contains(self, x, z):
        return (x >= self.sx0) & (x < self.sx1) & (z >= self.sz0) & (z < self.sz1)

    def depth(self, x, z):
        """How far beyond the linked edge a point is (valid inside the strip)."""
        if self.edge == "top":
            return self.sz1 - z
        if self.edge == "bottom":
            return z - self.sz0
        if self.edge == "left":
            return self.sx1 - x
        return x - self.sx0

    def apply(self, x, z):
        (a, b), (c, d) = self.M
        return a * x + b * z + self.t[0], c * x + d * z + self.t[1]

    def to_json(self) -> dict:
        return {
            "face": self.face, "edge": self.edge, "dest": self.dest, "dest_edge": self.dest_edge,
            "strip": {"x0": self.sx0, "x1": self.sx1, "z0": self.sz0, "z1": self.sz1},
            "matrix": [list(r) for r in self.M], "translation": list(self.t), "yaw": self.yaw,
        }


def _unit(v) -> np.ndarray:
    v = np.asarray(v, dtype=np.float64)
    return v / np.linalg.norm(v)


def lonlat_to_vec(lon, lat):
    lon = np.radians(lon)
    lat = np.radians(lat)
    c = np.cos(lat)
    return np.stack([c * np.cos(lon), c * np.sin(lon), np.sin(lat)], axis=-1)


def vec_to_lonlat(v):
    v = v / np.linalg.norm(v, axis=-1, keepdims=True)
    lat = np.degrees(np.arcsin(np.clip(v[..., 2], -1, 1)))
    lon = np.degrees(np.arctan2(v[..., 1], v[..., 0]))
    return lon, lat


def wrap_lon(lon):
    return (np.asarray(lon) + 180.0) % 360.0 - 180.0


def _yaw_delta(M) -> int:
    """Yaw change (-90, 0, 90 or 180) for a rotation matrix acting on (x, z).
    Minecraft yaw 0 faces +z and yaw 90 faces -x."""
    dx, dz = M[0][1], M[1][1]  # image of (0, 1), the yaw-0 direction
    return {(0, 1): 0, (-1, 0): 90, (0, -1): 180, (1, 0): -90}[(dx, dz)]


_ROT = {
    0: ((1, 0), (0, 1)),
    1: ((0, -1), (1, 0)),
    2: ((-1, 0), (0, -1)),
    3: ((0, 1), (-1, 0)),
}


class Projection:
    kind: str
    faces: list[Face]
    links: list[Link]
    margin: int
    meters_per_block: float
    lon0: float

    # -- to implement
    def face_inverse(self, face: Face, x, z):
        raise NotImplementedError

    def forward(self, lon, lat):
        raise NotImplementedError

    # -- shared
    def face(self, name: str) -> Face:
        for f in self.faces:
            if f.name == name:
                return f
        raise KeyError(name)

    def bounds(self) -> tuple[int, int, int, int]:
        """(xmin, xmax, zmin, zmax) of everything that can be generated,
        including seam margins (half-open)."""
        xs0 = [f.x0 for f in self.faces] + [l.sx0 for l in self.links]
        xs1 = [f.x1 for f in self.faces] + [l.sx1 for l in self.links]
        zs0 = [f.z0 for f in self.faces] + [l.sz0 for l in self.links]
        zs1 = [f.z1 for f in self.faces] + [l.sz1 for l in self.links]
        return min(xs0), max(xs1), min(zs0), max(zs1)

    def inverse(self, x, z):
        """Net coordinates (float arrays, use block centres) -> lon, lat, kind.

        ``kind`` is 0 where the point is outside everything that should be
        generated, 1 on a face, 2 in a seam margin (a copy of the other side
        of a link), 3 in a margin corner next to a cube vertex (continued
        projection of the nearest face).
        """
        x, z = np.broadcast_arrays(np.asarray(x, dtype=np.float64), np.asarray(z, dtype=np.float64))
        lon = np.full(x.shape, np.nan)
        lat = np.full(x.shape, np.nan)
        kind = np.zeros(x.shape, dtype=np.uint8)

        for f in self.faces:
            m = f.contains(x, z)
            if m.any():
                lon[m], lat[m] = self.face_inverse(f, x[m], z[m])
                kind[m] = 1
        # Seam margins.  Where the strips of two seams overlap (next to a
        # cube vertex) the nearer seam wins, as in the datapack.
        free = kind == 0
        if free.any() and self.links:
            depth = np.stack([np.where(l.contains(x, z), l.depth(x, z), np.inf) for l in self.links])
            best = np.argmin(depth, axis=0)
            hit = free & np.isfinite(depth.min(axis=0))
            for i, link in enumerate(self.links):
                m = hit & (best == i)
                if not m.any():
                    continue
                tx, tz = link.apply(x[m], z[m])
                dest = self.face(link.dest)
                # rounding safety: stay on the destination face
                tx = np.clip(tx, dest.x0, dest.x1 - 1e-6)
                tz = np.clip(tz, dest.z0, dest.z1 - 1e-6)
                lon[m], lat[m] = self.face_inverse(dest, tx, tz)
                kind[m] = 2
        if self.margin and self.kind == "cube":
            # corners diagonally beyond a face (at cube vertices): continue
            # the face's own projection so there is no hole in the view.
            for f in self.faces:
                m = ((kind == 0)
                     & (x >= f.x0 - self.margin) & (x < f.x1 + self.margin)
                     & (z >= f.z0 - self.margin) & (z < f.z1 + self.margin))
                if m.any():
                    lon[m], lat[m] = self.face_inverse(f, x[m], z[m])
                    kind[m] = 3
        return lon, lat, kind

    def to_json(self) -> dict:
        return {
            "projection": self.kind,
            "meters_per_block": self.meters_per_block,
            "lat0": self.lat0,
            "lon0": self.lon0,
            "margin": self.margin,
            "faces": [{"name": f.name, "x0": f.x0, "z0": f.z0, "width": f.width, "height": f.height}
                      for f in self.faces],
            "links": [l.to_json() for l in self.links],
        }


class CubeProjection(Projection):
    kind = "cube"

    def __init__(self, meters_per_block: float = 30.0, lon0: float = 24.0, margin: int = 512, lat0: float = 0.0):
        """``(lat0, lon0)`` is the centre of the ``front`` face, with north
        pointing to -z there.  With ``lat0 = 0`` the cube's axis is the
        Earth's axis and the poles sit at the centres of the ``north`` and
        ``south`` faces."""
        S = face_size_for_scale(meters_per_block)
        h = S // 2
        self.size = S
        self.meters_per_block = (EARTH_CIRCUMFERENCE_M / 4) / S  # effective scale on face centre lines
        self.lon0 = lon0
        self.lat0 = lat0
        self.margin = margin
        la, lo = math.radians(lat0), math.radians(lon0)
        n0 = np.array([math.cos(la) * math.cos(lo), math.cos(la) * math.sin(lo), math.sin(la)])
        e0 = np.array([-math.sin(lo), math.cos(lo), 0.0])
        u0 = np.cross(n0, e0)  # local north at the centre point

        def belt(name, k, x0):
            c, s_ = math.cos(k * math.pi / 2), math.sin(k * math.pi / 2)
            n = c * n0 + s_ * e0
            e = -s_ * n0 + c * e0
            return Face(name, x0, -h, S, S, n, e, -u0)

        self.faces = [
            belt("west", -1, -3 * h),
            belt("front", 0, -h),
            belt("east", 1, h),
            belt("back", 2, 3 * h),
            Face("north", -h, -3 * h, S, S, u0, e0, n0),
            Face("south", -h, h, S, S, -u0, e0, -n0),
        ]
        self.links = self._derive_links()

    # face-local equi-angular coordinates in [-1, 1]
    def face_inverse(self, f: Face, x, z):
        a = 2.0 * (np.asarray(x) - f.x0) / f.width - 1.0
        b = 2.0 * (np.asarray(z) - f.z0) / f.height - 1.0
        tu = np.tan(a * (math.pi / 4))[..., None]
        tw = np.tan(b * (math.pi / 4))[..., None]
        v = f.normal + tu * f.xaxis + tw * f.zaxis
        lon, lat = vec_to_lonlat(v)
        return lon, lat

    def _face_forward(self, f: Face, v):
        d = v @ f.normal
        tu = (v @ f.xaxis) / d
        tw = (v @ f.zaxis) / d
        a = np.arctan(tu) * (4 / math.pi)
        b = np.arctan(tw) * (4 / math.pi)
        return f.x0 + (a + 1) / 2 * f.width, f.z0 + (b + 1) / 2 * f.height

    def forward(self, lon, lat):
        v = lonlat_to_vec(np.asarray(lon, dtype=np.float64), np.asarray(lat, dtype=np.float64))
        dots = np.stack([v @ f.normal for f in self.faces], axis=-1)
        best = np.argmax(dots, axis=-1)
        x = np.zeros(best.shape)
        z = np.zeros(best.shape)
        for i, f in enumerate(self.faces):
            m = best == i
            if np.any(m):
                x[m], z[m] = self._face_forward(f, v[m])
        return x, z

    def _edge(self, f: Face, edge: str):
        """Endpoints of an edge in net coordinates, ordered, and the strip
        of the margin beyond it."""
        m = self.margin
        if edge == "top":
            return (f.x0, f.z0), (f.x1, f.z0), (f.x0, f.x1, f.z0 - m, f.z0)
        if edge == "bottom":
            return (f.x0, f.z1), (f.x1, f.z1), (f.x0, f.x1, f.z1, f.z1 + m)
        if edge == "left":
            return (f.x0, f.z0), (f.x0, f.z1), (f.x0 - m, f.x0, f.z0, f.z1)
        if edge == "right":
            return (f.x1, f.z0), (f.x1, f.z1), (f.x1, f.x1 + m, f.z0, f.z1)
        raise ValueError(edge)

    def _derive_links(self) -> list[Link]:
        links: list[Link] = []
        for f in self.faces:
            for edge in ("top", "bottom", "left", "right"):
                p1, p2, strip = self._edge(f, edge)
                # a point on the sphere just across this edge
                mid = ((p1[0] + p2[0]) / 2, (p1[1] + p2[1]) / 2)
                out = {"top": (0, -1), "bottom": (0, 1), "left": (-1, 0), "right": (1, 0)}[edge]
                probe = (mid[0] + out[0] * 0.5, mid[1] + out[1] * 0.5)
                pv = lonlat_to_vec(*self.face_inverse(f, np.array(probe[0]), np.array(probe[1])))
                g = max((o for o in self.faces if o is not f), key=lambda o: float(pv @ o.normal))
                if g.contains(probe[0], probe[1]):
                    continue  # the neighbour touches this edge in the net
                # map the edge's endpoints and midpoint onto g
                q = []
                for p in (p1, p2):
                    pv_ = lonlat_to_vec(*self.face_inverse(f, np.array(float(p[0])), np.array(float(p[1]))))
                    qx, qz = self._face_forward(g, pv_)
                    q.append((int(round(float(qx))), int(round(float(qz)))))
                d = (p2[0] - p1[0], p2[1] - p1[1])
                dq = (q[1][0] - q[0][0], q[1][1] - q[0][1])
                for r, M in _ROT.items():
                    if (M[0][0] * d[0] + M[0][1] * d[1], M[1][0] * d[0] + M[1][1] * d[1]) == dq:
                        break
                else:
                    raise AssertionError(f"no rotation maps edge {f.name}.{edge} onto {g.name}")
                t = (q[0][0] - (M[0][0] * p1[0] + M[0][1] * p1[1]),
                     q[0][1] - (M[1][0] * p1[0] + M[1][1] * p1[1]))
                link = Link(f.name, edge, g.name, "", *strip, M, t, _yaw_delta(M))
                # which edge of g did we land on?
                qm = link.apply(*mid)
                link.dest_edge = ("left" if qm[0] == g.x0 else "right" if qm[0] == g.x1
                                  else "top" if qm[1] == g.z0 else "bottom")
                # sanity: probe beyond the edge lands inside g
                tx, tz = link.apply(*probe)
                assert g.contains(tx, tz), (f.name, edge, g.name)
                links.append(link)
        return links


class EquirectProjection(Projection):
    kind = "equirect"

    def __init__(self, meters_per_block: float = 30.0, lon0: float = 24.0, margin: int = 512):
        self.lat0 = 0.0
        W = max(2048, round_to(EARTH_CIRCUMFERENCE_M / meters_per_block, 2048))
        self.width = W
        self.meters_per_block = EARTH_CIRCUMFERENCE_M / W
        self.lon0 = lon0
        self.margin = margin
        h = W // 4
        self.faces = [Face("world", -W // 2, -h, W, 2 * h)]
        ident = ((1, 0), (0, 1))
        m = margin
        self.links = [
            Link("world", "right", "world", "left", W // 2, W // 2 + m, -h, h, ident, (-W, 0), 0),
            Link("world", "left", "world", "right", -W // 2 - m, -W // 2, -h, h, ident, (W, 0), 0),
        ]

    def face_inverse(self, f, x, z):
        deg = 360.0 / self.width
        lon = wrap_lon(self.lon0 + np.asarray(x) * deg)
        lat = -np.asarray(z) * deg
        return lon, lat

    def forward(self, lon, lat):
        deg = 360.0 / self.width
        x = wrap_lon(np.asarray(lon, dtype=np.float64) - self.lon0) / deg
        z = -np.asarray(lat, dtype=np.float64) / deg
        return x, z


def local_distortion(proj: Projection, lon, lat):
    """Metres per block along the most and least stretched directions, and
    the maximum angular distortion (degrees), at the given points."""
    lon = np.asarray(lon, dtype=np.float64)
    lat = np.asarray(lat, dtype=np.float64)
    h = 1e-4
    x0, z0 = proj.forward(lon, lat)
    xe, ze = proj.forward(lon + h / np.cos(np.radians(lat)), lat)
    xn, zn = proj.forward(lon, lat + h)
    m = math.radians(h) * EARTH_MEAN_RADIUS_M
    J = np.stack([np.stack([(xe - x0) / m, (xn - x0) / m], -1),
                  np.stack([(ze - z0) / m, (zn - z0) / m], -1)], -2)  # blocks per metre
    sv = np.linalg.svd(J, compute_uv=False)
    a, b = sv[..., 0], sv[..., 1]
    return 1 / a, 1 / b, np.degrees(2 * np.arcsin((a - b) / (a + b)))


def make_projection(kind: str, meters_per_block: float = 30.0, lon0: float = 24.0, margin: int = 512,
                    lat0: float = 0.0) -> Projection:
    if kind == "cube":
        return CubeProjection(meters_per_block, lon0, margin, lat0)
    if kind == "equirect":
        if lat0:
            raise ValueError("the equirect projection cannot be tilted (lat0 must be 0)")
        return EquirectProjection(meters_per_block, lon0, margin)
    raise ValueError(f"unknown projection {kind!r}")


__all__ = ["Projection", "CubeProjection", "EquirectProjection", "Link", "Face", "make_projection", "local_distortion",
           "wrap_lon", "lonlat_to_vec", "vec_to_lonlat"]
