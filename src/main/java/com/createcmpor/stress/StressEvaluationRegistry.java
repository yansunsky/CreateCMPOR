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
 * <p><b>关键：存储的是原始 stress/impact 值（未乘转速）。</b>
 * 因为 Create 的 {@code calculateAddedStressCapacity()} 和 {@code calculateStressApplied()}
 * 返回的值会被 Create 内部乘以转速。如果存储的值已经乘了转速，就会双重乘法。
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
     * <p><b>关键修复</b>：network 的 capacity/stress 已经乘了转速（impact × |speed|），
     * 但 StressProfile 存储的应该是<b>原始值</b>（未乘转速），因为
     * {@code calculateAddedStressCapacity()} / {@code calculateStressApplied()}
     * 返回的值会被 Create 内部再乘一次转速。
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

        // INPUT: 网络应力消耗 - 真实容量 = 需输入的应力（网络单位，已乘转速）
        float realInputCapacity = inputTotalCap - inputTotalVirtual;
        float inputSUNetwork = Math.max(0f, inputTotalStress - realInputCapacity);
        // 转换为原始值（除以转速），Create 内部会再乘转速
        float inputSU = inputMaxSpeed > 0 ? inputSUNetwork / inputMaxSpeed : 0f;
        float inputRPM = inputSU > 0f ? inputMaxSpeed : 0f;

        // OUTPUT: 网络容量 - 网络消耗 = 可输出的应力（网络单位，已乘转速）
        float outputSUNetwork = Math.max(0f, outputTotalCap - outputTotalStress);
        // 转换为原始值
        float outputSU = outputMaxSpeed > 0 ? outputSUNetwork / outputMaxSpeed : 0f;
        float outputRPM = outputSU > 0f ? outputMaxSpeed : 0f;

        CreateCMPOR.LOGGER.info("[CreateCMPOR] room={} 聚合计算: inputSUNetwork={} / inputMaxSpeed={} = inputSU={} (原始值); outputSUNetwork={} / outputMaxSpeed={} = outputSU={} (原始值)",
                roomCode, inputSUNetwork, inputMaxSpeed, inputSU,
                outputSUNetwork, outputMaxSpeed, outputSU);

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
