package me.jamino.westernpump.content.pump;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import me.jamino.westernpump.WesternPump;
import me.jamino.westernpump.client.ModPartialModels;
import me.jamino.westernpump.content.hose.FuelEndpoint;
import me.jamino.westernpump.content.hose.HoseCurve;
import me.jamino.westernpump.content.pump.FuelPumpBlockEntity.NozzleState;
import me.jamino.westernpump.content.pump.NozzleAnimation.Pose;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Draws everything on the pump that moves: the nozzle, the hose, and the drum counter.
 *
 * <p>The hose follows Create: Power Grid's wire renderer (Apache-2.0, patryk3211): a standalone
 * tile texture outside the block atlas, U measured in blocks of arc length so the pattern never
 * stretches however long the hose is, light sampled where each ring actually hangs, and segment
 * size chosen by camera distance. The one deliberate difference is that V wraps the full
 * circumference with the seam underneath, so the tube has a lit top and a spiral rib.
 */
public class FuelPumpRenderer extends SafeBlockEntityRenderer<FuelPumpBlockEntity> {

    private static final ResourceLocation HOSE_TEXTURE = WesternPump.asResource("textures/special/fuel_pump_hose.png");
    private static final ResourceLocation FERRULE_TEXTURE = WesternPump.asResource("textures/special/fuel_pump_ferrule.png");
    private static final ResourceLocation DRUM_TEXTURE = WesternPump.asResource("textures/special/fuel_pump_drum.png");

    /** Hose radius in blocks: a hair under 3 px across, a proper forecourt hose next to a 4 px pipe. */
    private static final float HOSE_RADIUS = 0.085f;
    private static final float FERRULE_RADIUS = HOSE_RADIUS * 1.35f;
    private static final float FERRULE_LENGTH = 0.11f;

    /** Segment length in blocks per LOD level. */
    private static final double[] SEGMENT_LENGTH = {0.25, 0.75, 2.0};
    /** The hose is a square tube, like every other Minecraft cable; four flat faces at every LOD. */
    private static final int RING_SIDES = 4;
    private static final double LOD1_DISTANCE_SQR = 32 * 32;
    private static final double LOD2_DISTANCE_SQR = 64 * 64;

    /** Drum digit strip layout, matching tools/generate_art.py. */
    private static final int DRUM_TEX_W = 8;
    private static final int DRUM_TEX_H = 60;
    private static final int DRUM_CELL = 6;
    private static final int DRUM_DIGIT_H = 5;
    private static final int DRUM_DIGIT_W = 3;
    /** Window column starts in the upper block's front, in model pixels (matches generate_art.py DRUM_X). */
    private static final double[] DRUM_X = {0.5, 4.5, 8.5, 12.5};
    private static final double DRUM_Y0 = 6, DRUM_Y1 = 11, DRUM_Z = 0.5;

    public FuelPumpRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    protected void renderSafe(FuelPumpBlockEntity pump, float partialTicks, PoseStack ms,
                              MultiBufferSource buffer, int light, int overlay) {
        Level level = pump.getLevel();
        if (level == null) {
            return;
        }
        BlockPos origin = pump.getBlockPos();
        Direction facing = pump.getFacing();
        Vec3 anchor = pump.getHoseAnchor();

        Pose target = restingPose(pump, level, facing, partialTicks);
        Pose pose = pump.nozzleAnimation().update(pump.getNozzleState(), target,
                AnimationTickHolder.getRenderTime(level));

        renderCog(ms, buffer, pump, light);
        renderNozzle(ms, buffer, pump, pose, origin, light);
        renderHose(ms, buffer, level, anchor, pose.inlet(), origin, light);
        renderDrums(ms, buffer, pump, facing, partialTicks, light);
    }

    /** Create's own wooden cogwheel, turning with the kinetic network in the middle of the base. */
    private void renderCog(PoseStack ms, MultiBufferSource buffer, FuelPumpBlockEntity pump, int light) {
        KineticBlockEntityRenderer.standardKineticRotationTransform(
                        CachedBuffers.partial(AllPartialModels.SHAFTLESS_COGWHEEL, pump.getBlockState()), pump, light)
                .renderInto(ms, buffer.getBuffer(RenderType.solid()));
    }

