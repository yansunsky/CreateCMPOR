package com.yansunsky.createcmpor.preview;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 工厂方块的「微缩预览快照」——把房间产线压成 <b>调色板 + 字节网格</b>（外加可选的连通组表，
 * 以及 v3 起的稀疏转速表）。
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
 *     <li>{@code speeds}（v3 可选）：<b>稀疏转速表</b>——只记录"确实在转"的格（{@code |speed| > 1e-4}），
 *         编码成单条字节数组（varint 的<b>格索引增量</b> + 4 字节 big-endian float32），按格索引升序。
 *         表里"有没有这一格"本身就是"这格要走动态 pass"的天然标志位，同时避免稠密 float[] 的 4 倍膨胀
 *         （8192 格稠密表最坏 +32KB；稀疏表典型 50~300 格 → 约 0.3~2.5KB）。</li>
 *     <li>{@code animSeconds}（v3 可选）：采集侧配置的动画循环时长（秒）。客户端据此算整表缩放系数，
 *         <b>因此客户端完全不读配置</b>——服务器与客户端配置不一致也不会出错（行为由数据决定）。
 *         缺失或 &le;0 时客户端一律走静态路径。</li>
 * </ul>
 *
 * <p>本类只做数据与 NBT 编解码，<b>不依赖任何客户端类</b>。
 */
public final class PreviewSnapshot {

    /**
     * 当前格式版本。字段语义变更时递增。v2 = 调色板紧凑字符串 + 连通组表；
     * v3 = 在 v2 之上追加稀疏转速表（{@code speeds} + {@code animSeconds}）；
     * v4 = 在 v3 之上追加实体表（{@code entities}，纯增量：没有该键 = 没有实体）。
     */
    public static final int FORMAT_VERSION = 4;

    /** 可加载的最低版本：v2 视为"全静态"（无转速表），再早的版本结构不同，直接丢弃。 */
    public static final int MIN_SUPPORTED_VERSION = 2;

    /** 调色板上限：索引 0 占一个，其余用无符号 byte 表达，故最多 255 种非空气方块。 */
    public static final int MAX_PALETTE = 255;

    /** 连通组上限（组号用无符号 byte 表达，0 保留给"无组"）。 */
    public static final int MAX_GROUPS = 255;

    /** 低于该绝对值的转速视为静止（Create 的转速是 float，1e-4 远小于任何真实转速）。 */
    public static final float SPEED_EPSILON = 1.0E-4F;

    private final int width;
    private final int height;
    private final int depth;
    private final List<BlockState> palette;
    private final byte[] cells;
    private final byte[] groups;

    /** 稀疏转速表：与 {@link #speedValues} 等长、按格索引严格升序；无转速时为长度 0 的数组，永不为 null。 */
    private final int[] speedIndices;
    private final float[] speedValues;

    /** 动画循环时长（秒）。0 = 静态（客户端不渲染动态 pass）。 */
    private final float animationSeconds;

    /** 全表最大 |转速|（构造时算一次；0 表示没有可动格）。 */
    private final float maxAbsSpeed;

    /** v4 实体表：非空气包围盒内、除玩家与装置以外的实体（可能为空，永不为 null）。 */
    private final List<EntityRecord> entities;

    /**
     * 一条待重建的实体（v4）。
     *
     * <p><b>坐标是"快照局部格坐标"</b>（相对取景包围盒最小角，已按降采样步长折算），
     * 与 {@code cells} 同一坐标系——客户端直接用它在同一缩放变换下摆位，不需要世界坐标。
     *
     * @param type  实体类型注册名（如 {@code minecraft:cow}）
     * @param data  <b>裁剪后的存档 NBT</b>（外观必需标签：展示框的 Item/Facing、羊的 Color、村民 VillagerData…）。
     *              刻意不含 {@code Motion}/{@code UUID}/{@code Attributes}/{@code Brain}/背包等渲染无关的大块数据；
     *              客户端用 {@code EntityType.byString(type) + create(world) + load(data)} 重建。
     */
    public record EntityRecord(String type, float x, float y, float z, float yaw, float pitch, CompoundTag data) {
        public EntityRecord {
            data = data == null ? new CompoundTag() : data.copy();
        }
    }

