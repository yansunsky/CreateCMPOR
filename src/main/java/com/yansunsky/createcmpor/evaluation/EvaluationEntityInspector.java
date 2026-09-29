package com.yansunsky.createcmpor.evaluation;

import com.yansunsky.createcmpor.CreateCMPOR;
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

    /**
     * 需要<b>完整检查</b>（引用完整性 + 黑名单）的装置实体 id。
     *
     * <p>清单以 Create 6.0.10-281 发行 jar 为准（{@code AllEntityTypes}）：
     * {@code contraption} = {@code OrientedContraptionEntity}（矿车装置等）、
     * {@code stationary_contraption} = {@code ControlledContraptionEntity}
     * （机械轴承/发条轴承/机械活塞/线性致动器/绳索滑轮/电梯）、
     * {@code gantry_contraption} = {@code GantryContraptionEntity}。</p>
     *
     * <p><b>0.4.7 修复</b>：注册名与类名不一致，旧清单凭类名推 id 写成了不存在的
     * {@code create:oriented_contraption} / {@code create:controlled_contraption}，
     * 而真实存在的 {@code create:stationary_contraption} 被漏掉 —— 该 id 的装置
     * （轴承/活塞/滑轮装置）因此完全绕过检查。新增 id 前必须先在 jar 里核对字符串。</p>
     */
    private static final Set<String> IN_ROOM_CONTRAPTION_IDS = Set.of(
            "create:contraption", "create:stationary_contraption", "create:gantry_contraption");

    /**
     * 只做黑名单（方块 palette + 停用演员物品）、不做引用完整性判定的装置 id。
     *
     * <p>车厢装置：列车/车厢引用（{@code TrainId}）由 {@link EvaluationRailwayTransfer}
     * 的事务通道改写，乘客映射又可能指向房间外实体；在这里再判一次引用完整性有
     * <b>误伤正常列车评估</b>的风险（未实机验证），故保守取黑名单子集。</p>
     */
    private static final Set<String> BLACKLIST_ONLY_CONTRAPTION_IDS = Set.of(CARRIAGE_CONTRAPTION_ID);

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
        Blacklist.checkEntity(typeId);

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

        // contraption：引用完整性 + 物品/方块黑名单 + 内嵌乘客递归。
        // 判定门槛是「NBT 里带 Contraption 复合」+「id 在装置清单内」——
        // 缺一不可：没有 Contraption 复合的实体无需检查，不在清单内的 id 不做引用判定。
        if (tag.contains("Contraption", Tag.TAG_COMPOUND)
                && (IN_ROOM_CONTRAPTION_IDS.contains(id) || BLACKLIST_ONLY_CONTRAPTION_IDS.contains(id))) {
            CompoundTag contraption = tag.getCompound("Contraption");
            if (IN_ROOM_CONTRAPTION_IDS.contains(id)) {
                checkContraptionReferences(contraption, allUuids);
            }
            int paletteSize = checkContraptionBlacklist(contraption);
            // 实机取证用：这一行能区分「闸门跑了但没命中」与「闸门根本没跑」——
            // 0.4.7 之前的 id 清单 bug 属于后者，当时零日志、零提示（fail-open 静默）。
            CreateCMPOR.LOGGER.info("装置实体 {} 内容已检查：方块 palette {} 项，无黑名单命中",
                    id, paletteSize);
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

    /** 引用完整性：装置内引用的实体（座位乘客、子装置）必须也在本次快照里，否则副本里无人可坐/子装置悬空。 */
    private static void checkContraptionReferences(CompoundTag contraption, Set<UUID> allUuids) {
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
    }

    /**
     * 黑名单：装置携带的方块（{@code Blocks.Palette} 的 {@code Name}）与停用演员物品。
     *
     * <p>装置整体被复制进评估副本（实体 NBT 随区块写入 eval_world），其内部方块在副本里照常工作，
     * 所以这里的方块/物品黑名单是评估准入的一部分，不是可选优化。</p>
     *
     * @return 检查到的方块 palette 项数（仅供调用方记取证日志）
     */
    private static int checkContraptionBlacklist(CompoundTag contraption) {
        if (contraption.contains("DisabledActors", Tag.TAG_LIST)) {
            ListTag disabledActors = contraption.getList("DisabledActors", Tag.TAG_COMPOUND);
            for (int index = 0; index < disabledActors.size(); index++) {
                checkItemStack(disabledActors.getCompound(index));
            }
        }
        int paletteSize = 0;
        if (contraption.contains("Blocks", Tag.TAG_COMPOUND)) {
            CompoundTag blocks = contraption.getCompound("Blocks");
            if (blocks.contains("Palette", Tag.TAG_LIST)) {
                ListTag palette = blocks.getList("Palette", Tag.TAG_COMPOUND);
                paletteSize = palette.size();
                for (int index = 0; index < palette.size(); index++) {
                    CompoundTag stateTag = palette.getCompound(index);
                    if (stateTag.contains("Name", Tag.TAG_STRING)) {
                        Blacklist.checkBlock(stateTag.getString("Name"));
                    }
                }
            }
        }
        return paletteSize;
    }

    private static void checkItemStack(CompoundTag itemTag) {
        if (itemTag.contains("id", Tag.TAG_STRING)) {
            Blacklist.checkItem(ResourceLocation.tryParse(itemTag.getString("id")));
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
