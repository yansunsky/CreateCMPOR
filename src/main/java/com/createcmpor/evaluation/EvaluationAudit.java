package com.createcmpor.evaluation;

import com.createcmpor.Config;
import com.createcmpor.CreateCMPOR;
import com.createcmpor.init.ModBlocks;
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
                ModBlocks.STRESS_EXTENSION.get(),
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

                    for (Direction direction : Direction.values()) {
                        IItemHandler itemHandler = level.getCapability(
                                Capabilities.ItemHandler.BLOCK, pos, direction);
                        if (itemHandler != null && seenItemHandlers.add(itemHandler)) {
                            if (dedupBlockIds.contains(blockId)) {
                                ItemStack lastStack = ItemStack.EMPTY;
                                for (int slot = 0; slot < itemHandler.getSlots(); slot++) {
                                    ItemStack stack = itemHandler.getStackInSlot(slot);
                                    if (!stack.isEmpty()) {
                                        lastStack = stack;
                                    }
                                }
                                if (!lastStack.isEmpty()) {
                                    ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                                            .getKey(lastStack.getItem());
                                    items.merge(id, (long) lastStack.getCount(), Long::sum);
                                }
                            } else {
                                for (int slot = 0; slot < itemHandler.getSlots(); slot++) {
                                    ItemStack stack = itemHandler.getStackInSlot(slot);
                                    if (!stack.isEmpty()) {
                                        ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                                                .getKey(stack.getItem());
                                        items.merge(id, (long) stack.getCount(), Long::sum);
                                    }
                                }
                            }
                            break;
                        }
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

        if (items.isEmpty() && fluids.isEmpty() && energy == 0) {
            return InventorySnapshot.empty();
        }
        return new InventorySnapshot(items, fluids, energy);
    }

    /** 审计修正（RATE 模式）：realProduction = S1 - S0 + O - I。 */
    static Map<EvaluationTrace.FlowKey, Double> auditRates(
            InventorySnapshot s0, InventorySnapshot s1, EvaluationTrace trace,
            Map<EvaluationTrace.FlowKey, Double> rates) {
        if (s0 == null || s1 == null) {
            return rates;
        }
        Map<EvaluationTrace.FlowKey, Double> adjusted = new HashMap<>(rates);

        for (Map.Entry<EvaluationTrace.FlowKey, Double> entry : adjusted.entrySet()) {
            EvaluationTrace.FlowKey key = entry.getKey();
            if (!"item".equals(key.kind())) {
                continue;
            }
            long s0Count = s0.items().getOrDefault(key.id(), 0L);
            long s1Count = s1.items().getOrDefault(key.id(), 0L);
            EvaluationTrace.Series series = trace.series().get(key);
            long totalO = series == null ? 0L : series.outputTotal;
            long totalI = series == null ? 0L : series.inputTotal;
            long realTotal = s1Count - s0Count + totalO - totalI;

            if (realTotal <= 0) {
                entry.setValue(0.0);
            } else if (totalO > 0) {
                entry.setValue(entry.getValue() * ((double) realTotal / totalO));
            }
        }

        // 能量（单独键，不在此 Map）
        // 消耗物归入输入：S0 独有且 realTotal<0 的条目
        for (Map.Entry<ResourceLocation, Long> s0Entry : s0.items().entrySet()) {
            ResourceLocation id = s0Entry.getKey();
            long s1Count = s1.items().getOrDefault(id, 0L);
            EvaluationTrace.FlowKey key = EvaluationTrace.FlowKey.item(id);
            EvaluationTrace.Series series = trace.series().get(key);
            long totalO = series == null ? 0L : series.outputTotal;
            long totalI = series == null ? 0L : series.inputTotal;
            long realTotal = s1Count - s0Entry.getValue() + totalO - totalI;

            if (realTotal < 0 && !adjusted.containsKey(key)) {
                double rate = EvaluationRateEvaluator.evaluateStableRate(
                        series == null ? new int[1] : series.input);
                if (rate <= 0) {
                    long elapsed = Math.max(1, (long) trace.seconds() * 20);
                    rate = (double) (-realTotal) / elapsed;
                }
                if (rate > 0) {
                    adjusted.put(key, rate);
                }
            }
        }
        return adjusted;
    }

    static double auditEnergyRate(InventorySnapshot s0, InventorySnapshot s1,
                                  EvaluationTrace.EnergySeries energy, double recordedRate,
                                  int seconds) {
        if (s0 == null || s1 == null) {
            return recordedRate;
        }
        long realEnergy = s1.energy() - s0.energy()
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
