package com.yansunsky.createcmpor.evaluation;

import com.yansunsky.createcmpor.util.VirtualMachineErrors;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.IOUtilities;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** 同步持久化 Phase 4 清理责任，避免依赖 SavedData 的异步写入。 */
final class EvaluationCriticalJournal {
    private static final int SCHEMA_VERSION = 1;
    private static final long MAX_READ_BYTES = 64L * 1024L * 1024L;
    private static final String RELATIVE_PATH = "data/createcmpor/evaluation-critical-journal.nbt";
    /** A failed read must never be replaced by a snapshot of the still-unreconciled SavedData. */
    private static MinecraftServer unreadJournalServer;

    private EvaluationCriticalJournal() {
    }

    static void write(MinecraftServer server, EvaluationSavedData data) {
        try {
            requireWritable(server);
            writeSnapshot(server, data.criticalSessions());
        } catch (IOException | RuntimeException exception) {
            VirtualMachineErrors.rethrowIfPresent(exception);
            throw writeFailure("无法持久化 Phase 4 关键 journal", exception);
        }
    }

    /** Commit the responsibility removal while the live session still holds its room lock. */
    static void commitExcludingSession(MinecraftServer server, EvaluationSavedData data, UUID sessionId) {
        try {
            requireWritable(server);
            List<EvaluationSession> remaining = data.criticalSessions().stream()
                    .filter(session -> !session.id().equals(sessionId)).toList();
            if (remaining.isEmpty()) {
                deleteSnapshot(server);
            } else {
                writeSnapshot(server, remaining);
            }
        } catch (IOException | RuntimeException exception) {
            VirtualMachineErrors.rethrowIfPresent(exception);
            throw writeFailure("无法提交 Phase 4 会话责任移除", exception);
        }
    }

    private static void writeSnapshot(MinecraftServer server, Collection<EvaluationSession> snapshot)
            throws IOException {
        CompoundTag payload = new CompoundTag();
        ListTag sessions = new ListTag();
        for (EvaluationSession session : snapshot) {
            sessions.add(session.save());
        }
        payload.put("sessions", sessions);
        CompoundTag root = new CompoundTag();
        root.putInt("schema_version", SCHEMA_VERSION);
        root.put("payload", payload);
        root.putString("payload_hash", CanonicalNbtHasher.sha256(payload));
        Path path = path(server);
        Files.createDirectories(path.getParent());
        IOUtilities.writeNbtCompressed(root, path);
    }

    private static void deleteSnapshot(MinecraftServer server) throws IOException {
        Path path = path(server);
        Files.deleteIfExists(path);
        if (!Files.notExists(path)) {
            throw new IOException("删除后未能确认文件已不存在");
        }
    }

    static void deleteIfEmpty(MinecraftServer server, EvaluationSavedData data) {
        try {
            requireWritable(server);
            if (!data.criticalSessions().isEmpty()) {
                return;
            }
            deleteSnapshot(server);
        } catch (IOException | RuntimeException exception) {
            VirtualMachineErrors.rethrowIfPresent(exception);
            throw writeFailure("无法删除空 Phase 4 关键 journal", exception);
        }
    }

    /** Only a successful read AND reconciliation release the write fence. */
    static void recover(MinecraftServer server, EvaluationSavedData data) {
        try {
            Snapshot snapshot = read(server);
            data.reconcileCriticalSessions(snapshot.sessions(), snapshot.exists());
            unreadJournalServer = null;
        } catch (RuntimeException exception) {
            VirtualMachineErrors.rethrowIfPresent(exception);
            throw readFailure(server, "无法合并 Phase 4 关键 journal", exception);
        }
    }

