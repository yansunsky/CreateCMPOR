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

public class OutputBlockEntity extends BaseIOBlockEntity {
    private final IItemHandler itemHandler = new IItemHandler() {
        @Override
        public int getSlots() {
            return items.isEmpty() ? 1 : items.size();
        }

        @Override
        public @NotNull ItemStack getStackInSlot(int slot) {
            if (items.isEmpty() || slot < 0 || slot >= items.size()) return ItemStack.EMPTY;
            return new ItemStack(BuiltInRegistries.ITEM.get(items.get(slot)), 1);
        }

        @Override
        public @NotNull ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean simulate) {
            if (!isActive()) return stack;
            if (items.isEmpty() || items.contains(BuiltInRegistries.ITEM.getKey(stack.getItem()))) {
                if (!simulate) handle(stack);
                return ItemStack.EMPTY;
            }
            return stack;
        }

        @Override
        public @NotNull ItemStack extractItem(int slot, int amount, boolean simulate) {
            return ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
            return 64;
        }

        @Override
        public boolean isItemValid(int slot, @NotNull ItemStack stack) {
            return items.isEmpty() || items.contains(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        }
    };

    private final IFluidHandler fluidHandler = new IFluidHandler() {
        @Override
        public int getTanks() {
            return fluids.isEmpty() ? 1 : fluids.size();
        }

        @Override
        public @NotNull FluidStack getFluidInTank(int tank) {
            if (fluids.isEmpty() || tank < 0 || tank >= fluids.size()) return FluidStack.EMPTY;
            return new FluidStack(BuiltInRegistries.FLUID.get(fluids.get(tank)), 1);
        }

        @Override
        public int getTankCapacity(int tank) {
            return Integer.MAX_VALUE;
        }

        @Override
        public boolean isFluidValid(int tank, @NotNull FluidStack stack) {
            return fluids.isEmpty() || fluids.contains(BuiltInRegistries.FLUID.getKey(stack.getFluid()));
        }

        @Override
        public int fill(FluidStack resource, FluidAction action) {
            if (!isActive()) return 0;
            if (fluids.isEmpty() || fluids.contains(BuiltInRegistries.FLUID.getKey(resource.getFluid()))) {
                if (action == FluidAction.EXECUTE) handle(resource);
                return resource.getAmount();
            }
            return 0;
        }

        @Override
        public @NotNull FluidStack drain(FluidStack resource, FluidAction action) {
            return FluidStack.EMPTY;
        }

        @Override
        public @NotNull FluidStack drain(int maxDrain, FluidAction action) {
            return FluidStack.EMPTY;
        }
    };

    private final IEnergyStorage energyHandler = new IEnergyStorage() {
        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            if (!isActive()) return 0;
            if (!simulate) handle(maxReceive);
            return maxReceive;
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            return 0;
        }

        @Override
        public int getEnergyStored() {
            return 0;
        }

        @Override
        public int getMaxEnergyStored() {
            return Integer.MAX_VALUE;
        }

        @Override
        public boolean canExtract() {
            return false;
        }

        @Override
        public boolean canReceive() {
            return isActive();
        }
    };

    public OutputBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.OUTPUT.get(), pos, state);
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
