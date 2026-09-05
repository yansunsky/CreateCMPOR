package com.yansunsky.createcmpor.evaluation;

import com.yansunsky.createcmpor.CreateCMPOR;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 并行评估使用的固定独立维度。
 *
 * <p>每分支使用一个独立 {@link ServerLevel}，并在同一源房间坐标内发布副本，
 * 因此不引入 eval_world 空间偏移和坐标改写。Create 铁路、精妙背包等全局 UUID
 * 内容仍会在进入并行模式前被检测并整场退回串行分支。</p>
 */
public final class ParallelEvaluationWorlds {
    /** 并行评估固定 lane 数量；超过此数量自动退回串行分支。 */
    public static final int LANE_COUNT = 8;
    private static final String PATH_PREFIX = "parallel_eval_";

    private static final List<ResourceKey<Level>> LANES = java.util.stream.IntStream.range(0, LANE_COUNT)
            .mapToObj(ParallelEvaluationWorlds::laneKey)
            .toList();

    private ParallelEvaluationWorlds() {
    }

    public static ResourceKey<Level> lane(int index) {
        if (index < 0 || index >= LANE_COUNT) {
            throw new IllegalArgumentException("parallel lane out of range: " + index);
        }
        return LANES.get(index);
    }

    public static boolean isLane(ResourceKey<Level> dimension) {
        return laneIndex(dimension) >= 0;
    }

    /** 判断是否任意评估维度（主 eval_world 或并行 lane）。 */
    public static boolean isAnyEvaluationWorld(ResourceKey<Level> dimension) {
        return CreateCMPOR.EVAL_WORLD.equals(dimension) || isLane(dimension);
    }

    public static int laneIndex(ResourceKey<Level> dimension) {
        ResourceLocation location = dimension.location();
        if (!CreateCMPOR.MOD_ID.equals(location.getNamespace())
                || !location.getPath().startsWith(PATH_PREFIX)) {
            return -1;
        }
        String suffix = location.getPath().substring(PATH_PREFIX.length());
        try {
            int index = Integer.parseInt(suffix);
            return index >= 0 && index < LANE_COUNT ? index : -1;
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private static ResourceKey<Level> laneKey(int index) {
        return ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath(CreateCMPOR.MOD_ID, PATH_PREFIX + index));
    }
}