    static Snapshot read(MinecraftServer server) {
        try {
            Path path = path(server);
            if (Files.notExists(path)) {
                return new Snapshot(false, List.of());
            }
            CompoundTag root = NbtIo.readCompressed(path, NbtAccounter.create(MAX_READ_BYTES));
            if (!root.contains("schema_version", Tag.TAG_INT)
                    || root.getInt("schema_version") != SCHEMA_VERSION
                    || !root.contains("payload", Tag.TAG_COMPOUND)
                    || !root.contains("payload_hash", Tag.TAG_STRING)) {
                throw new IllegalStateException("关键 journal 版本或结构无效");
            }
            CompoundTag payload = root.getCompound("payload");
            if (!CanonicalNbtHasher.sha256(payload).equals(root.getString("payload_hash"))) {
                throw new IllegalStateException("关键 journal 摘要校验失败");
            }
            if (!payload.contains("sessions", Tag.TAG_LIST)) {
                throw new IllegalStateException("关键 journal 缺少会话清单");
            }
            ListTag sessionTags = (ListTag) payload.get("sessions");
            if (!sessionTags.isEmpty() && sessionTags.getElementType() != Tag.TAG_COMPOUND) {
                throw new IllegalStateException("关键 journal 会话清单类型无效");
            }
            List<EvaluationSession> sessions = new ArrayList<>();
            for (int index = 0; index < sessionTags.size(); index++) {
                sessions.add(EvaluationSession.load(sessionTags.getCompound(index), server.registryAccess()));
            }
            return new Snapshot(true, sessions);
        } catch (IOException | RuntimeException exception) {
            VirtualMachineErrors.rethrowIfPresent(exception);
            throw readFailure(server, "无法读取 Phase 4 关键 journal", exception);
        }
    }

    static PersistenceException writeFailure(String message, Throwable failure) {
        VirtualMachineErrors.rethrowIfPresent(failure);
        rethrowIfPersistenceFailure(failure);
        return new PersistenceException(message, failure, false);
    }

    static PersistenceException readFailure(MinecraftServer server, String message, Throwable failure) {
        VirtualMachineErrors.rethrowIfPresent(failure);
        unreadJournalServer = server;
        PersistenceException existing = persistenceFailure(failure);
        if (existing != null && existing.requiresReadRecovery()) {
            return existing;
        }
        return new PersistenceException(message, failure, true);
    }

    static void rethrowIfPersistenceFailure(Throwable failure) {
        VirtualMachineErrors.rethrowIfPresent(failure);
        PersistenceException persistence = persistenceFailure(failure);
        if (persistence != null) {
            throw persistence;
        }
    }

    /** Cause traversal must also terminate for malformed cyclic exception chains. */
    static PersistenceException persistenceFailure(Throwable failure) {
        VirtualMachineErrors.rethrowIfPresent(failure);
        Throwable slow = failure;
        Throwable fast = failure;
        while (slow != null) {
            if (slow instanceof PersistenceException persistence) {
                return persistence;
            }
            slow = slow.getCause();
            fast = fast == null ? null : fast.getCause();
            fast = fast == null ? null : fast.getCause();
            if (slow != null && slow == fast) {
                Throwable cycle = slow;
                do {
                    if (cycle instanceof PersistenceException persistence) {
                        return persistence;
                    }
                    cycle = cycle.getCause();
                } while (cycle != slow);
                return null;
            }
        }
        return null;
    }

    private static void requireWritable(MinecraftServer server) {
        if (unreadJournalServer == server) {
            throw new PersistenceException("关键 journal 尚未成功读取并合并，禁止覆盖或删除", null, true);
        }
    }

    private static Path path(MinecraftServer server) {
        Path worldRoot = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        Path path = worldRoot.resolve(RELATIVE_PATH).normalize();
        if (!path.startsWith(worldRoot)) {
            throw new IllegalStateException("关键 journal 路径逃逸世界目录");
        }
        return path;
    }

    static final class PersistenceException extends IllegalStateException {
        private final boolean requiresReadRecovery;

        private PersistenceException(String message, Throwable cause, boolean requiresReadRecovery) {
            super(message, cause);
            this.requiresReadRecovery = requiresReadRecovery;
        }

        boolean requiresReadRecovery() {
            return requiresReadRecovery;
        }
    }

    record Snapshot(boolean exists, List<EvaluationSession> sessions) {
    }
}
