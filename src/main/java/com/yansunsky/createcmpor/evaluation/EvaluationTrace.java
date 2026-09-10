package com.yansunsky.createcmpor.evaluation;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 6 评估采样模型：按秒 bucket 记录 IO 流量 + 累计总量双通道。
 *
 * <p>物品与流体用 {@link FlowKey}（kind + id）区分；能量独立序列。</p>
 *
 * <p><b>组件（DataComponents）保真</b>：1.20.5+ 起大量物品差异（药水内容、附魔、
 * 自定义名等）由 DataComponents 承载，仅靠 item id 无法刻画一种物品。采样时
 * 额外记录每条物品流<b>首个观测到</b>的物品模板（count=1、含组件），固化时交给
 * 工厂，使产物能按真实形态重建（否则"水瓶"会被还原成无组件的"不可合成药水"）。</p>
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
        /** 该流量首次观测到的物品模板（count=1、含组件）；未观测到则为 EMPTY。 */
        ItemStack inputTemplate = ItemStack.EMPTY;
        ItemStack outputTemplate = ItemStack.EMPTY;

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
            record(roomCode, key, amount, input, currentTick, ItemStack.EMPTY);
        }

        /**
         * 记录一次物品/流体流量；{@code template} 为本次流量的物品形态模板
         * （count=1、含 DataComponents），仅物品流有意义，用于固化时还原产物形态。
         */
        public void record(String roomCode, FlowKey key, long amount, boolean input, long currentTick,
                           ItemStack template) {
            EvaluationTrace trace = traces.get(roomCode);
            if (trace != null) {
                trace.record(key, amount, input, currentTick, template);
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

        /** 诊断：返回某会话的采样摘要（物品/流体条目与能量累计）。 */
        String stats(String roomCode) {
            EvaluationTrace trace = traces.get(roomCode);
            if (trace == null) {
                return "无 trace";
            }
            long items = 0;
            long fluids = 0;
            int itemKeys = 0;
            int fluidKeys = 0;
            for (Map.Entry<FlowKey, Series> entry : trace.series.entrySet()) {
                long total = EvaluationTrace.total(entry.getValue().input)
                        + EvaluationTrace.total(entry.getValue().output);
                if ("item".equals(entry.getKey().kind())) {
                    itemKeys++;
                    items += total;
                } else {
                    fluidKeys++;
                    fluids += total;
                }
            }
            long energyIn = trace.energy.input == null ? 0 : EvaluationTrace.total(trace.energy.input);
            long energyOut = trace.energy.output == null ? 0 : EvaluationTrace.total(trace.energy.output);
            return "物品条目=" + itemKeys + " 累计=" + items
                    + "，流体条目=" + fluidKeys + " 累计=" + fluids
                    + "，能量 in=" + energyIn + " out=" + energyOut;
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
        record(key, amount, input, currentTick, ItemStack.EMPTY);
    }

    void record(FlowKey key, long amount, boolean input, long currentTick, ItemStack template) {
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
        // 组件模板：只取首个非空模板（同 id 多变体在 id 粒度聚合下取先见者）
        if (template != null && !template.isEmpty()) {
            ItemStack one = template.copyWithCount(1);
            if (input) {
                if (entry.inputTemplate.isEmpty()) {
                    entry.inputTemplate = one;
                }
            } else if (entry.outputTemplate.isEmpty()) {
                entry.outputTemplate = one;
            }
        }
    }

    /** 输入物品模板表（仅包含已观测到模板的条目）：FlowKey → 模板 Stack（count=1）。 */
    Map<FlowKey, ItemStack> inputTemplates() {
        Map<FlowKey, ItemStack> templates = new LinkedHashMap<>();
        series.forEach((key, entry) -> {
            if (!entry.inputTemplate.isEmpty()) {
                templates.put(key, entry.inputTemplate);
            }
        });
        return templates;
    }

    /** 输出物品模板表（仅包含已观测到模板的条目）：FlowKey → 模板 Stack（count=1）。 */
    Map<FlowKey, ItemStack> outputTemplates() {
        Map<FlowKey, ItemStack> templates = new LinkedHashMap<>();
        series.forEach((key, entry) -> {
            if (!entry.outputTemplate.isEmpty()) {
                templates.put(key, entry.outputTemplate);
            }
        });
        return templates;
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
