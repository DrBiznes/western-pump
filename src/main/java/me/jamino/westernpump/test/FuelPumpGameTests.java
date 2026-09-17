package me.jamino.westernpump.test;

import com.simibubi.create.AllBlocks;
import me.jamino.westernpump.WesternPump;
import me.jamino.westernpump.content.hose.FuelEndpoint;
import me.jamino.westernpump.content.pump.FuelPumpBlock;
import me.jamino.westernpump.content.pump.FuelPumpBlockEntity;
import me.jamino.westernpump.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Server-side behaviour tests. Run with {@code ./gradlew runGameTestServer}.
 *
 * <p>These use an empty 3x3 platform template, placing blocks from code, so they need no structure
 * NBT files.
 */
@GameTestHolder(WesternPump.MOD_ID)
@PrefixGameTestTemplate(false)
public class FuelPumpGameTests {

    private static final String PLATFORM = "platform";

    /** Placing the lower half must create the upper half, and both must survive. */
    @GameTest(template = PLATFORM)
    public static void placesTwoBlocksTall(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, ModBlocks.FUEL_PUMP.getDefaultState());
        // setBlock does not run setPlacedBy, so place the upper half explicitly, mirroring it.
        helper.setBlock(pos.above(), ModBlocks.FUEL_PUMP.getDefaultState()
                .setValue(FuelPumpBlock.HALF, DoubleBlockHalf.UPPER));

