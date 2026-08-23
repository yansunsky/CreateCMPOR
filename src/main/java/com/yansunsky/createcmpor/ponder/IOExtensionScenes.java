package com.yansunsky.createcmpor.ponder;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;
import com.simibubi.create.foundation.ponder.CreateSceneBuilder;
import com.yansunsky.createcmpor.block.FactoryBlock;
import com.yansunsky.createcmpor.block.IOExtensionBlock;
import com.yansunsky.createcmpor.init.ModBlocks;

import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * IO 拓展方块（createcmpor:io_extension）的 Ponder 场景。
 *
 * <p>场景 nbt：{@code assets/createcmpor/ponder/io_extension/io_extension.nbt}（7x7x7 空间），
 * 初始布局为：工厂方块（6 面全关）@[3,4,3]，其上方 @[3,3,3] 一台智能溜槽。</p>
 *
 * <p>本场景为<b>一个场景、三个镜头</b>（3 段文字 + 实时方块变换）：
 * <ol>
 *   <li><b>6 面 IO</b>：工厂方块默认 6 个面都提供物品/流体/能量输入输出。</li>
 *   <li><b>扳手开接口</b>：用扳手打开西面传动轴接口 → 该面变为应力接口，失去输入输出能力。</li>
 *   <li><b>io_extension 拓展</b>：在接口面贴上 IO 拓展方块（把溜槽移到其外侧），
 *       即可从拓展方块的其它面继续输入输出。</li>
 * </ol>
 */
public final class IOExtensionScenes {

    private IOExtensionScenes() {
    }

    public static void ioExtensionBasics(SceneBuilder builder, SceneBuildingUtil util) {
        CreateSceneBuilder scene = new CreateSceneBuilder(builder);
        scene.title("io_extension", "IO 拓展方块");
        scene.scaleSceneView(0.65f);
        scene.showBasePlate();
        scene.idle(5);

        // 场景空间 7x7x7：工厂 @[3,4,3]，初始溜槽 @[3,3,3]（工厂上方）
        BlockPos factoryPos = util.grid().at(3, 4, 3);
        BlockPos chuteTopPos = util.grid().at(3, 3, 3);

        scene.world().showSection(util.select().everywhere(), Direction.UP);
        scene.idle(20);

        // ===== 镜头 1：工厂 6 面默认都提供 IO =====
        scene.overlay().showOutline(PonderPalette.GREEN, factoryPos,
                util.select().position(factoryPos), 30);
        scene.idle(10);
        scene.overlay().showText(80)
                .attachKeyFrame()
                .text("By default, all 6 faces of the factory block provide item/fluid/energy input and output.")
                .pointAt(util.vector().centerOf(factoryPos))
                .placeNearTarget();
        scene.idle(80);

        // ===== 镜头 2：扳手打开西面接口 → 该面失去 IO =====
        scene.overlay().showControls(util.vector().blockSurface(factoryPos, Direction.WEST),
                Pointing.RIGHT, 40)
                .rightClick().withItem(AllItems.WRENCH.asStack());
        scene.idle(10);
        scene.world().modifyBlock(factoryPos,
                state -> state.setValue(FactoryBlock.SHAFT_BY_FACE.get(Direction.WEST), true), true);
        scene.effects().indicateRedstone(factoryPos);
        scene.idle(10);
        scene.overlay().showText(80)
                .attachKeyFrame()
                .text("Opening a shaft interface with a Wrench makes that face lose its input/output ability.")
                .pointAt(util.vector().blockSurface(factoryPos, Direction.WEST))
                .placeNearTarget();
        scene.idle(80);

        // ===== 镜头 3：贴上 IO 拓展方块，把溜槽移到其外侧 =====
        BlockPos extPos = util.grid().at(2, 4, 3);
        BlockPos chuteSidePos = util.grid().at(2, 3, 3);
        scene.world().setBlock(extPos,
                ModBlocks.IO_EXTENSION.get().defaultBlockState()
                        .setValue(IOExtensionBlock.AXIS, Direction.Axis.X),
                true);
        // 把溜槽从工厂上方移到 IO 拓展方块外侧，示意从拓展方块继续进出物品
        scene.world().destroyBlock(chuteTopPos);
        scene.world().setBlock(chuteSidePos,
                AllBlocks.SMART_CHUTE.get().defaultBlockState(), true);
        scene.idle(10);
        scene.overlay().showOutline(PonderPalette.BLUE, extPos,
                util.select().position(extPos), 40);
        scene.idle(10);
        scene.overlay().showText(90)
                .attachKeyFrame()
                .text("Place an IO Extension Block on the shaft face to extend the factory's item/fluid input and output to its other faces.")
                .pointAt(util.vector().centerOf(extPos))
                .placeNearTarget();
        scene.idle(90);

        scene.markAsFinished();
    }
}
