package io.github.lazytive.alosearth;

import io.github.lazytive.alosearth.core.DataSources;
import io.github.lazytive.alosearth.core.EarthSettings;
import io.github.lazytive.alosearth.core.Terrain;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The mod, whatever the loader: the loader's entry point (fabric/AlosEarthFabric,
 * neoforge/AlosEarthNeoForge) registers the codecs under {@link #id}("earth") and calls these hooks
 * from its events.
 */
public final class AlosEarth {
    public static final String MOD_ID = "alosearth";
    public static final Logger LOG = LoggerFactory.getLogger("ALOS Earth");
    /** Immersive Portals installed: the seams become see-through portals. */
    public static final boolean IMMERSIVE_PORTALS = Platform.get().isModLoaded("immersive_portals");
    /** Distant Horizons installed: its far-away terrain comes straight from the terrain model. */
    public static final boolean DISTANT_HORIZONS = Platform.get().isModLoaded("distanthorizons");
    /** Set by a loader that supports Immersive Portals (Fabric): puts up the seam portals, and checks them. */
    public static java.util.function.BiConsumer<MinecraftServer, Boolean> seamPortals;
    public static BiFunction<ServerLevel, EarthChunkGenerator, String> seamPortalCheck;

    private static volatile DataSources data;
    private static final Map<EarthSettings, Terrain> TERRAINS = new ConcurrentHashMap<>();

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    private AlosEarth() {
    }

    /** When the mod loads (after the codecs are registered). */
    public static void init() {
        io.github.lazytive.alosearth.core.AutoDem.logger = msg -> LOG.info("Elevation download: {}", msg);
        if (DISTANT_HORIZONS) {
            try {
                io.github.lazytive.alosearth.compat.DistantHorizonsEarth.register();
            } catch (Throwable e) {
                LOG.warn("Distant Horizons integration unavailable: {}", e.toString());
            }
        }
    }

    /** At the end of every server level tick. */
    public static void levelTick(ServerLevel level) {
        if (level.getChunkSource().getGenerator() instanceof EarthChunkGenerator gen) {
            SeamHandler.tick(level);
            LayerHandler.tick(level, gen);
            ThroughTheEarth.tick(level, gen);
        }
    }

    /** When a server level loads (before its spawn area generates). */
    public static void levelLoaded(ServerLevel level) {
        // snow by real altitude: the overworld's settings
        if (level.dimension() == net.minecraft.world.level.Level.OVERWORLD) {
            EarthClimate.active = level.getChunkSource().getGenerator() instanceof EarthChunkGenerator g
                && g.settings.minecraftFeel() ? g.settings : null;
        }
    }

    public static void serverStarted(MinecraftServer server) {
        if (IMMERSIVE_PORTALS && seamPortals != null) {
            try {
                seamPortals.accept(server, EarthConfig.load().seamless_edges);
            } catch (Throwable e) {
                LOG.warn("Immersive Portals integration unavailable: {}", e.toString());
            }
        }
        if (SelfTest.enabled()) SelfTest.run(server);
    }

    public static void serverStopped() {
        EarthClimate.active = null;
    }

    /** The input data, loaded once (scanning a full AW3D30 download takes a few seconds). */
    public static DataSources data() {
        DataSources d = data;
        if (d == null) {
            synchronized (AlosEarth.class) {
                d = data;
                if (d == null) {
                    d = EarthConfig.load().open();
                    LOG.info("ALOS Earth data: {}", d.describe());
                    data = d;
                }
            }
        }
        return d;
    }

    /** The terrain for a world's settings (shared by the chunk generator and the biome source). */
    public static Terrain terrain(EarthSettings settings) {
        return TERRAINS.computeIfAbsent(settings, s -> new Terrain(s, data()));
    }
}
