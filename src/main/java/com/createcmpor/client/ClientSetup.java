package com.createcmpor.client;

import com.createcmpor.CreateCMPOR;
import com.createcmpor.block.StressExtensionBlockEntity;
import com.createcmpor.block.StressIOBlockEntity;
import com.createcmpor.init.ModBlockEntities;
import com.simibubi.create.content.kinetics.base.ShaftRenderer;
import com.simibubi.create.content.kinetics.base.SingleAxisRotatingVisual;
import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * 客户端渲染绑定。
 *
 * <p>应力拓展方块与应力 I/O 方块都复用 Create 的传动杆(shaft)旋转渲染：
 * <ul>
 *     <li><b>Flywheel visual</b>（{@link SingleAxisRotatingVisual#shaft}）——当 Flywheel 引擎可用时
 *         由它渲染随转速旋转的传动杆。这是主路径（运行时通常有 Flywheel）。</li>
 *     <li><b>{@link ShaftRenderer}（原版 BER）</b>——仅在无 Flywheel 时兜底渲染。</li>
 * </ul>
 *
 * <p>注意：{@link com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer#renderSafe}
 * 在 Flywheel 可用时会直接 return，因此**必须同时注册 Flywheel visual**，否则传动杆永远不渲染。
 */
@EventBusSubscriber(modid = CreateCMPOR.MOD_ID, value = Dist.CLIENT)
public class ClientSetup {

    /** 原版 BER 兜底（仅在无 Flywheel 时生效）。 */
    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModBlockEntities.STRESS_EXTENSION.get(), ShaftRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.STRESS_IO.get(), ShaftRenderer::new);
        CreateCMPOR.LOGGER.debug("[CreateCMPOR] 已注册 ShaftRenderer BER 兜底");
    }

    /** Flywheel visual 注册：Flywheel 存在时由它渲染/旋转传动杆。 */
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(ClientSetup::registerFlywheelVisuals);
    }

    private static void registerFlywheelVisuals() {
        SimpleBlockEntityVisualizer.<StressExtensionBlockEntity>builder(ModBlockEntities.STRESS_EXTENSION.get())
                .factory(SingleAxisRotatingVisual::shaft)
                .skipVanillaRender(be -> true) // 等价 Create Registrate .visual(..., false)
                .apply();

        SimpleBlockEntityVisualizer.<StressIOBlockEntity>builder(ModBlockEntities.STRESS_IO.get())
                .factory(SingleAxisRotatingVisual::shaft)
                .skipVanillaRender(be -> true)
                .apply();

        CreateCMPOR.LOGGER.info("[CreateCMPOR] 已注册 Flywheel visual（SingleAxisRotatingVisual::shaft）：stress_extension / stress_io");
    }
}
