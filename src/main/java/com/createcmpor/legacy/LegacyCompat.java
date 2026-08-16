package com.createcmpor.legacy;

import com.createcmpor.CreateCMPOR;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * ⚠️ 临时兼容代码（TEMPORARY）
 * 仅用于旧版 CompactMachinesPOR 存档过渡：注册旧 id 方块/实体/物品，加载旧存档后自动还原。
 * 待旧存档迁移完成（或确认无需兼容）后整个 legacy 包应被移除，勿在此基础上扩展业务逻辑。
 *
 * <p>旧版 CompactMachinesPOR 工厂方块兼容注册。
 *
 * <p>以 {@code compactmachinespor} 命名空间注册旧方块 / 方块实体 / 物品，
 * 使旧存档（旧 mod 已卸载）中的工厂方块能被识别并自动还原。
 * 若旧 mod 仍同时加载（id 已被占用），自动跳过注册避免冲突。
 */
public final class LegacyCompat {

    public static final String LEGACY_MOD_ID = "compactmachinespor";
    public static final ResourceLocation LEGACY_FACTORY_ID =
            ResourceLocation.fromNamespaceAndPath(LEGACY_MOD_ID, "factory_block");

    /** 注册后的旧工厂方块（RegisterEvent BLOCK 时创建）。 */
    public static Block LEGACY_BLOCK;
    /** 注册后的旧工厂方块实体类型（RegisterEvent BLOCK_ENTITY_TYPE 时创建）。 */
    public static BlockEntityType<LegacyFactoryBlockEntity> LEGACY_BE_TYPE;

    private LegacyCompat() {
    }

    /** 挂载到 mod 总线（由主类调用）。 */
    public static void register(net.neoforged.bus.api.IEventBus modEventBus) {
        modEventBus.addListener(LegacyCompat::onRegister);
    }

    private static void onRegister(RegisterEvent event) {
        if (event.getRegistryKey() == Registries.BLOCK) {
            if (!BuiltInRegistries.BLOCK.containsKey(LEGACY_FACTORY_ID)) {
                LEGACY_BLOCK = new LegacyFactoryBlock(BlockBehaviour.Properties.of()
                        .mapColor(MapColor.METAL)
                        .strength(3.0f)
                        .sound(SoundType.METAL)
                        .noOcclusion());
                event.register(Registries.BLOCK, LEGACY_FACTORY_ID, () -> LEGACY_BLOCK);
                CreateCMPOR.LOGGER.info("[CreateCMPOR] 已注册旧版工厂方块兼容 {}（旧存档兼容）",
                        LEGACY_FACTORY_ID);
            } else {
                CreateCMPOR.LOGGER.info("[CreateCMPOR] 检测到旧版 CompactMachinesPOR 仍在加载，跳过兼容注册");
            }
        } else if (event.getRegistryKey() == Registries.BLOCK_ENTITY_TYPE
                && LEGACY_BLOCK != null && !BuiltInRegistries.BLOCK_ENTITY_TYPE
                        .containsKey(LEGACY_FACTORY_ID)) {
            LEGACY_BE_TYPE = BlockEntityType.Builder.of(LegacyFactoryBlockEntity::new, LEGACY_BLOCK)
                    .build(null);
            event.register(Registries.BLOCK_ENTITY_TYPE, LEGACY_FACTORY_ID, () -> LEGACY_BE_TYPE);
        } else if (event.getRegistryKey() == Registries.ITEM
                && LEGACY_BLOCK != null && !BuiltInRegistries.ITEM.containsKey(LEGACY_FACTORY_ID)) {
            event.register(Registries.ITEM, LEGACY_FACTORY_ID,
                    () -> new BlockItem(LEGACY_BLOCK, new Item.Properties()));
        }
    }
}
