package com.yansunsky.createcmpor.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persisted IO filters and capability shells used by the later evaluator.
 * Phase 1 intentionally does not report data to the removed legacy evaluator.
 */
public abstract class BaseIOBlockEntity extends RoomCodeBlockEntity {
    protected final List<ResourceLocation> items = new ArrayList<>();
    protected final List<ResourceLocation> fluids = new ArrayList<>();
    /**
     * 白名单物品的登记形态模板（count=1、含 DataComponents）。
     * <p>1.20.5+ 大量物品差异（药水内容、附魔、自定义名等）由组件承载，仅凭 item id
     * 会让输入侧虚拟物品退化成无组件原型（如水瓶 → 不可合成的药水）。右键登记白名单时
     * 记录真实形态，抽取时按模板提供；旧档/无组件物品缺失该条目时回退纯 id 构造。</p>
     */
    protected final Map<ResourceLocation, ItemStack> itemTemplates = new LinkedHashMap<>();

    protected BaseIOBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    /** 按白名单模板构造物品堆（模板缺失回退纯 id；count ≤0 返回空）。 */
    public ItemStack filterStack(ResourceLocation id, int count) {
        if (count <= 0 || id == null) {
            return ItemStack.EMPTY;
        }
        ItemStack template = itemTemplates.get(id);
        ItemStack base = template == null || template.isEmpty()
                ? new ItemStack(BuiltInRegistries.ITEM.get(id)) : template;
        return base.copyWithCount(count);
    }

    /**
     * 登记/移除物品白名单（右键 IO 方块）：按 id 切换，并记录/清除真实形态模板。
     *
     * @return true = 本次为新增；false = 本次为移除
     */
    public boolean toggleItemFilter(ResourceLocation id, ItemStack stack) {
        if (items.contains(id)) {
            items.remove(id);
            itemTemplates.remove(id);
            return false;
        }
        items.add(id);
        if (stack != null && !stack.isEmpty()) {
            itemTemplates.put(id, stack.copyWithCount(1));
        }
        return true;
    }

    @Override
    protected void loadCommon(CompoundTag tag) {
        super.loadCommon(tag);
        items.clear();
        itemTemplates.clear();
        if (tag.contains("items", Tag.TAG_LIST)) {
            ListTag list = tag.getList("items", Tag.TAG_STRING);
            for (int i = 0; i < list.size(); i++) {
                ResourceLocation id = ResourceLocation.tryParse(list.getString(i));
                if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
                    items.add(id);
                }
            }
        }

