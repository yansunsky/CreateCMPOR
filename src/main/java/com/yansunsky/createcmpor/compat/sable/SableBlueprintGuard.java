package com.yansunsky.createcmpor.compat.sable;

import com.yansunsky.createcmpor.CreateCMPOR;
import com.yansunsky.createcmpor.init.ModBlocks;
import dev.rew1nd.sableschematicapi.api.blueprint.BlueprintBlockPlaceContext;
import dev.rew1nd.sableschematicapi.api.blueprint.BlueprintBlockSaveContext;
import dev.rew1nd.sableschematicapi.api.blueprint.SableBlueprintBlockMapper;
import dev.rew1nd.sableschematicapi.api.blueprint.SableBlueprintMapperRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

/**
 * 第三方蓝图模组（Sable Photomancy / modId {@code sable_schematic_api}）的蓝图复制防护。
 *
 * <p><b>为什么需要</b>：该模组导出蓝图走 vanilla 全量路径
 * {@code BlockEntity#saveWithFullMetadata}（见其 {@code BlueprintBlockSaveContext#saveDefaultBlockEntityTag}），
 * <b>不经过</b> Create 的 {@code SafeNbtWriterRegistry} / {@code PartialSafeNBT} 过滤。它自带的那层
 * "SANITIZED" 清洗（{@code BlueprintNbtSanitizers.FallbackSanitizer}）只按 <b>固定英文键名</b>删除：
 * {@code Items / Inventory / Fluid / Fluids / Tank / Tanks / Energy / ForgeCaps / LootTable /
 * LootTableSeed / Lock}；而本模组用的是 {@code room_code} / {@code restore_machine} /
 * {@code input_items} 这类小写下划线键，<b>一个都匹配不上</b> ⇒ 清洗实际是 no-op，等价于 FULL。</p>
 *
 * <p><b>后果</b>：工厂方块被蓝图复制后是<b>活</b>的（{@code installed=true}），并携带 IO 存量、
 * 以及 {@code restore_machine} 里原 CompactMachines 机器的完整 BE NBT ——
 * 玩家手持启动棒即可把复制体还原成一台完整的原机器，绕过评估流程实现整机 + 产线复制。</p>
 *
 * <p><b>Create 为什么没这个问题</b>：Create 蓝图炮走 {@code SmartBlockEntity#writeSafe}，
 * 其 {@code super.saveAdditional} 在字节码层是 {@code invokespecial} 直连
 * {@code BlockEntity#saveAdditional}（只写 NeoForgeData + attachments），<b>不回调子类 {@code write()}</b>，
 * 因此工厂的所有自定义键全部丢弃，打印出来是 {@code installed=false} 的惰性空壳。</p>
 *
 * <p><b>两道钩子</b>：
 * <ul>
 *     <li>{@link #save}：导出时返回 {@code null} ⇒ 蓝图文件里<b>根本不写 BE 载荷</b>
 *         （上游 javadoc 明示："Returning null means the block is still saved, but no block entity
 *         payload is stored for it"）。数据不进文件，则<b>所有</b>打印路径
 *         （蓝图炮 / 投影仪 / 蓝图工具 / 相机贴图）都无从泄漏。</li>
 *     <li>{@link #beforeLoadBlockEntity}：粘贴时二次白名单剔除，兜住<b>修复前已生成的旧蓝图文件</b>
 *         （那些文件里载荷已经写死）。两个 placer（{@code SableBlueprintPlacer} 与
 *         {@code SableBlueprintIncrementalPlacer}）都会调用该钩子，故对全部打印路径生效。</li>
 * </ul>
 *
 * <p><b>类加载安全</b>：本类实现 Sable 的接口，只有对方模组存在时才能被加载。调用方
 * （{@code BlueprintSafety}）必须先用 {@code ModList#isLoaded} 判断，并把引用放进<b>独立方法</b>里
 * （常量池解析惰性），否则会在未安装 Sable 时把本模组一起拖崩。</p>
 */
public final class SableBlueprintGuard implements SableBlueprintBlockMapper {

    /** 该模组的 modId（注意：发行 jar 文件名叫 sable-photomancy，modId 却是这个）。 */
    public static final String SABLE_MOD_ID = "sable_schematic_api";

    /**
     * 粘贴时允许保留的"结构键"。
     *
     * <p>刻意用<b>白名单</b>而非黑名单：将来给方块加新 NBT 键时无需同步维护这里，
     * 不会因为漏加一个键而重新打开漏洞。</p>
     */
    private static final String[] STRUCTURAL_KEYS = {"id", "x", "y", "z"};

    private static final SableBlueprintGuard INSTANCE = new SableBlueprintGuard();

    private SableBlueprintGuard() {
    }

    /** 由 {@code BlueprintSafety} 在确认 Sable 已加载后调用。 */
    public static void register() {
        // 工厂：本类防护的首要目标 —— 会话数据 + IO 存量 + 原机器还原镜像 + 产线快照
        register(ModBlocks.FACTORY.get());
        // 应力方块：补齐 Create 侧已有、但会被 Sable 绕过的同名保证
        // （Create 用 SafeNbtWriterRegistry 把 RoomCode / 生成转速挡在蓝图外，Sable 不认那套注册表）
        register(ModBlocks.STRESS_INPUT.get());
        register(ModBlocks.STRESS_OUTPUT.get());

        // 方块状态层净化（NBT 白名单覆盖不到 ACTIVE 状态），见 SableBlueprintStateSanitizer
        SableBlueprintStateSanitizer.register();

        CreateCMPOR.LOGGER.info("[蓝图安全] 已注册 Sable 蓝图防护（NBT 白名单 + 落地状态净化）");
    }

    private static void register(Block block) {
        // 只按 Block 注册：上游 save 与 beforeLoadBlockEntity 的查找都会先取
        // context.state().getBlock()，按方块注册即可同时覆盖"导出 + 粘贴"两条路径。
        SableBlueprintMapperRegistry.register(block, INSTANCE);
    }

    /**
     * 导出时：返回 {@code null} ⇒ 蓝图里不存该方块的 BE 载荷。
     *
     * <p>一次切断 {@code room_code}（房间绑定，会污染 {@code FactoryIndexSavedData} 反查索引与
     * 多工厂组还原数量校验）、{@code installed}（决定工厂是否兑换）、IO 存量
     * （{@code input_items}/{@code output_items}/{@code input_fluids}/…）、
     * {@code restore_machine}（原机器完整 NBT）与 {@code preview}（产线快照）。</p>
     */
    @Override
    public @Nullable CompoundTag save(BlueprintBlockSaveContext context,
                                      @Nullable CompoundTag defaultTag) {
        return null;
    }

    /**
     * 粘贴时：白名单剔除，兜住修复前已生成的旧蓝图文件。
     *
     * <p>只留 {@code id}/{@code x}/{@code y}/{@code z}，其余全部移除；粘出来的方块实体退回字段
     * 默认值（工厂 {@code installed=false}，惰性、不兑换、启动棒无法还原）。</p>
     */
    @Override
    public void beforeLoadBlockEntity(BlueprintBlockPlaceContext context, CompoundTag tag) {
        // 先取键快照再删，避免边遍历边改
        for (String key : tag.getAllKeys().toArray(new String[0])) {
            if (!isStructural(key)) {
                tag.remove(key);
            }
        }
    }

    private static boolean isStructural(String key) {
        for (String structural : STRUCTURAL_KEYS) {
            if (structural.equals(key)) {
                return true;
            }
        }
        return false;
    }
}
