package com.yansunsky.createcmpor.client.preview;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yansunsky.createcmpor.CreateCMPOR;
import com.yansunsky.createcmpor.preview.PreviewSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 微缩预览里的<b>实体</b>（v4 实体表）：烘焙期在微缩虚拟世界里重建，渲染期逐只交给
 * {@link EntityRenderDispatcher} 画出来。
 *
 * <h3>为什么这条路能走通（源码核实，非推测）</h3>
 * <ul>
 *     <li>重建：{@code EntityType.byString(id) + create(虚拟世界) + load(裁剪 NBT)}——
 *         完全不碰服务端，也不需要实体"被加入世界"；</li>
 *     <li>绘制：{@code EntityRenderDispatcher.render(entity, x,y,z, rotationYaw, partialTicks,
 *         poseStack, buffers, packedLight)}（{@code EntityRenderDispatcher.java:142}）——
 *         <b>唯一</b>的重载（1.21.1 不存在"不带 rotationYaw 的 8 参重载"，转述里那条不成立）。
 *         它<b>不查</b> {@code shouldRender}、也<b>不自行取光</b>：光照由调用方给，
 *         与 {@link PreviewRender} 的既有口径一致（渲染期 {@code packedLight}）。</li>
 *     <li>Flywheel 不参与：微缩世界 {@code supportsVisualization() == false}，
 *         Create 的 legacy 渲染分支对实体渲染器本来就走 vanilla 路径。</li>
 * </ul>
 *
 * <h3>必须遵守的四条硬约束（每一条都对应一段源码）</h3>
 * <ol>
 *     <li><b>绝不调 {@code dispatcher.prepare(...)}</b>：它会改全局的
 *         {@code level}/{@code camera}/{@code crosshairPickEntity}，污染本帧其他实体的渲染。</li>
 *     <li><b>必须先判空 renderer</b>：{@code getRenderer} 可能返回 {@code null}（该类型没注册渲染器），
 *         而 {@code render} 第一行就调 {@code entityrenderer.getRenderOffset(...)} → 直接 NPE，
 *         且它把任何 {@code Throwable} 包成 {@code ReportedException} <b>抛给调用方</b>
 *         （{@code EntityRenderDispatcher.java:184-194}）——在 BER 里冒泡就是崩客户端。</li>
 *     <li><b>渲染期关阴影</b>：阴影走的是 {@code this.level}（dispatcher 自己的世界 = 真实客户端世界）
 *         在"实体的坐标"处采样方块，而我们的实体坐标是<b>快照局部格坐标</b>，采到的完全是别的方块。
 *         {@code shouldRenderShadow} 默认 {@code true} 且原版没有任何地方改它，故此处
 *         "设 false → 画完设回 true"等价于恢复默认值（已核实全工程仅此一处 setter 调用点）。
 *         附带事实：阴影那块还有 {@code 1 - distanceToSqr/256} 的距离衰减，微缩实体的坐标
 *         离摄像机通常很远 → 本来也画不出来；这里显式关闭只是为了确定性。</li>
 *     <li><b>逐只 {@code try/catch(Throwable)} + 按类型锁存</b>：某个第三方渲染器抛异常时，
 *         只把<b>该类型</b>永久跳过（沿用 {@link PreviewDynamicParts} 的成熟做法），
 *         其余实体与方块内容照常绘制。</li>
 * </ol>
 *
 * <h3>已知取舍</h3>
 * <ul>
 *     <li><b>静态姿态</b>：重建即冻结 → 走路动画/随时间变化的局部动画只会显示默认姿态
 *         （产品定位就是"静态雕像"，不是缺陷）；</li>
 *     <li><b>不画玩家与装置</b>：玩家渲染依赖 {@code PlayerInfo}/皮肤（客户端造不出来），
 *         装置（contraption）需要单独重建 —— 两者在采集侧就被排除了；</li>
 *     <li><b>生命周期</b>：本对象持有的 {@link Entity} 与其虚拟世界只被 {@link PreviewBaked} 引用，
 *         记录被缓存淘汰/替换后整条引用链自动可回收（不注册到任何全局表）。</li>
 * </ul>
 */
public final class PreviewEntityScene {

    /** 没有实体的空场景（渲染侧零分支）。 */
    public static final PreviewEntityScene EMPTY = new PreviewEntityScene(List.of());

    /**
     * 已知会失败的类型（会话级锁存）：避免每帧重复抛异常刷日志。
     * 与 {@link PreviewDynamicParts#markFailed} 同一思路，但按<b>实体类型</b>而不是方块状态。
     */
    private static final Set<String> FAILED_TYPES = new HashSet<>();

    private final List<Placed> placed;

    private PreviewEntityScene(List<Placed> placed) {
        this.placed = List.copyOf(placed);
    }

    /** 一只已重建的实体及其在<b>快照局部格坐标系</b>里的位置。 */
    private record Placed(Entity entity, float x, float y, float z, String type) {
    }

