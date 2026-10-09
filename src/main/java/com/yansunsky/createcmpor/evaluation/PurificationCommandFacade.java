package com.yansunsky.createcmpor.evaluation;

import dev.compactmods.machines.api.CompactMachines;
import dev.compactmods.machines.api.dimension.CompactDimension;
import dev.compactmods.machines.api.room.RoomInstance;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

import java.util.Optional;

/**
 * {@code /ccmpor clean <roomCode> [execute]} 的实现。
 *
 * <p><b>为什么放在 evaluation 包里</b>：{@link EvaluationCleaner} 是包私有工具类，
 * 命令层（{@code command} 包）不该也不需要看见清理细节。这里只做"解析房间 → 干活 → 汇报"。</p>
 *
 * <p><b>目标是源房间</b>（compact_world 里玩家自己建的那间），因为"调试遗留物"本来就在那儿。
 * 评估开始时的净化走的是 {@code EvaluationScheduler}，作用于副本，两者共用同一个
 * {@link EvaluationCleaner} 与同一份配置。</p>
 *
 * <p><b>默认只报告、不动手</b>：删除物品不可逆，所以 {@code execute} 是显式可选项。</p>
 */
public final class PurificationCommandFacade {

    private PurificationCommandFacade() {
    }

    public static int cleanRoom(CommandSourceStack source, String roomCode, boolean execute) {
        Optional<RoomInstance> roomOptional = CompactMachines.room(source.getServer(), roomCode);
        if (roomOptional.isEmpty()) {
            source.sendFailure(Component.literal("找不到 CompactMachines 房间：" + roomCode));
            return 0;
        }
        ServerLevel level = source.getServer().getLevel(CompactDimension.LEVEL_KEY);
        if (level == null) {
            source.sendFailure(Component.literal("compact_world 维度未加载。"));
            return 0;
        }
        AABB bounds = roomOptional.get().boundaries().outerBounds();
        if (!chunksLoaded(level, bounds)) {
            source.sendFailure(Component.literal("房间区块未加载（先进入该房间或让玩家靠近），"
                    + "否则扫描会读到空数据、报告不可信。"));
            return 0;
        }

        EvaluationCleaner.Action action = execute
                ? EvaluationCleaner.Action.CLEAR_AND_RESTORE
                : EvaluationCleaner.Action.REPORT;
        EvaluationCleaner.Stats stats = EvaluationCleaner.clean(
                level, bounds, EvaluationCleaner.Policy.fromConfig(), action);

        String head = execute ? "已清理房间 " : "房间 ";
        source.sendSuccess(() -> Component.literal(head + roomCode + "：" + stats.summary()), false);
        if (!stats.excluded().isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    "  按当前配置策略跳过（不改动）：" + join(stats.excluded())), false);
        }
        if (stats.hasLeftover()) {
            source.sendSuccess(() -> Component.literal(
                    "  ⚠ 清不掉的残余物（净化评估会据此回退普通评估）：" + join(stats.leftover())), false);
        }
        if (!execute) {
            source.sendSuccess(() -> Component.literal(
                    "  这是只读报告，未改动任何方块；确认无误后加 execute 执行："
                            + "/ccmpor clean " + roomCode + " execute"), false);
        }
        return 1;
    }

    /** 8 个角落都加载过 = 整间房的方块都能读到。 */
    private static boolean chunksLoaded(ServerLevel level, AABB bounds) {
        int minX = (int) Math.floor(bounds.minX);
        int minZ = (int) Math.floor(bounds.minZ);
        int maxX = (int) Math.floor(bounds.maxX - 1.0E-5);
        int maxZ = (int) Math.floor(bounds.maxZ - 1.0E-5);
        return level.isLoaded(new net.minecraft.core.BlockPos(minX, 0, minZ))
                && level.isLoaded(new net.minecraft.core.BlockPos(minX, 0, maxZ))
                && level.isLoaded(new net.minecraft.core.BlockPos(maxX, 0, minZ))
                && level.isLoaded(new net.minecraft.core.BlockPos(maxX, 0, maxZ));
    }

    /** 列表过长时截断，避免一条命令把聊天刷爆。 */
    private static String join(java.util.List<String> entries) {
        int limit = 12;
        if (entries.size() <= limit) {
            return String.join("；", entries);
        }
        return String.join("；", entries.subList(0, limit)) + "；……（共 " + entries.size() + " 处）";
    }
}
