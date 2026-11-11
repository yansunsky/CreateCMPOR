package com.yansunsky.createcmpor.evaluation;

import com.yansunsky.createcmpor.CreateCMPOR;
import com.yansunsky.createcmpor.compat.inventory.ContainerItemExpander;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.AbstractMinecartContainer;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实体库存：把「实体携带的物品」纳入三扫描。
 *
 * <h2>为什么必须做（实体容器的物品复制漏洞）</h2>
 * <p>预装物品的矿车、箱子船或带箱子的马类进入房间后，物品可被机器取走并输出。
 * 若只扫描方块容器与掉落物，初始库存不可见，输出便会被误算成无输入的净产出。</p>
 *
 * <h2>读取优先级（同一实体只走一条路径）</h2>
 * <ol>
 *   <li>{@link ContainerEntity}：<b>有掉落表就走掉落表</b>（见下），否则遍历 {@code getItemStacks()}。</li>
 *   <li>{@link AbstractHorse}：遍历 {@code getInventory()} 的全部槽位，包括鞍。</li>
 *   <li>其它实体：优先使用 {@link Capabilities.ItemHandler#ENTITY}，非空即不读 NBT。
 *       NeoForge 为普通 {@code LivingEntity} 兜底暴露双手和四件护甲；这些是装备位，
 *       不是额外的存储空间，但装备同样属于房间里的真实物品。</li>
 *   <li>能力为空：只解析实体 NBT 顶层 {@code Items} 列表中含 {@code Slot} 字节与
 *       {@code id} 字符串的物品条目，不深入 Create 装置的挂载容器。</li>
 * </ol>
 * <p><b>玩家始终排除</b>：副本观察者的背包不是房间库存，不能计入快照。</p>
 *
 * <h2>⚠️ 为什么容器实体还要单独处理掉落表</h2>
 * <p>这是本次修复第一次审查时差点漏掉的逃逸路径，与本次修复<b>同形</b>：
 * {@code ContainerEntity.addChestVehicleSaveData} 的口径是「<b>有掉落表就只写 LootTable、
 * 完全不写 Items</b>」，而 {@code readChestVehicleSaveData} 读到掉落表也只塞回字段、
 * 不会立刻摇取成物品。于是<b>自然生成结构里的运输矿车</b>（带 LootTable、玩家还没开过）
 * 的 {@code itemStacks} 是全空的 —— 只读 {@code itemStacks} 会让它零库存，
 * 「把这辆矿车搬进房间 → 评估中开箱取物 → 输出」于是又变成凭空产出。</p>
 * <p>修法是只读地重算：用 {@code getRandomItems(params, seed, …)} 配上该实体自己的
 * {@code getLootTableSeed()}（与 {@code LootTable.fill} 走同一个随机序列、同一个种子）
 * 把内容摇出来计数，<b>但不调用 unpack</b>——实体状态不变，玩家日后真正开箱拿到的东西
 * 与这里计入的完全一致。</p>
 *
 * <h2>快照与异常边界</h2>
 * <p>仅由 {@link EvaluationAudit#scan} 在同一次 S0/S1/S2 快照中调用。
 * 所有物品统一走 {@link ContainerItemExpander#addToSnapshot}，保持身份签名、黑名单与
 * 嵌套容器展开口径一致。每个实体先写临时表，读取或解析失败便整笔丢弃，避免半笔入账。</p>
 */
final class EntityStorageScan {

    private EntityStorageScan() {
    }

    /**
     * 将房间内非玩家实体的库存计入本次快照；没有命中时不修改快照、不输出统计日志。
     *
     * @param level  评估副本维度
     * @param bounds 房间外边界（含 Y 轴，与其它库存段落使用同一边界）
     * @param items  本次快照的物品签名计数
     */
    static void countIntoSnapshot(ServerLevel level, AABB bounds, Map<String, Long> items) {
        if (level == null || bounds == null || items == null) {
            return;
        }
        List<Entity> entities = level.getEntities((Entity) null, bounds, entity -> !entity.isRemoved());
        if (entities.isEmpty()) {
            return;
        }

        HolderLookup.Provider registries = level.registryAccess();
        Map<ResourceLocation, Integer> entityCounts = new LinkedHashMap<>();
        Map<ResourceLocation, Long> itemCounts = new HashMap<>();
        int totalEntities = 0;
        long totalItems = 0;
        for (Entity entity : entities) {
            if (entity instanceof Player) {
                continue;
            }
            try {
                Map<String, Long> entityItems = new HashMap<>();
                countEntityIntoSnapshot(entity, registries, entityItems);
                if (entityItems.isEmpty()) {
                    continue;
                }
                long amount = entityItems.values().stream().mapToLong(Long::longValue).sum();
                ResourceLocation entityId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
                entityItems.forEach((signature, count) -> items.merge(signature, count, Long::sum));
                entityCounts.merge(entityId, 1, Integer::sum);
                itemCounts.merge(entityId, amount, Long::sum);
                totalEntities++;
                totalItems += amount;
            } catch (RuntimeException | LinkageError exception) {
                CreateCMPOR.LOGGER.warn("实体库存：实体 {} 读取失败（{}），已跳过",
                        BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()),
                        exception.getClass().getSimpleName());
            }
        }
        if (totalEntities > 0) {
            Map<ResourceLocation, String> details = new LinkedHashMap<>();
            entityCounts.forEach((entityId, count) ->
                    details.put(entityId, count + "(" + itemCounts.get(entityId) + "件)"));
            CreateCMPOR.LOGGER.info("实体库存：{} 个实体，合计 {} 件物品，明细 {}",
                    totalEntities, totalItems, details);
        }
    }

    /** 按互斥优先级读取单个实体；能力非空（即使全为空槽）也绝不回退到 NBT。 */
    private static void countEntityIntoSnapshot(Entity entity, HolderLookup.Provider registries,
                                                Map<String, Long> items) {
        if (entity instanceof ContainerEntity container) {
            // ⚠️ 有掉落表的容器实体，其内容不在 itemStacks 里，而在 LootTable 里：
            // ContainerEntity.addChestVehicleSaveData 是「有掉落表就只写 LootTable、不写 Items」，
            // 而 readChestVehicleSaveData 只把掉落表读回字段、不立刻摇取。
            // 于是自然生成结构里的运输矿车（带 LootTable 但玩家还没开过）在 itemStacks 里是全空的 ——
            // 只读 itemStacks 会让这类实体在扫描里零库存，形成与本次修复完全同形的逃逸路径。
            if (container.getLootTable() != null) {
                countLootTableContents(entity, container, registries, items);
            } else {
                for (ItemStack stack : container.getItemStacks()) {
                    ContainerItemExpander.addToSnapshot(stack, stack.getCount(), registries, items);
                }
            }
        } else if (entity instanceof AbstractHorse horse) {
            Container inventory = horse.getInventory();
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                ItemStack stack = inventory.getItem(slot);
                ContainerItemExpander.addToSnapshot(stack, stack.getCount(), registries, items);
            }
        } else {
            IItemHandler handler = entity.getCapability(Capabilities.ItemHandler.ENTITY);
            if (handler != null) {
                for (int slot = 0; slot < handler.getSlots(); slot++) {
                    ItemStack stack = handler.getStackInSlot(slot);
                    ContainerItemExpander.addToSnapshot(stack, stack.getCount(), registries, items);
                }
            } else {
                CompoundTag tag = entity.saveWithoutId(new CompoundTag());
                if (!tag.contains("Items", Tag.TAG_LIST)) {
                    return;
                }
                ListTag contents = tag.getList("Items", Tag.TAG_COMPOUND);
                for (int index = 0; index < contents.size(); index++) {
                    CompoundTag entry = contents.getCompound(index);
                    if (!entry.contains("Slot", Tag.TAG_BYTE) || !entry.contains("id", Tag.TAG_STRING)) {
                        continue;
                    }
                    ItemStack stack = ItemStack.parse(entity.registryAccess(), entry)
                            .orElseThrow(() -> new IllegalArgumentException("实体库存物品 NBT 无效"));
                    ContainerItemExpander.addToSnapshot(stack, stack.getCount(), registries, items);
                }
            }
        }
    }

    /**
     * 把「尚未摇取」的容器掉落表按其自身种子摇一次并计入快照。
     *
     * <p><b>只读语义</b>：不调用 {@code unpackChestVehicleLootTable}（那会把掉落表就地清空改成真实物品），
     * 而是用与 {@code LootTable.fill} <b>完全相同</b>的随机序列与种子（{@code getRandomItems(params, seed, …)}
     * 内部同样是 {@code withOptionalRandomSeed(seed)}）重算一遍。
     * 因此计入的量与玩家日后打开箱子会拿到的内容一致，且<b>不改变实体状态</b>——
     * 评估结束真摇取时结果不变，不会出现"扫描看的是 A、玩家拿到的是 B"的口径漂移。</p>
     *
     * <p>参数集与 NeoForge 的 {@code ContainerEntity.unpackChestVehicleLootTable} 对齐：CHEST、
     * ORIGIN 取实体位置、矿车额外带 ATTACKING_ENTITY。没有玩家参与，故不传幸运值。</p>
     */
    private static void countLootTableContents(Entity entity, ContainerEntity container,
                                               HolderLookup.Provider registries,
                                               Map<String, Long> items) {
        if (!(entity.level() instanceof ServerLevel serverLevel) || serverLevel.getServer() == null) {
            return;
        }
        ResourceKey<LootTable> key = container.getLootTable();
        if (key == null) {
            return;
        }
        LootTable table;
        try {
            table = serverLevel.getServer().reloadableRegistries().getLootTable(key);
        } catch (RuntimeException | LinkageError exception) {
            // 掉落表缺失/第三方数据包损坏：跳过该实体（偏保守，绝不把评估打崩）
            CreateCMPOR.LOGGER.warn("实体库存：容器掉落表 {} 读取失败（{}），已跳过",
                    key.location(), exception.getClass().getSimpleName());
            return;
        }
        if (table == null || table == LootTable.EMPTY) {
            return;
        }
        LootParams.Builder builder = new LootParams.Builder(serverLevel)
                .withParameter(LootContextParams.ORIGIN, container.position());
        if (entity instanceof AbstractMinecartContainer minecart) {
            builder.withParameter(LootContextParams.ATTACKING_ENTITY, minecart);
        }
        LootParams params = builder.create(LootContextParamSets.CHEST);
        // 种子的稳定性：容器矿车/箱子船自带 lootTableSeed 字段，非 0 时原样用（与玩家真开箱一致）；
        // 为 0 时（未设种子）退回 UUID 派生的稳定值 —— 否则 withOptionalRandomSeed(0) 是空操作，
        // 会回退到维度随机源，导致 S1/S2 算出不同的"未来开箱内容"（详见 LootRollSeed）。
        long seed = LootRollSeed.of(entity, container.getLootTableSeed());
        table.getRandomItems(params, seed, stack -> {
            if (stack != null && !stack.isEmpty()) {
                ContainerItemExpander.addToSnapshot(stack, stack.getCount(), registries, items);
            }
        });
    }
}
