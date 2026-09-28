"""Builds the climate map bundled with the mod (used when no Koppen GeoTIFF
is installed).

Source: the Koppen-Geiger map of Rubel et al. (koeppen-geiger.vu-wien.ac.at,
Rubel, Brugger, Haslinger & Auer 2017, doi:10.1127/metz/2016/0816) at
100 arc-seconds (~3 km), as shipped in the BSD-licensed `kgcpy` package
(https://github.com/cwru-sdle/kgcpy). It is stored run-length encoded per
row and renumbered to the Beck et al. class codes the terrain code uses
(0 = ocean).

Usage: pip download --no-deps kgcpy && unzip kgcpy-*.whl
       python make_climate.py kgcpy/kmz_int_reshape.png
"""

import gzip
import struct
import sys

import numpy as np
from PIL import Image

Image.MAX_IMAGE_PIXELS = None

RUBEL = "x Af Am As Aw BSh BSk BWh BWk Cfa Cfb Cfc Csa Csb Csc Cwa Cwb Cwc Dfa Dfb Dfc Dfd Dsa Dsb Dsc Dsd Dwa Dwb Dwc Dwd EF ET Ocean".split()
BECK = ("x Af Am Aw BWh BWk BSh BSk Csa Csb Csc Cwa Cwb Cwc Cfa Cfb Cfc Dsa Dsb Dsc Dsd Dwa Dwb Dwc Dwd "
        "Dfa Dfb Dfc Dfd ET EF").split()


def main():
    src = sys.argv[1]
    out = sys.argv[2] if len(sys.argv) > 2 else "src/main/resources/alosearth/koppen.rle.gz"
    a = np.array(Image.open(src))
    lut = np.zeros(256, dtype=np.uint8)
    for i, name in enumerate(RUBEL):
        if name in ("x", "Ocean"):
            continue
        lut[i] = BECK.index("Aw" if name == "As" else name)
    k = lut[a]
    h, w = k.shape
    parts = [b"KRLE", struct.pack(">ii", w, h)]
    for row in k:
        starts = np.flatnonzero(np.concatenate([[True], row[1:] != row[:-1]]))
        parts.append(struct.pack(">H", len(starts)))
        rec = np.zeros(len(starts), dtype=[("s", ">u2"), ("c", "u1")])
        rec["s"] = starts
        rec["c"] = row[starts]
        parts.append(rec.tobytes())
    with gzip.open(out, "wb", 9) as f:
        f.write(b"".join(parts))
    print(f"wrote {out}: {w}x{h}")


if __name__ == "__main__":
    main()
