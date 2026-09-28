package io.github.lazytive.alosearth;

import io.github.lazytive.alosearth.core.CubeProjection;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Carries entities across the globe's seams: anything that steps past a
 * cube edge that is not joined in the unfolded map is moved to the matching
 * spot on the other side, turned so it keeps its real-world heading, with
 * its velocity rotated to match. Riders travel with their vehicle.
 */
public final class SeamHandler {
    private SeamHandler() {
    }

    private static final int PRELOAD_DISTANCE = 96;

    public static void tick(ServerLevel level) {
        if (!(level.getChunkSource().getGenerator() instanceof EarthChunkGenerator gen)) return;
        CubeProjection p = gen.terrain().projection;
        List<Entity> movers = new ArrayList<>();
        for (Entity e : level.getAllEntities()) {
            if (e.isPassenger() || e.isRemoved()) continue;
            if (p.faceAt(e.getX(), e.getZ()) != null) continue;
            // with Immersive Portals the seam portals carry players across (their client moves them);
            // only step in for someone well past the edge, in case something slipped through
            if (AlosEarth.IMMERSIVE_PORTALS && e instanceof ServerPlayer && p.distanceToSeam(e.getX(), e.getZ()) < 32) continue;
            movers.add(e);
        }
        for (Entity e : movers) cross(level, p, e);
        if (level.getGameTime() % 20 == 0) {
            for (ServerPlayer player : level.players()) preload(level, p, player);
        }
    }

    /** Moves an entity that is off every face; returns true if it was moved. */
    public static boolean cross(ServerLevel level, CubeProjection p, Entity e) {
        double x = e.getX(), z = e.getZ();
        CubeProjection.Link l = p.linkAt(x, z);
        if (l == null) {
            // beyond a cube corner or outside the map: step back onto the nearest face
            CubeProjection.Face best = null;
            double bd = Double.MAX_VALUE;
            for (CubeProjection.Face f : p.faces) {
                double dx = Math.max(0, Math.max(f.x0 - x, x - f.x1())), dz = Math.max(0, Math.max(f.z0 - z, z - f.z1()));
                double d = dx * dx + dz * dz;
                if (d < bd) {
                    bd = d;
                    best = f;
                }
            }
            double tx = Math.max(best.x0 + 0.5, Math.min(best.x1() - 0.5, x));
            double tz = Math.max(best.z0 + 0.5, Math.min(best.z1() - 0.5, z));
            move(level, e, tx, e.getY(), tz, 0, 1, 0, 0, 1);
            return true;
        }
        CubeProjection.Face d = l.dest;
        double tx = Math.max(d.x0 + 1e-3, Math.min(d.x1() - 1e-3, l.applyX(x, z)));
        double tz = Math.max(d.z0 + 1e-3, Math.min(d.z1() - 1e-3, l.applyZ(x, z)));
        move(level, e, tx, e.getY(), tz, l.yaw, l.m00, l.m01, l.m10, l.m11);
        return true;
    }

    private static void move(ServerLevel level, Entity e, double x, double y, double z, float dYaw,
                             int m00, int m01, int m10, int m11) {
        List<Entity> riders = new ArrayList<>(e.getPassengers());
        for (Entity r : riders) r.stopRiding();

        Vec3 v = e.getDeltaMovement();
        Vec3 nv = new Vec3(m00 * v.x + m01 * v.z, v.y, m10 * v.x + m11 * v.z);
        float yaw = e.getYRot() + dYaw;
        if (e instanceof ServerPlayer player) {
            player.teleportTo(level, x, y, z, yaw, player.getXRot());
            clearSpace(level, player);
        } else {
            e.teleportTo(x, y, z);
            e.setYRot(yaw);
            e.setYHeadRot(e.getYHeadRot() + dYaw);
            if (e instanceof LivingEntity living) living.setYBodyRot(yaw);
        }
        e.setDeltaMovement(nv);
        e.hurtMarked = true;

        for (Entity r : riders) {
            move(level, r, x, y + (r.getY() - e.getY()), z, dYaw, m00, m01, m10, m11);
            r.startRiding(e, true);
        }
    }

    /** A player who tunnelled through a seam arrives in untouched rock: make room. */
    private static void clearSpace(ServerLevel level, ServerPlayer player) {
        BlockPos feet = player.blockPosition();
        for (BlockPos pos : new BlockPos[] {feet, feet.above()}) {
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
                level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            }
        }
    }

    /** Keep the chunks on the far side of a nearby seam loaded so crossing is instant. */
    private static void preload(ServerLevel level, CubeProjection p, ServerPlayer player) {
        double x = player.getX(), z = player.getZ();
        for (CubeProjection.Link l : p.links) {
            CubeProjection.Face f = l.face;
            double px, pz;
            switch (l.edge) {
                case "top" -> { px = x; pz = f.z0 - 1; }
                case "bottom" -> { px = x; pz = f.z1() + 1; }
                case "left" -> { px = f.x0 - 1; pz = z; }
                default -> { px = f.x1() + 1; pz = z; }
            }
            if (Math.abs(px - x) + Math.abs(pz - z) > PRELOAD_DISTANCE || !l.contains(px, pz)) continue;
            BlockPos target = BlockPos.containing(l.applyX(px, pz), player.getY(), l.applyZ(px, pz));
            level.getChunkSource().addRegionTicket(TicketType.PORTAL, new ChunkPos(target), 3, target);
        }
    }
}
