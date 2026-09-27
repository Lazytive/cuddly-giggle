# cuddly-giggle — the whole Earth in Minecraft at 1:30

This repository has two ways to play the Earth from JAXA's AW3D30 data:

* **[`mod/`](mod/README.md) — ALOS Earth, a Fabric 1.21.1 mod (beta, the
  main project).** It adds a world type that generates the globe on the fly
  as you explore, with "Minecraft‑like" exaggerated relief, varied biomes,
  vanilla ores, trees and structures, and no caves. Nothing is
  pre‑generated, so there are no terabytes to build.
* **`alos2mc/` — a Python pre‑generator** (described below) that writes a
  vanilla world with true‑scale terrain. It is also the reference the mod's
  projection and data readers are tested against. Its terrain rules are the
  older, realistic ones; they don't yet match the mod's style.

`alos2mc` turns JAXA's **ALOS World 3D – 30 m (AW3D30)** global elevation
model into a **Minecraft Java Edition** world at **1 block ≈ 30 m**,
horizontally and vertically. AW3D30 has one sample every 1 arc‑second
(≈ 30 m), so each block sits on about one DEM pixel.

The world is a **globe**, not a flat map with edges. You can walk or fly in
any direction, across the poles and around the planet, and arrive where you
would on the real Earth. How that works is explained below.

```
            +-------+
            | north |           each face: 333,824 x 333,824 blocks
    +-------+-------+-------+-------+
    | west  | front | east  | back  |   front is centred on --center
    +-------+-------+-------+-------+   (default 0°N 24°E)
            | south |
            +-------+
```

## How the globe works

Minecraft worlds are flat grids. A sphere can't be laid flat without cuts,
so the Earth is projected onto the six faces of a cube (an *equi‑angular
cubed sphere*) and the cube is unfolded into the cross above.

* **5 of the cube's 12 edges touch in the cross.** They are ordinary
  continuous terrain.
* **The other 7 edges are *seams*.** Two examples: going north off the `west`
  face continues on the left edge of the `north` face; going east off `back`
  wraps round to `west`. Each seam is a rigid block‑grid transform (a shift
  plus a 0/90/180° turn). There are no half‑blocks or resampling at a seam.
* **A vanilla datapack** (generated into the world) teleports you across a
  seam the moment you step past it. It turns you so you keep facing the same
  real‑world direction, and keeps your height.
* **Seam margins:** beyond every seam the generator writes a 512‑block strip
  that is a *block‑for‑block copy* of the terrain on the other side, so what
  you see across a seam is what you find after crossing. The tests check this
  copy block by block.
* Near the 8 cube corners the strips of two seams meet. The seam you are
  closest to wins, in both the terrain and the teleport.

Every point on Earth appears exactly once, including both poles. The price
is distortion: scale ranges from **20 to 35 m per block**, and shapes are
sheared by up to ~30° near the cube corners. There is no distortion at face
centres. The default `--center 0,24` minimises shear over the world's 50
largest cities (mean ≈ 12°).

To make a region of your choice undistorted, centre the cube on it. Check
any place first:

```
python -m alos2mc distortion 35.68 139.69                   # Tokyo, default cube: 23-31 m/block, 18° shear
python -m alos2mc distortion 35.68 139.69 --center 36,138   # Japan-centred cube: 30 m/block, 0° shear
```

`--projection equirect` gives a plain latitude/longitude grid instead. It
wraps east–west, but the poles are stretched lines you cannot cross, and scale
is badly distorted at high latitudes.

## Data you need to download

