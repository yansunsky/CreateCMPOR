package com.yansunsky.createcmpor;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/**
 * 模组配置（COMMON）。
 * Mod configuration (COMMON).
 */
public class Config {

    public static final ModConfigSpec SPEC;

    /**
     * 是否允许应力 IO 方块向外提供应力。
     * Whether stress I/O blocks are allowed to provide stress outward.
     * <ul>
     *     <li>{@code true}（默认）：应力大于 0 时向外提供（输出模式），同时也处理小于 0 的输入情况。</li>
     *     <li>{@code false}：禁止输出，仅在应力小于 0 时从外部吸取应力，绝不向外提供。</li>
     * </ul>
     */
    public static final ModConfigSpec.BooleanValue ENABLE_STRESS_OUTPUT;

    /**
     * 开发期：是否允许空手右键手动激活/停用应力 IO 方块。
     * Dev-only: allow empty-hand right-click to manually toggle stress I/O blocks.
     * <ul>
     *     <li>{@code true}（开发期）：保留右键手动切换，便于测试人员激活。</li>
     *     <li>{@code false}（默认，生产）：仅由本模组评估流程自动激活，右键无效。</li>
     * </ul>
     */
    public static final ModConfigSpec.BooleanValue DEV_MANUAL_ACTIVATION;

