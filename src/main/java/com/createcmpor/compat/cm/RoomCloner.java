package com.createcmpor.compat.cm;

import com.createcmpor.CreateCMPOR;
import dev.compactmods.machines.api.room.spatial.IRoomBoundaries;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/**
 * CompactMachines 房间复制工具。
 */
public class RoomCloner {

    /**
     * 读取房间内部区域，生成相对坐标快照。
     */
    public RoomSnapshot snapshot(ServerLevel level, IRoomBoundaries boundaries) {
        AABB inner = boundaries.innerBounds();
        BlockPos min = minCorner(inner);
        BlockPos max = maxCornerExclusive(inner);
        RoomSnapshot snapshot = new RoomSnapshot(max.getX() - min.getX(), max.getY() - min.getY(), max.getZ() - min.getZ());

        for (BlockPos absolute : BlockPos.betweenClosed(min, max.offset(-1, -1, -1))) {
            BlockPos immutableAbsolute = absolute.immutable();
            BlockPos relative = immutableAbsolute.subtract(min);
            var state = level.getBlockState(immutableAbsolute);
            snapshot.addBlock(relative, state);

            var blockEntity = level.getBlockEntity(immutableAbsolute);
            if (blockEntity != null) {
                var tag = blockEntity.saveWithFullMetadata(level.registryAccess());
                snapshot.addBlockEntity(relative, CreateNbtSanitizer.sanitizeBlockEntityTag(tag));
            }
        }

        CreateCMPOR.LOGGER.info("已快照房间：{} blocks, {} block entities", snapshot.blockCount(), snapshot.blockEntityCount());
        return snapshot;
    }

    /**
     * 将快照写入目标房间内部区域。
     */
    public void apply(ServerLevel level, IRoomBoundaries target, RoomSnapshot snapshot) {
        BlockPos targetMin = minCorner(target.innerBounds());

        for (RoomSnapshot.BlockEntry entry : snapshot.blocks()) {
            BlockPos targetPos = targetMin.offset(entry.relativePos());
            level.setBlock(targetPos, entry.state(), Block.UPDATE_ALL);
        }

        for (RoomSnapshot.BlockEntityEntry entry : snapshot.blockEntities()) {
            BlockPos targetPos = targetMin.offset(entry.relativePos());
            var blockEntity = level.getBlockEntity(targetPos);
            if (blockEntity != null) {
                blockEntity.loadWithComponents(entry.tag(), level.registryAccess());
                blockEntity.setChanged();
            }
        }

        CreateCMPOR.LOGGER.info("已应用房间快照：{} blocks, {} block entities", snapshot.blockCount(), snapshot.blockEntityCount());
    }

    /**
     * 清空房间内部区域。墙体不动，保留 CompactMachines 房间壳。
     */
    public void clearInner(ServerLevel level, IRoomBoundaries boundaries) {
        AABB inner = boundaries.innerBounds();
        BlockPos min = minCorner(inner);
        BlockPos max = maxCornerExclusive(inner);

        for (BlockPos pos : BlockPos.betweenClosed(min, max.offset(-1, -1, -1))) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    private static BlockPos minCorner(AABB bounds) {
        return BlockPos.containing(bounds.minX, bounds.minY, bounds.minZ);
    }

    /**
     * 返回半开区间最大角，便于计算尺寸。
     */
    private static BlockPos maxCornerExclusive(AABB bounds) {
        return BlockPos.containing(bounds.maxX, bounds.maxY, bounds.maxZ);
    }
}
