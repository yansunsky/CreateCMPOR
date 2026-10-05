package com.yansunsky.createcmpor.block;

import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.motor.KineticScrollValueBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.utility.CreateLang;
import com.yansunsky.createcmpor.CreateCMPOR;
import com.yansunsky.createcmpor.init.ModBlockEntities;
import com.yansunsky.createcmpor.stress.StressEvaluationRegistry;
import net.createmod.catnip.math.AngleHelper;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 应力输入方块的方块实体（放置于压缩空间内部）。
 *
 * <p>评估期激活后作为<b>应力源</b>（类创造马达）：提供虚拟应力容量（16384 SU）和可调转速
 * （默认 16 RPM，右键拖拽注记框调整 -256~256 支持正反转）。驱动内部机器运转以便测得真实
 * 应力消耗。每秒采样网络的三维度数据并上报：
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

    public static final int DEFAULT_SPEED = 16;
    public static final int MAX_SPEED = 256;

    public KineticScrollValueBehaviour generatedSpeed;

    private String roomCode;
    private int sampleCooldown = 0;

    public StressInputBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.STRESS_INPUT.get(), pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);
        int max = MAX_SPEED;
        generatedSpeed = new KineticScrollValueBehaviour(
                CreateLang.translateDirect("kinetics.creative_motor.rotation_speed"),
                this, new MotorValueBox());
        generatedSpeed.between(-max, max);
        generatedSpeed.value = DEFAULT_SPEED;
        generatedSpeed.withCallback(i -> this.updateGeneratedRotation());
        behaviours.add(generatedSpeed);
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

    /**
     * 对外发电转速。
     *
     * <p><b>安全门控（关键）</b>：必须同时满足「方块状态 ACTIVE」与「本 BE 处于一次合法的评估会话中」
     * 才对外发电。判据与 {@link #tick()} 的采样门控、以及
     * {@code BaseIOBlockEntity#isActive()} 的既有范式保持一致（{@code ACTIVE && roomCode != null}）。</p>
     *
     * <p><b>为什么必须有 roomCode 这一半</b>：{@code ACTIVE} 是<b>方块状态</b>
     * （{@code BlockStateProperties.POWERED}），随调色板被蓝图<b>逐字复制</b>，
     * 且第三方蓝图模组（Sable Photomancy）全项目<b>没有任何方块状态过滤 API</b>
     * ⇒ 只要在评估进行中（或开启 {@code devManualActivation} 手动激活后）框选导出，
     * 打印出来的方块就是 {@code ACTIVE=true} 的<b>免费应力源</b>
     * （{@code BlockStressValues.CAPACITIES} 已注册 16384 SU，转速可调至 256 RPM）。</p>
     *
     * <p>{@code roomCode} 是普通 BE NBT，已被 {@code SableBlueprintGuard} 的导出白名单剔除
     * ⇒ 复制体永远拿不到合法 roomCode ⇒ <b>无论方块状态被复制成什么，都不发电</b>。
     * 这条门控对<b>所有</b>复制途径成立，且没有"落地瞬间的激活窗口"时序问题。</p>
     *
     * <p><b>不误伤手动激活</b>：手动激活（{@code devManualActivation}）只切换 {@code ACTIVE} 状态、
     * 从不写 roomCode，而本方块在评估会话外本来就<b>不发电</b>（{@code tick()} 同样要求 roomCode）。
     * 手动激活的用途是调试方块状态与外观，不依赖对外发电，故不受影响。</p>
     */
    @Override
    public float getGeneratedSpeed() {
        if (!isActive() || roomCode == null || roomCode.isBlank())
            return 0;
        return convertToDirection(generatedSpeed.getValue(), getBlockState().getValue(StressInputBlock.FACING));
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

    // ===== 护目镜 tooltip =====

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        super.addToGoggleTooltip(tooltip, isPlayerSneaking);
        // 提示：评估时自动激活提供应力（右键注记框可调整转速）
        net.createmod.catnip.lang.Lang.builder("createcmpor")
                .translate("tooltip.stress_input.auto_activate")
                .forGoggles(tooltip, 1);
        return true;
    }

    /** 数值注记框位置：参照创造马达，位于朝向反面的半侧。 */
    static class MotorValueBox extends ValueBoxTransform.Sided {
        @Override
        protected Vec3 getSouthLocation() {
            return VecHelper.voxelSpace(8, 8, 12.5);
        }

        @Override
        public Vec3 getLocalOffset(LevelAccessor level, BlockPos pos, BlockState state) {
            Direction facing = state.getValue(StressInputBlock.FACING);
            return super.getLocalOffset(level, pos, state).add(Vec3.atLowerCornerOf(facing.getNormal())
                    .scale(-1 / 16f));
        }

        @Override
        public void rotate(LevelAccessor level, BlockPos pos, BlockState state, com.mojang.blaze3d.vertex.PoseStack ms) {
            super.rotate(level, pos, state, ms);
            Direction facing = state.getValue(StressInputBlock.FACING);
            if (facing.getAxis() == Axis.Y)
                return;
            if (getSide() != Direction.UP)
                return;
            dev.engine_room.flywheel.lib.transform.TransformStack.of(ms)
                    .rotateZDegrees(-AngleHelper.horizontalAngle(facing) + 180);
        }

        @Override
        protected boolean isSideActive(BlockState state, Direction direction) {
            Direction facing = state.getValue(StressInputBlock.FACING);
            if (facing.getAxis() != Axis.Y && direction == Direction.DOWN)
                return false;
            return direction.getAxis() != facing.getAxis();
        }
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
