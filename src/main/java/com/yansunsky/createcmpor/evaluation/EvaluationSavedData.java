package com.yansunsky.createcmpor.evaluation;

import com.yansunsky.createcmpor.CreateCMPOR;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 保存冻结事务和待退还启动棒，供崩溃恢复使用。 */
public final class EvaluationSavedData extends SavedData {
    private static final String DATA_NAME = CreateCMPOR.MOD_ID + "_evaluations";
    private static final int SCHEMA_VERSION = 2;
    private static final Factory<EvaluationSavedData> FACTORY = new Factory<>(
            EvaluationSavedData::new, EvaluationSavedData::load);

    private final Map<UUID, EvaluationSession> sessions = new LinkedHashMap<>();
    private final Map<UUID, UUID> pendingLauncherReturns = new LinkedHashMap<>();
    private final ListTag failedSessionTags = new ListTag();

    public static EvaluationSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    private static EvaluationSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        EvaluationSavedData data = new EvaluationSavedData();
        ListTag sessionTags = tag.getList("sessions", Tag.TAG_COMPOUND);
        for (int index = 0; index < sessionTags.size(); index++) {
            try {
                EvaluationSession session = EvaluationSession.load(sessionTags.getCompound(index), registries);
                data.sessions.put(session.id(), session);
            } catch (RuntimeException exception) {
                data.failedSessionTags.add(sessionTags.getCompound(index).copy());
                CreateCMPOR.LOGGER.error("无法读取评估会话 {}，原始记录已保留在 recovery_failed_sessions", index, exception);
            }
        }

        ListTag returnTags = tag.getList("pending_launcher_returns", Tag.TAG_COMPOUND);
        for (int index = 0; index < returnTags.size(); index++) {
            CompoundTag returnTag = returnTags.getCompound(index);
            if (returnTag.hasUUID("session") && returnTag.hasUUID("player")) {
                data.pendingLauncherReturns.put(returnTag.getUUID("session"), returnTag.getUUID("player"));
            }
        }
        ListTag failedTags = tag.getList("recovery_failed_sessions", Tag.TAG_COMPOUND);
        for (int index = 0; index < failedTags.size(); index++) {
            data.failedSessionTags.add(failedTags.getCompound(index).copy());
        }
        return data;
    }

    public Collection<EvaluationSession> sessions() {
        return new ArrayList<>(sessions.values());
    }

    public Optional<EvaluationSession> sessionByRoom(String roomCode) {
        return sessions.values().stream().filter(session -> session.roomCode().equals(roomCode)).findFirst();
    }

    public Optional<EvaluationSession> sessionByMachine(net.minecraft.core.GlobalPos machinePos) {
        return sessions.values().stream().filter(session -> session.machinePos().equals(machinePos)).findFirst();
    }

    public Optional<EvaluationSession> session(UUID sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    public void reconcileCriticalSessions(Collection<EvaluationSession> criticalSessions,
                                          boolean journalExists) {
        Map<UUID, EvaluationSession> criticalById = new LinkedHashMap<>();
        for (EvaluationSession session : criticalSessions) {
            if (!session.hasPhase4Manifest()) {
                throw new IllegalArgumentException("关键 journal 包含非 Phase 4 会话");
            }
            criticalById.put(session.id(), session);
        }
        boolean hasSavedPhase4 = sessions.values().stream().anyMatch(EvaluationSession::hasPhase4Manifest);
        if (!journalExists && hasSavedPhase4) {
            throw new IllegalStateException("Phase 4 会话存在，但关键 journal 缺失");
        }
        if (journalExists) {
            sessions.values().removeIf(session -> session.hasPhase4Manifest()
                    && !criticalById.containsKey(session.id()));
            criticalById.forEach(sessions::put);
            setDirty();
        }
    }

    public Collection<EvaluationSession> criticalSessions() {
        return sessions.values().stream().filter(EvaluationSession::hasPhase4Manifest).toList();
    }

    public void put(EvaluationSession session) {
        sessions.put(session.id(), session);
        setDirty();
    }

    public void changed() {
        setDirty();
    }

    public void remove(UUID sessionId) {
        sessions.remove(sessionId);
        setDirty();
    }

    public void addPendingLauncherReturn(UUID sessionId, UUID playerId) {
        pendingLauncherReturns.putIfAbsent(sessionId, playerId);
        setDirty();
    }

    public Map<UUID, UUID> pendingLauncherReturns(UUID playerId) {
        Map<UUID, UUID> result = new LinkedHashMap<>();
        pendingLauncherReturns.forEach((sessionId, ownerId) -> {
            if (ownerId.equals(playerId)) {
                result.put(sessionId, ownerId);
            }
        });
        return result;
    }

    public void removePendingLauncherReturn(UUID sessionId) {
        pendingLauncherReturns.remove(sessionId);
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("schema_version", SCHEMA_VERSION);
        ListTag sessionTags = new ListTag();
        sessions.values().forEach(session -> sessionTags.add(session.save()));
        tag.put("sessions", sessionTags);

        ListTag returnTags = new ListTag();
        pendingLauncherReturns.forEach((sessionId, playerId) -> {
            CompoundTag returnTag = new CompoundTag();
            returnTag.putUUID("session", sessionId);
            returnTag.putUUID("player", playerId);
            returnTags.add(returnTag);
        });
        tag.put("pending_launcher_returns", returnTags);
        tag.put("recovery_failed_sessions", failedSessionTags.copy());
        return tag;
    }
}
