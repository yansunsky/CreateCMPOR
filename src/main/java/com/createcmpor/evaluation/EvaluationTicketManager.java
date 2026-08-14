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
            target.getChunkSource().addRegionTicket(EVALUATION_TICKET, chunk.chunkPos(),
                    distance, manifest.sessionId(), false);
        }
    }

    private static void removeAtDistance(ServerLevel target, EvaluationManifest manifest, int distance) {
        for (EvaluationManifest.ChunkRecord chunk : manifest.chunks()) {
            target.getChunkSource().removeRegionTicket(EVALUATION_TICKET, chunk.chunkPos(),
                    distance, manifest.sessionId(), false);
        }
    }

    static boolean allReady(ServerLevel target, EvaluationManifest manifest) {
        return allReadyAt(target, manifest, FullChunkStatus.BLOCK_TICKING);
    }

    static boolean allEntityTickingReady(ServerLevel target, EvaluationManifest manifest) {
        return allReadyAt(target, manifest, FullChunkStatus.ENTITY_TICKING);
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
