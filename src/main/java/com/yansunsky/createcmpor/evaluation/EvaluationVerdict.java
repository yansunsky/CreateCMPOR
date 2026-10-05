package com.yansunsky.createcmpor.evaluation;

import com.yansunsky.createcmpor.Config;
import com.yansunsky.createcmpor.stress.StressProfile;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase 6 判定：稳定性/周期检测 → RATE / REPLAY / REJECTED。
 *
 * <p>本类与 {@link Result} 提升为 {@code public}：固化侧（{@code block} 包）需要读取模式表数据
 * （48 文档 §2.3 ①）。</p>
 */
public final class EvaluationVerdict {
    static final String VERDICT_RATE = "RATE";
    static final String VERDICT_REPLAY = "REPLAY";
    static final String VERDICT_REJECTED = "REJECTED";

    public record Result(String verdict, String rejectReason,
                         Map<EvaluationTrace.FlowKey, Double> inputRates,
                         Map<EvaluationTrace.FlowKey, Double> outputRates,
                         Map<EvaluationTrace.FlowKey, int[]> replayIn,
                         Map<EvaluationTrace.FlowKey, int[]> replayOut,
                         /** 输入/输出物品形态模板（count=1、含 DataComponents），固化时交给工厂还原产物形态。 */
                         Map<EvaluationTrace.FlowKey, ItemStack> inputItemTemplates,
                         Map<EvaluationTrace.FlowKey, ItemStack> outputItemTemplates,
                         double inputEnergyRate, double outputEnergyRate,
                         int[] energyReplayIn, int[] energyReplayOut,
                         StressProfile stressProfile,
                         double normalBurnDemandPerSecond, double superBurnDemandPerSecond,
                         String detail,
                         /**
                          * 本分支（模式）的触发物品身份签名（{@link ItemIdentity}）；空列表 = 默认模式。
                          *
                          * <p>采集点：{@code EvaluationScheduler.activateIoBlocks} 设置分支索引的同一处，
                          * 取该副本内<b>所有</b> {@code PARALLEL_INPUT} 方块在该 {@code branchIndex} 处
                          * 暴露的物品（去重、越界条目忽略、保持方块扫描顺序）。
                          * 并行评估每 lane 各自采集，串行评估每分支各自采集。</p>
                          */
                         List<String> triggerItems) {
        /** 规范化触发表：去 null / 空串、去重、保持顺序且不可变 ⇒ 任何构造点都不会留下 null 字段。 */
        public Result {
            triggerItems = normalizeTriggerItems(triggerItems);
        }

        public boolean rejected() {
            return VERDICT_REJECTED.equals(verdict);
        }
    }

    private static final int MIN_PERIOD = 5;
    private static final int MAX_PERIOD = 60;
    private static final int MIN_FULL_CYCLES = 3;

    private EvaluationVerdict() {
    }

    static Result decide(EvaluationTrace trace, EvaluationAudit.InventorySnapshot s0,
                         EvaluationAudit.InventorySnapshot s1,
                         EvaluationAudit.InventorySnapshot baseline,
                         StressProfile stressProfile,
                         List<String> triggerItems) {
        String mode = Config.EVALUATION_MODE.get().isEmpty()
                ? "AUTO" : Config.EVALUATION_MODE.get().getFirst();
        // 能量速率：trace 自统计（RATE 输入沿用；输出经 auditEnergyRate 用三扫描修正）
        double inputEnergyRate = trace.energy().input == null ? 0
                : EvaluationRateEvaluator.evaluateStableRate(trace.energy().input);
        double outputEnergyRate = trace.energy().output == null ? 0
                : EvaluationRateEvaluator.evaluateStableRate(trace.energy().output);

        if ("FORCE_RATE".equals(mode)) {
            return rateResult(trace, s0, s1, baseline,
                    inputEnergyRate, outputEnergyRate, stressProfile, triggerItems);
        }

        boolean replayNeeded = "FORCE_REPLAY".equals(mode);
        if ("AUTO".equals(mode)) {
            for (Map.Entry<EvaluationTrace.FlowKey, EvaluationTrace.Series> entry : trace.series().entrySet()) {
                if (EvaluationTrace.total(entry.getValue().output) <= 0) {
                    continue;
                }
                if (!isStable(entry.getValue().output)) {
                    replayNeeded = true;
                    break;
                }
            }
            if (!replayNeeded && trace.energy().output != null
                    && EvaluationTrace.total(trace.energy().output) > 0
                    && !isStable(trace.energy().output)) {
                replayNeeded = true;
            }
        }

        if (!replayNeeded) {
            return rateResult(trace, s0, s1, baseline,
                    inputEnergyRate, outputEnergyRate, stressProfile, triggerItems);
        }

        EvaluationReplay.ReplayResult replay = EvaluationReplay.build(
                trace, s0, s1, baseline, trace.seconds());
        String detail = "REPLAY：净平衡+损耗+" + Config.LOSS_RATE.get();
        return new Result(VERDICT_REPLAY, null, Map.of(), Map.of(),
                replay.replayIn(), replay.replayOut(),
                trace.inputTemplates(), trace.outputTemplates(),
                0, EvaluationTrace.total(replay.energyOut()) > 0
                        ? (double) EvaluationTrace.total(replay.energyOut()) / trace.seconds() : 0,
                replay.energyIn(), replay.energyOut(), stressProfile,
                replay.normalBurnDemandPerSecond(), replay.superBurnDemandPerSecond(),
                detail, triggerItems);
    }