    /**
     * 在微缩虚拟世界里重建整张实体表。
     *
     * <p>任何失败都<b>只影响该只实体</b>：造不出来就跳过（记一次 debug），绝不冒泡、绝不空指针。
     * 全部失败时返回 {@link #EMPTY}。
     */
    public static PreviewEntityScene build(Level virtualWorld, List<PreviewSnapshot.EntityRecord> records) {
        if (records.isEmpty()) {
            return EMPTY;
        }
        List<Placed> built = new ArrayList<>(records.size());
        for (PreviewSnapshot.EntityRecord record : records) {
            if (FAILED_TYPES.contains(record.type())) {
                continue;
            }
            try {
                EntityType<?> type = EntityType.byString(record.type()).orElse(null);
                if (type == null) {
                    FAILED_TYPES.add(record.type());
                    continue;
                }
                Entity entity = type.create(virtualWorld);
                if (entity == null) {
                    FAILED_TYPES.add(record.type());
                    continue;
                }
                // load 会读 Pos/Rotation/Motion…；我们随后用快照里那三个量化值覆盖位置与朝向，
                // 保证"实体坐标"与"绘制坐标"完全一致（有些渲染器会读 entity.position()）。
                entity.load(record.data());
                entity.setPos(record.x(), record.y(), record.z());
                entity.setYRot(record.yaw());
                entity.setXRot(record.pitch());
                entity.xRotO = record.pitch();
                entity.yRotO = record.yaw();
                entity.setDeltaMovement(0.0D, 0.0D, 0.0D);
                if (entity instanceof LivingEntity living) {
                    // 头/身体朝向由 yHeadRot/yBodyRot 驱动（不跟着 setYRot 走），必须一起对齐，
                    // 否则生物会"身体朝前、脑袋朝存档里的旧角度"。
                    living.yHeadRot = record.yaw();
                    living.yHeadRotO = record.yaw();
                    living.yBodyRot = record.yaw();
                    living.yBodyRotO = record.yaw();
                }
                // 位置旧值对齐：否则 Mth.lerp(partialTicks, xOld, x) 会把实体从原点插值过来
                entity.setOldPosAndRot();
                built.add(new Placed(entity, record.x(), record.y(), record.z(), record.type()));
            } catch (Throwable error) {
                FAILED_TYPES.add(record.type());
                CreateCMPOR.LOGGER.warn("[预览] 实体重建失败，今后跳过该类型：{}", record.type(), error);
            }
        }
        return built.isEmpty() ? EMPTY : new PreviewEntityScene(built);
    }

    public boolean isEmpty() {
        return placed.isEmpty();
    }

    public int size() {
        return placed.size();
    }

    /** 被锁存为"渲染/重建失败"的类型数量（调试用）。 */
    public static int failedTypeCount() {
        return FAILED_TYPES.size();
    }

    /**
     * 逐只绘制。姿态栈由调用方准备好（{@link PreviewRender} 已经施加了 1/N 缩放与居中/底部对齐），
     * 本方法只在当前姿态下按下标算出的局部坐标摆位。
     */
    public void render(PoseStack ms, MultiBufferSource buffers, int packedLight) {
        if (placed.isEmpty()) {
            return;
        }
        EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        // partialTicks 用 1.0F 而不是当帧的动画 partial tick：这些实体是"重建即冻结的雕像"，
        // 所有 prev* 字段（xOld/yOld/zOld、xRotO、yHeadRotO…）要么是默认值、要么被我们显式对齐过，
        // 而装置（contraption）的朝向来自 applyLocalTransforms 内部的插值（Create 的
        // ContraptionMatrices.translateToEntity 用 Mth.lerp(partialTicks, xOld, getX())，
        // 装置角度也按同样的 prev→current 插值取）——取 t=1 才拿到"当前/NBT 里的那个姿态"，
        // 取 0 或半途的 t 会得到"上一个采样值"或两者的中间值（这正是静态装置画歪的常见原因）。
        float partialTicks = 1.0F;
        try {
            dispatcher.setRenderShadow(false);
        } catch (Throwable error) {
            CreateCMPOR.LOGGER.debug("[预览] 关闭实体阴影失败（继续绘制）", error);
        }
        try {
            for (Placed placement : placed) {
                if (FAILED_TYPES.contains(placement.type())) {
                    continue;
                }
                try {
                    EntityRenderer<? super Entity> renderer = dispatcher.getRenderer(placement.entity());
                    if (renderer == null) {
                        FAILED_TYPES.add(placement.type());
                        CreateCMPOR.LOGGER.warn("[预览] 实体类型没有渲染器，跳过：{}", placement.type());
                        continue;
                    }
                    dispatcher.render(placement.entity(), placement.x(), placement.y(), placement.z(),
                            placement.entity().getYRot(), partialTicks, ms, buffers, packedLight);
                } catch (Throwable error) {
                    FAILED_TYPES.add(placement.type());
                    CreateCMPOR.LOGGER.warn("[预览] 实体渲染失败，今后跳过该类型：{}", placement.type(), error);
                }
            }
        } catch (Throwable error) {
            CreateCMPOR.LOGGER.debug("[预览] 实体绘制异常，本帧跳过全部实体", error);
        } finally {
            try {
                dispatcher.setRenderShadow(true);
            } catch (Throwable ignored) {
                // 恢复失败无所谓：原版下一帧自己会用默认值
            }
        }
    }
}
