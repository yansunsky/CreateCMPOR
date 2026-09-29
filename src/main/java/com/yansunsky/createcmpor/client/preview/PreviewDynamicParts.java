package com.yansunsky.createcmpor.client.preview;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual;
import com.simibubi.create.content.kinetics.waterwheel.WaterWheelRenderer;
import com.yansunsky.createcmpor.CreateCMPOR;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.SuperBufferFactory;
import net.createmod.catnip.render.SuperByteBuffer;
import net.createmod.catnip.render.SuperByteBufferCache;
import com.simibubi.create.content.contraptions.bearing.BearingBlock;
import net.createmod.catnip.math.AngleHelper;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.Direction.AxisDirection;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.model.data.ModelData;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 微缩预览"动态 pass"的机型白名单与旋转部件解析。
 *
 * <p><b>第一期（A1）只做纯旋转</b>：这类部件的姿态是"绕旋转轴转一个角度"，角度是
 * {@code (渲染时间 × 转速 × 0.3 + 相位)} 的纯函数（Create {@code KineticBlockEntityRenderer.getAngleForBe}），
 * 所以只需要快照里的<b>每格转速</b>就够了，无需 BE 的客户端 NBT。
 *
 * <h3>两类处理（关键区别）</h3>
 * <ul>
 *     <li><b>{@code replacesStatic = true}</b>——<b>方块模型本身就是旋转几何</b>（轴、齿轮、大齿轮、粉碎轮、
 *         飞轮块、转盘、动力轴、龙门轴）。这些格在静态烘焙时被剔除，改由动态 pass 旋转绘制；
 *         否则静态副本与动态副本会叠加（小齿轮是 4 根十字条，叠加不同角度会变成 8 齿）。</li>
 *     <li><b>{@code replacesStatic = false}</b>——<b>方块模型是静止外壳</b>，旋转件在 partial 里
 *         （装箱轴/装箱齿轮的内部传动件、磨石内盘、变速箱与离合换挡的伸出半轴、创造马达半轴、水车本体）。
 *         这些格<b>继续留在静态烘焙里</b>（外壳必须画），动态 pass 只是额外补上正在转的那部分。
 *         顺带修好了现状的"缺件"问题：磨石内盘、水车本体、装箱齿轮的齿在旧版预览里本来就是缺失的。</li>
 * </ul>
 *
 * <h3>为什么按"方块模型能不能整体转"来分</h3>
 * 整体旋转一个 <b>0..16 全尺寸立方体外壳</b>（变速箱/离合/换挡/装箱系列的模型都是满方块外壳）在任意角度下
 * 四角会戳出格子外，看起来是"外壳在乱转"——比静止更糟。所以外壳必须留在静态层。
 *
 * <h3>已知局限（如实标注）</h3>
 * <ul>
 *     <li>变速箱的 4 根半轴在真实世界里按 {@code sourceFacing} 取 ±号；v3 快照只存了速度、<b>没存 source</b>，
 *         所以 4 根半轴一律按同号旋转。微缩尺度下半轴只有约 0.1~0.2 像素，肉眼不可辨。</li>
 *     <li>离合/换挡同理：未通电时真实世界有一侧 {@code modifier = 0}（不转），这里两侧都转。</li>
 *     <li>创造马达用 {@code SHAFT_HALF} partial 近似，不含马达内部细节。</li>
 *     <li>水车用 Create 自己的 {@code WaterWheelRenderer.generateModel} 生成旋转模型，但<b>材质只能用橡木</b>
 *         （真实材质在 BE 字段 {@code material} 里，v3 快照没存）→ 深色木材的水车在预览里显示成橡木色。</li>
 *     <li>大尺寸水车（{@code large_water_wheel}，3×3×1 多方块）<b>不在本期白名单</b>：它的旋转模型按
 *         {@code EXTENSION} 分化成两套 partial，跨格几何在 1 格粒度的动态 pass 里无法忠实表达。</li>
 *     <li>PoweredShaft 的 {@code getRotationAngleOffset} 是 Create 里唯一覆写（Create
 *         {@code PoweredShaftBlockEntity:129-131}），本实现与相位计算一律不读 BE，故这一项相位可能差一点。</li>
 *     <li>飞轮块：Create 的 {@code FlywheelRenderer} 用 BE 字段 {@code angle} 与 {@code visualSpeed}
 *         算相位（不是 {@code getAngleForBe}），本实现用统一公式 → 转速一致，<b>相位可能漂移</b>。</li>
 *     <li>机械轴承的顶板朝向<b>原样搬运</b>了 Create 的两条 {@code rotateCentered} 修正
 *         （水平 facing 先绕 UP、再绕 EAST；垂直 facing 只绕 EAST 且 UP 时为 0 弧度），顺序与 Create 一致；
 *         风车轴承按 Create 源码走 {@code BEARING_TOP}（{@code isWoodenTop()} 在机械轴承里返回 false、
 *         风车轴承继承之）。发条轴承<b>不在白名单</b>：它的转角由排程驱动，不是"转速×时间"的纯函数。</li>
 *     <li>鼓风机扇叶：Create 的可见转速是 {@code getSpeed()×5} 再 clamp 到 [80, 64×20]（保证看起来总在狂转），
 *         本实现用统一的"转速×0.3×整表缩放"公式，<b>低转速下会比真实世界慢</b>（与其它部件保持一致性优先）。</li>
 *     <li>装箱系列的半轴/内部件用 partial 补件绘制，但<b>不画</b> Create 额外补的 SHAFT_HALF
 *         （只有几像素、微缩后不足 0.2 像素，且需要 {@code be.hasShaftTowards} 的邻居信息）。</li>
 * </ul>
 *
 * <h3>失败处理</h3>
 * 解析或绘制失败的状态一律<b>锁存</b>进 {@link #FAILED}（直到资源重载），该格退回"静态绘制"
 * （见 {@link PreviewDynamicCell#bakedStatically()}）。任何异常都在调用侧被吞掉，绝不冒泡到客户端。
 */
public final class PreviewDynamicParts {

    /** 一个可旋转子模型：顶点缓冲 + 所属 {@link RenderType} + 它的旋转轴 + 自旋之后追加的固定朝向修正。 */
    public record Part(SuperByteBuffer buffer, RenderType layer, Axis axis, List<FixedRotation> tail) {

        public Part(SuperByteBuffer buffer, RenderType layer, Axis axis) {
            this(buffer, layer, axis, List.of());
        }
    }

    /**
     * 自旋之后追加的固定旋转（Create 的 renderSafe 在 {@code kineticRotationTransform(...)} 之后
     * 还会补朝向修正，例如机械轴承的顶板按 facing 再转两下）。
     *
     * <p><b>必须按 Create 的调用顺序原样施加</b>：{@code rotateCentered} 是往缓冲自己的变换栈上叠加，
     * 顺序不同结果不同（项目里"JOML 叠加式旋转"的老坑）。这里只搬运 Create 的顺序，不重新推导。
     */
    public record FixedRotation(Axis axis, float radians) {
    }

    /**
     * 一个机型的旋转方案。
     *
     * <p>{@code parts} <b>保证非空且每个缓冲都非空</b>——空方案一律用 {@code null} 表示"不在动态通道"，
     * 理由见 {@link #partial(Part[], boolean)}（空缓冲会让 {@code renderInto} 提前返回并跳过 transform 栈复位，
     * 于是每帧越堆越高）。
     */
    public record Rotation(Part[] parts, boolean replacesStatic) {
    }

    /** 缓存里的"不参与动态"哨兵（含"不在白名单"与"已锁存失败"两种）。 */
    private static final Rotation NONE = new Rotation(new Part[0], false);

    /**
     * 静态回退专用的缓冲 compartment——<b>必须自己注册</b>。
     *
     * <p>为什么不复用 catnip 的 {@code CachedBuffers.GENERIC_BLOCK}：它<b>从未被注册</b>
     * （Create {@code CreateClient:93-99} 那几行注册全被注释掉了，只留下 101-105 的
     * {@code PARTIAL / DIRECTIONAL_PARTIAL / KINETIC_BLOCK / WATER_WHEEL / CONTRAPTION}），
     * 而未注册的 compartment 一访问就抛 {@code IllegalArgumentException}
     * （{@code SuperByteBufferCache.get:41-43}）→ 回退失效 → 正好违反"失败不许整格消失"。
     *
     * <p>为什么不复用 Create 的 {@code KINETIC_BLOCK}：那是动态绘制用的实例，中途抛异常会留下脏状态；
     * 回退必须是<b>另一份独立实例</b>才可靠。
     */
    private static final SuperByteBufferCache.Compartment<BlockState> FALLBACK_COMPARTMENT =
            new SuperByteBufferCache.Compartment<>();

    static {
        try {
            SuperByteBufferCache.getInstance().registerCompartment(FALLBACK_COMPARTMENT);
        } catch (Throwable error) {
            CreateCMPOR.LOGGER.debug("[预览] 静态回退缓冲 compartment 注册失败", error);
        }
    }

    private static final Map<BlockState, Rotation> CACHE = new HashMap<>();
    private static final Set<BlockState> FAILED = new HashSet<>();

    private PreviewDynamicParts() {
    }

    /**
     * 解析该方块状态的旋转方案；不在白名单 / 解析失败 / 已锁存失败都返回 {@code null}
     * （调用侧据此走静态路径，<b>绝不吞掉这一格</b>）。
     */
    public static Rotation resolve(BlockState state) {
        Rotation cached = CACHE.get(state);
        if (cached != null) {
            return cached == NONE ? null : cached;
        }
        Rotation computed;
        try {
            computed = compute(state);
        } catch (Throwable error) {
            CreateCMPOR.LOGGER.debug("[预览] 动态部件解析失败，该机型退回静态：{}", state, error);
            computed = null;
        }
        CACHE.put(state, computed == null ? NONE : computed);
        return computed;
    }

    /** 该状态是否已被锁存为"动态绘制失败"。 */
    public static boolean isFailed(BlockState state) {
        return FAILED.contains(state);
    }

    /** 锁存失败：此后该状态的格子一律走静态回退（避免每帧重复抛异常）。 */
    public static void markFailed(BlockState state) {
        FAILED.add(state);
        CACHE.put(state, NONE);
    }

    /**
     * 静态回退缓冲：与动态 draw 用的是<b>不同 compartment</b>（{@link #FALLBACK_COMPARTMENT} vs
     * Create 的 {@code KINETIC_BLOCK}），因此是两份独立实例——动态那份即使中途抛异常留下脏状态，
     * 也不会污染回退。
     *
     * <p>缓冲只包含模型的全部四边形（{@code SuperBufferFactory} 不按 RenderType 拆分），
     * 所以<b>只能往一个图层里画一次</b>；图层选取与 Create 的 {@code getRenderType} 同规则。
     *
     * <p>首次访问才真正构建（失败是罕见路径），构建失败返回 {@code null}，调用侧会安静跳过这一格而不是崩。
     */
    public static SuperByteBuffer fallback(BlockState state) {
        try {
            return CachedBuffers.block(FALLBACK_COMPARTMENT, state);
        } catch (Throwable error) {
            CreateCMPOR.LOGGER.debug("[预览] 静态回退缓冲不可用：{}", state, error);
            return null;
        }
    }

    /** 静态回退使用的图层（与动态 draw 同一套规则）。 */
    public static RenderType fallbackLayer(BlockState state) {
        try {
            return pickLayer(state, RenderType.cutoutMipped());
        } catch (Throwable error) {
            return RenderType.cutoutMipped();
        }
    }

    /** 资源重载 / 退出世界时清空（缓存里持有 BakedModel 顶点数据，必须跟着资源走）。 */
    public static void clear() {
        CACHE.clear();
        FAILED.clear();
        try {
            SuperByteBufferCache.getInstance().invalidate(FALLBACK_COMPARTMENT);
        } catch (Throwable error) {
            CreateCMPOR.LOGGER.debug("[预览] 静态回退缓冲清理失败", error);
        }
    }

    // ------------------------------------------------------------------
    // 机型 → 旋转方案
    // ------------------------------------------------------------------

    private static Rotation compute(BlockState state) {
        Block block = state.getBlock();
        Axis axis = rotationAxis(state);
        if (axis == null) {
            return null;
        }

        // ---- 方块模型本身就是旋转几何：整块旋转，静态层必须剔除 ----
        if (block == AllBlocks.SHAFT.get()
                || block == AllBlocks.COGWHEEL.get()
                || block == AllBlocks.LARGE_COGWHEEL.get()
                || block == AllBlocks.CRUSHING_WHEEL.get()
                || block == AllBlocks.FLYWHEEL.get()
                || block == AllBlocks.TURNTABLE.get()
                || block == AllBlocks.POWERED_SHAFT.get()
                || block == AllBlocks.GANTRY_SHAFT.get()) {
            return whole(state, axis, RenderType.cutoutMipped());
        }

        // ---- 装箱轴：外壳静止，内部轴 = create:shaft 的方块模型（照抄 Create ShaftRenderer
        //      getRenderedBlockState → KineticBlockEntityRenderer.shaft(axis)）----
        if (block == AllBlocks.ANDESITE_ENCASED_SHAFT.get()
                || block == AllBlocks.BRASS_ENCASED_SHAFT.get()
                || block == AllBlocks.ENCASED_CHAIN_DRIVE.get()
                || block == AllBlocks.METAL_GIRDER_ENCASED_SHAFT.get()) {
            BlockState shaft = KineticBlockEntityRenderer.shaft(axis);
            return partial(new Part(CachedBuffers.block(KineticBlockEntityRenderer.KINETIC_BLOCK, shaft),
                    pickLayer(shaft, RenderType.cutoutMipped()), axis), false);
        }

        // ---- 装箱齿轮：外壳静止，内部齿 = SHAFTLESS_COGWHEEL / LARGE（照抄 EncasedCogRenderer.getRotatedModel）----
        if (block == AllBlocks.ANDESITE_ENCASED_COGWHEEL.get() || block == AllBlocks.BRASS_ENCASED_COGWHEEL.get()) {
            return encasedCog(state, axis, AllPartialModels.SHAFTLESS_COGWHEEL);
        }
        if (block == AllBlocks.ANDESITE_ENCASED_LARGE_COGWHEEL.get()
                || block == AllBlocks.BRASS_ENCASED_LARGE_COGWHEEL.get()) {
            return encasedCog(state, axis, AllPartialModels.SHAFTLESS_LARGE_COGWHEEL);
        }

        // ---- 磨石：外壳静止，内盘 = MILLSTONE_COG（照抄 MillstoneRenderer.getRotatedModel）----
        if (block == AllBlocks.MILLSTONE.get()) {
            return partial(new Part(CachedBuffers.partial(AllPartialModels.MILLSTONE_COG, state),
                    RenderType.solid(), axis), false);
        }

        // ---- 变速箱：外壳静止，4 根非箱轴方向的伸出半轴在转（照抄 GearboxRenderer.renderSafe）----
        if (block == AllBlocks.GEARBOX.get()) {
            Part[] parts = new Part[4];
            int count = 0;
            for (Direction direction : Direction.values()) {
                if (direction.getAxis() == axis) {
                    continue;
                }
                parts[count++] = new Part(
                        CachedBuffers.partialFacing(AllPartialModels.SHAFT_HALF, state, direction),
                        RenderType.solid(), direction.getAxis());
            }
            return count == 0 ? null : partial(Arrays.copyOf(parts, count), false);
        }

        // ---- 离合 / 换挡：外壳静止，箱轴两端半轴在转（照抄 SplitShaftRenderer.renderSafe）----
        if (block == AllBlocks.CLUTCH.get() || block == AllBlocks.GEARSHIFT.get()) {
            Part[] parts = new Part[2];
            int count = 0;
            for (Direction direction : Direction.values()) {
                if (direction.getAxis() != axis) {
                    continue;
                }
                parts[count++] = new Part(
                        CachedBuffers.partialFacing(AllPartialModels.SHAFT_HALF, state, direction),
                        RenderType.solid(), direction.getAxis());
            }
            return count == 0 ? null : partial(Arrays.copyOf(parts, count), false);
        }

        // ---- 创造马达：外壳静止，半轴 = SHAFT_HALF（照抄 CreativeMotorRenderer.getRotatedModel）----
        if (block == AllBlocks.CREATIVE_MOTOR.get()) {
            return partial(new Part(CachedBuffers.partialFacing(AllPartialModels.SHAFT_HALF, state),
                    RenderType.solid(), axis), false);
        }

        // ---- 水车（仅小号）：支架静止，轮体 = Create 运行时生成的 water_wheel 模型 ----
        if (block == AllBlocks.WATER_WHEEL.get()) {
            SuperByteBuffer wheel = waterWheel(state);
            return wheel == null ? null : partial(new Part(wheel, RenderType.solid(), axis), false);
        }

        // ---- 机械轴承 / 风车轴承：底壳静止，顶板 + 半轴转（照抄 Create BearingRenderer.renderSafe；
        //      getRotatedModel 另给 SHAFT_HALF 朝向 facing.getOpposite()）----
        if (block == AllBlocks.MECHANICAL_BEARING.get() || block == AllBlocks.WINDMILL_BEARING.get()) {
            if (!(state.hasProperty(BearingBlock.FACING))) {
                return null;
            }
            Direction facing = state.getValue(BearingBlock.FACING);
            // isWoodenTop()：机械轴承返回 false，风车轴承继承它（Create 源码核实），故两者都用 BEARING_TOP
            List<FixedRotation> tail = new java.util.ArrayList<>(2);
            if (facing.getAxis().isHorizontal()) {
                tail.add(new FixedRotation(Axis.Y,
                        AngleHelper.rad(AngleHelper.horizontalAngle(facing.getOpposite()))));
            }
            tail.add(new FixedRotation(Axis.Z, AngleHelper.rad(-90 - AngleHelper.verticalAngle(facing))));
            Part top = new Part(CachedBuffers.partial(AllPartialModels.BEARING_TOP, state),
                    RenderType.solid(), facing.getAxis(), List.copyOf(tail));
            Part shaftHalf = new Part(
                    CachedBuffers.partialFacing(AllPartialModels.SHAFT_HALF, state, facing.getOpposite()),
                    RenderType.solid(), facing.getAxis());
            return partial(new Part[]{top, shaftHalf}, false);
        }

        // ---- 鼓风机：外壳静止，扇叶（propeller）+ 半轴转（照抄 Create EncasedFanRenderer.renderSafe）----
        if (block == AllBlocks.ENCASED_FAN.get() && state.hasProperty(BlockStateProperties.FACING)) {
            Direction facing = state.getValue(BlockStateProperties.FACING);
            Part blades = new Part(
                    CachedBuffers.partialFacing(AllPartialModels.ENCASED_FAN_INNER, state, facing.getOpposite()),
                    RenderType.cutoutMipped(), facing.getAxis());
            Part shaftHalf = new Part(
                    CachedBuffers.partialFacing(AllPartialModels.SHAFT_HALF, state, facing.getOpposite()),
                    RenderType.solid(), facing.getAxis());
            return partial(new Part[]{blades, shaftHalf}, false);
        }

        // ---- 动力泵：外壳静止，内部齿轮 = MECHANICAL_PUMP_COG（照抄 Create PumpRenderer.getRotatedModel）----
        if (block == AllBlocks.MECHANICAL_PUMP.get()) {
            return partial(new Part(CachedBuffers.partialFacing(AllPartialModels.MECHANICAL_PUMP_COG, state),
                    RenderType.solid(), axis), false);
        }

        // ---- 动力钻头：外壳静止，钻头 = DRILL_HEAD（照抄 Create DrillRenderer.getRotatedModel）----
        if (block == AllBlocks.MECHANICAL_DRILL.get()) {
            return partial(new Part(CachedBuffers.partialFacing(AllPartialModels.DRILL_HEAD, state),
                    RenderType.solid(), axis), false);
        }

        return null;
    }

    private static Rotation whole(BlockState state, Axis axis, RenderType fallbackLayer) {
        return partial(new Part(CachedBuffers.block(KineticBlockEntityRenderer.KINETIC_BLOCK, state),
                pickLayer(state, fallbackLayer), axis), true);
    }

    private static Rotation encasedCog(BlockState state, Axis axis,
                                       dev.engine_room.flywheel.lib.model.baked.PartialModel partial) {
        Direction facing = Direction.fromAxisAndDirection(axis, AxisDirection.POSITIVE);
        return partial(new Part(CachedBuffers.partialFacingVertical(partial, state, facing),
                RenderType.solid(), axis), false);
    }

    /**
     * 组装方案，并<b>剔除空缓冲</b>。
     *
     * <p>为什么必须剔除：{@code renderInto} 开头的 {@code if (isEmpty()) return;} 会<b>跳过末尾的
     * {@code reset()}</b>（catnip {@code ShadeSeparatingSuperByteBuffer:88-89} 与
     * {@code DefaultSuperByteBuffer:186-187}）。空缓冲如果留在计划里，每帧每格都会往它自己的
     * transform 栈上压一次旋转却从不弹——既画不出东西，又会把矩阵栈越堆越高（假的"每帧泄漏"）。
     * 剔掉以后，缓冲非空 → {@code renderInto} 一定走到 {@code reset()} → M 个格子可以安全复用同一实例。
     */
    private static Rotation partial(Part part, boolean replacesStatic) {
        return partial(new Part[]{part}, replacesStatic);
    }

    private static Rotation partial(Part[] parts, boolean replacesStatic) {
        List<Part> usable = new java.util.ArrayList<>(parts.length);
        for (Part part : parts) {
            if (part != null && part.buffer() != null && !part.buffer().isEmpty()) {
                usable.add(part);
            }
        }
        if (usable.isEmpty()) {
            return null;
        }
        return new Rotation(usable.toArray(new Part[0]), replacesStatic);
    }

    /**
     * 复刻 {@code WaterWheelRenderer.getRotatedModel}：用 Create 自己的 {@code generateModel}
     * 生成"材质化"的轮体模型，并按 facing 做 {@code rotateToFaceVertical}，缓冲缓存在 Create 的
     * {@code WATER_WHEEL} compartment（与它自己的缓存键同构，不额外占内存）。
     *
     * <p><b>材质未知</b>：真实木材在 BE 字段里，v3 快照不存 → 统一用橡木。
     */
    private static SuperByteBuffer waterWheel(BlockState state) {
        WaterWheelRenderer.ModelKey key = new WaterWheelRenderer.ModelKey(false, state,
                Blocks.OAK_PLANKS.defaultBlockState());
        Direction facing = state.getValue(com.simibubi.create.content.kinetics.waterwheel.WaterWheelBlock.FACING);
        return SuperByteBufferCache.getInstance().get(WaterWheelRenderer.WATER_WHEEL, key,
                () -> SuperBufferFactory.getInstance().createForBlock(
                        WaterWheelRenderer.generateModel(key), Blocks.AIR.defaultBlockState(),
                        CachedBuffers.rotateToFaceVertical(facing).get()));
    }

    /**
     * 旋转轴：走 Create 自己的 {@code KineticBlockEntityVisual.rotationAxis}（{@code IRotate} 实现），
     * 拿不到就退回 {@code null}（不猜）。
     */
    private static Axis rotationAxis(BlockState state) {
        try {
            return KineticBlockEntityVisual.rotationAxis(state);
        } catch (Throwable error) {
            CreateCMPOR.LOGGER.debug("[预览] 取旋转轴失败：{}", state, error);
            return null;
        }
    }

    /**
     * 选图层：<b>照抄 Create {@code KineticBlockEntityRenderer.getRenderType}</b>——
     * 按 {@code RenderType.chunkBufferLayers()} 的<b>逆序</b>取第一个模型支持的层，兜底 {@code fallback}。
     *
     * <p>为什么不硬编码 {@code solid()}：模型的实际层由资源包决定（数据包/资源包可改 render_type），
     * 硬编码在"模型其实是 cutout"时会把透明像素画成黑块（PE 踩过这个坑）。
     *
     * <p>为什么只画一层：{@code SuperByteBuffer} 里装的是模型的<b>全部</b>四边形（不按图层拆分），
     * 往多个图层各画一次 = 同一批几何被画多次（叠加变脏）。
     */
    private static RenderType pickLayer(BlockState state, RenderType fallback) {
        BakedModelRef ref = modelOf(state);
        if (ref == null) {
            return fallback;
        }
        ChunkRenderTypeSet types = ref.model().getRenderTypes(state, RandomSource.create(42L), ModelData.EMPTY);
        List<RenderType> layers = RenderType.chunkBufferLayers();
        for (int i = layers.size() - 1; i >= 0; i--) {
            if (types.contains(layers.get(i))) {
                return layers.get(i);
            }
        }
        return fallback;
    }

    private record BakedModelRef(net.minecraft.client.resources.model.BakedModel model) {
    }

    private static BakedModelRef modelOf(BlockState state) {
        try {
            return new BakedModelRef(Minecraft.getInstance().getBlockRenderer().getBlockModel(state));
        } catch (Throwable error) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 调试辅助
    // ------------------------------------------------------------------

    /**
     * 白名单机型清单（用于烘焙日志，便于实机核对"哪些机型被接了进来"）。
     *
     * <p>括号里是处理方式：{@code 整块} = 静态层剔除、由动态 pass 旋转整个方块模型；
     * {@code 补件} = 外壳留在静态层、动态 pass 只补上正在转的那部分。
     */
    public static String whitelistSummary() {
        return "轴/小齿轮/大齿轮/粉碎轮/飞轮块/转盘/动力轴/龙门轴（整块）"
                + " + 装箱轴/装箱齿轮/磨石/变速箱/离合/换挡/创造马达/小水车/机械轴承+风车轴承（顶板+半轴）"
                + "/鼓风机（扇叶+半轴）/动力泵/动力钻头（补件）";
    }
}
