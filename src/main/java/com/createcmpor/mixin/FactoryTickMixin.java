package com.createcmpor.mixin;

import com.createcmpor.CreateCMPOR;
import com.createcmpor.stress.FactorySatisfaction;
import com.createcmpor.stress.FactoryStressAccess;
import com.createcmpor.stress.StressProfile;
import com.compactmachinespor.block.FactoryBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 注入 CMPOR 的 {@code FactoryBlockEntity.tick(level,pos,state,be)}：
 * 若工厂内部为「消耗应力」(CONSUME) 模式且当前应力需求未被满足（无相邻应力拓展方块链供给），
 * 则取消本次 tick——工厂不工作。这与 CMPOR 能量通道「输入不足则不运转」的设定一致。
 *
 * <p>当相邻应力拓展方块链从外部 Create 网络获得足够应力并供给后，会通过
 * {@link FactorySatisfaction#markSatisfied} 标记满足，工厂恢复工作。
 */
@Mixin(targets = "com.compactmachinespor.block.FactoryBlockEntity", remap = false)
public class FactoryTickMixin {

    @Inject(
            method = "tick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lcom/compactmachinespor/block/FactoryBlockEntity;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void createcmpor$gateOnStress(Level level, BlockPos pos, BlockState state,
                                                 FactoryBlockEntity be, CallbackInfo ci) {
        if (level == null || level.isClientSide())
            return;
        BlockEntity blockEntity = be;

        StressProfile profile = FactoryStressAccess.get(blockEntity);
        if (!profile.isConsume())
            return; // 非消耗模式（输出或无应力）不拦截

        boolean satisfied = FactorySatisfaction.isSatisfied(level.dimension(), pos, level.getGameTime());
        if (!satisfied) {
            ci.cancel(); // 应力需求未满足 → 工厂停工
            // 节流日志（每 5 秒一次）
            if (level.getGameTime() % 100 == 0) {
                CreateCMPOR.LOGGER.debug("[CreateCMPOR] 工厂 {} 应力未满足({}SU)，停工等待外部供给",
                        pos, profile.inputSU());
            }
        }
    }
}
