package com.createcmpor.block;

import com.createcmpor.CreateCMPOR;
import com.createcmpor.init.ModBlockEntities;
import com.createcmpor.stress.StressEvaluationRegistry;
import com.compactmachinespor.core.Core;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * 应力输出方块的方块实体（放置于压缩空间内部）。
 *
 * <p>与应力输入方块不同，本方块是<b>被动观察者</b>：
 * <ul>
 *     <li>继承 {@link KineticBlockEntity}（非 Generating），不提供任何转速或应力容量。</li>
 *     <li>接入现有应力网络后，通过 {@code getOrCreateNetwork()} 读取网络状态。</li>
 *     <li>不覆盖 {@code getGeneratedSpeed()} → 返回 0，不干扰网络。</li>
 *     <li>不覆盖 {@code calculateAddedStressCapacity()} → 返回 0，不贡献容量。</li>
 *     <li>不覆盖 {@code calculateStressApplied()} → 返回 0，不施加消耗。</li>
 * </ul>
 *
 * <p>评估期每秒采样网络的 capacity 和 stress（virtualCapacity=0），
 * 采样类型标记为 {@link StressEvaluationRegistry.SampleType#OUTPUT}。
 * 评估结束后，OUTPUT 类型的采样聚合计算工厂的 output 可提供应力。
 */
public class StressOutputBlockEntity extends KineticBlockEntity {

    private String roomCode;
    private int sampleCooldown = 0;

    public StressOutputBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.STRESS_OUTPUT.get(), pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);
    }

    public void bindEvaluation(String roomCode) {
        this.roomCode = roomCode;
        setChanged();
//        CreateCMPOR.LOGGER.info("[CreateCMPOR] stress_output @{} 绑定评估 room={} active={} hasNetwork={}",
//                worldPosition, roomCode, isActive(), hasNetwork());
    }

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide())
            return;
        if (!isActive() || roomCode == null)
            return;
        var machine = Core.getMachine(roomCode);
        if (machine == null) {
//            CreateCMPOR.LOGGER.info("[CreateCMPOR] stress_output @{} room={} Core.MACHINES 已移除，停止采样",
//                    worldPosition, roomCode);
            return;
        }

        if (--sampleCooldown > 0)
            return;
        sampleCooldown = 20;

        KineticNetwork net = getOrCreateNetwork();
        if (net == null) {
//            CreateCMPOR.LOGGER.info("[CreateCMPOR] stress_output @{} room={} 暂无应力网络，跳过本次采样",
//                    worldPosition, roomCode);
            return;
        }

        float netCapacity = net.calculateCapacity();
        float netStress = net.calculateStress();
        float speed = Math.abs(getTheoreticalSpeed());

//        CreateCMPOR.LOGGER.info("[CreateCMPOR] stress_output @{} room={} netId={} 采样: netCap={} netStress={} virtualCap=0 speed={}",
//                worldPosition, roomCode, this.network, netCapacity, netStress, speed);

        StressEvaluationRegistry.record(roomCode, worldPosition.asLong(),
                new StressEvaluationRegistry.Sample(netCapacity, netStress, 0f, speed,
                        this.network, StressEvaluationRegistry.SampleType.OUTPUT));
    }

    public boolean isActive() {
        return getBlockState().getValue(StressOutputBlock.ACTIVE);
    }

    public void onActiveChanged() {
        // 被动观察者无需刷新发电，但触发一次方块更新让网络重新验证
        setChanged();
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
