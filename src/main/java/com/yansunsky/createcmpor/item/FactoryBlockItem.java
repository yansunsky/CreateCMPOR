package com.yansunsky.createcmpor.item;

import com.yansunsky.createcmpor.CreateCMPOR;
import com.yansunsky.createcmpor.evaluation.ItemIdentity;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 工厂方块物品：鼠标悬停显示信息。
 *
 * <p>挖掘/扳手掉落的工厂物品携带完整 BE NBT（{@code DataComponents.BLOCK_ENTITY_DATA}），
 * 此处读取并展示：<b>默认只显示输出</b>；<b>按住 Shift 显示完整信息</b>
 * （房间号/组数/模式/输入/输出/能量/燃烧需求/应力——与护目镜对齐）。</p>
 *
 * <p><b>两种 NBT 布局</b>（见 48 文档 §2.1）：
 * <ul>
 *   <li><b>新模式表（0.5.0+）</b>：顶层 {@code modes} 列表，每元素 = 一个互斥模式，元素内的表键名与旧版
 *       顶层平铺键完全一致（{@code input_item_rates} / {@code input_item_patterns} / …），
 *       另有 {@code trigger} / {@code verdict} / {@code progress}。</li>
 *   <li><b>旧档（&lt;0.5.0）</b>：无 {@code modes}，表就摊在顶层——原路径保留，行为不变。</li>
 * </ul>
 * 两者都在本类里被解析；{@code room_code} / {@code factory_count} / {@code branch_index} 与
 * {@code neoforge:attachments} 的应力行<b>两种布局下含义相同</b>，不受模式表改造影响。</p>
 *
 * <p>参考 Create {@code LogisticallyLinkedBlockItem} 模式（BlockItem + appendHoverText 读 BLOCK_ENTITY_DATA）。</p>
 */
public class FactoryBlockItem extends BlockItem {

    /** 新模式表逐模式明细的行首缩进（沿用旧版燃烧行的 5 空格风格）。 */
    private static final String MODE_INDENT = "     ";

    public FactoryBlockItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        CompoundTag tag = stack.getOrDefault(DataComponentsBLOCK_ENTITY_DATA, CustomData.EMPTY).copyTag();
        if (tag.isEmpty()) {
            return; // 未承载工厂数据（创造栏/配方产物）：无附加信息
        }

        boolean replay = tag.getBoolean("replay_mode");
        // 新模式表（0.5.0+）：顶层 modes 列表，每个元素 = 一个互斥模式。
        // 列表为空 ⇒ 旧档（单工厂 + 顶层平铺键），走原解析路径（48 文档 §2.1「旧档回退」）。
        ListTag modes = tag.getList("modes", Tag.TAG_COMPOUND);
        // 标题 + 模式
        tooltip.add(Component.translatable("createcmpor.tooltip.factory.title")
                .withStyle(ChatFormatting.GRAY));
        if (modes.isEmpty()) {
            tooltip.add(Component.translatable(
                            replay ? "createcmpor.tooltip.factory.mode_replay"
                                    : "createcmpor.tooltip.factory.mode_rate")
                    .withStyle(ChatFormatting.GRAY));
        } else {
            tooltip.add(Component.translatable("createcmpor.tooltip.factory.mode_count", modes.size())
                    .withStyle(ChatFormatting.GRAY));
        }

