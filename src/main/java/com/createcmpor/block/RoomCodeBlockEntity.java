package com.createcmpor.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Shared persistence for blocks associated with a CompactMachines room.
 */
public abstract class RoomCodeBlockEntity extends BlockEntity {
    protected String roomCode;

    protected RoomCodeBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public String getRoomCode() {
        return roomCode;
    }

    public void setRoomCode(String roomCode) {
        this.roomCode = roomCode;
        setChanged();
    }

    protected void loadCommon(CompoundTag tag) {
        roomCode = tag.contains("room_code") ? tag.getString("room_code") : null;
    }

    protected void saveCommon(CompoundTag tag) {
        if (roomCode != null && !roomCode.isBlank()) {
            tag.putString("room_code", roomCode);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        loadCommon(tag);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        saveCommon(tag);
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
        if (roomCode != null && !roomCode.isBlank()) {
            CompoundTag tag = new CompoundTag();
            saveCommon(tag);
            builder.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        }
    }
}
