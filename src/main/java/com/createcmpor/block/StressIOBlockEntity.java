package com.createcmpor.block;

import com.createcmpor.Config;
import com.createcmpor.CreateCMPOR;
import com.createcmpor.init.ModBlockEntities;
import com.createcmpor.stress.StressEvaluationRegistry;
import com.compactmachinespor.core.Core;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * 应力 I/O 方块的方块实体。
 *
 * <p>放置于压缩空间内部。激活（由 CMPOR 评估确认，或开发期手动）后接入内部 Create 应力网络，
 * 作为「类创造马达」的应力源向网络补足足够大的应力+转速。评估期每秒采样网络的应力消耗与转速，
 * 上报给 {@link StressEvaluationRegistry}，最终在评估结束时聚合为工厂应力档案：
 * <ul>
 *     <li>网络中**无外部应力源**（只有本方块在供）→ 采到的网络消耗即「工厂所需应力」→ 工厂 CONSUME。</li>
 *     <li>网络中**已有外部应力源** → 记录该应力作为「工厂可对外提供的应力」→ 工厂 PROVIDE。</li>
 * </ul>
 */
public class StressIOBlockEntity extends GeneratingKineticBlockEntity {

    /** 输入模式下提供的转速（类创造马达，提供足够大的最低转速）。 */
    public static final int INPUT_SPEED = 32;

    /** 评估绑定的房间码；null 表示未参与评估。 */
    private String roomCode;

    /** 评估期采样节流。 */
    private int sampleCooldown = 0;

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

    /** 由 {@code CoreProcessBlockMixin} 在评估扫描时调用，绑定房间并刷新发电。 */
    public void bindEvaluation(String roomCode) {
        this.roomCode = roomCode;
        setChanged();
        updateGeneratedRotation();
        CreateCMPOR.LOGGER.debug("[CreateCMPOR] stress_io @{} 绑定评估 room={}", worldPosition, roomCode);
    }

    /**
     * 生成速度：激活时沿 FACING 提供足够大的转速（类创造马达），驱动内部网络以便采样其应力需求。
     */
    @Override
    public float getGeneratedSpeed() {
        if (!isActive())
            return 0;
        return convertToDirection(INPUT_SPEED, getBlockState().getValue(StressIOBlock.FACING));
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide())
            return;
        if (!isActive() || roomCode == null)
            return;
        // 仅在评估进行中采样（房间仍在 Core.MACHINES 中）
        if (Core.getMachine(roomCode) == null)
            return;

        if (--sampleCooldown > 0)
            return;
        sampleCooldown = 20; // 每秒采样一次

        KineticNetwork net = hasNetwork() ? getOrCreateNetwork() : null;
        if (net == null)
            return;

        float netCapacity = net.calculateCapacity();   // 网络总容量（含本方块）
        float netStress = net.calculateStress();         // 网络总应力消耗
        float myCapacity = calculateAddedStressCapacity(); // 本方块单独贡献的容量

        // 网络中是否还有别的应力源（除本方块外仍有容量）
        boolean hadExternal = (netCapacity - myCapacity) > 1.0f;
        float speed = Math.abs(getTheoreticalSpeed());

        StressEvaluationRegistry.record(roomCode,
                new StressEvaluationRegistry.Sample(netStress, speed, hadExternal));
    }

    /** @return 是否处于激活状态（由 {@link StressIOBlock#ACTIVE} blockstate 决定）。 */
    public boolean isActive() {
        return getBlockState().getValue(StressIOBlock.ACTIVE);
    }

    /** 激活状态变化时调用，刷新发电状态。 */
    public void onActiveChanged() {
        updateGeneratedRotation();
    }

    @Override
    protected void write(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        if (roomCode != null)
            tag.putString("RoomCode", roomCode);
    }

    @Override
    protected void read(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        this.roomCode = tag.contains("RoomCode") ? tag.getString("RoomCode") : null;
    }
}
