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
 * <p>Registrate would normally place our items in a tab for us, and by default that tab is the
 * vanilla Search tab. Vanilla fills Search by copying every category tab, so that default adds
 * the pump a second time and vanilla throws
 * {@code IllegalArgumentException: Itemstack ... already exists in the tab's list} when the
 * creative menu is opened. {@link ModBlocks} removes that default, and all tab population goes
 * through {@link BuildCreativeModeTabContentsEvent} here, so there is exactly one code path
 * adding the item.
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
            addOnce(event);
            return;
        }
        // Only fall back to our own tab when there is no Diesel Generators tab to live in, so the
        // pump appears exactly once across the whole creative menu.
        if (!diesel && event.getTabKey() == MAIN.getKey()) {
            addOnce(event);
        }
    }

    /**
     * Adds the pump unless it is already in this tab.
     *
     * <p>This guard only covers our own listener. The duplicate that actually crashed the client
     * came from Registrate: its {@code ItemBuilder} defaults every item to the vanilla Search tab,
     * and vanilla builds Search by copying every category tab, so the pump was added there twice.
     * That is fixed at the source in {@link ModBlocks} with {@code removeTab(CreativeModeTabs.SEARCH)};
     * this method just keeps our own contribution idempotent should another mod also add the pump.
     */
    private static void addOnce(BuildCreativeModeTabContentsEvent event) {
        var item = ModBlocks.FUEL_PUMP.asItem();
        for (var existing : event.getParentEntries()) {
            if (existing.getItem() == item) {
                return;
            }
        }
        event.accept(ModBlocks.FUEL_PUMP);
    }
}
