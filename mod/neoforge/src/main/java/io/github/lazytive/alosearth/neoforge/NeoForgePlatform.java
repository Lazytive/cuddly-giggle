package io.github.lazytive.alosearth.neoforge;

import io.github.lazytive.alosearth.Platform;
import java.nio.file.Path;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

public final class NeoForgePlatform implements Platform.Services {
    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public Path gameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    @Override
    public boolean isModLoaded(String id) {
        return ModList.get().isLoaded(id);
    }

    @Override
    public String modVersion(String id) {
        return ModList.get().getModContainerById(id).map(c -> c.getModInfo().getVersion().toString()).orElse("?");
    }

    @Override
    public String name() {
        return "NeoForge";
    }
}
