package io.github.lazytive.alosearth;

import io.github.lazytive.alosearth.core.CubeProjection;
import io.github.lazytive.alosearth.core.Palette;
import io.github.lazytive.alosearth.core.Terrain;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;

/**
 * In-game checks run by CI (environment variable ALOSEARTH_SELFTEST=1) on a
 * real dedicated server with the synthetic test data: generated blocks and
 * biomes match the terrain core, vanilla ores are placed, every seam carries
 * an entity to the right place with its velocity turned, and the commands
 * exist. Prints ALOSEARTH SELFTEST PASS/FAIL and stops the server.
 */
final class SelfTest {
    private SelfTest() {
    }

    static boolean enabled() {
        return "1".equals(System.getenv("ALOSEARTH_SELFTEST"));
    }

    static void run(MinecraftServer server) {
        List<String> errors = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        try {
            check(server, errors, notes);
        } catch (Throwable e) {
            AlosEarth.LOG.error("self-test crashed", e);
            errors.add("crash: " + e);
        }
        StringBuilder report = new StringBuilder();
        for (String n : notes) report.append("note: ").append(n).append('\n');
        for (String e : errors) report.append("error: ").append(e).append('\n');
        report.append(errors.isEmpty() ? "ALOSEARTH SELFTEST PASS\n" : "ALOSEARTH SELFTEST FAIL\n");
        try {
            Files.writeString(Path.of("alosearth-selftest.txt"), report.toString());
        } catch (Exception ignored) {
            // the log below still has everything
        }
        for (String line : report.toString().split("\n")) AlosEarth.LOG.info(line);
        if (errors.isEmpty()) {
            server.halt(false);
        } else {
            Runtime.getRuntime().halt(1);
        }
    }

