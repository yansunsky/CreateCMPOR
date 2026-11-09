package com.yansunsky.createcmpor.evaluation;

import com.simibubi.create.content.logistics.box.PackageItem;
import com.yansunsky.createcmpor.Config;
import com.yansunsky.createcmpor.CreateCMPOR;
import com.yansunsky.createcmpor.compat.inventory.ContainerItemExpander;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Map;

/**
 * 实体态包裹（Create {@code create:package}）的副本处理。
 *
 * <h2>为什么实体态包裹必须单独处理（0.5.6 修复的物品复制漏洞）</h2>
 * <p>Create 的 {@code PackageItem.hasCustomEntity()} 返回 {@code true}，掉落时会变成
 * <b>{@code PackageEntity}</b>（继承 {@code LivingEntity}，<b>不是</b> {@code ItemEntity}）。
 * 而本模组的两条库存通道都不认它：</p>
 * <ul>
 *   <li>{@link EvaluationAudit#scan} 的地板段只查 {@code ItemEntity.class} ⇒ 实体包裹=零可见库存；</li>
 *   <li>方块容器能力也拿不到它（它不在任何槽里）。</li>
 * </ul>
 * <p>于是「往副本地上丢一堆装着物品的包裹 → 拆开 → 走输出方块」全程扫描看不到任何库存，
 * 物品凭空成为净产出 —— 与包裹物品逃逸是同一个漏洞的两个面。</p>
 *
 * <h2>两种策略（{@link Config.PackageEntityPolicy}）</h2>
 * <ul>
 *   <li><b>DELETE（默认）</b>：在副本维度生成后、S0 扫描前把它们清掉。
 *       彻底断源，也顺带清掉"用包裹实体搬运物品"这条绕过路径。</li>
 *   <li><b>COUNT</b>：不删除，把包裹内部的物品<b>计入快照</b>（走
 *       {@link ContainerItemExpander#addToSnapshot}，与容器内包裹同口径）。
 *       保留"用实体包裹跑物流"的产线，守恒由三扫描天然保证
 *       （S1 有、拆开后 S2 无 ⇒ net&lt;0 ⇒ 自动记为输入）。</li>
 * </ul>
 *
 * <h2>时机</h2>
 * <p>必须与「净化启动棒」同位置：副本区块已切到 ENTITY_TICKING、且<b>在 S0 扫描之前</b>。
 * 与净化不同，本处理<b>无条件执行</b>（净化只是可选路径，包裹漏洞不是可选项）。
 *
 * <h2>为什么用类名判定而不是直接 instanceof</h2>
 * <p>同 {@link DynamicStructureDetector}：直接引用会让 Create 的实体类在类加载期被强解析。
 * 这里改用 {@link PackageItem#isPackage} 的<b>物品</b>判定（Create 的 {@code PackageItem::isPackage}
 * 是既有的稳定 API，本类已经依赖 {@code PackageItem}），再配合实体 id 校验，
 * 避免额外解析 {@code PackageEntity}。
 */
final class CreatePackageEntities {

    /** Create 包裹实体的注册名（{@code AllEntityTypes.PACKAGE}: {@code register("package", ...)}）。 */
    private static final ResourceLocation PACKAGE_ENTITY_ID =
            ResourceLocation.fromNamespaceAndPath("create", "package");

    private CreatePackageEntities() {
    }

    /** 处理结果（供日志/诊断引用）。 */
    record Stats(int entities, long items, boolean counted) {
        static Stats none() {
            return new Stats(0, 0, false);
        }
    }

