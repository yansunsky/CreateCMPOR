package com.yansunsky.createcmpor.block;

import com.yansunsky.createcmpor.Config;
import com.yansunsky.createcmpor.CreateCMPOR;
import com.yansunsky.createcmpor.compat.cm.CreateNbtSanitizer;
import com.yansunsky.createcmpor.init.ModBlocks;
import com.yansunsky.createcmpor.init.ModItems;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.content.kinetics.base.KineticBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * CreateCMPOR 平行工厂方块：评估固化产物，kinetic 方块。
 *
 * <p>应力接口面（0.4.29 起，用户拍板）：
 * <ul>
 *   <li><b>展示态</b>（{@code encased=false}，含边框玻璃壳）：只有<b>底面</b>接应力，轴恒竖直；扳手不开面；</li>
 *   <li><b>安山机壳态</b>（{@code encased=true}）：六个 {@code shaft_*} 属性生效，扳手可<b>任意逐面开关、互不联动</b>
 *       （旧的"共轴约束"已废除——Create 的传播只看 {@code hasShaftTowards}，不看轴）。</li>
 * </ul>
 * 名义轴自 0.4.29 起恒为 {@code Axis.Y}（占位值，照搬 {@code createadditionallogistics:flexible_shaft}）。
 * 启动棒右键可还原为原 CompactMachines 机器。
 */
public class FactoryBlock extends KineticBlock implements EntityBlock {

    public static final BooleanProperty SHAFT_NORTH = BooleanProperty.create("shaft_north");
    public static final BooleanProperty SHAFT_SOUTH = BooleanProperty.create("shaft_south");
    public static final BooleanProperty SHAFT_EAST = BooleanProperty.create("shaft_east");
    public static final BooleanProperty SHAFT_WEST = BooleanProperty.create("shaft_west");
    public static final BooleanProperty SHAFT_UP = BooleanProperty.create("shaft_up");
    public static final BooleanProperty SHAFT_DOWN = BooleanProperty.create("shaft_down");

    public static final Map<Direction, BooleanProperty> SHAFT_BY_FACE = new EnumMap<>(Direction.class);

    /**
     * 是否处于「传统包壳模式」（0.4.0 展示模式的对立面）。
     *
     * <p><b>属性默认值必须是 {@code true}</b>：旧存档的 blockstate palette 里没有这个属性，
     * 原版解码器（{@code StateDefinition}/{@code StateHolder}）对缺失属性取"属性默认值"，
     * 于是老工厂自动落回传统六面开口模式，玩家既有应力布局不受影响；
     * 新放置的工厂由 {@link #getStateForPlacement} 显式返回 {@code false}（展示模式）。
     *
     * <p>{@code false} = 展示模式：方块内部渲染微缩产线、<b>只有底面</b>能接应力（轴恒竖直）。
     * {@code true} = 安山机壳模式：六个 {@code shaft_*} 属性生效，扳手可逐面任意开关（互不联动）。
     */
    public static final BooleanProperty ENCASED = BooleanProperty.create("encased");

    /**
     * 展示模式下是否已用 Create 的「边框玻璃」包壳（0.4.0）。
     *
     * <p>默认 {@code false} = 自带玻璃罩（模型 {@code factory_display}：3px 底座 + 四角立柱 + 顶部横梁 + 四面原版玻璃）；
     * {@code true} = 手持 {@code create:framed_glass} 右键后<b>撤掉自带罩子</b>（模型 {@code factory_display_glass}：
     * 只剩底座）——语义是"卸下自带外壳"，便于无遮挡观察（用户 0.4.0 定稿，见 commit 646c4fe）。
     * 只在 {@code ENCASED=false}（展示模式）下有意义。
     *
     * <p>与 {@link #ENCASED} 拆成两个属性的理由：现有全部 {@code !ENCASED} 分支的语义恰好等于
     * "新式展示形态（含玻璃壳）"，拆开可以不动一行既有逻辑；旧存档缺该属性时取默认值 false，零迁移。
     */
    public static final BooleanProperty GLASS_SHELL = BooleanProperty.create("glass");

