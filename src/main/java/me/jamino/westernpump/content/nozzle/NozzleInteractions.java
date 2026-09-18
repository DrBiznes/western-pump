package me.jamino.westernpump.content.nozzle;

import me.jamino.westernpump.config.PumpConfig;
import me.jamino.westernpump.content.hose.FuelEndpoint;
import me.jamino.westernpump.content.pump.FuelPumpBlockEntity;
import me.jamino.westernpump.content.pump.FuelPumpBlockEntity.NozzleState;
import me.jamino.westernpump.registry.ModSounds;
import me.jamino.westernpump.util.PumpLang;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.Nullable;

/**
 * Server-side rules for picking up, attaching and returning the nozzle.
 *
 * <p>All of this runs on the server. The client only ever asks; it never decides, which keeps a
 * modified client from attaching a hose to something it should not reach.
 */
public final class NozzleInteractions {
    private NozzleInteractions() {
    }

    /**
     * Right-clicking the pump itself: take the nozzle off its cradle, or hang it back up.
     *
     * @return true if the click was consumed
     */
    public static boolean onPumpClicked(Level level, BlockPos pumpPos, Player player) {
        if (!(level.getBlockEntity(pumpPos) instanceof FuelPumpBlockEntity pump)) {
            return false;
        }

        switch (pump.getNozzleState()) {
            case RACKED -> {
                pump.pickUpNozzle(player);
                HeldNozzleTracker.set(player, pumpPos);
                playSound(level, pumpPos, ModSounds.NOZZLE_PICKUP.get());
                message(player, "nozzle.taken");
                return true;
            }
            case HELD, ATTACHED -> {
                // Anyone may hang up a nozzle the owner abandoned, but only the owner may
                // interrupt an active session.
                if (!pump.isHeldBy(player) && ownerOnline(level, pump)) {
                    message(player, "nozzle.in_use");
                    return true;
                }
                pump.returnNozzle();
                HeldNozzleTracker.clear(player);
                playSound(level, pumpPos, ModSounds.NOZZLE_RETURN.get());
                message(player, "nozzle.returned");
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private static boolean ownerOnline(Level level, FuelPumpBlockEntity pump) {
        return pump.getHolder() != null && level.getPlayerByUUID(pump.getHolder()) != null;
    }

    /**
     * Right-clicking a candidate fuel port while holding the nozzle.
     *
     * <p>Clicking the target the hose is already attached to detaches it, which is the second half
     * of the two-click loop. While attached, clicks on anything else are not the nozzle's business.
     *
     * @return true if the click was consumed
     */
    public static boolean interceptsClickAt(FuelPumpBlockEntity pump, BlockPos pos) {
        return switch (pump.getNozzleState()) {
            case HELD -> true;
            case ATTACHED -> pump.getTarget() != null && pump.getTarget().pos().equals(pos);
            default -> false;
        };
    }

    /**
     * @return true if the click was consumed
     */
    public static boolean onTargetClicked(Level level, FuelPumpBlockEntity pump, Player player,
                                          BlockPos targetPos, Direction face) {
        if (!pump.isHeldBy(player)) {
            return false;
        }

        FuelEndpoint existing = pump.getTarget();
        if (pump.getNozzleState() == NozzleState.ATTACHED) {
            if (existing != null && existing.pos().equals(targetPos)) {
                pump.detachAndRetract();
                HeldNozzleTracker.clear(player);
                playSound(level, targetPos, ModSounds.NOZZLE_DETACH.get());
                message(player, "nozzle.detached");
                return true;
            }
            // Once plugged in the player is no longer aiming a hose: other blocks behave normally
            // and the coupler stays where it is until it or the pump is clicked.
            return false;
        }

        FuelEndpoint endpoint = FuelEndpoint.of(level, targetPos, face);

        IFluidHandler handler = level.getCapability(Capabilities.FluidHandler.BLOCK, targetPos, face);
        if (handler == null) {
            message(player, "target.invalid");
            return false;
        }
        if (!pump.isInRange(endpoint)) {
            message(player, "target.out_of_range", PumpConfig.maxHoseLength);
            return false;
        }

        pump.attachTo(endpoint);
        playSound(level, targetPos, ModSounds.NOZZLE_ATTACH.get());
        message(player, "nozzle.attached");
        return true;
    }

    /**
     * Finds the pump whose nozzle this player is holding.
     *
     * <p>Looks up the position recorded in {@link HeldNozzleTracker} rather than scanning the
     * surrounding blocks: a radius-14 scan would test over twenty thousand positions on every
     * right-click.
     */
    @Nullable
    public static FuelPumpBlockEntity findHeldPump(Level level, Player player) {
        BlockPos pumpPos = HeldNozzleTracker.get(player);
        if (pumpPos == null || !level.isLoaded(pumpPos)) {
            return null;
        }
        if (level.getBlockEntity(pumpPos) instanceof FuelPumpBlockEntity pump
                && pump.getNozzleState() != NozzleState.RACKED
                && pump.isHeldBy(player)) {
            return pump;
        }
        // Stale record: the pump was broken or the nozzle was returned elsewhere.
        HeldNozzleTracker.clear(player);
        return null;
    }

    private static void playSound(Level level, BlockPos pos, net.minecraft.sounds.SoundEvent sound) {
        level.playSound(null, pos, sound, SoundSource.BLOCKS, 0.7f, 1.0f);
    }

    private static void message(Player player, String key, Object... args) {
        player.displayClientMessage(PumpLang.translate("message." + key, args).component(), true);
    }
}
