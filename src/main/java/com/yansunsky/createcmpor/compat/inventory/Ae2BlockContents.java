package com.yansunsky.createcmpor.compat.inventory;

import com.yansunsky.createcmpor.CreateCMPOR;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.util.Map;

/** AE2 方块中不经标准 ItemHandler 暴露的本地存储兼容。 */
public final class Ae2BlockContents {
    private static final ResourceLocation ME_CHEST =
            ResourceLocation.fromNamespaceAndPath("ae2", "chest");

    private Ae2BlockContents() {
    }

    /**
     * ME Chest 只暴露 AE2 MEStorage capability；直接读取其 cell 物品，
     * 再复用 {@link ContainerItemExpander} 同时统计 cell 与内部物品。
     */
    public static boolean addToSnapshot(ServerLevel level, BlockPos pos,
                                        ResourceLocation blockId,
                                        HolderLookup.Provider registries,
                                        Map<ResourceLocation, Long> items) {
        if (!ME_CHEST.equals(blockId)) {
            return false;
        }
        Object blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            return true;
        }
        try {
            Method getCell = blockEntity.getClass().getMethod("getCell");
            Object rawCell = getCell.invoke(blockEntity);
            if (rawCell instanceof ItemStack cell && !cell.isEmpty()) {
                ContainerItemExpander.addToSnapshot(cell, cell.getCount(), registries, items);
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            CreateCMPOR.LOGGER.warn("AE2 ME Chest cell 读取失败（{}）：{}",
                    pos, exception.getClass().getSimpleName());
        } catch (LinkageError error) {
            CreateCMPOR.LOGGER.warn("AE2 ME Chest API 不可用（{}）：{}",
                    pos, error.getClass().getSimpleName());
        }
        return true;
    }
}
