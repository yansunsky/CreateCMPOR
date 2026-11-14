package com.yansunsky.createcmpor.client;

import com.simibubi.create.content.equipment.goggles.GogglesItem;
import com.simibubi.create.content.kinetics.base.IRotate.SpeedLevel;
import com.simibubi.create.content.kinetics.base.RotationIndicatorParticleData;
import com.yansunsky.createcmpor.block.StressInputBlock;
import com.yansunsky.createcmpor.block.StressInputBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Reuses Create's short-lived arrows, without server packets or renderer-dependent timing. */
public final class StressInputParticles {
    private static final int INTERVAL = 20;
    private static final double RANGE_SQUARED = 16 * 16;

    private StressInputParticles() {
    }

    public static void tick(StressInputBlockEntity blockEntity) {
        Minecraft minecraft = Minecraft.getInstance();
        var level = blockEntity.getLevel();
        var player = minecraft.player;
        if (level == null || level != minecraft.level || player == null)
            return;
        BlockPos pos = blockEntity.getBlockPos();
        // Stagger nearby inputs and avoid creating invisible particles for players without goggles.
        if (Math.floorMod(level.getGameTime() + pos.hashCode(), INTERVAL) != 0
                || player.distanceToSqr(Vec3.atCenterOf(pos)) > RANGE_SQUARED
                || !GogglesItem.isWearingGoggles(player))
            return;
        float speed = blockEntity.getDirectionPreviewSpeed();
        if (speed == 0)
            return;

        var state = blockEntity.getBlockState();
        var block = (StressInputBlock) state.getBlock();
        SpeedLevel speedLevel = SpeedLevel.of(speed);
        var particle = new RotationIndicatorParticleData(speedLevel.getColor(),
                speedLevel.getParticleSpeed() * Math.signum(speed),
                block.getParticleInitialRadius(), block.getParticleTargetRadius(), 10,
                block.getRotationAxis(state));
        Vec3 center = Vec3.atCenterOf(pos);
        for (int i = 0; i < 20; i++)
            level.addParticle(particle, center.x, center.y, center.z, 0, 0, 0);
    }
}
