package com.yansunsky.createcmpor.client.preview;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yansunsky.createcmpor.CreateCMPOR;
import com.yansunsky.createcmpor.preview.PreviewSnapshot;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.AxisDirection;

import java.util.List;
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
 *     <li>虚拟世界自身的光照<b>恒为 0</b>（<b>0.4.14 更正</b>：这里原来写的"sky 恒 15"是<b>错的</b>）。
 *         证据：Create {@code foundation/virtualWorld/VirtualRenderWorld.java:90} 构造光照引擎时写死
 *         {@code new LevelLightEngine(chunkSource, true, false)}（发行 jar 字节码同）
 *         ⇒ <b>天空光引擎被关闭</b>，{@code getBrightness(SKY)} 恒 0；方块光引擎虽然开着，
 *         但虚拟世界从不 {@code runLightEngine()} ⇒ 也是 0。</li>
 *     <li>所以 bake 出来的顶点光照 = {@code emission<<4}（只有方块自发光那一项存活）。</li>
 *     <li>旧行为：bake 前 {@code world.setExternalLight(packedLight)}，而 {@code VirtualRenderWorld.getBrightness}
 *         只做 {@code max(自身, 外部)} ⇒ 烘出的顶点光照 = 当时那个 {@code packedLight}
 *         （{@code sky<<20 | max(block(light), emission)<<4}，对全网格恒定）。</li>
 *     <li>新行为：bake 不设外部光照（顶点 = {@code emission<<4}，同样恒定）；
 *         渲染期 {@code maxLight(该值, packedLight)} = {@code packedLight}
 *         → <b>与旧值逐位相同</b>。</li>
 *     <li>因为烘出的光照在全网格恒定，AO 混合（{@code ModelBlockRenderer.AmbientOcclusionFace.blend}
 *         对几个采样值求平均）不引入差异。{@code SuperByteBuffer.maxLight} 是按位拆 block/sky 逐分量取 max 再 pack。</li>
 * </ol>
 *
 * <p><b>推论（0.4.13/0.4.14 的装置光照修复就建立在这条上）</b>：凡是<b>不是</b>我们烘、我们也没有机会
 * 调 {@code .light(...)} 的内容（首当其冲是 Create 自己渲染的装置），它的光照只能来自
 * "烘进顶点的值"或"{@code useLevelLight} 采样虚拟世界"——而这两条在这个世界里都是 0。
 * 修法是给虚拟世界逐帧设 external light（见 {@code PreviewEntityScene}）。
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
            // 静态层与动态 pass 各自独立 try：动态那一小步失败绝不能连累已经烤好的静态内容。
            try {
                for (Map.Entry<RenderType, SuperByteBuffer> entry : baked.layers().entrySet()) {
                    // light() 是"与顶点自带光照取 max"（ShadeSeparatingSuperByteBuffer.renderInto），
                    // 但两侧都是 sky=15 与 max(block(light), emission) 的组合，故结果与旧的"烘进光照"逐位相同——见类注释。
                    entry.getValue().light(packedLight).renderInto(ms, buffers.getBuffer(entry.getKey()));
                }
            } catch (Throwable error) {
                // 绝不冒泡：宁可这一帧缺一小块，也不能崩客户端
                CreateCMPOR.LOGGER.debug("[预览] 静态层渲染失败，本帧跳过该微缩内容", error);
            }
            if (baked.hasDynamicCells()) {
                renderDynamicCells(ms, buffers, packedLight, baked.dynamicCells());
            }
            // v4 实体：与方块同一个姿态（1/N 缩放 + 居中 + 底部对齐），故坐标直接用快照局部格坐标。
            // 逐只 try/catch(Throwable) 在 PreviewEntityScene 内部完成——dispatcher.render 会把
            // 任何 Throwable 包成 ReportedException 抛给调用方，绝不能让它冒泡到 BER。
            if (baked.hasEntities()) {
                baked.entityScene().render(ms, buffers, packedLight);
            }
        } catch (Throwable error) {
            // 绝不冒泡：宁可这一帧缺一小块，也不能崩客户端
            CreateCMPOR.LOGGER.debug("[预览] 渲染失败，本帧跳过该微缩内容", error);
        } finally {
            ms.popPose();
        }
    }

    /**
     * 每帧的"窄 pass"：只对<b>在转且机型在白名单内</b>的格子做一次
     * {@code light + rotateCentered + renderInto}。
     *
     * <h3>成本</h3>
     * 每格只做一次 {@code rotateCentered}（往 {@code PoseStack} 压两个矩阵）+ 把该模型已有顶点重写进
     * {@code VertexConsumer}，<b>没有 tessellate、没有分配</b>。M 个可动格 ≈ M × 2~6 µs（报告估算），
     * M=100 → 0.2~0.6 ms。静态层完全不受影响（仍是 4 次 {@code renderInto}）。
     *
     * <h3>复用同一份缓存缓冲（报告 §3.3 的实测结论）</h3>
     * {@code DefaultSuperByteBuffer.renderInto} 末尾会清空自身 transform 栈
     * （catnip {@code DefaultSuperByteBuffer:186-187}：{@code while (!transforms.clear()) transforms.popPose();}），
     * 而 {@code CachedBuffers.*} 又是 Guava 缓存里<b>同一实例</b>——所以一份 {@code SuperByteBuffer}
     * 可以被 M 个格子逐帧复用，<b>零逐格拷贝、零逐帧分配</b>。
     *
     * <h3>失败绝不退化成"整格消失"</h3>
     * 逐格 {@code try/catch(Throwable)}：
     * <ul>
     *     <li>绘制抛异常 → 把该 BlockState 锁存进 {@link PreviewDynamicParts#markFailed}（避免每帧重抛），
     *         然后立刻走下面的静态回退；</li>
     *     <li>静态回退用<b>另一份</b>缓存缓冲（{@link PreviewDynamicParts} 自己的 {@code FALLBACK_COMPARTMENT}，
     *         与动态用的 Create {@code KINETIC_BLOCK} compartment 不同实例），不旋转地画在该格位置——
     *         与"这格从没被踢出静态层"逐像素等价；</li>
     *     <li>对 {@code bakedStatically == true} 的格（外壳已在静态图层里）不重复画，
     *         避免半透明几何在同一位置画两遍。</li>
     * </ul>
     */
    private static void renderDynamicCells(PoseStack ms, MultiBufferSource buffers, int packedLight,
                                           List<PreviewDynamicCell> cells) {
        float time = AnimationTickHolder.getRenderTime();
        for (PreviewDynamicCell cell : cells) {
            ms.pushPose();
            try {
                ms.translate(cell.x(), cell.y(), cell.z());
                if (drawRotated(ms, buffers, packedLight, cell, time)) {
                    continue;
                }
                drawStaticFallback(ms, buffers, packedLight, cell);
            } catch (Throwable error) {
                // 逐格兜底：姿态栈在 finally 里一定配平地弹回，异常绝不冒泡
                CreateCMPOR.LOGGER.debug("[预览] 动态格绘制失败：{} @({},{},{})",
                        cell.state(), cell.x(), cell.y(), cell.z(), error);
            } finally {
                ms.popPose();
            }
        }
    }

    /** 旋转绘制；成功返回 {@code true}。任何异常都被吞掉并把该状态锁存为"走静态回退"。 */
    private static boolean drawRotated(PoseStack ms, MultiBufferSource buffers, int packedLight,
                                       PreviewDynamicCell cell, float time) {
        if (PreviewDynamicParts.isFailed(cell.state())) {
            return false;
        }
        PreviewDynamicParts.Rotation rotation = cell.rotation();
        PreviewDynamicParts.Part[] parts = rotation.parts();
        try {
            for (int i = 0; i < parts.length; i++) {
                PreviewDynamicParts.Part part = parts[i];
                // 角度口径与 Create KineticBlockEntityRenderer.getAngleForBe 完全一致：
                //   angle_deg = renderTime × speed × 3/10 + 相位；再 /180×π 转弧度。
                // 渲染时间用 catnip AnimationTickHolder（客户端静态 tick + partial tick），
                // 与 Create 各渲染器同源，避免两条路径相位差一个常量。
                float degrees = (time * cell.speed() * 0.3F + cell.phaseAt(i)) % 360.0F;
                float radians = degrees / 180.0F * (float) Math.PI;
                SuperByteBuffer buffer = part.buffer()
                        .light(packedLight)
                        .rotateCentered(radians, Direction.get(AxisDirection.POSITIVE, part.axis()));
                // Create 的 renderSafe 常在自旋之后追加朝向修正（如机械轴承顶板），顺序必须原样保留
                for (PreviewDynamicParts.FixedRotation fixed : part.tail()) {
                    buffer.rotateCentered(fixed.radians(), Direction.get(AxisDirection.POSITIVE, fixed.axis()));
                }
                buffer.renderInto(ms, buffers.getBuffer(part.layer()));
            }
            return true;
        } catch (Throwable error) {
            PreviewDynamicParts.markFailed(cell.state());
            CreateCMPOR.LOGGER.debug("[预览] 动态部件渲染失败，该机型锁存为静态：{}", cell.state(), error);
            return false;
        }
    }

    /** 静态回退：不旋转地把该格的方块模型画一次，等价于"这一格从没被踢出静态层"。 */
    private static void drawStaticFallback(PoseStack ms, MultiBufferSource buffers, int packedLight,
                                           PreviewDynamicCell cell) {
        if (cell.bakedStatically()) {
            // 外壳本来就在静态图层里 → 不重复画（多画一遍会让半透明几何叠加变脏）
            return;
        }
        try {
            SuperByteBuffer fallback = PreviewDynamicParts.fallback(cell.state());
            if (fallback == null) {
                return;
            }
            fallback.light(packedLight)
                    .renderInto(ms, buffers.getBuffer(PreviewDynamicParts.fallbackLayer(cell.state())));
        } catch (Throwable error) {
            CreateCMPOR.LOGGER.debug("[预览] 静态回退也失败，本帧放弃这一格：{}", cell.state(), error);
        }
    }
}
