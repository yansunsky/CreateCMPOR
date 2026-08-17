package com.createcmpor.block;

import com.createcmpor.Config;
import com.createcmpor.compat.cm.CreateNbtSanitizer;
import com.createcmpor.init.ModBlockEntities;
import com.createcmpor.init.ModItems;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.content.kinetics.base.KineticBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.simpleRelays.ICogWheel;
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
                .setValue(SHAFT_NORTH, false)
                .setValue(SHAFT_SOUTH, false)
                .setValue(SHAFT_EAST, false)
                .setValue(SHAFT_WEST, false)
                .setValue(SHAFT_UP, false)
                .setValue(SHAFT_DOWN, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(SHAFT_NORTH, SHAFT_SOUTH, SHAFT_EAST, SHAFT_WEST, SHAFT_UP, SHAFT_DOWN);
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
        BlockEvent.BreakEvent breakEvent = new BlockEvent.BreakEvent(level, pos, state, player);
        NeoForge.EVENT_BUS.post(breakEvent);
        if (breakEvent.isCanceled()) {
            return InteractionResult.SUCCESS;
        }
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
        dropFactoryWithData(serverLevel, pos, blockEntity);
    }

    /** 掉落携带完整 BE NBT 的工厂方块物品；BE 数据经 CreateNbtSanitizer 清理 Create 网络缓存字段。 */
    private static void dropFactoryWithData(ServerLevel level, BlockPos pos,
                                            @Nullable BlockEntity blockEntity) {
        ItemStack drop = new ItemStack(com.createcmpor.init.ModBlocks.FACTORY.get());
        if (blockEntity != null) {
            CompoundTag tag = CreateNbtSanitizer.sanitizeBlockEntityTag(
                    blockEntity.saveWithFullMetadata(level.registryAccess()));
            BlockItem.setBlockEntityData(drop, blockEntity.getType(), tag);
        }
        Block.popResource(level, pos, drop);
    }

    /** 接口轴状态变化会改变动力学等价性（网络需重建）。 */
    @Override
    protected boolean areStatesKineticallyEquivalent(BlockState oldState, BlockState newState) {
        if (newState.getBlock() instanceof FactoryBlock && oldState.getBlock() instanceof FactoryBlock) {
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
        // 六面开口：放置方向不再决定接口面，默认全闭。
        return defaultBlockState();
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

    /** 开口面可接传动轴（应力接口）。 */
    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        return state.getValue(SHAFT_BY_FACE.get(face));
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
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