    // ---- nozzle pose --------------------------------------------------------------------

    /** Where the nozzle wants to be for the pump's current state. */
    private Pose restingPose(FuelPumpBlockEntity pump, Level level, Direction facing, float partialTicks) {
        NozzleState state = pump.getNozzleState();
        Pose holster = holsterPose(pump, facing);

        if (state == NozzleState.HELD) {
            Player holder = pump.getHolder() == null ? null : level.getPlayerByUUID(pump.getHolder());
            return holder == null ? holster : handPose(holder, partialTicks);
        }
        if (state == NozzleState.ATTACHED) {
            FuelEndpoint target = pump.getTarget();
            if (target == null || !level.isLoaded(target.pos())) {
                return holster;
            }
            Vec3 face = target.renderAnchor(level, partialTicks);
            Vec3 normal = Vec3.atLowerCornerOf(target.side().getNormal());
            // Face points into the tank; the inlet sits one coupler-length back out along the normal.
            // Tank side panels tend to be inset a pixel, so on a side face the flange is sunk by one.
            double seat = target.side().getAxis().isHorizontal() ? -1 / 16.0 : 0.02;
            Vec3 inlet = face.add(normal.scale(PumpGeometry.NOZZLE_LENGTH + seat));
            Vec3 spout = normal.scale(-1);
            Vec3 up;
            if (Math.abs(normal.y) > 0.5) {
                // Plugged into a top or bottom face: turn the grip towards the pump.
                Vec3 back = pump.getHoseAnchor().subtract(face);
                up = new Vec3(back.x, 0, back.z);
            } else {
                up = new Vec3(0, 1, 0);
            }
            return new Pose(inlet, spout, up);
        }
        return holster;
    }

    private Pose holsterPose(FuelPumpBlockEntity pump, Direction facing) {
        // Hanging spout-down in the cradle, grip towards the back of the cabinet.
        return new Pose(pump.getHolsterInlet(), new Vec3(0, -1, 0), PumpGeometry.rotate(facing, new Vec3(0, 0, 1)));
    }

    /** The nozzle carried in the player's right hand, spout following their gaze. */
    public static Pose handPose(Player player, float partialTicks) {
        Vec3 eye = player.getEyePosition(partialTicks);
        Vec3 look = player.getViewVector(partialTicks);
        Vec3 right = look.cross(new Vec3(0, 1, 0));
        if (right.lengthSqr() < 1.0e-6) {
            right = new Vec3(1, 0, 0);
        }
        right = right.normalize();
        Vec3 inlet = eye.add(look.scale(0.45)).add(right.scale(0.32)).add(0, -0.30, 0);
        return new Pose(inlet, look, new Vec3(0, 1, 0));
    }

    // ---- nozzle -------------------------------------------------------------------------

    private void renderNozzle(PoseStack ms, MultiBufferSource buffer, FuelPumpBlockEntity pump, Pose pose,
                              BlockPos origin, int pumpLight) {
        Level level = pump.getLevel();
        Vec3 rel = pose.inlet().subtract(origin.getX(), origin.getY(), origin.getZ());

        // Basis: model -Y is the spout, model +Z is the grip (the side the hand is on).
        Vec3 yAxis = pose.spout().scale(-1);
        Vec3 zAxis = pose.up();
        Vec3 xAxis = yAxis.cross(zAxis);
        Matrix3f basis = new Matrix3f(
                new Vector3f((float) xAxis.x, (float) xAxis.y, (float) xAxis.z),
                new Vector3f((float) yAxis.x, (float) yAxis.y, (float) yAxis.z),
                new Vector3f((float) zAxis.x, (float) zAxis.y, (float) zAxis.z));

        ms.pushPose();
        ms.translate(rel.x, rel.y, rel.z);
        ms.mulPose(new Matrix4f().set(basis));
        ms.translate(-PumpGeometry.NOZZLE_INLET_LOCAL.x, -PumpGeometry.NOZZLE_INLET_LOCAL.y, -PumpGeometry.NOZZLE_INLET_LOCAL.z);

        int light = level == null ? pumpLight : lightAt(level, pose.inlet(), pumpLight);
        CachedBuffers.partial(ModPartialModels.NOZZLE, pump.getBlockState())
                .light(light)
                .renderInto(ms, buffer.getBuffer(RenderType.cutoutMipped()));
        ms.popPose();
    }

