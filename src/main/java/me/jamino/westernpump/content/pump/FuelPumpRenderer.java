package me.jamino.westernpump.content.pump;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import me.jamino.westernpump.content.hose.FuelEndpoint;
import me.jamino.westernpump.content.hose.HoseCurve;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Draws the fuel hose as a tube along the catenary between the pump's collar and wherever the
 * nozzle is plugged in.
 *
 * <p>The tube is built as a ring of quads swept along the sampled curve, with a stable orientation
 * frame carried from segment to segment so it does not twist. Segment count drops with camera
 * distance.
 */
public class FuelPumpRenderer extends SafeBlockEntityRenderer<FuelPumpBlockEntity> {

    /** Hose radius in blocks — about 2.5 pixels, chunky enough to read as a fuel line. */
    private static final float HOSE_RADIUS = 0.08f;
    /** Sides of the tube cross-section. */
    private static final int RADIAL_SIDES = 6;

    private static final int SEGMENTS_NEAR = 16;
    private static final int SEGMENTS_FAR = 8;
    private static final double FAR_DISTANCE_SQR = 32 * 32;

    private static final int HOSE_COLOR = 0x2A2A2E;

    public FuelPumpRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    protected void renderSafe(FuelPumpBlockEntity pump, float partialTicks, PoseStack ms,
                              MultiBufferSource buffer, int light, int overlay) {
        FuelEndpoint target = pump.getTarget();
        if (pump.getNozzleState() != FuelPumpBlockEntity.NozzleState.ATTACHED || target == null) {
            return;
        }
        Level level = pump.getLevel();
        if (level == null) {
            return;
        }

        Vec3 start = pump.getHoseAnchor();
        // Interpolated so a hose attached to a moving vehicle does not stutter at 20 Hz.
        Vec3 end = target.renderAnchor(level, partialTicks);

        // Hide rather than stretch a hose whose far end is unloaded or has gone out of range.
        if (!level.isLoaded(target.pos())) {
            return;
        }

        HoseCurve curve = HoseCurve.between(start, end, HOSE_RADIUS);
        if (!curve.valid) {
            return;
        }

        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        int segments = camera.distanceToSqr(start) > FAR_DISTANCE_SQR ? SEGMENTS_FAR : SEGMENTS_NEAR;
        Vec3[] points = curve.sample(segments);

        ms.pushPose();
        // Curve points are relative to the pump's anchor; the pose is at the block origin.
        BlockPos origin = pump.getBlockPos();
        ms.translate(start.x - origin.getX(), start.y - origin.getY(), start.z - origin.getZ());

        VertexConsumer consumer = buffer.getBuffer(RenderType.solid());
        renderTube(ms, consumer, points, level, start, light);

        ms.popPose();
    }

    /** Sweeps a ring of quads along the sampled centreline. */
    private void renderTube(PoseStack ms, VertexConsumer consumer, Vec3[] points, Level level,
                            Vec3 worldStart, int pumpLight) {
        Matrix4f pose = ms.last().pose();

        // Seed an "up" reference that is not parallel to the hose, then carry it along the curve so
        // consecutive rings stay aligned and the tube does not spin around its own axis.
        Vec3 reference = new Vec3(0, 1, 0);
        Vec3 firstDir = direction(points[0], points[1]);
        if (Math.abs(firstDir.dot(reference)) > 0.95) {
            reference = new Vec3(1, 0, 0);
        }

        Vec3[] previousRing = null;
        Vec3 normal = reference;

        for (int i = 0; i < points.length; i++) {
            Vec3 dir = i == 0
                    ? direction(points[0], points[1])
                    : direction(points[i - 1], points[i]);

            // Re-orthogonalise the carried reference against the new direction.
            Vec3 side = dir.cross(normal);
            if (side.lengthSqr() < 1.0e-6) {
                side = dir.cross(new Vec3(1, 0, 0));
                if (side.lengthSqr() < 1.0e-6) {
                    side = dir.cross(new Vec3(0, 0, 1));
                }
            }
            side = side.normalize();
            normal = side.cross(dir).normalize();

            Vec3[] ring = buildRing(points[i], side, normal);

            if (previousRing != null) {
                // Light each ring from the block it passes through, so a hose crossing a shadow
                // does not stay lit by the pump's own light value.
                int light = lightAt(level, worldStart.add(points[i]), pumpLight);
                for (int s = 0; s < RADIAL_SIDES; s++) {
                    int next = (s + 1) % RADIAL_SIDES;
                    quad(pose, consumer, previousRing[s], previousRing[next], ring[next], ring[s], light);
                }
            }
            previousRing = ring;
        }
    }

    private Vec3[] buildRing(Vec3 centre, Vec3 side, Vec3 normal) {
        Vec3[] ring = new Vec3[RADIAL_SIDES];
        for (int i = 0; i < RADIAL_SIDES; i++) {
            double angle = 2 * Math.PI * i / RADIAL_SIDES;
            double cos = Math.cos(angle) * HOSE_RADIUS;
            double sin = Math.sin(angle) * HOSE_RADIUS;
            ring[i] = centre.add(side.scale(cos)).add(normal.scale(sin));
        }
        return ring;
    }

    private Vec3 direction(Vec3 from, Vec3 to) {
        Vec3 delta = to.subtract(from);
        return delta.lengthSqr() < 1.0e-8 ? new Vec3(0, 1, 0) : delta.normalize();
    }

    private int lightAt(Level level, Vec3 worldPos, int fallback) {
        BlockPos pos = BlockPos.containing(worldPos);
        if (!level.isLoaded(pos)) {
            return fallback;
        }
        return LightTexture.pack(level.getBrightness(LightLayer.BLOCK, pos), level.getBrightness(LightLayer.SKY, pos));
    }

    private void quad(Matrix4f pose, VertexConsumer consumer, Vec3 a, Vec3 b, Vec3 c, Vec3 d, int light) {
        Vec3 faceNormal = c.subtract(a).cross(d.subtract(b));
        if (faceNormal.lengthSqr() < 1.0e-8) {
            return;
        }
        faceNormal = faceNormal.normalize();

        float r = ((HOSE_COLOR >> 16) & 0xFF) / 255f;
        float g = ((HOSE_COLOR >> 8) & 0xFF) / 255f;
        float bl = (HOSE_COLOR & 0xFF) / 255f;

        vertex(pose, consumer, a, r, g, bl, 0, 0, light, faceNormal);
        vertex(pose, consumer, b, r, g, bl, 1, 0, light, faceNormal);
        vertex(pose, consumer, c, r, g, bl, 1, 1, light, faceNormal);
        vertex(pose, consumer, d, r, g, bl, 0, 1, light, faceNormal);
    }

    private void vertex(Matrix4f pose, VertexConsumer consumer, Vec3 pos, float r, float g, float b,
                        float u, float v, int light, Vec3 normal) {
        consumer.addVertex(pose, (float) pos.x, (float) pos.y, (float) pos.z)
                .setColor(r, g, b, 1f)
                .setUv(u, v)
                .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal((float) normal.x, (float) normal.y, (float) normal.z);
    }

    /** The hose leaves the block, so the renderer must not be culled with it. */
    @Override
    public boolean shouldRenderOffScreen(FuelPumpBlockEntity pump) {
        return pump.getNozzleState() == FuelPumpBlockEntity.NozzleState.ATTACHED;
    }

    @Override
    public int getViewDistance() {
        return 96;
    }
}
