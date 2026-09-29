package com.yansunsky.createcmpor.client.preview;

import net.minecraft.world.level.block.state.BlockState;

/**
 * 一个"动态格"的绘制计划：告诉渲染侧"这一格要绕哪些轴、转多快、相位多少"。
 *
 * <p>计划在<b>烘焙时算一次</b>（那时才有快照、有部件解析、有整表缩放系数），渲染侧每帧只做
 * {@code light + rotateCentered + renderInto}，不做任何几何计算或分配。
 *
 * <p>字段：
 * <ul>
 *     <li>{@code x/y/z}：快照网格内的微缩坐标（与静态烘焙 pg 用的是同一套坐标）；</li>
 *     <li>{@code speed}：<b>已按整表缩放</b>的显示转速（RPM，带符号）。角度 = {@code speed × 0.3} 度/客户端 tick
 *         ——与 Create {@code getAngleForBe} 的 {@code time × speed × 3/10} 同口径；</li>
 *     <li>{@code rotation}：旋转部件方案（可能有多块，例如变速箱 4 根半轴，各自绕不同轴）；</li>
 *     <li>{@code phaseDegrees}：与 {@code rotation.parts()} 一一对应的相位（度），
 *         来自 {@code KineticBlockEntityVisual.rotationOffset(state, axis, 微缩坐标)}——
 *         用它才能让相邻齿轮/大齿轮互相咬合，而不是随机错位；</li>
 *     <li>{@code bakedStatically}：该格<b>是否仍在静态图层里</b>。为 {@code false}（整块旋转类）时，
 *         动态绘制失败必须补一次静态绘制，否则这一格会<b>整格消失</b>；为 {@code true}（补件类）
 *         时外壳本来就在静态图层里，动态失败只是少了转动的细节，格子不会消失。</li>
 * </ul>
 */
public record PreviewDynamicCell(int x, int y, int z, BlockState state, float speed,
                                 PreviewDynamicParts.Rotation rotation, float[] phaseDegrees,
                                 boolean bakedStatically) {

    /** 相位查表（越界返回 0，避免部件数量变化时数组越界）。 */
    public float phaseAt(int partIndex) {
        return partIndex >= 0 && partIndex < phaseDegrees.length ? phaseDegrees[partIndex] : 0.0F;
    }
}
