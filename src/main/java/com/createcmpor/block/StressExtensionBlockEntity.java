package com.createcmpor.block;

import com.createcmpor.CreateCMPOR;
import com.createcmpor.init.ModBlockEntities;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 应力拓展方块的方块实体。
 *
 * <p>继承 {@link GeneratingKineticBlockEntity}，因此既是 Create 应力网络的成员，
 * 也能作为应力源向网络供应力（{@link #getGeneratedSpeed()}）。
 *
 * <p>核心职责：
 * <ol>
 *     <li>接入应力网络（由父类完成），可读取所在网络的应力余量与转速。</li>
 *     <li>每若干 tick 扫描非轴向的四个侧面，记录相邻的 CMPOR 工厂方块坐标
 *         （{@link #boundFactoryPos}），供 capability 代理使用。</li>
 * </ol>
 */
public class StressExtensionBlockEntity extends GeneratingKineticBlockEntity {

    /** 相邻 CMPOR 工厂方块的坐标；null 表示当前没有贴靠工厂。 */
    @Nullable
    private BlockPos boundFactoryPos;

    /** 扫描相邻工厂的节流计数（避免每 tick 全方向查询）。 */
    private int scanCooldown = 0;

    public StressExtensionBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.STRESS_EXTENSION.get(), pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);
    }

    /**
     * 拓展方块本身不主动发电（应力源逻辑由应力 IO 方块主导），故生成速度恒为 0，
     * 仅作为网络成员（读取余量）与 IO 代理桥梁。
     */
    @Override
    public float getGeneratedSpeed() {
        return 0;
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide())
            return;
        if (--scanCooldown > 0)
            return;
        scanCooldown = 20; // 每秒扫描一次
        updateBoundFactory();
    }

    /** 扫描非轴向四个侧面，找到第一个相邻的 CMPOR 工厂方块。 */
    private void updateBoundFactory() {
        if (level == null)
            return;
        Direction.Axis axis = getBlockState().getValue(StressExtensionBlock.AXIS);
        BlockPos found = null;
        for (Direction dir : Direction.values()) {
            if (dir.getAxis() == axis)
                continue; // 跳过轴向两端（那是应力接口面）
            BlockPos neighbor = worldPosition.relative(dir);
            BlockState neighborState = level.getBlockState(neighbor);
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(neighborState.getBlock());
            if (id.toString().equals(CreateCMPOR.CMPOR_FACTORY_BLOCK_ID)) {
                found = neighbor;
                break;
            }
        }
        this.boundFactoryPos = found;
    }

    /** @return 当前绑定的 CMPOR 工厂方块坐标，可能为 null。 */
    @Nullable
    public BlockPos getBoundFactoryPos() {
        return boundFactoryPos;
    }

    /** @return 该侧面是否为非轴向的 IO 拓展面（轴向两端不拓展 IO，仅接应力）。 */
    public boolean isIoFace(@Nullable Direction side) {
        if (side == null)
            return true;
        return side.getAxis() != getBlockState().getValue(StressExtensionBlock.AXIS);
    }

    /** @return 网络总应力（来自父类同步字段），= 网络当前消耗。 */
    public float getNetworkStress() {
        return stress;
    }

    /** @return 网络总容量（来自父类同步字段），= 网络当前可提供上限。 */
    public float getNetworkCapacity() {
        return capacity;
    }

    /** @return 网络应力余量 = 容量 - 应力。 */
    public float getRemainingStress() {
        return capacity - stress;
    }
}
