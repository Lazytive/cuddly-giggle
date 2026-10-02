package io.github.lazytive.alosearth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.lazytive.alosearth.core.DataSources;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;

/**
 * {@code config/alosearth.json}: where the elevation data lives. Relative
 * paths are resolved against the game (or server) folder.
 */
public final class EarthConfig {
    public List<String> aw3d30 = List.of("alosearth-data/aw3d30");
    public List<String> fill_dem = List.of("alosearth-data/fill");
    public List<String> bathymetry = List.of("alosearth-data/gebco");
    public List<String> climate = List.of("alosearth-data/climate");
    public int cache_mb = 768;
    /** Download Copernicus 30 m elevation for areas with no installed tiles. */
    public boolean auto_download = true;
    public String auto_download_dir = "alosearth-data/auto";
    /** With Immersive Portals installed: see-through portals on the globe's seams. */
    public boolean seamless_edges = true;
    /**
     * Biomes from other mods: each ALOS Earth biome (vanilla name, e.g. "forest") can be replaced by
     * one or more biome ids, e.g. {"forest": ["terralith:forested_highlands", "minecraft:forest"]}.
     * Several share the land in patches. Ids of mods that aren't installed are skipped.
     */
    public java.util.Map<String, List<String>> biomes = new java.util.LinkedHashMap<>();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    static EarthConfig load() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("alosearth.json");
        EarthConfig cfg = new EarthConfig();
        try {
            if (Files.exists(file)) {
                try (Reader r = Files.newBufferedReader(file)) {
                    EarthConfig read = GSON.fromJson(r, EarthConfig.class);
                    if (read != null) cfg = read;
                }
            } else {
                Files.createDirectories(file.getParent());
                try (Writer w = Files.newBufferedWriter(file)) {
                    GSON.toJson(cfg, w);
                }
                Path game = FabricLoader.getInstance().getGameDir();
                for (String dir : List.of("aw3d30", "fill", "gebco", "climate")) {
                    Files.createDirectories(game.resolve("alosearth-data").resolve(dir));
                }
                Files.writeString(game.resolve("alosearth-data").resolve("README.txt"), """
                    ALOS Earth data folders (see config/alosearth.json):
                      aw3d30/   JAXA AW3D30 zip bundles or *_DSM.tif/*_MSK.tif tiles (main elevation)
                      fill/     optional: any lon/lat GeoTIFF DEM tiles used where AW3D30 has none
                      gebco/    optional: GEBCO global grid GeoTIFFs (sea floor, poles)
                      climate/  optional: Koppen-Geiger 1 km GeoTIFF (Beck et al.) for biomes
                      auto/     elevation downloaded automatically (Copernicus GLO-30) where
                                nothing above is installed; set "auto_download": false to turn off
                    """);
                AlosEarth.LOG.info("Wrote default config {}", file);
            }
        } catch (IOException e) {
            AlosEarth.LOG.error("Could not read/write {}", file, e);
        }
        return cfg;
    }

    DataSources open() {
        Path game = FabricLoader.getInstance().getGameDir();
        return new DataSources(paths(game, aw3d30), paths(game, fill_dem), paths(game, bathymetry), paths(game, climate),
            auto_download ? game.resolve(auto_download_dir == null ? "alosearth-data/auto" : auto_download_dir) : null,
            (long) Math.max(64, cache_mb) << 20);
    }

    private static List<Path> paths(Path base, List<String> in) {
        List<Path> out = new ArrayList<>();
        if (in != null) {
            for (String s : in) out.add(base.resolve(s));
        }
        return out;
    }
}
