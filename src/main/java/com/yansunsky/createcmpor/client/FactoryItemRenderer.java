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
 * <h3>调用链与姿态（实测 + 实机校核）</h3>
 * {@code ItemRenderer.render:123-124}（NeoForge 21.1.235 源码）：
 * <pre>
 * p_model = ClientHooks.handleCameraTransforms(poseStack, p_model, ctx, leftHand);   // 压入 M_ctx
 * poseStack.translate(-0.5F, -0.5F, -0.5F);
 * if (!p_model.isCustomRenderer()) { ...原版四边形路径... }
 * else { IClientItemExtensions.of(stack).getCustomRenderer().renderByItem(...); }
 * </pre>
 * 即<b>进入本方法时姿态 = M_ctx · T(-0.5,-0.5,-0.5)</b>。方块模型四边形在烘焙时已经除以 16
 * （{@code FaceBakery.bakeQuad} → 0..1 空间），于是 {@code T(-0.5)} 把 0..1 的方块模型搬到
 * {@code -0.5..0.5}（以物品原点为中心）——<b>但坐标系本身没变</b>：在这一层，坐标 {@code c ∈ [0,1]³}
 * 就是方块模型空间（原点 = 方块最小角、1 单位 = 1 格），与 BER 里 {@code FactoryPreviewRenderer}
 * 所处空间<b>逐位同构</b>。所以 {@link PreviewRender} 的 {@code BOX_PX/BOTTOM_PX/居中/缩放} 数学
 * 可以原样复用，且<b>不需要任何额外平移</b>（只要与机壳画在同一层即可）。
 *
 * <p><b>实机教训（0.4.2 的 bug，0.4.3 修正）</b>：0.4.2 曾照抄 Create 的
 * {@code CustomRenderedItemModelRenderer:24-25} 写了 {@code translate(0.5,0.5,0.5)}，实机结果是
 * 手持/物品栏里预览被整体推出方块（贴方块左上方半格，只剩一角露在外面）。
 * 根因：Create 那个 {@code +0.5} 是把坐标系换成「<b>以方块中心为原点</b>」的居中空间，
 * 所以它的 {@code PartialItemModelRenderer.render:64-65} 在画模型四边形时要再
 * {@code translate(-0.5,-0.5,-0.5)} 抵消回来；而本类要的是「方块最小角为原点」的模型空间
 * ——两者恰好差半格。判据不依赖推理：本类在同一层先画的机壳（{@link #drawShell}，同样是 0..1 空间、
 * 同样在入口姿态、同样零额外变换）位置正确，就证明入口姿态的坐标系正是预览需要的那个空间。
 *
 * <h3>机壳用哪个模型（0.4.4 澄清，含一条被推翻的猜测）</h3>
 * <b>物品机壳的模型由「物品模型 JSON」决定，与方块状态、与 {@code registerDefaultState} 无关。</b>
 * 证据链：
 * <ol>
 *     <li>{@code assets/createcmpor/models/item/factory_block.json} 是<b>显式</b>的
 *         {@code {"parent": "createcmpor:block/factory_block_closed"}}；
 *         {@code ModelBakery.loadItemModelAndDependencies} 把它注册成
 *         {@code createcmpor:factory_block#inventory}，全程<b>不读 blockstate、不解析任何 BlockState</b>；</li>
 *     <li>{@code factory_block_closed.json} 是独立模型文件（30 个 element、只用 {@code #casing}、无玻璃），
 *         不是 {@code blockstates/factory_block.json} 里 {@code encased=true} 那套 multipart
 *         （{@code factory_casing} + panel/fill 14 条 selector）；</li>
 *     <li>multipart 模型（{@code MultiPartBakedModel}）只由 <b>blockstate</b> 产生，物品路径拿不到它——所以
 *         "物品用了 {@code ENCASED} 默认值 true 的外壳"这一猜测不成立（0.4.3 曾据此记录待查，0.4.4 实测否定）。</li>
 * </ol>
 * 期望外观 = <b>展示形态</b>（3px 底座 + 四角立柱 + 顶部横梁 + 四面玻璃），对应
 * {@code createcmpor:block/factory_display}（{@code render_type: cutout}）——即方块世界里
 * {@code ENCASED=false, GLASS_SHELL=false} 那套。0.4.4 把<b>物品模型 JSON 的 parent</b> 指向它，
 * 于是机壳在<b>所有</b>物品路径（GUI / 手持 / 掉落物 / 展示框 / JEI / 配方预览）都是展示形态，
 * 不需要也不应该去构造 BlockState。
 *
 * <h3>绘制顺序</h3>
 * 先画预览、后画机壳——与世界里的顺序一致（不透明/实体 pass 先、半透明覆盖后），
 * 保证"透过玻璃看里面的微缩"的合成关系与方块侧相同（{@code factory_display} 的玻璃是
 * cutout：alpha=0 的像素被丢弃，所以微缩不会被玻璃挡黑）。
 *
 * <h3>降级（必须，绝不冒泡）</h3>
 * <ul>
 *     <li>无 {@code BLOCK_ENTITY_DATA} / 无 {@code preview} 子标签 / {@code PreviewSnapshot.load} 返回 null
 *         / {@code Minecraft.getInstance().level == null}（主菜单、资源包界面、世界卸载瞬间）
 *         → <b>不烘焙</b>，只画原机壳模型（机壳路径不依赖 level，绝不隐形）；</li>
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

    /** 上一次打过日志的机壳模型（仅用于"换模型时打一条 debug"，不参与任何渲染逻辑）。 */
    private static BakedModel lastLoggedShell;

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
            // ① 微缩预览先画，② 机壳后画——与世界里的绘制顺序一致（不透明/实体 pass 先、半透明覆盖后），
            //    这样"隔着一层玻璃看内容"的合成关系与方块侧相同。
            if (Config.ENABLE_FACTORY_ITEM_PREVIEW.get()) {
                PreviewBaked baked = resolvePreview(stack);
                if (baked != null) {
                    PreviewRender.render(ms, buffers, packedLight, baked);
                }
            }
            // 机壳：与 0.4.1 及以前的物品外观一致（模型来自物品模型 JSON，见类注释的 0.4.4 说明）。
            drawShell(stack, ms, buffers, packedLight, packedOverlay);
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
        PreviewBakeCache.ensureLevel(level); // 退出/切换世界（含切维度）后整体作废旧产物
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
        // 逐层拆包装：正常情况下只有我们这一层，但别的模组也换同一个物品模型时会套多层，
        // 只拆一层会让 getQuads 落在别人的包装上（最坏：机壳消失、只剩预览）。
        for (int depth = 0; depth < 4 && model instanceof FactoryPreviewItemModel wrapper; depth++) {
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
                logShellOnce(pass, layer);
            }
        }
    }

    /**
     * 机壳模型只在"换过一次"时打一条 debug（不是每帧刷屏）：排查"手持外壳用错模型"时，
     * 这一行直接回答"实际画的是哪个模型、落在哪个图层"。
     */
    private static void logShellOnce(BakedModel model, RenderType layer) {
        if (model == lastLoggedShell) {
            return;
        }
        lastLoggedShell = model;
        CreateCMPOR.LOGGER.debug("[预览] 物品机壳：模型 = {}，图层 = {}（模型来自物品模型 JSON，与方块状态无关）",
                model.getClass().getSimpleName(), layer);
    }
}
