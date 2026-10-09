package com.yansunsky.createcmpor.evaluation;

import com.simibubi.create.content.logistics.depot.DepotBehaviour;
import com.simibubi.create.content.logistics.depot.EjectorBlockEntity;
import com.simibubi.create.content.logistics.packagePort.PackagePortBlockEntity;
import com.simibubi.create.content.logistics.tunnel.BrassTunnelBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/**
 * Create 里"走能力清不掉"的容器残余物专用通道。
 *
 * <p><b>为什么单独一个类</b>：这里的直接引用会让 Create 的这几个类在类加载期被强解析。
 * 本类只在 {@link EvaluationCleaner} 真正走到"清理"路径时才被加载，因此万一将来某个
 * Create 版本把类移走，代价只是这一个方法失效，而不是整个净化路径 {@code NoClassDefFoundError}。</p>
 *
 * <p><b>每个条目都是"能力提取静默失效"的已知缝隙</b>（依据：调研报告
 * {@code build/research/01-Create容器清理面.md}，结论均以
 * {@code libs/create-1.21.1-6.0.10-281-slim.jar} 字节码核实）：</p>
 * <ul>
 *   <li><b>黄铜隧道</b>：{@code getStackInSlot(0)} 返回 {@code stackToDistribute}，
 *       但 {@code extractItem} 转调下方传送带 ⇒ 提取静默返回空，槽里的物品清不掉。</li>
 *   <li><b>蛙港 / 邮箱</b>：能力包的是 {@code PackagePortAutomationInventoryWrapper}，
 *       {@code extractItem} 只在包裹地址匹配时才放行 ⇒ 带地址过滤的包裹清不掉。
 *       基类 {@code clearContent()} 清 18 槽库存且<b>不动</b> {@code addressFilter}。</li>
 *   <li><b>弹射器</b>：库存挂在 {@code DepotBehaviour} 上，jar 里没有 {@code clearContent()}
 *       （字段包私有，改走 public {@code getBehaviour(DepotBehaviour.TYPE)}）。</li>
 * </ul>
 *
 * <p><b>不碰过滤器</b>：这三个通道都不动各自的过滤数据（黄铜隧道的是分配逻辑，
 * 蛙港的是 {@code addressFilter}，弹射器只动 {@code DepotBehaviour} 的物品）。</p>
 */
final class CreateInventoryClearer {

    private CreateInventoryClearer() {
    }

    /** 报告中这一项会清掉多少件物品；0 = 没有残余物。 */
    static int count(ServerLevel level, BlockPos pos) {
        var blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof BrassTunnelBlockEntity tunnel) {
            return sizeOf(tunnel.getStackToDistribute());
        }
        if (blockEntity instanceof PackagePortBlockEntity packagePort) {
            return countInventory(packagePort);
        }
        if (blockEntity instanceof EjectorBlockEntity ejector) {
            DepotBehaviour depot = ejector.getBehaviour(DepotBehaviour.TYPE);
            return depot == null ? 0 : sizeOf(depot.getHeldItemStack());
        }
        return 0;
    }

    /**
     * 执行清理，返回清掉的物品件数。
     *
     * <p>只清物品：这几个缝隙的残余物都是物品，流体/能量由通用能力通道负责。</p>
     */
    static int clear(ServerLevel level, BlockPos pos) {
        var blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof BrassTunnelBlockEntity tunnel) {
            int count = sizeOf(tunnel.getStackToDistribute());
            if (count > 0) {
                tunnel.setStackToDistribute(ItemStack.EMPTY, null);
            }
            return count;
        }
        if (blockEntity instanceof PackagePortBlockEntity packagePort) {
            int count = countInventory(packagePort);
            if (count > 0) {
                packagePort.clearContent();
            }
            return count;
        }
        if (blockEntity instanceof EjectorBlockEntity ejector) {
            DepotBehaviour depot = ejector.getBehaviour(DepotBehaviour.TYPE);
            if (depot == null) {
                return 0;
            }
            int count = sizeOf(depot.getHeldItemStack());
            if (count > 0) {
                depot.clearContent();
            }
            return count;
        }
        return 0;
    }

    /** 只读统计：{@code inventory} 是 public 字段，报告模式直接读它，不走能力（能力可能被地址过滤挡住）。 */
    private static int countInventory(PackagePortBlockEntity packagePort) {
        if (packagePort.inventory == null) {
            return 0;
        }
        int total = 0;
        for (int slot = 0; slot < packagePort.inventory.getSlots(); slot++) {
            total += sizeOf(packagePort.inventory.getStackInSlot(slot));
        }
        return total;
    }

    private static int sizeOf(ItemStack stack) {
        return stack == null || stack.isEmpty() ? 0 : stack.getCount();
    }
}
