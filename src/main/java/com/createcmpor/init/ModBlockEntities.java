package com.createcmpor.init;

import com.createcmpor.CreateCMPOR;
import com.createcmpor.block.EvaluatorBlockEntity;
import com.createcmpor.block.FactoryBlockEntity;
import com.createcmpor.block.InputBlockEntity;
import com.createcmpor.block.IOExtensionBlockEntity;
import com.createcmpor.block.OutputBlockEntity;
import com.createcmpor.block.StressInputBlockEntity;
import com.createcmpor.block.StressOutputBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, CreateCMPOR.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<IOExtensionBlockEntity>> IO_EXTENSION =
            BLOCK_ENTITIES.register("io_extension", () -> BlockEntityType.Builder.of(
                    IOExtensionBlockEntity::new, ModBlocks.IO_EXTENSION.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FactoryBlockEntity>> FACTORY =
            BLOCK_ENTITIES.register("factory_block", () -> BlockEntityType.Builder.of(
                    FactoryBlockEntity::new, ModBlocks.FACTORY.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<InputBlockEntity>> INPUT =
            BLOCK_ENTITIES.register("input_block", () -> BlockEntityType.Builder.of(
                    InputBlockEntity::new, ModBlocks.INPUT.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<OutputBlockEntity>> OUTPUT =
            BLOCK_ENTITIES.register("output_block", () -> BlockEntityType.Builder.of(
                    OutputBlockEntity::new, ModBlocks.OUTPUT.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<EvaluatorBlockEntity>> EVALUATOR =
            BLOCK_ENTITIES.register("evaluator_block", () -> BlockEntityType.Builder.of(
                    EvaluatorBlockEntity::new, ModBlocks.EVALUATOR.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<StressInputBlockEntity>> STRESS_INPUT =
            BLOCK_ENTITIES.register("stress_input", () -> BlockEntityType.Builder.of(
                    StressInputBlockEntity::new, ModBlocks.STRESS_INPUT.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<StressOutputBlockEntity>> STRESS_OUTPUT =
            BLOCK_ENTITIES.register("stress_output", () -> BlockEntityType.Builder.of(
                    StressOutputBlockEntity::new, ModBlocks.STRESS_OUTPUT.get()).build(null));

    public static void register(IEventBus modEventBus) {
        BLOCK_ENTITIES.register(modEventBus);
    }
}
