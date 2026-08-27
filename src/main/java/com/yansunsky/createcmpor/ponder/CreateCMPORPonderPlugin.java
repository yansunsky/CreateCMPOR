package com.yansunsky.createcmpor.ponder;

import com.yansunsky.createcmpor.CreateCMPOR;

import net.createmod.ponder.api.registration.PonderPlugin;
import net.createmod.ponder.api.registration.PonderSceneRegistrationHelper;
import net.createmod.ponder.api.registration.PonderTagRegistrationHelper;
import net.minecraft.resources.ResourceLocation;

/**
 * CreateCMPOR 的 Ponder 插件入口。
 *
 * <p>Ponder（Create 的游戏内交互式教程）通过 {@link PonderPlugin} 发现各模组的教程场景：
 * <ul>
 *     <li>{@link #getModId()} 决定场景资源与 lang 的命名空间（createcmpor）。</li>
 *     <li>{@link #registerScenes} 注册绑定到方块/物品的故事板（storyboard）。</li>
 * </ul>
 * 注册时机：在客户端初始化时调用 {@code PonderIndex.addPlugin(new CreateCMPORPonderPlugin())}。
 */
public class CreateCMPORPonderPlugin implements PonderPlugin {

    @Override
    public String getModId() {
        return CreateCMPOR.MOD_ID;
    }

    @Override
    public void registerScenes(PonderSceneRegistrationHelper<ResourceLocation> helper) {
        ModPonderScenes.register(helper);
    }

    @Override
    public void registerTags(PonderTagRegistrationHelper<ResourceLocation> helper) {
        // 关联词条：把全部物品的教程场景挂到总词条（Ponder 索引分类页可见）
        AllPonderTags.register(helper);
    }
}
