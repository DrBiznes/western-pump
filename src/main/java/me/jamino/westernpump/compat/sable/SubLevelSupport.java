package me.jamino.westernpump.compat.sable;

import dev.ryanhcode.sable.companion.ClientSubLevelAccess;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Position;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Thin wrapper over the Sable Companion API.
 *
 * <p>Sable moves a vehicle's blocks into a "sub-level" whose contents sit at fixed block
 * positions while the vehicle itself flies around the world. A fuel port bolted to a plane
 * therefore keeps a constant {@link BlockPos} even though its rendered position changes every
 * frame. Every method here converts between those two spaces.
 *
 * <p><b>No mod-loaded guards are needed.</b> Sable Companion is an API shim that registers a
 * no-op implementation through {@code META-INF/services}, so {@code SableCompanion.INSTANCE}
 * is present even when Sable is not installed. In that case {@link #getContaining} returns
 * {@code null} and {@link #toWorld} is the identity function, which degrades this whole class
 * to plain world coordinates — exactly the behaviour we want without Sable.
 */
public final class SubLevelSupport {
    private SubLevelSupport() {
    }

    /** The sub-level containing {@code pos}, or {@code null} if it is ordinary world space. */
    @Nullable
    public static SubLevelAccess getContaining(Level level, Vec3 pos) {
        return SableCompanion.INSTANCE.getContaining(level, pos);
    }

    @Nullable
    public static SubLevelAccess getContaining(Level level, BlockPos pos) {
        return SableCompanion.INSTANCE.getContaining(level, pos);
    }

    /**
     * Projects a position out of any sub-level into world space, where it can be rendered and
     * compared against positions belonging to other sub-levels. Returns {@code pos} unchanged
     * when it is not inside a sub-level.
     */
    public static Vec3 toWorld(Level level, Vec3 pos) {
        // The Position overload, not the Vec3 one: the latter is deprecated for removal in 2.0.0.
        return SableCompanion.INSTANCE.projectOutOfSubLevel(level, (Position) pos);
    }

    /**
     * Client-only, interpolated variant of {@link #toWorld}. Sable ticks sub-level poses at
     * 20 Hz, so rendering with the raw logical pose makes an attached hose visibly stutter on a
     * moving vehicle. {@code renderPose(partialTick)} gives the smoothed pose instead.
     */
    public static Vec3 toWorldForRender(Level level, Vec3 pos, float partialTick) {
        SubLevelAccess sub = SableCompanion.INSTANCE.getContaining(level, pos);
        if (sub instanceof ClientSubLevelAccess client) {
            return client.renderPose(partialTick).transformPosition(pos);
        }
        return toWorld(level, pos);
    }

    /**
     * Rotates a <i>direction</i> out of the sub-level containing {@code anchor} into world space.
     *
     * <p>This is the counterpart to {@link #toWorld} and is needed wherever an orientation, not
     * just a point, comes from inside a sub-level. A {@link net.minecraft.core.Direction} constant
     * such as {@code EAST} means "east along the sub-level's own grid"; once the vehicle has any
     * yaw or pitch that is not east in the world, so anything built from a raw face normal ends up
     * axis-aligned to the world while the vehicle it is bolted to is tilted.
     *
     * <p>{@code anchor} is only used to find the sub-level ({@code getContaining} is chunk-based
     * and so needs a position); {@code dir} is the direction actually transformed. Outside a
     * sub-level this is the identity, so it degrades to plain world directions without Sable.
     *
     * <p>Sable's {@code transformNormal} also applies the pose's scale, so the result is not unit
     * length under a scaled sub-level. Callers that need a basis vector should normalise — note
     * that {@link me.jamino.westernpump.content.pump.NozzleAnimation.Pose} already does.
     */
    public static Vec3 directionToWorld(Level level, Vec3 anchor, Vec3 dir) {
        SubLevelAccess sub = SableCompanion.INSTANCE.getContaining(level, anchor);
        return sub == null ? dir : sub.logicalPose().transformNormal(dir);
    }

    /**
     * Client-only, interpolated variant of {@link #directionToWorld}, for the same reason
     * {@link #toWorldForRender} exists: the logical pose only updates at 20 Hz, so a nozzle
     * oriented from it visibly snaps between ticks on a moving vehicle.
     */
    public static Vec3 directionToWorldForRender(Level level, Vec3 anchor, Vec3 dir, float partialTick) {
        SubLevelAccess sub = SableCompanion.INSTANCE.getContaining(level, anchor);
        if (sub instanceof ClientSubLevelAccess client) {
            return client.renderPose(partialTick).transformNormal(dir);
        }
        return directionToWorld(level, anchor, dir);
    }

    /**
     * Distance squared that stays correct when the two points live in different sub-levels
     * (Sable projects both into a common space first). Used for hose range checks so a plane
     * flying away from the pump actually breaks the connection.
     */
    public static double distanceSqr(Level level, Vec3 a, Vec3 b) {
        return SableCompanion.INSTANCE.distanceSquaredWithSubLevels(level, a, b);
    }

    /** Stable identity for a sub-level, persisted so a connection survives a reload. */
    @Nullable
    public static UUID idOf(@Nullable SubLevelAccess sub) {
        return sub == null ? null : sub.getUniqueId();
    }

    /** Whether Sable is actually present; only for diagnostics and tooltips, never control flow. */
    public static boolean sableInstalled() {
        return net.neoforged.fml.ModList.get().isLoaded("sable");
    }
}
