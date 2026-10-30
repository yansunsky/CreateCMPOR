package com.yansunsky.createcmpor.evaluation;

import com.yansunsky.createcmpor.CreateCMPOR;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;

import java.util.List;
import java.util.UUID;

/** 精确 room chunk 的 BLOCK_TICKING ticket 生命周期。 */
final class EvaluationTicketManager {
    private static final TicketType<UUID> EVALUATION_TICKET = TicketType.create(
            "createcmpor_evaluation", UUID::compareTo);
    private static final int BLOCK_TICKING_DISTANCE = 1;
    private static final int ENTITY_TICKING_DISTANCE = 2;

    /**
     * 每 tick 最多撤销多少个区块的 ticket（0.5.5 引入）。
     *
     * <p><b>为什么必须分批</b>：撤销 ticket 会让区块的可见性从非 HIDDEN 掉到 HIDDEN，
     * vanilla {@code PersistentEntitySectionManager.updateChunkStatus} 随即
     * {@code chunksToUnload.add(i)}。如果<b>同一 tick 内</b>一次性撤销数百个区块，
     * {@code chunksToUnload} 这张 fastutil {@code LongOpenHashSet} 会先被撑大、
     * 随后在 {@code processUnloads()} 里边迭代边删、触发缩容 rehash，
     * 已有的迭代器下标随之失效 ⇒ {@code ArrayIndexOutOfBoundsException}（详见
     * {@code docs/58-*} 的排查记录）。分批后单 tick 的 HIDDEN 转换量很小，
     * {@code chunksToUnload} 不会出现"大起大落"，自然避开那次缩容。
     *
     * <p>取值权衡：评估副本通常 16~256 个区块。取 32 时最多 8 tick（0.4 秒）撤完，
     * 对 CLEANING 阶段的观感无影响，但已足以把单 tick 的集合变动压到安全区间。</p>
     */
    private static final int UNLOAD_CHUNKS_PER_TICK = 32;

    private EvaluationTicketManager() {
    }

    static void add(ServerLevel target, EvaluationManifest manifest) {
        addAtDistance(target, manifest, BLOCK_TICKING_DISTANCE);
    }

    /**
     * 分批撤票游标（0.5.5）：一次 {@link RemoveCursor#advance} 只推进
     * {@link #UNLOAD_CHUNKS_PER_TICK} 个区块。
     *
     * <p>调用方把它挂在运行时状态里，每 tick 调一次，返回 {@code false} 表示还没撤完，
     * 必须先 return 等下一 tick，<b>不要</b>继续往下走卸载/清理流程——否则会和仍在进行的
     * 区块降级打架。</p>
     *
     * <p>可重入安全：只是递增下标，不持有任何迭代器。</p>
     */
    static final class RemoveCursor {
        private int chunkIndex;
        /** 当前正在撤的是第几种 distance：0 = BLOCK_TICKING，1 = ENTITY_TICKING。 */
        private int distanceIndex;
        /** 两种 distance 都撤完。 */
        private boolean done;

        /**
         * 推进一批。返回 {@code true} 表示两种 distance 都撤完了。
         *
         * <p>每次调用<b>只处理一种 distance 的一批区块</b>，处理后立即返回，把剩余工作留给下一 tick。
         * 这样单 tick 内产生的 HIDDEN 转换量不超过 {@link #UNLOAD_CHUNKS_PER_TICK} 个。</p>
         *
         * @param unforceChunks 全部撤完后是否顺带清 forced（只在真正撤完那一 tick 做一次）
         */
        boolean advance(ServerLevel target, EvaluationManifest manifest, boolean unforceChunks) {
            if (done) {
                return true;
            }
            List<EvaluationManifest.ChunkRecord> chunks = manifest.chunks();
            int total = chunks.size();

            // 空清单：直接算撤完，避免死循环。
            if (total == 0) {
                finish(target, manifest, unforceChunks);
                return true;
            }

            int end = Math.min(chunkIndex + UNLOAD_CHUNKS_PER_TICK, total);
            int distance = distanceIndex == 0 ? BLOCK_TICKING_DISTANCE : ENTITY_TICKING_DISTANCE;
            for (int i = chunkIndex; i < end; i++) {
                target.getChunkSource().removeRegionTicket(EVALUATION_TICKET,
                        chunks.get(i).chunkPos(), distance, manifest.sessionId(), true);
            }
            chunkIndex = end;

            if (chunkIndex < total) {
                // 本 distance 还没撤完，下一 tick 继续
                return false;
            }
            // 本 distance 撤完，切到下一种；两种都完了才收尾
            chunkIndex = 0;
            distanceIndex++;
            if (distanceIndex >= 2) {
                finish(target, manifest, unforceChunks);
                return true;
            }
            return false;
        }

