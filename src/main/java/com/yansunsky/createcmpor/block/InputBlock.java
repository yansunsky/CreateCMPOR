package com.yansunsky.createcmpor.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

public class InputBlock extends BaseIOBlock {
    public static final MapCodec<InputBlock> CODEC = simpleCodec(InputBlock::new);

    public InputBlock() {
        this(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).strength(3.0f, 6.0f));
    }

    public InputBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseIOBlock> codec() {
        return CODEC;
    }

    /**
     * 空手右键：开发期（devManualActivation）手动切换激活状态（与应力方块一致的 debug 功能）。
     * 手持物品/流体桶仍是配置白名单（见 {@link BaseIOBlock#useItemOn}）。
     * 生产模式静默不提示。
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (!level.isClientSide() && com.yansunsky.createcmpor.Config.DEV_MANUAL_ACTIVATION.get()) {
            boolean newActive = !state.getValue(ACTIVE);
            level.setBlock(pos, state.setValue(ACTIVE, newActive), Block.UPDATE_ALL);
            com.yansunsky.createcmpor.CreateCMPOR.LOGGER.debug("[CreateCMPOR] input_block @{} 手动切换 -> {}", pos, newActive);
            player.displayClientMessage(
                    net.minecraft.network.chat.Component.translatable(
                            newActive ? "message.createcmpor.io.activated"
                                    : "message.createcmpor.io.deactivated"),
                    true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new InputBlockEntity(pos, state);
    }
}
