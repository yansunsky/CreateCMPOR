package com.createcmpor.compat.cm;

import dev.compactmods.machines.api.CompactMachines;
import dev.compactmods.machines.api.room.RoomInstance;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec2;

import java.util.Optional;

/**
 * CompactMachines 7.x 适配实现。
 *
 * <p>这里只保留 CompactMachines 7.x 的稳定 API，版本敏感实现不进入评估主线。</p>
 */
public class CMAdapterV7 implements ICompactMachinesAdapter {

    @Override
    public Optional<RoomInstance> getRoom(MinecraftServer server, String roomCode) {
        return CompactMachines.room(server, roomCode);
    }

    @Override
    public void initializeDefaultSpawn(RoomInstance room) {
        CompactMachines.spawnManagers().get(room.code()).setDefaultSpawn(room.boundaries().defaultSpawn(), Vec2.ZERO);
    }

}
