package com.yansunsky.createcmpor.block;

import com.yansunsky.createcmpor.init.ModBlockEntities;
import com.simibubi.create.content.kinetics.base.RotatedPillarKineticBlock;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * IO 拓展方块（原应力拓展方块）。
 *
 * <p>继承 {@link RotatedPillarKineticBlock}，是 Create 应力网络的被动成员——像普通
 * 传动轴一样传递转速，不再承担应力源/负载职责（工厂方块本身已能直接传入传出应力）。
 *
 * <p><b>面功能分配</b>：
 * <ul>
 *     <li><b>轴向两端（应力接口面）</b>：唯一的传动/连接面——可贴工厂开口面、
 *         轴向相连其他 IO 拓展方块、或接外部传动轴。见 {@link #hasShaftTowards}。</li>
 *     <li><b>非轴向四面（IO 拓展面）</b>：把链上触达的工厂方块的物品/流体/能量缓存
 *         （输入+输出）代理转发到此处，见 {@link IOExtensionBlockEntity#isIoFace}。</li>
 * </ul>
 *
 * <p><b>自毁防呆</b>：放置时与持续 tick 检测——若本方块轴向两端都能（直接或间接）
 * 触达工厂缓存（即把两个工厂的缓存串在了一起），自动破坏自身，防止物品复制漏洞。
 * 这与 Create 中两个相反转向的应力源被传动轴连接时传动轴断裂的行为一致。
 */
public class IOExtensionBlock extends RotatedPillarKineticBlock implements IBE<IOExtensionBlockEntity> {

    public IOExtensionBlock(Properties properties) {
        super(properties);
    }

    /**
     * 只有轴向两端可接轴（应力接口面）。
     * <p>这与普通传动轴一致：轴向两端接传动杆/工厂开口面/相邻拓展方块，
     * 非轴向四面不接轴（仅作 IO 拓展面）。
     */
    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        return face.getAxis() == state.getValue(AXIS);
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(AXIS);
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof IOExtensionBlockEntity be) {
            be.checkSelfDestruct();
        }
    }

    @Override
    public Class<IOExtensionBlockEntity> getBlockEntityClass() {
        return IOExtensionBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends IOExtensionBlockEntity> getBlockEntityType() {
        return ModBlockEntities.IO_EXTENSION.get();
    }
}
