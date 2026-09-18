package me.jamino.westernpump.config;

import me.jamino.westernpump.WesternPump;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

@EventBusSubscriber(modid = WesternPump.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public class PumpConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final ModConfigSpec.IntValue MAX_HOSE_LENGTH = BUILDER
            .comment("Maximum distance, in blocks, between the pump and an attached fuel port.",
                    "The hose detaches automatically if the target moves beyond this range, and a",
                    "nozzle carried further than this from the pump snaps back onto its cradle.")
            .defineInRange("maxHoseLength", 12, 2, 64);

    private static final ModConfigSpec.IntValue BUFFER_CAPACITY = BUILDER
            .comment("Capacity of the pump's internal fuel buffer, in mB.",
                    "The pump never creates fuel; this buffer is filled by an upstream Create fluid network.")
            .defineInRange("bufferCapacity", 2000, 100, 64000);

    private static final ModConfigSpec.IntValue TRANSFER_RATE = BUILDER
            .comment("Maximum fuel moved into the target per tick, in mB.")
            .defineInRange("transferRate", 50, 1, 4000);

    private static final ModConfigSpec.BooleanValue AUTO_DETACH_WHEN_FULL = BUILDER
            .comment("Automatically retract the nozzle once the receiving tank is full.")
            .define("autoDetachWhenFull", false);

    public static final ModConfigSpec SPEC = BUILDER.build();

    public static int maxHoseLength = 12;
    public static int bufferCapacity = 2000;
    public static int transferRate = 50;
    public static boolean autoDetachWhenFull = false;

    /** Squared max length, for comparisons against distanceSquared without a sqrt. */
    public static double maxHoseLengthSqr() {
        return (double) maxHoseLength * maxHoseLength;
    }

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        if (event.getConfig().getSpec() != SPEC) {
            return;
        }
        maxHoseLength = MAX_HOSE_LENGTH.get();
        bufferCapacity = BUFFER_CAPACITY.get();
        transferRate = TRANSFER_RATE.get();
        autoDetachWhenFull = AUTO_DETACH_WHEN_FULL.get();
    }
}
