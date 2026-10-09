package com.yansunsky.createcmpor.item;

/**
 * 净化启动棒。
 *
 * <p>定位：在评估开始前清除副本容器内的残余物，使评估走 <b>S0 全空简化路径</b>
 * （S0 物品/流体/能量全空时只按 IO trace 结算，避免初始库存干扰净额计算），
 * 从而提高评估精确性；若检测到动态结构（装置、车厢等），则回退为普通评估流程。</p>
 *
 * <p>继承 {@link LauncherStickItem}，便于将来按“启动棒”统一判定，
 * 避免两根棒子混用。本类不含任何右键逻辑（右键由 mixin 层负责）。</p>
 */
public class PurificationStickItem extends LauncherStickItem {

    /** 物品提示（tooltip）本地化键。 */
    public static final String TOOLTIP_KEY = "tooltip.createcmpor.purification_stick";

    public PurificationStickItem(Properties properties) {
        super(properties);
    }
}
