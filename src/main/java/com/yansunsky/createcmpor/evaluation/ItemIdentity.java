package com.yansunsky.createcmpor.evaluation;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * 物品身份签名：{@code item id} + 组件规范化摘要。
 *
 * <p>1.20.5+ 起大量物品差异（{@code potion_contents}、附魔、自定义名、染色、容器内容等）
 * 由 {@code DataComponents} 承载，仅凭 item id 无法区分两种物品。本类给出稳定的"身份字符串"，
 * 使 <b>水瓶</b>（{@code minecraft:potion#<hash>}）与同 id 的其他药水不再被合并成同一条通道，
 * 也让"同 id 转化"产线（水瓶 → 粗制药水）不再在净平衡里互相抵消。</p>
 *
 * <p>格式：无组件时为 {@code id} 本身；有组件时为 {@code id#<sha256前16位>}。
 * 摘要复用 {@link CanonicalNbtHasher}（compound key 排序后哈希），跨会话稳定。
 * 旧存档/纯 id 场景读到的无 {@code #} 键即视为"无组件身份"，与原行为一致。</p>
 */
public final class ItemIdentity {
    /** 身份与摘要的分隔符。 */
    private static final char SEPARATOR = '#';
    /** 摘要长度（sha256 前 16 位十六进制；碰撞概率在 mod 场景可忽略）。 */
    private static final int HASH_LENGTH = 16;

    private ItemIdentity() {
    }

    /** 无组件身份（流体 / 纯 id 场景）：signature = id 字符串。 */
    public static String of(ResourceLocation id) {
        return id == null ? "" : id.toString();
    }

    /** 物品身份：{@code id#<组件摘要>}；无组件（组件补丁为空）时退化为 {@link #of(ResourceLocation)}。 */
    public static String of(ItemStack stack, HolderLookup.Provider registries) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (stack.getComponentsPatch().isEmpty()) {
            return of(id);
        }
        Tag saved = stack.copyWithCount(1).saveOptional(registries);
        String hash = CanonicalNbtHasher.sha256(saved);
        return id + String.valueOf(SEPARATOR) + hash.substring(0, HASH_LENGTH);
    }

    /** 从签名取回 item id（{@code #} 前段）；无法解析返回 null。 */
    public static ResourceLocation idOf(String signature) {
        if (signature == null || signature.isEmpty()) {
            return null;
        }
        int index = signature.indexOf(SEPARATOR);
        return ResourceLocation.tryParse(index < 0 ? signature : signature.substring(0, index));
    }

    /** 是否为带组件签名（含 {@code #} 摘要）。 */
    public static boolean hasComponents(String signature) {
        return signature != null && signature.indexOf(SEPARATOR) >= 0;
    }
}
