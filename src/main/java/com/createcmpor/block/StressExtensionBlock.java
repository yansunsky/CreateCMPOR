package com.createcmpor.block;

import com.createcmpor.init.ModBlockEntities;
import com.simibubi.create.content.kinetics.base.RotatedPillarKineticBlock;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 应力拓展方块。
 *
 * <p>继承 {@link RotatedPillarKineticBlock}，是 Create 应力网络的成员。
 *
 * <p>与安山齿轮箱类似，<b>所有六个面都可接轴</b>（{@link #hasShaftTowards} 返回 true），
 * 这样相邻的应力拓展方块自动属于同一 Create 应力网络，不论它们的轴向是否一致。
 * 这解决了多面贴合同一工厂方块时网络隔离的问题。
 *
 * <p>面功能分配：
 * <ul>
 *     <li><b>所有面</b>：均可接入 Create 应力网络（接传动杆或相邻拓展方块）。</li>
 *     <li><b>非轴向四面</b>：额外用于把相邻 CMPOR 工厂方块的物品/流体/能量 IO 拓展到此处。</li>
 * </ul>
 */
public class StressExtensionBlock extends RotatedPillarKineticBlock implements IBE<StressExtensionBlockEntity> {

    public StressExtensionBlock(Properties properties) {
        super(properties);
    }

    /**
     * 所有面都可接轴——相邻的应力拓展方块自动属于同一 Create 应力网络。
     * 这与安山齿轮箱（{@code create:andesite_encased_cogwheel}）的行为一致。
     */
    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        return true;
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(AXIS);
    }

    @Override
    public Class<StressExtensionBlockEntity> getBlockEntityClass() {
        return StressExtensionBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends StressExtensionBlockEntity> getBlockEntityType() {
        return ModBlockEntities.STRESS_EXTENSION.get();
    }
}
