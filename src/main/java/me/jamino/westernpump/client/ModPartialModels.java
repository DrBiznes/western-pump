package me.jamino.westernpump.client;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import me.jamino.westernpump.WesternPump;

/**
 * Models the block entity renderer positions itself, as opposed to the static block model.
 *
 * <p>{@link PartialModel#of} only records the location; Flywheel bakes it with the rest of the
 * model set, so {@link #init()} must run before model loading (it is called from the mod
 * constructor on the client).
 */
public final class ModPartialModels {

    /** The fuel nozzle: rests in the holster, travels to the player's hand, plugs into a tank. */
    public static final PartialModel NOZZLE = block("fuel_pump/nozzle");

    private ModPartialModels() {
    }

    private static PartialModel block(String path) {
        return PartialModel.of(WesternPump.asResource("block/" + path));
    }

    public static void init() {
        // Class-load trigger; the static fields above do the work.
    }
}
