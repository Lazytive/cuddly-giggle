package io.github.lazytive.alosearth;

import io.github.lazytive.alosearth.core.DataSources;
import io.github.lazytive.alosearth.core.EarthSettings;
import io.github.lazytive.alosearth.core.Terrain;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AlosEarth implements ModInitializer {
    public static final String MOD_ID = "alosearth";
    public static final Logger LOG = LoggerFactory.getLogger("ALOS Earth");
    /** Immersive Portals installed: the seams become see-through portals. */
    public static final boolean IMMERSIVE_PORTALS = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("immersive_portals");
    /** Distant Horizons installed: its far-away terrain comes straight from the terrain model. */
    public static final boolean DISTANT_HORIZONS = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("distanthorizons");

    private static volatile DataSources data;
    private static final Map<EarthSettings, Terrain> TERRAINS = new ConcurrentHashMap<>();

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        io.github.lazytive.alosearth.core.AutoDem.logger = msg -> LOG.info("Elevation download: {}", msg);
        Registry.register(BuiltInRegistries.CHUNK_GENERATOR, id("earth"), EarthChunkGenerator.CODEC);
        Registry.register(BuiltInRegistries.BIOME_SOURCE, id("earth"), EarthBiomeSource.CODEC);

        ServerTickEvents.END_WORLD_TICK.register(level -> {
            if (level.getChunkSource().getGenerator() instanceof EarthChunkGenerator gen) {
                SeamHandler.tick(level);
                LayerHandler.tick(level, gen);
                ThroughTheEarth.tick(level, gen);
            }
        });
        if (DISTANT_HORIZONS) {
            try {
                io.github.lazytive.alosearth.compat.DistantHorizonsEarth.register();
            } catch (Throwable e) {
                LOG.warn("Distant Horizons integration unavailable: {}", e.toString());
            }
        }
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> EarthCommands.register(dispatcher));
        // snow by real altitude: the overworld's settings, from when it loads (before its spawn area generates)
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents.LOAD.register((server, level) -> {
            if (level.dimension() == net.minecraft.world.level.Level.OVERWORLD) {
                EarthClimate.active = level.getChunkSource().getGenerator() instanceof EarthChunkGenerator g
                    && g.settings.minecraftFeel() ? g.settings : null;
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> EarthClimate.active = null);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (IMMERSIVE_PORTALS) {
                try {
                    io.github.lazytive.alosearth.compat.ImmersivePortalsSeams.setUp(server, EarthConfig.load().seamless_edges);
                } catch (Throwable e) {
                    LOG.warn("Immersive Portals integration unavailable: {}", e.toString());
                }
            }
            if (SelfTest.enabled()) SelfTest.run(server);
        });
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
