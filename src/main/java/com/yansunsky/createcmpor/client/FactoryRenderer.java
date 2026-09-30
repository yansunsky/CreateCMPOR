package com.yansunsky.createcmpor.client;

import com.yansunsky.createcmpor.block.FactoryBlock;
import com.yansunsky.createcmpor.block.FactoryBlockEntity;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import com.mojang.blaze3d.vertex.PoseStack;

/**
 * 工厂的 vanilla BER 兜底：展示态画底面 2px 短轴；包壳态对每个开口面渲染一段半轴（SHAFT_HALF）。
 * Flywheel 可用时由 FactoryVisual 渲染（本 renderer 由基类自动跳过）；本类负责无 Flywheel 时的兜底。
 */
public class FactoryRenderer extends KineticBlockEntityRenderer<FactoryBlockEntity> {

    /**
     * 展示模式短轴长度（像素）。
     *
     * <p>用户 2026-09-29 反馈：底座 3px 时短轴也是 3px 会与底座外表面共面 → z-fighting 锯齿。
     * 现改为 2px：底座底面内凹 1px（见 {@code factory_display.json} 的 2px 外圈 + 天花板），
     * 短轴 2px 恰好从凹槽里探出一点，形似 Create 的 {@code create:encased_shaft}。
     */
    private static final float BASE_PX = 2.0F;

    public FactoryRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    /**
     * 展示模式的底面短轴（用户要求"传动杆只在底座渲染一小节"）。
     *
     * <p>现有 `AllPartialModels.SHAFT_HALF` 是 8px 深的半轴（实测模型 `[6,6,8]→[10,10,16]`），
     * 直接用会伸进展示区 5px；这里沿 Y 轴把它缩放到 {@link #BASE_PX}/8 —— 缩放围绕方块底面（y=0），
     * 所以短轴恰好停在底座高度内。该轴恒竖直、4×4 截面关于 Y 轴对称，故无需旋转变换。
     *
     * <p>另：`RotatingInstance` 没有 scale 字段（只有位置与四元数），所以这条路径只能走 vanilla BER；
     * Flywheel 视觉在展示模式下不画轴（见 {@code FactoryVisual#shouldShowShaft}）。
     */
    private static void renderDisplayStub(FactoryBlockEntity be, BlockState blockState, PoseStack ms,
                                          MultiBufferSource buffer, int light) {
        SuperByteBuffer stub = CachedBuffers.partialFacing(AllPartialModels.SHAFT_HALF, blockState, Direction.DOWN);
        // 施加真实转角：否则轴是静止的，会被误判成"没接上应力/不能传动"（用户 2026-09-29 反馈）。
        // 转角在 buffer 内部绕方块中心旋转；随后的姿态栈 Y 压缩发生在旋转之后，而该轴关于 Y 轴对称、
        // 且压缩把几何朝底面（y=0）收，故不会破坏位置对齐。
        float angle = getAngleForBe(be, be.getBlockPos(), Direction.Axis.Y);
        kineticRotationTransform(stub, be, Direction.Axis.Y, angle, light);
        ms.pushPose();
        ms.scale(1.0F, BASE_PX / 8.0F, 1.0F);
        stub.renderInto(ms, buffer.getBuffer(RenderType.solid()));
        ms.popPose();
    }

    @Override
    protected void renderSafe(FactoryBlockEntity be, float partialTicks, PoseStack ms,
                              MultiBufferSource buffer, int light, int overlay) {
        // 微缩预览（0.4.0）：必须放在 Flywheel 早退之前——工厂注册视觉用的是 skipVanillaRender(false)，
        // 所以 Flywheel 开启时本方法仍每帧被调用；放在早退之后会导致"开 Flywheel 的玩家看不到预览"。
        FactoryPreviewRenderer.render(be, ms, buffer, light);

        BlockState blockState = be.getBlockState();
        Block block = blockState.getBlock();
        if (!(block instanceof FactoryBlock factoryBlock)) {
            return;
        }
        if (!blockState.getValue(FactoryBlock.ENCASED)) {
            // 展示模式：底面短轴同样必须在 Flywheel 早退之前画——展示模式下 Flywheel 视觉不画任何轴，
            // 若把这段放到早退之后，开 Flywheel 的玩家会一根轴都看不到。
            renderDisplayStub(be, blockState, ms, buffer, light);
            return;
        }

        // ⚠️ 0.4.30 教训：这里**绝对不能再画一次方块模型**。
        // 曾试过 `CachedBuffers.block(blockState).renderInto(ms, buffer.getBuffer(RenderType.cutoutMipped()))`
        // 取代 `super.renderSafe`，结果把外壳**重画了一遍**（用户实测：正常灰色机壳上又叠了一层发黑带网格的壳）。
        // 原因：模型的多层绘制本应**按模型声明的渲染类型分层**（玻璃 translucent、面板 solid），
        // 手写一个 `cutoutMipped` 会把玻璃/面板当不透明糊上去。
        // 而且方块自己的模型**本来就由 chunk 的 MODEL 渲染路径画了**（`getRenderShape` 返回 MODEL），
        // BER 只应负责模型表达不了的东西 —— 也就是下面这些**开口面的半轴**。

        if (VisualizationManager.supportsVisualization(be.getLevel())) {
            // Flywheel 开启：半轴由 FactoryVisual 画（那里逐面传轴，见该类的 update 注释）。
            return;
        }

        // 无 Flywheel 的兜底：**逐面取该面自己的轴**。
        // 旧实现六面共用 getRotationAxisOf(be)（= FactoryBlock.getRotationAxis）——多面开口时，
        // 非该轴的半轴会"绕错轴翻滚"而不是自转。getAngleForBe 与 kineticRotationTransform 都接受
        // Axis 参数，且相位偏移（KineticBlockEntityVisual.rotationOffset → shouldOffset）本身就是逐轴的，
        // 故必须逐面重算。参照实现：createadditionallogistics:flexible_shaft 的
        // LowEntityKineticBlockEntityRenderer（同一写法）。
        for (Direction d : Direction.values()) {
            if (!factoryBlock.hasShaftTowards(be.getLevel(), be.getBlockPos(), blockState, d)) {
                continue;
            }
            Axis axis = d.getAxis();
            float angle = getAngleForBe(be, be.getBlockPos(), axis);
            SuperByteBuffer shaft = CachedBuffers.partialFacing(AllPartialModels.SHAFT_HALF, blockState, d);
            kineticRotationTransform(shaft, be, axis, angle, light);
            shaft.renderInto(ms, buffer.getBuffer(RenderType.solid()));
        }
    }

    /**
     * 0.4.30：**刻意返回 {@code null}**——本渲染器不再走"旋转整个方块模型"这条路。
     *
     * <p>基类的 {@code renderSafe} 会把本方法的返回值交给
     * {@code renderRotatingBuffer → standardKineticRotationTransform}，而那个变换读的是
     * {@link FactoryBlock#getRotationAxis}（自 0.4.29 起**恒为 Y 的占位值**）⇒ 会把**整个机壳/玻璃模型绕 Y 转**。
     * 机壳是静止几何，不该转；真正要转的只有开口面的半轴。
     *
     * <p>{@link #renderSafe} 已经**不调 {@code super.renderSafe}**（自己画静态模型 + 逐面半轴），
     * 所以这里返回 null 主要是**防御性**的：若将来有人改回 {@code super.renderSafe}，
     * 也不会悄悄把机壳转起来。
     */
    @Override
    protected SuperByteBuffer getRotatedModel(FactoryBlockEntity be, BlockState state) {
        return null;
    }
}
