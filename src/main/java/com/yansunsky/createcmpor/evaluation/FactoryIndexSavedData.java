package com.yansunsky.createcmpor.evaluation;

import com.yansunsky.createcmpor.CreateCMPOR;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 已固化工厂的持久化索引：{@code roomCode → 主世界工厂位置列表}。
 *
 * <p><b>与 {@link EvaluationSavedData} 独立</b>：评估会话结束后该索引必须保留
 * （还原数据在工厂 BE 内，索引用于在玩家进入压缩空间时反查对应工厂，自动还原防复制；
 * 以及多工厂组还原时数量校验）。</p>
 *
 * <p>列表第一个元素 = 主位置（原机器位），其余为多工厂组中向上堆叠的成员。</p>
 *
 * <p><b>0.5.0 起</b>：一次评估只固化一个工厂 ⇒ 每个房间的列表<b>恰好一个</b>位置。
 * 写路径用 {@link #registerFactory(String, List)}（覆盖写，固化）与 {@link #removeFactory(String)}
 * （彻底清除，还原）——一房间一台工厂时它们就是正确语义。"多位置 = 多工厂组"是
 * <b>旧存档遗留形态</b>：只有<b>多位置查询</b> {@link #factoriesForRoom} / {@link #factoryForRoom}
 * 标了 {@code @Deprecated}（{@code positions.get(0)} 语义只服务旧档遗留组，计划 0.6.x 彻底移除）；
 * 旧档仍可读取、运行、整组还原，并可用 {@code /ccmpor factory legacy} 清理。</p>
 *
 * <p>写入时机：工厂固化（{@code EvaluationCloneManager.tickSolidifying}）时。
 * 清除时机：工厂被启动棒还原（{@code FactoryBlockEntity.revertToMachine}）时。
 * 查询若未命中只记录 WARN 日志，不阻塞（旧存档兼容：旧工厂无索引条目）。</p>
 */
public final class FactoryIndexSavedData extends SavedData {

    private static final String DATA_NAME = CreateCMPOR.MOD_ID + "_factory_index";
    private static final Factory<FactoryIndexSavedData> FACTORY = new Factory<>(
            FactoryIndexSavedData::new, FactoryIndexSavedData::load);

    private final Map<String, List<GlobalPos>> factoryByRoom = new HashMap<>();

    public static FactoryIndexSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    private static FactoryIndexSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        FactoryIndexSavedData data = new FactoryIndexSavedData();
        ListTag entries = tag.getList("factories", Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i);
            String roomCode = entry.getString("room");
            if (roomCode.isBlank()) {
                continue;
            }
            List<GlobalPos> positions = new ArrayList<>();
            if (entry.contains("dimension") && entry.contains("pos")) {
                String dimension = entry.getString("dimension");
                Optional<BlockPos> pos = NbtUtils.readBlockPos(entry, "pos");
                if (pos.isPresent() && !dimension.isBlank()) {
                    positions.add(GlobalPos.of(
                            ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                                    net.minecraft.resources.ResourceLocation.tryParse(dimension)),
                            pos.get()));
                }
            }
            // 旧格式可能只有单位置；新格式有多位置列表
            if (entry.contains("positions", Tag.TAG_LIST)) {
                ListTag posList = entry.getList("positions", Tag.TAG_COMPOUND);
                String dimension = entry.getString("dimension");
                for (int p = 0; p < posList.size(); p++) {
                    Optional<BlockPos> ppos = NbtUtils.readBlockPos(posList.getCompound(p), "pos");
                    if (ppos.isPresent() && !dimension.isBlank()) {
                        positions.add(GlobalPos.of(
                                ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                                        net.minecraft.resources.ResourceLocation.tryParse(dimension)),
                                ppos.get()));
                    }
                }
            }
            if (!positions.isEmpty()) {
                data.factoryByRoom.put(roomCode, positions);
            }
        }
        return data;
    }

    /**
     * 固化工厂时登记：roomCode → 工厂位置列表（第一个为主位置）。
     *
     * <p><b>0.5.0 起：一房间一台工厂，新固化路径就该用本方法</b>（传单位置列表）。
     * 它是<b>覆盖写</b>——保证索引里不会残留旧档遗留组的位置或历史脏数据，而"索引干净"
     * 正是评估前置闸门（判断该房间是否已固化出工厂）不误判的前提。
     * 增量登记（工厂物品被放下/重放）用 {@link #registerFactoryPosition(String, GlobalPos)}。</p>
     */
    public void registerFactory(String roomCode, List<GlobalPos> positions) {
        if (positions == null || positions.isEmpty()) {
            return;
        }
        factoryByRoom.put(roomCode, new ArrayList<>(positions));
        setDirty();
    }

    /**
     * 工厂还原时清除该房间的索引（整条）。
     *
     * <p><b>0.5.0 起：一房间一台工厂，新还原路径就该用本方法</b>。它是<b>彻底清除</b>——
     * 比按位置删更安全：位置一旦对不上（旧档坐标漂移、历史脏数据）就删不干净 ⇒ 残留 ⇒ 闸门误判。
     * 增量移除（工厂被破坏/取走）用 {@link #removeFactoryPosition(String, GlobalPos)}。</p>
     */
    public void removeFactory(String roomCode) {
        if (factoryByRoom.remove(roomCode) != null) {
            setDirty();
        }
    }

    /**
     * 增量注册单个工厂位置（幂等）：用于工厂被取下重放（任意位置）后保持索引与"实际存在的工厂"一致，
     * 使组还原数量校验不再依赖固化时坐标（无顺序/位置限制，齐了即可还原）。
     */
    public void registerFactoryPosition(String roomCode, GlobalPos pos) {
        List<GlobalPos> positions = factoryByRoom.computeIfAbsent(roomCode, k -> new ArrayList<>());
        if (!positions.contains(pos)) {
            positions.add(pos);
            setDirty();
        }
    }

    /** 增量移除单个工厂位置（工厂被破坏/取走时）；列表空则删除房间条目。 */
    public void removeFactoryPosition(String roomCode, GlobalPos pos) {
        List<GlobalPos> positions = factoryByRoom.get(roomCode);
        if (positions == null) {
            return;
        }
        if (positions.remove(pos)) {
            if (positions.isEmpty()) {
                factoryByRoom.remove(roomCode);
            }
            setDirty();
        }
    }

    /**
     * 按房间号查工厂位置列表（第一个 = 主位置）；未命中返回 empty。
     *
     * @deprecated 遗留兼容：旧存档的多工厂组（factory_count &gt; 1），计划 0.6.x 彻底移除。
     *     仅 {@code FactoryBlock.revertGroup} 与 {@code /ccmpor factory legacy remove|revert} 使用
     *     （{@code legacy list} 用 {@link #snapshotAll()}）。
     */
    @Deprecated
    public Optional<List<GlobalPos>> factoriesForRoom(String roomCode) {
        return Optional.ofNullable(factoryByRoom.get(roomCode));
    }

    /**
     * 按房间号查主位置工厂（防复制自动还原用）；未命中返回 empty（旧存档兼容：仅 WARN，不阻塞）。
     *
     * @deprecated 遗留兼容：旧存档的多工厂组（factory_count &gt; 1），计划 0.6.x 彻底移除。
     *     防复制护栏"只还原 index[0]"是遗留行为，跨版本保持不变（见
     *     {@code AntiDupeSpaceEntryHandler}）；新档每个房间只有一个位置，语义等价。
     */
    @Deprecated
    public Optional<GlobalPos> factoryForRoom(String roomCode) {
        List<GlobalPos> positions = factoryByRoom.get(roomCode);
        if (positions == null || positions.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(positions.get(0));
    }

    /**
     * 全部房间 → 位置列表的只读快照（供诊断、清理命令与评估前置闸门使用；返回副本，改动不影响内部状态）。
     *
     * <p>不是遗留 API：需要枚举所有房间的场景（例如"该房间是否已固化出工厂"的前置校验、
     * {@code /ccmpor factory legacy list} 挑出多成员组）无法用"按房间号逐个查"的旧 API 表达。
     * 返回的列表顺序 = 登记顺序（第一个为主位置）。</p>
     */
    public Map<String, List<GlobalPos>> snapshotAll() {
        Map<String, List<GlobalPos>> copy = new HashMap<>();
        factoryByRoom.forEach((roomCode, positions) -> copy.put(roomCode, List.copyOf(positions)));
        return copy;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag entries = new ListTag();
        factoryByRoom.forEach((roomCode, positions) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("room", roomCode);
            if (!positions.isEmpty()) {
                GlobalPos first = positions.get(0);
                entry.putString("dimension", first.dimension().location().toString());
                entry.put("pos", NbtUtils.writeBlockPos(first.pos()));
            }
            ListTag posList = new ListTag();
            for (GlobalPos pos : positions) {
                CompoundTag posTag = new CompoundTag();
                posTag.put("pos", NbtUtils.writeBlockPos(pos.pos()));
                posList.add(posTag);
            }
            entry.put("positions", posList);
            entries.add(entry);
        });
        tag.put("factories", entries);
        return tag;
    }
}
