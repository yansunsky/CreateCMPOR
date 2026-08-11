package com.createcmpor.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;

/** Base block for the future evaluator IO endpoints. */
public abstract class BaseIOBlock extends BaseEntityBlock {
    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");

    protected BaseIOBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(ACTIVE, false));
    }

    @Override
    protected abstract MapCodec<? extends BaseIOBlock> codec();

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ACTIVE);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                               Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (level.isClientSide) {
            return ItemInteractionResult.SUCCESS;
        }
        if (stack.isEmpty()) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof BaseIOBlockEntity ioBe)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        MutableComponent name;
        MutableComponent type;
        MutableComponent action;
        if (stack.getItem() instanceof BucketItem bucket && !bucket.content.isSame(Fluids.EMPTY)) {
            ResourceLocation fluidId = BuiltInRegistries.FLUID.getKey(bucket.content);
            name = Component.translatable(bucket.content.getFluidType().getDescriptionId()).withStyle(ChatFormatting.GRAY);
            type = Component.translatable("chat.createcmpor.fluid").withStyle(ChatFormatting.WHITE);
            if (ioBe.fluids.contains(fluidId)) {
                ioBe.fluids.remove(fluidId);
                action = Component.translatable("chat.createcmpor.removed").withStyle(ChatFormatting.WHITE);
            } else {
                ioBe.fluids.add(fluidId);
                action = Component.translatable("chat.createcmpor.added").withStyle(ChatFormatting.WHITE);
            }
        } else {
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
            name = stack.getHoverName().copy().withStyle(ChatFormatting.GRAY);
            type = Component.translatable("chat.createcmpor.item").withStyle(ChatFormatting.WHITE);
            if (ioBe.items.contains(itemId)) {
                ioBe.items.remove(itemId);
                action = Component.translatable("chat.createcmpor.removed").withStyle(ChatFormatting.WHITE);
            } else {
                ioBe.items.add(itemId);
                action = Component.translatable("chat.createcmpor.added").withStyle(ChatFormatting.WHITE);
            }
        }

        ioBe.setChanged();
        level.sendBlockUpdated(pos, state, state, Block.UPDATE_ALL);
        player.displayClientMessage(
                Component.translatable("chat.createcmpor.io_update", name, type, action)
                        .withStyle(ChatFormatting.GRAY), true);
        return ItemInteractionResult.SUCCESS;
    }
}
