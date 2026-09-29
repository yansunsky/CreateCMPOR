package com.yansunsky.createcmpor.client;

import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

/**
 * 极薄扩展点：只回答"这个物品的自定义渲染器是谁"。
 *
 * <p>{@code ItemRenderer.render} 在 {@code BakedModel.isCustomRenderer()} 为真时会调
 * {@code IClientItemExtensions.of(stack).getCustomRenderer().renderByItem(...)}
 * （{@code ItemRenderer.java:156}，实测）。NeoForge 的默认实现返回原版的
 * {@code Minecraft.getInstance().getItemRenderer().getBlockEntityRenderer()}，那不是我们想要的。
 *
 * <p>实例必须是<b>单例</b>：{@code getCustomRenderer()} 每次绘制都会被调用，不能每次 new 一个 BEWLR。
 *
 * <p>注册只做一次（{@code ClientSetup.registerItemClientExtensions}）：NeoForge 的
 * {@code ClientExtensionsManager.register} 对同一物品重复注册会抛 {@code IllegalStateException}
 * （启动期崩），不是覆盖。
 */
public final class FactoryItemClientExtensions implements IClientItemExtensions {

    /** 单例：注册与查询共用同一个实例。 */
    public static final FactoryItemClientExtensions INSTANCE = new FactoryItemClientExtensions();

    private final BlockEntityWithoutLevelRenderer renderer = new FactoryItemRenderer();

    private FactoryItemClientExtensions() {
    }

    @Override
    public BlockEntityWithoutLevelRenderer getCustomRenderer() {
        return renderer;
    }
}
