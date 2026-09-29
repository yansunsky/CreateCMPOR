package com.yansunsky.createcmpor.block;

import com.yansunsky.createcmpor.Config;
import com.yansunsky.createcmpor.CreateCMPOR;
import com.yansunsky.createcmpor.compat.cm.CreateNbtSanitizer;
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
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;

/**
 * CreateCMPOR 平行工厂方块：评估固化产物，kinetic 方块。
 *
 * <p>六面开口（兜底方案）：六个面各自有独立的接口轴布尔属性
 * （{@code shaft_north/shaft_south/shaft_east/shaft_west/shaft_up/shaft_down}）。
 * 扳手点击任意面 → toggle 该面接口轴；为保证单一旋转轴，开启新轴向上的面时
 * 自动关闭其他轴向上的开口面（允许同一轴向的对面同时开口，如 north+south）。
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
     * {@code true} = 传统模式：六个 {@code shaft_*} 属性生效，扳手可逐面开关。
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
                .setValue(SHAFT_DOWN, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ENCASED, GLASS_SHELL, SHAFT_NORTH, SHAFT_SOUTH, SHAFT_EAST, SHAFT_WEST, SHAFT_UP, SHAFT_DOWN);
    }

    /** 扳手：点击任意面 → toggle 该面接口轴；开启非当前轴向的面时自动关闭其他轴向开口（共轴约束）。 */
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

    /** toggle 指定面开口；若开启，先关闭所有非该面轴向的开口面。 */
    private static BlockState toggleFace(BlockState state, Direction face, boolean open) {
        if (!open) {
            return state.setValue(SHAFT_BY_FACE.get(face), false);
        }
        BlockState result = state;
        for (Map.Entry<Direction, BooleanProperty> entry : SHAFT_BY_FACE.entrySet()) {
            Direction d = entry.getKey();
            if (d.getAxis() != face.getAxis()) {
                result = result.setValue(entry.getValue(), false);
            }
        }
        return result.setValue(SHAFT_BY_FACE.get(face), true);
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

    /** 掉落携带完整 BE NBT 的工厂方块物品；BE 数据经 CreateNbtSanitizer 清理 Create 网络缓存字段。 */
    private static void dropFactoryWithData(ServerLevel level, BlockPos pos,
                                            @Nullable BlockEntity blockEntity) {
        ItemStack drop = new ItemStack(com.yansunsky.createcmpor.init.ModBlocks.FACTORY.get());
        if (blockEntity != null) {
            CompoundTag tag = CreateNbtSanitizer.sanitizeBlockEntityTag(
                    blockEntity.saveWithFullMetadata(level.registryAccess()));
            guardItemPreviewSize(tag, pos);
            BlockItem.setBlockEntityData(drop, blockEntity.getType(), tag);
        }
        Block.popResource(level, pos, drop);
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
        return defaultBlockState().setValue(ENCASED, false);
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

    /** 开口面可接传动轴（应力接口）；展示模式下固定只有底面。 */
    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        if (!state.getValue(ENCASED)) {
            // 展示模式：只有底面接应力（轴恒竖直）——底座在方块底部，传动杆只在底座里渲染
            return face == Direction.DOWN;
        }
        return state.getValue(SHAFT_BY_FACE.get(face));
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        if (!state.getValue(ENCASED)) {
            return Direction.Axis.Y;
        }
        for (Map.Entry<Direction, BooleanProperty> entry : SHAFT_BY_FACE.entrySet()) {
            if (state.getValue(entry.getValue())) {
                return entry.getKey().getAxis();
            }
        }
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
