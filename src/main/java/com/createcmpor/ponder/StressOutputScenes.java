package com.createcmpor.ponder;

import com.createcmpor.block.StressOutputBlock;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * 应力输出方块（createcmpor:stress_output）的 Ponder 场景动画。
 *
 * <p>场景 nbt：{@code assets/createcmpor/ponder/stress_output/stress_output.nbt}（7x3x7 空间），
 * 内容为一台 createcmpor:stress_output 应力输出方块（facing=east，初始 powered=false 未激活），
 * 东侧依次连接 create:stressometer（应力计）、create:speedometer（转速表）、create:motor（马达）。</p>
 *
 * <p>本场景演示激活流程：未激活 → 文本提示 → 方块激活（powered=true）→ 马达转动、仪表读数。</p>
 *
 * <p>注：使用 Create 的 {@link CreateSceneBuilder}（Create 是编译期依赖）以获得
 * {@code setKineticSpeed} / {@code rotationDirectionIndicator} 等 Create 专属动画指令；
 * 文本经 {@code .text()} 传英文默认，实际翻译读 lang key（createcmpor.ponder.stress_output.text_N）。</p>
 */
public final class StressOutputScenes {

    private StressOutputScenes() {
    }

    public static void stressOutputBasics(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("stress_output", "应力输出方块");
        scene.scaleSceneView(0.7f);
        scene.showBasePlate();
        scene.idle(5);

        // 应力输出方块位于 (2,1,3)，马达位于 (5,1,3)
        BlockPos outputPos = util.grid().at(2, 1, 3);
        BlockPos motorPos = util.grid().at(5, 1, 3);

        // 1. 显示整个场景（初始 powered=false 未激活）
        scene.world().showSection(util.select().everywhere(), Direction.UP);
        scene.idle(20);

        // 2. 文字：应力输出方块是观察者
        scene.overlay().showText(70)
                .attachKeyFrame()
                .text("The Stress Output block is a passive observer of the connected stress network.")
                .pointAt(util.vector().topOf(outputPos))
                .placeNearTarget();
        scene.idle(70);

        // 3. 文字：测量网络可提供的应力供工厂输出
        scene.overlay().showText(70)
                .attachKeyFrame()
                .text("It records how much stress the network can supply for the factory's output.")
                .pointAt(util.vector().topOf(outputPos))
                .placeNearTarget();
        scene.idle(70);

        // 4. 高亮输出方块，提示即将激活
        scene.overlay().showOutline(PonderPalette.GREEN, outputPos,
                util.select().position(outputPos), 40);
        scene.idle(40);

        // 5. 激活方块：powered false → true
        scene.world().modifyBlock(outputPos,
                state -> state.setValue(StressOutputBlock.ACTIVE, true), true);
        scene.effects().indicateRedstone(outputPos);
        scene.idle(20);

        // 6. 马达转动，仪表读数
        scene.world().setKineticSpeed(util.select().position(motorPos), 32);
        scene.effects().rotationDirectionIndicator(motorPos);
        scene.idle(20);

        // 7. 文字：激活后观测网络
        scene.overlay().showText(70)
                .attachKeyFrame()
                .text("Once activated it observes the network and records the stress that the factory can supply.")
                .pointAt(util.vector().centerOf(motorPos))
                .placeNearTarget();
        scene.idle(70);

        scene.markAsFinished();
    }
}