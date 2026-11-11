package com.yansunsky.createcmpor.evaluation;

import com.yansunsky.createcmpor.Config;
import com.yansunsky.createcmpor.CreateCMPOR;
import com.yansunsky.createcmpor.compat.inventory.ContainerItemExpander;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实体掉落点名：把预养活体生物折算成<b>预期掉落</b>库存。
 *
 * <h2>为什么必须做（用户实测确认的物品复制玩法）</h2>
 * <p>房间里预先繁殖一大批动物（牛羊猪鸡等），评估中开启自动屠宰机把它们全杀掉，
 * 掉落物走输出方块。活体动物是房间库存，但<b>不是任何容器</b>——
 * 旧扫描完全看不见它们，于是「把动物换成战利品」表现为<b>没有输入的纯产出</b>，
 * 固化出一台不需要任何原料、只吐战利品的机器。</p>
 *
 * <h2>⭐ 产品决策：这是可选防护，<b>默认关闭</b></h2>
 * <p>这个玩法本身是有趣的，而且大量活体实体确实会拖慢服务器，
 * 玩家用这种方式把它们消费掉是可接受的；何况动物是玩家自己繁殖出来的，不是无中生有。
 * 因此做成配置开关 {@code anticheat.entityLootCensusEnabled}，<b>默认 false</b>
 * （=默认不防护、保留玩法）。开启后按下述口径防护。</p>
 *
 * <h2>口径：与 {@link BlockCensus} 同构的独立减法项</h2>
 * <p>设 {@code L} = 评估窗口内消失的活体生物的预期掉落折算量，则</p>
 * <pre>
 *   net = (S2 - S1) - V - L + IO输出 - IO输入
 * </pre>
 * <p>一条公式覆盖两种形态（与方块点名同理）：</p>
 * <ul>
 *   <li>产物直接出界：{@code (0-0) + D - L = 0}（{@code D} 为实际掉落，约等于 {@code L}）✅</li>
 *   <li>产物留在容器：{@code (D-0) + 0 - L = 0} ✅ 不重复计算</li>
 * </ul>
 *
 * <h2>⭐ 为什么按「每只实体各摇一次」而不是「按类型取均值」</h2>
 * <p>两种写法都是<b>无偏</b>的（{@code E[L] = E[D]}），差别在别处，且结论与直觉相反：</p>
 * <ul>
 *   <li><b>按类型取均值会丢掉每只动物的状态</b>，这是<b>正确性问题</b>而非精度问题：
 *       掉落表里有 {@code THIS_ENTITY} 谓词（{@code is_on_fire} 决定牛肉变牛排、
 *       {@code equipment} 谓词等）。用一只"代表个体"算全类型，
 *       一旦群里有动物着火，就会按生肉计数、而实际产出是熟肉 ⇒
 *       <b>物品签名对不上，L 完全抵消不掉</b>，比统计误差严重得多。</li>
 *   <li><b>逐实体摇取天然逐只反映其真实状态</b>，且与「这头牛被宰时会掉什么」是同分布。</li>
 * </ul>
 * <p>代价是每次扫描的摇取次数 = 活体数量（500 头牛 = 500 次）。
 * 实测这是可接受的：掉落表求值是纯内存计算，远小于同规模区块/容器扫描；
 * 且该扫描只在 S0/S1/S2 各跑一次，不在每 tick 路径上。</p>
 *
 * <h2>⭐⭐ 种子必须是稳定的（否则"配置一开就随机误判"）</h2>
 * <p>这是本类最容易被写错的一处：{@code LivingEntity.getLootTableSeed()} 的实现是
 * {@code return 0L;}（常量，不是字段），而
 * {@code LootContext.Builder.withOptionalRandomSeed(0)} 是<b>空操作</b>
 * （{@code if (seed != 0L)}），随后 {@code create(...)} 会回退到
 * {@code serverlevel.getRandom()}（全维度共享随机源）。</p>
 * <p>后果不是"小幅抖动"：同一只活着的动物在 S1 与 S2 会摇出不同数量，
 * 于是<b>即使一只都没死</b>，{@code L = S1 − S2} 也会非零 ——
 * 既污染净额，又因为 {@code L} 非空而连带禁用「S0 全空简化路径」，
 * 把本该走简化路径的正常评估硬拽进完整三扫描。</p>
 * <p>故一律经 {@link LootRollSeed} 取种子：实体自带非零种子时原样用，
 * 为 0 时用 UUID 派生的稳定非零值。这样「同一只动物 + 同一次扫描点」结果恒定，
 * 存活个体贡献的差值恒为 0，只有真正消失的个体才会产生 {@code L}。</p>
 *
 * <h2>与原版掉落语义对齐的两处细节</h2>
 * <ul>
 *   <li><b>幼体排除</b>：{@code LivingEntity.shouldDropLoot()} 的实现就是
 *       {@code return !this.isBaby();}（{@code isBaby()} 声明在 {@code LivingEntity}，
 *       由 {@code AgeableMob} 覆写）。幼年动物被杀不掉落表产物，故不折算——
 *       这是<b>对齐原版</b>，不是额外发明。</li>
 *   <li><b>参数集</b>：与 {@code LivingEntity.dropFromLootTable} 一致用 {@code ENTITY}
 *       （必填 THIS_ENTITY / ORIGIN / DAMAGE_SOURCE）。没有玩家参与，
 *       故不传 {@code LAST_DAMAGE_PLAYER}、不设幸运值。</li>
 * </ul>
 *
 * <h2>残留误差（诚实说明，不可消除）</h2>
 * <p>实际掉落 {@code D} 是评估期间真发生的事件，我们只能预测 {@code L}。
 * 即使同分布，{@code D − L} 仍有 {@code √(2N)·σ} 量级的残差（{@code N} = 屠宰数量）。
 * 这是"不追踪实际掉落就无法消除"的固有误差，方向随机（可能多给也可能少给），
 * 与"完全看不见"时的 100% 漏算是本质区别。小额残差由既有的中间产物门槛滤除。</p>
 */