    static {
        SHAFT_BY_FACE.put(Direction.NORTH, SHAFT_NORTH);
        SHAFT_BY_FACE.put(Direction.SOUTH, SHAFT_SOUTH);
        SHAFT_BY_FACE.put(Direction.EAST, SHAFT_EAST);
        SHAFT_BY_FACE.put(Direction.WEST, SHAFT_WEST);
        SHAFT_BY_FACE.put(Direction.UP, SHAFT_UP);
        SHAFT_BY_FACE.put(Direction.DOWN, SHAFT_DOWN);
    }

    public FactoryBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState()
                // 注意：这里是"方块默认状态"，不等于"新放置状态"——新放置走 getStateForPlacement(=false)。
                // 默认状态设为 true 是为了让旧存档缺属性时解析成传统模式（见 ENCASED 注释）。
                .setValue(ENCASED, true)
                .setValue(GLASS_SHELL, false)
                .setValue(SHAFT_NORTH, false)
                .setValue(SHAFT_SOUTH, false)
                .setValue(SHAFT_EAST, false)
                .setValue(SHAFT_WEST, false)
                .setValue(SHAFT_UP, false)
                // shaft_down 的默认值 = true：展示态的底面接口是常态，包壳后保留。
                // （旧存档缺该属性时解析成 true＝底面开；旧版工厂的六面在包壳时由玩家扳手打开。）
                .setValue(SHAFT_DOWN, true));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ENCASED, GLASS_SHELL, SHAFT_NORTH, SHAFT_SOUTH, SHAFT_EAST, SHAFT_WEST, SHAFT_UP, SHAFT_DOWN);
    }

    /**
     * 扳手：点击任意面 → toggle 该面接口轴。
     *
     * <p>0.4.29（用户拍板）：**废除此前的"共轴约束"**——不再在开启某面时自动关闭其他轴向的开口面。
     * 依据（源码级，见 Create {@code RotationPropagator.getRotationSpeedModifier}）：Create 的连通判定
     * **只看两侧 {@code hasShaftTowards}**，从不读 {@code getRotationAxis}，因此"一个方块只能有一个轴"
     * **不是动力学硬约束**。参考实现 {@code createadditionallogistics:flexible_shaft} 就是六面任意开轴
     * （其 {@code getRotationAxis} 恒返回常量 {@code Axis.Y}）。</p>
     *
     * <p>展示模式（{@code !ENCASED}）仍不开面：接口面固定为底面（轴恒竖直）。</p>
     */
    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        Direction face = context.getClickedFace();
        BlockPos pos = context.getClickedPos();
        if (!state.getValue(ENCASED)) {
            // 展示模式：接口面固定在底面，扳手不开面——要传统六面开口请先用安山机壳包壳
            return InteractionResult.SUCCESS;
        }
        boolean open = state.getValue(SHAFT_BY_FACE.get(face));
        BlockState newState = toggleFace(state, face, !open);
        KineticBlockEntity.switchToBlockState(level, pos, newState);
        IWrenchable.playRotateSound(level, pos);
        return InteractionResult.SUCCESS;
    }

    /**
     * toggle 指定面开口。0.4.29：**只动这一面**，不再联动关闭其他轴向的面（六面各自独立）。
     *
     * <p>原先的"共轴约束"是 0.1.29 引入的，动机是 Create 的"一个 BE 一个轴"印象；但该印象只影响
     * 渲染与 {@code areStatesKineticallyEquivalent}，不影响传播（见 {@link #onWrenched} 的说明）。</p>
     */
    private static BlockState toggleFace(BlockState state, Direction face, boolean open) {
        return state.setValue(SHAFT_BY_FACE.get(face), open);
    }

    /**
     * 某面的传动轴接口是否可用（**唯一判据**，供 {@link #hasShaftTowards}、渲染与 io_extension 触达判定共用）。
     *
     * <p>三态语义（用户 2026-09-30 拍板）：
     * <ul>
     *   <li>{@code encased=false}（展示态，含边框玻璃壳）：**只有底面**是应力接口（轴恒竖直）；</li>
     *   <li>{@code encased=true}（安山机壳态）：**六面任意**，完全由 {@code shaft_*} 属性决定。</li>
     * </ul>
     *
     * <p>注意 {@link #getRotationAxis} 自 0.4.29 起恒为 {@code Axis.Y}（照搬 flexible_shaft 的占位语义），
     * 因此**不要**用"轴"来判断某面是否可用——一律走本方法。</p>
     */
    public static boolean isShaftFaceOpen(BlockState state, Direction face) {
        return state.getValue(SHAFT_BY_FACE.get(face));
    }

    /**
     * 潜行 + 扳手：拆除工厂，掉落携带完整 BE NBT 的物品（便于迁移重建）。
     *
     * <p>复刻 {@link IWrenchable#onSneakWrenched} 默认流程（BreakEvent → 掉落 → 移除 → 音效），
     * 但掉落物改为携带完整方块实体数据（经 {@link CreateNbtSanitizer} 清理 Create 网络缓存字段）。</p>
     */
    @Override
    public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        BlockPos pos = context.getClickedPos();
        Player player = context.getPlayer();
        if (!(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.SUCCESS;
        }
        if (!state.getValue(ENCASED) && state.getValue(GLASS_SHELL)) {
            // 三段式第一段：先脱掉边框玻璃壳（仍是展示模式）
            KineticBlockEntity.switchToBlockState(level, pos, state.setValue(GLASS_SHELL, false));
            playGlassSound(level, pos, true);
            return InteractionResult.SUCCESS;
        }
        if (state.getValue(ENCASED)) {
            // 两段式（用户 2026-09-29 拍板）：有壳时潜行扳手 = 去壳，回到展示模式；
            // 无壳时才走下面的"拆下工厂并携带完整 NBT"。与 Create 的"潜行扳手去壳"惯例一致。
            KineticBlockEntity.switchToBlockState(level, pos, state.setValue(ENCASED, false));
            IWrenchable.playRotateSound(level, pos);
            return InteractionResult.SUCCESS;
        }
        BlockEvent.BreakEvent breakEvent = new BlockEvent.BreakEvent(level, pos, state, player);
        NeoForge.EVENT_BUS.post(breakEvent);
        if (breakEvent.isCanceled()) {
            return InteractionResult.SUCCESS;
        }
        // 取下前：从组索引移除本位置（放回任意位置后仍可组还原——索引跟随实际位置）
        removeFromFactoryIndex(serverLevel, pos, level.getBlockEntity(pos));
        dropFactoryWithData(serverLevel, pos, level.getBlockEntity(pos));
        state.spawnAfterBreak(serverLevel, pos, context.getItemInHand(), false);
        level.destroyBlock(pos, false);
        IWrenchable.playRemoveSound(level, pos);
        return InteractionResult.SUCCESS;
    }

    /**
     * 玩家挖掘：掉落携带完整 BE NBT 的工厂方块（便于迁移重建）。
     * 无条件掉落（同泥土/沙子），不检查工具。
     *
     * <p>不调用 super：掉落完全由本方法控制；loot table 仅用于爆炸等非玩家破坏路径。</p>
     */
    @Override
    public void playerDestroy(Level level, Player player, BlockPos pos, BlockState state,
                              @Nullable BlockEntity blockEntity, ItemStack tool) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        removeFromFactoryIndex(serverLevel, pos, blockEntity);
        dropFactoryWithData(serverLevel, pos, blockEntity);
    }

    /**
     * 非玩家破坏路径的数据保全（0.4.29 新增）。
     *
     * <p><b>问题</b>：{@link #playerDestroy} 与 {@link #onSneakWrenched} 之外的一切破坏方式都走原版
     * loot table，而本方块注册的 loot table 只掉裸物品（{@code loot_table/blocks/factory_block.json}）
     * ⇒ 房间码 / 还原数据 / 产线 pattern / 微缩快照<b>全部丢失</b>。可达路径已逐一取证：
     * <ul>
     *   <li>Create 的动力学冲突自毁 {@code RotationPropagator}（tooFast / flicker / 反向 / 成环）与
     *       {@code GeneratingKineticBlockEntity.applyNewSpeed} —— 均为 {@code world.destroyBlock(pos, true)}；</li>
     *   <li>爆炸（TNT / 爬行者；工厂抗爆值只有 3.0）；</li>
     *   <li>Create 钻头/锯等机械破坏、装置（contraption）落点覆盖。</li>
     * </ul>
     *
     * <p><b>为什么覆写这里而不是挂 {@code BlockDropsEvent}</b>：{@code BlockBehaviour#getDrops} 是所有
     * loot 路径的<b>唯一漏斗</b>——玩家破坏、{@code destroyBlock} 全系列、爆炸
     * （{@code BlockBehaviour#onExplosionHit} 直接调 {@code state.getDrops(...)}）、Create 的
     * {@code BlockHelper} 机械破坏、装置落点全部经它；而 {@code BlockDropsEvent} 只在
     * {@code Block.dropResources} 里触发，<b>漏掉爆炸与机械破坏</b>。
     *
     * <p>注意不会与 {@link #playerDestroy} 重复掉落：后者刻意不调 {@code super}，掉落完全由它自己控制。
     * 本方法只在"loot table 真的产出了工厂物品"时给它挂 NBT，因此 {@code survives_explosion} 等
     * 原有语义（爆炸可能什么都不掉）<b>保持不变</b>。
     */
    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        List<ItemStack> drops = super.getDrops(state, params);
        if (drops.isEmpty()) {
            return drops;
        }
        BlockEntity be = params.getOptionalParameter(LootContextParams.BLOCK_ENTITY);
        if (be instanceof FactoryBlockEntity factory) {
            ItemStack template = new ItemStack(ModBlocks.FACTORY.get());
            BlockPos pos = be.getBlockPos();
            for (ItemStack drop : drops) {
                if (ItemStack.isSameItem(drop, template)) {
                    attachFactoryData(factory, drop, pos);
                    break;
                }
            }
        }
        return drops;
    }

    /** 掉落携带完整 BE NBT 的工厂方块物品；BE 数据经 CreateNbtSanitizer 清理 Create 网络缓存字段。 */
    private static void dropFactoryWithData(ServerLevel level, BlockPos pos,
                                            @Nullable BlockEntity blockEntity) {
        ItemStack drop = new ItemStack(ModBlocks.FACTORY.get());
        if (blockEntity != null) {
            attachFactoryData(blockEntity, drop, pos);
        }
        Block.popResource(level, pos, drop);
    }

    /**
     * 把工厂 BE 的完整数据（已清理 + 体积兜底）写进掉落/产出的物品。
     * 供 {@link #playerDestroy}、{@link #onSneakWrenched}（走 {@link #dropFactoryWithData}）与
     * {@link #getDrops}（非玩家路径）共用，保证两条通道的数据格式完全一致。
     */
    private static void attachFactoryData(BlockEntity blockEntity, ItemStack drop, BlockPos pos) {
        CompoundTag tag = CreateNbtSanitizer.sanitizeBlockEntityTag(
                blockEntity.saveWithFullMetadata(blockEntity.getLevel().registryAccess()));
        guardItemPreviewSize(tag, pos);
        BlockItem.setBlockEntityData(drop, blockEntity.getType(), tag);
    }

    /**
     * 物品侧体积兜底：把写进工厂物品的 BE NBT 压到 {@link Config#PREVIEW_ITEM_MAX_KB} 以内。
     *
     * <p>工厂物品的 NBT（含整份微缩快照）会随 ItemStack 走进容器内容包 / 槽位包 / 实体数据包，
     * 而客户端读包内 NBT 有 2 MB 硬配额（{@code FriendlyByteBuf.DEFAULT_NBT_QUOTA}）——
     * 单品 NBT 越界 = 收包方解析失败/断线。方块侧已有 {@code previewSyncMaxKb} 闸门，物品侧原先没有，
     * 这里是补上的那一层（用户 2026-09-29 认可"护栏只是兜底"）。
     *
     * <p>逐级瘦身，只在真的越界时发生（正常工厂 ~130 KB 一分不动）：
     * <ol>
     *   <li>丢掉预览的<b>快照实体段</b>（装置/动物，最占体积且纯装饰）；</li>
     *   <li>仍越界则丢掉<b>整份微缩预览</b>（物品照常可放置，只是放下后不显示微缩）。</li>
     * </ol>
     * 两级都打日志，便于事后定位"某个工厂物品体积异常"。
     */
    private static void guardItemPreviewSize(CompoundTag tag, BlockPos pos) {
        int budgetBytes = Config.PREVIEW_ITEM_MAX_KB.get() * 1024;
        int size = tag.sizeInBytes();
        if (size <= budgetBytes) {
            return;
        }
        CompoundTag preview = tag.getCompound("preview");
        if (preview.contains("entities")) {
            preview.remove("entities");
            int after = tag.sizeInBytes();
            CreateCMPOR.LOGGER.info("[预览] 物品侧体积兜底：{} 丢弃快照实体段 {} KB → {} KB（上限 {} KB）",
                    pos, size / 1024, after / 1024, budgetBytes / 1024);
            size = after;
        }
        if (size > budgetBytes) {
            tag.remove("preview");
            CreateCMPOR.LOGGER.warn("[预览] 物品侧体积兜底：{} 丢弃整份微缩快照 {} KB → {} KB"
                            + "（上限 {} KB；该物品放下后不显示微缩，房间/产线数据不受影响）",
                    pos, size / 1024, tag.sizeInBytes() / 1024, budgetBytes / 1024);
        }
    }

    /** 工厂被取下/破坏前：从组索引移除本位置（确保索引只记录实际存在的工厂，重放可组还原）。 */
    private static void removeFromFactoryIndex(ServerLevel level, BlockPos pos,
                                               @Nullable BlockEntity blockEntity) {
        if (!(blockEntity instanceof FactoryBlockEntity factory)) {
            return;
        }
        String room = factory.getRoomCode();
        if (room == null || room.isBlank()) {
            return;
        }
        com.yansunsky.createcmpor.evaluation.FactoryIndexSavedData.get(level.getServer())
                .removeFactoryPosition(room, net.minecraft.core.GlobalPos.of(level.dimension(), pos));
    }

    /** 接口轴/包壳状态变化都会改变动力学等价性（网络需重建）。 */
    @Override
    protected boolean areStatesKineticallyEquivalent(BlockState oldState, BlockState newState) {
        if (newState.getBlock() instanceof FactoryBlock && oldState.getBlock() instanceof FactoryBlock) {
            if (newState.getValue(ENCASED) != oldState.getValue(ENCASED)) {
                // 包壳状态会改变 getRotationAxis（展示模式恒 Y、传统模式取开口面轴）→ 必须报"不等价"。
                // 返回 true 会让 KineticBlock#updateIndirectNeighbourShapes 的重挂钩子失效 → 网络不重建、轴不转。
                return false;
            }
            for (BooleanProperty property : SHAFT_BY_FACE.values()) {
                if (newState.getValue(property) != oldState.getValue(property)) {
                    return false;
                }
            }
        }
        return super.areStatesKineticallyEquivalent(oldState, newState);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // 新放置 = 展示模式（encased=false）：方块内渲染微缩产线、只有底面接应力。
        // 老存档缺 encased 属性时取"属性默认值 true"，落回传统模式——见 ENCASED 注释。
        // shaft_down=true：展示态的底面接口是常态，包壳（ENCASED 置 true）后应**保留**该接口，
        // 玩家再用扳手自由增开其余五面（0.4.29 起六面各自独立）。
        return defaultBlockState()
                .setValue(ENCASED, false)
                .setValue(SHAFT_DOWN, true);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FactoryBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return (tickerLevel, pos, tickerState, entity) -> {
            if (entity instanceof FactoryBlockEntity factory) {
                factory.tick();
            }
        };
    }

    /**
     * 开口面可接传动轴（应力接口）。
     *
     * <p>0.4.29 起语义统一：展示态（含边框玻璃壳）**只有底面**；安山机壳态**六面任意**
     * （六面全由 {@code shaft_*} 决定，与轴无关——轴已退化为占位值，见 {@link #getRotationAxis}）。</p>
     */
    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        if (!state.getValue(ENCASED)) {
            // 展示模式：只有底面接应力（轴恒竖直）——底座在方块底部，传动杆只在底座里渲染
            return face == Direction.DOWN;
        }
        return isShaftFaceOpen(state, face);
    }

    /**
     * 名义旋转轴：**恒为 {@code Axis.Y}**（0.4.29，照搬 {@code createadditionallogistics:flexible_shaft}）。
     *
     * <p>为什么可以/需要恒为常量：
     * <ul>
     *   <li>Create 的连通判定（{@code RotationPropagator.getRotationSpeedModifier}）**只查两侧
     *       {@code hasShaftTowards}**，从不读本方法；齿轮分支要求 {@code block instanceof ICogWheel}，
     *       工厂不是，故本方法在动力学里**不可达**；</li>
     *   <li>旧实现返回"第一个为 true 的开口面的轴"，而 {@code SHAFT_BY_FACE} 是 {@code EnumMap}
     *       （枚举序 DOWN,UP,NORTH,SOUTH,WEST,EAST）⇒ 玩家**多开一个面就可能悄悄改变全厂轴向**，
     *       进而让 {@link FactoryBlockEntity#getGeneratedSpeed} 的方向翻转。恒为常量可彻底消除该抖动。</li>
     * </ul>
     *
     * <p>本方法现在只影响：无 Flywheel 时的 BER 绘制（{@code KineticBlockEntityRenderer.renderRotatingBuffer}
     * 会读它——但 {@code FactoryRenderer} 自 0.4.30 起**已不调 {@code super.renderSafe}**，故实际不再被读）、
     * {@code areStatesKineticallyEquivalent} 与调试/粒子。三种形态统一返回 Y，配合
     * {@link #areStatesKineticallyEquivalent} 的显式覆写保证"任何开口面变化都重建网络"。</p>
     */
    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return Direction.Axis.Y;
    }

    /** 手持物品右键：启动棒 → 还原；其他物品（含 Create 扳手）交给物品层处理（扳手旋转）。 */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (isFramedGlass(stack) && !state.getValue(ENCASED) && !state.getValue(GLASS_SHELL)) {
            // 边框玻璃包壳（0.4.0）：展示模式下的可选外壳。同样不消耗物品（与 Create 的机壳包壳语义一致）。
            if (level.isClientSide) {
                return ItemInteractionResult.SUCCESS;
            }
            KineticBlockEntity.switchToBlockState(level, pos, state.setValue(GLASS_SHELL, true));
            playGlassSound(level, pos, false);
            return ItemInteractionResult.SUCCESS;
        }
        if (isAndesiteCasing(stack) && !state.getValue(ENCASED)) {
            // 包壳（0.4.0）：复用 Create「手持机壳右键包壳」的语义——**不消耗机壳物品**（Create 的
            // EncasableBlock#tryEncase 全路径没有 shrink，机壳相当于"皮肤"）。
            // 这里刻意用「单方块 + encased 属性」而不是 Create 的 EncasingRegistry：后者两个方块必须用
            // 两个 BlockEntityType，原版 LevelChunk 会因 validBlocks 不匹配而销毁旧 BE，
            // 我们的 FactoryBlockEntity 里装着房间码/还原数据/评估状态，绝不能被丢弃。
            if (level.isClientSide) {
                return ItemInteractionResult.SUCCESS;
            }
            KineticBlockEntity.switchToBlockState(level, pos, state.setValue(ENCASED, true));
            playEncaseSound(level, pos);
            return ItemInteractionResult.SUCCESS;
        }
        if (level.isClientSide) {
            return stack.is(ModItems.LAUNCHER_STICK.get())
                    ? ItemInteractionResult.SUCCESS
                    : ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!(level.getBlockEntity(pos) instanceof FactoryBlockEntity factory)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!stack.is(ModItems.LAUNCHER_STICK.get())) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!Config.ENABLE_FACTORY_REVERT.get()) {
            player.displayClientMessage(
                    Component.translatable("message.createcmpor.factory.revert_disabled"), true);
            return ItemInteractionResult.SUCCESS;
        }
        if (!factory.hasRestoreData()) {
            player.displayClientMessage(
                    Component.translatable("message.createcmpor.factory.revert_unavailable"), true);
            return ItemInteractionResult.SUCCESS;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return ItemInteractionResult.SUCCESS;
        }
        // 多工厂组还原：同 roomCode 组内所有工厂一起还原（数量校验）
        if (factory.getFactoryCount() > 1 && !factory.getRoomCode().isBlank()) {
            return revertGroup(serverLevel, pos, factory, stack, player);
        }
        if (factory.revertToMachine(serverLevel)) {
            if (!player.isCreative()) {
                stack.shrink(1);
            }
            player.displayClientMessage(
                    Component.translatable("message.createcmpor.factory.reverted"), false);
        } else {
            player.displayClientMessage(
                    Component.translatable("message.createcmpor.factory.revert_failed"), true);
        }
        return ItemInteractionResult.SUCCESS;
    }

    /** 手持物是否为 Create 的边框玻璃（按注册名判断，避免依赖 Create 静态条目的可见性）。 */
    private static boolean isFramedGlass(ItemStack stack) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem())
                .equals(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("create", "framed_glass"));
    }

    /**
     * 玻璃壳的装/拆音效。
     *
     * <p>刻意写死 {@code SoundEvents.GLASS_PLACE / GLASS_BREAK}：现有 {@link #playEncaseSound} 取的是
     * **新方块状态的 SoundType**（工厂是 METAL），装玻璃会响金属声，与材质不符。
     */
    private static void playGlassSound(Level level, BlockPos pos, boolean removing) {
        level.playSound(null, pos,
                removing ? net.minecraft.sounds.SoundEvents.GLASS_BREAK : net.minecraft.sounds.SoundEvents.GLASS_PLACE,
                net.minecraft.sounds.SoundSource.BLOCKS, 0.9F, removing ? 1.1F : 1.0F);
    }

    /** 手持物是否为 Create 的安山机壳（按注册名判断，避免依赖 Create 的静态条目在附属环境下的可见性）。 */
    private static boolean isAndesiteCasing(ItemStack stack) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem())
                .equals(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("create", "andesite_casing"));
    }

    /** 包壳音效：照抄 Create {@code EncasableBlock#playEncaseSound} 的公式（新方块 place 音、(vol+1)/2、pitch×0.8）。 */
    private static void playEncaseSound(Level level, BlockPos pos) {
        net.minecraft.world.level.block.SoundType soundType = level.getBlockState(pos).getSoundType();
        level.playSound(null, pos, soundType.getPlaceSound(), net.minecraft.sounds.SoundSource.BLOCKS,
                (soundType.getVolume() + 1.0F) / 2.0F, soundType.getPitch() * 0.8F);
    }

    /**
     * 多工厂组还原：同 roomCode 组内所有工厂一起还原。
     * <ul>
     *   <li>数量校验：索引中同 roomCode 的工厂数 == factoryCount 才还原；否则提示"请重新排列子工厂"。</li>
     *   <li>被右键的还原为原 CM 机器，其余工厂直接消失（不掉落）。</li>
     * </ul>
     */
    private static ItemInteractionResult revertGroup(ServerLevel level, BlockPos pos,
                                                     FactoryBlockEntity factory, ItemStack stack, Player player) {
        String roomCode = factory.getRoomCode();
        int expected = factory.getFactoryCount();
        java.util.List<net.minecraft.core.GlobalPos> positions =
                com.yansunsky.createcmpor.evaluation.FactoryIndexSavedData.get(level.getServer())
                        .factoriesForRoom(roomCode).orElse(java.util.List.of());
        java.util.List<BlockPos> present = new java.util.ArrayList<>();
        for (net.minecraft.core.GlobalPos gp : positions) {
            if (gp.dimension().equals(level.dimension()) && level.isLoaded(gp.pos())
                    && level.getBlockEntity(gp.pos()) instanceof FactoryBlockEntity) {
                present.add(gp.pos());
            }
        }
        if (present.size() != expected) {
            player.displayClientMessage(
                    Component.translatable("message.createcmpor.factory.group_rearrange",
                            expected, present.size()), true);
            return ItemInteractionResult.SUCCESS;
        }
        // 数量正确：被右键的还原为原机器，其余消失
        for (BlockPos p : present) {
            if (!p.equals(pos) && level.getBlockEntity(p) instanceof FactoryBlockEntity other) {
                level.removeBlockEntity(p);
                level.setBlock(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
                        net.minecraft.world.level.block.Block.UPDATE_ALL);
            }
        }
        com.yansunsky.createcmpor.evaluation.FactoryIndexSavedData.get(level.getServer())
                .removeFactory(roomCode);
        if (factory.revertToMachine(level)) {
            if (!player.isCreative()) {
                stack.shrink(1);
            }
            player.displayClientMessage(
                    Component.translatable("message.createcmpor.factory.reverted"), false);
        } else {
            player.displayClientMessage(
                    Component.translatable("message.createcmpor.factory.revert_failed"), true);
        }
        return ItemInteractionResult.SUCCESS;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public boolean triggerEvent(BlockState state, Level level, BlockPos pos, int id, int param) {
        super.triggerEvent(state, level, pos, id, param);
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity != null && blockEntity.triggerEvent(id, param);
    }

    @Override
    public boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    public int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return 0;
    }
}
