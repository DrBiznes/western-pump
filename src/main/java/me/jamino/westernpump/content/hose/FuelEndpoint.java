package me.jamino.westernpump.content.hose;

import dev.ryanhcode.sable.companion.SubLevelAccess;
import me.jamino.westernpump.compat.sable.SubLevelSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Where the nozzle is plugged in.
 *
 * <p>An endpoint is stored as a {@link BlockPos} plus the side that was clicked. That pair is
 * stable for both cases we support:
 * <ul>
 *   <li><b>World block</b> — the position is ordinary world space.</li>
 *   <li><b>Sable vehicle</b> — the position is inside the vehicle's sub-level, which does not
 *       change as the vehicle flies. The world-space position is recomputed on demand by
 *       projecting out of the sub-level.</li>
 * </ul>
 *
 * <p>The sub-level's {@link UUID} is persisted alongside the position so that after a reload we
 * can tell "the same fuel port on the same aircraft" from "some unrelated block that happens to
 * occupy those coordinates now".
 */
public record FuelEndpoint(BlockPos pos, Direction side, @Nullable UUID subLevelId) {

    public static FuelEndpoint of(Level level, BlockPos pos, Direction side) {
        return new FuelEndpoint(pos.immutable(), side, SubLevelSupport.idOf(SubLevelSupport.getContaining(level, pos)));
    }

    /** Centre of the clicked face, in the endpoint's own space (sub-level space for a vehicle). */
    public Vec3 localAnchor() {
        return Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(side.getNormal()).scale(0.5));
    }

    /** Anchor projected into world space; used for range checks and server-side logic. */
    public Vec3 worldAnchor(Level level) {
        return SubLevelSupport.toWorld(level, localAnchor());
    }

    /** Anchor in world space, smoothed for rendering on a moving vehicle. */
    public Vec3 renderAnchor(Level level, float partialTick) {
        return SubLevelSupport.toWorldForRender(level, localAnchor(), partialTick);
    }

    /** True while this endpoint sits inside a Sable sub-level (i.e. a vehicle). */
    public boolean isOnVehicle(Level level) {
        return SubLevelSupport.getContaining(level, pos) != null;
    }

    /**
     * Resolves the fluid handler to fill. Returns {@code null} when the chunk is not loaded, the
     * block is gone, or it simply does not expose the capability on that side — all of which the
     * pump treats as "stop transferring".
     */
    @Nullable
    public IFluidHandler resolveHandler(Level level) {
        if (!level.isLoaded(pos)) {
            return null;
        }
        return level.getCapability(Capabilities.FluidHandler.BLOCK, pos, side);
    }

    /**
     * Whether this endpoint still refers to the thing the player originally clicked. A vehicle
     * that was disassembled (or reassembled into a different sub-level) fails this check, which
     * is what stops a hose from silently re-targeting an unrelated block.
     */
    public boolean isStillValid(Level level) {
        SubLevelAccess sub = SubLevelSupport.getContaining(level, pos);
        UUID currentId = SubLevelSupport.idOf(sub);
        if (!java.util.Objects.equals(currentId, subLevelId)) {
            return false;
        }
        return resolveHandler(level) != null;
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("X", pos.getX());
        tag.putInt("Y", pos.getY());
        tag.putInt("Z", pos.getZ());
        tag.putByte("Side", (byte) side.get3DDataValue());
        if (subLevelId != null) {
            tag.putUUID("SubLevel", subLevelId);
        }
        return tag;
    }

    @Nullable
    public static FuelEndpoint load(@Nullable CompoundTag tag) {
        if (tag == null || !tag.contains("X")) {
            return null;
        }
        BlockPos pos = new BlockPos(tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z"));
        Direction side = Direction.from3DDataValue(tag.getByte("Side"));
        UUID sub = tag.hasUUID("SubLevel") ? tag.getUUID("SubLevel") : null;
        return new FuelEndpoint(pos, side, sub);
    }
}