    /**
     * 按当前配置处理副本房间内的实体态包裹。
     *
     * @param level  评估副本维度
     * @param bounds 房间外边界（{@code RoomInstance.boundaries().outerBounds()}，含 Y 轴）
     * @return 处理统计；无包裹或策略异常时返回 {@link Stats#none()}
     */
    static Stats apply(ServerLevel level, AABB bounds) {
        if (level == null || bounds == null) {
            return Stats.none();
        }
        Config.PackageEntityPolicy policy;
        try {
            policy = Config.PACKAGE_ENTITY_POLICY.get();
        } catch (RuntimeException ignored) {
            // 配置未加载（理论上不成立）：按最安全的"删除"执行，绝不放行漏洞
            policy = Config.PackageEntityPolicy.DELETE;
        }
        if (policy == null) {
            policy = Config.PackageEntityPolicy.DELETE;
        }

        List<? extends Entity> entities = level.getEntities((Entity) null,
                bounds, entity -> isPackageEntity(level, entity));
        if (entities.isEmpty()) {
            return Stats.none();
        }

        int count = 0;
        long items = 0;
        for (Entity entity : entities) {
            ItemStack box = boxOf(entity);
            items += countContents(box);
            if (policy == Config.PackageEntityPolicy.DELETE) {
                entity.discard();
            }
            count++;
        }

        if (policy == Config.PackageEntityPolicy.COUNT) {
            // COUNT 模式不改世界：真正的内容计入发生在 EvaluationAudit.scan → countIntoSnapshot，
            // 那才是与 S0/S1/S2 同一次快照的口径（在这里计入会与 scan 的快照不同步）。
            CreateCMPOR.LOGGER.info("评估副本：发现 {} 个实体态包裹（内部物品 {} 件）；"
                    + "COUNT 策略 ⇒ 不删除，内容物已计入三扫描快照", count, items);
            return new Stats(count, items, true);
        }
        CreateCMPOR.LOGGER.info("评估副本：已删除 {} 个实体态包裹（内部物品 {} 件；DELETE 策略，防包裹逃逸）",
                count, items);
        return new Stats(count, items, false);
    }

    /**
     * COUNT 策略下把实体态包裹的内容物并入快照。
     *
     * <p>必须在 {@link EvaluationAudit#scan} 的<b>同一次快照</b>里做，否则会出现
     * "S1 记了、S2 没记"的口径漂移。故由 {@code EvaluationAudit.scan} 直接调用，
     * 而 {@link #apply} 只负责 DELETE。</p>
     */
    static void countIntoSnapshot(ServerLevel level, AABB bounds, Map<String, Long> items) {
        if (level == null || bounds == null || items == null) {
            return;
        }
        for (Entity entity : level.getEntities((Entity) null, bounds,
                candidate -> isPackageEntity(level, candidate))) {
            ItemStack box = boxOf(entity);
            if (box.isEmpty()) {
                continue;
            }
            ContainerItemExpander.addToSnapshot(box, box.getCount(), level.registryAccess(), items);
        }
    }

    /** 判定是否为 Create 包裹实体：实体 id 精确匹配。 */
    private static boolean isPackageEntity(ServerLevel level, Entity entity) {
        if (entity == null || entity.isRemoved()) {
            return false;
        }
        ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                .getKey(entity.getType());
        return PACKAGE_ENTITY_ID.equals(id);
    }

    /**
     * 取实体包裹里的「盒子」物品（Create {@code PackageEntity.box}）。
     *
     * <p>反射读取而不直接引用 {@code PackageEntity}：字段是 {@code public ItemStack box}，
     * 但引用该类会带来额外的类加载期强依赖。读不到就返回空（本实体随后按"无内容"处理）。</p>
     */
    private static ItemStack boxOf(Entity entity) {
        Class<?> type = entity.getClass();
        while (type != null && type != Object.class) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField("box");
                field.setAccessible(true);
                Object value = field.get(entity);
                if (value instanceof ItemStack stack && !stack.isEmpty() && PackageItem.isPackage(stack)) {
                    return stack;
                }
                return ItemStack.EMPTY;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (ReflectiveOperationException | RuntimeException exception) {
                CreateCMPOR.LOGGER.debug("实体态包裹内容读取失败（{}）：{}",
                        entity.getType(), exception.getClass().getSimpleName());
                return ItemStack.EMPTY;
            }
        }
        return ItemStack.EMPTY;
    }

    /** 统计包裹内部物品件数（仅用于日志与统计上报，不参与判定）。 */
    private static long countContents(ItemStack box) {
        if (box.isEmpty()) {
            return 0;
        }
        try {
            var handler = PackageItem.getContents(box);
            long total = 0;
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack stack = handler.getStackInSlot(slot);
                if (!stack.isEmpty()) {
                    total += stack.getCount();
                }
            }
            return total;
        } catch (RuntimeException | LinkageError ignored) {
            return 0;
        }
    }
}
