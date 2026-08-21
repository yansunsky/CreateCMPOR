package com.yansunsky.createcmpor.evaluation;

import com.yansunsky.createcmpor.CreateCMPOR;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.DimensionPalette;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackPropagator;
import dev.compactmods.machines.api.CompactMachines;
import dev.compactmods.machines.api.dimension.CompactDimension;
import dev.compactmods.machines.api.room.RoomInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 5 Railway 闭合子图事务复制。
 *
 * <p>源火车属于全局 {@code create_tracks} SavedData，复制使用新 train/graph UUID；
 * 目标轨道图不手动复制，而是发布后对房间内轨道方块调用 {@link TrackPropagator#onRailAdded}
 * 让 Create 自动在 eval_world 建图，再通过 DimensionPalette 重映射深复制 Train 结构。</p>
 */
final class EvaluationRailwayTransfer {
    private EvaluationRailwayTransfer() {
    }

    /** 发布前判定：检测车厢、校验导航/闭合子图，分配新 train UUID 并改写车厢实体 NBT。 */
    static boolean prepare(MinecraftServer server, EvaluationSession session,
                           EvaluationManifest manifest, List<CompoundTag> rewrittenEntities) {
        Map<UUID, CompoundTag> carriagesByUuid = new HashMap<>();
        for (CompoundTag entity : rewrittenEntities) {
            if (EvaluationEntityInspector.CARRIAGE_CONTRAPTION_ID.equals(entity.getString("id"))
                    && entity.hasUUID("UUID") && entity.hasUUID("TrainId")) {
                carriagesByUuid.put(entity.getUUID("UUID"), entity);
            }
        }
        if (carriagesByUuid.isEmpty()) {
            return false;
        }

        RoomInstance room = requireRoom(server, session);
        AABB bounds = room.boundaries().outerBounds();

        // 每个源 train 一个记录
        Map<UUID, List<UUID>> carriagesByTrain = new LinkedHashMap<>();
        for (Map.Entry<UUID, CompoundTag> entry : carriagesByUuid.entrySet()) {
            carriagesByTrain.computeIfAbsent(entry.getValue().getUUID("TrainId"),
                    ignored -> new ArrayList<>()).add(entry.getKey());
        }

        for (Map.Entry<UUID, List<UUID>> entry : carriagesByTrain.entrySet()) {
            UUID sourceTrainId = entry.getKey();
            Train sourceTrain = Create.RAILWAYS.trains.get(sourceTrainId);
            if (sourceTrain == null) {
                throw new EvaluationStorageBridge.UnsupportedContentException(
                        "message.createcmpor.evaluation.railway_train_missing");
            }
            if (sourceTrain.navigation.destination != null) {
                throw new EvaluationStorageBridge.UnsupportedContentException(
                        "message.createcmpor.evaluation.railway_navigation_outside");
            }
            if (sourceTrain.graph == null) {
                throw new EvaluationStorageBridge.UnsupportedContentException(
                        "message.createcmpor.evaluation.railway_graph_missing");
            }
            verifyClosedSubgraph(sourceTrain.graph, bounds);

            UUID newTrainId = UUID.randomUUID();
            manifest.railwayRecords().add(new EvaluationManifest.RailwayRecord(
                    newTrainId, sourceTrainId, entry.getValue()));
            for (UUID carriageUuid : entry.getValue()) {
                carriagesByUuid.get(carriageUuid).putUUID("TrainId", newTrainId);
            }
        }
        return true;
    }

    /** 发布后（loadChunksToFull 之后、车厢实体首次 tick 前）建图并复制 train。 */
    static void setup(ServerLevel target, EvaluationSession session, EvaluationManifest manifest) {
        if (manifest.railwayRecords().isEmpty()) {
            return;
        }
        RoomInstance room = requireRoom(target.getServer(), session);
        AABB bounds = room.boundaries().outerBounds();

        for (EvaluationManifest.RailwayRecord record : manifest.railwayRecords()) {
            if (record.graphBuilt()) {
                continue;
            }
            for (BlockPos pos : BlockPos.betweenClosed(
                    BlockPos.containing(bounds.minX, bounds.minY, bounds.minZ),
                    BlockPos.containing(bounds.maxX, bounds.maxY, bounds.maxZ))) {
                if (target.getBlockState(pos).getBlock() instanceof TrackBlock) {
                    TrackPropagator.onRailAdded(target, pos, target.getBlockState(pos));
                }
            }
            TrackGraph newGraph = findGraph(target, bounds, record);
            Train newTrain = copyTrain(target.getServer(), record, newGraph);
            Create.RAILWAYS.addTrain(newTrain);
            record.markGraphBuilt(newGraph.id);
        }
    }

    /** 幂等清理：删除复制的 train/graph，并兜底扫描房间坐标内的 eval_world 轨道图。 */
    static void cleanup(ServerLevel target, EvaluationSession session, EvaluationManifest manifest) {
        if (manifest.railwayRecords().isEmpty()) {
            return;
        }
        RoomInstance room = requireRoom(target.getServer(), session);
        AABB bounds = room.boundaries().outerBounds();

        for (EvaluationManifest.RailwayRecord record : manifest.railwayRecords()) {
            Create.RAILWAYS.removeTrain(record.newTrainId());
            if (record.graphBuilt() && record.newGraphId() != null) {
                TrackGraph graph = Create.RAILWAYS.trackNetworks.get(record.newGraphId());
                if (graph != null) {
                    Create.RAILWAYS.removeGraphAndGroup(graph);
                }
            }
        }
        // 兜底：graphBuilt 记录缺失（崩溃窗口）时，删除节点全部位于本房间坐标内的 eval_world 图
        for (TrackGraph graph : new ArrayList<>(Create.RAILWAYS.trackNetworks.values())) {
            if (!graph.getNodes().isEmpty() && graph.getNodes().stream().allMatch(node ->
                    CreateCMPOR.EVAL_WORLD.equals(node.getDimension())
                            && bounds.contains(node.getLocation()))) {
                Create.RAILWAYS.removeGraphAndGroup(graph);
            }
        }
        Create.RAILWAYS.markTracksDirty();
    }

    private static void verifyClosedSubgraph(TrackGraph graph, AABB bounds) {
        List<TrackNode> roomNodes = new ArrayList<>();
        for (TrackNodeLocation location : graph.getNodes()) {
            if (CompactDimension.LEVEL_KEY.equals(location.getDimension())
                    && bounds.contains(location.getLocation())) {
                TrackNode node = graph.locateNode(location);
                if (node == null) {
                    throw new IllegalStateException("轨道图节点无法定位：" + location);
                }
                roomNodes.add(node);
            }
        }
        if (roomNodes.isEmpty()) {
            throw new EvaluationStorageBridge.UnsupportedContentException(
                    "message.createcmpor.evaluation.railway_carriage_outside");
        }
        for (TrackNode node : roomNodes) {
            for (TrackNode neighbor : graph.getConnectionsFrom(node).keySet()) {
                TrackNodeLocation location = neighbor.getLocation();
                if (!CompactDimension.LEVEL_KEY.equals(location.getDimension())
                        || !bounds.contains(location.getLocation())) {
                    throw new EvaluationStorageBridge.UnsupportedContentException(
                            "message.createcmpor.evaluation.railway_crosses_boundary");
                }
            }
        }
    }

    private static TrackGraph findGraph(ServerLevel target, AABB bounds,
                                        EvaluationManifest.RailwayRecord record) {
        TrackGraph best = null;
        for (TrackGraph graph : Create.RAILWAYS.trackNetworks.values()) {
            if (graph.getNodes().isEmpty()) {
                continue;
            }
            boolean allInside = graph.getNodes().stream().allMatch(node ->
                    CreateCMPOR.EVAL_WORLD.equals(node.getDimension())
                            && bounds.contains(node.getLocation()));
            if (allInside && (best == null || graph.getNodes().size() > best.getNodes().size())) {
                best = graph;
            }
        }
        if (best == null) {
            throw new IllegalStateException("eval_world 轨道图未建立：" + record.sourceTrainId());
        }
        return best;
    }

    private static Train copyTrain(MinecraftServer server, EvaluationManifest.RailwayRecord record,
                                   TrackGraph newGraph) {
        Train sourceTrain = Create.RAILWAYS.trains.get(record.sourceTrainId());
        if (sourceTrain == null) {
            throw new IllegalStateException("源火车已消失：" + record.sourceTrainId());
        }
        DimensionPalette writePalette = new DimensionPalette();
        CompoundTag tag = sourceTrain.write(writePalette, server.registryAccess());
        tag.putUUID("Id", record.newTrainId());
        tag.putUUID("Graph", newGraph.id);

        ListTag carriages = tag.getList("Carriages", Tag.TAG_COMPOUND);
        for (int index = 0; index < carriages.size(); index++) {
            CompoundTag carriageTag = carriages.getCompound(index);
            CompoundTag entityTag = carriageTag.getCompound("Entity");
            if (entityTag.contains("TrainId")) {
                entityTag.putUUID("TrainId", record.newTrainId());
                carriageTag.put("Entity", entityTag);
            }
            if (carriageTag.contains("EntityPositioning", Tag.TAG_LIST)) {
                ListTag positions = carriageTag.getList("EntityPositioning", Tag.TAG_COMPOUND);
                for (int positionIndex = 0; positionIndex < positions.size(); positionIndex++) {
                    if (positions.getCompound(positionIndex).getInt("Dim") > 0) {
                        throw new EvaluationStorageBridge.UnsupportedContentException(
                                "message.createcmpor.evaluation.railway_cross_dimension");
                    }
                }
            }
        }

        // 源编码的维度 index 0 = compact_world；读取时重映射为 eval_world
        DimensionPalette readPalette = new DimensionPalette(List.of(CreateCMPOR.EVAL_WORLD));
        return Train.read(tag, server.registryAccess(), Create.RAILWAYS.trackNetworks, readPalette);
    }

    private static RoomInstance requireRoom(MinecraftServer server, EvaluationSession session) {
        return CompactMachines.room(server, session.roomCode()).orElseThrow(() ->
                new IllegalStateException("源房间不存在：" + session.roomCode()));
    }
}
