package me.jamino.westernpump.registry;

import com.tterrag.registrate.util.entry.BlockEntityEntry;
import me.jamino.westernpump.WesternPump;
import me.jamino.westernpump.content.pump.FuelPumpBlockEntity;
import me.jamino.westernpump.content.pump.FuelPumpRenderer;

public class ModBlockEntities {
    private static final com.simibubi.create.foundation.data.CreateRegistrate REGISTRATE = WesternPump.registrate();

    public static final BlockEntityEntry<FuelPumpBlockEntity> FUEL_PUMP = REGISTRATE
            .blockEntity("fuel_pump", FuelPumpBlockEntity::new)
            .validBlocks(ModBlocks.FUEL_PUMP)
            .renderer(() -> FuelPumpRenderer::new)
            .register();

    public static void register() {
        // Class-load trigger.
    }
}