    // ---- hose ---------------------------------------------------------------------------

    private void renderHose(PoseStack ms, MultiBufferSource buffer, Level level, Vec3 start, Vec3 end,
                            BlockPos origin, int pumpLight) {
        HoseCurve curve = HoseCurve.between(start, end, HOSE_RADIUS);
        if (!curve.valid) {
            return;
        }

        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        double camDist = Math.min(camera.distanceToSqr(start), camera.distanceToSqr(end));
        int lod = camDist > LOD2_DISTANCE_SQR ? 2 : camDist > LOD1_DISTANCE_SQR ? 1 : 0;
        int segments = Mth.clamp((int) Math.round(curve.length / SEGMENT_LENGTH[lod]), 4, 160);
        Vec3[] points = curve.sample(segments);

        ms.pushPose();
        // Curve points are relative to the hose anchor; the pose stack sits at the block origin.
        ms.translate(start.x - origin.getX(), start.y - origin.getY(), start.z - origin.getZ());

        // Standalone textures wrap, so U can simply be the distance travelled in blocks.
        VertexConsumer hose = buffer.getBuffer(RenderType.entityCutoutNoCull(HOSE_TEXTURE));
        sweepTube(ms, hose, points, RING_SIDES, HOSE_RADIUS, 1.0, level, start, pumpLight);

        // Copper ferrules where the hose meets the spigot and the nozzle.
        VertexConsumer ferrule = buffer.getBuffer(RenderType.entityCutoutNoCull(FERRULE_TEXTURE));
        Vec3 firstDir = direction(points[0], points[1]);
        Vec3 lastDir = direction(points[points.length - 2], points[points.length - 1]);
        Vec3 tail = points[points.length - 1];
        sweepTube(ms, ferrule, new Vec3[]{points[0], points[0].add(firstDir.scale(FERRULE_LENGTH))},
                RING_SIDES, FERRULE_RADIUS, 4.0, level, start, pumpLight);
        sweepTube(ms, ferrule, new Vec3[]{tail.subtract(lastDir.scale(FERRULE_LENGTH)), tail},
                RING_SIDES, FERRULE_RADIUS, 4.0, level, start, pumpLight);

        ms.popPose();
    }

