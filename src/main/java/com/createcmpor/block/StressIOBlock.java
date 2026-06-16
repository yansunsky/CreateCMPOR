package com.createcmpor.block;

import com.createcmpor.init.ModBlockEntities;
import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 应力 IO 方块。
 *
 * <p>复用创造马达（{@code create:creative_motor}）的外观与发电逻辑：继承 {@link DirectionalKineticBlock}，
 * 沿 {@code FACING} 方向接入 Create 应力网络。
 *
 * <p>含 {@code ACTIVE} 状态，默认 {@code false}（不激活）。空手右键可切换激活/停用
 * （参考 CMPOR 输入方块「默认不激活、需激活后工作」的设计）。激活后由
 * {@link StressIOBlockEntity} 根据本地应力网络盈亏作为应力源/读取器工作。
 */
public class StressIOBlock extends DirectionalKineticBlock implements IBE<StressIOBlockEntity> {

    /** 是否已激活。默认 false，空手右键切换。 */
    public static final BooleanProperty ACTIVE = BlockStateProperties.POWERED;

    public StressIOBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(ACTIVE, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(ACTIVE);
    }

    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        // 与创造马达一致：仅朝向面可接轴
        return face == state.getValue(FACING);
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(FACING).getAxis();
    }

    /** 空手右键：切换激活状态。 */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (!level.isClientSide()) {
            // 生产模式：仅由评估激活，右键无效
            if (!com.createcmpor.Config.DEV_MANUAL_ACTIVATION.get()) {
                player.displayClientMessage(
                        net.minecraft.network.chat.Component.translatable(
                                "message.createcmpor.stress_io.auto_only"),
                        true);
                return InteractionResult.sidedSuccess(level.isClientSide());
            }
            // 开发期：右键手动切换激活
            boolean newActive = !state.getValue(ACTIVE);
            level.setBlock(pos, state.setValue(ACTIVE, newActive), Block.UPDATE_ALL);
            // 状态变化后重新评估发电（让父类的应力源逻辑及时刷新）
            if (level.getBlockEntity(pos) instanceof StressIOBlockEntity be) {
                be.onActiveChanged();
            }
            com.createcmpor.CreateCMPOR.LOGGER.debug("[CreateCMPOR] stress_io @{} 手动切换 -> {}", pos, newActive);
            player.displayClientMessage(
                    net.minecraft.network.chat.Component.translatable(
                            newActive ? "message.createcmpor.stress_io.activated"
                                    : "message.createcmpor.stress_io.deactivated"),
                    true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    public Class<StressIOBlockEntity> getBlockEntityClass() {
        return StressIOBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends StressIOBlockEntity> getBlockEntityType() {
        return ModBlockEntities.STRESS_IO.get();
    }
}
