package me.jamino.westernpump.content.nozzle;

import me.jamino.westernpump.WesternPump;
import me.jamino.westernpump.content.pump.FuelPumpBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers which pump each player took a nozzle from, so the pump can be found in O(1) on the
 * player's next click instead of by searching the world.
 *
 * <p>Deliberately in-memory only. The pump itself persists the authoritative connection (holder
 * UUID and target), so losing this map across a restart costs nothing: a held-but-unattached
 * nozzle is returned to its cradle by the pump's own lazy tick.
 */
@EventBusSubscriber(modid = WesternPump.MOD_ID)
public final class HeldNozzleTracker {
    private static final Map<UUID, BlockPos> HELD = new ConcurrentHashMap<>();

    private HeldNozzleTracker() {
    }

    public static void set(Player player, BlockPos pumpPos) {
        HELD.put(player.getUUID(), pumpPos.immutable());
    }

    @Nullable
    public static BlockPos get(Player player) {
        return HELD.get(player.getUUID());
    }

    public static void clear(Player player) {
        HELD.remove(player.getUUID());
    }

    /**
     * Clears whoever was holding the nozzle from this pump. Used when the pump itself ends the
     * session (target lost, out of range, block broken), where there may be no player object to
     * hand — without this the stale entry would keep the client cancelling right-clicks until the
     * player's next interaction cleaned it up.
     */
    public static void clearByPump(BlockPos pumpPos) {
        // removeAll semantics: one pump could in principle be recorded for more than one player
        // if a session ended abnormally. values().remove() would only drop the first match.
        HELD.values().removeIf(pumpPos::equals);
    }

    /** A player who logs out drops the nozzle; the pump racks it again. */
    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        Player player = event.getEntity();
        BlockPos pumpPos = HELD.remove(player.getUUID());
        if (pumpPos == null) {
            return;
        }
        Level level = player.level();
        if (level.isLoaded(pumpPos) && level.getBlockEntity(pumpPos) instanceof FuelPumpBlockEntity pump
                && pump.isHeldBy(player)) {
            pump.returnNozzle();
        }
    }
}
