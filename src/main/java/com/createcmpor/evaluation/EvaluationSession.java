package com.createcmpor.evaluation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

/** 单次冻结事务的持久化状态。 */
public final class EvaluationSession {
    public enum State {
        PREPARED,
        EVALUATOR_INSTALLED,
        EVICTING_PLAYERS,
        SAVING_SOURCE,
        WAITING_UNLOAD,
        FROZEN,
        QUEUED,
        STAGING_SOURCE,
        STAGING_WRITTEN,
        STAGING_VERIFIED,
        PUBLISHING,
        PUBLISHED,
        CLEANING,
        ROLLING_BACK
    }

    private final UUID id;
    private final UUID owner;
    private final GlobalPos machinePos;
    private final String roomCode;
    private final BlockState originalState;
    private final CompoundTag originalBlockEntityNbt;
    private State state;
    private int stateTicks;
    private final boolean launcherReturnEligible;
    private EvaluationManifest manifest;
    private String rollbackMessageKey;

    public EvaluationSession(UUID id, UUID owner, GlobalPos machinePos, String roomCode,
                             BlockState originalState, CompoundTag originalBlockEntityNbt) {
        this(id, owner, machinePos, roomCode, originalState, originalBlockEntityNbt,
                State.PREPARED, 0, true, null, "message.createcmpor.evaluation.runtime_failed");
    }

    public EvaluationSession(UUID id, UUID owner, GlobalPos machinePos, String roomCode,
                             BlockState originalState, CompoundTag originalBlockEntityNbt,
                             boolean launcherReturnEligible) {
        this(id, owner, machinePos, roomCode, originalState, originalBlockEntityNbt,
                State.PREPARED, 0, launcherReturnEligible, null,
                "message.createcmpor.evaluation.runtime_failed");
    }

    private EvaluationSession(UUID id, UUID owner, GlobalPos machinePos, String roomCode,
                               BlockState originalState, CompoundTag originalBlockEntityNbt,
                               State state, int stateTicks, boolean launcherReturnEligible,
                               EvaluationManifest manifest, String rollbackMessageKey) {
        this.id = id;
        this.owner = owner;
        this.machinePos = machinePos;
        this.roomCode = roomCode;
        this.originalState = originalState;
        this.originalBlockEntityNbt = originalBlockEntityNbt.copy();
        this.state = state;
        this.stateTicks = stateTicks;
        this.launcherReturnEligible = launcherReturnEligible;
        this.manifest = manifest;
        this.rollbackMessageKey = rollbackMessageKey;
    }

    public UUID id() {
        return id;
    }

    public UUID owner() {
        return owner;
    }

    public GlobalPos machinePos() {
        return machinePos;
    }

    public String roomCode() {
        return roomCode;
    }

    public BlockState originalState() {
        return originalState;
    }

    public CompoundTag originalBlockEntityNbt() {
        return originalBlockEntityNbt.copy();
    }

    public State state() {
        return state;
    }

    public int stateTicks() {
        return stateTicks;
    }

    public boolean launcherReturnEligible() {
        return launcherReturnEligible;
    }

    public EvaluationManifest manifest() {
        return manifest;
    }

    public void setManifest(EvaluationManifest manifest) {
        this.manifest = manifest;
    }

    public boolean hasPhase4Manifest() {
        return manifest != null;
    }

    public String rollbackMessageKey() {
        return rollbackMessageKey;
    }

    public void setRollbackMessageKey(String rollbackMessageKey) {
        this.rollbackMessageKey = rollbackMessageKey;
    }

    public void setState(State state) {
        this.state = state;
        this.stateTicks = 0;
    }

    public void tickState() {
        stateTicks++;
    }

    public void resetStateTicks() {
        stateTicks = 0;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putUUID("owner", owner);
        tag.putString("machine_dimension", machinePos.dimension().location().toString());
        tag.put("machine_pos", NbtUtils.writeBlockPos(machinePos.pos()));
        tag.putString("room_code", roomCode);
        tag.put("original_state", NbtUtils.writeBlockState(originalState));
        tag.put("original_block_entity", originalBlockEntityNbt.copy());
        tag.putString("state", state.name());
        tag.putInt("state_ticks", stateTicks);
        tag.putBoolean("launcher_return_eligible", launcherReturnEligible);
        if (manifest != null) {
            tag.put("manifest", manifest.save());
        }
        tag.putString("rollback_message_key", rollbackMessageKey);
        return tag;
    }

    public static EvaluationSession load(CompoundTag tag, HolderLookup.Provider registries) {
        ResourceLocation dimensionId = ResourceLocation.parse(tag.getString("machine_dimension"));
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
        BlockPos machinePos = NbtUtils.readBlockPos(tag, "machine_pos")
                .orElseThrow(() -> new IllegalArgumentException("缺少评估机器坐标"));
        BlockState originalState = NbtUtils.readBlockState(
                registries.lookupOrThrow(Registries.BLOCK), tag.getCompound("original_state"));
        State state;
        try {
            state = State.valueOf(tag.getString("state"));
        } catch (IllegalArgumentException exception) {
            state = State.ROLLING_BACK;
        }
        EvaluationManifest manifest = tag.contains("manifest", net.minecraft.nbt.Tag.TAG_COMPOUND)
                ? EvaluationManifest.load(tag.getCompound("manifest"), registries)
                : null;
        if (manifest != null && !manifest.sessionId().equals(tag.getUUID("id"))) {
            throw new IllegalArgumentException("复制清单与评估会话不匹配");
        }
        String rollbackMessageKey = tag.getString("rollback_message_key");
        if (rollbackMessageKey.isBlank()) {
            rollbackMessageKey = "message.createcmpor.evaluation.runtime_failed";
        }
        return new EvaluationSession(
                tag.getUUID("id"),
                tag.getUUID("owner"),
                GlobalPos.of(dimension, machinePos),
                tag.getString("room_code"),
                originalState,
                tag.getCompound("original_block_entity"),
                state,
                tag.getInt("state_ticks"),
                tag.contains("launcher_return_eligible")
                        ? tag.getBoolean("launcher_return_eligible")
                        : tag.getBoolean("launcher_consumed"),
                manifest,
                rollbackMessageKey);
    }
}
