package me.jamino.westernpump.compat.sable;

import dev.ryanhcode.sable.companion.ClientSubLevelAccess;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.core.BlockPos;
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
        return SableCompanion.INSTANCE.projectOutOfSubLevel(level, pos);
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
