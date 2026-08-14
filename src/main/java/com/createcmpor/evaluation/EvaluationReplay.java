package com.createcmpor.evaluation;

import com.createcmpor.Config;
import com.createcmpor.CreateCMPOR;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Phase 6 录制回放净平衡（迁移自原 CMPOR Core 的 netBalance 系列 + 催化剂增强）。
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

        EvaluationAudit.InventorySnapshot base = baseline != null ? baseline
                : (s0 != null ? s0 : EvaluationAudit.InventorySnapshot.empty());
        EvaluationAudit.InventorySnapshot end = s1 != null ? s1 : EvaluationAudit.InventorySnapshot.empty();

        distributeMachineConsumption(trace, base, end, in, recordLength);
        netBalance(trace, base, end, in, out, recordLength);
        energyNetBalance(trace, base, end, energyIn, energyOut, recordLength);
        applyLossAndFilter(in, out, energyOut, recordLength);
        return new ReplayResult(in, out, energyIn, energyOut);
    }

    private static void distributeMachineConsumption(EvaluationTrace trace,
                                                     EvaluationAudit.InventorySnapshot s0,
                                                     EvaluationAudit.InventorySnapshot s1,
                                                     Map<EvaluationTrace.FlowKey, int[]> in,
                                                     int recordLength) {
        Set<ResourceLocation> catalysts = catalystSet();
        for (Map.Entry<ResourceLocation, Long> entry : s0.items().entrySet()) {
            ResourceLocation id = entry.getKey();
            EvaluationTrace.FlowKey key = EvaluationTrace.FlowKey.item(id);
            EvaluationTrace.Series series = trace.series().get(key);
            if (series != null && EvaluationTrace.total(series.output) > 0) {
                continue; // 产出物在净平衡处理
            }
            long s1Count = s1.items().getOrDefault(id, 0L);
            long ioInput = series == null ? 0L : series.inputTotal;
            long machineConsumed = (entry.getValue() - s1Count) - ioInput;
            if (machineConsumed <= 0) {
                continue;
            }
            int[] pattern = in.computeIfAbsent(key, ignored -> new int[recordLength]);
            distribute(pattern, machineConsumed, recordLength);
            CreateCMPOR.LOGGER.info("回放-容器直接消耗: {} 消耗 {}（催化剂={}）",
                    id, machineConsumed, catalysts.contains(id));
        }
    }

    private static void netBalance(EvaluationTrace trace,
                                   EvaluationAudit.InventorySnapshot s0,
                                   EvaluationAudit.InventorySnapshot s1,
                                   Map<EvaluationTrace.FlowKey, int[]> in,
                                   Map<EvaluationTrace.FlowKey, int[]> out,
                                   int recordLength) {
        for (Map.Entry<EvaluationTrace.FlowKey, int[]> entry : new HashSet<>(out.entrySet())) {
            EvaluationTrace.FlowKey key = entry.getKey();
            int[] outPattern = entry.getValue();
            long produced = EvaluationTrace.total(outPattern);
            EvaluationTrace.Series series = trace.series().get(key);
            long ioInput = series == null ? 0L : series.inputTotal;
            long floorCount = "item".equals(key.kind())
                    ? trace.floorItems.getOrDefault(key, 0L) : 0L;
            long s0Count = "item".equals(key.kind())
                    ? s0.items().getOrDefault(key.id(), 0L) : s0.fluids().getOrDefault(key.id(), 0L);
            long s1Count = "item".equals(key.kind())
                    ? s1.items().getOrDefault(key.id(), 0L) : s1.fluids().getOrDefault(key.id(), 0L);

            long consumed;
            if (s0Count == 0) {
                consumed = ioInput + floorCount;
            } else {
                consumed = Math.max(0, (s0Count + ioInput + floorCount) - s1Count);
            }
            long net = produced - consumed;

            long maxIo = Math.max(produced, consumed);
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
                                         EvaluationAudit.InventorySnapshot s0,
                                         EvaluationAudit.InventorySnapshot s1,
                                         int[] energyIn, int[] energyOut, int recordLength) {
        long ioIn = trace.energy().inputTotal;
        long ioOut = trace.energy().outputTotal;
        long consumed = ioIn + Math.max(0, s0.energy() - s1.energy());
        long net = ioOut - consumed;

        long maxIo = Math.max(ioOut, consumed);
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
