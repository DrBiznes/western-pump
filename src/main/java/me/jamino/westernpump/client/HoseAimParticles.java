package me.jamino.westernpump.client;

import me.jamino.westernpump.WesternPump;
import me.jamino.westernpump.compat.sable.SubLevelSupport;
import me.jamino.westernpump.config.PumpConfig;
import me.jamino.westernpump.content.pump.FuelPumpBlock;
import me.jamino.westernpump.content.pump.FuelPumpBlockEntity;
import me.jamino.westernpump.content.pump.FuelPumpRenderer;
import me.jamino.westernpump.content.pump.NozzleAnimation;
import me.jamino.westernpump.content.pump.PumpGeometry;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.joml.Vector3f;

/**
 * While the nozzle is held, a trickle of coloured dust runs from the coupler's face to whatever
 * block the player is looking at, the way Create's logistics blocks show a link: green for a
 * tank that will take fuel, amber for a block with no fluid handler, red for out of range.
 *
 * <p>Purely client-side and read-only. It raycasts locally and colours the trail by whether the
 * target looks valid; the server re-checks everything when the player actually clicks, so a
 * wrong guess here can only mislead the player, never grant an invalid connection.
 */
@EventBusSubscriber(modid = WesternPump.MOD_ID, value = Dist.CLIENT)
public final class HoseAimParticles {

    private static final Vector3f GREEN = new Vector3f(0.33f, 1.0f, 0.4f);
    private static final Vector3f AMBER = new Vector3f(1.0f, 0.7f, 0.0f);
    private static final Vector3f RED = new Vector3f(1.0f, 0.25f, 0.25f);

    /** Particles per block of trail spawned each tick; they drift down the line as they age. */
    private static final double DENSITY = 1.5;

    private HoseAimParticles() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.isPaused()) {
            return;
        }
        FuelPumpBlockEntity pump = ClientHeldNozzle.findHeldPump(mc.level, mc.player);
        if (pump == null || pump.getNozzleState() != FuelPumpBlockEntity.NozzleState.HELD) {
            return;
        }
        HitResult hit = mc.hitResult;
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
            return;
        }

        NozzleAnimation.Pose pose = pump.nozzleAnimation().current();
        if (pose == null) {
            pose = FuelPumpRenderer.handPose(mc.player, 1f);
        }
        Vec3 start = pose.inlet().add(pose.spout().scale(PumpGeometry.NOZZLE_LENGTH));

        BlockPos pos = blockHit.getBlockPos();
        Vec3 end = Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(blockHit.getDirection().getNormal()).scale(0.5));
        Vector3f color = classify(mc.level, pump, pos, blockHit, end);

        spawnTrail(mc.level, start, end, color);
    }

    private static Vector3f classify(Level level, FuelPumpBlockEntity pump, BlockPos pos, BlockHitResult hit, Vec3 end) {
        if (level.getBlockState(pos).getBlock() instanceof FuelPumpBlock) {
            // Aiming back at the pump means "hang it up", which is always allowed.
            return GREEN;
        }
        if (SubLevelSupport.distanceSqr(level, pump.getHoseAnchor(), end) > PumpConfig.maxHoseLengthSqr()) {
            return RED;
        }
        boolean hasHandler = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, hit.getDirection()) != null;
        return hasHandler ? GREEN : AMBER;
    }

    /** Dust motes spaced along the line, phase-shifted each tick so they appear to flow towards the target. */
    private static void spawnTrail(Level level, Vec3 start, Vec3 end, Vector3f color) {
        Vec3 delta = end.subtract(start);
        double length = delta.length();
        if (length < 0.2) {
            return;
        }
        int count = Math.max(1, (int) Math.round(length * DENSITY));
        double phase = (level.getGameTime() % 10) / 10.0 / count;
        DustParticleOptions dust = new DustParticleOptions(color, 0.55f);
        for (int i = 0; i < count; i++) {
            double t = Math.min(1.0, (i + 0.5) / count + phase);
            Vec3 p = start.add(delta.scale(t));
            level.addParticle(dust, p.x, p.y, p.z, 0, 0, 0);
        }
    }
}
