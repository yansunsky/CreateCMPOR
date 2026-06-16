package com.createcmpor.block;

import com.createcmpor.CreateCMPOR;
import com.createcmpor.init.ModBlockEntities;
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

import java.util.List;

/**
 * 应力拓展方块的方块实体。
 *
 * <p>继承 {@link GeneratingKineticBlockEntity}，既是 Create 应力网络的成员，也能作为应力源/负载。
 *
 * <p>核心职责：
 * <ol>
 *     <li>扫描非轴向四面，绑定相邻 CMPOR 工厂方块（{@link #boundFactoryPos}），供 IO capability 代理使用。</li>
 *     <li>读取该工厂的应力档案（{@link StressProfile}，评估固化后写入）：
 *         <ul>
 *             <li>PROVIDE：作为应力源向 Create 网络供给转速与应力容量。</li>
 *             <li>CONSUME：作为负载向网络施加应力需求（需外部源带动）。</li>
 *         </ul>
 *     </li>
 * </ol>
 */
public class StressExtensionBlockEntity extends GeneratingKineticBlockEntity {

    /** 相邻 CMPOR 工厂方块的坐标；null 表示当前没有贴靠工厂。 */
    @Nullable
    private BlockPos boundFactoryPos;

    /** 缓存的相邻工厂应力档案（每秒刷新）。 */
    private StressProfile factoryProfile = StressProfile.EMPTY;

    /** 扫描相邻工厂的节流计数（避免每 tick 全方向查询）。 */
    private int scanCooldown = 0;

    public StressExtensionBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.STRESS_EXTENSION.get(), pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);
    }

    /**
     * 生成速度：仅当相邻工厂为 PROVIDE 模式时，沿轴向供给工厂记录的转速；否则 0。
     */
    @Override
    public float getGeneratedSpeed() {
        if (factoryProfile.mode() != StressProfile.Mode.PROVIDE)
            return 0;
        Direction.Axis axis = getBlockState().getValue(StressExtensionBlock.AXIS);
        Direction dir = Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE);
        return convertToDirection(factoryProfile.speed(), dir);
    }

    /**
     * 向网络贡献的应力容量：PROVIDE 模式 = 工厂可提供的应力；否则 0。
     */
    @Override
    public float calculateAddedStressCapacity() {
        float capacityProvided = (factoryProfile.mode() == StressProfile.Mode.PROVIDE)
                ? factoryProfile.stress() : 0f;
        this.lastCapacityProvided = capacityProvided;
        return capacityProvided;
    }

    /**
     * 向网络施加的应力消耗：CONSUME 模式 = 工厂所需应力；否则 0。
     */
    @Override
    public float calculateStressApplied() {
        float stressApplied = (factoryProfile.mode() == StressProfile.Mode.CONSUME)
                ? factoryProfile.stress() : 0f;
        this.lastStressApplied = stressApplied;
        return stressApplied;
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide())
            return;
        if (--scanCooldown > 0)
            return;
        scanCooldown = 20; // 每秒扫描一次
        updateBoundFactory();

        StressProfile old = factoryProfile;
        factoryProfile = readFactoryProfile();
        if (!old.equals(factoryProfile)) {
            CreateCMPOR.LOGGER.info("[CreateCMPOR] stress_extension @{} 工厂应力档案变更: {} -> {}",
                    worldPosition, old, factoryProfile);
            // 模式/数值变化：刷新应力源并通知网络容量变化
            updateGeneratedRotation();
            notifyStressCapacityChange(calculateAddedStressCapacity());
            setChanged();
        }
    }

    /** 读取绑定工厂的应力档案；无绑定则 EMPTY。 */
    private StressProfile readFactoryProfile() {
        if (level == null || boundFactoryPos == null)
            return StressProfile.EMPTY;
        BlockEntity be = level.getBlockEntity(boundFactoryPos);
        return FactoryStressAccess.get(be);
    }

    /** 扫描非轴向四个侧面，找到第一个相邻的 CMPOR 工厂方块。 */
    private void updateBoundFactory() {
        if (level == null)
            return;
        Direction.Axis axis = getBlockState().getValue(StressExtensionBlock.AXIS);
        BlockPos found = null;
        for (Direction dir : Direction.values()) {
            if (dir.getAxis() == axis)
                continue; // 跳过轴向两端（那是应力接口面）
            BlockPos neighbor = worldPosition.relative(dir);
            BlockState neighborState = level.getBlockState(neighbor);
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(neighborState.getBlock());
            if (id.toString().equals(CreateCMPOR.CMPOR_FACTORY_BLOCK_ID)) {
                found = neighbor;
                break;
            }
        }
        if (found != null && !found.equals(boundFactoryPos)) {
            CreateCMPOR.LOGGER.debug("[CreateCMPOR] stress_extension @{} 绑定工厂 {}", worldPosition, found);
        }
        this.boundFactoryPos = found;
    }

    /** @return 当前绑定的 CMPOR 工厂方块坐标，可能为 null。 */
    @Nullable
    public BlockPos getBoundFactoryPos() {
        return boundFactoryPos;
    }

    /** @return 该侧面是否为非轴向的 IO 拓展面（轴向两端不拓展 IO，仅接应力）。 */
    public boolean isIoFace(@Nullable Direction side) {
        if (side == null)
            return true;
        return side.getAxis() != getBlockState().getValue(StressExtensionBlock.AXIS);
    }
}
