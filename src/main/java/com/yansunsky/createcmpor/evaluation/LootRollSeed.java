package com.yansunsky.createcmpor.evaluation;

import net.minecraft.world.entity.Entity;

import java.util.UUID;

/**
 * 掉落表摇取用的<b>稳定种子</b>。
 *
 * <h2>为什么必须自己造种子（踩坑级）</h2>
 * <p>{@code LootContext.Builder.withOptionalRandomSeed(seed)} 的实现是
 * {@code if (seed != 0L) { this.random = RandomSource.create(seed); }} ——
 * <b>传 0 等于什么都没做</b>，随后 {@code create(...)} 会回退到
 * {@code serverlevel.getRandom()}（全维度共享的随机源）。</p>
 *
 * <p>而 {@code LivingEntity.getLootTableSeed()} 的实现是 {@code return 0L;}（常量，不是字段）。
 * 于是「直接拿 {@code getLootTableSeed()} 去摇掉落表」每次都走维度随机源，
 * <b>同一只动物在 S1 和 S2 会摇出不同的数量</b>。</p>
 *
 * <h2>不稳定的直接后果：凭空制造减法项</h2>
 * <p>实体掉落点名把「S1 预期掉落 − S2 预期掉落」当作独立减法项 {@code L}。
 * 若摇取不稳定，即使房间里<b>一只动物都没少</b>，S1 与 S2 的随机结果也会不相等，
 * 于是算出一个本来不存在的 {@code L}：

 * <ul>
 *   <li>它会污染净额（把正常产线的产出错扣成消耗）；</li>
 *   <li>它非空 ⇒ 还会连带禁用「S0 全空简化路径」（{@code hasCensusDeduction}），
 *       把一条本来走简化路径的正常评估硬拽进完整三扫描。</li>
 * </ul>
 * <p>也就是说：<b>种子不稳定不是"小幅抖动"，而是"配置一开就随机误判"。</b></p>
 *
 * <h2>本类的口径</h2>
 * <ul>
 *   <li>实体自带非零种子（容器矿车/箱子船走 {@code lootTableSeed} 字段）→ <b>原样用</b>，
 *       这样计数与玩家真开箱拿到的内容逐件一致；</li>
 *   <li>种子为 0（{@code LivingEntity} 恒如此，以及少数未设种子的容器）→ 用<b>实体 UUID 派生</b>的
 *       稳定非零种子。同一实体在 S1/S2/重试之间恒定 ⇒ 同一只活着的动物贡献恒为 0 差。</li>
 * </ul>
 *
 * <p>UUID 是最自然的稳定标识：它随实体写入区块 NBT，在评估副本里与源房间一致，
 * 且在 S1→S2 期间不变。派生时混入一个奇常数，避免相邻 UUID 产生相邻种子后
 * 在随机序列上表现出相关性。</p>
 */
final class LootRollSeed {

    /** 黄金比例奇常数（Knuth 混合常数）；用来打散 UUID 低位规律。 */
    static final long MIX = 0x9E3779B97F4A7C15L;

    /** 兜底种子：极端情况下（UUID 全 0）也必须给一个非零值，否则又退回维度随机源。 */
    private static final long FALLBACK = 1L;

    private LootRollSeed() {
    }

    /**
     * 取该实体本次摇取应使用的种子。
     *
     * @param entity 目标实体（{@code null} 时返回兜底种子）
     * @param seed   实体自带的种子；<b>非 0 时原样返回</b>
     */
    static long of(Entity entity, long seed) {
        return seed != 0L ? seed : derive(entity);
    }

    /**
     * 由实体 UUID 派生稳定非零种子。
     *
     * <p>只依赖 UUID，不含任何时间/随机成分 ⇒ 同一实体在任何一次扫描里结果相同。</p>
     */
    static long derive(Entity entity) {
        if (entity == null) {
            return FALLBACK;
        }
        UUID uuid = entity.getUUID();
        long mixed = (uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits()) * MIX;
        return mixed == 0L ? FALLBACK : mixed;
    }
}
