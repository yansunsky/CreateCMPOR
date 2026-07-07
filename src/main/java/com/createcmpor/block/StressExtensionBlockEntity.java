package com.createcmpor.block;

import com.createcmpor.Config;
import com.createcmpor.CreateCMPOR;
import com.createcmpor.init.ModBlockEntities;
import com.createcmpor.stress.FactorySatisfaction;
import com.createcmpor.stress.FactoryStressAccess;
import com.createcmpor.stress.StressProfile;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.base.IRotate;
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
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 应力拓展方块的方块实体。
 *
 * <p>继承 {@link GeneratingKineticBlockEntity}，是 Create 应力网络的成员，可作为应力源或负载。
 *
 * <p><b>双机制设计</b>：
 * <ul>
 *     <li><b>逻辑链（BFS）</b>：{@link #rebuildChain()} BFS 遍历相邻的应力拓展方块，
 *         <b>穿过工厂方块</b>继续搜索，收集所有关联工厂的应力档案，选举 Primary。</li>
 *     <li><b>物理网络（Create）</b>：通过覆盖 {@link #addPropagationLocations} 和
 *         {@link #propagateRotationTo}，让 Create 的 {@code RotationPropagator} "看穿"工厂方块，
 *         把贴在工厂不同面的应力拓展方块连接到同一个 {@code KineticNetwork}。</li>
 * </ul>
 *
 * <p><b>Primary 选举</b>：链中 {@code BlockPos.asLong()} 最小的方块为 Primary。
 * <ul>
 *     <li><b>只有 Primary</b> 生成转速（{@code getGeneratedSpeed()} 返回非零）并承载应力消耗/输出。</li>
 *     <li><b>其余方块</b>返回 0，作为被动成员接收转速——就像 Create 的传动轴/齿轮箱，
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

    /**
     * <b>只有 Primary 方块</b>生成转速。
     * 非 Primary 返回 0，成为被动成员，由 Create 的转速传播机制从 Primary 接收转速。
     * 这样所有拓展方块都在同一个 KineticNetwork 中，只有一个应力源。
     */
    @Override
    public float getGeneratedSpeed() {
        if (!isPrimary || chainNetStress == 0f)
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

    // ===================== Create 网络穿工厂连接 =====================

    /**
     * 告诉 Create 的 {@code RotationPropagator}：除了6个直接相邻位置，
     * 还要检查<b>穿过工厂方块</b>的对侧位置（2格远）。
     *
     * <p>这样贴在工厂方块不同面的应力拓展方块能被 Create 识别为潜在邻居，
     * 进而通过 {@link #propagateRotationTo} 建立连接，合并到同一个 KineticNetwork。
     */
    @Override
    public List<BlockPos> addPropagationLocations(IRotate block, BlockState state, List<BlockPos> neighbours) {
        super.addPropagationLocations(block, state, neighbours);
        if (level == null)
            return neighbours;
        for (Direction d : Direction.values()) {
            BlockPos adjacent = worldPosition.relative(d);
            if (isFactoryBlock(level.getBlockState(adjacent))) {
                // 把工厂方块周围6个面的位置全部加入邻居列表（排除自身位置）。
                // 这样不仅正对面（manhattan=2），斜对角（manhattan=2）的拓展方块
                // 也能被 Create 的 findConnectedNeighbour 发现并建立连接。
                for (Direction fd : Direction.values()) {
                    BlockPos around = adjacent.relative(fd);
                    if (!around.equals(worldPosition) && !neighbours.contains(around))
                        neighbours.add(around);
                }
            }
        }
        return neighbours;
    }

    /**
     * 当目标也是应力拓展方块、且中间隔着同一个工厂方块时，返回 1（1:1 同速同向传递）。
     *
     * <p>涵盖所有穿工厂的配对：
     * <ul>
     *     <li><b>正对面</b>（如上↔下）：diff 在单轴上 = ±2，manhattan=2</li>
     *     <li><b>斜对角面</b>（如前↔左）：diff 在两轴上各 = ±1，manhattan=2</li>
     * </ul>
     * 两种情况的 manhattan 距离都是 2，且都存在一个工厂方块同时与双方相邻。
     * 对于非工厂穿过的连接（如直接相邻），返回 0 让 Create 的默认逻辑处理。
     */
    @Override
    public float propagateRotationTo(KineticBlockEntity target, BlockState stateFrom, BlockState stateTo,
                                     BlockPos diff, boolean connectedByAxis, boolean connectedByGears) {
        // 只处理穿过工厂方块的连接（曼哈顿距离=2）
        int manhattan = Math.abs(diff.getX()) + Math.abs(diff.getY()) + Math.abs(diff.getZ());
        if (manhattan != 2)
            return 0;
        if (!(target instanceof StressExtensionBlockEntity))
            return 0;
        if (level == null)
            return 0;
        // 检查是否存在一个工厂方块同时与自身和目标相邻
        BlockPos targetPos = target.getBlockPos();
        for (Direction d : Direction.values()) {
            BlockPos factoryPos = worldPosition.relative(d);
            if (!isFactoryBlock(level.getBlockState(factoryPos)))
                continue;
            for (Direction d2 : Direction.values()) {
                if (factoryPos.relative(d2).equals(targetPos))
                    return 1; // 工厂方块作为 1:1 传动介质
            }
        }
        return 0;
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
//            CreateCMPOR.LOGGER.info("[CreateCMPOR] stress_extension @{} 链变更: net={} primary={} (工厂{}个, 链成员{}个, 转速{})",
//                    worldPosition, chainNetStress, isPrimary, chainFactories.size(), chainMembers.size(), chainSpeed);
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

                if (CreateCMPOR.FACTORY_BLOCK_ID.equals(idStr)) {
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

    private boolean isFactoryBlock(BlockState state) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return CreateCMPOR.FACTORY_BLOCK_ID.equals(id.toString());
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
