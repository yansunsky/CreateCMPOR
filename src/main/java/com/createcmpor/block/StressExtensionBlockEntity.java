package com.createcmpor.block;

import com.createcmpor.Config;
import com.createcmpor.CreateCMPOR;
import com.createcmpor.init.ModBlockEntities;
import com.createcmpor.stress.FactorySatisfaction;
import com.createcmpor.stress.FactoryStressAccess;
import com.createcmpor.stress.StressProfile;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 应力拓展方块的方块实体。
 *
 * <p>继承 {@link GeneratingKineticBlockEntity}，是 Create 应力网络的成员，可作为应力源或负载。
 *
 * <p><b>链式收集 + Primary 选举</b>：
 * <ul>
 *     <li>BFS 遍历相邻的应力拓展方块，<b>穿过工厂方块</b>继续搜索，
 *         这样贴合同一工厂方块不同面的应力拓展方块都属于同一条链。</li>
 *     <li>链中所有拓展方块通过一致的确定性算法（{@code BlockPos.asLong()} 最小值）
 *         选举出<b>唯一一个 Primary 方块</b>。</li>
 *     <li><b>只有 Primary 方块</b>真正承载应力消耗/输出（{@code calculateStressApplied()} /
 *         {@code calculateAddedStressCapacity()} 返回非零值）。</li>
 *     <li><b>其余方块</b>返回 0，纯传递——就像 Create 的传动轴/齿轮箱，
 *         只传递转速不额外消耗应力。</li>
 * </ul>
 * 这样无论拓展了多少个应力拓展方块，它们应力网络的应力和都等于工厂方块的 input/output，
 * 不会因为方块数量增加而倍增。
 *
 * <p><b>关键 API 注意事项</b>：
 * <ul>
 *     <li>{@code calculateAddedStressCapacity()} 返回的是 <b>stress value</b>（未乘转速），
 *         Create 内部会乘 {@code |speed|} 得到实际容量。</li>
 *     <li>{@code StressProfile} 中的 SU 值是<b>实际 SU</b>（已含转速乘法，来自评估期采样）。</li>
 *     <li>因此返回值 = {@code SU / |speed|}，Create 再乘回 {@code |speed|} 得到正确值。</li>
 * </ul>
 */
public class StressExtensionBlockEntity extends GeneratingKineticBlockEntity {

    public static final int INPUT_SPEED = 32;

    /** 链上关联的工厂方块坐标列表。 */
    private final List<BlockPos> chainFactories = new java.util.ArrayList<>();
    /** 链上所有应力拓展方块的坐标（含自身）。 */
    private final List<BlockPos> chainMembers = new java.util.ArrayList<>();
    /** 链上应力和（负=需输入，正=可输出）。 */
    private float chainNetStress = 0f;
    /** 链上转速。 */
    private float chainSpeed = 0f;
    /** 本方块是否为链中的 Primary（唯一承载应力的方块）。 */
    private boolean isPrimary = true;
    private int scanCooldown = 0;

