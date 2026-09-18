package me.jamino.westernpump.client;

import me.jamino.westernpump.WesternPump;
import me.jamino.westernpump.registry.ModBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

/**
 * TEMPORARY diagnostic. Builds every creative tab the way the creative inventory screen does,
 * right after joining a world, and logs where the fuel pump ended up.
 *
 * <p>This exists because a gametest asserting the same thing passed while the real client still
 * crashed: {@code GameTestServer} never builds tabs through the client path, so it could not see
 * the duplicate. Delete once the placement is confirmed in a real client.
 */
@EventBusSubscriber(modid = WesternPump.MOD_ID, value = Dist.CLIENT)
public final class CreativeTabProbe {
    private CreativeTabProbe() {
    }

    @SubscribeEvent
    public static void onJoin(ClientPlayerNetworkEvent.LoggingIn event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        var params = new CreativeModeTab.ItemDisplayParameters(
                mc.level.enabledFeatures(), true, mc.level.registryAccess());

        int total = 0;
        StringBuilder found = new StringBuilder();
        for (var entry : BuiltInRegistries.CREATIVE_MODE_TAB.entrySet()) {
            CreativeModeTab tab = entry.getValue();
            if (tab.getType() != CreativeModeTab.Type.CATEGORY) {
                continue;
            }
            try {
                tab.buildContents(params);
            } catch (Exception e) {
                WesternPump.LOGGER.error("[TAB PROBE] building {} threw: {}",
                        entry.getKey().location(), e.toString());
                continue;
            }
            long here = tab.getDisplayItems().stream()
                    .filter(s -> s.getItem() == ModBlocks.FUEL_PUMP.asItem())
                    .count();
            if (here > 0) {
                total += here;
                found.append(entry.getKey().location()).append("x").append(here).append(" ");
            }
        }
        WesternPump.LOGGER.info("[TAB PROBE] fuel_pump total={} tabs=[{}]", total, found.toString().trim());
    }
}
