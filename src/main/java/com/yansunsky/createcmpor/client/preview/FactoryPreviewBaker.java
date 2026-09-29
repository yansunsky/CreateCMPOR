package com.yansunsky.createcmpor.client.preview;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.blockEntity.IMultiBlockEntityContainer;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.virtualWorld.VirtualRenderWorld;
import com.yansunsky.createcmpor.CreateCMPOR;
import com.yansunsky.createcmpor.preview.PreviewSnapshot;
import net.createmod.catnip.render.ShadedBlockSbbBuilder;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 微缩预览的烘焙器：把 {@link PreviewSnapshot} 烘成逐 {@link RenderType} 的顶点缓冲。
 *
 * <p><b>与方块实体无关</b>：签名只有 {@code (源 Level, 快照)}，方块侧传工厂所在世界，
 * 将来的手持物品预览传客户端当前世界——因此物品侧才能复用同一条烘焙路径。
 *
 * <p>流程（0.4.0 原逻辑逐字迁移，仅去掉光照）：
 * <ol>
 *     <li>建一个 Create 的 {@link VirtualRenderWorld} 子类当"微型世界"，把快照里的方块写进去；</li>
 *     <li>为 {@code hasBlockEntity} 的格子造代理 BE（连通组走 {@code markVirtual} + 伪控制器）；</li>
 *     <li>逐 {@code RenderType.chunkBufferLayers()} 用 {@link ShadedBlockSbbBuilder}
 *         + {@link ModelBlockRenderer#tesselateBlock} 烘一次；</li>
 *     <li>任何异常都吞掉并返回 {@code null}——渲染失败绝不能让游戏崩溃（BER 里的异常会直接崩客户端）。</li>
 * </ol>
 *
 * <p><b>光照不在这里烘</b>：不再调用 {@code world.setExternalLight(light)} /
 * {@code resetExternalLight()}。光照改由 {@link PreviewRender} 在渲染期施加，理由与实测语义见该类的注释。
 */
public final class FactoryPreviewBaker {

    private FactoryPreviewBaker() {
    }

    /**
     * 把快照烘成逐 RenderType 的顶点缓冲。
     *
     * <p>任何异常都吞掉并返回 {@code null}——渲染失败绝不能让游戏崩溃（BER 里的异常会直接崩客户端）。
     */
    public static PreviewBaked bake(Level sourceLevel, PreviewSnapshot snapshot) {
        BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
        BakedModel missingModel = Minecraft.getInstance().getModelManager().getMissingModel();
        ModelBlockRenderer modelRenderer = dispatcher.getModelRenderer();
        RandomSource random = RandomSource.create();
        PoseStack pose = new PoseStack();
        Map<RenderType, SuperByteBuffer> layers = new LinkedHashMap<>();
        try {
            VirtualRenderWorld world = new NonVisualVirtualWorld(sourceLevel, 0, 16, BlockPos.ZERO, () -> {
            });

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
                                    if (group > 0 && proxy instanceof IMultiBlockEntityContainer container) {
                                        // 必须先 markVirtual()！ItemVaultBlockEntity / FluidTankBlockEntity 的 setController
                                        // 字节码首三条是 `if (level.isClientSide && !isVirtual()) return;`
                                        // —— 不标虚拟模式的话，客户端注入是**静默 no-op**（这正是上一版仍然黑的直接原因）。
                                        // Create 自己的 SchematicHandler#fixControllerBlockEntities 也是同款用法。
                                        if (proxy instanceof SmartBlockEntity smart) {
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
            return layers.isEmpty() ? null : new PreviewBaked(snapshot, PreviewBaked.contentHash(snapshot), layers);
        } catch (Throwable error) {
            CreateCMPOR.LOGGER.warn("[预览] 烘焙失败，本次不渲染该工厂的微缩内容", error);
            return null;
        }
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
