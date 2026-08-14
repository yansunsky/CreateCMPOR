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
    public static final ModConfigSpec.ConfigValue<List<? extends String>> SUSPICIOUS_ITEMS;
    public static final ModConfigSpec.IntValue MAX_CONCURRENT_EVALUATIONS;
    public static final ModConfigSpec.IntValue EVALUATE_SECONDS;
    public static final ModConfigSpec.IntValue RECORD_START;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> EVALUATION_MODE;
    public static final ModConfigSpec.DoubleValue LOSS_RATE;
    public static final ModConfigSpec.DoubleValue INTERMEDIATE_RATIO;
    public static final ModConfigSpec.DoubleValue IO_ERROR_RATIO;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> CATALYST_ITEMS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> DEDUP_BLOCKS;
    public static final ModConfigSpec.DoubleValue ENERGY_STABILITY_RELAXATION;
    public static final ModConfigSpec.DoubleValue STRESS_STABILITY_RELAXATION;

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
        SUSPICIOUS_ITEMS = builder
                .comment("明确禁止进入评估的物品 ID（Phase 5 检查掉落物与 contraption 携带物）。")
                .defineListAllowEmpty("suspiciousItems", List.of(), value -> value instanceof String);
        MAX_CONCURRENT_EVALUATIONS = builder
                .comment("同时进行区块副本克隆的评估会话上限；超出后按创建顺序排队。")
                .defineInRange("maxConcurrentEvaluations", 4, 1, 16);
        EVALUATE_SECONDS = builder
                .comment("评估时长（秒）。开发阶段配置文件中覆盖为 60。")
                .defineInRange("evaluateSeconds", 300, 1, 3600);
        RECORD_START = builder
                .comment("评估开始时的预热秒数（该时段不计入结果）。开发阶段配置文件中覆盖为 0。")
                .defineInRange("recordStart", 60, 0, 600);
        EVALUATION_MODE = builder
                .comment("评估模式：AUTO 自动判定，FORCE_RATE 强制速率拟合，FORCE_REPLAY 强制录制回放。")
                .defineListAllowEmpty("evaluationMode", List.of("AUTO"), value -> value instanceof String);
        LOSS_RATE = builder
                .comment("产出损耗护栏（0-1）：回放/速率结果乘以该系数，默认 0.95 即扣 5%。")
                .defineInRange("lossRate", 0.95, 0.0, 1.0);
        INTERMEDIATE_RATIO = builder
                .comment("中间产物过滤比例：总量低于 评估秒数×该比例 的条目视为中间产物。")
                .defineInRange("intermediateRatio", 0.25, 0.0, 1.0);
        IO_ERROR_RATIO = builder
                .comment("动态噪声阈值比例：|净产出| < max(产出,消耗)×该比例 视为噪声。")
                .defineInRange("ioErrorRatio", 0.001, 0.0, 1.0);
        CATALYST_ITEMS = builder
                .comment("催化剂保留清单：这些物品即使量小也保留为输入（不按中间产物删除）。")
                .defineListAllowEmpty("catalystItems", List.of(), value -> value instanceof String);
        DEDUP_BLOCKS = builder
                .comment("库存扫描需要去重的方块 ID（多方块容器防重复计数）。")
                .defineListAllowEmpty("dedupBlocks",
                        List.of("storagedrawers:compacting_drawers_3", "storagedrawers:compacting_drawers_2"),
                        value -> value instanceof String);
        ENERGY_STABILITY_RELAXATION = builder
                .comment("能量条目的稳定性容差放宽倍数（能量网络波动大，避免误判为不稳定）。")
                .defineInRange("energyStabilityRelaxation", 2.0, 1.0, 10.0);
        STRESS_STABILITY_RELAXATION = builder
                .comment("应力条目的稳定性容差放宽倍数。")
                .defineInRange("stressStabilityRelaxation", 2.0, 1.0, 10.0);
        builder.pop();

        SPEC = builder.build();
    }
}
