package io.github.lazytive.alosearth.compat;

import io.github.lazytive.alosearth.AlosEarth;
import io.github.lazytive.alosearth.EarthChunkGenerator;
import io.github.lazytive.alosearth.core.CubeProjection;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage;
import qouteall.q_misc_util.my_util.DQuaternion;

/**
 * With Immersive Portals installed, every seam of the globe gets a see-through portal: a wall the
 * height of the world along the seam edge, showing (and leading to) the matching place on the other
 * side, turned the same way the seam turns. The world then has no visible edges or coordinate
 * jumps you notice while walking. Only loaded when Immersive Portals is present.
 */
public final class ImmersivePortalsSeams {
    private ImmersivePortalsSeams() {
    }

    public static final String TAG = "alosearth:seam";

    /** (Re)creates the seam portals in every ALOS Earth level. */
    public static void setUp(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            if (!(level.getChunkSource().getGenerator() instanceof EarthChunkGenerator gen)) continue;
            GlobalPortalStorage storage = GlobalPortalStorage.get(level);
            storage.removePortals(p -> TAG.equals(p.portalTag));
            int n = 0;
            for (CubeProjection.Link l : gen.terrain().projection.links) {
                storage.addPortal(create(level, l));
                n++;
            }
            storage.onDataChanged();
            AlosEarth.LOG.info("Immersive Portals: {} seam portals in {}", n, level.dimension().location());
        }
    }

    static Portal create(ServerLevel level, CubeProjection.Link l) {
        double minY = level.getMinBuildHeight(), height = level.getHeight(), cy = minY + height / 2;
        Vec3 up = new Vec3(0, 1, 0), normal; // the normal points into the face the portal belongs to
        double ox, oz, width;
        switch (l.edge) {
            case "top" -> { normal = new Vec3(0, 0, 1); ox = (l.sx0 + l.sx1) / 2.0; oz = l.sz1; width = l.sx1 - l.sx0; }
            case "bottom" -> { normal = new Vec3(0, 0, -1); ox = (l.sx0 + l.sx1) / 2.0; oz = l.sz0; width = l.sx1 - l.sx0; }
            case "left" -> { normal = new Vec3(1, 0, 0); ox = l.sx1; oz = (l.sz0 + l.sz1) / 2.0; width = l.sz1 - l.sz0; }
            default -> { normal = new Vec3(-1, 0, 0); ox = l.sx0; oz = (l.sz0 + l.sz1) / 2.0; width = l.sz1 - l.sz0; }
        }
        Vec3 axisW = up.cross(normal); // so that axisW x axisH = normal
        Portal portal = Portal.ENTITY_TYPE.create(level);
        portal.setOriginPos(new Vec3(ox, cy, oz));
        portal.setDestinationDimension(level.dimension());
        portal.setDestination(new Vec3(l.applyX(ox, oz), cy, l.applyZ(ox, oz)));
        portal.setOrientationAndSize(axisW, up, width, height);
        // the seam's turn about the vertical axis: x' = cos*x + sin*z, z' = -sin*x + cos*z
        portal.setRotation(DQuaternion.rotationByRadians(up, Math.atan2(l.m01, l.m00)));
        portal.portalTag = TAG;
        return portal;
    }

    /** For the in-game self-test: every seam has a portal whose transform matches the seam's. */
    public static String check(ServerLevel level, EarthChunkGenerator gen) {
        List<Portal> portals = GlobalPortalStorage.getGlobalPortals(level).stream().filter(p -> TAG.equals(p.portalTag)).toList();
        CubeProjection pr = gen.terrain().projection;
        if (portals.size() != pr.links.length) return "expected " + pr.links.length + " seam portals, found " + portals.size();
        for (CubeProjection.Link l : pr.links) {
            double x = (l.sx0 + l.sx1) / 2.0 + 3.3, z = (l.sz0 + l.sz1) / 2.0 - 2.7, y = 70.5;
            Vec3 want = new Vec3(l.applyX(x, z), y, l.applyZ(x, z));
            boolean found = false;
            for (Portal p : portals) {
                Vec3 edge = p.getOriginPos();
                boolean mine = switch (l.edge) {
                    case "top" -> edge.z == l.sz1 && edge.x == (l.sx0 + l.sx1) / 2.0;
                    case "bottom" -> edge.z == l.sz0 && edge.x == (l.sx0 + l.sx1) / 2.0;
                    case "left" -> edge.x == l.sx1 && edge.z == (l.sz0 + l.sz1) / 2.0;
                    default -> edge.x == l.sx0 && edge.z == (l.sz0 + l.sz1) / 2.0;
                };
                if (!mine) continue;
                found = true;
                Vec3 got = p.transformPoint(new Vec3(x, y, z));
                if (got.distanceTo(want) > 1e-3) return "portal for " + l + " maps to " + got + ", expected " + want;
                Vec3 inside = new Vec3((l.face.x0 + l.face.x1()) / 2.0, y, (l.face.z0 + l.face.z1()) / 2.0);
                if (!p.isInFrontOfPortal(inside)) return "portal for " + l + " faces away from its face";
            }
            if (!found) return "no portal for " + l;
        }
        return null;
    }
}
