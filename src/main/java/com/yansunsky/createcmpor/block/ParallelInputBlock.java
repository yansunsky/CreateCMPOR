package com.yansunsky.createcmpor.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import org.jetbrains.annotations.Nullable;

/**
 * 并行空间输入方块。
 *
 * <p>配置方式与 {@link InputBlock}（压缩空间输入方块）一致：手持物品/流体桶右键添加/移除白名单。
 *
 * <p><b>与普通输入方块的区别</b>：评估期根据配置列表<b>分分支评估</b>——
 * 副本中本方块通过 {@link ParallelInputBlockEntity#setBranchIndex(int)} 只暴露配置列表中的
 * 第 N 个物品（其余物品不可抽取），从而让每次评估只记录一条输入线的流量。全部分支评估完成后，
 * 一次评估固化出 N 个工厂方块（每个对应一种输入）。</p>
 */
public class ParallelInputBlock extends BaseIOBlock {
    public static final MapCodec<ParallelInputBlock> CODEC = simpleCodec(ParallelInputBlock::new);

    public ParallelInputBlock() {
        this(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).strength(3.0f, 6.0f));
    }

    public ParallelInputBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseIOBlock> codec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ParallelInputBlockEntity(pos, state);
    }
}
