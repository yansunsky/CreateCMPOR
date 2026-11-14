package com.yansunsky.createcmpor.gametest;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.yansunsky.createcmpor.block.StressInputBlock;
import com.yansunsky.createcmpor.block.StressInputBlockEntity;
import com.yansunsky.createcmpor.block.StressOutputBlock;
import com.yansunsky.createcmpor.init.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Collision and real outline-ray checks; visual input acceptance remains a client check. */
@GameTestHolder("createcmpor")
@PrefixGameTestTemplate(false)
public final class StressShapeGameTests {
    private static final BlockPos SUBJECT = new BlockPos(2, 2, 2);

    private StressShapeGameTests() {
    }

    @GameTest(template = "io_fixture", timeoutTicks = 100)
    public static void input_outline_and_player_collision_match_motor(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(SUBJECT);
        var context = CollisionContext.of(helper.makeMockPlayer(GameType.CREATIVE));
        AABB fallingPlayer = new AABB(.2, 2, .2, .8, 3.8, .8);
        for (Direction facing : Direction.values()) {
            BlockState motor = AllBlocks.CREATIVE_MOTOR.getDefaultState().setValue(StressInputBlock.FACING, facing);
            VoxelShape expectedOutline = motor.getShape(helper.getLevel(), pos, context);
            VoxelShape expectedCollision = motor.getCollisionShape(helper.getLevel(), pos, context);
            for (boolean active : new boolean[]{false, true}) {
                BlockState state = ModBlocks.STRESS_INPUT.get().defaultBlockState()
                        .setValue(StressInputBlock.FACING, facing).setValue(StressInputBlock.ACTIVE, active);
                helper.setBlock(SUBJECT, state);
                assertSameShape(helper, state.getShape(helper.getLevel(), pos, context), expectedOutline,
                        "Input outline must match motor: " + facing + "/" + active);
                VoxelShape collision = state.getCollisionShape(helper.getLevel(), pos, context);
                assertSameShape(helper, collision, expectedCollision,
                        "Input player collision must match motor: " + facing + "/" + active);
                double feet = 2 + collision.collide(Direction.Axis.Y, fallingPlayer, -2);
                double expectedFeet = 2 + expectedCollision.collide(Direction.Axis.Y, fallingPlayer, -2);
                helper.assertTrue(Math.abs(feet - expectedFeet) < 1e-7,
                        "Standing height must match motor: " + facing + "/" + active);
                if (facing.getAxis().isHorizontal())
                    helper.assertTrue(Math.abs(feet - 13 / 16d) < 1e-7,
                            "Horizontal input standing height must be 13/16, not a full block");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "io_fixture", timeoutTicks = 100)
    public static void output_shape_follows_existing_gauge_model_rotations(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(SUBJECT);
        var context = CollisionContext.of(helper.makeMockPlayer(GameType.CREATIVE));
        for (Direction shaft : Direction.values()) {
            // Integer collision extents of the current gauge model, excluding its 0.05px z-fighting offsets.
            VoxelShape base = switch (shaft) {
                case DOWN -> Block.box(1, 0, 0, 15, 16, 2);
                case UP -> Block.box(1, 0, 14, 15, 16, 16);
                case NORTH -> Block.box(0, 1, 0, 2, 15, 16);
                case SOUTH -> Block.box(14, 1, 0, 16, 15, 16);
                case WEST -> Block.box(0, 1, 14, 16, 15, 16);
                case EAST -> Block.box(0, 1, 0, 16, 15, 2);
            };
            VoxelShape body = switch (shaft.getAxis()) {
                case X -> Block.box(1, 2, 2, 15, 14, 14);
                case Y -> Block.box(2, 1, 2, 14, 15, 14);
                case Z -> Block.box(2, 2, 1, 14, 14, 15);
            };
            VoxelShape expected = Shapes.or(base, body);
            for (boolean active : new boolean[]{false, true}) {
                BlockState state = ModBlocks.STRESS_OUTPUT.get().defaultBlockState()
                        .setValue(StressOutputBlock.FACING, shaft).setValue(StressOutputBlock.ACTIVE, active);
                helper.setBlock(SUBJECT, state);
                assertSameShape(helper, state.getShape(helper.getLevel(), pos, context), expected,
                        "Output outline must follow gauge model: " + shaft + "/" + active);
                assertSameShape(helper, state.getCollisionShape(helper.getLevel(), pos, context), expected,
                        "Output collision must follow gauge model: " + shaft + "/" + active);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "io_fixture", timeoutTicks = 100)
    public static void oblique_outline_rays_reach_the_input_speed_control(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(SUBJECT);
        var player = helper.makeMockPlayer(GameType.CREATIVE);
        int checked = 0;
        for (Direction facing : Direction.values()) {
            for (boolean active : new boolean[]{false, true}) {
                helper.setBlock(SUBJECT, ModBlocks.STRESS_INPUT.get().defaultBlockState()
                        .setValue(StressInputBlock.FACING, facing).setValue(StressInputBlock.ACTIVE, active));
                StressInputBlockEntity input = helper.getBlockEntity(SUBJECT);
                var slot = (ValueBoxTransform.Sided) input.generatedSpeed.getSlotPositioning();
                for (Direction side : Direction.values()) {
                    slot.fromSide(side);
                    if (!slot.shouldRender(helper.getLevel(), pos, input.getBlockState()))
                        continue;
                    Vec3 target = slot.getLocalOffset(helper.getLevel(), pos, input.getBlockState())
                            .add(Vec3.atLowerCornerOf(pos));
                    Vec3 diagonal = Vec3.atLowerCornerOf(side.getNormal())
                            .add(Vec3.atLowerCornerOf(facing.getNormal()));
                    var hit = helper.getLevel().clip(new ClipContext(target.add(diagonal.scale(2)),
                            target.subtract(diagonal.scale(.5)), ClipContext.Block.OUTLINE,
                            ClipContext.Fluid.NONE, player));
                    helper.assertTrue(hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)
                                    && hit.getDirection() == side && input.generatedSpeed.testHit(hit.getLocation()),
                            "Oblique ray must reach the speed control: " + facing + "/" + side + "/" + active);
                    checked++;
                }
            }
        }
        helper.assertTrue(checked == 40, "Must check every visible control face in both active states");
        helper.succeed();
    }

    private static void assertSameShape(GameTestHelper helper, VoxelShape actual, VoxelShape expected, String message) {
        helper.assertTrue(!Shapes.joinIsNotEmpty(actual, expected, BooleanOp.NOT_SAME), message);
    }
}
