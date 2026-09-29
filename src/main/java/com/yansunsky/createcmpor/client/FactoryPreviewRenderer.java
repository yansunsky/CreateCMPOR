package com.yansunsky.createcmpor.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.virtualWorld.VirtualRenderWorld;
import com.yansunsky.createcmpor.Config;
import com.yansunsky.createcmpor.CreateCMPOR;
import com.yansunsky.createcmpor.block.FactoryBlockEntity;
import com.yansunsky.createcmpor.preview.PreviewSnapshot;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.render.ShadedBlockSbbBuilder;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 工厂方块的「微缩预览」客户端渲染器（0.4.0）。
 *
 * <p>把固化时采到的 {@link PreviewSnapshot}（调色板 + 字节网格）在方块内部缩小渲染出来，
 * 全程<b>不加载房间区块</b>——世界信息只来自 BE 同步过来的快照。
 *
 * <p>实现路线（证据来自上游两处成熟实现）：
 * <ol>
 *     <li>建一个 Create 的 {@link VirtualRenderWorld} 子类当"微型世界"，把快照里的方块写进去；</li>
 *     <li>按 {@code RenderType} 层<b>烘一次</b>成 {@link SuperByteBuffer} 并缓存
 *         （范式照 Create 自己的 {@code SchematicRenderer.drawLayer}）；
 *         内容与光照不变时每帧只做几次 draw call，不再重新 tessellate；</li>
 *     <li>渲染时 push → 平移到展示区左下角 → 按格大小缩放 → 逐层 renderInto。</li>
 * </ol>
 *
 * <p><b>头号坑（上游实测）</b>：{@link VirtualRenderWorld} 实现了 Flywheel 的 {@code VisualizationLevel}
 * 且默认返回 true，若不覆写成 false，Flywheel 会"抢答"认为支持可视化、同时 Create 全部渲染器又早退
 * → 微缩内容<b>整块不渲染且不报任何错</b>。见 {@link NonVisualVirtualWorld}。
 */
public final class FactoryPreviewRenderer {

    /** 展示区边长（像素）。方块内部空腔约 14px，这里留出 1px 余量。 */
    private static final float BOX_PX = 12.0F;

    /** 展示区距方块底面的高度（像素）= 展示模型底座高度（y 0..3）。 */
    private static final float BOTTOM_PX = 3.0F;

    /** 超过该距离（格）不渲染也不烘焙。 */
    private static final double MAX_DISTANCE = 64.0;

    /** 每个游戏 tick 最多新烘一个工厂，避免进入视野瞬间的帧尖峰。 */
    private static final Map<FactoryBlockEntity, Baked> CACHE = new WeakHashMap<>();
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
        PreviewSnapshot snapshot = be.getPreviewSnapshot();
        if (snapshot == null || snapshot.nonAirCount() == 0) {
            return;
        }
        Level level = be.getLevel();
        Minecraft minecraft = Minecraft.getInstance();
        if (level == null || minecraft.player == null) {
            return;
        }
        double distanceSqr = minecraft.player.position().distanceToSqr(Vec3.atCenterOf(be.getBlockPos()));
        if (distanceSqr > MAX_DISTANCE * MAX_DISTANCE) {
            CACHE.remove(be);
            return;
        }

        Baked baked = CACHE.get(be);
        boolean needsBake = baked == null || baked.snapshot != snapshot || baked.light != light;
        if (needsBake) {
            long tick = AnimationTickHolder.getTicks();
            if (baked == null && tick == lastBakeTick) {
                // 本 tick 的烘焙预算已用完：这帧先不画，下一 tick 再补
                return;
            }
            lastBakeTick = tick;
            baked = bake(level, snapshot, light);
            if (baked == null) {
                return;
            }
            CACHE.put(be, baked);
        }

        float cellPx = BOX_PX / snapshot.maxDimension();
        float scale = cellPx / 16.0F;
        // 水平居中、底部对齐（pose stack 单位 = 1 格，故像素要 /16）
        float offsetX = (16.0F - snapshot.width() * cellPx) / 32.0F;
        float offsetZ = (16.0F - snapshot.depth() * cellPx) / 32.0F;
        float offsetY = BOTTOM_PX / 16.0F;

