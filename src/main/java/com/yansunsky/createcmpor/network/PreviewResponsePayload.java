package com.yansunsky.createcmpor.network;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

/**
 * 服务端 → 客户端：微缩快照响应（按需同步，0.4.21）。
 *
 * <p><b>为什么用专用包而不是复用原版 {@code ClientboundBlockEntityDataPacket}</b>（方案报告 §2.4）：
 * 原版那条路的 {@code getTag() == null} 会让客户端走 {@code readClient(new CompoundTag())}，
 * 把 BE 的**其它所有字段清成默认值**（rates/patterns 会瞬间消失），且它用
 * {@code TRUSTED_COMPOUND_TAG}（没有 2 MB 配额保护），也无法做版本协商。
 *
 * @param pos       回显请求坐标
 * @param rev       服务端当前版本号（可能比客户端已知的更新）
 * @param requestId 回显，用于丢弃过期响应
 * @param status    见下方常量：终止性（EMPTY/TOO_BIG/TOO_FAR）与可重试（NOT_LOADED/RATE_LIMITED）之分，
 *                  是"没有快照就必须能终止重试"这条要求的落地
 * @param preview   仅 {@link #STATUS_OK} 时非空
 */
public record PreviewResponsePayload(BlockPos pos, int rev, int requestId, int status,
                                    Optional<CompoundTag> preview) implements CustomPacketPayload {

    /** 成功，{@code preview} 非空。 */
    public static final int STATUS_OK = 0;
    /** 确实没有快照（不是工厂 / 未采集 / 配置关闭）——**终止性**：本 rev 内不再重试。 */
    public static final int STATUS_EMPTY = 1;
    /** 区块或方块实体暂时不在（卸载竞态）——可重试（指数退避）。 */
    public static final int STATUS_NOT_LOADED = 2;
    /** 体积超过单包上限——**终止性** + 记日志（服务端已先记一条）。 */
    public static final int STATUS_TOO_BIG = 3;
    /** 触发限流——可重试。 */
    public static final int STATUS_RATE_LIMITED = 4;
    /** 距离超限——**终止性**（可能是作弊探测；也容忍服务器 TPS 抖动）。 */
    public static final int STATUS_TOO_FAR = 5;

    public static final CustomPacketPayload.Type<PreviewResponsePayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath("createcmpor", "preview_response"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PreviewResponsePayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, PreviewResponsePayload::pos,
                    ByteBufCodecs.VAR_INT, PreviewResponsePayload::rev,
                    ByteBufCodecs.VAR_INT, PreviewResponsePayload::requestId,
                    ByteBufCodecs.VAR_INT, PreviewResponsePayload::status,
                    ByteBufCodecs.OPTIONAL_COMPOUND_TAG, PreviewResponsePayload::preview,
                    PreviewResponsePayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
