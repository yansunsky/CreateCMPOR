package com.createcmpor.ponder;

import com.createcmpor.block.BaseIOBlock;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * 输入方块（createcmpor:input_block）的 Ponder 场景动画。
 *
 * <p>场景 nbt：{@code assets/createcmpor/ponder/input_block/input_block.nbt}（7x7x7 空间），
 * 内容为 createcmpor:input_block（输入方块）在上、create:smart_chute（智能溜槽）在下的垂直结构。</p>
 *
 * <p>本场景演示：输入方块是房间的物品/流体入口，评估时激活；白名单中的物品从外部流入，
 * 经智能溜槽送往内部机器。流程：未激活 → 文本 → 激活 → 物品经溜槽流动。</p>
 */
public final class InputBlockScenes {

    private InputBlockScenes() {
    }

    public static void inputBlockBasics(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("input_block", "输入方块");
        // 场景空间为 7x7x7，放大视图以完整展示垂直堆叠
        scene.scaleSceneView(0.7f);
        scene.showBasePlate();
        scene.idle(5);

        // 输入方块位于 (3,3,3)，智能溜槽位于 (3,2,3)
        BlockPos inputPos = util.grid().at(3, 3, 3);
        BlockPos chutePos = util.grid().at(3, 2, 3);

        // 1. 显示整个场景
        scene.world().showSection(util.select().everywhere(), Direction.UP);
        scene.idle(20);

        // 2. 文字：输入方块是房间的物品入口
        scene.overlay().showText(70)
                .attachKeyFrame()
                .text("The Input block is where items and fluids enter the room from outside.")
                .pointAt(util.vector().topOf(inputPos))
                .placeNearTarget();
        scene.idle(70);

        // 3. 文字：白名单 + 评估时激活
        scene.overlay().showText(70)
                .attachKeyFrame()
                .text("Right-click with an item or bucket to set the whitelist; it activates during evaluation.")
                .pointAt(util.vector().centerOf(inputPos))
                .placeNearTarget();
        scene.idle(70);

        // 4. 高亮输入方块，提示即将激活
        scene.overlay().showOutline(PonderPalette.GREEN, inputPos,
                util.select().position(inputPos), 40);
        scene.idle(40);

        // 5. 激活方块：active false → true（BaseIOBlock.ACTIVE）
        scene.world().modifyBlock(inputPos,
                state -> state.setValue(BaseIOBlock.ACTIVE, true), true);
        scene.effects().indicateRedstone(inputPos);
        scene.idle(20);

        // 6. 高亮智能溜槽，说明物品流向
        scene.overlay().showOutline(PonderPalette.BLUE, chutePos,
                util.select().position(chutePos), 30);
        scene.idle(20);

        // 7. 文字：激活后白名单物品经溜槽送往内部机器
        scene.overlay().showText(70)
                .attachKeyFrame()
                .text("Once activated, whitelisted items flow down through the chute to the machines inside.")
                .pointAt(util.vector().centerOf(chutePos))
                .placeNearTarget();
        scene.idle(70);

        scene.markAsFinished();
    }
}
