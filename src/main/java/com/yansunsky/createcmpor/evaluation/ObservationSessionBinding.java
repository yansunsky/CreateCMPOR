package com.yansunsky.createcmpor.evaluation;

import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Pure ownership rules for external-player adoption and legacy observation cleanup. */
final class ObservationSessionBinding {
    record Candidate(UUID sessionId, String roomCode, String dimension, Set<Long> chunks, boolean live) {
        Candidate {
            chunks = Set.copyOf(chunks);
        }
    }

    private ObservationSessionBinding() {
    }

    static Optional<UUID> resolve(Collection<Candidate> candidates, String dimension,
                                  String roomCode, long playerChunk) {
        Set<UUID> matches = new HashSet<>();
        for (Candidate candidate : candidates) {
            if (!candidate.live() || !candidate.dimension().equals(dimension)) {
                continue;
            }
            boolean locationMatches = roomCode == null || roomCode.isBlank()
                    ? candidate.chunks().contains(playerChunk)
                    : candidate.roomCode().equals(roomCode);
            if (locationMatches) {
                matches.add(candidate.sessionId());
            }
        }
        return matches.size() == 1 ? Optional.of(matches.iterator().next()) : Optional.empty();
    }

    static boolean mayEvictCleanupOccupant(boolean boundToOtherLiveSession, boolean insideTargetChunk,
                                           boolean targetChunkOwnedByOtherSession) {
        return (!boundToOtherLiveSession || insideTargetChunk) && !targetChunkOwnedByOtherSession;
    }

    static boolean mayHoldPlayerTickets(boolean spectator, boolean spectatorsGenerateChunks) {
        return !spectator || spectatorsGenerateChunks;
    }

    static boolean mayHoldPlayerTickets(boolean cleanTargetOnCancel, boolean ticketsAdded,
                                        boolean targetReady, boolean targetWriteIntent) {
        return cleanTargetOnCancel || ticketsAdded || targetReady || targetWriteIntent;
    }

    static boolean withinPlayerTicketRange(int playerX, int playerZ, int targetX, int targetZ, int viewDistance) {
        long dx = Math.abs((long) playerX - targetX);
        long dz = Math.abs((long) playerZ - targetZ);
        return Math.max(dx, dz) <= Math.max(0, viewDistance);
    }

    static boolean belongsToCleanup(UUID recordedSessionId, UUID cleanupSessionId,
                                    String recordedRoomCode, String cleanupRoomCode,
                                    String recordedDimension, Collection<String> cleanupDimensions) {
        if (recordedSessionId != null) {
            return recordedSessionId.equals(cleanupSessionId);
        }
        return cleanupRoomCode.equals(recordedRoomCode)
                && cleanupDimensions.contains(recordedDimension);
    }
}
