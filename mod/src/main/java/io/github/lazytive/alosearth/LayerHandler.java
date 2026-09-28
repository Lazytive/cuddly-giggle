package io.github.lazytive.alosearth;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;

/**
 * Minecraft worlds are at most 4064 blocks tall, so a 1:1 ocean (the
 * Mariana Trench is 11 km deep) continues below the main world in stacked
 * "deep layer" dimensions. Neighbouring layers share
 * {@link io.github.lazytive.alosearth.core.EarthSettings#LAYER_OVERLAP} blocks of
 * identical terrain; anything that sinks to near the bottom of one layer
 * moves into the one below (and back up the same way), at the same x and z.
 */
public final class LayerHandler {
    private LayerHandler() {
    }

    /** How close to a layer's floor or ceiling an entity gets before it moves. */
    static final int EDGE = 32;
    private static final int PRELOAD = 64;

    /** The level showing layer {@code k} of the same world, or null. */
    public static ServerLevel level(MinecraftServer server, EarthChunkGenerator gen, int k) {
        for (ServerLevel l : server.getAllLevels()) {
            if (l.getChunkSource().getGenerator() instanceof EarthChunkGenerator g && g.layer == k
                && g.settings.equals(gen.settings)) {
                return l;
            }
        }
        return null;
    }

    public static void tick(ServerLevel level, EarthChunkGenerator gen) {
        if (gen.settings.deepLayers() == 0) return;
        int floor = level.getMinBuildHeight() + EDGE, ceiling = level.getMaxBuildHeight() - EDGE;
        boolean down = gen.layer < gen.settings.deepLayers(), up = gen.layer > 0;
        List<Entity> movers = new ArrayList<>();
        for (Entity e : level.getAllEntities()) {
            if (e.isPassenger() || e.isRemoved()) continue;
            if ((down && e.getY() < floor) || (up && e.getY() > ceiling)) movers.add(e);
        }
        MinecraftServer server = level.getServer();
        for (Entity e : movers) {
            boolean goDown = e.getY() < floor;
            ServerLevel dest = level(server, gen, gen.layer + (goDown ? 1 : -1));
            if (dest == null) continue;
            double y = e.getY() + (goDown ? 1 : -1) * gen.settings.layerShift();
            move(e, dest, y);
        }
        if (level.getGameTime() % 20 == 0) {
            for (ServerPlayer p : level.players()) {
                int k = p.getY() < floor + PRELOAD && down ? 1 : p.getY() > ceiling - PRELOAD && up ? -1 : 0;
                if (k == 0) continue;
                ServerLevel dest = level(server, gen, gen.layer + k);
                if (dest == null) continue;
                BlockPos target = BlockPos.containing(p.getX(), p.getY() + k * gen.settings.layerShift(), p.getZ());
                dest.getChunkSource().addRegionTicket(TicketType.PORTAL, new ChunkPos(target), 3, target);
            }
        }
    }

    static void move(Entity e, ServerLevel dest, double y) {
        Vec3 v = e.getDeltaMovement();
        float fall = e.fallDistance;
        Entity moved = e.changeDimension(new DimensionTransition(dest, new Vec3(e.getX(), y, e.getZ()), v,
            e.getYRot(), e.getXRot(), DimensionTransition.DO_NOTHING));
        if (moved == null) return;
        moved.fallDistance = fall;
        moved.setDeltaMovement(v);
        moved.hurtMarked = true;
        if (moved instanceof ServerPlayer player) {
            // a player who dug down arrives in rock the layer below has never seen dug: make room
            BlockPos feet = player.blockPosition();
            for (BlockPos pos : new BlockPos[] {feet, feet.above()}) {
                if (!dest.getBlockState(pos).getCollisionShape(dest, pos).isEmpty()) {
                    dest.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                }
            }
        }
    }
}
