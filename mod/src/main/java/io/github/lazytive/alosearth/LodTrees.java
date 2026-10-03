package io.github.lazytive.alosearth;

import io.github.lazytive.alosearth.core.Terrain;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.material.FluidState;

/**
 * The structures (villages, temples, ...) and trees (and plants) a chunk will get, worked out without
 * generating it: the structures Minecraft starts around it, the ground it fills in and cuts away
 * under them, their pieces, and Minecraft's own vegetation features run, with the world seed, in the
 * order and with the random numbers Minecraft's decoration uses, on a stand-in world that answers
 * from the terrain model and only records what they place. So far terrain (Distant Horizons) can
 * show the very buildings and trees that appear when the chunk loads.
 *
 * <p>Chunks are worked out on their own; where two chunks' trees meet, the real world can differ a
 * little (which chunk was decorated first decides which tree fits). Structures are placed chunk by
 * chunk in the world too, so they come out the same.
 */
public final class LodTrees {
    /** The tree blocks of an area, by block column (x, z), bottom to top. */
    public static final class Area {
        final Long2ObjectOpenHashMap<int[]> ys = new Long2ObjectOpenHashMap<>();
        final Long2ObjectOpenHashMap<BlockState[]> states = new Long2ObjectOpenHashMap<>();
    }

    private final ServerLevel level;
    private final EarthChunkGenerator gen;
    private final long seed;
    private final BiomeManager biomes;
    private final EarthBiomeSource biomeSource;
    /** Per decoration step, the features in Minecraft's order (as ChunkGenerator builds it). */
    private final List<FeatureSorter.StepFeatureData> steps;
    private final int vegetal = GenerationStep.Decoration.VEGETAL_DECORATION.ordinal();
    /** The tree features of the vegetal step, by index in that order (null: not a tree feature). */
    private final boolean[] isTree;
    /** Tree blocks per chunk, most recently used last. */
    private final LinkedHashMap<Long, Map<Long, BlockState>> cache = new LinkedHashMap<>(256, 0.75f, true);
    private static final int CACHE_CHUNKS = 4096;
    /** Whether the world has structures, and those placed with the surface (villages, temples, ...) in registry order. */
    private final boolean structures;
    private final int surfaceStep = GenerationStep.Decoration.SURFACE_STRUCTURES.ordinal();
    private final List<Structure> surfaceStructures;
    private final net.minecraft.core.Registry<Biome> biomeRegistry;
    /** The structures starting in each chunk (most are empty), most recently used last. */
    private final LinkedHashMap<Long, Map<Structure, StructureStart>> startCache = new LinkedHashMap<>(256, 0.75f, true);
    private static final int CACHE_STARTS = 32768;

