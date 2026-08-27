package com.yansunsky.createcmpor.ponder;

import com.yansunsky.createcmpor.CreateCMPOR;

import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.minecraft.resources.ResourceLocation;

/**
 * CreateCMPOR 全部 Ponder 故事板的注册入口。
 *
 * <p>故事板（storyboard）= 一个完整的 Ponder 思索场景（由 .nbt 初始布局 + Java 动画指令组成）。
 * 绑定到方块/物品的注册 id（如 createcmpor:stress_input），玩家对该物品按 Ponder 键即可触发。</p>
 *
 * <p>场景资源路径约定（Ponder 源码 {@code PonderSceneRegistry.loadSchematic}）：
 * {@code assets/<modid>/ponder/<schematicPath>.nbt}，其中 schematicPath 即 {@code addStoryBoard}
 * 的第一个 String 参数（如 {@code "stress_input/stress_input"}）。</p>
 */
public final class ModPonderScenes {

    private ModPonderScenes() {
    }

    public static void register(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        // 组件用方块/物品的注册 id；Ponder 索引用 Item 的注册 id 匹配（PonderTooltipHandler 源码确认）
        ResourceLocation stressInput = ResourceLocation.fromNamespaceAndPath(
                CreateCMPOR.MOD_ID, "stress_input");
        ResourceLocation stressOutput = ResourceLocation.fromNamespaceAndPath(
                CreateCMPOR.MOD_ID, "stress_output");
        ResourceLocation inputBlock = ResourceLocation.fromNamespaceAndPath(
                CreateCMPOR.MOD_ID, "input_block");
        ResourceLocation outputBlock = ResourceLocation.fromNamespaceAndPath(
                CreateCMPOR.MOD_ID, "output_block");
        ResourceLocation ioExtension = ResourceLocation.fromNamespaceAndPath(
                CreateCMPOR.MOD_ID, "io_extension");
        ResourceLocation parallelInput = ResourceLocation.fromNamespaceAndPath(
                CreateCMPOR.MOD_ID, "parallel_input_block");

        helper.forComponents(stressInput)
                .addStoryBoard("stress_input/stress_input", StressInputScenes::stressInputBasics,
                        AllPonderTags.SYSTEM);
        helper.forComponents(stressOutput)
                .addStoryBoard("stress_output/stress_output", StressOutputScenes::stressOutputBasics,
                        AllPonderTags.SYSTEM);

        helper.forComponents(inputBlock)
                .addStoryBoard("input_block/input_block", InputBlockScenes::inputBlockBasics,
                        AllPonderTags.SYSTEM);
        helper.forComponents(outputBlock)
                .addStoryBoard("output_block/output_block", OutputBlockScenes::outputBlockBasics,
                        AllPonderTags.SYSTEM);

        // IO 拓展方块：6 面 IO → 扳手开接口（牺牲该面 IO）→ io_extension 拓展
        helper.forComponents(ioExtension)
                .addStoryBoard("io_extension/io_extension", IOExtensionScenes::ioExtensionBasics,
                        AllPonderTags.SYSTEM);

        // 并行空间输入方块：认识/配置 → 同一产线逐物品分支评估 → 评估过程 → 生成 N 个工厂
        helper.forComponents(parallelInput)
                .addStoryBoard("parallel_input_block/parallel_input_block1", ParallelInputScenes::configure,
                        AllPonderTags.SYSTEM)
                .addStoryBoard("parallel_input_block/parallel_input_block2", ParallelInputScenes::evaluateLine,
                        AllPonderTags.SYSTEM)
                .addStoryBoard("parallel_input_block/parallel_input_block3", ParallelInputScenes::evaluating,
                        AllPonderTags.SYSTEM)
                .addStoryBoard("parallel_input_block/parallel_input_block4", ParallelInputScenes::solidified,
                        AllPonderTags.SYSTEM);

        // 工厂方块：完整生命周期（注册顺序即流程顺序，Ponder 内可上/下切换）
        ResourceLocation factoryBlock = ResourceLocation.fromNamespaceAndPath(
                CreateCMPOR.MOD_ID, "factory_block");
        ResourceLocation launcherStick = ResourceLocation.fromNamespaceAndPath(
                CreateCMPOR.MOD_ID, "launcher_stick");
        helper.forComponents(factoryBlock)
                .addStoryBoard("factory_block/machine1", FactoryScenes::machine, AllPonderTags.SYSTEM)
                .addStoryBoard("factory_block/machine2", FactoryScenes::evaluatorStart, AllPonderTags.SYSTEM)
                .addStoryBoard("factory_block/machine4", FactoryScenes::solidified, AllPonderTags.SYSTEM)
                .addStoryBoard("factory_block/machine5", FactoryScenes::running, AllPonderTags.SYSTEM)
                .addStoryBoard("factory_block/machine6", FactoryScenes::reverted, AllPonderTags.SYSTEM);
        helper.forComponents(launcherStick)
                .addStoryBoard("factory_block/machine1", FactoryScenes::machine, AllPonderTags.SYSTEM)
                .addStoryBoard("factory_block/machine2", FactoryScenes::evaluatorStart, AllPonderTags.SYSTEM)
                .addStoryBoard("factory_block/machine4", FactoryScenes::solidified, AllPonderTags.SYSTEM)
                .addStoryBoard("factory_block/machine5", FactoryScenes::running, AllPonderTags.SYSTEM)
                .addStoryBoard("factory_block/machine6", FactoryScenes::reverted, AllPonderTags.SYSTEM);

        // Compact Machines 房间机器：应用工厂方块的完整生命周期思索（玩家对着机器即可查看教程）
        // CM 7.0.81 新世界默认机器是 new_machine；machine 是旧注册名（旧存档兼容），两个都绑定
        for (String machineId : new String[] { "machine", "new_machine" }) {
            ResourceLocation cmMachine = ResourceLocation.fromNamespaceAndPath(
                    "compactmachines", machineId);
            helper.forComponents(cmMachine)
                    .addStoryBoard("factory_block/machine1", FactoryScenes::machine, AllPonderTags.SYSTEM)
                    .addStoryBoard("factory_block/machine2", FactoryScenes::evaluatorStart, AllPonderTags.SYSTEM)
                    .addStoryBoard("factory_block/machine4", FactoryScenes::solidified, AllPonderTags.SYSTEM)
                    .addStoryBoard("factory_block/machine5", FactoryScenes::running, AllPonderTags.SYSTEM)
                    .addStoryBoard("factory_block/machine6", FactoryScenes::reverted, AllPonderTags.SYSTEM);
        }
    }
}
