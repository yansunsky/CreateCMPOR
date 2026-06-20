package com.createcmpor.stress;

import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 工厂方块应力档案的读写封装，集中所有 attachment 访问。
 */
public final class FactoryStressAccess {

    private FactoryStressAccess() {}

    /** 读取方块实体的应力档案；无则返回 {@link StressProfile#EMPTY}。 */
    public static StressProfile get(BlockEntity be) {
        if (be == null) return StressProfile.EMPTY;
        return be.getData(ModAttachments.STRESS_PROFILE.get());
    }

    /** 写入方块实体的应力档案并标记 dirty（持久化）。 */
    public static void set(BlockEntity be, StressProfile profile) {
        if (be == null) return;
        be.setData(ModAttachments.STRESS_PROFILE.get(), profile);
        be.setChanged();
    }
}
