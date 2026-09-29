package com.yansunsky.createcmpor.client.preview;

import com.yansunsky.createcmpor.block.FactoryBlockEntity;

import java.util.Collections;
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
 *     <li><b>物品侧</b>：手持物品预览没有 BE 可当键，故用内容指纹（{@link PreviewBaked#contentHash}）
 *         当键的 LRU（容量 {@value #ITEM_CAPACITY}），另配一个同样上限的失败集，
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

    /** 物品侧查询：未命中或已知失败都返回 {@code null}（失败态由 {@link #isItemFailed} 单独问）。 */
    public static PreviewBaked getItem(long contentHash) {
        return ITEMS.get(contentHash);
    }

    public static void putItem(long contentHash, PreviewBaked baked) {
        ITEMS.put(contentHash, baked);
        ITEM_FAILURES.remove(contentHash);
    }

    public static boolean isItemFailed(long contentHash) {
        return ITEM_FAILURES.contains(contentHash);
    }

    public static void markItemFailed(long contentHash) {
        ITEM_FAILURES.add(contentHash);
    }

    /** 清空全部缓存（资源重载/退出世界时调用，避免持有旧 BakedModel 产生的顶点数据）。 */
    public static void clear() {
        BLOCKS.clear();
        ITEMS.clear();
        ITEM_FAILURES.clear();
    }
}
