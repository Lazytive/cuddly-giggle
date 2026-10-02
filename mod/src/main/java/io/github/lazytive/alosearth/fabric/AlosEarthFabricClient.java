package io.github.lazytive.alosearth.fabric;

import io.github.lazytive.alosearth.AlosEarthClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.DimensionRenderingRegistry;

public final class AlosEarthFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        AlosEarthClient.skies().forEach(DimensionRenderingRegistry::registerDimensionEffects);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> AlosEarthClient.joined(client));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> AlosEarthClient.left(client));
    }
}
