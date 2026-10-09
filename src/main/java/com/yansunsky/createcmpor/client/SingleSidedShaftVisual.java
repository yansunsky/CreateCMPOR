package com.yansunsky.createcmpor.client;

import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.OrientedRotatingVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * 单面传动轴 visual：只在方块的 {@code FACING} 一侧画<b>半轴</b>。
 *
 * <p><b>为什么必须专门开一个</b>：{@code SingleAxisRotatingVisual.shaft(...)} 用的是
 * {@code AllPartialModels.SHAFT}（几何 {@code [6,0,6]→[10,16,10]}，<b>贯穿整格</b>），
 * 而那个构造器的 {@code Direction.UP} 只是"模型源朝向"（SHAFT 是 Y 对齐的），
 * 它会被 {@code rotateToFace(UP, rotationAxis)} 转到方块轴上 —— <b>但长度仍是整格</b>。
 * 于是"只从 FACING 面接轴"的方块（如应力输入方块）背面会多捅出一截 4×4×6 的轴。</p>
 *
 * <p><b>Create 自己的做法</b>（照抄，勿自创）：创造马达这类单面轴方块在 BER 里用
 * {@code CachedBuffers.partialFacing(AllPartialModels.SHAFT_HALF, state)}
 * —— 半轴几何是 {@code [6,6,8]→[10,10,16]}，即"从方块中心起、沿 <b>+Z</b> 伸出 8px"。
 * 因此 Flywheel 侧的对应写法是固定源朝向 {@code Direction.SOUTH}（与 +Z 半轴对齐），
 * 再转到目标面。参照实现：Create 的 {@code OrientedRotatingVisual.of(partial)}
 * 与 {@code BearingVisual} 的 {@code (SOUTH, FACING, SHAFT_HALF)}。</p>
 *
 * <p>对比：双面接轴的方块（如应力输出方块）应当继续用全长 {@code SHAFT}；沿轴两端接轴的
 * 方块（如 io_extension）同理 —— 它们的轴本来就该从两侧露出。</p>
 */
public final class SingleSidedShaftVisual<T extends KineticBlockEntity> extends OrientedRotatingVisual<T> {

    public SingleSidedShaftVisual(VisualizationContext context, T blockEntity, float partialTick, Direction facing) {
        super(context, blockEntity, partialTick, Direction.SOUTH, facing, Models.partial(AllPartialModels.SHAFT_HALF));
    }

    /** 工厂：读取方块的 {@code FACING} 决定半轴朝哪一面。 */
    public static <T extends KineticBlockEntity> SimpleBlockEntityVisualizer.Factory<T> of() {
        return (context, blockEntity, partialTick) -> new SingleSidedShaftVisual<>(
                context, blockEntity, partialTick,
                blockEntity.getBlockState().getValue(BlockStateProperties.FACING));
    }
}
