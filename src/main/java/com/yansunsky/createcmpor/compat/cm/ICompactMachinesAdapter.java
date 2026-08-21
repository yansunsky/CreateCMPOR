package com.yansunsky.createcmpor.compat.cm;

import dev.compactmods.machines.api.room.RoomInstance;
import net.minecraft.server.MinecraftServer;

import java.util.Optional;

/**
 * CompactMachines 版本适配接口。
 *
 * <p>后续所有对 CompactMachines 主 Mod 的调用都应经过此接口，避免评估主线直接依赖版本敏感实现类。</p>
 */
public interface ICompactMachinesAdapter {

    /** 查询已经注册的房间。 */
    Optional<RoomInstance> getRoom(MinecraftServer server, String roomCode);

    /**
     * 初始化房间默认出生点。
     *
     * <p>CompactMachines 7.0.81 在空模板创建房间时可能不会写入 default_spawn，
     * 但保存 spawn manager 时 codec 要求该字段非空，所以评估房间创建后必须主动初始化。</p>
     */
    void initializeDefaultSpawn(RoomInstance room);
}
