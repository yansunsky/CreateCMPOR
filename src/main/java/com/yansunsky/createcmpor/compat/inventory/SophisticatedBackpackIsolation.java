package com.yansunsky.createcmpor.compat.inventory;

import com.yansunsky.createcmpor.CreateCMPOR;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.neoforged.fml.ModList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 精妙背包（Sophisticated Backpacks / Sophisticated Storage）的<b>副本存储隔离</b>。
 *
 * <h2>要解决的问题</h2>
 * 背包物品只携带 {@code sophisticatedcore:storage_uuid} 组件，真实内容存在
 * <b>主世界全局 SavedData</b> {@code BackpackStorage.backpackContents}（UUID → CompoundTag）。
 * 该存储不按维度隔离：{@code BackpackStorage.get()} 永远返回同一个 Map。
 *
 * <p>因此把房间 NBT 原样克隆到 {@code eval_world} 时，副本背包与源房间背包
 * <b>共用同一个 UUID</b> → 共用同一份物理内容。评估期副本侧的任何写入
 * （机器产物送入背包、磁铁升级吸取掉落物等）都会直接落到源房间的背包里，
 * 表现为"评估前明明是空的，还原回去却凭空多出产物"。三扫描完全看不见这种跨世界串写。</p>
 *
 * <h2>隔离方案（新 UUID + 内容深拷贝 + 清理）</h2>
 * <ol>
 *   <li><b>重写</b>：克隆时把副本 NBT 中所有 {@code sophisticatedcore:storage_uuid}
 *       换成新 UUID（同一旧 UUID 在一次评估内只映射一次，重复引用保持一致）。</li>
 *   <li><b>深拷贝</b>：把源 UUID 的内容复制到新 UUID。<b>只换 UUID 不拷贝会让副本背包变空</b>，
 *       评估会失真，因此两者必须成对。<b>嵌套背包</b>在内容 NBT 内递归处理。</li>
 *   <li><b>清理</b>：评估结束（或取消）后删除新 UUID 的内容，避免全局 SavedData 堆积孤儿条目。</li>
 * </ol>
 *
 * <p><b>线程约束</b>：{@code BackpackStorage} 是 SavedData，只能在服务端主线程访问
 * （{@code BackpackStorage.get()} 在线程组不匹配时会返回 dummy 的 clientStorageCopy）。
 * 因此本类的 {@link #copyContents} / {@link #deleteContents} <b>必须在服务端线程调用</b>；
 * 只有纯 NBT 的 {@link #rewriteUuids} 允许在异步 IO 线程执行。</p>
 */
public final class SophisticatedBackpackIsolation {
    /** 组件在物品 NBT 中的键名（1.20.5+ 组件子键使用完整注册名）。 */
    public static final String STORAGE_UUID_KEY = "sophisticatedcore:storage_uuid";

    private static final String STORAGE_CLASS =
            "net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackStorage";
    private static final String STORAGE_CONTENTS_FIELD = "backpackContents";

    /** 数据组件是 UUID，编码为 4 个 int 的 IntArrayTag。 */
    private static final int UUID_INT_COUNT = 4;
    /** NBT 递归深度上限（防恶意深嵌套）。 */
    private static final int MAX_NBT_DEPTH = 64;
    /** 嵌套背包递归上限（与 ContainerItemExpander 的展开深度一致）。 */
    private static final int MAX_NESTING = 8;

    private SophisticatedBackpackIsolation() {
    }

    /** 精妙背包是否已加载（未加载时本类所有方法均为安全空操作）。 */
    public static boolean isAvailable() {
        return ModList.get().isLoaded("sophisticatedbackpacks");
    }

    // ------------------------------------------------------------------
    // 1) 纯 NBT：重写副本中的 storage_uuid
    // ------------------------------------------------------------------

    /**
     * 把 tag 树中所有 {@code sophisticatedcore:storage_uuid} 组件替换为新 UUID。
     *
     * <p>纯 NBT 操作，无全局状态访问，可在异步 IO 线程调用。
     * 同一旧 UUID 只分配一次新 UUID，保证重复引用（同一背包物品出现在多处）映射一致。</p>
     *
     * @param tag     待改写的 NBT（区块 tag / 实体 tag / 背包内容 tag 均可）
     * @param mapping 旧 UUID → 新 UUID 的映射（会被写入，跨调用累积）
     * @return 本次改写命中的旧 UUID 集合（供嵌套背包递归使用）
     */
    public static Set<UUID> rewriteUuids(Tag tag, Map<UUID, UUID> mapping) {
        Set<UUID> discovered = new LinkedHashSet<>();
        rewrite(tag, mapping, discovered, 0);
        return discovered;
    }

    private static void rewrite(Tag tag, Map<UUID, UUID> mapping,
                                Set<UUID> discovered, int depth) {
        if (tag == null || depth > MAX_NBT_DEPTH) {
            return;
        }
        if (tag instanceof CompoundTag compound) {
            Tag value = compound.get(STORAGE_UUID_KEY);
            if (value instanceof IntArrayTag array && array.size() == UUID_INT_COUNT) {
                UUID original = loadUuidOrNull(array);
                if (original != null) {
                    UUID replacement = mapping.computeIfAbsent(original, ignored -> UUID.randomUUID());
                    compound.put(STORAGE_UUID_KEY, NbtUtils.createUUID(replacement));
                    discovered.add(original);
                }
            }
            // 复制键列表：replace 不改动键集合，但避免依赖实时视图的实现细节
            for (String key : List.copyOf(compound.getAllKeys())) {
                rewrite(compound.get(key), mapping, discovered, depth + 1);
            }
        } else if (tag instanceof ListTag list) {
            for (int index = 0; index < list.size(); index++) {
                rewrite(list.get(index), mapping, discovered, depth + 1);
            }
        }
    }

    private static UUID loadUuidOrNull(IntArrayTag tag) {
        try {
            return NbtUtils.loadUUID(tag);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 2) 服务端线程：内容深拷贝
    // ------------------------------------------------------------------

    /**
     * 为映射中的每个源 UUID 复制内容到新 UUID（服务端线程）。
     *
     * <p>递归处理<b>嵌套背包</b>：背包内容里若还放着别的背包，其 {@code storage_uuid}
     * 同样会被换成新 UUID 并复制内容——否则嵌套背包仍与源房间共享存储。
     * 新发现的嵌套 UUID 会补进 {@code mapping}，清理时一并删除。</p>
     *
     * @return true = 成功（或无需处理）；false = 反射不可用，调用方应<b>失败关闭</b>（取消评估）
     */
    public static boolean copyContents(Map<UUID, UUID> mapping) {
        if (mapping.isEmpty() || !isAvailable()) {
            return true;
        }
        StorageAccess access = StorageAccess.open();
        if (access == null) {
            return false;
        }
        // 顶层遍历 mapping 的快照：递归过程中 mapping 会被写入（发现嵌套背包）
        for (UUID source : new ArrayList<>(mapping.keySet())) {
            copyOne(source, mapping, access, new HashSet<>(), 0);
        }
        access.markDirty();
        return true;
    }

    private static void copyOne(UUID source, Map<UUID, UUID> mapping,
                                StorageAccess access, Set<UUID> visited, int depth) {
        if (depth > MAX_NESTING || !visited.add(source)) {
            return;
        }
        UUID copy = mapping.computeIfAbsent(source, ignored -> UUID.randomUUID());
        CompoundTag content = access.get(source);
        if (content == null) {
            // 源为空：不创建条目（SBP 的 getOrCreate 会凭空建空条目污染存储）。
            // 副本侧后续写入时由 SBP 自行 getOrCreate，语义与"空背包"一致。
            return;
        }
        CompoundTag copied = content.copy();
        Set<UUID> nested = rewriteUuids(copied, mapping);
        access.set(copy, copied);
        for (UUID child : nested) {
            copyOne(child, mapping, access, visited, depth + 1);
        }
    }

    // ------------------------------------------------------------------
    // 3) 服务端线程：清理副本 UUID
    // ------------------------------------------------------------------

    /**
     * 删除副本侧 UUID 的内容（服务端线程）。幂等：已不存在的键直接跳过。
     *
     * @return true = 成功（或无需处理）；false = 反射不可用
     */
    public static boolean deleteContents(Collection<UUID> copyUuids) {
        if (copyUuids == null || copyUuids.isEmpty() || !isAvailable()) {
            return true;
        }
        StorageAccess access = StorageAccess.open();
        if (access == null) {
            return false;
        }
        for (UUID uuid : copyUuids) {
            access.remove(uuid);
        }
        access.markDirty();
        return true;
    }

    // ------------------------------------------------------------------
    // 映射持久化（UUID 编码为 int 数组，跨重启稳定）
    // ------------------------------------------------------------------

    /** 反序列化 {@link #serializeMapping} 写出的映射。 */
    public static Map<UUID, UUID> deserializeMapping(ListTag tag) {
        Map<UUID, UUID> mapping = new ConcurrentHashMap<>();
        if (tag == null) {
            return mapping;
        }
        for (int index = 0; index < tag.size(); index++) {
            Tag entry = tag.get(index);
            if (!(entry instanceof IntArrayTag array) || array.size() != UUID_INT_COUNT * 2) {
                continue;
            }
            int[] values = array.getAsIntArray();
            int[] source = new int[UUID_INT_COUNT];
            int[] copy = new int[UUID_INT_COUNT];
            System.arraycopy(values, 0, source, 0, UUID_INT_COUNT);
            System.arraycopy(values, UUID_INT_COUNT, copy, 0, UUID_INT_COUNT);
            mapping.put(NbtUtils.loadUUID(new IntArrayTag(source)),
                    NbtUtils.loadUUID(new IntArrayTag(copy)));
        }
        return mapping;
    }

    /** 序列化映射为 {@code [I; srcX,srcY... copyX,copyY...]} 列表（每条 8 个 int）。 */
    public static ListTag serializeMapping(Map<UUID, UUID> mapping) {
        ListTag tag = new ListTag();
        if (mapping == null) {
            return tag;
        }
        for (Map.Entry<UUID, UUID> entry : mapping.entrySet()) {
            int[] values = new int[UUID_INT_COUNT * 2];
            writeUuid(entry.getKey(), values, 0);
            writeUuid(entry.getValue(), values, UUID_INT_COUNT);
            tag.add(new IntArrayTag(values));
        }
        return tag;
    }

    private static void writeUuid(UUID uuid, int[] target, int offset) {
        int[] raw = NbtUtils.createUUID(uuid).getAsIntArray();
        System.arraycopy(raw, 0, target, offset, UUID_INT_COUNT);
    }

    // ------------------------------------------------------------------
    // 反射访问 BackpackStorage（零编译依赖）
    // ------------------------------------------------------------------

    /**
     * 零编译依赖的 {@code BackpackStorage} 访问器。
     *
     * <p><b>读</b>走私有字段 {@code backpackContents}：SBP 只提供
     * {@code getOrCreateBackpackContents}，对不存在的 UUID 会<b>凭空创建空条目</b>并 setDirty，
     * 只读探测绝不能使用。</p>
     *
     * <p><b>写/删</b>走公开 API {@code setBackpackContents} / {@code removeBackpackContents}：
     * 保留 SBP 自身的簿记（updatedBackpackSettingsFlags 等），比直接改 Map 更安全。</p>
     */
    private static final class StorageAccess {
        private static final Method GET = findMethod("get");
        private static final Method SET_CONTENTS = findMethod("setBackpackContents", UUID.class, CompoundTag.class);
        private static final Method REMOVE_CONTENTS = findMethod("removeBackpackContents", UUID.class);
        private static final Method SET_DIRTY = findMethod("setDirty");
        private static final Field CONTENTS_FIELD = findContentsField();

        private final Object storage;
        private final Map<UUID, CompoundTag> contents;

        private StorageAccess(Object storage, Map<UUID, CompoundTag> contents) {
            this.storage = storage;
            this.contents = contents;
        }

        static StorageAccess open() {
            if (GET == null || CONTENTS_FIELD == null) {
                CreateCMPOR.LOGGER.warn("精妙背包隔离不可用：BackpackStorage 反射失败（类或字段缺失）");
                return null;
            }
            try {
                Object instance = GET.invoke(null);
                if (instance == null) {
                    return null;
                }
                Object raw = CONTENTS_FIELD.get(instance);
                if (!(raw instanceof Map<?, ?> map)) {
                    CreateCMPOR.LOGGER.warn("精妙背包隔离不可用：backpackContents 不是 Map");
                    return null;
                }
                @SuppressWarnings("unchecked")
                Map<UUID, CompoundTag> typed = (Map<UUID, CompoundTag>) map;
                return new StorageAccess(instance, typed);
            } catch (ReflectiveOperationException | RuntimeException exception) {
                CreateCMPOR.LOGGER.warn("精妙背包隔离不可用：BackpackStorage 访问失败（{}）",
                        exception.getClass().getSimpleName());
                return null;
            }
        }

        CompoundTag get(UUID uuid) {
            return contents.get(uuid);
        }

        void set(UUID uuid, CompoundTag tag) {
            if (SET_CONTENTS == null) {
                contents.put(uuid, tag);
                return;
            }
            try {
                SET_CONTENTS.invoke(storage, uuid, tag);
            } catch (ReflectiveOperationException | RuntimeException exception) {
                CreateCMPOR.LOGGER.warn("精妙背包副本内容写入失败（{}），回退为直接写 Map",
                        exception.getClass().getSimpleName());
                contents.put(uuid, tag);
            }
        }

        void remove(UUID uuid) {
            if (REMOVE_CONTENTS == null) {
                contents.remove(uuid);
                return;
            }
            try {
                REMOVE_CONTENTS.invoke(storage, uuid);
            } catch (ReflectiveOperationException | RuntimeException exception) {
                contents.remove(uuid);
            }
        }

        /**
         * 标记脏数据以便落盘。
         * <b>注意</b>：SBP 的 {@code setBackpackContents} 在"新键"分支不会 setDirty，
         * 因此副本内容写完必须显式调用，否则不落盘。
         */
        void markDirty() {
            if (SET_DIRTY == null) {
                return;
            }
            try {
                SET_DIRTY.invoke(storage);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // 落盘标记失败不影响本次评估（内容仍在内存中）
            }
        }

        private static Method findMethod(String name, Class<?>... parameters) {
            try {
                return Class.forName(STORAGE_CLASS).getMethod(name, parameters);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
                return null;
            }
        }

        private static Field findContentsField() {
            try {
                Field field = Class.forName(STORAGE_CLASS).getDeclaredField(STORAGE_CONTENTS_FIELD);
                field.setAccessible(true);
                return field;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
                return null;
            }
        }
    }
}