    private static Result rateResult(EvaluationTrace trace,
                                     EvaluationAudit.InventorySnapshot s0,
                                     EvaluationAudit.InventorySnapshot s1,
                                     EvaluationAudit.InventorySnapshot baseline,
                                     double inputEnergyRate, double outputEnergyRate,
                                     StressProfile stressProfile,
                                     List<String> triggerItems) {
        // 基线 = S1（预热结束）；无预热时回退 S0
        EvaluationAudit.InventorySnapshot base = baseline != null ? baseline : s0;
        EvaluationAudit.RateAudit audited = EvaluationAudit.auditRates(
                s0, base, s1, trace, trace.seconds());
        double auditedEnergy = EvaluationAudit.auditEnergyRate(
                base, s1, trace.energy(), outputEnergyRate, trace.seconds());
        return new Result(VERDICT_RATE, null, audited.inputs(), audited.outputs(),
                Map.of(), Map.of(),
                trace.inputTemplates(), trace.outputTemplates(),
                inputEnergyRate, auditedEnergy,
                null, null, stressProfile,
                audited.normalBurnDemandPerSecond(), audited.superBurnDemandPerSecond(),
                "RATE：稳定速率拟合", triggerItems);
    }

    static Result reject(String reason, String detail) {
        return reject(reason, detail, List.of());
    }

    /** 带触发物品的拒绝：让"哪条分支失败"的提示能显示该分支的输入物品（空列表 = 未知/无输入）。 */
    static Result reject(String reason, String detail, List<String> triggerItems) {
        return new Result(VERDICT_REJECTED, reason, Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of(),
                0, 0, null, null, StressProfile.EMPTY, 0, 0, detail, triggerItems);
    }

    // ===== T3：可触发性校验（48 文档 §2.3 ⑤；固化前由固化侧调用，本类不调用）=====

    /**
     * 校验每条分支（模式）是否存在<b>至少一个</b>可喂入的触发物品。
     *
     * <p><b>判据</b>（Lead 2026-10-05 复核放宽 + P0 修正）：该分支输入表签名集 =
     * {@code inputRates} 与 {@code replayIn} <b>两者</b>的物品 flow key 签名集合
     * （RATE 分支的输入在 {@code inputRates}；REPLAY 分支的 {@code inputRates} 恒为空表、输入在 {@code replayIn}）；
     * 有效触发 = {@code triggerItems} ∩ 输入表。<b>有效触发为空</b>才判不可触发
     * （玩家没有槽位可喂 ⇒ 该模式永远无法激活）；有效触发非空即通过（哪怕只有一个有效）。</p>
     *
     * <p><b>自洽性</b>：这两张表正是固化侧写入该模式输入表的同一批键 ⇒ 本校验问的
     * "触发物品在不在输入表里"与固化后"工厂有没有这个输入槽"必然是同一个答案，
     * 不会出现"校验放过、工厂却没有槽位"或反之。</p>
     *
     * <p><b>为何放宽</b>：分支数取房间里各并行方块白名单条目数的<b>最大值</b>，"多并行方块"是受支持配置，
     * 此时分支 i 的触发集 = 各方块第 i 项暴露物的<b>并集</b>；只要其中一项没被那条支路消耗（很常见），
     * 旧判据（任一缺失即失败）就会把 0.4.31 能固化的合法房间整场取消。</p>
     *
     * <p><b>本校验不是"注垃圾"的防线</b>：注入物是净输入，天然就在输入表里；真正的防线是严格互斥（I2）。
     * 不要为了"更严"而扩大判据，否则只会误伤合法房间。</p>
     *
     * <p>{@code triggerItems} 为空 = 默认模式（无触发条件、始终可激活）⇒ <b>不参与校验</b>。
     * 返回：空列表 = 全部通过；每有一个不可触发的模式返回一个可直接呈现的 Component
     * （由固化侧负责拼接与呈现）。</p>
     */
    public static List<Component> untriggerableModes(List<Result> modes) {
        List<Component> problems = new ArrayList<>();
        if (modes == null) {
            return problems;
        }
        for (int index = 0; index < modes.size(); index++) {
            Result mode = modes.get(index);
            if (mode == null || mode.triggerItems().isEmpty()) {
                // 无触发物品 = 默认模式：始终可激活，不参与校验（否则单机评估会被整场取消）
                continue;
            }
            Set<String> inputSignatures = new LinkedHashSet<>();
            for (EvaluationTrace.FlowKey key : mode.inputRates().keySet()) {
                if ("item".equals(key.kind())) {
                    inputSignatures.add(key.signature());
                }
            }
            // P0 修正（Lead 2026-10-05 复核）：REPLAY 分支的 inputRates 恒为空表（构造点传 Map.of()），
            // 它的输入表在 replayIn 里。两条路径必须都取，否则"有并行方块 + 任一分支判 REPLAY"
            // 会让有效触发恒为空 ⇒ 合法房间被整场取消（0.4.31 能固化，本版固化不了）。
            for (EvaluationTrace.FlowKey key : mode.replayIn().keySet()) {
                if ("item".equals(key.kind())) {
                    inputSignatures.add(key.signature());
                }
            }
            boolean anyTriggerable = false;
            for (String trigger : mode.triggerItems()) {
                if (inputSignatures.contains(trigger)) {
                    anyTriggerable = true;
                    break;
                }
            }
            if (!anyTriggerable) {
                problems.add(Component.translatable("message.createcmpor.evaluation.untriggerable_branch",
                        index + 1, triggerItemsListing(mode)));
            }
        }
        return problems;
    }

