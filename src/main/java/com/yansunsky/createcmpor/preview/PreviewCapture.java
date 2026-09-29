package com.yansunsky.createcmpor.preview;

import com.yansunsky.createcmpor.CreateCMPOR;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务端侧「房间产线 → 微缩预览快照」采集器。
 *
 * <p>只在评估固化那一刻调用一次（此后源房间已卸载，无法回查）。采集流程：
 * <ol>
 *     <li>把房间内界 {@code innerBounds} 对齐成方块网格；</li>
 *     <li>若网格体积超过配置上限，按整数步长做<b>盒式降采样</b>（每一组取组内出现次数最多的非空气方块，
 *         而不是简单抽样——抽样会把机器整块漏掉）；</li>
 *     <li>逐格读方块状态，跳过空气，建调色板；</li>
 *     <li>把网格裁剪到非空气方块的包围盒（让微缩内容尽量填满展示罩），</li>
 * </ol>
 *
 * <p>任何一步失败（无房间、无副本、调色板超限、空房间、尺寸非法）都返回 {@code null}——
 * 预览是纯装饰，采集失败绝不允许影响固化的正确性。
 */
public final class PreviewCapture {

    /** 降采样步长上限，防止病态配置导致死循环。 */
    private static final int MAX_STEP = 32;

    /** 源网格体积硬上限（45³≈9.1 万，留足余量），超过直接放弃采集。 */
    private static final long MAX_SOURCE_VOLUME = 1_000_000L;

    private PreviewCapture() {
    }

