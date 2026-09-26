package com.yansunsky.createcmpor.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import org.jetbrains.annotations.Nullable;

/** 评估期间替代原机器的标记方块；可被破坏，破坏事件由 EvaluationManager 接管并恢复原机器。 */
public class EvaluatorBlock extends BaseEntityBlock {
    public static final MapCodec<EvaluatorBlock> CODEC = simpleCodec(EvaluatorBlock::new);
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

    public EvaluatorBlock() {
        // 普通硬度 = 可被生存模式破坏（破坏走还原流程）；抗爆保留极高值防止意外破坏
        this(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).strength(2.0f, 3600000.0f)
                .pushReaction(PushReaction.BLOCK).noLootTable());
    }

    public EvaluatorBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(POWERED, false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(POWERED);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
                                   BlockPos neighborPos, boolean movedByPiston) {
        if (!level.isClientSide) {
            boolean powered = level.hasNeighborSignal(pos);
            if (powered != state.getValue(POWERED)) {
                level.setBlockAndUpdate(pos, state.setValue(POWERED, powered));
            }
        }
    }

    /**
     * 右键评估方块 = 观察评估副本（黑盒观察）。
     *
     * <ul>
     * <li><b>手持 {@code compactmachines:personal_shrinking_device} + 右键</b>：
     * 进入评估副本；已在观察中则切换到下一个并行分支（串行时相当于刷新位置）。</li>
     * <li><b>潜行 + 右键</b>：退出观察，回到进入前的位置与游戏模式。</li>
     * <li>其它情况不拦截（返回 PASS，交给原版/其它模组处理）。</li>
     * </ul>
     *
     * <p>观察者不产生区块票据、不参与刷怪，因此观看副本不影响评估结果。
     */
    @Override
    protected net.minecraft.world.ItemInteractionResult useItemOn(
            net.minecraft.world.item.ItemStack stack, BlockState state, Level level, BlockPos pos,
            net.minecraft.world.entity.player.Player player, net.minecraft.world.InteractionHand hand,
            net.minecraft.world.phys.BlockHitResult hitResult) {
        if (level.isClientSide) {
            // 客户端：手持缩小设备时给出成功反馈，避免手臂摆动/重复触发
            return com.yansunsky.createcmpor.evaluation.EvaluationObservationManager.isShrinkingDevice(stack)
                    ? net.minecraft.world.ItemInteractionResult.SUCCESS
                    : net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)) {
            return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        var observation = com.yansunsky.createcmpor.evaluation.EvaluationObservationManager.INSTANCE;
        if (serverPlayer.isShiftKeyDown()
                && observation.isObserving(serverPlayer)
                && com.yansunsky.createcmpor.evaluation.EvaluationObservationManager.isShrinkingDevice(stack)) {
            observation.exit(serverPlayer, "message.createcmpor.observation.exited");
            return net.minecraft.world.ItemInteractionResult.SUCCESS;
        }
        if (!com.yansunsky.createcmpor.evaluation.EvaluationObservationManager.isShrinkingDevice(stack)) {
            return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!(level.getBlockEntity(pos) instanceof EvaluatorBlockEntity evaluator)) {
            return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        observation.enterOrSwitch(serverPlayer, evaluator.getRoomCode());
        return net.minecraft.world.ItemInteractionResult.SUCCESS;
    }

    @Override
    protected net.minecraft.world.InteractionResult useWithoutItem(
            BlockState state, Level level, BlockPos pos,
            net.minecraft.world.entity.player.Player player, net.minecraft.world.phys.BlockHitResult hitResult) {
        // 潜行空手右键 = 退出观察（便捷出口，避免玩家必须手持设备才能出来）
        if (!level.isClientSide && player.isShiftKeyDown()
                && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            var observation = com.yansunsky.createcmpor.evaluation.EvaluationObservationManager.INSTANCE;
            if (observation.isObserving(serverPlayer)) {
                observation.exit(serverPlayer, "message.createcmpor.observation.exited");
                return net.minecraft.world.InteractionResult.SUCCESS;
            }
        }
        return net.minecraft.world.InteractionResult.PASS;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new EvaluatorBlockEntity(pos, state);
    }

    /** 服务端 tick：驱动评估方块实体做倒计时递减（进度同步）。 */
    @Override
    @Nullable
    public <T extends BlockEntity> net.minecraft.world.level.block.entity.BlockEntityTicker<T> getTicker(
            Level level, BlockState state, net.minecraft.world.level.block.entity.BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return (tickerLevel, pos, tickerState, entity) -> {
            if (entity instanceof EvaluatorBlockEntity evaluator) {
                evaluator.tick();
            }
        };
    }
}