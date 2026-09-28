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

    private static volatile DataSources data;
    private static volatile io.github.lazytive.alosearth.core.Buildings buildings;
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
            }
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> EarthCommands.register(dispatcher));
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            startInACity(server);
            if (SelfTest.enabled()) SelfTest.run(server);
        });
    }

    /**
     * A brand-new world with buildings would otherwise start at the map's
     * centre, in the central African rainforest: start it in a city instead.
     */
    private static void startInACity(net.minecraft.server.MinecraftServer server) {
        var level = server.overworld();
        if (!(level.getChunkSource().getGenerator() instanceof EarthChunkGenerator g) || !g.buildings
            || level.getGameTime() != 0) {
            return;
        }
        String place = EarthConfig.load().start_place;
        double[] ll = place == null ? null : EarthCommands.PLACES.get(place.toLowerCase(java.util.Locale.ROOT));
        if (ll == null) return;
        double[] p = new double[2];
        g.terrain().projection.forward(ll[1], ll[0], p);
        int x = (int) Math.floor(p[0]), z = (int) Math.floor(p[1]);
        level.setDefaultSpawnPos(new net.minecraft.core.BlockPos(x, g.terrain().surface(x, z) + 1, z), 0f);
        LOG.info("New world with buildings: starting in {} ({}, {})", place, x, z);
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

    /** OpenStreetMap buildings (only opened by worlds that place them). */
    public static io.github.lazytive.alosearth.core.Buildings buildings() {
        var b = buildings;
        if (b == null) {
            synchronized (AlosEarth.class) {
                b = buildings;
                if (b == null) buildings = b = EarthConfig.load().openBuildings();
            }
        }
        return b;
    }

    /** The terrain for a world's settings (shared by the chunk generator and the biome source). */
    public static Terrain terrain(EarthSettings settings) {
        return TERRAINS.computeIfAbsent(settings, s -> new Terrain(s, data()));
    }
}
