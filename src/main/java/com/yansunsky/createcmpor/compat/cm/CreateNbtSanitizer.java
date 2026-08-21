package com.yansunsky.createcmpor.compat.cm;

import net.minecraft.nbt.CompoundTag;

/**
 * 清理跨房间复制时不应继承的 Create 动能网络缓存。
 */
public final class CreateNbtSanitizer {

    private static final String[] CREATE_NETWORK_KEYS = {
            "Network",
            "network",
            "HasNetwork",
            "hasNetwork",
            "NeedsSpeedUpdate",
            "needsSpeedUpdate",
            "Source",
            "source",
            "LastKnownPos",
            "lastKnownPos",
            "Overstressed",
            "overstressed",
            "Anchor",
            "anchor"
    };

    private CreateNbtSanitizer() {
    }

    /**
     * 返回清理后的副本，避免修改原始快照 tag。
     */
    public static CompoundTag sanitizeBlockEntityTag(CompoundTag original) {
        CompoundTag sanitized = original.copy();
        for (String key : CREATE_NETWORK_KEYS) {
            sanitized.remove(key);
        }
        return sanitized;
    }
}