    public PreviewSnapshot(int width, int height, int depth, List<BlockState> palette, byte[] cells) {
        this(width, height, depth, palette, cells, null, null, null, 0.0F);
    }

    public PreviewSnapshot(int width, int height, int depth, List<BlockState> palette, byte[] cells, byte[] groups) {
        this(width, height, depth, palette, cells, groups, null, null, 0.0F);
    }

    /**
     * @param speedIndices     稀疏转速表的格索引（严格升序）；{@code null} 等价于空表
     * @param speedValues      与 {@code speedIndices} 一一对应的转速（RPM，可为负）
     * @param animationSeconds 动画循环时长（秒）；0 = 静态
     */
    public PreviewSnapshot(int width, int height, int depth, List<BlockState> palette, byte[] cells, byte[] groups,
                           int[] speedIndices, float[] speedValues, float animationSeconds) {
        this(width, height, depth, palette, cells, groups, speedIndices, speedValues, animationSeconds, null);
    }

    /**
     * @param entities v4 实体表；{@code null} 等价于空表（v2/v3 快照就是这一支）
     */
    public PreviewSnapshot(int width, int height, int depth, List<BlockState> palette, byte[] cells, byte[] groups,
                           int[] speedIndices, float[] speedValues, float animationSeconds,
                           List<EntityRecord> entities) {
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
        int[] indices = speedIndices == null ? new int[0] : speedIndices;
        float[] values = speedValues == null ? new float[0] : speedValues;
        if (indices.length != values.length) {
            throw new IllegalArgumentException("转速表的索引与取值长度不一致");
        }
        for (int i = 0; i < indices.length; i++) {
            if (indices[i] < 0 || indices[i] >= cells.length) {
                throw new IllegalArgumentException("转速表格索引越界：" + indices[i]);
            }
            if (i > 0 && indices[i] <= indices[i - 1]) {
                throw new IllegalArgumentException("转速表格索引必须严格升序");
            }
        }
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.palette = List.copyOf(palette);
        this.cells = cells;
        this.groups = groups;
        this.speedIndices = indices;
        this.speedValues = values;
        this.animationSeconds = Math.max(0.0F, animationSeconds);
        float max = 0.0F;
        for (float value : values) {
            float abs = Math.abs(value);
            if (abs > max) {
                max = abs;
            }
        }
        this.maxAbsSpeed = max;
        this.entities = entities == null ? List.of() : List.copyOf(entities);
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

    // ------------------------------------------------------------------
    // v3：稀疏转速表
    // ------------------------------------------------------------------

    /** 是否有可动格（即是否有资格走动态 pass）。 */
    public boolean hasSpeeds() {
        return speedIndices.length > 0;
    }

    /** 可动格数量。 */
    public int movingCount() {
        return speedIndices.length;
    }

    /** 动画循环时长（秒）；0 = 静态。v2 快照（无该字段）同样返回 0。 */
    public float animationSeconds() {
        return animationSeconds;
    }

    /** 全表最大 |转速|（RPM）；0 表示没有可动格。 */
    public float maxAbsSpeed() {
        return maxAbsSpeed;
    }

    /** 第 {@code i} 个可动格的线性格索引（要求 {@code 0 <= i < movingCount()}）。 */
    public int movingIndexAt(int i) {
        return speedIndices[i];
    }

    /** 第 {@code i} 个可动格的转速（RPM，带符号）。 */
    public float movingSpeedAt(int i) {
        return speedValues[i];
    }

    /** 按线性下标取转速；静止格返回 0（表里没有这一格就是静止）。 */
    public float speedAt(int linearIndex) {
        int low = 0;
        int high = speedIndices.length - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int value = speedIndices[mid];
            if (value < linearIndex) {
                low = mid + 1;
            } else if (value > linearIndex) {
                high = mid - 1;
            } else {
                return speedValues[mid];
            }
        }
        return 0.0F;
    }

    /** 该格是否"在转"（即是否被采进转速表）。 */
    public boolean isMoving(int linearIndex) {
        return speedAt(linearIndex) != 0.0F;
    }

