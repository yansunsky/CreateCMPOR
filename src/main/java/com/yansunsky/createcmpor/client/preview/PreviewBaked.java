package com.yansunsky.createcmpor.client.preview;

import com.yansunsky.createcmpor.preview.PreviewSnapshot;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次微缩预览烘焙的产物。
 *
 * <p>字段：
 * <ul>
 *     <li>{@code layers}：逐 {@link RenderType} 的顶点缓冲，<b>保持插入顺序</b>
 *         （图层顺序影响半透明混合结果，故用 LinkedHashMap 复制而不是 Map.copyOf）。
 *         <b>动态格不在这里</b>——整块旋转类机型（轴/齿轮/粉碎轮/飞轮块/转盘/动力轴/龙门轴）
 *         被刻意排除在静态烘焙之外，否则静态副本会与动态旋转副本叠加；</li>
 *     <li>{@code contentHash}：快照内容的 64 位指纹，供<b>没有 BE 可用作缓存键</b>的一侧
 *         （手持物品预览）当 LRU 键；</li>
 *     <li>{@code snapshot}：生成该产物的快照本体。方块侧用它做 O(1) 的"缓存是否过期"判断
 *         （快照是整体替换的，见 {@code FactoryBlockEntity.installPreview}，身份比较即语义比较），
 *         渲染侧用它取网格尺寸算 1/N 缩放；</li>
 *     <li>{@code dynamicCells}：每帧"窄 pass"的绘制计划（转速、旋转轴、相位，已按动画周期整表缩放）。
 *         为空 = 纯静态（v2 旧档、没采到转速、或动画被服务器关成 0 秒）。</li>
 *     <li>{@code entityScene}（v4）：已重建的实体（生物/掉落物/展示框…），烘焙期一次性造好、
 *         渲染期逐只交给 {@code EntityRenderDispatcher}。空场景用 {@link PreviewEntityScene#EMPTY}
 *         （v2/v3 旧档、房间内没有实体、或实体全部重建失败），<b>永不为 null</b>。</li>
 * </ul>
 *
 * <p><b>刻意不含 light 字段</b>：光照不再烘进顶点，而是在渲染期由
 * {@link PreviewRender} 用 {@code SuperByteBuffer.light(packedLight)} 施加，
 * 因此光照变化不再触发整块重烘。
 */
public record PreviewBaked(PreviewSnapshot snapshot, long contentHash, Map<RenderType, SuperByteBuffer> layers,
                           List<PreviewDynamicCell> dynamicCells, PreviewEntityScene entityScene) {

    /** FNV-1a 64 位哈希（自己实现，避免依赖任何可能随版本变化的哈希工具）。 */
    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    public PreviewBaked {
        layers = Collections.unmodifiableMap(new LinkedHashMap<>(layers));
        dynamicCells = List.copyOf(dynamicCells);
        entityScene = entityScene == null ? PreviewEntityScene.EMPTY : entityScene;
    }

    /** 是否有可动的格（无则渲染侧整段跳过，零额外开销）。 */
    public boolean hasDynamicCells() {
        return !dynamicCells.isEmpty();
    }

    /** 是否有实体要画。 */
    public boolean hasEntities() {
        return !entityScene.isEmpty();
    }

    /**
     * 快照内容指纹：网格尺寸 + 调色板方块状态 + 逐格调色板索引 + 逐格连通组号 + 稀疏转速表。
     *
     * <p>只用于<b>会话内</b>的缓存键，不落盘、不跨会话比较：调色板条目用
     * {@link Block#getId(BlockState)}（注册表状态 id）参与哈希，注册表 id 在会话内稳定。
     * 组表缺失与"组表全 0"指纹相同——这对烘焙恰好正确，因为烘焙侧也只读
     * {@code snapshot.groupAt(...) == 0} 这一个判据。
     *
     * <p><b>转速也要进指纹</b>：否则"只有转速变了"的两份快照（例如同布局不同转速的信标）会命中同一条 LRU 记录，
     * 手持物品侧就会画出另一个转速的动画。
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
        hash = mix(hash, Float.floatToIntBits(snapshot.animationSeconds()));
        for (int i = 0; i < snapshot.movingCount(); i++) {
            hash = mix(hash, snapshot.movingIndexAt(i));
            hash = mix(hash, Float.floatToIntBits(snapshot.movingSpeedAt(i)));
        }
        // v4 实体表也进指纹：否则"同布局但换了生物"的两份快照会命中同一条物品侧 LRU 记录。
        // 用 NBT 的 hashCode（内容哈希）+ 类型名 + 三个坐标/两个朝向的位模式——会话内稳定即可。
        for (PreviewSnapshot.EntityRecord entity : snapshot.entities()) {
            for (int i = 0; i < entity.type().length(); i++) {
                hash = mix(hash, entity.type().charAt(i));
            }
            hash = mix(hash, Float.floatToIntBits(entity.x()));
            hash = mix(hash, Float.floatToIntBits(entity.y()));
            hash = mix(hash, Float.floatToIntBits(entity.z()));
            hash = mix(hash, Float.floatToIntBits(entity.yaw()));
            hash = mix(hash, Float.floatToIntBits(entity.pitch()));
            hash = mix(hash, entity.data().hashCode());
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
