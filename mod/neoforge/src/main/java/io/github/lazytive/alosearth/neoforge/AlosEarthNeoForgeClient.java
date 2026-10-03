package io.github.lazytive.alosearth.neoforge;

import io.github.lazytive.alosearth.AlosEarthClient;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterDimensionSpecialEffectsEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Client side on NeoForge (only loaded on the client). */
final class AlosEarthNeoForgeClient {
    private AlosEarthNeoForgeClient() {
    }

    static void init(IEventBus modBus) {
        modBus.addListener((RegisterDimensionSpecialEffectsEvent e) -> AlosEarthClient.skies().forEach(e::register));
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn e) -> AlosEarthClient.joined(Minecraft.getInstance()));
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> AlosEarthClient.left(Minecraft.getInstance()));
    }
}
