package com.createcmpor;

import net.neoforged.neoforge.common.ModConfigSpec;

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
     *     <li>{@code false}（生产）：仅由 CMPOR 评估自动激活，右键无效。</li>
     * </ul>
     */
    public static final ModConfigSpec.BooleanValue DEV_MANUAL_ACTIVATION;

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
                        "false（生产）：仅由 CMPOR 评估自动激活，右键无效。")
                .define("devManualActivation", true);
        builder.pop();

        SPEC = builder.build();
    }
}
