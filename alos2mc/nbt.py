"""Minimal NBT (Named Binary Tag) encoder/decoder.

Only what is needed to write Minecraft Java chunks and level.dat, plus a
reader used by the preview renderer and the tests.  Python values map to
tags like this when encoding:

    dict            -> TAG_Compound
    str             -> TAG_String
    bool            -> TAG_Byte
    int             -> TAG_Int (use Byte/Short/Long wrappers for others)
    float           -> TAG_Double (use Float wrapper for TAG_Float)
    List(type, xs)  -> TAG_List with an explicit element type
    numpy int64 arr -> TAG_Long_Array (via LongArray)
"""

from __future__ import annotations

import gzip
import struct

import numpy as np

END, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, BYTE_ARRAY, STRING, LIST, COMPOUND, INT_ARRAY, LONG_ARRAY = range(13)


class Byte(int):
    pass


class Short(int):
    pass


class Long(int):
    pass


class Float(float):
    pass


class ByteArray(bytes):
    pass


class IntArray:
    def __init__(self, values):
        self.values = np.asarray(values, dtype=">i4")


class LongArray:
    def __init__(self, values):
        self.values = np.asarray(values, dtype=">i8")


class List(list):
    """TAG_List with an explicit element tag type (needed for empty lists)."""

    def __init__(self, tag_type: int, items=()):
        super().__init__(items)
        self.tag_type = tag_type


def _tag_type(v) -> int:
    if isinstance(v, Byte) or isinstance(v, bool):
        return BYTE
    if isinstance(v, Short):
        return SHORT
    if isinstance(v, Long):
        return LONG
    if isinstance(v, int):
        return INT
    if isinstance(v, Float):
        return FLOAT
    if isinstance(v, float):
        return DOUBLE
    if isinstance(v, ByteArray):
        return BYTE_ARRAY
    if isinstance(v, str):
        return STRING
    if isinstance(v, List):
        return LIST
    if isinstance(v, dict):
        return COMPOUND
    if isinstance(v, IntArray):
        return INT_ARRAY
    if isinstance(v, LongArray):
        return LONG_ARRAY
    if isinstance(v, list):
        raise TypeError("use nbt.List(tag_type, items) for lists")
    raise TypeError(f"cannot encode {type(v)!r} as NBT")


def _write_str(out: list, s: str) -> None:
    b = s.encode("utf-8")  # ASCII-only in practice; modified UTF-8 differs only for NUL/astral chars
    out.append(struct.pack(">H", len(b)))
    out.append(b)


def _write_payload(out: list, t: int, v) -> None:
    if t == BYTE:
        out.append(struct.pack(">b", int(v)))
    elif t == SHORT:
        out.append(struct.pack(">h", v))
    elif t == INT:
        out.append(struct.pack(">i", v))
    elif t == LONG:
        out.append(struct.pack(">q", v))
    elif t == FLOAT:
        out.append(struct.pack(">f", v))
    elif t == DOUBLE:
        out.append(struct.pack(">d", v))
    elif t == BYTE_ARRAY:
        out.append(struct.pack(">i", len(v)))
        out.append(bytes(v))
    elif t == STRING:
        _write_str(out, v)
    elif t == LIST:
        et = v.tag_type
        out.append(struct.pack(">bi", et, len(v)))
        for item in v:
            _write_payload(out, et, item)
    elif t == COMPOUND:
        for k, item in v.items():
            it = _tag_type(item)
            out.append(struct.pack(">b", it))
            _write_str(out, k)
            _write_payload(out, it, item)
        out.append(b"\x00")
    elif t == INT_ARRAY:
        out.append(struct.pack(">i", len(v.values)))
        out.append(v.values.tobytes())
    elif t == LONG_ARRAY:
        out.append(struct.pack(">i", len(v.values)))
        out.append(v.values.tobytes())
    else:
        raise ValueError(t)


def encode(root: dict, name: str = "") -> bytes:
    out: list = [struct.pack(">b", COMPOUND)]
    _write_str(out, name)
    _write_payload(out, COMPOUND, root)
    return b"".join(out)


def write_gzip_file(path, root: dict) -> None:
    with gzip.open(path, "wb") as f:
        f.write(encode(root))


# ---------------------------------------------------------------- decoding


class _Reader:
    def __init__(self, data: bytes):
        self.buf = memoryview(data)
        self.pos = 0

    def take(self, n: int) -> memoryview:
        b = self.buf[self.pos:self.pos + n]
        if len(b) != n:
            raise EOFError("truncated NBT")
        self.pos += n
        return b

    def unpack(self, fmt: str):
        size = struct.calcsize(fmt)
        return struct.unpack(fmt, self.take(size))

    def string(self) -> str:
        (n,) = self.unpack(">H")
        return bytes(self.take(n)).decode("utf-8", "replace")

    def payload(self, t: int):
        if t == BYTE:
            return Byte(self.unpack(">b")[0])
        if t == SHORT:
            return Short(self.unpack(">h")[0])
        if t == INT:
            return self.unpack(">i")[0]
        if t == LONG:
            return Long(self.unpack(">q")[0])
        if t == FLOAT:
            return Float(self.unpack(">f")[0])
        if t == DOUBLE:
            return self.unpack(">d")[0]
        if t == BYTE_ARRAY:
            (n,) = self.unpack(">i")
            return ByteArray(bytes(self.take(n)))
        if t == STRING:
            return self.string()
        if t == LIST:
            et, n = self.unpack(">bi")
            return List(et, [self.payload(et) for _ in range(n)])
        if t == COMPOUND:
            d = {}
            while True:
                (it,) = self.unpack(">b")
                if it == END:
                    return d
                k = self.string()
                d[k] = self.payload(it)
        if t == INT_ARRAY:
            (n,) = self.unpack(">i")
            return IntArray(np.frombuffer(self.take(4 * n), dtype=">i4"))
        if t == LONG_ARRAY:
            (n,) = self.unpack(">i")
            return LongArray(np.frombuffer(self.take(8 * n), dtype=">i8"))
        raise ValueError(f"bad tag type {t}")


def decode(data: bytes) -> tuple[str, dict]:
    r = _Reader(data)
    (t,) = r.unpack(">b")
    if t != COMPOUND:
        raise ValueError("root tag is not a compound")
    name = r.string()
    return name, r.payload(COMPOUND)


def read_gzip_file(path) -> dict:
    with gzip.open(path, "rb") as f:
        return decode(f.read())[1]


__all__ = [
    "Byte", "Short", "Long", "Float", "ByteArray", "IntArray", "LongArray", "List",
    "encode", "decode", "write_gzip_file", "read_gzip_file",
]
