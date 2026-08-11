package com.createcmpor.item;

import net.minecraft.world.item.Item;

/**
 * 平行房间评估启动棒。
 *
 * <p>实际右键 CompactMachines 机器的处理由 Mixin 拦截 BoundCompactMachineBlock 完成。</p>
 */
public class LauncherStickItem extends Item {
    public LauncherStickItem(Properties properties) {
        super(properties);
    }
}
