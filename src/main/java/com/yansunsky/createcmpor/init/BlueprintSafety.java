package com.yansunsky.createcmpor.init;

import com.simibubi.create.api.schematic.nbt.SafeNbtWriterRegistry;
import com.simibubi.create.api.schematic.state.SchematicStateFilterRegistry;
import com.yansunsky.createcmpor.block.StressInputBlock;
import com.yansunsky.createcmpor.block.StressOutputBlock;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;

/**
 * 蓝图（Schematic）打印安全处理：本模组方块不允许被蓝图复制出会话数据。
 *
 * <p><b>背景</b>：激活态（{@code ACTIVE=true}）的应力方块是"评估期临时供电"状态；
 * 若蓝图保存时原样复制，玩家可用蓝图大炮在非评估空间打印出已激活的廉价应力源。</p>
 *
 * <p><b>处理</b>：
 * <ul>
 *     <li>{@link SchematicStateFilterRegistry}：蓝图保存时把 {@code ACTIVE} 强制置 false。</li>
 *     <li>{@link SafeNbtWriterRegistry}：蓝图保存时只保留安全 NBT（排除 roomCode 等会话数据）。</li>
 *     <li>{@link #registerSableGuard()}：第三方蓝图模组（Sable Photomancy）不认上面两个 Create 注册表，
 *         需另走它的 mapper 注册表 —— 见 {@code SableBlueprintGuard} 的类注释。</li>
 * </ul>
 */
public final class BlueprintSafety {

    private BlueprintSafety() {
    }

    public static void register() {
        // 方块状态：激活态不进蓝图（打印出来一定是未激活）
        SchematicStateFilterRegistry.REGISTRY.register(ModBlocks.STRESS_INPUT.get(),
                (be, state) -> state.hasProperty(StressInputBlock.ACTIVE)
                        ? state.setValue(StressInputBlock.ACTIVE, false)
                        : state);
        SchematicStateFilterRegistry.REGISTRY.register(ModBlocks.STRESS_OUTPUT.get(),
                (be, state) -> state.hasProperty(StressOutputBlock.ACTIVE)
                        ? state.setValue(StressOutputBlock.ACTIVE, false)
                        : state);

        // BE NBT：只保留基础数据（roomCode/生成速度等不进蓝图）
        SafeNbtWriterRegistry.REGISTRY.register(com.yansunsky.createcmpor.init.ModBlockEntities.STRESS_INPUT.get(),
                (be, tag, registries) -> writeSafeCommon(be, tag, registries));
        SafeNbtWriterRegistry.REGISTRY.register(com.yansunsky.createcmpor.init.ModBlockEntities.STRESS_OUTPUT.get(),
                (be, tag, registries) -> writeSafeCommon(be, tag, registries));

        // 第三方蓝图模组防护（可选依赖）
        if (ModList.get().isLoaded("sable_schematic_api")) {
            registerSableGuard();
        }
    }

    /**
     * 独立的静态方法承载 Sable 类型的引用。
     *
     * <p><b>为什么不在 {@link #register()} 里直接写</b>：{@code SableBlueprintGuard} 实现了 Sable 的接口，
     * 一旦在未安装该模组的实例上被加载就会抛 {@code NoClassDefFoundError} 并把本模组一起拖崩。
     * 放在独立方法里，类加载只会在 {@code isLoaded} 判定为真、真正调用到这里时才发生。</p>
     */
    private static void registerSableGuard() {
        com.yansunsky.createcmpor.compat.sable.SableBlueprintGuard.register();
    }

    /** 蓝图安全 NBT：只写基础 id（roomCode / 生成速度等会话数据不进蓝图）。 */
    private static void writeSafeCommon(net.minecraft.world.level.block.entity.BlockEntity be,
                                        CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
        tag.putString("id", net.minecraft.world.level.block.entity.BlockEntityType.getKey(
                be.getType()).toString());
    }
}
