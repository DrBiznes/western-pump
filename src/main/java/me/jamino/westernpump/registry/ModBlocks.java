package me.jamino.westernpump.registry;

import com.simibubi.create.api.stress.BlockStressValues;
import com.simibubi.create.foundation.data.SharedProperties;
import com.tterrag.registrate.util.entry.BlockEntry;
import me.jamino.westernpump.WesternPump;
import me.jamino.westernpump.content.pump.FuelPumpBlock;
import net.minecraft.world.item.CreativeModeTabs;
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
            // Stress impact at 1 RPM, in line with Create's Mechanical Pump.
            .onRegister(block -> BlockStressValues.IMPACTS.register(block, () -> 4.0))
            // Hand-written blockstate/model/loot/lang live in src/main/resources; tell Registrate
            // not to generate its own and overwrite them.
            .blockstate((ctx, prov) -> {
            })
            .loot((lootTables, block) -> {
            })
            .lang("Fuel Pump")
            .item()
            // Registrate defaults every item to the vanilla Search tab. Vanilla already fills
            // Search by copying every category tab, so leaving this on adds the pump a second
            // time and crashes the creative menu ("already exists in the tab's list").
            // Placement is handled in ModCreativeTab instead.
            .removeTab(CreativeModeTabs.SEARCH)
            .model((ctx, prov) -> {
            })
            .build()
            .register();

    public static void register() {
        // Class-load trigger; the static fields above do the work.
    }
}
