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
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 应力拓展方块的方块实体。
 *
 * <p>继承 {@link GeneratingKineticBlockEntity}，是 Create 应力网络的成员，可作为应力源或负载。
 *
 * <p><b>链式收集</b>：BFS 不仅遍历相邻的应力拓展方块，还<b>穿过工厂方块</b>继续搜索，
 * 这样贴合同一工厂方块不同面的应力拓展方块都属于同一条链，共享同一个应力档案。
 * 这解决了"不同面的拓展方块网络隔离"问题。
 *
 * <p><b>应力分摊</b>：链上每个拓展方块独立接入各自的 Create 应力网络，
 * 但每个方块只承担 {@code chainNetStress / chainSize} 的应力份额。
 * 这样 N 个拓展方块分摊工厂的总应力需求，不会重复计算。
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

    private final List<BlockPos> chainFactories = new ArrayList<>();
    private float chainNetStress = 0f;
    private float chainSpeed = 0f;
    /** 链上应力拓展方块的总数（含自身），用于分摊应力。 */
    private int chainSize = 1;
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
     * <p>返回 stress value = (分摊后的 outputSU) / |speed|。
     * Create 内部会乘 |speed| 得到实际容量 = 分摊后的 outputSU。
     */
    @Override
    public float calculateAddedStressCapacity() {
        if (chainNetStress <= 0f || chainSize <= 0) {
            this.lastCapacityProvided = 0f;
            return 0f;
        }
        float speed = Math.abs(getTheoreticalSpeed());
        if (speed == 0f) {
            this.lastCapacityProvided = 0f;
            return 0f;
        }
        double loss = Config.STRESS_LOSS_FACTOR.get();
        float sharePerBlock = chainNetStress / chainSize;
        float cap = (float) (sharePerBlock * (1.0 - loss) / speed);
        this.lastCapacityProvided = cap;
        return cap;
    }

    /**
     * 输入模式时作为负载向网络施加应力消耗。
     * <p>返回 stress value = (分摊后的 inputSU) / |speed|。
     * Create 内部会乘 |speed| 得到实际消耗 = 分摊后的 inputSU。
     */
    @Override
    public float calculateStressApplied() {
        if (chainNetStress >= 0f || chainSize <= 0) {
            this.lastStressApplied = 0f;
            return 0f;
        }
        float speed = Math.abs(getTheoreticalSpeed());
        if (speed == 0f) {
            this.lastStressApplied = 0f;
            return 0f;
        }
        float sharePerBlock = (-chainNetStress) / chainSize;
        float su = sharePerBlock / speed;
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
        int oldSize = chainSize;
        rebuildChain();

        if (oldNet != chainNetStress || oldSize != chainSize) {
            CreateCMPOR.LOGGER.info("[CreateCMPOR] stress_extension @{} 链变更: net={} -> {} size={} -> {} (工厂{}个, 转速{})",
                    worldPosition, oldNet, chainNetStress, oldSize, chainSize, chainFactories.size(), chainSpeed);
            updateGeneratedRotation();
            if (hasNetwork()) {
                notifyStressCapacityChange(calculateAddedStressCapacity());
            }
            setChanged();
        }

        // 输入模式：检查外部网络是否满足本方块分摊的份额
        if (chainNetStress < 0f) {
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
     */
    private void rebuildChain() {
        chainFactories.clear();
        chainNetStress = 0f;
        chainSpeed = 0f;
        chainSize = 0;
        if (level == null)
            return;

        Set<BlockPos> visited = new HashSet<>();
        Set<BlockPos> seenFactory = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();

        queue.add(worldPosition);
        visited.add(worldPosition);

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
                    // 工厂方块：累加应力档案，并穿过它继续搜索其他面的拓展方块
                    visited.add(np);
                    if (seenFactory.add(np))
                        accumulateFactory(np);
                } else if ("createcmpor:stress_extension".equals(idStr)) {
                    visited.add(np);
                    queue.add(np);
                    chainSize++;
                }
            }
        }

        if (chainSize == 0)
            chainSize = 1; // 至少算自身
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
}
