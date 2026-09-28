# ALOS Earth (Fabric mod, beta)

A **world type** that generates the whole Earth at about **1 block ≈ 30 m**
(or **1:1**, see below), on the fly, from JAXA's ALOS AW3D30 elevation data. The world is a **globe**:
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
* **Underground:** mostly solid rock with rare winding caves (see below).
  Ores, trees, plants, villages and other structures, and mobs are all
  vanilla.

### Minecraft feel (worlds made with beta.21 or later)

Real terrain is smooth and gentle, so new worlds add Minecraft‑style
character on top of the real shape. It's in every world type:
* **Rivers and streams:**
  * Channels are worked out from the elevation data: which way water flows
    and how much land drains through each point, down every valley.
  * They widen as they collect water and have banks sloping down to them.
  * They start below the snow line.
* **Cliffs and ledges:** steep slopes step down in flat ledges and short
  cliffs, and cliff edges sometimes overhang.
* **Rocks:** boulders (mossy in damp climates), andesite outcrops on
  hillsides, and dunes in sandy deserts.
* **Ground patches:** coarse dirt, moss, podzol and gravel, so the ground
  varies instead of being one block for kilometres.
* **More biomes:**
  * cherry groves in the hills of Japan, Korea and Taiwan;
  * mushroom fields on a few remote islands: Pitcairn, Tristan da Cunha,
    Saint Helena, Ascension, Clipperton and Easter Island;
  * meadows, groves, eroded badlands, windswept savanna and old growth
    birch forest.
* **Caves:** rare spaghetti tunnels within 64 blocks of the surface, away
  from water, occasionally opening at the surface. Lush caves lie under
  jungles and swamps, dripstone caves under dry land and mountains.
* **Snow by real altitude:** Minecraft normally cools every biome above y 80
  whatever the scale. Here it gets colder with real height above 1,000 m,
  so plains get snow from about 3 km up, taiga from about 1.3 km. The
  snow line and tree line follow the real ones (the Alps: snow line about
  2,900 m, tree line about 2,000 m).
* **Through the Earth:** there is no bedrock at the bottom of the world.
  Dig or fall out of the bottom (the lowest deep layer in 1:1 worlds) and
  you come out at the opposite point of the globe (latitude flipped,
  longitude plus 180°). You burst out of a small hole and get thrown up and
  forward, the way you were facing, so you land clear of it, with a few
  seconds of slow falling. Mobs and items go through too.
  Most land is opposite ocean, so expect to surface at sea.
* **Structures** (all worlds, including old ones, for newly generated
  chunks):
  * Placement checks see the real terrain's biomes, so villages don't land
    in rivers or the sea, and shipwrecks aren't left on land.
  * Surface buildings (villages, outposts, temples, witch huts, igloos,
    mansions) aren't placed on steep ground.
  * Ocean monuments, which vanilla always builds at y 39, are only placed
    where the sea floor is just below that, instead of hanging in deep
    water.
  * Shipwrecks need at least 12 blocks of water (8 all around) and ocean
    ruins at least 6 (4 around), so they sit fully under water instead of on
    beaches or in shallows. Beached shipwrecks still wash up on beaches.
  * Around buildings the ground is filled in or cut away, as vanilla does,
    so houses don't float above the ground or sit buried in a slope.

Worlds made with earlier betas keep generating exactly as before, so newly
explored chunks still match the old ones.

## Install

* **Needs:** Minecraft **1.21.1**, Fabric Loader, and Fabric API.
* **Where the mod goes:** drop `alos-earth-<version>.jar` into `mods/`.
* **Servers:** the mod only has to be installed on the server; players
  can join with an unmodded client.
