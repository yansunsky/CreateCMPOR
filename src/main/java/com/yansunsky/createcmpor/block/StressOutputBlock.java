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
 * 应力输出方块（放置于压缩空间内部）。
 *
 * <p>复用 Create 应力表（stressometer）的外观。继承 {@link DirectionalKineticBlock}，
 * 沿 {@code FACING} 方向接入 Create 应力网络。
 *
 * <p>与应力输入方块不同，本方块<b>不作为应力源</b>，而是被动观察者：
 * 它接入现有应力网络，但不提供任何虚拟容量或转速。
 * 评估期每秒采样网络的 capacity 和 stress（virtualCapacity=0），
 * 采样类型标记为 {@link com.yansunsky.createcmpor.stress.StressEvaluationRegistry.SampleType#OUTPUT}，
 * 评估结束后聚合计算工厂的 output 可提供应力。
 *
 * <p>含 {@code ACTIVE} 状态，默认 {@code false}（不激活），后续由本模组平行房间评估流程自动激活。
 */
public class StressOutputBlock extends DirectionalKineticBlock implements IBE<StressOutputBlockEntity> {

    public static final BooleanProperty ACTIVE = BlockStateProperties.POWERED;

    public StressOutputBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(ACTIVE, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(ACTIVE);
    }

    /** 双面接入：FACING 及其对称面都能接传动轴并传递应力（对穿轴，像应力表）。 */
    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        Direction facing = state.getValue(FACING);
        return face == facing || face == facing.getOpposite();
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(FACING).getAxis();
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (!level.isClientSide()) {
            if (!com.yansunsky.createcmpor.Config.DEV_MANUAL_ACTIVATION.get()) {
                player.displayClientMessage(
                        net.minecraft.network.chat.Component.translatable(
                                "message.createcmpor.stress_output.auto_only"),
                        true);
                return InteractionResult.sidedSuccess(level.isClientSide());
            }
            boolean newActive = !state.getValue(ACTIVE);
            level.setBlock(pos, state.setValue(ACTIVE, newActive), Block.UPDATE_ALL);
            if (level.getBlockEntity(pos) instanceof StressOutputBlockEntity be) {
                be.onActiveChanged();
            }
            com.yansunsky.createcmpor.CreateCMPOR.LOGGER.debug("[CreateCMPOR] stress_output @{} 手动切换 -> {}", pos, newActive);
            player.displayClientMessage(
                    net.minecraft.network.chat.Component.translatable(
                            newActive ? "message.createcmpor.stress_output.activated"
                                    : "message.createcmpor.stress_output.deactivated"),
                    true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    public Class<StressOutputBlockEntity> getBlockEntityClass() {
        return StressOutputBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends StressOutputBlockEntity> getBlockEntityType() {
        return ModBlockEntities.STRESS_OUTPUT.get();
    }
}
