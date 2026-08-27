package com.yansunsky.createcmpor.block;

import com.yansunsky.createcmpor.init.ModBlockEntities;
import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 应力输入方块（放置于压缩空间内部）。
 *
 * <p>复用创造马达外观与发电逻辑：继承 {@link DirectionalKineticBlock}，
 * 沿 {@code FACING} 方向接入 Create 应力网络。
 *
 * <p>评估期激活后作为<b>应力源</b>（类创造马达）提供虚拟应力容量和转速，
 * 驱动内部机器运转以便测得真实应力消耗。采样上报网络的三维度数据
 * （capacity / stress / virtualCapacity / speed），评估结束后聚合计算
 * 工厂的 input 应力需求。
 *
 * <p>含 {@code ACTIVE} 状态，默认 {@code false}（不激活），后续由本模组平行房间评估流程自动激活。
 */
public class StressInputBlock extends DirectionalKineticBlock implements IBE<StressInputBlockEntity> {

    public static final BooleanProperty ACTIVE = BlockStateProperties.POWERED;

    public StressInputBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(ACTIVE, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(ACTIVE);
    }

    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        return face == state.getValue(FACING);
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(FACING).getAxis();
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (!level.isClientSide()) {
            // 仅前后两面（传动轴面 FACING 及其对称面）可右键激活；侧面被转速/方向注记框占用，忽略
            Direction facing = state.getValue(FACING);
            Direction clicked = hit.getDirection();
            if (clicked != facing && clicked != facing.getOpposite()) {
                return InteractionResult.PASS;
            }
            if (!com.yansunsky.createcmpor.Config.DEV_MANUAL_ACTIVATION.get()) {
                // 生产模式：右键激活为 debug 功能，静默不提示
                return InteractionResult.PASS;
            }
            boolean newActive = !state.getValue(ACTIVE);
            level.setBlock(pos, state.setValue(ACTIVE, newActive), Block.UPDATE_ALL);
            if (level.getBlockEntity(pos) instanceof StressInputBlockEntity be) {
                be.onActiveChanged();
            }
            com.yansunsky.createcmpor.CreateCMPOR.LOGGER.debug("[CreateCMPOR] stress_input @{} 手动切换 -> {}", pos, newActive);
            player.displayClientMessage(
                    net.minecraft.network.chat.Component.translatable(
                            newActive ? "message.createcmpor.stress_input.activated"
                                    : "message.createcmpor.stress_input.deactivated"),
                    true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    public Class<StressInputBlockEntity> getBlockEntityClass() {
        return StressInputBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends StressInputBlockEntity> getBlockEntityType() {
        return ModBlockEntities.STRESS_INPUT.get();
    }
}
