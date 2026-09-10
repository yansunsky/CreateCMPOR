package com.yansunsky.createcmpor.block;

import com.yansunsky.createcmpor.init.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.NotNull;

/**
 * 并行空间输入方块的方块实体。
 *
 * <p><b>分支评估模式</b>：{@code branchIndex >= 0} 时只暴露配置物品列表中的第
 * {@code branchIndex} 个物品（其余物品不可抽取），流体与能量保持全暴露。评估调度器在
 * 每个分支开始时调用 {@link #setBranchIndex(int)} 切换，从而实现"每次评估只记录一条
 * 输入线的流量"。</p>
 *
 * <p>正常模式（{@code branchIndex = -1}，默认）行为与 {@link InputBlockEntity} 一致：
 * 全部白名单物品可抽取。</p>
 */
public class ParallelInputBlockEntity extends BaseIOBlockEntity {

    /** 当前分支索引；-1 表示正常模式（全部白名单物品暴露）。 */
    private int branchIndex = -1;

    private final IItemHandler itemHandler = new IItemHandler() {
        @Override
        public int getSlots() {
            return branchItems().size();
        }

        @Override
        public @NotNull ItemStack getStackInSlot(int slot) {
            java.util.List<ResourceLocation> branch = branchItems();
            if (slot < 0 || slot >= branch.size()) return ItemStack.EMPTY;
            // 按白名单登记形态提供（含组件）
            return filterStack(branch.get(slot), Integer.MAX_VALUE);
        }

        @Override
        public @NotNull ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean simulate) {
            return stack;
        }

        @Override
        public @NotNull ItemStack extractItem(int slot, int amount, boolean simulate) {
            java.util.List<ResourceLocation> branch = branchItems();
            if (!isActive() || slot < 0 || slot >= branch.size()) return ItemStack.EMPTY;
            ItemStack result = filterStack(branch.get(slot), amount);
            if (!simulate) handle(result);
            return result;
        }

        @Override
        public int getSlotLimit(int slot) {
            return Integer.MAX_VALUE;
        }

        @Override
        public boolean isItemValid(int slot, @NotNull ItemStack stack) {
            return false;
        }
    };

    private final IFluidHandler fluidHandler = new IFluidHandler() {
        @Override
        public int getTanks() {
            return fluids.size();
        }

        @Override
        public @NotNull FluidStack getFluidInTank(int tank) {
            if (tank < 0 || tank >= fluids.size()) return FluidStack.EMPTY;
            return new FluidStack(BuiltInRegistries.FLUID.get(fluids.get(tank)), Integer.MAX_VALUE);
        }

        @Override
        public int getTankCapacity(int tank) {
            return Integer.MAX_VALUE;
        }

        @Override
        public boolean isFluidValid(int tank, @NotNull FluidStack stack) {
            return false;
        }

        @Override
        public int fill(FluidStack resource, FluidAction action) {
            return 0;
        }

        @Override
        public @NotNull FluidStack drain(FluidStack resource, FluidAction action) {
            if (!isActive() || !fluids.contains(BuiltInRegistries.FLUID.getKey(resource.getFluid()))) {
                return FluidStack.EMPTY;
            }
            if (action == FluidAction.EXECUTE) handle(resource);
            return resource.copy();
        }

        @Override
        public @NotNull FluidStack drain(int maxDrain, FluidAction action) {
            if (!isActive() || maxDrain < 0 || fluids.isEmpty()) return FluidStack.EMPTY;
            FluidStack result = new FluidStack(BuiltInRegistries.FLUID.get(fluids.getFirst()), maxDrain);
            if (action == FluidAction.EXECUTE) handle(result);
            return result;
        }
    };

    private final IEnergyStorage energyHandler = new IEnergyStorage() {
        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            return 0;
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            if (!isActive()) return 0;
            if (!simulate) handle(maxExtract);
            return maxExtract;
        }

        @Override
        public int getEnergyStored() {
            return Integer.MAX_VALUE;
        }

        @Override
        public int getMaxEnergyStored() {
            return Integer.MAX_VALUE;
        }

        @Override
        public boolean canExtract() {
            return isActive();
        }

        @Override
        public boolean canReceive() {
            return false;
        }
    };

    public ParallelInputBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.PARALLEL_INPUT.get(), pos, state);
    }

    /** 评估调度器调用：切换分支模式。{@code index >= 0} 只暴露第 index 个物品；-1 恢复全部。 */
    public void setBranchIndex(int index) {
        this.branchIndex = index;
        setChanged();
    }

    public int getBranchIndex() {
        return branchIndex;
    }

    /** 配置的物品数量（评估调度器用于确定总分支数）。 */
    public int configuredItemCount() {
        return items.size();
    }

    /** 输入角色：流量必须记为输入方向（否则原料会被误记为输出）。 */
    @Override
    protected boolean isInputSide() {
        return true;
    }

    /** 分支模式下只返回对应物品（越界返回空列表）；正常模式返回全部白名单物品。 */
    private java.util.List<ResourceLocation> branchItems() {
        if (branchIndex >= 0) {
            if (branchIndex < items.size()) {
                return java.util.List.of(items.get(branchIndex));
            }
            return java.util.List.of();
        }
        return items;
    }

    @Override
    public IItemHandler getItemHandler() {
        return itemHandler;
    }

    @Override
    public IFluidHandler getFluidHandler() {
        return fluidHandler;
    }

    @Override
    public IEnergyStorage getEnergyHandler() {
        return energyHandler;
    }

    @Override
    protected void loadCommon(net.minecraft.nbt.CompoundTag tag) {
        super.loadCommon(tag);
        this.branchIndex = tag.contains("branch_index") ? tag.getInt("branch_index") : -1;
    }

    @Override
    protected void saveCommon(net.minecraft.nbt.CompoundTag tag) {
        super.saveCommon(tag);
        tag.putInt("branch_index", branchIndex);
    }
}
