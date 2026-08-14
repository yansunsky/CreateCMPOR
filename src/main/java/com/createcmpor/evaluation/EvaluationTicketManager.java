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

    private EvaluationTicketManager() {
    }

    static void add(ServerLevel target, EvaluationManifest manifest) {
        for (EvaluationManifest.ChunkRecord chunk : manifest.chunks()) {
            target.getChunkSource().addRegionTicket(EVALUATION_TICKET, chunk.chunkPos(),
                    BLOCK_TICKING_DISTANCE, manifest.sessionId(), false);
        }
    }

    static void remove(ServerLevel target, EvaluationManifest manifest) {
        for (EvaluationManifest.ChunkRecord chunk : manifest.chunks()) {
            target.getChunkSource().removeRegionTicket(EVALUATION_TICKET, chunk.chunkPos(),
                    BLOCK_TICKING_DISTANCE, manifest.sessionId(), false);
        }
    }

    static boolean allReady(ServerLevel target, EvaluationManifest manifest) {
        return manifest.chunks().stream().allMatch(chunk -> {
            var holder = target.getChunkSource().chunkMap
                    .getVisibleChunkIfPresent(chunk.chunkPos().toLong());
            return holder != null
                    && holder.getFullStatus().isOrAfter(FullChunkStatus.BLOCK_TICKING)
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
