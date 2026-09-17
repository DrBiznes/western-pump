package me.jamino.westernpump.client;

import me.jamino.westernpump.content.pump.FuelPumpBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

/**
 * Client-side lookup for "which pump is this player holding a nozzle from".
 *
 * <p>The server's {@code HeldNozzleTracker} map does not exist on the client, but the pump syncs
 * its holder UUID and nozzle state, so the answer can be recovered by checking the handful of
 * pumps near the player. The result is cached for the tick it was computed on, because this runs
 * from the render loop.
 */
public final class ClientHeldNozzle {

    @Nullable
    private static BlockPos cachedPos;
    private static long cachedAtTick = -1;

    private ClientHeldNozzle() {
    }

    @Nullable
    public static FuelPumpBlockEntity findHeldPump(Level level, Player player) {
        long now = level.getGameTime();

        // Re-use last tick's answer while it still holds. The render loop calls this many times per
        // tick, and a full scan every frame would be wasteful.
        if (cachedPos != null) {
            FuelPumpBlockEntity cached = pumpAt(level, cachedPos, player);
            if (cached != null) {
                return cached;
            }
            cachedPos = null;
        }
        if (cachedAtTick == now) {
            // Already searched this tick and found nothing; do not search again until next tick.
            return null;
        }
        cachedAtTick = now;

        // A held (not yet attached) nozzle keeps the player within arm's reach of the pump they
        // took it from, so a small radius is enough and costs a few hundred cheap checks once a
        // tick rather than tens of thousands every frame.
        final int radius = 6;
        BlockPos origin = player.blockPosition();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int y = -radius; y <= radius; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    cursor.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    FuelPumpBlockEntity pump = pumpAt(level, cursor, player);
                    if (pump != null) {
                        cachedPos = cursor.immutable();
                        return pump;
                    }
                }
            }
        }
        return null;
    }

    @Nullable
    private static FuelPumpBlockEntity pumpAt(Level level, BlockPos pos, Player player) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof FuelPumpBlockEntity pump
                && pump.getNozzleState() == FuelPumpBlockEntity.NozzleState.HELD
                && pump.isHeldBy(player)) {
            return pump;
        }
        return null;
    }
}