    /** 触发表显示名拼接（本地化分隔符）；仅在"全部触发物品都不可喂"时提示，故一次列出全部。 */
    private static Component triggerItemsListing(Result mode) {
        MutableComponent joined = Component.empty();
        List<String> triggers = mode.triggerItems();
        for (int index = 0; index < triggers.size(); index++) {
            if (index > 0) {
                joined.append(Component.translatable("message.createcmpor.evaluation.trigger_separator"));
            }
            joined.append(triggerItemName(mode, triggers.get(index)));
        }
        return joined;
    }

    /** 触发表规范化：去 null / 空串、去重、保持顺序、不可变。 */
    private static List<String> normalizeTriggerItems(List<String> triggerItems) {
        if (triggerItems == null || triggerItems.isEmpty()) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String item : triggerItems) {
            if (item != null && !item.isEmpty()) {
                unique.add(item);
            }
        }
        return List.copyOf(unique);
    }

    /**
     * 触发物品的本地化显示名：优先取该模式输入模板（含组件形态）的 hover name；
     * 模板缺失时用签名里的 item id 构造物品堆再取名；两者都拿不到时退化为签名原文（不应发生）。
     */
    private static Component triggerItemName(Result mode, String signature) {
        for (Map.Entry<EvaluationTrace.FlowKey, ItemStack> entry : mode.inputItemTemplates().entrySet()) {
            if (!"item".equals(entry.getKey().kind()) || !signature.equals(entry.getKey().signature())) {
                continue;
            }
            ItemStack template = entry.getValue();
            if (template != null && !template.isEmpty()) {
                return template.getHoverName();
            }
        }
        return itemNameFromSignature(signature);
    }

    /** 签名 → 显示名（{@link ItemIdentity} 只保留 id + 组件摘要，故只能取回基础物品名）。 */
    private static Component itemNameFromSignature(String signature) {
        ResourceLocation id = ItemIdentity.idOf(signature);
        Item item = id == null ? null : BuiltInRegistries.ITEM.get(id);
        if (item == null || item == Items.AIR) {
            return Component.literal(signature == null ? "" : signature);
        }
        return new ItemStack(item).getHoverName();
    }

    private static boolean isStable(int[] series) {
        if (!EvaluationRateEvaluator.isStable(series)) {
            return false;
        }
        int period = detectPeriod(series);
        if (period >= MIN_PERIOD) {
            int cycles = series.length / period;
            return cycles >= MIN_FULL_CYCLES;
        }
        return true;
    }

    /** 轻量自相关周期检测：返回检测到的周期（秒），无周期返回 0。 */
    static int detectPeriod(int[] series) {
        int n = series.length;
        if (n < MIN_PERIOD * 2) {
            return 0;
        }
        double mean = 0;
        for (int value : series) {
            mean += value;
        }
        mean /= n;
        if (mean <= 0) {
            return 0;
        }

        int maxLag = Math.min(MAX_PERIOD, n / 3);
        int bestLag = 0;
        double bestCorrelation = 0.5;
        for (int lag = MIN_PERIOD; lag <= maxLag; lag++) {
            double numerator = 0;
            double denominator = 0;
            for (int i = 0; i + lag < n; i++) {
                double x = series[i] - mean;
                double y = series[i + lag] - mean;
                numerator += x * y;
                denominator += x * x;
            }
            if (denominator <= 0) {
                continue;
            }
            double correlation = numerator / denominator;
            if (correlation > bestCorrelation) {
                bestCorrelation = correlation;
                bestLag = lag;
            }
        }
        return bestLag;
    }
}
