import math

import numpy as np
import pytest

from alos2mc.projection import CubeProjection, EquirectProjection, lonlat_to_vec


def angle_m(lon1, lat1, lon2, lat2):
    """Great-circle distance in metres."""
    v1, v2 = lonlat_to_vec(lon1, lat1), lonlat_to_vec(lon2, lat2)
    chord = np.linalg.norm(v1 - v2, axis=-1)
    return 2 * np.arcsin(np.clip(chord / 2, 0, 1)) * 6_371_008.8


@pytest.fixture(scope="module", params=[(0.0, 24.0), (36.0, 138.0)], ids=["polar", "tilted"])
def cube(request):
    lat0, lon0 = request.param
    return CubeProjection(30.0, lon0, 512, lat0)


def random_sphere(n, seed=0):
    rng = np.random.default_rng(seed)
    v = rng.normal(size=(n, 3))
    v /= np.linalg.norm(v, axis=1, keepdims=True)
    return np.degrees(np.arctan2(v[:, 1], v[:, 0])), np.degrees(np.arcsin(v[:, 2]))


def test_face_size_and_scale(cube):
    assert cube.size % 1024 == 0
    assert abs(cube.meters_per_block - 30) < 0.1


@pytest.mark.parametrize("proj", [CubeProjection(30, 24, 512), CubeProjection(30, 138, 512, 36),
                                  EquirectProjection(30, 10, 512)])
def test_roundtrip(proj):
    lon, lat = random_sphere(20000)
    if proj.kind == "equirect":
        lat = lat * 0.99
    x, z = proj.forward(lon, lat)
    lon2, lat2, kind = proj.inverse(x, z)
    assert (kind == 1).all()
    assert angle_m(lon, lat, lon2, lat2).max() < 0.01


def test_every_point_is_on_exactly_one_face(cube):
    lon, lat = random_sphere(20000, 1)
    x, z = cube.forward(lon, lat)
    inside = np.stack([f.contains(x, z) for f in cube.faces])
    assert (inside.sum(0) == 1).all()
    if cube.lat0:
        return
    # poles are face centres
    px, pz = cube.forward(np.array([0.0, 0.0]), np.array([90.0, -90.0]))
    n, s = cube.face("north"), cube.face("south")
    assert (px[0], pz[0]) == ((n.x0 + n.x1) / 2, (n.z0 + n.z1) / 2)
    assert (px[1], pz[1]) == ((s.x0 + s.x1) / 2, (s.z0 + s.z1) / 2)


def test_block_scale_everywhere(cube):
    """Neighbouring blocks are 30 m +- 30 % apart anywhere on the globe."""
    rng = np.random.default_rng(2)
    for f in cube.faces:
        x = f.x0 + rng.random(5000) * (f.width - 1)
        z = f.z0 + rng.random(5000) * (f.height - 1)
        lo, la, _ = cube.inverse(x, z)
        for dx, dz in ((1, 0), (0, 1)):
            lo2, la2, _ = cube.inverse(x + dx, z + dz)
            d = angle_m(lo, la, lo2, la2)
            assert 0.7 * 30 < d.min() and d.max() < 1.3 * 30, (f.name, d.min(), d.max())


