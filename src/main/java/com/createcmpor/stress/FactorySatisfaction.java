package com.createcmpor.stress;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工厂「应力是否已满足」的运行期状态（非持久化）。
 *
 * <p>对于内部消耗应力（CONSUME）的工厂：默认未满足 → 工厂不工作（由 {@code FactoryTickMixin} 拦截）；
 * 当相邻应力拓展方块链成功从外部 Create 网络获得足够应力并供给后，标记为已满足 → 工厂恢复工作。
 *
 * <p>键为「维度 + 工厂坐标」，值为最近一次被标记满足的游戏时间（tick）。超过 {@link #TTL_TICKS}
 * 未刷新视为不再满足（拓展方块被移除/网络断流时自动失效）。
 */
public class FactorySatisfaction {

    /** 满足状态的有效期（tick）。拓展方块每秒刷新一次，给 3 秒容差。 */
    private static final long TTL_TICKS = 60L;

    private static final Map<String, Long> SATISFIED = new ConcurrentHashMap<>();

    private static String key(ResourceKey<Level> dim, BlockPos pos) {
        return dim.location() + "@" + pos.asLong();
    }

    /** 标记某工厂的应力需求已被满足（拓展方块每秒调用）。 */
    public static void markSatisfied(ResourceKey<Level> dim, BlockPos factoryPos, long gameTime) {
        SATISFIED.put(key(dim, factoryPos), gameTime);
    }

    /** 查询某工厂的应力需求当前是否满足。 */
    public static boolean isSatisfied(ResourceKey<Level> dim, BlockPos factoryPos, long gameTime) {
        Long t = SATISFIED.get(key(dim, factoryPos));
        return t != null && (gameTime - t) <= TTL_TICKS;
    }
}
