package com.createcmpor.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.List;

/**
 * Persisted IO filters and capability shells used by the later evaluator.
 * Phase 1 intentionally does not report data to the removed legacy evaluator.
 */
public abstract class BaseIOBlockEntity extends RoomCodeBlockEntity {
    protected final List<ResourceLocation> items = new ArrayList<>();
    protected final List<ResourceLocation> fluids = new ArrayList<>();

    protected BaseIOBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    protected void loadCommon(CompoundTag tag) {
        super.loadCommon(tag);
        items.clear();
        if (tag.contains("items", Tag.TAG_LIST)) {
            ListTag list = tag.getList("items", Tag.TAG_STRING);
            for (int i = 0; i < list.size(); i++) {
                ResourceLocation id = ResourceLocation.tryParse(list.getString(i));
                if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
                    items.add(id);
                }
            }
        }

        fluids.clear();
        if (tag.contains("fluids", Tag.TAG_LIST)) {
            ListTag list = tag.getList("fluids", Tag.TAG_STRING);
            for (int i = 0; i < list.size(); i++) {
                ResourceLocation id = ResourceLocation.tryParse(list.getString(i));
                if (id != null && BuiltInRegistries.FLUID.containsKey(id)) {
                    fluids.add(id);
                }
            }
        }
    }

    @Override
    protected void saveCommon(CompoundTag tag) {
        super.saveCommon(tag);
        ListTag itemTag = new ListTag();
        items.forEach(id -> itemTag.add(StringTag.valueOf(id.toString())));
        tag.put("items", itemTag);

        ListTag fluidTag = new ListTag();
        fluids.forEach(id -> fluidTag.add(StringTag.valueOf(id.toString())));
        tag.put("fluids", fluidTag);
    }

    public abstract IItemHandler getItemHandler();

    public abstract IFluidHandler getFluidHandler();

    public abstract IEnergyStorage getEnergyHandler();

    protected boolean isActive() {
        return getBlockState().getValue(BaseIOBlock.ACTIVE) && roomCode != null && !roomCode.isBlank();
    }

    protected boolean checkAndDeactivate() {
        if (isActive()) {
            return true;
        }
        if (getBlockState().getValue(BaseIOBlock.ACTIVE) && getLevel() != null) {
            getLevel().setBlock(getBlockPos(), getBlockState().setValue(BaseIOBlock.ACTIVE, false), Block.UPDATE_CLIENTS);
        }
        return false;
    }

    /** Legacy accounting was deliberately removed; Phase 6 will provide the new sampler. */
    protected void handle(ItemStack stack) {
    }

    protected void handle(FluidStack stack) {
    }

    protected void handle(Holder<?> holder, int count) {
    }

    protected void handle(int energy) {
    }

    @Override
    protected void applyImplicitComponents(BlockEntity.DataComponentInput componentInput) {
        super.applyImplicitComponents(componentInput);
        CustomData customData = componentInput.get(DataComponents.CUSTOM_DATA);
        if (customData != null) {
            loadCommon(customData.copyTag());
        }
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder builder) {
        super.collectImplicitComponents(builder);
        CompoundTag tag = new CompoundTag();
        saveCommon(tag);
        builder.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
