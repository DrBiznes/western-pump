package me.jamino.westernpump.content.nozzle;

import me.jamino.westernpump.WesternPump;
import me.jamino.westernpump.content.pump.FuelPumpBlock;
import me.jamino.westernpump.content.pump.FuelPumpBlockEntity;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Routes a right-click on any block to the nozzle logic while the player is carrying a nozzle.
 *
 * <p>This has to be an event rather than a block method because the target is somebody else's
 * block — an Aeronautical Diesel tank, a Create fluid tank, or any other block exposing a fluid
 * capability. Intercepting the interaction is what lets the pump work with tanks it knows nothing
 * about.
 */
@EventBusSubscriber(modid = WesternPump.MOD_ID)
public final class NozzleEventHandler {
    private NozzleEventHandler() {
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        Level level = player.level();

        // Empty hand only, mirroring the pump's own rule.
        if (!event.getItemStack().isEmpty()) {
            return;
        }
        // The pump's own block handles clicks on itself.
        if (level.getBlockState(event.getPos()).getBlock() instanceof FuelPumpBlock) {
            return;
        }

        if (level.isClientSide) {
            // Suppress the client-side arm swing only when a nozzle is actually held, so normal
            // interactions elsewhere are untouched.
            if (HeldNozzleTracker.get(player) != null) {
                event.setCancellationResult(InteractionResult.SUCCESS);
                event.setCanceled(true);
            }
            return;
        }

        FuelPumpBlockEntity pump = NozzleInteractions.findHeldPump(level, player);
        if (pump == null) {
            return;
        }

        BlockHitResult hit = event.getHitVec();
        boolean handled = NozzleInteractions.onTargetClicked(
                level, pump, player, event.getPos(), hit.getDirection());

        // Consume the click either way: while holding a nozzle the player is aiming a hose, not
        // operating the block in front of them.
        event.setCancellationResult(handled ? InteractionResult.CONSUME : InteractionResult.FAIL);
        event.setCanceled(true);
    }
}
