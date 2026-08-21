package com.yansunsky.createcmpor.evaluation;

import com.yansunsky.createcmpor.Config;
import com.yansunsky.createcmpor.CreateCMPOR;
import com.yansunsky.createcmpor.compat.inventory.Ae2BlockContents;
import com.yansunsky.createcmpor.compat.inventory.ContainerItemExpander;
import com.yansunsky.createcmpor.init.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Phase 6 库存基线审计：S0/S_warmup/S1 全房间能力扫描（迁移自原 CMPOR Core.scanInventory）。
 */
final class EvaluationAudit {
    record InventorySnapshot(Map<ResourceLocation, Long> items,
                             Map<ResourceLocation, Long> fluids, long energy) {
        static InventorySnapshot empty() {
            return new InventorySnapshot(Map.of(), Map.of(), 0L);
        }

        boolean isEmpty() {
            return items.isEmpty() && fluids.isEmpty() && energy == 0;
        }
    }

    private static final double EPSILON = 1.0E-5;

    private EvaluationAudit() {
    }

    static InventorySnapshot scan(ServerLevel level, AABB bounds) {
        int startX = (int) Math.floor(bounds.minX);
        int startY = (int) Math.floor(bounds.minY);
        int startZ = (int) Math.floor(bounds.minZ);
        int endX = (int) Math.floor(bounds.maxX - EPSILON);
        int endY = (int) Math.floor(bounds.maxY - EPSILON);
        int endZ = (int) Math.floor(bounds.maxZ - EPSILON);

        Map<ResourceLocation, Long> items = new HashMap<>();
        Map<ResourceLocation, Long> fluids = new HashMap<>();
        long energy = 0;
        var registries = level.registryAccess();

        Set<IItemHandler> seenItemHandlers = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<IFluidHandler> seenFluidHandlers = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<IEnergyStorage> seenEnergyHandlers = Collections.newSetFromMap(new IdentityHashMap<>());

        Set<ResourceLocation> dedupBlockIds = new HashSet<>();
        for (String raw : Config.DEDUP_BLOCKS.get()) {
            ResourceLocation parsed = ResourceLocation.tryParse(raw);
            if (parsed != null) {
                dedupBlockIds.add(parsed);
            }
        }
        Set<Block> ownBlocks = Set.of(
                ModBlocks.INPUT.get(), ModBlocks.OUTPUT.get(),
                ModBlocks.STRESS_INPUT.get(), ModBlocks.STRESS_OUTPUT.get(),
                ModBlocks.IO_EXTENSION.get(),
                ModBlocks.EVALUATOR.get(), ModBlocks.FACTORY.get());

        for (int x = startX; x <= endX; x++) {
            for (int y = startY; y <= endY; y++) {
                for (int z = startZ; z <= endZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir() || ownBlocks.contains(state.getBlock())) {
                        continue;
                    }
                    ResourceLocation blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                            .getKey(state.getBlock());

                    boolean itemHandlerScanned = false;
                    if (!isStorageDrawersController(blockId)) {
                        for (Direction direction : Direction.values()) {
                            IItemHandler itemHandler = level.getCapability(
                                    Capabilities.ItemHandler.BLOCK, pos, direction);
                            if (itemHandler != null && seenItemHandlers.add(itemHandler)) {
                                if (dedupBlockIds.contains(blockId) || isStorageDrawersCompacting(blockId)) {
                                    ItemStack lastStack = ItemStack.EMPTY;
                                    for (int slot = 0; slot < itemHandler.getSlots(); slot++) {
                                        ItemStack stack = itemHandler.getStackInSlot(slot);
                                        if (!stack.isEmpty()) {
                                            lastStack = stack;
                                        }
                                    }
                                    if (!lastStack.isEmpty()) {
                                        ContainerItemExpander.addToSnapshot(
                                                lastStack, lastStack.getCount(), registries, items);
                                    }
                                } else {
                                    for (int slot = 0; slot < itemHandler.getSlots(); slot++) {
                                        ItemStack stack = itemHandler.getStackInSlot(slot);
                                        if (!stack.isEmpty()) {
                                            ContainerItemExpander.addToSnapshot(
                                                    stack, stack.getCount(), registries, items);
                                        }
                                    }
                                }
                                itemHandlerScanned = true;
                                break;
                            }
                        }
                    }
                    if (!itemHandlerScanned) {
                        Ae2BlockContents.addToSnapshot(level, pos, blockId, registries, items);
                    }

                    for (Direction direction : Direction.values()) {
                        IFluidHandler fluidHandler = level.getCapability(
                                Capabilities.FluidHandler.BLOCK, pos, direction);
                        if (fluidHandler != null && seenFluidHandlers.add(fluidHandler)) {
                            for (int tank = 0; tank < fluidHandler.getTanks(); tank++) {
                                var fluidStack = fluidHandler.getFluidInTank(tank);
                                if (!fluidStack.isEmpty()) {
                                    ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.FLUID
                                            .getKey(fluidStack.getFluid());
                                    fluids.merge(id, (long) fluidStack.getAmount(), Long::sum);
                                }
                            }
                            break;
                        }
                    }

                    for (Direction direction : Direction.values()) {
                        IEnergyStorage energyStorage = level.getCapability(
                                Capabilities.EnergyStorage.BLOCK, pos, direction);
                        if (energyStorage != null && seenEnergyHandlers.add(energyStorage)) {
                            energy += energyStorage.getEnergyStored();
                            break;
                        }
                    }
                }
            }
        }