        if (Screen.hasShiftDown()) {
            appendFullInfo(tag, tooltip, replay, modes);
        } else {
            appendOutputOnly(tag, tooltip, replay, modes);
        }
    }

    /** 默认：只显示输出（产物概览）。新模式表下 = 每个模式一行「模式 i/N：触发「沙」→ 出「铁块」」。 */
    private void appendOutputOnly(CompoundTag tag, List<Component> tooltip, boolean replay, ListTag modes) {
        if (!modes.isEmpty()) {
            for (int i = 0; i < modes.size(); i++) {
                CompoundTag mode = modes.getCompound(i);
                tooltip.add(Component.translatable("createcmpor.tooltip.factory.mode_line_io",
                                i + 1, modes.size(), triggerName(mode.getString("trigger")),
                                outputSummary(mode, isReplay(mode, replay)))
                        .withStyle(ChatFormatting.GOLD));
            }
        } else {
            appendLegacyOutputs(tag, tooltip, replay);
        }
        tooltip.add(Component.translatable("createcmpor.tooltip.factory.item_shift_hint")
                .withStyle(ChatFormatting.DARK_GRAY));
    }

    /** 旧档（顶层平铺键）的输出行：0.4.31 行为不变。 */
    private void appendLegacyOutputs(CompoundTag tag, List<Component> tooltip, boolean replay) {
        RateView.of(tag, false, replay).append(tooltip, "createcmpor.tooltip.factory.io_out_item",
                "createcmpor.tooltip.factory.io_out_fluid",
                "createcmpor.tooltip.factory.io_out_energy");
    }

    /** Shift：完整信息（房间号/组数/模式/输入/输出/能量/燃烧/应力）。 */
    private void appendFullInfo(CompoundTag tag, List<Component> tooltip, boolean replay, ListTag modes) {
        String room = tag.getString("room_code");
        int count = Math.max(1, tag.getInt("factory_count"));
        if (!room.isBlank()) {
            tooltip.add(Component.translatable("createcmpor.tooltip.factory.room", room)
                    .withStyle(ChatFormatting.GRAY));
        }
        if (count > 1) {
            // 遗留兼容：旧存档的多工厂组（factory_count > 1）仍显示"子工厂 i/N"（见 48 文档 §6.3）
            tooltip.add(Component.translatable("createcmpor.tooltip.factory.group_count",
                    tag.getInt("branch_index") + 1, count)
                    .withStyle(ChatFormatting.GRAY));
        }

        if (!modes.isEmpty()) {
            appendModeDetails(tag, tooltip, replay, modes);
        } else {
            // 旧档：顶层平铺键（0.4.31 行为不变）
            RateView.of(tag, true, replay).append(tooltip,
                    "createcmpor.tooltip.factory.io_in_item",
                    "createcmpor.tooltip.factory.io_in_fluid",
                    "createcmpor.tooltip.factory.io_in_energy");
            appendLegacyOutputs(tag, tooltip, replay);
            appendBurn(tag, tag, tooltip, MODE_INDENT);
        }

        // 应力（attachments 的 stress_profile）——两种布局下都是顶层一份（I5 合并后）
        appendStress(tag, tooltip);
    }

    /** 新模式表：逐模式展开输入/输出/燃烧需求，并说明互斥语义。 */
    private void appendModeDetails(CompoundTag tag, List<Component> tooltip, boolean replay, ListTag modes) {
        // 互斥语义必须写清：多模式不叠加产出（47 文档 I2）
        tooltip.add(Component.translatable("createcmpor.tooltip.factory.mode_exclusive")
                .withStyle(ChatFormatting.DARK_GRAY));
        for (int i = 0; i < modes.size(); i++) {
            CompoundTag mode = modes.getCompound(i);
            boolean modeReplay = isReplay(mode, replay);
            tooltip.add(Component.translatable("createcmpor.tooltip.factory.mode_line",
                            i + 1, modes.size(), triggerName(mode.getString("trigger")))
                    .withStyle(ChatFormatting.GOLD));
            // 输入（物品/流体/能量）
            RateView.of(mode, true, modeReplay, MODE_INDENT).append(tooltip,
                    "createcmpor.tooltip.factory.io_in_item",
                    "createcmpor.tooltip.factory.io_in_fluid",
                    "createcmpor.tooltip.factory.io_in_energy");
            // 输出
            RateView.of(mode, false, modeReplay, MODE_INDENT).append(tooltip,
                    "createcmpor.tooltip.factory.io_out_item",
                    "createcmpor.tooltip.factory.io_out_fluid",
                    "createcmpor.tooltip.factory.io_out_energy");
            // 燃烧需求（每模式一套；缺失时回退到顶层共享值）
            appendBurn(mode, tag, tooltip, MODE_INDENT);
        }
    }

    /** 燃烧需求行；{@code mode} 优先，缺键时回退 {@code fallback}（旧档/部分写入）。 */
    private static void appendBurn(CompoundTag mode, CompoundTag fallback, List<Component> tooltip, String indent) {
        double normalBurn = mode.contains("normal_burn_demand")
                ? mode.getDouble("normal_burn_demand") : fallback.getDouble("normal_burn_demand");
        double superBurn = mode.contains("super_burn_demand")
                ? mode.getDouble("super_burn_demand") : fallback.getDouble("super_burn_demand");
        if (normalBurn <= 0 && superBurn <= 0) {
            return;
        }
        MutableComponent line = Component.literal(indent).append(
                Component.translatable("createcmpor.tooltip.factory.burn_rate",
                        Component.literal(String.format(java.util.Locale.ROOT, "%.2f/s", normalBurn))
                                .withStyle(ChatFormatting.GOLD),
                        Component.literal(String.format(java.util.Locale.ROOT, "%.2f/s", superBurn))
                                .withStyle(ChatFormatting.AQUA)));
        tooltip.add(line);
    }

    /** 模式的判定类型：优先读模式自己的 {@code verdict}，缺失时回退顶层 {@code replay_mode}（旧档）。 */
    private static boolean isReplay(CompoundTag mode, boolean fallback) {
        String verdict = mode.getString("verdict");
        if (verdict.isBlank()) {
            return fallback;
        }
        // 归一化判定：仅 REPLAY 视为回放
        return "REPLAY".equalsIgnoreCase(verdict.trim());
    }

    /**
     * 触发物品签名串 → 本地化显示名（D3 要求：显示名而不是 ID）。
     *
     * <p>{@code modes[i].trigger} 是<b>多个签名用换行拼接</b>的串
     * （{@code FactoryBlockEntity.TRIGGER_SEPARATOR = "\n"}：该分支评估时房间里所有并行方块暴露的物品），
     * 故这里逐个解析并用本地化分隔符拼接（与评估侧共用
     * {@code message.createcmpor.evaluation.trigger_separator}）。</p>
     *
     * <p>空串 = 该模式是"默认模式"（无触发物品），显示为「（无输入）」占位
     * （key 与评估侧共用 {@code message.createcmpor.evaluation.no_input}）。
     * 带组件摘要的签名（{@code id#hash}）在名称后附「（带组件）」，避免与无组件同 id 物品混淆。</p>
     */
    private static Component triggerName(String raw) {
        if (raw == null || raw.isBlank()) {
            return Component.translatable("message.createcmpor.evaluation.no_input");
        }
        // 与 FactoryBlockEntity.TRIGGER_SEPARATOR 对齐：模式可同时被多种物品触发
        String[] parts = raw.split("\n");
        MutableComponent out = Component.empty();
        int written = 0;
        for (String part : parts) {
            String signature = part.trim();
            if (signature.isEmpty()) {
                continue;
            }
            if (written++ > 0) {
                out.append(Component.translatable("message.createcmpor.evaluation.trigger_separator"));
            }
            out.append(singleTriggerName(signature));
        }
        return written == 0 ? Component.translatable("message.createcmpor.evaluation.no_input") : out;
    }

    /** 单个触发物品签名 → 显示名；解析不出时原样回显签名（不丢信息）。 */
    private static Component singleTriggerName(String signature) {
        ResourceLocation id = ItemIdentity.idOf(signature);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            return Component.literal(signature);
        }
        MutableComponent name = BuiltInRegistries.ITEM.get(id).getDescription().copy();
        if (ItemIdentity.hasComponents(signature)) {
            name.append(Component.translatable("createcmpor.tooltip.factory.item_with_components"));
        }
        return name;
    }

    /** 默认视图的输出摘要：物品名（最多 3 个，多则省略号）；无物品输出时退回流体名。 */
    private static Component outputSummary(CompoundTag mode, boolean replay) {
        List<Component> names = new ArrayList<>(outputNames(mode, replay, false));
        if (names.isEmpty()) {
            names.addAll(outputNames(mode, replay, true));
        }
        if (names.isEmpty()) {
            return Component.translatable("createcmpor.tooltip.factory.no_output");
        }
        MutableComponent summary = Component.empty();
        int shown = Math.min(3, names.size());
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                // 与评估侧共用同一个多物品分隔符（本地化：中文「、」/ 英文 ", "）
                summary.append(Component.translatable("message.createcmpor.evaluation.trigger_separator"));
            }
            summary.append(names.get(i));
        }
        if (names.size() > shown) {
            summary.append(Component.literal("…"));
        }
        return summary;
    }

    /** 模式输出表里的物品（或流体）显示名；按模式的判定类型取速率表或回放平均表。 */
    private static List<Component> outputNames(CompoundTag mode, boolean replay, boolean fluid) {
        String nbtKey = (fluid ? "output_fluid_" : "output_item_") + (replay ? "patterns" : "rates");
        List<Component> names = new ArrayList<>();
        if (!mode.contains(nbtKey, Tag.TAG_COMPOUND)) {
            return names;
        }
        CompoundTag map = mode.getCompound(nbtKey);
        for (String signature : map.getAllKeys()) {
            boolean positive = replay
                    ? RateView.average(map.getIntArray(signature)) > 0
                    : map.getDouble(signature) > 0;
            if (positive) {
                names.add(RateView.displayName(signature));
            }
        }
        return names;
    }

    private void appendStress(CompoundTag tag, List<Component> tooltip) {
        if (!tag.contains("neoforge:attachments", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            return;
        }
        CompoundTag attachments = tag.getCompound("neoforge:attachments");
        if (!attachments.contains("createcmpor:stress_profile", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            return;
        }
        CompoundTag profile = attachments.getCompound("createcmpor:stress_profile");
        float inputSU = profile.getFloat("input_su");
        float outputSU = profile.getFloat("output_su");
        if (inputSU > 0 || outputSU > 0) {
            tooltip.add(Component.translatable("createcmpor.tooltip.factory.stress",
                            inputSU, outputSU)
                    .withStyle(ChatFormatting.GRAY));
        }
    }

    /** 便利读取：速率视图（物品/流体按每秒；能量按每秒；REPLAY 取均值）。 */
    private static final class RateView {
        private final boolean input;
        private final boolean replay;
        private final CompoundTag tag;
        /** 行首缩进（新模式表的逐模式明细用；旧档为空串）。 */
        private final String indent;

        RateView(CompoundTag tag, boolean input, boolean replay, String indent) {
            this.tag = tag;
            this.input = input;
            this.replay = replay;
            this.indent = indent;
        }

        static RateView of(CompoundTag tag, boolean input, boolean replay) {
            return new RateView(tag, input, replay, "");
        }

        static RateView of(CompoundTag tag, boolean input, boolean replay, String indent) {
            return new RateView(tag, input, replay, indent);
        }

        /** 统一落行入口：非空缩进时前置缩进（样式仍由外层决定，见 Component#toFlatList）。 */
        private void add(List<Component> tooltip, Component line) {
            tooltip.add(indent.isEmpty() ? line : Component.literal(indent).append(line));
        }

        void append(List<Component> tooltip, String itemKey, String fluidKey, String energyKey) {
            String prefix = input ? "input_" : "output_";
            if (replay) {
                appendPattern(tooltip, itemKey, prefix + "item_patterns");
                appendPattern(tooltip, fluidKey, prefix + "fluid_patterns");
                int[] energyPattern = tag.getIntArray(prefix + "energy_pattern");
                double energy = average(energyPattern);
                if (energy > 0) {
                    // 能量键只有 1 个参数（%1$s = FE/秒）
                    add(tooltip, Component.translatable(energyKey, format1(energy))
                            .withStyle(ChatFormatting.AQUA));
                }
            } else {
                appendRateMap(tooltip, itemKey, prefix + "item_rates");
                appendRateMap(tooltip, fluidKey, prefix + "fluid_rates");
                double energy = tag.getDouble(prefix + "energy_rate") * 20.0;
                if (energy > 0) {
                    add(tooltip, Component.translatable(energyKey, format1(energy))
                            .withStyle(ChatFormatting.AQUA));
                }
            }
        }

        private void appendRateMap(List<Component> tooltip, String key, String nbtKey) {
            if (!tag.contains(nbtKey, Tag.TAG_COMPOUND)) {
                return;
            }
            CompoundTag map = tag.getCompound(nbtKey);
            for (String idKey : map.getAllKeys()) {
                double perTick = map.getDouble(idKey);
                if (perTick <= 0) {
                    continue;
                }
                // 物品/流体键：%1$s=名称、%2$s=每秒数量（数字）
                add(tooltip, Component.translatable(key, displayName(idKey), format1(perTick * 20.0))
                        .withStyle(ChatFormatting.AQUA));
            }
        }

        private void appendPattern(List<Component> tooltip, String key, String nbtKey) {
            if (!tag.contains(nbtKey, Tag.TAG_COMPOUND)) {
                return;
            }
            CompoundTag map = tag.getCompound(nbtKey);
            for (String idKey : map.getAllKeys()) {
                int[] pattern = map.getIntArray(idKey);
                double avg = average(pattern);
                if (avg <= 0) {
                    continue;
                }
                add(tooltip, Component.translatable(key, displayName(idKey), format1(avg))
                        .withStyle(ChatFormatting.AQUA));
            }
        }

        /** 每秒速率显示：%.2f 数字（占位符只接受数字，不拼单位——单位已写在 lang 串里）。 */
        private static String format1(double value) {
            return String.format(java.util.Locale.ROOT, "%.2f", value);
        }

        private static long average(int[] array) {
            long sum = 0;
            for (int v : array) {
                sum += v;
            }
            return array.length == 0 ? 0 : sum / array.length;
        }

        /**
         * 速率表/回放表里的键 → 本地化显示名。
         *
         * <p>键可能是带组件摘要的签名（{@code id#hash}，见 {@code ItemIdentity}），故走
         * {@link ItemIdentity#idOf} 取 id 段而不是直接 {@code ResourceLocation.tryParse}；
         * 解析不出（或不是物品）时原样显示该键（流体键在旧版即如此，行为不变）。</p>
         */
        private static Component displayName(String signature) {
            ResourceLocation id = ItemIdentity.idOf(signature);
            if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
                return Component.literal(signature);
            }
            return BuiltInRegistries.ITEM.get(id).getDescription();
        }
    }

    private static final net.minecraft.core.component.DataComponentType<CustomData> DataComponentsBLOCK_ENTITY_DATA =
            net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA;
}
