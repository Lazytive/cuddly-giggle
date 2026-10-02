package io.github.lazytive.alosearth.fabric;

import io.github.lazytive.alosearth.AlosEarth;
import io.github.lazytive.alosearth.EarthBiomeSource;
import io.github.lazytive.alosearth.EarthChunkGenerator;
import io.github.lazytive.alosearth.EarthCommands;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;

/** Fabric entry point: registers the world generator and hooks the mod into Fabric's events. */
public final class AlosEarthFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        Registry.register(BuiltInRegistries.CHUNK_GENERATOR, AlosEarth.id("earth"), EarthChunkGenerator.CODEC);
        Registry.register(BuiltInRegistries.BIOME_SOURCE, AlosEarth.id("earth"), EarthBiomeSource.CODEC);
        if (AlosEarth.IMMERSIVE_PORTALS) { // Immersive Portals is Fabric-only
            AlosEarth.seamPortals = io.github.lazytive.alosearth.compat.ImmersivePortalsSeams::setUp;
            AlosEarth.seamPortalCheck = io.github.lazytive.alosearth.compat.ImmersivePortalsSeams::check;
        }
        AlosEarth.init();
        ServerTickEvents.END_WORLD_TICK.register(AlosEarth::levelTick);
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> EarthCommands.register(dispatcher));
        ServerWorldEvents.LOAD.register((server, level) -> AlosEarth.levelLoaded(level));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> AlosEarth.serverStopped());
        ServerLifecycleEvents.SERVER_STARTED.register(AlosEarth::serverStarted);
    }
}
