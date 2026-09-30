package com.yansunsky.createcmpor.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yansunsky.createcmpor.Config;
import com.yansunsky.createcmpor.block.FactoryBlock;
import com.yansunsky.createcmpor.block.FactoryBlockEntity;
import com.yansunsky.createcmpor.client.preview.FactoryPreviewBaker;
import com.yansunsky.createcmpor.client.preview.PreviewBakeCache;
import com.yansunsky.createcmpor.client.preview.PreviewBaked;
import com.yansunsky.createcmpor.client.preview.PreviewRender;
import com.yansunsky.createcmpor.client.preview.ClientPreviewSync;
import com.yansunsky.createcmpor.preview.PreviewSnapshot;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 工厂方块的「微缩预览」客户端渲染器（0.4.0）——<b>只剩胶水</b>。
 *
 * <p>把固化时采到的 {@link PreviewSnapshot}（调色板 + 字节网格）在方块内部缩小渲染出来，
 * 全程<b>不加载房间区块</b>——世界信息只来自 BE 同步过来的快照。
 *
 * <p>本类只负责与"方块实体 + BER 生命周期"强相关的三件事：
 * <ol>
 *     <li>LOD：超过 {@link #MAX_DISTANCE} 格不渲染也不烘焙，并顺手丢掉该 BE 的缓存；</li>
 *     <li>限流：每个游戏 tick 最多新烘一个工厂，避免进入视野瞬间的帧尖峰；</li>
 *     <li>取缓存 / 触发烘焙，然后把渲染交给共用的 {@link PreviewRender}。</li>
 * </ol>
 *
 * <p>真正的实现分家到 {@code com.yansunsky.createcmpor.client.preview}：
 * 烘焙 {@link FactoryPreviewBaker}（与 BE 无关，手持物品侧可复用）、缓存 {@link PreviewBakeCache}
 * （方块侧弱键 + 物品侧 LRU）、渲染 {@link PreviewRender}（缩放与光照施加）。
 *
 * <p><b>与 0.4.0 的唯一刻意差异</b>：光照不再进烘焙缓存键（原来 {@code baked.light != light}
 * 会让光照一变就整块重烘）。烘焙也不再 {@code setExternalLight}，
 * 光照改由 {@link PreviewRender} 在渲染期施加——最终顶点光照与旧实现逐位相同
 * （推导见 {@link PreviewRender} 的类注释）。
 *
 * <p>调用点必须在 Flywheel 早退<b>之前</b>（{@code FactoryRenderer.renderSafe} 顶部）——
 * 工厂注册视觉时用的是 {@code skipVanillaRender(be -> false)}，所以 Flywheel 开启时 vanilla BER
 * 仍每帧被调用；若把预览放在早退之后，开 Flywheel 的玩家就永远看不到预览。
 */
public final class FactoryPreviewRenderer {

    /** 超过该距离（格）不渲染也不烘焙。 */
    private static final double MAX_DISTANCE = 64.0;

    /** 上一个"新烘一个工厂"的 tick；与 {@link PreviewBakeCache} 的方块侧缓存配合做每 tick 限流。 */
    private static long lastBakeTick = Long.MIN_VALUE;

    private FactoryPreviewRenderer() {
    }

    /**
     * 在 BER 里渲染该工厂的微缩预览。无预览数据/太远/烘失败都安静返回。
     *
     * <p>调用点必须在 Flywheel 早退<b>之前</b>（{@code FactoryRenderer.renderSafe} 顶部）——
     * 工厂注册视觉时用的是 {@code skipVanillaRender(be -> false)}，所以 Flywheel 开启时 vanilla BER
     * 仍每帧被调用；若把预览放在早退之后，开 Flywheel 的玩家就永远看不到预览。
     */
    public static void render(FactoryBlockEntity be, PoseStack ms, MultiBufferSource buffers, int light) {
        if (!Config.ENABLE_FACTORY_PREVIEW.get()) {
            return;
        }
        // 0.4.30：**包壳态不画内部微缩**（用户 2026-09-30 反馈）。
        // 包壳（安山机壳）的语义之一是减少渲染压力：factory_casing（1px 不透明壳）+
        // factory_panel/factory_fill（封住每面 8×8 中心孔）已经把内部完全挡死，
        // 再每帧走 静态层 renderInto + 动态旋转 pass + 实体 dispatcher.render 纯属浪费。
        //
        // 判定必须用 ENCASED，**不能**用 GLASS_SHELL：边框玻璃壳（encased=false, glass=true）
        // 的预览是**故意可见**的（那正是"拆掉自带罩子看内部"的形态）。
        //
        // 副作用：包壳期间不再触发下面的 maybeRequest —— 按需同步的快照不会下发（顺带省流量）；
        // 脱壳后 ON_DEMAND 模式下需要一次 RTT 才会出现（FULL 模式无延迟）。
        // 已烘产物仍留在缓存里（不释放、也不重烘）。
        if (be.getBlockState().getValue(FactoryBlock.ENCASED)) {
            return;
        }
        PreviewSnapshot snapshot = be.getPreviewSnapshot();
        if (snapshot == null || snapshot.nonAirCount() == 0) {
            // 按需同步（0.4.21）：本地没有快照时，问服务端要一份（命中本地缓存则零请求直接装上）。
            // 放在这里的原因：视锥剔除/渲染距离/区块加载状态由引擎免费提供——背对工厂时根本不会调到这里。
            ClientPreviewSync.maybeRequest(be);
            return;
        }
        Level level = be.getLevel();
        Minecraft minecraft = Minecraft.getInstance();
        if (level == null || minecraft.player == null) {
            return;
        }
        double distanceSqr = minecraft.player.position().distanceToSqr(Vec3.atCenterOf(be.getBlockPos()));
        if (distanceSqr > MAX_DISTANCE * MAX_DISTANCE) {
            PreviewBakeCache.evictBlock(be);
            return;
        }

        PreviewBaked baked = PreviewBakeCache.getBlock(be);
        // 快照是整体替换的（FactoryBlockEntity.installPreview），身份比较即"内容是否变了"；
        // 光照不再是重烘理由（light 在渲染期施加）。
        boolean needsBake = baked == null || baked.snapshot() != snapshot;
        if (needsBake) {
            long tick = AnimationTickHolder.getTicks();
            if (baked == null && tick == lastBakeTick) {
                // 本 tick 的烘焙预算已用完：这帧先不画，下一 tick 再补
                return;
            }
            lastBakeTick = tick;
            baked = FactoryPreviewBaker.bake(level, snapshot);
            if (baked == null) {
                return;
            }
            PreviewBakeCache.putBlock(be, baked);
        }

        PreviewRender.render(ms, buffers, light, baked);
    }

    /** 清空缓存（资源重载/退出世界时调用，避免持有旧 BakedModel 产生的顶点数据）。 */
    public static void clear() {
        PreviewBakeCache.clear();
        lastBakeTick = Long.MIN_VALUE;
    }
}
