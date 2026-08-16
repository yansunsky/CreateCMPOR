package com.createcmpor.client;

import com.createcmpor.CreateCMPOR;
import com.createcmpor.block.FactoryBlockEntity;
import com.createcmpor.block.StressExtensionBlockEntity;
import com.createcmpor.block.StressInputBlockEntity;
import com.createcmpor.block.StressOutputBlockEntity;
import com.createcmpor.init.ModBlockEntities;
import com.simibubi.create.content.kinetics.base.ShaftRenderer;
import com.simibubi.create.content.kinetics.base.SingleAxisRotatingVisual;
import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

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
 */
@Mod(value = CreateCMPOR.MOD_ID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = CreateCMPOR.MOD_ID, value = Dist.CLIENT)
public class ClientSetup {

    public ClientSetup(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.STRESS_EXTENSION.get(), ShaftRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.STRESS_INPUT.get(), ShaftRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.STRESS_OUTPUT.get(), ShaftRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.FACTORY.get(), FactoryRenderer::new);
        CreateCMPOR.LOGGER.debug("[CreateCMPOR] 已注册 ShaftRenderer BER 兜底");
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(ClientSetup::registerFlywheelVisuals);
    }

    private static void registerFlywheelVisuals() {
        SimpleBlockEntityVisualizer.<StressExtensionBlockEntity>builder(ModBlockEntities.STRESS_EXTENSION.get())
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
