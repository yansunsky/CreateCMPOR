package com.yansunsky.createcmpor.legacy;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import org.jetbrains.annotations.Nullable;

/**
 * ⚠️ 临时兼容代码（TEMPORARY）
 * 旧版 CompactMachinesPOR 评估方块兼容注册（id: compactmachinespor:evaluator_block）。
 *
 * <p>旧存档中评估中断/失败残留的评估方块（id 未知会整体消失），
 * 由 {@link LegacyEvaluatorBlockEntity} 在首次 tick 时还原为原空间机器方块。
 * 必须复刻旧方块的状态属性（POWERED），否则旧存档 palette 状态无法解析。</p>
 */
public class LegacyEvaluatorBlock extends Block implements EntityBlock {

    /** 旧版评估方块的状态属性（BlockStateProperties.POWERED）。 */
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

    public LegacyEvaluatorBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(POWERED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(POWERED);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new LegacyEvaluatorBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return (tickerLevel, pos, tickerState, entity) -> {
            if (entity instanceof LegacyEvaluatorBlockEntity legacy) {
                legacy.tick();
            }
        };
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }
}
