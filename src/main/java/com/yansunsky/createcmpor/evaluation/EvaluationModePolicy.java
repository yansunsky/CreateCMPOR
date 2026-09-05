package com.yansunsky.createcmpor.evaluation;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;

import java.util.List;
import java.util.Map;

/**
 * 并行评估的自动退回策略。
 *
 * <p>默认优先并行；只有会把分支状态串到全局 UUID/SavedData 的内容才退回当前串行分支。
 * POI 是维度内自包含存储，不触发退回。</p>
 */
final class EvaluationModePolicy {
    private EvaluationModePolicy() {
    }

    /** 检测房间内容是否需要退回串行；返回 null 表示允许并行。 */
    static String serialFallbackReason(Map<ChunkPos, CompoundTag> sourceChunks,
                                       Map<ChunkPos, List<CompoundTag>> rewrittenEntities) {
        if (sourceChunks != null) {
            for (CompoundTag chunk : sourceChunks.values()) {
                String reason = scanTag(chunk, 0);
                if (reason != null) {
                    return reason;
                }
            }
        }
        if (rewrittenEntities != null) {
            for (List<CompoundTag> entities : rewrittenEntities.values()) {
                for (CompoundTag entity : entities) {
                    String reason = scanTag(entity, 0);
                    if (reason != null) {
                        return reason;
                    }
                }
            }
        }
        return null;
    }

    private static String scanTag(Tag tag, int depth) {
        if (tag == null || depth > 64) {
            return null;
        }
        if (tag instanceof CompoundTag compound) {
            if (compound.contains("sophisticatedcore:storage_uuid")) {
                return "sophisticatedcore:storage_uuid";
            }
            if (compound.contains("id", Tag.TAG_STRING)) {
                String id = compound.getString("id");
                if (isParallelUnsafeId(id)) {
                    return id;
                }
            }
            if (compound.contains("Name", Tag.TAG_STRING)) {
                String id = compound.getString("Name");
                if (isParallelUnsafeId(id)) {
                    return id;
                }
            }
            for (String key : compound.getAllKeys()) {
                if ("sophisticatedcore:storage_uuid".equals(key)) {
                    return key;
                }
                String reason = scanTag(compound.get(key), depth + 1);
                if (reason != null) {
                    return reason;
                }
            }
        } else if (tag instanceof ListTag list) {
            for (int index = 0; index < list.size(); index++) {
                String reason = scanTag(list.get(index), depth + 1);
                if (reason != null) {
                    return reason;
                }
            }
        }
        return null;
    }

    private static boolean isParallelUnsafeId(String id) {
        ResourceLocation parsed = ResourceLocation.tryParse(id);
        if (parsed == null) {
            return false;
        }
        if ("sophisticatedbackpacks".equals(parsed.getNamespace())
                || "sophisticatedstorage".equals(parsed.getNamespace())) {
            return true;
        }
        if (!"create".equals(parsed.getNamespace())) {
            return false;
        }
        return parsed.getPath().startsWith("track")
                || "carriage_contraption".equals(parsed.getPath())
                || parsed.getPath().endsWith("_contraption");
    }
}
