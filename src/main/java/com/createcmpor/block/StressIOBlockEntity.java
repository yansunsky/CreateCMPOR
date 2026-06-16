package com.createcmpor.block;

import com.createcmpor.Config;
import com.createcmpor.init.ModBlockEntities;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * 应力 IO 方块的方块实体。
 *
 * <p>继承 {@link GeneratingKineticBlockEntity}，激活后根据本地应力网络的盈亏决定工作模式：
 * <ul>
 *     <li><b>输入模式</b>（网络应力余量 &lt; 0，即缺应力）：提供一个「类创造马达」的应力源，
 *         按 {@link #INPUT_SPEED} 转速为内部网络补足最低转速与所需应力。</li>
 *     <li><b>输出模式</b>（网络应力余量 &gt; 0，即有剩余）：标记为可向外提供应力，
 *         由外部应力拓展方块取用（受 {@link Config#ENABLE_STRESS_OUTPUT} 约束）。</li>
 * </ul>
 *
 * <p>当 {@link Config#ENABLE_STRESS_OUTPUT} 为 {@code false} 时，禁止输出，
 * 仅在缺应力时作为输入源工作，绝不向外提供。
 */
public class StressIOBlockEntity extends GeneratingKineticBlockEntity {

    /** 输入模式下提供的转速（类创造马达，提供最低转速）。 */
    public static final int INPUT_SPEED = 16;

    public StressIOBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.STRESS_IO.get(), pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);
    }

    @Override
    public void initialize() {
        super.initialize();
        if (!hasSource() || getGeneratedSpeed() > getTheoreticalSpeed())
            updateGeneratedRotation();
    }

    /**
     * 生成速度：仅在激活且处于「输入模式」时提供转速；否则为 0。
     *
     * <p>输入模式判定：网络当前应力余量（容量-应力）&lt; 0，说明网络缺应力需要补足。
     * 当本方块尚未入网或刚放置时，{@code capacity}/{@code stress} 为 0，余量为 0，
     * 此时默认提供转速以引导网络建立（与创造马达初始行为一致）。
     */
    @Override
    public float getGeneratedSpeed() {
        if (!isActive())
            return 0;

        // 余量 < 0 → 输入模式，供应力；余量 >= 0 → 已满足，无需本方块发电
        float remaining = capacity - stress;
        boolean needInput = remaining < 0 || (capacity == 0 && stress == 0);
        if (!needInput)
            return 0;

        // 沿 FACING 方向供给最低转速（类创造马达）
        return convertToDirection(INPUT_SPEED, getBlockState().getValue(StressIOBlock.FACING));
    }

    /** @return 该方块是否处于激活状态（由 {@link StressIOBlock#ACTIVE} blockstate 决定）。 */
    public boolean isActive() {
        return getBlockState().getValue(StressIOBlock.ACTIVE);
    }

    /**
     * @return 是否允许向外提供应力（输出模式）。
     * 当 {@link Config#ENABLE_STRESS_OUTPUT} 为 false 时恒为 false。
     */
    public boolean canOutput() {
        if (!Config.ENABLE_STRESS_OUTPUT.get())
            return false;
        // 仅当网络有剩余应力时才可向外输出
        return (capacity - stress) > 0;
    }

    /** 激活状态变化时调用，刷新发电状态。 */
    public void onActiveChanged() {
        updateGeneratedRotation();
    }
}
