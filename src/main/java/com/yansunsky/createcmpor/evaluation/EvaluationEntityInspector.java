package com.yansunsky.createcmpor.evaluation;

import com.yansunsky.createcmpor.Config;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 5 实体 NBT 检查与改写。
 *
 * <p>全部操作在 NBT 级完成，不实体化：分类（普通/contraption/车厢）、黑名单三级、
 * 实体图引用完整性、拴绳温和退化、车厢 TrainId 改写。</p>
 */
final class EvaluationEntityInspector {
    static final String CARRIAGE_CONTRAPTION_ID = "create:carriage_contraption";
    private static final Set<String> CONTRAPTION_ENTITY_IDS = Set.of(
            "create:contraption", "create:oriented_contraption",
            "create:controlled_contraption", "create:gantry_contraption");

    record AllChunksResult(Map<ChunkPos, List<CompoundTag>> rewrittenByChunk,
                           List<UUID> carriageUuids, int totalEntities) {
        boolean hasEntities() {
            return totalEntities > 0;
        }
    }

    /** 全房间一起检查：跨 chunk 的实体图引用需要全局 UUID 集合。 */
    static AllChunksResult inspectAll(Map<ChunkPos, ListTag> entitiesByChunk) {
        Set<UUID> allUuids = new HashSet<>();
        for (ListTag entities : entitiesByChunk.values()) {
            for (int index = 0; index < entities.size(); index++) {
                collectUuids(entities.getCompound(index), allUuids);
            }
        }

        Map<ChunkPos, List<CompoundTag>> rewrittenByChunk = new LinkedHashMap<>();
        List<UUID> carriages = new ArrayList<>();
        int total = 0;
        for (Map.Entry<ChunkPos, ListTag> entry : entitiesByChunk.entrySet()) {
            ListTag entities = entry.getValue();
            List<CompoundTag> rewritten = new ArrayList<>();
            for (int index = 0; index < entities.size(); index++) {
                CompoundTag tag = entities.getCompound(index).copy();
                checkEntity(tag, allUuids, carriages, 0);
                rewritten.add(tag);
                total++;
            }
            rewrittenByChunk.put(entry.getKey(), rewritten);
        }
        return new AllChunksResult(rewrittenByChunk, carriages, total);
    }

    private static void collectUuids(CompoundTag entity, Set<UUID> output) {
        if (entity.hasUUID("UUID")) {
            output.add(entity.getUUID("UUID"));
        }
        if (entity.contains("Passengers", Tag.TAG_LIST)) {
            ListTag passengers = entity.getList("Passengers", Tag.TAG_COMPOUND);
            for (int index = 0; index < passengers.size(); index++) {
                collectUuids(passengers.getCompound(index), output);
            }
        }
    }

    private static void checkEntity(CompoundTag tag, Set<UUID> allUuids, List<UUID> carriages, int depth) {
        if (depth > 8) {
            throw new IllegalStateException("实体乘客嵌套过深");
        }
        String id = tag.getString("id");
        if (id.isBlank() || ResourceLocation.tryParse(id) == null) {
            throw new IllegalStateException("实体缺少有效 id：" + id);
        }
        ResourceLocation typeId = ResourceLocation.parse(id);
        if (Config.SUSPICIOUS_MODS.get().contains(typeId.getNamespace())) {
            throw new EvaluationStorageBridge.UnsupportedContentException(
                    "message.createcmpor.evaluation.entity_mod_blacklisted");
        }

        if (CARRIAGE_CONTRAPTION_ID.equals(id)) {
            if (!tag.hasUUID("UUID")) {
                throw new IllegalStateException("车厢实体缺少 UUID");
            }
            carriages.add(tag.getUUID("UUID"));
        }

        // 物品级黑名单：掉落物实体的携带物
        if ("minecraft:item".equals(id) && tag.contains("Item", Tag.TAG_COMPOUND)) {
            checkItemStack(tag.getCompound("Item"));
        }

        // contraption：引用完整性 + 物品/方块黑名单 + 内嵌乘客递归
        if (CONTRAPTION_ENTITY_IDS.contains(id) && tag.contains("Contraption", Tag.TAG_COMPOUND)) {
            checkContraption(tag.getCompound("Contraption"), allUuids);
        }

        // 温和退化：拴绳引用过滤（房间外的 UUID 丢弃）
        degradeLeash(tag, allUuids);

        // 内嵌乘客递归检查
        if (tag.contains("Passengers", Tag.TAG_LIST)) {
            ListTag passengers = tag.getList("Passengers", Tag.TAG_COMPOUND);
            for (int index = 0; index < passengers.size(); index++) {
                CompoundTag passenger = passengers.getCompound(index);
                checkEntity(passenger, allUuids, carriages, depth + 1);
            }
        }
    }

