package com.createcmpor.block;

import com.createcmpor.CreateCMPOR;
import com.createcmpor.Config;
import com.createcmpor.evaluation.EvaluationTrace;
import com.createcmpor.init.ModBlockEntities;
import com.createcmpor.stress.FactoryStressAccess;
import com.createcmpor.stress.StressProfile;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
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
 */
public class FactoryBlockEntity extends KineticBlockEntity
        implements IHaveGoggleInformation {

    private static final int BUFFER_SECONDS = 20;
    /** 产物暂存仓容量：物品 4 组。 */
    private static final long ITEM_OUTPUT_BUFFER = 256;
    /** 产物暂存仓容量：流体 4 桶。 */
    private static final long FLUID_OUTPUT_BUFFER = 4000;

    private String roomCode;

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

    private boolean lastSuccess = true;
    private int tickCount;

    private CompoundTag restoreMachineState;
    private CompoundTag restoreMachineNbt;

    private final IItemHandler itemHandler = new ItemHandler();
    private final IFluidHandler fluidHandler = new FluidHandler();
    private final IEnergyStorage energyHandler = new EnergyHandler();

    public FactoryBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.FACTORY.get(), pos, state);
    }

    public String getRoomCode() {
        return roomCode;
    }

    public void setRoomCode(String roomCode) {
        this.roomCode = roomCode;
        setChanged();
    }

    // ===== 评估结果安装（固化时调用一次） =====

    /** RATE 模式：输入容量 = 每秒速率 × 20 秒缓冲；输出 = 大容量暂存仓（连续产出积累）。 */
    public void installRates(Map<EvaluationTrace.FlowKey, Double> inputRates,
                             Map<EvaluationTrace.FlowKey, Double> outputRates,
                             double inputEnergyRate, double outputEnergyRate) {
        replayMode = false;
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
                                int[] energyIn, int[] energyOut) {
        replayMode = true;
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
        // 应力暂停：输入型工厂（需要外部应力）在网络过载时完全暂停兑换。
        if (stressInputRequired() && isOverStressed()) {
            lastSuccess = false;
            return;
        }
        tickCount++;
        if (tickCount >= 20) {
            tickCount = 0;
        }
        if (replayMode) {
            if (tickCount == 0) {
                tickReplay();
            }
        } else {
            tickRateContinuous();
        }
    }

    /** 工厂是否为输入型（需要外部应力驱动）。 */
    private boolean stressInputRequired() {
        return FactoryStressAccess.get(this).isConsume();
    }

    /** RATE 连续流：每 tick 按速率累计，输入不足即卡住；产物持续流入大容量暂存仓。 */
    private void tickRateContinuous() {
        if (!inputsSatisfied()) {
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
        for (Map.Entry<ResourceLocation, int[]> entry : inputItemPatterns.entrySet()) {
            Container container = Objects.requireNonNull(inputItems.get(entry.getKey()));
            if (container.amount < entry.getValue()[second % entry.getValue().length]) {
                return false;
            }
        }
        for (Map.Entry<ResourceLocation, int[]> entry : inputFluidPatterns.entrySet()) {
            Container container = Objects.requireNonNull(inputFluids.get(entry.getKey()));
            if (container.amount < entry.getValue()[second % entry.getValue().length]) {
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

    private void retryAfterExternalChange() {
        // RATE 连续流每 tick 自恢复；仅 REPLAY 需要外部变化后立即重试当前秒。
        if (!lastSuccess && installed && replayMode) {
            if (replayIsReady(replayCurrentSecond)) {
                replayApply(replayCurrentSecond);
                replayCurrentSecond = (replayCurrentSecond + 1) % patternLength;
                lastSuccess = true;
            }
        }
    }

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
            return inputKeys().size() + outputKeys().size();
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            List<Item> inputs = inputKeys();
            if (slot < inputs.size()) {
                Container container = inputItems.get(BuiltInRegistries.ITEM.getKey(inputs.get(slot)));
                long amount = container == null ? 0 : Math.min(container.amount, 64);
                return new ItemStack(inputs.get(slot), (int) Math.max(1, amount));
            }
            int outputIndex = slot - inputs.size();
            List<Item> outputs = outputKeys();
            if (outputIndex < outputs.size()) {
                Container container = outputItems.get(BuiltInRegistries.ITEM.getKey(outputs.get(outputIndex)));
                long amount = container == null ? 0 : Math.min(container.amount, 64);
                return new ItemStack(outputs.get(outputIndex), (int) Math.max(1, amount));
            }
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
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
                retryAfterExternalChange();
                setChanged();
            }
            return accepted >= stack.getCount() ? ItemStack.EMPTY
                    : stack.copyWithCount(stack.getCount() - accepted);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
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
                retryAfterExternalChange();
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
                return new FluidStack(inputs.get(tank), container == null ? 0 : (int) Math.min(container.amount, 1));
            }
            int outputIndex = tank - inputs.size();
            List<Fluid> outputs = outputFluids.keySet().stream()
                    .map(BuiltInRegistries.FLUID::get).filter(Objects::nonNull).toList();
            if (outputIndex < outputs.size()) {
                Container container = outputFluids.get(BuiltInRegistries.FLUID.getKey(outputs.get(outputIndex)));
                return new FluidStack(outputs.get(outputIndex), container == null ? 0 : (int) Math.min(container.amount, 1));
            }
            return FluidStack.EMPTY;
        }

        @Override
        public int getTankCapacity(int tank) {
            return Integer.MAX_VALUE;
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
                    retryAfterExternalChange();
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
                    retryAfterExternalChange();
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
                    retryAfterExternalChange();
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
                retryAfterExternalChange();
                setChanged();
            }
            return accepted;
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            long available = Math.min(outputEnergyAmount, maxExtract);
            if (!simulate) {
                outputEnergyAmount -= available;
                retryAfterExternalChange();
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
        // Catnip LangBuilder 与 Create 同款：自动拼接 createcmpor 翻译域 + 缩进
        net.createmod.catnip.lang.Lang.builder("createcmpor")
                .translate("tooltip.factory.title").forGoggles(tooltip, 1);
        net.createmod.catnip.lang.Lang.builder("createcmpor")
                .translate(replayMode ? "tooltip.factory.mode_replay" : "tooltip.factory.mode_rate")
                .forGoggles(tooltip, 1);
        appendIoLines(tooltip);
        StressProfile profile = FactoryStressAccess.get(this);
        if (!profile.isEmpty()) {
            net.createmod.catnip.lang.Lang.builder("createcmpor")
                    .translate("tooltip.factory.stress", profile.inputSU(), profile.outputSU())
                    .forGoggles(tooltip, 1);
        }
        return true;
    }

    private void appendIoLines(List<Component> tooltip) {
        if (!replayMode) {
            inputItems.forEach((id, container) -> net.createmod.catnip.lang.Lang.builder("createcmpor")
                    .translate("tooltip.factory.io_in_item", id.toString(),
                            container.capacity / (double) BUFFER_SECONDS)
                    .forGoggles(tooltip, 1));
            inputFluids.forEach((id, container) -> net.createmod.catnip.lang.Lang.builder("createcmpor")
                    .translate("tooltip.factory.io_in_fluid", id.toString(),
                            container.capacity / (double) BUFFER_SECONDS)
                    .forGoggles(tooltip, 1));
            outputItems.forEach((id, container) -> net.createmod.catnip.lang.Lang.builder("createcmpor")
                    .translate("tooltip.factory.io_out_item", id.toString(),
                            container.capacity / (double) BUFFER_SECONDS)
                    .forGoggles(tooltip, 1));
            outputFluids.forEach((id, container) -> net.createmod.catnip.lang.Lang.builder("createcmpor")
                    .translate("tooltip.factory.io_out_fluid", id.toString(),
                            container.capacity / (double) BUFFER_SECONDS)
                    .forGoggles(tooltip, 1));
        } else {
            inputItemPatterns.forEach((id, pattern) -> net.createmod.catnip.lang.Lang.builder("createcmpor")
                    .translate("tooltip.factory.io_in_item", id.toString(), average(pattern))
                    .forGoggles(tooltip, 1));
            outputItemPatterns.forEach((id, pattern) -> net.createmod.catnip.lang.Lang.builder("createcmpor")
                    .translate("tooltip.factory.io_out_item", id.toString(), average(pattern))
                    .forGoggles(tooltip, 1));
        }
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
        replayMode = tag.getBoolean("replay_mode");
        installed = tag.getBoolean("installed");
        loadContainerMap(tag, "input_items", inputItems);
        loadContainerMap(tag, "output_items", outputItems);
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
    }

    @Override
    protected void write(CompoundTag tag, HolderLookup.Provider registries, boolean clientPacket) {
        super.write(tag, registries, clientPacket);
        tag.putString("room_code", roomCode == null ? "" : roomCode);
        tag.putBoolean("replay_mode", replayMode);
        tag.putBoolean("installed", installed);
        saveContainerMap(tag, "input_items", inputItems);
        saveContainerMap(tag, "output_items", outputItems);
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

    /** 输出型工厂（outputSU>0）作为应力源：返回档案转速沿任意开口面方向。 */
    @Override
    public float getGeneratedSpeed() {
        StressProfile profile = FactoryStressAccess.get(this);
        if (!profile.isProvide()) {
            return 0;
        }
        float speed = profile.outputRPM() > 0 ? profile.outputRPM() : 32;
        return convertToDirection(speed, firstOpenFace());
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
        if (!profile.isProvide() || !Config.ENABLE_STRESS_OUTPUT.get()) {
            return 0;
        }
        float speed = Math.abs(getTheoreticalSpeed());
        if (speed == 0) {
            return 0;
        }
        return profile.outputSU() * (1.0f - Config.STRESS_LOSS_FACTOR.get().floatValue()) / speed;
    }

    /** 输入型工厂向网络申报应力消耗。 */
    @Override
    public float calculateStressApplied() {
        StressProfile profile = FactoryStressAccess.get(this);
        if (!profile.isConsume()) {
            return 0;
        }
        float speed = Math.abs(getTheoreticalSpeed());
        if (speed == 0) {
            return 0;
        }
        return profile.inputSU() / speed;
    }
}
