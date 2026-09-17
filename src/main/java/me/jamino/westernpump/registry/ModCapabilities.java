package me.jamino.westernpump.registry;

import me.jamino.westernpump.WesternPump;
import me.jamino.westernpump.content.pump.FuelPumpBlock;
import me.jamino.westernpump.content.pump.FuelPumpBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

/**
 * Exposes the pump's buffer as a standard NeoForge fluid handler, which is how Create's pipes and
 * Mechanical Pumps fill it. Nothing Create-specific is required — any mod's fluid transport works.
 */
@EventBusSubscriber(modid = WesternPump.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public class ModCapabilities {

    @SubscribeEvent
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlock(
                Capabilities.FluidHandler.BLOCK,
                (level, pos, state, blockEntity, side) -> {
                    FuelPumpBlockEntity pump = resolvePump(level, pos, state);
                    return pump == null ? null : pump.getFluidCapability();
                },
                ModBlocks.FUEL_PUMP.get());
    }

    /**
     * Both halves of the pump accept fuel, so players can run a pipe into whichever is convenient.
     * The upper half forwards to the lower half's block entity, which owns the buffer.
     */
    private static FuelPumpBlockEntity resolvePump(net.minecraft.world.level.Level level, BlockPos pos, BlockState state) {
        BlockPos lower = state.hasProperty(FuelPumpBlock.HALF)
                && state.getValue(FuelPumpBlock.HALF) == DoubleBlockHalf.UPPER
                ? pos.below()
                : pos;
        BlockEntity be = level.getBlockEntity(lower);
        return be instanceof FuelPumpBlockEntity pump ? pump : null;
    }
}
