package me.jamino.westernpump.content.pump;

import com.simibubi.create.foundation.item.ItemDescription;
import me.jamino.westernpump.config.PumpConfig;
import net.createmod.catnip.lang.FontHelper;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.item.Item;

/**
 * Create-style "Hold [Shift] for Summary" tooltip for the fuel pump item.
 *
 * <p>{@link ItemDescription} normally reads every line straight out of the language file, which
 * cannot express a value that changes at runtime. The hose range is configurable, so this modifier
 * fills the builder itself and formats the range line with the live
 * {@link PumpConfig#maxHoseLength}. Everything else — line wrapping, the {@code _highlight_}
 * markers, the grey/white Shift header — comes from Create's own builder, so the tooltip renders
 * identically to vanilla Create items.
 */
public class FuelPumpTooltip extends ItemDescription.Modifier {

    /** The range baked into {@link #description}, so a config reload can invalidate the cache. */
    private int cachedHoseLength = -1;

    public FuelPumpTooltip(Item item) {
        super(item, FontHelper.Palette.STANDARD_CREATE);
    }

    @Override
    protected boolean checkLocale() {
        // super.checkLocale() has the side effect of updating the cached language, so it must run
        // even when the range alone has changed; keep it on the left of the ||.
        boolean localeChanged = super.checkLocale();
        if (cachedHoseLength != PumpConfig.maxHoseLength) {
            cachedHoseLength = PumpConfig.maxHoseLength;
            return true;
        }
        return localeChanged;
    }

    /**
     * Mirrors {@link ItemDescription#create(Item, FontHelper.Palette)}, but substitutes the
     * configured hose range into the summary and behaviour lines.
     */
    private static ItemDescription build(Item item) {
        String key = ItemDescription.getTooltipTranslationKey(item);
        if (!ItemDescription.canFillBuilder(key + ".summary")) {
            return null;
        }

        Object range = PumpConfig.maxHoseLength;
        ItemDescription.Builder builder = new ItemDescription.Builder(FontHelper.Palette.STANDARD_CREATE);
        builder.addSummary(I18n.get(key + ".summary", range));
        for (int i = 1; I18n.exists(key + ".condition" + i); i++) {
            builder.addBehaviour(I18n.get(key + ".condition" + i, range), I18n.get(key + ".behaviour" + i, range));
        }
        return builder.build();
    }

    @Override
    public void modify(net.neoforged.neoforge.event.entity.player.ItemTooltipEvent context) {
        if (checkLocale()) {
            description = build(item);
        }
        if (description == null) {
            return;
        }
        context.getToolTip().addAll(1, description.getCurrentLines());
    }
}
