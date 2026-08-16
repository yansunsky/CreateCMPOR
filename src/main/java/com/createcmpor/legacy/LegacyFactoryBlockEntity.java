package com.createcmpor.legacy;

import com.createcmpor.CreateCMPOR;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
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
 * 并在首次 tick 时自动把方块还原为 Compact Machines 的绑定机器方块
 * （含房间号与机器颜色附件），使旧数据无损过渡。
 *
 * <p>旧 NBT 布局（compactmachinespor FactoryBlockEntity）：
 * <ul>
 *     <li>顶层 {@code room_code}：房间号（RoomCodeBlockEntity 写入）</li>
 *     <li>CustomData 组件内 {@code original_attachments}：固化时拷贝的原机器附件
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
        revertToBoundMachine();
    }

    /**
     * 还原为 Compact Machines 绑定机器方块（照抄旧 Core.revertToBoundMachine 的方块部分，
     * 不依赖旧 mod 的磁盘快照——仅还原方块与房间绑定，房间内容由 CM 自身数据决定）。
     */
    private void revertToBoundMachine() {
        Level world = this.level;
        BlockPos pos = this.worldPosition;
        if (world == null || world.isClientSide) {
            return;
        }
        CompoundTag savedAttachments = this.originalAttachments;

        Block machineBlock;
        try {
            net.minecraft.world.item.Item item =
                    dev.compactmods.machines.machine.Machines.Items.BOUND_MACHINE.get();
            if (!(item instanceof net.minecraft.world.item.BlockItem blockItem)) {
                CreateCMPOR.LOGGER.error("[CreateCMPOR] 旧工厂还原失败：BOUND_MACHINE 不是 BlockItem");
                return;
            }
            machineBlock = blockItem.getBlock();
        } catch (Exception e) {
            CreateCMPOR.LOGGER.error("[CreateCMPOR] 旧工厂还原失败：无法获取 BOUND_MACHINE", e);
            return;
        }
        if (machineBlock == null) {
            CreateCMPOR.LOGGER.error("[CreateCMPOR] 旧工厂还原失败：BOUND_MACHINE 方块为空");
            return;
        }

        // 先放方块，取默认 BE 的类型 id
        world.removeBlockEntity(pos);
        world.setBlockAndUpdate(pos, machineBlock.defaultBlockState());

        BlockEntity defaultBe = world.getBlockEntity(pos);
        if (defaultBe == null) {
            CreateCMPOR.LOGGER.warn("[CreateCMPOR] 旧工厂还原警告：{} 处未生成机器 BE", pos);
            return;
        }

        // 构造机器 BE 的 NBT：房间号 + 机器颜色附件
        String beTypeId = net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE
                .getKey(defaultBe.getType()).toString();
        CompoundTag beNbt = new CompoundTag();
        beNbt.putString("id", beTypeId);
        beNbt.putInt("x", pos.getX());
        beNbt.putInt("y", pos.getY());
        beNbt.putInt("z", pos.getZ());
        if (roomCode != null && !roomCode.isBlank()) {
            beNbt.putString("room_code", roomCode);
        }
        if (savedAttachments != null) {
            beNbt.put("neoforge:attachments", savedAttachments.copy());
        } else {
            CompoundTag defaultAttachments = new CompoundTag();
            defaultAttachments.putString("compactmachines:machine_color", "#C95B13");
            beNbt.put("neoforge:attachments", defaultAttachments);
        }

        BlockEntity loaded = BlockEntity.loadStatic(pos, world.getBlockState(pos), beNbt,
                world.registryAccess());
        if (loaded != null) {
            world.setBlockEntity(loaded);
            loaded.setChanged();
            CreateCMPOR.LOGGER.info("[CreateCMPOR] 旧工厂已还原为空间机器 @{} (room={})", pos, roomCode);
        } else {
            CreateCMPOR.LOGGER.warn("[CreateCMPOR] 旧工厂还原失败：loadStatic 返回 null @{}", pos);
        }
        world.sendBlockUpdated(pos, world.getBlockState(pos), world.getBlockState(pos),
                Block.UPDATE_ALL);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.roomCode = tag.contains("room_code", Tag.TAG_STRING)
                ? tag.getString("room_code") : null;
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
            if (tag.contains("original_attachments", Tag.TAG_COMPOUND)) {
                this.originalAttachments = tag.getCompound("original_attachments");
            }
        }
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder builder) {
        super.collectImplicitComponents(builder);
        CompoundTag tag = new CompoundTag();
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
