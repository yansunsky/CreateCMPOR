package com.yansunsky.createcmpor.evaluation;

import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;

/** 对 Compound key 排序后计算稳定 SHA-256，不依赖 HashMap 遍历顺序。 */
final class CanonicalNbtHasher {
    private CanonicalNbtHasher() {
    }

    /**
     * 第三方模组可能在区块写盘路径给 NBT 附加自定义顶层字段（如
     * Create: Railways 的 {@code Railways_DataVersion}）。这些字段在"源读取"
     * 时不存在、在"staging 写入"时被追加，导致摘要校验收到的 NBT 与源不同。
     * 计算摘要时忽略这些附加字段，保证源与 staging 摘要一致。
     */
    private static final Set<String> IGNORED_TOP_LEVEL_KEYS = Set.of(
            "Railways_DataVersion");

    static String sha256(Tag tag) {
        return sha256(tag, IGNORED_TOP_LEVEL_KEYS);
    }

    static String sha256(Tag tag, Set<String> ignoredTopLevelKeys) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                write(tag, output, ignoredTopLevelKeys);
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("无法计算 NBT 摘要", exception);
        }
    }

    private static void write(Tag tag, DataOutputStream output, Set<String> ignoredTopLevelKeys)
            throws IOException {
        write(tag, output, ignoredTopLevelKeys, true);
    }

    private static void write(Tag tag, DataOutputStream output, Set<String> ignoredTopLevelKeys,
                              boolean topLevel) throws IOException {
        output.writeByte(tag.getId());
        switch (tag.getId()) {
            case Tag.TAG_END -> {
            }
            case Tag.TAG_BYTE -> output.writeByte(((NumericTag) tag).getAsByte());
            case Tag.TAG_SHORT -> output.writeShort(((NumericTag) tag).getAsShort());
            case Tag.TAG_INT -> output.writeInt(((NumericTag) tag).getAsInt());
            case Tag.TAG_LONG -> output.writeLong(((NumericTag) tag).getAsLong());
            case Tag.TAG_FLOAT -> output.writeInt(Float.floatToRawIntBits(((NumericTag) tag).getAsFloat()));
            case Tag.TAG_DOUBLE -> output.writeLong(Double.doubleToRawLongBits(((NumericTag) tag).getAsDouble()));
            case Tag.TAG_BYTE_ARRAY -> writeBytes(((ByteArrayTag) tag).getAsByteArray(), output);
            case Tag.TAG_STRING -> writeString(((StringTag) tag).getAsString(), output);
            case Tag.TAG_LIST -> writeList((ListTag) tag, output, ignoredTopLevelKeys, false);
            case Tag.TAG_COMPOUND -> writeCompound((CompoundTag) tag, output, ignoredTopLevelKeys, topLevel);
            case Tag.TAG_INT_ARRAY -> {
                int[] values = ((IntArrayTag) tag).getAsIntArray();
                output.writeInt(values.length);
                for (int value : values) {
                    output.writeInt(value);
                }
            }
            case Tag.TAG_LONG_ARRAY -> {
                long[] values = ((LongArrayTag) tag).getAsLongArray();
                output.writeInt(values.length);
                for (long value : values) {
                    output.writeLong(value);
                }
            }
            default -> throw new IOException("未知 NBT 类型：" + tag.getId());
        }
    }

    private static void writeBytes(byte[] values, DataOutputStream output) throws IOException {
        output.writeInt(values.length);
        output.write(values);
    }

    private static void writeString(String value, DataOutputStream output) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static void writeList(ListTag list, DataOutputStream output, Set<String> ignoredTopLevelKeys,
                                  boolean topLevel) throws IOException {
        output.writeInt(list.size());
        for (int index = 0; index < list.size(); index++) {
            write(list.get(index), output, ignoredTopLevelKeys, topLevel);
        }
    }

    private static void writeCompound(CompoundTag compound, DataOutputStream output,
                                      Set<String> ignoredTopLevelKeys, boolean topLevel) throws IOException {
        var keys = compound.getAllKeys().stream().sorted().toList();
        output.writeInt(keys.size());
        for (String key : keys) {
            if (topLevel && ignoredTopLevelKeys.contains(key)) {
                continue;
            }
            writeString(key, output);
            write(compound.get(key), output, ignoredTopLevelKeys, false);
        }
    }
}