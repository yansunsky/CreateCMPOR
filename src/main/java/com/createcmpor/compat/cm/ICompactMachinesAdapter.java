package com.createcmpor.compat.cm;

import dev.compactmods.machines.api.room.RoomInstance;
import dev.compactmods.machines.api.room.spatial.IRoomBoundaries;
import dev.compactmods.machines.api.room.template.RoomTemplate;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * CompactMachines 版本适配接口。
 *
 * <p>后续所有对 CompactMachines 主 Mod 的调用都应经过此接口，避免评估主线直接依赖版本敏感实现类。</p>
 */
public interface ICompactMachinesAdapter {

    /** 查询已经注册的房间。 */
    Optional<RoomInstance> getRoom(MinecraftServer server, String roomCode);

    /** 创建一个一次性评估房间，并生成 CompactMachines 标准墙体。 */
    RoomInstance createEvaluationRoom(MinecraftServer server, RoomTemplate template, UUID owner);

    /** 获取房间所在的 CompactMachines 维度。 */
    ServerLevel getRoomLevel(RoomInstance room);

    /** 获取房间内部 chunk 列表。 */
    Stream<ChunkPos> getInnerChunks(IRoomBoundaries boundaries);

    /** 强加载或撤销强加载一个房间的内部 chunk。 */
    void setRoomForced(RoomInstance room, UUID ticketId, boolean forced);

    /**
     * 初始化房间默认出生点。
     *
     * <p>CompactMachines 7.0.81 在空模板创建房间时可能不会写入 default_spawn，
     * 但保存 spawn manager 时 codec 要求该字段非空，所以评估房间创建后必须主动初始化。</p>
     */
    void initializeDefaultSpawn(RoomInstance room);
}
