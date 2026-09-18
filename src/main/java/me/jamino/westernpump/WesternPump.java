package me.jamino.westernpump;

import com.mojang.logging.LogUtils;
import com.simibubi.create.foundation.data.CreateRegistrate;
import me.jamino.westernpump.config.PumpConfig;
import me.jamino.westernpump.registry.ModBlockEntities;
import me.jamino.westernpump.registry.ModBlocks;
import me.jamino.westernpump.registry.ModCreativeTab;
import me.jamino.westernpump.registry.ModSounds;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;

/**
 * Create: Western Pump — a gas-station fuel pump with a physical, sagging hose that can be
 * dragged to a tank and left attached while fuel flows.
 *
 * <p>Registration goes through Create's {@link CreateRegistrate} so our blocks pick up
 * Create's tooltip, creative-tab and datagen conventions for free.
 */
@Mod(WesternPump.MOD_ID)
public class WesternPump {
    public static final String MOD_ID = "westernpump";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final CreateRegistrate REGISTRATE = CreateRegistrate.create(MOD_ID);

    public WesternPump(IEventBus modEventBus, ModContainer modContainer) {
        REGISTRATE.registerEventListeners(modEventBus);

        ModCreativeTab.register(modEventBus);
        ModBlocks.register();
        ModBlockEntities.register();
        ModSounds.register(modEventBus);

        modContainer.registerConfig(ModConfig.Type.COMMON, PumpConfig.SPEC);

        // Partial models must be registered before models bake. The branch keeps the client-only
        // class from being loaded on a dedicated server.
        if (net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) {
            me.jamino.westernpump.client.ModPartialModels.init();
        }
    }

    public static CreateRegistrate registrate() {
        return REGISTRATE;
    }

    public static net.minecraft.resources.ResourceLocation asResource(String path) {
        return net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
