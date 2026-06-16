package com.createcmpor.block;

import com.createcmpor.init.ModBlockEntities;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.RotatedPillarKineticBlock;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 应力拓展方块。
 *
 * <p>完全复用安山传动箱（{@code create:andesite_encased_shaft}）的逻辑与渲染：
 * 继承 {@link RotatedPillarKineticBlock}，按 {@code AXIS} 轴向旋转，沿轴向的两端（如 AXIS=Y 时的上下面）
 * 可接入 Create 应力网络的传动杆。
 *
 * <p>面功能分配（见计划书）：
 * <ul>
 *     <li><b>轴向两端（上下面）</b>：应力接口面，{@link #hasShaftTowards} 返回 true，可连传动杆。</li>
 *     <li><b>其余四面</b>：用于把相邻 CMPOR 工厂方块的物品/流体/能量 IO 拓展到此处
 *         （由 {@link StressExtensionBlockEntity} 与 capability 代理实现）。</li>
 * </ul>
 */
public class StressExtensionBlock extends RotatedPillarKineticBlock implements IBE<StressExtensionBlockEntity> {

    public StressExtensionBlock(Properties properties) {
        super(properties);
    }

    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        // 仅沿旋转轴的两端可接轴（与安山传动箱一致）
        return face.getAxis() == state.getValue(AXIS);
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
