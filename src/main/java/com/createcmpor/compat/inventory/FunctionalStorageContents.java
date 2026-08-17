package com.createcmpor.compat.inventory;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 零编译依赖读取 Functional Storage 的抽屉物品。
 *
 * <p>压缩抽屉只追加顶层 {@code Amount + Parent} 的规范数量，不把各压缩档位
 * 再次相加，避免把同一份库存重复计入审计。</p>
 */
final class FunctionalStorageContents {
    private static final String SOURCE = "functionalstorage_drawer";
    private static final String MOD_ID = "functionalstorage";
    private static final String TILE_COMPONENT = "functionalstorage:tile";
    private static final Set<String> DRAWER_WOODS = Set.of(
            "oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "crimson", "warped",
            "mangrove", "cherry", "framed");
    private static final Set<String> COMPACTING_DRAWERS = Set.of(
            "compacting_drawer", "compacting_framed_drawer",
            "simple_compacting_drawer", "framed_simple_compacting_drawer");

    private FunctionalStorageContents() {
    }

    static ContainerItemExpander.ReadResult read(ItemStack stack,
                                                 HolderLookup.Provider registries) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (!MOD_ID.equals(id.getNamespace()) || !isDrawerLike(id.getPath())) {
            return ContainerItemExpander.ReadResult.notApplicable();
        }

        try {
            CompoundTag components = serializedComponents(stack, registries);
            CompoundTag tile = optionalCompound(components, TILE_COMPONENT);
            if (tile == null) {
                return ContainerItemExpander.ReadResult.success(SOURCE, List.of());
            }
            CompoundTag handler = requiredCompound(tile, "handler");
            if (COMPACTING_DRAWERS.contains(id.getPath())) {
                return readCompacting(handler, registries);
            }
            return readNormal(handler, registries);
        } catch (RuntimeException exception) {
            return ContainerItemExpander.ReadResult.failed(SOURCE,
                    "抽屉读取失败: " + exception.getClass().getSimpleName());
        }
    }

    private static ContainerItemExpander.ReadResult readNormal(
            CompoundTag handler, HolderLookup.Provider registries) {
        CompoundTag bigItems = requiredCompound(handler, "BigItems");
        List<ContainerItemExpander.ContainedStack> result = new ArrayList<>();
        for (IndexedTag indexed : orderedCompounds(bigItems)) {
            CompoundTag entry = indexed.tag();
            if (!entry.contains("Amount", Tag.TAG_ANY_NUMERIC)) {
                return ContainerItemExpander.ReadResult.failed(SOURCE,
                        "普通抽屉槽位 " + indexed.index() + " 缺少 Amount");
            }
            long amount = entry.getLong("Amount");
            if (amount <= 0) {
                continue;
            }
            ItemStack item = parseOptionalStack(entry, "Stack", registries);
            if (item.isEmpty()) {
                return ContainerItemExpander.ReadResult.failed(SOURCE,
                        "普通抽屉槽位 " + indexed.index() + " 物品解析失败");
            }
            result.add(new ContainerItemExpander.ContainedStack(
                    amount, item, "fs_drawer[slot=" + indexed.index() + "]"));
        }
        return ContainerItemExpander.ReadResult.success(SOURCE, result);
    }

    private static ContainerItemExpander.ReadResult readCompacting(
            CompoundTag handler, HolderLookup.Provider registries) {
        if (!handler.contains("Amount", Tag.TAG_ANY_NUMERIC)) {
            return ContainerItemExpander.ReadResult.failed(SOURCE,
                    "压缩抽屉缺少 Amount");
        }
        long amount = handler.getLong("Amount");
        if (amount <= 0) {
            return ContainerItemExpander.ReadResult.success(SOURCE, List.of());
        }
        ItemStack parent = parseOptionalStack(handler, "Parent", registries);
        if (parent.isEmpty() && handler.contains("BigItems", Tag.TAG_COMPOUND)) {
            for (IndexedTag indexed : orderedCompounds(handler.getCompound("BigItems"))) {
                parent = parseOptionalStack(indexed.tag(), "Stack", registries);
                if (!parent.isEmpty()) {
                    break;
                }
            }
        }
        if (parent.isEmpty()) {
            return ContainerItemExpander.ReadResult.failed(SOURCE,
                    "压缩抽屉 Parent 无法解析");
        }
        return ContainerItemExpander.ReadResult.success(SOURCE, List.of(
                new ContainerItemExpander.ContainedStack(
                        amount, parent, "fs_compacting[amount=" + amount + "]")));
    }

    private static CompoundTag serializedComponents(ItemStack stack,
                                                     HolderLookup.Provider registries) {
        Tag saved = stack.saveOptional(registries);
        if (!(saved instanceof CompoundTag root)) {
            throw new IllegalArgumentException("ItemStack 不是复合标签");
        }
        Tag rawComponents = root.get("components");
        if (rawComponents == null) {
            return new CompoundTag();
        }
        if (!(rawComponents instanceof CompoundTag components)) {
            throw new IllegalArgumentException("ItemStack components 不是复合标签");
        }
        return components;
    }

    private static CompoundTag optionalCompound(CompoundTag parent, String key) {
        Tag value = parent.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof CompoundTag compound)) {
            throw new IllegalArgumentException(key + " 不是复合标签");
        }
        return compound;
    }

    private static CompoundTag requiredCompound(CompoundTag parent, String key) {
        CompoundTag value = optionalCompound(parent, key);
        if (value == null) {
            throw new IllegalArgumentException(key + " 缺失");
        }
        return value;
    }

    private static ItemStack parseOptionalStack(CompoundTag parent, String key,
                                                HolderLookup.Provider registries) {
        Tag raw = parent.get(key);
        return raw instanceof CompoundTag stackTag
                ? ItemStack.parseOptional(registries, stackTag)
                : ItemStack.EMPTY;
    }

    private static List<IndexedTag> orderedCompounds(CompoundTag parent) {
        List<IndexedTag> result = new ArrayList<>();
        for (String key : parent.getAllKeys()) {
            int index;
            try {
                index = Integer.parseInt(key);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("抽屉存在非法数字键: " + key);
            }
            Tag value = parent.get(key);
            if (!(value instanceof CompoundTag compound) || index < 0) {
                throw new IllegalArgumentException("抽屉槽位数据无效: " + key);
            }
            result.add(new IndexedTag(index, compound));
        }
        result.sort(Comparator.comparingInt(IndexedTag::index));
        return result;
    }

    private static boolean isDrawerLike(String path) {
        if (COMPACTING_DRAWERS.contains(path)) {
            return true;
        }
        return DRAWER_WOODS.stream().anyMatch(wood ->
                path.equals(wood + "_1") || path.equals(wood + "_2") || path.equals(wood + "_4"));
    }

    private record IndexedTag(int index, CompoundTag tag) {
    }
}
