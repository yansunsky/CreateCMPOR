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

    /** 固化工厂时登记整组：roomCode → 工厂位置列表（第一个为主位置）。 */
    public void registerFactory(String roomCode, List<GlobalPos> positions) {
        if (positions == null || positions.isEmpty()) {
            return;
        }
        factoryByRoom.put(roomCode, new ArrayList<>(positions));
        setDirty();
    }

    /** 工厂还原时移除索引（整组）。 */
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

    /** 按房间号查工厂位置列表（第一个 = 主位置）；未命中返回 empty。 */
    public Optional<List<GlobalPos>> factoriesForRoom(String roomCode) {
        return Optional.ofNullable(factoryByRoom.get(roomCode));
    }

    /** 按房间号查主位置工厂（防复制自动还原用）；未命中返回 empty（旧存档兼容：仅 WARN，不阻塞）。 */
    public Optional<GlobalPos> factoryForRoom(String roomCode) {
        List<GlobalPos> positions = factoryByRoom.get(roomCode);
        if (positions == null || positions.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(positions.get(0));
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
