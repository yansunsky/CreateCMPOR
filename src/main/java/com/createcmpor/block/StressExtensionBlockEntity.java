package com.createcmpor.block;

import com.createcmpor.Config;
import com.createcmpor.CreateCMPOR;
import com.createcmpor.init.ModBlockEntities;
import com.createcmpor.stress.FactorySatisfaction;
import com.createcmpor.stress.FactoryStressAccess;
import com.createcmpor.stress.StressProfile;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
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
 * <p>由于 {@link StressExtensionBlock#hasShaftTowards} 对所有面返回 true，
 * 相邻的应力拓展方块自动属于同一 Create 应力网络（无需手动连轴）。
 *
 * <p>核心逻辑（每秒刷新）：
 * <ol>
 *     <li><b>链式收集</b>：沿相邻的应力拓展方块链 BFS，收集链上所有工厂方块的应力档案并求和。</li>
 *     <li><b>输出模式</b>（净应力 > 0）：提供转速 + 应力容量（扣除损耗）。</li>
 *     <li><b>输入模式</b>（净应力 < 0）：作为负载消耗应力，满足后标记工厂可工作。</li>
 * </ol>
 *
 * <p><b>关键 API 注意事项：</b>
 * <ul>
 *     <li>{@code calculateAddedStressCapacity()} 返回的是 <b>stress value</b>（未乘转速），
 *         Create 内部在计算网络容量时会自动乘以 {@code |speed|}。</li>
 *     <li>因此我们返回的值应该是 <b>原始 SU 值</b>（即工厂档案中的 outputSU），
 *         不要手动乘转速——否则会导致双重乘法，输出应力远大于理论值。</li>
 *     <li>同理 {@code calculateStressApplied()} 也返回原始 SU 值，Create 内部会乘转速。</li>
 * </ul>
 */
public class StressExtensionBlockEntity extends GeneratingKineticBlockEntity {

    /** 输入模式时本方块运行的转速。 */
    public static final int INPUT_SPEED = 32;

    private final List<BlockPos> chainFactories = new ArrayList<>();
    private float chainNetStress = 0f;
    private float chainSpeed = 0f;
    private int scanCooldown = 0;

    public StressExtensionBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.STRESS_EXTENSION.get(), pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);
    }

    // ===================== Create 应力网络接入 =====================

    /**
     * 输出模式时提供转速；输入模式也提供转速（作为负载载体需要被驱动）。
     * 无应力交互时返回 0。
     */
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
     * <p>返回的是 stress value（未乘转速），Create 内部会乘 |speed| 得到实际容量。
     */
    @Override
    public float calculateAddedStressCapacity() {
        float cap = 0f;
        if (chainNetStress > 0f) {
            double loss = Config.STRESS_LOSS_FACTOR.get();
            cap = (float) (chainNetStress * (1.0 - loss));
        }
        this.lastCapacityProvided = cap;
        return cap;
    }

    /**
     * 输入模式时作为负载向网络施加应力消耗。
     * <p>返回的是 stress value（未乘转速），Create 内部会乘 |speed| 得到实际消耗。
     */
    @Override
    public float calculateStressApplied() {
        float su = (chainNetStress < 0f) ? -chainNetStress : 0f;
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
        rebuildChain();

        if (oldNet != chainNetStress) {
            CreateCMPOR.LOGGER.info("[CreateCMPOR] stress_extension @{} 链净应力变更: {} -> {} (工厂{}个, 转速{})",
                    worldPosition, oldNet, chainNetStress, chainFactories.size(), chainSpeed);
            updateGeneratedRotation();
            // 安全刷新：先确保网络存在
            if (hasNetwork()) {
                notifyStressCapacityChange(calculateAddedStressCapacity());
            }
            setChanged();
        }

        // 输入模式：检查外部网络是否满足需求
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
     * 沿相邻应力拓展方块链 BFS，收集链上所有方块各自贴合的工厂（去重），并对其净应力求和。
     */
    private void rebuildChain() {
        chainFactories.clear();
        chainNetStress = 0f;
        chainSpeed = 0f;
        if (level == null)
            return;

        Set<BlockPos> visitedExt = new HashSet<>();
        Set<BlockPos> seenFactory = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(worldPosition);
        visitedExt.add(worldPosition);

        while (!queue.isEmpty()) {
            BlockPos ext = queue.poll();
            for (Direction d : Direction.values()) {
                BlockPos np = ext.relative(d);
                BlockState ns = level.getBlockState(np);
                ResourceLocation id = BuiltInRegistries.BLOCK.getKey(ns.getBlock());
                String idStr = id.toString();
                if (CreateCMPOR.CMPOR_FACTORY_BLOCK_ID.equals(idStr)) {
                    if (seenFactory.add(np))
                        accumulateFactory(np);
                } else if ("createcmpor:stress_extension".equals(idStr)) {
                    if (visitedExt.add(np))
                        queue.add(np);
                }
            }
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

    /**
     * 该侧面是否为 IO 拓展面（用于 capability 代理）。
     * 所有面都可接轴，但 IO 代理只在非轴向面生效。
     */
    public boolean isIoFace(@Nullable Direction side) {
        if (side == null)
            return true;
        return side.getAxis() != getBlockState().getValue(StressExtensionBlock.AXIS);
    }
}