    /** 编解码后的 NBT 体积（字节），用于调试与上限校验。 */
    public int encodedSize() {
        return save().sizeInBytes();
    }

    /** v4 实体表（永不为 null）。 */
    public List<EntityRecord> entities() {
        return entities;
    }

    public int entityCount() {
        return entities.size();
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
        if (speedIndices.length > 0 && animationSeconds > 0.0F) {
            tag.putByteArray("speeds", encodeSpeeds(speedIndices, speedValues));
            tag.putFloat("animSeconds", animationSeconds);
        }
        if (!entities.isEmpty()) {
            tag.put("entities", saveEntities(entities));
        }
        return tag;
    }

    /**
     * 实体表编码：每条一个复合标签。
     *
     * <p>刻意用 {@code ListTag<CompoundTag>} 而不是自定义紧凑字节流：实体表最多几十条、
     * 每条自带裁剪 NBT，包装开销（~20 B/条）相对数据本身可忽略，而换来的是
     * "逐条独立容错 + 可用 {@code /data get} 直接看"的可排查性。
     */
    private static ListTag saveEntities(List<EntityRecord> entities) {
        ListTag list = new ListTag();
        for (EntityRecord record : entities) {
            CompoundTag entry = new CompoundTag();
            entry.putString("id", record.type());
            entry.putFloat("x", record.x());
            entry.putFloat("y", record.y());
            entry.putFloat("z", record.z());
            entry.putFloat("yaw", record.yaw());
            entry.putFloat("pitch", record.pitch());
            if (!record.data().isEmpty()) {
                entry.put("data", record.data());
            }
            list.add(entry);
        }
        return list;
    }

