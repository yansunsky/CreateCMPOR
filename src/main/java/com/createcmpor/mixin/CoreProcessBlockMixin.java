package com.createcmpor.mixin;

import com.createcmpor.CreateCMPOR;
import com.createcmpor.block.StressInputBlock;
import com.createcmpor.block.StressInputBlockEntity;
import com.createcmpor.block.StressOutputBlock;
import com.createcmpor.block.StressOutputBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 注入 CMPOR 的 {@code Core.processBlock(level, x, y, z, roomCode)}：评估扫描房间时，
 * 若扫到本 mod 的应力输入/输出方块，则绑定 roomCode 并激活。
 */
@Mixin(targets = "com.compactmachinespor.core.Core", remap = false)
public class CoreProcessBlockMixin {

    @Inject(
            method = "processBlock(Lnet/minecraft/server/level/ServerLevel;IIILjava/lang/String;)V",
            at = @At("TAIL")
    )
    private static void createcmpor$registerStressBlocks(ServerLevel level, int x, int y, int z, String roomCode,
                                                          CallbackInfo ci) {
        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = level.getBlockState(pos);
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();

        switch (blockId) {
            case "createcmpor:stress_input" -> registerStressInput(level, pos, state, roomCode);
            case "createcmpor:stress_output" -> registerStressOutput(level, pos, state, roomCode);
        }
    }

    private static void registerStressInput(ServerLevel level, BlockPos pos, BlockState state, String roomCode) {
        if (!(level.getBlockEntity(pos) instanceof StressInputBlockEntity)) return;

        if (state.hasProperty(StressInputBlock.ACTIVE)
                && !state.getValue(StressInputBlock.ACTIVE)) {
            level.setBlock(pos, state.setValue(StressInputBlock.ACTIVE, true), Block.UPDATE_ALL);
        }

        if (level.getBlockEntity(pos) instanceof StressInputBlockEntity io) {
            io.bindEvaluation(roomCode);
            CreateCMPOR.LOGGER.info("[CreateCMPOR] 评估扫描登记应力输入方块 @{} room={} active={} hasNetwork={}",
                    pos, roomCode,
                    level.getBlockState(pos).getValue(StressInputBlock.ACTIVE),
                    io.hasNetwork());
        } else {
            CreateCMPOR.LOGGER.warn("[CreateCMPOR] 评估扫描登记应力输入方块 @{} 失败：setBlock 后 BE 类型不匹配", pos);
        }
    }

    private static void registerStressOutput(ServerLevel level, BlockPos pos, BlockState state, String roomCode) {
        if (!(level.getBlockEntity(pos) instanceof StressOutputBlockEntity)) return;

        if (state.hasProperty(StressOutputBlock.ACTIVE)
                && !state.getValue(StressOutputBlock.ACTIVE)) {
            level.setBlock(pos, state.setValue(StressOutputBlock.ACTIVE, true), Block.UPDATE_ALL);
        }

        if (level.getBlockEntity(pos) instanceof StressOutputBlockEntity io) {
            io.bindEvaluation(roomCode);
            CreateCMPOR.LOGGER.info("[CreateCMPOR] 评估扫描登记应力输出方块 @{} room={} active={}",
                    pos, roomCode,
                    level.getBlockState(pos).getValue(StressOutputBlock.ACTIVE));
        } else {
            CreateCMPOR.LOGGER.warn("[CreateCMPOR] 评估扫描登记应力输出方块 @{} 失败：setBlock 后 BE 类型不匹配", pos);
        }
    }
}