    /**
     * Sweeps a square tube ({@code sides} flat faces) along the polyline. Corners sit on the
     * diagonals so the top face is flat, and {@code radius} is the half-width of a face. U
     * advances by arc length times {@code uPerBlock}; V goes once around starting from the
     * underside, so a 16 px tile maps one block of hose along by all four faces around.
     */
    private void sweepTube(PoseStack ms, VertexConsumer consumer, Vec3[] points, int sides, float radius,
                           double uPerBlock, Level level, Vec3 worldStart, int pumpLight) {
        Matrix4f pose = ms.last().pose();

        // Carry an "up" reference along the curve so consecutive rings stay aligned and the tube
        // does not twist; re-orthogonalise it against each new direction.
        Vec3 normal = new Vec3(0, 1, 0);
        Vec3 firstDir = direction(points[0], points[1]);
        if (Math.abs(firstDir.dot(normal)) > 0.95) {
            normal = new Vec3(1, 0, 0);
        }

        Vec3[] previousRing = null;
        Vec3[] previousNormals = null;
        double previousU = 0;
        int previousLight = pumpLight;
        double arc = 0;

        for (int i = 0; i < points.length; i++) {
            Vec3 dir = i == 0 ? firstDir : direction(points[i - 1], points[i]);
            if (i > 0) {
                arc += points[i].distanceTo(points[i - 1]);
            }
            Vec3 side = dir.cross(normal);
            if (side.lengthSqr() < 1.0e-6) {
                side = dir.cross(new Vec3(1, 0, 0));
                if (side.lengthSqr() < 1.0e-6) {
                    side = dir.cross(new Vec3(0, 0, 1));
                }
            }
            side = side.normalize();
            normal = side.cross(dir).normalize();

            Vec3[] ring = new Vec3[sides];
            Vec3[] ringNormals = new Vec3[sides];
            double cornerRadius = radius / Math.cos(Math.PI / sides);
            for (int s = 0; s < sides; s++) {
                // Corner s starts face s; the first face is the underside so the seam hangs where
                // nobody looks. Corners sit half a face-angle before the face's outward direction.
                double corner = 2 * Math.PI * (s - 0.5) / sides - Math.PI / 2;
                double faceAngle = 2 * Math.PI * s / sides - Math.PI / 2;
                ring[s] = points[i].add(side.scale(Math.cos(corner)).add(normal.scale(Math.sin(corner))).scale(cornerRadius));
                // Flat shading: the face normal, not the corner direction.
                ringNormals[s] = side.scale(Math.cos(faceAngle)).add(normal.scale(Math.sin(faceAngle)));
            }
            double u = arc * uPerBlock;
            int light = lightAt(level, worldStart.add(points[i]), pumpLight);

            if (previousRing != null) {
                for (int s = 0; s < sides; s++) {
                    int next = (s + 1) % sides;
                    float v0 = (float) s / sides;
                    float v1 = (float) (s + 1) / sides;
                    // Face s: previous ring (u0) to this ring (u1), corner s (v0) to corner s+1 (v1).
                    vertex(pose, consumer, previousRing[s], (float) previousU, v0, previousLight, previousNormals[s]);
                    vertex(pose, consumer, ring[s], (float) u, v0, light, ringNormals[s]);
                    vertex(pose, consumer, ring[next], (float) u, v1, light, ringNormals[s]);
                    vertex(pose, consumer, previousRing[next], (float) previousU, v1, previousLight, previousNormals[s]);
                }
            }
            previousRing = ring;
            previousNormals = ringNormals;
            previousU = u;
            previousLight = light;
        }
    }

    private static Vec3 direction(Vec3 from, Vec3 to) {
        Vec3 delta = to.subtract(from);
        return delta.lengthSqr() < 1.0e-10 ? new Vec3(0, -1, 0) : delta.normalize();
    }

    private static int lightAt(Level level, Vec3 worldPos, int fallback) {
        BlockPos pos = BlockPos.containing(worldPos);
        if (!level.isLoaded(pos)) {
            return fallback;
        }
        return LightTexture.pack(level.getBrightness(LightLayer.BLOCK, pos), level.getBrightness(LightLayer.SKY, pos));
    }

    private static void vertex(Matrix4f pose, VertexConsumer consumer, Vec3 p, float u, float v, int light, Vec3 normal) {
        consumer.addVertex(pose, (float) p.x, (float) p.y, (float) p.z)
                .setColor(255, 255, 255, 255)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal((float) normal.x, (float) normal.y, (float) normal.z);
    }

    // ---- drum counter -------------------------------------------------------------------

    /**
     * Four odometer drums behind the bezel showing what this fill-up has delivered as "NN.NN"
     * buckets (so 1500 mB reads 01.50), resetting when the nozzle is racked. Four 3 px digits are
     * all a 16 px face can hold, so the reading is in hundredths of a bucket rather than raw mB.
     * Each drum is a strip of ten digits scrolled past the window; a drum only turns while the
     * drum below it rolls from 9 to 0, which is what makes it read as gearing rather than a counter.
     */
    private void renderDrums(PoseStack ms, MultiBufferSource buffer, FuelPumpBlockEntity pump, Direction facing,
                             float partialTicks, int light) {
        double hundredths = pump.estimateSessionMb(partialTicks) / 10.0;
        double[] positions = odometer(hundredths, DRUM_X.length);

        ms.pushPose();
        // The drums live in the upper block; rotate with the blockstate around the column's axis.
        ms.translate(0.5, 0, 0.5);
        ms.mulPose(Axis.YP.rotationDegrees(-PumpGeometry.yRotationDegrees(facing)));
        ms.translate(-0.5, 1, -0.5);
        Matrix4f pose = ms.last().pose();
        VertexConsumer consumer = buffer.getBuffer(RenderType.entityCutout(DRUM_TEXTURE));

        for (int i = 0; i < DRUM_X.length; i++) {
            // Leftmost drum is the most significant digit.
            double p = positions[DRUM_X.length - 1 - i];
            double vTop = p * DRUM_CELL + 1;
            drawDrumFace(pose, consumer, DRUM_X[i], vTop, light);
        }
        ms.popPose();
    }

