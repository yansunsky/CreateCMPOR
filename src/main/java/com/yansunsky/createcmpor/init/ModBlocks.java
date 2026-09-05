package com.yansunsky.createcmpor.init;

import com.yansunsky.createcmpor.CreateCMPOR;
import com.yansunsky.createcmpor.block.EvaluatorBlock;
import com.yansunsky.createcmpor.block.FactoryBlock;
import com.yansunsky.createcmpor.block.FactoryBlockEntity;
import com.yansunsky.createcmpor.block.InputBlock;
import com.yansunsky.createcmpor.block.IOExtensionBlock;
import com.yansunsky.createcmpor.block.OutputBlock;
import com.yansunsky.createcmpor.block.ParallelInputBlock;
import com.yansunsky.createcmpor.block.StressInputBlock;
import com.yansunsky.createcmpor.block.StressInputBlockEntity;
import com.yansunsky.createcmpor.block.StressOutputBlock;
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

    public static final DeferredBlock<ParallelInputBlock> PARALLEL_INPUT =
            BLOCKS.register("parallel_input_block", () -> new ParallelInputBlock(
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
                             .strength(2.0f, 3600000.0f) // 可被破坏；破坏事件走还原流程（EvaluationManager.onBlockBreak）
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

        // 并行空间输入方块：与输入方块相同的 Forge 能力暴露（Jade 通过能力系统识别并显示内容）
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModBlockEntities.PARALLEL_INPUT.get(),
                (be, side) -> be.getItemHandler());
        event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, ModBlockEntities.PARALLEL_INPUT.get(),
                (be, side) -> be.getFluidHandler());
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, ModBlockEntities.PARALLEL_INPUT.get(),
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
        // 应力输入方块：与创造马达一致——容量 16384 SU + 生成转速 16 RPM（tooltip 显示"应力量：16384x转/分钟"）
        BlockStressValues.CAPACITIES.register(STRESS_INPUT.get(), () -> 16384.0);
        BlockStressValues.RPM.register(STRESS_INPUT.get(),
                new BlockStressValues.GeneratedRpm(StressInputBlockEntity.DEFAULT_SPEED, true));
    }
}
