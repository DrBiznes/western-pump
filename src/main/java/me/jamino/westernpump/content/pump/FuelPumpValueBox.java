package me.jamino.westernpump.content.pump;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import dev.engine_room.flywheel.lib.transform.TransformStack;
import net.createmod.catnip.math.AngleHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Places the flow-direction selector on the cabinet front, directly under the drum counter.
 *
 * <p>Create only looks for value boxes on the block the player is aiming at, and only the lower
 * half of the pump has a block entity, so the box has to sit entirely inside the lower block: it
 * fills the short cabinet panel between the cog and the counter (model y 10..16), where
 * {@code tools/generate_art.py} paints the matching brass selector plate.
 */
public class FuelPumpValueBox extends ValueBoxTransform {

    /** Centre of the selector plate, just proud of the cabinet front (z = 2 in the north frame). */
    private static final Vec3 PLATE_CENTRE = PumpGeometry.SELECTOR_CENTRE;

    @Override
    public Vec3 getLocalOffset(LevelAccessor level, BlockPos pos, BlockState state) {
        Vec3 centred = PLATE_CENTRE.subtract(0.5, 0, 0.5);
        return PumpGeometry.rotate(facing(state), centred).add(0.5, 0, 0.5);
    }

    @Override
    public void rotate(LevelAccessor level, BlockPos pos, BlockState state, PoseStack ms) {
        // Same convention as ValueBoxTransform.Sided for a box on the face the cabinet looks out of.
        TransformStack.of(ms).rotateYDegrees(AngleHelper.horizontalAngle(facing(state)) + 180);
    }

    /** 6 px: the plate is as tall as the panel it sits on, a little smaller than Create's 8 px default. */
    @Override
    public float getScale() {
        return 6 / 16f;
    }

    private static Direction facing(BlockState state) {
        return state.hasProperty(FuelPumpBlock.FACING) ? state.getValue(FuelPumpBlock.FACING) : Direction.NORTH;
    }
}
