package com.yansunsky.createcmpor.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yansunsky.createcmpor.Config;
import com.yansunsky.createcmpor.CreateCMPOR;
import com.yansunsky.createcmpor.client.preview.PreviewBakeCache;
import com.yansunsky.createcmpor.client.preview.PreviewBaked;
import com.yansunsky.createcmpor.client.preview.PreviewRender;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

/**
 * 工厂物品的「自带微缩预览」渲染器（{@link BlockEntityWithoutLevelRenderer}，BEWLR）。
 *
 * <p>目标：手持（第一/第三人称）、物品栏 GUI、掉落物、展示框里的工厂物品，显示它<b>自己携带</b>的那份
 * 微缩产线预览——与方块侧的 {@code FactoryPreviewRenderer} 共用同一条烘焙/绘制管线
 * （{@link PreviewBakeCache} + {@link com.yansunsky.createcmpor.client.preview.FactoryPreviewBaker}
 * + {@link PreviewRender}），不另起一套。
 *
 * <h3>调用链与姿态（实测）</h3>
 * {@code ItemRenderer.render:123-124}（NeoForge 21.1.235 源码）：
 * <pre>
 * p_model = ClientHooks.handleCameraTransforms(poseStack, p_model, ctx, leftHand);   // 压入 M_ctx
 * poseStack.translate(-0.5F, -0.5F, -0.5F);
 * if (!p_model.isCustomRenderer()) { ...原版四边形路径... }
 * else { IClientItemExtensions.of(stack).getCustomRenderer().renderByItem(...); }
 * </pre>
 * 即<b>进入本方法时姿态 = M_ctx · T(-0.5,-0.5,-0.5)</b>。而方块模型四边形在烘焙时已经除以 16
 * （{@code FaceBakery.bakeQuad} → 0..1 空间），所以只要在这里 {@code translate(0.5,0.5,0.5)} 抵消，
 * 坐标系就与 BER 的方块空间<b>逐位同构</b>，{@link PreviewRender} 里那套
 * {@code BOX_PX/BOTTOM_PX/居中/缩放} 数学可以逐行复用（与 Create 的
 * {@code CustomRenderedItemModelRenderer:24-25} 完全同款做法）。
 *
 * <h3>降级（必须，绝不冒泡）</h3>
 * <ul>
 *     <li>无 {@code BLOCK_ENTITY_DATA} / 无 {@code preview} 子标签 / {@code PreviewSnapshot.load} 返回 null
 *         / {@code Minecraft.getInstance().level == null}（主菜单、资源包界面、世界卸载瞬间）
 *         → <b>不烘焙</b>，只画原机壳模型（外观与今天逐位一致，绝不隐形）；</li>
 *     <li>{@code VirtualRenderWorld} 的构造器第一条指令就解引用 level → level 为 null 时连解析都不要做；</li>
 *     <li>整个方法体 {@code try/catch(Throwable)}：GUI 路径里异常会变成
 *         {@code CrashReport("Rendering item") → ReportedException}，<b>直接崩游戏</b>
 *         （{@code GuiGraphics:1298-1306}，实测）。任何第三方调用（模型数据、代理 BE、CTM）都不许冒泡。</li>
 * </ul>
 *
 * <h3>为什么"永远自定义渲染 + 自己画机壳"</h3>
 * {@code isCustomRenderer()} 是<b>按模型</b>问的，签名里没有 stack，所以做不出"有预览才自定义渲染"的
 * 条件式判断（报告的"条件式 isCustomRenderer"写法在 1.21.1 不成立）。既然恒 true，那么
 * {@code BLOCK_ENTITY_DATA} 缺失的绝大多数场景（创造栏、合成产物）就得我们自己把机壳画出来——
 * 见 {@link #drawShell}，它逐行复刻 {@code ItemRenderer.render} 的原版分支（{@code flag1=true} 一路）。
 *
 * <h3>绝不做的事</h3>
 * 降级路径里<b>禁止</b>调用 {@code BlockRenderDispatcher.renderSingleBlock} /
 * {@code ItemRenderer.render}：前者对 {@code RenderShape.ENTITYBLOCK_ANIMATED} 的方块会用
 * "<b>零组件空壳 stack + ItemDisplayContext.NONE</b>"反向回调 {@code renderByItem}
 * （{@code BlockRenderDispatcher:159-161}，实测），这正是"无限递归"的真身。本类只画四边形（
 * {@link ItemRenderer#renderModelLists}）与已烘好的顶点缓冲，永不回调渲染分发器。
 */
public final class FactoryItemRenderer extends BlockEntityWithoutLevelRenderer {

