package com.yansunsky.createcmpor.client;

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

    public FactoryRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected void renderSafe(FactoryBlockEntity be, float partialTicks, PoseStack ms,
                              MultiBufferSource buffer, int light, int overlay) {
        super.renderSafe(be, partialTicks, ms, buffer, light, overlay);
        if (VisualizationManager.supportsVisualization(be.getLevel())) {
            return;
        }

        BlockState blockState = be.getBlockState();
        Block block = blockState.getBlock();
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
