package com.yansunsky.createcmpor.ponder;

import com.yansunsky.createcmpor.block.StressInputBlock;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * 应力输入方块（createcmpor:stress_input）的 Ponder 场景动画。
 *
 * <p>场景 nbt：{@code assets/createcmpor/ponder/stress_input/stress_input.nbt}（7x3x7 空间），
 * 内容为一台 createcmpor:stress_input 应力输入方块（facing=east，初始 powered=false 未激活），
 * 正东侧连接一台 create:encased_fan 机械风扇。</p>
 *
 * <p>本场景演示激活流程：未激活 → 文本提示 → 方块激活（powered=true）→ 风扇获得 32 RPM 开始旋转。</p>
 *
 * <p>注：使用 Create 的 {@link CreateSceneBuilder}（Create 是编译期依赖）以获得
 * {@code setKineticSpeed} / {@code rotationDirectionIndicator} 等 Create 专属动画指令；
 * 文本经 {@code .text()} 传英文默认，实际翻译读 lang key（createcmpor.ponder.stress_input.text_N）。</p>
 */
public final class StressInputScenes {

    private StressInputScenes() {
    }

    public static void stressInputBasics(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("stress_input", "应力输入方块");
        // 场景空间为 7x3x7，缩小视图以完整展示
        scene.scaleSceneView(0.7f);
        scene.showBasePlate();
        scene.idle(5);

        // 应力输入方块位于 (2,1,3)，机械风扇位于 (3,1,3)
        BlockPos inputPos = util.grid().at(2, 1, 3);
        BlockPos fanPos = util.grid().at(3, 1, 3);

        // 1. 显示整个场景（初始 powered=false 未激活）
        scene.world().showSection(util.select().everywhere(), Direction.UP);
        scene.idle(20);

        // 2. 文字提示：方块用于接入外部应力
        scene.overlay().showText(70)
                .attachKeyFrame()
                .text("The Stress Input block brings mechanical stress from outside into the room.")
                .pointAt(util.vector().topOf(inputPos))
                .placeNearTarget();
        scene.idle(70);

        // 3. 文字提示：评估时会自动激活
        scene.overlay().showText(70)
                .attachKeyFrame()
                .text("IO blocks are activated during evaluation to provide items or stress.")
                .pointAt(util.vector().topOf(inputPos))
                .placeNearTarget();
        scene.idle(70);

        // 4. 高亮应力输入方块，提示即将激活
        scene.overlay().showOutline(PonderPalette.GREEN, inputPos,
                util.select().position(inputPos), 40);
        scene.idle(40);

        // 5. 激活方块：powered false → true（ACTIVE = BlockStateProperties.POWERED）
        scene.world().modifyBlock(inputPos,
                state -> state.setValue(StressInputBlock.ACTIVE, true), true);
        scene.effects().indicateRedstone(inputPos);
        scene.idle(20);

        // 6. 连接的风扇获得 32 RPM 开始旋转 + 旋转方向指示（CreateSceneBuilder 内部类扩展）
        scene.world().setKineticSpeed(util.select().position(fanPos), 32);
        scene.effects().rotationDirectionIndicator(inputPos);
        scene.effects().rotationDirectionIndicator(fanPos);
        scene.idle(20);

        // 7. 文字说明激活后提供应力驱动内部机器
        scene.overlay().showText(70)
                .attachKeyFrame()
                .text("Once activated it acts as a stress source, providing rotation to connected machinery.")
                .pointAt(util.vector().centerOf(fanPos))
                .placeNearTarget();
        scene.idle(70);

        scene.markAsFinished();
    }
}
