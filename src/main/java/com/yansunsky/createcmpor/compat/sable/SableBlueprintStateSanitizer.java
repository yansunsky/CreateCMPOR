package com.yansunsky.createcmpor.compat.sable;

import com.yansunsky.createcmpor.CreateCMPOR;
import com.yansunsky.createcmpor.block.BaseIOBlock;
import dev.rew1nd.sableschematicapi.api.blueprint.BlueprintPlaceSession;
import dev.rew1nd.sableschematicapi.api.blueprint.BlueprintPlacedBlock;
import dev.rew1nd.sableschematicapi.api.blueprint.SableBlueprintEvent;
import dev.rew1nd.sableschematicapi.api.blueprint.SableBlueprintEventRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * Sable 蓝图<b>落地后</b>的方块状态净化：把本模组方块的激活态强制改回未激活。
 *
 * <p><b>为什么需要（这是 NBT 白名单覆盖不到的一面）</b>：
 * 本模组的"激活态"是<b>方块状态</b>而非方块实体 NBT ——
 * 应力方块用 {@code ACTIVE = BlockStateProperties.POWERED}，
 * IO 类方块用 {@code BaseIOBlock.ACTIVE = BooleanProperty.create("active")}。
 * 方块状态随调色板被蓝图<b>逐字复制</b>，而
 * {@link SableBlueprintGuard} 的 {@code save()} / {@code beforeLoadBlockEntity()}
 * 只管方块实体 NBT，<b>碰不到方块状态</b>。
 * Sable 侧全项目<b>没有任何方块状态过滤 API</b>（导出时 {@code blockPalette.add(state)} 原样存），
 * 而 Create 的 {@code SchematicStateFilterRegistry}（{@code BlueprintSafety} 已为应力方块注册
 * {@code ACTIVE→false}）在 Sable 路径上<b>完全不被调用</b>。</p>
 *
 * <p><b>后果</b>：评估进行中（{@code activateIoBlocks} 会把 ACTIVE 置 true）
 * 或开启 {@code devManualActivation} 手动激活后导出，打印出来的应力输入方块就是
 * {@code ACTIVE=true} 的<b>免费应力源</b>（容量 16384 SU 已注册，转速可调至 256 RPM）；
 * IO 方块同理会让复制体绕过 roomCode 门槛直接参与能力暴露。</p>
 *
 * <p><b>为什么用全局事件而不是 mapper 钩子</b>：{@link SableBlueprintGuard#save} 返回 {@code null}
 * ⇒ 蓝图里该方块 {@code hasBlockEntityData() == false}
 * ⇒ 两个 placer 都不会调用 {@code loadBlockEntity}
 * ⇒ {@code beforeLoadBlockEntity} / {@code afterLoadBlockEntity} <b>根本不会触发</b>
 * （已对照 {@code SableBlueprintPlacer:139} 与 {@code SableBlueprintIncrementalPlacer:526} 核实）。
 * 因此必须挂在会话级事件 {@link SableBlueprintEvent#onPlaceAfterBlocks} 上
 * —— 该事件在方块状态写入、方块实体加载、方块通知之后触发，两个 placer 都会调用。</p>
 *
 * <p><b>与 NBT 门控的分工（两层互为兜底）</b>：
 * 本类负责"观感与状态正确性"（复制体显示为未激活）；
 * 真正的<b>发电安全</b>由 {@code StressInputBlockEntity#getGeneratedSpeed()} 的
 * {@code roomCode} 门控保证 —— 即便本类因故没跑到，复制体也不会发电。</p>
 */
public final class SableBlueprintStateSanitizer implements SableBlueprintEvent {

    private static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(CreateCMPOR.MOD_ID, "blueprint_state_sanitizer");

    private static final SableBlueprintStateSanitizer INSTANCE = new SableBlueprintStateSanitizer();

    private SableBlueprintStateSanitizer() {
    }

    /** 由 {@code SableBlueprintGuard} 在确认 Sable 已加载后调用。 */
    public static void register() {
        SableBlueprintEventRegistry.register(INSTANCE);
    }

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void onPlaceAfterBlocks(BlueprintPlaceSession session, CompoundTag data) {
        final ServerLevel level = session.level();
        int cleared = 0;

        for (final BlueprintPlacedBlock placed : session.placedBlocks().blocks()) {
            final BlockPos pos = placed.storagePos();

            // 读【实时】状态而非记录状态：放置是分步进行的，其间可能被其它逻辑改动；
            // 实时读取可避免用陈旧状态覆盖世界（也顺带跳过已被替换掉的方块）。
            final BlockState state = level.getBlockState(pos);

            // 应力方块：ACTIVE = BlockStateProperties.POWERED
            // IO 类方块（input/output/parallel_input/io_extension）：ACTIVE = BooleanProperty("active")
            final BooleanProperty active;
            if (state.getBlock() instanceof BaseIOBlock) {
                active = BaseIOBlock.ACTIVE;
            } else if (state.getBlock() instanceof com.yansunsky.createcmpor.block.StressInputBlock) {
                active = com.yansunsky.createcmpor.block.StressInputBlock.ACTIVE;
            } else if (state.getBlock() instanceof com.yansunsky.createcmpor.block.StressOutputBlock) {
                active = com.yansunsky.createcmpor.block.StressOutputBlock.ACTIVE;
            } else {
                continue;
            }

            if (!state.hasProperty(active) || !state.getValue(active)) {
                continue;
            }

            // UPDATE_ALL（BLOCK|CLIENTS）：必须通知客户端，否则复制体在客户端仍显示为激活态。
            level.setBlock(pos, state.setValue(active, false), Block.UPDATE_ALL);
            cleared++;
        }

        if (cleared > 0) {
            CreateCMPOR.LOGGER.info("[蓝图安全] Sable 蓝图落地：已把 {} 个本模组方块的激活态改回未激活", cleared);
        }
    }
}
