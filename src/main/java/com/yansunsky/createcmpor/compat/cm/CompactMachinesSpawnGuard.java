package com.yansunsky.createcmpor.compat.cm;

import com.yansunsky.createcmpor.CreateCMPOR;
import dev.compactmods.machines.api.CompactMachines;
import dev.compactmods.machines.api.dimension.CompactDimension;
import dev.compactmods.machines.api.room.registration.IRoomRegistrar;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * CompactMachines 7.0.81 存档期 <b>default_spawn 为 null</b> 的崩溃兜底。
 *
 * <h2>上游 bug（7.0.81，已用字节码 + 离线复现双向确证）</h2>
 * <p>{@code SpawnManager.CODEC} 把三个字段全部声明为<b>必需</b>的 {@code fieldOf}：</p>
 * <pre>
 *   Codec.STRING.fieldOf("roomCode")
 *   PLAYER_SPAWNS_CODEC.fieldOf("player_spawns")
 *   RoomSpawn.CODEC.fieldOf("default_spawn")      // ← 必需，不是 optionalFieldOf
 * </pre>
 * <p>但 {@code CompactMachines.newRoom} 只在<b>模板自带 floor</b> 时才写这个字段：</p>
 * <pre>
 *   template.optionalFloor().ifPresent(floorState -> {
 *       spawnManager.setDefaultSpawn(boundaries().defaultSpawn().add(0, 1, 0), Vec2.ZERO);
 *       ...
 *   });
 * </pre>
 * <p>而 7.0.81 内置的 8 个模板里<b>只有 farming 带 floor</b>
 * （tiny / small / normal / large / giant / colossal / soaryn 全都没有）。
 * 于是用这些模板建的房间，{@code SpawnManager.defaultSpawn} 保持 null，直到有人调用
 * {@code spawns()} 才懒初始化。</p>
 *
 * <p>唯一会懒初始化它的 {@code spawns()} 只有两个调用点：
 * {@code RoomHelper.teleportPlayerIntoRoom}（<b>仅在进入成功分支</b>）与本模组的
 * {@code EvalWorldCommands.enterOriginal}。玩家一旦没成功进房
 * （例如 {@code RoomEntryResult.FAILED_TOO_FAR_DOWN} 提前 return），
 * 下一次 {@code LevelEvent.Save → CompactMachinesServer.saveAll → CMKeyedDataFileManager.save}
 * 就会在 {@code codec.encodeStart(...).getOrThrow()} 处抛 NPE 并<b>崩服</b>。</p>
 *
 * <h2>崩溃栈特征（用于快速认领）</h2>
 * <pre>
 *   NullPointerException
 *     at RecordCodecBuilder$Instance.lambda$ap2$4(RecordCodecBuilder.java:215)
 *     at RecordCodecBuilder$Instance$6.encode(RecordCodecBuilder.java:297)
 *     at CodecNbtFunctions.store(CodecNbtFunctions.java:31)
 *     at CMKeyedDataFileManager.save(CMKeyedDataFileManager.java:62→65)
 *     at RoomSpawnManagers.save(RoomSpawnManagers.java:23)
 *     at CompactMachinesServer.saveAll(CompactMachinesServer.java:61)
 * </pre>
 * <p><b>不是</b>我们模组的锅：栈里没有一帧 {@code createcmpor}。
 * 但既然爆炸点在存档路径上、且每条分支都会把整个服务器拖下去，就由本模组兜住。</p>
 *
 * <h2>兜底策略</h2>
 * <p>在 {@code compact_world} 存档<b>之前</b>（{@code EventPriority.HIGHEST}，
 * 上游自己的 {@code levelSaved} 挂在 {@code LOW}），遍历所有已注册房间，
 * 对每个房间调用一次 {@code spawns()} —— 这一个调用就会把
 * {@code defaultSpawn} 从 null 懒初始化成 {@code boundaries().defaultSpawn()}。</p>
 *
 * <p>为什么选 {@code spawns()} 而不是直接 {@code setDefaultSpawn(...)}：
 * 前者走的是上游自己的懒初始化路径，语义与"玩家正常进过一次房间"完全一致，
 * 不会引入对 {@code boundaries().defaultSpawn()} 坐标含义的重复解释；
 * 同时它不依赖 {@code SpawnManager} 这个实现类，只用到 api 包的
 * {@link dev.compactmods.machines.api.room.spawn.IRoomSpawnManager}。</p>
 *
 * <h2>铁律：兜底代码自己绝不能成为新的崩点</h2>
 * <p>逐房间 try/catch，任何异常只记 WARN 并继续下一个；本方法整体也不吞掉
 * 存档流程（{@code LevelEvent.Save} 是通知型事件，我们不做 cancel）。</p>
 */
public final class CompactMachinesSpawnGuard {

    /** 首次成功补写的房间数只打印一次，避免每 5 分钟刷一行日志。 */
    private static boolean reported = false;

    private CompactMachinesSpawnGuard() {
    }

    public static void onLevelSave(LevelEvent.Save event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        // 上游 saveAll 本身也只在 compact_world 存档时触发，这里保持一致以免无谓遍历。
        if (!CompactDimension.isLevelCompact(level)) {
            return;
        }
        MinecraftServer server = level.getServer();
        if (server == null) {
            return;
        }

        int repaired = 0;
        try {
            IRoomRegistrar registrar = CompactMachines.roomRegistrar(server);
            for (String code : registrar.allRoomCodes().toList()) {
                try {
                    // 一个调用即完成懒初始化：defaultSpawn 为 null 时会被填上。
                    CompactMachines.spawnManagers().get(code).spawns();
                    repaired++;
                } catch (Throwable perRoom) {
                    // 单个房间失败（例如房间记录已损坏）不应影响其余房间与存档本身。
                    CreateCMPOR.LOGGER.warn(
                            "[CM兼容] 存档前补写房间 {} 的 default_spawn 失败，已跳过（不阻塞存档）",
                            code, perRoom);
                }
            }
        } catch (Throwable fatal) {
            // 连 roomRegistrar 都拿不到（未安装 / API 变化）：记录后放行，绝不阻塞存档。
            CreateCMPOR.LOGGER.warn("[CM兼容] 存档前 default_spawn 兜底整体失败，已放行", fatal);
            return;
        }

        if (!reported && repaired > 0) {
            reported = true;
            CreateCMPOR.LOGGER.info(
                    "[CM兼容] 已确保 {} 个 CompactMachines 房间的 default_spawn 非空"
                            + "（上游 7.0.81 无 floor 模板不写该字段，null 会在存档时 NPE 崩服）",
                    repaired);
        }
    }
}
