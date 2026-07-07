package com.createcmpor.block;

import com.createcmpor.init.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * CreateCMPOR 自有工厂方块实体。
 *
 * <p>Phase 1 只保留最小持久化字段，证明本模组已经具备独立工厂落点。
 * 后续阶段会在这里加入评估结果、速率缓存、IO 配对和应力档案读写。
 */
public class FactoryBlockEntity extends BlockEntity {

    /** 原 CompactMachines 机器颜色。Phase 1 先只持久化，渲染和外观映射后续实现。 */
    private int machineColor = -1;

    public FactoryBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntities.FACTORY.get(), pos, blockState);
    }

    public int getMachineColor() {
        return machineColor;
    }

    public void setMachineColor(int machineColor) {
        this.machineColor = machineColor;
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("MachineColor", machineColor);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        machineColor = tag.contains("MachineColor") ? tag.getInt("MachineColor") : -1;
    }
}
