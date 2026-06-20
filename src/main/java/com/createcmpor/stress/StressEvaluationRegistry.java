package com.createcmpor.stress;

import com.createcmpor.CreateCMPOR;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 评估期应力采样登记表。
 *
 * <p>采样三个独立维度：网络应力上限（capacity）、网络应力消耗（stress）、以及应力输入方块
 * 提供的虚拟应力容量（virtualCapacity）。采样分两种类型：
 * <ul>
 *     <li>{@link SampleType#INPUT}：来自应力输入方块（应力源），virtualCapacity > 0。
 *         用于计算工厂的 input 应力需求。</li>
 *     <li>{@link SampleType#OUTPUT}：来自应力输出方块（被动观察者），virtualCapacity = 0。
 *         用于计算工厂的 output 可提供应力。</li>
 * </ul>
 *
 * <p><b>关键：存储的是实际 SU 值（已乘转速）。</b>
 * 网络 capacity/stress 本身就是实际 SU（Create 内部已乘转速）。
 * StressProfile 存储实际 SU，由应力拓展方块的 {@code calculateStressApplied()} /
 * {@code calculateAddedStressCapacity()} 在返回时除以转速转为 raw stress value，
 * Create 内部再乘回转速，得到正确的实际 SU。
 */
public class StressEvaluationRegistry {

    public enum SampleType { INPUT, OUTPUT }

    public record Sample(float capacity, float stress, float virtualCapacity, float speed, SampleType type) {}

    private static final Map<String, Map<Long, List<Sample>>> DATA = new ConcurrentHashMap<>();

    public static void record(String roomCode, long ioPosKey, Sample sample) {
        if (roomCode == null) return;
        DATA.computeIfAbsent(roomCode, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(ioPosKey, k -> new ArrayList<>())
                .add(sample);
        CreateCMPOR.LOGGER.info("[CreateCMPOR] 应力采样 room={} io={} type={} cap={} stress={} virtual={} speed={}",
                roomCode, ioPosKey, sample.type(), sample.capacity(), sample.stress(),
                sample.virtualCapacity(), sample.speed());
    }

    /**
     * 评估结束：分别聚合 INPUT 和 OUTPUT 采样，计算工厂的 input/output 应力。
     *
     * <p><b>存储实际 SU（已乘转速）</b>：network 的 capacity/stress 本身就是实际 SU，
     * 直接存入 StressProfile。应力拓展方块在返回给 Create API 时会除以转速，
     * Create 内部再乘回转速，得到正确的实际 SU。
     * <p>之前错误地在此处除以转速，导致拓展方块再次除以转速时产生双重除法。
     */
    public static StressProfile consume(String roomCode) {
        Map<Long, List<Sample>> perIo = DATA.remove(roomCode);
        if (perIo == null || perIo.isEmpty()) {
            CreateCMPOR.LOGGER.info("[CreateCMPOR] room={} 无应力采样，profile=EMPTY", roomCode);
            return StressProfile.EMPTY;
        }

        float inputTotalCap = 0f, inputTotalStress = 0f, inputTotalVirtual = 0f, inputMaxSpeed = 0f;
        int inputCount = 0;
        float outputTotalCap = 0f, outputTotalStress = 0f, outputMaxSpeed = 0f;
        int outputCount = 0;

        for (Map.Entry<Long, List<Sample>> e : perIo.entrySet()) {
            List<Sample> series = e.getValue();
            if (series.isEmpty()) continue;
            Sample first = series.get(0);
            float sCap = stableValue(series, Sample::capacity);
            float sStress = stableValue(series, Sample::stress);
            float sVirtual = stableValue(series, Sample::virtualCapacity);
            float sSpeed = stableValue(series, Sample::speed);

            if (first.type() == SampleType.INPUT) {
                inputTotalCap += sCap;
                inputTotalStress += sStress;
                inputTotalVirtual += sVirtual;
                inputMaxSpeed = Math.max(inputMaxSpeed, sSpeed);
                inputCount++;
                CreateCMPOR.LOGGER.info("[CreateCMPOR] room={} input_io={} 稳定值 cap={} stress={} virtual={} speed={} (样本{})",
                        roomCode, e.getKey(), sCap, sStress, sVirtual, sSpeed, series.size());
            } else {
                outputTotalCap += sCap;
                outputTotalStress += sStress;
                outputMaxSpeed = Math.max(outputMaxSpeed, sSpeed);
                outputCount++;
                CreateCMPOR.LOGGER.info("[CreateCMPOR] room={} output_io={} 稳定值 cap={} stress={} speed={} (样本{})",
                        roomCode, e.getKey(), sCap, sStress, sSpeed, series.size());
            }
        }

        // INPUT: 网络应力消耗 - 真实容量 = 需输入的应力（实际 SU，已含转速）
        float realInputCapacity = inputTotalCap - inputTotalVirtual;
        float inputSU = Math.max(0f, inputTotalStress - realInputCapacity);
        float inputRPM = inputSU > 0f ? inputMaxSpeed : 0f;

        // OUTPUT: 网络容量 - 网络消耗 = 可输出的应力（实际 SU，已含转速）
        float outputSU = Math.max(0f, outputTotalCap - outputTotalStress);
        float outputRPM = outputSU > 0f ? outputMaxSpeed : 0f;

        CreateCMPOR.LOGGER.info("[CreateCMPOR] room={} 聚合计算: inputSU={} (实际SU); outputSU={} (实际SU)",
                roomCode, inputSU, outputSU);

        StressProfile profile = (inputSU == 0f && outputSU == 0f)
                ? StressProfile.EMPTY
                : new StressProfile(inputSU, inputRPM, outputSU, outputRPM);
        CreateCMPOR.LOGGER.info("[CreateCMPOR] room={} 聚合应力档案: {}（input_io={}, output_io={}）",
                roomCode, profile, inputCount, outputCount);
        return profile;
    }

    /** dump 当前 DATA 状态（诊断用）。 */
    public static void dumpState() {
        CreateCMPOR.LOGGER.info("[CreateCMPOR] StressEvaluationRegistry DATA 状态: rooms={}",
                DATA.keySet());
        DATA.forEach((room, perIo) -> {
            int total = perIo.values().stream().mapToInt(List::size).sum();
            CreateCMPOR.LOGGER.info("[CreateCMPOR]   room={} IO方块数={} 总采样数={}",
                    room, perIo.size(), total);
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
