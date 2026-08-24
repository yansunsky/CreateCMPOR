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

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 已固化工厂的持久化索引：{@code roomCode → 主世界工厂位置}。
 *
 * <p><b>与 {@link EvaluationSavedData} 独立</b>：评估会话结束后该索引必须保留
 * （还原数据在工厂 BE 内，索引用于在玩家进入压缩空间时反查对应工厂，自动还原防复制）。
 * </p>
 *
 * <p>写入时机：工厂固化（{@code EvaluationCloneManager.tickSolidifying}）时。
 * 清除时机：工厂被启动棒还原（{@code FactoryBlockEntity.revertToMachine}）时。
 * 查询若未命中只记录 WARN 日志，不阻塞（旧存档兼容：旧工厂无索引条目）。</p>
 */
public final class FactoryIndexSavedData extends SavedData {

    private static final String DATA_NAME = CreateCMPOR.MOD_ID + "_factory_index";
    private static final Factory<FactoryIndexSavedData> FACTORY = new Factory<>(
            FactoryIndexSavedData::new, FactoryIndexSavedData::load);

    private final Map<String, GlobalPos> factoryByRoom = new HashMap<>();

    public static FactoryIndexSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    private static FactoryIndexSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        FactoryIndexSavedData data = new FactoryIndexSavedData();
        ListTag entries = tag.getList("factories", Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i);
            String roomCode = entry.getString("room");
            if (roomCode.isBlank() || !entry.contains("dimension") || !entry.contains("pos")) {
                continue;
            }
            String dimension = entry.getString("dimension");
            Optional<BlockPos> pos = NbtUtils.readBlockPos(entry, "pos");
            if (pos.isEmpty() || dimension.isBlank()) {
                continue;
            }
            data.factoryByRoom.put(roomCode, GlobalPos.of(
                    ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                            net.minecraft.resources.ResourceLocation.tryParse(dimension)),
                    pos.get()));
        }
        return data;
    }

    /** 固化工厂时登记：roomCode → 主世界工厂位置。 */
    public void registerFactory(String roomCode, GlobalPos factoryPos) {
        factoryByRoom.put(roomCode, factoryPos);
        setDirty();
    }

    /** 工厂还原时移除索引。 */
    public void removeFactory(String roomCode) {
        if (factoryByRoom.remove(roomCode) != null) {
            setDirty();
        }
    }

    /** 按房间号查工厂位置；未命中返回 empty（旧存档兼容：仅 WARN，不阻塞）。 */
    public Optional<GlobalPos> factoryForRoom(String roomCode) {
        return Optional.ofNullable(factoryByRoom.get(roomCode));
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag entries = new ListTag();
        factoryByRoom.forEach((roomCode, globalPos) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("room", roomCode);
            entry.putString("dimension", globalPos.dimension().location().toString());
            entry.put("pos", NbtUtils.writeBlockPos(globalPos.pos()));
            entries.add(entry);
        });
        tag.put("factories", entries);
        return tag;
    }
}