final class EntityLootCensus {

    private EntityLootCensus() {
    }

    /**
     * 采集房间内活体生物的预期掉落。
     *
     * @param level  评估副本维度
     * @param bounds 房间外边界（含 Y 轴，与其它库存段落同一边界）
     * @return 签名 → 件数；配置关闭或无活体时返回空表（不改变任何既有行为）
     */
    static Map<String, Long> livingLootSnapshot(ServerLevel level, AABB bounds) {
        if (!shouldRun() || level == null || bounds == null) {
            return Map.of();
        }
        if (level.getServer() == null || level.getServer().reloadableRegistries() == null) {
            return Map.of();
        }
        List<LivingEntity> entities = level.getEntitiesOfClass(LivingEntity.class, bounds,
                entity -> !(entity instanceof Player) && !entity.isRemoved() && entity.isAlive());
        if (entities.isEmpty()) {
            return Map.of();
        }

        HolderLookup.Provider registries = level.registryAccess();
        Map<String, Long> items = new HashMap<>();
        Map<ResourceLocation, Integer> entityCounts = new LinkedHashMap<>();
        Map<ResourceLocation, Long> itemCounts = new HashMap<>();
        int totalEntities = 0;
        long totalItems = 0;
        for (LivingEntity living : entities) {
            // 幼体不折算：对齐 LivingEntity.shouldDropLoot() 的 !isBaby() 语义
            if (living.isBaby()) {
                continue;
            }
            Map<String, Long> entityItems = new HashMap<>();
            try {
                countOne(level, living, registries, entityItems);
            } catch (RuntimeException | LinkageError exception) {
                // 第三方掉落表抛异常：跳过该个体（偏保守，绝不把评估打崩）
                CreateCMPOR.LOGGER.warn("实体掉落点名：实体 {} 掉落表摇取失败（{}），已跳过",
                        BuiltInRegistries.ENTITY_TYPE.getKey(living.getType()),
                        exception.getClass().getSimpleName());
                continue;
            }
            if (entityItems.isEmpty()) {
                continue;
            }
            long amount = entityItems.values().stream().mapToLong(Long::longValue).sum();
            if (amount <= 0) {
                continue;
            }
            entityItems.forEach((signature, count) -> items.merge(signature, count, Long::sum));
            ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(living.getType());
            entityCounts.merge(typeId, 1, Integer::sum);
            itemCounts.merge(typeId, amount, Long::sum);
            totalEntities++;
            totalItems += amount;
        }
        if (totalEntities <= 0 || items.isEmpty()) {
            return Map.of();
        }
        Map<ResourceLocation, String> details = new LinkedHashMap<>();
        entityCounts.forEach((entityId, count) ->
                details.put(entityId, count + "只(" + itemCounts.get(entityId) + "件)"));
        CreateCMPOR.LOGGER.info("实体掉落点名：{} 只活体生物，折算 {} 件预期掉落，明细 {}",
                totalEntities, totalItems, details);
        return Map.copyOf(items);
    }