    public StressExtensionBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.STRESS_EXTENSION.get(), pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);
    }

    // ===================== Create 应力网络接入 =====================

    @Override
    public float getGeneratedSpeed() {
        if (chainNetStress == 0f)
            return 0;
        Direction.Axis axis = getBlockState().getValue(StressExtensionBlock.AXIS);
        Direction dir = Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE);
        float speed = chainSpeed > 0 ? chainSpeed : INPUT_SPEED;
        return convertToDirection(speed, dir);
    }

    /**
     * 输出模式时向网络贡献应力容量。
     * <p><b>只有 Primary 方块</b>返回非零值，其余返回 0（纯传递）。
     * <p>返回 stress value = outputSU / |speed|。
     * Create 内部会乘 |speed| 得到实际容量 = outputSU。
     */
    @Override
    public float calculateAddedStressCapacity() {
        if (!isPrimary || chainNetStress <= 0f) {
            this.lastCapacityProvided = 0f;
            return 0f;
        }
        float speed = Math.abs(getTheoreticalSpeed());
        if (speed == 0f) {
            this.lastCapacityProvided = 0f;
            return 0f;
        }
        double loss = Config.STRESS_LOSS_FACTOR.get();
        float cap = (float) (chainNetStress * (1.0 - loss) / speed);
        this.lastCapacityProvided = cap;
        return cap;
    }

    /**
     * 输入模式时作为负载向网络施加应力消耗。
     * <p><b>只有 Primary 方块</b>返回非零值，其余返回 0（纯传递）。
     * <p>返回 stress value = inputSU / |speed|。
     * Create 内部会乘 |speed| 得到实际消耗 = inputSU。
     */
    @Override
    public float calculateStressApplied() {
        if (!isPrimary || chainNetStress >= 0f) {
            this.lastStressApplied = 0f;
            return 0f;
        }
        float speed = Math.abs(getTheoreticalSpeed());
        if (speed == 0f) {
            this.lastStressApplied = 0f;
            return 0f;
        }
        float su = (-chainNetStress) / speed;
        this.lastStressApplied = su;
        return su;
    }

    // ===================== 每秒刷新链与档案 =====================

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide())
            return;
        if (--scanCooldown > 0)
            return;
        scanCooldown = 20;

        float oldNet = chainNetStress;
        boolean oldPrimary = isPrimary;
        rebuildChain();

        if (oldNet != chainNetStress || oldPrimary != isPrimary) {
            CreateCMPOR.LOGGER.info("[CreateCMPOR] stress_extension @{} 链变更: net={} primary={} (工厂{}个, 链成员{}个, 转速{})",
                    worldPosition, chainNetStress, isPrimary, chainFactories.size(), chainMembers.size(), chainSpeed);
            updateGeneratedRotation();
            if (hasNetwork()) {
                notifyStressCapacityChange(calculateAddedStressCapacity());
            }
            setChanged();
        }

        // 输入模式：检查外部网络是否满足本方块的应力需求
        // 只有 Primary 需要检查（非 Primary 不消耗应力）
        if (isPrimary && chainNetStress < 0f) {
            boolean satisfied = hasNetwork() && (capacity - stress) >= 0 && getSpeed() != 0;
            if (satisfied) {
                long now = level.getGameTime();
                for (BlockPos fp : chainFactories) {
                    FactorySatisfaction.markSatisfied(level.dimension(), fp, now);
                }
            }
        }
    }

    /**
     * BFS 遍历相邻的应力拓展方块<b>以及工厂方块</b>。
     * 穿过工厂方块继续搜索，这样贴合同一工厂不同面的拓展方块都在同一条链中。
     * 选举链中 BlockPos.asLong() 最小的方块作为 Primary。
     */
    private void rebuildChain() {
        chainFactories.clear();
        chainMembers.clear();
        chainNetStress = 0f;
        chainSpeed = 0f;
        if (level == null)
            return;

        Set<BlockPos> visited = new HashSet<>();
        Set<BlockPos> seenFactory = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();

        // 自身入队
        queue.add(worldPosition);
        visited.add(worldPosition);
        chainMembers.add(worldPosition);
        long minPosLong = worldPosition.asLong();

        while (!queue.isEmpty()) {
            BlockPos cur = queue.poll();
            for (Direction d : Direction.values()) {
                BlockPos np = cur.relative(d);
                if (visited.contains(np))
                    continue;
                BlockState ns = level.getBlockState(np);
                ResourceLocation id = BuiltInRegistries.BLOCK.getKey(ns.getBlock());
                String idStr = id.toString();

                if (CreateCMPOR.CMPOR_FACTORY_BLOCK_ID.equals(idStr)) {
                    // 工厂方块：累加应力档案，并加入队列穿过它继续搜索
                    visited.add(np);
                    if (seenFactory.add(np))
                        accumulateFactory(np);
                    queue.add(np); // 关键修复：穿过工厂方块继续 BFS
                } else if ("createcmpor:stress_extension".equals(idStr)) {
                    visited.add(np);
                    queue.add(np);
                    chainMembers.add(np);
                    minPosLong = Math.min(minPosLong, np.asLong());
                }
            }
        }

        // 选举 Primary：asLong() 最小的方块
        isPrimary = (worldPosition.asLong() == minPosLong);

        // 无链成员时保持默认（自身即 Primary）
        if (chainMembers.isEmpty()) {
            isPrimary = true;
        }
    }

    private void accumulateFactory(BlockPos factoryPos) {
        BlockEntity be = level.getBlockEntity(factoryPos);
        StressProfile profile = FactoryStressAccess.get(be);
        if (profile.isEmpty())
            return;
        chainFactories.add(factoryPos);
        chainNetStress += profile.net();
        chainSpeed = Math.max(chainSpeed, profile.netSpeed());
    }

    // ===================== 供 capability 联合拓展 =====================

    public List<BlockPos> getChainFactories() {
        return chainFactories;
    }

    public boolean isIoFace(@Nullable Direction side) {
        if (side == null)
            return true;
        return side.getAxis() != getBlockState().getValue(StressExtensionBlock.AXIS);
    }

    /** 本方块是否为链中的 Primary（调试/测试用）。 */
    public boolean isPrimary() {
        return isPrimary;
    }
}