        private void finish(ServerLevel target, EvaluationManifest manifest, boolean unforceChunks) {
            done = true;
            if (unforceChunks) {
                setChunksForced(target, manifest, false);
            }
        }

        /** 是否已全部撤完（供幂等短路）。 */
        boolean finished() {
            return done;
        }
    }

    static void switchToEntityTicking(ServerLevel target, EvaluationManifest manifest) {
        removeAtDistance(target, manifest, BLOCK_TICKING_DISTANCE);
        addAtDistance(target, manifest, ENTITY_TICKING_DISTANCE);
    }

    static void switchToBlockTicking(ServerLevel target, EvaluationManifest manifest) {
        removeAtDistance(target, manifest, ENTITY_TICKING_DISTANCE);
        addAtDistance(target, manifest, BLOCK_TICKING_DISTANCE);
    }

    private static void addAtDistance(ServerLevel target, EvaluationManifest manifest, int distance) {
        for (EvaluationManifest.ChunkRecord chunk : manifest.chunks()) {
            // forceTicks=true：无玩家维度上实体/方块也必须真正 tick（否则评估期副本不运行，
            // PLAYER ticket 才驱动——P6 测试的产出正是玩家进入副本时产生的）
            target.getChunkSource().addRegionTicket(EVALUATION_TICKET, chunk.chunkPos(),
                    distance, manifest.sessionId(), true);
        }
    }

    private static void removeAtDistance(ServerLevel target, EvaluationManifest manifest, int distance) {
        for (EvaluationManifest.ChunkRecord chunk : manifest.chunks()) {
            target.getChunkSource().removeRegionTicket(EVALUATION_TICKET, chunk.chunkPos(),
                    distance, manifest.sessionId(), true);
        }
    }

    static boolean allReady(ServerLevel target, EvaluationManifest manifest) {
        return allReadyAt(target, manifest, FullChunkStatus.BLOCK_TICKING);
    }

    static boolean allEntityTickingReady(ServerLevel target, EvaluationManifest manifest) {
        return allReadyAt(target, manifest, FullChunkStatus.ENTITY_TICKING);
    }

    /**
     * 用 vanilla forced chunk 保持无玩家维度的实体持续 tick：
     * ServerLevel.tick 的实体门控为 players 非空 || hasForcedChunks || emptyTime&lt;300，
     * eval_world 没有玩家，必须通过 setChunkForced（写入 "chunks" SavedData，
     * FORCED ticket 等级 = ENTITY_TICKING）模拟"有玩家加载"。
     */
    static void setChunksForced(ServerLevel target, EvaluationManifest manifest, boolean add) {
        for (EvaluationManifest.ChunkRecord chunk : manifest.chunks()) {
            target.setChunkForced(chunk.chunkPos().x, chunk.chunkPos().z, add);
        }
        if (add) {
            CreateCMPOR.LOGGER.info("评估会话 {} 已强制加载 {} 个副本区块（模拟玩家 tick）",
                    manifest.sessionId(), manifest.chunks().size());
        }
    }

    private static boolean allReadyAt(ServerLevel target, EvaluationManifest manifest,
                                      FullChunkStatus status) {
        return manifest.chunks().stream().allMatch(chunk -> {
            var holder = target.getChunkSource().chunkMap
                    .getVisibleChunkIfPresent(chunk.chunkPos().toLong());
            return holder != null
                    && holder.getFullStatus().isOrAfter(status)
                    && holder.getTickingChunk() != null;
        });
    }

    static void logPending(ServerLevel target, EvaluationManifest manifest) {
        StringBuilder states = new StringBuilder();
        for (EvaluationManifest.ChunkRecord chunk : manifest.chunks()) {
            var holder = target.getChunkSource().chunkMap
                    .getVisibleChunkIfPresent(chunk.chunkPos().toLong());
            if (!states.isEmpty()) {
                states.append(", ");
            }
            states.append(chunk.chunkPos()).append('=');
            if (holder == null) {
                states.append("no-holder");
            } else {
                states.append("ticket").append(holder.getTicketLevel())
                        .append('/').append(holder.getFullStatus())
                        .append(holder.getTickingChunk() != null ? "/ticking" : "/no-ticking");
            }
        }
        CreateCMPOR.LOGGER.info("评估会话 {} 等待副本加载：{}", manifest.sessionId(), states);
    }
}