    /**
     * 应力输出损耗系数（0~1）。应力拓展方块对外输出应力时的损耗：
     * 实际输出应力 = 工厂可提供应力 ×(1 - stressLossFactor)。
     * Stress output loss factor (0~1). Loss when the stress extension block outputs stress:
     * actual output = factory available stress ×(1 - stressLossFactor).
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
    public static final ModConfigSpec.BooleanValue ENABLE_FACTORY_REVERT;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment(
                "CreateCMPOR 通用配置",
                "CreateCMPOR General Configuration").push("stress");
        ENABLE_STRESS_OUTPUT = builder
                .comment(
                        "是否允许应力 IO 方块向外提供应力。",
                        "Whether stress I/O blocks are allowed to provide stress outward.",
                        "true：应力 > 0 时向外提供（输出模式），同时处理 < 0 的输入。",
                        "true: provide stress outward when stress > 0 (output mode), also handle input when < 0.",
                        "false：禁止输出，仅在应力 < 0 时从外部吸取，绝不向外提供。",
                        "false: never provide outward, only draw from outside when stress < 0.")
                .define("enableStressOutput", true);
        DEV_MANUAL_ACTIVATION = builder
                .comment(
                        "开发期：是否允许空手右键手动激活/停用应力 IO 方块。",
                        "Dev-only: allow empty-hand right-click to manually toggle stress I/O blocks.",
                        "true（开发期）：保留右键手动切换，便于测试。",
                        "true (dev): keep right-click manual toggle for testing.",
                        "false（默认，生产）：仅由本模组评估流程自动激活，右键无效。",
                        "false (default, production): activated only by the evaluation flow; right-click does nothing.")
                .define("devManualActivation", false);
        STRESS_LOSS_FACTOR = builder
                .comment(
                        "应力输出损耗系数（0~1，默认 0.1）。",
                        "Stress output loss factor (0~1, default 0.1).",
                        "应力拓展方块对外输出应力时：实际输出 = 工厂可提供应力 ×(1 - stressLossFactor)。",
                        "When the stress extension block outputs stress: actual = factory available ×(1 - stressLossFactor).")
                .defineInRange("stressLossFactor", 0.1, 0.0, 1.0);
        builder.pop();

        builder.comment(
                "评估安全配置",
                "Evaluation Security Configuration").push("evaluation");
        ENABLE_INVENTORY_AUDIT = builder
                .comment(
                        "是否启用评估前后库存守恒审计。",
                        "Enable inventory conservation audit before/after evaluation.")
                .define("enableInventoryAudit", true);
        SUSPICIOUS_MODS = builder
                .comment(
                        "包含不可审计存储方块的模组 ID。",
                        "Mod IDs whose storage blocks cannot be audited.")
                .defineListAllowEmpty("suspiciousMods", List.of("ae2", "refinedstorage"), value -> value instanceof String);
        SUSPICIOUS_BLOCKS = builder
                .comment(
                        "明确禁止进入评估的方块 ID。",
                        "Block IDs explicitly forbidden from entering evaluation.")
                .defineListAllowEmpty("suspiciousBlocks", List.of(), value -> value instanceof String);
        SUSPICIOUS_ITEMS = builder
                .comment(
                        "明确禁止进入评估的物品 ID（Phase 5 检查掉落物与 contraption 携带物）。",
                        "Item IDs explicitly forbidden from entering evaluation (Phase 5 checks dropped items and contraption cargo).")
                .defineListAllowEmpty("suspiciousItems", List.of(), value -> value instanceof String);
        MAX_CONCURRENT_EVALUATIONS = builder
                .comment(
                        "同时进行区块副本克隆的评估会话上限；超出后按创建顺序排队。",
                        "Max concurrent evaluation sessions cloning replica chunks; extra ones queue in creation order.")
                .defineInRange("maxConcurrentEvaluations", 4, 1, 16);
        EVALUATE_SECONDS = builder
                .comment(
                        "评估时长（秒）。",
                        "Evaluation duration (seconds).")
                .defineInRange("evaluateSeconds", 60, 1, 3600);
        RECORD_START = builder
                .comment(
                        "评估开始时的预热秒数（该时段不计入结果）。",
                        "Warmup seconds at evaluation start (not counted in the result).")
                .defineInRange("recordStart", 60, 0, 600);
        EVALUATION_MODE = builder
                .comment(
                        "评估模式：AUTO 自动判定，FORCE_RATE 强制速率拟合，FORCE_REPLAY 强制录制回放。",
                        "Evaluation mode: AUTO auto-decide, FORCE_RATE force rate fitting, FORCE_REPLAY force recorded playback.")
                .defineListAllowEmpty("evaluationMode", List.of("AUTO"), value -> value instanceof String);
        LOSS_RATE = builder
                .comment(
                        "产出损耗护栏（0-1）：回放/速率结果乘以该系数，默认 0.95 即扣 5%。",
                        "Output loss guardrail (0-1): replay/rate results are multiplied by this factor; default 0.95 = 5% deduction.")
                .defineInRange("lossRate", 0.95, 0.0, 1.0);
        INTERMEDIATE_RATIO = builder
                .comment(
                        "中间产物过滤比例：总量低于 评估秒数×该比例 的条目视为中间产物。",
                        "Intermediate product filter ratio: entries below (evaluation seconds × this ratio) are treated as intermediates.")
                .defineInRange("intermediateRatio", 0.25, 0.0, 1.0);
        IO_ERROR_RATIO = builder
                .comment(
                        "动态噪声阈值比例：|净产出| < max(产出,消耗)×该比例 视为噪声。",
                        "Dynamic noise threshold ratio: |net output| < max(output, consumption) × this ratio is treated as noise.")
                .defineInRange("ioErrorRatio", 0.001, 0.0, 1.0);
        CATALYST_ITEMS = builder
                .comment(
                        "催化剂保留清单：这些物品即使量小也保留为输入（不按中间产物删除）。",
                        "Catalyst keep-list: these items are kept as inputs even in small amounts (not removed as intermediates).")
                .defineListAllowEmpty("catalystItems", List.of(), value -> value instanceof String);
        DEDUP_BLOCKS = builder
                .comment(
                        "库存扫描需要去重的方块 ID（多视图容器防重复计数）。",
                        "Block IDs that need de-duplication during inventory scan (prevent double counting for multi-view containers).",
                        "Storage Drawers 压缩抽屉会由代码自动识别，此列表可补充其他模组方块。",
                        "Storage Drawers compacting drawers are auto-detected; add other mod blocks here.")
                .defineListAllowEmpty("dedupBlocks",
                        List.of(
                                "storagedrawers:compacting_drawers_2",
                                "storagedrawers:compacting_drawers_3",
                                "storagedrawers:compacting_half_drawers_2",
                                "storagedrawers:compacting_half_drawers_3",
                                "storagedrawers:framed_compacting_drawers_2",
                                "storagedrawers:framed_compacting_drawers_3",
                                "storagedrawers:framed_compacting_half_drawers_2",
                                "storagedrawers:framed_compacting_half_drawers_3"),
                        value -> value instanceof String);
        ENERGY_STABILITY_RELAXATION = builder
                .comment(
                        "能量条目的稳定性容差放宽倍数（能量网络波动大，避免误判为不稳定）。",
                        "Stability tolerance relaxation multiplier for energy entries (energy networks fluctuate; avoid false instability).")
                .defineInRange("energyStabilityRelaxation", 2.0, 1.0, 10.0);
        STRESS_STABILITY_RELAXATION = builder
                .comment(
                        "应力条目的稳定性容差放宽倍数。",
                        "Stability tolerance relaxation multiplier for stress entries.")
                .defineInRange("stressStabilityRelaxation", 2.0, 1.0, 10.0);
        ENABLE_FACTORY_REVERT = builder
                .comment(
                        "是否允许启动棒把工厂还原为原 CompactMachines 机器（新版无库存复制风险，默认开启）。",
                        "Allow the launcher stick to revert the factory back to the original CompactMachines machine (no item duplication risk in the new version; enabled by default).")
                .define("enableFactoryRevert", true);
        builder.pop();

        SPEC = builder.build();
    }
}