    /** Drum positions (0..10, fractional while turning) for each power of ten, least significant first. */
    private static double[] odometer(double value, int drums) {
        double[] out = new double[drums];
        double v = Math.max(0, value);
        double carry = v % 1.0;
        for (int i = 0; i < drums; i++) {
            double scaled = v / Math.pow(10, i);
            int digit = (int) Math.floor(scaled) % 10;
            if (i == 0) {
                out[i] = digit + carry;
            } else {
                // Turn only during the last tenth of the lower drum's cycle, and only if that
                // drum is passing 9 -> 0 (so every drum above rolls over together).
                double lower = out[i - 1];
                double frac = lower >= 9 ? lower - 9 : 0;
                out[i] = digit + frac;
            }
        }
        return out;
    }

    /** One 3x5 px window onto the digit strip, split in two where the strip wraps. */
    private static void drawDrumFace(Matrix4f pose, VertexConsumer consumer, double xPx, double vTopPx, int light) {
        double vTop = vTopPx % DRUM_TEX_H;
        double vBottom = vTop + DRUM_DIGIT_H;
        double x0 = xPx / 16.0, x1 = (xPx + DRUM_DIGIT_W) / 16.0;
        double y1 = DRUM_Y1 / 16.0, y0 = DRUM_Y0 / 16.0;
        double z = DRUM_Z / 16.0;
        if (vBottom <= DRUM_TEX_H) {
            drumQuad(pose, consumer, x0, x1, y1, y0, z, vTop, vBottom, light);
            return;
        }
        double split = DRUM_TEX_H - vTop;               // texture px shown before the wrap
        double ySplit = y1 - (y1 - y0) * (split / DRUM_DIGIT_H);
        drumQuad(pose, consumer, x0, x1, y1, ySplit, z, vTop, DRUM_TEX_H, light);
        drumQuad(pose, consumer, x0, x1, ySplit, y0, z, 0, vBottom - DRUM_TEX_H, light);
    }

    private static void drumQuad(Matrix4f pose, VertexConsumer consumer, double x0, double x1, double yTop, double yBottom,
                                 double z, double vTopPx, double vBottomPx, int light) {
        float u0 = 0f, u1 = (float) DRUM_DIGIT_W / DRUM_TEX_W;
        float v0 = (float) (vTopPx / DRUM_TEX_H), v1 = (float) (vBottomPx / DRUM_TEX_H);
        Vec3 n = new Vec3(0, 0, -1);
        // Facing north (-z): seen from the front, +x is on the viewer's left, so u0 goes to x1.
        vertex(pose, consumer, new Vec3(x1, yTop, z), u0, v0, light, n);
        vertex(pose, consumer, new Vec3(x1, yBottom, z), u0, v1, light, n);
        vertex(pose, consumer, new Vec3(x0, yBottom, z), u1, v1, light, n);
        vertex(pose, consumer, new Vec3(x0, yTop, z), u1, v0, light, n);
    }

    // ---- culling ------------------------------------------------------------------------

    /** The hose leaves the block, so the renderer must not be culled with it. */
    @Override
    public boolean shouldRenderOffScreen(FuelPumpBlockEntity pump) {
        return pump.getNozzleState() != NozzleState.RACKED;
    }

    /**
     * Computed fresh each time: Create caches the block entity's own box and only clears it on a
     * sync, which is too late for a hose that follows a moving hand or vehicle.
     */
    @Override
    public @NotNull AABB getRenderBoundingBox(@NotNull FuelPumpBlockEntity pump) {
        return pump.createRenderBoundingBox();
    }

    @Override
    public int getViewDistance() {
        return 96;
    }
}
