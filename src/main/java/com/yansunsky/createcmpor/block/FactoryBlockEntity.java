package com.yansunsky.createcmpor.block;

import com.yansunsky.createcmpor.CreateCMPOR;
import com.yansunsky.createcmpor.Config;
import com.yansunsky.createcmpor.evaluation.EvaluationTrace;
import com.yansunsky.createcmpor.init.ModBlockEntities;
import com.yansunsky.createcmpor.stress.FactoryStressAccess;
import com.yansunsky.createcmpor.stress.StressProfile;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Phase 7 平行工厂方块实体：真实库存容器 + RATE/REPLAY 兑换 + 还原镜像 + 护目镜 tooltip。
 *
 * <p>继承 {@link GeneratingKineticBlockEntity}：输出型工厂作为 Create 应力源，需要
 * {@code updateGeneratedRotation()}（applyNewSpeed）来建立自身转速——RotationPropagator
 * 传播速度依赖源的 speed 字段，而该字段只有 Generating 的 applyNewSpeed 会设置。
 */
public class FactoryBlockEntity extends GeneratingKineticBlockEntity
        implements IHaveGoggleInformation {

    private static final int BUFFER_SECONDS = 20;
    /** 产物暂存仓容量：物品 4 组。 */
    private static final long ITEM_OUTPUT_BUFFER = 256;
    /** 产物暂存仓容量：流体 4 桶。 */
    private static final long FLUID_OUTPUT_BUFFER = 4000;

    private String roomCode;

    /** 多工厂组：本工厂的分支索引（0-based）；单工厂 = 0。 */
    private int branchIndex = 0;
    /** 多工厂组：同 roomCode 工厂总数（组还原数量校验用）；单工厂 = 1。 */
    private int factoryCount = 1;

    private static final class Container {
        long capacity;
        long amount;
        double fraction;

        Container(long capacity) {
            this.capacity = capacity;
        }
    }

    private boolean replayMode;
    private boolean installed;

    private final Map<ResourceLocation, Container> inputItems = new LinkedHashMap<>();
    private final Map<ResourceLocation, Container> outputItems = new LinkedHashMap<>();
    private final Map<ResourceLocation, Container> inputFluids = new LinkedHashMap<>();
    private final Map<ResourceLocation, Container> outputFluids = new LinkedHashMap<>();
    private long inputEnergyCapacity;
    private long inputEnergyAmount;
    private long outputEnergyCapacity;
    private long outputEnergyAmount;
    private double inputEnergyFraction;
    private double outputEnergyFraction;

    // ===== 燃烧热值（评估预存折算；>0 = "燃烧模式"玩家投任意燃料按热值动态消耗） =====
    /** 普通热值需求（tick/秒；物品流无燃料时对外表现） */
    private double normalBurnDemandPerSecond;
    /** 超热热值需求（tick/秒） */
    private double superBurnDemandPerSecond;
    /** 燃烧累计 fraction（需求/燃料热值 → 每满 1 从输入缓存扣 1 个燃料） */
    private double normalBurnFraction;
    private double superBurnFraction;
    /** 独立燃料仓（与输入/输出缓存解耦；burn 模式时 handler 末位追加 1 个燃料槽）。 */
    private final Map<ResourceLocation, Container> burnerFuelItems = new LinkedHashMap<>();

    // RATE 连续流速率（每 tick），持久化
    private final Map<ResourceLocation, Double> inputItemTickRates = new LinkedHashMap<>();
    private final Map<ResourceLocation, Double> outputItemTickRates = new LinkedHashMap<>();
    private final Map<ResourceLocation, Double> inputFluidTickRates = new LinkedHashMap<>();
    private final Map<ResourceLocation, Double> outputFluidTickRates = new LinkedHashMap<>();
    private double inputEnergyTickRate;
    private double outputEnergyTickRate;

    private final Map<ResourceLocation, int[]> inputItemPatterns = new LinkedHashMap<>();
    private final Map<ResourceLocation, int[]> outputItemPatterns = new LinkedHashMap<>();
    private final Map<ResourceLocation, int[]> inputFluidPatterns = new LinkedHashMap<>();
    private final Map<ResourceLocation, int[]> outputFluidPatterns = new LinkedHashMap<>();
    private int[] inputEnergyPattern = new int[0];
    private int[] outputEnergyPattern = new int[0];
    private int patternLength;
    private int replayTick;
    private int replayCurrentSecond;

    private boolean lastSuccess = false;
    private int tickCount;

    private CompoundTag restoreMachineState;
    private CompoundTag restoreMachineNbt;

    private final IItemHandler itemHandler = new ItemHandler();
    private final IFluidHandler fluidHandler = new FluidHandler();
    private final IEnergyStorage energyHandler = new EnergyHandler();

    public FactoryBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.FACTORY.get(), pos, state);
    }

    /**
     * 加载时把本工厂注册进组索引（幂等）：工厂被取下重放（任意位置）后，
     * 索引位置跟随实际存在位置 —— 组还原数量校验不再依赖固化时坐标（无顺序/位置限制）。
     * 每次 chunk 加载都会触发，索引 contains 判重保证幂等。
     */
    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide && installed && roomCode != null && !roomCode.isBlank()) {
            com.yansunsky.createcmpor.evaluation.FactoryIndexSavedData.get(
                            level.getServer())
                    .registerFactoryPosition(roomCode,
                            net.minecraft.core.GlobalPos.of(level.dimension(), worldPosition));
        }
    }

    public String getRoomCode() {
        return roomCode;
    }

    public void setRoomCode(String roomCode) {
        this.roomCode = roomCode;
        setChanged();
    }

    public int getBranchIndex() {
        return branchIndex;
    }

    public int getFactoryCount() {
        return factoryCount;
    }

    /** 固化时写入多工厂组信息（单工厂 branchIndex=0, factoryCount=1）。 */
    public void setGroupInfo(int branchIndex, int factoryCount) {
        this.branchIndex = branchIndex;
        this.factoryCount = Math.max(1, factoryCount);
        setChanged();
    }

    // ===== 评估结果安装（固化时调用一次） =====

    /** RATE 模式：输入容量 = 每秒速率 × 20 秒缓冲；输出 = 大容量暂存仓（连续产出积累）。 */
    public void installRates(Map<EvaluationTrace.FlowKey, Double> inputRates,
                             Map<EvaluationTrace.FlowKey, Double> outputRates,
                             double inputEnergyRate, double outputEnergyRate,
                             double normalBurnDemandPerSecond, double superBurnDemandPerSecond) {
        replayMode = false;
        this.normalBurnDemandPerSecond = normalBurnDemandPerSecond;
        this.superBurnDemandPerSecond = superBurnDemandPerSecond;
        this.normalBurnFraction = 0;
        this.superBurnFraction = 0;
        burnerFuelItems.clear();
        inputItems.clear();
        outputItems.clear();
        inputFluids.clear();
        outputFluids.clear();
        for (Map.Entry<EvaluationTrace.FlowKey, Double> entry : inputRates.entrySet()) {
            long capacity = capacityFromTickRate(entry.getValue());
            if (capacity <= 0) {
                continue;
            }
            if ("item".equals(entry.getKey().kind())) {
                inputItems.put(entry.getKey().id(), new Container(capacity));
            } else {
                inputFluids.put(entry.getKey().id(), new Container(capacity));
            }
        }
        for (Map.Entry<EvaluationTrace.FlowKey, Double> entry : outputRates.entrySet()) {
            if (entry.getValue() <= 0) {
                continue;
            }
            long buffer = "item".equals(entry.getKey().kind()) ? ITEM_OUTPUT_BUFFER : FLUID_OUTPUT_BUFFER;
            if ("item".equals(entry.getKey().kind())) {
                outputItems.put(entry.getKey().id(), new Container(buffer));
            } else {
                outputFluids.put(entry.getKey().id(), new Container(buffer));
            }
        }
        inputEnergyCapacity = capacityFromTickRate(inputEnergyRate);
        outputEnergyCapacity = outputEnergyRate > 0
                ? Math.max(4000, capacityFromTickRate(outputEnergyRate)) : 0;
        inputEnergyAmount = 0;
        outputEnergyAmount = 0;
        inputItemTickRates.clear();
        outputItemTickRates.clear();
        inputFluidTickRates.clear();
        outputFluidTickRates.clear();
        inputRates.forEach((key, rate) -> {
            if ("item".equals(key.kind())) {
                inputItemTickRates.put(key.id(), rate);
            } else {
                inputFluidTickRates.put(key.id(), rate);
            }
        });
        outputRates.forEach((key, rate) -> {
            if ("item".equals(key.kind())) {
                outputItemTickRates.put(key.id(), rate);
            } else {
                outputFluidTickRates.put(key.id(), rate);
            }
        });
        inputEnergyTickRate = inputEnergyRate;
        outputEnergyTickRate = outputEnergyRate;
        installed = true;
        setChanged();
    }

    /** REPLAY 模式：每秒 pattern → 容器容量（峰值秒 × 20 缓冲）。 */
    public void installPatterns(Map<EvaluationTrace.FlowKey, int[]> replayIn,
                                Map<EvaluationTrace.FlowKey, int[]> replayOut,
                                int[] energyIn, int[] energyOut,
                                double normalBurnDemandPerSecond, double superBurnDemandPerSecond) {
        replayMode = true;
        this.normalBurnDemandPerSecond = normalBurnDemandPerSecond;
        this.superBurnDemandPerSecond = superBurnDemandPerSecond;
        this.normalBurnFraction = 0;
        this.superBurnFraction = 0;
        burnerFuelItems.clear();
        inputItemPatterns.clear();
        outputItemPatterns.clear();
        inputFluidPatterns.clear();
        outputFluidPatterns.clear();
        for (Map.Entry<EvaluationTrace.FlowKey, int[]> entry : replayIn.entrySet()) {
            if ("item".equals(entry.getKey().kind())) {
                inputItemPatterns.put(entry.getKey().id(), entry.getValue());
            } else {
                inputFluidPatterns.put(entry.getKey().id(), entry.getValue());
            }
        }
        for (Map.Entry<EvaluationTrace.FlowKey, int[]> entry : replayOut.entrySet()) {
            if ("item".equals(entry.getKey().kind())) {
                outputItemPatterns.put(entry.getKey().id(), entry.getValue());
            } else {
                outputFluidPatterns.put(entry.getKey().id(), entry.getValue());
            }
        }
        inputEnergyPattern = energyIn == null ? new int[0] : energyIn;
        outputEnergyPattern = energyOut == null ? new int[0] : energyOut;

        patternLength = Math.max(inputItemPatterns.values().stream().mapToInt(v -> v.length).max().orElse(0),
                Math.max(outputItemPatterns.values().stream().mapToInt(v -> v.length).max().orElse(0),
                        Math.max(inputEnergyPattern.length, outputEnergyPattern.length)));
        if (patternLength <= 0) {
            patternLength = 1;
        }
        inputItems.clear();
        outputItems.clear();
        inputFluids.clear();
        outputFluids.clear();
        inputItemPatterns.forEach((id, pattern) -> inputItems.put(id,
                new Container(maxInt(pattern) * BUFFER_SECONDS)));
        outputItemPatterns.forEach((id, pattern) -> outputItems.put(id,
                new Container(Math.max(ITEM_OUTPUT_BUFFER, maxInt(pattern) * BUFFER_SECONDS))));
        inputFluidPatterns.forEach((id, pattern) -> inputFluids.put(id,
                new Container(maxInt(pattern) * BUFFER_SECONDS)));
        outputFluidPatterns.forEach((id, pattern) -> outputFluids.put(id,
                new Container(Math.max(FLUID_OUTPUT_BUFFER, maxInt(pattern) * BUFFER_SECONDS))));
        inputEnergyCapacity = maxInt(inputEnergyPattern) * BUFFER_SECONDS;
        outputEnergyCapacity = maxInt(outputEnergyPattern) * BUFFER_SECONDS;
        inputEnergyAmount = 0;
        outputEnergyAmount = 0;
        replayTick = 0;
        replayCurrentSecond = 0;
        installed = true;
        setChanged();
    }

    private static long capacityFromTickRate(double ratePerTick) {
        if (ratePerTick <= 0) {
            return 0;
        }
        return Math.max(1, (long) Math.floor(ratePerTick * 20.0 * BUFFER_SECONDS));
    }

    private static int maxInt(int[] pattern) {
        int max = 0;
        for (int value : pattern) {
            max = Math.max(max, value);
        }
        return max;
    }

    /** 固化时写入还原镜像（原机器 BlockState + 完整 BE NBT）。 */
    public void installRestoreData(BlockState originalState, CompoundTag originalNbt,
                                   StressProfile stressProfile) {
        restoreMachineState = NbtUtils.writeBlockState(originalState);
        restoreMachineNbt = originalNbt.copy();
        FactoryStressAccess.set(this, stressProfile);
        // 输出型：立即应用生成转速（建立/恢复源网络）
        if (level != null && !level.isClientSide && stressProfile.isProvide()) {
            updateGeneratedRotation();
        }
        // 档案安装后应力数据变化：若工厂已接入网络，立即上报（容量 + 消耗）
        if (level != null && !level.isClientSide && hasNetwork()) {
            com.simibubi.create.content.kinetics.KineticNetwork network = getOrCreateNetwork();
            network.updateCapacityFor(this, calculateAddedStressCapacity());
            network.updateStressFor(this, calculateStressApplied());
        }
        // 同步客户端（attachment sync：护目镜 Impact/Capacity 行依赖客户端档案）
        if (level != null && !level.isClientSide) {
            sendData();
        }
        setChanged();
    }

    public boolean hasRestoreData() {
        return restoreMachineState != null && restoreMachineNbt != null;
    }

    /** 启动棒还原：工厂变回原 CompactMachines 机器。 */
    public boolean revertToMachine(ServerLevel level) {
        if (!hasRestoreData()) {
            return false;
        }
        // 清除 roomCode → 工厂 索引（防止自动还原后玩家再进空间误判存在工厂）
        if (roomCode != null && !roomCode.isBlank() && level.getServer() != null) {
            com.yansunsky.createcmpor.evaluation.FactoryIndexSavedData.get(level.getServer())
                    .removeFactory(roomCode);
        }
        BlockPos pos = getBlockPos();
        BlockState originalState = NbtUtils.readBlockState(
                level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK),
                restoreMachineState);
        level.removeBlockEntity(pos);
        level.setBlockAndUpdate(pos, originalState);
        BlockEntity restored = BlockEntity.loadStatic(pos, originalState, restoreMachineNbt,
                level.registryAccess());
        if (restored == null) {
            CreateCMPOR.LOGGER.error("工厂还原失败：无法重建原机器方块实体 {}", pos);
            return false;
        }
        level.setBlockEntity(restored);
        restored.setChanged();
        level.sendBlockUpdated(pos, originalState, originalState, Block.UPDATE_ALL);
        return true;
    }

    // ===== 兑换 tick =====

    @Override
    public void tick() {
        super.tick();
        if (level == null || level.isClientSide || !installed) {
            return;
        }
        // 自愈：输入型工厂失去源/网络后，Create 不会自动重连被动方块
        // （无 onPlace/邻居变化事件重置 updateSpeed）——标记 updateSpeed=true，
        // 下一次 super.tick 的 attachKinetics 会重新发现邻居（如马达）并恢复传播。
        if (stressInputRequired() && !hasSource() && !hasNetwork()) {
            updateSpeed = true;
        }
        // 输出型工厂：作为应力源主动维持生成转速。
        // 源的速度只能由 updateGeneratedRotation（applyNewSpeed）建立；任何状态变化
        // （放置/固化/开口/重启）后这里都会自愈：理论速度与生成速度不一致时重新应用。
        if (FactoryStressAccess.get(this).isProvide() && getTheoreticalSpeed() != getGeneratedSpeed()) {
            updateGeneratedRotation();
        }
        // 输入型工厂：必须接入 Create 应力网络并获得实际转速才工作
        // （Create 应力网络语义：无转速 = 无动能；断开应力源 / 网络超载时 getSpeed 归 0 → 暂停兑换）
        if (stressInputRequired() && Math.abs(getSpeed()) == 0) {
            lastSuccess = false;
            return;
        }
        // 应力暂停：输入型工厂（需要外部应力）在网络过载时完全暂停兑换（双保险，超载通常已使 getSpeed 归 0）。
        if (stressInputRequired() && isOverStressed()) {
            lastSuccess = false;
            return;
        }
        tickCount++;
        if (tickCount >= 20) {
            tickCount = 0;
        }
        // 燃烧热值：每 tick 按需求从输入缓存动态消耗燃料（熔岩桶返还空桶到输出）
        tickBurner();
        if (replayMode) {
            if (tickCount == 0) {
                tickReplay();
            }
        } else {
            tickRateContinuous();
        }
        // 输出型工厂：lastSuccess（运转状态）变化时触发 updateGeneratedRotation ——
        // 走 Create 原生通知链路（applyNewSpeed → notifyStressCapacityChange → 客户端同步），
        // 不要定时 updateCapacityFor 硬塞（客户端渲染不同步）。首 tick 从 false 起步也在此补齐。
        if (FactoryStressAccess.get(this).isProvide()
                && lastSuccess != lastSuccessReported) {
            lastSuccessReported = lastSuccess;
            updateGeneratedRotation();
            // 客户端护目镜"应力/容量"行显示的是 lastCapacityProvided（经 write/read 的 Network.AddedCapacity 同步）。
            // 强制 sendData 立即推给客户端（否则只在网络 sync/入网时更新——"创建时设定"旧值）。
            if (level != null && !level.isClientSide) {
                sendData();
            }
        }
    }

    /** 上次上报运转状态的快照（输出型工厂触发网络重报用）。 */
    private boolean lastSuccessReported = false;

    /** 工厂是否为输入型（需要外部应力驱动）。 */
    private boolean stressInputRequired() {
        return FactoryStressAccess.get(this).isConsume();
    }

    /** RATE 连续流：每 tick 按速率累计，输入不足或输出满仓即卡住（背压，产物不丢弃）。 */
    private void tickRateContinuous() {
        if (!inputsSatisfied() || !burnerSatisfied() || !outputsHaveSpace()) {
            lastSuccess = false;
            return;
        }
        for (Map.Entry<ResourceLocation, Container> entry : inputItems.entrySet()) {
            Double rate = inputItemTickRates.get(entry.getKey());
            if (rate == null) {
                continue;
            }
            Container container = entry.getValue();
            container.fraction += rate;
            long whole = (long) container.fraction;
            if (whole > 0) {
                container.fraction -= whole;
                container.amount = Math.max(0, container.amount - whole);
            }
        }
        for (Map.Entry<ResourceLocation, Container> entry : inputFluids.entrySet()) {
            Double rate = inputFluidTickRates.get(entry.getKey());
            if (rate == null) {
                continue;
            }
            Container container = entry.getValue();
            container.fraction += rate;
            long whole = (long) container.fraction;
            if (whole > 0) {
                container.fraction -= whole;
                container.amount = Math.max(0, container.amount - whole);
            }
        }
        if (inputEnergyCapacity > 0) {
            inputEnergyFraction += inputEnergyTickRate;
            long whole = (long) inputEnergyFraction;
            if (whole > 0) {
                inputEnergyFraction -= whole;
                inputEnergyAmount = Math.max(0, inputEnergyAmount - whole);
            }
        }

        for (Map.Entry<ResourceLocation, Container> entry : outputItems.entrySet()) {
            Double rate = outputItemTickRates.get(entry.getKey());
            if (rate == null) {
                continue;
            }
            Container container = entry.getValue();
            container.fraction += rate;
            long whole = (long) container.fraction;
            if (whole > 0) {
                container.fraction -= whole;
                container.amount = Math.min(container.capacity, container.amount + whole);
            }
        }
        for (Map.Entry<ResourceLocation, Container> entry : outputFluids.entrySet()) {
            Double rate = outputFluidTickRates.get(entry.getKey());
            if (rate == null) {
                continue;
            }
            Container container = entry.getValue();
            container.fraction += rate;
            long whole = (long) container.fraction;
            if (whole > 0) {
                container.fraction -= whole;
                container.amount = Math.min(container.capacity, container.amount + whole);
            }
        }
        if (outputEnergyCapacity > 0) {
            outputEnergyFraction += outputEnergyTickRate;
            long whole = (long) outputEnergyFraction;
            if (whole > 0) {
                outputEnergyFraction -= whole;
                outputEnergyAmount = Math.min(outputEnergyCapacity, outputEnergyAmount + whole);
            }
        }
        lastSuccess = true;
    }

    /** 连续流输入检查：每类输入至少持有 1 个单位才允许本 tick 推进。 */
    private boolean inputsSatisfied() {
        for (Map.Entry<ResourceLocation, Container> entry : inputItems.entrySet()) {
            Double rate = inputItemTickRates.get(entry.getKey());
            if (rate != null && rate > 0 && entry.getValue().amount < 1) {
                return false;
            }
        }
        for (Map.Entry<ResourceLocation, Container> entry : inputFluids.entrySet()) {
            Double rate = inputFluidTickRates.get(entry.getKey());
            if (rate != null && rate > 0 && entry.getValue().amount < 1) {
                return false;
            }
        }
        return inputEnergyCapacity <= 0 || inputEnergyTickRate <= 0 || inputEnergyAmount >= 1;
    }

    /** 连续流输出空间检查：任一输出暂存仓满 → 暂停兑换（背压，不丢弃产物）。 */
    private boolean outputsHaveSpace() {
        for (Container container : outputItems.values()) {
            if (container.amount >= container.capacity) {
                return false;
            }
        }
        for (Container container : outputFluids.values()) {
            if (container.amount >= container.capacity) {
                return false;
            }
        }
        return outputEnergyCapacity <= 0 || outputEnergyAmount < outputEnergyCapacity;
    }

    private void tickReplay() {
        replayTick++;
        if (replayTick < 20) {
            return;
        }
        replayTick = 0;
        if (replayIsReady(replayCurrentSecond)) {
            replayApply(replayCurrentSecond);
            replayCurrentSecond = (replayCurrentSecond + 1) % patternLength;
            lastSuccess = true;
        } else {
            lastSuccess = false;
        }
    }

    private boolean replayIsReady(int second) {
        if (!burnerSatisfied()) {
            return false;
        }
        for (Map.Entry<ResourceLocation, int[]> entry : inputItemPatterns.entrySet()) {
            Container container = Objects.requireNonNull(inputItems.get(entry.getKey()));
            int need = entry.getValue()[second % entry.getValue().length];
            if (container.amount < need) {
                return false;
            }
        }
        for (Map.Entry<ResourceLocation, int[]> entry : inputFluidPatterns.entrySet()) {
            Container container = Objects.requireNonNull(inputFluids.get(entry.getKey()));
            int need = entry.getValue()[second % entry.getValue().length];
            if (container.amount < need) {
                return false;
            }
        }
        for (Map.Entry<ResourceLocation, int[]> entry : outputItemPatterns.entrySet()) {
            Container container = Objects.requireNonNull(outputItems.get(entry.getKey()));
            int produce = entry.getValue()[second % entry.getValue().length];
            if (container.amount + produce > container.capacity) {
                return false;
            }
        }
        for (Map.Entry<ResourceLocation, int[]> entry : outputFluidPatterns.entrySet()) {
            Container container = Objects.requireNonNull(outputFluids.get(entry.getKey()));
            int produce = entry.getValue()[second % entry.getValue().length];
            if (container.amount + produce > container.capacity) {
                return false;
            }
        }
        if (inputEnergyPattern.length > 0) {
            int consume = inputEnergyPattern[second % inputEnergyPattern.length];
            if (inputEnergyAmount < consume) {
                return false;
            }
        }
        if (outputEnergyPattern.length > 0) {
            int produce = outputEnergyPattern[second % outputEnergyPattern.length];
            if (outputEnergyAmount + produce > outputEnergyCapacity) {
                return false;
            }
        }
        return true;
    }

    private void replayApply(int second) {
        for (Map.Entry<ResourceLocation, int[]> entry : inputItemPatterns.entrySet()) {
            Container container = Objects.requireNonNull(inputItems.get(entry.getKey()));
            container.amount -= entry.getValue()[second % entry.getValue().length];
        }
        for (Map.Entry<ResourceLocation, int[]> entry : inputFluidPatterns.entrySet()) {
            Container container = Objects.requireNonNull(inputFluids.get(entry.getKey()));
            container.amount -= entry.getValue()[second % entry.getValue().length];
        }
        for (Map.Entry<ResourceLocation, int[]> entry : outputItemPatterns.entrySet()) {
            Container container = Objects.requireNonNull(outputItems.get(entry.getKey()));
            container.amount += entry.getValue()[second % entry.getValue().length];
        }
        for (Map.Entry<ResourceLocation, int[]> entry : outputFluidPatterns.entrySet()) {
            Container container = Objects.requireNonNull(outputFluids.get(entry.getKey()));
            container.amount += entry.getValue()[second % entry.getValue().length];
        }
        if (inputEnergyPattern.length > 0) {
            inputEnergyAmount -= inputEnergyPattern[second % inputEnergyPattern.length];
        }
        if (outputEnergyPattern.length > 0) {
            outputEnergyAmount += outputEnergyPattern[second % outputEnergyPattern.length];
        }
        setChanged();
    }

    // ===== 燃烧热值（评估预存折算）=====

    /** 燃烧模式：普通或超热需求 > 0。 */
    private boolean burnModeActive() {
        return normalBurnDemandPerSecond > 0 || superBurnDemandPerSecond > 0;
    }

    /** 每 tick 按需求从输入缓存动态消耗燃料（需求/燃料热值 → fraction 满 1 扣 1 个）。 */
    private void tickBurner() {
        if (!burnModeActive()) {
            return;
        }
        if (normalBurnDemandPerSecond > 0) {
            consumeNormalBurnFuel();
        }
        if (superBurnDemandPerSecond > 0) {
            consumeSuperBurnFuel();
        }
    }

    private void consumeNormalBurnFuel() {
        for (Map.Entry<ResourceLocation, Container> entry : burnerFuelItems.entrySet()) {
            ResourceLocation id = entry.getKey();
            if (BURN_BLAZE_CAKE.equals(id)) {
                continue; // 烈焰蛋糕是超热燃料，归超热档
            }
            double heat = burnTimeOf(id);
            if (heat <= 0) {
                continue;
            }
            normalBurnFraction += normalBurnDemandPerSecond / 20.0 / heat;
            long whole = (long) normalBurnFraction;
            if (whole > 0) {
                normalBurnFraction -= whole;
                entry.getValue().amount = Math.max(0, entry.getValue().amount - whole);
                // 熔岩桶：烧完返还空桶到输出缓存（同 Create 燃烧室：玩家投桶 → 拿回空桶）
                if (BURN_LAVA_BUCKET.equals(id)) {
                    returnEmptyBucket(whole);
                }
            }
            break; // 一种燃料（LinkedHashMap 先入先烧）
        }
    }

    private void consumeSuperBurnFuel() {
        Container cake = burnerFuelItems.get(BURN_BLAZE_CAKE);
        if (cake == null || cake.amount <= 0) {
            return;
        }
        superBurnFraction += superBurnDemandPerSecond / 20.0 / 3200.0; // 烈焰蛋糕 3200 tick
        long whole = (long) superBurnFraction;
        if (whole > 0) {
            superBurnFraction -= whole;
            cake.amount = Math.max(0, cake.amount - whole);
        }
    }

    /** 熔岩桶烧完：空桶以掉落物形式直接弹出到工厂位置（同玩家投桶拿回空桶的直觉；不占工厂输出缓存）。 */
    private void returnEmptyBucket(long count) {
        if (level == null || level.isClientSide() || count <= 0) {
            return;
        }
        ItemStack buckets = new ItemStack(net.minecraft.world.item.Items.BUCKET, (int) Math.min(count, 64));
        net.minecraft.world.entity.item.ItemEntity entity = new net.minecraft.world.entity.item.ItemEntity(
                level, worldPosition.getX() + 0.5, worldPosition.getY() + 0.5, worldPosition.getZ() + 0.5, buckets);
        entity.setDefaultPickUpDelay();
        level.addFreshEntity(entity);
    }

    /** 燃烧满足：需求档对应燃料在独立燃料仓中（amount ≥ 1）才允许推进。 */
    private boolean burnerSatisfied() {
        if (!burnModeActive()) {
            return true;
        }
        if (normalBurnDemandPerSecond > 0 && !hasBurnFuel(false)) {
            return false;
        }
        return superBurnDemandPerSecond <= 0 || hasBurnFuel(true);
    }

    private boolean hasBurnFuel(boolean superHeated) {
        if (superHeated) {
            Container cake = burnerFuelItems.get(BURN_BLAZE_CAKE);
            return cake != null && cake.amount >= 1;
        }
        for (Map.Entry<ResourceLocation, Container> entry : burnerFuelItems.entrySet()) {
            if (!BURN_BLAZE_CAKE.equals(entry.getKey())
                    && burnTimeOf(entry.getKey()) > 0
                    && entry.getValue().amount >= 1) {
                return true;
            }
        }
        return false;
    }

    /** 物品燃料热值（tick；超热烈焰蛋糕=3200）。 */
    double burnTimeOf(ResourceLocation id) {
        Item item = BuiltInRegistries.ITEM.get(id);
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            return 0;
        }
        if (BURN_BLAZE_CAKE.equals(id)) {
            return 3200;
        }
        int burn = new ItemStack(item).getBurnTime(null);
        return burn > 0 ? burn : 0;
    }

    /** 燃烧模式是否接受该物品为燃料（普通需求收普通可燃物；超热需求收烈焰蛋糕）。 */
    private boolean acceptBurnFuel(ItemStack stack) {
        if (!burnModeActive() || stack.isEmpty()) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (BURN_BLAZE_CAKE.equals(id)) {
            return superBurnDemandPerSecond > 0;
        }
        return normalBurnDemandPerSecond > 0 && burnTimeOf(id) > 0;
    }

    private static final ResourceLocation BURN_BLAZE_CAKE =
            ResourceLocation.fromNamespaceAndPath("create", "blaze_cake");
    private static final ResourceLocation BURN_LAVA_BUCKET =
            ResourceLocation.fromNamespaceAndPath("minecraft", "lava_bucket");

    // ===== 能力 =====

    public IItemHandler getItemHandler() {
        return itemHandler;
    }

    public IFluidHandler getFluidHandler() {
        return fluidHandler;
    }

    public IEnergyStorage getEnergyHandler() {
        return energyHandler;
    }

    private final class ItemHandler implements IItemHandler {
        private List<Item> inputKeys() {
            List<Item> keys = new ArrayList<>();
            inputItems.keySet().forEach(id -> {
                if (BuiltInRegistries.ITEM.containsKey(id)) {
                    keys.add(BuiltInRegistries.ITEM.get(id));
                }
            });
            return keys;
        }

        private List<Item> outputKeys() {
            List<Item> keys = new ArrayList<>();
            outputItems.keySet().forEach(id -> {
                if (BuiltInRegistries.ITEM.containsKey(id)) {
                    keys.add(BuiltInRegistries.ITEM.get(id));
                }
            });
            return keys;
        }

        @Override
        public int getSlots() {
            return inputKeys().size() + outputKeys().size() + (burnModeActive() ? 1 : 0);
        }

        /** 燃料槽索引（burn 模式追加在输入+输出之后；未激活返回 -1）。 */
        private int fuelSlotIndex() {
            return burnModeActive() ? inputKeys().size() + outputKeys().size() : -1;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            // 燃料槽（burn 模式追加的末位槽）：返回燃料仓首个非空堆（空则 EMPTY）
            int fuelSlot = fuelSlotIndex();
            if (burnModeActive() && slot == fuelSlot) {
                for (Map.Entry<ResourceLocation, Container> entry : burnerFuelItems.entrySet()) {
                    if (entry.getValue().amount > 0) {
                        return new ItemStack(BuiltInRegistries.ITEM.get(entry.getKey()),
                                (int) Math.min(entry.getValue().amount, 64));
                    }
                }
                return ItemStack.EMPTY;
            }
            List<Item> inputs = inputKeys();
            Container inputContainer = slot < inputs.size()
                    ? inputItems.get(BuiltInRegistries.ITEM.getKey(inputs.get(slot))) : null;
            if (slot < inputs.size()) {
                long amount = inputContainer == null ? 0 : Math.min(inputContainer.amount, 64);
                return new ItemStack(inputs.get(slot), (int) amount); // 空槽返回 EMPTY（不再伪 1，便于 insertItemStacked 类自动化）
            }
            List<Item> outputs = outputKeys();
            int outputIndex = slot - inputs.size();
            if (outputIndex >= 0 && outputIndex < outputs.size()) {
                Container container = outputItems.get(BuiltInRegistries.ITEM.getKey(outputs.get(outputIndex)));
                long amount = container == null ? 0 : Math.min(container.amount, 64);
                return new ItemStack(outputs.get(outputIndex), (int) amount); // 空槽返回 EMPTY
            }
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            // 燃料槽：接受任意合格燃料进独立燃料仓（容量 1024/类；按热值由 tickBurner 消耗；与其他缓存解耦）
            if (acceptBurnFuel(stack) && slot == fuelSlotIndex()) {
                ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
                Container container = burnerFuelItems.computeIfAbsent(id, k -> new Container(1024));
                long space = container.capacity - container.amount;
                int accepted = (int) Math.min(space, stack.getCount());
                if (!simulate) {
                    container.amount += accepted;
                    setChanged();
                }
                return accepted >= stack.getCount() ? ItemStack.EMPTY
                        : stack.copyWithCount(stack.getCount() - accepted);
            }
            List<Item> inputs = inputKeys();
            if (slot >= inputs.size() || stack.isEmpty()) {
                return stack;
            }
            Item expected = inputs.get(slot);
            if (!stack.is(expected)) {
                return stack;
            }
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(expected);
            Container container = inputItems.get(id);
            long space = container.capacity - container.amount;
            int accepted = (int) Math.min(space, stack.getCount());
            if (!simulate) {
                container.amount += accepted;
                setChanged();
            }
            return accepted >= stack.getCount() ? ItemStack.EMPTY
                    : stack.copyWithCount(stack.getCount() - accepted);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            // 燃料槽只进不出（燃料在工厂内部燃烧；熔岩桶同 Create 燃烧室语义——直接消耗不返还）
            if (burnModeActive() && slot == fuelSlotIndex()) {
                return ItemStack.EMPTY;
            }
            List<Item> inputs = inputKeys();
            int outputIndex = slot - inputs.size();
            List<Item> outputs = outputKeys();
            if (outputIndex < 0 || outputIndex >= outputs.size()) {
                return ItemStack.EMPTY;
            }
            Item expected = outputs.get(outputIndex);
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(expected);
            Container container = outputItems.get(id);
            long available = Math.min(container.amount, amount);
            if (!simulate) {
                container.amount -= available;
                setChanged();
            }
            return available <= 0 ? ItemStack.EMPTY
                    : new ItemStack(expected, (int) Math.min(available, expected.getDefaultMaxStackSize()));
        }

        @Override
        public int getSlotLimit(int slot) {
            return 64;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            // 燃料槽：接受任意合格燃料
            if (burnModeActive() && slot == fuelSlotIndex() && acceptBurnFuel(stack)) {
                return true;
            }
            List<Item> inputs = inputKeys();
            return slot < inputs.size() && stack.is(inputs.get(slot));
        }
    }

    private final class FluidHandler implements IFluidHandler {
        @Override
        public int getTanks() {
            return inputFluids.size() + outputFluids.size();
        }

        @Override
        public FluidStack getFluidInTank(int tank) {
            List<Fluid> inputs = inputFluids.keySet().stream()
                    .map(BuiltInRegistries.FLUID::get).filter(Objects::nonNull).toList();
            if (tank < inputs.size()) {
                Container container = inputFluids.get(BuiltInRegistries.FLUID.getKey(inputs.get(tank)));
                return new FluidStack(inputs.get(tank),
                        container == null ? 0 : (int) Math.min(container.amount, Integer.MAX_VALUE));
            }
            int outputIndex = tank - inputs.size();
            List<Fluid> outputs = outputFluids.keySet().stream()
                    .map(BuiltInRegistries.FLUID::get).filter(Objects::nonNull).toList();
            if (outputIndex < outputs.size()) {
                Container container = outputFluids.get(BuiltInRegistries.FLUID.getKey(outputs.get(outputIndex)));
                return new FluidStack(outputs.get(outputIndex),
                        container == null ? 0 : (int) Math.min(container.amount, Integer.MAX_VALUE));
            }
            return FluidStack.EMPTY;
        }

        @Override
        public int getTankCapacity(int tank) {
            List<Fluid> inputs = inputFluids.keySet().stream()
                    .map(BuiltInRegistries.FLUID::get).filter(Objects::nonNull).toList();
            if (tank < inputs.size()) {
                Container container = inputFluids.get(BuiltInRegistries.FLUID.getKey(inputs.get(tank)));
                return container == null ? 0 : (int) Math.min(container.capacity, Integer.MAX_VALUE);
            }
            int outputIndex = tank - inputs.size();
            List<Fluid> outputs = outputFluids.keySet().stream()
                    .map(BuiltInRegistries.FLUID::get).filter(Objects::nonNull).toList();
            if (outputIndex < outputs.size()) {
                Container container = outputFluids.get(BuiltInRegistries.FLUID.getKey(outputs.get(outputIndex)));
                return container == null ? 0 : (int) Math.min(container.capacity, Integer.MAX_VALUE);
            }
            return 0;
        }

        @Override
        public boolean isFluidValid(int tank, FluidStack stack) {
            List<Fluid> inputs = inputFluids.keySet().stream()
                    .map(BuiltInRegistries.FLUID::get).filter(Objects::nonNull).toList();
            return tank < inputs.size() && stack.is(inputs.get(tank));
        }

        @Override
        public int fill(FluidStack resource, FluidAction action) {
            if (resource.isEmpty()) {
                return 0;
            }
            for (Map.Entry<ResourceLocation, Container> entry : inputFluids.entrySet()) {
                if (!entry.getKey().equals(BuiltInRegistries.FLUID.getKey(resource.getFluid()))) {
                    continue;
                }
                long space = entry.getValue().capacity - entry.getValue().amount;
                int accepted = (int) Math.min(space, resource.getAmount());
                if (action.execute()) {
                    entry.getValue().amount += accepted;
                    setChanged();
                }
                return accepted;
            }
            return 0;
        }

        @Override
        public FluidStack drain(FluidStack resource, FluidAction action) {
            for (Map.Entry<ResourceLocation, Container> entry : outputFluids.entrySet()) {
                if (!entry.getKey().equals(BuiltInRegistries.FLUID.getKey(resource.getFluid()))) {
                    continue;
                }
                int drained = (int) Math.min(entry.getValue().amount, resource.getAmount());
                if (action.execute()) {
                    entry.getValue().amount -= drained;
                    setChanged();
                }
                return new FluidStack(resource.getFluid(), drained);
            }
            return FluidStack.EMPTY;
        }

        @Override
        public FluidStack drain(int maxDrain, FluidAction action) {
            for (Map.Entry<ResourceLocation, Container> entry : outputFluids.entrySet()) {
                Fluid fluid = BuiltInRegistries.FLUID.get(entry.getKey());
                if (fluid == null || entry.getValue().amount <= 0) {
                    continue;
                }
                int drained = (int) Math.min(entry.getValue().amount, maxDrain);
                if (action.execute()) {
                    entry.getValue().amount -= drained;
                    setChanged();
                }
                return new FluidStack(fluid, drained);
            }
            return FluidStack.EMPTY;
        }
    }

    private final class EnergyHandler implements IEnergyStorage {
        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            long space = inputEnergyCapacity - inputEnergyAmount;
            int accepted = (int) Math.min(space, maxReceive);
            if (!simulate) {
                inputEnergyAmount += accepted;
                setChanged();
            }
            return accepted;
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            long available = Math.min(outputEnergyAmount, maxExtract);
            if (!simulate) {
                outputEnergyAmount -= available;
                setChanged();
            }
            return (int) available;
        }

        @Override
        public int getEnergyStored() {
            return (int) Math.min(Integer.MAX_VALUE, inputEnergyAmount + outputEnergyAmount);
        }

        @Override
        public int getMaxEnergyStored() {
            if (inputEnergyCapacity <= 0 && outputEnergyCapacity <= 0) {
                return 0;
            }
            return (int) Math.min(Integer.MAX_VALUE, inputEnergyCapacity + outputEnergyCapacity);
        }

        @Override
        public boolean canExtract() {
            return outputEnergyCapacity > 0 && outputEnergyAmount > 0;
        }

        @Override
        public boolean canReceive() {
            return inputEnergyCapacity > 0 && inputEnergyAmount < inputEnergyCapacity;
        }
    }

    // ===== 护目镜 tooltip =====

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        // Create 风格应力数据由父类 GeneratingKineticBlockEntity 提供：
        // - 输入型：KineticBlockEntity 的 Impact 行（calculateStressApplied 非 0 时显示）
        // - 输出型：GeneratingKineticBlockEntity 的 generator_stats + capacityProvided 容量行
        super.addToGoggleTooltip(tooltip, isPlayerSneaking);
        // 房间号 + 同类工厂数量（多工厂组：组还原数量校验提示用）
        if (roomCode != null && !roomCode.isBlank()) {
            net.createmod.catnip.lang.Lang.builder("createcmpor")
                    .translate("tooltip.factory.room", roomCode)
                    .style(ChatFormatting.GRAY)
                    .forGoggles(tooltip, 1);
        }
        if (factoryCount > 1) {
            net.createmod.catnip.lang.Lang.builder("createcmpor")
                    .translate("tooltip.factory.group_count", branchIndex + 1, factoryCount)
                    .style(ChatFormatting.GRAY)
                    .forGoggles(tooltip, 1);
        }
        // 自定义行：所需/提供的应力总量（SU），方便玩家直接看到需要消耗多少应力
        StressProfile profile = FactoryStressAccess.get(this);
        if (!profile.isEmpty()) {
            net.createmod.catnip.lang.Lang.builder("createcmpor")
                    .translate("tooltip.factory.stress", profile.inputSU(), profile.outputSU())
                    .forGoggles(tooltip, 1);
        }
        net.createmod.catnip.lang.Lang.builder("createcmpor")
                .translate("tooltip.factory.title").forGoggles(tooltip, 1);
        net.createmod.catnip.lang.Lang.builder("createcmpor")
                .translate(replayMode ? "tooltip.factory.mode_replay" : "tooltip.factory.mode_rate")
                .forGoggles(tooltip, 1);
        if (burnModeActive()) {
            // 燃烧：普通 X/s（橙红） · 超热 Y/s（蓝白）——对应 KINDLED 橙红火 / SEETHING 蓝白魂火
            // 手动 5 空格缩进（复制 catnip LangBuilder.forGoggles 的字符串缩进：getIndents(font, 4+1)，默认字体＝5 空格）
            MutableComponent burnLine = Component.literal("     ").append(
                    Component.translatable("createcmpor.tooltip.factory.burn_rate",
                            Component.literal(String.format(java.util.Locale.ROOT, "%.2f/s", normalBurnDemandPerSecond))
                                    .withStyle(ChatFormatting.GOLD),
                            Component.literal(String.format(java.util.Locale.ROOT, "%.2f/s", superBurnDemandPerSecond))
                                    .withStyle(ChatFormatting.AQUA)));
            tooltip.add(burnLine);
        }
        appendIoLines(tooltip);
        return true;
    }

    private void appendIoLines(List<Component> tooltip) {
        if (!replayMode) {
            // 速率显示统一为"每秒"（tickRate 是每 tick 值 × 20）。
            // 注意：不能用容器容量换算（输出仓容量是固定暂存仓 256/4000，与速率无关；
            // 输入容量虽然是 rate×400，换算结果碰巧一致，但不直观且能量无容量可换算）。
            inputItems.forEach((id, container) -> net.createmod.catnip.lang.Lang.builder("createcmpor")
                    .translate("tooltip.factory.io_in_item", itemDisplayName(id),
                            ratePerSecond(inputItemTickRates.get(id)))
                    .style(ChatFormatting.AQUA)
                    .forGoggles(tooltip, 1));
            inputFluids.forEach((id, container) -> net.createmod.catnip.lang.Lang.builder("createcmpor")
                    .translate("tooltip.factory.io_in_fluid", fluidDisplayName(id),
                            ratePerSecond(inputFluidTickRates.get(id)))
                    .style(ChatFormatting.AQUA)
                    .forGoggles(tooltip, 1));
            outputItems.forEach((id, container) -> {
                if (!outputItemTickRates.containsKey(id)) {
                    return; // 返还桶等非产品项（无速率）不显示为产物
                }
                net.createmod.catnip.lang.Lang.builder("createcmpor")
                        .translate("tooltip.factory.io_out_item", itemDisplayName(id),
                                ratePerSecond(outputItemTickRates.get(id)))
                        .style(ChatFormatting.AQUA)
                        .forGoggles(tooltip, 1);
            });
            outputFluids.forEach((id, container) -> net.createmod.catnip.lang.Lang.builder("createcmpor")
                    .translate("tooltip.factory.io_out_fluid", fluidDisplayName(id),
                            ratePerSecond(outputFluidTickRates.get(id)))
                    .style(ChatFormatting.AQUA)
                    .forGoggles(tooltip, 1));
            if (inputEnergyCapacity > 0) {
                net.createmod.catnip.lang.Lang.builder("createcmpor")
                        .translate("tooltip.factory.io_in_energy", inputEnergyTickRate * 20.0)
                        .style(ChatFormatting.AQUA)
                        .forGoggles(tooltip, 1);
            }
            if (outputEnergyCapacity > 0) {
                net.createmod.catnip.lang.Lang.builder("createcmpor")
                        .translate("tooltip.factory.io_out_energy", outputEnergyTickRate * 20.0)
                        .style(ChatFormatting.AQUA)
                        .forGoggles(tooltip, 1);
            }
        } else {
            inputItemPatterns.forEach((id, pattern) -> net.createmod.catnip.lang.Lang.builder("createcmpor")
                    .translate("tooltip.factory.io_in_item", itemDisplayName(id), average(pattern))
                    .style(ChatFormatting.AQUA)
                    .forGoggles(tooltip, 1));
            inputFluidPatterns.forEach((id, pattern) -> net.createmod.catnip.lang.Lang.builder("createcmpor")
                    .translate("tooltip.factory.io_in_fluid", fluidDisplayName(id), average(pattern))
                    .style(ChatFormatting.AQUA)
                    .forGoggles(tooltip, 1));
            outputItemPatterns.forEach((id, pattern) -> net.createmod.catnip.lang.Lang.builder("createcmpor")
                    .translate("tooltip.factory.io_out_item", itemDisplayName(id), average(pattern))
                    .style(ChatFormatting.AQUA)
                    .forGoggles(tooltip, 1));
            outputFluidPatterns.forEach((id, pattern) -> net.createmod.catnip.lang.Lang.builder("createcmpor")
                    .translate("tooltip.factory.io_out_fluid", fluidDisplayName(id), average(pattern))
                    .style(ChatFormatting.AQUA)
                    .forGoggles(tooltip, 1));
            if (inputEnergyPattern.length > 0) {
                net.createmod.catnip.lang.Lang.builder("createcmpor")
                        .translate("tooltip.factory.io_in_energy", average(inputEnergyPattern))
                        .style(ChatFormatting.AQUA)
                        .forGoggles(tooltip, 1);
            }
            if (outputEnergyPattern.length > 0) {
                net.createmod.catnip.lang.Lang.builder("createcmpor")
                        .translate("tooltip.factory.io_out_energy", average(outputEnergyPattern))
                        .style(ChatFormatting.AQUA)
                        .forGoggles(tooltip, 1);
            }
        }
    }

    /** 速率显示换算：每 tick 值 → 每秒（tickRate × 20）。 */
    private static double ratePerSecond(Double tickRate) {
        return tickRate == null ? 0.0 : tickRate * 20.0;
    }

    /** 物品显示名：本地化名称（找不到时回退为 id）。 */
    private static Object itemDisplayName(ResourceLocation id) {
        net.minecraft.world.item.Item item = BuiltInRegistries.ITEM.get(id);
        if (item == null || item == BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.withDefaultNamespace("air"))) {
            return id.toString();
        }
        return item.getDescription();
    }

    /** 流体显示名：本地化名称（找不到时回退为 id）。 */
    private static Object fluidDisplayName(ResourceLocation id) {
        net.minecraft.world.level.material.Fluid fluid = BuiltInRegistries.FLUID.get(id);
        if (fluid == null) {
            return id.toString();
        }
        return fluid.getFluidType().getDescription();
    }

    private static double average(int[] pattern) {
        if (pattern.length == 0) {
            return 0;
        }
        long total = 0;
        for (int value : pattern) {
            total += value;
        }
        return total / (double) pattern.length;
    }

    // ===== NBT =====

    @Override
    protected void read(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.read(tag, registries, clientPacket);
        roomCode = tag.getString("room_code");
        branchIndex = tag.getInt("branch_index");
        factoryCount = Math.max(1, tag.getInt("factory_count"));
        replayMode = tag.getBoolean("replay_mode");
        installed = tag.getBoolean("installed");
        lastSuccess = tag.getBoolean("last_success");
        loadContainerMap(tag, "input_items", inputItems);
        loadContainerMap(tag, "output_items", outputItems);
        loadContainerMap(tag, "burner_fuel_items", burnerFuelItems);
        loadContainerMap(tag, "input_fluids", inputFluids);
        loadContainerMap(tag, "output_fluids", outputFluids);
        inputEnergyCapacity = tag.getLong("input_energy_capacity");
        inputEnergyAmount = tag.getLong("input_energy_amount");
        outputEnergyCapacity = tag.getLong("output_energy_capacity");
        outputEnergyAmount = tag.getLong("output_energy_amount");
        loadPatternMap(tag, "input_item_patterns", inputItemPatterns);
        loadPatternMap(tag, "output_item_patterns", outputItemPatterns);
        loadPatternMap(tag, "input_fluid_patterns", inputFluidPatterns);
        loadPatternMap(tag, "output_fluid_patterns", outputFluidPatterns);
        inputEnergyPattern = tag.getIntArray("input_energy_pattern");
        outputEnergyPattern = tag.getIntArray("output_energy_pattern");
        patternLength = tag.getInt("pattern_length");
        replayCurrentSecond = tag.getInt("replay_current_second");
        restoreMachineState = tag.contains("restore_state", Tag.TAG_COMPOUND)
                ? tag.getCompound("restore_state") : null;
        restoreMachineNbt = tag.contains("restore_machine", Tag.TAG_COMPOUND)
                ? tag.getCompound("restore_machine") : null;
        loadRateMap(tag, "input_item_rates", inputItemTickRates);
        loadRateMap(tag, "output_item_rates", outputItemTickRates);
        loadRateMap(tag, "input_fluid_rates", inputFluidTickRates);
        loadRateMap(tag, "output_fluid_rates", outputFluidTickRates);
        inputEnergyTickRate = tag.getDouble("input_energy_rate");
        outputEnergyTickRate = tag.getDouble("output_energy_rate");
        normalBurnDemandPerSecond = tag.getDouble("normal_burn_demand");
        superBurnDemandPerSecond = tag.getDouble("super_burn_demand");
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putString("room_code", roomCode == null ? "" : roomCode);
        tag.putInt("branch_index", branchIndex);
        tag.putInt("factory_count", factoryCount);
        tag.putBoolean("replay_mode", replayMode);
        tag.putBoolean("installed", installed);
        tag.putBoolean("last_success", lastSuccess);
        saveContainerMap(tag, "input_items", inputItems);
        saveContainerMap(tag, "output_items", outputItems);
        saveContainerMap(tag, "burner_fuel_items", burnerFuelItems);
        saveContainerMap(tag, "input_fluids", inputFluids);
        saveContainerMap(tag, "output_fluids", outputFluids);
        tag.putLong("input_energy_capacity", inputEnergyCapacity);
        tag.putLong("input_energy_amount", inputEnergyAmount);
        tag.putLong("output_energy_capacity", outputEnergyCapacity);
        tag.putLong("output_energy_amount", outputEnergyAmount);
        savePatternMap(tag, "input_item_patterns", inputItemPatterns);
        savePatternMap(tag, "output_item_patterns", outputItemPatterns);
        savePatternMap(tag, "input_fluid_patterns", inputFluidPatterns);
        savePatternMap(tag, "output_fluid_patterns", outputFluidPatterns);
        tag.putIntArray("input_energy_pattern", inputEnergyPattern);
        tag.putIntArray("output_energy_pattern", outputEnergyPattern);
        tag.putInt("pattern_length", patternLength);
        tag.putInt("replay_current_second", replayCurrentSecond);
        if (restoreMachineState != null) {
            tag.put("restore_state", restoreMachineState.copy());
        }
        if (restoreMachineNbt != null) {
            tag.put("restore_machine", restoreMachineNbt.copy());
        }
        saveRateMap(tag, "input_item_rates", inputItemTickRates);
        saveRateMap(tag, "output_item_rates", outputItemTickRates);
        saveRateMap(tag, "input_fluid_rates", inputFluidTickRates);
        saveRateMap(tag, "output_fluid_rates", outputFluidTickRates);
        tag.putDouble("input_energy_rate", inputEnergyTickRate);
        tag.putDouble("output_energy_rate", outputEnergyTickRate);
        tag.putDouble("normal_burn_demand", normalBurnDemandPerSecond);
        tag.putDouble("super_burn_demand", superBurnDemandPerSecond);
    }

    private static void loadRateMap(CompoundTag tag, String key,
                                    Map<ResourceLocation, Double> output) {
        output.clear();
        if (!tag.contains(key, Tag.TAG_COMPOUND)) {
            return;
        }
        CompoundTag map = tag.getCompound(key);
        for (String idKey : map.getAllKeys()) {
            output.put(ResourceLocation.parse(idKey), map.getDouble(idKey));
        }
    }

    private static void saveRateMap(CompoundTag tag, String key,
                                    Map<ResourceLocation, Double> map) {
        CompoundTag mapTag = new CompoundTag();
        for (Map.Entry<ResourceLocation, Double> entry : map.entrySet()) {
            mapTag.putDouble(entry.getKey().toString(), entry.getValue());
        }
        tag.put(key, mapTag);
    }

    private static void loadContainerMap(CompoundTag tag, String key,
                                         Map<ResourceLocation, Container> output) {
        output.clear();
        if (!tag.contains(key, Tag.TAG_COMPOUND)) {
            return;
        }
        CompoundTag map = tag.getCompound(key);
        for (String idKey : map.getAllKeys()) {
            CompoundTag entry = map.getCompound(idKey);
            Container container = new Container(entry.getLong("capacity"));
            container.amount = entry.getLong("amount");
            output.put(ResourceLocation.parse(idKey), container);
        }
    }

    private static void saveContainerMap(CompoundTag tag, String key,
                                         Map<ResourceLocation, Container> map) {
        CompoundTag mapTag = new CompoundTag();
        for (Map.Entry<ResourceLocation, Container> entry : map.entrySet()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putLong("capacity", entry.getValue().capacity);
            entryTag.putLong("amount", entry.getValue().amount);
            mapTag.put(entry.getKey().toString(), entryTag);
        }
        tag.put(key, mapTag);
    }

    private static void loadPatternMap(CompoundTag tag, String key,
                                       Map<ResourceLocation, int[]> output) {
        output.clear();
        if (!tag.contains(key, Tag.TAG_COMPOUND)) {
            return;
        }
        CompoundTag map = tag.getCompound(key);
        for (String idKey : map.getAllKeys()) {
            output.put(ResourceLocation.parse(idKey), map.getIntArray(idKey));
        }
    }

    private static void savePatternMap(CompoundTag tag, String key,
                                       Map<ResourceLocation, int[]> map) {
        CompoundTag mapTag = new CompoundTag();
        for (Map.Entry<ResourceLocation, int[]> entry : map.entrySet()) {
            mapTag.putIntArray(entry.getKey().toString(), entry.getValue());
        }
        tag.put(key, mapTag);
    }

    public boolean isInstalled() {
        return installed;
    }

    public boolean isReplayMode() {
        return replayMode;
    }

    /** 工厂是否配置了能量 IO（无能量 IO 时不注册 FE 能力，UI 不显示 FE 标识）。 */
    public boolean hasEnergyIo() {
        return inputEnergyCapacity > 0 || outputEnergyCapacity > 0;
    }

    // ===== Create 应力网络参与 =====

    /** 应力自报：不走 BlockStressValues 注册表。 */
    @Override
    protected Block getStressConfigKey() {
        return getBlockState().getBlock();
    }

    /** 输出型工厂（outputSU>0）作为应力源：生成转速 = 空间内应力输出方块的转速（评估时网络采样）。 */
    @Override
    public float getGeneratedSpeed() {
        StressProfile profile = FactoryStressAccess.get(this);
        if (!profile.isProvide() || !lastSuccess) {
            // 未在运转（缺料/燃料不足/输出满仓暂停）→ 不生成转速 → 不输出应力（网络无转速、下游机械停）
            return 0;
        }
        // 默认转速 = 评估时空间内应力输出方块的转速（outputRPM 来自 KineticNetwork 采样），
        // 不再使用固定 32 RPM 兜底：评估时空间无动力则 outputRPM=0 → 工厂不输出。
        return convertToDirection(profile.outputRPM(), firstOpenFace());
    }

    /** 返回任一开口面方向（无开口时默认 up，仅用于转速方向推导）。 */
    private Direction firstOpenFace() {
        BlockState state = getBlockState();
        for (Map.Entry<Direction, BooleanProperty> entry : FactoryBlock.SHAFT_BY_FACE.entrySet()) {
            if (state.getValue(entry.getValue())) {
                return entry.getKey();
            }
        }
        return Direction.UP;
    }

    /** 输出型工厂向网络提供应力容量（含损耗系数）。 */
    @Override
    public float calculateAddedStressCapacity() {
        StressProfile profile = FactoryStressAccess.get(this);
        float capacity = 0;
        if (profile.isProvide() && Config.ENABLE_STRESS_OUTPUT.get()) {
            // 水车式：容量固定（注册 outputSU 折算，不随运转状态变）——护目镜显示 = 容量×转速（转速才随激活开关）。
            // 之前用 |getTheoreticalSpeed()| 折算且随 lastSuccess 变 → 客户端不同步/显示 0。
            float nominalSpeed = Math.abs(profile.outputRPM());
            if (nominalSpeed != 0) {
                capacity = profile.outputSU() * (1.0f - Config.STRESS_LOSS_FACTOR.get().floatValue()) / nominalSpeed;
            }
        }
        this.lastCapacityProvided = capacity;
        return capacity;
    }

    /** 输入型工厂向网络申报应力消耗。 */
    @Override
    public float calculateStressApplied() {
        StressProfile profile = FactoryStressAccess.get(this);
        float impact = 0;
        if (profile.isConsume()) {
            float speed = Math.abs(getTheoreticalSpeed());
            if (speed != 0) {
                impact = profile.inputSU() / speed;
            }
        }
        this.lastStressApplied = impact;
        return impact;
    }

    /**
     * 转速变化后主动向网络重报应力数据。
     *
     * <p>原因：Create 的 {@code KineticNetwork.add()} 只在方块加入网络时调用一次
     * {@code calculateStressApplied()} 存入 members——静态 impact（BlockStressValues）没问题，
     * 但工厂的动态 impact（inputSU/|speed|）在被动接入时 add 发生在 speed 设置之前，
     * speed=0 导致 members 存 0 且此后无人重算（{@code updateStressFor} 只有源方块会调）。
     * 这里对齐 GeneratingKineticBlockEntity 的自报模式，在速度变化后重新上报。
     */
    @Override
    public void onSpeedChanged(float previousSpeed) {
        super.onSpeedChanged(previousSpeed);
        if (level == null || level.isClientSide || !hasNetwork()) {
            return;
        }
        com.simibubi.create.content.kinetics.KineticNetwork network = getOrCreateNetwork();
        if (isSource()) {
            network.updateCapacityFor(this, calculateAddedStressCapacity());
        }
        network.updateStressFor(this, calculateStressApplied());
    }
}
