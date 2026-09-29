package com.yansunsky.createcmpor.client;

import com.yansunsky.createcmpor.block.FactoryBlock;
import com.yansunsky.createcmpor.block.FactoryBlockEntity;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.IRotate;
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
 * 工厂的 vanilla BER 兜底：对每个开口面渲染一段半轴（SHAFT_HALF）。
 * Flywheel 可用时由 FactoryVisual 渲染（本 renderer 由基类自动跳过）；
 * 仅在无 Flywheel 时兜底，保证六面开口都有轴可见。
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
    private static void renderDisplayStub(BlockState blockState, PoseStack ms, MultiBufferSource buffer) {
        SuperByteBuffer stub = CachedBuffers.partialFacing(AllPartialModels.SHAFT_HALF, blockState, Direction.DOWN);
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
        if (block instanceof FactoryBlock && !blockState.getValue(FactoryBlock.ENCASED)) {
            // 展示模式：底面短轴同样必须在 Flywheel 早退之前画——展示模式下 Flywheel 视觉不画任何轴，
            // 若把这段放到早退之后，开 Flywheel 的玩家会一根轴都看不到。
            renderDisplayStub(blockState, ms, buffer);
            return;
        }

        super.renderSafe(be, partialTicks, ms, buffer, light, overlay);
        if (VisualizationManager.supportsVisualization(be.getLevel())) {
            return;
        }
        if (!(block instanceof IRotate def)) {
            return;
        }

        Axis axis = getRotationAxisOf(be);
        float angle = getAngleForBe(be, be.getBlockPos(), axis);

        for (Direction d : Direction.values()) {
            if (!def.hasShaftTowards(be.getLevel(), be.getBlockPos(), blockState, d)) {
                continue;
            }
            SuperByteBuffer shaft = CachedBuffers.partialFacing(AllPartialModels.SHAFT_HALF, be.getBlockState(), d);
            kineticRotationTransform(shaft, be, axis, angle, light);
            shaft.renderInto(ms, buffer.getBuffer(RenderType.solid()));
        }
    }

    /** 主体渲染：方块自身的 blockstate 模型（multipart 开孔由模型层负责）。 */
    @Override
    protected SuperByteBuffer getRotatedModel(FactoryBlockEntity be, BlockState state) {
        return CachedBuffers.block(state);
    }
}
