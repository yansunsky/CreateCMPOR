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
 * <p>核心逻辑（每秒刷新）：
 * <ol>
 *     <li><b>链式收集</b>：沿相邻的应力拓展方块链（不限跳数）做 BFS，收集链上所有方块各自贴合的
 *         CMPOR 工厂方块（去重），读取每个工厂的有符号应力档案并求和（{@link #chainNetStress}）。</li>
 *     <li><b>方向</b>：求和为正 → 输出（向外 Create 网络提供应力，经损耗后），为负 → 输入
 *         （作为负载，需外部应力带动；满足后标记工厂可工作）。</li>
 *     <li><b>IO 容器拓展</b>：链上任一工厂的物品/流体/能量 capability 由整条链共享（见
 *         {@link com.createcmpor.init.ModBlocks#registerCapabilities}，本类提供 {@link #getChainFactories}）。</li>
 * </ol>
 */
public class StressExtensionBlockEntity extends GeneratingKineticBlockEntity {

    /** 输入模式（链净应力为负，需外部供应）时本方块运行的转速。 */
    public static final int INPUT_SPEED = 32;

    /** 链上去重后的工厂坐标（用于 IO capability 联合拓展）。 */
    private final List<BlockPos> chainFactories = new ArrayList<>();

    /** 链上所有工厂有符号应力之和（正=净输出，负=净输入需求）。 */
    private float chainNetStress = 0f;

    /** 链上工厂转速档案的最大值。 */
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

    /** 净输出（链和为正）时沿轴向供给转速；否则若净输入则以 {@link #INPUT_SPEED} 运行作为负载载体。 */
    @Override
    public float getGeneratedSpeed() {
        Direction.Axis axis = getBlockState().getValue(StressExtensionBlock.AXIS);
        Direction dir = Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE);
        if (chainNetStress > 0f) {
            // 输出：向外提供工厂的转速
            return convertToDirection(chainSpeed > 0 ? chainSpeed : INPUT_SPEED, dir);
        }
        return 0;
    }

    /** 净输出时向网络贡献应力容量（= 工厂可提供应力，扣除损耗）。 */
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

    /** 净输入时作为负载向网络施加应力消耗（= |链净应力|）。 */
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
            notifyStressCapacityChange(calculateAddedStressCapacity());
            setChanged();
        }

        // 输入模式：若本地 Create 网络能满足需求（容量≥消耗），标记链上各工厂"应力已满足"→ 工厂可工作
        if (chainNetStress < 0f) {
            boolean satisfied = (capacity - stress) >= 0 && getSpeed() != 0;
            if (satisfied) {
                long now = level.getGameTime();
                for (BlockPos fp : chainFactories) {
                    FactorySatisfaction.markSatisfied(level.dimension(), fp, now);
                }
            }
        }
    }

    /**
     * 沿相邻应力拓展方块链 BFS，收集链上所有方块各自贴合的工厂（去重），并对其有符号应力求和。
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
            // 该拓展方块的非轴向四面找工厂；六面找相邻拓展方块（链不限轴向）
            Direction.Axis axis = level.getBlockState(ext).hasProperty(StressExtensionBlock.AXIS)
                    ? level.getBlockState(ext).getValue(StressExtensionBlock.AXIS) : null;
            for (Direction d : Direction.values()) {
                BlockPos np = ext.relative(d);
                BlockState ns = level.getBlockState(np);
                ResourceLocation id = BuiltInRegistries.BLOCK.getKey(ns.getBlock());
                String idStr = id.toString();
                if (CreateCMPOR.CMPOR_FACTORY_BLOCK_ID.equals(idStr)) {
                    // 工厂只在非轴向面拓展（轴向两端是应力接口面）
                    if (axis == null || d.getAxis() != axis) {
                        if (seenFactory.add(np))
                            accumulateFactory(np);
                    }
                } else if ("createcmpor:stress_extension".equals(idStr)) {
                    if (visitedExt.add(np))
                        queue.add(np);
                }
            }
        }
    }

    /** 累加单个工厂的有符号应力与转速。 */
    private void accumulateFactory(BlockPos factoryPos) {
        BlockEntity be = level.getBlockEntity(factoryPos);
        StressProfile profile = FactoryStressAccess.get(be);
        if (profile.isEmpty())
            return;
        chainFactories.add(factoryPos);
        chainNetStress += profile.net();       // input 为负、output 为正
        chainSpeed = Math.max(chainSpeed, profile.speed());
    }

    // ===================== 供 capability 联合拓展 =====================

    /** @return 链上去重后的所有工厂坐标（IO capability 联合拓展用）。 */
    public List<BlockPos> getChainFactories() {
        return chainFactories;
    }

    /** @return 该侧面是否为非轴向的 IO 拓展面（轴向两端仅接应力）。 */
    public boolean isIoFace(@Nullable Direction side) {
        if (side == null)
            return true;
        return side.getAxis() != getBlockState().getValue(StressExtensionBlock.AXIS);
    }
}
