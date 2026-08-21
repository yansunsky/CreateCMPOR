package com.yansunsky.createcmpor.block;

import com.yansunsky.createcmpor.CreateCMPOR;
import com.yansunsky.createcmpor.init.ModBlockEntities;
import com.yansunsky.createcmpor.stress.StressEvaluationRegistry;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * 应力输入方块的方块实体（放置于压缩空间内部）。
 *
 * <p>评估期激活后作为<b>应力源</b>（类创造马达），提供虚拟应力容量（16384 SU）和转速（32 RPM），
 * 驱动内部机器运转以便测得真实应力消耗。每秒采样网络的三维度数据并上报：
 * <ul>
 *     <li>{@code capacity}：网络总应力上限（含本方块提供的虚拟容量）</li>
 *     <li>{@code stress}：网络总应力消耗</li>
 *     <li>{@code virtualCapacity}：本方块单独贡献的虚拟容量（用于评估后扣除）</li>
 *     <li>{@code speed}：当前转速</li>
 * </ul>
 * 采样类型标记为 {@link StressEvaluationRegistry.SampleType#INPUT}，
 * 评估结束后聚合计算工厂的 input 应力需求。
 */
public class StressInputBlockEntity extends GeneratingKineticBlockEntity {

    public static final int INPUT_SPEED = 32;

    private String roomCode;
    private int sampleCooldown = 0;

    public StressInputBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.STRESS_INPUT.get(), pos, state);
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

    public void bindEvaluation(String roomCode) {
        this.roomCode = roomCode;
        setChanged();
        updateGeneratedRotation();
//        CreateCMPOR.LOGGER.info("[CreateCMPOR] stress_input @{} 绑定评估 room={} active={} generatedSpeed={} hasNetwork={}",
//                worldPosition, roomCode, isActive(), getGeneratedSpeed(), hasNetwork());
    }

    @Override
    public float getGeneratedSpeed() {
        if (!isActive())
            return 0;
        return convertToDirection(INPUT_SPEED, getBlockState().getValue(StressInputBlock.FACING));
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide())
            return;
        if (!isActive() || roomCode == null)
            return;
        // Phase 1 已剥离旧外部评估主线，不再查询旧评估注册表。
        // 后续平行房间评估会由 EvaluationManager 控制 roomCode 生命周期；当前只以 roomCode 存在作为采样开关。
        if (--sampleCooldown > 0)
            return;
        sampleCooldown = 20;

        KineticNetwork net = getOrCreateNetwork();
        if (net == null) {
//            CreateCMPOR.LOGGER.info("[CreateCMPOR] stress_input @{} room={} 暂无应力网络，尝试 updateGeneratedRotation 重建",
//                    worldPosition, roomCode);
            updateGeneratedRotation();
            net = getOrCreateNetwork();
            if (net == null) {
                CreateCMPOR.LOGGER.warn("[CreateCMPOR] stress_input @{} room={} 仍无网络（轴未连接？跳过本次采样）",
                        worldPosition, roomCode);
                return;
            }
        }

        float netCapacity = net.calculateCapacity();
        float netStress = net.calculateStress();
        float speed = Math.abs(getTheoreticalSpeed());

        // calculateAddedStressCapacity() 返回的是原始 stress value（如 16384），
        // 但 KineticNetwork.calculateCapacity() 内部会乘以转速（stressValue × |speed|）。
        // 因此 virtualCapacity 也必须乘以转速，才能正确从 netCapacity 中扣除。
        float rawStressValue = calculateAddedStressCapacity();
        float myVirtualCapacity = rawStressValue * speed;

//        CreateCMPOR.LOGGER.info("[CreateCMPOR] stress_input @{} room={} netId={} 采样: netCap={} netStress={} rawStressValue={} virtualCap={} (×{}rpm) speed={}",
//                worldPosition, roomCode, this.network, netCapacity, netStress, rawStressValue, myVirtualCapacity, speed, speed);

        StressEvaluationRegistry.record(roomCode, worldPosition.asLong(),
                new StressEvaluationRegistry.Sample(netCapacity, netStress, myVirtualCapacity, speed,
                        this.network, StressEvaluationRegistry.SampleType.INPUT));
    }

    public boolean isActive() {
        return getBlockState().getValue(StressInputBlock.ACTIVE);
    }

    public void onActiveChanged() {
        updateGeneratedRotation();
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        if (roomCode != null)
            tag.putString("RoomCode", roomCode);
    }

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        this.roomCode = tag.contains("RoomCode") ? tag.getString("RoomCode") : null;
    }
}
