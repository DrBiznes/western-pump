package me.jamino.westernpump.registry;

import com.simibubi.create.foundation.data.SharedProperties;
import com.tterrag.registrate.util.entry.BlockEntry;
import me.jamino.westernpump.WesternPump;
import me.jamino.westernpump.content.pump.FuelPumpBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

public class ModBlocks {
    private static final com.simibubi.create.foundation.data.CreateRegistrate REGISTRATE = WesternPump.registrate();

    public static final BlockEntry<FuelPumpBlock> FUEL_PUMP = REGISTRATE
            .block("fuel_pump", FuelPumpBlock::new)
            .initialProperties(SharedProperties::softMetal)
            .properties(p -> p
                    .mapColor(MapColor.COLOR_RED)
                    .instrument(NoteBlockInstrument.IRON_XYLOPHONE)
                    .sound(SoundType.NETHERITE_BLOCK)
                    // A two-tall block must never be shoved out of alignment by a piston.
                    .pushReaction(PushReaction.BLOCK)
                    .noOcclusion())
            .transform(com.simibubi.create.foundation.data.TagGen.pickaxeOnly())
            // Hand-written blockstate/model/loot/lang live in src/main/resources; tell Registrate
            // not to generate its own and overwrite them.
            .blockstate((ctx, prov) -> {
            })
            .loot((lootTables, block) -> {
            })
            .lang("Fuel Pump")
            .item()
            .model((ctx, prov) -> {
            })
            .build()
            .register();

    public static void register() {
        // Class-load trigger; the static fields above do the work.
    }
}
