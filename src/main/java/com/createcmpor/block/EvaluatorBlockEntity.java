package com.createcmpor.block;

import com.createcmpor.init.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** Stores the machine snapshot needed by the later evaluator implementation. */
public class EvaluatorBlockEntity extends RoomCodeBlockEntity {
    @Nullable
    private ResourceLocation originalMachineBlock;
    @Nullable
    private CompoundTag savedOriginalNbt;

    public EvaluatorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.EVALUATOR.get(), pos, state);
    }

    public void saveOriginalMachine(ResourceLocation blockId, CompoundTag beNbt) {
        originalMachineBlock = blockId;
        savedOriginalNbt = beNbt.copy();
        setChanged();
    }

    @Nullable
    public ResourceLocation getOriginalMachineBlock() {
        return originalMachineBlock;
    }

    @Nullable
    public CompoundTag getSavedOriginalNbt() {
        return savedOriginalNbt == null ? null : savedOriginalNbt.copy();
    }

    /** Evaluation trigger is intentionally empty until the v1 evaluator is implemented. */
    public void trigger() {
    }

    @Override
    protected void loadCommon(CompoundTag tag) {
        super.loadCommon(tag);
        originalMachineBlock = tag.contains("original_machine_block")
                ? ResourceLocation.tryParse(tag.getString("original_machine_block"))
                : null;
        savedOriginalNbt = tag.contains("original_machine_nbt")
                ? tag.getCompound("original_machine_nbt")
                : null;
    }

    @Override
    protected void saveCommon(CompoundTag tag) {
        super.saveCommon(tag);
        if (originalMachineBlock != null) {
            tag.putString("original_machine_block", originalMachineBlock.toString());
        }
        if (savedOriginalNbt != null) {
            tag.put("original_machine_nbt", savedOriginalNbt.copy());
        }
    }
}
