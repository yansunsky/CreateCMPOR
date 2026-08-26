package com.yansunsky.createcmpor;

import com.yansunsky.createcmpor.command.EvalWorldCommands;
import com.yansunsky.createcmpor.init.ModBlockEntities;
import com.yansunsky.createcmpor.init.ModBlocks;
import com.yansunsky.createcmpor.init.ModCreativeTabs;
import com.yansunsky.createcmpor.init.ModItems;
import com.yansunsky.createcmpor.evaluation.EvalWorldGuard;
import com.yansunsky.createcmpor.evaluation.EvaluationManager;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import org.slf4j.Logger;

/**
 * CreateCMPOR 主类。
 *
 * <p>本附属模组把 Create 的应力系统接入 CompactMachines 的平行房间评估流程，提供以下核心方块：
 * <ul>
 *     <li><b>工厂方块</b>（{@code factory_block}）——本模组自有的固化产能落点，后续阶段承载评估结果与 IO 配对。</li>
 *     <li><b>IO 拓展方块</b>（{@code io_extension}，原应力拓展方块）——复用安山传动箱外观，
 *         像普通传动轴一样被动接入 Create 应力网络，并把链上触达的本模组工厂方块的
 *         物品/流体/能量缓存（输入+输出）拓展到自身非轴向四面。</li>
 *     <li><b>应力输入方块</b>（{@code stress_input}）——复用创造马达外观，放置于压缩空间内，
 *         评估期作为应力源驱动内部机器运转，测得真实应力消耗。</li>
 *     <li><b>应力输出方块</b>（{@code stress_output}）——复用应力表外观，放置于压缩空间内，
 *         评估期被动观察网络应力余量，测得工厂可对外提供的应力。</li>
 * </ul>
 */
@Mod(CreateCMPOR.MOD_ID)
public class CreateCMPOR {

    public static final String MOD_ID = "createcmpor";
    public static final Logger LOGGER = LogUtils.getLogger();
    public static final ResourceKey<Level> EVAL_WORLD = ResourceKey.create(
            Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(MOD_ID, "eval_world"));

    /** 本模组工厂方块的注册名。后续阶段会改为 block tag，Phase 1 先用常量完成脱离 CMPOR 的最小骨架。 */
    public static final String FACTORY_BLOCK_ID = "createcmpor:factory_block";

    /** 本模组 IO 拓展方块（原应力拓展方块）的注册名。 */
    public static final String IO_EXTENSION_BLOCK_ID = "createcmpor:io_extension";

    public CreateCMPOR(IEventBus modEventBus, ModContainer modContainer) {
        // 注册所有延迟注册表
        ModBlocks.register(modEventBus);
        ModItems.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        ModCreativeTabs.register(modEventBus);
        com.yansunsky.createcmpor.stress.ModAttachments.register(modEventBus);
        com.yansunsky.createcmpor.legacy.LegacyCompat.register(modEventBus);

        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(ModBlocks::registerCapabilities);
        modEventBus.addListener(CreateCMPOR::onAddPackFinders);
        NeoForge.EVENT_BUS.addListener(EvalWorldCommands::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(EvaluationManager.INSTANCE::onServerTick);
        NeoForge.EVENT_BUS.addListener(EvaluationManager.INSTANCE::onServerStarted);
        NeoForge.EVENT_BUS.addListener(EvaluationManager.INSTANCE::onPlayerLogin);
        NeoForge.EVENT_BUS.addListener(EvaluationManager.INSTANCE::onBlockBreak);
        NeoForge.EVENT_BUS.addListener(EvalWorldGuard::onServerTick);
        NeoForge.EVENT_BUS.addListener(EvalWorldGuard::onPlayerRespawn);
        // 防复制护栏：玩家进入已评估房间的空间时自动还原工厂
        NeoForge.EVENT_BUS.addListener(com.yansunsky.createcmpor.evaluation.AntiDupeSpaceEntryHandler::onServerTick);

        // 配置文件（含 enableStressOutput）
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        // 在主线程安全地向 Create 注册应力值（不能用 CStress——它对非 Create 方块抛异常）
        event.enqueueWork(() -> {
            ModBlocks.registerStressValues();
            // 蓝图打印安全：应力方块激活态不允许被蓝图复制
            com.yansunsky.createcmpor.init.BlueprintSafety.register();
        });
    }

    /**
     * 默认启用 CompactMachines 内置的 basic_templates 数据包（房间模板）。
     *
     * <p>CompactMachines 7.0.81 把房间模板放在内置数据包 {@code data/compactmachines/datapacks/basic_templates}
     * 中，但默认不激活，玩家必须手动点击启用。这里在 mod 总线事件中把该数据包注册为 always-active，
     * 使新建世界默认拥有房间模板，消除 "No Room Templates are registered!" 提示。
     */
    private static void onAddPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.SERVER_DATA) {
            return;
        }
        event.addPackFinders(
                ResourceLocation.fromNamespaceAndPath("compactmachines", "data/compactmachines/datapacks/basic_templates"),
                PackType.SERVER_DATA,
                Component.literal("Compact Machines Basic Templates"),
                PackSource.BUILT_IN,
                true,
                Pack.Position.TOP);
    }
}
