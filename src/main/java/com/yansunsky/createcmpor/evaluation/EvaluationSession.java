package com.yansunsky.createcmpor.evaluation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
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
        PARALLEL_PREPARING,
        PARALLEL_PUBLISHING,
        PARALLEL_PUBLISHED,
        PARALLEL_EVALUATING,
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

    /** 多分支评估（并行空间输入方块）：当前分支索引（0-based）。 */
    private int branchIndex = 0;
    /** 多分支评估：总分支数（并行方块配置物品数）；1 = 单分支普通评估。 */
    private int branchCount = 1;
    /** 多分支评估：各分支结果（运行时内存，重启后回滚）。 */
    private final java.util.List<EvaluationVerdict.Result> branchResults = new java.util.ArrayList<>();
    /** 分支间清理标记：advanceBranch 后进入 CLEANING 是"正常分支间清理"；失败清理会清掉此标记。 */
    private boolean branchTransitionPending = false;
    /**
     * 净化评估标记：发起人用的是净化启动棒（详见 {@code EvaluationCleaner}）。
     *
     * <p>运行时会话状态（模块内可变），<b>不持久化</b>：评估本就不跨重启续跑
     * （重启走 cleanup-first 回滚），净化标志随会话一起消失即可，无需进 NBT。</p>
     */
    private boolean purified = false;
    /** 净化结果是否已报告（并行 lane 去重；运行期状态，不持久化）。 */
    private boolean purificationReported = false;
    /**
     * 待送达的净化降级原因（见 {@link #purificationFallbackReason()}）。
     */
    private Component purificationFallbackReason;

    /**
     * 净化评估的"房间内有动态结构"会话级判定（{@code null} = 还没有分支作出判定）。
     *
     * <p>并行评估每个 lane 是独立维度、各自跑一遍检测，理论上结论一致；
     * 但装配是延迟一 tick 的、lane 的 tick 时序也可能不同，于是存在某条 lane
     * 检测不到而另一条检测到的窗口。这里把它钉成会话级既定事实：
     * <b>任一条 lane 发现装置，整场就按普通评估执行</b>——同一根净化棒、同一次评估，
     * 不该出现"分支 1 净化了、分支 2 没净化"这种口径分裂。</p>
     */
    private Boolean hasDynamicStructure;

    public Boolean hasDynamicStructureDecision() {
        return hasDynamicStructure;
    }

    /**
     * 记录"房间内有动态结构"的判定，<b>只会上调不会下调</b>。
     *
     * <p>并行评估的多个 lane 可能落在<b>同一个 tick</b> 各自判定，若允许互相覆盖，
     * 一条 lane 的"有"会被另一条同 tick 的"无"冲掉。粘性写入保证
     * "任一 lane 发现装置 ⇒ 整场按普通评估执行"这条语义是硬保证。</p>
     */
    public void markDynamicStructureFound() {
        this.hasDynamicStructure = Boolean.TRUE;
    }

    /** 记录"本 lane 未发现动态结构"；已经判为"有"时不会被覆盖。 */
    public void markDynamicStructureAbsent() {
        if (this.hasDynamicStructure == null) {
            this.hasDynamicStructure = Boolean.FALSE;
        }
    }
    /** 并行评估退回串行的原因；null 表示源内容允许并行。 */
    private String serialFallbackReason;
    /** 真正并行模式下的独立分支事务；未初始化时为空。 */
    private java.util.List<EvaluationBranch> parallelBranches = java.util.List.of();

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

    /** 本次评估是否为净化评估（净化启动棒发起）。 */
    public boolean purified() {
        return purified;
    }

    public void setPurified(boolean purified) {
        this.purified = purified;
    }

    /** 净化结果是否已向发起人报告（并行 lane 各跑一次净化，靠它去重）。 */
    public boolean isPurificationReported() {
        return purificationReported;
    }

    public void markPurificationReported() {
        this.purificationReported = true;
    }

    /**
     * 净化评估降级为普通评估的原因（null = 净化正常）。
     *
     * <p>为什么不在净化那一刻直接发提示：那一刻的提示会被紧接着的
     * "评估开始：采样 N 秒（预热 N 秒）"盖掉，玩家根本看不清。改为暂存，
     * 由 S0 扫描完成后与开始提示相邻送达。</p>
     */
    public Component purificationFallbackReason() {
        return purificationFallbackReason;
    }

    public void setPurificationFallbackReason(Component reason) {
        this.purificationFallbackReason = reason;
    }

    public EvaluationManifest manifest() {
        return manifest;
    }

    public void setManifest(EvaluationManifest manifest) {
        this.manifest = manifest;
    }

    public boolean hasPhase4Manifest() {
        return manifest != null || !parallelBranches.isEmpty();
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
        // 净化标志必须落盘：启动棒是在会话移除之后才退还的（addPendingLauncherReturn → 玩家上线/轮询时发放），
        // 那时已经读不到会话了；不写这里，用净化棒评估完拿回来的会是普通启动棒（道具掉级）。
        tag.putBoolean("purified", purified);
        tag.putInt("branch_index", branchIndex);
        tag.putInt("branch_count", branchCount);
        if (serialFallbackReason != null) {
            tag.putString("serial_fallback_reason", serialFallbackReason);
        }
        if (!parallelBranches.isEmpty()) {
            net.minecraft.nbt.ListTag branchTags = new net.minecraft.nbt.ListTag();
            for (EvaluationBranch branch : parallelBranches) {
                branchTags.add(branch.save());
            }
            tag.put("parallel_branches", branchTags);
        }
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
        session.purified = tag.getBoolean("purified");
        session.branchIndex = tag.getInt("branch_index");
        session.branchCount = Math.max(1, tag.getInt("branch_count"));
        session.serialFallbackReason = tag.contains("serial_fallback_reason")
                ? tag.getString("serial_fallback_reason")
                : null;
        if (tag.contains("parallel_branches", net.minecraft.nbt.Tag.TAG_LIST)) {
            net.minecraft.nbt.ListTag branchTags = tag.getList(
                    "parallel_branches", net.minecraft.nbt.Tag.TAG_COMPOUND);
            java.util.List<EvaluationBranch> branches = new java.util.ArrayList<>();
            for (int index = 0; index < branchTags.size(); index++) {
                branches.add(EvaluationBranch.load(branchTags.getCompound(index), registries));
            }
            session.setParallelBranches(branches);
        }
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

    // ===== 多分支评估（并行空间输入方块）=====

    /** 并行评估是否被允许；一旦因特殊内容退回串行，整场保持串行。 */
    public boolean parallelAllowed() {
        return serialFallbackReason == null;
    }

    public String serialFallbackReason() {
        return serialFallbackReason;
    }

    public void setSerialFallbackReason(String serialFallbackReason) {
        this.serialFallbackReason = serialFallbackReason;
    }

    /** 并行分支是否已建立。 */
    public boolean hasParallelBranches() {
        return !parallelBranches.isEmpty();
    }

    public java.util.List<EvaluationBranch> parallelBranches() {
        return parallelBranches;
    }

    /** 并行分支的 manifest（命令/诊断用；返回公开类型，避免命令层触达包内分支类型）。 */
    public java.util.List<EvaluationManifest> parallelBranchManifests() {
        return parallelBranches.stream().map(EvaluationBranch::manifest).toList();
    }

    /** 调试命令：返回当前可观察副本的目标维度；并行时选第一个已 ready 分支，否则 0 号分支。 */
    public ResourceKey<Level> liveCopyDimension() {
        if (!parallelBranches.isEmpty()) {
            for (EvaluationBranch branch : parallelBranches) {
                if (branch.manifest().targetReady()) {
                    return branch.manifest().targetDimension();
                }
            }
            return parallelBranches.getFirst().manifest().targetDimension();
        }
        return manifest == null ? null : manifest.targetDimension();
    }

    public void setParallelBranches(java.util.List<EvaluationBranch> parallelBranches) {
        this.parallelBranches = parallelBranches == null ? java.util.List.of() : parallelBranches;
    }

    public int branchIndex() {
        return branchIndex;
    }

    public int branchCount() {
        return branchCount;
    }

    public void setBranchCount(int branchCount) {
        this.branchCount = Math.max(1, branchCount);
    }

    public java.util.List<EvaluationVerdict.Result> branchResults() {
        return branchResults;
    }

    /** 进入下一分支：记录已完成分支、推进索引。返回是否还有下一分支。 */
    public boolean advanceBranch(EvaluationVerdict.Result result) {
        branchResults.add(result);
        branchIndex++;
        branchTransitionPending = true;
        return branchIndex < branchCount;
    }

    /** 分支间清理标记：CLEANING 完成后据此判断是"继续下一分支"还是"收尾/回滚"。 */
    public boolean branchTransitionPending() {
        return branchTransitionPending;
    }

    /** 清除分支间清理标记（CLEANING 完成、进入下一分支前调用）。 */
    public void clearBranchTransitionPending() {
        this.branchTransitionPending = false;
    }
}
