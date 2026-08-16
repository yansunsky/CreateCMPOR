package com.createcmpor.stress;

import com.createcmpor.CreateCMPOR;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/**
 * NeoForge Data Attachment 注册。
 *
     * <p>用 Data Attachment 给本模组工厂方块实体附加「应力档案」，避免把应力数据塞入主数据模型。
     * attachment 会自动随方块实体存档/读档（写入 BE 的 {@code neoforge:attachments}）。
 */
public class ModAttachments {

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, CreateCMPOR.MOD_ID);

    /** 工厂方块的应力档案 attachment：序列化（BE 存档）+ 同步客户端（护目镜 Impact/Capacity 行依赖）。 */
    public static final Supplier<AttachmentType<StressProfile>> STRESS_PROFILE =
            ATTACHMENT_TYPES.register("stress_profile", () -> AttachmentType
                    .builder(() -> StressProfile.EMPTY)
                    .serialize(StressProfile.CODEC)
                    .sync(StressProfile.STREAM_CODEC)
                    .build());

    public static void register(IEventBus modEventBus) {
        ATTACHMENT_TYPES.register(modEventBus);
    }
}
