package com.yansunsky.createcmpor.evaluation;

/**
 * 迁移自原 CMPOR {@code RateEvaluator}：7 步稳定速率算法。
 */
final class EvaluationRateEvaluator {
    private EvaluationRateEvaluator() {
    }

    /** 对按秒聚合的 IO 序列拟合稳定产率（每 tick 速率）。 */
    static double evaluateStableRate(int[] timeSeriesData) {
        int n = timeSeriesData.length;
        if (n == 0) {
            return 0;
        }

        long[] c = new long[n + 1];
        for (int i = 0; i < n; i++) {
            c[i + 1] = c[i] + timeSeriesData[i];
        }

        int tRef = (int) ((long) n * 2 / 3);
        if (n - tRef <= 0) {
            return 0;
        }
        double kRef = (double) (c[n] - c[tRef]) / (n - tRef);

        double maxDev = 0;
        for (int t = tRef; t <= n; t++) {
            double expected = c[n] - kRef * (n - t);
            maxDev = Math.max(maxDev, Math.abs(c[t] - expected));
        }

        double tolerance = Math.max(maxDev * 2.0, Math.max(kRef * 1.5, 1.0));

        int stableStart = 0;
        for (int t = tRef - 1; t >= 0; t--) {
            double expected = c[n] - kRef * (n - t);
            if (Math.abs(c[t] - expected) > tolerance) {
                stableStart = t + 1;
                break;
            }
        }

        int stableDuration = n - stableStart;
        if (stableDuration <= 0) {
            return 0;
        }
        return (c[n] - c[stableStart]) / (double) stableDuration / 20.0;
    }

    /** 稳定性判定：稳定段占比 >= 2/3 视为稳定（预热/倾倒占比小）。 */
    static boolean isStable(int[] timeSeriesData) {
        int n = timeSeriesData.length;
        if (n == 0) {
            return false;
        }
        long[] c = new long[n + 1];
        for (int i = 0; i < n; i++) {
            c[i + 1] = c[i] + timeSeriesData[i];
        }
        int tRef = (int) ((long) n * 2 / 3);
        if (n - tRef <= 0) {
            return false;
        }
        double kRef = (double) (c[n] - c[tRef]) / (n - tRef);
        double maxDev = 0;
        for (int t = tRef; t <= n; t++) {
            maxDev = Math.max(maxDev, Math.abs(c[t] - (c[n] - kRef * (n - t))));
        }
        double tolerance = Math.max(maxDev * 2.0, Math.max(kRef * 1.5, 1.0));
        int stableStart = 0;
        for (int t = tRef - 1; t >= 0; t--) {
            if (Math.abs(c[t] - (c[n] - kRef * (n - t))) > tolerance) {
                stableStart = t + 1;
                break;
            }
        }
        return stableStart <= n / 3;
    }
}