    public LodTrees(ServerLevel level, EarthChunkGenerator gen) {
        this.level = level;
        this.gen = gen;
        this.seed = level.getSeed();
        this.biomeSource = (EarthBiomeSource) gen.getBiomeSource();
        this.biomes = new BiomeManager((qx, qy, qz) -> biomeSource.exactBiome(qx, qy, qz), BiomeManager.obfuscateSeed(seed));
        this.steps = FeatureSorter.buildFeaturesPerStep(List.copyOf(gen.getBiomeSource().possibleBiomes()),
            b -> gen.getBiomeGenerationSettings(b).features(), true);
        List<PlacedFeature> vegetalFeatures = steps.size() > vegetal ? steps.get(vegetal).features() : List.of();
        isTree = new boolean[vegetalFeatures.size()];
        for (int k = 0; k < isTree.length; k++) {
            isTree[k] = vegetalFeatures.get(k).getFeatures().anyMatch(cf -> cf.feature() instanceof TreeFeature);
        }
        this.biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);
        // structures belong to the surface world, not the deep layers under it
        this.structures = gen.offset == 0 && level.structureManager().shouldGenerateStructures();
        // as ChunkGenerator.applyBiomeDecoration numbers them: per step, in registry order
        this.surfaceStructures = level.registryAccess().registryOrThrow(Registries.STRUCTURE).stream()
            .filter(st -> st.step().ordinal() == surfaceStep).toList();
    }

    /** Whether any biome here has trees at all (worlds without the vegetal step have none). */
    public boolean hasTrees() {
        for (boolean b : isTree) if (b) return true;
        return false;
    }

    /** Whether chunks here can get anything far terrain shows: trees, or buildings. */
    public boolean hasDecoration() {
        return hasTrees() || structures && !surfaceStructures.isEmpty();
    }

    /** The blocks (buildings, trees, plants) that decorating chunk (cx, cz) places, by block position. */
    public Map<Long, BlockState> chunk(int cx, int cz) {
        long key = ((long) cx << 32) ^ (cz & 0xffffffffL);
        synchronized (cache) {
            Map<Long, BlockState> m = cache.get(key);
            if (m != null) return m;
        }
        Map<Long, BlockState> m = simulate(cx, cz, null);
        synchronized (cache) {
            cache.put(key, m);
            if (cache.size() > CACHE_CHUNKS) {
                var it = cache.entrySet().iterator();
                it.next();
                it.remove();
            }
        }
        return m;
    }

    /**
     * The tree blocks of every chunk that can reach the block area [x0, x0 + w) x [z0, z0 + w)
     * (trees spill a few blocks into neighbouring chunks), by column.
     */
    public Area area(int x0, int z0, int w) {
        Map<Long, List<Long>> byColumn = new java.util.HashMap<>();
        Map<Long, BlockState> all = new java.util.HashMap<>();
        for (int cz = (z0 >> 4) - 1; cz <= ((z0 + w - 1) >> 4) + 1; cz++) {
            for (int cx = (x0 >> 4) - 1; cx <= ((x0 + w - 1) >> 4) + 1; cx++) {
                for (Map.Entry<Long, BlockState> e : chunk(cx, cz).entrySet()) {
                    long p = e.getKey();
                    int x = BlockPos.getX(p), z = BlockPos.getZ(p);
                    if (x < x0 || z < z0 || x >= x0 + w || z >= z0 + w) continue;
                    if (all.putIfAbsent(p, e.getValue()) == null) byColumn.computeIfAbsent(column(x, z), c -> new ArrayList<>()).add(p);
                }
            }
        }
        Area out = new Area();
        for (Map.Entry<Long, List<Long>> e : byColumn.entrySet()) {
            List<Long> ps = e.getValue();
            ps.sort((a, b) -> Integer.compare(BlockPos.getY(a), BlockPos.getY(b)));
            int[] y = new int[ps.size()];
            BlockState[] st = new BlockState[ps.size()];
            for (int k = 0; k < y.length; k++) {
                y[k] = BlockPos.getY(ps.get(k));
                st[k] = all.get(ps.get(k));
            }
            out.ys.put(e.getKey().longValue(), y);
            out.states.put(e.getKey().longValue(), st);
        }
        return out;
    }

    /** The biome the trees see at a position (as decoration does: Minecraft's biome blending included). */
    public Holder<Biome> biomeAt(BlockPos pos) {
        return biomes.getBiome(pos);
    }

    public static long column(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    /** Heights (ascending) of the tree blocks in a column of an area, or null. */
    public static int[] heights(Area area, int x, int z) {
        return area.ys.get(column(x, z));
    }

    public static BlockState[] states(Area area, int x, int z) {
        return area.states.get(column(x, z));
    }

    /** For the self-test: the blocks the structures (not the trees) place in chunk (cx, cz). */
    public Map<Long, BlockState> structureBlocks(int cx, int cz) {
        Map<Long, BlockState> record = new java.util.HashMap<>();
        simulate(cx, cz, record);
        return record;
    }

    // ------------------------------------------------------------ structures

    /** The structures that start in chunk (cx, cz), as the world's own generation picks them. */
    public Map<Structure, StructureStart> starts(int cx, int cz) {
        long key = ChunkPos.asLong(cx, cz);
        synchronized (startCache) {
            Map<Structure, StructureStart> m = startCache.get(key);
            if (m != null) return m;
        }
        Map<Structure, StructureStart> m = Map.of();
        if (structures) {
            ProtoChunk proto = new ProtoChunk(new ChunkPos(cx, cz), UpgradeData.EMPTY, level, biomeRegistry, null);
            try {
                gen.createStructures(level.registryAccess(), level.getChunkSource().getGeneratorState(), level.structureManager(),
                    proto, level.getStructureManager());
                if (!proto.getAllStarts().isEmpty()) m = Map.copyOf(proto.getAllStarts());
            } catch (RuntimeException e) {
                // a structure that can't be worked out here: far terrain goes without it
            }
        }
        synchronized (startCache) {
            startCache.put(key, m);
            if (startCache.size() > CACHE_STARTS) {
                var it = startCache.entrySet().iterator();
                it.next();
                it.remove();
            }
        }
        return m;
    }

    /** The chunks whose structures reach into chunk (cx, cz), per structure (as Minecraft's createReferences finds them). */
    private Map<Structure, LongSet> references(int cx, int cz) {
        Map<Structure, LongSet> refs = new java.util.HashMap<>();
        if (!structures) return refs;
        int bx = cx << 4, bz = cz << 4;
        for (int x = cx - 8; x <= cx + 8; x++) {
            for (int z = cz - 8; z <= cz + 8; z++) {
                for (StructureStart start : starts(x, z).values()) {
                    if (start.isValid() && start.getBoundingBox().intersects(bx, bz, bx + 15, bz + 15)) {
                        refs.computeIfAbsent(start.getStructure(), k -> new LongOpenHashSet()).add(ChunkPos.asLong(x, z));
                    }
                }
            }
        }
        return refs;
    }

    /** A chunk as far as structures go: its starts and, if asked, what reaches into it (for Minecraft's StructureManager). */
    private ChunkAccess structureChunk(int cx, int cz, Map<Structure, LongSet> refs) {
        ProtoChunk proto = new ProtoChunk(new ChunkPos(cx, cz), UpgradeData.EMPTY, level, biomeRegistry, null);
        proto.setAllStarts(starts(cx, cz));
        if (refs != null) proto.setAllReferences(refs);
        return proto;
    }

    /**
     * Places the structures reaching into chunk (cx, cz) in the stand-in world, as decoration does:
     * the ground filled in and cut away under them first, then their pieces, each structure with its
     * own random numbers, written only inside the chunk.
     */
    private void placeStructures(int cx, int cz, StandIn stand, WorldGenLevel world, WorldgenRandom random, long decoration) {
        ChunkPos chunkPos = new ChunkPos(cx, cz);
        StructureManager manager = new StructureManager(world, level.getServer().getWorldData().worldGenOptions(), null);
        int minY = level.getMinBuildHeight(), maxY = level.getMaxBuildHeight() - 1;
        try {
            Beardifier beard = Beardifier.forStructuresInChunk(manager, chunkPos);
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    int x = (cx << 4) + lx, z = (cz << 4) + lz;
                    gen.adaptColumn(beard, x, z, minY, maxY, (y, state) -> stand.set(new BlockPos(x, y, z), state));
                }
            }
        } catch (RuntimeException e) {
            // no beard: buildings stand on the ground as it is
        }
        BoundingBox box = new BoundingBox(cx << 4, minY + 1, cz << 4, (cx << 4) + 15, maxY, (cz << 4) + 15);
        SectionPos section = SectionPos.of(chunkPos, level.getMinSection());
        stand.building = true;
        try {
            for (int l = 0; l < surfaceStructures.size(); l++) {
                Structure structure = surfaceStructures.get(l);
                if (!stand.refs.containsKey(structure)) continue;
                random.setFeatureSeed(decoration, l, surfaceStep);
                List<StructureStart> found;
                try {
                    found = manager.startsForStructure(section, structure);
                } catch (RuntimeException e) {
                    continue;
                }
                for (StructureStart start : found) {
                    // StructureStart.placeInChunk, with each piece on its own: a piece that wants more of the world
                    // than the stand-in has (mobs to spawn) loses only the rest of itself
                    List<StructurePiece> pieces = start.getPieces();
                    if (pieces.isEmpty()) continue;
                    BoundingBox first = pieces.get(0).getBoundingBox();
                    BlockPos centre = first.getCenter();
                    BlockPos pivot = new BlockPos(centre.getX(), first.minY(), centre.getZ());
                    for (StructurePiece piece : pieces) {
                        if (!piece.getBoundingBox().intersects(box)) continue;
                        try {
                            piece.postProcess(world, manager, gen, random, box, chunkPos, pivot);
                        } catch (RuntimeException e) {
                            // the blocks placed so far stay
                        }
                    }
                    try {
                        structure.afterPlace(world, manager, gen, random, box, chunkPos, new PiecesContainer(pieces));
                    } catch (RuntimeException e) {
                        // e.g. a desert pyramid's suspicious sand: not seen from afar
                    }
                }
            }
        } finally {
            stand.building = false;
        }
    }

    // ------------------------------------------------------------ canopy statistics, for far away

    /**
     * What a biome's forest looks like from afar, measured from its own trees: the share of the
     * ground under leaves, how high the tree tops are and where the crowns start (blocks above
     * the ground), and the most common leaves and log.
     */
    public record Canopy(double cover, int[] tops, int crownBottom, BlockState leaves, BlockState log) {
        /** A tree-top height for a column, picked by a 0..1 value so that tops vary like the real ones. */
        public int top(double u) {
            return tops[Math.min(tops.length - 1, (int) (u * tops.length))];
        }
    }

    private static final class Tally {
        long columns, treeColumns;
        final it.unimi.dsi.fastutil.ints.IntArrayList tops = new it.unimi.dsi.fastutil.ints.IntArrayList();
        final it.unimi.dsi.fastutil.ints.IntArrayList bottoms = new it.unimi.dsi.fastutil.ints.IntArrayList();
        final Map<BlockState, Integer> leaves = new java.util.HashMap<>(), logs = new java.util.HashMap<>();
        Canopy canopy;
        long canopyAt;
        int probes;
    }

    private final Map<Holder<Biome>, Tally> tallies = new java.util.concurrent.ConcurrentHashMap<>();
    /** Columns of a biome to have seen before its canopy is trusted (else a few chunks are sampled). */
    private static final int ENOUGH = 2048, MAX_PROBES = 12;

    /** Learns from an area worked out for near terrain: its trees, column by column, per biome. */
    public void observe(Area area, int x0, int z0, int w) {
        Terrain t = gen.terrain();
        for (int z = z0; z < z0 + w; z++) {
            for (int x = x0; x < x0 + w; x++) {
                Terrain.Tile tile = t.tileAt(x, z);
                int i = Terrain.index(x, z);
                if (tile.water[i] > tile.top[i]) continue; // forests are measured on land
                Tally tally = tallies.computeIfAbsent(biomeSource.biomeAt(tile.biome[i], x, z), b -> new Tally());
                int ground = tile.top[i] + gen.offset;
                int[] ys = heights(area, x, z);
                BlockState[] st = states(area, x, z);
                synchronized (tally) {
                    tally.columns++;
                    if (ys == null) continue;
                    int lowLeaves = Integer.MAX_VALUE, high = Integer.MIN_VALUE;
                    for (int k = 0; k < ys.length; k++) {
                        if (st[k].is(net.minecraft.tags.BlockTags.LEAVES)) {
                            lowLeaves = Math.min(lowLeaves, ys[k]);
                            high = Math.max(high, ys[k]);
                            tally.leaves.merge(st[k].getBlock().defaultBlockState(), 1, Integer::sum);
                        } else if (st[k].is(net.minecraft.tags.BlockTags.LOGS)) {
                            tally.logs.merge(st[k].getBlock().defaultBlockState(), 1, Integer::sum);
                        }
                    }
                    if (high == Integer.MIN_VALUE || high <= ground) continue;
                    tally.treeColumns++;
                    if (tally.tops.size() < 4096) {
                        tally.tops.add(high - ground);
                        tally.bottoms.add(Math.max(1, lowLeaves - ground));
                    }
                }
            }
        }
    }

    /**
     * The canopy of a biome, for far terrain at (x, z). The first times a biome comes up, a few of
     * its chunks there are worked out to measure it; null if the biome has no trees.
     */
    public Canopy canopy(Holder<Biome> biome, int x, int z) {
        Tally tally = tallies.computeIfAbsent(biome, b -> new Tally());
        boolean probe;
        synchronized (tally) {
            probe = tally.columns < ENOUGH && tally.probes < MAX_PROBES;
            if (probe) tally.probes++;
        }
        if (probe) {
            int cx = x >> 4, cz = z >> 4;
            observe(area(cx << 4, cz << 4, 16), cx << 4, cz << 4, 16);
        }
        synchronized (tally) {
            if (tally.treeColumns == 0) return null;
            if (tally.canopy == null || tally.columns > tally.canopyAt * 5 / 4) {
                int[] tops = tally.tops.toIntArray();
                Arrays.sort(tops);
                int[] bottoms = tally.bottoms.toIntArray();
                Arrays.sort(bottoms);
                BlockState leaves = tally.leaves.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey)
                    .orElse(Blocks.OAK_LEAVES.defaultBlockState());
                BlockState log = tally.logs.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey)
                    .orElse(Blocks.OAK_LOG.defaultBlockState());
                tally.canopy = new Canopy((double) tally.treeColumns / tally.columns, tops, bottoms[bottoms.length / 2], leaves, log);
                tally.canopyAt = tally.columns;
            }
            return tally.canopy;
        }
    }

    // ------------------------------------------------------------ decoration: structures and trees

    private Map<Long, BlockState> simulate(int cx, int cz, Map<Long, BlockState> record) {
        Map<Long, BlockState> placed = new java.util.HashMap<>();
        int x0 = cx << 4, z0 = cz << 4;
        Map<Structure, LongSet> refs = references(cx, cz);
        boolean anyStructure = false;
        for (Structure s : refs.keySet()) anyStructure |= s.step().ordinal() == surfaceStep;
        // the vegetal features of the biomes in and around this chunk, as Minecraft picks them
        int[] indices = new int[0];
        if (steps.size() > vegetal) {
            Set<Holder<Biome>> near = new java.util.HashSet<>();
            for (int dz = -16; dz <= 31; dz += 4) {
                for (int dx = -16; dx <= 31; dx += 4) {
                    int top = gen.terrain().top(x0 + dx, z0 + dz) + gen.offset;
                    for (int y : new int[] {top, top - 30}) { // the surface, and caves (azaleas grow over lush caves)
                        near.add(biomeSource.exactBiome(QuartPos.fromBlock(x0 + dx), QuartPos.fromBlock(y), QuartPos.fromBlock(z0 + dz)));
                    }
                }
            }
            FeatureSorter.StepFeatureData step = steps.get(vegetal);
            indices = near.stream().flatMap(b -> {
                List<HolderSet<PlacedFeature>> f = gen.getBiomeGenerationSettings(b).features();
                return vegetal < f.size() ? f.get(vegetal).stream() : java.util.stream.Stream.empty();
            }).mapToInt(h -> step.indexMapping().applyAsInt(h.value())).filter(k -> k >= 0 && k < isTree.length)
                .distinct().sorted().toArray();
        }
        // All of the step runs, not just the trees: a flower or a bush placed before a tree can stand where
        // its trunk would go, and then that tree fails and every later one in the chunk moves (they share
        // one stream of random numbers).
        boolean anyTree = false;
        for (int k : indices) anyTree |= isTree[k];
        if (!anyTree && !anyStructure) return placed;

        StandIn stand = new StandIn(placed, cx, cz, refs);
        WorldGenLevel world = (WorldGenLevel) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(), new Class<?>[] {WorldGenLevel.class}, stand);
        WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0));
        long decoration = random.setDecorationSeed(seed, x0, z0);
        if (anyStructure) {
            stand.record = record;
            placeStructures(cx, cz, stand, world, random, decoration);
            stand.record = null;
        }
        if (!anyTree || record != null) return placed;
        FeatureSorter.StepFeatureData step = steps.get(vegetal);
        BlockPos origin = new BlockPos(x0, level.getMinBuildHeight(), z0);
        for (int k : indices) {
            random.setFeatureSeed(decoration, k, vegetal);
            try {
                step.features().get(k).placeWithBiomeCheck(world, gen, random, origin);
            } catch (RuntimeException e) {
                // a feature that needs more of the world than the stand-in has: no trees from it here
            }
        }
        return placed;
    }

    // ------------------------------------------------------------ the stand-in world

    private BlockState terrainState(int x, int y, int z) {
        Terrain t = gen.terrain();
        return gen.states()[t.block(x, y - gen.offset, z)];
    }

    /** Height of the first block above the column's highest one that counts for the heightmap. */
    private int height(Map<Long, BlockState> placed, Map<Long, int[]> top, Heightmap.Types type, int x, int z) {
        Terrain t = gen.terrain();
        int ground = t.top(x, z) + gen.offset, surface = t.surface(x, z) + gen.offset;
        Predicate<BlockState> counts = type.isOpaque();
        int h = surface > ground && counts.test(gen.states()[io.github.lazytive.alosearth.core.Palette.WATER]) ? surface + 1 : ground + 1;
        int[] ys = top.get(column(x, z));
        if (ys != null) {
            for (int y : ys) {
                if (y + 1 > h) {
                    BlockState s = placed.get(BlockPos.asLong(x, y, z));
                    if (s != null && counts.test(s)) h = y + 1;
                }
            }
            // ground cut away under a building (or a block taken out): the heightmap goes down to what is left
            int floor = Math.max(level.getMinBuildHeight(), h - 64);
            while (h > floor) {
                BlockState s = placed.get(BlockPos.asLong(x, h - 1, z));
                if (s == null) s = terrainState(x, h - 1, z);
                if (counts.test(s)) break;
                h--;
            }
        }
        return h;
    }

    /**
     * The stand-in world: blocks from the terrain model, plus what has been placed; block entities
     * for placed blocks that have them (chests and beehives take random numbers when filled).
     */
    private final class StandIn implements InvocationHandler {
        final Map<Long, BlockState> placed;
        final Map<Long, int[]> columns = new java.util.HashMap<>(); // heights placed per column
        final Map<Long, BlockEntity> blockEntities = new java.util.HashMap<>();
        final int cx, cz;
        final Map<Structure, LongSet> refs;
        final Map<Long, ChunkAccess> chunks = new java.util.HashMap<>();
        /** Placing structures: their chunks can be asked for, mobs they spawn are left out. */
        boolean building;
        /** Also put what the structures place here (or null). */
        Map<Long, BlockState> record;

        StandIn(Map<Long, BlockState> placed, int cx, int cz, Map<Structure, LongSet> refs) {
            this.placed = placed;
            this.cx = cx;
            this.cz = cz;
            this.refs = refs;
        }

        @Override
        public Object invoke(Object proxy, Method m, Object[] a) throws Throwable {
            Class<?> r = m.getReturnType();
            Class<?>[] p = m.getParameterTypes();
            if (m.getDeclaringClass() == Object.class) {
                return switch (p.length) {
                    case 0 -> r == int.class ? System.identityHashCode(proxy) : "ALOS Earth far-terrain stand-in";
                    default -> proxy == a[0];
                };
            }
            if (p.length == 1 && p[0] == BlockPos.class) {
                BlockPos pos = (BlockPos) a[0];
                if (r == BlockState.class) return state(pos);
                if (r == FluidState.class) return state(pos).getFluidState();
                if (r == BlockEntity.class) return blockEntity(pos);
                if (Holder.class.isAssignableFrom(r)) return biomes.getBiome(pos);
            }
            if (p.length == 2 && p[0] == BlockPos.class && p[1] == Predicate.class && r == boolean.class) {
                BlockPos pos = (BlockPos) a[0];
                @SuppressWarnings("unchecked") Predicate<Object> test = (Predicate<Object>) a[1];
                boolean fluid = m.getGenericParameterTypes()[1] instanceof ParameterizedType pt
                    && pt.getActualTypeArguments()[0] == FluidState.class;
                return fluid ? test.test(state(pos).getFluidState()) : test.test(state(pos));
            }
            if (p.length == 2 && p[0] == BlockPos.class && p[1] == BlockEntityType.class) {
                BlockEntity be = blockEntity((BlockPos) a[0]);
                return be != null && be.getType() == a[1] ? Optional.of(be) : Optional.empty();
            }
            if (p.length >= 3 && p[0] == BlockPos.class && p[1] == BlockState.class && r == boolean.class && !m.isDefault()) {
                set((BlockPos) a[0], (BlockState) a[1]);
                return true;
            }
            if (p.length >= 2 && p[0] == BlockPos.class && p[1] == boolean.class && r == boolean.class && !m.isDefault()) {
                set((BlockPos) a[0], Blocks.AIR.defaultBlockState()); // removeBlock / destroyBlock
                return true;
            }
            if (p.length == 3 && p[0] == Heightmap.Types.class && r == int.class) {
                return height(placed, columns, (Heightmap.Types) a[0], (int) a[1], (int) a[2]);
            }
            if (p.length == 2 && p[0] == Heightmap.Types.class && r == BlockPos.class) {
                BlockPos pos = (BlockPos) a[1];
                return new BlockPos(pos.getX(), height(placed, columns, (Heightmap.Types) a[0], pos.getX(), pos.getZ()), pos.getZ());
            }
            if (p.length == 3 && p[0] == int.class && p[1] == int.class && p[2] == int.class && Holder.class.isAssignableFrom(r)) {
                return biomeSource.exactBiome((int) a[0], (int) a[1], (int) a[2]);
            }
            if (ChunkAccess.class.isAssignableFrom(r) && p.length >= 3 && p[0] == int.class && p[1] == int.class && p[2] == ChunkStatus.class
                && (a[2] == ChunkStatus.STRUCTURE_STARTS || a[2] == ChunkStatus.STRUCTURE_REFERENCES || building)) {
                // what Minecraft's StructureManager asks for (and pieces marking blocks for later shape updates)
                int x = (int) a[0], z = (int) a[1];
                boolean withRefs = a[2] != ChunkStatus.STRUCTURE_STARTS;
                long key = ChunkPos.asLong(x, z) * 2 + (withRefs ? 1 : 0);
                ChunkAccess c = chunks.get(key);
                if (c == null) {
                    c = structureChunk(x, z, !withRefs ? null : x == cx && z == cz ? refs : references(x, z));
                    chunks.put(key, c);
                }
                return c;
            }
            if (ChunkAccess.class.isAssignableFrom(r) || r == net.minecraft.world.level.BlockGetter.class) {
                throw new UnsupportedOperationException("the far-terrain stand-in has no chunks");
            }
            if (r == BiomeManager.class) return biomes;
            if (r == RandomSource.class) return RandomSource.create(seed);
            if (p.length == 0) {
                if (r == long.class && m.getDeclaringClass() == WorldGenLevel.class) return seed;
                if (r == ServerLevel.class && building) {
                    // mobs in structures (villagers, witches, ...): not seen from afar, and spawning them would reach
                    // into the real world; Minecraft's own templates skip a mob that can't be made
                    throw new UnsupportedOperationException("the far-terrain stand-in spawns no mobs");
                }
                if (r == int.class || r == boolean.class || r == ServerLevel.class
                    || r == net.minecraft.core.RegistryAccess.class || r == net.minecraft.world.level.dimension.DimensionType.class
                    || r == net.minecraft.world.flag.FeatureFlagSet.class || r == net.minecraft.world.level.storage.LevelData.class
                    || r == net.minecraft.world.level.border.WorldBorder.class) {
                    return m.invoke(level); // reading the real level's settings is harmless
                }
            }
            if (m.isDefault()) return InvocationHandler.invokeDefault(proxy, m, a);
            return defaultValue(r);
        }

        private BlockState state(BlockPos pos) {
            BlockState s = placed.get(pos.asLong());
            return s != null ? s : terrainState(pos.getX(), pos.getY(), pos.getZ());
        }

        private BlockEntity blockEntity(BlockPos pos) {
            BlockState s = placed.get(pos.asLong());
            if (s == null || !s.hasBlockEntity() || !(s.getBlock() instanceof EntityBlock eb)) return null;
            BlockPos at = pos.immutable();
            return blockEntities.computeIfAbsent(at.asLong(), k -> eb.newBlockEntity(at, s));
        }

        void set(BlockPos pos, BlockState s) {
            long key = pos.asLong();
            BlockState old = placed.put(key, s);
            if (old == null) {
                columns.merge(column(pos.getX(), pos.getZ()), new int[] {pos.getY()}, (o, n) -> {
                    int[] c = Arrays.copyOf(o, o.length + 1);
                    c[o.length] = n[0];
                    return c;
                });
            } else if (old.getBlock() != s.getBlock()) {
                blockEntities.remove(key);
            }
            if (record != null && building) record.put(key, s);
        }
    }

    /** What an interface method we don't care about returns: nothing, or a do-nothing object. */
    private static Object defaultValue(Class<?> r) {
        if (r == void.class) return null;
        if (r == boolean.class) return false;
        if (r == int.class) return 0;
        if (r == long.class) return 0L;
        if (r == float.class) return 0f;
        if (r == double.class) return 0d;
        if (r == short.class) return (short) 0;
        if (r == byte.class) return (byte) 0;
        if (r == char.class) return (char) 0;
        if (r == Optional.class) return Optional.empty();
        if (r == List.class) return List.of();
        if (r.isInterface()) { // e.g. tick schedulers: accept and forget
            return Proxy.newProxyInstance(r.getClassLoader(), new Class<?>[] {r}, (proxy, m, a) -> {
                if (m.getDeclaringClass() == Object.class) {
                    return m.getName().equals("equals") ? proxy == a[0] : m.getName().equals("hashCode") ? System.identityHashCode(proxy) : r.getName();
                }
                return m.isDefault() ? InvocationHandler.invokeDefault(proxy, m, a) : defaultValue(m.getReturnType());
            });
        }
        return null;
    }

    /** For the self-test and statistics: the leaves and logs among a set of tree blocks. */
    public static boolean isTreeBlock(BlockState s) {
        return s.is(net.minecraft.tags.BlockTags.LEAVES) || s.is(net.minecraft.tags.BlockTags.LOGS);
    }
}
