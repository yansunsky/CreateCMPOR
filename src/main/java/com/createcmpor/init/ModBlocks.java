package com.createcmpor.init;

import com.createcmpor.CreateCMPOR;
import com.createcmpor.block.StressExtensionBlock;
import com.createcmpor.block.StressExtensionBlockEntity;
import com.createcmpor.block.StressIOBlock;
import com.simibubi.create.api.stress.BlockStressValues;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 方块注册。
 */
public class ModBlocks {

    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(CreateCMPOR.MOD_ID);

    /** 应力拓展方块（复用安山传动箱外观）。 */
    public static final DeferredBlock<StressExtensionBlock> STRESS_EXTENSION =
            BLOCKS.register("stress_extension", () -> new StressExtensionBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.PODZOL)
                            .strength(2.0f)
                            .sound(SoundType.WOOD)
                            .noOcclusion()));

    /** 应力 IO 方块（复用创造马达外观）。 */
    public static final DeferredBlock<StressIOBlock> STRESS_IO =
            BLOCKS.register("stress_io", () -> new StressIOBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.TERRACOTTA_CYAN)
                            .strength(2.0f)
                            .sound(SoundType.METAL)
                            .noOcclusion()));

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
    }

    /**
     * 注册方块 capability。
     *
     * <p>应力拓展方块对其非轴向的四个侧面，把相邻 CMPOR 工厂方块的物品/流体/能量 handler
     * 代理转发到自身，从而把工厂的 IO 能力拓展到拓展方块处（指向同一底层容器）。
     */
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        // 物品：转发到链上任一工厂的 handler（联合拓展，返回第一个可用的）
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModBlockEntities.STRESS_EXTENSION.get(),
                (be, side) -> {
                    if (be.getLevel() == null || !be.isIoFace(side))
                        return null;
                    for (var fp : be.getChainFactories()) {
                        var h = be.getLevel().getCapability(Capabilities.ItemHandler.BLOCK, fp, side);
                        if (h != null) return h;
                    }
                    return null;
                });
        // 流体
        event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, ModBlockEntities.STRESS_EXTENSION.get(),
                (be, side) -> {
                    if (be.getLevel() == null || !be.isIoFace(side))
                        return null;
                    for (var fp : be.getChainFactories()) {
                        var h = be.getLevel().getCapability(Capabilities.FluidHandler.BLOCK, fp, side);
                        if (h != null) return h;
                    }
                    return null;
                });
        // 能量
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, ModBlockEntities.STRESS_EXTENSION.get(),
                (be, side) -> {
                    if (be.getLevel() == null || !be.isIoFace(side))
                        return null;
                    for (var fp : be.getChainFactories()) {
                        var h = be.getLevel().getCapability(Capabilities.EnergyStorage.BLOCK, fp, side);
                        if (h != null) return h;
                    }
                    return null;
                });
    }

    /**
     * 向 Create 注册应力值。
     *
     * <p>注意：不能使用 Create 的 {@code CStress}（它对非 Create 方块抛异常），
     * 必须直接调用 {@link BlockStressValues} 的注册表。
     */
    public static void registerStressValues() {
        // 应力 IO 方块作为输入模式应力源，提供较大容量基准（类创造马达）
        BlockStressValues.CAPACITIES.register(STRESS_IO.get(), () -> 16384.0);
    }
}
