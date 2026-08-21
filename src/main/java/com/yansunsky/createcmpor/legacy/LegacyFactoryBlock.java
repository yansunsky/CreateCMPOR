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
import org.jetbrains.annotations.Nullable;

/**
 * ⚠️ 临时兼容代码（TEMPORARY）
 * 仅用于旧版 CompactMachinesPOR 存档过渡：检测到旧工厂方块后自动还原为空间机器。
 * 待旧存档迁移完成（或确认无需兼容）后整个 legacy 包应被移除，勿在此基础上扩展业务逻辑。
 *
 * <p>旧版 CompactMachinesPOR 工厂方块兼容注册（id: compactmachinespor:factory_block）。
 *
 * <p>仅用于旧存档兼容：方块显示为闭合机壳外观，服务端 tick 由
 * {@link LegacyFactoryBlockEntity} 自动还原为 Compact Machines 空间机器。
 */
public class LegacyFactoryBlock extends Block implements EntityBlock {

    public LegacyFactoryBlock(Properties properties) {
        super(properties);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new LegacyFactoryBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return (tickerLevel, pos, tickerState, entity) -> {
            if (entity instanceof LegacyFactoryBlockEntity legacy) {
                legacy.tick();
            }
        };
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }
}
