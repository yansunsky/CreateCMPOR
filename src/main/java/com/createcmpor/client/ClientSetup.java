package com.createcmpor.client;

import com.createcmpor.CreateCMPOR;
import com.createcmpor.init.ModBlockEntities;
import com.simibubi.create.content.kinetics.base.ShaftRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * 客户端渲染绑定。
 *
 * <p>应力拓展方块复用 Create 的 {@link ShaftRenderer}：外壳走静态方块模型，内部画一根随转速旋转的传动杆，
 * 与安山传动箱完全一致。应力 IO 方块为创造马达静态外观，无需特殊 BER。
 */
@EventBusSubscriber(modid = CreateCMPOR.MOD_ID, value = Dist.CLIENT)
public class ClientSetup {

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        // 应力拓展方块：复用安山传动箱的传动杆渲染
        event.registerBlockEntityRenderer(ModBlockEntities.STRESS_EXTENSION.get(), ShaftRenderer::new);
        // 应力 IO 方块：同样用 ShaftRenderer 渲染朝向轴上的旋转杆（创造马达内部也有轴）
        event.registerBlockEntityRenderer(ModBlockEntities.STRESS_IO.get(), ShaftRenderer::new);
    }
}
