package me.jamino.westernpump.registry;

import me.jamino.westernpump.WesternPump;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Creative tab placement for the fuel pump.
 *
 * <p>The pump belongs with Create: Diesel Generators, so when that mod is present the item is
 * added to its tab. Diesel Generators is optional, so we still register a small tab of our own as
 * a fallback — otherwise the pump would be unobtainable in creative without it.
 *
 * <p>Registrate would normally add our items to a tab for us via {@code setCreativeTab}. We
 * deliberately do <em>not</em> call that: combined with an explicit {@code displayItems} entry it
 * adds the same stack twice, and vanilla throws
 * {@code IllegalArgumentException: Itemstack ... already exists in the tab's list} when the
 * creative menu is opened. All tab population here goes through
 * {@link BuildCreativeModeTabContentsEvent} instead, so there is exactly one code path adding the
 * item and no chance of a duplicate.
 */
@EventBusSubscriber(modid = WesternPump.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public class ModCreativeTab {

    private static final String DIESEL_GENERATORS = "createdieselgenerators";

    /** Verified from the Create: Diesel Generators jar: registered as "cdg_creative_tab". */
    private static final ResourceKey<CreativeModeTab> DIESEL_TAB = ResourceKey.create(
            Registries.CREATIVE_MODE_TAB,
            ResourceLocation.fromNamespaceAndPath(DIESEL_GENERATORS, "cdg_creative_tab"));

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, WesternPump.MOD_ID);

    /**
     * Fallback tab, only populated when Diesel Generators is absent. It is always registered: a
     * tab that exists but is empty is harmless, whereas conditionally registering it would change
     * the registry contents between clients and break syncing.
     */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup." + WesternPump.MOD_ID))
                    .icon(() -> ModBlocks.FUEL_PUMP.asStack())
                    // Contents are filled by onBuildTabContents below, never here, so the item
                    // cannot be added twice.
                    .displayItems((params, output) -> {
                    })
                    .build());

    public static void register(IEventBus modEventBus) {
        TABS.register(modEventBus);
    }

    private static boolean dieselGeneratorsLoaded() {
        return ModList.get().isLoaded(DIESEL_GENERATORS);
    }

    @SubscribeEvent
    public static void onBuildTabContents(BuildCreativeModeTabContentsEvent event) {
        boolean diesel = dieselGeneratorsLoaded();

        if (diesel && event.getTabKey() == DIESEL_TAB) {
            event.accept(ModBlocks.FUEL_PUMP);
            return;
        }
        // Only fall back to our own tab when there is no Diesel Generators tab to live in, so the
        // pump appears exactly once across the whole creative menu.
        if (!diesel && event.getTabKey() == MAIN.getKey()) {
            event.accept(ModBlocks.FUEL_PUMP);
        }
    }
}
