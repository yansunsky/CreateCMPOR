package com.createcmpor.evaluation;

import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 6 评估采样模型：按秒 bucket 记录 IO 流量 + 累计总量双通道。
 *
 * <p>物品与流体用 {@link FlowKey}（kind + id）区分；能量独立序列。</p>
 */
public final class EvaluationTrace {
    public record FlowKey(String kind, ResourceLocation id) {
        public static FlowKey item(ResourceLocation id) {
            return new FlowKey("item", id);
        }

        public static FlowKey fluid(ResourceLocation id) {
            return new FlowKey("fluid", id);
        }
    }

    static final class Series {
        final int[] input;
        final int[] output;
        long inputTotal;
        long outputTotal;

        Series(int seconds) {
            input = new int[seconds];
            output = new int[seconds];
        }
    }

    static final class EnergySeries {
        int[] input;
        int[] output;
        long inputTotal;
        long outputTotal;

        void init(int seconds) {
            input = new int[seconds];
            output = new int[seconds];
        }
    }

    /** 活动评估的采样登记表：roomCode → Trace（运行时内存，重启不恢复）。 */
    public static final class Hub {
        public static final Hub INSTANCE = new Hub();
        private final Map<String, EvaluationTrace> traces = new ConcurrentHashMap<>();

        public void start(String roomCode, int seconds, long startTick) {
            traces.put(roomCode, new EvaluationTrace(seconds, startTick));
        }

        public void record(String roomCode, FlowKey key, long amount, boolean input, long currentTick) {
            EvaluationTrace trace = traces.get(roomCode);
            if (trace != null) {
                trace.record(key, amount, input, currentTick);
            }
        }

        public void recordEnergy(String roomCode, long amount, boolean input, long currentTick) {
            EvaluationTrace trace = traces.get(roomCode);
            if (trace != null) {
                trace.recordEnergy(amount, input, currentTick);
            }
        }

        void setFloorItems(String roomCode, Map<FlowKey, Long> floorItems) {
            EvaluationTrace trace = traces.get(roomCode);
            if (trace != null) {
                trace.floorItems.putAll(floorItems);
            }
        }

        EvaluationTrace get(String roomCode) {
            return traces.get(roomCode);
        }

        void remove(String roomCode) {
            traces.remove(roomCode);
        }

        int activeCount() {
            return traces.size();
        }
    }

    private final int seconds;
    private final long startTick;
    private final Map<FlowKey, Series> series = new HashMap<>();
    private final EnergySeries energy = new EnergySeries();
    final Map<FlowKey, Long> floorItems = new HashMap<>();

    private EvaluationTrace(int seconds, long startTick) {
        this.seconds = seconds;
        this.startTick = startTick;
    }

    long startTick() {
        return startTick;
    }

    int seconds() {
        return seconds;
    }

    Map<FlowKey, Series> series() {
        return series;
    }

    EnergySeries energy() {
        return energy;
    }

    void record(FlowKey key, long amount, boolean input, long currentTick) {
        int second = (int) ((currentTick - startTick) / 20);
        if (second < 0 || second >= seconds) {
            return;
        }
        Series entry = series.computeIfAbsent(key, ignored -> new Series(seconds));
        int[] bucket = input ? entry.input : entry.output;
        int add = (int) Math.min(amount, Integer.MAX_VALUE - (long) bucket[second]);
        bucket[second] += add;
        if (input) {
            entry.inputTotal += amount;
        } else {
            entry.outputTotal += amount;
        }
    }

    void recordEnergy(long amount, boolean input, long currentTick) {
        int second = (int) ((currentTick - startTick) / 20);
        if (second < 0 || second >= seconds) {
            return;
        }
        if (energy.input == null) {
            energy.init(seconds);
        }
        int[] bucket = input ? energy.input : energy.output;
        int add = (int) Math.min(amount, Integer.MAX_VALUE - (long) bucket[second]);
        bucket[second] += add;
        if (input) {
            energy.inputTotal += amount;
        } else {
            energy.outputTotal += amount;
        }
    }

    static long total(int[] array) {
        long sum = 0;
        for (int value : array) {
            sum += value;
        }
        return sum;
    }
}
