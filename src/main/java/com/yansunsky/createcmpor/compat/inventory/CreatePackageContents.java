package com.yansunsky.createcmpor.compat.inventory;

import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.logistics.box.PackageItem;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

import java.util.ArrayList;
import java.util.List;

/**
 * Create 包裹（package）的内容读取通道：把 {@code create:package_contents} 里的物品展开进审计快照。
 *
 * <h2>为什么必须有这个通道（0.5.6 修复的物品复制漏洞）</h2>
 * <p>机械动力的打包机可以把物品打包成「包裹」物品，包裹内容存在<b>自己的数据组件</b>里：
 * {@code AllDataComponents.PACKAGE_CONTENTS}（类型 {@link ItemContainerContents}，
 * 见 {@code AllDataComponents.java:301-305}），读取入口是 {@link PackageItem#getContents(ItemStack)}。</p>
 *
 * <p>{@link ContainerItemExpander} 原本只展开原版的 {@code DataComponents.CONTAINER} 与
 * {@code BUNDLE_CONTENTS}，<b>看不到</b> {@code PACKAGE_CONTENTS}。于是这条漏洞成立：</p>
 * <pre>
 *   玩家预存大量「装着钻石的包裹」→ 评估中拆包 → 把钻石输出
 *   ⇒ 扫描里包裹物品只是"某个签名少了一个"，钻石却是全额 net&gt;0 的产物
 *   ⇒ 三扫描净平衡算不出输入，固化出无损耗的产出机器。
 * </pre>
 *
 * <h2>为什么放在本类而不是塞进 ContainerItemExpander</h2>
 * <p>这里的直接引用会让 Create 的 {@code PackageItem} 在类加载期被强解析。
 * 本类只在 {@link ContainerItemExpander#expand} 真正走到展开路径时才被加载，
 * 万一将来某个 Create 版本把类移走，代价只是这一个通道失效，
 * 而不是整个库存扫描 {@code NoClassDefFoundError}（同 {@code CreateInventoryClearer} 的既有做法）。</p>
 *
 * <h2>外层物品仍然照常计入</h2>
 * <p>本通道只<b>追加</b>内部物品，包裹本身由调用方先按原有逻辑计入（与潜影盒/AE2 cell 同口径）。
 * 这样包裹被消耗或产出时仍然能被守恒审计捕获，而包裹<b>内部</b>的东西也不会再凭空出现。</p>
 */
final class CreatePackageContents {

    private CreatePackageContents() {
    }

    /**
     * 读取包裹内容物。
     *
     * @param stack      外层物品（包裹）
     * @param registries 注册表访问器（保留给将来需要的解析路径）
     * @return {@link ContainerItemExpander.ReadResult#notApplicable()} = 不是包裹；
     * 否则返回内容物（空内容也是一次成功的读取）
     */
    static ContainerItemExpander.ReadResult read(ItemStack stack, HolderLookup.Provider registries) {
        if (stack == null || stack.isEmpty() || !PackageItem.isPackage(stack)) {
            return ContainerItemExpander.ReadResult.notApplicable();
        }
        try {
            ItemContainerContents contents = stack.get(AllDataComponents.PACKAGE_CONTENTS);
            if (contents == null) {
                // 空包裹：成功的读取，内容物为空。不能返回 notApplicable ——
                // 否则上层会继续走别的通道，而"空包裹"这个事实本身就是有效信息。
                return ContainerItemExpander.ReadResult.success("create:package", List.of());
            }
            List<ContainerItemExpander.ContainedStack> out = new ArrayList<>();
            for (int slot = 0; slot < contents.getSlots(); slot++) {
                ItemStack child = contents.getStackInSlot(slot);
                if (child.isEmpty()) {
                    continue;
                }
                // count 语义：ItemContainerContents 的一槽就是"那一摞的真实数量"，
                // 与 AE2 cell / 精妙背包通道一致，直接按 child.getCount() 上报。
                out.add(new ContainerItemExpander.ContainedStack(child.getCount(), child,
                        "create:package_contents[slot=" + slot + "]"));
            }
            return ContainerItemExpander.ReadResult.success("create:package", out);
        } catch (RuntimeException exception) {
            // 第三方在 PACKAGE_CONTENTS 上挂了异常组件：记一条并"保留包裹本身统计"，
            // 绝不让一个坏数据打崩整场评估（与 ContainerItemExpander 的既有失败语义一致）
            return ContainerItemExpander.ReadResult.failed("create:package",
                    exception.getClass().getSimpleName());
        }
    }
}
