package com.createcmpor.stress;

import com.createcmpor.CreateCMPOR;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 评估期应力采样登记表。
 *
 * <p>采样三个独立维度：网络应力上限（capacity）、网络应力消耗（stress）、以及应力输入方块
 * 提供的虚拟应力容量（virtualCapacity）。采样分两种类型：
 * <ul>
 *     <li>{@link SampleType#INPUT}：来自应力输入方块（应力源），virtualCapacity > 0。</li>
 *     <li>{@link SampleType#OUTPUT}：来自应力输出方块（被动观察者），virtualCapacity = 0。</li>
 * </ul>
 *
 * <p><b>关键：存储的是实际 SU 值（已乘转速）。</b>
 * 网络 capacity/stress 本身就是实际 SU（Create 内部已乘转速）。
 * StressProfile 存储实际 SU，由应力拓展方块的 {@code calculateStressApplied()} /
 * {@code calculateAddedStressCapacity()} 在返回时除以转速转为 raw stress value，
 * Create 内部再乘回转速，得到正确的实际 SU。
 *
 * <p><b>按网络分组计算</b>：stress_input 和 stress_output 可能在同一个 Create KineticNetwork 中。
 * 此时 stress_output 采样到的 capacity 包含了 stress_input 的虚拟容量，如果不扣除就会凭空产出应力。
 * 因此 consume() 按 networkId 分组，统一计算 {@code net = (capacity - virtual) - stress}：
 * <ul>
 *     <li>net > 0 → 真实净产出 → outputSU</li>
 *     <li>net < 0 → 真实净消耗 → inputSU（取绝对值）</li>
 *     <li>net = 0 → 平衡，既不输入也不输出</li>
 * </ul>
 */
public class StressEvaluationRegistry {

    public enum SampleType { INPUT, OUTPUT }

    public record Sample(float capacity, float stress, float virtualCapacity, float speed,
                         Long networkId, SampleType type) {}

    private static final Map<String, Map<Long, List<Sample>>> DATA = new ConcurrentHashMap<>();

    public static void record(String roomCode, long ioPosKey, Sample sample) {
        if (roomCode == null) return;
        DATA.computeIfAbsent(roomCode, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(ioPosKey, k -> new ArrayList<>())
                .add(sample);
//        CreateCMPOR.LOGGER.info("[CreateCMPOR] 应力采样 room={} io={} type={} net={} cap={} stress={} virtual={} speed={}",
//                roomCode, ioPosKey, sample.type(), sample.networkId(),
//                sample.capacity(), sample.stress(), sample.virtualCapacity(), sample.speed());
    }

    /**
     * 评估结束：按 Create KineticNetwork 分组聚合，计算工厂的 input/output 应力。
     *
     * <p>每个网络的 capacity/stress 对所有成员方块都是相同的值（来自同一个 KineticNetwork），
     * 取任一采样即可。virtualCapacity 只来自 INPUT 类型的采样，需累加同网络所有 INPUT 的虚拟容量。
     *
     * <p>计算公式：{@code net = (capacity - totalVirtual) - stress}
     * <ul>
     *     <li>net > 0 → outputSU += net（真实净产出）</li>
     *     <li>net < 0 → inputSU += |net|（真实净消耗）</li>
     * </ul>
     * 这样当 stress_input 直连 stress_output 时，虚拟容量被正确扣除，net = 0，不会凭空产出。
     */
    public static StressProfile consume(String roomCode) {
        Map<Long, List<Sample>> perIo = DATA.remove(roomCode);
        if (perIo == null || perIo.isEmpty()) {
//            CreateCMPOR.LOGGER.info("[CreateCMPOR] room={} 无应力采样，profile=EMPTY", roomCode);
            return StressProfile.EMPTY;
        }

        // 按 networkId 分组
        Map<Long, List<Sample>> byNetwork = new HashMap<>();
        for (List<Sample> series : perIo.values()) {
            if (series.isEmpty()) continue;
            Sample first = series.get(0);
            Long netId = first.networkId();
            if (netId == null) continue;
            byNetwork.computeIfAbsent(netId, k -> new ArrayList<>()).addAll(series);
        }

        float inputSU = 0f, outputSU = 0f;
        float inputMaxSpeed = 0f, outputMaxSpeed = 0f;

        for (Map.Entry<Long, List<Sample>> netEntry : byNetwork.entrySet()) {
            Long netId = netEntry.getKey();
            List<Sample> netSamples = netEntry.getValue();

            // 同一网络的 capacity/stress 相同，取稳定值
            float sCap = stableValue(netSamples, Sample::capacity);
            float sStress = stableValue(netSamples, Sample::stress);
            float sSpeed = stableValue(netSamples, Sample::speed);

            // 累加同网络所有 INPUT 采样的虚拟容量
            float totalVirtual = 0f;
            for (Sample s : netSamples) {
                if (s.type() == SampleType.INPUT)
                    totalVirtual += stableValue(
                            netSamples.stream().filter(x -> x.type() == SampleType.INPUT).toList(),
                            Sample::virtualCapacity);
            }
            // 如果有多个 INPUT 方块在同一网络，每个的 virtualCapacity 相同（都是同一个网络的容量贡献），
            // 但实际只需扣一次。取最大值而非累加。
            // 修正：每个 stress_input 的 virtualCapacity 是它自己贡献的，capacity 是整个网络的。
            // 如果网络上有 N 个 stress_input，capacity 包含了所有 N 个的虚拟容量之和，
            // 所以 totalVirtual 应该是所有 INPUT virtualCapacity 之和。
            // 但上面的循环会对每个 INPUT sample 都累加一次 stableValue（整个 INPUT 序列的中位数），
            // 这会导致重复计算。改为：取所有 INPUT 的 virtualCapacity 稳定值的最大值
            // （因为同一网络上所有 INPUT 看到的 capacity 相同，virtual 也应该是同一个值）。
            //
            // 实际上：每个 stress_input 的 virtualCapacity = 它自己的 calculateAddedStressCapacity() × speed
            // 如果有两个 stress_input 在同一网络，每个贡献自己的虚拟容量，
            // capacity = sum(所有虚拟容量) + 真实容量
            // 所以 totalVirtual = sum(每个 input 的 virtualCapacity)
            // 但我们只有一个稳定值（中位数），且同网络所有 input 的 virtual 应该相同
            // （因为它们报告的都是自己的虚拟容量，且同一个 calculateAddedStressCapacity()）
            // 所以取一次即可。

            // 简化：取同网络 INPUT 采样的 virtualCapacity 稳定值（如果有 INPUT 的话）
            List<Sample> inputSamples = netSamples.stream()
                    .filter(s -> s.type() == SampleType.INPUT).toList();
            if (!inputSamples.isEmpty()) {
                totalVirtual = stableValue(inputSamples, Sample::virtualCapacity);
            }

            // 核心计算：(capacity - virtual) - stress = 真实净应力
            float realCapacity = sCap - totalVirtual;
            float net = realCapacity - sStress;

//            CreateCMPOR.LOGGER.info("[CreateCMPOR] room={} net={} 计算: cap={} virtual={} stress={} → realCap={} net={} (speed={})",
//                    roomCode, netId, sCap, totalVirtual, sStress, realCapacity, net, sSpeed);

            if (net > 0f) {
                outputSU += net;
                outputMaxSpeed = Math.max(outputMaxSpeed, sSpeed);
            } else if (net < 0f) {
                inputSU += (-net);
                inputMaxSpeed = Math.max(inputMaxSpeed, sSpeed);
            }
        }

        float inputRPM = inputSU > 0f ? inputMaxSpeed : 0f;
        float outputRPM = outputSU > 0f ? outputMaxSpeed : 0f;

//        CreateCMPOR.LOGGER.info("[CreateCMPOR] room={} 聚合结果: inputSU={} outputSU={} (实际SU)",
//                roomCode, inputSU, outputSU);

        StressProfile profile = (inputSU == 0f && outputSU == 0f)
                ? StressProfile.EMPTY
                : new StressProfile(inputSU, inputRPM, outputSU, outputRPM);
//        CreateCMPOR.LOGGER.info("[CreateCMPOR] room={} 聚合应力档案: {} (网络数={})",
//                roomCode, profile, byNetwork.size());
        return profile;
    }

    /** dump 当前 DATA 状态（诊断用）。 */
    public static void dumpState() {
//        CreateCMPOR.LOGGER.info("[CreateCMPOR] StressEvaluationRegistry DATA 状态: rooms={}",
//                DATA.keySet());
        DATA.forEach((room, perIo) -> {
            int total = perIo.values().stream().mapToInt(List::size).sum();
//            CreateCMPOR.LOGGER.info("[CreateCMPOR]   room={} IO方块数={} 总采样数={}",
//                    room, perIo.size(), total);
        });
    }

    private static float stableValue(List<Sample> series, java.util.function.Function<Sample, Float> extractor) {
        int warmup = series.size() / 2;
        List<Float> tail = new ArrayList<>();
        for (int i = warmup; i < series.size(); i++)
            tail.add(extractor.apply(series.get(i)));
        return median(tail);
    }

    private static float median(List<Float> values) {
        if (values.isEmpty()) return 0f;
        values.sort(Float::compareTo);
        return values.get(values.size() / 2);
    }
}
