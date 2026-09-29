package com.yansunsky.createcmpor.block;

import com.yansunsky.createcmpor.CreateCMPOR;
import com.yansunsky.createcmpor.Config;
import com.yansunsky.createcmpor.evaluation.EvaluationTrace;
import com.yansunsky.createcmpor.evaluation.ItemIdentity;
import com.yansunsky.createcmpor.init.ModBlockEntities;
import com.yansunsky.createcmpor.preview.PreviewSnapshot;
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
    /**
     * 产物暂存仓<b>保底</b>容量（物品 4 组）。
     *
     * <p>实际容量 = {@code max(保底, 速率 × BUFFER_SECONDS)}（见 {@link #capacityFromTickRate}），
     * 与输入侧同口径——即"20 秒产量"缓冲。低速产线用保底值，高速产线按速率放大。
     *
     * <p>为什么必须随速率放大（0.3.48 修复）：旧实现固定 256，一条 6601 个/秒的产线只有
     * 0.04 秒缓冲 → 产物持续溢出静默丢弃；且容量 256 只够暴露 4 个 64 分片，
     * Create 打包机一包（9 组 = 576 个）只能取到 4 组。放大后容量与速率匹配、分片可达 9。
     */
    private static final long ITEM_OUTPUT_BUFFER = 256;
    /** 产物暂存仓保底容量（流体 4 桶）；实际容量同物品侧按速率放大。 */
    private static final long FLUID_OUTPUT_BUFFER = 4000;

    private String roomCode;

    /** 多工厂组：本工厂的分支索引（0-based）；单工厂 = 0。 */
    private int branchIndex = 0;
    /** 多工厂组：同 roomCode 工厂总数（组还原数量校验用）；单工厂 = 1。 */
    private int factoryCount = 1;

    private static final class Container {
        /** 身份签名（id + 组件摘要；无组件时为 id 字符串）——容器在 map 中的键。 */
        final String signature;
        /** 物品/流体 id（注册表查询、堆叠上限、燃料热值、日志用）。 */
        final ResourceLocation id;
        long capacity;
        long amount;
        double fraction;
        /**
         * 物品形态模板（count=1、含 DataComponents）。
         * 由评估采样（{@code EvaluationTrace} 的组件模板）或首次投入的真实物品得出，
         * 用于重建产物——否则带组件物品会退化成无组件原型（如水瓶 → 不可合成的药水）。
         */
        ItemStack template = ItemStack.EMPTY;

        Container(String signature, ResourceLocation id, long capacity) {
            this.signature = signature;
            this.id = id;
            this.capacity = capacity;
        }

        /** 记录首个非空模板（同签名下不应出现第二变体；空模板时以首次投入的真实形态补全）。 */
        void applyTemplate(ItemStack stack) {
            if (template.isEmpty() && stack != null && !stack.isEmpty()) {
                template = stack.copyWithCount(1);
            }
        }
    }

    /**
     * 物品容器 → 物品堆（模板优先，无模板回退纯 id）；数量 ≤0 或 id 无效返回空。
     * 仅用于物品容器（流体容器不调用）。
     */
    private static ItemStack itemStackOf(Container container, int count) {
        if (container == null || count <= 0 || container.id == null) {
            return ItemStack.EMPTY;
        }
        ItemStack base = container.template.isEmpty()
                ? new ItemStack(BuiltInRegistries.ITEM.get(container.id)) : container.template;
        return base.copyWithCount(count);
    }

    /** 物品堆的最大堆叠数（模板优先，回退 id 对应物品）。 */
    private static int maxStackSizeOf(Container container) {
        if (container == null) {
            return 64;
        }
        if (!container.template.isEmpty()) {
            return container.template.getMaxStackSize();
        }
        Item item = container.id == null ? null : BuiltInRegistries.ITEM.get(container.id);
        return item == null ? 64 : item.getDefaultMaxStackSize();
    }

    /** 从模板表取模板（表为空返回 EMPTY）。 */
    private static ItemStack templateOf(Map<EvaluationTrace.FlowKey, ItemStack> templates,
                                        EvaluationTrace.FlowKey key) {
        if (templates == null) {
            return ItemStack.EMPTY;
        }
        ItemStack template = templates.get(key);
        return template == null ? ItemStack.EMPTY : template;
    }

    private boolean replayMode;
    private boolean installed;

    /**
     * 房间产线的微缩预览快照（0.4.0）。
     *
     * <p>固化那一刻从评估副本采集，只存方块 + 调色板，不含方块实体数据；为空表示"无预览"
     * （旧存档、采集失败、超限降级都落在这里），渲染侧必须按"没有预览"优雅处理。
     */
    private PreviewSnapshot previewSnapshot;

    /** 快照版本号（落盘）：每换一次快照 +1，客户端据此判断本地缓存是否过期。0 = 旧档/从来没换过。 */
    private int previewRev;

    /** "有没有可渲染内容"（落盘，install 时算一次）：轻量 tag 同步给客户端，避免客户端现算 O(体积)。 */
    private boolean previewHasContent;

    /** 序列化缓存（不落盘）：按需同步的响应体，随 previewRev 失效。 */
    private CompoundTag previewPayloadCache;

    /** 物品容器表：键 = 身份签名（id + 组件摘要），故同 id 的不同组件变体各自独立成槽。 */
    private final Map<String, Container> inputItems = new LinkedHashMap<>();
    private final Map<String, Container> outputItems = new LinkedHashMap<>();
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
    /** 独立燃料仓（与输入/输出缓存解耦；burn 模式时 handler 末位追加 1 个燃料槽）。键 = 身份签名。 */
    private final Map<String, Container> burnerFuelItems = new LinkedHashMap<>();

    // RATE 连续流速率（每 tick），持久化；物品速率键 = 身份签名
    private final Map<String, Double> inputItemTickRates = new LinkedHashMap<>();
    private final Map<String, Double> outputItemTickRates = new LinkedHashMap<>();
    private final Map<ResourceLocation, Double> inputFluidTickRates = new LinkedHashMap<>();
    private final Map<ResourceLocation, Double> outputFluidTickRates = new LinkedHashMap<>();
    private double inputEnergyTickRate;
    private double outputEnergyTickRate;

    /** 回放 pattern（每秒应兑现量）；物品 pattern 键 = 身份签名。 */
    private final Map<String, int[]> inputItemPatterns = new LinkedHashMap<>();
    private final Map<String, int[]> outputItemPatterns = new LinkedHashMap<>();
    private final Map<ResourceLocation, int[]> inputFluidPatterns = new LinkedHashMap<>();
    private final Map<ResourceLocation, int[]> outputFluidPatterns = new LinkedHashMap<>();
    private int[] inputEnergyPattern = new int[0];
    private int[] outputEnergyPattern = new int[0];
    private int patternLength;
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
                             Map<EvaluationTrace.FlowKey, ItemStack> inputItemTemplates,
                             Map<EvaluationTrace.FlowKey, ItemStack> outputItemTemplates,
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
                Container container = new Container(entry.getKey().signature(), entry.getKey().id(), capacity);
                container.applyTemplate(templateOf(inputItemTemplates, entry.getKey()));
                inputItems.put(entry.getKey().signature(), container);
            } else {
                inputFluids.put(entry.getKey().id(),
                        new Container(ItemIdentity.of(entry.getKey().id()), entry.getKey().id(), capacity));
            }
        }
        for (Map.Entry<EvaluationTrace.FlowKey, Double> entry : outputRates.entrySet()) {
            if (entry.getValue() <= 0) {
                continue;
            }
            boolean item = "item".equals(entry.getKey().kind());
            // 输出容量 = max(保底, 速率 × 20 秒)，与输入侧 capacityFromTickRate 同口径。
            // 旧实现写死 256：高速产线缓冲不足会溢出丢产物，且分片只有 4 → 打包机一包只能取 4 组。
            long scaled = capacityFromTickRate(entry.getValue());
            long buffer = item ? Math.max(ITEM_OUTPUT_BUFFER, scaled)
                    : Math.max(FLUID_OUTPUT_BUFFER, scaled);
            if (item) {
                Container container = new Container(entry.getKey().signature(), entry.getKey().id(), buffer);
                container.applyTemplate(templateOf(outputItemTemplates, entry.getKey()));
                outputItems.put(entry.getKey().signature(), container);
            } else {
                outputFluids.put(entry.getKey().id(),
                        new Container(ItemIdentity.of(entry.getKey().id()), entry.getKey().id(), buffer));
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
                inputItemTickRates.put(key.signature(), rate);
            } else {
                inputFluidTickRates.put(key.id(), rate);
            }
        });
        outputRates.forEach((key, rate) -> {
            if ("item".equals(key.kind())) {
                outputItemTickRates.put(key.signature(), rate);
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
                                Map<EvaluationTrace.FlowKey, ItemStack> inputItemTemplates,
                                Map<EvaluationTrace.FlowKey, ItemStack> outputItemTemplates,
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
                inputItemPatterns.put(entry.getKey().signature(), entry.getValue());
            } else {
                inputFluidPatterns.put(entry.getKey().id(), entry.getValue());
            }
        }
        for (Map.Entry<EvaluationTrace.FlowKey, int[]> entry : replayOut.entrySet()) {
            if ("item".equals(entry.getKey().kind())) {
                outputItemPatterns.put(entry.getKey().signature(), entry.getValue());
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
        for (Map.Entry<EvaluationTrace.FlowKey, int[]> entry : replayIn.entrySet()) {
            boolean item = "item".equals(entry.getKey().kind());
            long capacity = maxInt(entry.getValue()) * BUFFER_SECONDS;
            if (item) {
                Container container = new Container(entry.getKey().signature(), entry.getKey().id(), capacity);
                container.applyTemplate(templateOf(inputItemTemplates, entry.getKey()));
                inputItems.put(entry.getKey().signature(), container);
            } else {
                inputFluids.put(entry.getKey().id(),
                        new Container(ItemIdentity.of(entry.getKey().id()), entry.getKey().id(), capacity));
            }
        }
        for (Map.Entry<EvaluationTrace.FlowKey, int[]> entry : replayOut.entrySet()) {
            boolean item = "item".equals(entry.getKey().kind());
            long capacity = item
                    ? Math.max(ITEM_OUTPUT_BUFFER, maxInt(entry.getValue()) * BUFFER_SECONDS)
                    : Math.max(FLUID_OUTPUT_BUFFER, maxInt(entry.getValue()) * BUFFER_SECONDS);
            if (item) {
                Container container = new Container(entry.getKey().signature(), entry.getKey().id(), capacity);
                container.applyTemplate(templateOf(outputItemTemplates, entry.getKey()));
                outputItems.put(entry.getKey().signature(), container);
            } else {
                outputFluids.put(entry.getKey().id(),
                        new Container(ItemIdentity.of(entry.getKey().id()), entry.getKey().id(), capacity));
            }
        }
        inputEnergyCapacity = maxInt(inputEnergyPattern) * BUFFER_SECONDS;
        outputEnergyCapacity = maxInt(outputEnergyPattern) * BUFFER_SECONDS;
        inputEnergyAmount = 0;
        outputEnergyAmount = 0;
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

    /**
     * 安装微缩预览快照（固化时调用一次）。传 {@code null} 表示本次不采集——
     * 保持现状而不是清空，避免多分支固化时后续分支的采集失败把已装好的预览抹掉。
     */
    public void installPreview(PreviewSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        this.previewSnapshot = snapshot;
        // 0.4.20：版本号 +1 —— 客户端据此判断"我本地那份是不是过期了"。
        // 没有它，按需同步下"重新固化"会显示过期微缩（见 docs/工作日志.md）。
        this.previewRev++;
        this.previewHasContent = snapshot.nonAirCount() > 0;
        this.previewPayloadCache = null;   // 序列化缓存随版本失效（按需同步的响应包要用它）
        this.carryPreviewOnce = true;      // 固化当场那一包仍带全量：站在机器旁的玩家 0 RTT 看到微缩
        setChanged();
        // 工厂方块刚放置、玩家可能就在旁边：主动推一次，别等下一次网络重挂
        if (level != null && !level.isClientSide) {
            sendData();
        }
    }

    /** 是否有可渲染的微缩预览。 */
    public boolean hasPreview() {
        return previewSnapshot != null && previewSnapshot.nonAirCount() > 0;
    }

    /** 微缩预览快照；无预览返回 {@code null}。 */
    public PreviewSnapshot getPreviewSnapshot() {
        return previewSnapshot;
    }

    /** 快照版本号：每换一次快照 +1；0 = 从来没有过（旧档）。客户端用它判断本地缓存是否过期。 */
    public int previewRev() {
        return previewRev;
    }

    /** 服务端语义的"有没有可渲染的微缩"（轻量 tag 同步给客户端，避免客户端现算 O(体积) 的 nonAirCount）。 */
    public boolean previewHasContent() {
        return previewHasContent;
    }

    /**
     * 按需同步的响应体：把快照序列化成 NBT，<b>按版本缓存</b>（每 tick 重建一份 1 MB 的 CompoundTag 太贵）。
     *
     * <p>注意：返回的 tag <b>发布后禁止就地修改</b>——它会直接进网络包，netty 线程只读它。
     */
    public CompoundTag previewPayloadTag() {
        if (previewSnapshot == null) {
            return null;
        }
        if (previewPayloadCache == null) {
            previewPayloadCache = previewSnapshot.save();
        }
        return previewPayloadCache;
    }

    /** 按需同步的占地体积（字节）；用于服务端的单包体积闸门。 */
    public int previewEncodedSize() {
        CompoundTag tag = previewPayloadTag();
        return tag == null ? 0 : tag.sizeInBytes();
    }

    /**
     * <b>客户端专用</b>：把服务端按需发来的快照装进这个（客户端的）方块实体。
     *
     * <p>渲染层零改动——{@code FactoryPreviewRenderer} / {@code PreviewBakeCache} 的过期判据本来就是
     * "BE 上的快照对象身份"，装回去就自动重烘。
     */
    public void installClientPreview(PreviewSnapshot snapshot, int rev) {
        this.previewSnapshot = snapshot;
        this.previewRev = rev;
        this.previewHasContent = snapshot != null && snapshot.nonAirCount() > 0;
        this.previewPayloadCache = null;
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
                // 0.4.19 省流量：这条包是**高频**的（运转状态每次翻转都会发），而微缩快照只在固化时变，
                // 重发它纯粹是浪费（大装置一次可达上百 KB/玩家）。标记后这一个客户端包不带 preview，
                // 客户端保留已有快照（见 read 里对应的处理）。区块加载/固化/放置那几条路径照常携带。
                skipPreviewInClientPayload = true;
                sendData();
            }
        }
    }

    /** 上次上报运转状态的快照（输出型工厂触发网络重报用）。 */
    private boolean lastSuccessReported = false;

    /**
     * 下一个<b>客户端包</b>是否刻意省略 {@code preview}（0.4.19 省流量）。
     *
     * <p>背景：微缩快照是随方块实体 NBT 一起同步的，而快照里最重的是装置（contraption）的
     * 方块结构（实测一台 116 KB）。它只在<b>固化那一刻</b>变，但 {@code sendData()} 的调用点里
     * 有一条是<b>高频</b>的——"输出型工厂运转状态翻转"（{@code lastSuccess} 变化，启停一次发一次），
     * 每次重发整份快照纯属浪费。
     *
     * <p>因此：高位翻转那条路径置位本标记后再 {@code sendData()}，让这一个包不带 {@code preview}
     * （客户端保留已有快照，见 {@code read(...)} 里的对应判断）；区块加载、固化、放置三条路径照常携带。
     *
     * <p>不落盘、不进 NBT（纯瞬时状态）。
     */
    private boolean skipPreviewInClientPayload;

    /**
     * 下一个客户端包是否<b>携带全量快照</b>（一次性、不落盘）。
     *
     * <p>置位时机：① {@code installPreview(...)}（固化那一刻——站在机器旁的玩家 0 RTT 看到微缩）；
     * ② 从完整 NBT（落盘/物品）里读到非空快照时——"放下自己刚挖的工厂"同样 0 RTT。
     * 其余玩家走按需请求，这正是设计意图。
     */
    private boolean carryPreviewOnce;

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
        for (Map.Entry<String, Container> entry : inputItems.entrySet()) {
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

        for (Map.Entry<String, Container> entry : outputItems.entrySet()) {
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
        for (Map.Entry<String, Container> entry : inputItems.entrySet()) {
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

    /**
     * REPLAY 回放推进：外层 {@link #tick()} 的 tickCount 每 20 tick（1 秒）才调用本方法一次，
     * pattern 每个元素 = 该"秒"应兑现的量，因此这里每次都直接兑现当前秒槽并推进到下一秒。
     * （注意：不得在本方法内再叠一层 20 tick 计数——那会让每秒的兑现变成每 20 秒一次，产出只有理论的 1/20。）
     */
    private void tickReplay() {
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
        for (Map.Entry<String, int[]> entry : inputItemPatterns.entrySet()) {
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
        for (Map.Entry<String, int[]> entry : outputItemPatterns.entrySet()) {
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
        for (Map.Entry<String, int[]> entry : inputItemPatterns.entrySet()) {
            Container container = Objects.requireNonNull(inputItems.get(entry.getKey()));
            container.amount -= entry.getValue()[second % entry.getValue().length];
        }
        for (Map.Entry<ResourceLocation, int[]> entry : inputFluidPatterns.entrySet()) {
            Container container = Objects.requireNonNull(inputFluids.get(entry.getKey()));
            container.amount -= entry.getValue()[second % entry.getValue().length];
        }
        for (Map.Entry<String, int[]> entry : outputItemPatterns.entrySet()) {
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
        for (Map.Entry<String, Container> entry : burnerFuelItems.entrySet()) {
            ResourceLocation id = entry.getValue().id;
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
        Container cake = burnerFuelItems.get(itemKey(BURN_BLAZE_CAKE));
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
            Container cake = burnerFuelItems.get(itemKey(BURN_BLAZE_CAKE));
            return cake != null && cake.amount >= 1;
        }
        for (Map.Entry<String, Container> entry : burnerFuelItems.entrySet()) {
            if (!BURN_BLAZE_CAKE.equals(entry.getValue().id)
                    && burnTimeOf(entry.getValue().id) > 0
                    && entry.getValue().amount >= 1) {
                return true;
            }
        }
        return false;
    }

    /** 燃料仓键：无组件物品的签名即其 id 字符串（烈焰蛋糕/熔岩桶等原版燃料无组件）。 */
    private static String itemKey(ResourceLocation id) {
        return ItemIdentity.of(id);
    }

    /** 当前世界的注册表访问器（物品身份签名 / 模板编解码用）。 */
    private net.minecraft.core.HolderLookup.Provider registries() {
        return level == null ? null : level.registryAccess();
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
        /**
         * 输入槽位列表：返回<b>身份签名</b>（id + 组件摘要）。
         * 同一 id 的不同组件变体因此各占一个槽（如水瓶与治疗药水互不覆盖）。
         * 签名对应的 id 已不在注册表（模组被移除）时跳过，避免暴露空槽。
         */
        private List<String> inputIds() {
            List<String> keys = new ArrayList<>();
            inputItems.forEach((signature, container) -> {
                if (container.id != null && BuiltInRegistries.ITEM.containsKey(container.id)) {
                    keys.add(signature);
                }
            });
            return keys;
        }

        private List<String> outputIds() {
            List<String> keys = new ArrayList<>();
            outputItems.forEach((signature, container) -> {
                if (container.id != null && BuiltInRegistries.ITEM.containsKey(container.id)) {
                    keys.add(signature);
                }
            });
            return keys;
        }

        /**
         * 每个签名暴露的分片槽数：把"单槽大容量"拆成"多槽 × 每槽 64"。
         *
         * <p>为什么必须分片（Create 6.0.10 源码确证）：打包机拆包
         * （{@code DefaultUnpackingHandler.unpack}）在 simulate 阶段遍历目标槽，
         * <b>空槽</b>按 {@code getSlotLimit} 认领、<b>非空槽</b>受
         * {@code min(itemInSlot.getMaxStackSize(), getSlotLimit)} 限制（恒 ≤ 64）。
         * 一包最多 9 组（{@code PackageItem.SLOTS=9} × 64 = 576 个），若每个签名只有 1 个槽，
         * 一次只能吃下 64 → 整包拆包判定失败。分片后 9 个槽可一次吸收 576 个。
         *
         * <p>分片语义：分片 i 负责该容器 [i×64, (i+1)×64) 段。对外等价于"多个 64 容量的格子"，
         * 与 Create ItemVault（20 槽 × 64）思路一致；内部仍是单一 {@code Container.amount}（long），
         * 存档格式不变。
         */
        /** 每片固定 64（等价于"一个普通格子"）。 */
        private static final int SHARD_SIZE = 64;
        /**
         * 每个签名最多暴露的分片数（上限，防高速产线容量过大导致槽位爆炸）。
         *
         * <p>9 是打包机一包的上限（{@code PackageItem.SLOTS=9}），足够一次吸收整包；
         * 容量超过 9×64=576 时，超出部分由最后一片"兜底覆盖"（见 {@link #shardCapacity}），
         * 保证**存量全覆盖**——不会出现"看不见/取不出"的卡死。
         */
        private static final int MAX_SHARDS_PER_SIGNATURE = 9;

        /** 该容器应暴露的分片数：按容量推导（≤64 一片；超 576 仍为 9 片，末片兜底）。 */
        private int shardCount(Container container) {
            if (container == null) {
                return 0;
            }
            long byCapacity = (Math.max(0L, container.capacity) + SHARD_SIZE - 1) / SHARD_SIZE;
            return (int) Math.max(1L, Math.min(MAX_SHARDS_PER_SIGNATURE, byCapacity));
        }

        /** 输入区槽位总数（各签名分片数之和）。 */
        private int inputSlotTotal() {
            int total = 0;
            for (String signature : inputIds()) {
                total += shardCount(inputItems.get(signature));
            }
            return total;
        }

        /** 输出区槽位总数。 */
        private int outputSlotTotal() {
            int total = 0;
            for (String signature : outputIds()) {
                total += shardCount(outputItems.get(signature));
            }
            return total;
        }

        /** 输出区分片起始索引。 */
        private int outputShardStart() {
            return inputSlotTotal();
        }

        /** 燃料槽索引（burn 模式追加在输入+输出分片之后；未激活返回 -1）。 */
        private int fuelSlotIndex() {
            return burnModeActive() ? inputSlotTotal() + outputSlotTotal() : -1;
        }

        @Override
        public int getSlots() {
            return inputSlotTotal() + outputSlotTotal() + (burnModeActive() ? 1 : 0);
        }

        /**
         * 分片槽 → 容器；越界/燃料槽/未命中返回 null。
         *
         * @param shardOut 输出参数：[0] = 分片序号（该签名内的第几片），仅在返回非 null 时有效
         */
        private Container containerForShard(int slot, int[] shardOut) {
            if (slot < 0) {
                return null;
            }
            int cursor = 0;
            for (String signature : inputIds()) {
                Container container = inputItems.get(signature);
                int count = shardCount(container);
                if (slot < cursor + count) {
                    shardOut[0] = slot - cursor;
                    return container;
                }
                cursor += count;
            }
            for (String signature : outputIds()) {
                Container container = outputItems.get(signature);
                int count = shardCount(container);
                if (slot < cursor + count) {
                    shardOut[0] = slot - cursor;
                    return container;
                }
                cursor += count;
            }
            return null;
        }

        /**
         * 分片段容量上限。
         *
         * <p><b>末片兜底</b>：容量 > 片数×64 时，最后一片覆盖剩余全部容量，
         * 保证所有存量都能被 getStackInSlot 看到、被 extractItem 取出（否则超出部分永久卡死）。
         */
        private long shardCapacity(Container container, int shard) {
            long start = (long) shard * SHARD_SIZE;
            long end = shard == shardCount(container) - 1
                    ? container.capacity
                    : Math.min(container.capacity, start + SHARD_SIZE);
            return Math.max(0L, end - start);
        }

        /** 分片内已占用量（用于判断槽是否"看着为空"与剩余空间）。 */
        private long shardAmount(Container container, int shard) {
            long start = (long) shard * SHARD_SIZE;
            return Math.max(0L, Math.min(container.amount - start, shardCapacity(container, shard)));
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            // 燃料槽（burn 模式追加的末位槽）：返回燃料仓首个非空堆（空则 EMPTY）
            int fuelSlot = fuelSlotIndex();
            if (burnModeActive() && slot == fuelSlot) {
                for (Map.Entry<String, Container> entry : burnerFuelItems.entrySet()) {
                    if (entry.getValue().amount > 0) {
                        return itemStackOf(entry.getValue(),
                                (int) Math.min(entry.getValue().amount, 64));
                    }
                }
                return ItemStack.EMPTY;
            }
            // 输入/输出分片槽：返回该分片段的占用量（空分片返回 EMPTY）。
            // 分片语义让外部设备看到"多个 64 容量的格子"，与 Create ItemVault 一致；
            // 内部仍是单一 Container.amount，存档不变。
            int[] shard = new int[1];
            Container container = containerForShard(slot, shard);
            if (container == null) {
                return ItemStack.EMPTY;
            }
            long amount = Math.min(shardAmount(container, shard[0]), 64L);
            return itemStackOf(container, (int) amount);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            // 燃料槽：接受任意合格燃料进独立燃料仓（容量 1024/类；按热值由 tickBurner 消耗；与其他缓存解耦）
            if (acceptBurnFuel(stack) && slot == fuelSlotIndex()) {
                String signature = ItemIdentity.of(stack, registries());
                Container container = burnerFuelItems.computeIfAbsent(signature,
                        k -> new Container(signature, BuiltInRegistries.ITEM.getKey(stack.getItem()), 1024));
                long space = container.capacity - container.amount;
                int accepted = (int) Math.min(space, stack.getCount());
                if (!simulate) {
                    container.amount += accepted;
                    container.applyTemplate(stack); // 记录燃料真实形态（组件保真，如带名的桶/容器）
                    setChanged();
                }
                return accepted >= stack.getCount() ? ItemStack.EMPTY
                        : stack.copyWithCount(stack.getCount() - accepted);
            }
            // 输入分片槽：只接受本分片段范围内的量（见 getSlotLimit 注释——超领会被拆包逻辑销毁）。
            if (stack.isEmpty()) {
                return stack;
            }
            int[] shard = new int[1];
            Container container = containerForShard(slot, shard);
            if (container == null || container.id == null
                    || !stack.is(BuiltInRegistries.ITEM.get(container.id))) {
                return stack;
            }
            long free = shardCapacity(container, shard[0]) - shardAmount(container, shard[0]);
            int accepted = (int) Math.max(0, Math.min(free, stack.getCount()));
            if (!simulate && accepted > 0) {
                container.amount += accepted;
                container.applyTemplate(stack); // 记录投入原料真实形态（组件保真）
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
            // 输出分片槽：只能从"输出区"取；总量取自该分片段（扣减写回容器总量）。
            // 注意：这里不按分片段上限截断取用量——抽取是"清空容器"语义，
            // 允许一次取走整段（上限交给调用方 amount 与 maxStackSize）。
            int[] shard = new int[1];
            Container container = containerForShard(slot, shard);
            if (container == null || !isOutputShard(slot)) {
                return ItemStack.EMPTY;
            }
            long available = Math.min(shardAmount(container, shard[0]), amount);
            if (!simulate && available > 0) {
                container.amount -= available;
                setChanged();
            }
            // 组件保真：按模板重建产物（水瓶仍是水瓶，而非无组件的"不可合成药水"）
            return itemStackOf(container, (int) Math.min(available, maxStackSizeOf(container)));
        }

        /** 该槽是否属于输出区（输入区与燃料槽不可抽取）。 */
        private boolean isOutputShard(int slot) {
            if (burnModeActive() && slot == fuelSlotIndex()) {
                return false;
            }
            int start = outputShardStart();
            return slot >= start && slot < start + outputSlotTotal();
        }

        /**
         * 槽位上限 = 该缓存容器的实际容量（对齐 StorageDrawers 抽屉 getMaxCapacity 的成熟范式），
         * 而不是硬编码 64。
         *
         * <p>原因：Create 打包机拆包（{@code DefaultUnpackingHandler}）在 simulate 阶段对<b>空槽</b>
         * 用 {@code getSlotLimit(slot)} 判断"放得下多少"；若这里返回 64，一包 9 组（576 个）会被判为
         * 放不下 → 整包拆包失败。返回真实容量后，空槽可一次接纳整包。
         *
         * <p>注意（源码确证）：非空槽分支仍受 {@code itemInSlot.getMaxStackSize()}（物品自身堆叠上限，
         * 通常 64）限制，与 getSlotLimit 无关——单槽大口吞吐需配合"空槽喂料"或自定义 UnpackingHandler。
         * 上限裁剪到 Integer.MAX_VALUE（IItemHandler 契约要求 int）。
         */
        /**
         * 槽位上限 = 该<b>分片</b>的剩余空间（不是总容量）。
         *
         * <p>为什么必须是"剩余空间"而不是"总容量"（Create 源码确证）：打包机拆包的
         * {@code DefaultUnpackingHandler} 在空槽分支里<b>忽略 insertItem 的返回值</b>——
         * 它按 {@code getSlotLimit} 认定"这些全放下了"。若这里报总容量，容器接近满时会超领，
         * 真实插入不足的部分被凭空销毁（丢物品）。报剩余空间则认领量恰好等于可接受量。
         *
         * <p>分片满时返回 0 → 打包机在 simulate 阶段把该槽判为"放不下"并继续试下一片，
         * 与 ItemVault 的"满槽跳过"行为一致。
         */
        @Override
        public int getSlotLimit(int slot) {
            if (burnModeActive() && slot == fuelSlotIndex()) {
                return 1024;
            }
            int[] shard = new int[1];
            Container container = containerForShard(slot, shard);
            if (container == null) {
                return 64;
            }
            long free = shardCapacity(container, shard[0]) - shardAmount(container, shard[0]);
            return (int) Math.max(0, Math.min(free, Integer.MAX_VALUE));
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            // 燃料槽：接受任意合格燃料
            if (burnModeActive() && slot == fuelSlotIndex() && acceptBurnFuel(stack)) {
                return true;
            }
            // 仅输入区可插入；且必须是该分片所属容器的物品
            if (slot < 0 || slot >= outputShardStart()) {
                return false;
            }
            int[] shard = new int[1];
            Container container = containerForShard(slot, shard);
            return container != null && container.id != null
                    && stack.is(BuiltInRegistries.ITEM.get(container.id));
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
            inputItems.forEach((signature, container) -> net.createmod.catnip.lang.Lang.builder("createcmpor")
                    .translate("tooltip.factory.io_in_item", itemDisplayName(container.id, container),
                            ratePerSecond(inputItemTickRates.get(signature)))
                    .style(ChatFormatting.AQUA)
                    .forGoggles(tooltip, 1));
            inputFluids.forEach((id, container) -> net.createmod.catnip.lang.Lang.builder("createcmpor")
                    .translate("tooltip.factory.io_in_fluid", fluidDisplayName(id),
                            ratePerSecond(inputFluidTickRates.get(id)))
                    .style(ChatFormatting.AQUA)
                    .forGoggles(tooltip, 1));
            outputItems.forEach((signature, container) -> {
                if (!outputItemTickRates.containsKey(signature)) {
                    return; // 返还桶等非产品项（无速率）不显示为产物
                }
                net.createmod.catnip.lang.Lang.builder("createcmpor")
                        .translate("tooltip.factory.io_out_item", itemDisplayName(container.id, container),
                                ratePerSecond(outputItemTickRates.get(signature)))
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
            inputItemPatterns.forEach((signature, pattern) -> net.createmod.catnip.lang.Lang.builder("createcmpor")
                    .translate("tooltip.factory.io_in_item",
                            itemDisplayName(null, inputItems.get(signature)), average(pattern))
                    .style(ChatFormatting.AQUA)
                    .forGoggles(tooltip, 1));
            inputFluidPatterns.forEach((id, pattern) -> net.createmod.catnip.lang.Lang.builder("createcmpor")
                    .translate("tooltip.factory.io_in_fluid", fluidDisplayName(id), average(pattern))
                    .style(ChatFormatting.AQUA)
                    .forGoggles(tooltip, 1));
            outputItemPatterns.forEach((signature, pattern) -> net.createmod.catnip.lang.Lang.builder("createcmpor")
                    .translate("tooltip.factory.io_out_item",
                            itemDisplayName(null, outputItems.get(signature)), average(pattern))
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

    /**
     * 物品显示名（优先用容器模板的 hover name）：带组件物品（如水瓶/附魔书）
     * 显示其真实名称，而不是无组件原型的名称（如"不可合成的药水"）。
     */
    private static Object itemDisplayName(ResourceLocation id, Container container) {
        if (container != null && !container.template.isEmpty()) {
            return container.template.getHoverName();
        }
        ResourceLocation effective = id != null ? id : (container == null ? null : container.id);
        return effective == null ? "?" : itemDisplayName(effective);
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
        loadItemContainerMap(tag, "input_items", inputItems, registries);
        loadItemContainerMap(tag, "output_items", outputItems, registries);
        loadItemContainerMap(tag, "burner_fuel_items", burnerFuelItems, registries);
        loadFluidContainerMap(tag, "input_fluids", inputFluids);
        loadFluidContainerMap(tag, "output_fluids", outputFluids);
        inputEnergyCapacity = tag.getLong("input_energy_capacity");
        inputEnergyAmount = tag.getLong("input_energy_amount");
        outputEnergyCapacity = tag.getLong("output_energy_capacity");
        outputEnergyAmount = tag.getLong("output_energy_amount");
        loadSignaturePatternMap(tag, "input_item_patterns", inputItemPatterns);
        loadSignaturePatternMap(tag, "output_item_patterns", outputItemPatterns);
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
        // 微缩预览（0.4.0）：解析失败一律降级为"无预览"，绝不影响工厂本体加载。
        //
        // 0.4.20：这里改成**按版本号**判断（此前是"客户端包没有 preview 键就保留旧快照"，
        // 那个写法在"重新固化后恰好收到一个不带数据的包"时会**保留过期微缩**）。
        if (!clientPacket) {
            // 落盘 / 物品 NBT：永远是完整语义
            previewSnapshot = tag.contains("preview", Tag.TAG_COMPOUND)
                    ? PreviewSnapshot.load(tag.getCompound("preview")) : null;
            previewRev = tag.getInt("preview_rev");
            previewHasContent = previewSnapshot != null && previewSnapshot.nonAirCount() > 0;
            previewPayloadCache = null;
            if (previewHasContent) {
                // 从完整 NBT（存档 / 工厂物品）读到快照：让下一个客户端包带上它，
                // 于是"放下自己刚挖的工厂 / 服务器重启后首次加载"都能 0 RTT 看到微缩。
                carryPreviewOnce = true;
            }
        } else {
            int incomingRev = tag.getInt("preview_rev");
            boolean incomingHas = tag.getBoolean("has_preview");
            if (tag.contains("preview", Tag.TAG_COMPOUND)) {
                previewSnapshot = PreviewSnapshot.load(tag.getCompound("preview"));
                previewRev = incomingRev;
                previewHasContent = previewSnapshot != null && previewSnapshot.nonAirCount() > 0;
                previewPayloadCache = null;
            } else if (previewSnapshot != null && previewRev == incomingRev && incomingHas) {
                // 同一版本、服务端也确认有内容 → 保留（区块重发 / 状态翻转走这条，零开销）
            } else {
                // 版本变了但数据没跟着来（按需同步），或服务端说没有 → 清掉，
                // 让渲染侧去按需索取（客户端缓存里若已有同 rev 的副本会被立刻装回来）
                previewSnapshot = null;
                previewRev = incomingRev;
                previewHasContent = incomingHas;
                previewPayloadCache = null;
            }
        }
        loadSignatureRateMap(tag, "input_item_rates", inputItemTickRates);
        loadSignatureRateMap(tag, "output_item_rates", outputItemTickRates);
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
        saveItemContainerMap(tag, "input_items", inputItems, registries);
        saveItemContainerMap(tag, "output_items", outputItems, registries);
        saveItemContainerMap(tag, "burner_fuel_items", burnerFuelItems, registries);
        saveFluidContainerMap(tag, "input_fluids", inputFluids);
        saveFluidContainerMap(tag, "output_fluids", outputFluids);
        tag.putLong("input_energy_capacity", inputEnergyCapacity);
        tag.putLong("input_energy_amount", inputEnergyAmount);
        tag.putLong("output_energy_capacity", outputEnergyCapacity);
        tag.putLong("output_energy_amount", outputEnergyAmount);
        saveSignaturePatternMap(tag, "input_item_patterns", inputItemPatterns);
        saveSignaturePatternMap(tag, "output_item_patterns", outputItemPatterns);
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
        if (shouldWritePreview(clientPacket)) {
            tag.put("preview", previewSnapshot.save());
        }
        if (clientPacket) {
            // 轻量元数据：永远写（约 6 字节），客户端靠它判断"本地缓存是否过期"。
            tag.putInt("preview_rev", previewRev);
            tag.putBoolean("has_preview", previewHasContent);
            // 两个一次性标记都只影响这一个客户端包
            carryPreviewOnce = false;
            skipPreviewInClientPayload = false;
        }
        saveSignatureRateMap(tag, "input_item_rates", inputItemTickRates);
        saveSignatureRateMap(tag, "output_item_rates", outputItemTickRates);
        saveRateMap(tag, "input_fluid_rates", inputFluidTickRates);
        saveRateMap(tag, "output_fluid_rates", outputFluidTickRates);
        tag.putDouble("input_energy_rate", inputEnergyTickRate);
        tag.putDouble("output_energy_rate", outputEnergyTickRate);
        tag.putDouble("normal_burn_demand", normalBurnDemandPerSecond);
        tag.putDouble("super_burn_demand", superBurnDemandPerSecond);
    }

    /**
     * 这个包/NBT 要不要带全量快照。
     *
     * <ul>
     *     <li><b>落盘 / 物品 NBT（{@code clientPacket == false}）永远带</b>——不许动，
     *         否则存档与工厂物品都会丢快照；</li>
     *     <li>客户端包：{@code carryPreviewOnce}（固化/放置等一次性时机）带；
     *         其余按 {@link Config.PreviewSyncMode} 决定——{@code FULL} 时带（但 0.4.19 起
     *         "运转状态翻转"这类高频包不带，见 {@code skipPreviewInClientPayload}），
     *         {@code ON_DEMAND} 时一律不带（客户端要渲染时会自己来要）。</li>
     * </ul>
     */
    private boolean shouldWritePreview(boolean clientPacket) {
        if (previewSnapshot == null || !previewHasContent) {
            return false;
        }
        if (!clientPacket) {
            return true;
        }
        if (carryPreviewOnce) {
            return true;
        }
        return Config.PREVIEW_SYNC_MODE.get() == Config.PreviewSyncMode.FULL
                && !skipPreviewInClientPayload;
    }

    // ===== 物品表：键 = 身份签名（可能含 "#组件摘要"，故原样存取，不做 id 解析） =====

    private static void loadSignatureRateMap(CompoundTag tag, String key, Map<String, Double> output) {
        output.clear();
        if (!tag.contains(key, Tag.TAG_COMPOUND)) {
            return;
        }
        CompoundTag map = tag.getCompound(key);
        for (String signature : map.getAllKeys()) {
            output.put(signature, map.getDouble(signature));
        }
    }

    private static void saveSignatureRateMap(CompoundTag tag, String key, Map<String, Double> map) {
        CompoundTag mapTag = new CompoundTag();
        map.forEach((signature, value) -> mapTag.putDouble(signature, value));
        tag.put(key, mapTag);
    }

    private static void loadSignaturePatternMap(CompoundTag tag, String key, Map<String, int[]> output) {
        output.clear();
        if (!tag.contains(key, Tag.TAG_COMPOUND)) {
            return;
        }
        CompoundTag map = tag.getCompound(key);
        for (String signature : map.getAllKeys()) {
            output.put(signature, map.getIntArray(signature));
        }
    }

    private static void saveSignaturePatternMap(CompoundTag tag, String key, Map<String, int[]> map) {
        CompoundTag mapTag = new CompoundTag();
        map.forEach(mapTag::putIntArray);
        tag.put(key, mapTag);
    }

    /**
     * 载入物品容器表。条目可带 {@code template}（count=1 的完整 ItemStack NBT，含 DataComponents）：
     * 有模板时 id 由模板反推，无模板（旧档 / 无组件物品）时取签名 {@code #} 前段。
     * 旧档键为裸 id → 视为"无组件签名"，行为与修复前一致（向后兼容）。
     */
    private static void loadItemContainerMap(CompoundTag tag, String key,
                                             Map<String, Container> output,
                                             HolderLookup.Provider registries) {
        output.clear();
        if (!tag.contains(key, Tag.TAG_COMPOUND)) {
            return;
        }
        CompoundTag map = tag.getCompound(key);
        for (String signature : map.getAllKeys()) {
            CompoundTag entry = map.getCompound(signature);
            ItemStack template = entry.contains("template", Tag.TAG_COMPOUND)
                    ? ItemStack.parseOptional(registries, entry.getCompound("template"))
                    : ItemStack.EMPTY;
            ResourceLocation id = !template.isEmpty()
                    ? BuiltInRegistries.ITEM.getKey(template.getItem())
                    : ItemIdentity.idOf(signature);
            Container container = new Container(signature, id, entry.getLong("capacity"));
            container.amount = entry.getLong("amount");
            container.template = template;
            output.put(signature, container);
        }
    }

    private static void saveItemContainerMap(CompoundTag tag, String key,
                                             Map<String, Container> map,
                                             HolderLookup.Provider registries) {
        CompoundTag mapTag = new CompoundTag();
        for (Map.Entry<String, Container> entry : map.entrySet()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putLong("capacity", entry.getValue().capacity);
            entryTag.putLong("amount", entry.getValue().amount);
            if (!entry.getValue().template.isEmpty()) {
                entryTag.put("template", entry.getValue().template.copyWithCount(1).saveOptional(registries));
            }
            mapTag.put(entry.getKey(), entryTag);
        }
        tag.put(key, mapTag);
    }

    // ===== 流体表：键 = 流体 id =====

    private static void loadFluidContainerMap(CompoundTag tag, String key,
                                              Map<ResourceLocation, Container> output) {
        output.clear();
        if (!tag.contains(key, Tag.TAG_COMPOUND)) {
            return;
        }
        CompoundTag map = tag.getCompound(key);
        for (String idKey : map.getAllKeys()) {
            ResourceLocation id = ResourceLocation.tryParse(idKey);
            if (id == null) {
                continue;
            }
            CompoundTag entry = map.getCompound(idKey);
            Container container = new Container(ItemIdentity.of(id), id, entry.getLong("capacity"));
            container.amount = entry.getLong("amount");
            output.put(id, container);
        }
    }

    private static void saveFluidContainerMap(CompoundTag tag, String key,
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

    private static void loadRateMap(CompoundTag tag, String key,
                                    Map<ResourceLocation, Double> output) {
        output.clear();
        if (!tag.contains(key, Tag.TAG_COMPOUND)) {
            return;
        }
        CompoundTag map = tag.getCompound(key);
        for (String idKey : map.getAllKeys()) {
            ResourceLocation id = ResourceLocation.tryParse(idKey);
            if (id != null) {
                output.put(id, map.getDouble(idKey));
            }
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

    private static void loadPatternMap(CompoundTag tag, String key,
                                       Map<ResourceLocation, int[]> output) {
        output.clear();
        if (!tag.contains(key, Tag.TAG_COMPOUND)) {
            return;
        }
        CompoundTag map = tag.getCompound(key);
        for (String idKey : map.getAllKeys()) {
            ResourceLocation id = ResourceLocation.tryParse(idKey);
            if (id != null) {
                output.put(id, map.getIntArray(idKey));
            }
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
