package com.yansunsky.createcmpor.ponder;

import com.yansunsky.createcmpor.init.ModItems;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;

/**
 * 工厂方块（createcmpor:factory_block）与评估启动棒（createcmpor:launcher_stick）的 Ponder 场景。
 *
 * <p>使用 5 个结构（machine1、machine2、machine4~6）演示完整生命周期：
 * <ol>
 *   <li>machine1：Compact Machines 机器（起点）</li>
 *   <li>machine2：启动棒右键机器 → 评估方块（冻结评估）</li>
 *   <li>machine3：评估完成 → 工厂固化（原 machine4）</li>
 *   <li>machine4：工厂运转 + 漏斗 IO + 扳手开传动轴面（原 machine5）</li>
 *   <li>machine5：启动棒右键工厂 → 还原回机器（原 machine6）</li>
 * </ol>
 * 每个 storyboard 用对应 .nbt 作为初始布局，绑定到 factory_block 与 launcher_stick。</p>
 *
 * <p>物品右键操作用 Ponder 的 {@code overlay().showControls(pos, Pointing, 时长).rightClick().withItem(启动棒)}
 * 模拟（InputElementBuilder 源码确认支持 rightClick/withItem）。</p>
 */
public final class FactoryScenes {

    private FactoryScenes() {
    }

    private static ItemStack launcher() {
        return new ItemStack(ModItems.LAUNCHER_STICK.get());
    }

    /** 情境 1：Compact Machines 机器（起点）。 */
    public static void machine(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("factory_machine", "平行房间评估：起点");
        scene.scaleSceneView(0.6f);
        scene.showBasePlate();
        scene.idle(5);
        scene.world().showSection(util.select().everywhere(), Direction.UP);
        scene.idle(20);

        BlockPos machinePos = util.grid().at(3, 3, 3);
        scene.overlay().showText(80)
                .attachKeyFrame()
                .text("Compact Machines 的房间机器是评估的起点。")
                .pointAt(util.vector().topOf(machinePos))
                .placeNearTarget();
        scene.idle(80);

        // 模拟：手持启动棒右键机器
        scene.overlay().showControls(util.vector().topOf(machinePos), Pointing.DOWN, 40)
                .rightClick().withItem(launcher());
        scene.idle(10);
        scene.overlay().showText(80)
                .attachKeyFrame()
                .text("手持评估启动棒右键机器，即可开始评估房间。")
                .pointAt(util.vector().topOf(machinePos))
                .placeNearTarget();
        scene.idle(80);
        scene.markAsFinished();
    }

    /** 情境 2：启动棒右键机器 → 评估方块（冻结评估）。 */
    public static void evaluatorStart(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("factory_evaluator_start", "冻结与评估");
        scene.scaleSceneView(0.6f);
        scene.showBasePlate();
        scene.idle(5);
        scene.world().showSection(util.select().everywhere(), Direction.UP);
        scene.idle(20);

        BlockPos evaluatorPos = util.grid().at(3, 3, 3);
        scene.overlay().showText(80)
                .attachKeyFrame()
                .text("机器变为评估方块：房间被冻结，内容克隆到评估世界。")
                .pointAt(util.vector().topOf(evaluatorPos))
                .placeNearTarget();
        scene.idle(80);
        scene.markAsFinished();
    }

    /** 情境 4：评估完成 → 工厂固化。 */
    public static void solidified(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("factory_solidified", "工厂固化");
        scene.scaleSceneView(0.6f);
        scene.showBasePlate();
        scene.idle(5);
        scene.world().showSection(util.select().everywhere(), Direction.UP);
        scene.idle(20);

        BlockPos factoryPos = util.grid().at(3, 3, 3);
        scene.overlay().showText(80)
                .attachKeyFrame()
                .text("评估完成：评估方块固化为平行工厂。")
                .pointAt(util.vector().topOf(factoryPos))
                .placeNearTarget();
        scene.idle(80);
        scene.markAsFinished();
    }

    /** 情境 5：工厂运转 + 漏斗 IO。 */
    public static void running(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("factory_running", "工厂运转");
        scene.scaleSceneView(0.6f);
        scene.showBasePlate();
        scene.idle(5);
        scene.world().showSection(util.select().everywhere(), Direction.UP);
        scene.idle(20);

        BlockPos factoryPos = util.grid().at(3, 3, 3);
        BlockPos funnelPos = util.grid().at(4, 3, 3);
        scene.overlay().showOutline(PonderPalette.GREEN, factoryPos,
                util.select().position(factoryPos), 30);
        scene.idle(10);
        scene.overlay().showOutline(PonderPalette.BLUE, funnelPos,
                util.select().position(funnelPos), 30);
        scene.idle(20);
        scene.overlay().showText(80)
                .attachKeyFrame()
                .text("工厂持续复刻产线：通过漏斗等通道输入原料、输出产物。")
                .pointAt(util.vector().centerOf(funnelPos))
                .placeNearTarget();
        scene.idle(80);

        // 用扳手右键任意一面打开传动轴面（应力接口）
        scene.overlay().showControls(util.vector().topOf(factoryPos), Pointing.DOWN, 40)
                .rightClick().withItem(com.simibubi.create.AllItems.WRENCH.asStack());
        scene.idle(10);
        scene.overlay().showText(80)
                .attachKeyFrame()
                .text("用扳手右键工厂任意一面，可打开传动轴面，接入机械应力。")
                .pointAt(util.vector().topOf(factoryPos))
                .placeNearTarget();
        scene.idle(80);
        scene.markAsFinished();
    }

    /** 情境 6：启动棒右键工厂 → 还原回机器。 */
    public static void reverted(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("factory_reverted", "还原房间机器");
        scene.scaleSceneView(0.6f);
        scene.showBasePlate();
        scene.idle(5);
        scene.world().showSection(util.select().everywhere(), Direction.UP);
        scene.idle(20);

        BlockPos machinePos = util.grid().at(3, 3, 3);
        // 文本 1 先出现并停留足够久
        scene.overlay().showText(90)
                .attachKeyFrame()
                .text("手持启动棒右键工厂，即可还原为原来的 Compact Machines 机器。")
                .pointAt(util.vector().topOf(machinePos))
                .placeNearTarget();
        scene.idle(100);

        scene.overlay().showControls(util.vector().topOf(machinePos), Pointing.DOWN, 40)
                .rightClick().withItem(launcher());
        scene.idle(10);
        // 文本 2 在操作演示之后出现，位置略微偏移避免与文本 1 叠加
        scene.overlay().showText(90)
                .attachKeyFrame()
                .independent()
                .text("还原后房间恢复原状，可再次进入或重新评估。")
                .pointAt(util.vector().blockSurface(machinePos, Direction.NORTH))
                .placeNearTarget();
        scene.idle(100);
        scene.markAsFinished();
    }
}
