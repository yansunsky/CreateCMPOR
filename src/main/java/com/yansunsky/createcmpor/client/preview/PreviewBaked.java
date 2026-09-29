package com.yansunsky.createcmpor.client.preview;

import com.yansunsky.createcmpor.preview.PreviewSnapshot;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次微缩预览烘焙的产物。
 *
 * <p>字段：
 * <ul>
 *     <li>{@code layers}：逐 {@link RenderType} 的顶点缓冲，<b>保持插入顺序</b>
 *         （图层顺序影响半透明混合结果，故用 LinkedHashMap 复制而不是 Map.copyOf）；</li>
 *     <li>{@code contentHash}：快照内容的 64 位指纹，供<b>没有 BE 可用作缓存键</b>的一侧
 *         （手持物品预览）当 LRU 键；</li>
 *     <li>{@code snapshot}：生成该产物的快照本体。方块侧用它做 O(1) 的"缓存是否过期"判断
 *         （快照是整体替换的，见 {@code FactoryBlockEntity.installPreview}，身份比较即语义比较），
 *         渲染侧用它取网格尺寸算 1/N 缩放。</li>
 * </ul>
 *
 * <p><b>刻意不含 light 字段</b>：光照不再烘进顶点，而是在渲染期由
 * {@link PreviewRender} 用 {@code SuperByteBuffer.light(packedLight)} 施加，
 * 因此光照变化不再触发整块重烘。
 */
public record PreviewBaked(PreviewSnapshot snapshot, long contentHash, Map<RenderType, SuperByteBuffer> layers) {

    /** FNV-1a 64 位哈希（自己实现，避免依赖任何可能随版本变化的哈希工具）。 */
    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    public PreviewBaked {
        layers = Collections.unmodifiableMap(new LinkedHashMap<>(layers));
    }

    /**
     * 快照内容指纹：网格尺寸 + 调色板方块状态 + 逐格调色板索引 + 逐格连通组号。
     *
     * <p>只用于<b>会话内</b>的缓存键，不落盘、不跨会话比较：调色板条目用
     * {@link Block#getId(BlockState)}（注册表状态 id）参与哈希，注册表 id 在会话内稳定。
     * 组表缺失与"组表全 0"指纹相同——这对烘焙恰好正确，因为烘焙侧也只读
     * {@code snapshot.groupAt(...) == 0} 这一个判据。
     *
     * <p>碰撞概率按 64 位计可忽略；真撞了也只是复用到另一份微缩内容（纯装饰数据），
     * 不会崩、不会污染存档。
     */
    public static long contentHash(PreviewSnapshot snapshot) {
        long hash = FNV_OFFSET_BASIS;
        hash = mix(hash, snapshot.width());
        hash = mix(hash, snapshot.height());
        hash = mix(hash, snapshot.depth());
        for (BlockState state : snapshot.palette()) {
            hash = mix(hash, Block.getId(state));
        }
        int volume = snapshot.volume();
        for (int i = 0; i < volume; i++) {
            hash = mix(hash, snapshot.indexAt(i));
        }
        for (int i = 0; i < volume; i++) {
            hash = mix(hash, snapshot.groupAt(i));
        }
        return hash;
    }

    private static long mix(long hash, int value) {
        for (int shift = 0; shift < Integer.SIZE; shift += 8) {
            hash ^= (value >>> shift) & 0xFFL;
            hash *= FNV_PRIME;
        }
        return hash;
    }
}
