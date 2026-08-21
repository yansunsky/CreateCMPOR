package com.yansunsky.createcmpor.compat.inventory;

import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AE2 存储元件物品读取器。
 *
 * <p>不引用 AE2 类型，避免 AE2 未安装时 CreateCMPOR 的类加载失败；运行时通过
 * AE2 公开的 {@code StorageCells.getCellInventory} 和 {@code getAvailableStacks}
 * 反射读取 cell 内的物品。</p>
 */
final class Ae2CellContents {
    private static final String SOURCE = "ae2_cell";
    private static volatile Access access;

    private Ae2CellContents() {
    }

    static ContainerItemExpander.ReadResult read(ItemStack stack) {
        try {
            Access api = access();
            Object cell = api.getCellInventory().invoke(null, stack, null);
            if (cell == null) {
                return ContainerItemExpander.ReadResult.notApplicable();
            }

            Object available = api.getAvailableStacks().invoke(cell);
            if (!(available instanceof Iterable<?> entries)) {
                return ContainerItemExpander.ReadResult.failed(SOURCE, "getAvailableStacks 不是可迭代结果");
            }

            List<ContainerItemExpander.ContainedStack> contents = new ArrayList<>();
            for (Object entry : entries) {
                if (!(entry instanceof Map.Entry<?, ?> mapEntry)) {
                    return ContainerItemExpander.ReadResult.failed(SOURCE, "AE2 数量条目不是 Map.Entry");
                }
                Object key = mapEntry.getKey();
                if (key == null || !api.itemKeyClass().isInstance(key)) {
                    continue;
                }
                Object rawAmount = mapEntry.getValue();
                if (!(rawAmount instanceof Number number)) {
                    return ContainerItemExpander.ReadResult.failed(SOURCE, "AE2 数量不是数字");
                }
                long amount = number.longValue();
                if (amount <= 0) {
                    continue;
                }
                Object item = api.toStack().invoke(key);
                if (!(item instanceof ItemStack itemStack) || itemStack.isEmpty()) {
                    return ContainerItemExpander.ReadResult.failed(SOURCE, "AE2 物品键无法转换为 ItemStack");
                }
                contents.add(new ContainerItemExpander.ContainedStack(
                        amount, itemStack, "ae2_cell[amount=" + amount + "]"));
            }
            return ContainerItemExpander.ReadResult.success(SOURCE, contents);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return ContainerItemExpander.ReadResult.failed(
                    SOURCE, "读取失败: " + exception.getClass().getSimpleName());
        } catch (LinkageError error) {
            return ContainerItemExpander.ReadResult.failed(
                    SOURCE, "AE2 API 不可用: " + error.getClass().getSimpleName());
        }
    }

    private static Access access() throws ReflectiveOperationException {
        Access current = access;
        if (current != null) {
            return current;
        }
        synchronized (Ae2CellContents.class) {
            current = access;
            if (current == null) {
                Class<?> storageCells = Class.forName("appeng.api.storage.StorageCells");
                Method getCellInventory = findCellMethod(storageCells);
                if (getCellInventory == null) {
                    throw new NoSuchMethodException("StorageCells.getCellInventory");
                }
                Class<?> meStorage = Class.forName("appeng.api.storage.MEStorage");
                Method getAvailableStacks = meStorage.getMethod("getAvailableStacks");
                Class<?> itemKeyClass = Class.forName("appeng.api.stacks.AEItemKey");
                Method toStack = itemKeyClass.getMethod("toStack");
                current = new Access(getCellInventory, getAvailableStacks, itemKeyClass, toStack);
                access = current;
            }
        }
        return current;
    }

    private static Method findCellMethod(Class<?> storageCells) {
        for (Method method : storageCells.getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (Modifier.isStatic(method.getModifiers())
                    && method.getName().equals("getCellInventory")
                    && parameters.length == 2
                    && parameters[0].isAssignableFrom(ItemStack.class)
                    && !parameters[1].isPrimitive()) {
                return method;
            }
        }
        return null;
    }

    private record Access(Method getCellInventory, Method getAvailableStacks,
                          Class<?> itemKeyClass, Method toStack) {
    }
}
