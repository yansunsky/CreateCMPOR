package com.createcmpor.block;

import com.createcmpor.init.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** 保存评估会话标识和原机器镜像，权威事务记录位于 EvaluationSavedData。 */
public class EvaluatorBlockEntity extends RoomCodeBlockEntity {
    @Nullable
    private UUID sessionId;
    @Nullable
    private UUID ownerId;
    private boolean launcherReturnEligible;
    @Nullable
    private CompoundTag originalMachineState;
    @Nullable
    private CompoundTag savedOriginalNbt;

    public EvaluatorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.EVALUATOR.get(), pos, state);
    }

    public void initialize(UUID sessionId, UUID ownerId, boolean launcherReturnEligible,
                           String roomCode, BlockState originalState, CompoundTag beNbt) {
        this.sessionId = sessionId;
        this.ownerId = ownerId;
        this.launcherReturnEligible = launcherReturnEligible;
        this.roomCode = roomCode;
        originalMachineState = NbtUtils.writeBlockState(originalState);
        savedOriginalNbt = beNbt.copy();
        setChanged();
    }

    @Nullable
    public UUID getSessionId() {
        return sessionId;
    }

    @Nullable
    public UUID getOwnerId() {
        return ownerId;
    }

    public boolean isLauncherReturnEligible() {
        return launcherReturnEligible;
    }

    @Nullable
    public CompoundTag getOriginalMachineState() {
        return originalMachineState == null ? null : originalMachineState.copy();
    }

    @Nullable
    public CompoundTag getSavedOriginalNbt() {
        return savedOriginalNbt == null ? null : savedOriginalNbt.copy();
    }

    @Override
    protected void loadCommon(CompoundTag tag) {
        super.loadCommon(tag);
        sessionId = tag.hasUUID("session_id") ? tag.getUUID("session_id") : null;
        ownerId = tag.hasUUID("owner_id") ? tag.getUUID("owner_id") : null;
        launcherReturnEligible = tag.getBoolean("launcher_return_eligible");
        originalMachineState = tag.contains("original_machine_state")
                ? tag.getCompound("original_machine_state").copy()
                : null;
        savedOriginalNbt = tag.contains("original_machine_nbt")
                ? tag.getCompound("original_machine_nbt")
                : null;
    }

    @Override
    protected void saveCommon(CompoundTag tag) {
        super.saveCommon(tag);
        if (sessionId != null) {
            tag.putUUID("session_id", sessionId);
        }
        if (ownerId != null) {
            tag.putUUID("owner_id", ownerId);
        }
        tag.putBoolean("launcher_return_eligible", launcherReturnEligible);
        if (originalMachineState != null) {
            tag.put("original_machine_state", originalMachineState.copy());
        }
        if (savedOriginalNbt != null) {
            tag.put("original_machine_nbt", savedOriginalNbt.copy());
        }
    }
}
