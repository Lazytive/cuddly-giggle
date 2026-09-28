package io.github.lazytive.alosearth;

import io.github.lazytive.alosearth.core.CubeProjection;
import io.github.lazytive.alosearth.core.Terrain;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;

/**
 * New worlds have no bedrock floor. Anything that falls out of the bottom of the world (the lowest
 * deep layer in 1:1 worlds) comes out on the opposite side of the Earth, at the antipode: latitude
 * flipped, longitude turned half way round. It bursts out of a small hole in the ground there and
 * is thrown up and forward (the way it was facing), so it lands clear of the hole, with a few
 * seconds of slow falling so the landing doesn't hurt.
 */
public final class ThroughTheEarth {
    private ThroughTheEarth() {
    }

    /** How far below the floor something falls before it goes through (vanilla's void kills at 64). */
    static final int DEPTH = 8;

    public static void tick(ServerLevel level, EarthChunkGenerator gen) {
        if (!gen.settings.minecraftFeel() || gen.layer != gen.settings.deepLayers()) return;
        int floor = level.getMinBuildHeight() - DEPTH;
        List<Entity> fallers = new ArrayList<>();
        for (Entity e : level.getAllEntities()) {
            if (!e.isPassenger() && !e.isRemoved() && e.getY() < floor) fallers.add(e);
        }
        for (Entity e : fallers) send(level, gen, e);
    }

    /** Moves an entity to the surface at its antipode; returns it (possibly a new instance), or null. */
    static Entity send(ServerLevel level, EarthChunkGenerator gen, Entity e) {
        Terrain t = gen.terrain();
        double[] ll = new double[2];
        if (t.projection.inverse(e.getX(), e.getZ(), ll) == CubeProjection.OUTSIDE) return null;
        double lon = ll[0] + 180, lat = -ll[1];
        if (lon > 180) lon -= 360;
        double[] p = new double[2];
        t.projection.forward(lon, lat, p);
        int x = (int) Math.floor(p[0]), z = (int) Math.floor(p[1]);
        ServerLevel dest = gen.layer == 0 ? level : LayerHandler.level(level.getServer(), gen, 0);
        if (dest == null) return null;
        int top = t.top(x, z), y = t.surface(x, z) + 1;
        boolean dry = t.surface(x, z) == top;
        dest.getChunkSource().addRegionTicket(TicketType.PORTAL, new ChunkPos(new BlockPos(x, y, z)), 3, new BlockPos(x, y, z));
        Vec3 to = new Vec3(x + 0.5, y, z + 0.5);
        Entity moved;
        if (dest == level) {
            e.teleportTo(to.x, to.y, to.z);
            moved = e;
        } else {
            moved = e.changeDimension(new DimensionTransition(dest, to, Vec3.ZERO, e.getYRot(), e.getXRot(),
                DimensionTransition.DO_NOTHING));
        }
        if (moved == null) return null;
        if (dry) { // the exit hole it came up through
            for (int k = 0; k < 3; k++) {
                BlockPos hole = new BlockPos(x, top - k, z);
                if (!dest.getBlockState(hole).is(net.minecraft.world.level.block.Blocks.BEDROCK)) {
                    dest.setBlockAndUpdate(hole, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                }
            }
        }
        double yaw = Math.toRadians(moved.getYRot());
        moved.setDeltaMovement(-Math.sin(yaw) * 0.9, 1.1, Math.cos(yaw) * 0.9); // up and forward, clear of the hole
        moved.fallDistance = 0;
        moved.hurtMarked = true;
        if (moved instanceof net.minecraft.world.entity.LivingEntity living) {
            living.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.SLOW_FALLING, 120, 0));
        }
        if (moved instanceof ServerPlayer player) {
            String where = String.format(Locale.ROOT, "%.2f%s %.2f%s", Math.abs(lat), lat >= 0 ? "N" : "S", Math.abs(lon),
                lon >= 0 ? "E" : "W");
            player.sendSystemMessage(Component.literal("You fell through the Earth and came out on the other side, at "
                + where + (y <= gen.settings.seaLevel() + 1 ? " (in the sea)" : "") + "."));
        }
        return moved;
    }
}
