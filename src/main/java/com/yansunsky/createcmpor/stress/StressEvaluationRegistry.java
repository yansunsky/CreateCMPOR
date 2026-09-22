package com.yansunsky.createcmpor.stress;

import com.yansunsky.createcmpor.CreateCMPOR;

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
 * 因此 consume() 按 networkId 分组，统一计算 {@code net = (capacity - Σvirtual) - stress}
 * （Σvirtual = 同网络**每个** 应力输入方块各自虚拟容量之和；漏扣任意一份都会凭空产出应力）：
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

    /** 显式清理：评估异常、取消或并行分支失败时移除未消费样本。 */
    public static void clear(String roomCode) {
        if (roomCode == null) return;
        DATA.remove(roomCode);
    }

    /**
     * 评估结束：按 Create KineticNetwork 分组聚合，计算工厂的 input/output 应力。
     *
     * <p>每个网络的 capacity/stress 对所有成员方块都是相同的值（来自同一个 KineticNetwork），
     * 取任一采样即可。virtualCapacity 只来自 INPUT 类型的采样，**必须累加同网络每个 INPUT 方块
     * 各自的虚拟容量**：Create 的 calculateCapacity() 对所有 source 求和（raw × |各源自身转速|），
     * 同网络 N 个应力输入方块时 capacity 含 N 份虚拟容量，漏扣即凭空多出 (N-1)×16384×speed
     * （0.3.44 之前版本只扣 1 份 → 两个应力输入方块即可做出无限应力输出工厂）。
     *
     * <p>计算公式：{@code net = (capacity - Σvirtual) - stress}
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

            // 累加同网络「所有」INPUT 方块的虚拟容量（按 IO 方块去重后各自取稳定值再求和）。
            //
            // 为什么必须求和：Create 的 KineticNetwork.calculateCapacity() 对网络内所有 source 求和
            // （presentCapacity += getActualCapacityOf(be)，即 raw × |该源自身转速|）。
            // 同网络 N 个 stress_input 时，capacity 里含 N 份虚拟容量，所以必须扣 N 份。
            // 旧实现只扣 1 份（取 INPUT 采样中位值）→ net 凭空多出 (N-1) × 16384 × speed，
            // 玩家用 2 个应力输入方块即可做出「0 输入、大输出」的无限应力工厂（本次修复的漏洞）。
            float totalVirtual = 0f;
            int inputBlockCount = 0;
            for (List<Sample> ioSeries : perIo.values()) {
                if (ioSeries.isEmpty()) {
                    continue;
                }
                Sample head = ioSeries.get(0);
                if (head.type() != SampleType.INPUT || !netId.equals(head.networkId())) {
                    continue;
                }
                totalVirtual += stableValue(ioSeries, Sample::virtualCapacity);
                inputBlockCount++;
            }
            if (inputBlockCount > 1) {
                // 同一网络挂了多个应力输入方块：正常房间只需 1 个即可驱动全部机器。
                // 这里只告警不拒绝（求和已保证不凭空产出），便于线上发现新的堆叠利用变种。
                CreateCMPOR.LOGGER.warn("[CreateCMPOR] room={} 网络 {} 上存在 {} 个应力输入方块，"
                                + "虚拟容量按 {} SU 全额扣除（net={}）；若该房间被用于刷应力请检查布局",
                        roomCode, netId, inputBlockCount, totalVirtual,
                        Math.max(0f, sCap - totalVirtual) - sStress);
            }

            // 核心计算：(capacity - Σvirtual) - stress = 真实净应力
            float realCapacity = Math.max(0f, sCap - totalVirtual);
            float net = realCapacity - sStress;
            // 护栏：capacity 与虚拟容量的求和顺序不同可能残留极小浮点差，
            // 含注入源的网络理论上净值为 0（或负），用相对阈值抹掉假输出/假输入。
            float epsilon = Math.max(1f, Math.abs(sCap) * 1e-6f);
            if (Math.abs(net) < epsilon) {
                net = 0f;
            }

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
