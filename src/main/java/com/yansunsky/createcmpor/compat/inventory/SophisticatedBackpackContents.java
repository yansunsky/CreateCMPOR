package com.yansunsky.createcmpor.compat.inventory;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 零编译依赖读取 Sophisticated Backpacks 的背包物品。
 *
 * <p>背包物品只保留 {@code sophisticatedcore:storage_uuid}，内容在
 * {@code BackpackStorage.backpackContents} SavedData 中，故这里仅反射访问
 * 已在 ItemScan 项目验证过的公开 get() 与私有映射。</p>
 *
 * <p><b>只统计存储槽（inventory）</b>：升级槽（upgradeInventory）是配置型物品
 * （升级组件），不是房间库存，不参与三扫描/净平衡（0.3.17 起）。</p>
 */
final class SophisticatedBackpackContents {
    private static final String SOURCE = "sophisticated_backpack";
    private static final ResourceLocation STORAGE_UUID_ID =
            ResourceLocation.fromNamespaceAndPath("sophisticatedcore", "storage_uuid");

    private SophisticatedBackpackContents() {
    }

    static ContainerItemExpander.ReadResult read(ItemStack stack,
                                                 HolderLookup.Provider registries) {
        var componentType = BuiltInRegistries.DATA_COMPONENT_TYPE.get(STORAGE_UUID_ID);
        if (componentType == null || !stack.has(componentType)) {
            return ContainerItemExpander.ReadResult.notApplicable();
        }
        Object rawUuid = stack.get(componentType);
        if (!(rawUuid instanceof UUID uuid)) {
            return ContainerItemExpander.ReadResult.failed(SOURCE,
                    "storage_uuid 组件不是 UUID");
        }

        try {
            CompoundTag contents = readContents(uuid);
            if (contents == null) {
                return ContainerItemExpander.ReadResult.success(SOURCE,
                        "sbp:" + uuid, List.of());
            }
            List<ContainerItemExpander.ContainedStack> result = new ArrayList<>();
            // 升级槽（upgradeInventory）装的是配置型升级物品（磁铁/充电器/音栅等），
            // 严格意义上不是房间库存，不参与三扫描/净平衡统计（S0 全空判定也不该被它污染）。
            // 升级物品若放入普通存储槽仍会被 inventory 正常统计（精确识别槽位语义）。
            String error = readSlotSection(contents, "inventory", "backpack", registries, result);
            if (error != null) {
                return ContainerItemExpander.ReadResult.failed(SOURCE, error);
            }
            return ContainerItemExpander.ReadResult.success(SOURCE,
                    "sbp:" + uuid, result);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return ContainerItemExpander.ReadResult.failed(SOURCE,
                    "内容读取失败: " + exception.getClass().getSimpleName());
        } catch (LinkageError error) {
            return ContainerItemExpander.ReadResult.failed(SOURCE,
                    "类不可用: " + error.getClass().getSimpleName());
        }
    }

    @SuppressWarnings("unchecked")
    private static CompoundTag readContents(UUID uuid) throws ReflectiveOperationException {
        Class<?> storageClass = Class.forName(
                "net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackStorage");
        Method getMethod = storageClass.getMethod("get");
        Object storage = getMethod.invoke(null);
        Field contentsField = storageClass.getDeclaredField("backpackContents");
        contentsField.setAccessible(true);
        Object rawContents = contentsField.get(storage);
        if (!(rawContents instanceof Map<?, ?> contentsMap)) {
            throw new IllegalStateException("backpackContents 不是 Map");
        }
        Object value = contentsMap.get(uuid);
        return value instanceof CompoundTag tag ? tag : null;
    }

    private static String readSlotSection(CompoundTag contents, String sectionName,
                                          String displayName,
                                          HolderLookup.Provider registries,
                                          List<ContainerItemExpander.ContainedStack> output) {
        if (!contents.contains(sectionName, Tag.TAG_COMPOUND)) {
            return null;
        }
        CompoundTag section = contents.getCompound(sectionName);
        if (!section.contains("Items", Tag.TAG_LIST)) {
            return null;
        }
        ListTag entries = section.getList("Items", Tag.TAG_COMPOUND);
        for (int index = 0; index < entries.size(); index++) {
            CompoundTag itemTag = entries.getCompound(index);
            if (itemTag.isEmpty() || !itemTag.contains("count", Tag.TAG_ANY_NUMERIC)) {
                continue;
            }
            long amount = itemTag.getLong("count");
            if (amount <= 0) {
                continue;
            }
            int slot = itemTag.getInt("Slot");
            if (slot < 0) {
                return displayName + " 存在负数槽位: " + slot;
            }
            ItemStack item = parseOversized(itemTag, registries);
            if (item.isEmpty()) {
                return displayName + " 槽位 " + slot + " 物品解析失败";
            }
            output.add(new ContainerItemExpander.ContainedStack(
                    amount, item, "sbp:" + displayName + "[" + slot + "]"));
        }
        return null;
    }

    private static ItemStack parseOversized(CompoundTag itemTag,
                                            HolderLookup.Provider registries) {
        if (itemTag.getLong("count") > 99) {
            CompoundTag slim = itemTag.copy();
            slim.putInt("count", 1);
            return ItemStack.parseOptional(registries, slim);
        }
        return ItemStack.parseOptional(registries, itemTag);
    }
}
