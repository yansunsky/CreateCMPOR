package com.createcmpor.init;

import com.createcmpor.CreateCMPOR;
import com.createcmpor.block.EvaluatorBlock;
import com.createcmpor.block.FactoryBlock;
import com.createcmpor.block.InputBlock;
import com.createcmpor.block.OutputBlock;
import com.createcmpor.block.StressExtensionBlock;
import com.createcmpor.block.StressInputBlock;
import com.createcmpor.block.StressOutputBlock;
import com.simibubi.create.api.stress.BlockStressValues;
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

    public static final DeferredBlock<StressExtensionBlock> STRESS_EXTENSION =
            BLOCKS.register("stress_extension", () -> new StressExtensionBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.PODZOL)
                            .strength(2.0f)
                            .sound(SoundType.WOOD)
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
                            .strength(3.0f, 6.0f)));

    public static final DeferredBlock<OutputBlock> OUTPUT =
            BLOCKS.register("output_block", () -> new OutputBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.COLOR_BLACK)
                            .strength(3.0f, 6.0f)));

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
                            .noOcclusion()));

    public static final DeferredBlock<StressOutputBlock> STRESS_OUTPUT =
            BLOCKS.register("stress_output", () -> new StressOutputBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.TERRACOTTA_BLUE)
                            .strength(2.0f)
                            .sound(SoundType.METAL)
                            .noOcclusion()));

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
    }

    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
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
    }

    public static void registerStressValues() {
        BlockStressValues.CAPACITIES.register(STRESS_INPUT.get(), () -> 16384.0);
    }
}
