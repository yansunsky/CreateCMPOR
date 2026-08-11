package com.createcmpor;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/**
 * 模组配置（COMMON）。
 */
public class Config {

    public static final ModConfigSpec SPEC;

    /**
     * 是否允许应力 IO 方块向外提供应力。
     * <ul>
     *     <li>{@code true}（默认）：应力大于 0 时向外提供（输出模式），同时也处理小于 0 的输入情况。</li>
     *     <li>{@code false}：禁止输出，仅在应力小于 0 时从外部吸取应力，绝不向外提供。</li>
     * </ul>
     */
    public static final ModConfigSpec.BooleanValue ENABLE_STRESS_OUTPUT;

    /**
     * 开发期：是否允许空手右键手动激活/停用应力 IO 方块。
     * <ul>
     *     <li>{@code true}（默认，开发期）：保留右键手动切换，便于测试人员激活。</li>
     *     <li>{@code false}（生产）：仅由本模组评估流程自动激活，右键无效。</li>
     * </ul>
     */
    public static final ModConfigSpec.BooleanValue DEV_MANUAL_ACTIVATION;

    /**
     * 应力输出损耗系数（0~1）。应力拓展方块对外输出应力时的损耗：
     * 实际输出应力 = 工厂可提供应力 ×(1 - stressLossFactor)。
     */
    public static final ModConfigSpec.DoubleValue STRESS_LOSS_FACTOR;
    public static final ModConfigSpec.BooleanValue ENABLE_INVENTORY_AUDIT;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> SUSPICIOUS_MODS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> SUSPICIOUS_BLOCKS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("CreateCMPOR 通用配置").push("stress");
        ENABLE_STRESS_OUTPUT = builder
                .comment(
                        "是否允许应力 IO 方块向外提供应力。",
                        "true：应力 > 0 时向外提供（输出模式），同时处理 < 0 的输入。",
                        "false：禁止输出，仅在应力 < 0 时从外部吸取，绝不向外提供。")
                .define("enableStressOutput", true);
        DEV_MANUAL_ACTIVATION = builder
                .comment(
                        "开发期：是否允许空手右键手动激活/停用应力 IO 方块。",
                        "true（默认，开发期）：保留右键手动切换，便于测试。",
                        "false（生产）：仅由本模组评估流程自动激活，右键无效。")
                .define("devManualActivation", false);
        STRESS_LOSS_FACTOR = builder
                .comment(
                        "应力输出损耗系数（0~1，默认 0.1）。",
                        "应力拓展方块对外输出应力时：实际输出 = 工厂可提供应力 ×(1 - stressLossFactor)。")
                .defineInRange("stressLossFactor", 0.1, 0.0, 1.0);
        builder.pop();

        builder.comment("评估安全配置").push("evaluation");
        ENABLE_INVENTORY_AUDIT = builder
                .comment("是否启用评估前后库存守恒审计。")
                .define("enableInventoryAudit", true);
        SUSPICIOUS_MODS = builder
                .comment("包含不可审计存储方块的模组 ID。")
                .defineListAllowEmpty("suspiciousMods", List.of("ae2", "refinedstorage"), value -> value instanceof String);
        SUSPICIOUS_BLOCKS = builder
                .comment("明确禁止进入评估的方块 ID。")
                .defineListAllowEmpty("suspiciousBlocks", List.of(), value -> value instanceof String);
        builder.pop();

        SPEC = builder.build();
    }
}
