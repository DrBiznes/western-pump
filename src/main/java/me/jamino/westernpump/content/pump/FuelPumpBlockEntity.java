package me.jamino.westernpump.content.pump;

import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.pump.PumpBlock;
import com.simibubi.create.content.fluids.pump.PumpBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.fluid.SmartFluidTankBehaviour;
import me.jamino.westernpump.compat.sable.SubLevelSupport;
import me.jamino.westernpump.config.PumpConfig;
import me.jamino.westernpump.content.hose.FuelEndpoint;
import me.jamino.westernpump.content.hose.HoseCurve;
import me.jamino.westernpump.util.PumpLang;
import net.createmod.catnip.data.Iterate;
import net.createmod.catnip.data.Pair;
import net.createmod.catnip.math.BlockFace;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The fuel pump's server-side brain: it pulls fuel up out of the pipe network beneath it while it
 * is turning, holds a small buffer, remembers where the nozzle is plugged in, and moves fuel while
 * a connection is live.
 *
 * <p>This block entity lives on the <b>lower</b> half of the two-tall pump. The upper half
 * forwards its interactions down here (see {@link FuelPumpBlock}), so there is exactly one
 * authority per pump.
 *
 * <p>It is a kinetic block entity: without rotation nothing is pulled in and nothing is dispensed.
 * The pumping itself reuses Create's own approach, see {@link #distributePressure}.
 */
public class FuelPumpBlockEntity extends KineticBlockEntity {

    /** Nozzle lifecycle. Only the server changes this; clients receive it for rendering. */
    public enum NozzleState {
        RACKED,
        HELD,
        ATTACHED
    }

    /** The only side fuel can enter through. */
    public static final Direction INLET = Direction.DOWN;

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
    /** mB delivered since the nozzle was last plugged in; what the drum counter shows. */
    private int sessionMb;
    /** Client only: game time when the last sync arrived, so the drums can keep rolling between syncs. */
    private long clientSyncTick;
    /** Client only: smoothing state for the nozzle's travel. Created lazily by the renderer. */
    @Nullable
    private NozzleAnimation nozzleAnimation;

    /** mB delivered since the counter last clicked; the mechanism ticks over every CLICK_EVERY_MB. */
    private int clickAccumulator;
    /** Ticks until the second half of the "tank full" double ding, 0 when none is pending. */
    private int dingTimer;
    /** One counter click per this much fuel: four ticks a bucket, a mechanical rattle at full flow. */
    private static final int CLICK_EVERY_MB = 250;

    /** Set whenever the pipe network below may need our pressure re-applied. */
    private boolean pressureDirty = true;
    /** What the network looked like when pressure was last applied, to notice pipes being changed. */
    @Nullable
    private NetworkScan lastScan;

    public enum Status {
        IDLE,
        FLOWING,
        SOURCE_EMPTY,
        TARGET_FULL,
        FLUID_MISMATCH,
        OUT_OF_RANGE,
        TARGET_GONE,
        UNPOWERED
    }

    public FuelPumpBlockEntity(net.minecraft.world.level.block.entity.BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        setLazyTickRate(20);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);
        // Insertion only: the pipe below pushes fuel in, the nozzle takes it out through our own
        // code. Forbidding extraction stops other pipes from siphoning the buffer back out.
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

    public int getSessionMb() {
        return sessionMb;
    }

    /**
     * Client-side estimate of the session total, extrapolated from the last sync using the flow
     * rate at that moment. The next sync corrects it; the drums never need to roll backwards
     * because the server only ever adds until the session is reset.
     */
    public double estimateSessionMb(float partialTicks) {
        if (level == null || lastTransferred <= 0) {
            return sessionMb;
        }
        double ticksSince = Math.max(0, level.getGameTime() - clientSyncTick) + partialTicks;
        return sessionMb + lastTransferred * ticksSince;
    }

    public NozzleAnimation nozzleAnimation() {
        if (nozzleAnimation == null) {
            nozzleAnimation = new NozzleAnimation();
        }
        return nozzleAnimation;
    }

