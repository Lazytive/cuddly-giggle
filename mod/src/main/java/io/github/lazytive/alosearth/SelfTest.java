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
        String v = System.getenv("ALOSEARTH_SELFTEST");
        return "1".equals(v) || "auto".equals(v);
    }

    static void run(MinecraftServer server) {
        List<String> errors = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        try {
            if ("auto".equals(System.getenv("ALOSEARTH_SELFTEST"))) checkAutoDownload(server, errors, notes);
            else check(server, errors, notes);
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

    /** A world with no installed data: land must come from the automatic download. */
    private static void checkAutoDownload(MinecraftServer server, List<String> errors, List<String> notes) {
        ServerLevel level = server.overworld();
        if (!(level.getChunkSource().getGenerator() instanceof EarthChunkGenerator gen)) {
            errors.add("overworld is not ALOS Earth");
            return;
        }
        Terrain t = gen.terrain();
        if (t.data.autoDem == null) errors.add("auto-download is off");
        BlockPos spawn = level.getSharedSpawnPos();
        notes.add("spawn " + spawn + " at " + t.describe(spawn.getX(), spawn.getZ()));
        if (t.surface(spawn.getX(), spawn.getZ()) <= t.settings.seaLevel()) errors.add("spawn is not on land");
        double[] p = new double[2];
        t.projection.forward(138.7274, 35.3606, p); // Mt Fuji
        int x = (int) Math.floor(p[0]), z = (int) Math.floor(p[1]);
        level.getChunk(x >> 4, z >> 4);
        int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z);
        notes.add("Fuji summit column top y=" + top);
        if (top < t.settings.seaLevel() + 200) errors.add("Fuji is not a mountain: top " + top);
        // the sea floor is downloaded too: the Japan Trench is ~6.8 km deep
        t.projection.forward(143.9, 38.0, p);
        x = (int) Math.floor(p[0]);
        z = (int) Math.floor(p[1]);
        int floor = t.top(x, z);
        notes.add("Japan Trench floor y=" + floor + " (" + EarthCommands.elevation(gen, floor) + ")");
        if (!t.settings.seaFloor()) errors.add("new worlds should use the downloaded sea floor");
        else if (floor > t.settings.seaLevel() - t.settings.oceanBlocks(5000)) errors.add("ocean is too shallow: floor " + floor);
        notes.add("data: " + t.data.describe());
    }

    private static void checkLayers(MinecraftServer server, ServerLevel level, EarthChunkGenerator gen, Terrain t,
                                    List<String> errors, List<String> notes) {
        int shift = t.settings.layerShift();
        for (int k = 1; k <= t.settings.deepLayers(); k++) {
            ServerLevel deep = LayerHandler.level(server, gen, k);
            if (deep == null) {
                errors.add("deep layer " + k + " missing");
                return;
            }
            if (deep.getMinBuildHeight() != t.settings.minY() || deep.getMaxBuildHeight() != t.settings.maxY()) {
                errors.add("deep layer " + k + " height " + deep.getMinBuildHeight() + ".." + deep.getMaxBuildHeight());
            }
        }
        ServerLevel deep1 = LayerHandler.level(server, gen, 1);
        EarthChunkGenerator g1 = (EarthChunkGenerator) deep1.getChunkSource().getGenerator();
        double[] p = new double[2];
        t.projection.forward(-12.857, -4, p); // ~2.5 km deep in the synthetic ocean
        int x = (int) Math.floor(p[0]), z = (int) Math.floor(p[1]);
        int floor = t.top(x, z);
        notes.add("deep sea floor at virtual y " + floor + " (" + EarthCommands.elevation(gen, floor) + ")");
        if (floor >= level.getMinBuildHeight()) errors.add("synthetic deep ocean is not below the main world: " + floor);
        level.getChunk(x >> 4, z >> 4);
        deep1.getChunk(x >> 4, z >> 4);
        int local = floor + g1.offset;
        Block want = gen.states()[t.block(x, floor, z)].getBlock();
        Block got = deep1.getBlockState(new BlockPos(x, local, z)).getBlock();
        Block above = deep1.getBlockState(new BlockPos(x, local + 1, z)).getBlock();
        Block main = level.getBlockState(new BlockPos(x, level.getMinBuildHeight(), z)).getBlock();
        Block overlap = deep1.getBlockState(new BlockPos(x, level.getMinBuildHeight() + shift, z)).getBlock();
        notes.add("deep layer 1 floor " + got + ", above it " + above + "; main world bottom " + main + " = layer 1 " + overlap);
        if (got != want) errors.add("deep layer 1 sea floor is " + got + ", expected " + want);
        if (!deep1.getFluidState(new BlockPos(x, local + 3, z)).is(net.minecraft.tags.FluidTags.WATER)) {
            errors.add("no water above the deep sea floor");
        }
        if (main != net.minecraft.world.level.block.Blocks.WATER || overlap != main) errors.add("layers do not overlap seamlessly");
        StringBuilder hm = new StringBuilder();
        for (var type : new net.minecraft.world.level.levelgen.Heightmap.Types[] {
            net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR, net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE,
            net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING}) {
            hm.append(type).append('=').append(deep1.getHeight(type, x, z)).append(' ');
        }
        for (int y = deep1.getMaxBuildHeight() - 1; y > local - 5; y--) {
            var bs = deep1.getBlockState(new BlockPos(x, y, z));
            if (bs.blocksMotion()) {
                hm.append("highest motion-blocking block ").append(bs).append(" at ").append(y);
                break;
            }
        }
        notes.add("deep layer 1 heightmaps: " + hm);
        int top = deep1.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR, x, z) - 1;
        if (top != local) errors.add("deep layer heightmap " + top + ", expected " + local);
        var capital = deep1.getBlockState(new BlockPos(x, deep1.getMaxBuildHeight() - 1, z));
        if (!capital.is(net.minecraft.world.level.block.Blocks.WATER)) errors.add("top of deep layer 1 is " + capital + ", not water");

        // a pig sinking past the main world's floor moves to layer 1 at the same place, and back up
        if (LayerHandler.direction(level, gen, level.getMinBuildHeight() + 40) != 0
            || LayerHandler.direction(level, gen, level.getMinBuildHeight() + 10) != 1
            || LayerHandler.direction(level, gen, level.getMaxBuildHeight() - 1) != 0
            || LayerHandler.direction(deep1, g1, deep1.getMaxBuildHeight() - 10) != -1
            || LayerHandler.direction(deep1, g1, deep1.getMaxBuildHeight() - 40) != 0
            || LayerHandler.direction(deep1, g1, level.getMinBuildHeight() + 10 + shift) != 0) {
            errors.add("layer crossing thresholds are wrong (an entity could bounce between layers)");
        }
        Pig pig = EntityType.PIG.create(level);
        pig.moveTo(x + 0.5, level.getMinBuildHeight() + 10.5, z + 0.5, 0f, 0f);
        pig.setDeltaMovement(0.1, -0.2, 0.05);
        level.addFreshEntity(pig);
        net.minecraft.world.entity.Entity moved = LayerHandler.cross(level, gen, pig, 1);
        if (moved == null || moved.level() != deep1 || Math.abs(moved.getY() - (level.getMinBuildHeight() + 10.5 + shift)) > 1e-6
            || Math.abs(moved.getX() - (x + 0.5)) > 1e-6 || moved.getDeltaMovement().y != -0.2) {
            errors.add("pig did not sink into deep layer 1 correctly: " + (moved == null ? "null" : moved.level().dimension()
                + " " + moved.position() + " " + moved.getDeltaMovement()));
        } else {
            moved.moveTo(moved.getX(), deep1.getMaxBuildHeight() - 5.5, moved.getZ());
            net.minecraft.world.entity.Entity back = LayerHandler.cross(deep1, g1, moved, -1);
            if (back == null || back.level() != level || Math.abs(back.getY() - (deep1.getMaxBuildHeight() - 5.5 - shift)) > 1e-6) {
                errors.add("pig did not rise back into the main world");
            } else {
                notes.add("pig crossed the layer join both ways");
                back.discard();
            }
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

        // 4. 1:1 worlds: the ocean carries on through the deep layers, and things sink into them
        if (t.settings.deepLayers() > 0) checkLayers(server, level, gen, t, errors, notes);

        // 5. new worlds: snow by real altitude (plains are mild at sea level, freezing at 4 km)
        if (t.settings.minecraftFeel()) {
            if (EarthClimate.active == null) errors.add("snow-by-altitude climate not active");
            else {
                var plains = server.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME)
                    .getOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS);
                int low = t.settings.seaLevel() + 5;
                int high = (int) Math.round(t.settings.seaLevel() + t.settings.landBlocks(4000));
                boolean warmLow = !plains.coldEnoughToSnow(new BlockPos(0, low, 0)), coldHigh = plains.coldEnoughToSnow(new BlockPos(0, high, 0));
                notes.add("plains snow at y " + low + ": " + !warmLow + ", at y " + high + " (4 km): " + coldHigh);
                if (!warmLow || !coldHigh) errors.add("snow line by altitude is wrong");
            }
        }

        // 6. new worlds: no bedrock, and falling out of the bottom leads to the antipode
        if (t.settings.minecraftFeel() && t.settings.deepLayers() == 0) {
            double[] q = new double[2];
            t.projection.forward(139.5, 35.5, q);
            int bx = (int) Math.floor(q[0]), bz = (int) Math.floor(q[1]);
            level.getChunk(bx >> 4, bz >> 4);
            Block floorBlock = level.getBlockState(new BlockPos(bx, level.getMinBuildHeight(), bz)).getBlock();
            if (floorBlock == net.minecraft.world.level.block.Blocks.BEDROCK) errors.add("the world floor is still bedrock");
            Pig faller = EntityType.PIG.create(level);
            faller.moveTo(bx + 0.5, level.getMinBuildHeight() - 10, bz + 0.5, 0f, 0f);
            level.addFreshEntity(faller);
            net.minecraft.world.entity.Entity out = ThroughTheEarth.send(level, gen, faller);
            double[] ll = new double[2];
            if (out == null) errors.add("falling through the Earth failed");
            else {
                t.projection.inverse(out.getX(), out.getZ(), ll);
                boolean antipode = Math.abs(ll[1] + 35.5) < 0.05 && Math.abs(Math.abs(ll[0] - 139.5) - 180) < 0.05;
                notes.add(String.format("fell through the Earth from 35.5N 139.5E to %.2f, %.2f at y %.0f", ll[1], ll[0], out.getY()));
                if (!antipode || out.getY() < t.settings.seaLevel() - 1) errors.add("did not come out at the antipode surface");
                out.discard();
            }
        }

        // 7. with Immersive Portals: a see-through portal on every seam, mapping exactly like the seam
        if (AlosEarth.IMMERSIVE_PORTALS) {
            String problem = AlosEarth.seamPortalCheck == null ? "not supported with " + Platform.get().name()
                : AlosEarth.seamPortalCheck.apply(level, gen);
            if (problem != null) errors.add("Immersive Portals: " + problem);
            else notes.add("Immersive Portals: seam portals match all " + gen.terrain().projection.links.length + " seams");
        }

        // 9. biomes from other mods (config "biomes"): replacements are placed, shared in patches,
        // and unknown ids are skipped
        {
            var lookup = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BIOME);
            EarthBiomeSource src = new EarthBiomeSource(lookup, t.settings, java.util.Map.of(
                "plains", List.of("minecraft:meadow", "minecraft:cherry_grove", "nosuchmod:nosuchbiome")));
            int plains = Palette.biome("plains"), meadow = 0, cherry = 0;
            double[] at = new double[2];
            t.projection.forward(139.5, 35.5, at);
            for (int k = 0; k < 400; k++) { // 20 x 20 spots 1500 blocks apart
                int x = (int) at[0] + (k % 20) * 1500, z = (int) at[1] + (k / 20) * 1500;
                String got = src.biomeAt(plains, x, z).unwrapKey().map(key -> key.location().getPath()).orElse("?");
                if (got.equals("meadow")) meadow++;
                else if (got.equals("cherry_grove")) cherry++;
            }
            boolean listed = src.possibleBiomes().stream().anyMatch(h -> h.is(net.minecraft.world.level.biome.Biomes.MEADOW));
            notes.add("biome replacements: plains became meadow " + meadow + "x and cherry grove " + cherry + "x of 400");
            if (meadow + cherry != 400 || meadow < 40 || cherry < 40 || !listed) errors.add("biome replacements from the config");
            if (src.biomeAt(Palette.biome("forest"), 0, 0).unwrapKey().map(key -> !key.location().getPath().equals("forest")).orElse(true)) {
                errors.add("a biome without a replacement changed");
            }
        }

        // 10. the trees far terrain shows are the trees the chunk gets: in a forest near the test area,
        // worked out without generating it, then compared with the real chunk (logs and leaves)
        {
            double[] at = new double[2];
            t.projection.forward(139.5, 35.5, at);
            int w = 64, step = 32, x0 = (int) at[0] - w * step / 2, z0 = (int) at[1] - w * step / 2, best = -1;
            Terrain.Tile far = t.far(x0, z0, step, w);
            double bestD = Double.MAX_VALUE;
            for (int k = 0; k < w * w; k++) {
                String b = Palette.BIOMES[far.biome[k]];
                if (far.water[k] > far.top[k] || !(b.contains("forest") || b.contains("taiga") || b.contains("jungle"))) continue;
                double dx = k % w - w / 2.0, dz = k / w - w / 2.0;
                if (dx * dx + dz * dz < bestD) {
                    bestD = dx * dx + dz * dz;
                    best = k;
                }
            }
            if (best < 0) {
                notes.add("trees: no forest near the test area");
            } else {
                int cx = (x0 + (best % w) * step + step / 2) >> 4, cz = (z0 + (best / w) * step + step / 2) >> 4;
                long t0 = System.nanoTime();
                LodTrees lod = new LodTrees(level, gen);
                LodTrees.Area area = lod.area(cx << 4, cz << 4, 16);
                long simMs = (System.nanoTime() - t0) / 1_000_000;
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) level.getChunk(cx + dx, cz + dz); // the real ones, with their neighbours' trees
                }
                int real = 0, simulated = 0, both = 0, innerReal = 0, innerSim = 0, innerBoth = 0;
                int realTrunks = 0, simTrunks = 0, bothTrunks = 0;
                List<String> trunkNotes = new ArrayList<>();
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        int x = (cx << 4) + lx, z = (cz << 4) + lz;
                        boolean inner = lx >= 4 && lx < 12 && lz >= 4 && lz < 12;
                        java.util.Set<Integer> sim = new java.util.HashSet<>();
                        int[] ys = LodTrees.heights(area, x, z);
                        net.minecraft.world.level.block.state.BlockState[] st = LodTrees.states(area, x, z);
                        int ground = t.top(x, z);
                        if (ys != null) for (int k = 0; k < ys.length; k++) if (ys[k] > ground && LodTrees.isTreeBlock(st[k])) sim.add(ys[k]);
                        int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z);
                        for (int y = ground + 1; y < top; y++) {
                            if (LodTrees.isTreeBlock(level.getBlockState(new BlockPos(x, y, z)))) {
                                real++;
                                if (sim.contains(y)) both++;
                                if (inner) {
                                    innerReal++;
                                    if (sim.contains(y)) innerBoth++;
                                }
                            }
                        }
                        simulated += sim.size();
                        if (inner) innerSim += sim.size();
                        // trunks: a log just above the ground
                        boolean realTrunk = level.getBlockState(new BlockPos(x, ground + 1, z)).is(net.minecraft.tags.BlockTags.LOGS);
                        boolean simTrunk = ys != null && java.util.stream.IntStream.range(0, ys.length)
                            .anyMatch(k -> ys[k] == ground + 1 && st[k].is(net.minecraft.tags.BlockTags.LOGS));
                        if (realTrunk) realTrunks++;
                        if (simTrunk) simTrunks++;
                        if (realTrunk && simTrunk) bothTrunks++;
                        if (realTrunk != simTrunk && trunkNotes.size() < 6) { // what's different there
                            BlockPos g = new BlockPos(x, ground, z);
                            trunkNotes.add(String.format("%d,%d %s: ground y %d is %s, above it %s, biome %s / worked out %s", x, z,
                                realTrunk ? "real only" : "worked out only", ground,
                                BuiltInRegistries.BLOCK.getKey(level.getBlockState(g).getBlock()).getPath(),
                                BuiltInRegistries.BLOCK.getKey(level.getBlockState(g.above()).getBlock()).getPath(),
                                level.getBiome(g.above()).unwrapKey().map(k -> k.location().getPath()).orElse("?"),
                                lod.biomeAt(g.above()).unwrapKey().map(k -> k.location().getPath()).orElse("?")));
                        }
                    }
                }
                double match = real + simulated == both ? 1 : both / (double) (real + simulated - both);
                double innerMatch = innerReal + innerSim == innerBoth ? 1 : innerBoth / (double) (innerReal + innerSim - innerBoth);
                notes.add(String.format("trees: %s chunk %d,%d has %d log and leaf blocks, %d worked out in advance (%d ms), %.0f%% the same"
                        + " (%.0f%% away from the chunk's edges); trunks %d real, %d worked out, %d in the same place",
                    Palette.BIOMES[far.biome[best]], cx, cz, real, simulated, simMs, 100 * match, 100 * innerMatch,
                    realTrunks, simTrunks, bothTrunks));
                if (real == 0 || match < 0.6) {
                    errors.add("the trees worked out for far terrain don't match the real chunk");
                    for (String n : trunkNotes) notes.add("trees: " + n);
                    String refs = level.getChunk(cx, cz).getAllReferences().keySet().stream()
                        .map(st -> level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.STRUCTURE).getKey(st))
                        .map(String::valueOf).collect(java.util.stream.Collectors.joining(", "));
                    notes.add("trees: structures reaching the chunk: " + (refs.isEmpty() ? "none" : refs) + "; seed " + level.getSeed());
                }
            }
        }

        // 8. with Distant Horizons: the LOD columns it gets from the terrain model pass its own checks
        if (AlosEarth.DISTANT_HORIZONS) {
            String problem = io.github.lazytive.alosearth.compat.DistantHorizonsEarth.check(level, gen, notes);
            if (problem != null) errors.add("Distant Horizons: " + problem);
        }

        // 4. commands are registered
        if (server.getCommands().getDispatcher().getRoot().getChild("earth") == null) errors.add("/earth missing");
        if (Palette.BLOCKS.length != gen.states().length) errors.add("block palette");
    }
}
