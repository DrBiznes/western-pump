package me.jamino.westernpump.client;

import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * Makes the "Config" button on the mod list open our settings.
 *
 * <p>NeoForge builds the whole screen from {@link me.jamino.westernpump.config.PumpConfig}'s spec —
 * entry widgets, ranges and tooltips all come from the spec's types and {@code .translation(...)}
 * keys — so all that is needed is to register the factory as an extension point.
 *
 * <p>This class is client-only: it references GUI types that do not exist on a dedicated server, so
 * {@link me.jamino.westernpump.WesternPump} only calls {@link #init(ModContainer)} behind a
 * {@code FMLEnvironment.dist.isClient()} check, keeping the class from being loaded there at all.
 */
public final class ModConfigScreen {
    private ModConfigScreen() {
    }

    public static void init(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }
}
