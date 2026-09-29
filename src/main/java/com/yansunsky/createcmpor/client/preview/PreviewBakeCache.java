package com.yansunsky.createcmpor.client.preview;

import com.yansunsky.createcmpor.CreateCMPOR;
import com.yansunsky.createcmpor.block.FactoryBlockEntity;
import com.yansunsky.createcmpor.preview.PreviewSnapshot;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 微缩预览的两侧烘焙缓存。
 *
 * <ul>
 *     <li><b>方块侧</b>：{@code WeakHashMap<FactoryBlockEntity, PreviewBaked>}——工厂 BE 是天然的
 *         弱键（区块卸载/方块破坏后不阻止回收），"缓存是否过期"由调用方按快照身份判断。</li>
 *     <li><b>物品侧</b>：手持物品预览没有 BE 可当键，故分两级——第一级是
 *         {@code IdentityHashMap<CustomData, PreviewBaked>} 的身份快路径（组件没变时每帧 O(1) 命中，
 *         连 NBT 都不用解析）；第二级是用内容指纹（{@code PreviewBaked.contentHash(snapshot)}）当键的 LRU
 *         （容量 {@value #ITEM_CAPACITY}），另配一个同样上限的失败集，
 *         避免"烘不出来的内容"每帧重试。</li>
 * </ul>
 *
 * <p><b>light 不参与任何缓存键</b>：光照在渲染期施加（{@link PreviewRender}），
 * 光照变化不再导致重烘。这是 0.4.0 的行为变化点之一（观感不变，重烘次数变少）。
 *
 * <p>只在客户端渲染线程访问，无同步措施（与原实现一致）。
 */
public final class PreviewBakeCache {

    /** 物品侧 LRU 容量。 */
    public static final int ITEM_CAPACITY = 32;

    /** 快照在 BE NBT 里的键（与 {@code FactoryBlockEntity} 双写一致）。 */
    private static final String KEY_PREVIEW = "preview";

    /** 身份快路径的规模上限（见 {@link #remember}）。 */
    private static final int ITEM_RESOLVED_LIMIT = 512;

    private static final Map<FactoryBlockEntity, PreviewBaked> BLOCKS = new WeakHashMap<>();

    /** accessOrder=true 的 LinkedHashMap + removeEldestEntry = 最简 LRU。 */
    private static final Map<Long, PreviewBaked> ITEMS = new LinkedHashMap<>(16, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, PreviewBaked> eldest) {
            return size() > ITEM_CAPACITY;
        }
    };

    /** 失败集：物品侧烘失败的内容指纹（同样 LRU，避免无限增长）。 */
    private static final Set<Long> ITEM_FAILURES = Collections.newSetFromMap(new LinkedHashMap<>(16, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
            return size() > ITEM_CAPACITY;
        }
    });

    /**
     * 物品侧"按组件身份"的已解析表（{@code null} 值 = 已知失败，别再重试）。
     *
     * <p>键必须是 {@link IdentityHashMap}：{@code CustomData.hashCode()} 是
     * {@code CompoundTag.hashCode()}（整棵 NBT 递归哈希），比解析快照本身还贵。
     */
    private static final Map<CustomData, PreviewBaked> ITEM_RESOLVED = new IdentityHashMap<>();

    /** 物品侧上一次"真正开烘"的客户端 tick（每 tick 一份的烘焙预算，见 {@link #resolveItem}）。 */
    private static long lastItemBakeTick = Long.MIN_VALUE;

    private PreviewBakeCache() {
    }

    public static PreviewBaked getBlock(FactoryBlockEntity be) {
        return BLOCKS.get(be);
    }

    public static void putBlock(FactoryBlockEntity be, PreviewBaked baked) {
        BLOCKS.put(be, baked);
    }

    /** 距离剔除等场景下主动丢弃（原来直接写在渲染器里的 {@code CACHE.remove(be)}）。 */
    public static void evictBlock(FactoryBlockEntity be) {
        BLOCKS.remove(be);
    }

    /** 物品侧查询（只在本类的解析流程里用）：未命中返回 {@code null}。 */
    private static PreviewBaked getItem(long contentHash) {
        return ITEMS.get(contentHash);
    }

    private static void putItem(long contentHash, PreviewBaked baked) {
        ITEMS.put(contentHash, baked);
        ITEM_FAILURES.remove(contentHash);
    }

    private static void markItemFailed(long contentHash) {
        ITEM_FAILURES.add(contentHash);
    }

    /**
     * <b>手持物品侧的完整解析入口</b>：身份快路径 → 解析快照 → 内容指纹 LRU → 必要时烘焙。
     *
     * <p>为什么要有"身份快路径"：{@code ItemStack} 的组件是不可变的，
     * {@code stack.get(BLOCK_ENTITY_DATA)} 在组件没变时<b>返回同一个 {@code CustomData} 实例</b>
     * （组件不可变契约），所以用 {@link IdentityHashMap} 可以每帧 O(1) 直接命中，
     * <b>完全避开 NBT 深解析</b>。这一点必须做：1.21.1 没有 {@code ItemStackRenderState}，
     * {@code renderByItem} 每帧每槽位都会被调用一次，而 {@link PreviewSnapshot#load} 要走调色板的
     * {@code BlockStateParser} 解析——每帧几十个槽位就是几百次字符串解析。
     *
     * <p>返回 {@code null} = 本帧不画预览（无数据 / 解析失败 / 已记失败 / 本 tick 的烘焙预算已用完）。
     * 三种"不画"的区别只影响<b>下次是否重试</b>：
     * <ul>
     *     <li>无数据/解析失败/烘焙失败 → 记进身份表（{@code null} 值）与失败集，之后不再重试；</li>
     *     <li>预算用尽 → <b>不记</b>，下一 tick 自然重试（首个未命中的槽位先画）。</li>
     * </ul>
     *
     * <p>每客户端 tick 最多新烘一份：进入物品栏的第一帧可能同时出现几十个携带不同快照的工厂物品，
     * 不设预算会出现数百 ms 级尖峰；未中签的槽位这一帧只画机壳，观感是"逐个亮起来"。
     */
    public static PreviewBaked resolveItem(CustomData data, Level level) {
        if (ITEM_RESOLVED.containsKey(data)) {
            return ITEM_RESOLVED.get(data); // 命中（含"已知失败"= null 值）
        }
        PreviewSnapshot snapshot = readSnapshot(data);
        if (snapshot == null || snapshot.nonAirCount() == 0) {
            remember(data, null);
            return null;
        }
        long contentHash = PreviewBaked.contentHash(snapshot);
        PreviewBaked cached = getItem(contentHash);
        if (cached != null) {
            remember(data, cached);
            return cached;
        }
        if (ITEM_FAILURES.contains(contentHash)) { // 已知失败的内容：不再重试
            remember(data, null);
            return null;
        }
        long tick = AnimationTickHolder.getTicks();
        if (tick == lastItemBakeTick) {
            return null; // 本 tick 的烘焙预算已用完：不记失败，下一 tick 重试
        }
        lastItemBakeTick = tick;
        PreviewBaked baked = FactoryPreviewBaker.bake(level, snapshot);
        if (baked == null) {
            markItemFailed(contentHash);
            remember(data, null);
            return null;
        }
        putItem(contentHash, baked);
        remember(data, baked);
        return baked;
    }

    /**
     * 从物品携带的 BE NBT 里取 {@code preview} 子标签。
     *
     * <p>键名与 {@code FactoryBlockEntity.write/read} 一致（顶层 {@code preview}）。
     * 缺失/类型不符/版本不符一律返回 {@code null}——预览是纯装饰数据，静默降级。
     *
     * <p><b>为什么用已弃用的 {@code getUnsafe()}</b>：这里只读不写，而另一个非弃用入口
     * {@code copyTag()} 会<b>整份复制</b> BE NBT（工厂 NBT 含速率/pattern 表，可达数 KB）——
     * 对一条"每个物品第一次出现时走一次"的渲染路径不划算。物品的组件是不可变的，读到的 tag
     * 不会被别处就地改写（若将来有代码这么做，最坏后果是"预览滞后到下次换组件"，不崩、不影响存档）。
     */
    @SuppressWarnings("deprecation")
    private static PreviewSnapshot readSnapshot(CustomData data) {
        try {
            CompoundTag tag = data.getUnsafe();
            if (!tag.contains(KEY_PREVIEW, Tag.TAG_COMPOUND)) {
                return null;
            }
            return PreviewSnapshot.load(tag.getCompound(KEY_PREVIEW));
        } catch (Throwable error) {
            CreateCMPOR.LOGGER.debug("[预览] 物品预览快照解析失败，该物品只画机壳", error);
            return null;
        }
    }

    /**
     * 写入身份表。超限时<b>整体丢弃</b>而不是逐条淘汰：这张表只是"省一次 NBT 解析"的快路径，
     * 丢掉最坏后果是下一帧重新解析一次，但能保证它不会随"见过的 ItemStack 数"无限增长。
     */
    private static void remember(CustomData data, PreviewBaked baked) {
        if (ITEM_RESOLVED.size() >= ITEM_RESOLVED_LIMIT) {
            ITEM_RESOLVED.clear();
        }
        ITEM_RESOLVED.put(data, baked);
    }

    /**
     * 清空全部缓存（资源重载/退出世界时调用，避免持有旧 BakedModel 产生的顶点数据）。
     *
     * <p>动态部件的解析缓存（含"某状态已锁存为动态失败"的标记）必须一起清——它同样持有
     * {@code SuperByteBuffer}，资源重载后旧实例的顶点数据已失效。
     */
    public static void clear() {
        BLOCKS.clear();
        ITEMS.clear();
        ITEM_FAILURES.clear();
        ITEM_RESOLVED.clear();
        lastItemBakeTick = Long.MIN_VALUE;
        PreviewDynamicParts.clear();
    }
}
