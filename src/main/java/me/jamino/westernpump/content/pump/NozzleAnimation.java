package me.jamino.westernpump.content.pump;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Client-side smoothing of the nozzle between its three resting places (holster, hand, tank).
 *
 * <p>The server only ever says which state the nozzle is in. When that changes, the client
 * remembers where the nozzle was drawn last and slides it to the new place over a few ticks, so
 * lifting it from the cradle, latching onto a tank and retracting all read as motion instead of a
 * teleport. The hose end follows the interpolated inlet, which is what makes the hose look like it
 * is being pulled out and reeled back in.
 *
 * <p>Pure data: no client classes are referenced so the block entity can own one on either side.
 */
public class NozzleAnimation {

    /** Ticks a transition takes. */
    private static final float DURATION = 8f;

    /** A resting or interpolated nozzle pose. */
    public record Pose(Vec3 inlet, Vec3 spout, Vec3 up) {
        public Pose {
            spout = spout.normalize();
            up = orthonormal(up, spout);
        }

        private static Vec3 orthonormal(Vec3 up, Vec3 spout) {
            Vec3 u = up.subtract(spout.scale(up.dot(spout)));
            if (u.lengthSqr() < 1.0e-6) {
                u = Math.abs(spout.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(0, 0, 1);
                u = u.subtract(spout.scale(u.dot(spout)));
            }
            return u.normalize();
        }

        Pose lerpTo(Pose to, float t) {
            return new Pose(
                    inlet.lerp(to.inlet, t),
                    spout.lerp(to.spout, t).lengthSqr() < 1.0e-6 ? to.spout : spout.lerp(to.spout, t),
                    up.lerp(to.up, t).lengthSqr() < 1.0e-6 ? to.up : up.lerp(to.up, t));
        }
    }

    @Nullable
    private FuelPumpBlockEntity.NozzleState lastState;
    @Nullable
    private Pose from;
    @Nullable
    private Pose current;
    private float startTime;

    /**
     * Advances the animation towards {@code target} for the given state and returns the pose to
     * draw this frame. {@code renderTime} is in ticks (with partial ticks folded in).
     */
    public Pose update(FuelPumpBlockEntity.NozzleState state, Pose target, float renderTime) {
        if (lastState == null || current == null) {
            lastState = state;
            current = target;
            return current;
        }
        if (state != lastState) {
            lastState = state;
            from = current;
            startTime = renderTime;
        }
        if (from == null) {
            current = target;
            return current;
        }
        float t = (renderTime - startTime) / DURATION;
        if (t >= 1f) {
            from = null;
            current = target;
            return current;
        }
        t = Mth.clamp(t, 0f, 1f);
        // Smoothstep: eases out of the cradle and into the socket rather than snapping.
        t = t * t * (3f - 2f * t);
        current = from.lerpTo(target, t);
        return current;
    }

    /** Last pose handed out, for other renderers that want to start a line at the nozzle. */
    @Nullable
    public Pose current() {
        return current;
    }
}
