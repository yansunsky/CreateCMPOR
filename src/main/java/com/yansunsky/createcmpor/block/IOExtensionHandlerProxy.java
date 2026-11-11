package com.yansunsky.createcmpor.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

/** Lifecycle guards for cached IOExtension capabilities. */
public final class IOExtensionHandlerProxy {
    private IOExtensionHandlerProxy() {
    }

    public static IItemHandler item(IOExtensionBlockEntity extension, Direction side) {
        return new ItemProxy(extension, side);
    }

    public static IFluidHandler fluid(IOExtensionBlockEntity extension, Direction side) {
        return new FluidProxy(extension, side);
    }

    public static IEnergyStorage energy(IOExtensionBlockEntity extension, Direction side) {
        return new EnergyProxy(extension, side);
    }

    private static FactoryBlockEntity resolve(IOExtensionBlockEntity extension, Direction side,
                                              boolean energyOnly) {
        Level level = extension.getLevel();
        if (level == null || extension.isRemoved() || !extension.isIoFace(side)) {
            return null;
        }
        BlockPos extensionPos = extension.getBlockPos();
        if (!level.isLoaded(extensionPos) || level.getBlockEntity(extensionPos) != extension) {
            return null;
        }
        for (BlockPos targetPos : extension.getReachableFactories()) {
            if (!level.isLoaded(targetPos)) {
                continue;
            }
            if (level.getBlockEntity(targetPos) instanceof FactoryBlockEntity factory
                    && !factory.isRemoved() && (!energyOnly || factory.hasEnergyIo())) {
                return factory;
            }
        }
        return null;
    }

    private static final class ItemProxy implements IItemHandler {
        private final IOExtensionBlockEntity extension;
        private final Direction side;

        private ItemProxy(IOExtensionBlockEntity extension, Direction side) {
            this.extension = extension;
            this.side = side;
        }

        private IItemHandler current() {
            FactoryBlockEntity factory = resolve(extension, side, false);
            return factory == null ? null : factory.getItemHandler();
        }

        @Override
        public int getSlots() {
            IItemHandler handler = current();
            return handler == null ? 0 : handler.getSlots();
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            IItemHandler handler = current();
            return handler == null ? ItemStack.EMPTY : handler.getStackInSlot(slot);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            IItemHandler handler = current();
            return handler == null ? stack : handler.insertItem(slot, stack, simulate);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            IItemHandler handler = current();
            return handler == null ? ItemStack.EMPTY : handler.extractItem(slot, amount, simulate);
        }

        @Override
        public int getSlotLimit(int slot) {
            IItemHandler handler = current();
            return handler == null ? 0 : handler.getSlotLimit(slot);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            IItemHandler handler = current();
            return handler != null && handler.isItemValid(slot, stack);
        }
    }

    private static final class FluidProxy implements IFluidHandler {
        private final IOExtensionBlockEntity extension;
        private final Direction side;

        private FluidProxy(IOExtensionBlockEntity extension, Direction side) {
            this.extension = extension;
            this.side = side;
        }

        private IFluidHandler current() {
            FactoryBlockEntity factory = resolve(extension, side, false);
            return factory == null ? null : factory.getFluidHandler();
        }

        @Override
        public int getTanks() {
            IFluidHandler handler = current();
            return handler == null ? 0 : handler.getTanks();
        }

        @Override
        public FluidStack getFluidInTank(int tank) {
            IFluidHandler handler = current();
            return handler == null ? FluidStack.EMPTY : handler.getFluidInTank(tank);
        }

        @Override
        public int getTankCapacity(int tank) {
            IFluidHandler handler = current();
            return handler == null ? 0 : handler.getTankCapacity(tank);
        }

        @Override
        public boolean isFluidValid(int tank, FluidStack stack) {
            IFluidHandler handler = current();
            return handler != null && handler.isFluidValid(tank, stack);
        }

        @Override
        public int fill(FluidStack resource, FluidAction action) {
            IFluidHandler handler = current();
            return handler == null ? 0 : handler.fill(resource, action);
        }

        @Override
        public FluidStack drain(FluidStack resource, FluidAction action) {
            IFluidHandler handler = current();
            return handler == null ? FluidStack.EMPTY : handler.drain(resource, action);
        }

        @Override
        public FluidStack drain(int maxDrain, FluidAction action) {
            IFluidHandler handler = current();
            return handler == null ? FluidStack.EMPTY : handler.drain(maxDrain, action);
        }
    }

    private static final class EnergyProxy implements IEnergyStorage {
        private final IOExtensionBlockEntity extension;
        private final Direction side;

        private EnergyProxy(IOExtensionBlockEntity extension, Direction side) {
            this.extension = extension;
            this.side = side;
        }

        private IEnergyStorage current() {
            FactoryBlockEntity factory = resolve(extension, side, true);
            return factory == null ? null : factory.getEnergyHandler();
        }

        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            IEnergyStorage storage = current();
            return storage == null ? 0 : storage.receiveEnergy(maxReceive, simulate);
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            IEnergyStorage storage = current();
            return storage == null ? 0 : storage.extractEnergy(maxExtract, simulate);
        }

        @Override
        public int getEnergyStored() {
            IEnergyStorage storage = current();
            return storage == null ? 0 : storage.getEnergyStored();
        }

        @Override
        public int getMaxEnergyStored() {
            IEnergyStorage storage = current();
            return storage == null ? 0 : storage.getMaxEnergyStored();
        }

        @Override
        public boolean canExtract() {
            IEnergyStorage storage = current();
            return storage != null && storage.canExtract();
        }

        @Override
        public boolean canReceive() {
            IEnergyStorage storage = current();
            return storage != null && storage.canReceive();
        }
    }
}
