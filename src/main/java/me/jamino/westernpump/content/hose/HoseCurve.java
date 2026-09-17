package me.jamino.westernpump.content.hose;

import net.minecraft.world.phys.Vec3;

/**
 * A hanging hose between two anchors, shaped as a real catenary (the curve a heavy cable makes
 * under its own weight) rather than a guessed Bezier.
 *
 * <p>The curve is {@code y = a * cosh((x - b) / a) + c}, solved so that the arc length equals the
 * hose's slack length and both ends land exactly on their anchors. Solving for {@code a} has no
 * closed form, so it is found with a few Newton iterations.
 *
 * <p>Adapted from Create: Power Grid's {@code CurveParameters}
 * (https://github.com/patryk3211/PowerGrid, Apache License 2.0, Copyright 2025 patryk3211),
 * which in turn follows https://math.stackexchange.com/questions/3557767. Reimplemented here for
 * a fuel hose: no electrical state, and the hose length is derived from the anchors plus a slack
 * factor instead of being placed by the player.
 */
public class HoseCurve {
    /**
     * How far the middle of the hose hangs below a straight line between the anchors, in blocks.
     * Grows with distance but flattens off, so a short hose is not a tight string and a long one
     * does not drag on the floor. Tuned by solving for the slack length that produces this sag.
     */
    private static double targetSag(double distance) {
        return Math.min(0.30 + 0.16 * Math.sqrt(distance), 1.25);
    }

    private double a;
    private double b;
    private double c;
    /** Horizontal unit direction from anchor 1 to anchor 2. */
    private final Vec3 normal;
    /** Horizontal span between the anchors. */
    private final double dx;
    /** Vertical rise between the anchors. */
    private final double dy;
    /** Arc length of the hose. */
    public final double length;
    public final float thickness;
    /** False when the anchors are further apart than the hose is long (it would have to stretch). */
    public boolean valid = true;

    public HoseCurve(Vec3 from, Vec3 to, double length, float thickness) {
        Vec3 horizontal = new Vec3(to.x - from.x, 0, to.z - from.z);
        this.dx = horizontal.length();
        this.dy = to.y - from.y;
        this.normal = dx > 1.0e-4 ? horizontal.scale(1 / dx) : new Vec3(0, 0, 0);
        this.length = length;
        this.thickness = thickness;
        solve();
    }

    /**
     * Builds the curve for two anchors, picking the slack length that yields {@link #targetSag}.
     *
     * <p>Sag is not a simple function of slack, so it is inverted by bisection. The search is on a
     * monotonic quantity over a bracketed range and runs a fixed 32 iterations, which converges far
     * past what is visible and keeps the cost predictable — this is called only when a hose is
     * created or its anchors move, not per frame.
     */
    public static HoseCurve between(Vec3 from, Vec3 to, float thickness) {
        double straight = from.distanceTo(to);
        if (straight < 1.0e-3) {
            return new HoseCurve(from, to, 0.35, thickness);
        }
        double target = targetSag(straight);

        double lo = straight + 1.0e-3;
        double hi = straight * 1.5 + 2.0;
        for (int i = 0; i < 32; i++) {
            double mid = (lo + hi) * 0.5;
            HoseCurve probe = new HoseCurve(from, to, mid, thickness);
            if (!probe.valid || -probe.lowestPoint() < target) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return new HoseCurve(from, to, (lo + hi) * 0.5, thickness);
    }

    private void solve() {
        if (dx <= 1.0e-4) {
            // Purely vertical drop: the catenary degenerates, so the renderer walks straight down.
            a = b = c = 0;
            return;
        }

        double r = Math.sqrt(length * length - dy * dy) / dx;
        if (Double.isNaN(r) || r <= 1) {
            // Anchors are further apart than the hose length; the curve cannot be formed.
            valid = false;
            a = b = c = 0;
            return;
        }

        // Initial guess, then Newton's method on sinh(A) - r*A = 0.
        double A = r < 3 ? Math.sqrt(6 * (r - 1)) : Math.log(2 * r) + Math.log(Math.log(2 * r));
        if (Double.isNaN(A)) {
            valid = false;
            return;
        }
        for (int i = 0; i < 6; i++) {
            double top = Math.sinh(A) - r * A;
            double bot = Math.cosh(A) - r;
            if (Math.abs(bot) < 1.0e-9) {
                break;
            }
            A -= top / bot;
        }
        if (Double.isNaN(A) || A <= 0) {
            valid = false;
            return;
        }

        a = dx / (2 * A);
        double z = dy / length;
        b = -a * 0.5 * Math.log((1 + z) / (1 - z));
        c = 0.5 * (dy - length / Math.tanh(A));
        if (Double.isNaN(a) || Double.isNaN(b) || Double.isNaN(c)) {
            valid = false;
        }
    }

    /** Height of the curve at horizontal offset {@code x}, measured from the midpoint. */
    public double heightAt(double x) {
        if (dx <= 1.0e-4) {
            return 0;
        }
        return a * Math.cosh((x - b) / a) + c;
    }

    public boolean isVertical() {
        return dx <= 1.0e-4;
    }

    /**
     * Samples {@code segments + 1} points along the hose, relative to the first anchor.
     * The renderer strings a tube through these.
     */
    public Vec3[] sample(int segments) {
        Vec3[] points = new Vec3[segments + 1];
        if (isVertical()) {
            for (int i = 0; i <= segments; i++) {
                points[i] = new Vec3(0, dy * i / (double) segments, 0);
            }
            return points;
        }
        double half = dx / 2;
        double baseline = heightAt(-half);
        for (int i = 0; i <= segments; i++) {
            double x = -half + dx * i / (double) segments;
            double y = heightAt(x) - baseline;
            double along = x + half;
            points[i] = new Vec3(normal.x * along, y, normal.z * along);
        }
        return points;
    }

    /** Lowest point of the hose relative to the first anchor; used to size render bounds. */
    public double lowestPoint() {
        if (isVertical()) {
            return Math.min(0, dy);
        }
        double half = dx / 2;
        double baseline = heightAt(-half);
        // The minimum of cosh sits at x = b, clamped to the span we actually draw.
        double x = Math.max(-half, Math.min(half, b));
        return Math.min(0, heightAt(x) - baseline);
    }
}
