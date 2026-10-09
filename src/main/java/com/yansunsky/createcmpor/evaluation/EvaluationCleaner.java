package com.yansunsky.createcmpor.evaluation;

import com.yansunsky.createcmpor.Config;
import com.yansunsky.createcmpor.CreateCMPOR;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * 净化启动棒的核心：在评估副本里清空容器残余物，让 S0 快照全空。
 *
 * <p><b>为什么这么做</b>：{@link EvaluationAudit#auditRates} 在 {@code S0 全空} 时走简化路径
 * （只用 IO 追踪的输入/输出，完全不做 S1/S2 容器净平衡）。用户调试时房间总会有遗留物，
 * 于是永远走不到那条路径。净化启动棒把副本清干净，从而稳定落进简化路径。</p>
 *
 * <p><b>执行时机</b>：必须在 S0 扫描<b>之前</b>（{@code EvaluationScheduler.tickStarting} /
 * {@code tickParallelBranch}），且此时副本区块已切到 ENTITY_TICKING。</p>
 *
 * <p><b>清理对象</b>：</p>
 * <ul>
 *   <li>按 {@link Config#PURIFICATION_CLEAR_MODE} 判定的方块容器（物品/流体/能量三类 NeoForge 能力）；</li>
 *   <li><b>无条件</b>：地板掉落物实体。</li>
 * </ul>
 *
 * <p><b>本次不做</b>：烈焰人燃烧室的预存余热（Create 无公开清零 API，见 {@link #describe} 的说明）；
 * 装置内部的库存（改由"检测到动态结构就整体回退普通评估"处理）。</p>
 *
 * <p><b>清不掉就如实上报</b>：有些容器根本不对外暴露 {@code Capabilities.*.BLOCK}
 * （典型如 Create 传送带/置物台——它们对外只进不出），本类清不到它们；
 * 复检会把它们写进 {@link Stats#leftover}，用于如实上报"净化没能清干净"，
 * 而不是静默地"以为清干净了"。</p>
 *
 * <p><b>过滤器一律不动</b>：属性/列表/包裹过滤器与各类工具的数据都存在<b>物品组件</b>里，
 * 本类只动方块容器能力，天然碰不到它们。</p>
 */
public final class EvaluationCleaner {

    private EvaluationCleaner() {
    }

    /** 清理策略：模式 + 已解析的方块 ID 集合（每次调用只解析一次配置）。 */
    record Policy(Config.PurificationClearMode mode,
                  Set<ResourceLocation> whitelist,
                  Set<ResourceLocation> blacklist) {

        public static Policy fromConfig() {
            Config.PurificationClearMode mode = null;
            try {
                mode = Config.PURIFICATION_CLEAR_MODE.get();
            } catch (RuntimeException ignored) {
                // 配置未加载（理论上不成立）：退回默认模式，绝不让净化把评估拖垮
            }
            if (mode == null) {
                mode = Config.PurificationClearMode.WHITELIST;
            }
            return new Policy(mode, parse(Config.PURIFICATION_WHITELIST.get()),
                    parse(Config.PURIFICATION_BLACKLIST.get()));
        }

        private static Set<ResourceLocation> parse(List<? extends String> raw) {
            Set<ResourceLocation> out = new HashSet<>();
            if (raw == null) {
                return out;
            }
            for (String value : raw) {
                if (value == null) {
                    continue;
                }
                ResourceLocation id = ResourceLocation.tryParse(value);
                if (id != null) {
                    out.add(id);
                }
            }
            return out;
        }

        boolean shouldClean(ResourceLocation blockId) {
            return switch (mode) {
                case ALL -> true;
                case WHITELIST -> whitelist.contains(blockId);
                case BLACKLIST -> !blacklist.contains(blockId);
            };
        }
    }

    /**
     * 清理统计。{@code leftover} 非空 = 有清不掉的容器（诊断用，判净化不成立）。
     *
     * <p>{@code excluded} 是"按当前策略就不该清"的方块（白名单外/豁免名单内），
     * 它们不是失败，而是配置结果；单独一列只为了让人看懂报告。</p>
     */
    record Stats(int containers, long items, long fluidUnits, long energy,
                 int droppedEntities, long droppedItems,
                 int specialItems, List<String> excluded, List<String> leftover) {

        static Stats none() {
            return new Stats(0, 0, 0, 0, 0, 0, 0, List.of(), List.of());
        }

        public boolean hasLeftover() {
            return !leftover.isEmpty();
        }

        /** 单行摘要（写日志/聊天用，避免多行 I/O）。 */
        public String summary() {
            String base = "容器 " + containers + " 个 / 物品 " + items + " 件 / 流体 " + fluidUnits
                    + " / 能量 " + energy + " / 掉落物 " + droppedEntities + " 堆(" + droppedItems + " 件)";
            if (specialItems > 0) {
                base += " / 专用通道 " + specialItems + " 件";
            }
            if (!excluded.isEmpty()) {
                base += " / 策略外 " + excluded.size() + " 处";
            }
            return base;
        }

        String leftoverSummary() {
            return leftover.isEmpty() ? "无" : String.join("；", leftover);
        }
    }

    /**
     * 清理动作模式。
     *
     * <p>{@link #REPORT} 完全不修改世界，只统计"会清掉什么"——调试命令与开启净化时的
     * 自检都用它，避免误删玩家不想丢的东西。</p>
     */
    public enum Action {
        /** 只报告：不改世界。 */
        REPORT,
        /** 清空：提取/抽干/删除掉落物。 */
        CLEAR,
        /** 清空并复读断言（净化评估用这个）。 */
        CLEAR_AND_RESTORE
    }

    /**
     * 清理房间内的容器残余物。
     *
     * @param level  评估副本（或源房间，调试命令用）所在的服务端维度
     * @param bounds 房间外边界（{@code RoomInstance.boundaries().outerBounds()}，含 Y 轴）
     * @param policy 清理策略（白名单只决定"允许动谁"，不允许的会在报告里标注）
     * @param action {@link Action}
     * @return 清理统计；净化评估用 {@link Stats#hasLeftover()} 判定是否真的清干净
     */
    public static Stats clean(ServerLevel level, AABB bounds, Policy policy, Action action) {
        boolean mutate = action != Action.REPORT;
        boolean verify = action == Action.CLEAR_AND_RESTORE;
        Sweep sweep = new Sweep(mutate);

        walk(level, bounds, (pos, state) -> {
            ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            boolean allowed = policy.shouldClean(blockId);
            // 执行时策略外的方块直接跳过，不读也不动（连读都省了：报告模式才需要统计它们）
            if (!allowed && mutate) {
                return;
            }
            try {
                sweep.encounter(level, pos, blockId, allowed);
                if (mutate && allowed) {
                    sweep.specialItems += CreateInventoryClearer.clear(level, pos);
                }
            } catch (RuntimeException exception) {
                // 第三方 capability 提供者抛异常：记一条并按"有残余物"处理 → 回退普通评估，
                // 而不是让一个坏模组把整场评估打崩
                sweep.noteLeftover(blockId, pos, "能力读取异常：" + exception.getClass().getSimpleName());
            }
        });

        if (verify) {
            walk(level, bounds, (pos, state) -> {
                ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
                if (!policy.shouldClean(blockId)) {
                    return;
                }
                try {
                    sweep.verifyAfterClear(level, pos, blockId);
                } catch (RuntimeException exception) {
                    sweep.noteLeftover(blockId, pos, "复检异常：" + exception.getClass().getSimpleName());
                }
            });
        }

        int droppedEntities = 0;
        long droppedItems = 0L;
        List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class, bounds);
        for (ItemEntity entity : drops) {
            droppedItems += entity.getItem().getCount();
            droppedEntities++;
            if (mutate) {
                entity.discard();
            }
        }

        return sweep.toStats(droppedEntities, droppedItems);
    }

    /** 三维遍历工具：与 {@link EvaluationAudit#scan} 同口径的边界取整。 */
    private static void walk(ServerLevel level, AABB bounds, BlockVisitor visitor) {
        int startX = (int) Math.floor(bounds.minX);
        int startY = (int) Math.floor(bounds.minY);
        int startZ = (int) Math.floor(bounds.minZ);
        int endX = (int) Math.floor(bounds.maxX - 1.0E-5);
        int endY = (int) Math.floor(bounds.maxY - 1.0E-5);
        int endZ = (int) Math.floor(bounds.maxZ - 1.0E-5);
        for (int x = startX; x <= endX; x++) {
            for (int y = startY; y <= endY; y++) {
                for (int z = startZ; z <= endZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (!state.isAir()) {
                        visitor.visit(pos, state);
                    }
                }
            }
        }
    }

    @FunctionalInterface
    private interface BlockVisitor {
        void visit(BlockPos pos, BlockState state);
    }

    /** 可变的清理累加器（连同三类能力的实例去重集合）。 */
    private static final class Sweep {
        final Set<IItemHandler> seenItems = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<IFluidHandler> seenFluids = Collections.newSetFromMap(new IdentityHashMap<>());
        final Set<IEnergyStorage> seenEnergy = Collections.newSetFromMap(new IdentityHashMap<>());
        private final boolean mutate;
        private final List<String> leftover = new ArrayList<>();
        private final List<String> excluded = new ArrayList<>();

        int containers;
        long items;
        long fluidUnits;
        long energy;
        int specialItems;

        Sweep(boolean mutate) {
            this.mutate = mutate;
        }

        /** 读一个位置的三类能力：REPORT 只统计，CLEAR 系列顺手清掉。 */
        void encounter(ServerLevel level, BlockPos pos, ResourceLocation blockId, boolean allowed) {
            boolean touched = false;
            for (Direction direction : Direction.values()) {
                IItemHandler itemHandler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, direction);
                if (itemHandler != null && seenItems.add(itemHandler) && itemHandler.getSlots() > 0) {
                    for (int slot = 0; slot < itemHandler.getSlots(); slot++) {
                        ItemStack observed = itemHandler.extractItem(slot, Integer.MAX_VALUE, !mutate);
                        if (!observed.isEmpty()) {
                            items += observed.getCount();
                            touched = true;
                        }
                    }
                }
                IFluidHandler fluidHandler = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, direction);
                if (fluidHandler != null && seenFluids.add(fluidHandler)) {
                    IFluidHandler.FluidAction fluidAction = mutate
                            ? IFluidHandler.FluidAction.EXECUTE : IFluidHandler.FluidAction.SIMULATE;
                    for (int tank = 0; tank < fluidHandler.getTanks(); tank++) {
                        FluidStack observed = fluidHandler.drain(
                                fluidHandler.getTankCapacity(tank), fluidAction);
                        if (!observed.isEmpty()) {
                            fluidUnits += observed.getAmount();
                            touched = true;
                        }
                    }
                }
                IEnergyStorage storage = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, direction);
                if (storage != null && seenEnergy.add(storage)) {
                    int stored = storage.getEnergyStored();
                    if (stored > 0) {
                        if (mutate && storage.canExtract()) {
                            // simulate=false：真抽。REPORT 模式永远不会走到这里（mutate 为假）
                            stored -= storage.extractEnergy(stored, false);
                        }
                        if (stored > 0) {
                            energy += stored;
                            touched = true;
                            if (mutate) {
                                // 提取后仍非零 = 只进储能等无法清空的实现
                                noteLeftover(blockId, pos, "无法提取的能量 " + stored);
                            }
                        }
                    }
                }
            }
            if (touched) {
                containers++;
            }
            if (!allowed) {
                excluded.add(blockId + "@" + posOf(pos));
            }
        }

        /** 清完之后复读断言：仍然非空 = 清不掉的残余物。 */
        void verifyAfterClear(ServerLevel level, BlockPos pos, ResourceLocation blockId) {
            for (Direction direction : Direction.values()) {
                IItemHandler itemHandler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, direction);
                if (itemHandler != null && seenItems.add(itemHandler) && itemHandler.getSlots() > 0) {
                    for (int slot = 0; slot < itemHandler.getSlots(); slot++) {
                        ItemStack remaining = itemHandler.extractItem(slot, Integer.MAX_VALUE, true);
                        if (!remaining.isEmpty()) {
                            noteLeftover(blockId, pos, "物品 " + remaining.getCount() + "×"
                                    + BuiltInRegistries.ITEM.getKey(remaining.getItem()));
                            break;
                        }
                    }
                }
                IFluidHandler fluidHandler = level.getCapability(Capabilities.FluidHandler.BLOCK, pos, direction);
                if (fluidHandler != null && seenFluids.add(fluidHandler)) {
                    for (int tank = 0; tank < fluidHandler.getTanks(); tank++) {
                        FluidStack remaining = fluidHandler.drain(
                                fluidHandler.getTankCapacity(tank), IFluidHandler.FluidAction.SIMULATE);
                        if (!remaining.isEmpty()) {
                            noteLeftover(blockId, pos, "流体 " + remaining.getAmount() + " "
                                    + remaining.getFluidHolder().getRegisteredName());
                            break;
                        }
                    }
                }
                IEnergyStorage storage = level.getCapability(Capabilities.EnergyStorage.BLOCK, pos, direction);
                if (storage != null && seenEnergy.add(storage)) {
                    int remaining = storage.getEnergyStored();
                    if (remaining > 0) {
                        noteLeftover(blockId, pos, "能量 " + remaining);
                    }
                }
            }
        }

        private void noteLeftover(ResourceLocation blockId, BlockPos pos, String detail) {
            leftover.add(blockId + "@" + posOf(pos) + "（" + detail + "）");
        }

        Stats toStats(int droppedEntities, long droppedItems) {
            return new Stats(containers, items, fluidUnits, energy,
                    droppedEntities, droppedItems, specialItems,
                    List.copyOf(excluded), List.copyOf(leftover));
        }
    }

    /**
     * 写日志用的一句话诊断；返回给聊天提示的短文案。
     *
     * <p><b>注意</b>：能不能走"S0 全空简化路径"最终由 {@code s0.isEmpty()} 决定
     * （见 {@code EvaluationScheduler.deliverPurificationMessage}），不是由这里的统计决定
     * —— 因为 S0 还包含燃烧室余热等本清理器<b>刻意不碰</b>的东西。</p>
     *
     * <p><b>已知未覆盖（按用户 2026-10-09 决定跳过）</b>：烈焰人燃烧室的预存余热本次<b>不清理</b>。
     * Create 6.0.10-281 没有任何公开的"清零余热"API（{@code remainingBurnTime}/{@code activeFuel}
     * 都是 {@code protected}，唯一写入路径 {@code tryUpdateFuel} 只会<b>加</b>燃料），
     * 故房间里若有一个烧过的燃烧室，S0 仍会非空 → 自动退回完整三扫描（净化被稀释，但不崩、不误判）。
     * 将来若要补上，路径已探明：用 {@code loadStatic} 载入一份只含
     * {@code burnTimeRemaining=0} 与 {@code fuelLevel=0}（{@code FuelType.NONE.ordinal()}）的 NBT
     * —— 这两个键名已由 {@code javap -c} 核实（Create 的 {@code read()} 读的就是它们）。</p>
     */
    static String describe(Stats stats, boolean usingSimplifiedPath) {
        if (!usingSimplifiedPath) {
            CreateCMPOR.LOGGER.warn("净化：存在无法清理的容器，已回退普通评估（不保证 S0 全空）。残留：{}",
                    stats.leftoverSummary());
            return "净化未生效（存在无法清理的容器）";
        }
        return stats.summary();
    }

    private static String posOf(BlockPos pos) {
        return "(" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ")";
    }
}