* **Single player:** "Create New World" starts on **ALOS Earth (1:10)**,
  the recommended type: it's the closest to vanilla Minecraft's
  proportions. You can also pick one of the other types:
  * **ALOS Earth (1:10)** (the default): 1 block = 10 m, everything to
    scale, see [The 1:10 and 1:5 worlds](#the-110-and-15-worlds).
  * **ALOS Earth (Minecraft‑like):** the style described above.
  * **ALOS Earth (true 1:30):** the data's real proportions, 1 block = 30 m
    vertically as well. Hills are gentle, Everest peaks near y 358, and
    oceans reach down to y −304 (the world runs from y −320 to 384).
  * **ALOS Earth (1:5):** 1 block = 5 m, everything to scale, trenches
    included.
  * **ALOS Earth (max, 1:4.9):** the biggest Earth with real proportions that
    fits in Minecraft. Everest is at the top of the world and the deepest
    trench at the bottom.
  * **ALOS Earth (1:1):** 1 block = 1 m, see [The 1:1 world](#the-11-world).
* **Dedicated server:** set `level-type=alosearth\:earth` (Minecraft‑like),
  `level-type=alosearth\:earth_true_scale`, `level-type=alosearth\:earth_1to10`,
  `level-type=alosearth\:earth_1to5`, `level-type=alosearth\:earth_max` or
  `level-type=alosearth\:earth_1to1`
  in `server.properties` before the world is first created.

### The 1:10 and 1:5 worlds

Both are to scale across and up and down alike, so every mountain, valley
and ocean has its real shape. **1:10** (1 block = 10 m):
* **Heights:** Everest peaks near y 948, the Mariana Trench bottoms out near
  y −1036, and the world runs from y −1152 to 1088.
* **One world:** it's a single dimension with no squashing and no deep layers.
* **Size:** Fuji is about 4,000 blocks across and 377 blocks tall, and the
  Earth is 4 million blocks around.
* **Detail:** the data has a point every 3 blocks, smoothly interpolated with
  light detail added.

**1:5** (1 block = 5 m) fits the whole Earth, trenches included, in one
Minecraft world (y −2032 to 2031):
* **Heights:** sea level is y 161, Everest peaks near y 1931, and the
  Challenger Deep bottoms out just above bedrock.
* **Size:** Fuji is about 7,500 blocks across and 755 blocks tall, and the
  Earth is 8 million blocks around.
* **Detail:** the data has a point every 6 blocks.
* **Older 1:5 worlds:** worlds made with beta.18 keep sea level y 63, so only
  their trench bottoms (below 10.4 km) are trimmed.

**Max** (1 block = 4.9 m) is the biggest Earth with real proportions that
Minecraft can hold:
* **Heights:** sea level is y 206, the Challenger Deep is near y −2026 (just
  above bedrock) and Everest reaches about y 2012 (just under the build
  limit).

**Clouds:** vanilla Minecraft draws clouds at y 192, which in these worlds
would be close to sea level or even underwater. With the mod installed on
your game, the 1:5 and max worlds draw clouds at y 480, and 1:1 draws them at
y 1300, about 1.2–2 km above sea level. Players without the mod on a server
still see clouds at y 192.

### The 1:1 world

Everything is life size: a street is a few blocks wide, the Earth is
40 million blocks around (the map fits inside Minecraft's 30‑million‑block
world border).

* **Oceans are at their real depth.** Minecraft worlds can be at most 4,064
  blocks tall, so the sea carries on below the world's floor (y −2032)
  in three stacked *deep layer* dimensions, down to the Mariana Trench
  about 11 km below sea level. Sink (or dig) past about y −2000 and you move
  into the layer below at the same x and z. Swim back up past y 2000 in a
  deep layer and you return to the one above. Neighbouring layers share
  96 blocks of identical water and rock, so the join isn't visible. The deep
  is pitch dark, and structures don't generate there.
* **Land is 1:1 near sea level** and eases off with height so the highest
  peaks fit under the y 2031 build limit:

  | real height | block height above sea |
  |---|---|
  | 100 m | 94 |
  | 1,000 m | 638 |
  | 3,776 m (Fuji) | 1,358 |
  | 8,849 m (Everest) | 1,928 |

  Mountains higher than about 1 km are therefore squashed. Stacking layers
  upwards as well would let them be 1:1, but you would see the mountains cut
  off at the join (underwater you can't see far enough to notice).
* **Detail:** the data has one point every 30 m. The mod interpolates
  smoothly between the points and adds small bumps on top.
* `/earth whereami` and F3 show your real elevation or depth.

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
* **Open ocean:** it has no land tiles, and that is remembered so nothing is
  re‑fetched.
* **Sea floor:** new worlds also download ocean depths (about 250 KB per
  tile, 1 km detail) from the free
  [terrain tiles on AWS](https://registry.opendata.aws/terrain-tiles/),
  whose oceans come from ETOPO1 and GEBCO, into
  `alosearth-data/auto/seafloor/`. Worlds made with an earlier beta keep
  their shallow seas, so new chunks still match the ones already
  generated.
* **Offline:** unreachable areas become ocean; the mod tries again later.

Install your own data to override it. The first launch writes
`config/alosearth.json` and creates these folders; relative paths are
resolved against the game or server folder:

| folder | what | needed? |
|---|---|---|
| `alosearth-data/aw3d30/` | JAXA ALOS AW3D30 zip bundles as downloaded (or extracted `*_DSM.tif` + `*_MSK.tif`) | optional; **preferred** over the download, and adds lakes and rivers from its water mask |
| `alosearth-data/gebco/` | GEBCO global grid GeoTIFFs | recommended: real sea floor and poles |
| `alosearth-data/fill/` | any lon/lat GeoTIFF DEM tiles | optional: fills gaps |
| `alosearth-data/climate/` | Köppen‑Geiger 1 km GeoTIFF (Beck et al.) | optional: a sharper climate map than the built‑in one |

* **Order of use:** AW3D30 → fill → auto‑download → GEBCO (or the
  downloaded sea floor) → ocean.
* **Turning off downloads:** set `"auto_download": false` in the config.
* **Checking what's loaded:** run `/earth status`.

**Climate is built in.** A 3 km Köppen‑Geiger climate map ships inside the
mod, so deserts, steppe, jungle, taiga and ice caps appear where they really
are without any download. It's from Rubel, Brugger, Haslinger & Auer (2017),
[koeppen-geiger.vu-wien.ac.at](http://koeppen-geiger.vu-wien.ac.at), via the
BSD‑licensed [kgcpy](https://github.com/cwru-sdle/kgcpy); see
`scripts/make_climate.py`.

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

**Seamless edges with Immersive Portals (optional).** Install
[Immersive Portals](https://modrinth.com/mod/immersiveportals) for 1.21.1
alongside ALOS Earth, on both the client and the server. Every seam then
becomes a see‑through portal the height of the world:
* you see across the edge to the other side;
* you walk over it with no teleport jump;
* your heading carries on.
The mod places these portals each time the server starts, and
`/earth whereami` still shows where you really are.

Known limits:
* Your coordinates jump when you cross a seam. (Immersive Portals makes the
  crossing itself seamless, but the numbers still change.)
* Blocks built or dug right at a seam aren't mirrored into the copy on the
  other side.
* Trees near a seam aren't copied exactly.

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
# --true-scale or --one-to-one for the other world types; --auto DIR to download data
```

CI (`.github/workflows/mod.yml`) builds the mod, runs the core tests, then
starts a real dedicated server with an ALOS Earth world and runs an in‑game
self‑test. The self‑test checks generated blocks, biomes, ores, every seam
crossing and the commands.
