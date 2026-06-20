package com.createcmpor.stress;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * 工厂方块的应力档案，记录评估得出的内部应力输入/输出需求。
 *
 * <p>采用 input/output 双轴模型（类似 CMPOR 能量的 inputEnergy/outputEnergy）：
 * <ul>
 *     <li>{@code inputSU > 0}：工厂内部消耗应力，需从外部输入的应力值（SU）。</li>
 *     <li>{@code outputSU > 0}：工厂内部产应力，可向外部提供的应力值（SU）。</li>
 *     <li>两者互斥：评估时净值为负→input，净值为正→output。</li>
 * </ul>
 *
 * @param inputSU   需输入的应力值（SU），>=0
 * @param inputRPM  需输入的转速（RPM，绝对值），>=0
 * @param outputSU  可输出的应力值（SU），>=0
 * @param outputRPM 可输出的转速（RPM，绝对值），>=0
 */
public record StressProfile(float inputSU, float inputRPM, float outputSU, float outputRPM) {

    /** 空档案：无应力交互。 */
    public static final StressProfile EMPTY = new StressProfile(0f, 0f, 0f, 0f);

    public static final Codec<StressProfile> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.FLOAT.fieldOf("input_su").forGetter(StressProfile::inputSU),
            Codec.FLOAT.fieldOf("input_rpm").forGetter(StressProfile::inputRPM),
            Codec.FLOAT.fieldOf("output_su").forGetter(StressProfile::outputSU),
            Codec.FLOAT.fieldOf("output_rpm").forGetter(StressProfile::outputRPM)
    ).apply(instance, StressProfile::new));

    /** 是否无应力交互。 */
    public boolean isEmpty() {
        return inputSU == 0f && outputSU == 0f;
    }

    /** 工厂是否需要外部输入应力。 */
    public boolean isConsume() {
        return inputSU > 0f;
    }

    /** 工厂是否可对外提供应力。 */
    public boolean isProvide() {
        return outputSU > 0f;
    }

    /** 有符号净值：负=需输入，正=可输出。用于拓展方块链求和。 */
    public float net() {
        return outputSU - inputSU;
    }

    /** 链求和用的转速：有输出取输出转速，否则取输入转速。 */
    public float netSpeed() {
        return outputSU > 0f ? outputRPM : inputRPM;
    }

    @Override
    public String toString() {
        return "StressProfile{in=" + inputSU + "SU@" + inputRPM + "rpm, out=" + outputSU + "SU@" + outputRPM + "rpm}";
    }
}
