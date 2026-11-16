package me.jamino.westernpump.content.pump;

import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.INamedIconOptions;
import com.simibubi.create.foundation.gui.AllIcons;

/**
 * Which way fuel moves through the pump, chosen on the selector under the counter.
 *
 * <p>The icons are the pair Create uses for the Steam Engine's and Windmill Bearing's direction
 * selector, so the control reads the same as every other "reverse this machine" box in the game.
 */
public enum FlowDirection implements INamedIconOptions {

    /** Up out of the pipes below and out through the nozzle: filling a vehicle. */
    DISPENSE(AllIcons.I_REFRESH),
    /** In through the nozzle and down into the pipes below: emptying a vehicle. */
    DRAIN(AllIcons.I_ROTATE_CCW);

    private final AllIcons icon;
    private final String translationKey;

    FlowDirection(AllIcons icon) {
        this.icon = icon;
        this.translationKey = "westernpump.gui.fuel_pump.flow_direction."
                + name().toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public AllIcons getIcon() {
        return icon;
    }

    @Override
    public String getTranslationKey() {
        return translationKey;
    }
}
