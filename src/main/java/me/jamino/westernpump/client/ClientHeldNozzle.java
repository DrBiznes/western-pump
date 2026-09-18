package me.jamino.westernpump.client;

import me.jamino.westernpump.WesternPump;
import me.jamino.westernpump.content.pump.FuelPumpBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side lookup for "which pump is this player holding a nozzle from".
 *
 * <p>This is a mirror of the server's {@code HeldNozzleTracker}, filled in from the pump's own
 * sync packet: each pump that reports {@code HELD} registers itself here under its holder's UUID.
 * The lookup is then O(1) and, crucially, does not depend on the pump being anywhere near the
 * player in <i>block</i> space.
 *
 * <p>That last point is what makes this Sable-safe. A pump bolted to a vehicle lives at a fixed
 * position inside the vehicle's sub-level plot, which is nowhere near the player's world-space
 * position even when the two are standing next to each other on screen. Any search that scans
 * blocks around the player therefore misses the pump entirely and re-scans every tick forever;
 * see the note on {@link #findHeldPump}.
 */
@EventBusSubscriber(modid = WesternPump.MOD_ID, value = Dist.CLIENT)
public final class ClientHeldNozzle {

    /** Holder UUID → the pump position they are holding a nozzle from. Client level only. */
    private static final Map<UUID, BlockPos> HELD = new ConcurrentHashMap<>();

    private ClientHeldNozzle() {
    }

    /**
     * Records or clears this pump's entry from its synced state. Called from the pump's client-side
     * read, so the map tracks exactly what the server last told us.
     */
    public static void sync(BlockPos pumpPos, @Nullable UUID holder, FuelPumpBlockEntity.NozzleState state) {
        if (holder != null && state == FuelPumpBlockEntity.NozzleState.HELD) {
            HELD.put(holder, pumpPos.immutable());
            return;
        }
        // Not held any more: drop whatever this pump had registered. Keyed by value because the
        // holder UUID is already gone from the packet by the time the nozzle is racked.
        HELD.values().removeIf(pumpPos::equals);
    }

    /**
     * Dropped on disconnect so positions from a previous world can never be resolved. Without this
     * a pump position from one world could collide with an unrelated pump at the same coordinates
     * in the next one, since the map is static and survives the level.
     */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        HELD.clear();
    }

    /**
     * The pump whose nozzle {@code player} is holding, or {@code null}.
     *
     * <p>Never scans. The previous implementation walked a 13x13x13 cube of {@code getBlockEntity}
     * calls around the player, which cost 2197 lookups per tick and — on a Sable vehicle, where the
     * pump is not in that cube at all — never terminated early, because a miss cannot be cached
     * as an answer.
     */
    @Nullable
    public static FuelPumpBlockEntity findHeldPump(Level level, Player player) {
        BlockPos pumpPos = HELD.get(player.getUUID());
        if (pumpPos == null || !level.isLoaded(pumpPos)) {
            return null;
        }
        BlockEntity be = level.getBlockEntity(pumpPos);
        if (be instanceof FuelPumpBlockEntity pump
                && pump.getNozzleState() == FuelPumpBlockEntity.NozzleState.HELD
                && pump.isHeldBy(player)) {
            return pump;
        }
        // Stale: the pump was broken or unloaded without a final sync.
        HELD.remove(player.getUUID());
        return null;
    }
}
