package io.github.lazytive.alosearth.neoforge;

import io.github.lazytive.alosearth.AlosEarth;
import io.github.lazytive.alosearth.EarthBiomeSource;
import io.github.lazytive.alosearth.EarthChunkGenerator;
import io.github.lazytive.alosearth.EarthCommands;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

/** NeoForge entry point: registers the world generator and hooks the mod into NeoForge's events. */
@Mod(AlosEarth.MOD_ID)
public final class AlosEarthNeoForge {
    public AlosEarthNeoForge(IEventBus modBus, Dist dist) {
        modBus.addListener((RegisterEvent e) -> {
            e.register(Registries.CHUNK_GENERATOR, r -> r.register(AlosEarth.id("earth"), EarthChunkGenerator.CODEC));
            e.register(Registries.BIOME_SOURCE, r -> r.register(AlosEarth.id("earth"), EarthBiomeSource.CODEC));
        });
        AlosEarth.init();
        NeoForge.EVENT_BUS.addListener((LevelTickEvent.Post e) -> {
            if (e.getLevel() instanceof ServerLevel level) AlosEarth.levelTick(level);
        });
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> EarthCommands.register(e.getDispatcher()));
        NeoForge.EVENT_BUS.addListener((LevelEvent.Load e) -> {
            if (e.getLevel() instanceof ServerLevel level) AlosEarth.levelLoaded(level);
        });
        NeoForge.EVENT_BUS.addListener((ServerAboutToStartEvent e) -> AlosEarth.serverStarting(e.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent e) -> AlosEarth.serverStarted(e.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> AlosEarth.serverStopped());
        if (dist.isClient()) AlosEarthNeoForgeClient.init(modBus);
    }
}
