package com.createcmpor.mixin;

import com.createcmpor.CreateCMPOR;
import com.createcmpor.stress.FactoryStressAccess;
import com.createcmpor.stress.StressEvaluationRegistry;
import com.createcmpor.stress.StressProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 注入 CMPOR 的 {@code Core.finalizeMachine(overworld, compactWorld, roomCode, overworldPos)}：
 * 在工厂方块真正被创建（{@code replaceBlock} + {@code setRoomCode}）之后，
 * 把本 mod 在评估期累积的应力采样聚合成 {@link StressProfile} 写入工厂方块的 Data Attachment。
 *
 * <p><b>为什么不能注入 {@code Core.finish()}？</b>
 * 因为 CMPOR 的评估流程是分阶段的：
 * <ol>
 *     <li>{@code finish()} — 停用 IO + S1 扫描 + 标记 {@code pendingRoomClear}</li>
 *     <li>[下一 tick] {@code clearRoomBlocks()} — 清空房间方块</li>
 *     <li>[再下一 tick] {@code cleanRoomItems()} + {@code finalizeMachine()} —
 *         这里才 {@code replaceBlock} 创建 {@code FactoryBlockEntity}</li>
 * </ol>
 * 注入 {@code finish()} 时 {@code overworldPos} 处还是 MachineFrame 方块，不是 FactoryBlock；
 * 且 {@code finalizeMachine} 的 {@code replaceBlock} 会销毁旧 BE（连同 attachment），导致数据丢失。
 *
 * <p><b>采样时机：</b>{@code finalizeMachine} 执行时 {@code Core.MACHINES} 中仍有该 roomCode 的 Machine
 * （{@code MACHINES.remove} 在 {@code finalizeMachine} 末尾才调用），所以 {@code StressIOBlockEntity.tick()}
 * 在 {@code finalizeMachine} 前仍能正常采样。{@code consume} 在此处调用是安全的——评估已结束、
 * 采样已完整、工厂方块刚创建。
 */
@Mixin(targets = "com.compactmachinespor.core.Core", remap = false)
public class CoreFinishMixin {

    @Inject(
            method = "finalizeMachine(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/server/level/ServerLevel;Ljava/lang/String;Lnet/minecraft/core/BlockPos;)V",
            at = @At("TAIL")
    )
    private static void createcmpor$writeStressProfile(ServerLevel overworld, ServerLevel compactWorld,
                                                       String roomCode, BlockPos overworldPos, CallbackInfo ci) {
//        CreateCMPOR.LOGGER.info("[CreateCMPOR] CoreFinishMixin(finalizeMachine) 触发 room={} pos={}", roomCode, overworldPos);

        // 诊断：消费前先 dump DATA 状态
        StressEvaluationRegistry.dumpState();

        // 消费采样数据，聚合成 StressProfile
        StressProfile profile = StressEvaluationRegistry.consume(roomCode);
//        CreateCMPOR.LOGGER.info("[CreateCMPOR] CoreFinishMixin consume 结果: {}", profile);

        // 此时 overworldPos 处已是刚创建的 FactoryBlockEntity（replaceBlock 已执行）
        BlockEntity be = overworld.getBlockEntity(overworldPos);
        if (be == null) {
            CreateCMPOR.LOGGER.warn("[CreateCMPOR] finalizeMachine 时 {} 无方块实体", overworldPos);
            return;
        }

//        CreateCMPOR.LOGGER.info("[CreateCMPOR] 找到工厂方块实体: {} @ {}, 准备写入 profile={}",
//                be.getClass().getSimpleName(), overworldPos, profile);

        // 写入 Data Attachment（即使 EMPTY 也写入，清除旧数据）
        FactoryStressAccess.set(be, profile);

        // 验证写入
        StressProfile readBack = FactoryStressAccess.get(be);
//        CreateCMPOR.LOGGER.info("[CreateCMPOR] 写入验证: 写入={} 读回={}", profile, readBack);
    }
}