        fluids.clear();
        if (tag.contains("fluids", Tag.TAG_LIST)) {
            ListTag list = tag.getList("fluids", Tag.TAG_STRING);
            for (int i = 0; i < list.size(); i++) {
                ResourceLocation id = ResourceLocation.tryParse(list.getString(i));
                if (id != null && BuiltInRegistries.FLUID.containsKey(id)) {
                    fluids.add(id);
                }
            }
        }
    }

    /** 含注册表的载入：额外恢复白名单物品形态模板。 */
    @Override
    protected void loadCommon(CompoundTag tag, HolderLookup.Provider registries) {
        loadCommon(tag);
        if (tag.contains("item_templates", Tag.TAG_COMPOUND)) {
            CompoundTag templates = tag.getCompound("item_templates");
            for (String idKey : templates.getAllKeys()) {
                ResourceLocation id = ResourceLocation.tryParse(idKey);
                if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
                    continue;
                }
                CompoundTag stackTag = templates.getCompound(idKey);
                ItemStack stack = ItemStack.parseOptional(registries, stackTag);
                if (!stack.isEmpty()) {
                    itemTemplates.put(id, stack.copyWithCount(1));
                }
            }
        }
    }

    @Override
    protected void saveCommon(CompoundTag tag) {
        super.saveCommon(tag);
        ListTag itemTag = new ListTag();
        items.forEach(id -> itemTag.add(StringTag.valueOf(id.toString())));
        tag.put("items", itemTag);

        ListTag fluidTag = new ListTag();
        fluids.forEach(id -> fluidTag.add(StringTag.valueOf(id.toString())));
        tag.put("fluids", fluidTag);
    }

    /** 含注册表的保存：额外写入白名单物品形态模板（旧档无该键，读侧保持兼容）。 */
    @Override
    protected void saveCommon(CompoundTag tag, HolderLookup.Provider registries) {
        saveCommon(tag);
        CompoundTag templates = new CompoundTag();
        itemTemplates.forEach((id, stack) -> {
            if (!stack.isEmpty()) {
                templates.put(id.toString(), stack.copyWithCount(1).saveOptional(registries));
            }
        });
        if (!templates.isEmpty()) {
            tag.put("item_templates", templates);
        }
    }

    public abstract IItemHandler getItemHandler();

    public abstract IFluidHandler getFluidHandler();

    public abstract IEnergyStorage getEnergyHandler();

    protected boolean isActive() {
        return getBlockState().getValue(BaseIOBlock.ACTIVE) && roomCode != null && !roomCode.isBlank();
    }

    /**
     * 是否作为"输入方向"采样（输入方块 → 原料进房间；输出方块 → 产物出房间）。
     * 默认按实例类型判断；并行空间输入方块是输入角色，必须覆盖返回 true，
     * 否则其流量会被记为输出方向（原料变输出的 bug）。
     */
    protected boolean isInputSide() {
        return this instanceof InputBlockEntity;
    }

    /** 诊断：IO 白名单摘要（评估激活日志用）。 */
    public String describeIoFilter() {
        return "items=" + items + " fluids=" + fluids;
    }

    protected boolean checkAndDeactivate() {
        if (isActive()) {
            return true;
        }
        if (getBlockState().getValue(BaseIOBlock.ACTIVE) && getLevel() != null) {
            getLevel().setBlock(getBlockPos(), getBlockState().setValue(BaseIOBlock.ACTIVE, false), Block.UPDATE_CLIENTS);
        }
        return false;
    }

    /** Phase 6 采样器：把 IO 流量写入活动评估会话的按秒 bucket。 */
    protected void handle(ItemStack stack) {
        if (getLevel() == null || getLevel().isClientSide || roomCode == null || roomCode.isBlank()) {
            return;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        boolean input = isInputSide();
        // 身份签名 = id + 组件摘要：让"水瓶"与同 id 的其他药水成为两条独立通道
        // （否则同 id 进出会在净平衡里互相抵消、固化后整条通道消失）。
        String signature = com.yansunsky.createcmpor.evaluation.ItemIdentity.of(
                stack, getLevel().registryAccess());
        // 组件保真：把本次真实物品的形态（count=1、含 DataComponents）一并上报，
        // 固化后工厂据此重建产物（否则带组件物品会退化成无组件原型，如水瓶→不可合成药水）。
        com.yansunsky.createcmpor.evaluation.EvaluationTrace.Hub.INSTANCE.record(
                roomCode, com.yansunsky.createcmpor.evaluation.EvaluationTrace.FlowKey.item(id, signature),
                stack.getCount(), input, getLevel().getGameTime(), stack.copyWithCount(1));
    }

    protected void handle(FluidStack stack) {
        if (getLevel() == null || getLevel().isClientSide || roomCode == null || roomCode.isBlank()) {
            return;
        }
        ResourceLocation id = BuiltInRegistries.FLUID.getKey(stack.getFluid());
        boolean input = isInputSide();
        com.yansunsky.createcmpor.evaluation.EvaluationTrace.Hub.INSTANCE.record(
                roomCode, com.yansunsky.createcmpor.evaluation.EvaluationTrace.FlowKey.fluid(id),
                stack.getAmount(), input, getLevel().getGameTime());
    }

    protected void handle(Holder<?> holder, int count) {
        if (getLevel() == null || getLevel().isClientSide || roomCode == null || roomCode.isBlank()
                || holder.value() == null) {
            return;
        }
        boolean input = isInputSide();
        if (holder.value() instanceof net.minecraft.world.item.Item item) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            com.yansunsky.createcmpor.evaluation.EvaluationTrace.Hub.INSTANCE.record(
                    roomCode, com.yansunsky.createcmpor.evaluation.EvaluationTrace.FlowKey.item(id),
                    count, input, getLevel().getGameTime());
        }
    }

    protected void handle(int energy) {
        if (getLevel() == null || getLevel().isClientSide || roomCode == null || roomCode.isBlank()) {
            return;
        }
        com.yansunsky.createcmpor.evaluation.EvaluationTrace.Hub.INSTANCE.recordEnergy(
                roomCode, energy, isInputSide(), getLevel().getGameTime());
    }

    @Override
    protected void applyImplicitComponents(BlockEntity.DataComponentInput componentInput) {
        super.applyImplicitComponents(componentInput);
        CustomData customData = componentInput.get(DataComponents.CUSTOM_DATA);
        if (customData != null) {
            loadCommon(customData.copyTag());
        }
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder builder) {
        super.collectImplicitComponents(builder);
        CompoundTag tag = new CompoundTag();
        saveCommon(tag);
        builder.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
