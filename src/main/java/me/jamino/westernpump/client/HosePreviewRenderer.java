package me.jamino.westernpump.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import me.jamino.westernpump.WesternPump;
import me.jamino.westernpump.compat.sable.SubLevelSupport;
import me.jamino.westernpump.config.PumpConfig;
import me.jamino.westernpump.content.hose.HoseCurve;
import me.jamino.westernpump.content.pump.FuelPumpBlock;
import me.jamino.westernpump.content.pump.FuelPumpBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * Draws a preview of where the hose would go while the player is carrying the nozzle.
 *
 * <p>Purely client-side and read-only: it raycasts locally and colours the line by whether the
 * target looks valid. The server re-checks everything when the player actually clicks, so a wrong
 * guess here can only mislead the player, never grant an invalid connection.
 */
@EventBusSubscriber(modid = WesternPump.MOD_ID, value = Dist.CLIENT)
public final class HosePreviewRenderer {

    private static final int GREEN = 0x55FF66;
    private static final int AMBER = 0xFFB300;
    private static final int RED = 0xFF4040;

    private HosePreviewRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }

        FuelPumpBlockEntity pump = ClientHeldNozzle.findHeldPump(mc.level, mc.player);
        if (pump == null || pump.getNozzleState() != FuelPumpBlockEntity.NozzleState.HELD) {
            return;
        }

        Vec3 start = pump.getHoseAnchor();
        Vec3 end;
        int color;

        HitResult hit = mc.hitResult;
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = blockHit.getBlockPos();
            end = Vec3.atCenterOf(pos).add(Vec3.atLowerCornerOf(blockHit.getDirection().getNormal()).scale(0.5));
            color = classify(mc.level, pump, pos, blockHit, start, end);
        } else {
            // Nothing under the crosshair: trail the hose to the player's hand.
            end = mc.player.getEyePosition(event.getPartialTick().getGameTimeDeltaPartialTick(false))
                    .add(mc.player.getLookAngle().scale(1.5));
            color = AMBER;
        }

        renderLine(event.getPoseStack(), start, end, color);
    }

    private static int classify(Level level, FuelPumpBlockEntity pump, BlockPos pos,
                                BlockHitResult hit, Vec3 start, Vec3 end) {
        if (level.getBlockState(pos).getBlock() instanceof FuelPumpBlock) {
            // Aiming back at the pump means "hang it up", which is always allowed.
            return GREEN;
        }
        if (SubLevelSupport.distanceSqr(level, start, end) > PumpConfig.maxHoseLengthSqr()) {
            return RED;
        }
        boolean hasHandler = level.getCapability(
                Capabilities.FluidHandler.BLOCK, pos, hit.getDirection()) != null;
        return hasHandler ? GREEN : RED;
    }

    /** A thin quad strip along the would-be hose curve, drawn without depth-testing surprises. */
    private static void renderLine(PoseStack ms, Vec3 start, Vec3 end, int color) {
        Minecraft mc = Minecraft.getInstance();
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        VertexConsumer consumer = buffer.getBuffer(RenderType.lines());

        HoseCurve curve = HoseCurve.between(start, end, 0.05f);
        Vec3[] points = curve.valid ? curve.sample(12) : new Vec3[]{Vec3.ZERO, end.subtract(start)};

        ms.pushPose();
        ms.translate(start.x - camera.x, start.y - camera.y, start.z - camera.z);
        Matrix4f pose = ms.last().pose();

        float r = ((color >> 16) & 0xFF) / 255f;
        float g = ((color >> 8) & 0xFF) / 255f;
        float b = (color & 0xFF) / 255f;

        for (int i = 0; i < points.length - 1; i++) {
            Vec3 a = points[i];
            Vec3 c = points[i + 1];
            Vec3 dir = c.subtract(a);
            if (dir.lengthSqr() < 1.0e-9) {
                continue;
            }
            dir = dir.normalize();
            consumer.addVertex(pose, (float) a.x, (float) a.y, (float) a.z)
                    .setColor(r, g, b, 0.85f)
                    .setNormal((float) dir.x, (float) dir.y, (float) dir.z);
            consumer.addVertex(pose, (float) c.x, (float) c.y, (float) c.z)
                    .setColor(r, g, b, 0.85f)
                    .setNormal((float) dir.x, (float) dir.y, (float) dir.z);
        }

        ms.popPose();
        buffer.endBatch(RenderType.lines());
    }
}
