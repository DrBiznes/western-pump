package me.jamino.westernpump.content.pump;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * Fixed points on the pump model, shared by the block entity (hose anchor, range checks) and the
 * renderer (nozzle cradle pose). All numbers are block pixels in the model's own frame, which is
 * the NORTH-facing pump with the lower block at the origin, and mirror
 * {@code tools/generate_art.py} (SPIGOT_ANCHOR_PX / HOLSTER_INLET_PX / NOZZLE_INLET_LOCAL).
 *
 * <p>Blockstate rotation {@code y=90/180/270} for EAST/SOUTH/WEST is reproduced here so a model
 * point can be turned into a world point without re-deriving vanilla's rotation conventions in
 * more than one place.
 */
public final class PumpGeometry {

    /** Hose origin: 1 px outside the spigot stub on the upper block's east side, towards the front. */
    public static final Vec3 SPIGOT_ANCHOR = px(17.5, 26, 5);
    /** Coupler inlet when racked in the holster on the upper block's east side. */
    public static final Vec3 HOLSTER_INLET = px(20.5, 29.25, 8);
    /** Inlet point in the coupler partial model's own space (the model's face points down -Y). */
    public static final Vec3 NOZZLE_INLET_LOCAL = px(8, 12, 8);
    /** Distance from the coupler's hose inlet to its locking face, in blocks. */
    public static final double NOZZLE_LENGTH = 12 / 16.0;

    private PumpGeometry() {
    }

    private static Vec3 px(double x, double y, double z) {
        return new Vec3(x / 16.0, y / 16.0, z / 16.0);
    }

    /** Rotation angle, in degrees about +Y, that vanilla applies for this facing's blockstate. */
    public static float yRotationDegrees(Direction facing) {
        return switch (facing) {
            case EAST -> 90f;
            case SOUTH -> 180f;
            case WEST -> 270f;
            default -> 0f;
        };
    }

    /**
     * Rotates a direction vector from the NORTH-facing model frame to the given facing.
     * Matches blockstate {@code y} rotation, which maps north (-z) to east (+x) for {@code y=90}.
     */
    public static Vec3 rotate(Direction facing, Vec3 v) {
        return switch (facing) {
            case EAST -> new Vec3(-v.z, v.y, v.x);
            case SOUTH -> new Vec3(-v.x, v.y, -v.z);
            case WEST -> new Vec3(v.z, v.y, -v.x);
            default -> v;
        };
    }

    /** Model-frame point (blocks, lower block at origin) to world space for a pump at {@code lowerPos}. */
    public static Vec3 toWorld(BlockPos lowerPos, Direction facing, Vec3 modelPoint) {
        Vec3 centred = modelPoint.subtract(0.5, 0, 0.5);
        Vec3 rotated = rotate(facing, centred);
        return new Vec3(lowerPos.getX() + 0.5 + rotated.x, lowerPos.getY() + rotated.y, lowerPos.getZ() + 0.5 + rotated.z);
    }
}
