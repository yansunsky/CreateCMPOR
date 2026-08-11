package com.createcmpor.mixin;

import com.createcmpor.evaluation.EvaluationManager;
import com.createcmpor.evaluation.EvaluationSession;
import com.createcmpor.init.ModItems;
import dev.compactmods.machines.machine.block.BoundCompactMachineBlock;
import dev.compactmods.machines.machine.block.BoundCompactMachineBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
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

/**
 * 拦截 CompactMachines 绑定机器交互，用启动棒进入 CreateCMPOR 平行房间评估流程。
 */
@Mixin(BoundCompactMachineBlock.class)
public class BoundCompactMachineBlockMixin {

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
    private void createcmpor$handleLauncherStick(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                                 Player player, InteractionHand hand, BlockHitResult hitResult,
                                                 CallbackInfoReturnable<ItemInteractionResult> cir) {
        GlobalPos machinePos = GlobalPos.of(level.dimension(), pos);

        if (!stack.is(ModItems.LAUNCHER_STICK.get())) {
            if (!level.isClientSide && EvaluationManager.INSTANCE.isMachineLocked(machinePos)) {
                player.displayClientMessage(Component.literal("该压缩机器正在进行 CreateCMPOR 评估，暂时无法交互。"), true);
                cir.setReturnValue(ItemInteractionResult.CONSUME);
            }
            return;
        }

        if (level.isClientSide) {
            cir.setReturnValue(ItemInteractionResult.SUCCESS);
            return;
        }

        if (!(player instanceof ServerPlayer serverPlayer) || !(level instanceof ServerLevel serverLevel)) {
            cir.setReturnValue(ItemInteractionResult.FAIL);
            return;
        }

        if (!(serverLevel.getBlockEntity(pos) instanceof BoundCompactMachineBlockEntity machine)) {
            serverPlayer.displayClientMessage(Component.literal("这里不是有效的 CompactMachines 绑定机器。"), true);
            cir.setReturnValue(ItemInteractionResult.FAIL);
            return;
        }

        if (EvaluationManager.INSTANCE.isMachineLocked(machinePos)) {
            EvaluationManager.INSTANCE.sessionAt(machinePos).ifPresentOrElse(session -> {
                String target = session.evaluationRoomCode();
                if (target == null || target.isBlank()) {
                    serverPlayer.displayClientMessage(Component.literal("该压缩机器正在评估中，请稍后。"), true);
                } else {
                    serverPlayer.displayClientMessage(Component.literal("该压缩机器已复制到评估房间：" + target), false);
                    sendCopyableCommand(serverPlayer, "调试进入", "/createcmpor enter_room " + target);
                }
            }, () -> serverPlayer.displayClientMessage(Component.literal("该压缩机器正在评估中。"), true));
            cir.setReturnValue(ItemInteractionResult.CONSUME);
            return;
        }

        String roomCode = machine.connectedRoom();
        if (roomCode == null || roomCode.isBlank()) {
            serverPlayer.displayClientMessage(Component.literal("该压缩机器没有绑定有效房间。"), true);
            cir.setReturnValue(ItemInteractionResult.FAIL);
            return;
        }

        EvaluationSession session = EvaluationManager.INSTANCE.startPhase3Clone(serverPlayer, machinePos, roomCode);
        if (session.state() == EvaluationSession.State.FAILED) {
            serverPlayer.displayClientMessage(session.failureReason(), false);
            cir.setReturnValue(ItemInteractionResult.FAIL);
            return;
        }

        serverPlayer.displayClientMessage(Component.literal("CreateCMPOR 评估房间复制完成：" + session.evaluationRoomCode()), false);
        sendCopyableCommand(serverPlayer, "调试进入", "/createcmpor enter_room " + session.evaluationRoomCode());
        cir.setReturnValue(ItemInteractionResult.CONSUME);
    }

    /**
     * 发送可点击复制的调试命令，方便实机验收时直接粘贴执行。
     */
    private static void sendCopyableCommand(ServerPlayer player, String label, String command) {
        player.displayClientMessage(Component.literal(label + "：")
                .append(Component.literal(command)
                        .withStyle(style -> style
                                .withColor(ChatFormatting.AQUA)
                                .withUnderlined(true)
                                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, command))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                        Component.literal("点击复制命令"))))), false);
    }
}
