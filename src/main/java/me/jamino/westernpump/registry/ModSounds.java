package me.jamino.westernpump.registry;

import me.jamino.westernpump.WesternPump;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(Registries.SOUND_EVENT, WesternPump.MOD_ID);

    public static final DeferredHolder<SoundEvent, SoundEvent> NOZZLE_PICKUP = register("nozzle_pickup");
    public static final DeferredHolder<SoundEvent, SoundEvent> NOZZLE_RETURN = register("nozzle_return");
    public static final DeferredHolder<SoundEvent, SoundEvent> NOZZLE_ATTACH = register("nozzle_attach");
    public static final DeferredHolder<SoundEvent, SoundEvent> NOZZLE_DETACH = register("nozzle_detach");
    /** The mechanical counter ticking over while fuel flows. */
    public static final DeferredHolder<SoundEvent, SoundEvent> PUMP_CLICK = register("pump_click");
    /** The bell an old forecourt pump rings; here, when the tank being filled is full. */
    public static final DeferredHolder<SoundEvent, SoundEvent> PUMP_DING = register("pump_ding");

    private static DeferredHolder<SoundEvent, SoundEvent> register(String name) {
        ResourceLocation id = WesternPump.asResource(name);
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(id));
    }

    public static void register(IEventBus modEventBus) {
        SOUNDS.register(modEventBus);
    }
}
