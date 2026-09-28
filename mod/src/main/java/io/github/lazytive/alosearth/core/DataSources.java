package io.github.lazytive.alosearth.core;

import java.nio.file.Path;
import java.util.List;

/**
 * The input datasets: AW3D30 tiles (main elevation), an optional gap-fill DEM
 * (any lon/lat GeoTIFFs, e.g. Copernicus GLO-30), optional GEBCO bathymetry
 * (also used for land where neither DEM has data) and an optional
 * Koppen-Geiger climate map.
 */
public final class DataSources {
    public final Rasters.SegmentCache cache;
    public final Rasters.Aw3d30 aw3d30;
    public final Rasters.RasterSet fillDem, bathymetry, climate;
    /** Downloads elevation where nothing is installed; null when disabled. */
    public final AutoDem autoDem;

    public DataSources(List<Path> aw3d30, List<Path> fillDem, List<Path> bathymetry, List<Path> climate, long cacheBytes) {
        this(aw3d30, fillDem, bathymetry, climate, null, cacheBytes);
    }

    public DataSources(List<Path> aw3d30, List<Path> fillDem, List<Path> bathymetry, List<Path> climate,
                       Path autoDownloadDir, long cacheBytes) {
        cache = new Rasters.SegmentCache(cacheBytes);
        this.autoDem = autoDownloadDir == null ? null : new AutoDem(autoDownloadDir, cache);
        this.aw3d30 = new Rasters.Aw3d30(aw3d30, cache);
        this.fillDem = new Rasters.RasterSet(fillDem, cache);
        this.bathymetry = new Rasters.RasterSet(bathymetry, cache);
        this.climate = new Rasters.RasterSet(climate, cache);
    }

    public static DataSources empty() {
        return new DataSources(List.of(), List.of(), List.of(), List.of(), 1 << 20);
    }

    public String describe() {
        return aw3d30.tileCount() + " AW3D30 tiles, fill DEM " + (fillDem.isEmpty() ? "none" : fillDem.size() + " files")
            + ", bathymetry " + (bathymetry.isEmpty() ? "none" : "yes") + ", climate " + (climate.isEmpty() ? "none" : "yes")
            + ", " + (autoDem == null ? "auto-download off" : autoDem.describe());
    }

    /** True if some elevation source could cover this place (installed tiles or auto-download). */
    public boolean hasElevationSource(double lon, double lat) {
        if (autoDem != null || !bathymetry.isEmpty()) return true;
        lon = Rasters.normLon(lon);
        return aw3d30.hasTile((int) Math.floor(lat), (int) Math.floor(lon)) || !Double.isNaN(fillDem.nearest(lon, lat));
    }
}
