package com.yansunsky.createcmpor.preview;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * 工厂方块的「微缩预览快照」——把房间产线压成 <b>调色板 + 字节网格</b>（外加可选的连通组表）。
 *
 * <p>结构：
 * <ul>
 *     <li>{@code palette}：调色板，<b>索引 0 恒为空气</b>，其余按首次出现顺序追加；
 *         以 {@link BlockStateParser#serialize} 的紧凑字符串存（如 {@code create:item_vault[axis=x,large=true]}），
 *         而不是 {@code NbtUtils.writeBlockState} 的嵌套 compound——后者在只有 8 种方块时会占掉约 4KB，
 *         是整包体积的真正大头（实测 270 格网格只占 270 字节）。</li>
 *     <li>{@code cells}：长度 = {@code width*height*depth} 的字节数组，值为无符号调色板索引（0 = 空气）。</li>
 *     <li>{@code groups}（可选）：与 cells 等长的字节数组，<b>多方块连通组号</b>（0 = 无/单方块）。
 *         同一组号的方块在客户端会被赋予同一个"控制器坐标"，让 Create 的
 *         {@code ConnectivityHandler.isConnected} 判定为连通，从而正确渲染连接纹理（CTM）。</li>
 * </ul>
 *
 * <p>本类只做数据与 NBT 编解码，<b>不依赖任何客户端类</b>。
 */
public final class PreviewSnapshot {

    /**
     * 格式版本。字段语义变更时递增，加载侧不认的版本直接丢弃
     * （预览属于装饰，宁可没有也不要读出脏数据）。v2 = 调色板紧凑字符串 + 连通组表。
     */
    public static final int FORMAT_VERSION = 2;

    /** 调色板上限：索引 0 占一个，其余用无符号 byte 表达，故最多 255 种非空气方块。 */
    public static final int MAX_PALETTE = 255;

    /** 连通组上限（组号用无符号 byte 表达，0 保留给"无组"）。 */
    public static final int MAX_GROUPS = 255;

    private final int width;
    private final int height;
    private final int depth;
    private final List<BlockState> palette;
    private final byte[] cells;
    private final byte[] groups;

    public PreviewSnapshot(int width, int height, int depth, List<BlockState> palette, byte[] cells) {
        this(width, height, depth, palette, cells, null);
    }

    public PreviewSnapshot(int width, int height, int depth, List<BlockState> palette, byte[] cells, byte[] groups) {
        if (width <= 0 || height <= 0 || depth <= 0) {
            throw new IllegalArgumentException("预览网格尺寸非法：" + width + "x" + height + "x" + depth);
        }
        if (cells.length != width * height * depth) {
            throw new IllegalArgumentException("预览网格长度不匹配：期望 " + (width * height * depth)
                    + "，实际 " + cells.length);
        }
        if (groups != null && groups.length != cells.length) {
            throw new IllegalArgumentException("连通组表长度必须与网格一致");
        }
        if (palette.isEmpty() || !palette.getFirst().isAir()) {
            throw new IllegalArgumentException("调色板索引 0 必须是空气");
        }
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.palette = List.copyOf(palette);
        this.cells = cells;
        this.groups = groups;
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

    public boolean hasGroups() {
        return groups != null;
    }

    /** 线性下标的连通组号（0 = 无组/单方块）。 */
    public int groupAt(int linearIndex) {
        if (groups == null || linearIndex < 0 || linearIndex >= groups.length) {
            return 0;
        }
        return groups[linearIndex] & 0xFF;
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
            paletteTag.add(StringTag.valueOf(BlockStateParser.serialize(palette.get(i))));
        }
        tag.put("palette", paletteTag);
        tag.putByteArray("cells", cells);
        if (groups != null) {
            tag.putByteArray("groups", groups);
        }
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
        byte[] groups = tag.contains("groups", Tag.TAG_BYTE_ARRAY) ? tag.getByteArray("groups") : null;
        if (groups != null && groups.length != cells.length) {
            groups = null;
        }

        List<BlockState> palette = new ArrayList<>();
        palette.add(Blocks.AIR.defaultBlockState());
        ListTag paletteTag = tag.getList("palette", Tag.TAG_STRING);
        for (int i = 0; i < paletteTag.size(); i++) {
            if (palette.size() > MAX_PALETTE) {
                return null;
            }
            try {
                palette.add(BlockStateParser
                        .parseForBlock(net.minecraft.core.registries.BuiltInRegistries.BLOCK.asLookup(),
                                paletteTag.getString(i), false)
                        .blockState());
            } catch (CommandSyntaxException | RuntimeException error) {
                return null;
            }
        }
        try {
            return new PreviewSnapshot(width, height, depth, palette, cells, groups);
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    /** 供调试：把连通组号换算成稳定的伪控制器坐标（客户端注入代理 BE 用，只用于相等比较）。 */
    public static BlockPos syntheticController(int group) {
        return new BlockPos(group, 0, 0);
    }
}