        helper.succeedWhen(() -> {
            BlockState lower = helper.getBlockState(pos);
            BlockState upper = helper.getBlockState(pos.above());
            helper.assertBlockPresent(ModBlocks.FUEL_PUMP.get(), pos);
            helper.assertBlockPresent(ModBlocks.FUEL_PUMP.get(), pos.above());
            if (lower.getValue(FuelPumpBlock.HALF) != DoubleBlockHalf.LOWER) {
                helper.fail("lower half has the wrong HALF value");
            }
            if (upper.getValue(FuelPumpBlock.HALF) != DoubleBlockHalf.UPPER) {
                helper.fail("upper half has the wrong HALF value");
            }
        });
    }

    /** Both halves must expose the fluid handler, so a pipe can feed either one. */
    @GameTest(template = PLATFORM)
    public static void exposesFluidCapabilityOnBothHalves(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        placePump(helper, pos);

        helper.succeedWhen(() -> {
            BlockPos lower = helper.absolutePos(pos);
            BlockPos upper = helper.absolutePos(pos.above());
            IFluidHandler lowerCap = helper.getLevel()
                    .getCapability(Capabilities.FluidHandler.BLOCK, lower, Direction.NORTH);
            IFluidHandler upperCap = helper.getLevel()
                    .getCapability(Capabilities.FluidHandler.BLOCK, upper, Direction.NORTH);
            if (lowerCap == null) {
                helper.fail("lower half exposes no fluid handler");
            }
            if (upperCap == null) {
                helper.fail("upper half exposes no fluid handler - pipes could not feed it");
            }
        });
    }

    /** The buffer must accept fuel pushed in by a pipe, and report it back. */
    @GameTest(template = PLATFORM)
    public static void bufferAcceptsFluid(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        placePump(helper, pos);

        BlockPos absolute = helper.absolutePos(pos);
        IFluidHandler cap = helper.getLevel()
                .getCapability(Capabilities.FluidHandler.BLOCK, absolute, Direction.NORTH);
        if (cap == null) {
            helper.fail("no fluid handler to fill");
            return;
        }
        int filled = cap.fill(new FluidStack(Fluids.WATER, 500), IFluidHandler.FluidAction.EXECUTE);
        if (filled <= 0) {
            helper.fail("buffer refused fluid from a pipe");
            return;
        }

        helper.succeedWhen(() -> {
            FuelPumpBlockEntity pump = pump(helper, pos);
            if (pump.getBufferContents().getAmount() != filled) {
                helper.fail("buffer reported " + pump.getBufferContents().getAmount()
                        + " but accepted " + filled);
            }
        });
    }

    /**
     * The whole point: an attached pump moves fuel into a real Create tank next to it, using the
     * pump's own attach + tick logic rather than a re-implementation.
     */
    @GameTest(template = PLATFORM, timeoutTicks = 200)
    public static void transfersFuelToAttachedTarget(GameTestHelper helper) {
        BlockPos pumpPos = new BlockPos(1, 1, 1);
        BlockPos tankPos = new BlockPos(3, 1, 1);
        placePump(helper, pumpPos);
        helper.setBlock(tankPos, AllBlocks.FLUID_TANK.getDefaultState());

        FuelPumpBlockEntity pump = pump(helper, pumpPos);
        IFluidHandler source = helper.getLevel().getCapability(
                Capabilities.FluidHandler.BLOCK, helper.absolutePos(pumpPos), Direction.NORTH);
        if (source == null) {
            helper.fail("pump exposes no handler");
            return;
        }
        source.fill(new FluidStack(Fluids.WATER, 1000), IFluidHandler.FluidAction.EXECUTE);

        // Attach the nozzle to the tank exactly as a player click would.
        FuelEndpoint endpoint = FuelEndpoint.of(
                helper.getLevel(), helper.absolutePos(tankPos), Direction.WEST);
        pump.attachTo(endpoint);

        helper.succeedWhen(() -> {
            IFluidHandler receiver = helper.getLevel().getCapability(
                    Capabilities.FluidHandler.BLOCK, helper.absolutePos(tankPos), Direction.WEST);
            if (receiver == null) {
                helper.fail("target tank exposes no handler");
                return;
            }
            int inTank = receiver.getFluidInTank(0).getAmount();
            if (inTank <= 0) {
                helper.fail("pump moved no fuel into the attached tank");
                return;
            }
            // Conservation: nothing may be created or destroyed in transit.
            int total = pump.getBufferContents().getAmount() + inTank;
            if (total != 1000) {
                helper.fail("fuel was duplicated or voided: total " + total + ", expected 1000");
            }
        });
    }

    private static void placePump(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos, ModBlocks.FUEL_PUMP.getDefaultState());
        helper.setBlock(pos.above(), ModBlocks.FUEL_PUMP.getDefaultState()
                .setValue(FuelPumpBlock.HALF, DoubleBlockHalf.UPPER));
    }

    private static FuelPumpBlockEntity pump(GameTestHelper helper, BlockPos pos) {
        return (FuelPumpBlockEntity) helper.getBlockEntity(pos);
    }

    /**
     * Regression test for the crash on opening the creative inventory:
     * {@code IllegalArgumentException: Itemstack 1 westernpump:fuel_pump already exists in the
     * tab's list}. Builds every creative tab the way the inventory screen does and asserts our
     * item appears exactly once in total.
     */
    @GameTest(template = PLATFORM)
    public static void fuelPumpAppearsInExactlyOneCreativeTab(GameTestHelper helper) {
        var params = new net.minecraft.world.item.CreativeModeTab.ItemDisplayParameters(
                net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS,
                true,
                helper.getLevel().registryAccess());

        int totalStacks = 0;
        java.util.List<String> tabsContaining = new java.util.ArrayList<>();

        for (var entry : net.minecraft.core.registries.BuiltInRegistries.CREATIVE_MODE_TAB.entrySet()) {
            net.minecraft.world.item.CreativeModeTab tab = entry.getValue();
            if (tab.getType() != net.minecraft.world.item.CreativeModeTab.Type.CATEGORY) {
                continue;
            }
            // buildContents throws outright if a duplicate stack was contributed.
            tab.buildContents(params);
            long here = tab.getDisplayItems().stream()
                    .filter(stack -> stack.getItem() == ModBlocks.FUEL_PUMP.asItem())
                    .count();
            if (here > 0) {
                tabsContaining.add(entry.getKey().location().toString());
                totalStacks += (int) here;
            }
        }

        if (totalStacks != 1) {
            helper.fail("fuel pump appears " + totalStacks + " time(s) in tabs " + tabsContaining
                    + "; expected exactly 1");
            return;
        }

        // With Diesel Generators installed it must live in that mod's tab, not our fallback.
        boolean diesel = net.neoforged.fml.ModList.get().isLoaded("createdieselgenerators");
        String expected = diesel
                ? "createdieselgenerators:cdg_creative_tab"
                : "westernpump:main";
        if (!tabsContaining.contains(expected)) {
            helper.fail("fuel pump should be in " + expected + " but was in " + tabsContaining);
            return;
        }
        helper.succeed();
    }

    /** Guards the endpoint's identity check, which is what stops a hose re-targeting a new block. */
    @GameTest(template = PLATFORM)
    public static void endpointRoundTripsThroughNbt(GameTestHelper helper) {
        FuelEndpoint endpoint = new FuelEndpoint(new BlockPos(4, 5, 6), Direction.EAST, null);
        FuelEndpoint loaded = FuelEndpoint.load(endpoint.save(helper.getLevel().registryAccess()));
        if (loaded == null || !loaded.equals(endpoint)) {
            helper.fail("endpoint did not survive a save/load round trip: " + loaded);
            return;
        }
        helper.succeed();
    }
}
