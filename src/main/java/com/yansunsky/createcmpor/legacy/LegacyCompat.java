package com.yansunsky.createcmpor.legacy;

import com.yansunsky.createcmpor.CreateCMPOR;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.jetbrains.annotations.Nullable;

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
    public static final ResourceLocation LEGACY_EVALUATOR_ID =
            ResourceLocation.fromNamespaceAndPath(LEGACY_MOD_ID, "evaluator_block");

    /** 注册后的旧工厂方块（RegisterEvent BLOCK 时创建）。 */
    public static Block LEGACY_BLOCK;
    /** 注册后的旧工厂方块实体类型（RegisterEvent BLOCK_ENTITY_TYPE 时创建）。 */
    public static BlockEntityType<LegacyFactoryBlockEntity> LEGACY_BE_TYPE;
    /** 注册后的旧评估方块（RegisterEvent BLOCK 时创建）。 */
    public static Block LEGACY_EVALUATOR_BLOCK;
    /** 注册后的旧评估方块实体类型（RegisterEvent BLOCK_ENTITY_TYPE 时创建）。 */
    public static BlockEntityType<LegacyEvaluatorBlockEntity> LEGACY_EVALUATOR_BE_TYPE;

    private LegacyCompat() {
    }

    /** 挂载到 mod 总线（由主类调用）。 */
    public static void register(net.neoforged.bus.api.IEventBus modEventBus) {
        modEventBus.addListener(LegacyCompat::onRegister);
    }

    /**
     * 把旧存档残留方块（工厂/评估方块）还原为 Compact Machines 绑定机器方块。
     *
     * <p>只做两件事：取房间号填充机器 BE 的 {@code room_code}，并（可选）恢复机器颜色附件。
     * 房间内容由 CM 自身数据决定，不做原机器还原。</p>
     *
     * @param attachments 旧 NBT 里保存的机器附件（如 machine_color）；可为 null 用默认色
     * @return true 还原成功
     */
    public static boolean restoreAsBoundMachine(net.minecraft.server.level.ServerLevel level,
                                                net.minecraft.core.BlockPos pos,
                                                String roomCode,
                                                @Nullable CompoundTag attachments) {
        if (roomCode == null || roomCode.isBlank()) {
            CreateCMPOR.LOGGER.warn("[CreateCMPOR] 旧方块缺少 room_code，无法还原为空间机器 @{}", pos);
            return false;
        }
        net.minecraft.world.level.block.Block machineBlock;
        try {
            net.minecraft.world.item.Item item =
                    dev.compactmods.machines.machine.Machines.Items.BOUND_MACHINE.get();
            if (!(item instanceof net.minecraft.world.item.BlockItem blockItem)) {
                CreateCMPOR.LOGGER.error("[CreateCMPOR] 旧方块还原失败：BOUND_MACHINE 不是 BlockItem");
                return false;
            }
            machineBlock = blockItem.getBlock();
        } catch (Throwable t) {
            CreateCMPOR.LOGGER.error("[CreateCMPOR] 旧方块还原失败：无法获取 BOUND_MACHINE", t);
            return false;
        }
        if (machineBlock == null) {
            CreateCMPOR.LOGGER.error("[CreateCMPOR] 旧方块还原失败：BOUND_MACHINE 方块为空");
            return false;
        }

        // 先放方块，取默认 BE 的类型 id
        level.removeBlockEntity(pos);
        level.setBlockAndUpdate(pos, machineBlock.defaultBlockState());

        net.minecraft.world.level.block.entity.BlockEntity defaultBe = level.getBlockEntity(pos);
        if (defaultBe == null) {
            CreateCMPOR.LOGGER.warn("[CreateCMPOR] 旧方块还原警告：{} 处未生成机器 BE", pos);
            return false;
        }

        String beTypeId = net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE
                .getKey(defaultBe.getType()).toString();
        CompoundTag beNbt = new CompoundTag();
        beNbt.putString("id", beTypeId);
        beNbt.putInt("x", pos.getX());
        beNbt.putInt("y", pos.getY());
        beNbt.putInt("z", pos.getZ());
        beNbt.putString("room_code", roomCode);
        if (attachments != null && !attachments.isEmpty()) {
            beNbt.put("neoforge:attachments", attachments.copy());
        } else {
            CompoundTag defaultAttachments = new CompoundTag();
            defaultAttachments.putString("compactmachines:machine_color", "#C95B13");
            beNbt.put("neoforge:attachments", defaultAttachments);
        }

        net.minecraft.world.level.block.entity.BlockEntity loaded = net.minecraft.world.level
                .block.entity.BlockEntity.loadStatic(pos, level.getBlockState(pos), beNbt,
                        level.registryAccess());
        if (loaded != null) {
            level.setBlockEntity(loaded);
            loaded.setChanged();
            CreateCMPOR.LOGGER.info("[CreateCMPOR] 旧方块已还原为空间机器 @{} (room={})", pos, roomCode);
            return true;
        }
        CreateCMPOR.LOGGER.warn("[CreateCMPOR] 旧方块还原失败：loadStatic 返回 null @{}", pos);
        return false;
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
            if (!BuiltInRegistries.BLOCK.containsKey(LEGACY_EVALUATOR_ID)) {
                LEGACY_EVALUATOR_BLOCK = new LegacyEvaluatorBlock(BlockBehaviour.Properties.of()
                        .mapColor(MapColor.COLOR_BLACK)
                        .strength(3.0f, 6.0f));
                event.register(Registries.BLOCK, LEGACY_EVALUATOR_ID, () -> LEGACY_EVALUATOR_BLOCK);
                CreateCMPOR.LOGGER.info("[CreateCMPOR] 已注册旧版评估方块兼容 {}（旧存档兼容）",
                        LEGACY_EVALUATOR_ID);
            }
        } else if (event.getRegistryKey() == Registries.BLOCK_ENTITY_TYPE) {
            if (LEGACY_BLOCK != null && !BuiltInRegistries.BLOCK_ENTITY_TYPE
                    .containsKey(LEGACY_FACTORY_ID)) {
                LEGACY_BE_TYPE = BlockEntityType.Builder.of(LegacyFactoryBlockEntity::new, LEGACY_BLOCK)
                        .build(null);
                event.register(Registries.BLOCK_ENTITY_TYPE, LEGACY_FACTORY_ID, () -> LEGACY_BE_TYPE);
            }
            if (LEGACY_EVALUATOR_BLOCK != null && !BuiltInRegistries.BLOCK_ENTITY_TYPE
                    .containsKey(LEGACY_EVALUATOR_ID)) {
                LEGACY_EVALUATOR_BE_TYPE = BlockEntityType.Builder.of(
                        LegacyEvaluatorBlockEntity::new, LEGACY_EVALUATOR_BLOCK)
                        .build(null);
                event.register(Registries.BLOCK_ENTITY_TYPE, LEGACY_EVALUATOR_ID,
                        () -> LEGACY_EVALUATOR_BE_TYPE);
            }
        } else if (event.getRegistryKey() == Registries.ITEM) {
            if (LEGACY_BLOCK != null && !BuiltInRegistries.ITEM.containsKey(LEGACY_FACTORY_ID)) {
                event.register(Registries.ITEM, LEGACY_FACTORY_ID,
                        () -> new BlockItem(LEGACY_BLOCK, new Item.Properties()));
            }
            if (LEGACY_EVALUATOR_BLOCK != null && !BuiltInRegistries.ITEM
                    .containsKey(LEGACY_EVALUATOR_ID)) {
                event.register(Registries.ITEM, LEGACY_EVALUATOR_ID,
                        () -> new BlockItem(LEGACY_EVALUATOR_BLOCK, new Item.Properties()));
            }
        }
    }
}