    private static void checkContraption(CompoundTag contraption, Set<UUID> allUuids) {
        if (contraption.contains("Passengers", Tag.TAG_LIST)) {
            ListTag passengers = contraption.getList("Passengers", Tag.TAG_COMPOUND);
            for (int index = 0; index < passengers.size(); index++) {
                CompoundTag entry = passengers.getCompound(index);
                if (!entry.contains("Id", Tag.TAG_INT_ARRAY)) {
                    throw new IllegalStateException("contraption 乘客映射缺少 Id");
                }
                UUID referenced = NbtUtils.loadUUID(entry.get("Id"));
                if (!allUuids.contains(referenced)) {
                    throw new EvaluationStorageBridge.UnsupportedContentException(
                            "message.createcmpor.evaluation.entity_reference_outside");
                }
            }
        }
        if (contraption.contains("SubContraptions", Tag.TAG_LIST)) {
            ListTag subContraptions = contraption.getList("SubContraptions", Tag.TAG_COMPOUND);
            for (int index = 0; index < subContraptions.size(); index++) {
                CompoundTag entry = subContraptions.getCompound(index);
                if (!entry.hasUUID("Id") || !allUuids.contains(entry.getUUID("Id"))) {
                    throw new EvaluationStorageBridge.UnsupportedContentException(
                            "message.createcmpor.evaluation.entity_reference_outside");
                }
            }
        }
        if (contraption.contains("DisabledActors", Tag.TAG_LIST)) {
            ListTag disabledActors = contraption.getList("DisabledActors", Tag.TAG_COMPOUND);
            for (int index = 0; index < disabledActors.size(); index++) {
                checkItemStack(disabledActors.getCompound(index));
            }
        }
        if (contraption.contains("Blocks", Tag.TAG_COMPOUND)) {
            CompoundTag blocks = contraption.getCompound("Blocks");
            if (blocks.contains("Palette", Tag.TAG_LIST)) {
                ListTag palette = blocks.getList("Palette", Tag.TAG_COMPOUND);
                for (int index = 0; index < palette.size(); index++) {
                    CompoundTag stateTag = palette.getCompound(index);
                    if (stateTag.contains("Name", Tag.TAG_STRING)
                            && Config.SUSPICIOUS_BLOCKS.get().contains(stateTag.getString("Name"))) {
                        throw new EvaluationStorageBridge.UnsupportedContentException(
                                "message.createcmpor.evaluation.block_blacklisted");
                    }
                }
            }
        }
    }

    private static void checkItemStack(CompoundTag itemTag) {
        if (itemTag.contains("id", Tag.TAG_STRING)
                && Config.SUSPICIOUS_ITEMS.get().contains(itemTag.getString("id"))) {
            throw new EvaluationStorageBridge.UnsupportedContentException(
                    "message.createcmpor.evaluation.item_blacklisted");
        }
    }

    private static void degradeLeash(CompoundTag tag, Set<UUID> allUuids) {
        if (!tag.contains("Leash", Tag.TAG_LIST)) {
            return;
        }
        ListTag leash = tag.getList("Leash", Tag.TAG_INT_ARRAY);
        ListTag filtered = new ListTag();
        for (int index = 0; index < leash.size(); index++) {
            UUID referenced = NbtUtils.loadUUID(leash.get(index));
            if (allUuids.contains(referenced)) {
                filtered.add(leash.get(index));
            }
        }
        if (filtered.isEmpty()) {
            tag.remove("Leash");
        } else {
            tag.put("Leash", filtered);
        }
    }
}
