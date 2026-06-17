package com.createcmpor.block;

import com.createcmpor.CreateCMPOR;
import com.createcmpor.init.ModBlockEntities;
import com.createcmpor.stress.StressEvaluationRegistry;
import com.compactmachinespor.core.Core;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * 应力 I/O 方块的方块实体。
 *
 * <p>放置于压缩空间内部，激活（由 CMPOR 评估确认，或开发期手动）后接入内部 Create 应力网络。
 * 它**始终作为应力源**（类创造马达，转速 {@link #INPUT_SPEED}）提供足够大的应力容量，
 * 以保证内部机器在评估期能运转、从而测得真实应力消耗。
 *
 * <p>评估期每秒采样，按「剩余应力余量」判定方向（参考 Create stressometer 的读法）：
 * <ul>
 *     <li>{@code remainingWithoutMe = (网络容量 - 本方块容量) - 网络消耗 > 0}：网络自身已够用 →
 *         <b>输出</b>，记录 {@code +remainingWithoutMe}（工厂可对外提供的应力余量）。</li>
 *     <li>{@code remainingWithoutMe <= 0}：网络缺应力 → <b>输入</b>，记录 {@code -网络消耗}
 *         （工厂运行所需应力；本方块提供的源使机器照常运转，网络消耗即真实需求）。</li>
 * </ul>
 * 采样上报 {@link StressEvaluationRegistry}，评估结束聚合为工厂的有符号应力档案。
 */
public class StressIOBlockEntity extends GeneratingKineticBlockEntity {

    /** 评估期作为应力源提供的转速（类创造马达，足够大的最低转速）。 */
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
     * 生成速度：激活时沿 FACING 提供足够大的转速（类创造马达），驱动内部网络以便采样真实应力消耗。
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

        float netCapacity = net.calculateCapacity();        // 网络总容量（含本方块的大容量）
        float netStress = net.calculateStress();             // 网络总应力消耗
        float myCapacity = calculateAddedStressCapacity();   // 本方块单独贡献的容量

        // 排除本方块贡献后的剩余余量：>0 网络自给（输出），<=0 缺应力（输入）
        float remainingWithoutMe = (netCapacity - myCapacity) - netStress;
        float signedStress = (remainingWithoutMe > 0f)
                ? remainingWithoutMe       // 输出：记录可提供余量（正）
                : -netStress;              // 输入：记录所需应力（负）
        float speed = Math.abs(getTheoreticalSpeed());

        StressEvaluationRegistry.record(roomCode, worldPosition.asLong(),
                new StressEvaluationRegistry.Sample(signedStress, speed));
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
