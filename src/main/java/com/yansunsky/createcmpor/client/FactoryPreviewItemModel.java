package com.yansunsky.createcmpor.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemDisplayContext;
import net.neoforged.neoforge.client.model.BakedModelWrapper;

/**
 * 工厂物品（{@code createcmpor:factory_block#inventory}）的模型包装：把物品渲染让给
 * {@link FactoryItemRenderer}（BEWLR）。
 *
 * <p><b>为什么用"换 BakedModel"而不是把物品模型 JSON 改成 {@code "parent": "builtin/entity"}？</b>
 * 后者能跑通，但：① 会丢掉 {@code block/block} 的整套 display 变换（GUI/手持/掉落物的角度与缩放全得手抄）；
 * ② {@code BuiltInModel.getQuads} 返回空列表，一旦降级就是<b>物品完全隐形</b>；
 * ③ {@code gui_light}/{@code isGui3d} 语义要自己对。换模型这条路 display 变换天然继承（本类不碰
 * {@link #getTransforms()}，由 {@link BakedModelWrapper} 转发给原机壳模型），且 {@code getQuads} 仍转发
 * 原模型——JEI/合成预览/粒子图标等旁路取用行为不变。
 *
 * <p><b>两个必须覆写的方法（都是实测坑）</b>：
 * <ol>
 *     <li>{@link #isCustomRenderer()} 返回 {@code true} —— 否则 {@code ItemRenderer.render} 根本不会走到
 *         {@code IClientItemExtensions#getCustomRenderer().renderByItem(...)}，<b>且没有任何报错</b>；</li>
 *     <li>{@link #applyTransform} <b>必须返回 {@code this}</b> —— {@link BakedModelWrapper} 的默认实现是
 *         {@code return originalModel.applyTransform(...)}（NeoForge 源码 {@code BakedModelWrapper:84-87}），
 *         返回的是原模型；{@code ItemRenderer} 拿这个返回值去问 {@code isCustomRenderer()}，于是"改了
 *         isCustomRenderer 也白改"。正确写法：先让 {@code super} 把 display 变换<b>施加到姿态栈</b>（副作用要留），
 *         再把返回值换成自己。</li>
 * </ol>
 *
 * <p>本类<b>只有</b>这两个语义改动，其余一律转发：因此模型在其它路径（GUI 图标、粒子、JEI）上的
 * 几何/粒子贴图/环境光遮蔽行为与今天完全一致。
 */
public final class FactoryPreviewItemModel extends BakedModelWrapper<BakedModel> {

    public FactoryPreviewItemModel(BakedModel originalModel) {
        super(originalModel);
    }

    /** 原始机壳模型：降级自画机壳时要用它（见 {@link FactoryItemRenderer}）。 */
    public BakedModel originalModel() {
        return originalModel;
    }

    @Override
    public boolean isCustomRenderer() {
        return true;
    }

    @Override
    public BakedModel applyTransform(ItemDisplayContext ctx, PoseStack ms, boolean leftHand) {
        // 副作用必须保留：把该上下文的 display 变换压进姿态栈（block/block 的那一套）。
        super.applyTransform(ctx, ms, leftHand);
        // 但返回值必须是 this——返回 originalModel 会让 renderByItem 永不调用（零报错）。
        return this;
    }
}
