package com.yansunsky.createcmpor.evaluation;

import com.yansunsky.createcmpor.CreateCMPOR;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * 动态结构（机械动力装置 / 移动结构）检测。
 *
 * <p><b>用途</b>：净化启动棒评估前若房间内存在动态结构，整体回退为普通评估
 * （装置内部的库存要跟方块库存双向同步，本次不做支持，见 {@code EvaluationScheduler#applyPurification}）。</p>
 *
 * <p><b>判定语义是"房间内"而不是"容器内"</b>：装置可以停在房间任意位置，
 * 只查容器会漏判。</p>
 *
 * <p><b>为什么用基类而不是枚举具体子类</b>：Create 6.0.10-281 里
 * {@code AbstractContraptionEntity} 恰好有 4 个具体子类
 * （{@code OrientedContraptionEntity} 及其子类 {@code CarriageContraptionEntity}、
 * {@code ControlledContraptionEntity}、{@code GantryContraptionEntity}，
 * 对应实体 id {@code create:contraption} / {@code create:carriage_contraption} /
 * {@code create:stationary_contraption} / {@code create:gantry_contraption}），
 * 且附属模组可以自行登记新的装置实体。查公共父类一次覆盖全部。
 * （注意：<b>不存在</b>名为 {@code ContraptionEntity} 的类——别按想象写类名。）</p>
 *
 * <p><b>反射加载</b>：Create 是本模组的必需依赖，正常一定能加载成功；但"依赖存在"和
 * "类能被解析"是两回事（版本裁剪 jar、类被移走都会让直接引用变成
 * {@code NoClassDefFoundError}——一旦发生在评估启动路径上就是整场崩溃）。
 * 因此这里只在首次调用时解析一次类，解析失败就退化为"查不到动态结构"并留一条警告，
 * 绝不让净化把评估拖崩。</p>
 */
final class DynamicStructureDetector {

    /** 解析结果：null = 已尝试且失败（以后不再尝试）。 */
    private static Class<?> contraptionEntityClass;
    private static boolean resolutionAttempted = false;

    private DynamicStructureDetector() {
    }

    /**
     * 找出房间内的动态结构实体。
     *
     * @param level  评估副本所在维度
     * @param bounds 房间外边界（含 Y 轴）
     * @return 命中的实体（空列表 = 无动态结构，或 Create 类不可用而检测被跳过）
     */
    static List<?> findIn(ServerLevel level, AABB bounds) {
        Class<?> type = resolveContraptionEntityClass();
        if (type == null) {
            return List.of();
        }
        return new ArrayList<>(level.getEntitiesOfClass(type.asSubclass(
                net.minecraft.world.entity.Entity.class), bounds));
    }

    private static Class<?> resolveContraptionEntityClass() {
        if (!resolutionAttempted) {
            resolutionAttempted = true;
            try {
                contraptionEntityClass = Class.forName(
                        "com.simibubi.create.content.contraptions.AbstractContraptionEntity");
            } catch (ClassNotFoundException | LinkageError exception) {
                contraptionEntityClass = null;
                CreateCMPOR.LOGGER.warn("净化：找不到 Create 装置实体基类，动态结构检测已跳过（净化仍会执行）：{}",
                        exception.getClass().getSimpleName());
            }
        }
        return contraptionEntityClass;
    }
}
