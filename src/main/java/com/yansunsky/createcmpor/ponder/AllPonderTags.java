package com.yansunsky.createcmpor.ponder;

import com.yansunsky.createcmpor.CreateCMPOR;
import com.yansunsky.createcmpor.init.ModItems;

import net.createmod.ponder.api.registration.PonderTagRegistrationHelper;
import net.minecraft.resources.ResourceLocation;

/**
 * CreateCMPOR 的 Ponder 关联词条（教程分类）。
 *
 * <p><b>关联词条机制</b>（Create 的"思索=关联词条"）：把有 Ponder 教程的物品挂到词条上，
 * 玩家在 Ponder 索引界面（词条分类页）和场景内看到关联词条，可点击跳到同类的其他教程。
 * Create 用 {@code kinetic_appliances} / {@code logistics} 等 20+ 词条；本模组用一个
 * 总词条 {@link #SYSTEM} 把全部物品的教程关联起来。</p>
 *
 * <p><b>语言键</b>（lang 文件里可本地化，见 {@link net.createmod.ponder.foundation.registration.PonderLocalization}
 * 字节码常量池确认）：</p>
 * <ul>
 *     <li>词条标题：{@code <modid>.ponder.tag.<id>}（如 {@code createcmpor.ponder.tag.system}）</li>
 *     <li>词条描述：{@code <modid>.ponder.tag.<id>.description}</li>
 * </ul>
 *
 * <p>图标用物品（{@link ModItems#LAUNCHER_STICK} 评估棒），无需额外贴图。</p>
 */
public final class AllPonderTags {

    /** 总词条：CreateCMPOR 全部评估/工厂/IO 教程。 */
    public static final ResourceLocation SYSTEM = ResourceLocation.fromNamespaceAndPath(
            CreateCMPOR.MOD_ID, "system");

    private AllPonderTags() {
    }

    public static void register(PonderTagRegistrationHelper<ResourceLocation> helper) {
        helper.registerTag(SYSTEM)
                .addToIndex()
                .item(ModItems.LAUNCHER_STICK.get(), true, false)
                .title("CreateCMPOR System")
                .description("Parallel room evaluation and factory replication")
                .register();
    }
}
