package com.createcmpor.init;

import com.createcmpor.CreateCMPOR;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 物品注册（两个方块的 BlockItem）。
 */
public class ModItems {

    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(CreateCMPOR.MOD_ID);

    public static final DeferredItem<BlockItem> STRESS_EXTENSION =
            ITEMS.registerSimpleBlockItem("stress_extension", ModBlocks.STRESS_EXTENSION);

    public static final DeferredItem<BlockItem> STRESS_IO =
            ITEMS.registerSimpleBlockItem("stress_io", ModBlocks.STRESS_IO);

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
    }
}
