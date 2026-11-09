package com.yansunsky.createcmpor.evaluation;

import com.yansunsky.createcmpor.Config;
import com.yansunsky.createcmpor.CreateCMPOR;
import com.yansunsky.createcmpor.compat.inventory.ContainerItemExpander;
import com.yansunsky.createcmpor.init.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 方块点名：把「房间里作为方块存在的库存」纳入三扫描。
 *
 * <h2>为什么必须做（0.5.6 修复的物品复制漏洞）</h2>
 * <p>{@link EvaluationAudit#scan} 只聚合<b>容器能力 + 掉落物</b>，房间里"以方块形式存在的库存"
 * 完全不在账上。于是这条漏洞成立：</p>
 * <pre>
 *   预置一批钻石块 → 机器把方块破坏成物品 → 从输出方块送出
 *   ⇒ S2-S1 恒为 0（钻石块不是容器，扫描看不见）
 *   ⇒ 破坏出来的钻石是全额 net&gt;0 的产物
 *   ⇒ 固化出「只输出钻石块、不需要任何输入」的机器。
 * </pre>
 *
 * <h2>修复口径：方块折算是一个<b>独立减法项</b></h2>
 * <p>设 {@code V} = 评估窗口内消失的方块按其掉落物折算出的物品量，则</p>
 * <pre>
 *   net = (S2 - S1) - V + IO输出 - IO输入
 * </pre>
 *
 * <h3>⭐ 为什么<b>不能</b>写成"从终态快照里扣物品"（早期实现踩过的坑）</h3>
 * <p>初版把 {@code V} 从 S2 的 items 里减掉，<b>在用户真正要用的那条路径上完全失效</b>：
 * 机器破坏方块后，掉落物通常<b>不经容器常驻</b>，直接被吸入输出方块送走 ——
 * 此时 S2 里根本没有那件物品，减无可减，{@code net = 0 + ioOut - 0} 仍是全额产出。</p>
 *
 * <p>而"独立减法项"一条公式同时覆盖两种形态：</p>
 * <ul>
 *   <li><b>产物直接出界</b>：{@code (0-0) + 64 - 64 = 0} ✅</li>
 *   <li><b>产物留在容器</b>：{@code (64-0) + 0 - 64 = 0} ✅ 不重复计算</li>
 *   <li><b>正常产线</b>（无方块变化）：{@code V=0}，公式退回原样 ✅</li>
 * </ul>
 * <p>折算量估高时 {@code net} 变负 = 少给产出，方向安全。</p>
 *
 * <h3>为什么用"掉落物"而不是"方块本身对应的物品"</h3>
 * <p>破坏方块的玩家真实收益就是它的掉落物（钻石矿掉钻石、圆石掉圆石）。
 * 用 {@code Block.getDrops(...)} 走真实 loot table 才能与产线产物对上号。
 * 空工具（{@code ItemStack.EMPTY}）的口径已经实测核对：
 * 原版 loot table <b>不检查"正确工具"</b>（如 {@code diamond_block} 只有 {@code survives_explosion}，
 * 而 {@code ExplosionCondition} 在无 {@code EXPLOSION_RADIUS} 时直接放行），
 * 需要镐的方块（stone/iron_ore）在无工具时仍返回掉落物。
 * 即便个别模组方块因此返回空，方向也是<b>偏保守</b>（少扣 ⇒ 少给产出），不会造成多发。
 *
 * <h2>排除清单（防误伤的核心）</h2>
 * <ul>
 *   <li>空气；流体方块（流动水/岩浆，含 {@code level} 属性）——流体会自然蔓延/回缩，端点比对必然抖动；</li>
 *   <li>本模组 8 个方块（{@link ModBlocks} 全套）；</li>
 *   <li><b>随机 tick 方块</b>（雪/冰/树叶腐烂/耕地/菌类等）：S1→S2 之间自行变化，与产线无关。</li>
 * </ul>
 *
 * <h2>⭐ 装置（contraption）不跳过，而是<b>并入点名</b></h2>
 * <p>早期实现"房间内有装置就整场跳过点名"，<b>实测证明这条豁免把修复整个废掉了</b>：
 * 玩家最常用的机器形态就是「轴承 + 钻头」（{@code create:stationary_contraption}），
 * 一装到轴承上就被判"有动态结构" ⇒ 点名永不执行 ⇒ 漏洞照旧。
 * 现在改为把装置<b>内部携带的方块也计入点名</b>（见 {@link #mergeContraptionBlocks}），
 * 这样"方块在房间里"与"方块被装进装置"两种形态都被统计。</p>
 *
 * <h2>关于砍树机</h2>
 * <p>"预置一片森林 → 砍树产线"按本口径会把<b>消失的原木正确地算成输入</b>——这不是误伤，
 * 树本身就是房间库存，允许它无限再生又不计输入才是漏洞。
 * 小额抖动（1~2 个方块）由既有的 {@link EvaluationAudit#belowIntermediateThreshold} 自动滤掉。
 */
final class BlockCensus {

    private BlockCensus() {
    }

    /** 一次方块点名快照：只记"非空且未被排除"的方块状态（位置不参与判定）。 */
    static final class Snapshot {
        static final Snapshot EMPTY = new Snapshot(Map.of());

        private final Map<BlockState, Integer> counts;

        private Snapshot(Map<BlockState, Integer> counts) {
            this.counts = counts;
        }

        boolean isEmpty() {
            return counts.isEmpty();
        }

        int size() {
            return counts.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    /**
     * 采集房间方块清单。
     *
     * @param level  评估副本维度
     * @param bounds 房间外边界（含 Y 轴；边界取整口径与 {@link EvaluationAudit#scan} 完全一致）
     */
    static Snapshot capture(ServerLevel level, AABB bounds) {
        if (level == null || bounds == null) {
            return Snapshot.EMPTY;
        }
        int startX = (int) Math.floor(bounds.minX);
        int startY = (int) Math.floor(bounds.minY);
        int startZ = (int) Math.floor(bounds.minZ);
        int endX = (int) Math.floor(bounds.maxX - 1.0E-5);
        int endY = (int) Math.floor(bounds.maxY - 1.0E-5);
        int endZ = (int) Math.floor(bounds.maxZ - 1.0E-5);

        Map<BlockState, Integer> counts = new HashMap<>();
        Set<Block> ownBlocks = ownBlocks();
        for (int x = startX; x <= endX; x++) {
            for (int y = startY; y <= endY; y++) {
                for (int z = startZ; z <= endZ; z++) {
                    BlockState state = level.getBlockState(new BlockPos(x, y, z));
                    if (!isCounted(state, ownBlocks)) {
                        continue;
                    }
                    counts.merge(state, 1, Integer::sum);
                }
            }
        }
        // 装置（轴承/活塞类）会把方块搬出世界装进实体 NBT —— 不并入的话，
        // 「装在轴承上的钻头机」在点名里等于"房间方块全消失"，反而会把整条产线算成巨额输入。
        mergeContraptionBlocks(level, bounds, counts);
        return counts.isEmpty() ? Snapshot.EMPTY : new Snapshot(Map.copyOf(counts));
    }

    /** 点名是否该统计该方块状态（排除清单的唯一判定入口，新增排除项只改这里）。 */
    private static boolean isCounted(BlockState state, Set<Block> ownBlocks) {
        if (state == null || state.isAir()) {
            return false;
        }
        if (ownBlocks.contains(state.getBlock())) {
            return false;
        }
        // 流体方块：water/lava 与其流动形态都由 level 属性区分，端点比对必然抖动
        if (!state.getFluidState().isEmpty()) {
            return false;
        }
        // 随机 tick 方块：会自行变化（雪融化/冰冻结/树叶腐烂/耕地干湿/菌类扩散），与产线无关
        return !state.isRandomlyTicking();
    }

    private static Set<Block> ownBlocks() {
        Set<Block> own = new HashSet<>();
        own.add(ModBlocks.INPUT.get());
        own.add(ModBlocks.OUTPUT.get());
        own.add(ModBlocks.PARALLEL_INPUT.get());
        own.add(ModBlocks.STRESS_INPUT.get());
        own.add(ModBlocks.STRESS_OUTPUT.get());
        own.add(ModBlocks.IO_EXTENSION.get());
        own.add(ModBlocks.EVALUATOR.get());
        own.add(ModBlocks.FACTORY.get());
        return own;
    }

    /**
     * 计算「起始态有、终态没有」的方块，并折算成掉落物物品。
     *
     * <p>只处理<b>整个状态</b>消失的情形（同 id 不同 blockstate 各自独立成键，
     * 例如 {@code oak_log[axis=x]} 与 {@code [axis=y]} 是两笔），
     * 这样"旋转/翻转同一种方块"不会被误算成消失。</p>
     *
     * <p><b>返回 {@link EvaluationTrace.FlowKey} 而非签名</b>：折算结果直接作为
     * {@code auditRates} / {@code EvaluationReplay.netBalance} 的减法项参与 net 计算，
     * 键必须与 trace、快照回填用的键完全同构，否则那条通道会被算成两条、净额错乱。</p>
     *
     * @return FlowKey → 件数；空表 = 无方块消失或点名应被跳过
     */
    static Map<EvaluationTrace.FlowKey, Long> vanishedAsItems(ServerLevel level,
                                                              Snapshot before, Snapshot after,
                                                              HolderLookup.Provider registries) {
        if (level == null || before == null || after == null || before.isEmpty()) {
            return Map.of();
        }
        if (!shouldRun(level)) {
            return Map.of();
        }
        // 先用签名聚合（展开器只认签名），最后再统一转成 FlowKey
        Map<String, Long> bySignature = new HashMap<>();
        int vanishedStates = 0;
        long vanishedBlocks = 0;
        for (Map.Entry<BlockState, Integer> entry : before.counts.entrySet()) {
            BlockState state = entry.getKey();
            int beforeCount = entry.getValue();
            int afterCount = after.counts.getOrDefault(state, 0);
            int vanished = beforeCount - afterCount;
            if (vanished <= 0) {
                continue;
            }
            vanishedStates++;
            vanishedBlocks += vanished;
            List<ItemStack> drops = dropsOf(level, state);
            if (drops.isEmpty()) {
                continue;
            }
            for (ItemStack drop : drops) {
                if (drop == null || drop.isEmpty()) {
                    continue;
                }
                long amount = ContainerItemExpander.saturatingMultiply(vanished, drop.getCount());
                if (amount <= 0) {
                    continue;
                }
                ContainerItemExpander.addToSnapshot(drop, amount, registries, bySignature);
            }
        }
        if (vanishedBlocks <= 0) {
            return Map.of();
        }
        // 诊断：消失方块的构成（按方块 id 聚合，取前 8 名）。
        // 实测排查时这一行能直接回答"到底哪些方块消失了、各消失多少"，
        // 不必再从 net 反推（0.5.6 首次实测就是因为缺这一行而多绕了一圈）。
        Map<ResourceLocation, Integer> byBlock = new HashMap<>();
        for (Map.Entry<BlockState, Integer> entry : before.counts.entrySet()) {
            int vanished = entry.getValue() - after.counts.getOrDefault(entry.getKey(), 0);
            if (vanished > 0) {
                byBlock.merge(BuiltInRegistries.BLOCK.getKey(entry.getKey().getBlock()), vanished, Integer::sum);
            }
        }
        CreateCMPOR.LOGGER.info("方块点名：消失方块构成 {}", byBlock);
        Map<EvaluationTrace.FlowKey, Long> result = new HashMap<>();
        bySignature.forEach((signature, amount) -> {
            if (signature == null || signature.isEmpty() || amount == null || amount <= 0) {
                return;
            }
            result.merge(EvaluationTrace.FlowKey.item(ItemIdentity.idOf(signature), signature),
                    amount, Long::sum);
        });
        CreateCMPOR.LOGGER.info("方块点名：消失 {} 个方块（{} 种状态）⇒ 折算 {} 种物品 {} 件，明细 {}",
                vanishedBlocks, vanishedStates, result.size(),
                result.values().stream().mapToLong(Long::longValue).sum(), describe(result));
        return result;
    }

    /** 取一个方块状态的掉落物（真实 loot table；空工具口径已核实不会因"缺正确工具"返回空）。 */
    private static List<ItemStack> dropsOf(ServerLevel level, BlockState state) {
        try {
            // 不能传真实坐标：loot 上下文的 ORIGIN 只影响随机序列，不影响原版掉落物种类/数量。
            // blockEntity 传 null：方块已经消失了，且带 BE 的方块（箱子等）本身走的是容器扫描那一路。
            return Block.getDrops(state, level, BlockPos.ZERO, null, null, ItemStack.EMPTY);
        } catch (RuntimeException | LinkageError exception) {
            // 第三方 loot table 抛异常：跳过该方块（偏保守 = 少扣 = 不误伤产出）
            CreateCMPOR.LOGGER.warn("方块点名：方块 {} 掉落物读取失败（{}），已跳过",
                    BuiltInRegistries.BLOCK.getKey(state.getBlock()),
                    exception.getClass().getSimpleName());
            return List.of();
        }
    }

    /**
     * 是否应该执行点名（配置开关）。
     */
    private static boolean shouldRun(ServerLevel level) {
        try {
            return Config.BLOCK_CENSUS_ENABLED.get();
        } catch (RuntimeException ignored) {
            // 配置未加载：按默认开启处理（漏洞默认关闭才是危险方向）
            return true;
        }
    }

    /**
     * 把房间内 <b>Create 装置实体</b> 携带的方块并入点名。
     *
     * <h2>为什么必须并入（0.5.6 修正：早期版本"有装置就跳过"是错的）</h2>
     * <p>装置装配时方块被搬出世界、变成实体 NBT 里的一份 palette；拆解时再搬回。
     * 早期实现选择"房间内检测到装置就整体跳过点名"，理由是怕装配/拆解造成巨额虚假消失。</p>
     *
     * <p><b>但实测证明这条豁免把修复整个废掉了</b>：玩家最常用的机器形态就是
     * 「轴承 + 钻头」这类装置（{@code create:stationary_contraption}），
     * 一装到轴承上就被判"有动态结构" ⇒ 方块点名永远不执行 ⇒ 漏洞照旧。
     * 日志现场：{@code 方块点名跳过：房间内存在动态结构}，随后 {@code net=10} 全额产出。</p>
     *
     * <p><b>正确做法</b>：把装置<b>内部的方块也算进点名</b>。
     * 这样「方块在房间里」与「方块被装进装置」两种形态都被统计，
     * 装置搬运方块不再表现为"消失"，而装置内方块真被钻头消耗掉时仍能被算成输入。</p>
     *
     * <h2>装置 NBT 结构（Create 6.0.10-281 源码核实）</h2>
     * <pre>
     *   Contraption.writeNBT → nbt.put("Blocks", ...)
     *     Blocks.Palette   : ListTag&lt;CompoundTag&gt;  每项是一个 blockstate（NbtUtils.readBlockState 可读）
     *     Blocks.BlockList : ListTag&lt;CompoundTag&gt;  每项 { Pos:long, State:int(调色板下标), Data?:Compound }
     * </pre>
     * <p>（{@code Contraption.readBlocksCompound} / {@code readStructureBlockInfo} 核实。
     * 旧版无 {@code Palette} 键的格式走 {@code Block} 复合，这里一并兼容。）</p>
     */
    static void mergeContraptionBlocks(ServerLevel level, AABB bounds, Map<BlockState, Integer> sink) {
        if (level == null || bounds == null || sink == null) {
            return;
        }
        List<?> contraptions;
        try {
            contraptions = DynamicStructureDetector.findIn(level, bounds);
        } catch (RuntimeException | LinkageError exception) {
            return;
        }
        if (contraptions.isEmpty()) {
            return;
        }
        int merged = 0;
        for (Object entity : contraptions) {
            if (!(entity instanceof net.minecraft.world.entity.Entity rawEntity)) {
                continue;
            }
            merged += mergeOneContraption(rawEntity, sink);
        }
        if (merged > 0) {
            CreateCMPOR.LOGGER.info("方块点名：{} 个装置实体的 {} 个内部方块已并入点名", contraptions.size(), merged);
        }
    }

    /** 读一个装置实体的 NBT，把它携带的方块计入 {@code sink}。返回计入的方块数。 */
    private static int mergeOneContraption(net.minecraft.world.entity.Entity entity,
                                           Map<BlockState, Integer> sink) {
        net.minecraft.nbt.CompoundTag tag;
        try {
            // 用返回值而不是传进去的实例：saveWithoutId 在 vanil 里是原地写并返回同一实例，
            // 但签名允许返回新实例，按返回值写才不会被实现细节坑到。
            tag = entity.saveWithoutId(new net.minecraft.nbt.CompoundTag());
        } catch (RuntimeException | LinkageError exception) {
            return 0;
        }
        if (tag == null || !tag.contains("Contraption", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            return 0;
        }
        net.minecraft.nbt.CompoundTag contraption = tag.getCompound("Contraption");
        if (!contraption.contains("Blocks", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            return 0;
        }
        net.minecraft.nbt.CompoundTag blocks = contraption.getCompound("Blocks");
        List<BlockState> palette = readPalette(blocks, entity);
        if (palette.isEmpty()) {
            return 0;
        }
        net.minecraft.nbt.ListTag blockList = blocks.getList("BlockList",
                net.minecraft.nbt.Tag.TAG_COMPOUND);
        if (blockList.isEmpty()) {
            return 0;
        }
        int counted = 0;
        for (int index = 0; index < blockList.size(); index++) {
            net.minecraft.nbt.CompoundTag entry = blockList.getCompound(index);
            int stateIndex = entry.getInt("State");
            if (stateIndex < 0 || stateIndex >= palette.size()) {
                continue;
            }
            BlockState state = palette.get(stateIndex);
            if (state == null || state.isAir()) {
                continue;
            }
            sink.merge(state, 1, Integer::sum);
            counted++;
        }
        return counted;
    }

    /** 读装置调色板（{@code Blocks.Palette}）；读不到返回空表。 */
    private static List<BlockState> readPalette(net.minecraft.nbt.CompoundTag blocks,
                                                net.minecraft.world.entity.Entity entity) {
        net.minecraft.nbt.ListTag paletteTag = blocks.getList("Palette",
                net.minecraft.nbt.Tag.TAG_COMPOUND);
        if (paletteTag.isEmpty()) {
            return List.of();
        }
        List<BlockState> palette = new ArrayList<>(paletteTag.size());
        var holderGetter = entity.level().holderLookup(
                net.minecraft.core.registries.Registries.BLOCK);
        for (int index = 0; index < paletteTag.size(); index++) {
            try {
                palette.add(net.minecraft.nbt.NbtUtils.readBlockState(
                        holderGetter, paletteTag.getCompound(index)));
            } catch (RuntimeException | LinkageError exception) {
                // 单个状态解析失败：补空气占位以保持下标对齐（跳过它不影响其它方块）
                palette.add(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            }
        }
        return palette;
    }

    private static String describe(Map<EvaluationTrace.FlowKey, Long> items) {
        if (items.isEmpty()) {
            return "无";
        }
        List<String> parts = new ArrayList<>();
        items.entrySet().stream()
                .sorted(Map.Entry.<EvaluationTrace.FlowKey, Long>comparingByValue().reversed())
                .limit(8)
                .forEach(entry -> parts.add(entry.getKey().signature() + "=" + entry.getValue()));
        if (items.size() > 8) {
            parts.add("…共 " + items.size() + " 种");
        }
        return String.join(" ", parts);
    }

    /** 诊断：把方块清单转成"方块 id → 数量"（只给日志用，状态属性被忽略）。 */
    static Map<ResourceLocation, Integer> flatten(Snapshot snapshot) {
        Map<ResourceLocation, Integer> out = new LinkedHashMap<>();
        if (snapshot == null) {
            return out;
        }
        snapshot.counts.forEach((state, count) ->
                out.merge(BuiltInRegistries.BLOCK.getKey(state.getBlock()), count, Integer::sum));
        return out;
    }
}
