package me.jamino.westernpump.content.pump;

import com.mojang.serialization.MapCodec;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.content.kinetics.base.KineticBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.simpleRelays.ICogWheel;
import com.simibubi.create.foundation.block.IBE;
import me.jamino.westernpump.content.nozzle.NozzleInteractions;
import me.jamino.westernpump.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * The fuel pump cabinet. Two blocks tall from a single item, placed like a door: the lower half
 * carries the block entity and all logic, the upper half is a visual extension that forwards
 * interactions downwards.
 *
 * <p>Kinetically it is a small cogwheel on the vertical axis, like the Mechanical Arm: the wooden
 * cog in the middle of the lower block meshes with any cogwheel placed beside it. Fuel comes in
 * through the bottom only; with rotation the pump pulls it up out of the pipe network below.
 */
public class FuelPumpBlock extends KineticBlock implements IBE<FuelPumpBlockEntity>, ICogWheel, SimpleWaterloggedBlock {

    public static final MapCodec<FuelPumpBlock> CODEC = simpleCodec(FuelPumpBlock::new);

    /** Which way the cabinet front faces. */
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;

    private static final VoxelShape SHAPE_LOWER = Block.box(1, 0, 1, 15, 16, 15);
    private static final VoxelShape SHAPE_UPPER = Block.box(1, 0, 1, 15, 15, 15);

    public FuelPumpBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState()
                .setValue(FACING, Direction.NORTH)
                .setValue(HALF, DoubleBlockHalf.LOWER)
                .setValue(WATERLOGGED, false));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, HALF, WATERLOGGED);
        super.createBlockStateDefinition(builder);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(HALF) == DoubleBlockHalf.LOWER ? SHAPE_LOWER : SHAPE_UPPER;
    }

    // ---- kinetics -----------------------------------------------------------------------

    /** The cog spins about the vertical axis so cogwheels beside the lower block can drive it. */
    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return Direction.Axis.Y;
    }

    /** No shaft input: the bottom is the fuel inlet, the top is the cabinet. Cogs only. */
    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        return false;
    }

    // ---- placement: two blocks from one item --------------------------------------------

    @Override
    @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos pos = context.getClickedPos();
        if (pos.getY() >= context.getLevel().getMaxBuildHeight() - 1
                || !context.getLevel().getBlockState(pos.above()).canBeReplaced(context)) {
            return null;
        }
        boolean waterlogged = context.getLevel().getFluidState(pos).getType() == Fluids.WATER;
        return defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite())
                .setValue(HALF, DoubleBlockHalf.LOWER)
                .setValue(WATERLOGGED, waterlogged);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        BlockPos above = pos.above();
        boolean waterlogged = level.getFluidState(above).getType() == Fluids.WATER;
        level.setBlock(above, state.setValue(HALF, DoubleBlockHalf.UPPER).setValue(WATERLOGGED, waterlogged), Block.UPDATE_ALL);
    }

    /** Breaking either half removes the other, so a half-pump can never be left behind. */
    @Override
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                  LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (state.getValue(WATERLOGGED)) {
            level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        }
        DoubleBlockHalf half = state.getValue(HALF);
        boolean towardOtherHalf = direction.getAxis() == Direction.Axis.Y
                && (half == DoubleBlockHalf.LOWER) == (direction == Direction.UP);
        if (towardOtherHalf) {
            boolean stillPaired = neighborState.is(this) && neighborState.getValue(HALF) != half;
            if (!stillPaired) {
                return Blocks.AIR.defaultBlockState();
            }
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && player.isCreative()) {
            // Mirror vanilla doors: stop the other half dropping an extra item in creative.
            DoubleBlockHalf half = state.getValue(HALF);
            if (half == DoubleBlockHalf.UPPER) {
                BlockPos below = pos.below();
                BlockState belowState = level.getBlockState(below);
                if (belowState.is(this) && belowState.getValue(HALF) == DoubleBlockHalf.LOWER) {
                    level.setBlock(below, Blocks.AIR.defaultBlockState(), Block.UPDATE_SUPPRESS_DROPS | Block.UPDATE_ALL);
                    level.levelEvent(player, 2001, below, Block.getId(belowState));
                }
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        if (state.getValue(HALF) == DoubleBlockHalf.UPPER) {
            BlockState below = level.getBlockState(pos.below());
            return below.is(this) && below.getValue(HALF) == DoubleBlockHalf.LOWER;
        }
        return super.canSurvive(state, level, pos);
    }

    @Override
    public FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    // ---- wrenching -----------------------------------------------------------------------

    /**
     * Rotates the whole cabinet, not just the half that was clicked.
     *
     * <p>Create's default implementation rotates {@link #FACING} on the clicked position alone.
     * That leaves the two halves disagreeing: the model of the clicked half turns, while the hose
     * anchor and holster keep pointing the old way, because those are derived from the
     * <em>lower</em> half's facing (the half that owns the block entity). Turn both halves in one
     * go so the cabinet, the spigot and the hose can never come apart.
     */
    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        BlockState rotated = getRotatedBlockState(state, context.getClickedFace());
        if (rotated == state || !rotated.hasProperty(FACING)) {
            return InteractionResult.PASS;
        }

        DoubleBlockHalf half = state.getValue(HALF);
        BlockPos otherPos = half == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
        BlockState otherState = level.getBlockState(otherPos);
        if (!otherState.is(this) || otherState.getValue(HALF) == half) {
            // A half-pump should not exist, but never rotate into a broken pair if it does.
            return InteractionResult.PASS;
        }

        Direction facing = rotated.getValue(FACING);
        if (!rotated.canSurvive(level, pos)) {
            return InteractionResult.PASS;
        }

        // The nozzle's resting places move with the cabinet. A hose left plugged in across the
        // rotation would jump; hang it back up first so it re-seats on the new side.
        if (level.getBlockEntity(lowerPos(state, pos)) instanceof FuelPumpBlockEntity pump
                && pump.getNozzleState() != FuelPumpBlockEntity.NozzleState.RACKED) {
            pump.returnNozzle();
        }

        KineticBlockEntity.switchToBlockState(level, pos, rotated);
        KineticBlockEntity.switchToBlockState(level, otherPos, otherState.setValue(FACING, facing));

        if (level.getBlockState(pos) != state) {
            IWrenchable.playRotateSound(level, pos);
        }
        return InteractionResult.SUCCESS;
    }

    // ---- interaction ---------------------------------------------------------------------

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        // Hands only: let wrenches, buckets and goggles keep their normal behaviour.
        if (!stack.isEmpty()) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        BlockPos lower = lowerPos(state, pos);
        if (level.isClientSide) {
            return ItemInteractionResult.SUCCESS;
        }
        return NozzleInteractions.onPumpClicked(level, lower, player)
                ? ItemInteractionResult.CONSUME
                : ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    /** The lower half owns the block entity; the upper half defers to it. */
    public static BlockPos lowerPos(BlockState state, BlockPos pos) {
        return state.getValue(HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
    }

    // ---- IBE -----------------------------------------------------------------------------

    @Override
    public Class<FuelPumpBlockEntity> getBlockEntityClass() {
        return FuelPumpBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends FuelPumpBlockEntity> getBlockEntityType() {
        return ModBlockEntities.FUEL_PUMP.get();
    }

    /** Only the lower half has a block entity, so only the lower half is part of a kinetic network. */
    @Override
    @Nullable
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        if (state.getValue(HALF) == DoubleBlockHalf.UPPER) {
            return null;
        }
        return IBE.super.newBlockEntity(pos, state);
    }
}
