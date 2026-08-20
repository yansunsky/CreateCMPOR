package com.createcmpor.evaluation;

import com.createcmpor.Config;
import com.createcmpor.CreateCMPOR;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Phase 6 录制回放净平衡（统一用户定义的三扫描公式）。
 *
 * <p>三扫描语义：S1（预热结束）为基线、S2（评估结束、IO 已停）为终态；
 * 对每个物品/流体 key：{@code net = (S2 - S1) + IO输出 - IO输入}。
 * net &gt; 0 → 净产物；net &lt; 0 → 净原料；net = 0/噪声 → 双向移除。
 * S0 仅在无预热时作为回退基线（由调用方传入）。</p>
 */
final class EvaluationReplay {
    record ReplayResult(Map<EvaluationTrace.FlowKey, int[]> replayIn,
                        Map<EvaluationTrace.FlowKey, int[]> replayOut,
                        int[] energyIn, int[] energyOut) {
    }

    private EvaluationReplay() {
    }

    static ReplayResult build(EvaluationTrace trace, EvaluationAudit.InventorySnapshot s0,
                              EvaluationAudit.InventorySnapshot s1,
                              EvaluationAudit.InventorySnapshot baseline, int recordLength) {
        Map<EvaluationTrace.FlowKey, int[]> in = new HashMap<>();
        Map<EvaluationTrace.FlowKey, int[]> out = new HashMap<>();
        trace.series().forEach((key, series) -> {
            in.put(key, series.input.clone());
            out.put(key, series.output.clone());
        });
        int[] energyIn = trace.energy().input == null ? new int[recordLength] : trace.energy().input.clone();
        int[] energyOut = trace.energy().output == null ? new int[recordLength] : trace.energy().output.clone();

        // 基线 = S1（预热结束）；无预热时回退 S0
        EvaluationAudit.InventorySnapshot base = baseline != null ? baseline
                : (s0 != null ? s0 : EvaluationAudit.InventorySnapshot.empty());
        EvaluationAudit.InventorySnapshot end = s1 != null ? s1 : EvaluationAudit.InventorySnapshot.empty();

        netBalance(trace, base, end, in, out, recordLength);
        energyNetBalance(trace, base, end, energyIn, energyOut, recordLength);
        applyLossAndFilter(in, out, energyOut, recordLength);
        return new ReplayResult(in, out, energyIn, energyOut);
    }

    /**
     * 物品/流体净平衡：对基线库存、终态库存、IO 流量三处出现的每个 key 统一应用
     * {@code net = (S2 - S1) + O - I}。容器直接消耗、掉落物积累、IO 纯过路全部由同一公式覆盖。
     */
    private static void netBalance(EvaluationTrace trace,
                                   EvaluationAudit.InventorySnapshot base,
                                   EvaluationAudit.InventorySnapshot end,
                                   Map<EvaluationTrace.FlowKey, int[]> in,
                                   Map<EvaluationTrace.FlowKey, int[]> out,
                                   int recordLength) {
        Set<EvaluationTrace.FlowKey> keys = new HashSet<>(trace.series().keySet());
        base.items().keySet().forEach(id -> keys.add(EvaluationTrace.FlowKey.item(id)));
        base.fluids().keySet().forEach(id -> keys.add(EvaluationTrace.FlowKey.fluid(id)));
        end.items().keySet().forEach(id -> keys.add(EvaluationTrace.FlowKey.item(id)));
        end.fluids().keySet().forEach(id -> keys.add(EvaluationTrace.FlowKey.fluid(id)));

        for (EvaluationTrace.FlowKey key : keys) {
            EvaluationTrace.Series series = trace.series().get(key);
            long ioOut = series == null ? 0L : series.outputTotal;
            long ioIn = series == null ? 0L : series.inputTotal;
            boolean item = "item".equals(key.kind());
            long baseCount = item
                    ? base.items().getOrDefault(key.id(), 0L)
                    : base.fluids().getOrDefault(key.id(), 0L);
            long endCount = item
                    ? end.items().getOrDefault(key.id(), 0L)
                    : end.fluids().getOrDefault(key.id(), 0L);
            long net = (endCount - baseCount) + ioOut - ioIn;

            long consumedEstimate = Math.max(0, (baseCount - endCount) + ioIn);
            long maxIo = Math.max(ioOut, consumedEstimate);
            long dynThreshold = Math.max((long) (recordLength * Config.INTERMEDIATE_RATIO.get()),
                    (long) (maxIo * Config.IO_ERROR_RATIO.get()));
            if (Math.abs(net) < dynThreshold) {
                in.remove(key);
                out.remove(key);
                CreateCMPOR.LOGGER.info("回放-噪声删除: {} net={} threshold={}", key.id(), net, dynThreshold);
                continue;
            }
            if (net == 0) {
                in.remove(key);
                out.remove(key);
                CreateCMPOR.LOGGER.info("回放-纯过路删除: {}", key.id());
                continue;
            }
            if (net > 0) {
                in.remove(key);
                int[] outPattern = out.computeIfAbsent(key, ignored -> new int[recordLength]);
                distribute(outPattern, net, recordLength);
                CreateCMPOR.LOGGER.info("回放-净产物: {} net={}", key.id(), net);
            } else {
                out.remove(key);
                int[] inPattern = in.computeIfAbsent(key, ignored -> new int[recordLength]);
                distribute(inPattern, -net, recordLength);
                CreateCMPOR.LOGGER.info("回放-净原料: {} net={}", key.id(), net);
            }
        }
    }

