package com.yansunsky.createcmpor.ponder;

import com.yansunsky.createcmpor.block.BaseIOBlock;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * 输出方块（createcmpor:output_block）的 Ponder 场景动画。
 *
 * <p>场景 nbt：{@code assets/createcmpor/ponder/output_block/output_block.nbt}（7x7x7 空间），
 * 内容为 createcmpor:output_block（输出方块）在下、create:smart_chute（智能溜槽）、
 * create:creative_crate（创造板条箱）在上的垂直结构。</p>
 *
 * <p>本场景演示：输出方块是房间的物品/流体出口，评估时激活；内部产出的物品经智能溜槽
 * 送至输出方块送出房间。流程：未激活 → 文本 → 激活 → 物品经溜槽流向输出方块。</p>
 */
public final class OutputBlockScenes {

    private OutputBlockScenes() {
    }

    public static void outputBlockBasics(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("output_block", "输出方块");
        scene.scaleSceneView(0.7f);
        scene.showBasePlate();
        scene.idle(5);

        // 输出方块位于 (3,1,3)，智能溜槽 (3,2,3)，创造板条箱 (3,3,3)
        BlockPos outputPos = util.grid().at(3, 1, 3);
        BlockPos chutePos = util.grid().at(3, 2, 3);

        // 1. 显示整个场景
        scene.world().showSection(util.select().everywhere(), Direction.UP);
        scene.idle(20);

        // 2. 文字：输出方块是房间的物品出口
        scene.overlay().showText(70)
                .attachKeyFrame()
                .text("The Output block is where items and fluids leave the room to the outside.")
                .pointAt(util.vector().topOf(outputPos))
                .placeNearTarget();
        scene.idle(70);

        // 3. 文字：白名单 + 评估时激活
        scene.overlay().showText(70)
                .attachKeyFrame()
                .text("Right-click with an item or bucket to set the whitelist; it activates during evaluation.")
                .pointAt(util.vector().centerOf(outputPos))
                .placeNearTarget();
        scene.idle(70);

        // 4. 高亮输出方块，提示即将激活
        scene.overlay().showOutline(PonderPalette.GREEN, outputPos,
                util.select().position(outputPos), 40);
        scene.idle(40);

        // 5. 激活方块：active false → true（BaseIOBlock.ACTIVE）
        scene.world().modifyBlock(outputPos,
                state -> state.setValue(BaseIOBlock.ACTIVE, true), true);
        scene.effects().indicateRedstone(outputPos);
        scene.idle(20);

        // 6. 高亮智能溜槽，说明物品来源
        scene.overlay().showOutline(PonderPalette.BLUE, chutePos,
                util.select().position(chutePos), 30);
        scene.idle(20);

        // 7. 文字：激活后白名单物品经溜槽送到输出方块送出
        scene.overlay().showText(70)
                .attachKeyFrame()
                .text("Once activated, whitelisted products from inside flow down to the Output block and leave the room.")
                .pointAt(util.vector().centerOf(chutePos))
                .placeNearTarget();
        scene.idle(70);

        scene.markAsFinished();
    }
}
