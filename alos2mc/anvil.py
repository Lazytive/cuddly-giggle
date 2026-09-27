"""Minecraft Java Edition chunk encoding and Anvil (.mca) region files.

Chunks are written in the 1.18+ format (``sections`` with paletted
``block_states`` and ``biomes``).  Heightmaps are omitted and ``isLightOn``
is 0 so the game computes both when a chunk is first loaded, which keeps
the writer simple and always consistent with the blocks.

Chunk NBT is assembled directly as bytes from pre-encoded pieces; this is
several times faster than building Python dicts and encoding them, and the
tests decode the output with an independent NBT library to check it.
"""

from __future__ import annotations

import gzip
import os
import struct
import time
import zlib

import numpy as np

from . import nbt
from .blocks import AIR, BEDROCK, BIOMES, BLOCKS, DEEPSLATE, STONE, SURF_DEPTH, SURF_SUB, SURF_TOP, WATER

# 1.21.1.  Newer game versions upgrade these chunks automatically on load.
DEFAULT_DATA_VERSION = 3955


def pack_indices(indices: np.ndarray, bits: int) -> np.ndarray:
    """Pack palette indices into longs the way PalettedContainer does since
    1.16 (entries never straddle two longs).  Returns big-endian int64."""
    vpl = 64 // bits
    n = indices.size
    nlongs = -(-n // vpl)
    buf = np.zeros(nlongs * vpl, dtype=np.uint64)
    buf[:n] = indices.ravel()
    buf = buf.reshape(nlongs, vpl) << (np.arange(vpl, dtype=np.uint64) * np.uint64(bits))
    return np.bitwise_or.reduce(buf, axis=1).view(np.int64).astype(">i8")


def unpack_indices(longs: np.ndarray, bits: int, n: int) -> np.ndarray:
    vpl = 64 // bits
    v = np.asarray(longs).astype("<i8").view(np.uint64)
    shifts = np.arange(vpl, dtype=np.uint64) * np.uint64(bits)
    mask = np.uint64((1 << bits) - 1)
    out = (v[:, None] >> shifts[None, :]) & mask
    return out.ravel()[:n].astype(np.int64)


def _tag(t: int, name: str) -> bytes:
    b = name.encode()
    return struct.pack(">bH", t, len(b)) + b


def _compound_payload(d: dict) -> bytes:
    """Payload bytes of a compound (its entries and TAG_End)."""
    return nbt.encode(d)[3:]  # skip TAG_Compound + empty name


_BLOCK_PAYLOADS = [_compound_payload(b) for b in BLOCKS]
_BIOME_PAYLOADS = [struct.pack(">H", len(n)) + n.encode() for n in BIOMES]
_BS = _tag(nbt.COMPOUND, "block_states")
_BIO = _tag(nbt.COMPOUND, "biomes")
_Y = _tag(nbt.BYTE, "Y")


def _palette_bytes(values: np.ndarray, nvalues: int, payloads, elem_type: int, min_bits: int) -> bytes:
    """Payload of a paletted container compound (``palette``, ``data`` if
    needed, TAG_End)."""
    flat = values.ravel()
    counts = np.bincount(flat, minlength=nvalues)
    uniq = np.flatnonzero(counts)
    out = [_tag(nbt.LIST, "palette"), struct.pack(">bi", elem_type, len(uniq))]
    out += [payloads[u] for u in uniq]
    if len(uniq) > 1:
        lookup = np.zeros(nvalues, dtype=np.uint64)
        lookup[uniq] = np.arange(len(uniq), dtype=np.uint64)
        bits = max(min_bits, int(len(uniq) - 1).bit_length())
        longs = pack_indices(lookup[flat], bits)
        out += [_tag(nbt.LONG_ARRAY, "data"), struct.pack(">i", len(longs)), longs.tobytes()]
    out.append(b"\0")
    return b"".join(out)


class ChunkBuilder:
    def __init__(self, min_y: int, max_y: int, data_version: int = DEFAULT_DATA_VERSION):
        if min_y % 16 or max_y % 16:
            raise ValueError("world height limits must be multiples of 16")
        self.min_y = min_y
        self.max_y = max_y
        self.data_version = data_version
        self._ys = np.arange(16, dtype=np.int32)[:, None, None]  # (y, z, x) broadcast
        self._uniform: dict[int, bytes] = {}
        self._prefix: dict[tuple, bytes] = {}
        self._biome_cache: dict[bytes, bytes] = {}
        # constant parts of the chunk compound, around the variable fields
        self._head = struct.pack(">bH", nbt.COMPOUND, 0) + _tag(nbt.INT, "DataVersion")
        self._xpos = _tag(nbt.INT, "xPos")
        self._zpos = _tag(nbt.INT, "zPos")
        self._sections = _tag(nbt.LIST, "sections")
        self._tail = _compound_payload({
            "yPos": min_y >> 4,
            "Status": "minecraft:full",
            "LastUpdate": nbt.Long(0),
            "InhabitedTime": nbt.Long(0),
            "isLightOn": nbt.Byte(0),
            "Heightmaps": {},
            "block_entities": nbt.List(nbt.COMPOUND),
            "block_ticks": nbt.List(nbt.COMPOUND),
            "fluid_ticks": nbt.List(nbt.COMPOUND),
            "PostProcessing": nbt.List(nbt.LIST),
            "structures": {"References": {}, "starts": {}},
        })

    def _uniform_section(self, block: int) -> bytes:
        cached = self._uniform.get(block)
        if cached is None:
            cached = _BS + _palette_bytes(np.array([block], dtype=np.uint8), len(BLOCKS), _BLOCK_PAYLOADS,
                                          nbt.COMPOUND, 4)
            self._uniform[block] = cached
        return cached

    def _palette_prefix(self, present: tuple) -> bytes:
        cached = self._prefix.get(present)
        if cached is None:
            cached = (_BS + _tag(nbt.LIST, "palette") + struct.pack(">bi", nbt.COMPOUND, len(present))
                      + b"".join(_BLOCK_PAYLOADS[i] for i in present)
                      + _tag(nbt.LONG_ARRAY, "data") + struct.pack(">i", 256))
            self._prefix[present] = cached
        return cached

    def _biomes(self, cells: np.ndarray) -> bytes:
        key = cells.tobytes()
        cached = self._biome_cache.get(key)
        if cached is None:
            cached = _BIO + _palette_bytes(np.broadcast_to(cells, (4, 4, 4)), len(BIOMES), _BIOME_PAYLOADS,
                                           nbt.STRING, 0)
            if len(self._biome_cache) < 4096:
                self._biome_cache[key] = cached
        return cached

    def section_blocks(self, y0: int, top, sub_top, sub_blk, top_blk, water):
        """Block ids (K,16,16,16) indexed [k][y][z][x] for the section at y0
        of K chunks whose column arrays are (K,16,16)."""
        y = self._ys[None] + y0
        top, sub_top, sub_blk, top_blk, water = (a[:, None] for a in (top, sub_top, sub_blk, top_blk, water))
        ids = np.where(y <= water, np.uint8(WATER), np.uint8(AIR))
        ids = np.where(y == top, top_blk, ids)
        ids = np.where(y < top, sub_blk, ids)
        if y0 >= 0:
            deep = np.uint8(STONE)
        elif y0 + 16 <= 0:
            deep = np.uint8(DEEPSLATE)
        else:
            deep = np.where(y < 0, np.uint8(DEEPSLATE), np.uint8(STONE))
        ids = np.where(y < sub_top, deep, ids)
        if y0 == self.min_y:
            ids[:, 0] = BEDROCK
        return ids

    def build_many(self, coords, top, surf, water, biome) -> list[bytes]:
        """Uncompressed chunk NBT for K chunks.  ``coords`` are (cx, cz);
        column arrays are (K,16,16) indexed [k][z][x]."""
        K = len(coords)
        top = np.clip(top.astype(np.int32), self.min_y + 1, self.max_y - 1)
        water = np.minimum(water.astype(np.int32), self.max_y - 1)
        top_blk = SURF_TOP[surf]
        sub_blk = SURF_SUB[surf]
        sub_top = top - SURF_DEPTH[surf]  # lowest y of sub-surface material
        hmax = np.maximum(top.max(axis=(1, 2)), water.max(axis=(1, 2)))
        smin = sub_top.min(axis=(1, 2))
        nb = len(BLOCKS)

        sections: list[list[bytes]] = [[] for _ in range(K)]
        for y0 in range(self.min_y, self.max_y, 16):
            y1 = y0 + 15
            air = y0 > hmax
            deep_ok = (y1 < smin) & (y0 != self.min_y) & ((y0 >= 0) | (y1 < 0))
            todo = np.flatnonzero(~air & ~deep_ok)
            ybyte = _Y + struct.pack(">b", y0 >> 4)
            air_b = self._uniform_section(AIR)
            deep_b = self._uniform_section(STONE if y0 >= 0 else DEEPSLATE)
            out = [None] * K
            for k in np.flatnonzero(air):
                out[k] = air_b
            for k in np.flatnonzero(deep_ok & ~air):
                out[k] = deep_b
            if todo.size:
                ids = self.section_blocks(y0, top[todo], sub_top[todo], sub_blk[todo], top_blk[todo],
                                          water[todo]).reshape(todo.size, 4096)
                flat = ids + (np.arange(todo.size, dtype=np.int32)[:, None] * nb)
                counts = np.bincount(flat.ravel(), minlength=todo.size * nb).reshape(todo.size, nb)
                present = counts > 0
                npres = present.sum(axis=1)
                rank = (np.cumsum(present, axis=1) - 1).astype(np.uint8).ravel()
                inv = rank[flat]  # palette index of every block, (n, 4096)
                # 4 bits per entry, 16 per long, entry i at bits 4i..4i+3:
                # little-endian byte j holds entries 2j (low) and 2j+1 (high)
                packed = inv[:, 0::2] | (inv[:, 1::2] << 4)
                longs = packed.reshape(todo.size, 256, 8)[:, :, ::-1]  # to big-endian longs
                for j, k in enumerate(todo):
                    if npres[j] == 1:
                        out[k] = self._uniform_section(int(ids[j, 0]))
                    else:
                        pres = tuple(np.flatnonzero(present[j]).tolist())
                        out[k] = self._palette_prefix(pres) + longs[j].tobytes() + b"\0"
            for k in range(K):
                sections[k].append(ybyte)
                sections[k].append(out[k])

        nsec = (self.max_y - self.min_y) // 16
        result = []
        for k, (cx, cz) in enumerate(coords):
            biomes = self._biomes(np.ascontiguousarray(biome[k, 2::4, 2::4]))
            parts = [self._head, struct.pack(">i", self.data_version),
                     self._xpos, struct.pack(">i", cx), self._zpos, struct.pack(">i", cz),
                     self._sections, struct.pack(">bi", nbt.COMPOUND, nsec)]
            secs = sections[k]
            for i in range(0, len(secs), 2):
                parts += [secs[i], secs[i + 1], biomes, b"\0"]
            parts.append(self._tail)
            result.append(b"".join(parts))
        return result

    def build(self, cx: int, cz: int, top, surf, water, biome) -> bytes:
        """Uncompressed chunk NBT for 16x16 column arrays indexed [z][x]."""
        return self.build_many([(cx, cz)], top[None], surf[None], water[None], biome[None])[0]

    def build_nbt(self, cx: int, cz: int, top, surf, water, biome) -> dict:
        return nbt.decode(self.build(cx, cz, top, surf, water, biome))[1]


def write_region(path: str, chunks: dict[tuple[int, int], bytes]) -> int:
    """Write an .mca file.  ``chunks`` maps local (x, z) in 0..31 to raw
    (uncompressed) chunk NBT.  Written atomically; returns bytes written."""
    locations = bytearray(4096)
    timestamps = bytearray(4096)
    body = []
    sector = 2
    now = int(time.time())
    for (lx, lz), raw in sorted(chunks.items(), key=lambda kv: (kv[0][1], kv[0][0])):
        data = zlib.compress(raw, 1)  # chunks are padded to 4 KiB sectors anyway
        payload = struct.pack(">IB", len(data) + 1, 2) + data
        nsect = -(-len(payload) // 4096)
        if nsect > 255:
            raise ValueError("chunk too large for region file")
        payload += b"\0" * (nsect * 4096 - len(payload))
        i = 4 * (lx + 32 * lz)
        locations[i:i + 4] = struct.pack(">I", (sector << 8) | nsect)
        timestamps[i:i + 4] = struct.pack(">I", now)
        body.append(payload)
        sector += nsect
    tmp = path + ".tmp"
    with open(tmp, "wb") as f:
        f.write(locations)
        f.write(timestamps)
        for b in body:
            f.write(b)
    os.replace(tmp, path)
    return sector * 4096


def read_region_raw(path: str) -> dict[tuple[int, int], bytes]:
    """Every chunk of an .mca file -> {(lx, lz): uncompressed NBT bytes}."""
    with open(path, "rb") as f:
        data = f.read()
    out = {}
    for i in range(1024):
        (loc,) = struct.unpack_from(">I", data, 4 * i)
        if not loc:
            continue
        off = (loc >> 8) * 4096
        length, comp = struct.unpack_from(">IB", data, off)
        raw = data[off + 5: off + 4 + length]
        if comp == 2:
            raw = zlib.decompress(raw)
        elif comp == 1:
            raw = gzip.decompress(raw)
        elif comp != 3:
            raise ValueError(f"unsupported chunk compression {comp}")
        out[(i % 32, i // 32)] = raw
    return out


def read_region(path: str) -> dict[tuple[int, int], dict]:
    return {k: nbt.decode(v)[1] for k, v in read_region_raw(path).items()}


def decode_section_blocks(section: dict) -> tuple[list[str], np.ndarray]:
    """-> (palette names, indices[4096]) for a section's block_states."""
    bs = section["block_states"]
    names = [p["Name"] for p in bs["palette"]]
    if "data" not in bs:
        return names, np.zeros(4096, dtype=np.int64)
    bits = max(4, (len(names) - 1).bit_length())
    return names, unpack_indices(bs["data"].values, bits, 4096)
