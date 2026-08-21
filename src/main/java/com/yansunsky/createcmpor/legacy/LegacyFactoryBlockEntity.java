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
 * 仅用于旧版 CompactMachinesPOR 存档过渡：检测到旧工厂方块后自动还原为空间机器。
 * 待旧存档迁移完成（或确认无需兼容）后整个 legacy 包应被移除，勿在此基础上扩展业务逻辑。
 *
 * <p>旧版 CompactMachinesPOR 工厂方块的兼容实体（id: compactmachinespor:factory_block）。
 *
 * <p>目的：旧 mod 卸载后，旧存档中的工厂方块因 id 未知会数据丢失。
 * 本实体注册同 id，加载旧 NBT（{@code room_code} / {@code original_attachments}），
 * 并在首次 tick 时把方块还原为 Compact Machines 的绑定机器方块（提取房间号填充）。
 *
 * <p>旧 NBT 布局（compactmachinespor FactoryBlockEntity）：
 * <ul>
 *     <li>顶层 {@code room_code}：房间号（RoomCodeBlockEntity 写入）</li>
 *     <li>顶层 {@code original_attachments}：固化时拷贝的原机器附件
 *         （如 machine_color），用于还原机器外观</li>
 * </ul>
 */
public class LegacyFactoryBlockEntity extends BlockEntity {

    private String roomCode;
    private CompoundTag originalAttachments;
    private boolean restored;

    public LegacyFactoryBlockEntity(BlockPos pos, BlockState state) {
        super(LegacyCompat.LEGACY_BE_TYPE, pos, state);
    }

    /** 服务端 tick：首次 tick 自动还原为空间机器方块。 */
    public void tick() {
        if (level == null || level.isClientSide || restored) {
            return;
        }
        restored = true;
        if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            LegacyCompat.restoreAsBoundMachine(serverLevel, worldPosition, roomCode,
                    originalAttachments);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.roomCode = tag.contains("room_code", Tag.TAG_STRING)
                ? tag.getString("room_code") : null;
        // 旧版 FactoryBlockEntity 把 original_attachments 写在 NBT 顶层（saveCommon）；
        // 同时保留 CustomData 组件读取兜底（部分版本写入组件）。
        if (tag.contains("original_attachments", Tag.TAG_COMPOUND)) {
            this.originalAttachments = tag.getCompound("original_attachments");
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
            if (tag.contains("original_attachments", Tag.TAG_COMPOUND)) {
                this.originalAttachments = tag.getCompound("original_attachments");
            }
        }
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder builder) {
        super.collectImplicitComponents(builder);
        CompoundTag tag = new CompoundTag();
        if (roomCode != null) {
            tag.putString("room_code", roomCode);
        }
        if (originalAttachments != null) {
            tag.put("original_attachments", originalAttachments.copy());
        }
        if (!tag.isEmpty()) {
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

    /** 供外部读取（调试用）。 */
    public String getLegacyRoomCode() {
        return roomCode;
    }
}
