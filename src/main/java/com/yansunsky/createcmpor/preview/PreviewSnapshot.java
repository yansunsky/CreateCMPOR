package com.yansunsky.createcmpor.preview;

import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * 工厂方块的「微缩预览快照」——把房间产线压成 <b>调色板 + 字节网格</b>。
 *
 * <p>设计取向与 Create 的 Schematic 不同：这里只要"看得见形状"，不要方块实体、不要 NBT 明细，
 * 因此采用最省事也最紧凑的定长网格：
 * <ul>
 *     <li>{@code palette}：调色板，<b>索引 0 恒为空气</b>，其余按首次出现顺序追加；</li>
 *     <li>{@code cells}：长度 = {@code width*height*depth} 的字节数组，
 *         下标 {@code x + width * (y + height * z)}，值为无符号调色板索引（0 = 空气）。</li>
 * </ul>
 *
 * <p>体积量级：7³ 房间 = 343 字节网格 + 调色板（通常 1~2 KB 文本，压缩后更小）；
 * 13³ = 2197 字节。采集侧 {@link PreviewCapture} 负责按体积上限降采样与裁剪。
 *
 * <p>本类只做数据与 NBT 编解码，<b>不依赖任何客户端类</b>，服务端采集与客户端渲染共用。
 */
public final class PreviewSnapshot {

    /** 格式版本。字段语义变更时递增，加载侧不认的版本直接丢弃（预览属于装饰，宁可没有也不要读出脏数据）。 */
    public static final int FORMAT_VERSION = 1;

    /** 调色板上限：索引 0 占一个，其余用无符号 byte 表达，故最多 255 种非空气方块。 */
    public static final int MAX_PALETTE = 255;

    private final int width;
    private final int height;
    private final int depth;
    private final List<BlockState> palette;
    private final byte[] cells;

    public PreviewSnapshot(int width, int height, int depth, List<BlockState> palette, byte[] cells) {
        if (width <= 0 || height <= 0 || depth <= 0) {
            throw new IllegalArgumentException("预览网格尺寸非法：" + width + "x" + height + "x" + depth);
        }
        if (cells.length != width * height * depth) {
            throw new IllegalArgumentException("预览网格长度不匹配：期望 " + (width * height * depth)
                    + "，实际 " + cells.length);
        }
        if (palette.isEmpty() || !palette.getFirst().isAir()) {
            throw new IllegalArgumentException("调色板索引 0 必须是空气");
        }
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.palette = List.copyOf(palette);
        this.cells = cells;
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

    /** 网格总格数（含空气）。 */
    public int volume() {
        return cells.length;
    }

    /** 非空气格数。 */
    public int nonAirCount() {
        int count = 0;
        for (byte cell : cells) {
            if (cell != 0) {
                count++;
            }
        }
        return count;
    }

    /** 调色板条目数（含索引 0 的空气）。 */
    public int paletteSize() {
        return palette.size();
    }

    public List<BlockState> palette() {
        return palette;
    }

    /** 最长边；渲染侧用它推导缩放。 */
    public int maxDimension() {
        return Math.max(width, Math.max(height, depth));
    }

    /** 按网格坐标取调色板索引（0 = 空气）。越界返回 0。 */
    public int indexAt(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= width || y >= height || z >= depth) {
            return 0;
        }
        return cells[cellIndex(x, y, z)] & 0xFF;
    }

    /** 按线性下标取调色板索引（0 = 空气）。越界返回 0。 */
    public int indexAt(int linearIndex) {
        if (linearIndex < 0 || linearIndex >= cells.length) {
            return 0;
        }
        return cells[linearIndex] & 0xFF;
    }

    /** 按线性下标取方块状态；空气返回 {@code null}（渲染侧可直接跳过）。 */
    public BlockState stateAt(int linearIndex) {
        int index = indexAt(linearIndex);
        return index == 0 ? null : palette.get(index);
    }

    public int cellIndex(int x, int y, int z) {
        return x + width * (y + height * z);
    }

    /** 编解码后的 NBT 体积（字节），用于调试与上限校验。 */
    public int encodedSize() {
        return save().sizeInBytes();
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("version", FORMAT_VERSION);
        tag.putInt("w", width);
        tag.putInt("h", height);
        tag.putInt("d", depth);
        ListTag paletteTag = new ListTag();
        for (int i = 1; i < palette.size(); i++) {
            paletteTag.add(NbtUtils.writeBlockState(palette.get(i)));
        }
        tag.put("palette", paletteTag);
        tag.putByteArray("cells", cells);
        return tag;
    }

    /**
     * 从 NBT 还原；任何异常/版本不符/结构非法都返回 {@code null}。
     *
     * <p>预览是纯装饰数据，解析失败必须静默降级为"没有预览"，绝不能影响工厂方块的正常加载。
     */
    public static PreviewSnapshot load(CompoundTag tag) {
        if (!tag.contains("w", Tag.TAG_INT) || !tag.contains("h", Tag.TAG_INT)
                || !tag.contains("d", Tag.TAG_INT) || !tag.contains("cells", Tag.TAG_BYTE_ARRAY)) {
            return null;
        }
        if (tag.getInt("version") != FORMAT_VERSION) {
            return null;
        }
        int width = tag.getInt("w");
        int height = tag.getInt("h");
        int depth = tag.getInt("d");
        byte[] cells = tag.getByteArray("cells");
        if (width <= 0 || height <= 0 || depth <= 0 || cells.length != width * height * depth) {
            return null;
        }
        List<BlockState> palette = new ArrayList<>();
        palette.add(Blocks.AIR.defaultBlockState());
        HolderGetter<Block> lookup = BuiltInRegistries.BLOCK.asLookup();
        ListTag paletteTag = tag.getList("palette", Tag.TAG_COMPOUND);
        for (int i = 0; i < paletteTag.size(); i++) {
            if (palette.size() > MAX_PALETTE) {
                return null;
            }
            palette.add(NbtUtils.readBlockState(lookup, paletteTag.getCompound(i)));
        }
        try {
            return new PreviewSnapshot(width, height, depth, palette, cells);
        } catch (IllegalArgumentException error) {
            return null;
        }
    }
}
