package com.yansunsky.createcmpor.client.preview;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yansunsky.createcmpor.CreateCMPOR;
import com.yansunsky.createcmpor.preview.PreviewSnapshot;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;

import java.util.Map;

/**
 * 微缩内容的渲染：把一次烘焙的产物按"1/N 缩放 + 底部对齐 + 水平居中"摆进展示区。
 *
 * <p>方块侧 BER 与将来的手持物品 BEWLR 共用这一条路径——所以这里只做渲染，
 * 不碰缓存、不碰 LOD、不碰限流（那些属于各自的调用方）。
 *
 * <h3>光照：为什么在渲染期施加，以及 {@code light()} 的真实语义（已用 javap + 源码核实）</h3>
 *
 * <p><b>实测结论（catnip 1.0.82，ponder-neoforge-1.0.82+mc1.21.1-sources.jar）：</b>
 * {@code light(int)} 在<b>不同实现上语义不同</b>——
 * <ul>
 *     <li>我们实际拿到的缓冲由 {@code ShadedBlockSbbBuilder.end()} 产出，类型<b>恒为</b>
 *         {@code ShadeSeparatingSuperByteBuffer}（该类第 51-63 行）。它的 {@code renderInto} 是
 *         {@code light = template.light(i); if (hasCustomLight) light = maxLight(light, packedLight);}
 *         ——<b>是与顶点自带光照取 max 的"合并"，不是覆盖</b>。</li>
 *     <li>{@code DefaultSuperByteBuffer} 在非 {@code useWorldLight} 分支才是纯覆盖
 *         （{@code light = packedLightCoordinates}）。所以<b>不能</b>按"light() 就是设置"来理解。</li>
 * </ul>
 *
 * <p><b>但在这个工程里，max 合并与旧的"把光照烘进顶点"逐位等价</b>（源码推导，非实机观测）：
 * <ol>
 *     <li>虚拟世界自身的光照是<b>恒定</b>的：sky 恒 15、block 恒 0。
 *         sky 见 {@code SkyLightSectionStorage.getLightValue}（无 section 数据时 {@code j < k} 为假 → 返回 15；
 *         虚拟世界从不调 {@code runLightEngine()}，{@code visibleSectionData} 永远为空）；
 *         block 见 {@code BlockLightSectionStorage}（无 DataLayer → 0）。</li>
 *     <li>{@code LevelRenderer.getLightColor} = {@code sky<<20 | max(block, state.getLightEmission())<<4}。</li>
 *     <li>旧行为：bake 前 {@code world.setExternalLight(light)}，而 {@code VirtualRenderWorld.getBrightness}
 *         只做 {@code max(自身, 外部)} → 烘出的顶点光照 = {@code 15<<20 | max(block(light), emission)<<4}
 *         （对全网格恒定）。</li>
 *     <li>新行为：bake 不再设外部光照 → 顶点光照 = {@code 15<<20 | emission<<4}（同样恒定）；
 *         渲染期 {@code maxLight(该值, light)} = {@code 15<<20 | max(block(light), emission)<<4}
 *         → <b>与旧值逐位相同</b>。</li>
 *     <li>因为烘出的光照在全网格恒定，AO 混合（{@code ModelBlockRenderer.AmbientOcclusionFace.blend}
 *         对几个采样值求平均）不引入差异；自发光（emissiveRendering）状态两边都恒为 15728880。
 *         {@code SuperByteBuffer.maxLight} 是按位拆 block/sky 逐分量取 max 再 pack。</li>
 * </ol>
 *
 * <p><b>缓冲复用安全</b>：{@code renderInto} 末尾会调 {@code reset()}，而 {@code reset()} 会清掉
 * {@code hasCustomLight}/{@code packedLight}，所以"缓存实例复用 + 每帧 setCustomLight"不会残留状态。
 * 唯一不 reset 的路径是 {@code renderInto} 开头的 {@code isEmpty()} 早退——但缓存里只放非空缓冲
 * （烘焙侧已判 {@code !buffer.isEmpty()}），故该路径不会携带脏状态。
 *
 * <p>异常处理：轮廓与本方法都不允许把异常冒泡给客户端（BER 里抛异常 = 崩客户端）。
 */
public final class PreviewRender {

    /**
     * 展示区边长（像素）。方块内部空腔约 14px；底座占 3px、玻璃壳顶板在 y=15，
     * 故取 11 让内容顶端停在 y=14，与顶板留 1px 净空（避免共面闪烁）。
     */
    private static final float BOX_PX = 11.0F;

    /** 展示区距方块底面的高度（像素）= 展示模型底座高度（y 0..3）。 */
    private static final float BOTTOM_PX = 3.0F;

    private PreviewRender() {
    }

    /**
     * 渲染一次烘焙产物。姿态栈由本方法自己 push/pop（调用方不必预备变换）。
     *
     * @param packedLight 当前帧的光照（{@code LightTexture} 打包值），渲染期才施加——见类注释
     */
    public static void render(PoseStack ms, MultiBufferSource buffers, int packedLight, PreviewBaked baked) {
        PreviewSnapshot snapshot = baked.snapshot();
        float cellPx = BOX_PX / snapshot.maxDimension();
        float scale = cellPx / 16.0F;
        // 水平居中、底部对齐（pose stack 单位 = 1 格，故像素要 /16）
        float offsetX = (16.0F - snapshot.width() * cellPx) / 32.0F;
        float offsetZ = (16.0F - snapshot.depth() * cellPx) / 32.0F;
        float offsetY = BOTTOM_PX / 16.0F;

        ms.pushPose();
        try {
            ms.translate(offsetX, offsetY, offsetZ);
            ms.scale(scale, scale, scale);
            for (Map.Entry<RenderType, SuperByteBuffer> entry : baked.layers().entrySet()) {
                // light() 是"与顶点自带光照取 max"（ShadeSeparatingSuperByteBuffer.renderInto），
                // 但两侧都是 sky=15 与 max(block(light), emission) 的组合，故结果与旧的"烘进光照"逐位相同——见类注释。
                entry.getValue().light(packedLight).renderInto(ms, buffers.getBuffer(entry.getKey()));
            }
        } catch (Throwable error) {
            // 绝不冒泡：宁可这一帧缺一小块，也不能崩客户端
            CreateCMPOR.LOGGER.debug("[预览] 渲染失败，本帧跳过该微缩内容", error);
        } finally {
            ms.popPose();
        }
    }
}