    private static void energyNetBalance(EvaluationTrace trace,
                                         EvaluationAudit.InventorySnapshot base,
                                         EvaluationAudit.InventorySnapshot end,
                                         int[] energyIn, int[] energyOut, int recordLength) {
        long ioIn = trace.energy().inputTotal;
        long ioOut = trace.energy().outputTotal;
        // 统一三扫描公式：net = (S2-S1) + IO输出 - IO输入
        long net = (end.energy() - base.energy()) + ioOut - ioIn;

        long consumedEstimate = Math.max(0, (base.energy() - end.energy()) + ioIn);
        long maxIo = Math.max(ioOut, consumedEstimate);
        long dynThreshold = Math.max((long) (recordLength * Config.INTERMEDIATE_RATIO.get()),
                (long) (maxIo * Config.IO_ERROR_RATIO.get()));
        if (Math.abs(net) < dynThreshold || net == 0) {
            java.util.Arrays.fill(energyIn, 0);
            java.util.Arrays.fill(energyOut, 0);
            CreateCMPOR.LOGGER.info("回放-能量: net={} 视为噪声/过路，双向归零", net);
            return;
        }
        if (net > 0) {
            java.util.Arrays.fill(energyIn, 0);
            distribute(energyOut, net, recordLength);
        } else {
            java.util.Arrays.fill(energyOut, 0);
            distribute(energyIn, -net, recordLength);
        }
        CreateCMPOR.LOGGER.info("回放-能量: net={}", net);
    }

    private static void applyLossAndFilter(Map<EvaluationTrace.FlowKey, int[]> in,
                                           Map<EvaluationTrace.FlowKey, int[]> out,
                                           int[] energyOut, int recordLength) {
        double lossRate = Config.LOSS_RATE.get();
        double intermediateRatio = Config.INTERMEDIATE_RATIO.get();
        Set<ResourceLocation> catalysts = catalystSet();

        for (int[] pattern : out.values()) {
            applyLoss(pattern, lossRate, recordLength);
        }
        applyLoss(energyOut, lossRate, recordLength);

        for (Map.Entry<EvaluationTrace.FlowKey, int[]> entry : new HashSet<>(in.entrySet())) {
            if ("item".equals(entry.getKey().kind()) && catalysts.contains(entry.getKey().id())) {
                CreateCMPOR.LOGGER.info("回放-催化剂保留: {}", entry.getKey().id());
                continue;
            }
            long total = EvaluationTrace.total(entry.getValue());
            if (total < (long) (recordLength * intermediateRatio) && total < recordLength) {
                in.remove(entry.getKey());
                CreateCMPOR.LOGGER.info("回放-中间产物过滤: {} total={}", entry.getKey().id(), total);
            }
        }
    }

    private static void applyLoss(int[] pattern, double lossRate, int recordLength) {
        long total = EvaluationTrace.total(pattern);
        long adjusted = (long) Math.floor(total * lossRate);
        if (adjusted >= total) {
            return;
        }
        distribute(pattern, adjusted, recordLength);
    }

    private static void distribute(int[] pattern, long amount, int recordLength) {
        java.util.Arrays.fill(pattern, 0);
        int base = (int) (amount / recordLength);
        int remainder = (int) (amount % recordLength);
        for (int second = 0; second < recordLength; second++) {
            pattern[second] = base + (remainder > 0 ? 1 : 0);
            if (remainder > 0) {
                remainder--;
            }
        }
    }

    private static Set<ResourceLocation> catalystSet() {
        Set<ResourceLocation> catalysts = new HashSet<>();
        for (String raw : Config.CATALYST_ITEMS.get()) {
            ResourceLocation parsed = ResourceLocation.tryParse(raw);
            if (parsed != null) {
                catalysts.add(parsed);
            }
        }
        return catalysts;
    }
}