        // 掉落物也是房间库存的一部分：纳入 S0/S1/S2 快照。
        // 防止"向漏斗丢大量掉落物 → 漏斗传输进输出方块"的作弊：
        // 掉落物进入漏斗/输出方块后被扫描记录，三扫描/净平衡会把它当作
        // 库存消耗抵消掉凭空记录的产出流量。
        for (net.minecraft.world.entity.item.ItemEntity entity
                : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, bounds)) {
            if (entity.getItem().isEmpty()) {
                continue;
            }
            ContainerItemExpander.addToSnapshot(
                    entity.getItem(), entity.getItem().getCount(), registries, items);
        }

        if (items.isEmpty() && fluids.isEmpty() && energy == 0) {
            return InventorySnapshot.empty();
        }
        return new InventorySnapshot(items, fluids, energy);
    }

    /**
     * Storage Drawers 压缩抽屉的 handler 槽是同一 pooled count 的换算视图；
     * 最后一个非空槽是最小单位总量。自动识别所有普通/半高/框架变体，
     * 避免旧配置文件缺少新变体时重复计数。
     */
    private static boolean isStorageDrawersCompacting(ResourceLocation blockId) {
        if (!"storagedrawers".equals(blockId.getNamespace())) {
            return false;
        }
        String path = blockId.getPath();
        return path.contains("compacting")
                && (path.endsWith("_2") || path.endsWith("_3"));
    }

    /** Controller 暴露整个抽屉网络的聚合 handler；实际抽屉会单独扫描，故这里跳过以防整网双计。 */
    private static boolean isStorageDrawersController(ResourceLocation blockId) {
        if (!"storagedrawers".equals(blockId.getNamespace())) {
            return false;
        }
        return switch (blockId.getPath()) {
            case "controller", "controller_io", "framed_controller", "framed_controller_io" -> true;
            default -> false;
        };
    }

    /** RATE 审计结果：净消耗速率（net<0）与净产出速率（net>0）。 */
    record RateAudit(Map<EvaluationTrace.FlowKey, Double> inputs,
                     Map<EvaluationTrace.FlowKey, Double> outputs) {
    }

    /**
     * RATE 三扫描防刷净平衡（用户定义规则）：
     * <ul>
     *   <li>S0 全空（物品+流体+能量）→ 简化路径：只用 IO trace 的输入/输出。</li>
     *   <li>产物 = 输出方块实际 trace 收集到的种类（outputTotal&gt;0）且 {@code net=(S2-S1)+O-I > 0}。</li>
     *   <li>中间产物（非输出种类、S0 无、S1 有）→ 完全忽略（即使被消耗）。</li>
     *   <li>消耗 = 非中间产物且 {@code net < 0}（含输出种类减少、初始库存消耗）。</li>
     * </ul>
     */
    static RateAudit auditRates(InventorySnapshot s0, InventorySnapshot baseline, InventorySnapshot end,
                                EvaluationTrace trace, int seconds) {
        long elapsedTicks = Math.max(1, (long) seconds * 20);
        Map<EvaluationTrace.FlowKey, Double> inputs = new HashMap<>();
        Map<EvaluationTrace.FlowKey, Double> outputs = new HashMap<>();

        // S0 全空 → 简化路径：只用 IO trace（输入方块/输出方块自统计）
        if (s0 == null || s0.isEmpty()) {
            for (Map.Entry<EvaluationTrace.FlowKey, EvaluationTrace.Series> entry : trace.series().entrySet()) {
                if (entry.getValue().inputTotal > 0) {
                    inputs.put(entry.getKey(), (double) entry.getValue().inputTotal / elapsedTicks);
                }
                if (entry.getValue().outputTotal > 0) {
                    outputs.put(entry.getKey(), (double) entry.getValue().outputTotal / elapsedTicks);
                }
            }
            return new RateAudit(inputs, outputs);
        }

        Set<EvaluationTrace.FlowKey> outputTypes = new HashSet<>();
        trace.series().forEach((key, series) -> {
            if (series.outputTotal > 0) {
                outputTypes.add(key);
            }
        });

        Set<EvaluationTrace.FlowKey> keys = new HashSet<>(trace.series().keySet());
        baseline.items().keySet().forEach(id -> keys.add(EvaluationTrace.FlowKey.item(id)));
        baseline.fluids().keySet().forEach(id -> keys.add(EvaluationTrace.FlowKey.fluid(id)));
        end.items().keySet().forEach(id -> keys.add(EvaluationTrace.FlowKey.item(id)));
        end.fluids().keySet().forEach(id -> keys.add(EvaluationTrace.FlowKey.fluid(id)));

        for (EvaluationTrace.FlowKey key : keys) {
            boolean item = "item".equals(key.kind());
            long baseCount = item ? baseline.items().getOrDefault(key.id(), 0L)
                    : baseline.fluids().getOrDefault(key.id(), 0L);
            long endCount = item ? end.items().getOrDefault(key.id(), 0L)
                    : end.fluids().getOrDefault(key.id(), 0L);
            long s0Count = item ? s0.items().getOrDefault(key.id(), 0L)
                    : s0.fluids().getOrDefault(key.id(), 0L);
            EvaluationTrace.Series series = trace.series().get(key);
            long ioOut = series == null ? 0L : series.outputTotal;
            long ioIn = series == null ? 0L : series.inputTotal;
            long net = (endCount - baseCount) + ioOut - ioIn;

            boolean isOutputType = outputTypes.contains(key);
            // 中间产物：非输出种类、S0 无、S1 有、且无 IO 输入流量（从 IO 外部输入的原料不算中间产物）
            boolean intermediate = !isOutputType && ioIn == 0 && s0Count == 0 && baseCount > 0;
            if (intermediate) {
                CreateCMPOR.LOGGER.info("审计-中间产物忽略: {}", key.id());
                continue;
            }
            if (isOutputType && net > 0) {
                outputs.put(key, (double) net / elapsedTicks);
            } else if (net < 0) {
                inputs.put(key, (double) (-net) / elapsedTicks);
            }
        }
        return new RateAudit(inputs, outputs);
    }

    static double auditEnergyRate(InventorySnapshot baseline, InventorySnapshot end,
                                  EvaluationTrace.EnergySeries energy, double recordedRate,
                                  int seconds) {
        if (baseline == null || end == null) {
            return recordedRate;
        }
        long realEnergy = end.energy() - baseline.energy()
                + energy.outputTotal - energy.inputTotal;
        if (realEnergy <= 0) {
            return 0;
        }
        if (energy.outputTotal > 0) {
            return recordedRate * ((double) realEnergy / energy.outputTotal);
        }
        return recordedRate;
    }

    static void logInventory(String label, InventorySnapshot snapshot) {
        StringBuilder builder = new StringBuilder(label).append(": items[");
        snapshot.items().forEach((id, count) -> builder.append(id).append('=').append(count).append(' '));
        builder.append("] fluids[");
        snapshot.fluids().forEach((id, amount) -> builder.append(id).append('=').append(amount).append(' '));
        builder.append("] energy=").append(snapshot.energy());
        CreateCMPOR.LOGGER.info(builder.toString());
    }
}
