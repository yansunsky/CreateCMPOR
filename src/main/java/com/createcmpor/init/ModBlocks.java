package com.createcmpor.init;

import com.createcmpor.CreateCMPOR;
import com.createcmpor.block.EvaluatorBlock;
import com.createcmpor.block.FactoryBlock;
import com.createcmpor.block.FactoryBlockEntity;
import com.createcmpor.block.InputBlock;
import com.createcmpor.block.IOExtensionBlock;
import com.createcmpor.block.OutputBlock;
import com.createcmpor.block.StressInputBlock;
import com.createcmpor.block.StressOutputBlock;
import com.simibubi.create.api.stress.BlockStressValues;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlocks {

    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(CreateCMPOR.MOD_ID);

    public static final DeferredBlock<IOExtensionBlock> IO_EXTENSION =
            BLOCKS.register("io_extension", () -> new IOExtensionBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.PODZOL)
                            .strength(2.0f)
                            .sound(SoundType.WOOD)
                            .requiresCorrectToolForDrops()
                            .noOcclusion()));

    public static final DeferredBlock<FactoryBlock> FACTORY =
            BLOCKS.register("factory_block", () -> new FactoryBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.METAL)
                            .strength(3.0f)
                            .sound(SoundType.METAL)
                            .noOcclusion()));

    public static final DeferredBlock<InputBlock> INPUT =
            BLOCKS.register("input_block", () -> new InputBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.COLOR_BLACK)
                            .strength(3.0f, 6.0f)
                            .requiresCorrectToolForDrops()));

    public static final DeferredBlock<OutputBlock> OUTPUT =
            BLOCKS.register("output_block", () -> new OutputBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.COLOR_BLACK)
                            .strength(3.0f, 6.0f)
                            .requiresCorrectToolForDrops()));

    public static final DeferredBlock<EvaluatorBlock> EVALUATOR =
            BLOCKS.register("evaluator_block", () -> new EvaluatorBlock(
                    BlockBehaviour.Properties.of()
                             .mapColor(MapColor.COLOR_BLACK)
                             .strength(-1.0f, 3600000.0f)
                             .pushReaction(PushReaction.BLOCK)
                             .noLootTable()));

    public static final DeferredBlock<StressInputBlock> STRESS_INPUT =
            BLOCKS.register("stress_input", () -> new StressInputBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.TERRACOTTA_CYAN)
                            .strength(2.0f)
                            .sound(SoundType.METAL)
                            .requiresCorrectToolForDrops()
                            .noOcclusion()));

    public static final DeferredBlock<StressOutputBlock> STRESS_OUTPUT =
            BLOCKS.register("stress_output", () -> new StressOutputBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.TERRACOTTA_BLUE)
                            .strength(2.0f)
                            .sound(SoundType.METAL)
                            .requiresCorrectToolForDrops()
                            .noOcclusion()));

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
    }

    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        // IO 拓展方块：把链上触达的工厂方块的缓存（输入+输出）代理转发到自身 IO 面。
        // 直接访问工厂 BE 的 handler（指向工厂自身缓存，无面过滤/应力档案门控），
        // 保证「产物能从它出去、原料能从它进入」，且不会复制物品。
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModBlockEntities.IO_EXTENSION.get(),
                (be, side) -> {
                    if (be.getLevel() == null || !be.isIoFace(side))
                        return null;
                    for (BlockPos fp : be.getReachableFactories()) {
                        if (be.getLevel().getBlockEntity(fp) instanceof FactoryBlockEntity fbe)
                            return fbe.getItemHandler();
                    }
                    return null;
                });
        event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, ModBlockEntities.IO_EXTENSION.get(),
                (be, side) -> {
                    if (be.getLevel() == null || !be.isIoFace(side))
                        return null;
                    for (BlockPos fp : be.getReachableFactories()) {
                        if (be.getLevel().getBlockEntity(fp) instanceof FactoryBlockEntity fbe)
                            return fbe.getFluidHandler();
                    }
                    return null;
                });
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, ModBlockEntities.IO_EXTENSION.get(),
                (be, side) -> {
                    if (be.getLevel() == null || !be.isIoFace(side))
                        return null;
                    for (BlockPos fp : be.getReachableFactories()) {
                        if (be.getLevel().getBlockEntity(fp) instanceof FactoryBlockEntity fbe && fbe.hasEnergyIo())
                            return fbe.getEnergyHandler();
                    }
                    return null;
                });

        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModBlockEntities.INPUT.get(),
                (be, side) -> be.getItemHandler());
        event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, ModBlockEntities.INPUT.get(),
                (be, side) -> be.getFluidHandler());
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, ModBlockEntities.INPUT.get(),
                (be, side) -> be.getEnergyHandler());

        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModBlockEntities.OUTPUT.get(),
                (be, side) -> be.getItemHandler());
        event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, ModBlockEntities.OUTPUT.get(),
                (be, side) -> be.getFluidHandler());
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, ModBlockEntities.OUTPUT.get(),
                (be, side) -> be.getEnergyHandler());

        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModBlockEntities.FACTORY.get(),
                (be, side) -> isFactoryIoFace(be, side) ? be.getItemHandler() : null);
        event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, ModBlockEntities.FACTORY.get(),
                (be, side) -> isFactoryIoFace(be, side) ? be.getFluidHandler() : null);
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, ModBlockEntities.FACTORY.get(),
                (be, side) -> isFactoryIoFace(be, side) && be.hasEnergyIo() ? be.getEnergyHandler() : null);
    }

    /** 开口面（应力接口）不提供物品/流体/能量 IO；其余面正常。 */
    private static boolean isFactoryIoFace(FactoryBlockEntity be, Direction side) {
        if (side == null) {
            return true;
        }
        return !be.getBlockState()
                .getValue(FactoryBlock.SHAFT_BY_FACE.get(side));
    }

    public static void registerStressValues() {
        BlockStressValues.CAPACITIES.register(STRESS_INPUT.get(), () -> 16384.0);
    }
}