| Dataset | Required? | What it's used for |
|---|---|---|
| **AW3D30** DSM + MSK tiles, from [JAXA EORC](https://www.eorc.jaxa.jp/ALOS/en/index_e.htm) (free registration) | yes | land elevation; sea and lake mask |
| **GEBCO** global grid (GeoTIFF version, from gebco.net) | strongly recommended | sea floor; land where AW3D30 has no tiles (beyond ~82°N/S, gaps) |
| **Köppen‑Geiger** 1 km climate map (Beck et al., GeoTIFF) | optional | biomes and ground cover: deserts, jungle, taiga, tundra, ice caps |

* **AW3D30:** keep the downloaded zip bundles as they are. `alos2mc` finds
  `…_DSM.tif` / `…_MSK.tif` tiles inside zips or folders, in any directory
  layout. You can pass `--dem` several times.
* **GEBCO:** without it, open ocean is a flat 10‑block‑deep sea, and
  everything beyond AW3D30's coverage is ocean. That includes Antarctica's
  interior and the far north of Greenland.
* **Köppen:** without it, ground cover falls back to latitude bands. Snow
  lines, bare rock on steep slopes, beaches and lakes/rivers are derived from
  the terrain either way.

Any lon/lat GeoTIFF works as `--bathymetry` or `--climate`. Large files are
read in windows, so they are never loaded whole.

## Usage

```bash
pip install -e .            # needs Python 3.10+, numpy, tifffile, imagecodecs

# 1. create the world folder (config, level.dat, datapack)
python -m alos2mc create worlds/earth \
    --dem /data/aw3d30 --bathymetry /data/gebco --climate /data/koppen_1km.tif \
    --spawn 35.36,138.73                       # e.g. Mt. Fuji

# 2. see what an area needs, and which AW3D30 tiles are missing
python -m alos2mc plan worlds/earth --bbox 30,128,46,146 --list-missing

# 3. generate it (parallel, resumable, can be run again for more areas)
python -m alos2mc generate worlds/earth --bbox 30,128,46,146 --workers 16

# 4. look at it
python -m alos2mc preview worlds/earth --bbox 35,138,36,140 -o fuji.png
python -m alos2mc locate  worlds/earth 27.988 86.925      # -> /tp @s x y z for Everest
python -m alos2mc whereis worlds/earth 12345 -67890        # -> lat/lon

# the whole planet: leave out --bbox
python -m alos2mc generate worlds/earth --workers 32
```

`--bbox` is `SOUTH,WEST,NORTH,EAST` in degrees (use west > east to cross the
antimeridian). Regions already written are skipped, so generating Japan
today and Asia tomorrow just adds to the world. It also writes the seam
margins that show the requested area. Copy the world folder into
`.minecraft/saves/` or use it as a server's `world`.

### Options worth knowing (`create`)

* `--center LAT,LON` — the least distorted point of the map (see above).
* `--scale 30` / `--vertical-scale 30` — metres per block. For example,
  `--vertical-scale 15` doubles relief.
* `--height-mode vanilla` (default) — uses the standard −64…320 height.
  Sea level is y = 20, so Everest (y ≈ 315) fits at true scale. Ocean depths
  are true‑scale down to about 1.2 km, then compressed to fit.
* `--height-mode extended` — adds a datapack `dimension_type` (y −320…384,
  sea level 63) so ocean depths are true‑scale too, down to the Mariana
  Trench. The JSON follows the 1.21.x format. Future versions may need it
  updated; vanilla mode needs no such file.
* `--margin 512` — how far the seam copies extend. Make it at least your view
  distance in blocks (32 chunks = 512 blocks).
* `--write-all-ocean` — by default, chunks that are plain open ocean are not
  written at all. The world's flat generator is set to produce exactly the
  same ocean column, so the game fills them identically for free.

## Size and time (whole planet)

Measured on synthetic test terrain: ~0.5 s per land region and ~0.12 s per
open‑ocean region, per CPU core (a region is 512×512 blocks). Real terrain
will differ somewhat, so treat these figures as estimates.

| | regions | CPU time | disk |
|---|---|---|---|
| land only (no GEBCO; open ocean left to the flat generator) | 2.56 M visited, ~0.9 M written | ~180 core‑hours | ~3–5 TB |
| with GEBCO sea floor (every chunk written) | 2.56 M | ~350 core‑hours | ~10–11 TB |

* **Disk is dominated by Minecraft's region format**, not the data. A
  compressed chunk here is ~0.6–1.5 KiB, but every chunk occupies at least
  one 4 KiB sector. A filesystem with transparent compression (btrfs `zstd`,
  ZFS `lz4`) removes most of that padding.
* Everything is resumable: stop at any time and run the same command again.

Start with one area, check it in game, then scale up.

## What you get in game

* **Terrain:** elevation from the DSM. This is a *surface* model, so forests
  and cities appear as terrain bumps. There are no trees, buildings or roads.
* **Layers under the surface:** bedrock → deepslate (below y = 0) → stone →
  a few blocks of dirt/sand (packed ice under ice caps) → the surface block.
* **Surface blocks:** grass, sand, coarse dirt or podzol (by climate), snow
  above the snow line, stone on steep slopes, sand beaches, sand/gravel sea
  floor, clay lake beds.
* **Biomes:** from climate (jungle, savanna, desert, forest, taiga, snowy
  plains…); frozen peaks, stony peaks and beaches from terrain; ocean biomes
  by latitude.
* **Seams:** the vanilla datapack teleports *players* across seams. For a
  player they are invisible, apart from a brief chunk‑loading moment and your
  compass/F3 heading changing. Mobs, dropped items and vehicles are not
  carried across; they wander into the seam margin (the copy) instead. A
  player riding something may be dismounted when teleported. At a rotated
  seam an elytra flight keeps its old momentum direction for a moment.

### Mods (optional)

The world is vanilla. Nothing is required, but these help:

* **Distant Horizons** — renders far terrain (mountain ranges hundreds of km
  away), which suits a 1:30 Earth very well.
* **Seamless seams:** a portal mod that can render through a rotated portal
  (e.g. *Immersive Portals*) could replace the teleport with a see‑through
  joint that also carries entities. Every seam is described in
  `alos2mc_links.json` in the world folder: the strip each seam covers, its
  2×2 rotation matrix, translation and yaw change. So a mod or script can
  build those portals.

## Compatibility and testing notes

* Chunks and `level.dat` are written in the Minecraft **1.21.1** format
  (DataVersion 3955), so the world needs **Java Edition 1.21.1 or newer**.
  Newer versions upgrade the chunks automatically when the world is opened.
  The datapack uses function macros and `return run`.
* This build environment has no access to Mojang's servers, so the output has
  **not been opened in the actual game here**. What *is* verified by the test
  suite (`pytest tests`):
  * the NBT and region‑file encoding, checked against an independent NBT
    library and against Minecraft's bit‑packing rules;
  * every block column in the generated chunks;
  * projection round‑trips, face coverage and scale everywhere on the globe;
  * that seam margins are exact copies of the other side;
  * the datapack itself, executed through an interpreter for the commands it
    uses: every seam lands you on the matching spot, you never bounce back,
    and players who haven't crossed are never moved.

  Please try a small `--bbox` in the game first.
* The AW3D30 water mask is decoded from the MSK file's low two bits (2 = land
  water, 3 = sea), per JAXA's product description. If lakes or coasts look
  wrong on real data, that decoding is the first thing to check
  (`alos2mc/raster.py`).

## Code map

| file | what it does |
|---|---|
| `alos2mc/projection.py` | cube / equirect projections, seam links, margins |
| `alos2mc/raster.py` | AW3D30 tile index (folders + zips), GeoTIFF sampling |
| `alos2mc/terrain.py` | elevation/water/climate → block columns, surfaces, biomes |
| `alos2mc/anvil.py` | chunk NBT and `.mca` region files |
| `alos2mc/world.py` | `level.dat`, flat ocean generator, seam datapack |
| `alos2mc/generate.py` | region planning, parallel resumable generation |
| `alos2mc/cli.py` | command line |
