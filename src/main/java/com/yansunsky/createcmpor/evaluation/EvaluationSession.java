package com.yansunsky.createcmpor.evaluation;

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
        RAILWAY_TRANSFER,
        PUBLISHING,
        PUBLISHED,
        EVALUATING,
        EVALUATED,
        SOLIDIFYING,
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
    private EvaluationVerdict.Result evaluationResult;
    private boolean factoryInstalled;
    /** 目标冲突取消标记：为 true 时 CLEANING 阶段会强制清理目标区域（即使未写入）。 */
    private boolean cleanTargetOnCancel;

    public EvaluationSession(UUID id, UUID owner, GlobalPos machinePos, String roomCode,
                             BlockState originalState, CompoundTag originalBlockEntityNbt) {
        this(id, owner, machinePos, roomCode, originalState, originalBlockEntityNbt,
                State.PREPARED, 0, true, null, "message.createcmpor.evaluation.runtime_failed", false);
    }

    public EvaluationSession(UUID id, UUID owner, GlobalPos machinePos, String roomCode,
                             BlockState originalState, CompoundTag originalBlockEntityNbt,
                             boolean launcherReturnEligible) {
        this(id, owner, machinePos, roomCode, originalState, originalBlockEntityNbt,
                State.PREPARED, 0, launcherReturnEligible, null,
                "message.createcmpor.evaluation.runtime_failed", false);
    }

    private EvaluationSession(UUID id, UUID owner, GlobalPos machinePos, String roomCode,
                               BlockState originalState, CompoundTag originalBlockEntityNbt,
                               State state, int stateTicks, boolean launcherReturnEligible,
                               EvaluationManifest manifest, String rollbackMessageKey,
                               boolean factoryInstalled) {
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
        this.factoryInstalled = factoryInstalled;
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

    /** Phase 6 评估结果（运行时内存，不持久化；重启后回滚）。 */
    public EvaluationVerdict.Result evaluationResult() {
        return evaluationResult;
    }

    public void setEvaluationResult(EvaluationVerdict.Result evaluationResult) {
        this.evaluationResult = evaluationResult;
    }

    /** 工厂已固化到主世界（固化后清理副本但保留工厂，不再回滚机器）。 */
    public boolean factoryInstalled() {
        return factoryInstalled;
    }

    public void setFactoryInstalled(boolean factoryInstalled) {
        this.factoryInstalled = factoryInstalled;
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
        tag.putBoolean("factory_installed", factoryInstalled);
        tag.putBoolean("clean_target_on_cancel", cleanTargetOnCancel);
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
        EvaluationSession session = new EvaluationSession(
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
                rollbackMessageKey,
                tag.getBoolean("factory_installed"));
        session.cleanTargetOnCancel = tag.getBoolean("clean_target_on_cancel");
        return session;
    }

    /** 目标冲突取消后标记：CLEANING 阶段将强制清理目标区域（eval_world 无法进入，残留必是评估残留）。 */
    public void markCleanTargetOnCancel() {
        this.cleanTargetOnCancel = true;
    }

    /** 清理完成后清除标记。 */
    public void clearCleanTargetOnCancel() {
        this.cleanTargetOnCancel = false;
    }

    /** 是否需要在 CLEANING 阶段强制清理目标区域。 */
    public boolean cleanTargetOnCancel() {
        return cleanTargetOnCancel;
    }
}
