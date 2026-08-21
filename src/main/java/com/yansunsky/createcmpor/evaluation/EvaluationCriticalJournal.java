package com.yansunsky.createcmpor.evaluation;

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
import java.util.List;

/** 同步持久化 Phase 4 清理责任，避免依赖 SavedData 的异步写入。 */
final class EvaluationCriticalJournal {
    private static final int SCHEMA_VERSION = 1;
    private static final long MAX_READ_BYTES = 64L * 1024L * 1024L;
    private static final String RELATIVE_PATH = "data/createcmpor/evaluation-critical-journal.nbt";

    private EvaluationCriticalJournal() {
    }

    static void write(MinecraftServer server, EvaluationSavedData data) {
        CompoundTag payload = new CompoundTag();
        ListTag sessions = new ListTag();
        for (EvaluationSession session : data.criticalSessions()) {
            sessions.add(session.save());
        }
        payload.put("sessions", sessions);

        CompoundTag root = new CompoundTag();
        root.putInt("schema_version", SCHEMA_VERSION);
        root.put("payload", payload);
        root.putString("payload_hash", CanonicalNbtHasher.sha256(payload));
        Path path = path(server);
        try {
            Files.createDirectories(path.getParent());
            IOUtilities.writeNbtCompressed(root, path);
        } catch (IOException exception) {
            throw new IllegalStateException("无法持久化 Phase 4 关键 journal", exception);
        }
    }

    static void deleteIfEmpty(MinecraftServer server, EvaluationSavedData data) {
        if (!data.criticalSessions().isEmpty()) {
            return;
        }
        Path path = path(server);
        try {
            Files.deleteIfExists(path);
            if (Files.exists(path)) {
                throw new IOException("删除后文件仍存在");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("无法删除空 Phase 4 关键 journal", exception);
        }
    }

    static Snapshot read(MinecraftServer server) {
        Path path = path(server);
        if (Files.notExists(path)) {
            return new Snapshot(false, List.of());
        }
        try {
            CompoundTag root = NbtIo.readCompressed(path, NbtAccounter.create(MAX_READ_BYTES));
            if (root.getInt("schema_version") != SCHEMA_VERSION
                    || !root.contains("payload", Tag.TAG_COMPOUND)) {
                throw new IllegalStateException("关键 journal 版本或结构无效");
            }
            CompoundTag payload = root.getCompound("payload");
            if (!CanonicalNbtHasher.sha256(payload).equals(root.getString("payload_hash"))) {
                throw new IllegalStateException("关键 journal 摘要校验失败");
            }
            List<EvaluationSession> sessions = new ArrayList<>();
            ListTag sessionTags = payload.getList("sessions", Tag.TAG_COMPOUND);
            for (int index = 0; index < sessionTags.size(); index++) {
                sessions.add(EvaluationSession.load(sessionTags.getCompound(index), server.registryAccess()));
            }
            return new Snapshot(true, sessions);
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("无法读取 Phase 4 关键 journal：" + path, exception);
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

    record Snapshot(boolean exists, List<EvaluationSession> sessions) {
    }
}