    private static void check(MinecraftServer server, List<String> errors, List<String> notes) {
        ServerLevel level = server.overworld();
        if (!(level.getChunkSource().getGenerator() instanceof EarthChunkGenerator gen)) {
            errors.add("overworld generator is " + level.getChunkSource().getGenerator().getClass().getName());
            return;
        }
        Terrain t = gen.terrain();
        notes.add("data: " + t.data.describe());
        if (t.data.aw3d30.tileCount() == 0) errors.add("no AW3D30 test tiles found");
        notes.add("world height " + level.getMinBuildHeight() + ".." + level.getMaxBuildHeight());
        if (level.getMinBuildHeight() != t.settings.minY() || level.getMaxBuildHeight() != t.settings.maxY()) {
            errors.add("dimension height does not match settings");
        }

        // 1. blocks and biomes at the synthetic mountain, lake, coast and sea
        double[][] places = {{35.5, 139.5}, {35.75, 139.25}, {35.3, 139.8}, {35.1, 139.95}, {35.6, 138.4}};
        double[] p = new double[2];
        int columns = 0, surfaceMatches = 0, deepMatches = 0, deepTotal = 0, biomeMatches = 0, ores = 0;
        for (double[] ll : places) {
            t.projection.forward(ll[1], ll[0], p);
            int cx = (int) Math.floor(p[0]) >> 4, cz = (int) Math.floor(p[1]) >> 4;
            level.getChunk(cx, cz); // generates it fully (features included)
            for (int lx = 0; lx < 16; lx += 3) {
                for (int lz = 0; lz < 16; lz += 3) {
                    int x = (cx << 4) + lx, z = (cz << 4) + lz;
                    int top = t.top(x, z);
                    columns++;
                    Block want = gen.states()[t.block(x, top, z)].getBlock();
                    if (level.getBlockState(new BlockPos(x, top, z)).getBlock() == want) surfaceMatches++;
                    for (int y = level.getMinBuildHeight() + 6; y < top - 16; y += 7) {
                        Block got = level.getBlockState(new BlockPos(x, y, z)).getBlock();
                        String id = BuiltInRegistries.BLOCK.getKey(got).getPath();
                        if (id.endsWith("_ore")) ores++;
                        deepTotal++;
                        if (got == gen.states()[t.block(x, y, z)].getBlock() || id.endsWith("_ore")
                            || id.equals("tuff") || id.equals("granite") || id.equals("diorite") || id.equals("andesite")
                            || id.equals("gravel") || id.equals("dirt") || id.equals("clay")) {
                            deepMatches++;
                        }
                    }
                    int qx = QuartPos.fromBlock(x), qz = QuartPos.fromBlock(z);
                    String biome = level.getNoiseBiome(qx, QuartPos.fromBlock(t.settings.seaLevel()), qz).unwrapKey()
                        .map(k -> k.location().getPath()).orElse("?");
                    if (biome.equals(t.biome(QuartPos.toBlock(qx) + 2, QuartPos.toBlock(qz) + 2))) biomeMatches++;
                }
            }
        }
        notes.add(String.format("columns %d: surface matches %d, underground matches %d/%d, biome matches %d",
            columns, surfaceMatches, deepMatches, deepTotal, biomeMatches));
        if (surfaceMatches < columns * 0.8) errors.add("surface blocks differ from the terrain model");
        if (deepMatches < deepTotal * 0.97) errors.add("underground blocks differ from the terrain model");
        if (biomeMatches != columns) errors.add("biomes differ from the terrain model");

        // 2. vanilla ores are generated in the solid rock
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                t.projection.forward(139.5, 35.5, p);
                int cx = ((int) Math.floor(p[0]) >> 4) + dx, cz = ((int) Math.floor(p[1]) >> 4) + dz;
                level.getChunk(cx, cz);
                for (int x = cx << 4; x < (cx << 4) + 16; x++) {
                    for (int z = cz << 4; z < (cz << 4) + 16; z++) {
                        for (int y = level.getMinBuildHeight() + 1; y < 40; y++) {
                            String id = BuiltInRegistries.BLOCK.getKey(level.getBlockState(new BlockPos(x, y, z)).getBlock()).getPath();
                            if (id.endsWith("_ore")) ores++;
                        }
                    }
                }
            }
        }
        notes.add("ore blocks found: " + ores);
        if (ores < 50) errors.add("too few ores: " + ores);

        // 3. every seam carries an entity across, turned correctly
        CubeProjection pr = t.projection;
        for (CubeProjection.Link l : pr.links) {
            double mx = (l.sx0 + l.sx1) / 2.0 + 0.3, mz = (l.sz0 + l.sz1) / 2.0 + 0.3;
            double x = switch (l.edge) {
                case "left" -> l.sx1 - 0.6;
                case "right" -> l.sx0 + 0.6;
                default -> mx;
            };
            double z = switch (l.edge) {
                case "top" -> l.sz1 - 0.6;
                case "bottom" -> l.sz0 + 0.6;
                default -> mz;
            };
            int y = t.surface((int) Math.floor(x), (int) Math.floor(z)) + 2;
            level.getChunk((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
            Pig pig = EntityType.PIG.create(level);
            if (pig == null) {
                errors.add("could not create a pig");
                break;
            }
            pig.moveTo(x, y, z, 0f, 0f);
            pig.setDeltaMovement(0.3, 0.0, 0.1);
            level.addFreshEntity(pig);
            SeamHandler.cross(level, pr, pig);
            double ex = l.applyX(x, z), ez = l.applyZ(x, z);
            Vec3 v = pig.getDeltaMovement();
            double evx = l.rotX(0.3, 0.1), evz = l.rotZ(0.3, 0.1);
            boolean ok = Math.abs(pig.getX() - ex) < 0.01 && Math.abs(pig.getZ() - ez) < 0.01
                && l.dest.contains(pig.getX(), pig.getZ()) && Math.abs(v.x - evx) < 1e-6 && Math.abs(v.z - evz) < 1e-6
                && Math.abs(Math.floorMod((int) Math.round(pig.getYRot() - l.yaw), 360)) == 0;
            if (!ok) {
                errors.add("seam " + l + ": pig at " + pig.getX() + "," + pig.getZ() + " yaw " + pig.getYRot()
                    + " velocity " + v + ", expected " + ex + "," + ez + " yaw " + l.yaw);
            }
            pig.discard();
        }
        notes.add("seams checked: " + pr.links.length);

        // 4. commands are registered
        if (server.getCommands().getDispatcher().getRoot().getChild("earth") == null) errors.add("/earth missing");
        if (Palette.BLOCKS.length != gen.states().length) errors.add("block palette");
    }
}
