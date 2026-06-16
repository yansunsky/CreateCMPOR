package com.createcmpor.init;

import com.createcmpor.CreateCMPOR;
import com.createcmpor.block.StressExtensionBlockEntity;
import com.createcmpor.block.StressIOBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 方块实体类型注册。
 */
public class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, CreateCMPOR.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<StressExtensionBlockEntity>> STRESS_EXTENSION =
            BLOCK_ENTITIES.register("stress_extension", () -> BlockEntityType.Builder.of(
                    StressExtensionBlockEntity::new, ModBlocks.STRESS_EXTENSION.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<StressIOBlockEntity>> STRESS_IO =
            BLOCK_ENTITIES.register("stress_io", () -> BlockEntityType.Builder.of(
                    StressIOBlockEntity::new, ModBlocks.STRESS_IO.get()).build(null));

    public static void register(IEventBus modEventBus) {
        BLOCK_ENTITIES.register(modEventBus);
    }
}
