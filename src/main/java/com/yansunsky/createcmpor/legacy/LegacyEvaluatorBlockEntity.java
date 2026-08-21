package com.yansunsky.createcmpor.legacy;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * ⚠️ 临时兼容代码（TEMPORARY）
 * 旧版 CompactMachinesPOR 评估方块兼容实体（id: compactmachinespor:evaluator_block）。
 *
 * <p>旧存档中评估中断/失败残留的评估方块（id 未知会整体消失）。
 * 本实体加载旧 NBT 的 {@code room_code} 后，在首次 tick 时把方块还原为
 * Compact Machines 绑定机器方块（提取房间号填充），房间内容由 CM 自身数据决定。</p>
 *
 * <p>旧 NBT 布局（compactmachinespor EvaluatorBlockEntity）：
 * <ul>
 *     <li>顶层 {@code room_code}：房间号</li>
 * </ul>
 * 旧版同时把数据写入 CustomData 组件（双写），此处读取均做兜底。
 */
public class LegacyEvaluatorBlockEntity extends BlockEntity {

    private String roomCode;
    private boolean restored;

    public LegacyEvaluatorBlockEntity(BlockPos pos, BlockState state) {
        super(LegacyCompat.LEGACY_EVALUATOR_BE_TYPE, pos, state);
    }

    /** 服务端 tick：首次 tick 自动还原为空间机器方块。 */
    public void tick() {
        if (level == null || level.isClientSide || restored) {
            return;
        }
        restored = true;
        if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            LegacyCompat.restoreAsBoundMachine(serverLevel, worldPosition, roomCode, null);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("room_code", Tag.TAG_STRING)) {
            this.roomCode = tag.getString("room_code");
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (roomCode != null) {
            tag.putString("room_code", roomCode);
        }
    }

    @Override
    protected void applyImplicitComponents(BlockEntity.DataComponentInput componentInput) {
        super.applyImplicitComponents(componentInput);
        CustomData customData = componentInput.get(DataComponents.CUSTOM_DATA);
        if (customData != null) {
            CompoundTag tag = customData.copyTag();
            if (tag.contains("room_code", Tag.TAG_STRING)) {
                this.roomCode = tag.getString("room_code");
            }
        }
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder builder) {
        super.collectImplicitComponents(builder);
        if (roomCode != null) {
            CompoundTag tag = new CompoundTag();
            tag.putString("room_code", roomCode);
            builder.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        }
    }

    @Override
    public net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }
}
