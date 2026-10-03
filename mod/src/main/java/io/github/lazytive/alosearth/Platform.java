package io.github.lazytive.alosearth;

import java.nio.file.Path;
import java.util.ServiceLoader;

/**
 * What differs between mod loaders (Fabric, NeoForge) apart from events: folders and which other
 * mods are installed. Each loader's build supplies one {@link Services} (listed in
 * META-INF/services).
 */
public final class Platform {
    private Platform() {
    }

    public interface Services {
        Path configDir();

        Path gameDir();

        boolean isModLoaded(String id);

        /** The installed version of a mod, or "?". */
        String modVersion(String id);

        /** "Fabric" or "NeoForge". */
        String name();
    }

    private static final Services SERVICES = ServiceLoader.load(Services.class, Platform.class.getClassLoader()).findFirst()
        .orElseThrow(() -> new IllegalStateException("ALOS Earth: no platform services for this mod loader"));

    public static Services get() {
        return SERVICES;
    }
}
