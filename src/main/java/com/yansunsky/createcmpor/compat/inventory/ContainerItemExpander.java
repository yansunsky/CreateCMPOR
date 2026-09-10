package com.yansunsky.createcmpor.compat.inventory;

import com.yansunsky.createcmpor.CreateCMPOR;
import com.yansunsky.createcmpor.evaluation.ItemIdentity;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把库存槽位中的容器类物品展开为审计快照中的内部物品。
 *
 * <p>外层容器物品由调用方先按原有逻辑计入，展开器只追加内部物品，
 * 这样容器本身被消耗或产出时仍然能够被守恒审计捕获。</p>
 */
public final class ContainerItemExpander {
    private static final int MAX_DEPTH = 8;

    private ContainerItemExpander() {
    }

    /**
     * 将一个槽位物品及其内部内容追加到快照。
     *
     * @param stack       槽位中的物品
     * @param amount      外层物品数量
     * @param registries  当前世界的注册表访问器
     * @param items       目标物品快照
     */
    public static void addToSnapshot(ItemStack stack, long amount,
                                     HolderLookup.Provider registries,
                                     Map<String, Long> items) {
        if (stack == null || stack.isEmpty() || amount <= 0) {
            return;
        }

        ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (isConfigOnlyItem(id)) {
            return; // 配置型物品（createsifter 筛网等）不是房间库存，不统计
        }
        // 身份签名（id + 组件摘要）：不同组件变体各自成桶，不再被合并计数
        merge(items, ItemIdentity.of(stack, registries), amount);
        expand(stack.copyWithCount(1), amount, registries, items,
                0, new HashSet<>(), new HashSet<>());
    }

    /**
     * 判定"配置型物品"（严格意义上不是房间库存，不参与三扫描/净平衡）：
     * 目前覆盖 createsifter 的筛网（{@code <metal>_mesh} / custom_mesh / sturdy_mesh 等，
     * 全部以 {@code _mesh} 结尾，与其原料/产物（raw_*_piece/pebble/crushed_* 等）无冲突）。
     *
     * <p>与精妙背包升级槽（{@link SophisticatedBackpackContents} 只读 inventory）同类的
     * 配置型排除；注意：判定按"物品本身"而非槽位 —— 玩家把筛网放进普通容器也不会被统计
     * （配置型耗材语义，用户确认）。</p>
     */
    static boolean isConfigOnlyItem(ResourceLocation id) {
        return "createsifter".equals(id.getNamespace()) && id.getPath().endsWith("_mesh");
    }

    private static void expand(ItemStack stack, long multiplier,
                               HolderLookup.Provider registries,
                               Map<String, Long> items,
                               int depth, Set<String> activeIdentities,
                               Set<String> reportedFailures) {
        if (stack.isEmpty() || multiplier <= 0 || depth >= MAX_DEPTH) {
            return;
        }

        List<ReadResult> results = new ArrayList<>();
        results.add(readVanillaContainers(stack));
        if (ModList.get().isLoaded("sophisticatedbackpacks")) {
            results.add(SophisticatedBackpackContents.read(stack, registries));
        }
        if (ModList.get().isLoaded("ae2")) {
            results.add(Ae2CellContents.read(stack));
        }
        if (ModList.get().isLoaded("functionalstorage")) {
            results.add(FunctionalStorageContents.read(stack, registries));
        }

        for (ReadResult result : results) {
            if (!result.applicable()) {
                continue;
            }
            if (!result.success()) {
                reportFailure(result.source(), result.error(), reportedFailures);
                continue;
            }

            String identity = result.identity();
            if (identity != null && !activeIdentities.add(identity)) {
                continue;
            }
            for (ContainedStack contained : result.contents()) {
                long containedAmount = saturatingMultiply(multiplier, contained.amount());
                if (containedAmount <= 0 || contained.stack().isEmpty()) {
                    continue;
                }
                ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(contained.stack().getItem());
                if (!isConfigOnlyItem(id)) {
                    merge(items, ItemIdentity.of(contained.stack(), registries), containedAmount);
                }
                expand(contained.stack(), containedAmount, registries, items,
                        depth + 1, activeIdentities, reportedFailures);
            }
            if (identity != null) {
                activeIdentities.remove(identity);
            }
        }
    }

    private static ReadResult readVanillaContainers(ItemStack stack) {
        List<ContainedStack> contents = new ArrayList<>();
        ItemContainerContents container = stack.get(DataComponents.CONTAINER);
        if (container != null) {
            for (int slot = 0; slot < container.getSlots(); slot++) {
                ItemStack child = container.getStackInSlot(slot);
                if (!child.isEmpty()) {
                    contents.add(new ContainedStack(child.getCount(), child,
                            "minecraft:container[slot=" + slot + "]"));
                }
            }
        }

        BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null) {
            bundle.items().forEach(child -> {
                if (!child.isEmpty()) {
                    contents.add(new ContainedStack(child.getCount(), child,
                            "minecraft:bundle"));
                }
            });
        }
        if (contents.isEmpty()) {
            return ReadResult.notApplicable();
        }
        return ReadResult.success("minecraft:container", contents);
    }

    private static void merge(Map<String, Long> items,
                              String signature, long amount) {
        if (signature == null || signature.isEmpty() || amount <= 0) {
            return;
        }
        items.merge(signature, amount, ContainerItemExpander::saturatingAdd);
    }

    static long saturatingMultiply(long left, long right) {
        if (left <= 0 || right <= 0) {
            return 0;
        }
        if (left > Long.MAX_VALUE / right) {
            return Long.MAX_VALUE;
        }
        return left * right;
    }

    private static long saturatingAdd(long left, long right) {
        if (right > Long.MAX_VALUE - left) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static void reportFailure(String source, String error, Set<String> reportedFailures) {
        String key = source + ":" + error;
        if (reportedFailures.add(key)) {
            CreateCMPOR.LOGGER.warn("评估库存容器读取失败（{}）：{}；保留容器本身统计", source, error);
        }
    }

    record ContainedStack(long amount, ItemStack stack, String path) {
        ContainedStack {
            if (amount <= 0 || stack == null || stack.isEmpty()) {
                throw new IllegalArgumentException("容器内部物品无效");
            }
            stack = stack.copyWithCount(1);
        }
    }

    record ReadResult(boolean applicable, List<ContainedStack> contents,
                      String source, String error, String identity) {
        ReadResult {
            contents = List.copyOf(contents);
        }

        static ReadResult notApplicable() {
            return new ReadResult(false, List.of(), "unknown", null, null);
        }

        static ReadResult success(String source, List<ContainedStack> contents) {
            return new ReadResult(true, contents, source, null, null);
        }

        static ReadResult success(String source, String identity, List<ContainedStack> contents) {
            return new ReadResult(true, contents, source, null, identity);
        }

        static ReadResult failed(String source, String error) {
            return new ReadResult(true, List.of(), source, error, null);
        }

        boolean success() {
            return error == null;
        }
    }
}