def test_links_are_seamless(cube):
    """Stepping across any linked edge lands next to where you were on the
    globe, and linked edges come in inverse pairs."""
    assert len(cube.links) == 14
    rng = np.random.default_rng(4)
    for link in cube.links:
        f = cube.face(link.face)
        g = cube.face(link.dest)
        t = rng.random(2000)
        if link.edge in ("top", "bottom"):
            x = f.x0 + t * f.width
            z_in = f.z0 + 0.25 if link.edge == "top" else f.z1 - 0.25
            z_out = f.z0 - 0.25 if link.edge == "top" else f.z1 + 0.25
            x_in = x_out = x
        else:
            z = f.z0 + t * f.height
            x_in = f.x0 + 0.25 if link.edge == "left" else f.x1 - 0.25
            x_out = f.x0 - 0.25 if link.edge == "left" else f.x1 + 0.25
            z_in = z_out = z
            x_in, x_out = np.full_like(z, x_in), np.full_like(z, x_out)
        tx, tz = link.apply(x_out, z_out)
        assert g.contains(tx, tz).all()
        lo_a, la_a, _ = cube.inverse(x_in, z_in)
        lo_b, la_b, kind = cube.inverse(tx, tz)
        assert (kind == 1).all()
        d = angle_m(lo_a, la_a, lo_b, la_b)
        assert d.max() < 0.5 * 1.3 * 30 + 1, (link.face, link.edge, d.max())
        # the margin shows exactly the destination's terrain
        lo_m, la_m, kind_m = cube.inverse(x_out, z_out)
        assert (kind_m == 2).all()
        assert angle_m(lo_m, la_m, lo_b, la_b).max() < 1e-3
        # inverse link exists
        back = [l for l in cube.links if l.face == link.dest and l.edge == link.dest_edge]
        assert len(back) == 1
        (a, b), (c, d_) = link.M
        (A, B), (C, D) = back[0].M
        assert np.allclose(np.array([[A, B], [C, D]]) @ np.array([[a, b], [c, d_]]), np.eye(2))
        bx, bz = back[0].apply(*link.apply(123.0, 456.0))
        assert (bx, bz) == (123.0, 456.0)
        # yaw keeps the walking direction: direction (0,1) rotates like yaw
        yaw = math.radians(link.yaw)
        assert np.allclose((b, d_), (-math.sin(yaw), math.cos(yaw)), atol=1e-9)


def test_net_adjacent_edges_are_continuous(cube):
    """The five edges that touch in the cross layout need no link."""
    pairs = [("west", "front"), ("front", "east"), ("east", "back")]
    for a, b in pairs:
        fa = cube.face(a)
        z = fa.z0 + np.linspace(0.5, fa.height - 0.5, 1000)
        lo1, la1, _ = cube.inverse(np.full_like(z, fa.x1 - 0.5), z)
        lo2, la2, _ = cube.inverse(np.full_like(z, fa.x1 + 0.5), z)
        assert angle_m(lo1, la1, lo2, la2).max() < 40
    e = cube.face("front")
    x = e.x0 + np.linspace(0.5, e.width - 0.5, 1000)
    for zz in (e.z0, e.z1):
        lo1, la1, _ = cube.inverse(x, np.full_like(x, zz - 0.5))
        lo2, la2, _ = cube.inverse(x, np.full_like(x, zz + 0.5))
        assert angle_m(lo1, la1, lo2, la2).max() < 40


def test_equirect_wraps_east_west():
    p = EquirectProjection(30, 10, 512)
    right, left = p.links
    assert right.t == (-p.width, 0) and left.t == (p.width, 0)
    lo1, la1, _ = p.inverse(np.array([p.width / 2 - 0.5]), np.array([100.5]))
    lo2, la2, _ = p.inverse(np.array([-p.width / 2 + 0.5]), np.array([100.5]))
    assert angle_m(lo1, la1, lo2, la2)[0] < 40


def test_center_has_no_distortion():
    from alos2mc.projection import local_distortion
    p = CubeProjection(30, 138.7, 512, 35.4)
    x, z = p.forward(np.array([138.7]), np.array([35.4]))
    assert abs(x[0]) < 1e-6 and abs(z[0]) < 1e-6
    a, b, shear = local_distortion(p, np.array([138.7]), np.array([35.4]))
    assert shear[0] < 0.1 and abs(a[0] - 30) < 0.1 and abs(b[0] - 30) < 0.1
    # north is -z at the centre
    x2, z2 = p.forward(np.array([138.7]), np.array([35.5]))
    assert abs(x2[0]) < 1e-3 and z2[0] < -300
