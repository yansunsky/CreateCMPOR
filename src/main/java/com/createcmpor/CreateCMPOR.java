package com.createcmpor;

import com.createcmpor.init.ModBlockEntities;
import com.createcmpor.init.ModBlocks;
import com.createcmpor.init.ModCreativeTabs;
import com.createcmpor.init.ModItems;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.Logger;

/**
 * CreateCMPOR 主类。
 *
 * <p>本附属模组把 Create 的应力系统接入 CompactMachinesPOR 的工厂方块，提供两个新方块：
 * <ul>
 *     <li><b>应力拓展方块</b>（{@code stress_extension}）——复用安山传动箱外观，
 *         接入 Create 应力网络，并把相邻 CMPOR 工厂方块的物品/流体/能量 IO 能力拓展到自身四侧。</li>
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

    /** CMPOR 工厂方块的注册名，用于运行时按 id 识别相邻工厂（避免编译期硬依赖 CMPOR 类）。 */
    public static final String CMPOR_FACTORY_BLOCK_ID = "compactmachinespor:factory_block";

    public CreateCMPOR(IEventBus modEventBus, ModContainer modContainer) {
        // 注册所有延迟注册表
        ModBlocks.register(modEventBus);
        ModItems.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        ModCreativeTabs.register(modEventBus);
        com.createcmpor.stress.ModAttachments.register(modEventBus);

        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(ModBlocks::registerCapabilities);

        // 配置文件（含 enableStressOutput）
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        // 在主线程安全地向 Create 注册应力值（不能用 CStress——它对非 Create 方块抛异常）
        event.enqueueWork(ModBlocks::registerStressValues);
    }
}
