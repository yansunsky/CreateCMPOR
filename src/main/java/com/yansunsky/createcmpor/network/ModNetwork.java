package com.yansunsky.createcmpor.network;

import com.yansunsky.createcmpor.Config;
import com.yansunsky.createcmpor.CreateCMPOR;
import com.yansunsky.createcmpor.block.FactoryBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 按需同步（0.4.21）的网络层：两个包的注册 + 服务端 handler。
 *
 * <h3>为什么通道必须 {@code optional()}</h3>
 * NeoForge 的通道协商里，注册为必选而对方缺失会导致**连接被拒**；注册成可选则缺失时静默移除
 * ⇒ 旧客户端连新服务端不会断线（只是看不到微缩），这正是我们要的兼容行为。
 * 客户端再用 {@link NetworkRegistry#hasChannel} 判断"对端支不支持"，不支持就一次都不发请求。
 *
 * <h3>安全与限流（逐条，全部在服务端做）</h3>
 * <ul>
 *     <li><b>维度</b>：一律用 {@code context.player()} 的 {@code serverLevel()}，包里不带维度 ⇒ 无法跨维度探测；</li>
 *     <li><b>区块</b>：{@code level.isLoaded(pos)}，<b>绝不强制加载</b>（避免 ticket 注入 DoS）；</li>
 *     <li><b>类型</b>：必须 {@code instanceof FactoryBlockEntity} ⇒ 不能用来探测任意方块实体；</li>
 *     <li><b>距离</b>：{@code previewSyncMaxDistance}（默认 128 格，比客户端请求半径 48 宽）；</li>
 *     <li><b>限流</b>：每玩家令牌桶（容量 8，每 10 tick 补 1 个 ≈ 2/秒，允许"一次进视野一堆工厂"的突发）；</li>
 *     <li><b>体积闸门</b>：{@code previewSyncMaxKb}（默认 1 MB）——客户端读 NBT 有 2 MB 硬配额，
 *         越界不是"显示异常"而是<b>区块包解析失败/断线</b>，所以必须发之前先量；</li>
 *     <li><b>同版本短路</b>：{@code knownRev == f.previewRev()} 时**不回包**（正常客户端稳态成本 = 0）。</li>
 * </ul>
 */
public final class ModNetwork {

    private static final String PROTOCOL_VERSION = "1";

    /** 令牌桶：容量 8、每 10 tick 补 1。 */
    private static final int BUCKET_CAPACITY = 8;
    private static final int BUCKET_REFILL_TICKS = 10;

    private static final Map<UUID, Bucket> BUCKETS = new HashMap<>();

    /**
     * 检测到旧客户端后整体降级为 FULL。
     *
     * <p>tag 是<b>全渠道广播</b>的（同一条 {@code getUpdateTag} 发给所有追踪者），
     * 所以只能整体降级、不能只照顾那一个玩家——这也是正确且足够的做法。
     */
    private static volatile boolean forcedFull;

    /** 首个按需请求只记一次（验收探针：证明"按需"这条链真的在工作）。 */
    private static boolean firstRequestLogged;

    /** 体积超限只记一次（每工厂），避免刷日志。 */
    private static final java.util.Set<Long> TOO_BIG_REPORTED = new java.util.HashSet<>();

    private ModNetwork() {
    }

    // ------------------------------------------------------------------
    // 注册（挂 mod 总线：CreateCMPOR 构造器里 modEventBus.addListener）
    // ------------------------------------------------------------------

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION).optional();
        registrar.playToServer(RequestPreviewPayload.TYPE, RequestPreviewPayload.STREAM_CODEC,
                ModNetwork::onRequest);
        registrar.playToClient(PreviewResponsePayload.TYPE, PreviewResponsePayload.STREAM_CODEC,
                ModNetwork::onResponse);
    }

    /** 服务端是否需要把快照塞回 tag（旧客户端存在 / 配置就是 FULL）。 */
    public static boolean forcedFull() {
        return forcedFull;
    }

    /**
     * 登录时检测"装了旧版本本模组的客户端"：没有我们的通道 ⇒ 本会话整体降级为 FULL 并 WARN。
     *
     * @return true 表示检测到旧客户端
     */
    public static boolean detectOutdatedClient(ServerPlayer player) {
        try {
            boolean has = NetworkRegistry.hasChannel(player.connection, PreviewResponsePayload.TYPE.id());
            if (!has && !forcedFull) {
                forcedFull = true;
                CreateCMPOR.LOGGER.warn("[预览同步] 检测到未注册按需同步通道的客户端 {}（旧版本本模组），"
                        + "本会话自动回退到 FULL（快照随方块实体 NBT 发送）", player.getName().getString());
            }
            return !has;
        } catch (Throwable error) {
            CreateCMPOR.LOGGER.debug("[预览同步] 通道检测失败（按 FULL 处理）", error);
            return true;
        }
    }

    /** 玩家登出时清掉他的令牌桶。 */
    public static void forgetPlayer(UUID playerId) {
        BUCKETS.remove(playerId);
    }

    // ------------------------------------------------------------------
    // 服务端：处理请求
    // ------------------------------------------------------------------

    private static void onRequest(RequestPreviewPayload payload, IPayloadContext context) {
        // handler 默认在主线程（PayloadRegistrar 会包 MainThreadPayloadHandler），可以直接读 level/BE
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        BlockPos pos = payload.pos();
        try {
            ServerLevel level = player.serverLevel();
            BlockEntity be = level.isLoaded(pos) ? level.getBlockEntity(pos) : null;
            if (be == null) {
                reply(player, payload, 0, PreviewResponsePayload.STATUS_NOT_LOADED, null);
                return;
            }
            double max = Config.PREVIEW_SYNC_MAX_DISTANCE.get();
            if (player.blockPosition().distSqr(pos) > max * max) {
                reply(player, payload, 0, PreviewResponsePayload.STATUS_TOO_FAR, null);
                return;
            }
            if (!tryConsume(player)) {
                reply(player, payload, 0, PreviewResponsePayload.STATUS_RATE_LIMITED, null);
                return;
            }
            if (!(be instanceof FactoryBlockEntity factory)) {
                // EMPTY 只在"类型对但确实没快照"与"不是工厂"两种情况下返回；
                // 配合 NOT_LOADED 的区分，不构成"任意 BE 存在性探测"的工具（必须先通过距离与限流）
                reply(player, payload, 0, PreviewResponsePayload.STATUS_EMPTY, null);
                return;
            }
            int rev = factory.previewRev();
            if (!firstRequestLogged) {
                firstRequestLogged = true;
                CreateCMPOR.LOGGER.info("[预览同步] 收到首个按需请求：玩家 {} 请求 {}（距离 {} 格，knownRev={}，服务端 rev={}）",
                        player.getName().getString(), pos,
                        (int) Math.sqrt(player.blockPosition().distSqr(pos)), payload.knownRev(), rev);
            }
            if (!factory.previewHasContent() || factory.getPreviewSnapshot() == null) {
                reply(player, payload, rev, PreviewResponsePayload.STATUS_EMPTY, null);
                return;
            }
            if (payload.knownRev() == rev) {
                return;   // 客户端已经是最新的：一个字节都不发
            }
            CompoundTag tag = factory.previewPayloadTag();
            int capBytes = Config.PREVIEW_SYNC_MAX_KB.get() * 1024;
            if (tag == null || tag.sizeInBytes() > capBytes) {
                long key = pos.asLong();
                if (TOO_BIG_REPORTED.add(key)) {
                    CreateCMPOR.LOGGER.warn("[预览同步] 工厂 {} 的快照 {} KB 超过单包上限 {} KB，"
                            + "已拒绝发送（该工厂本次会话不再尝试）", pos,
                            tag == null ? 0 : tag.sizeInBytes() / 1024, capBytes / 1024);
                }
                reply(player, payload, rev, PreviewResponsePayload.STATUS_TOO_BIG, null);
                return;
            }
            reply(player, payload, rev, PreviewResponsePayload.STATUS_OK, tag);
        } catch (Throwable error) {
            CreateCMPOR.LOGGER.debug("[预览同步] 处理请求失败：{}", pos, error);
        }
    }

    private static void reply(ServerPlayer player, RequestPreviewPayload request, int rev, int status,
                              CompoundTag preview) {
        CreateCMPOR.LOGGER.info("[预览同步] 服务端响应 {}：rev={} status={} 数据={} KB",
                request.pos(), rev, status, (preview == null ? 0 : preview.sizeInBytes()) / 1024);
        try {
            PacketDistributor.sendToPlayer(player, new PreviewResponsePayload(
                    request.pos(), rev, request.requestId(), status, Optional.ofNullable(preview)));
        } catch (Throwable error) {
            CreateCMPOR.LOGGER.debug("[预览同步] 回包失败：{}", request.pos(), error);
        }
    }

    // ------------------------------------------------------------------
    // 服务端：每玩家令牌桶
    // ------------------------------------------------------------------

    private static final class Bucket {
        private double tokens = BUCKET_CAPACITY;
        private long lastRefillTick;
    }

    private static boolean tryConsume(ServerPlayer player) {
        Bucket bucket = BUCKETS.computeIfAbsent(player.getUUID(), id -> new Bucket());
        long now = player.serverLevel().getGameTime();
        if (bucket.lastRefillTick == 0) {
            bucket.lastRefillTick = now;
        }
        long elapsed = now - bucket.lastRefillTick;
        if (elapsed > 0) {
            bucket.tokens = Math.min(BUCKET_CAPACITY,
                    bucket.tokens + (double) elapsed / BUCKET_REFILL_TICKS);
            bucket.lastRefillTick = now;
        }
        if (bucket.tokens < 1.0D) {
            return false;
        }
        bucket.tokens -= 1.0D;
        return true;
    }

    // ------------------------------------------------------------------
    // 客户端：处理响应
    // ------------------------------------------------------------------

    private static void onResponse(PreviewResponsePayload payload, IPayloadContext context) {
        if (FMLEnvironment.dist.isClient()) {
            com.yansunsky.createcmpor.client.preview.ClientPreviewSync.onResponse(payload);
        }
    }

    /** 供诊断命令使用：当前协议 id（打印用）。 */
    public static ResourceLocation responseChannelId() {
        return PreviewResponsePayload.TYPE.id();
    }
}
