import io
import os

import nbtlib
import numpy as np

from alos2mc import blocks as bk
from alos2mc import nbt
from alos2mc.anvil import ChunkBuilder, decode_section_blocks, pack_indices, read_region, unpack_indices, write_region


def reference_pack(values, bits):
    """Straight transcription of Minecraft's SimpleBitStorage layout."""
    vpl = 64 // bits
    longs = []
    for i in range(0, len(values), vpl):
        v = 0
        for j, x in enumerate(values[i:i + vpl]):
            v |= int(x) << (j * bits)
        if v >= 1 << 63:
            v -= 1 << 64
        longs.append(v)
    return longs


def test_pack_matches_reference():
    rng = np.random.default_rng(1)
    for bits in (1, 2, 4, 5, 6, 7, 9, 12):
        vals = rng.integers(0, 1 << bits, 4096).astype(np.uint64)
        ours = pack_indices(vals, bits).astype(np.int64).tolist()
        assert ours == reference_pack(vals.tolist(), bits)
        assert (unpack_indices(pack_indices(vals, bits), bits, 4096) == vals).all()


def test_nbt_roundtrip_and_nbtlib():
    root = {"a": nbt.Byte(3), "b": 7, "c": nbt.Long(-5), "d": "hé", "e": nbt.List(nbt.STRING, ["x", "y"]),
            "f": {"g": 1.5}, "h": nbt.LongArray([1, -2]), "i": nbt.List(nbt.COMPOUND)}
    raw = nbt.encode(root)
    name, back = nbt.decode(raw)
    assert back["a"] == 3 and back["b"] == 7 and back["c"] == -5 and back["d"] == "hé"
    assert back["h"].values.tolist() == [1, -2]
    f = nbtlib.File.parse(io.BytesIO(raw))
    assert int(f["c"]) == -5 and list(f["h"]) == [1, -2] and str(f["e"][1]) == "y"


def _columns(n=16):
    z, x = np.mgrid[0:n, 0:n]
    top = (40 + x + z).astype(np.int32)          # sloping land 40..70
    top[0, :4] = 10                               # a sea inlet
    water = np.full((n, n), -64, dtype=np.int32)
    water[0, :4] = 20
    surf = np.full((n, n), bk.S_GRASS, dtype=np.uint8)
    surf[0, :4] = bk.S_SEABED
    surf[10:, 10:] = bk.S_SNOW
    biome = np.full((n, n), bk.B["plains"], dtype=np.uint8)
    biome[:8, :8] = bk.B["ocean"]
    return top, surf, water, biome


def column_blocks(chunk, x, z):
    """{y: block name} of one column of a decoded chunk."""
    out = {}
    for s in chunk["sections"]:
        names, idx = decode_section_blocks(s)
        idx = idx.reshape(16, 16, 16)
        for yy in range(16):
            out[s["Y"] * 16 + yy] = names[idx[yy, z, x]]
    return out


def test_chunk_contents():
    b = ChunkBuilder(-64, 320)
    top, surf, water, biome = _columns()
    raw = b.build(5, -7, top, surf, water, biome)
    chunk = nbt.decode(raw)[1]
    assert chunk["xPos"] == 5 and chunk["zPos"] == -7 and chunk["yPos"] == -4
    assert chunk["Status"] == "minecraft:full"
    assert [s["Y"] for s in chunk["sections"]] == list(range(-4, 20))

    col = column_blocks(chunk, 3, 2)  # land, top = 45, grass over 3 dirt
    assert col[-64] == "minecraft:bedrock"
    assert col[-1] == "minecraft:deepslate" and col[0] == "minecraft:stone"
    assert col[41] == "minecraft:stone"
    assert [col[y] for y in (42, 43, 44)] == ["minecraft:dirt"] * 3
    assert col[45] == "minecraft:grass_block" and col[46] == "minecraft:air"

    sea = column_blocks(chunk, 1, 0)  # sea floor at 10, water to 20
    assert sea[10] == "minecraft:sand" and sea[11] == "minecraft:water" and sea[20] == "minecraft:water"
    assert sea[21] == "minecraft:air"

    snow = column_blocks(chunk, 12, 12)
    assert snow[64] == "minecraft:snow_block" and snow[63] == "minecraft:stone"

    # properties are written
    pal = [p for s in chunk["sections"] for p in s["block_states"]["palette"]]
    water_entry = next(p for p in pal if p["Name"] == "minecraft:water")
    assert water_entry["Properties"]["level"] == "0"

    # biomes: 4x4 cells, ocean in the north-west quarter
    bio = chunk["sections"][10]["biomes"]
    names = list(bio["palette"])
    idx = unpack_indices(bio["data"].values, 1, 64).reshape(4, 4, 4)
    assert names[idx[0, 0, 0]] == "minecraft:ocean" and names[idx[3, 3, 3]] == "minecraft:plains"

    # an independent NBT reader agrees on the structure
    f = nbtlib.File.parse(io.BytesIO(raw))
    assert len(f["sections"]) == 24 and int(f["DataVersion"]) == b.data_version


def test_build_many_equals_single():
    b = ChunkBuilder(-320, 384)
    rng = np.random.default_rng(3)
    K = 5
    top = rng.integers(-300, 380, (K, 16, 16)).astype(np.int32)
    water = np.where(rng.random((K, 16, 16)) < 0.3, top + rng.integers(1, 20, (K, 16, 16)), -400).astype(np.int32)
    surf = rng.integers(0, len(bk.SURFACES), (K, 16, 16)).astype(np.uint8)
    biome = rng.integers(0, len(bk.BIOMES), (K, 16, 16)).astype(np.uint8)
    many = b.build_many([(i, -i) for i in range(K)], top, surf, water, biome)
    for i in range(K):
        assert many[i] == b.build(i, -i, top[i], surf[i], water[i], biome[i])
        chunk = nbt.decode(many[i])[1]
        # spot-check one column against the layer rules
        x, z = 7, 9
        col = column_blocks(chunk, x, z)
        t = int(np.clip(top[i, z, x], -319, 383))
        assert col[t] == bk.BLOCK_NAMES[bk.SURF_TOP[surf[i, z, x]]]
        if water[i, z, x] > t:
            assert col[t + 1] == "minecraft:water"
        assert col[-320] == "minecraft:bedrock"


def test_region_roundtrip(tmp_path):
    b = ChunkBuilder(-64, 320)
    top, surf, water, biome = _columns()
    chunks = {(0, 0): b.build(0, 0, top, surf, water, biome), (31, 5): b.build(31, 5, top, surf, water, biome)}
    p = os.path.join(tmp_path, "r.0.0.mca")
    size = write_region(p, chunks)
    assert os.path.getsize(p) == size and size % 4096 == 0
    back = read_region(p)
    assert set(back) == {(0, 0), (31, 5)}
    assert back[(31, 5)]["xPos"] == 31
