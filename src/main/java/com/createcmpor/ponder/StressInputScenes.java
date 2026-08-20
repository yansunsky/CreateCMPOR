package com.createcmpor.ponder;

import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.Direction;

/**
 * 应力输入方块（createcmpor:stress_input）的 Ponder 场景动画。
 *
 * <p>场景 nbt：{@code assets/createcmpor/ponder/stress_input/stress_input.nbt}（7x3x7 空间），
 * 内容为一台 createcmpor:stress_input 应力输入方块朝东，正东侧连接一台 create:encased_fan 机械风扇，
 * 演示"应力输入 → 风扇旋转"。</p>
 */
public final class StressInputScenes {

    private StressInputScenes() {
    }

    public static void stressInputBasics(SceneBuilder builder, SceneBuildingUtil util) {
        // 标题（lang key：createcmpor.ponder.stress_input_basics）
        builder.title("stress_input_basics", "应力输入方块");
        // 场景空间为 7x3x7，缩小视图以完整展示
        builder.scaleSceneView(0.7f);
        builder.showBasePlate();
        builder.idle(5);

        // 显示整个场景（7x3x7 空间内的草平台 + 应力输入方块 + 机械风扇）
        builder.world().showSection(util.select().everywhere(), Direction.UP);
        builder.idle(20);
        builder.markAsFinished();
    }
}
