package com.yansunsky.createcmpor.evaluation;

import com.yansunsky.createcmpor.Config;
import com.yansunsky.createcmpor.stress.StressProfile;

import java.util.Map;

/**
 * Phase 6 判定：稳定性/周期检测 → RATE / REPLAY / REJECTED。
 */
final class EvaluationVerdict {
    static final String VERDICT_RATE = "RATE";
    static final String VERDICT_REPLAY = "REPLAY";
    static final String VERDICT_REJECTED = "REJECTED";

    record Result(String verdict, String rejectReason,
                  Map<EvaluationTrace.FlowKey, Double> inputRates,
                  Map<EvaluationTrace.FlowKey, Double> outputRates,
                  Map<EvaluationTrace.FlowKey, int[]> replayIn,
                  Map<EvaluationTrace.FlowKey, int[]> replayOut,
                  /** 输入/输出物品形态模板（count=1、含 DataComponents），固化时交给工厂还原产物形态。 */
                  Map<EvaluationTrace.FlowKey, net.minecraft.world.item.ItemStack> inputItemTemplates,
                  Map<EvaluationTrace.FlowKey, net.minecraft.world.item.ItemStack> outputItemTemplates,
                  double inputEnergyRate, double outputEnergyRate,
                  int[] energyReplayIn, int[] energyReplayOut,
                  StressProfile stressProfile,
                  double normalBurnDemandPerSecond, double superBurnDemandPerSecond,
                  String detail) {
        boolean rejected() {
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
                         StressProfile stressProfile) {
        String mode = Config.EVALUATION_MODE.get().isEmpty()
                ? "AUTO" : Config.EVALUATION_MODE.get().getFirst();
        // 能量速率：trace 自统计（RATE 输入沿用；输出经 auditEnergyRate 用三扫描修正）
        double inputEnergyRate = trace.energy().input == null ? 0
                : EvaluationRateEvaluator.evaluateStableRate(trace.energy().input);
        double outputEnergyRate = trace.energy().output == null ? 0
                : EvaluationRateEvaluator.evaluateStableRate(trace.energy().output);

        if ("FORCE_RATE".equals(mode)) {
            return rateResult(trace, s0, s1, baseline,
                    inputEnergyRate, outputEnergyRate, stressProfile);
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
                    inputEnergyRate, outputEnergyRate, stressProfile);
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
                detail);
    }

    private static Result rateResult(EvaluationTrace trace,
                                     EvaluationAudit.InventorySnapshot s0,
                                     EvaluationAudit.InventorySnapshot s1,
                                     EvaluationAudit.InventorySnapshot baseline,
                                     double inputEnergyRate, double outputEnergyRate,
                                     StressProfile stressProfile) {
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
                "RATE：稳定速率拟合");
    }

    static Result reject(String reason, String detail) {
        return new Result(VERDICT_REJECTED, reason, Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of(),
                0, 0, null, null, StressProfile.EMPTY, 0, 0, detail);
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
