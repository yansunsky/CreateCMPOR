package com.yansunsky.createcmpor.ponder;

import com.yansunsky.createcmpor.block.FactoryBlock;
import com.yansunsky.createcmpor.init.ModItems;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;

import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
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
 *   <li>baoke1~3：包壳三形态（展示形态 / 安山机壳 / 边框玻璃）</li>
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

    // ------------------------------------------------------------------
    // 包壳（0.4.26）：结构 baoke1/2/3 依次对应三种外壳形态
    //   encased=false, glass=false = 展示形态（默认，自带玻璃罩）
    //   encased=true               = 传统安山机壳形态（六面可扳手开传动轴面）
    //   encased=false, glass=true  = 边框玻璃包壳后：撤掉自带罩子，只剩底座
    // 形态对应的模型元素数（实测）：17 / 24 / 5（见 blockstates/factory_block.json）。
    // ------------------------------------------------------------------

    /** 安山机壳（create:andesite_casing）物品堆。 */
    private static ItemStack andesiteCasing() {
        return new ItemStack(com.simibubi.create.AllBlocks.ANDESITE_CASING.get());
    }

    /**
     * 边框玻璃（create:framed_glass）物品堆。
     *
     * <p>Create 的 {@code AllBlocks} 里没有 framed_glass 常量（只有 FRAMED_GLASS_DOOR/TRAPDOOR），
     * 按注册名取。
     */
    private static ItemStack framedGlass() {
        return new ItemStack(BuiltInRegistries.ITEM.get(
                ResourceLocation.fromNamespaceAndPath("create", "framed_glass")));
    }

    /** 扳手物品堆。 */
    private static ItemStack wrench() {
        return com.simibubi.create.AllItems.WRENCH.asStack();
    }

    /** 包壳情境 1（baoke1 = 默认展示形态）：安山机壳 → 边框玻璃 → 潜行扳手逐级退回。 */
    public static void casingApply(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("factory_casing_apply", "工厂包壳：三种外壳形态");
        scene.scaleSceneView(0.6f);
        scene.showBasePlate();
        scene.idle(5);
        scene.world().showSection(util.select().everywhere(), Direction.UP);
        scene.idle(20);

        BlockPos factoryPos = util.grid().at(2, 1, 2);
        scene.overlay().showText(90)
                .attachKeyFrame()
                .text("默认是展示形态：3px 底座 + 四角立柱 + 顶部横梁 + 四面玻璃，能直接看到里面的微缩产线。")
                .pointAt(util.vector().topOf(factoryPos))
                .placeNearTarget();
        scene.idle(110);

        // ① 安山机壳 → 传统机壳形态
        scene.overlay().showControls(util.vector().blockSurface(factoryPos, Direction.NORTH),
                Pointing.RIGHT, 40).rightClick().withItem(andesiteCasing());
        scene.idle(10);
        scene.world().modifyBlock(factoryPos,
                state -> state.setValue(FactoryBlock.ENCASED, true), true);
        scene.idle(10);
        scene.overlay().showText(90)
                .attachKeyFrame()
                .text("手持 Create 的安山机壳右键：切成传统机壳外形，六个面都能用扳手开传动轴面接应力（机壳不消耗）。")
                .pointAt(util.vector().topOf(factoryPos))
                .placeNearTarget();
        scene.idle(110);

        // ② 边框玻璃 → 撤掉罩子
        scene.overlay().showControls(util.vector().blockSurface(factoryPos, Direction.NORTH),
                Pointing.RIGHT, 40).rightClick().withItem(framedGlass());
        scene.idle(10);
        scene.world().modifyBlock(factoryPos, state -> state.setValue(FactoryBlock.GLASS_SHELL, true)
                .setValue(FactoryBlock.ENCASED, false), true);
        scene.idle(10);
        scene.overlay().showText(90)
                .attachKeyFrame()
                .text("手持 Create 的边框玻璃右键：连罩带框一起卸掉，只剩底座，一览无遗（同样不消耗）。")
                .pointAt(util.vector().topOf(factoryPos))
                .placeNearTarget();
        scene.idle(110);

        // ③ 潜行扳手 → 装回罩子（回展示形态）
        scene.overlay().showControls(util.vector().blockSurface(factoryPos, Direction.NORTH),
                Pointing.RIGHT, 40).rightClick().whileSneaking().withItem(wrench());
        scene.idle(10);
        scene.world().modifyBlock(factoryPos,
                state -> state.setValue(FactoryBlock.GLASS_SHELL, false), true);
        scene.idle(10);
        scene.overlay().showText(90)
                .attachKeyFrame()
                .text("潜行 + 扳手右键逐级退回：罩子 → 机壳 → 展示形态；都退完后再潜行扳手才是拆下工厂（掉落带完整数据的物品）。")
                .pointAt(util.vector().blockSurface(factoryPos, Direction.NORTH))
                .placeNearTarget();
        scene.idle(110);
        scene.markAsFinished();
    }

    /** 包壳情境 2（baoke2 = 安山机壳形态）：形态说明 + 潜行扳手脱壳。 */
    public static void casingEncased(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("factory_casing_encased", "安山机壳形态");
        scene.scaleSceneView(0.6f);
        scene.showBasePlate();
        scene.idle(5);
        scene.world().showSection(util.select().everywhere(), Direction.UP);
        scene.idle(20);

        BlockPos factoryPos = util.grid().at(2, 1, 2);
        scene.overlay().showText(90)
                .attachKeyFrame()
                .text("用安山机壳包壳后是传统工厂外形：六个面各自可以用扳手开关传动轴面，接入机械应力。")
                .pointAt(util.vector().topOf(factoryPos))
                .placeNearTarget();
        scene.idle(110);

        scene.overlay().showControls(util.vector().blockSurface(factoryPos, Direction.NORTH),
                Pointing.RIGHT, 40).rightClick().whileSneaking().withItem(wrench());
        scene.idle(10);
        scene.world().modifyBlock(factoryPos,
                state -> state.setValue(FactoryBlock.ENCASED, false), true);
        scene.idle(10);
        scene.overlay().showText(90)
                .attachKeyFrame()
                .text("潜行 + 扳手右键脱下机壳，回到展示形态——材料不消耗，两种形态可反复切换。")
                .pointAt(util.vector().blockSurface(factoryPos, Direction.NORTH))
                .placeNearTarget();
        scene.idle(110);
        scene.markAsFinished();
    }

    /** 包壳情境 3（baoke3 = 边框玻璃形态）：形态说明 + 潜行扳手装回罩子。 */
    public static void casingGlass(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("factory_casing_glass", "边框玻璃形态");
        scene.scaleSceneView(0.6f);
        scene.showBasePlate();
        scene.idle(5);
        scene.world().showSection(util.select().everywhere(), Direction.UP);
        scene.idle(20);

        BlockPos factoryPos = util.grid().at(2, 1, 2);
        scene.overlay().showText(90)
                .attachKeyFrame()
                .text("用边框玻璃包壳后：自带玻璃罩被卸掉，只剩 3px 底座，微缩产线一览无遗。")
                .pointAt(util.vector().topOf(factoryPos))
                .placeNearTarget();
        scene.idle(110);

        scene.overlay().showControls(util.vector().blockSurface(factoryPos, Direction.NORTH),
                Pointing.RIGHT, 40).rightClick().whileSneaking().withItem(wrench());
        scene.idle(10);
        scene.world().modifyBlock(factoryPos,
                state -> state.setValue(FactoryBlock.GLASS_SHELL, false), true);
        scene.idle(10);
        scene.overlay().showText(90)
                .attachKeyFrame()
                .text("潜行 + 扳手右键把罩子装回来，回到展示形态（同样不消耗材料）。")
                .pointAt(util.vector().blockSurface(factoryPos, Direction.NORTH))
                .placeNearTarget();
        scene.idle(110);
        scene.markAsFinished();
    }
}
