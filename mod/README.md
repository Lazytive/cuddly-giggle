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

## Data

The first launch writes `config/alosearth.json` and creates the folders
below. Put the data in them; relative paths are resolved against the game or
server folder.

| folder | what | needed? |
|---|---|---|
| `alosearth-data/aw3d30/` | AW3D30 zip bundles as downloaded from JAXA (or extracted `*_DSM.tif` + `*_MSK.tif`) | yes; without it everything is ocean |
| `alosearth-data/gebco/` | GEBCO global grid GeoTIFFs | recommended: sea floor, poles, gaps |
| `alosearth-data/fill/` | any lon/lat GeoTIFF DEM tiles (e.g. Copernicus GLO‑30) | optional: fills AW3D30 gaps |
| `alosearth-data/climate/` | Köppen‑Geiger 1 km GeoTIFF (Beck et al.) | optional: deserts, jungles, taiga… |

* Data is read on demand, with a cache of recently used tiles (`cache_mb`
  in the config), so only the areas people visit are ever loaded.
* Put it on an SSD if you can: the first visit to an area reads it from
  disk.

## In game

* **Commands:**
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
