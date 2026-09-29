package com.yansunsky.createcmpor.client.preview;

import com.yansunsky.createcmpor.Config;
import com.yansunsky.createcmpor.CreateCMPOR;
import com.yansunsky.createcmpor.block.FactoryBlockEntity;
import com.yansunsky.createcmpor.network.ModNetwork;
import com.yansunsky.createcmpor.network.PreviewResponsePayload;
import com.yansunsky.createcmpor.network.RequestPreviewPayload;
import com.yansunsky.createcmpor.preview.PreviewSnapshot;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 客户端侧的按需同步与缓存（0.4.21）。
 *
 * <h3>为什么既写回 BE 又保留独立的缓存表</h3>
 * <ul>
 *     <li><b>写回客户端 BE</b>：渲染层零改动——{@code FactoryPreviewRenderer} / {@code PreviewBakeCache}
 *         的过期判据本来就是"BE 上那份快照的对象身份"，装回去就自动重烘；</li>
 *     <li><b>独立缓存表</b>：客户端 BE 在区块重载时会被 {@code clearAllBlockEntities()} 丢掉，
 *         "走出视距再回来"不该重新下载（这正是省流量的收益点），所以快照还得存在 BE 之外。</li>
 * </ul>
 *
 * <h3>什么时候发请求</h3>
 * 只在渲染回调里（{@link #maybeRequest}）——视锥剔除、渲染距离、区块加载状态全部由引擎免费提供；
 * 玩家背对工厂时根本不会调到这里。再加上：本地缓存命中就零请求、服务端说没有就永不请求（终止性负缓存）、
 * 每 tick 全局预算、每工厂冷却 + 指数退避 + 最大尝试次数。
 *
 * <h3>生命周期</h3>
 * <table>
 *     <tr><td>区块重载</td><td>缓存<b>保留</b>（BE 重建后靠轻量 tag 里的 rev 命中缓存）</td></tr>
 *     <tr><td>切维度 / 换世界</td><td>{@link #ensureLevel} 检测到维度变化即整体清空</td></tr>
 *     <tr><td>资源重载（F3+T）</td><td>无需处理：快照只是数据，不含模型资源</td></tr>
 * </table>
 */
public final class ClientPreviewSync {

    private ClientPreviewSync() {
    }

    private record Key(ResourceKey<Level> dimension, BlockPos pos) {
    }

    /** 一条缓存/节流记录。可变（inFlight/attempts 要就地更新）。 */
    private static final class Entry {
        private int rev = -1;
        private PreviewSnapshot snapshot;
        private boolean absent;
        private int attempts;
        /**
         * 上次发请求的 tick；<b>0 = 从未请求过</b>。
         *
         * <p>⚠️ 0.4.24 修复：这里原先用 {@code Long.MIN_VALUE} 当"从未"哨兵，而判断是
         * {@code now - lastRequestTick < cooldown} ⇒ {@code now - Long.MIN_VALUE} <b>溢出成负数</b>，
         * 于是<b>第一次请求必然被"冷却未到"挡掉</b>（实机现象：bumprev 后微缩消失且永不索取）。
         * 用 0 做哨兵并显式判 0 即可，代价是"第 0 tick 请求过"这种不可能的情形。
         */
        private long lastRequestTick;
        private boolean inFlight;
        private int bytes;
    }

    /** LRU：访问序（按条目数 + 估算字节双闸门淘汰）。 */
    private static final Map<Key, Entry> CACHE = new LinkedHashMap<>(16, 0.75F, true);
    private static final int MAX_ENTRIES = 128;
    private static long cachedBytes;

    private static long budgetTick = -1L;
    private static int budgetUsed;
    private static int requestId;
    private static ResourceKey<Level> lastDimension;

    /** 诊断计数（{@code /ccmpor preview sync} 用）。 */
    private static int hits;
    private static int misses;
    private static int requests;
    private static int evictions;
    private static boolean firstArrivalLogged;
    /** 首次"为什么没发请求"只记一条 INFO（实机排查用；DEBUG 在本工程会被过滤）。 */
    private static boolean firstBailLogged;

    private static void bailOnce(String reason) {
        if (!firstBailLogged) {
            firstBailLogged = true;
            CreateCMPOR.LOGGER.info("[预览同步] 本次没有发请求，原因：{}", reason);
        }
    }

    /** 渲染器唯一入口：需要就发请求 / 命中缓存就装回 BE。任何异常都吞掉（装饰路径，绝不冒烟）。 */
    public static void maybeRequest(FactoryBlockEntity be) {
        try {
            if (!Config.ENABLE_FACTORY_PREVIEW.get()) {
                bailOnce("客户端配置 enableFactoryPreview=false");
                return;
            }
            if (Config.PREVIEW_REQUEST_MODE.get() != Config.PreviewSyncMode.ON_DEMAND) {
                bailOnce("客户端配置 previewRequestMode 不是 ON_DEMAND");
                return;
            }
            Level level = be.getLevel();
            if (level == null) {
                return;
            }
            ensureLevel(level);
            if (!channelAvailable()) {
                bailOnce("对端没有本模组的按需同步通道（服务端为旧版本，或未注册 createcmpor:preview_response）");
                return;   // 服务端没装/旧版本：一次都不发
            }
            Key key = new Key(level.dimension(), be.getBlockPos());
            int rev = be.previewRev();
            if (!be.previewHasContent()) {
                bailOnce("服务端在轻量 tag 里说这个工厂没有快照（has_preview=false）");
                Entry absent = CACHE.computeIfAbsent(key, k -> new Entry());
                absent.absent = true;
                absent.rev = rev;
                evictIfNeeded();
                return;   // 服务端确认没有快照 → 永不请求（最省的早退）
            }
            Entry entry = CACHE.get(key);
            if (entry != null && entry.rev == rev) {
                if (entry.absent) {
                    return;
                }
                hits++;
                if (entry.snapshot != null && be.getPreviewSnapshot() != entry.snapshot) {
                    // 缓存命中：零请求，直接把快照装回 BE（渲染层随即重烘）
                    be.installClientPreview(entry.snapshot, rev);
                }
                return;
            }
            misses++;

            long now = AnimationTickHolder.getTicks();
            if (entry == null) {
                entry = new Entry();
                CACHE.put(key, entry);
                evictIfNeeded();
            }
            if (entry.inFlight) {
                return;
            }
            if (entry.attempts >= Config.PREVIEW_REQUEST_MAX_ATTEMPTS.get()) {
                bailOnce("同一工厂已尝试 " + entry.attempts + " 次（上限 "
                        + Config.PREVIEW_REQUEST_MAX_ATTEMPTS.get() + "），本会话放弃");
                return;
            }
            long cooldown = (long) Config.PREVIEW_REQUEST_COOLDOWN_TICKS.get()
                    * (1L << Math.min(entry.attempts, 4));   // 20 / 40 / 80 / 160 tick 退避
            if (entry.lastRequestTick != 0L && now - entry.lastRequestTick < cooldown) {
                return;
            }
            if (!takeBudget(now)) {
                return;   // 每 tick 预算用完：下一 tick 再试，不算异常
            }
            if (Minecraft.getInstance().player == null) {
                bailOnce("客户端玩家实体还没就绪");
                return;
            }
            double radius = Config.PREVIEW_REQUEST_RADIUS.get();
            if (Minecraft.getInstance().player.distanceToSqr(Vec3.atCenterOf(be.getBlockPos()))
                    > radius * radius) {
                bailOnce("玩家距工厂 " + (int) Math.sqrt(
                                Minecraft.getInstance().player.distanceToSqr(Vec3.atCenterOf(be.getBlockPos())))
                                + " 格，超出请求半径 " + Config.PREVIEW_REQUEST_RADIUS.get() + " 格");
                return;   // 比渲染 LOD 小的请求半径：天然滞回，避免边缘反复请求
            }
            entry.inFlight = true;
            entry.attempts++;
            entry.lastRequestTick = now;
            requests++;
            // INFO 而非 DEBUG：本工程环境里我们 logger 的 DEBUG 会被过滤，而请求次数受节流限制（很稀疏），
            // 写成 INFO 才能让"按需同步到底有没有工作"直接可观测。
            CreateCMPOR.LOGGER.info("[预览同步] 客户端发出请求：{} knownRev={}（第 {} 次尝试）",
                    be.getBlockPos(), rev, entry.attempts);
            PacketDistributor.sendToServer(new RequestPreviewPayload(be.getBlockPos(),
                    be.getPreviewSnapshot() == null ? -1 : rev, nextRequestId()));
        } catch (Throwable error) {
            CreateCMPOR.LOGGER.debug("[预览同步] 请求判定失败", error);
        }
    }

    /** 服务端响应（已在主线程，见 ModNetwork 的注册）。 */
    public static void onResponse(PreviewResponsePayload payload) {
        try {
            Level level = Minecraft.getInstance().level;
            if (level == null) {
                return;
            }
            Key key = new Key(level.dimension(), payload.pos());
            Entry entry = CACHE.computeIfAbsent(key, k -> new Entry());
            entry.inFlight = false;
            switch (payload.status()) {
                case PreviewResponsePayload.STATUS_OK -> {
                    PreviewSnapshot snapshot = payload.preview().map(PreviewSnapshot::load).orElse(null);
                    if (snapshot == null) {
                        entry.absent = true;
                        entry.rev = payload.rev();
                        return;
                    }
                    cachedBytes += snapshot.encodedSize() - entry.bytes;
                    entry.bytes = snapshot.encodedSize();
                    entry.snapshot = snapshot;
                    entry.rev = payload.rev();
                    entry.absent = false;
                    entry.attempts = 0;
                    if (level.getBlockEntity(payload.pos()) instanceof FactoryBlockEntity be) {
                        be.installClientPreview(snapshot, payload.rev());
                    }
                    if (!firstArrivalLogged) {
                        firstArrivalLogged = true;
                        CreateCMPOR.LOGGER.info("[预览同步] 首个按需快照已到达：{} rev={} {} KB"
                                        + "（之后命中本地缓存不再请求）",
                                payload.pos(), payload.rev(), snapshot.encodedSize() / 1024);
                    }
                    evictIfNeeded();
                }
                // 终止性：本 rev 内不再重试
                case PreviewResponsePayload.STATUS_EMPTY,
                     PreviewResponsePayload.STATUS_TOO_BIG,
                     PreviewResponsePayload.STATUS_TOO_FAR -> {
                    entry.absent = true;
                    entry.rev = payload.rev();
                }
                // NOT_LOADED / RATE_LIMITED：保持非终止，等下一次渲染回调按退避重试
                default -> {
                }
            }
            CreateCMPOR.LOGGER.info("[预览同步] 客户端收到响应：{} rev={} {} 数据={} B",
                    payload.pos(), payload.rev(), statusName(payload.status()),
                    payload.preview().map(tag -> tag.sizeInBytes()).orElse(0));
        } catch (Throwable error) {
            CreateCMPOR.LOGGER.debug("[预览同步] 处理响应失败", error);
        }
    }

    /** status 的可读名（日志用）。 */
    private static String statusName(int status) {
        return switch (status) {
            case PreviewResponsePayload.STATUS_OK -> "OK";
            case PreviewResponsePayload.STATUS_EMPTY -> "EMPTY(确实没有快照)";
            case PreviewResponsePayload.STATUS_NOT_LOADED -> "NOT_LOADED(稍后重试)";
            case PreviewResponsePayload.STATUS_TOO_BIG -> "TOO_BIG(超过单包上限)";
            case PreviewResponsePayload.STATUS_RATE_LIMITED -> "RATE_LIMITED(稍后重试)";
            case PreviewResponsePayload.STATUS_TOO_FAR -> "TOO_FAR(距离超限)";
            default -> "status=" + status;
        };
    }

    /** 维度/世界变化就整体清空（客户端 BE 换了，缓存里的坐标也不再有意义）。 */
    private static void ensureLevel(Level level) {
        ResourceKey<Level> dimension = level.dimension();
        if (lastDimension != null && !lastDimension.equals(dimension)) {
            clear();
        }
        lastDimension = dimension;
    }

    /** 退出世界 / 切服务器 / 资源重载时清空（由 ClientSetup 与渲染器调用）。 */
    public static void clear() {
        CACHE.clear();
        cachedBytes = 0;
        budgetTick = -1L;
        budgetUsed = 0;
        lastDimension = null;
        hits = 0;
        misses = 0;
        requests = 0;
        evictions = 0;
    }

    /** 诊断行（{@code /ccmpor preview sync}）。 */
    public static String describe() {
        return String.format("缓存 %d 条 / 约 %d KB（命中 %d、未命中 %d、请求 %d、淘汰 %d）；"
                        + "服务端通道 = %s；客户端请求模式 = %s",
                CACHE.size(), cachedBytes / 1024, hits, misses, requests, evictions,
                channelAvailable() ? "可用" : "不可用",
                Config.PREVIEW_REQUEST_MODE.get());
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private static boolean channelAvailable() {
        try {
            var connection = Minecraft.getInstance().getConnection();
            if (connection == null) {
                return false;
            }
            return NetworkRegistry.hasChannel(connection, ModNetwork.responseChannelId());
        } catch (Throwable error) {
            return false;   // 判定失败就当不可用：宁可显示机壳，也不要乱发包
        }
    }

    private static int nextRequestId() {
        requestId = requestId == Integer.MAX_VALUE ? 1 : requestId + 1;
        return requestId;
    }

    /** 每 tick 全局请求预算（默认 2 个）：一次性走进一堆工厂时也不会瞬间打出几十个包。 */
    private static boolean takeBudget(long now) {
        if (budgetTick != now) {
            budgetTick = now;
            budgetUsed = 0;
        }
        int perTick = Config.PREVIEW_REQUEST_PER_TICK.get();
        if (budgetUsed >= perTick) {
            return false;
        }
        budgetUsed++;
        return true;
    }

    private static void evictIfNeeded() {
        long maxBytes = Config.PREVIEW_CACHE_MAX_KB.get() * 1024L;
        while (!CACHE.isEmpty() && (CACHE.size() > MAX_ENTRIES || cachedBytes > maxBytes)) {
            var iterator = CACHE.entrySet().iterator();
            if (!iterator.hasNext()) {
                return;
            }
            Map.Entry<Key, Entry> eldest = iterator.next();
            cachedBytes -= eldest.getValue().bytes;
            iterator.remove();
            evictions++;
        }
    }
}
