package com.yansunsky.createcmpor.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
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

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new InputBlockEntity(pos, state);
    }
}
