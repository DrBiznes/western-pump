package me.jamino.westernpump.registry;

import me.jamino.westernpump.WesternPump;
import me.jamino.westernpump.content.pump.FuelPumpBlock;
import me.jamino.westernpump.content.pump.FuelPumpBlockEntity;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/**
 * Exposes the pump's buffer as a standard NeoForge fluid handler on the bottom of the lower
 * block only. That is the face the pump pulls through, and Create's pipes deliver into it; the
 * cabinet sides and the upper block are deliberately blank so fuel cannot be side-loaded.
 */
@EventBusSubscriber(modid = WesternPump.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public class ModCapabilities {

    @SubscribeEvent
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlock(
                Capabilities.FluidHandler.BLOCK,
                (level, pos, state, blockEntity, side) -> {
                    if (state.hasProperty(FuelPumpBlock.HALF)
                            && state.getValue(FuelPumpBlock.HALF) == DoubleBlockHalf.UPPER) {
                        return null;
                    }
                    if (side != null && side != FuelPumpBlockEntity.INLET) {
                        return null;
                    }
                    return blockEntity instanceof FuelPumpBlockEntity pump ? pump.getFluidCapability() : null;
                },
                ModBlocks.FUEL_PUMP.get());
    }
}
