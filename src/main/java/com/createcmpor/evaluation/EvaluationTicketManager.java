package com.createcmpor.evaluation;

import com.createcmpor.CreateCMPOR;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;

import java.util.UUID;

/** 精确 room chunk 的 BLOCK_TICKING ticket 生命周期。 */
final class EvaluationTicketManager {
    private static final TicketType<UUID> EVALUATION_TICKET = TicketType.create(
            "createcmpor_evaluation", UUID::compareTo);
    private static final int BLOCK_TICKING_DISTANCE = 1;
    private static final int ENTITY_TICKING_DISTANCE = 2;

    private EvaluationTicketManager() {
    }

    static void add(ServerLevel target, EvaluationManifest manifest) {
        addAtDistance(target, manifest, BLOCK_TICKING_DISTANCE);
    }

    static void remove(ServerLevel target, EvaluationManifest manifest) {
        // 幂等：同时撤销两种 distance（评估期可能切到 ENTITY_TICKING）
        removeAtDistance(target, manifest, BLOCK_TICKING_DISTANCE);
        removeAtDistance(target, manifest, ENTITY_TICKING_DISTANCE);
    }

    static void switchToEntityTicking(ServerLevel target, EvaluationManifest manifest) {
        removeAtDistance(target, manifest, BLOCK_TICKING_DISTANCE);
        addAtDistance(target, manifest, ENTITY_TICKING_DISTANCE);
    }

    static void switchToBlockTicking(ServerLevel target, EvaluationManifest manifest) {
        removeAtDistance(target, manifest, ENTITY_TICKING_DISTANCE);
        addAtDistance(target, manifest, BLOCK_TICKING_DISTANCE);
    }

    private static void addAtDistance(ServerLevel target, EvaluationManifest manifest, int distance) {
        for (EvaluationManifest.ChunkRecord chunk : manifest.chunks()) {
            // forceTicks=true：无玩家维度上实体/方块也必须真正 tick（否则评估期副本不运行，
            // PLAYER ticket 才驱动——P6 测试的产出正是玩家进入副本时产生的）
            target.getChunkSource().addRegionTicket(EVALUATION_TICKET, chunk.chunkPos(),
                    distance, manifest.sessionId(), true);
        }
    }

    private static void removeAtDistance(ServerLevel target, EvaluationManifest manifest, int distance) {
        for (EvaluationManifest.ChunkRecord chunk : manifest.chunks()) {
            target.getChunkSource().removeRegionTicket(EVALUATION_TICKET, chunk.chunkPos(),
                    distance, manifest.sessionId(), true);
        }
    }

    static boolean allReady(ServerLevel target, EvaluationManifest manifest) {
        return allReadyAt(target, manifest, FullChunkStatus.BLOCK_TICKING);
    }

    static boolean allEntityTickingReady(ServerLevel target, EvaluationManifest manifest) {
        return allReadyAt(target, manifest, FullChunkStatus.ENTITY_TICKING);
    }

    /**
     * 用 vanilla forced chunk 保持无玩家维度的实体持续 tick：
     * ServerLevel.tick 的实体门控为 players 非空 || hasForcedChunks || emptyTime&lt;300，
     * eval_world 没有玩家，必须通过 setChunkForced（写入 "chunks" SavedData，
     * FORCED ticket 等级 = ENTITY_TICKING）模拟"有玩家加载"。
     */
    static void setChunksForced(ServerLevel target, EvaluationManifest manifest, boolean add) {
        for (EvaluationManifest.ChunkRecord chunk : manifest.chunks()) {
            target.setChunkForced(chunk.chunkPos().x, chunk.chunkPos().z, add);
        }
        if (add) {
            CreateCMPOR.LOGGER.info("评估会话 {} 已强制加载 {} 个副本区块（模拟玩家 tick）",
                    manifest.sessionId(), manifest.chunks().size());
        }
    }

    private static boolean allReadyAt(ServerLevel target, EvaluationManifest manifest,
                                      FullChunkStatus status) {
        return manifest.chunks().stream().allMatch(chunk -> {
            var holder = target.getChunkSource().chunkMap
                    .getVisibleChunkIfPresent(chunk.chunkPos().toLong());
            return holder != null
                    && holder.getFullStatus().isOrAfter(status)
                    && holder.getTickingChunk() != null;
        });
    }

    static void logPending(ServerLevel target, EvaluationManifest manifest) {
        StringBuilder states = new StringBuilder();
        for (EvaluationManifest.ChunkRecord chunk : manifest.chunks()) {
            var holder = target.getChunkSource().chunkMap
                    .getVisibleChunkIfPresent(chunk.chunkPos().toLong());
            if (!states.isEmpty()) {
                states.append(", ");
            }
            states.append(chunk.chunkPos()).append('=');
            if (holder == null) {
                states.append("no-holder");
            } else {
                states.append("ticket").append(holder.getTicketLevel())
                        .append('/').append(holder.getFullStatus())
                        .append(holder.getTickingChunk() != null ? "/ticking" : "/no-ticking");
            }
        }
        CreateCMPOR.LOGGER.info("评估会话 {} 等待副本加载：{}", manifest.sessionId(), states);
    }
}
