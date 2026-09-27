package io.github.lazytive.alosearth;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.lazytive.alosearth.core.CubeProjection;
import io.github.lazytive.alosearth.core.Terrain;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** {@code /earth whereami}, {@code /earth goto <lat> <lon>}, {@code /earth goto <place>}. */
public final class EarthCommands {
    private EarthCommands() {
    }

    /** A few places worth visiting: name -> {lat, lon}. */
    static final Map<String, double[]> PLACES = new LinkedHashMap<>();

    static {
        place("everest", 27.9881, 86.9250);
        place("k2", 35.8808, 76.5155);
        place("mont_blanc", 45.8326, 6.8652);
        place("matterhorn", 45.9763, 7.6586);
        place("fuji", 35.3606, 138.7274);
        place("tokyo", 35.6812, 139.7671);
        place("kyoto", 35.0116, 135.7681);
        place("osaka", 34.6937, 135.5023);
        place("sapporo", 43.0618, 141.3545);
        place("london", 51.5074, -0.1278);
        place("paris", 48.8566, 2.3522);
        place("rome", 41.9028, 12.4964);
        place("new_york", 40.7128, -74.0060);
        place("san_francisco", 37.7749, -122.4194);
        place("grand_canyon", 36.1069, -112.1129);
        place("yosemite", 37.7456, -119.5936);
        place("denali", 63.0692, -151.0070);
        place("rio_de_janeiro", -22.9068, -43.1729);
        place("aconcagua", -32.6532, -70.0109);
        place("sydney", -33.8688, 151.2093);
        place("uluru", -25.3444, 131.0369);
        place("cape_town", -33.9249, 18.4241);
        place("kilimanjaro", -3.0674, 37.3556);
        place("cairo", 30.0444, 31.2357);
        place("reykjavik", 64.1466, -21.9426);
        place("mauna_kea", 19.8207, -155.4681);
        place("north_pole", 89.9, 0.0);
        place("south_pole", -89.9, 0.0);
    }

    private static void place(String name, double lat, double lon) {
        PLACES.put(name, new double[] {lat, lon});
    }

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("earth")
            .then(Commands.literal("whereami").executes(EarthCommands::whereami))
            .then(Commands.literal("goto").requires(s -> s.hasPermission(2))
                .then(Commands.argument("lat", DoubleArgumentType.doubleArg(-90, 90))
                    .then(Commands.argument("lon", DoubleArgumentType.doubleArg(-180, 180))
                        .executes(c -> go(c, DoubleArgumentType.getDouble(c, "lat"), DoubleArgumentType.getDouble(c, "lon"), null))))
                .then(Commands.argument("place", StringArgumentType.word())
                    .suggests((c, b) -> SharedSuggestionProvider.suggest(PLACES.keySet(), b))
                    .executes(c -> {
                        String name = StringArgumentType.getString(c, "place").toLowerCase(Locale.ROOT);
                        double[] ll = PLACES.get(name);
                        if (ll == null) {
                            c.getSource().sendFailure(Component.literal("Unknown place " + name + "; try /earth goto <lat> <lon>"));
                            return 0;
                        }
                        return go(c, ll[0], ll[1], name);
                    }))));
    }

    private static EarthChunkGenerator generator(CommandSourceStack src) {
        ServerLevel level = src.getLevel();
        if (level.getChunkSource().getGenerator() instanceof EarthChunkGenerator g) return g;
        src.sendFailure(Component.literal("This dimension is not an ALOS Earth world"));
        return null;
    }

    private static int whereami(CommandContext<CommandSourceStack> c) {
        EarthChunkGenerator g = generator(c.getSource());
        if (g == null) return 0;
        Terrain t = g.terrain();
        int x = (int) Math.floor(c.getSource().getPosition().x), z = (int) Math.floor(c.getSource().getPosition().z);
        double[] ll = new double[2];
        int kind = t.projection.inverse(x + 0.5, z + 0.5, ll);
        String text = t.describe(x, z);
        if (kind != CubeProjection.OUTSIDE) {
            double[] dist = t.projection.distortion(ll[0], ll[1]);
            text += String.format(Locale.ROOT, ", biome %s, 1 block = %.0f-%.0f m", t.biome(x, z), dist[0], dist[1]);
        }
        String msg = text;
        c.getSource().sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    private static int go(CommandContext<CommandSourceStack> c, double lat, double lon, String name)
        throws CommandSyntaxException {
        EarthChunkGenerator g = generator(c.getSource());
        if (g == null) return 0;
        ServerPlayer player = c.getSource().getPlayerOrException();
        Terrain t = g.terrain();
        double[] p = new double[2];
        t.projection.forward(lon, lat, p);
        int x = (int) Math.floor(p[0]), z = (int) Math.floor(p[1]);
        int y = t.surface(x, z) + 1;
        player.teleportTo(c.getSource().getLevel(), x + 0.5, y, z + 0.5, player.getYRot(), player.getXRot());
        String label = name != null ? name : String.format(Locale.ROOT, "%.4f, %.4f", lat, lon);
        c.getSource().sendSuccess(() -> Component.literal("Teleported to " + label + " (" + x + " " + y + " " + z + ")"), true);
        return 1;
    }
}
