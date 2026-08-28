package com.yansunsky.createcmpor.item;

import com.yansunsky.createcmpor.CreateCMPOR;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;

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
 * <p>参考 Create {@code LogisticallyLinkedBlockItem} 模式（BlockItem + appendHoverText 读 BLOCK_ENTITY_DATA）。</p>
 */
public class FactoryBlockItem extends BlockItem {

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
        // 标题 + 模式
        tooltip.add(Component.translatable("createcmpor.tooltip.factory.title")
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable(
                        replay ? "createcmpor.tooltip.factory.mode_replay"
                                : "createcmpor.tooltip.factory.mode_rate")
                .withStyle(ChatFormatting.GRAY));

        if (Screen.hasShiftDown()) {
            appendFullInfo(tag, tooltip, replay);
        } else {
            appendOutputOnly(tag, tooltip, replay);
        }
    }

    /** 默认：只显示输出（产物概览）。 */
    private void appendOutputOnly(CompoundTag tag, List<Component> tooltip, boolean replay) {
        RateView output = RateView.of(tag, false, replay);
        output.append(tooltip, "createcmpor.tooltip.factory.io_out_item",
                "createcmpor.tooltip.factory.io_out_fluid",
                "createcmpor.tooltip.factory.io_out_energy");
        tooltip.add(Component.translatable("createcmpor.tooltip.factory.item_shift_hint")
                .withStyle(ChatFormatting.DARK_GRAY));
    }

    /** Shift：完整信息（房间号/组数/模式/输入/输出/能量/燃烧/应力）。 */
    private void appendFullInfo(CompoundTag tag, List<Component> tooltip, boolean replay) {
        String room = tag.getString("room_code");
        int count = Math.max(1, tag.getInt("factory_count"));
        if (!room.isBlank()) {
            tooltip.add(Component.translatable("createcmpor.tooltip.factory.room", room)
                    .withStyle(ChatFormatting.GRAY));
        }
        if (count > 1) {
            tooltip.add(Component.translatable("createcmpor.tooltip.factory.group_count",
                    tag.getInt("branch_index") + 1, count)
                    .withStyle(ChatFormatting.GRAY));
        }

        // 输入（物品/流体/能量）
        RateView.of(tag, true, replay).append(tooltip,
                "createcmpor.tooltip.factory.io_in_item",
                "createcmpor.tooltip.factory.io_in_fluid",
                "createcmpor.tooltip.factory.io_in_energy");
        // 输出
        appendOutputOnly(tag, tooltip, replay);

        // 燃烧需求
        double normalBurn = tag.getDouble("normal_burn_demand");
        double superBurn = tag.getDouble("super_burn_demand");
        if (normalBurn > 0 || superBurn > 0) {
            MutableComponent line = Component.literal("     ").append(
                    Component.translatable("createcmpor.tooltip.factory.burn_rate",
                            Component.literal(String.format(java.util.Locale.ROOT, "%.2f/s", normalBurn))
                                    .withStyle(ChatFormatting.GOLD),
                            Component.literal(String.format(java.util.Locale.ROOT, "%.2f/s", superBurn))
                                    .withStyle(ChatFormatting.AQUA)));
            tooltip.add(line);
        }

        // 应力（attachments 的 stress_profile）
        appendStress(tag, tooltip);
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

        RateView(CompoundTag tag, boolean input, boolean replay) {
            this.tag = tag;
            this.input = input;
            this.replay = replay;
        }

        static RateView of(CompoundTag tag, boolean input, boolean replay) {
            return new RateView(tag, input, replay);
        }

        void append(List<Component> tooltip, String itemKey, String fluidKey, String energyKey) {
            String prefix = input ? "input_" : "output_";
            if (replay) {
                appendPattern(tooltip, itemKey, prefix + "item_patterns");
                appendPattern(tooltip, fluidKey, prefix + "fluid_patterns");
                long energy = tag.getIntArray(prefix + "energy_pattern").length == 0
                        ? 0
                        : average(tag.getIntArray(prefix + "energy_pattern"));
                if (energy > 0) {
                    tooltip.add(line(energyKey, value(energy, "每秒")));
                }
            } else {
                appendRateMap(tooltip, itemKey, prefix + "item_rates");
                appendRateMap(tooltip, fluidKey, prefix + "fluid_rates");
                double energy = tag.getDouble(prefix + "energy_rate") * 20.0;
                if (energy > 0) {
                    tooltip.add(line(energyKey, value(energy, "每秒")));
                }
            }
        }

        private void appendRateMap(List<Component> tooltip, String key, String nbtKey) {
            if (!tag.contains(nbtKey, net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                return;
            }
            CompoundTag map = tag.getCompound(nbtKey);
            for (String idKey : map.getAllKeys()) {
                double perTick = map.getDouble(idKey);
                if (perTick <= 0) {
                    continue;
                }
                tooltip.add(line(key, value(perTick * 20.0, "每秒", displayName(idKey))));
            }
        }

        private void appendPattern(List<Component> tooltip, String key, String nbtKey) {
            if (!tag.contains(nbtKey, net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                return;
            }
            CompoundTag map = tag.getCompound(nbtKey);
            for (String idKey : map.getAllKeys()) {
                int[] pattern = map.getIntArray(idKey);
                if (pattern.length == 0 || average(pattern) <= 0) {
                    continue;
                }
                tooltip.add(line(key, value((double) average(pattern), "每秒", displayName(idKey))));
            }
        }

        private static Component line(String key, Object... args) {
            return Component.translatable(key, args).withStyle(ChatFormatting.AQUA);
        }

        private static String value(double perSecond, String unit, Object name) {
            return String.format(java.util.Locale.ROOT, "%s ×%.2f/%s", name, perSecond, unit);
        }

        private static String value(double perSecond, String unit) {
            return String.format(java.util.Locale.ROOT, "%.2f/%s", perSecond, unit);
        }

        private static long average(int[] array) {
            long sum = 0;
            for (int v : array) {
                sum += v;
            }
            return array.length == 0 ? 0 : sum / array.length;
        }

        private static Object displayName(String idKey) {
            ResourceLocation id = ResourceLocation.tryParse(idKey);
            return id == null ? idKey
                    : BuiltInRegistries.ITEM.get(id).getDescription().getString();
        }
    }

    private static final net.minecraft.core.component.DataComponentType<CustomData> DataComponentsBLOCK_ENTITY_DATA =
            net.minecraft.core.component.DataComponents.BLOCK_ENTITY_DATA;
}
