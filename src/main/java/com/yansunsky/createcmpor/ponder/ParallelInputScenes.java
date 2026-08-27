package com.yansunsky.createcmpor.ponder;

import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * 并行空间输入方块（createcmpor:parallel_input_block）的 Ponder 场景动画。
 *
 * <p>使用 4 个结构（parallel_input_block1~4.nbt，均为 7x7x7 空间）按顺序演示
 * 「配置多种物品 → 同一套产线逐物品分支评估 → 评估过程 → 生成同等数量工厂」：
 * <ol>
 *   <li><b>parallel_input_block1</b>：认识方块 + 配置白名单（raw_iron/raw_copper/raw_gold 三种物品，
 *       方块在 (3,5,3)，黄铜漏斗在 (3,4,3) 向下）。</li>
 *   <li><b>parallel_input_block2</b>：完整粉碎轮产线——原料经顶部输入方块 → 溜槽 → 粉碎轮 → 产物收集，
 *       同一套产线按白名单物品逐一评估（每次只暴露一种物品）。</li>
 *   <li><b>parallel_input_block3</b>：评估过程——启动棒右键 Compact Machines 机器，房间冻结评估。</li>
 *   <li><b>parallel_input_block4</b>：评估完成——垂直堆叠生成 N 个工厂（场景中 3 个），
 *       每个工厂对应一种白名单物品。</li>
 * </ol>
 *
 * <p>文本经 {@code .text()} 传英文默认，实际翻译读 lang key（createcmpor.ponder.parallel_input.text_N）。</p>
 */
public final class ParallelInputScenes {

    private ParallelInputScenes() {
    }

    /** 情境 1：认识并行空间输入方块（配置多种物品）。 */
    public static void configure(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("parallel_input_configure", "并行空间输入方块");
        scene.scaleSceneView(0.7f);
        scene.showBasePlate();
        scene.idle(5);
        scene.world().showSection(util.select().everywhere(), Direction.UP);
        scene.idle(20);

        // 并行空间输入方块 (3,5,3)，黄铜漏斗 (3,4,3)（向下）
        BlockPos blockPos = util.grid().at(3, 5, 3);
        BlockPos funnelPos = util.grid().at(3, 4, 3);

        // 1. 介绍方块
        scene.overlay().showText(80)
                .attachKeyFrame()
                .text("The Parallel Space Input block evaluates multiple items with a single evaluation.")
                .pointAt(util.vector().topOf(blockPos))
                .placeNearTarget();
        scene.idle(80);

        // 2. 配置白名单：与输入方块相同，用物品/流体容器右键
        scene.overlay().showText(80)
                .attachKeyFrame()
                .text("Right-click with items or buckets to configure its whitelist, just like the Input block.")
                .pointAt(util.vector().centerOf(blockPos))
                .placeNearTarget();
        scene.idle(80);

        // 3. 高亮白名单物品（raw_iron / raw_copper / raw_gold 三种），说明一次评估对应多种物品
        scene.overlay().showOutline(PonderPalette.GREEN, blockPos,
                util.select().position(blockPos), 60);
        scene.idle(30);
        scene.overlay().showText(90)
                .attachKeyFrame()
                .text("Each whitelisted item becomes one evaluation branch — e.g. raw iron, raw copper and raw gold.")
                .pointAt(util.vector().centerOf(funnelPos))
                .placeNearTarget();
        scene.idle(90);
        scene.markAsFinished();
    }

