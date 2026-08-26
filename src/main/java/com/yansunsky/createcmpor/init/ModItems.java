package com.yansunsky.createcmpor.init;

import com.yansunsky.createcmpor.CreateCMPOR;
import com.yansunsky.createcmpor.item.LauncherStickItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems {

    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(CreateCMPOR.MOD_ID);

    public static final DeferredItem<BlockItem> IO_EXTENSION =
            ITEMS.registerSimpleBlockItem("io_extension", ModBlocks.IO_EXTENSION);

    public static final DeferredItem<BlockItem> FACTORY =
            ITEMS.registerSimpleBlockItem("factory_block", ModBlocks.FACTORY);

    public static final DeferredItem<BlockItem> INPUT =
            ITEMS.registerSimpleBlockItem("input_block", ModBlocks.INPUT);

    public static final DeferredItem<BlockItem> PARALLEL_INPUT =
            ITEMS.registerSimpleBlockItem("parallel_input_block", ModBlocks.PARALLEL_INPUT);

    public static final DeferredItem<BlockItem> OUTPUT =
            ITEMS.registerSimpleBlockItem("output_block", ModBlocks.OUTPUT);

    public static final DeferredItem<BlockItem> STRESS_INPUT =
            ITEMS.registerSimpleBlockItem("stress_input", ModBlocks.STRESS_INPUT);

    public static final DeferredItem<BlockItem> STRESS_OUTPUT =
            ITEMS.registerSimpleBlockItem("stress_output", ModBlocks.STRESS_OUTPUT);

    public static final DeferredItem<LauncherStickItem> LAUNCHER_STICK =
            ITEMS.register("launcher_stick", () -> new LauncherStickItem(new Item.Properties().stacksTo(1)));

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
    }
}
