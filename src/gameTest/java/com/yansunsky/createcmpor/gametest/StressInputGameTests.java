package com.yansunsky.createcmpor.gametest;

import com.simibubi.create.AllBlocks;
import com.yansunsky.createcmpor.block.StressInputBlock;
import com.yansunsky.createcmpor.block.StressInputBlockEntity;
import com.yansunsky.createcmpor.init.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.DirectionalPlaceContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real block placement and preview rules; particle rendering is accepted separately on a client. */
@GameTestHolder("createcmpor")
@PrefixGameTestTemplate(false)
public final class StressInputGameTests {
    private static final BlockPos INPUT = new BlockPos(2, 2, 2);

    private StressInputGameTests() {
    }

    @GameTest(template = "io_fixture", timeoutTicks = 100)
    public static void single_shaft_placement_connects_all_six_faces(GameTestHelper helper) {
        var block = ModBlocks.STRESS_INPUT.get();
        BlockPos absolute = helper.absolutePos(INPUT);
        for (Direction direction : Direction.values()) {
            BlockPos neighbour = INPUT.relative(direction);
            helper.setBlock(neighbour, AllBlocks.SHAFT.getDefaultState()
                    .setValue(BlockStateProperties.AXIS, direction.getAxis()));
            var context = new DirectionalPlaceContext(helper.getLevel(), absolute,
                    Direction.NORTH, new ItemStack(block), Direction.UP);
            helper.assertTrue(block.getPreferredFacing(context) == direction,
                    "Fixture must offer exactly one connecting shaft: " + direction);
            BlockState placed = block.getStateForPlacement(context);
            helper.assertTrue(placed.getValue(StressInputBlock.FACING) == direction,
                    "Single output must face the neighbouring shaft: " + direction);
            helper.assertTrue(!placed.getValue(StressInputBlock.ACTIVE), "Placement must remain inactive");
            for (Direction face : Direction.values())
                helper.assertTrue(block.hasShaftTowards(helper.getLevel(), absolute, placed, face)
                                == (face == direction), "Only the output face may connect: " + face);
            helper.setBlock(neighbour, Blocks.AIR);
        }
        helper.succeed();
    }

    @GameTest(template = "io_fixture", timeoutTicks = 100)
    public static void free_and_sneaking_placement_match_creative_motor(GameTestHelper helper) {
        var block = ModBlocks.STRESS_INPUT.get();
        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        BlockPos absolute = helper.absolutePos(INPUT);
        for (boolean neighbour : new boolean[]{false, true}) {
            helper.setBlock(INPUT.north(), neighbour ? AllBlocks.SHAFT.getDefaultState()
                    .setValue(BlockStateProperties.AXIS, Direction.Axis.Z) : Blocks.AIR.defaultBlockState());
            for (boolean sneaking : new boolean[]{false, true}) {
                player.setShiftKeyDown(sneaking);
                for (Direction look : Direction.values()) {
                    player.setYRot(look.getAxis().isHorizontal() ? look.toYRot() : 0);
                    player.setXRot(look == Direction.UP ? -90 : look == Direction.DOWN ? 90 : 0);
                    var context = new BlockPlaceContext(helper.getLevel(), player, InteractionHand.MAIN_HAND,
                            new ItemStack(block), new BlockHitResult(Vec3.atCenterOf(absolute),
                            Direction.UP, absolute, false));
                    Direction expected = AllBlocks.CREATIVE_MOTOR.get().getStateForPlacement(context)
                            .getValue(StressInputBlock.FACING);
                    helper.assertTrue(block.getStateForPlacement(context).getValue(StressInputBlock.FACING) == expected,
                            "Placement must preserve motor controls: neighbour=" + neighbour
                                    + " sneak=" + sneaking + " look=" + look);
                }
            }
        }
        helper.succeed();
    }

    @GameTest(template = "io_fixture", timeoutTicks = 100)
    public static void idle_preview_has_no_power_and_active_preview_uses_actual_speed(GameTestHelper helper) {
        for (Direction facing : Direction.values()) {
            helper.setBlock(INPUT, ModBlocks.STRESS_INPUT.get().defaultBlockState()
                    .setValue(StressInputBlock.FACING, facing));
            StressInputBlockEntity blockEntity = helper.getBlockEntity(INPUT);
            for (int configured : new int[]{-256, -16, 0, 16, 256}) {
                blockEntity.generatedSpeed.setValue(configured);
                float expected = facing.getAxisDirection() == Direction.AxisDirection.POSITIVE
                        ? configured : -configured;
                helper.assertTrue(blockEntity.getDirectionPreviewSpeed() == expected,
                        "Idle preview must respect axis sign: " + facing + "/" + configured);
                blockEntity.tick();
                helper.assertTrue(blockEntity.getGeneratedSpeed() == 0 && blockEntity.getSpeed() == 0
                                && !blockEntity.isActive(), "Idle preview must not activate or power the network");
            }
            helper.setBlock(INPUT, helper.getBlockState(INPUT).setValue(StressInputBlock.ACTIVE, true));
            blockEntity = helper.getBlockEntity(INPUT);
            for (float actual : new float[]{-64, 0, 64}) {
                blockEntity.setSpeed(actual);
                helper.assertTrue(blockEntity.getDirectionPreviewSpeed() == actual,
                        "Active preview must use actual speed without converting it again: " + facing);
            }
            helper.setBlock(INPUT, Blocks.AIR);
        }
        helper.succeed();
    }
}
