package com.createcmpor.compat.cm;

import com.createcmpor.CreateCMPOR;
import dev.compactmods.machines.api.CompactMachines;
import dev.compactmods.machines.api.dimension.MissingDimensionException;
import dev.compactmods.machines.api.room.RoomInstance;
import dev.compactmods.machines.api.room.spatial.IRoomBoundaries;
import dev.compactmods.machines.api.room.template.RoomTemplate;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec2;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * CompactMachines 7.x 适配实现。
 *
 * <p>稳定 API 直接走 core-api；强加载 ticket 暂时通过反射访问主 Mod 内部控制器，后续如 CM 暴露公共 API 再替换。</p>
 */
public class CMAdapterV7 implements ICompactMachinesAdapter {

    @Override
    public Optional<RoomInstance> getRoom(MinecraftServer server, String roomCode) {
        return CompactMachines.room(server, roomCode);
    }

    @Override
    public RoomInstance createEvaluationRoom(MinecraftServer server, RoomTemplate template, UUID owner) {
        try {
            return CompactMachines.newRoom(server, template, owner);
        } catch (MissingDimensionException exception) {
            throw new IllegalStateException("CompactMachines 维度不存在，无法创建评估房间", exception);
        }
    }

    @Override
    public ServerLevel getRoomLevel(RoomInstance room) {
        return room.level();
    }

    @Override
    public Stream<ChunkPos> getInnerChunks(IRoomBoundaries boundaries) {
        return boundaries.innerChunkPositions();
    }

    @Override
    public void setRoomForced(RoomInstance room, UUID ticketId, boolean forced) {
        ServerLevel level = room.level();
        getInnerChunks(room.boundaries()).forEach(chunkPos -> forceChunk(level, ticketId, chunkPos, forced));
    }

    @Override
    public void initializeDefaultSpawn(RoomInstance room) {
        CompactMachines.spawnManagers().get(room.code()).setDefaultSpawn(room.boundaries().defaultSpawn(), Vec2.ZERO);
    }

    /**
     * 反射调用 CompactMachinesServer.CHUNK_TICKET_CONTROLLER.forceChunk。
     *
     * <p>这里刻意不直接 import 主 Mod 内部类，避免后续 CompactMachines 小版本变动时污染评估主线。</p>
     */
    private void forceChunk(ServerLevel level, UUID ticketId, ChunkPos chunkPos, boolean forced) {
        try {
            Class<?> serverClass = Class.forName("dev.compactmods.machines.server.CompactMachinesServer");
            Object controller = serverClass.getField("CHUNK_TICKET_CONTROLLER").get(null);
            Method forceChunk = controller.getClass().getMethod(
                    "forceChunk", ServerLevel.class, Object.class, int.class, int.class, boolean.class, boolean.class);
            forceChunk.invoke(controller, level, ticketId, chunkPos.x, chunkPos.z, forced, true);
        } catch (ReflectiveOperationException exception) {
            CreateCMPOR.LOGGER.warn("无法通过 CompactMachines chunk ticket 控制器{}加载 chunk {}", forced ? "强制" : "取消强制", chunkPos, exception);
        }
    }
}
