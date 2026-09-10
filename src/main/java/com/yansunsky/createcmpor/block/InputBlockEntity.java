package com.yansunsky.createcmpor.block;

import com.yansunsky.createcmpor.init.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.NotNull;

public class InputBlockEntity extends BaseIOBlockEntity {
    private final IItemHandler itemHandler = new IItemHandler() {
        @Override
        public int getSlots() {
            return items.size();
        }

        @Override
        public @NotNull ItemStack getStackInSlot(int slot) {
            if (slot < 0 || slot >= items.size()) return ItemStack.EMPTY;
            // 按白名单登记形态提供（含组件），避免带组件物品退化为无组件原型
            return filterStack(items.get(slot), Integer.MAX_VALUE);
        }

        @Override
        public @NotNull ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean simulate) {
            return stack;
        }

        @Override
        public @NotNull ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (!isActive() || slot < 0 || slot >= items.size()) return ItemStack.EMPTY;
            ItemStack result = filterStack(items.get(slot), amount);
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

    public InputBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.INPUT.get(), pos, state);
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
}
