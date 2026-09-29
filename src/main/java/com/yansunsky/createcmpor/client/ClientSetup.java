package com.yansunsky.createcmpor.client;

import com.yansunsky.createcmpor.CreateCMPOR;
import com.yansunsky.createcmpor.block.FactoryBlockEntity;
import com.yansunsky.createcmpor.block.IOExtensionBlockEntity;
import com.yansunsky.createcmpor.block.StressInputBlockEntity;
import com.yansunsky.createcmpor.block.StressOutputBlockEntity;
import com.yansunsky.createcmpor.init.ModBlockEntities;
import com.yansunsky.createcmpor.init.ModItems;
import com.yansunsky.createcmpor.client.preview.PreviewBakeCache;
import com.simibubi.create.content.kinetics.base.ShaftRenderer;
import com.simibubi.create.content.kinetics.base.SingleAxisRotatingVisual;
import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

import java.util.Map;

/**
 * 客户端渲染绑定。
 *
 * <p>三个方块都复用 Create 的传动杆(shaft)旋转渲染：
 * <ul>
 *     <li><b>Flywheel visual</b>（{@link SingleAxisRotatingVisual#shaft}）——当 Flywheel 引擎可用时
 *         由它渲染随转速旋转的传动杆。这是主路径（运行时通常有 Flywheel）。</li>
 *     <li><b>{@link ShaftRenderer}（原版 BER）</b>——仅在无 Flywheel 时兜底渲染。</li>
 * </ul>
 *
 * <p>注意：应力输出方块虽然不提供转速（被动观察者），但它的网络中可能有其他应力源驱动它旋转，
 * 因此也需要注册渲染。{@link com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer#renderSafe}
 * 在 Flywheel 可用时会直接 return，因此**必须同时注册 Flywheel visual**，否则传动杆永远不渲染。
 *
 * <p>另有三项「手持/物品栏微缩预览」（0.4.2）的注册：{@code ModifyBakingResult} 换物品模型、
 * {@code RegisterClientExtensionsEvent} 挂 BEWLR、{@code RegisterClientReloadListenersEvent} 清缓存。
 * 三者与上面的 {@code RegisterRenderers} 都在 {@code ClientHooks.initClientHooks} 的同一段里 post，
 * 时机一致（顺序：客户端扩展 → 重载监听 → 渲染器 → 模型烘焙）。
 */
@Mod(value = CreateCMPOR.MOD_ID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = CreateCMPOR.MOD_ID, value = Dist.CLIENT)
public class ClientSetup {

    public ClientSetup(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.IO_EXTENSION.get(), ShaftRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.STRESS_INPUT.get(), ShaftRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.STRESS_OUTPUT.get(), ShaftRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.FACTORY.get(), FactoryRenderer::new);
        CreateCMPOR.LOGGER.debug("[CreateCMPOR] 已注册 ShaftRenderer BER 兜底");
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(ClientSetup::registerFlywheelVisuals);
        // Ponder（Create 游戏内教程）：注册 CreateCMPOR 的场景插件
        event.enqueueWork(() -> net.createmod.ponder.foundation.PonderIndex.addPlugin(
                new com.yansunsky.createcmpor.ponder.CreateCMPORPonderPlugin()));
    }

    /**
     * 手持/物品栏预览（0.4.2）：把工厂物品的 baked 模型换成
     * {@link FactoryPreviewItemModel}，让 {@code ItemRenderer} 改走
     * {@code IClientItemExtensions#getCustomRenderer().renderByItem(...)}。
     *
     * <p>键必须是 {@code <物品id>#inventory}（{@link ModelResourceLocation#inventory}），这是 Create 的
     * {@code ModelSwapper} 在生产中用的同一条路径。找不到条目只记 debug 并保持原样（模型没烘出来时
     * 不该让客户端崩）。
     */
    @SubscribeEvent
    public static void modifyBakingResult(ModelEvent.ModifyBakingResult event) {
        Map<ModelResourceLocation, BakedModel> models = event.getModels();
        ModelResourceLocation location = ModelResourceLocation.inventory(
                ResourceLocation.fromNamespaceAndPath(CreateCMPOR.MOD_ID, "factory_block"));
        BakedModel original = models.get(location);
        if (original == null) {
            CreateCMPOR.LOGGER.debug("[预览] 未找到 {} ，物品侧微缩预览未启用", location);
            return;
        }
        models.put(location, new FactoryPreviewItemModel(original));
        CreateCMPOR.LOGGER.debug("[预览] 已接管 {} 的物品渲染（BEWLR）", location);
    }

    /**
     * 注册物品的自定义渲染器。只注册我们自己的物品：NeoForge 的
     * {@code ClientExtensionsManager} 对同一物品重复注册会抛 {@code IllegalStateException}（启动期崩）。
     *
     * <p>时机由现有代码背书：{@code ClientHooks.initClientHooks} 里
     * {@code ClientExtensionsManager.init()} 与 {@code EntityRenderersEvent.RegisterRenderers} 是
     * 同一个方法里先后 post 的。
     */
    @SubscribeEvent
    public static void registerItemClientExtensions(RegisterClientExtensionsEvent event) {
        event.registerItem(FactoryItemClientExtensions.INSTANCE, ModItems.FACTORY.get());
    }

    /**
     * 资源重载（F3+T / 切资源包）后必须清空预览缓存：缓存里握着上一次烘焙产生的顶点缓冲，
     * 而模型/图集已整体换代。
     */
    @SubscribeEvent
    public static void registerReloadListeners(RegisterClientReloadListenersEvent event) {
        ResourceManagerReloadListener listener = manager -> {
            FactoryPreviewRenderer.clear();
            PreviewBakeCache.clear();
        };
        event.registerReloadListener(listener);
    }

    private static void registerFlywheelVisuals() {
        SimpleBlockEntityVisualizer.<IOExtensionBlockEntity>builder(ModBlockEntities.IO_EXTENSION.get())
                .factory(SingleAxisRotatingVisual::shaft)
                .skipVanillaRender(be -> false)
                .apply();

        SimpleBlockEntityVisualizer.<StressInputBlockEntity>builder(ModBlockEntities.STRESS_INPUT.get())
                .factory(SingleAxisRotatingVisual::shaft)
                .skipVanillaRender(be -> false)
                .apply();

        SimpleBlockEntityVisualizer.<StressOutputBlockEntity>builder(ModBlockEntities.STRESS_OUTPUT.get())
                .factory(SingleAxisRotatingVisual::shaft)
                .skipVanillaRender(be -> false)
                .apply();

        SimpleBlockEntityVisualizer.<FactoryBlockEntity>builder(ModBlockEntities.FACTORY.get())
                .factory(FactoryVisual::factory)
                .skipVanillaRender(be -> false)
                .apply();
    }
}