    /** 情境 2：同一套产线逐物品分支评估（复用产线）。 */
    public static void evaluateLine(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("parallel_input_evaluate", "逐物品分支评估");
        scene.scaleSceneView(0.7f);
        scene.showBasePlate();
        scene.idle(5);
        scene.world().showSection(util.select().everywhere(), Direction.UP);
        scene.idle(20);

        // 产线布局：parallel_input_block (3,5,3) → chute (3,4,3) → crushing_wheel 组 (2,3,3)(4,3,3) + controller (3,3,3)
        // → chute (3,2,3)；creative_motor (2,3,4)(4,3,4)
        BlockPos inputPos = util.grid().at(3, 5, 3);
        BlockPos topChute = util.grid().at(3, 4, 3);
        BlockPos wheelLeft = util.grid().at(2, 3, 3);
        BlockPos wheelRight = util.grid().at(4, 3, 3);
        BlockPos bottomChute = util.grid().at(3, 2, 3);

        // 1. 整条产线
        scene.overlay().showText(80)
                .attachKeyFrame()
                .text("Build one production line inside the room — here, a crushing wheel setup.")
                .pointAt(util.vector().centerOf(wheelLeft))
                .placeNearTarget();
        scene.idle(80);

        // 2. 原料从顶部输入方块流入
        scene.overlay().showOutline(PonderPalette.GREEN, inputPos,
                util.select().position(inputPos), 40);
        scene.idle(10);
        scene.overlay().showOutline(PonderPalette.BLUE, topChute,
                util.select().position(topChute), 40);
        scene.idle(10);
        scene.overlay().showText(90)
                .attachKeyFrame()
                .text("During evaluation, only ONE whitelisted item is exposed at a time and fed into the same line.")
                .pointAt(util.vector().centerOf(topChute))
                .placeNearTarget();
        scene.idle(90);

        // 3. 粉碎轮处理 + 产物从底部溜槽流出
        scene.world().setKineticSpeed(util.select().position(wheelLeft), 32);
        scene.world().setKineticSpeed(util.select().position(wheelRight), 32);
        scene.effects().rotationDirectionIndicator(wheelLeft);
        scene.effects().rotationDirectionIndicator(wheelRight);
        scene.idle(20);
        scene.overlay().showText(90)
                .attachKeyFrame()
                .text("The line runs once per item — the same production line is reused for every branch.")
                .pointAt(util.vector().centerOf(bottomChute))
                .placeNearTarget();
        scene.idle(90);
        scene.markAsFinished();
    }

    /** 情境 3：评估过程——启动棒右键机器。 */
    public static void evaluating(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("parallel_input_evaluating", "开始评估");
        scene.scaleSceneView(0.7f);
        scene.showBasePlate();
        scene.idle(5);
        scene.world().showSection(util.select().everywhere(), Direction.UP);
        scene.idle(20);

        // Compact Machines 机器 (3,1,3)
        BlockPos machinePos = util.grid().at(3, 1, 3);

        scene.overlay().showText(80)
                .attachKeyFrame()
                .text("Right-click the Compact Machines room machine with the Launcher Stick to start.")
                .pointAt(util.vector().topOf(machinePos))
                .placeNearTarget();
        scene.idle(80);

        scene.overlay().showControls(util.vector().topOf(machinePos),
                net.createmod.catnip.math.Pointing.DOWN, 40)
                .rightClick().withItem(new net.minecraft.world.item.ItemStack(
                        com.yansunsky.createcmpor.init.ModItems.LAUNCHER_STICK.get()));
        scene.idle(10);
        scene.overlay().showText(90)
                .attachKeyFrame()
                .text("The room freezes and each branch is evaluated one after another on the cloned replica.")
                .pointAt(util.vector().topOf(machinePos))
                .placeNearTarget();
        scene.idle(90);
        scene.markAsFinished();
    }

    /** 情境 4：评估完成——垂直堆叠生成同等数量工厂。 */
    public static void solidified(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("parallel_input_solidified", "生成多个工厂");
        scene.scaleSceneView(0.7f);
        scene.showBasePlate();
        scene.idle(5);
        scene.world().showSection(util.select().everywhere(), Direction.UP);
        scene.idle(20);

        // 3 个工厂垂直堆叠 (3,1,3)(3,2,3)(3,3,3)
        BlockPos factoryBottom = util.grid().at(3, 1, 3);
        BlockPos factoryMid = util.grid().at(3, 2, 3);
        BlockPos factoryTop = util.grid().at(3, 3, 3);

        scene.overlay().showText(80)
                .attachKeyFrame()
                .text("Once all branches finish, one Factory block is solidified per whitelisted item.")
                .pointAt(util.vector().centerOf(factoryMid))
                .placeNearTarget();
        scene.idle(80);

        // 逐个高亮三个工厂
        scene.overlay().showOutline(PonderPalette.GREEN, factoryBottom,
                util.select().position(factoryBottom), 30);
        scene.idle(15);
        scene.overlay().showOutline(PonderPalette.GREEN, factoryMid,
                util.select().position(factoryMid), 30);
        scene.idle(15);
        scene.overlay().showOutline(PonderPalette.GREEN, factoryTop,
                util.select().position(factoryTop), 30);
        scene.idle(15);
        scene.overlay().showText(90)
                .attachKeyFrame()
                .text("The factories are stacked vertically above the machine — N items, N factories.")
                .pointAt(util.vector().topOf(factoryTop))
                .placeNearTarget();
        scene.idle(90);
        scene.markAsFinished();
    }
}
