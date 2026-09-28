# ALOS Earth (Fabric mod, beta)

A **world type** that generates the whole Earth at about **1 block ≈ 30 m**,
on the fly, from JAXA's ALOS AW3D30 elevation data. The world is a **globe**:
walk or fly in any direction, across the poles or around the planet, and you
arrive where you would on the real Earth.

* **Terrain:** land is "Minecraft‑like" rather than strictly to scale.
  * Lowland relief is exaggerated about 3×.
  * Mountains are compressed so Everest peaks near **y 600**.
  * Minecraft‑style bumps and ridges are added on top of the real shape.
  * The world runs from y −128 to 640; sea level is 63.
* **Biomes:**
  * Climate zones (from a Köppen map, or by latitude) are mixed with natural
    variety in patches.
  * Mountains get meadows, groves, snowy slopes and peaks.
  * Coasts get beaches, stony shores, swamps and mangroves.
  * Rivers are widened to boat size.
* **Underground:** solid rock with no caves. Ores, trees, plants, villages
  and other structures, and mobs are all vanilla.

## Install

* **Needs:** Minecraft **1.21.1**, Fabric Loader, and Fabric API.
* **Where the mod goes:** drop `alos-earth-<version>.jar` into `mods/`.
* **Servers:** the mod only has to be installed on the server; players
  can join with an unmodded client.
* **Single player:** choose **World Type: ALOS Earth** when creating the
  world.
* **Dedicated server:** set `level-type=alosearth\:earth` in
  `server.properties` before the world is first created.

### Automatic updates (Prism Launcher)

Every build that passes CI is published as the
[`alos-earth-beta` release](https://github.com/Lazytive/cuddly-giggle/releases/tag/alos-earth-beta),
always at the same link:
<https://github.com/Lazytive/cuddly-giggle/releases/download/alos-earth-beta/alos-earth.jar>

To have Prism install and update it on every launch:

1. Open **Edit instance → Settings → Custom commands**.
2. Tick the box to enable custom commands.
3. Paste this as the **Pre‑launch command**:

```
powershell -NoProfile -ExecutionPolicy Bypass -Command "try { [Net.ServicePointManager]::SecurityProtocol='Tls12'; & ([scriptblock]::Create((New-Object Net.WebClient).DownloadString('https://github.com/Lazytive/cuddly-giggle/releases/download/alos-earth-beta/update-alos-earth.ps1'))) } catch { Write-Host 'ALOS Earth update skipped' }"
```

On each launch it fetches [`tools/update-alos-earth.ps1`](tools/update-alos-earth.ps1)
from the release and runs it. The script puts the newest `alos-earth.jar` into
the instance's `mods` folder, removing older copies. If there's no internet
it leaves the current jar alone and the game starts normally.

## Data

**It works out of the box.** Where no elevation data is installed, the mod
downloads the free **Copernicus GLO‑30** 30 m elevation model one 1°×1° tile
at a time (about 40 MB each) the first time an area generates, and caches it
in `alosearth-data/auto/`.

* **First visit to an area:** there's a short pause while its tile
  downloads.
* **Open ocean:** it has no tiles, and that is remembered so nothing is
  re‑fetched.
* **Offline:** unreachable areas become ocean; the mod tries again later.

Install your own data to override it. The first launch writes
`config/alosearth.json` and creates these folders; relative paths are
resolved against the game or server folder:

| folder | what | needed? |
|---|---|---|
| `alosearth-data/aw3d30/` | JAXA ALOS AW3D30 zip bundles as downloaded (or extracted `*_DSM.tif` + `*_MSK.tif`) | optional; **preferred** over the download, and adds lakes and rivers from its water mask |
| `alosearth-data/gebco/` | GEBCO global grid GeoTIFFs | recommended: real sea floor and poles |
| `alosearth-data/fill/` | any lon/lat GeoTIFF DEM tiles | optional: fills gaps |
| `alosearth-data/climate/` | Köppen‑Geiger 1 km GeoTIFF (Beck et al.) | optional: deserts, jungles, taiga where they really are |

* **Order of use:** AW3D30 → fill → auto‑download → GEBCO → ocean.
* **Turning off downloads:** set `"auto_download": false` in the config.
* **Checking what's loaded:** run `/earth status`.

Auto‑downloaded terrain contains modified Copernicus Service information
(Copernicus DEM GLO‑30, © DLR e.V. 2010‑2014 and © Airbus Defence and Space
GmbH 2014‑2018, provided under COPERNICUS by the European Union and ESA).

## In game

* **Commands:**
  * `/earth status` shows which elevation data is in use.
  * `/earth goto <place>` or `/earth goto <lat> <lon>` teleports you
    (operators only). Built‑in places include `everest`, `fuji`,
    `mont_blanc`, `grand_canyon`, `london`, `north_pole` and more; press Tab
    for the list.
  * `/earth whereami` shows latitude/longitude, biome and local scale.
* **F3** shows your latitude/longitude.

### The globe and its seams

The Earth is projected onto the six faces of a cube, unfolded like this
(each face is 333,824 blocks across):

```
            north
    west  front  east  back
            south
```

* **Which edges are seams:** five edges touch in this layout. The other
  seven are *seams*.
* **Crossing:** anything that crosses a seam (players, mobs, items, boats
  with riders) is moved to the matching spot on the other side. It's turned
  so it keeps its real‑world heading and keeps its momentum.
* **Looking across:** past each seam there is a 512‑block strip that is an
  exact copy of the terrain on the other side, so what you see ahead is
  what you'll find.
* **No loading pause:** chunks on the far side are loaded as you approach.

Known limits in the beta:
* Your coordinates jump when you cross a seam.
* Blocks built or dug right at a seam aren't mirrored into the copy on the
  other side.
* Trees near a seam aren't copied exactly.
* Nothing renders through a seam; for that, see the Immersive Portals plan in
  the main README.

## Building

```bash
./gradlew build        # needs JDK 21; the jar lands in build/libs/
```

The terrain core (`io.github.lazytive.alosearth.core`) is plain Java.
`scripts/core-test.sh` runs its tests with only a JDK, after
`python scripts/make_testdata.py` has made the fixtures using the Python
reference implementation in the repository root.

Preview any area as a PNG without starting Minecraft:

```bash
java -cp build/libs/alos-earth-*.jar io.github.lazytive.alosearth.core.Preview \
    --aw3d30 alosearth-data/aw3d30 --bbox 35.2,138.5,35.6,139.0 --step 2 -o fuji.png
```

CI (`.github/workflows/mod.yml`) builds the mod, runs the core tests, then
starts a real dedicated server with an ALOS Earth world and runs an in‑game
self‑test. The self‑test checks generated blocks, biomes, ores, every seam
crossing and the commands.
