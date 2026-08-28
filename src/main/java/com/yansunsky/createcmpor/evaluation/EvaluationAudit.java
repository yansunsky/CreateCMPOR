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
                             Map<ResourceLocation, Long> fluids, long energy,
                             long normalBurnTicks, long superBurnTicks) {
        static InventorySnapshot empty() {
            return new InventorySnapshot(Map.of(), Map.of(), 0L, 0L, 0L);
        }

        boolean isEmpty() {
            return items.isEmpty() && fluids.isEmpty() && energy == 0
                    && normalBurnTicks == 0 && superBurnTicks == 0;
        }
    }

    private static final double EPSILON = 1.0E-5;

    private EvaluationAudit() {
    }

    // 想不到有一天能写出O(5)的垃圾代码
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
        long normalBurnTicks = 0;
        long superBurnTicks = 0;
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
                ModBlocks.PARALLEL_INPUT.get(),
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
                    // 烈焰人燃烧室：累计剩余燃烧时间（分普通/超热两档，创造燃烧室跳过）。
                    // 用于评估"预存热能"消耗——S1→S2 的净减少折成燃料成本（防预填燃料欺骗）。
                    if (state.getBlock() instanceof com.simibubi.create.content.processing.burner.BlazeBurnerBlock) {
                        if (level.getBlockEntity(pos)
                                instanceof com.simibubi.create.content.processing.burner.BlazeBurnerBlockEntity burner
                                && !burner.isCreative()) {
                            int remaining = burner.getRemainingBurnTime();
                            switch (burner.getActiveFuel()) {
                                case SPECIAL -> superBurnTicks += remaining;
                                case NORMAL -> normalBurnTicks += remaining;
                                default -> {
                                }
                            }
                        }
                    }
                    ResourceLocation blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                            .getKey(state.getBlock());

                    boolean itemHandlerScanned = false;
                    if (!isStorageDrawersController(blockId)) {
                        for (Direction direction : Direction.values()) {
                            IItemHandler itemHandler = level.getCapability(
                                    Capabilities.ItemHandler.BLOCK, pos, direction);
                            if (itemHandler != null && seenItemHandlers.add(itemHandler)) {
                                logScanItemSource(pos, blockId, "handler");
                                if (dedupBlockIds.contains(blockId) || isStorageDrawersCompacting(blockId)) {
                                    ItemStack lastStack = ItemStack.EMPTY;
                                    for (int slot = 0; slot < itemHandler.getSlots(); slot++) {
                                        ItemStack stack = itemHandler.getStackInSlot(slot);
                                        if (!stack.isEmpty()) {
                                            lastStack = stack;
                                        }
                                    }
                                    if (!lastStack.isEmpty()) {
                                        logScanItemSource(pos, blockId, "物品(压缩聚合)");
                                        ContainerItemExpander.addToSnapshot(
                                                lastStack, lastStack.getCount(), registries, items);
                                    }
                                } else {
                                    for (int slot = 0; slot < itemHandler.getSlots(); slot++) {
                                        ItemStack stack = itemHandler.getStackInSlot(slot);
                                        if (!stack.isEmpty()) {
                                            logScanItemSource(pos, blockId,
                                                    "物品槽#" + slot + " " + stack);
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
                        logScanItemSource(pos, blockId, "AE2 展开");
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
                                    logScanItemSource(pos, blockId,
                                            "流体#tank" + tank + " " + id + " x " + fluidStack.getAmount());
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
                            int stored = energyStorage.getEnergyStored();
                            if (stored > 0) {
                                logScanItemSource(pos, blockId, "能量 x " + stored);
                            }
                            energy += stored;
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
            logScanItemSource(entity.blockPosition(), null,
                    "掉落物 " + entity.getItem());
            ContainerItemExpander.addToSnapshot(
                    entity.getItem(), entity.getItem().getCount(), registries, items);
        }

        if (items.isEmpty() && fluids.isEmpty() && energy == 0
                && normalBurnTicks == 0 && superBurnTicks == 0) {
            return InventorySnapshot.empty();
        }
        return new InventorySnapshot(items, fluids, energy, normalBurnTicks, superBurnTicks);
    }

    /** 调试：打印 scan 统计到的每个条目来源（方块坐标 + 类型 + 内容），便于定位干扰模组。 */
    private static void logScanItemSource(BlockPos pos, ResourceLocation blockId, String detail) {
        CreateCMPOR.LOGGER.info("[scan] pos=({},{},{}) {} -> {}", pos.getX(), pos.getY(), pos.getZ(),
                blockId == null ? "(掉落物)" : blockId, detail);
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

    /** RATE 审计结果：净消耗速率（net<0）与净产出速率（net>0）；burnDemand* 为对外表现的燃烧需求（热值，tick/秒）。 */
    record RateAudit(Map<EvaluationTrace.FlowKey, Double> inputs,
                     Map<EvaluationTrace.FlowKey, Double> outputs,
                     double normalBurnDemandPerSecond, double superBurnDemandPerSecond) {
    }

    /** 燃烧室预存折算结果：折算的燃料物品输入（每秒）+ 对外表现的两档燃烧需求（tick/秒，0=物品流已覆盖或无需）。 */
    record BurnerPreload(Map<ResourceLocation, Double> fuelInputsPerSecond,
                         double normalDemandPerSecond, double superDemandPerSecond) {
        static BurnerPreload none() {
            return new BurnerPreload(Map.of(), 0, 0);
        }
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
            return new RateAudit(inputs, outputs, 0, 0);
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

        // 燃烧室预存热能折算（防预填燃料欺骗）：
        // - 预存净消耗 = max(0, S1 - S2)（S2>S1 说明中途补充过 → 记 0，投喂照常走物品流）
        // - 超热档：单独折算成烈焰蛋糕（3200 tick）加入工厂输入
        // - 普通档：物品流有可燃物 → 折算成最高优先级代表燃料；无 → 对外表现"燃烧需求"
        BurnerPreload burner = resolveBurnerPreload(baseline, end, trace, seconds);
        for (Map.Entry<ResourceLocation, Double> entry : burner.fuelInputsPerSecond().entrySet()) {
            inputs.merge(EvaluationTrace.FlowKey.item(entry.getKey()), entry.getValue(), Double::sum);
        }
        return new RateAudit(inputs, outputs, burner.normalDemandPerSecond(), burner.superDemandPerSecond());
    }

    /**
     * 燃烧室预存热能折算。
     *
     * <p><b>规则（用户 2026-08-27 确认）</b>：
     * <ul>
     *   <li>预存消耗 = {@code max(0, 基线档 - 终态档)}（分普通/超热两档；+差值表示中途补充 → 记为 0，投喂照常走物品流）</li>
     *   <li>超热档：单独折算成烈焰蛋糕（burn_time=3200）加入工厂输入</li>
     *   <li>普通档：物品流（trace 输入）有可燃物 → 折算成<b>最高优先级代表燃料</b>（煤炭&gt;木炭&gt;熔岩桶&gt;木板&gt;原木&gt;苔藓&gt;地毯&gt;其他）的消耗量；
     *       物品流<b>没有</b>燃料 → 不折算物品，对外表现"燃烧需求"（玩家投任意燃料，工厂动态消耗）</li>
     *   <li>物品流有可燃物时热值系统显示 0（成本已折算进燃料物品流）</li>
     * </ul>
     */
    static BurnerPreload resolveBurnerPreload(InventorySnapshot base, InventorySnapshot end,
                                              EvaluationTrace trace, double seconds) {
        if (base == null || end == null) {
            return BurnerPreload.none();
        }
        long preloadNormal = Math.max(0, base.normalBurnTicks() - end.normalBurnTicks());
        long preloadSuper = Math.max(0, base.superBurnTicks() - end.superBurnTicks());
        if (preloadNormal <= 0 && preloadSuper <= 0) {
            return BurnerPreload.none();
        }
        double elapsedSeconds = Math.max(1, seconds);
        Map<ResourceLocation, Double> fuelInputs = new HashMap<>();
        double normalDemand = 0;

        // 超热档：单独折算烈焰蛋糕（唯一超热燃料，burn_time=3200）
        if (preloadSuper > 0) {
            fuelInputs.merge(BLAZE_CAKE_ID, preloadSuper / elapsedSeconds / 3200.0, Double::sum);
        }
        // 普通档
        if (preloadNormal > 0) {
            ResourceLocation representative = highestPriorityBurnableInTrace(trace);
            if (representative != null) {
                double burnTime = burnTimeOf(representative, trace);
                fuelInputs.merge(representative,
                        preloadNormal / elapsedSeconds / Math.max(1, burnTime), Double::sum);
                // 物品流有燃料 → 热值系统显示 0（demand = 0）
            } else {
                normalDemand = preloadNormal / elapsedSeconds; // 对外表现燃烧需求（tick/秒）
            }
        }
        return new BurnerPreload(fuelInputs, normalDemand, 0);
    }

    /** 优先级代表燃料（物品流出现多种可燃物时选最高优先级）。煤炭>木炭>熔岩桶>木板>原木>苔藓>地毯>其他。 */
    private static ResourceLocation highestPriorityBurnableInTrace(EvaluationTrace trace) {
        // 收集 trace 物品输入中的可燃物（排除超热烈焰蛋糕——超热单独折算）
        for (String id : PRIORITY_BURNER_FUELS) {
            ResourceLocation candidate = ResourceLocation.tryParse(id);
            if (candidate == null) {
                continue;
            }
            if (hasItemFlowInput(trace, candidate)) {
                return candidate;
            }
        }
        // "其他"：任意可燃物（按优先级最低处理）
        for (Map.Entry<EvaluationTrace.FlowKey, EvaluationTrace.Series> entry : trace.series().entrySet()) {
            if (!"item".equals(entry.getKey().kind()) || entry.getValue().inputTotal <= 0) {
                continue;
            }
            ResourceLocation id = entry.getKey().id();
            if (BLAZE_CAKE_ID.equals(id)) {
                continue; // 超热单独折算
            }
            if (burnTimeOf(id, trace) > 0) {
                return id;
            }
        }
        return null;
    }

    private static boolean hasItemFlowInput(EvaluationTrace trace, ResourceLocation id) {
        EvaluationTrace.Series series = trace.series().get(EvaluationTrace.FlowKey.item(id));
        return series != null && series.inputTotal > 0;
    }

    /** 物品燃料热值（tick；超热烈焰蛋糕=3200 走 datamap 语义）。 */
    private static double burnTimeOf(ResourceLocation id, EvaluationTrace trace) {
        net.minecraft.world.item.Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id);
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            return 0;
        }
        if (BLAZE_CAKE_ID.equals(id)) {
            return 3200;
        }
        int burn = new ItemStack(item).getBurnTime(null);
        return burn > 0 ? burn : 0;
    }

    private static final ResourceLocation BLAZE_CAKE_ID =
            ResourceLocation.fromNamespaceAndPath("create", "blaze_cake");

    /** 普通档优先级代表燃料（物品流存在时选它）。 */
    private static final java.util.List<String> PRIORITY_BURNER_FUELS = java.util.List.of(
            "minecraft:coal",
            "minecraft:charcoal",
            "minecraft:lava_bucket",
            "minecraft:oak_planks", "minecraft:spruce_planks", "minecraft:birch_planks",
            "minecraft:jungle_planks", "minecraft:acacia_planks", "minecraft:dark_oak_planks",
            "minecraft:mangrove_planks", "minecraft:cherry_planks", "minecraft:bamboo_planks",
            "minecraft:crimson_planks", "minecraft:warped_planks",
            "minecraft:oak_log", "minecraft:spruce_log", "minecraft:birch_log",
            "minecraft:jungle_log", "minecraft:acacia_log", "minecraft:dark_oak_log",
            "minecraft:mangrove_log", "minecraft:cherry_log",
            "minecraft:moss_block",
            "minecraft:white_carpet", "minecraft:orange_carpet", "minecraft:magenta_carpet",
            "minecraft:light_blue_carpet", "minecraft:yellow_carpet", "minecraft:lime_carpet",
            "minecraft:pink_carpet", "minecraft:gray_carpet", "minecraft:light_gray_carpet",
            "minecraft:cyan_carpet", "minecraft:purple_carpet", "minecraft:blue_carpet",
            "minecraft:brown_carpet", "minecraft:green_carpet", "minecraft:red_carpet",
            "minecraft:black_carpet");

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
