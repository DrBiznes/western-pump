package me.jamino.westernpump.content.pump;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.fluid.SmartFluidTankBehaviour;
import me.jamino.westernpump.compat.sable.SubLevelSupport;
import me.jamino.westernpump.config.PumpConfig;
import me.jamino.westernpump.content.hose.FuelEndpoint;
import me.jamino.westernpump.content.hose.HoseCurve;
import me.jamino.westernpump.util.PumpLang;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * The fuel pump's server-side brain: it holds the fuel buffer, remembers where the nozzle is
 * plugged in, and moves fuel while a connection is live.
 *
 * <p>This block entity lives on the <b>lower</b> half of the two-tall pump. The upper half
 * forwards its interactions down here (see {@link FuelPumpBlock}), so there is exactly one
 * authority per pump.
 *
 * <p>The pump never creates fuel. Its buffer is filled through the ordinary fluid capability by an
 * upstream Create network (tank -> Mechanical Pump -> pipes), and is drained only into a target
 * that reports it can accept the fluid.
 */
public class FuelPumpBlockEntity extends SmartBlockEntity implements IHaveGoggleInformation {

    /** Nozzle lifecycle. Only the server changes this; clients receive it for rendering. */
    public enum NozzleState {
        RACKED,
        HELD,
        ATTACHED
    }

    private SmartFluidTankBehaviour buffer;

    private NozzleState nozzleState = NozzleState.RACKED;
    /** Who is holding or owns the nozzle; only this player may operate it. */
    @Nullable
    private UUID holder;
    @Nullable
    private FuelEndpoint target;

    /** Last reason transfer stopped, surfaced through the goggle tooltip. */
    private Status status = Status.IDLE;
    /** mB moved on the previous tick, for the flow indicator and sounds. */
    private int lastTransferred;

    public enum Status {
        IDLE,
        FLOWING,
        SOURCE_EMPTY,
        TARGET_FULL,
        FLUID_MISMATCH,
        OUT_OF_RANGE,
        TARGET_GONE
    }

    public FuelPumpBlockEntity(net.minecraft.world.level.block.entity.BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        setLazyTickRate(20);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        // Insertion only: Create pipes push fuel in, the nozzle takes it out through our own code.
        // Forbidding extraction stops other pipes from siphoning the buffer back out.
        buffer = SmartFluidTankBehaviour.single(this, PumpConfig.bufferCapacity)
                .allowInsertion()
                .forbidExtraction()
                .whenFluidUpdates(this::onFluidUpdated);
        behaviours.add(buffer);
    }

    private void onFluidUpdated() {
        setChanged();
    }

    public IFluidHandler getFluidCapability() {
        return buffer.getCapability();
    }

    public FluidStack getBufferContents() {
        return buffer.getPrimaryHandler().getFluid();
    }

    public NozzleState getNozzleState() {
        return nozzleState;
    }

    public Status getStatus() {
        return status;
    }

    public int getLastTransferred() {
        return lastTransferred;
    }

    @Nullable
    public FuelEndpoint getTarget() {
        return target;
    }

    @Nullable
    public UUID getHolder() {
        return holder;
    }

    public boolean isHeldBy(Player player) {
        return holder != null && holder.equals(player.getUUID());
    }

    /** World-space point the hose leaves the pump from: the collar on the cabinet's front. */
    public Vec3 getHoseAnchor() {
        Direction facing = getBlockState().hasProperty(FuelPumpBlock.FACING)
                ? getBlockState().getValue(FuelPumpBlock.FACING)
                : Direction.NORTH;
        Vec3 centre = Vec3.atCenterOf(worldPosition);
        Vec3 out = Vec3.atLowerCornerOf(facing.getNormal()).scale(0.45);
        // The collar sits near the top of the lower block, where the hose reel is modelled.
        return centre.add(out).add(0, 0.45, 0);
    }

    // ---- nozzle state machine -----------------------------------------------------------

    public void pickUpNozzle(Player player) {
        nozzleState = NozzleState.HELD;
        holder = player.getUUID();
        target = null;
        status = Status.IDLE;
        notifyUpdate();
    }