    /**
     * 单只活体生物的预期掉落（用其自身状态与稳定种子各摇一次）。
     *
     * @param sink 输出签名 → 件数；该个体失败时整笔丢弃，避免半笔入账
     */
    private static void countOne(ServerLevel level, LivingEntity living,
                                 HolderLookup.Provider registries, Map<String, Long> sink) {
        ResourceKey<LootTable> key = living.getLootTable();
        if (key == null) {
            return;
        }
        LootTable table = level.getServer().reloadableRegistries().getLootTable(key);
        if (table == null || table == LootTable.EMPTY) {
            return;
        }
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.THIS_ENTITY, living)
                .withParameter(LootContextParams.ORIGIN, living.position())
                .withParameter(LootContextParams.DAMAGE_SOURCE, living.damageSources().generic())
                .withOptionalParameter(LootContextParams.ATTACKING_ENTITY, null)
                .withOptionalParameter(LootContextParams.DIRECT_ATTACKING_ENTITY, null)
                .create(LootContextParamSets.ENTITY);
        // 稳定种子：LivingEntity.getLootTableSeed() 恒为 0，直接传会回退到维度共享随机源，
        // 使同一只存活动物在 S1/S2 摇出不同结果（详见 LootRollSeed 与类注释）。
        long seed = LootRollSeed.of(living, living.getLootTableSeed());
        table.getRandomItems(params, seed, stack -> {
            if (stack != null && !stack.isEmpty()) {
                ContainerItemExpander.addToSnapshot(stack, stack.getCount(), registries, sink);
            }
        });
    }

    /**
     * 把「基线有、终态没有」的预期掉落转成与 IO trace 同构的独立减法项 {@code L}。
     *
     * <p>无预热时基线回退 S0（与 RATE/REPLAY 的 baseline 选择一致）。</p>
     */
    static Map<EvaluationTrace.FlowKey, Long> vanishedAsItems(Map<String, Long> before,
                                                              Map<String, Long> after) {
        if (!shouldRun() || before == null || after == null || before.isEmpty()) {
            return Map.of();
        }
        Map<EvaluationTrace.FlowKey, Long> result = new HashMap<>();
        before.forEach((signature, count) -> {
            long vanished = count - after.getOrDefault(signature, 0L);
            if (vanished > 0) {
                result.put(EvaluationTrace.FlowKey.item(ItemIdentity.idOf(signature), signature), vanished);
            }
        });
        return result.isEmpty() ? Map.of() : Map.copyOf(result);
    }

    /** 配置未加载时同样按默认关闭处理：不改变快照、不输出日志、不影响简化路径判据。 */
    private static boolean shouldRun() {
        try {
            return Config.ENTITY_LOOT_CENSUS_ENABLED.get();
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
