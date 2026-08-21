package com.yansunsky.createcmpor.compat.cm;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * 房间快照。
 *
 * <p>Phase 2 先记录方块和方块实体；实体复制会在 Contraption spike 中单独处理。</p>
 */
public class RoomSnapshot {

    private final int width;
    private final int height;
    private final int depth;
    private final List<BlockEntry> blocks;
    private final List<BlockEntityEntry> blockEntities;

    public RoomSnapshot(int width, int height, int depth) {
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.blocks = new ArrayList<>();
        this.blockEntities = new ArrayList<>();
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int depth() {
        return depth;
    }

    public List<BlockEntry> blocks() {
        return blocks;
    }

    public List<BlockEntityEntry> blockEntities() {
        return blockEntities;
    }

    public void addBlock(BlockPos relativePos, BlockState state) {
        blocks.add(new BlockEntry(relativePos.immutable(), state));
    }

    public void addBlockEntity(BlockPos relativePos, CompoundTag tag) {
        blockEntities.add(new BlockEntityEntry(relativePos.immutable(), tag.copy()));
    }

    public int blockCount() {
        return blocks.size();
    }

    public int blockEntityCount() {
        return blockEntities.size();
    }

    public record BlockEntry(BlockPos relativePos, BlockState state) {
    }

    public record BlockEntityEntry(BlockPos relativePos, CompoundTag tag) {
    }
}
