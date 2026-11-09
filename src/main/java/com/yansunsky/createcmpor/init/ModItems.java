package com.yansunsky.createcmpor.init;

import com.yansunsky.createcmpor.CreateCMPOR;
import com.yansunsky.createcmpor.item.LauncherStickItem;
import com.yansunsky.createcmpor.item.PurificationStickItem;
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

    public static final DeferredItem<com.yansunsky.createcmpor.item.FactoryBlockItem> FACTORY =
            ITEMS.register("factory_block", () -> new com.yansunsky.createcmpor.item.FactoryBlockItem(
                    ModBlocks.FACTORY.get(), new Item.Properties()));

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

    /**
     * 启动棒耐久（0.5.9）：一次评估扣 1 点，耗尽即消耗掉整根棒子。
     *
     * <p><b>为什么用耐久而不是「消耗 + 事后退还」</b>：旧机制为了让道具在评估结束后回来，
     * 必须把「已扣但未还」记进 SavedData 和玩家 NBT，于是每次扣棒都要
     * {@code PlayerList.saveAll()} 落盘（实测 18 人串行同步写盘 ≈ 3.9 秒全服冻结，
     * 一次评估卡一次）。耐久把这套借贷记账整个删掉：道具从头到尾没离开玩家背包，
     * 失败路径（下线 / 背包满 / 崩服 / 孤立方块恢复）随之全部消失。</p>
     *
     * <p>{@code durability(N)} 会同时设 MAX_DAMAGE / MAX_STACK_SIZE=1 / DAMAGE=0；
     * 创造模式免扣、耐久附魔、爆装消失都由 {@code ItemStack.hurtAndBreak} 原生处理。</p>
     */
    private static final int LAUNCHER_DURABILITY = 64;
    private static final int PURIFICATION_DURABILITY = 32;

    public static final DeferredItem<LauncherStickItem> LAUNCHER_STICK =
            ITEMS.register("launcher_stick", () -> new LauncherStickItem(
                    new Item.Properties().durability(LAUNCHER_DURABILITY)));

    public static final DeferredItem<PurificationStickItem> PURIFICATION_STICK =
            ITEMS.register("purification_stick", () -> new PurificationStickItem(
                    new Item.Properties().durability(PURIFICATION_DURABILITY)));

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
    }
}
