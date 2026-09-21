package com.yansunsky.createcmpor.evaluation;

import com.yansunsky.createcmpor.Config;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * 黑名单策略的<b>唯一判定入口</b>（方块 / 实体 / 物品三档）。
 *
 * <p>本类存在的意义是避免"同一份黑名单在多处各判一遍"导致的语义漂移——
 * 0.3.45 修复的 {@code suspiciousMods} 只在实体层生效、方块层完全失效，正是这种漂移的结果
 * （区块 palette 只查 {@code suspiciousBlocks}，contraption palette 又各写了一份）。
 * 新增调用点时<b>一律走本类</b>，不要就地读配置。</p>
 *
 * <h2>三档语义（对齐旧 CompactMachinesPOR {@code Core.hasSuspiciousBlocks}）</h2>
 * <ul>
 *   <li>{@code suspiciousMods}：<b>命名空间</b>级——该模组的方块与实体都拒绝（用于 capability
 *       不可审计的存储模组）。</li>
 *   <li>{@code suspiciousBlocks}：精确方块 id。</li>
 *   <li>{@code suspiciousItems}：精确物品 id——覆盖容器槽位、嵌套容器内部、掉落物、contraption 携带物。</li>
 * </ul>
 *
 * <p>命中一律抛出 {@link EvaluationStorageBridge.UnsupportedContentException}（fail-closed），
 * 由 {@code EvaluationCloneManager} 统一转为安全取消 + 聊天提示。</p>
 */
public final class Blacklist {
    private Blacklist() {
    }

    /**
     * 方块判定：先精确 id，再命名空间。
     *
     * @param id 方块注册名；null 时安全返回
     */
    public static void checkBlock(ResourceLocation id) {
        if (id == null) {
            return;
        }
        List<? extends String> blocks = Config.SUSPICIOUS_BLOCKS.get();
        if (!blocks.isEmpty() && blocks.contains(id.toString())) {
            throw new EvaluationStorageBridge.UnsupportedContentException(
                    "message.createcmpor.evaluation.block_blacklisted");
        }
        List<? extends String> mods = Config.SUSPICIOUS_MODS.get();
        if (!mods.isEmpty() && mods.contains(id.getNamespace())) {
            throw new EvaluationStorageBridge.UnsupportedContentException(
                    "message.createcmpor.evaluation.block_mod_blacklisted");
        }
    }

    /** 方块判定便利重载：直接吃 palette / NBT 里的 {@code Name} 字符串。 */
    public static void checkBlock(String id) {
        checkBlock(id == null ? null : ResourceLocation.tryParse(id));
    }

    /**
     * 实体判定：命名空间级（{@code suspiciousMods}）。
     *
     * @param id 实体类型注册名
     */
    public static void checkEntity(ResourceLocation id) {
        if (id == null) {
            return;
        }
        List<? extends String> mods = Config.SUSPICIOUS_MODS.get();
        if (!mods.isEmpty() && mods.contains(id.getNamespace())) {
            throw new EvaluationStorageBridge.UnsupportedContentException(
                    "message.createcmpor.evaluation.entity_mod_blacklisted");
        }
    }

    /**
     * 物品判定：精确 id（{@code suspiciousItems}）。
     *
     * @param id 物品注册名
     */
    public static void checkItem(ResourceLocation id) {
        if (id == null) {
            return;
        }
        List<? extends String> items = Config.SUSPICIOUS_ITEMS.get();
        if (!items.isEmpty() && items.contains(id.toString())) {
            throw new EvaluationStorageBridge.UnsupportedContentException(
                    "message.createcmpor.evaluation.item_blacklisted");
        }
    }
}
