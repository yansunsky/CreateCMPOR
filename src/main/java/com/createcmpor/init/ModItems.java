package com.createcmpor.init;

import com.createcmpor.CreateCMPOR;
import net.minecraft.world.item.BlockItem;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems {

    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(CreateCMPOR.MOD_ID);

    public static final DeferredItem<BlockItem> STRESS_EXTENSION =
            ITEMS.registerSimpleBlockItem("stress_extension", ModBlocks.STRESS_EXTENSION);

    public static final DeferredItem<BlockItem> FACTORY =
            ITEMS.registerSimpleBlockItem("factory_block", ModBlocks.FACTORY);

    public static final DeferredItem<BlockItem> STRESS_INPUT =
            ITEMS.registerSimpleBlockItem("stress_input", ModBlocks.STRESS_INPUT);

    public static final DeferredItem<BlockItem> STRESS_OUTPUT =
            ITEMS.registerSimpleBlockItem("stress_output", ModBlocks.STRESS_OUTPUT);

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
    }
}