    public Direction getFacing() {
        return getBlockState().hasProperty(FuelPumpBlock.FACING)
                ? getBlockState().getValue(FuelPumpBlock.FACING)
                : Direction.NORTH;
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

    /** World-space point the hose leaves the pump from: the spigot boss on the upper cabinet's side. */
    public Vec3 getHoseAnchor() {
        return PumpGeometry.toWorld(worldPosition, getFacing(), PumpGeometry.SPIGOT_ANCHOR);
    }

    /** World-space point of the coupler's hose inlet while it rests in the holster. */
    public Vec3 getHolsterInlet() {
        return PumpGeometry.toWorld(worldPosition, getFacing(), PumpGeometry.HOLSTER_INLET);
    }

    public boolean isPowered() {
        return getSpeed() != 0;
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
        // A new fill-up: the counter starts from zero like a forecourt pump. It keeps the last
        // reading while racked so you can still see what the previous sale came to.
        sessionMb = 0;
        clickAccumulator = 0;
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

    // ---- ticking ------------------------------------------------------------------------

    @Override
    public void initialize() {
        super.initialize();
        pressureDirty = true;
    }

    @Override
    public void onSpeedChanged(float previousSpeed) {
        super.onSpeedChanged(previousSpeed);
        if (level == null || level.isClientSide) {
            return;
        }
        if (Math.abs(previousSpeed) != Math.abs(getSpeed())) {
            pressureDirty = true;
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide) {
            return;
        }
        if (pressureDirty) {
            pressureDirty = false;
            updatePressure();
        }
        pullFromHandlerBelow();
        snapBackIfStretched();
        tickDing();

        if (nozzleState != NozzleState.ATTACHED || target == null) {
            if (lastTransferred != 0) {
                lastTransferred = 0;
                notifyUpdate();
            }
            return;
        }
        transferTick();
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
        // While fuel flows the client extrapolates the counter; a sync once a second keeps it honest
        // without the per-tick packet the transfer loop deliberately avoids.
        if (nozzleState == NozzleState.ATTACHED && lastTransferred > 0) {
            sendData();
        }
        // Create tells its own Mechanical Pumps when a pipe changes, but not us. Re-walk the
        // network once a second and re-apply pressure only if it actually changed.
        if (isPowered() && lastScan != null && !scanNetwork().sameAs(lastScan)) {
            pressureDirty = true;
        }
    }

    /**
     * A held hose has a maximum reach (the configured hose length). Walk further than that and it
     * pulls itself out of your hand and back onto the cradle, the way a real reel hose would.
     */
    private void snapBackIfStretched() {
        if (nozzleState != NozzleState.HELD || holder == null) {
            return;
        }
        Player player = level.getPlayerByUUID(holder);
        if (player == null) {
            return;
        }
        double distSqr = SubLevelSupport.distanceSqr(level, getHoseAnchor(), player.getEyePosition());
        if (distSqr <= PumpConfig.maxHoseLengthSqr()) {
            return;
        }
        returnNozzle();
        level.playSound(null, worldPosition, me.jamino.westernpump.registry.ModSounds.NOZZLE_RETURN.get(),
                net.minecraft.sounds.SoundSource.BLOCKS, 0.7f, 0.8f);
        player.displayClientMessage(
                PumpLang.translate("message.nozzle.snapped_back", PumpConfig.maxHoseLength).component(), true);
    }

    // ---- pumping: pull from the pipe network below --------------------------------------
    //
    // Adapted from Create's PumpBlockEntity (MIT, Creators of Create). A Mechanical Pump is itself
    // a pipe segment that pressurises the pipes on both of its sides; this block is not a pipe,
    // so it pressurises the network below it as if a pump sat on top pulling, and gives the pipe
    // directly underneath an outbound face aimed at our own fluid handler. Create's pipes then do
    // the actual moving, flows and all, and deliver into the buffer.

    private void updatePressure() {
        BlockPos below = worldPosition.below();
        // Wipe stale pressure on the whole connected network; real Mechanical Pumps on it re-apply
        // theirs on their next tick, we re-apply ours right now.
        FluidPropagator.propagateChangedPipe(level, below, level.getBlockState(below));
        NetworkScan scan = scanNetwork();
        lastScan = scan;
        if (!isPowered()) {
            return;
        }
        distributePressure(scan);
    }

    /** Everything found by walking the pipes below, plus what is needed to compare two walks. */
    private record NetworkScan(Map<BlockPos, Pair<Integer, Map<Direction, Boolean>>> pipeGraph,
                               Set<BlockFace> targets, Set<BlockPos> pipes) {
        boolean sameAs(NetworkScan other) {
            return targets.equals(other.targets) && pipes.equals(other.pipes);
        }
    }

    private NetworkScan scanNetwork() {
        final boolean pull = true;
        BlockFace start = new BlockFace(worldPosition, INLET);
        Set<BlockFace> targets = new HashSet<>();
        Set<BlockPos> visited = new HashSet<>();
        Map<BlockPos, Pair<Integer, Map<Direction, Boolean>>> pipeGraph = new HashMap<>();

        if (!hasReachedValidEndpoint(level, start, pull)) {
            pipeGraph.computeIfAbsent(worldPosition, $ -> Pair.of(0, new IdentityHashMap<>()))
                    .getSecond()
                    .put(INLET, pull);
            pipeGraph.computeIfAbsent(start.getConnectedPos(), $ -> Pair.of(1, new IdentityHashMap<>()))
                    .getSecond()
                    .put(INLET.getOpposite(), !pull);

            List<Pair<Integer, BlockPos>> frontier = new ArrayList<>();
            int maxDistance = FluidPropagator.getPumpRange();
            frontier.add(Pair.of(1, start.getConnectedPos()));

            while (!frontier.isEmpty()) {
                Pair<Integer, BlockPos> entry = frontier.remove(0);
                int distance = entry.getFirst();
                BlockPos currentPos = entry.getSecond();
                if (!level.isLoaded(currentPos) || visited.contains(currentPos)) {
                    continue;
                }
                visited.add(currentPos);
                BlockState currentState = level.getBlockState(currentPos);
                FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, currentPos);
                if (pipe == null) {
                    continue;
                }

                for (Direction face : FluidPropagator.getPipeConnections(currentState, pipe)) {
                    BlockFace blockFace = new BlockFace(currentPos, face);
                    BlockPos connectedPos = blockFace.getConnectedPos();
                    if (!level.isLoaded(connectedPos) || blockFace.isEquivalent(start)) {
                        continue;
                    }
                    if (hasReachedValidEndpoint(level, blockFace, pull)) {
                        pipeGraph.computeIfAbsent(currentPos, $ -> Pair.of(distance, new IdentityHashMap<>()))
                                .getSecond()
                                .put(face, pull);
                        targets.add(blockFace);
                        continue;
                    }

                    FluidTransportBehaviour pipeBehaviour = FluidPropagator.getPipe(level, connectedPos);
                    if (pipeBehaviour == null || visited.contains(connectedPos)) {
                        continue;
                    }
                    if (isMechanicalPump(pipeBehaviour)) {
                        continue;
                    }
                    if (distance + 1 >= maxDistance) {
                        pipeGraph.computeIfAbsent(currentPos, $ -> Pair.of(distance, new IdentityHashMap<>()))
                                .getSecond()
                                .put(face, pull);
                        targets.add(blockFace);
                        continue;
                    }

                    pipeGraph.computeIfAbsent(currentPos, $ -> Pair.of(distance, new IdentityHashMap<>()))
                            .getSecond()
                            .put(face, pull);
                    pipeGraph.computeIfAbsent(connectedPos, $ -> Pair.of(distance + 1, new IdentityHashMap<>()))
                            .getSecond()
                            .put(face.getOpposite(), !pull);
                    frontier.add(Pair.of(distance + 1, connectedPos));
                }
            }
        }
        return new NetworkScan(pipeGraph, targets, visited);
    }

    /** A Mechanical Pump's transport behaviour is an inner class; there is no public marker for it. */
    private static boolean isMechanicalPump(FluidTransportBehaviour behaviour) {
        return behaviour.blockEntity instanceof PumpBlockEntity;
    }

    private void distributePressure(NetworkScan scan) {
        final boolean pull = true;
        BlockFace start = new BlockFace(worldPosition, INLET);
        Map<Integer, Set<BlockFace>> validFaces = new HashMap<>();
        searchForEndpointRecursively(scan.pipeGraph(), scan.targets(), validFaces,
                new BlockFace(start.getPos(), start.getOppositeFace()), pull);

        float pressure = Math.abs(getSpeed());
        for (Set<BlockFace> set : validFaces.values()) {
            int parallelBranches = Math.max(1, set.size() - 1);
            for (BlockFace face : set) {
                BlockPos pipePos = face.getPos();
                Direction pipeSide = face.getFace();
                if (pipePos.equals(worldPosition)) {
                    continue;
                }
                boolean inbound = scan.pipeGraph().get(pipePos).getSecond().get(pipeSide);
                FluidTransportBehaviour pipeBehaviour = FluidPropagator.getPipe(level, pipePos);
                if (pipeBehaviour == null) {
                    continue;
                }
                pipeBehaviour.addPressure(pipeSide, inbound, pressure / parallelBranches);
            }
        }
    }

    private boolean searchForEndpointRecursively(Map<BlockPos, Pair<Integer, Map<Direction, Boolean>>> pipeGraph,
                                                 Set<BlockFace> targets, Map<Integer, Set<BlockFace>> validFaces,
                                                 BlockFace currentFace, boolean pull) {
        BlockPos currentPos = currentFace.getPos();
        if (!pipeGraph.containsKey(currentPos)) {
            return false;
        }
        Pair<Integer, Map<Direction, Boolean>> pair = pipeGraph.get(currentPos);
        int distance = pair.getFirst();
        boolean atLeastOneBranchSuccessful = false;
        for (Direction nextFacing : Iterate.directions) {
            if (nextFacing == currentFace.getFace()) {
                continue;
            }
            Map<Direction, Boolean> map = pair.getSecond();
            if (!map.containsKey(nextFacing)) {
                continue;
            }
            BlockFace localTarget = new BlockFace(currentPos, nextFacing);
            if (targets.contains(localTarget)) {
                validFaces.computeIfAbsent(distance, $ -> new HashSet<>()).add(localTarget);
                atLeastOneBranchSuccessful = true;
                continue;
            }
            if (map.get(nextFacing) != pull) {
                continue;
            }
            if (!searchForEndpointRecursively(pipeGraph, targets, validFaces,
                    new BlockFace(currentPos.relative(nextFacing), nextFacing.getOpposite()), pull)) {
                continue;
            }
            validFaces.computeIfAbsent(distance, $ -> new HashSet<>()).add(localTarget);
            atLeastOneBranchSuccessful = true;
        }
        if (atLeastOneBranchSuccessful) {
            validFaces.computeIfAbsent(distance, $ -> new HashSet<>()).add(currentFace);
        }
        return atLeastOneBranchSuccessful;
    }

    private static boolean hasReachedValidEndpoint(LevelAccessor world, BlockFace blockFace, boolean pull) {
        BlockPos connectedPos = blockFace.getConnectedPos();
        BlockState connectedState = world.getBlockState(connectedPos);
        BlockEntity blockEntity = world.getBlockEntity(connectedPos);
        Direction face = blockFace.getFace();

        // A real Mechanical Pump in the line: an endpoint only if it works against us.
        if (PumpBlock.isPump(connectedState) && connectedState.getValue(PumpBlock.FACING).getAxis() == face.getAxis()
                && blockEntity instanceof PumpBlockEntity pumpBE) {
            boolean facingIsFront = blockFace.getOppositeFace() == connectedState.getValue(PumpBlock.FACING);
            return pumpBE.isPullingOnSide(facingIsFront) != pull;
        }
        // Another pipe: keep walking.
        FluidTransportBehaviour pipe = FluidPropagator.getPipe(world, connectedPos);
        if (pipe != null && pipe.canHaveFlowToward(connectedState, blockFace.getOppositeFace())) {
            return false;
        }
        // A fluid handler: this is a source.
        if (blockEntity != null) {
            IFluidHandler capability = blockEntity.getLevel()
                    .getCapability(Capabilities.FluidHandler.BLOCK, blockEntity.getBlockPos(), face.getOpposite());
            if (capability != null) {
                return true;
            }
        }
        // An open pipe end.
        return FluidPropagator.isOpenEnd(world, blockFace.getPos(), face);
    }

    /**
     * A tank sitting directly underneath, with no pipe in between, is not part of any pipe
     * network, so drain it ourselves at the rate a Create network would move at this speed.
     */
    private void pullFromHandlerBelow() {
        if (!isPowered()) {
            return;
        }
        BlockPos below = worldPosition.below();
        if (!level.isLoaded(below) || FluidPropagator.getPipe(level, below) != null) {
            return;
        }
        IFluidHandler source = level.getCapability(Capabilities.FluidHandler.BLOCK, below, Direction.UP);
        if (source == null) {
            return;
        }
        int rate = Math.max(1, (int) (Math.abs(getSpeed()) / 2f));
        var tank = buffer.getPrimaryHandler();
        FluidStack offered = source.drain(rate, IFluidHandler.FluidAction.SIMULATE);
        if (offered.isEmpty()) {
            return;
        }
        int accepted = tank.fill(offered, IFluidHandler.FluidAction.SIMULATE);
        if (accepted <= 0) {
            return;
        }
        FluidStack drained = source.drain(offered.copyWithAmount(accepted), IFluidHandler.FluidAction.EXECUTE);
        if (!drained.isEmpty()) {
            tank.fill(drained, IFluidHandler.FluidAction.EXECUTE);
        }
    }

    // ---- transfer -----------------------------------------------------------------------

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
        sessionMb += moved;
        playPumpSounds(moved, previous);

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
        if (!isPowered()) {
            status = Status.UNPOWERED;
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

    /**
     * The pump only makes noise while it is doing something: the counter clicks as fuel goes
     * through, and the bell rings twice when the tank it is filling tops out. Nothing plays while
     * it idles, waits for fuel, or sits unpowered.
     */
    private void playPumpSounds(int moved, Status previous) {
        if (moved > 0) {
            clickAccumulator += moved;
            if (clickAccumulator >= CLICK_EVERY_MB) {
                clickAccumulator -= CLICK_EVERY_MB;
                sound(me.jamino.westernpump.registry.ModSounds.PUMP_CLICK.get(), 0.35f, 1.0f);
            }
        }
        if (status == Status.TARGET_FULL && previous != Status.TARGET_FULL && sessionMb > 0) {
            sound(me.jamino.westernpump.registry.ModSounds.PUMP_DING.get(), 0.8f, 1.0f);
            dingTimer = 7;
        }
    }

    private void tickDing() {
        if (dingTimer > 0 && --dingTimer == 0) {
            sound(me.jamino.westernpump.registry.ModSounds.PUMP_DING.get(), 0.8f, 1.12f);
        }
    }

    private void sound(net.minecraft.sounds.SoundEvent event, float volume, float pitch) {
        level.playSound(null, worldPosition, event, net.minecraft.sounds.SoundSource.BLOCKS, volume, pitch);
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
        tag.putInt("SessionMb", sessionMb);
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
        sessionMb = tag.getInt("SessionMb");
        holder = tag.hasUUID("Holder") ? tag.getUUID("Holder") : null;
        target = tag.contains("Target") ? FuelEndpoint.load(tag.getCompound("Target")) : null;
        if (clientPacket) {
            clientSyncTick = level != null ? level.getGameTime() : 0;
            // The cached box must follow the hose; Create only recomputes it when told to.
            invalidateRenderBoundingBox();
        }
    }

    /**
     * The hose leaves this block, so the default one-block render box would cull it. Expand the box
     * to cover the whole curve including its sag, or the hose vanishes when the pump is off-screen.
     */
    @Override
    public AABB createRenderBoundingBox() {
        Vec3 anchor = getHoseAnchor();
        Vec3 holster = getHolsterInlet();
        // Two blocks tall, plus the holster and coupler that overhang the cabinet's side.
        AABB base = new AABB(worldPosition).expandTowards(0, 1, 0).inflate(0.5)
                .minmax(new AABB(anchor, anchor)).minmax(new AABB(holster, holster).inflate(0.6));
        if (level == null) {
            return base;
        }
        if (nozzleState == NozzleState.HELD) {
            // The hose follows the player's hand, anywhere within reach of the pump.
            return base.inflate(PumpConfig.maxHoseLength + 1);
        }
        if (nozzleState == NozzleState.RACKED || target == null) {
            return base;
        }
        Vec3 far = target.worldAnchor(level);
        HoseCurve curve = HoseCurve.between(anchor, far, 0.1f);
        return base.minmax(new AABB(far, far).inflate(0.75)).expandTowards(0, curve.lowestPoint(), 0).inflate(0.5);
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        // Kinetic stats (speed, stress) first, the way every Create machine reads.
        super.addToGoggleTooltip(tooltip, isPlayerSneaking);

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
