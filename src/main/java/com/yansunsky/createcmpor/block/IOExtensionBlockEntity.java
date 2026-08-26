package com.yansunsky.createcmpor.block;

import com.yansunsky.createcmpor.CreateCMPOR;
import com.yansunsky.createcmpor.init.ModBlockEntities;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * IO 拓展方块的方块实体（原应力拓展方块）。
 *
 * <p>继承普通 {@link KineticBlockEntity}——工厂方块本身已能直接传入/传出应力
 * （{@link FactoryBlockEntity} 是 {@code GeneratingKineticBlockEntity}），本方块只需
 * 像普通传动轴一样被动传递转速，不再承担应力源/负载、Primary 选举、链应力聚合等职责。
 *
 * <p><b>核心功能：拓展工厂 IO</b>：
 * <ul>
 *     <li>本方块只能由<b>应力接口面（轴向两端）</b>与工厂开口面/其他 IO 拓展方块相连。</li>
 *     <li>非轴向四面是 IO 拓展面：把链上触达的所有工厂方块的物品/流体/能量缓存
 *         （输入+输出）代理转发到此处（{@link #isIoFace}）。</li>
 *     <li><b>直接相邻</b>：轴向端直接贴工厂开口面 → 转发该工厂缓存。</li>
 *     <li><b>间接相邻</b>：轴向端通过一串轴向相连的 IO 拓展方块连到工厂 → 整条链
 *         上每个方块都转发链触达的所有工厂缓存（{@link #getReachableFactories()}）。</li>
 *     <li><b>自毁防呆</b>：若本方块轴向两端都能（直接或间接）触达工厂缓存，说明它把
 *         两个工厂的缓存串在了一起，存在物品复制漏洞风险 → 自动破坏自身（与 Create
 *         两个相反转向应力源连接时传动轴断裂的行为一致，见
 *         {@code com.simibubi.create.content.kinetics.RotationPropagator}）。</li>
 * </ul>
 */
public class IOExtensionBlockEntity extends KineticBlockEntity {

    private int scanCooldown = 0;

    public IOExtensionBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.IO_EXTENSION.get(), pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);
    }

    // ===================== 供 capability 联合拓展 =====================

    /**
     * 该侧面是否为非轴向的 IO 拓展面（轴向两端不拓展 IO，仅接应力）。
     */
    public boolean isIoFace(@Nullable Direction side) {
        if (side == null)
            return true;
        return side.getAxis() != getBlockState().getValue(IOExtensionBlock.AXIS);
    }

    /**
     * 本方块能触达的所有工厂方块坐标（IO 转发目标）。
     *
     * <p>BFS 沿<b>轴向两端</b>（应力接口面）扩展：经过轴向对齐的 IO 拓展方块
     * （间接相邻），遇到贴在本方块或链上任意成员轴向端的工厂<b>开口面</b>
     * （{@link FactoryBlock#SHAFT_BY_FACE}=true）即收集。整条链上的每个方块
     * 都返回链触达的所有工厂——直接相邻与间接相邻统一覆盖。
     */
    public List<BlockPos> getReachableFactories() {
        List<BlockPos> result = new ArrayList<>();
        if (level == null)
            return result;
        Set<Long> visited = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(worldPosition);
        visited.add(worldPosition.asLong());
        while (!queue.isEmpty()) {
            BlockPos cur = queue.poll();
            BlockState curState = level.getBlockState(cur);
            if (!isIoExtensionState(curState))
                continue; // 防御：队列里只应有 IO 拓展方块
            Direction.Axis curAxis = curState.getValue(IOExtensionBlock.AXIS);
            for (Direction d : Direction.values()) {
                if (d.getAxis() != curAxis)
                    continue; // 只沿轴向两端扩展
                BlockPos np = cur.relative(d);
                if (visited.contains(np.asLong()))
                    continue;
                BlockState ns = level.getBlockState(np);
                if (isIoExtensionState(ns)) {
                    // 相邻拓展方块的该面必须也是轴向端（轴向对齐）
                    if (ns.getValue(IOExtensionBlock.AXIS) != d.getAxis())
                        continue;
                    visited.add(np.asLong());
                    queue.add(np);
                } else if (isFactoryState(ns)) {
                    // 工厂面向本方块的那一面（d 的反方向）必须是开口面（应力接口）
                    if (!ns.getValue(FactoryBlock.SHAFT_BY_FACE.get(d.getOpposite())))
                        continue;
                    visited.add(np.asLong());
                    result.add(np);
                }
            }
        }
        return result;
    }

    // ===================== 自毁防呆 =====================

    /**
     * 自毁检测：若本方块轴向两端都能（直接或间接）触达工厂缓存，则自动破坏自身。
     *
     * <p>这表示本方块把两个工厂的缓存串在了一起（物品可从一方流入另一方，或通过
     * 两个访问点造成复制漏洞），与 Create 中两个相反转向应力源被传动轴连接时传动轴
     * 断裂（{@code world.destroyBlock(pos, true)}）的行为一致。
     */
    public void checkSelfDestruct() {
        if (level == null || level.isClientSide())
            return;
        Direction.Axis axis = getBlockState().getValue(IOExtensionBlock.AXIS);
        Direction posDir = Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE);
        Direction negDir = posDir.getOpposite();
        Set<BlockPos> a = factoriesFromEnd(negDir);
        Set<BlockPos> b = factoriesFromEnd(posDir);
        if (!a.isEmpty() && !b.isEmpty()) {
            CreateCMPOR.LOGGER.info("[CreateCMPOR] io_extension @{} 轴向两端均连接工厂缓存（负端 {} / 正端 {}），"
                    + "自动破坏自身防止物品复制漏洞", worldPosition, a, b);
            level.destroyBlock(worldPosition, true);
        }
    }

    /** 从本方块的 {@code startDir} 轴向端出发 BFS，收集触达的工厂（供自毁检测）。 */
    private Set<BlockPos> factoriesFromEnd(Direction startDir) {
        Set<BlockPos> factories = new HashSet<>();
        if (level == null)
            return factories;
        Direction.Axis axis = getBlockState().getValue(IOExtensionBlock.AXIS);
        Set<Long> visited = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();

        // 起点隔离：本方块自身加入 visited，防止 BFS 从起始端扩展时绕回另一侧
        // （否则"正端出发"会经链上其他成员绕回负端收集到工厂，导致两端都判为
        // 触达工厂而误自毁——连续拓展时旧方块被破坏的根因）。
        visited.add(worldPosition.asLong());

        BlockPos first = worldPosition.relative(startDir);
        BlockState fs = level.getBlockState(first);
        if (isIoExtensionState(fs) && fs.getValue(IOExtensionBlock.AXIS) == axis) {
            visited.add(first.asLong());
            queue.add(first);
        } else if (isFactoryState(fs) && fs.getValue(FactoryBlock.SHAFT_BY_FACE.get(startDir.getOpposite()))) {
            factories.add(first);
        }

        while (!queue.isEmpty()) {
            BlockPos cur = queue.poll();
            BlockState curState = level.getBlockState(cur);
            Direction.Axis curAxis = curState.getValue(IOExtensionBlock.AXIS);
            for (Direction d : Direction.values()) {
                if (d.getAxis() != curAxis)
                    continue;
                BlockPos np = cur.relative(d);
                if (visited.contains(np.asLong()))
                    continue;
                BlockState ns = level.getBlockState(np);
                if (isIoExtensionState(ns) && ns.getValue(IOExtensionBlock.AXIS) == curAxis) {
                    visited.add(np.asLong());
                    queue.add(np);
                } else if (isFactoryState(ns) && ns.getValue(FactoryBlock.SHAFT_BY_FACE.get(d.getOpposite()))) {
                    factories.add(np);
                }
            }
        }
        return factories;
    }

    // ===================== 每秒自毁复检 =====================

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide())
            return;
        if (--scanCooldown > 0)
            return;
        scanCooldown = 20;
        checkSelfDestruct();
    }

    // ===================== 方块判定 =====================

    private boolean isIoExtensionState(BlockState state) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return CreateCMPOR.IO_EXTENSION_BLOCK_ID.equals(id.toString());
    }

    private boolean isFactoryState(BlockState state) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return CreateCMPOR.FACTORY_BLOCK_ID.equals(id.toString());
    }
}
