package com.yansunsky.createcmpor.client.preview;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.OrientedContraptionEntity;
import com.yansunsky.createcmpor.CreateCMPOR;
import com.yansunsky.createcmpor.preview.PreviewSnapshot;
import net.createmod.catnip.animation.AnimationTickHolder;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.foundation.virtualWorld.VirtualRenderWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.level.LightLayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

import java.lang.reflect.Field;
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
    public static final PreviewEntityScene EMPTY = new PreviewEntityScene(List.of(), null, List.of());

    /**
     * 已知会失败的类型（会话级锁存）：避免每帧重复抛异常刷日志。
     * 与 {@link PreviewDynamicParts#markFailed} 同一思路，但按<b>实体类型</b>而不是方块状态。
     */
    private static final Set<String> FAILED_TYPES = new HashSet<>();

    /** 光照诊断只打一次（避免刷屏）。 */
    private static boolean lightProbeLogged;

    /** {@code ControlledContraptionEntity.prevAngle} 的反射缓存（懒解析，解析失败保持 null）。 */
    private static Field PREV_ANGLE_FIELD;
    private static boolean PREV_FIELD_RESOLVED;
    private static boolean PREV_FIELD_WARNED;

    private final List<Placed> placed;

    /** 微缩虚拟世界（= 实体的 level）：渲染期要把本帧光照设成它的 external light，见 {@link #render}。 */
    private final Level virtualWorld;

    /**
     * 各装置自己的<b>烘焙世界</b>（{@code ClientContraption.getRenderLevel()}）。
     *
     * <p>为什么也要逐帧设光照：装置内的"机关零件"渲染路径有三条（子代理源码复核），
     * 其中 {@code DrillRenderer:51} 与 {@code HarvesterRenderer:56} <b>只读烘焙世界</b>
     * （{@code .light(LevelRenderer.getLightColor(renderWorld, localPos))}），
     * 不像其余零件那样再叠一次 {@code useLevelLight(context.world)}。
     * 不设的话钻头/收割机会永远发黑。
     */
    private final List<VirtualRenderWorld> contraptionWorlds;

    private PreviewEntityScene(List<Placed> placed, Level virtualWorld,
                               List<VirtualRenderWorld> contraptionWorlds) {
        this.placed = List.copyOf(placed);
        this.virtualWorld = virtualWorld;
        this.contraptionWorlds = List.copyOf(contraptionWorlds);
    }

    /**
     * 一只已重建的实体及其在<b>快照局部格坐标系</b>里的位置。
     *
     * @param baseAngle 快照里记录的基准转角（度）——只有可控装置用
     * @param animDegPerTick <b>已按动画周期整表缩放</b>的角速度（度/tick，0 = 静止）
     */
    private record Placed(Entity entity, float x, float y, float z, String type,
                          float baseAngle, float animDegPerTick) {
    }

    /**
     * 在微缩虚拟世界里重建整张实体表。
     *
     * <p>任何失败都<b>只影响该只实体</b>：造不出来就跳过（记一次 debug），绝不冒泡、绝不空指针。
     * 全部失败时返回 {@link #EMPTY}。
     */
    public static PreviewEntityScene build(Level virtualWorld, List<PreviewSnapshot.EntityRecord> records,
                                           float speedScale) {
        if (records.isEmpty()) {
            return EMPTY;
        }
        List<Placed> built = new ArrayList<>(records.size());
        List<VirtualRenderWorld> contraptionWorlds = new ArrayList<>(2);
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
                // 装置额外一步：把"上一帧姿态"对齐到"当前姿态"（影子实体永远不会 tick，见方法注释）
                alignContraptionPrevFields(entity);
                // 装置额外一步：登记它的烘焙世界（渲染期逐帧喂光照，见类字段注释）
                VirtualRenderWorld bakeWorld = contraptionBakeWorld(entity);
                if (bakeWorld != null && bakeWorld != virtualWorld
                        && contraptionWorlds.stream().noneMatch(existing -> existing == bakeWorld)) {
                    contraptionWorlds.add(bakeWorld);
                }
                // 角速度与转速表同一口径缩放（整表缩放系数由烘焙侧算好传进来），
                // 于是"装置"与"驱动它的轴承"在微缩里转速一致；基准角取 NBT 里那个冻结的角度。
                float anim = record.animDegPerTick() * speedScale;
                if (Math.abs(anim) < PreviewSnapshot.SPEED_EPSILON) {
                    anim = 0.0F;
                }
                float baseAngle = entity instanceof ControlledContraptionEntity controlled
                        ? controlled.getAngle(1.0F) : 0.0F;
                built.add(new Placed(entity, record.x(), record.y(), record.z(), record.type(), baseAngle, anim));
            } catch (Throwable error) {
                FAILED_TYPES.add(record.type());
                CreateCMPOR.LOGGER.warn("[预览] 实体重建失败，今后跳过该类型：{}", record.type(), error);
            }
        }
        return built.isEmpty() ? EMPTY : new PreviewEntityScene(built, virtualWorld, contraptionWorlds);
    }

    public boolean isEmpty() {
        return placed.isEmpty();
    }

    /**
     * 把本帧光照喂给微缩虚拟世界（= 实体所在世界），并（仅首次）打印一条光照诊断。
     *
     * <p>顺序很重要：<b>先读后写</b>。诊断要在设置之前采样，否则打印出来的是"已经被我们改过的值"，
     * 看不到问题现场（子代理复核时指出的原始实现缺陷）。
     */
    private void applyPreviewLight(int packedLight) {
        if (!(virtualWorld instanceof VirtualRenderWorld world)) {
            return;
        }
        try {
            if (!lightProbeLogged) {
                lightProbeLogged = true;
                BlockPos probe = BlockPos.containing(placed.getFirst().x(), placed.getFirst().y(),
                        placed.getFirst().z());
                CreateCMPOR.LOGGER.info(
                        "[预览] 装置光照诊断（设置前）：微缩世界 {}(sky={} block={} → 采样色 {})，本帧 packedLight={}",
                        world.getClass().getSimpleName(),
                        world.getBrightness(LightLayer.SKY, probe),
                        world.getBrightness(LightLayer.BLOCK, probe),
                        LevelRenderer.getLightColor(world, probe), packedLight);
            }
            world.setExternalLight(packedLight);
        } catch (Throwable error) {
            CreateCMPOR.LOGGER.debug("[预览] 设置预览世界外部光照失败（装置可能偏暗）", error);
        }
    }

    /**
     * 取装置的<b>烘焙世界</b>（{@code ClientContraption.getRenderLevel()}）；非装置/失败返回 {@code null}。
     *
     * <p>光照为什么要这么绕（子代理独立复核 + 本代理 jar/源码双向确认）：
     * <ul>
     *     <li>Create 的 {@code VirtualRenderWorld} 构造器写死
     *         {@code new LevelLightEngine(chunkSource, true, false)}
     *         （{@code foundation/virtualWorld/VirtualRenderWorld.java:90}，发行 jar 字节码同样）。
     *         ⇒ <b>天空光引擎被关掉</b>，{@code getBrightness(SKY)} 恒 0；方块光引擎虽开着但我们从不
     *         {@code runLightEngine()} ⇒ 也恒 0。所以虚拟世界自身光照 = 0。</li>
     *     <li>装置的顶点光照是<b>烘</b>出来的（Create 自己的 {@code CONTRAPTION} 缓存，懒烘），
     *         而 {@code ContraptionEntityRenderer:128} 只再叠一次
     *         {@code useLevelLight(entity.level() = 我们的微缩世界)} 采样 —— 采样值同样是 0
     *         ⇒ {@code max(0,0)=0} ⇒ 装置发黑。</li>
     *     <li>我们自己的方块不受影响：它们渲染期用 {@code .light(packedLight)} 合并。
     *         真实世界也不受影响：那边 {@code useLevelLight} 采样的是真实光照。</li>
     * </ul>
     * 结论：唯一能插手的旋钮就是"<b>给这些虚拟世界设 external light</b>"（渲染期逐帧设成本帧
     * {@code packedLight}，见 {@link #render}）。<b>不能设成恒定值</b>——{@code maxLight} 是逐分量取 max，
     * 烘进顶点的光照就成了"降不下来的地板"，暗处的装置会比周围方块亮。
     */
    private static VirtualRenderWorld contraptionBakeWorld(Entity entity) {
        if (!(entity instanceof AbstractContraptionEntity contraptionEntity)) {
            return null;
        }
        try {
            Contraption contraption = contraptionEntity.getContraption();
            return contraption == null ? null : contraption.getOrCreateClientContraptionLazy().getRenderLevel();
        } catch (Throwable error) {
            CreateCMPOR.LOGGER.debug("[预览] 装置烘焙世界获取失败", error);
            return null;
        }
    }

    /**
     * 让"绕单轴旋转"的装置动起来：每帧把角度推给实体（{@code ControlledContraptionEntity}）。
     *
     * <h3>为什么这样驱动是对的（三步证据）</h3>
     * <ol>
     *     <li>真实世界里角度就是这么来的：{@code MechanicalBearingBlockEntity.tick()} 每 tick
     *         {@code angle += convertToAngular(getSpeed())}（{@code :265-266}），
     *         然后 {@code movedContraption.setAngle(angle)}（{@code :286}）；</li>
     *     <li>渲染读的是 {@code getAngle(partialTicks)}，我们每帧同时写 {@code angle} 与 {@code prevAngle}
     *         ⇒ 返回的正是我们写进去的那个角度（{@code prevAngle} 是 protected，靠反射，见
     *         {@link #alignContraptionPrevFields}）；</li>
     *     <li>角速度口径与转速表一致：{@code animDegPerTick} 在烘焙期已经乘过同一个整表缩放系数，
     *         所以"装置转一圈"与"轴承转一圈"用的是同一个周期。</li>
     * </ol>
     *
     * <p>相位用 {@code (renderTime % period) × speed} 求，而不是 {@code renderTime × speed % 360}：
     * 后者在会话后期（{@code renderTime} 以 tick 计、可达千万级）会因 float 精度丢失而抖动。
     *
     * <p>失败只让<b>这一个装置</b>静止：属性写入包在自己的 try 里，绝不因此把它锁存成"渲染失败"。
     */
    private static void driveContraptionAngle(Placed placement, float renderTime) {
        if (placement.animDegPerTick() == 0.0F
                || !(placement.entity() instanceof ControlledContraptionEntity controlled)) {
            return;
        }
        try {
            float period = 360.0F / Math.abs(placement.animDegPerTick());
            float degrees = placement.baseAngle() + (renderTime % period) * placement.animDegPerTick();
            controlled.setAngle(degrees);
            Field field = prevAngleField();
            if (field != null) {
                field.setFloat(controlled, degrees);
            }
        } catch (Throwable error) {
            CreateCMPOR.LOGGER.debug("[预览] 装置角度驱动失败（该装置保持静止）：{}", placement.type(), error);
        }
    }

    /**
     * 让装置（contraption）的"上一帧姿态"等于"当前姿态"。
     *
     * <h3>为什么必须做（三段源码串起来才是完整结论）</h3>
     * <ol>
     *     <li>装置的角度是<b>插值</b>出来的，不是直接读 {@code angle}：
     *         {@code ControlledContraptionEntity.getAngle(pt)} =
     *         {@code pt == 1.0F ? angle : AngleHelper.angleLerp(pt, prevAngle, angle)}；</li>
     *     <li>写 {@code prevAngle} 的只有 {@code tickContraption()}（{@code prevAngle = angle;}）——
     *         影子实体<b>永远不会 tick</b>；而存档里<b>只写 {@code Angle}</b>
     *         （{@code ControlledContraptionEntity} 存取键只有 {@code Angle}）
     *         ⇒ 重建出来的 {@code prevAngle} 恒为 0；</li>
     *     <li>这条插值发生在 {@code ContraptionMatrices.setup(...)} <b>内部</b>，它自己取
     *         {@code AnimationTickHolder.getPartialTicks()}
     *         （= {@code mc.getTimer().getGameTimeDeltaPartialTick(false)}，取值 [0,1)）
     *         —— <b>不是</b>我们传给 {@code dispatcher.render} 的那个 partialTicks。
     * </ol>
     * 三者相乘的后果：存档里 90° 的轴承装置会画成 {@code angleLerp(当帧 partialTick, 0, 90)}
     * —— 既偏小、又<b>每帧抖动</b>（角度随 20 次/秒的 tick 分数来回扫）。
     * 所以修法只能从实体字段侧对齐（把 prev 写成 current），而不是调 {@code dispatcher.render} 的参数。
     *
     * <h3>为什么用反射而不是 AT / mixin</h3>
     * {@code OrientedContraptionEntity} 的 {@code prevYaw/prevPitch} 是 <b>public</b> 字段，直接赋值；
     * {@code ControlledContraptionEntity} 的 {@code prevAngle} 是 <b>protected</b>（且没有公开 setter，
     * {@code setAngle} 只写 {@code angle}）。AT/Accessor mixin 一旦目标字段改名就是<b>加载期崩溃</b>，
     * 而这条路径是纯装饰——按本项目"装饰失败绝不崩客户端"的口径，反射 + 一次 WARN 更合适。
     */
    private static void alignContraptionPrevFields(Entity entity) {
        try {
            if (entity instanceof OrientedContraptionEntity oriented) {
                oriented.prevYaw = oriented.yaw;
                oriented.prevPitch = oriented.pitch;
                return;
            }
            if (entity instanceof ControlledContraptionEntity controlled) {
                Field field = prevAngleField();
                if (field != null) {
                    // getAngle(1.0F) 就是 angle 本身（见上面第 1 条），避免再反射读 angle 字段
                    field.setFloat(controlled, controlled.getAngle(1.0F));
                }
            }
        } catch (Throwable error) {
            if (!PREV_FIELD_WARNED) {
                PREV_FIELD_WARNED = true;
                CreateCMPOR.LOGGER.warn("[预览] 装置上一帧姿态对齐失败：该装置按上一帧角度插值显示（可能偏小/抖动）", error);
            }
        }
    }

    /** 懒解析并缓存 {@code ControlledContraptionEntity.prevAngle}；解析失败返回 {@code null}（只 WARN 一次）。 */
    private static Field prevAngleField() {
        if (!PREV_FIELD_RESOLVED) {
            PREV_FIELD_RESOLVED = true;
            try {
                Field field = ControlledContraptionEntity.class.getDeclaredField("prevAngle");
                field.setAccessible(true);
                PREV_ANGLE_FIELD = field;
            } catch (Throwable error) {
                CreateCMPOR.LOGGER.warn("[预览] 找不到 ControlledContraptionEntity.prevAngle，装置角度将按上一帧值插值", error);
            }
        }
        return PREV_ANGLE_FIELD;
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
        // 渲染器若按 prev→current 插值，prev 字段要么是默认值、要么被我们显式对齐过，
        // 取 t=1 一定拿到"当前/NBT 里的那个姿态"。
        // ⚠️ 注意边界（0.4.9 修正）：这个参数**管不到装置（contraption）的角度**——
        // ContraptionEntityRenderer → ContraptionMatrices.setup(...) 内部自己取
        // AnimationTickHolder.getPartialTicks()，我们的参数根本传不进去；装置的角度靠
        // alignContraptionPrevFields(...) 从实体字段侧对齐（详见该方法注释）。
        float partialTicks = 1.0F;
        try {
            dispatcher.setRenderShadow(false);
        } catch (Throwable error) {
            CreateCMPOR.LOGGER.debug("[预览] 关闭实体阴影失败（继续绘制）", error);
        }
        float renderTime = AnimationTickHolder.getRenderTime();
        applyPreviewLight(packedLight);
        if (!contraptionWorlds.isEmpty()) {
            for (VirtualRenderWorld world : contraptionWorlds) {
                try {
                    world.setExternalLight(packedLight);
                } catch (Throwable error) {
                    CreateCMPOR.LOGGER.debug("[预览] 设置装置烘焙世界光照失败（钻头/收割机可能偏暗）", error);
                }
            }
        }
        try {
            for (Placed placement : placed) {
                if (FAILED_TYPES.contains(placement.type())) {
                    continue;
                }
                try {
                    driveContraptionAngle(placement, renderTime);
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