    /**
     * 实体表解码：<b>逐条独立容错</b>——某一条非法只丢这一条，绝不影响方块网格与其余实体
     * （与转速表"坏了只丢转速"同一口径）。
     */
    private static List<EntityRecord> loadEntities(CompoundTag tag) {
        ListTag list = tag.getList("entities", Tag.TAG_COMPOUND);
        if (list.isEmpty()) {
            return List.of();
        }
        List<EntityRecord> records = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            try {
                CompoundTag entry = list.getCompound(i);
                String id = entry.getString("id");
                if (id.isBlank() || ResourceLocation.tryParse(id) == null) {
                    continue;
                }
                records.add(new EntityRecord(id, entry.getFloat("x"), entry.getFloat("y"), entry.getFloat("z"),
                        entry.getFloat("yaw"), entry.getFloat("pitch"),
                        entry.contains("data", Tag.TAG_COMPOUND) ? entry.getCompound("data") : new CompoundTag()));
            } catch (RuntimeException error) {
                // 单条损坏只丢单条
            }
        }
        return records;
    }

    /**
     * 从 NBT 还原；任何异常/版本不符/结构非法都返回 {@code null}。
     *
     * <p><b>兼容策略</b>：接受 v{@value #MIN_SUPPORTED_VERSION}（视为全静态）与 v{@value #FORMAT_VERSION}，
     * 其余版本（含更高版本）一律丢弃——新字段是纯增量的，旧档没必要让预览消失。
     *
     * <p>预览是纯装饰数据，解析失败必须静默降级为"没有预览"，绝不能影响工厂方块的正常加载。
     * 转速表单独解析、单独容错：表坏了只丢转速（退化为静态），不影响方块网格本身。
     */
    public static PreviewSnapshot load(CompoundTag tag) {
        if (!tag.contains("w", Tag.TAG_INT) || !tag.contains("h", Tag.TAG_INT)
                || !tag.contains("d", Tag.TAG_INT) || !tag.contains("cells", Tag.TAG_BYTE_ARRAY)) {
            return null;
        }
        int version = tag.getInt("version");
        if (version < MIN_SUPPORTED_VERSION || version > FORMAT_VERSION) {
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

        int[] speedIndices = null;
        float[] speedValues = null;
        float animationSeconds = 0.0F;
        if (version >= 3 && tag.contains("speeds", Tag.TAG_BYTE_ARRAY)) {
            SparseTable table = decodeSpeeds(tag.getByteArray("speeds"), cells.length);
            if (table != null) {
                if (tag.contains("animSeconds", Tag.TAG_FLOAT)) {
                    animationSeconds = tag.getFloat("animSeconds");
                }
                if (animationSeconds > 0.0F) {
                    speedIndices = table.indices();
                    speedValues = table.values();
                }
                // 没有循环时长（或时长非法）的转速表没有意义：客户端无从算整表缩放系数，退化为静态。
            }
        }
        try {
            return new PreviewSnapshot(width, height, depth, palette, cells, groups,
                    speedIndices, speedValues, animationSeconds,
                    // v4 实体段：v2/v3 快照没有这个键 → 空表（"没有实体"是合法状态，不是损坏）
                    version >= 4 ? loadEntities(tag) : List.of());
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    /** 稀疏转速表的解码结果。 */
    private record SparseTable(int[] indices, float[] values) {
    }

    /**
     * 稀疏转速表编码：按格索引升序写 (varint 增量, 4 字节 big-endian float32)。
     *
     * <p>增量编码让 varint 绝大多数情况只占 1 字节（相邻可动格通常挨得很近），
     * 于是每格成本约 5 字节——远小于稠密 float[]（4 字节/格，且静止格也要占位）。
     */
    private static byte[] encodeSpeeds(int[] indices, float[] values) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(indices.length * 5);
        int previous = -1;
        for (int i = 0; i < indices.length; i++) {
            writeVarInt(out, indices[i] - previous);
            previous = indices[i];
            int bits = Float.floatToIntBits(values[i]);
            out.write((bits >>> 24) & 0xFF);
            out.write((bits >>> 16) & 0xFF);
            out.write((bits >>> 8) & 0xFF);
            out.write(bits & 0xFF);
        }
        return out.toByteArray();
    }

    /**
     * 稀疏转速表解码。<b>任何结构异常都返回 {@code null}</b>（只丢转速，不影响方块网格）。
     *
     * <p>校验：增量必须 &ge;1（保证索引严格升序、无重复）、索引必须落在网格内、
     * varint 最多 5 字节、float 必须是有限非零值。
     */
    private static SparseTable decodeSpeeds(byte[] data, int volume) {
        if (data.length == 0) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(data);
        int[] indices = new int[Math.min(volume, 64)];
        float[] values = new float[indices.length];
        int count = 0;
        int previous = -1;
        while (buffer.hasRemaining()) {
            int delta = 0;
            int shift = 0;
            int read;
            do {
                if (!buffer.hasRemaining() || shift > 28) {
                    return null;
                }
                read = buffer.get() & 0xFF;
                delta |= (read & 0x7F) << shift;
                shift += 7;
            } while ((read & 0x80) != 0);
            if (delta <= 0) {
                return null;
            }
            int index = previous + delta;
            if (index >= volume || buffer.remaining() < 4) {
                return null;
            }
            float value = buffer.getFloat();
            if (!Float.isFinite(value) || Math.abs(value) <= SPEED_EPSILON) {
                return null;
            }
            if (count == indices.length) {
                if (count >= volume) {
                    return null;
                }
                int grown = Math.min(volume, Math.max(count + 1, count * 2));
                indices = Arrays.copyOf(indices, grown);
                values = Arrays.copyOf(values, grown);
            }
            indices[count] = index;
            values[count] = value;
            count++;
            previous = index;
        }
        if (count == 0) {
            return null;
        }
        return new SparseTable(Arrays.copyOf(indices, count), Arrays.copyOf(values, count));
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        int remaining = value;
        while ((remaining & ~0x7F) != 0) {
            out.write((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        out.write(remaining);
    }

    /**
     * 连通组号 → 稳定的"伪控制器坐标"，供客户端给代理 BE 注入，使
     * {@code ConnectivityHandler.isConnected} 的 {@code one.getController().equals(two.getController())}
     * 判为连通。
     *
     * <p>取值**刻意远离快照坐标空间**（0..w/h/d）：否则可能和"无组"空壳 BE 回退出的自身坐标撞车，
     * 把本该判为不连通的两格误判成连通。
     */
    public static BlockPos syntheticController(int group) {
        return new BlockPos(-65536 - group, -65536, -65536);
    }
}