    /**
     * 从 {@code level} 的 {@code innerBounds} 区域采集预览快照。
     *
     * @param maxVolume 快照网格体积上限（降采样后不得超过）
     * @return 快照；无内容或超限时返回 {@code null}
     */
    public static PreviewSnapshot capture(ServerLevel level, AABB innerBounds, int maxVolume) {
        BlockPos min = BlockPos.containing(innerBounds.minX, innerBounds.minY, innerBounds.minZ);
        BlockPos maxExclusive = BlockPos.containing(innerBounds.maxX, innerBounds.maxY, innerBounds.maxZ);
        int sourceWidth = maxExclusive.getX() - min.getX();
        int sourceHeight = maxExclusive.getY() - min.getY();
        int sourceDepth = maxExclusive.getZ() - min.getZ();
        if (sourceWidth <= 0 || sourceHeight <= 0 || sourceDepth <= 0) {
            CreateCMPOR.LOGGER.warn("[预览] 房间内界非法：{}x{}x{}，跳过预览", sourceWidth, sourceHeight, sourceDepth);
            return null;
        }
        long sourceVolume = (long) sourceWidth * sourceHeight * sourceDepth;
        if (sourceVolume > MAX_SOURCE_VOLUME) {
            CreateCMPOR.LOGGER.warn("[预览] 房间体积 {} 超过采集上限 {}，跳过预览", sourceVolume, MAX_SOURCE_VOLUME);
            return null;
        }

        int step = 1;
        while (step < MAX_STEP) {
            long sampled = (long) ceilDiv(sourceWidth, step) * ceilDiv(sourceHeight, step)
                    * ceilDiv(sourceDepth, step);
            if (sampled <= maxVolume) {
                break;
            }
            step++;
        }

        int width = ceilDiv(sourceWidth, step);
        int height = ceilDiv(sourceHeight, step);
        int depth = ceilDiv(sourceDepth, step);

        Map<BlockState, Integer> indexByState = new HashMap<>();
        List<BlockState> palette = new ArrayList<>();
        palette.add(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        byte[] cells = new byte[width * height * depth];

        Map<BlockState, int[]> histogram = step > 1 ? new HashMap<>() : null;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int z = 0; z < depth; z++) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    BlockState state = step == 1
                            ? level.getBlockState(cursor.set(min.getX() + x, min.getY() + y, min.getZ() + z))
                            : dominantState(level, min, x, y, z, step, sourceWidth, sourceHeight, sourceDepth,
                                    histogram, cursor);
                    if (state == null || state.isAir()) {
                        continue;
                    }
                    Integer index = indexByState.get(state);
                    if (index == null) {
                        if (palette.size() > PreviewSnapshot.MAX_PALETTE) {
                            CreateCMPOR.LOGGER.warn("[预览] 调色板超过 {} 种方块，跳过预览（房间方块种类过多）",
                                    PreviewSnapshot.MAX_PALETTE);
                            return null;
                        }
                        palette.add(state);
                        index = palette.size() - 1;
                        indexByState.put(state, index);
                    }
                    cells[x + width * (y + height * z)] = index.byteValue();
                }
            }
        }

        PreviewSnapshot cropped = crop(width, height, depth, palette, cells);
        if (cropped == null) {
            CreateCMPOR.LOGGER.info("[预览] 房间内没有可用方块，跳过预览");
            return null;
        }
        CreateCMPOR.LOGGER.info("[预览] 采集完成：源 {}x{}x{}（步长 {}）→ 快照 {}x{}x{}，非空气 {} 格，调色板 {} 种，约 {} 字节",
                sourceWidth, sourceHeight, sourceDepth, step,
                cropped.width(), cropped.height(), cropped.depth(), cropped.nonAirCount(),
                cropped.paletteSize(), cropped.encodedSize());
        return cropped;
    }

    /**
     * 盒式降采样：取 {@code step³} 组内出现次数最多的非空气方块状态（全空气则返回 null）。
     * 这样整台机器只要在组里占多数就能被保留，比"取每 step 格之一"可靠得多。
     */
    private static BlockState dominantState(ServerLevel level, BlockPos min, int x, int y, int z, int step,
                                            int sourceWidth, int sourceHeight, int sourceDepth,
                                            Map<BlockState, int[]> histogram, BlockPos.MutableBlockPos cursor) {
        histogram.clear();
        for (int dz = 0; dz < step; dz++) {
            int sz = z * step + dz;
            if (sz >= sourceDepth) {
                break;
            }
            for (int dy = 0; dy < step; dy++) {
                int sy = y * step + dy;
                if (sy >= sourceHeight) {
                    break;
                }
                for (int dx = 0; dx < step; dx++) {
                    int sx = x * step + dx;
                    if (sx >= sourceWidth) {
                        break;
                    }
                    BlockState state = level.getBlockState(cursor.set(min.getX() + sx, min.getY() + sy, min.getZ() + sz));
                    if (state.isAir()) {
                        continue;
                    }
                    histogram.computeIfAbsent(state, ignored -> new int[1])[0]++;
                }
            }
        }
        BlockState best = null;
        int bestCount = 0;
        for (Map.Entry<BlockState, int[]> entry : histogram.entrySet()) {
            if (entry.getValue()[0] > bestCount) {
                best = entry.getKey();
                bestCount = entry.getValue()[0];
            }
        }
        return best;
    }

    /** 裁剪到非空气方块的包围盒；全空气返回 {@code null}。 */
    private static PreviewSnapshot crop(int width, int height, int depth, List<BlockState> palette, byte[] cells) {
        int minX = width;
        int minY = height;
        int minZ = depth;
        int maxX = -1;
        int maxY = -1;
        int maxZ = -1;
        for (int z = 0; z < depth; z++) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (cells[x + width * (y + height * z)] == 0) {
                        continue;
                    }
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    minZ = Math.min(minZ, z);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                    maxZ = Math.max(maxZ, z);
                }
            }
        }
        if (maxX < 0) {
            return null;
        }
        int newWidth = maxX - minX + 1;
        int newHeight = maxY - minY + 1;
        int newDepth = maxZ - minZ + 1;
        if (newWidth == width && newHeight == height && newDepth == depth) {
            return new PreviewSnapshot(width, height, depth, palette, cells);
        }
        byte[] cropped = new byte[newWidth * newHeight * newDepth];
        for (int z = 0; z < newDepth; z++) {
            for (int y = 0; y < newHeight; y++) {
                for (int x = 0; x < newWidth; x++) {
                    cropped[x + newWidth * (y + newHeight * z)] =
                            cells[(x + minX) + width * ((y + minY) + height * (z + minZ))];
                }
            }
        }
        return new PreviewSnapshot(newWidth, newHeight, newDepth, palette, cropped);
    }

    private static int ceilDiv(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    /** 供调试命令使用：把调色板前若干项格式化成可读文本。 */
    public static List<String> describePalette(PreviewSnapshot snapshot, int limit) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < snapshot.volume(); i++) {
            BlockState state = snapshot.stateAt(i);
            if (state == null) {
                continue;
            }
            counts.merge(state.toString(), 1, Integer::sum);
        }
        List<String> lines = new ArrayList<>();
        counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(limit)
                .forEach(entry -> lines.add(entry.getKey() + " ×" + entry.getValue()));
        return lines;
    }
}
