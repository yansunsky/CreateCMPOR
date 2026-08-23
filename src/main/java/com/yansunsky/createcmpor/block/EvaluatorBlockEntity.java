package com.yansunsky.createcmpor.block;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.yansunsky.createcmpor.init.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * 保存评估会话标识和原机器镜像，权威事务记录位于 EvaluationSavedData。
 *
 * <p><b>评估进度（护目镜）</b>：评估方块在主世界机器位置。评估期间服务端按阶段
 * 记录剩余秒数（{@link #stage} + {@link #remainingSeconds}），随方块实体数据同步到
 * 客户端；玩家戴护目镜看评估方块时，{@link #addToGoggleTooltip} 显示阶段与剩余时间。
 * 阶段切换由评估调度器（{@code EvaluationScheduler}）在预热/采样起点调用
 * {@link #beginCountdown}（读配置预设值写入）。</p>
 */
public class EvaluatorBlockEntity extends RoomCodeBlockEntity implements IHaveGoggleInformation {

    /** 评估进度阶段。 */
    public enum EvaluationStage {
        /** 未在评估（默认，不显示护目镜信息）。 */
        NONE,
        /** 评估准备中（冻结/克隆，无秒数）。 */
        PREPARING,
        /** 预热（RECORD_START 秒倒计时）。 */
        WARMING,
        /** 采样（EVALUATE_SECONDS 秒倒计时）。 */
        SAMPLING
    }

    @Nullable
    private UUID sessionId;
    @Nullable
    private UUID ownerId;
    private boolean launcherReturnEligible;
    @Nullable
    private CompoundTag originalMachineState;
    @Nullable
    private CompoundTag savedOriginalNbt;
    private boolean phase4CleanupRequired;

    /** 评估进度：当前阶段。 */
    private EvaluationStage stage = EvaluationStage.NONE;
    /** 评估进度：阶段剩余秒数（服务端每 20 tick 递减 1，同步客户端）。 */
    private int remainingSeconds = 0;
    /** 倒计时节流（每 20 tick 递减一次）。 */
    private int countdownCooldown = 0;

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
        phase4CleanupRequired = false;
        // 评估方块装上即进入"准备中"，等待调度器切换预热/采样
        setStage(EvaluationStage.PREPARING, 0);
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

    public boolean isPhase4CleanupRequired() {
        return phase4CleanupRequired;
    }

    public void markPhase4CleanupRequired() {
        phase4CleanupRequired = true;
        setChanged();
    }

    public EvaluationStage getStage() {
        return stage;
    }

    public int getRemainingSeconds() {
        return remainingSeconds;
    }

    /**
     * 由评估调度器在阶段起点调用：写入阶段与剩余秒数（读配置预设值）。
     *
     * @param newStage 目标阶段（PREPARING/WARMING/SAMPLING）
     * @param seconds  该阶段总秒数（预热=RECORD_START，采样=EVALUATE_SECONDS）；0 表示无秒数
     */
    public void beginCountdown(EvaluationStage newStage, int seconds) {
        setStage(newStage, seconds);
    }

    private void setStage(EvaluationStage newStage, int seconds) {
        this.stage = newStage;
        this.remainingSeconds = Math.max(0, seconds);
        this.countdownCooldown = 0;
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    // ===== 服务端 tick：倒计时递减 + 同步 =====

    public void tick() {
        if (level == null || level.isClientSide) {
            return;
        }
        if (stage == EvaluationStage.NONE || remainingSeconds <= 0) {
            return;
        }
        if (--countdownCooldown > 0) {
            return;
        }
        countdownCooldown = 20; // 每 20 tick = 1 秒递减
        remainingSeconds = Math.max(0, remainingSeconds - 1);
        setChanged();
        if (remainingSeconds == 0) {
            // 倒计时归零：阶段收尾，同步客户端（显示"剩余 0 秒"直至固化替换）
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    // ===== 护目镜 tooltip（客户端调用） =====

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        switch (stage) {
            case PREPARING -> tooltip.add(Component.literal("平行房间评估：准备中…"));
            case WARMING -> {
                tooltip.add(Component.literal("平行房间评估：预热中"));
                tooltip.add(Component.literal("剩余 " + remainingSeconds + " 秒"));
            }
            case SAMPLING -> {
                tooltip.add(Component.literal("平行房间评估：采集中"));
                tooltip.add(Component.literal("剩余 " + remainingSeconds + " 秒"));
            }
            case NONE -> {
                return false;
            }
        }
        return true;
    }

    // ===== NBT 持久化 =====

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
        phase4CleanupRequired = tag.getBoolean("phase4_cleanup_required");
        try {
            stage = tag.contains("eval_stage")
                    ? EvaluationStage.valueOf(tag.getString("eval_stage"))
                    : EvaluationStage.NONE;
        } catch (IllegalArgumentException e) {
            stage = EvaluationStage.NONE;
        }
        remainingSeconds = tag.getInt("eval_remaining_seconds");
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
        tag.putBoolean("phase4_cleanup_required", phase4CleanupRequired);
        if (stage != EvaluationStage.NONE) {
            tag.putString("eval_stage", stage.name());
            tag.putInt("eval_remaining_seconds", remainingSeconds);
        }
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