    /**
     * 与 Create 的 {@code CustomRenderedItemModelRenderer} 同款：不走基类实现
     * （基类实现只认原版的床/旗帜/头颅/箱子等方块），所以两个参数给 null 即可。
     */
    public FactoryItemRenderer() {
        super(null, null);
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext ctx, PoseStack ms,
                             MultiBufferSource buffers, int packedLight, int packedOverlay) {
        try {
            if (stack.isEmpty()) {
                return;
            }
            // ① 机壳：先画，与今天的物品外观逐位一致（GUI/掉落物/手持的 display 变换由 M_ctx 提供）。
            drawShell(stack, ms, buffers, packedLight, packedOverlay);
            // ② 微缩预览：进 0..1 方块空间，交给与方块侧共用的绘制器。
            if (Config.ENABLE_FACTORY_ITEM_PREVIEW.get()) {
                PreviewBaked baked = resolvePreview(stack);
                if (baked != null) {
                    ms.pushPose();
                    try {
                        // 抵消 ItemRenderer 的 translate(-0.5,-0.5,-0.5) → 与 BER 的方块空间同构
                        ms.translate(0.5F, 0.5F, 0.5F);
                        PreviewRender.render(ms, buffers, packedLight, baked);
                    } finally {
                        ms.popPose();
                    }
                }
            }
        } catch (Throwable error) {
            // 绝不冒泡：GUI 路径会 ReportedException 崩游戏，手持/掉落物路径会崩客户端渲染线程。
            CreateCMPOR.LOGGER.debug("[预览] 物品微缩预览渲染失败，本帧跳过（不影响机壳绘制）", error);
        }
    }

    /**
     * 解析本 stack 的微缩预览（缓存 → 必要时烘焙）。任何一步不满足都返回 {@code null} = 只有机壳。
     *
     * <p>缓存与限流都在 {@link PreviewBakeCache#resolveItem} 里：按 {@code CustomData} <b>身份</b>走
     * 身份表快路径，未命中才解析快照 → 内容指纹 → 物品侧 LRU(32)。每个客户端 tick 最多新烘一份，
     * 未中签的这一帧先不画预览（下一 tick 补），避免"一开物品栏几十个工厂同时烘"的帧尖峰。
     */
    private static PreviewBaked resolvePreview(ItemStack stack) {
        CustomData data = stack.get(DataComponents.BLOCK_ENTITY_DATA);
        if (data == null || data.isEmpty()) {
            return null; // 创造栏 / 合成产物 / 无 NBT：不解析、不烘焙
        }
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return null; // 主菜单等：VirtualRenderWorld 构造必 NPE，连快照都不必解析
        }
        return PreviewBakeCache.resolveItem(data, level);
    }

    /**
     * 画原机壳模型（降级路径 + 正常外观的底座外壳）。
     *
     * <p>逐行复刻 {@code ItemRenderer.render} 的<b>非自定义渲染器分支</b>：
     * <pre>
     * for (var model : p_model.getRenderPasses(stack, flag1))
     *     for (var rendertype : model.getRenderTypes(stack, flag1))
     *         renderModelLists(model, stack, light, overlay, poseStack, getFoilBufferDirect(buf, rendertype, true, stack.hasFoil()))
     * </pre>
     * 其中 {@code flag1} 在 GUI/第一人称恒为 true；其余上下文是
     * {@code !(HalfTransparentBlock) && !(StainedGlassPaneBlock)}——工厂方块两者都不是，所以
     * <b>所有上下文的 flag1 都是 true</b>，这里直接写死 true 与元素版本一致。
     *
     * <p>姿态：本方法在 {@code renderByItem} 入口（= M_ctx·T(-0.5)）调用，与 {@code ItemRenderer}
     * 原版分支所处的姿态完全相同，因此不需要任何额外的 push/translate。
     */
    private static void drawShell(ItemStack stack, PoseStack ms, MultiBufferSource buffers,
                                 int packedLight, int packedOverlay) {
        Minecraft minecraft = Minecraft.getInstance();
        ItemRenderer itemRenderer = minecraft.getItemRenderer();
        BakedModel model = itemRenderer.getModel(stack, minecraft.level, null, 0);
        if (model instanceof FactoryPreviewItemModel wrapper) {
            model = wrapper.originalModel();
        }
        if (model.isCustomRenderer()) {
            // 不是我们的包装（例如别处给这个物品换了自定义渲染模型）：交给它自己，绝不在这里回调渲染分发器。
            return;
        }
        for (BakedModel pass : model.getRenderPasses(stack, true)) {
            for (RenderType layer : pass.getRenderTypes(stack, true)) {
                VertexConsumer consumer = ItemRenderer.getFoilBufferDirect(buffers, layer, true, stack.hasFoil());
                itemRenderer.renderModelLists(pass, stack, packedLight, packedOverlay, ms, consumer);
            }
        }
    }
}
