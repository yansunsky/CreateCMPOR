package com.createcmpor.mixin;

import com.createcmpor.evaluation.EvaluationManager;
import com.createcmpor.init.ModItems;
import dev.compactmods.machines.machine.block.BoundCompactMachineBlock;
import dev.compactmods.machines.machine.block.BoundCompactMachineBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 把 CompactMachines 绑定机器的启动棒入口接入 Phase 3 冻结事务。 */
@Mixin(BoundCompactMachineBlock.class)
public class BoundCompactMachineBlockMixin {

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void createcmpor$handleItem(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                        Player player, InteractionHand hand, BlockHitResult hitResult,
                                        CallbackInfoReturnable<ItemInteractionResult> cir) {
        if (!(level.getBlockEntity(pos) instanceof BoundCompactMachineBlockEntity machine)) {
            return;
        }

        String roomCode = machine.connectedRoom();
        GlobalPos machinePos = GlobalPos.of(level.dimension(), pos);
        boolean launcher = stack.is(ModItems.LAUNCHER_STICK.get());

        if (level.isClientSide) {
            if (launcher) {
                cir.setReturnValue(ItemInteractionResult.SUCCESS);
            }
            return;
        }

        if (!(player instanceof ServerPlayer serverPlayer) || !(level instanceof ServerLevel)) {
            cir.setReturnValue(ItemInteractionResult.FAIL);
            return;
        }

        if (roomCode == null || roomCode.isBlank()) {
            if (launcher) {
                serverPlayer.displayClientMessage(
                        Component.translatable("message.createcmpor.evaluation.room_missing", ""), true);
                cir.setReturnValue(ItemInteractionResult.FAIL);
            }
            return;
        }

        if (EvaluationManager.INSTANCE.isRoomLocked(serverPlayer.server, roomCode)
                || EvaluationManager.INSTANCE.isMachineLocked(serverPlayer.server, machinePos)) {
            serverPlayer.displayClientMessage(
                    Component.translatable("message.createcmpor.evaluation.already_running"), true);
            cir.setReturnValue(ItemInteractionResult.CONSUME);
            return;
        }

        if (!launcher) {
            return;
        }

        EvaluationManager.StartResult result = EvaluationManager.INSTANCE.start(
                serverPlayer, machinePos, roomCode, stack);
        serverPlayer.displayClientMessage(result.message(), false);
        cir.setReturnValue(result.successful()
                ? ItemInteractionResult.CONSUME
                : ItemInteractionResult.FAIL);
    }

    @Inject(method = "useWithoutItem", at = @At("HEAD"), cancellable = true)
    private void createcmpor$blockFrozenRoomEntry(BlockState state, Level level, BlockPos pos, Player player,
                                                   BlockHitResult hitResult,
                                                   CallbackInfoReturnable<InteractionResult> cir) {
        if (level.isClientSide || !(level.getBlockEntity(pos) instanceof BoundCompactMachineBlockEntity machine)
                || machine.connectedRoom() == null || player.getServer() == null) {
            return;
        }
        if (EvaluationManager.INSTANCE.isRoomLocked(
                player.getServer(), machine.connectedRoom())) {
            player.displayClientMessage(
                    Component.translatable("message.createcmpor.evaluation.already_running"), true);
            cir.setReturnValue(InteractionResult.CONSUME);
        }
    }
}