        ms.pushPose();
        ms.translate(offsetX, offsetY, offsetZ);
        ms.scale(scale, scale, scale);
        for (Map.Entry<RenderType, SuperByteBuffer> entry : baked.layers.entrySet()) {
            entry.getValue().renderInto(ms, buffers.getBuffer(entry.getKey()));
        }
        ms.popPose();
    }

    /** 清空缓存（资源重载/退出世界时调用，避免持有旧 BakedModel 产生的顶点数据）。 */
    public static void clear() {
        CACHE.clear();
        lastBakeTick = Long.MIN_VALUE;
    }

    /**
     * 把快照烘成逐 RenderType 的顶点缓冲。
     *
     * <p>任何异常都吞掉并返回 {@code null}——渲染失败绝不能让游戏崩溃（BER 里的异常会直接崩客户端）。
     */
    private static Baked bake(Level sourceLevel, PreviewSnapshot snapshot, int light) {
        BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
        BakedModel missingModel = Minecraft.getInstance().getModelManager().getMissingModel();
        ModelBlockRenderer modelRenderer = dispatcher.getModelRenderer();
        RandomSource random = RandomSource.create();
        PoseStack pose = new PoseStack();
        Map<RenderType, SuperByteBuffer> layers = new LinkedHashMap<>();
        VirtualRenderWorld world = null;
        try {
            world = new NonVisualVirtualWorld(sourceLevel, 0, 16, BlockPos.ZERO, () -> {
            });
            world.setExternalLight(light);

            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            int skipped = 0;
            int proxies = 0;
            for (int y = 0; y < snapshot.height(); y++) {
                for (int z = 0; z < snapshot.depth(); z++) {
                    for (int x = 0; x < snapshot.width(); x++) {
                        BlockState state = snapshot.stateAt(snapshot.cellIndex(x, y, z));
                        if (state == null) {
                            continue;
                        }
                        pos.set(x, y, z);
                        world.setBlock(pos, state, 2);
                        // 造代理方块实体。**注意：连通纹理（CTM）不需要它**——已用源码证否：
                        // `CTModel.gatherModelData` 是直接查世界邻居算数据的，且 `getQuads` 在拿不到 CT 数据时
                        // 会回落成普通四边形（不会变黑）。保留代理 BE 的真实理由是另一些模型确实读 BE：
                        // 例如 `CopycatModel` / `FluidTankModel` 的 gatherModelData 会经 world.getBlockEntity 取数据。
                        // 失败只记 debug，绝不冒泡。
                        if (state.hasBlockEntity() && state.getBlock() instanceof EntityBlock entityBlock) {
                            try {
                                BlockEntity proxy = entityBlock.newBlockEntity(pos, state);
                                if (proxy != null) {
                                    proxy.setLevel(world);
                                    proxy.setBlockState(state);
                                    world.setBlockEntity(proxy);
                                    // 连通组 → 伪控制器坐标：Create 的 ConnectivityHandler.isConnected 实现是
                                    // `one.getController().equals(two.getController())`，装上同一个伪坐标即判为连通，
                                    // 连通纹理（CTM）才能算对。伪坐标只参与 equals 比较，不是真实位置。
                                    int group = snapshot.groupAt(snapshot.cellIndex(x, y, z));
                                    if (group > 0 && proxy instanceof com.simibubi.create.foundation.blockEntity
                                            .IMultiBlockEntityContainer container) {
                                        // 必须先 markVirtual()！ItemVaultBlockEntity / FluidTankBlockEntity 的 setController
                                        // 字节码首三条是 `if (level.isClientSide && !isVirtual()) return;`
                                        // —— 不标虚拟模式的话，客户端注入是**静默 no-op**（这正是上一版仍然黑的直接原因）。
                                        // Create 自己的 SchematicHandler#fixControllerBlockEntities 也是同款用法。
                                        if (proxy instanceof com.simibubi.create.foundation.blockEntity.SmartBlockEntity smart) {
                                            smart.markVirtual();
                                        }
                                        container.setController(PreviewSnapshot.syntheticController(group));
                                    }
                                    proxies++;
                                }
                            } catch (Throwable error) {
                                CreateCMPOR.LOGGER.debug("[预览] 代理方块实体创建失败：{}", state, error);
                            }
                        }
                    }
                }
            }

            ShadedBlockSbbBuilder builder = ShadedBlockSbbBuilder.create();
            ModelBlockRenderer.enableCaching();
            try {
                for (RenderType layer : RenderType.chunkBufferLayers()) {
                    builder.begin();
                    boolean wrote = false;
                    for (int y = 0; y < snapshot.height(); y++) {
                        for (int z = 0; z < snapshot.depth(); z++) {
                            for (int x = 0; x < snapshot.width(); x++) {
                                BlockState state = snapshot.stateAt(snapshot.cellIndex(x, y, z));
                                if (state == null) {
                                    continue;
                                }
                                pos.set(x, y, z);
                                BakedModel model = dispatcher.getBlockModel(state);
                                // 只跳过"没有模型"的方块（缺失模型是紫黑格子，画出来只会更糟）。
                                // 刻意不再按 RenderShape 过滤：Create 的粉碎轮/飞轮/曲柄/涡轮等把 getRenderShape 覆写成
                                // ENTITYBLOCK_ANIMATED，只为把渲染让给 Flywheel visual——它们的方块模型其实是完整几何
                                // （如粉碎轮的 block.json 直接指向 crushing_wheel.obj），所以照画即可复原外观。
                                if (model == missingModel) {
                                    skipped++;
                                    continue;
                                }
                                ModelData modelData = model.getModelData(world, pos, state, world.getModelData(pos));
                                if (model instanceof com.simibubi.create.foundation.block.connected.CTModel
                                        && snapshot.groupAt(snapshot.cellIndex(x, y, z)) == 0) {
                                    // CTM 且**没有连通信息**（单方块、或降采样快照没有组表）：走 CTModel 源码里的回落分支
                                    // `if (!extraData.has(CT_PROPERTY)) return quads;`，用原始四边形保证不出现黑块。
                                    // 有连通信息的（多方块机器）保留真实 CT 数据 → 连接纹理正确显示。
                                    modelData = ModelData.EMPTY;
                                }
                                long seed = state.getSeed(pos);
                                random.setSeed(seed);
                                if (!model.getRenderTypes(state, random, modelData).contains(layer)) {
                                    continue;
                                }
                                pose.pushPose();
                                pose.translate(x, y, z);
                                modelRenderer.tesselateBlock(world, model, state, pos, pose, builder, true,
                                        random, seed, OverlayTexture.NO_OVERLAY, modelData, layer);
                                pose.popPose();
                                wrote = true;
                            }
                        }
                    }
                    SuperByteBuffer buffer = builder.end();
                    if (wrote && buffer != null && !buffer.isEmpty()) {
                        layers.put(layer, buffer);
                    }
                }
            } finally {
                ModelBlockRenderer.clearCache();
            }
            if (skipped > 0 || proxies > 0 || layers.isEmpty()) {
                CreateCMPOR.LOGGER.info("[预览] 烘焙完成：网格 {}x{}x{}，图层 {} 个，代理 BE {} 个，跳过 {} 格（非空气 {} 格）",
                        snapshot.width(), snapshot.height(), snapshot.depth(), layers.size(), proxies, skipped,
                        snapshot.nonAirCount());
                // 内容清单：定位"某块渲染异常"时，先看这里有哪些方块（排查渲染问题的最直接证据）
                CreateCMPOR.LOGGER.info("[预览] 内容 = {}", String.join(" | ",
                        com.yansunsky.createcmpor.preview.PreviewCapture.describePalette(snapshot, 16)));
            }
            return layers.isEmpty() ? null : new Baked(snapshot, light, layers);
        } catch (Throwable error) {
            CreateCMPOR.LOGGER.warn("[预览] 烘焙失败，本次不渲染该工厂的微缩内容", error);
            return null;
        } finally {
            if (world != null) {
                world.resetExternalLight();
            }
        }
    }

    /** 一次烘焙的产物：逐 RenderType 的顶点缓冲 + 生成它的快照与光照（用于判断是否需要重烘）。 */
    private record Baked(PreviewSnapshot snapshot, int light, Map<RenderType, SuperByteBuffer> layers) {
    }

    /**
     * 关掉 Flywheel 参与的虚拟世界。
     *
     * <p>{@code VirtualRenderWorld} 实现 {@code VisualizationLevel} 且默认返回 true；Flywheel 见到它就会
     * 认为"这个 level 由我接管"，而它实际并不接管 → Create 各渲染器集体早退 → 微缩内容整块消失且零报错。
     * 覆写成 false 才能逼 Create 走 legacy BER 路径。
     */
    private static final class NonVisualVirtualWorld extends VirtualRenderWorld {
        private NonVisualVirtualWorld(Level level, int minBuildHeight, int height, Vec3i biomeOffset,
                                      Runnable onBlockUpdated) {
            super(level, minBuildHeight, height, biomeOffset, onBlockUpdated);
        }

        @Override
        public boolean supportsVisualization() {
            return false;
        }
    }
}
