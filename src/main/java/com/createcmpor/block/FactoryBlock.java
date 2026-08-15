package com.createcmpor.block;

import com.createcmpor.Config;
import com.createcmpor.init.ModBlockEntities;
import com.createcmpor.init.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * CreateCMPOR 平行工厂方块：评估固化产物，启动棒可还原为原 CompactMachines 机器。
 */
public class FactoryBlock extends Block implements EntityBlock {

    public FactoryBlock(Properties properties) {
        super(properties);
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
                FactoryBlockEntity.tick((ServerLevel) tickerLevel, pos, tickerState, factory);
            }
        };
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hitResult) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!(level.getBlockEntity(pos) instanceof FactoryBlockEntity factory)) {
            return InteractionResult.PASS;
        }
        ItemStack stack = player.getMainHandItem();
        if (!stack.is(ModItems.LAUNCHER_STICK.get())) {
            player.displayClientMessage(
                    Component.translatable("message.createcmpor.factory.revert_hint"), true);
            return InteractionResult.SUCCESS;
        }
        if (!Config.ENABLE_FACTORY_REVERT.get()) {
            player.displayClientMessage(
                    Component.translatable("message.createcmpor.factory.revert_disabled"), true);
            return InteractionResult.SUCCESS;
        }
        if (!factory.hasRestoreData()) {
            player.displayClientMessage(
                    Component.translatable("message.createcmpor.factory.revert_unavailable"), true);
            return InteractionResult.SUCCESS;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.PASS;
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
        return InteractionResult.SUCCESS;
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
        if (level.getBlockEntity(pos) instanceof FactoryBlockEntity factory
                && factory.getFluidHandler() != null) {
            return 0;
        }
        return 0;
    }
}
