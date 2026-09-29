package com.yansunsky.createcmpor.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 → 服务端：请求某个工厂的微缩快照（按需同步，0.4.21）。
 *
 * <p>只在客户端**即将渲染**这个工厂、且本地缓存不是当前版本时才发（见
 * {@code ClientPreviewSync.maybeRequest}）——视锥剔除/渲染距离/区块加载状态由引擎免费提供。
 *
 * @param pos       目标工厂坐标。<b>刻意不带维度</b>：服务端一律用发送者当前维度，杜绝跨维度探测。
 * @param knownRev  客户端已有的版本号；{@code -1} = 本地完全没有。服务端据此短路（同版本不回包）。
 * @param requestId 客户端单调自增，仅用于日志与丢弃过期响应。
 */
public record RequestPreviewPayload(BlockPos pos, int knownRev, int requestId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RequestPreviewPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath("createcmpor", "request_preview"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RequestPreviewPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, RequestPreviewPayload::pos,
                    ByteBufCodecs.VAR_INT, RequestPreviewPayload::knownRev,
                    ByteBufCodecs.VAR_INT, RequestPreviewPayload::requestId,
                    RequestPreviewPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
