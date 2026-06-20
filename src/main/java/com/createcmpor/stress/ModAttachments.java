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
 * <p>用 Data Attachment 给 CMPOR 的工厂方块实体附加「应力档案」，无需修改 CMPOR 的
 * private 字段——attachment 会自动随方块实体存档/读档（写入 BE 的 {@code neoforge:attachments}）。
 */
public class ModAttachments {

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, CreateCMPOR.MOD_ID);

    /** 工厂方块的应力档案 attachment（可序列化、随 BE 存档）。 */
    public static final Supplier<AttachmentType<StressProfile>> STRESS_PROFILE =
            ATTACHMENT_TYPES.register("stress_profile", () -> AttachmentType
                    .builder(() -> StressProfile.EMPTY)
                    .serialize(StressProfile.CODEC)
                    .build());

    public static void register(IEventBus modEventBus) {
        ATTACHMENT_TYPES.register(modEventBus);
    }
}
