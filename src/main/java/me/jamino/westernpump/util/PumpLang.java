package me.jamino.westernpump.util;

import me.jamino.westernpump.WesternPump;
import net.createmod.catnip.lang.Lang;
import net.createmod.catnip.lang.LangBuilder;

/**
 * Shorthand for Catnip's builder-style localisation, scoped to this mod's namespace.
 *
 * <p>Catnip exposes {@code Lang.builder(modId).translate(key)} rather than a static
 * {@code Lang.translate}, so this wrapper keeps call sites readable.
 */
public final class PumpLang {
    private PumpLang() {
    }

    public static LangBuilder builder() {
        return Lang.builder(WesternPump.MOD_ID);
    }

    public static LangBuilder translate(String key, Object... args) {
        return builder().translate(key, args);
    }
}
