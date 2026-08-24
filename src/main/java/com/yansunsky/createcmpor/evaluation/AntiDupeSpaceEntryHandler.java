package com.yansunsky.createcmpor.evaluation;

import com.yansunsky.createcmpor.CreateCMPOR;
import com.yansunsky.createcmpor.block.FactoryBlockEntity;
import dev.compactmods.machines.api.CompactMachines;
import dev.compactmods.machines.api.dimension.CompactDimension;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.List;
import java.util.Optional;

/**
 * 防复制护栏：玩家进入已评估房间的压缩空间时，自动还原对应主世界工厂。
 *
 * <p><b>背景</b>：评估固化后，主世界留下工厂（持续产出）；而 compact_world 的原房间内容
 * 原封不动保留且房间锁已释放。玩家若通过传送模组/重生点直接进入该压缩空间，可搬空原房间
 * 机器内容，同时保留主世界工厂产出——形成复制漏洞。</p>
 *
 * <p><b>机制</b>：每 20 tick 检测一次 compact_world 中是否有玩家位于某房间内；若有，用
 * {@link FactoryIndexSavedData} 按房间号反查对应工厂；命中且主世界该位置仍是工厂 → 自动
 * {@code revertToMachine}（不消耗启动棒）。玩家因此二选一：保留工厂则不进入空间，进入空间
 * 则工厂自动还原，无法同时获得双份。</p>
 *
 * <p><b>性能</b>：仅在 compact_world 存在玩家时才遍历该维度玩家（主世界玩家完全零开销）；
 * 且每 20 tick 才检测一次。</p>
 *
 * <p><b>容错</b>：索引查不到只 WARN 不阻塞（旧存档兼容：旧工厂无索引条目）。</p>
 */
public final class AntiDupeSpaceEntryHandler {

    private static int tickAccumulator = 0;

    private AntiDupeSpaceEntryHandler() {
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        if (++tickAccumulator < 20) {
            return;
        }
        tickAccumulator = 0;

        ServerLevel compactWorld = event.getServer().getLevel(CompactDimension.LEVEL_KEY);
        if (compactWorld == null || compactWorld.players().isEmpty()) {
            return;
        }
        for (ServerPlayer player : List.copyOf(compactWorld.players())) {
            checkPlayer(event, player);
        }
    }

    private static void checkPlayer(ServerTickEvent.Post event, ServerPlayer player) {
        Optional<String> roomCode = CompactMachines.chunkManager()
                .findRoomByChunk(new ChunkPos(player.blockPosition()));
        if (roomCode.isEmpty()) {
            return;
        }
        FactoryIndexSavedData index = FactoryIndexSavedData.get(event.getServer());
        Optional<GlobalPos> factoryPos = index.factoryForRoom(roomCode.get());
        if (factoryPos.isEmpty()) {
            if (event.getServer().getLevel(CompactDimension.LEVEL_KEY) != null
                    && !roomCode.get().isBlank()) {
                CreateCMPOR.LOGGER.debug("[防复制] 玩家 {} 进入房间 {}，未找到对应工厂索引（可能未评估或已还原）",
                        player.getGameProfile().getName(), roomCode.get());
            }
            return;
        }
        GlobalPos pos = factoryPos.get();
        ServerLevel machineLevel = event.getServer().getLevel(pos.dimension());
        if (machineLevel == null || !machineLevel.isLoaded(pos.pos())) {
            return;
        }
        if (!(machineLevel.getBlockEntity(pos.pos()) instanceof FactoryBlockEntity factory)) {
            // 工厂已被其他方式移除/还原，索引过期但无害：清除避免误判
            index.removeFactory(roomCode.get());
            return;
        }
        if (factory.revertToMachine(machineLevel)) {
            CreateCMPOR.LOGGER.info("[防复制] 玩家 {} 进入已评估房间 {}，自动还原主世界工厂 @{} 防止物品复制",
                    player.getGameProfile().getName(), roomCode.get(), pos.pos());
            // 清除索引（revertToMachine 内已清理，此处兜底）
            index.removeFactory(roomCode.get());
            player.displayClientMessage(
                    net.minecraft.network.chat.Component.translatable(
                            "message.createcmpor.antidupe.space_factory_reverted"), false);
        } else {
            CreateCMPOR.LOGGER.warn("[防复制] 玩家 {} 进入房间 {}，自动还原工厂失败（缺少还原数据）",
                    player.getGameProfile().getName(), roomCode.get());
        }
    }
}