    public void returnNozzle() {
        nozzleState = NozzleState.RACKED;
        holder = null;
        target = null;
        status = Status.IDLE;
        lastTransferred = 0;
        me.jamino.westernpump.content.nozzle.HeldNozzleTracker.clearByPump(worldPosition);
        notifyUpdate();
    }

    public void attachTo(FuelEndpoint endpoint) {
        target = endpoint;
        nozzleState = NozzleState.ATTACHED;
        status = Status.IDLE;
        notifyUpdate();
    }

    /** True when the endpoint is within the configured hose range of this pump. */
    public boolean isInRange(FuelEndpoint endpoint) {
        if (level == null) {
            return false;
        }
        double distSqr = SubLevelSupport.distanceSqr(level, getHoseAnchor(), endpoint.worldAnchor(level));
        return distSqr <= PumpConfig.maxHoseLengthSqr();
    }

    // ---- transfer -----------------------------------------------------------------------

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide) {
            return;
        }
        if (nozzleState != NozzleState.ATTACHED || target == null) {
            if (lastTransferred != 0) {
                lastTransferred = 0;
                notifyUpdate();
            }
            return;
        }
        transferTick();
    }

    private void transferTick() {
        Status previous = status;
        int moved = doTransfer();

        // doTransfer() detaches (and syncs) by itself when the connection dies; nothing more to do.
        if (nozzleState != NozzleState.ATTACHED) {
            lastTransferred = 0;
            return;
        }

        boolean statusChanged = previous != status;
        boolean flowChanged = (moved > 0) != (lastTransferred > 0);
        lastTransferred = moved;

        if (statusChanged || flowChanged) {
            // Only sync on a meaningful change, never every tick while fuel flows.
            notifyUpdate();
        } else if (moved > 0) {
            setChanged();
        }

        if (status == Status.TARGET_FULL && PumpConfig.autoDetachWhenFull) {
            detachAndRetract();
        }
    }

    /**
     * Moves up to the configured rate from the buffer into the target, and returns how much
     * actually moved. Everything is simulated first so the buffer is only drained by an amount the
     * target has committed to accept — this is what prevents both fuel duplication and voiding.
     */
    private int doTransfer() {
        if (level == null || target == null) {
            return 0;
        }

        if (!target.isStillValid(level)) {
            status = Status.TARGET_GONE;
            detachAndRetract();
            return 0;
        }
        if (!isInRange(target)) {
            status = Status.OUT_OF_RANGE;
            detachAndRetract();
            return 0;
        }

        IFluidHandler receiver = target.resolveHandler(level);
        if (receiver == null) {
            status = Status.TARGET_GONE;
            detachAndRetract();
            return 0;
        }

        var tank = buffer.getPrimaryHandler();
        FluidStack available = tank.getFluid();
        if (available.isEmpty()) {
            status = Status.SOURCE_EMPTY;
            return 0;
        }

        FluidStack offer = available.copyWithAmount(Math.min(available.getAmount(), PumpConfig.transferRate));
        int accepted = receiver.fill(offer, IFluidHandler.FluidAction.SIMULATE);
        if (accepted <= 0) {
            // Distinguish "no room" from "wrong fuel" so the player gets a useful message.
            status = acceptsFluidAtAll(receiver, offer) ? Status.TARGET_FULL : Status.FLUID_MISMATCH;
            return 0;
        }

        FluidStack drained = tank.drain(accepted, IFluidHandler.FluidAction.EXECUTE);
        if (drained.isEmpty()) {
            status = Status.SOURCE_EMPTY;
            return 0;
        }
        int filled = receiver.fill(drained, IFluidHandler.FluidAction.EXECUTE);
        if (filled < drained.getAmount()) {
            // The target accepted less than it promised; put the remainder back rather than void it.
            FluidStack leftover = drained.copyWithAmount(drained.getAmount() - filled);
            tank.fill(leftover, IFluidHandler.FluidAction.EXECUTE);
        }

        status = filled > 0 ? Status.FLOWING : Status.TARGET_FULL;
        return filled;
    }

    /** Whether the receiver would take this fluid if it had room, used to classify a refusal. */
    private boolean acceptsFluidAtAll(IFluidHandler receiver, FluidStack stack) {
        for (int i = 0; i < receiver.getTanks(); i++) {
            if (receiver.isFluidValid(i, stack)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Drops the connection and puts the nozzle back on its cradle.
     *
     * <p>Deliberately leaves {@link #status} alone: when the pump detaches itself because the
     * target vanished or moved out of range, that reason is what the goggle tooltip should keep
     * showing. {@link #returnNozzle()} is the manual path and does clear it.
     */
    public void detachAndRetract() {
        target = null;
        nozzleState = NozzleState.RACKED;
        holder = null;
        lastTransferred = 0;
        me.jamino.westernpump.content.nozzle.HeldNozzleTracker.clearByPump(worldPosition);
        notifyUpdate();
    }

    @Override
    public void lazyTick() {
        super.lazyTick();
        if (level == null || level.isClientSide) {
            return;
        }
        // A player who logs out or wanders off should not leave the nozzle hanging in mid-air.
        if (nozzleState == NozzleState.HELD && holder != null && level.getPlayerByUUID(holder) == null) {
            returnNozzle();
        }
    }

    @Override
    public void destroy() {
        super.destroy();
        target = null;
        holder = null;
        me.jamino.westernpump.content.nozzle.HeldNozzleTracker.clearByPump(worldPosition);
    }

    // ---- persistence & sync -------------------------------------------------------------

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putByte("NozzleState", (byte) nozzleState.ordinal());
        tag.putByte("Status", (byte) status.ordinal());
        tag.putInt("LastTransferred", lastTransferred);
        if (holder != null) {
            tag.putUUID("Holder", holder);
        }
        if (target != null) {
            tag.put("Target", target.save(registries));
        }
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        nozzleState = NozzleState.values()[Math.min(tag.getByte("NozzleState"), (byte) (NozzleState.values().length - 1))];
        status = Status.values()[Math.min(tag.getByte("Status"), (byte) (Status.values().length - 1))];
        lastTransferred = tag.getInt("LastTransferred");
        holder = tag.hasUUID("Holder") ? tag.getUUID("Holder") : null;
        target = tag.contains("Target") ? FuelEndpoint.load(tag.getCompound("Target")) : null;
    }

    /**
     * The hose leaves this block, so the default one-block render box would cull it. Expand the box
     * to cover the whole curve including its sag, or the hose vanishes when the pump is off-screen.
     */
    @Override
    public AABB createRenderBoundingBox() {
        Vec3 anchor = getHoseAnchor();
        AABB base = new AABB(worldPosition).inflate(0.5).minmax(new AABB(anchor, anchor));
        if (nozzleState == NozzleState.RACKED || target == null || level == null) {
            return base;
        }
        Vec3 far = target.worldAnchor(level);
        HoseCurve curve = HoseCurve.between(anchor, far, 0.1f);
        return base.minmax(new AABB(far, far)).expandTowards(0, curve.lowestPoint(), 0).inflate(0.5);
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        PumpLang.translate("gui.goggles.fuel_pump").forGoggles(tooltip);
        containedFluidTooltip(tooltip, isPlayerSneaking, getFluidCapability());

        PumpLang.translate("gui.goggles.fuel_pump.nozzle")
                .style(ChatFormatting.GRAY)
                .forGoggles(tooltip);
        PumpLang.translate("gui.goggles.fuel_pump.nozzle." + nozzleState.name().toLowerCase(java.util.Locale.ROOT))
                .style(ChatFormatting.WHITE)
                .forGoggles(tooltip, 1);

        if (status != Status.IDLE) {
            PumpLang.translate("gui.goggles.fuel_pump.status." + status.name().toLowerCase(java.util.Locale.ROOT))
                    .style(status == Status.FLOWING ? ChatFormatting.GREEN : ChatFormatting.GOLD)
                    .forGoggles(tooltip, 1);
        }
        return true;
    }
}
